use crate::cdn_client::{CdnClient, CdnManifestResult};
use crate::content_manifest::ContentManifest;
use crate::depot_config::{DepotConfigStore, DepotProgressStore, INVALID_MANIFEST_ID};
use crate::depot_writer::{write_depot_sequential, DepotWriteOptions};
use crate::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

/// Sentinel worker count that switches the writer into adaptive mode: hosts
/// are scored by measured throughput (Valve-client / GameHub style) instead of
/// being round-robined. Mirrors the "Adaptive" entry in the app's download
/// speed setting.
pub const ADAPTIVE_WORKERS_SENTINEL: u32 = 100;

/// Worker count used when adaptive mode is selected.
pub const ADAPTIVE_DEFAULT_WORKERS: u32 = 64;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct DepotSpec {
    pub depot_id: u32,
    pub manifest_id: u64,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ResolvedDepotSpec {
    pub depot_id: u32,
    pub manifest_id: u64,
    pub depot_key: Vec<u8>,
    pub manifest_request_code: u64,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct DepotDownloadProgress {
    pub depot_id: u32,
    pub depot_done: u64,
    pub depot_total: u64,
    pub depots_done: u32,
    pub depots_total: u32,
    pub verifying: bool,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotDownloadResult {
    pub success: bool,
    pub error: String,
    pub bytes_written: u64,
    pub depots_completed: u32,
    pub depots_skipped: u32,
}

impl DepotDownloadResult {
    pub fn fail(error: impl Into<String>) -> Self {
        Self {
            success: false,
            error: error.into(),
            ..Default::default()
        }
    }

    pub fn ok(bytes_written: u64, depots_completed: u32, depots_skipped: u32) -> Self {
        Self {
            success: true,
            bytes_written,
            depots_completed,
            depots_skipped,
            error: String::new(),
        }
    }
}

pub fn clean_pause_marker_name(depot_id: u32, manifest_id: u64) -> String {
    format!("{depot_id}_{manifest_id}.cleanpause")
}

pub fn clean_pause_marker_path(
    config_dir: impl AsRef<Path>,
    depot_id: u32,
    manifest_id: u64,
) -> PathBuf {
    config_dir
        .as_ref()
        .join(clean_pause_marker_name(depot_id, manifest_id))
}

pub fn has_clean_pause_marker(
    config_dir: impl AsRef<Path>,
    depot_id: u32,
    manifest_id: u64,
) -> bool {
    clean_pause_marker_path(config_dir, depot_id, manifest_id).is_file()
}

pub fn write_clean_pause_marker(
    config_dir: impl AsRef<Path>,
    depot_id: u32,
    manifest_id: u64,
) -> bool {
    let path = clean_pause_marker_path(config_dir, depot_id, manifest_id);
    let Some(parent) = path.parent() else {
        return false;
    };
    if fs::create_dir_all(parent).is_err() {
        return false;
    }
    fs::write(path, manifest_id.to_string()).is_ok()
}

pub fn remove_clean_pause_marker(config_dir: impl AsRef<Path>, depot_id: u32, manifest_id: u64) {
    let _ = fs::remove_file(clean_pause_marker_path(config_dir, depot_id, manifest_id));
}

pub fn validate_download_inputs(
    install_dir: &str,
    depots: &[DepotSpec],
) -> Result<(), DepotDownloadResult> {
    if install_dir.is_empty() {
        return Err(DepotDownloadResult::fail("download: empty install dir"));
    }
    if depots.is_empty() {
        return Err(DepotDownloadResult::fail("download: no depots"));
    }
    Ok(())
}

pub fn validate_resolved_download_inputs(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
) -> Result<(), DepotDownloadResult> {
    if install_dir.is_empty() {
        return Err(DepotDownloadResult::fail("download: empty install dir"));
    }
    if depots.is_empty() {
        return Err(DepotDownloadResult::fail("download: no depots"));
    }
    if servers.is_empty() {
        return Err(DepotDownloadResult::fail(
            "download: no CDN servers available",
        ));
    }
    Ok(())
}

/// Keeps every CDN server that has a reachable host (or vhost) while ordering
/// the list so that globally-reachable servers are tried first and China-only
/// servers act as a last-resort fallback.
///
/// Previously `steam_china_only` servers were discarded outright. On networks
/// where the international `*.steamcontent.com` CDN is unreachable or
/// unstable (e.g. mainland China), that left the downloader with no usable
/// server at all, so manifest/chunk fetches failed before a single byte could
/// be downloaded. Keeping them — but deprioritising them — preserves the fast
/// path for the rest of the world while giving those networks a working path.
pub fn filter_usable_cdn_servers(
    servers: impl IntoIterator<Item = CContentServerDirectoryServerInfo>,
) -> Vec<CContentServerDirectoryServerInfo> {
    let mut global = Vec::new();
    let mut china = Vec::new();
    for server in servers {
        // A server is usable if either `host` or `vhost` is present; the CDN
        // client prefers `vhost` for the URL/SNI and falls back to `host`.
        let has_host = !server.host.is_empty() || !server.vhost.is_empty();
        if !has_host {
            continue;
        }
        if server.steam_china_only {
            china.push(server);
        } else {
            global.push(server);
        }
    }
    global.extend(china);
    // The CM picks CDN nodes by the device's network location. On a device
    // inside mainland China it hands out only the Perfect World partner CDNs
    // (dl.steam.clngaa.com / st.dl.eccdnx.com / xz.pphimalayanrt.com), which
    // reject manifests for non-PerfectWorld (international) accounts with
    // HTTP 403 — leaving the downloader with zero working nodes. Append the
    // global Valve CDN pool as a last-resort fallback so international
    // accounts on Chinese networks can still fetch manifests and chunks.
    global.extend(global_cdn_fallback_servers());
    global
}

/// Global Valve CDN hosts that serve any depot regardless of account region.
///
/// These are Steam's Asia-Pacific edge nodes (`dist*-hkg1` / `dist*-sgp1`).
/// Unlike the `cache*.steamcontent.com` pool — which is behind DNS pollution
/// and TLS SNI blocking on mainland-Chinese networks — the Hong Kong and
/// Singapore edges are served from Akamai addresses that are directly
/// reachable from China while still honouring international Steam accounts
/// (a 401 "needs a valid manifest request code" instead of the 403 that the
/// Perfect World partner CDNs return for international accounts).
fn global_cdn_fallback_servers() -> Vec<CContentServerDirectoryServerInfo> {
    const GLOBAL_CDN_HOSTS: &[&str] = &[
        "dist1-hkg1.steamcontent.com",
        "dist2-hkg1.steamcontent.com",
        "dist3-hkg1.steamcontent.com",
        "dist1-sgp1.steamcontent.com",
        "dist2-sgp1.steamcontent.com",
        "dist3-sgp1.steamcontent.com",
        // Last-resort global edges in case the Asia-Pacific pool is unreachable.
        "cache1.steamcontent.com",
        "cache2.steamcontent.com",
    ];
    GLOBAL_CDN_HOSTS
        .iter()
        .map(|host| CContentServerDirectoryServerInfo {
            server_type: "CDN".to_string(),
            host: (*host).to_string(),
            vhost: (*host).to_string(),
            https_support: "mandatory".to_string(),
            ..Default::default()
        })
        .collect()
}

pub fn manifest_retry_server_indices(server_count: usize) -> Vec<usize> {
    if server_count == 0 {
        return Vec::new();
    }
    // Try each server in order exactly once. When the list mixes global and
    // China-only fallback servers, a fixed round-robin window (e.g. 5 attempts)
    // could exhaust itself on the global servers and never reach the fallback
    // that actually works on a restricted network.
    (0..server_count).collect()
}

pub fn retry_backoff_millis(attempt: u32) -> u64 {
    if attempt == 0 {
        0
    } else {
        (300u64 << (attempt - 1)).min(4000)
    }
}

/// Returns the CDN auth token for a specific depot + host. Region-restricted
/// CDN pools require a per-host token to serve manifests/chunks.
pub type CdnAuthTokenRefresher<'a> = &'a (dyn Fn(u32, &str) -> Option<String> + Sync);

pub fn fetch_manifest_with_retry(
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    depot_id: u32,
    manifest_id: u64,
    request_code: u64,
    cdn_auth_token: &str,
    timeout: Duration,
) -> CdnManifestResult {
    fetch_manifest_with_retry_and_token(
        cdn,
        servers,
        depot_id,
        manifest_id,
        request_code,
        cdn_auth_token,
        None,
        timeout,
    )
}

pub fn fetch_manifest_with_retry_and_token(
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    depot_id: u32,
    manifest_id: u64,
    request_code: u64,
    fallback_token: &str,
    token_refresher: Option<CdnAuthTokenRefresher<'_>>,
    timeout: Duration,
) -> CdnManifestResult {
    if servers.is_empty() {
        return CdnManifestResult {
            error: "download: no CDN servers available".to_string(),
            ..Default::default()
        };
    }
    // Sweep every server once (global first, then China-only fallback) so a
    // restricted network can still reach a working CDN node. A fixed retry
    // window would otherwise burn itself on the first few global servers and
    // never touch the fallback list.
    let mut last = CdnManifestResult::default();
    let mut seen_errors: Vec<String> = Vec::new();
    for (attempt, server_idx) in
        manifest_retry_server_indices(servers.len())
            .into_iter()
            .enumerate()
    {
        if attempt > 0 {
            thread::sleep(Duration::from_millis(retry_backoff_millis(attempt as u32)));
        }
        let server = &servers[server_idx];
        // A region-restricted host rejects tokenless requests with 403; fetch a
        // per-host token on demand when the caller provided a provider.
        let token = token_refresher
            .and_then(|refresh| refresh(depot_id, preferred_cdn_host(server)))
            .unwrap_or_else(|| fallback_token.to_string());
        last = cdn.fetch_manifest(
            server,
            depot_id,
            manifest_id,
            request_code,
            &token,
            timeout,
        );
        if last.ok() {
            return last;
        }
        // Record one line per distinct failure so a remote-diagnosis log shows
        // which CDN hosts were tried and how each rejected the request.
        let summary = format!(
            "manifest depot {depot_id} via {}:{} -> {}",
            preferred_cdn_host(server),
            last.http_status,
            last.error
        );
        if !seen_errors.contains(&summary) {
            seen_errors.push(summary.clone());
            log_line(&summary);
        }
    }
    if !seen_errors.is_empty() {
        log_line(&format!(
            "manifest depot {depot_id} failed on all {} servers; last: {}",
            servers.len(),
            last.error
        ));
    }
    last
}

fn preferred_cdn_host(server: &CContentServerDirectoryServerInfo) -> &str {
    if !server.vhost.is_empty() {
        &server.vhost
    } else {
        &server.host
    }
}

// One-line Android log emit; kept dependency-free so the downloader stays
// testable off-device (no android linkage required for unit tests).
pub fn log_line(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let tag = CString::new("WnDepotDownloader").unwrap_or_default();
        let msg = CString::new(message).unwrap_or_default();
        unsafe {
            // __android_log_print(ANDROID_LOG_INFO, tag, fmt, ...)
            extern "C" {
                fn __android_log_print(
                    prio: i32,
                    tag: *const std::os::raw::c_char,
                    fmt: *const std::os::raw::c_char,
                    ...
                ) -> i32;
            }
            let _ = __android_log_print(4, tag.as_ptr(), b"%s\0".as_ptr() as *const _, msg.as_ptr());
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("{message}");
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DepotResumeDecision {
    SkipInstalled,
    Download { trust_existing_chunks: bool },
}

pub fn decide_depot_resume(
    fresh: bool,
    cfg: &DepotConfigStore,
    spec: DepotSpec,
    clean_pause_marker_exists: bool,
) -> DepotResumeDecision {
    if !fresh && cfg.is_installed(spec.depot_id, spec.manifest_id) {
        DepotResumeDecision::SkipInstalled
    } else {
        DepotResumeDecision::Download {
            trust_existing_chunks: !fresh && clean_pause_marker_exists,
        }
    }
}

pub fn in_progress_manifest_id() -> u64 {
    INVALID_MANIFEST_ID
}

pub fn map_write_progress(
    depot_id: u32,
    depots_done: u32,
    depots_total: u32,
    done: u64,
    total: u64,
    verifying: bool,
) -> DepotDownloadProgress {
    DepotDownloadProgress {
        depot_id,
        depot_done: done,
        depot_total: total,
        depots_done,
        depots_total,
        verifying,
    }
}

pub type DepotProgressCallback<'a> = &'a (dyn Fn(&DepotDownloadProgress) + Sync);

/// Returns a fresh manifest request code for (depot_id, manifest_id); Steam rotates codes ~every 5 min.
pub type ManifestCodeRefresher<'a> = &'a (dyn Fn(u32, u64) -> Option<u64> + Sync);

pub fn download_resolved_depots(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
    ca_bundle_path: &str,
    fresh: bool,
    max_workers: u32,
) -> DepotDownloadResult {
    download_resolved_depots_with_cancel_progress(
        install_dir,
        depots,
        servers,
        ca_bundle_path,
        fresh,
        max_workers,
        None,
        None,
        None,
        "",
        None,
        false,
    )
}

pub fn download_resolved_depots_with_cancel(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
    ca_bundle_path: &str,
    fresh: bool,
    max_workers: u32,
    cancel: Option<&AtomicBool>,
) -> DepotDownloadResult {
    download_resolved_depots_with_cancel_progress(
        install_dir,
        depots,
        servers,
        ca_bundle_path,
        fresh,
        max_workers,
        cancel,
        None,
        None,
        "",
        None,
        false,
    )
}

pub fn download_resolved_depots_with_cancel_progress(
    install_dir: &str,
    depots: &[ResolvedDepotSpec],
    servers: &[CContentServerDirectoryServerInfo],
    ca_bundle_path: &str,
    fresh: bool,
    max_workers: u32,
    cancel: Option<&AtomicBool>,
    on_progress: Option<DepotProgressCallback<'_>>,
    code_refresher: Option<ManifestCodeRefresher<'_>>,
    cdn_auth_token: &str,
    token_refresher: Option<CdnAuthTokenRefresher<'_>>,
    adaptive: bool,
) -> DepotDownloadResult {
    if let Err(error) = validate_resolved_download_inputs(install_dir, depots, servers) {
        return error;
    }
    let usable_servers = filter_usable_cdn_servers(servers.iter().cloned());
    if usable_servers.is_empty() {
        return DepotDownloadResult::fail("download: no usable CDN server");
    }

    if let Err(error) = fs::create_dir_all(install_dir) {
        return DepotDownloadResult::fail(format!("download: mkdir install dir: {error}"));
    }
    let config_dir = Path::new(install_dir).join(".DepotDownloader");
    if let Err(error) = fs::create_dir_all(&config_dir) {
        return DepotDownloadResult::fail(format!("download: mkdir config dir: {error}"));
    }

    let mut cfg = DepotConfigStore::load(&config_dir);
    if fresh {
        // Reset only this batch's depots; a global discard would wipe earlier batches' records.
        for depot in depots {
            cfg.forget_depot(depot.depot_id);
            DepotProgressStore::remove(&config_dir, depot.depot_id, depot.manifest_id);
            remove_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
        }
    }

    let cdn = CdnClient::new(ca_bundle_path);
    let mut result = DepotDownloadResult {
        success: true,
        ..Default::default()
    };

    let depots_total = depots.len() as u32;
    for (depot_index, depot) in depots.iter().enumerate() {
        if cancel.is_some_and(|cancel| cancel.load(Ordering::Relaxed)) {
            return DepotDownloadResult::fail("cancelled");
        }
        let spec = DepotSpec {
            depot_id: depot.depot_id,
            manifest_id: depot.manifest_id,
        };
        let clean_pause = has_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
        if decide_depot_resume(fresh, &cfg, spec, clean_pause) == DepotResumeDecision::SkipInstalled
        {
            result.depots_skipped += 1;
            continue;
        }
        if depot.depot_key.len() != 32 {
            return DepotDownloadResult::fail(format!(
                "download: depot key unavailable for depot {}",
                depot.depot_id
            ));
        }
        if !cfg.begin_depot(depot.depot_id) {
            return DepotDownloadResult::fail(format!(
                "download: depot.config begin failed for depot {}",
                depot.depot_id
            ));
        }

        let cache_path = cfg.manifest_cache_path(depot.depot_id, depot.manifest_id);
        let raw_manifest = match read_cached_manifest(&cache_path) {
            Some(raw) => raw,
            None => {
                if cancel.is_some_and(|cancel| cancel.load(Ordering::Relaxed)) {
                    return DepotDownloadResult::fail("cancelled");
                }
                // Prefer a code obtained now; the pre-resolved one may have expired.
                let refreshed_code = code_refresher
                    .and_then(|refresh| refresh(depot.depot_id, depot.manifest_id));
                let request_code = refreshed_code.unwrap_or(depot.manifest_request_code);
                let mut manifest = fetch_manifest_with_retry_and_token(
                    &cdn,
                    &usable_servers,
                    depot.depot_id,
                    depot.manifest_id,
                    request_code,
                    cdn_auth_token,
                    token_refresher,
                    CdnClient::default_timeout(),
                );
                if !manifest.ok() {
                    // One more pass with a code obtained after the failed attempts.
                    if let Some(fresh) = code_refresher
                        .and_then(|refresh| refresh(depot.depot_id, depot.manifest_id))
                        .filter(|fresh| *fresh != request_code)
                    {
                        manifest = fetch_manifest_with_retry_and_token(
                            &cdn,
                            &usable_servers,
                            depot.depot_id,
                            depot.manifest_id,
                            fresh,
                            cdn_auth_token,
                            token_refresher,
                            CdnClient::default_timeout(),
                        );
                    }
                }
                if !manifest.ok() {
                    return DepotDownloadResult::fail(format!(
                        "download: manifest fetch failed for depot {}: {}",
                        depot.depot_id, manifest.error
                    ));
                }
                let _ = write_manifest_cache(&cache_path, &manifest.raw_manifest);
                manifest.raw_manifest
            }
        };

        let Some(mut manifest) = ContentManifest::parse(&raw_manifest) else {
            return DepotDownloadResult::fail(format!(
                "download: manifest parse failed for depot {}",
                depot.depot_id
            ));
        };
        if !manifest.decrypt_filenames(&depot.depot_key) {
            return DepotDownloadResult::fail(format!(
                "download: filename decryption failed for depot {}",
                depot.depot_id
            ));
        }

        let depot_id = depot.depot_id;
        let depots_done = depot_index as u32;
        let chunk_progress = |done: u64, total: u64, verifying: bool| {
            if let Some(on_progress) = on_progress {
                let progress = map_write_progress(
                    depot_id,
                    depots_done,
                    depots_total,
                    done,
                    total,
                    verifying,
                );
                on_progress(&progress);
            }
        };
        let chunk_progress: crate::depot_writer::DepotChunkProgressCallback =
            &chunk_progress;
        let write_result = write_depot_sequential(
            &manifest,
            &depot.depot_key,
            &cdn,
            &usable_servers,
            install_dir,
            DepotWriteOptions {
                max_workers,
                cancel,
                on_progress: Some(chunk_progress),
                cdn_auth_token,
                adaptive,
                ..Default::default()
            },
        );
        if !write_result.ok() {
            if write_result.resume_trust_safe {
                let _ = write_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
            }
            return DepotDownloadResult::fail(format!(
                "download: depot {} write failed: {}",
                depot.depot_id, write_result.error
            ));
        }

        if !cfg.finish_depot(depot.depot_id, depot.manifest_id) {
            return DepotDownloadResult::fail(format!(
                "download: depot.config finish failed for depot {}",
                depot.depot_id
            ));
        }
        DepotProgressStore::new(&config_dir, depot.depot_id, depot.manifest_id).discard();
        remove_clean_pause_marker(&config_dir, depot.depot_id, depot.manifest_id);
        result.bytes_written += write_result.bytes_written;
        result.depots_completed += 1;
    }

    result
}

fn read_cached_manifest(path: &Path) -> Option<Vec<u8>> {
    let bytes = fs::read(path).ok()?;
    (!bytes.is_empty()).then_some(bytes)
}

fn write_manifest_cache(path: &Path, raw_manifest: &[u8]) -> bool {
    let Some(parent) = path.parent() else {
        return false;
    };
    if fs::create_dir_all(parent).is_err() {
        return false;
    }
    fs::write(path, raw_manifest).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::content_manifest::{END_OF_MANIFEST_MAGIC, METADATA_MAGIC, PAYLOAD_MAGIC};
    use crate::proto_wire::Writer;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn marker_names_match_cpp_format() {
        assert_eq!(clean_pause_marker_name(123, 456), "123_456.cleanpause");
    }

    #[test]
    fn clean_pause_marker_files_roundtrip() {
        let dir = temp_dir("clean_pause");
        assert!(!has_clean_pause_marker(&dir, 123, 456));
        assert!(write_clean_pause_marker(&dir, 123, 456));
        assert!(has_clean_pause_marker(&dir, 123, 456));
        assert_eq!(
            fs::read_to_string(clean_pause_marker_path(&dir, 123, 456)).unwrap(),
            "456"
        );
        remove_clean_pause_marker(&dir, 123, 456);
        assert!(!has_clean_pause_marker(&dir, 123, 456));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn keeps_china_servers_as_fallback_and_drops_hostless() {
        let servers = filter_usable_cdn_servers([
            CContentServerDirectoryServerInfo {
                host: "global1".into(),
                ..Default::default()
            },
            CContentServerDirectoryServerInfo {
                host: "china1".into(),
                steam_china_only: true,
                ..Default::default()
            },
            CContentServerDirectoryServerInfo {
                host: "global2".into(),
                ..Default::default()
            },
            // vhost-only servers are still reachable (CDN client falls back to vhost).
            CContentServerDirectoryServerInfo {
                host: String::new(),
                vhost: "cdn-vhost.example".into(),
                ..Default::default()
            },
            // No host and no vhost: unusable, must be dropped.
            CContentServerDirectoryServerInfo {
                host: String::new(),
                ..Default::default()
            },
            CContentServerDirectoryServerInfo {
                host: "china2".into(),
                steam_china_only: true,
                ..Default::default()
            },
        ]);
        // Global servers first, then China-only, then the global Valve CDN
        // fallback pool; hostless entries dropped.
        assert_eq!(servers.len(), 5 + 8);
        assert_eq!(servers[0].host, "global1");
        assert_eq!(servers[1].host, "global2");
        assert_eq!(servers[2].vhost, "cdn-vhost.example");
        assert_eq!(servers[3].host, "china1");
        assert!(servers[3].steam_china_only);
        assert_eq!(servers[4].host, "china2");
        assert!(servers[4].steam_china_only);
        // The tail is the Valve global CDN fallback pool (Asia-Pacific edges first).
        assert_eq!(servers[5].host, "dist1-hkg1.steamcontent.com");
        assert!(servers.last().unwrap().host.ends_with("cache2.steamcontent.com"));
    }

    #[test]
    fn appends_global_fallback_when_only_partner_cdns_offered() {
        // Reproduces the field failure: a CM on a mainland-Chinese network
        // hands out only Perfect World partner CDNs (china_only=false!), which
        // 403 international accounts. The global pool must be appended.
        let servers = filter_usable_cdn_servers([
            CContentServerDirectoryServerInfo {
                host: "st.dl.eccdnx.com".into(),
                vhost: "st.dl.eccdnx.com".into(),
                ..Default::default()
            },
            CContentServerDirectoryServerInfo {
                host: "dl.steam.clngaa.com".into(),
                vhost: "dl.steam.clngaa.com".into(),
                ..Default::default()
            },
        ]);
        assert_eq!(servers.len(), 2 + 8);
        assert_eq!(servers[0].host, "st.dl.eccdnx.com");
        assert_eq!(servers[1].host, "dl.steam.clngaa.com");
        assert_eq!(servers[2].host, "dist1-hkg1.steamcontent.com");
        assert_eq!(servers[2].port(), 443);
    }

    #[test]
    fn resume_decision_matches_cpp_fresh_and_installed_rules() {
        let dir = temp_dir("resume_decision");
        let mut cfg = DepotConfigStore::load(&dir);
        cfg.finish_depot(100, 555);
        let spec = DepotSpec {
            depot_id: 100,
            manifest_id: 555,
        };
        assert_eq!(
            decide_depot_resume(false, &cfg, spec, false),
            DepotResumeDecision::SkipInstalled
        );
        assert_eq!(
            decide_depot_resume(true, &cfg, spec, true),
            DepotResumeDecision::Download {
                trust_existing_chunks: false
            }
        );
        assert_eq!(
            decide_depot_resume(
                false,
                &cfg,
                DepotSpec {
                    depot_id: 100,
                    manifest_id: 777
                },
                true
            ),
            DepotResumeDecision::Download {
                trust_existing_chunks: true
            }
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn retry_sweep_and_progress_mapping() {
        // The manifest retry sweeps each server once (in order), so fallback
        // servers at the tail are still reached.
        assert_eq!(manifest_retry_server_indices(3), [0, 1, 2]);
        assert_eq!(manifest_retry_server_indices(1), [0]);
        assert_eq!(manifest_retry_server_indices(0), Vec::<usize>::new());
        assert_eq!(retry_backoff_millis(1), 300);
        assert_eq!(retry_backoff_millis(5), 4000);
        assert_eq!(
            map_write_progress(100, 2, 4, 10, 20, true),
            DepotDownloadProgress {
                depot_id: 100,
                depot_done: 10,
                depot_total: 20,
                depots_done: 2,
                depots_total: 4,
                verifying: true
            }
        );
    }

    #[test]
    fn validates_download_inputs() {
        assert_eq!(
            validate_download_inputs("", &[DepotSpec::default()])
                .unwrap_err()
                .error,
            "download: empty install dir"
        );
        assert_eq!(
            validate_download_inputs("/tmp/app", &[]).unwrap_err().error,
            "download: no depots"
        );
        assert!(validate_download_inputs("/tmp/app", &[DepotSpec::default()]).is_ok());
        assert_eq!(in_progress_manifest_id(), INVALID_MANIFEST_ID);
    }

    #[test]
    fn validates_resolved_inputs_and_filters_servers() {
        assert_eq!(
            validate_resolved_download_inputs("", &[ResolvedDepotSpec::default()], &[])
                .unwrap_err()
                .error,
            "download: empty install dir"
        );
        assert_eq!(
            validate_resolved_download_inputs("/tmp/app", &[], &[])
                .unwrap_err()
                .error,
            "download: no depots"
        );
        assert_eq!(
            validate_resolved_download_inputs("/tmp/app", &[ResolvedDepotSpec::default()], &[])
                .unwrap_err()
                .error,
            "download: no CDN servers available"
        );
    }

    #[test]
    fn resolved_download_uses_cached_manifest_and_records_install() {
        let dir = temp_dir("resolved_download_cached_manifest");
        let config_dir = dir.join(".DepotDownloader");
        fs::create_dir_all(&config_dir).unwrap();
        let raw_manifest = raw_layout_manifest(100, 555, "empty.bin", 5);
        fs::write(config_dir.join("100_555.manifest"), raw_manifest).unwrap();

        let result = download_resolved_depots(
            dir.to_str().unwrap(),
            &[ResolvedDepotSpec {
                depot_id: 100,
                manifest_id: 555,
                depot_key: vec![1u8; 32],
                manifest_request_code: 0,
            }],
            &[CContentServerDirectoryServerInfo {
                host: "cdn.example".into(),
                https_support: "mandatory".into(),
                ..Default::default()
            }],
            "",
            false,
            4,
        );

        assert!(result.success, "{}", result.error);
        assert_eq!(result.depots_completed, 1);
        assert_eq!(result.depots_skipped, 0);
        assert_eq!(fs::metadata(dir.join("empty.bin")).unwrap().len(), 5);
        let cfg = DepotConfigStore::load(&config_dir);
        assert!(cfg.is_installed(100, 555));

        let skipped = download_resolved_depots(
            dir.to_str().unwrap(),
            &[ResolvedDepotSpec {
                depot_id: 100,
                manifest_id: 555,
                depot_key: vec![1u8; 32],
                manifest_request_code: 0,
            }],
            &[CContentServerDirectoryServerInfo {
                host: "cdn.example".into(),
                https_support: "mandatory".into(),
                ..Default::default()
            }],
            "",
            false,
            4,
        );
        assert!(skipped.success);
        assert_eq!(skipped.depots_completed, 0);
        assert_eq!(skipped.depots_skipped, 1);
        let _ = fs::remove_dir_all(&dir);
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "wnsteam_downloader_{name}_{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    fn raw_layout_manifest(depot_id: u32, manifest_id: u64, filename: &str, size: u64) -> Vec<u8> {
        let mut file_body = Vec::new();
        {
            let mut writer = Writer::new(&mut file_body);
            writer.string_field(1, filename);
            writer.uint64_field(2, size);
        }

        let mut payload = Vec::new();
        Writer::new(&mut payload).submessage_field(1, &file_body);

        let mut metadata = Vec::new();
        {
            let mut writer = Writer::new(&mut metadata);
            writer.uint32_field(1, depot_id);
            writer.uint64_field(2, manifest_id);
            writer.bool_field_force(4, false);
        }

        let mut raw = Vec::new();
        push_section(&mut raw, PAYLOAD_MAGIC, &payload);
        push_section(&mut raw, METADATA_MAGIC, &metadata);
        raw.extend_from_slice(&END_OF_MANIFEST_MAGIC.to_le_bytes());
        raw
    }

    fn push_section(out: &mut Vec<u8>, magic: u32, body: &[u8]) {
        out.extend_from_slice(&magic.to_le_bytes());
        out.extend_from_slice(&(body.len() as u32).to_le_bytes());
        out.extend_from_slice(body);
    }
}
