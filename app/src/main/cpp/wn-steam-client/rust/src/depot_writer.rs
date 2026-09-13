use crate::cdn_client::{CdnClient, CdnConnection};
use crate::content_manifest::{ChunkData, ContentManifest};
use crate::depot_chunk::process_depot_chunk;
use crate::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Component, Path};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

pub const DEPOT_FILE_FLAG_EXECUTABLE: u32 = 32;
pub const DEPOT_FILE_FLAG_DIRECTORY: u32 = 64;
pub const DEPOT_FILE_FLAG_SYMLINK: u32 = 512;
pub const MAX_CHUNK_ATTEMPTS: u32 = 5;
const RESIZE_BUFFER_BYTES: usize = 256 * 1024;
const ERRNO_EPERM: i32 = 1;
const ERRNO_EACCES: i32 = 13;
const ERRNO_ENOSYS: i32 = 38;
const ERRNO_EOPNOTSUPP: i32 = 95;
pub const SLOW_CHUNK_ROTATE_THRESHOLD_SECS: u64 = 8;
pub const SLOW_CHUNK_ROTATE_CONSECUTIVE_LIMIT: u32 = 3;

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotWriteResult {
    pub files_written: u64,
    pub bytes_written: u64,
    pub resume_trust_safe: bool,
    pub error: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DepotFileAction {
    Directory { path: String },
    Symlink { path: String, target: String },
    Regular { path: String, size: u64, mode: u32 },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ChunkWriteJob {
    pub file_idx: u32,
    pub chunk_idx: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotWritePlan {
    pub total_bytes: u64,
    pub files_written: u64,
    pub actions: Vec<DepotFileAction>,
    pub chunk_jobs: Vec<ChunkWriteJob>,
    pub worker_count: u32,
}

pub type DepotChunkProgressCallback<'a> = &'a (dyn Fn(u64, u64, bool) + Sync);

#[derive(Clone, Copy)]
pub struct DepotWriteOptions<'a> {
    pub cdn_auth_token: &'a str,
    pub timeout: Duration,
    pub max_workers: u32,
    pub cancel: Option<&'a AtomicBool>,
    pub on_progress: Option<DepotChunkProgressCallback<'a>>,
}

impl Default for DepotWriteOptions<'_> {
    fn default() -> Self {
        Self {
            cdn_auth_token: "",
            timeout: CdnClient::default_timeout(),
            max_workers: 8,
            cancel: None,
            on_progress: None,
        }
    }
}

impl DepotWriteResult {
    pub fn ok(&self) -> bool {
        self.error.is_empty()
    }

    pub fn success(files_written: u64, bytes_written: u64) -> Self {
        Self {
            files_written,
            bytes_written,
            resume_trust_safe: true,
            error: String::new(),
        }
    }

    pub fn fail(error: impl Into<String>, resume_trust_safe: bool) -> Self {
        Self {
            error: error.into(),
            resume_trust_safe,
            ..Default::default()
        }
    }
}

pub fn retry_backoff_millis(attempt: u32) -> u64 {
    if attempt == 0 {
        return 0;
    }
    (300u64 << (attempt - 1)).min(4000)
}

pub fn chunk_attempt_server_indices(
    start_server_index: usize,
    server_count: usize,
    attempts: u32,
) -> Vec<usize> {
    if server_count == 0 || attempts == 0 {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(attempts as usize);
    let mut server_index = start_server_index % server_count;
    for attempt in 0..attempts {
        if attempt > 0 && server_count > 1 {
            server_index = (server_index + 1) % server_count;
        }
        out.push(server_index);
    }
    out
}

pub fn should_rotate_after_slow_chunks(consecutive_slow_chunks: u32, server_count: usize) -> bool {
    server_count > 1 && consecutive_slow_chunks >= SLOW_CHUNK_ROTATE_CONSECUTIVE_LIMIT
}

pub fn depot_adler_hash(data: &[u8]) -> u32 {
    const BLOCK: usize = 5552;
    let mut a = 0u32;
    let mut b = 0u32;
    for chunk in data.chunks(BLOCK) {
        for byte in chunk {
            a += *byte as u32;
            b += a;
        }
        a %= 65521;
        b %= 65521;
    }
    a | (b << 16)
}

pub fn path_is_safe(rel: &str) -> bool {
    if rel.is_empty() || rel.starts_with('/') || rel.starts_with('\\') {
        return false;
    }
    let path = Path::new(rel);
    if path.is_absolute() {
        return false;
    }
    path.components().all(|component| {
        matches!(component, Component::Normal(_)) || matches!(component, Component::CurDir)
    }) && !path
        .components()
        .any(|component| matches!(component, Component::ParentDir | Component::Prefix(_)))
}

pub fn clamp_worker_count(max_workers: u32, outstanding_chunks: usize) -> u32 {
    if outstanding_chunks == 0 {
        return 0;
    }
    let requested = if max_workers == 0 { 1 } else { max_workers };
    requested.min(64).min(outstanding_chunks as u32)
}

pub fn plan_depot_write(
    manifest: &ContentManifest,
    depot_key: &[u8],
    server_count: usize,
    target_dir: &str,
    max_workers: u32,
) -> Result<DepotWritePlan, DepotWriteResult> {
    if manifest.metadata.filenames_encrypted {
        return Err(DepotWriteResult::fail(
            "write_depot: manifest filenames are still encrypted",
            false,
        ));
    }
    if depot_key.len() != 32 {
        return Err(DepotWriteResult::fail(
            "write_depot: bad depot key length",
            false,
        ));
    }
    if server_count == 0 {
        return Err(DepotWriteResult::fail("write_depot: no CDN servers", false));
    }

    let mut plan = DepotWritePlan {
        total_bytes: manifest.files.iter().map(|file| file.size).sum(),
        ..Default::default()
    };

    for (file_idx, file) in manifest.files.iter().enumerate() {
        if !path_is_safe(&file.filename) {
            return Err(DepotWriteResult::fail(
                format!("write_depot: unsafe path '{}'", file.filename),
                false,
            ));
        }
        let path = join_target_path(target_dir, &file.filename);
        if !file.linktarget.is_empty() {
            plan.actions.push(DepotFileAction::Symlink {
                path,
                target: file.linktarget.clone(),
            });
            plan.files_written += 1;
            continue;
        }
        if (file.flags & DEPOT_FILE_FLAG_DIRECTORY) != 0 {
            plan.actions.push(DepotFileAction::Directory { path });
            continue;
        }
        let mode = if (file.flags & DEPOT_FILE_FLAG_EXECUTABLE) != 0 {
            0o755
        } else {
            0o644
        };
        plan.actions.push(DepotFileAction::Regular {
            path,
            size: file.size,
            mode,
        });
        plan.files_written += 1;
        for chunk_idx in 0..file.chunks.len() {
            plan.chunk_jobs.push(ChunkWriteJob {
                file_idx: file_idx as u32,
                chunk_idx: chunk_idx as u32,
            });
        }
    }
    plan.worker_count = clamp_worker_count(max_workers, plan.chunk_jobs.len());
    Ok(plan)
}

pub fn write_depot_sequential(
    manifest: &ContentManifest,
    depot_key: &[u8],
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    target_dir: &str,
    options: DepotWriteOptions<'_>,
) -> DepotWriteResult {
    let plan = match plan_depot_write(
        manifest,
        depot_key,
        servers.len(),
        target_dir,
        options.max_workers,
    ) {
        Ok(plan) => plan,
        Err(error) => return error,
    };
    let layout = create_depot_layout(&plan);
    if !layout.ok() {
        return layout;
    }

    if plan.worker_count > 1 && plan.chunk_jobs.len() > 1 && servers.len() > 0 {
        return write_depot_parallel(
            manifest,
            depot_key,
            cdn,
            servers,
            target_dir,
            &plan,
            &options,
        );
    }

    let mut bytes_written = 0u64;
    let total_bytes = plan.total_bytes;
    let mut conn = cdn.open_connection();
    for (job_index, job) in plan.chunk_jobs.iter().enumerate() {
        if options
            .cancel
            .is_some_and(|cancel| cancel.load(Ordering::Relaxed))
        {
            return DepotWriteResult::fail("cancelled", true);
        }
        let file = match manifest.files.get(job.file_idx as usize) {
            Some(file) => file,
            None => return DepotWriteResult::fail("bad file index", true),
        };
        let chunk = match file.chunks.get(job.chunk_idx as usize) {
            Some(chunk) => chunk,
            None => return DepotWriteResult::fail("bad chunk index", true),
        };
        let path = join_target_path(target_dir, &file.filename);
        if existing_chunk_matches(&path, chunk) {
            bytes_written += chunk.cb_original as u64;
            if let Some(on_progress) = options.on_progress {
                on_progress(bytes_written, total_bytes, true);
            }
            continue;
        }
        match fetch_process_write_chunk(
            cdn,
            Some(&mut conn),
            servers,
            manifest,
            job.file_idx as usize,
            job.chunk_idx as usize,
            depot_key,
            target_dir,
            options.cdn_auth_token,
            job_index % servers.len(),
            options.timeout,
        ) {
            Ok(bytes) => {
                bytes_written += bytes;
                if let Some(on_progress) = options.on_progress {
                    on_progress(bytes_written, total_bytes, false);
                }
            }
            Err(error) => return DepotWriteResult::fail(error, true),
        }
    }

    for file in &manifest.files {
        if options
            .cancel
            .is_some_and(|cancel| cancel.load(Ordering::Relaxed))
        {
            return DepotWriteResult::fail("cancelled", true);
        }
        if !file.linktarget.is_empty() || (file.flags & DEPOT_FILE_FLAG_DIRECTORY) != 0 {
            continue;
        }
        let path = join_target_path(target_dir, &file.filename);
        if let Err(error) = finalize_regular_file(path, file.size) {
            return DepotWriteResult::fail(error, true);
        }
    }

    DepotWriteResult {
        files_written: plan.files_written,
        bytes_written,
        resume_trust_safe: true,
        error: String::new(),
    }
}

fn write_depot_parallel(
    manifest: &ContentManifest,
    depot_key: &[u8],
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    target_dir: &str,
    plan: &DepotWritePlan,
    options: &DepotWriteOptions<'_>,
) -> DepotWriteResult {
    let total_bytes = plan.total_bytes;
    let bytes_written = Arc::new(AtomicU64::new(0));
    let error_slot: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
    let next_index = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let jobs = Arc::new(plan.chunk_jobs.clone());
    let worker_count = (plan.worker_count as usize).max(1).min(jobs.len());
    let cdn_auth_token = options.cdn_auth_token.to_string();
    let timeout = options.timeout;
    let cancel_flag = options.cancel.map(|c| {
        let raw = c as *const AtomicBool;
        unsafe { raw.as_ref() }.unwrap()
    });
    // SAFETY: we only spawn scoped threads so all `'a` references outlive joins.
    let scope_result = thread::scope(|scope| -> DepotWriteResult {
        let mut handles = Vec::with_capacity(worker_count);
        for worker_id in 0..worker_count {
            let bytes_written = Arc::clone(&bytes_written);
            let error_slot = Arc::clone(&error_slot);
            let next_index = Arc::clone(&next_index);
            let jobs = Arc::clone(&jobs);
            let cdn_auth_token = cdn_auth_token.clone();
            let manifest_ref = manifest;
            let depot_key_ref = depot_key;
            let target_dir_ref = target_dir;
            let cdn_ref = cdn;
            let servers_ref = servers;
            let progress = options.on_progress;
            handles.push(scope.spawn(move || {
                let mut conn = cdn_ref.open_connection();
                let mut slow_chunks = 0u32;
                let mut worker_server_bias = worker_id % servers_ref.len();
                loop {
                    if cancel_flag.is_some_and(|c| c.load(Ordering::Relaxed)) {
                        return;
                    }
                    if error_slot.lock().expect("err slot poisoned").is_some() {
                        return;
                    }
                    let idx = next_index.fetch_add(1, Ordering::Relaxed);
                    if idx >= jobs.len() {
                        return;
                    }
                    let job = jobs[idx];
                    let file = match manifest_ref.files.get(job.file_idx as usize) {
                        Some(file) => file,
                        None => {
                            *error_slot.lock().expect("err slot poisoned") =
                                Some("bad file index".to_string());
                            return;
                        }
                    };
                    let chunk = match file.chunks.get(job.chunk_idx as usize) {
                        Some(chunk) => chunk,
                        None => {
                            *error_slot.lock().expect("err slot poisoned") =
                                Some("bad chunk index".to_string());
                            return;
                        }
                    };
                    let path = join_target_path(target_dir_ref, &file.filename);
                    if existing_chunk_matches(&path, chunk) {
                        let total =
                            bytes_written.fetch_add(chunk.cb_original as u64, Ordering::Relaxed)
                                + chunk.cb_original as u64;
                        if let Some(cb) = progress {
                            cb(total, total_bytes, true);
                        }
                        continue;
                    }
                    if should_rotate_after_slow_chunks(slow_chunks, servers_ref.len()) {
                        worker_server_bias = (worker_server_bias + 1) % servers_ref.len();
                        conn = cdn_ref.open_connection();
                        slow_chunks = 0;
                    }
                    let start_server = (idx + worker_server_bias) % servers_ref.len();
                    let started = Instant::now();
                    match fetch_process_write_chunk(
                        cdn_ref,
                        Some(&mut conn),
                        servers_ref,
                        manifest_ref,
                        job.file_idx as usize,
                        job.chunk_idx as usize,
                        depot_key_ref,
                        target_dir_ref,
                        &cdn_auth_token,
                        start_server,
                        timeout,
                    ) {
                        Ok(bytes) => {
                            if started.elapsed()
                                > Duration::from_secs(SLOW_CHUNK_ROTATE_THRESHOLD_SECS)
                                && servers_ref.len() > 1
                            {
                                slow_chunks += 1;
                            } else {
                                slow_chunks = 0;
                            }
                            let total = bytes_written.fetch_add(bytes, Ordering::Relaxed) + bytes;
                            if let Some(cb) = progress {
                                cb(total, total_bytes, false);
                            }
                        }
                        Err(error) => {
                            *error_slot.lock().expect("err slot poisoned") = Some(error);
                            return;
                        }
                    }
                }
            }));
        }
        for handle in handles {
            let _ = handle.join();
        }
        if cancel_flag.is_some_and(|c| c.load(Ordering::Relaxed)) {
            return DepotWriteResult::fail("cancelled", true);
        }
        if let Some(error) = error_slot.lock().expect("err slot poisoned").take() {
            return DepotWriteResult::fail(error, true);
        }
        DepotWriteResult {
            files_written: plan.files_written,
            bytes_written: bytes_written.load(Ordering::Relaxed),
            resume_trust_safe: true,
            error: String::new(),
        }
    });
    if !scope_result.ok() {
        return scope_result;
    }

    for file in &manifest.files {
        if options
            .cancel
            .is_some_and(|cancel| cancel.load(Ordering::Relaxed))
        {
            return DepotWriteResult::fail("cancelled", true);
        }
        if !file.linktarget.is_empty() || (file.flags & DEPOT_FILE_FLAG_DIRECTORY) != 0 {
            continue;
        }
        let path = join_target_path(target_dir, &file.filename);
        if let Err(error) = finalize_regular_file(path, file.size) {
            return DepotWriteResult::fail(error, true);
        }
    }
    scope_result
}

pub fn create_depot_layout(plan: &DepotWritePlan) -> DepotWriteResult {
    for action in &plan.actions {
        let result = match action {
            DepotFileAction::Directory { path } => create_directory(path),
            DepotFileAction::Symlink { path, target } => create_symlink(path, target),
            DepotFileAction::Regular { path, mode, .. } => create_regular_file(path, *mode),
        };
        if let Err(error) = result {
            return DepotWriteResult::fail(error, false);
        }
    }
    DepotWriteResult {
        files_written: plan.files_written,
        bytes_written: 0,
        resume_trust_safe: true,
        error: String::new(),
    }
}

pub fn write_chunk_at(path: impl AsRef<Path>, offset: u64, data: &[u8]) -> Result<u64, String> {
    let path = path.as_ref();
    make_parent_dirs(path)?;
    let mut file = OpenOptions::new()
        .create(true)
        .write(true)
        .read(true)
        .truncate(false)
        .open(path)
        .map_err(|err| format!("write_depot: open '{}': {err}", path.display()))?;
    file.seek(SeekFrom::Start(offset))
        .map_err(|err| format!("write_depot: seek '{}': {err}", path.display()))?;
    file.write_all(data)
        .map_err(|err| format!("write_depot: write '{}': {err}", path.display()))?;
    Ok(data.len() as u64)
}

pub fn process_and_write_chunk(
    path: impl AsRef<Path>,
    chunk: &ChunkData,
    raw_chunk: &[u8],
    depot_key: &[u8],
) -> Result<u64, String> {
    let processed = process_depot_chunk(raw_chunk, depot_key, chunk.crc, chunk.cb_original);
    if !processed.ok() {
        return Err(format!("decode: {}", processed.error));
    }
    write_chunk_at(path, chunk.offset, &processed.data)
}

#[allow(clippy::too_many_arguments)]
pub fn fetch_process_write_chunk(
    cdn: &CdnClient,
    mut conn: Option<&mut CdnConnection>,
    servers: &[CContentServerDirectoryServerInfo],
    manifest: &ContentManifest,
    file_idx: usize,
    chunk_idx: usize,
    depot_key: &[u8],
    target_dir: &str,
    cdn_auth_token: &str,
    start_server_index: usize,
    timeout: Duration,
) -> Result<u64, String> {
    if servers.is_empty() {
        return Err("write_depot: no CDN servers".to_string());
    }
    let file = manifest
        .files
        .get(file_idx)
        .ok_or_else(|| "write_depot: bad file index".to_string())?;
    let chunk = file
        .chunks
        .get(chunk_idx)
        .ok_or_else(|| "write_depot: bad chunk index".to_string())?;
    let path = join_target_path(target_dir, &file.filename);
    let mut last_error = String::new();
    for (attempt, server_idx) in
        chunk_attempt_server_indices(start_server_index, servers.len(), MAX_CHUNK_ATTEMPTS)
            .into_iter()
            .enumerate()
    {
        if attempt > 0 {
            thread::sleep(Duration::from_millis(retry_backoff_millis(attempt as u32)));
            if let Some(connection) = conn.as_deref_mut() {
                *connection = cdn.open_connection();
            }
        }
        let fetched = match conn.as_deref_mut() {
            Some(connection) => cdn.fetch_chunk_with_connection(
                connection,
                &servers[server_idx],
                manifest.metadata.depot_id,
                &chunk.sha,
                cdn_auth_token,
                timeout,
            ),
            None => cdn.fetch_chunk(
                &servers[server_idx],
                manifest.metadata.depot_id,
                &chunk.sha,
                cdn_auth_token,
                timeout,
            ),
        };
        if !fetched.ok() {
            last_error = fetched.error;
            continue;
        }
        match process_and_write_chunk(&path, chunk, &fetched.data, depot_key) {
            Ok(bytes) => return Ok(bytes),
            Err(error) => last_error = error,
        }
    }
    Err(format!(
        "write_depot: chunk for '{}' failed after {} attempts: {}",
        file.filename, MAX_CHUNK_ATTEMPTS, last_error
    ))
}

pub fn existing_chunk_matches(path: impl AsRef<Path>, chunk: &ChunkData) -> bool {
    let path = path.as_ref();
    let Ok(mut file) = File::open(path) else {
        return false;
    };
    let Ok(metadata) = file.metadata() else {
        return false;
    };
    let end = chunk.offset.saturating_add(chunk.cb_original as u64);
    if chunk.cb_original == 0 || metadata.len() < end {
        return false;
    }
    let mut buf = vec![0u8; chunk.cb_original as usize];
    if file.seek(SeekFrom::Start(chunk.offset)).is_err() || file.read_exact(&mut buf).is_err() {
        return false;
    }
    depot_adler_hash(&buf) == chunk.crc
}

pub fn sync_file(path: impl AsRef<Path>) -> bool {
    OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .and_then(|file| file.sync_all())
        .is_ok()
}

pub fn finalize_regular_file(path: impl AsRef<Path>, size: u64) -> Result<(), String> {
    let path = path.as_ref();
    let current = fs::metadata(path)
        .map_err(|err| format!("write_depot: final stat '{}': {err}", path.display()))?
        .len();
    if current != size {
        resize_regular_file(path, current, size)?;
    }
    OpenOptions::new()
        .write(true)
        .open(path)
        .and_then(|file| file.sync_all())
        .map_err(|err| format!("write_depot: final sync '{}': {err}", path.display()))
}

fn resize_regular_file(path: &Path, current: u64, size: u64) -> Result<(), String> {
    let file = OpenOptions::new()
        .write(true)
        .open(path)
        .map_err(|err| format!("write_depot: final open '{}': {err}", path.display()))?;
    match file.set_len(size) {
        Ok(()) => Ok(()),
        Err(err) if metadata_op_unsupported(&err) => {
            drop(file);
            if size > current {
                extend_with_zeros(path, current, size)
            } else {
                shrink_by_rewrite(path, size)
            }
        }
        Err(err) => Err(format!(
            "write_depot: final truncate '{}': {err}",
            path.display()
        )),
    }
}

fn extend_with_zeros(path: &Path, current: u64, size: u64) -> Result<(), String> {
    let mut file = OpenOptions::new()
        .write(true)
        .open(path)
        .map_err(|err| format!("write_depot: extend open '{}': {err}", path.display()))?;
    file.seek(SeekFrom::Start(current))
        .map_err(|err| format!("write_depot: extend seek '{}': {err}", path.display()))?;
    let mut remaining = size - current;
    let zeros = vec![0u8; RESIZE_BUFFER_BYTES.min(remaining as usize)];
    while remaining > 0 {
        let step = (zeros.len() as u64).min(remaining) as usize;
        file.write_all(&zeros[..step])
            .map_err(|err| format!("write_depot: extend write '{}': {err}", path.display()))?;
        remaining -= step as u64;
    }
    Ok(())
}

fn shrink_by_rewrite(path: &Path, size: u64) -> Result<(), String> {
    let temp = shrink_temp_path(path);
    let result = copy_prefix(path, &temp, size);
    if result.is_err() {
        let _ = fs::remove_file(&temp);
        return result;
    }
    fs::rename(&temp, path).map_err(|err| {
        let _ = fs::remove_file(&temp);
        format!("write_depot: shrink rename '{}': {err}", path.display())
    })
}

fn copy_prefix(path: &Path, temp: &Path, size: u64) -> Result<(), String> {
    let mut source = File::open(path)
        .map_err(|err| format!("write_depot: shrink open '{}': {err}", path.display()))?;
    let mut target = File::create(temp)
        .map_err(|err| format!("write_depot: shrink create '{}': {err}", temp.display()))?;
    let mut remaining = size;
    let mut buffer = vec![0u8; RESIZE_BUFFER_BYTES];
    while remaining > 0 {
        let step = (buffer.len() as u64).min(remaining) as usize;
        source
            .read_exact(&mut buffer[..step])
            .map_err(|err| format!("write_depot: shrink read '{}': {err}", path.display()))?;
        target
            .write_all(&buffer[..step])
            .map_err(|err| format!("write_depot: shrink write '{}': {err}", temp.display()))?;
        remaining -= step as u64;
    }
    target
        .sync_all()
        .map_err(|err| format!("write_depot: shrink sync '{}': {err}", temp.display()))
}

fn shrink_temp_path(path: &Path) -> std::path::PathBuf {
    let mut name = path.file_name().unwrap_or_default().to_os_string();
    name.push(".wnresize");
    path.with_file_name(name)
}

fn metadata_op_unsupported(err: &std::io::Error) -> bool {
    matches!(
        err.raw_os_error(),
        Some(ERRNO_EPERM) | Some(ERRNO_EACCES) | Some(ERRNO_ENOSYS) | Some(ERRNO_EOPNOTSUPP)
    )
}

fn join_target_path(target_dir: &str, rel: &str) -> String {
    if target_dir.ends_with('/') || target_dir.ends_with('\\') {
        format!("{target_dir}{rel}")
    } else {
        format!("{target_dir}/{rel}")
    }
}

fn create_directory(path: &str) -> Result<(), String> {
    fs::create_dir_all(path).map_err(|err| format!("write_depot: mkdir '{path}': {err}"))
}

fn create_regular_file(path: &str, mode: u32) -> Result<(), String> {
    let path_ref = Path::new(path);
    make_parent_dirs(path_ref)?;
    OpenOptions::new()
        .create(true)
        .write(true)
        .read(true)
        .truncate(false)
        .open(path_ref)
        .map_err(|err| format!("write_depot: open '{path}': {err}"))?;
    set_file_mode(path_ref, mode)
}

fn create_symlink(path: &str, target: &str) -> Result<(), String> {
    let path_ref = Path::new(path);
    make_parent_dirs(path_ref)?;
    if path_ref.exists() {
        fs::remove_file(path_ref).map_err(|err| format!("write_depot: unlink '{path}': {err}"))?;
    }
    create_platform_symlink(target, path_ref)
        .map_err(|err| format!("write_depot: symlink '{path}': {err}"))
}

fn make_parent_dirs(path: &Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)
                .map_err(|err| format!("write_depot: mkdir '{}': {err}", parent.display()))?;
        }
    }
    Ok(())
}

#[cfg(unix)]
fn create_platform_symlink(target: &str, path: &Path) -> std::io::Result<()> {
    std::os::unix::fs::symlink(target, path)
}

#[cfg(windows)]
fn create_platform_symlink(target: &str, path: &Path) -> std::io::Result<()> {
    std::os::windows::fs::symlink_file(target, path)
}

#[cfg(unix)]
fn set_file_mode(path: &Path, mode: u32) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    let permissions = fs::Permissions::from_mode(mode);
    match fs::set_permissions(path, permissions) {
        Ok(()) => Ok(()),
        Err(err) if metadata_op_unsupported(&err) => Ok(()),
        Err(err) => Err(format!("write_depot: chmod '{}': {err}", path.display())),
    }
}

#[cfg(not(unix))]
fn set_file_mode(_path: &Path, _mode: u32) -> Result<(), String> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::{aes256_cbc_encrypt, aes256_ecb_encrypt_block, AES_BLOCK_BYTES};
    use std::path::PathBuf;

    #[test]
    fn finalize_leaves_matching_file_untouched() {
        let dir = temp_dir("depot_writer_finalize_match");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("exact.bin");
        fs::write(&path, b"abcdef").unwrap();
        finalize_regular_file(&path, 6).unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"abcdef");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn extend_with_zeros_grows_file_without_truncate() {
        let dir = temp_dir("depot_writer_extend");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("grow.bin");
        fs::write(&path, b"abc").unwrap();
        extend_with_zeros(&path, 3, 6).unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"abc\0\0\0");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn shrink_by_rewrite_keeps_prefix_and_removes_temp() {
        let dir = temp_dir("depot_writer_shrink");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("shrink.bin");
        fs::write(&path, b"abcdef").unwrap();
        shrink_by_rewrite(&path, 3).unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"abc");
        assert!(!shrink_temp_path(&path).exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn unsupported_metadata_errors_are_classified() {
        use std::io::{Error, ErrorKind};
        assert!(metadata_op_unsupported(&Error::from_raw_os_error(
            ERRNO_EPERM
        )));
        assert!(metadata_op_unsupported(&Error::from_raw_os_error(
            ERRNO_EOPNOTSUPP
        )));
        assert!(!metadata_op_unsupported(&Error::from_raw_os_error(28)));
        assert!(!metadata_op_unsupported(&Error::new(ErrorKind::Other, "x")));
    }

    #[test]
    fn finalize_survives_filesystem_that_rejects_truncate() {
        let dir = temp_dir("depot_writer_no_truncate");
        fs::create_dir_all(&dir).unwrap();

        let same = dir.join("same.bin");
        fs::write(&same, b"abcdef").unwrap();
        finalize_regular_file(&same, 6).unwrap();
        assert_eq!(fs::read(&same).unwrap(), b"abcdef");

        let grow = dir.join("grow.bin");
        fs::write(&grow, b"abc").unwrap();
        finalize_regular_file(&grow, 6).unwrap();
        assert_eq!(fs::read(&grow).unwrap(), b"abc\0\0\0");

        let shrink = dir.join("shrink.bin");
        fs::write(&shrink, b"abcdef").unwrap();
        finalize_regular_file(&shrink, 2).unwrap();
        assert_eq!(fs::read(&shrink).unwrap(), b"ab");

        let empty = dir.join("empty.bin");
        fs::write(&empty, b"").unwrap();
        finalize_regular_file(&empty, 0).unwrap();
        assert_eq!(fs::metadata(&empty).unwrap().len(), 0);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn depot_adler_uses_steam_zero_seed() {
        assert_eq!(depot_adler_hash(b""), 0);
        assert_eq!(depot_adler_hash(b"abc"), 0x024a_0126);
    }

    #[test]
    fn rejects_paths_that_escape_target() {
        assert!(path_is_safe("a/b/file.txt"));
        assert!(path_is_safe("./a/file.txt"));
        assert!(!path_is_safe(""));
        assert!(!path_is_safe("../file.txt"));
        assert!(!path_is_safe("a/../file.txt"));
        assert!(!path_is_safe("/abs/file.txt"));
    }

    #[test]
    fn retry_backoff_matches_cpp_caps() {
        assert_eq!(retry_backoff_millis(1), 300);
        assert_eq!(retry_backoff_millis(2), 600);
        assert_eq!(retry_backoff_millis(5), 4000);
        assert_eq!(MAX_CHUNK_ATTEMPTS, 5);
        assert_eq!(SLOW_CHUNK_ROTATE_THRESHOLD_SECS, 8);
    }

    #[test]
    fn chunk_retry_rotates_across_servers_like_cpp_worker() {
        assert_eq!(chunk_attempt_server_indices(0, 3, 5), [0, 1, 2, 0, 1]);
        assert_eq!(chunk_attempt_server_indices(2, 3, 5), [2, 0, 1, 2, 0]);
        assert_eq!(chunk_attempt_server_indices(0, 1, 5), [0, 0, 0, 0, 0]);
        assert!(chunk_attempt_server_indices(0, 0, 5).is_empty());
        assert!(!should_rotate_after_slow_chunks(2, 3));
        assert!(should_rotate_after_slow_chunks(3, 3));
        assert!(!should_rotate_after_slow_chunks(3, 1));
    }

    #[test]
    fn depot_plan_validates_inputs_and_enumerates_actions() {
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                ..Default::default()
            },
            files: vec![
                crate::content_manifest::FileMapping {
                    filename: "bin".into(),
                    flags: DEPOT_FILE_FLAG_DIRECTORY,
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "bin/game".into(),
                    size: 10,
                    flags: DEPOT_FILE_FLAG_EXECUTABLE,
                    chunks: vec![crate::content_manifest::ChunkData::default()],
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "link".into(),
                    linktarget: "bin/game".into(),
                    ..Default::default()
                },
            ],
            signature: Vec::new(),
        };

        let plan = plan_depot_write(&manifest, &[7u8; 32], 2, "/target", 99).unwrap();
        assert_eq!(plan.total_bytes, 10);
        assert_eq!(plan.files_written, 2);
        assert_eq!(plan.worker_count, 1);
        assert_eq!(
            plan.actions,
            [
                DepotFileAction::Directory {
                    path: "/target/bin".into()
                },
                DepotFileAction::Regular {
                    path: "/target/bin/game".into(),
                    size: 10,
                    mode: 0o755
                },
                DepotFileAction::Symlink {
                    path: "/target/link".into(),
                    target: "bin/game".into()
                }
            ]
        );
        assert_eq!(
            plan.chunk_jobs,
            [ChunkWriteJob {
                file_idx: 1,
                chunk_idx: 0
            }]
        );
    }

    #[test]
    fn depot_plan_rejects_cpp_error_cases() {
        let mut manifest = ContentManifest {
            files: vec![crate::content_manifest::FileMapping {
                filename: "../escape".into(),
                ..Default::default()
            }],
            ..Default::default()
        };
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 32], 1, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: unsafe path '../escape'"
        );
        manifest.files[0].filename = "ok".into();
        manifest.metadata.filenames_encrypted = true;
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 32], 1, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: manifest filenames are still encrypted"
        );
        manifest.metadata.filenames_encrypted = false;
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 31], 1, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: bad depot key length"
        );
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 32], 0, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: no CDN servers"
        );
    }

    #[test]
    fn worker_clamping_matches_cpp_limits() {
        assert_eq!(clamp_worker_count(0, 10), 1);
        assert_eq!(clamp_worker_count(128, 100), 64);
        assert_eq!(clamp_worker_count(8, 3), 3);
        assert_eq!(clamp_worker_count(8, 0), 0);
    }

    #[test]
    fn creates_layout_and_writes_chunks_at_offsets() {
        let dir = temp_dir("depot_writer_layout");
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                ..Default::default()
            },
            files: vec![
                crate::content_manifest::FileMapping {
                    filename: "bin".into(),
                    flags: DEPOT_FILE_FLAG_DIRECTORY,
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "bin/game.dat".into(),
                    size: 6,
                    chunks: vec![ChunkData {
                        offset: 2,
                        cb_original: 3,
                        crc: depot_adler_hash(b"abc"),
                        ..Default::default()
                    }],
                    ..Default::default()
                },
            ],
            signature: Vec::new(),
        };
        let plan = plan_depot_write(&manifest, &[1u8; 32], 1, dir.to_str().unwrap(), 4).unwrap();
        let result = create_depot_layout(&plan);
        assert!(result.ok(), "{}", result.error);
        let file = dir.join("bin/game.dat");
        assert!(file.exists());

        assert_eq!(write_chunk_at(&file, 2, b"abc").unwrap(), 3);
        assert!(existing_chunk_matches(&file, &manifest.files[1].chunks[0]));
        assert!(!existing_chunk_matches(
            &file,
            &ChunkData {
                offset: 2,
                cb_original: 3,
                crc: 1,
                ..Default::default()
            }
        ));
        assert!(sync_file(&file));
        finalize_regular_file(&file, 6).unwrap();
        assert_eq!(fs::metadata(&file).unwrap().len(), 6);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn process_and_write_chunk_decrypts_and_materializes_bytes() {
        let dir = temp_dir("depot_writer_process_chunk");
        let file = dir.join("content.bin");
        let key = [9u8; 32];
        let payload = b"materialized chunk";
        let raw = encrypted_stored_zip_chunk(&key, payload);
        let chunk = ChunkData {
            offset: 4,
            cb_original: payload.len() as u32,
            crc: depot_adler_hash(payload),
            ..Default::default()
        };

        assert_eq!(
            process_and_write_chunk(&file, &chunk, &raw, &key).unwrap(),
            payload.len() as u64
        );
        assert!(existing_chunk_matches(&file, &chunk));
        let mut bytes = Vec::new();
        File::open(&file).unwrap().read_to_end(&mut bytes).unwrap();
        assert_eq!(&bytes[4..], payload);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn sequential_write_handles_layout_only_manifest() {
        let dir = temp_dir("depot_writer_sequential_layout");
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                depot_id: 7,
                ..Default::default()
            },
            files: vec![
                crate::content_manifest::FileMapping {
                    filename: "empty.bin".into(),
                    size: 5,
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "folder".into(),
                    flags: DEPOT_FILE_FLAG_DIRECTORY,
                    ..Default::default()
                },
            ],
            signature: Vec::new(),
        };
        let server = CContentServerDirectoryServerInfo {
            host: "cdn.example".into(),
            https_support: "mandatory".into(),
            ..Default::default()
        };
        let result = write_depot_sequential(
            &manifest,
            &[3u8; 32],
            &CdnClient::new(""),
            &[server],
            dir.to_str().unwrap(),
            DepotWriteOptions::default(),
        );
        assert!(result.ok(), "{}", result.error);
        assert_eq!(result.files_written, 1);
        assert_eq!(result.bytes_written, 0);
        assert_eq!(fs::metadata(dir.join("empty.bin")).unwrap().len(), 5);
        assert!(dir.join("folder").is_dir());
        let _ = fs::remove_dir_all(&dir);
    }

    fn temp_dir(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "wnsteam_{name}_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ))
    }

    fn encrypted_stored_zip_chunk(key: &[u8; 32], payload: &[u8]) -> Vec<u8> {
        let mut zip = Vec::new();
        zip.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u32.to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&1u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(b"x");
        zip.extend_from_slice(payload);

        let iv = [6u8; AES_BLOCK_BYTES];
        let wrapped = aes256_ecb_encrypt_block(key, &iv).unwrap();
        let body = aes256_cbc_encrypt(key, &iv, &zip).unwrap();
        let mut out = wrapped.to_vec();
        out.extend_from_slice(&body);
        out
    }
}
