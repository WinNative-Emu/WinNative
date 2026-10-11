package com.winlator.cmod.feature.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.shared.android.StoragePathUtils
import com.winlator.cmod.shared.io.StorageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class ExternalDrive(
    val id: String,
    val label: String,
    val rootPath: String,
    val downloadPath: String,
)

data class ExternalDriveStatus(
    val drive: ExternalDrive,
    val connected: Boolean,
    val freeBytes: Long,
)

data class ExternalStorageSnapshot(
    val drives: List<ExternalDriveStatus> = emptyList(),
    val mountedRoots: Set<String> = emptySet(),
    val scanned: Boolean = false,
) {
    val connectivityKey: String =
        buildString {
            append(scanned)
            drives.forEach { status ->
                append('|').append(status.drive.id).append('=').append(status.connected)
                append('@').append(status.drive.downloadPath)
            }
            mountedRoots.sorted().forEach { root -> append('#').append(root) }
        }

    val connectedDrives: List<ExternalDriveStatus>
        get() = drives.filter { it.connected }

    fun preferredDrive(): ExternalDriveStatus? = drives.firstOrNull { it.connected }

    fun isOnDisconnectedDrive(path: String): Boolean {
        if (!scanned) return false
        val target = ExternalStorage.canonicalize(path)
        if (target.isEmpty()) return false
        if (mountedRoots.any { ExternalStorage.isUnder(target, it) }) return false

        val registered =
            drives.firstOrNull { status ->
                ExternalStorage.isUnder(target, status.drive.rootPath) ||
                    ExternalStorage.isUnder(target, status.drive.downloadPath)
            }
        if (registered != null) return !registered.connected

        val shapedRoot = ExternalStorage.externalShapedRootOf(target) ?: return false
        return mountedRoots.none { ExternalStorage.samePath(it, shapedRoot) }
    }
}

object ExternalStorage {
    private const val MEDIA_RW_PREFIX = "/mnt/media_rw"
    private const val STORAGE_PREFIX = "/storage"

    private val installed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    @Volatile
    private var appContext: Context? = null

    private val _state = MutableStateFlow(ExternalStorageSnapshot())
    val state: StateFlow<ExternalStorageSnapshot> = _state.asStateFlow()

    private val mediaReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                refresh()
            }
        }

    fun install(context: Context) {
        val application = context.applicationContext
        appContext = application
        if (!installed.compareAndSet(false, true)) {
            refresh()
            return
        }

        runCatching {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_MEDIA_MOUNTED)
                    addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                    addAction(Intent.ACTION_MEDIA_EJECT)
                    addAction(Intent.ACTION_MEDIA_REMOVED)
                    addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                    addDataScheme("file")
                }
            ContextCompat.registerReceiver(
                application,
                mediaReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Timber.w(it, "External storage media receiver registration failed") }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                application.getSystemService(StorageManager::class.java)?.registerStorageVolumeCallback(
                    { command -> scope.launch { command.run() } },
                    object : StorageManager.StorageVolumeCallback() {
                        override fun onStateChanged(volume: StorageVolume) {
                            refresh()
                        }
                    },
                )
            }.onFailure { Timber.w(it, "External storage volume callback registration failed") }
        }

        refresh()
    }

    fun refresh() {
        val context = appContext ?: return
        scope.launch { refreshNow(context) }
    }

    suspend fun refreshNow(context: Context) {
        withContext(Dispatchers.IO) {
            refreshLock.withLock {
                _state.value = scan(context.applicationContext)
            }
        }
    }

    suspend fun addDrive(
        context: Context,
        selectedPath: String,
    ): Result<ExternalDrive> =
        withContext(Dispatchers.IO) {
            runCatching {
                val application = context.applicationContext
                val target = canonicalize(selectedPath)
                require(target.isNotEmpty()) { "empty path" }

                val root =
                    checkNotNull(resolveVolumeRoot(application, target)) { "not on external storage" }

                val volume = volumeFor(application, root)
                val fallbackName = File(root).name.ifBlank { root }
                val drive =
                    ExternalDrive(
                        id = volume?.uuid?.takeIf { it.isNotBlank() } ?: fallbackName,
                        label =
                            volume
                                ?.getDescription(application)
                                ?.takeIf { it.isNotBlank() }
                                ?: fallbackName,
                        rootPath = root,
                        downloadPath = target,
                    )

                refreshLock.withLock {
                    saveDrives(loadDrives().filterNot { it.id == drive.id } + drive)
                    _state.value = scan(application)
                }
                drive
            }.onFailure { Timber.w(it, "External drive registration failed") }
        }

    suspend fun removeDrive(
        context: Context,
        id: String,
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                val application = context.applicationContext
                refreshLock.withLock {
                    saveDrives(loadDrives().filterNot { it.id == id })
                    _state.value = scan(application)
                }
            }.onFailure { Timber.w(it, "External drive removal failed") }
        }
    }

    fun storeInstallRoot(
        basePath: String,
        store: InstallStore,
    ): String {
        val relative =
            when (store) {
                InstallStore.STEAM -> "Steam/steamapps/common"
                InstallStore.EPIC -> "Epic/games"
                InstallStore.GOG -> "GOG/games/common"
                InstallStore.ITCH -> "Itch/games"
            }
        return File(basePath, relative).absolutePath
    }

    fun connectedInstallRoots(store: InstallStore): List<String> =
        _state.value.connectedDrives.map { storeInstallRoot(it.drive.downloadPath, store) }

    fun protectedRoots(): List<String> =
        _state.value.drives.flatMap { listOf(it.drive.rootPath, it.drive.downloadPath) }

    internal fun canonicalize(path: String?): String {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return runCatching { File(raw).absolutePath }
            .getOrDefault(raw)
            .trimEnd('/')
            .ifEmpty { "/" }
    }

    internal fun samePath(
        a: String,
        b: String,
    ): Boolean = canonicalize(a) == canonicalize(b)

    internal fun isUnder(
        candidate: String,
        root: String,
    ): Boolean {
        val normalizedRoot = canonicalize(root)
        if (normalizedRoot.isEmpty() || normalizedRoot == "/") return false
        val normalizedCandidate = canonicalize(candidate)
        return normalizedCandidate == normalizedRoot || normalizedCandidate.startsWith("$normalizedRoot/")
    }

    internal fun isExternalVolumeRoot(path: String): Boolean {
        val shaped = externalShapedRootOf(path) ?: return false
        return samePath(shaped, path)
    }

    internal fun externalShapedRootOf(path: String): String? {
        val normalized = canonicalize(path)
        if (normalized.startsWith("$MEDIA_RW_PREFIX/")) {
            val name = normalized.removePrefix("$MEDIA_RW_PREFIX/").substringBefore('/')
            return if (name.isBlank()) null else "$MEDIA_RW_PREFIX/$name"
        }
        if (normalized.startsWith("$STORAGE_PREFIX/")) {
            val name = normalized.removePrefix("$STORAGE_PREFIX/").substringBefore('/')
            if (name.isBlank() || name == "emulated" || name == "self") return null
            return "$STORAGE_PREFIX/$name"
        }
        return null
    }

    private fun scan(context: Context): ExternalStorageSnapshot {
        val mounted = linkedSetOf<String>()
        runCatching {
            StoragePathUtils
                .getMountedStorageRoots(
                    context = context,
                    includePrimary = false,
                    includeMediaRw = true,
                    requireBrowsable = true,
                ).forEach { root ->
                    listOf(root.path, root.absolutePath)
                        .map(::canonicalize)
                        .filter(::isExternalVolumeRoot)
                        .forEach { mounted += it }
                }
        }.onFailure { Timber.w(it, "External storage root scan failed") }

        val statuses =
            loadDrives().map { drive ->
                val connected =
                    mounted.any { samePath(it, drive.rootPath) } && File(drive.downloadPath).isDirectory
                ExternalDriveStatus(
                    drive = drive,
                    connected = connected,
                    freeBytes =
                        if (connected) {
                            runCatching { StorageUtils.getAvailableSpace(drive.downloadPath) }.getOrDefault(0L)
                        } else {
                            0L
                        },
                )
            }

        return ExternalStorageSnapshot(drives = statuses, mountedRoots = mounted, scanned = true)
    }

    private fun resolveVolumeRoot(
        context: Context,
        path: String,
    ): String? {
        val mounted =
            runCatching {
                StoragePathUtils.getMountedStorageRoots(
                    context = context,
                    includePrimary = false,
                    includeMediaRw = true,
                    requireBrowsable = true,
                )
            }.getOrDefault(emptyList())

        val match =
            mounted
                .map { canonicalize(it.path) }
                .filter { isExternalVolumeRoot(it) && isUnder(path, it) }
                .maxByOrNull { it.length }
        if (match != null) return match
        return externalShapedRootOf(path)?.takeIf { File(it).isDirectory }
    }

    private fun volumeFor(
        context: Context,
        root: String,
    ): StorageVolume? =
        runCatching {
            val manager = context.getSystemService(StorageManager::class.java) ?: return@runCatching null
            manager.getStorageVolume(File(root))
                ?: manager.storageVolumes.firstOrNull { volume ->
                    val uuidPath = volume.uuid?.let { "$STORAGE_PREFIX/$it" }
                    val directPath =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory?.absolutePath else null
                    (uuidPath != null && samePath(uuidPath, root)) ||
                        (directPath != null && samePath(directPath, root))
                }
        }.getOrNull()

    private fun loadDrives(): List<ExternalDrive> =
        runCatching {
            val raw = PrefManager.externalDrivesJson
            if (raw.isBlank()) return emptyList()
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val rootPath = entry.optString("root").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val downloadPath = entry.optString("download").takeIf { it.isNotBlank() } ?: rootPath
                ExternalDrive(
                    id = id,
                    label = entry.optString("label").ifBlank { File(rootPath).name },
                    rootPath = rootPath,
                    downloadPath = downloadPath,
                )
            }
        }.getOrElse {
            Timber.w(it, "External drive registry parse failed")
            emptyList()
        }

    private fun saveDrives(drives: List<ExternalDrive>) {
        val array = JSONArray()
        drives.forEach { drive ->
            array.put(
                JSONObject().apply {
                    put("id", drive.id)
                    put("label", drive.label)
                    put("root", drive.rootPath)
                    put("download", drive.downloadPath)
                },
            )
        }
        PrefManager.externalDrivesJson = array.toString()
    }
}
