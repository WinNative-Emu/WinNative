package com.winlator.cmod.runtime.linux

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.annotation.StringRes
import com.winlator.cmod.R
import com.winlator.cmod.feature.library.LinuxApps
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerCreation
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.wayland.WineWaylandSupport
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import com.winlator.cmod.shared.util.OnExtractFileListener
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONObject

/**
 * Installs what the GameScope container needs for the native Steam client: the Linux runtime,
 * published as a release asset of WinNative's Components repository, Valve's arm64 Proton from
 * the same release so the first Windows title has its compatibility tool, then the arm64 Steam client
 * from Valve's update servers, and finally the GameScope container with its Steam library entry.
 * Valve's client is not redistributable, so it always comes from Valve. winnative-steam-install performs the same client steps inside a session and skips them
 * once the stamp written here exists.
 */
object LinuxClientInstaller {
    private const val TAG = "LinuxClientInstaller"
    private const val RELEASE = "https://github.com/WinNative-Emu/Components/releases/download/Assets"
    private const val RUNTIME_ARCHIVE = "$RELEASE/linuxfs.tar.zst"
    private const val RUNTIME_INFO = "$RELEASE/linuxfs.json"
    private const val PROTON_ARCHIVE = "$RELEASE/proton-arm64.tar.zst"
    private const val PROTON_INFO = "$RELEASE/proton-arm64.json"
    private const val PROTON_DIR = "/opt/winnative-proton"
    private const val PROTON_STAGING_DIR = "proton.staging"
    private const val DRIVER_ARCHIVE = "$RELEASE/linux-turnip.tar.zst"
    private const val DRIVER_INFO = "$RELEASE/linux-turnip.json"
    private const val DRIVER_STAGING_DIR = "linux-driver.staging"
    private const val COMPOSITOR_DRIVER_ASSET = "WN-Turnip-1.16-p_Axxx.zip"
    private const val COMPOSITOR_DRIVER =
        "https://github.com/nicholasx417/WinNative-Components/releases/download/WinNative-Turnip/$COMPOSITOR_DRIVER_ASSET"
    private const val COMPOSITOR_DRIVER_SHA256 = "146023e8cb402390d2792087f3af6569d24a95d490105cbf41be1a7dacee8957"
    private const val COMPOSITOR_DRIVER_SIZE = 2673455L
    private const val RUNTIME_VERSION_FILE = "etc/winnative/version"
    private const val PROTON_VERSION_FILE = "winnative-version"

    /**
     * What a runtime being replaced hands on to the new one: the home directory with the client
     * and its sign-in, the library the app maps in with its prefixes and saves, Proton, and the
     * machine id the client ties its sign-in to.
     */
    private val KEPT_PATHS = listOf("root", "mnt/winnative", PROTON_DIR.substring(1), "etc/machine-id")
    private const val PREFERENCES = "linux_client"
    private const val DISMISSED_UPDATE = "dismissed_update"
    private const val STEAM_CDN = "https://client-update.fastly.steamstatic.com"
    private const val STEAM_MANIFEST = "steam_client_publicbeta_linuxarm64"
    private const val STEAM_ROOT = "/root/.local/share/Steam"
    private const val STEAM_STAMP = "package/winnative-installed"
    private const val WORK_DIR = "linux-client-download"
    private const val STAGING_DIR = "linuxfs.staging"
    private const val RETIRED_DIR = "linuxfs.old"
    private const val SPACE_MARGIN = 512L shl 20
    private const val STEAM_UNPACK_FACTOR = 3L
    private const val RECORD_DIRECTORY = -1L
    private const val RECORD_LINK = -2L
    private const val RECORD_OS_VERSION = -184
    private const val RECORD_VERSION = 3
    private const val CLIENT_ZONE_SECONDS = 8 * 3600L
    private const val PROGRESS_INTERVAL_MS = 100L

    enum class Stage { CONNECT, DOWNLOAD_RUNTIME, INSTALL_RUNTIME, DOWNLOAD_PROTON, INSTALL_PROTON, DOWNLOAD_STEAM, INSTALL_STEAM, LIBRARY }

    sealed interface State {
        data object Checking : State

        data object Missing : State

        data object Installed : State

        /**
         * Installed, and the release carries a newer runtime, Proton or driver, published as [version].
         * [isNews] until the user has been shown that version once.
         */
        data class UpdateAvailable(val version: Long, val isNews: Boolean) : State

        data class Working(val stage: Stage, val done: Long, val total: Long) : State

        data object Failed : State

        data class NoSpace(val needed: Long, val available: Long) : State

        /** The GameScope container cannot be created; [message] says what is missing. */
        data class Blocked(@StringRes val message: Int) : State
    }

    private class NoSpaceException(val needed: Long, val available: Long) : IOException()

    private class BlockedException(@StringRes val messageRes: Int) : IOException()

    private class Part(val name: String, val file: String, val size: Long, val sha256: String)

    /** One line of the client's install record: a file's size, or -1 for a directory and -2 for a link. */
    private class Installed(val name: String, val size: Long, val modified: Long, val crc: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val mutableState = MutableStateFlow<State>(State.Checking)
    private var generation = 0
    private var job: Job? = null
    private var updating = false
    private val swapLock = Any()

    val state: StateFlow<State> = mutableState.asStateFlow()

    val isWorking: Boolean get() = mutableState.value is State.Working

    /** Worker thread. */
    fun isInstalled(context: Context): Boolean =
        LinuxRuntime.isInstalled(context) && isProtonInstalled(context) && isSteamInstalled(context) &&
            hasCompositorDriver(context) && !LinuxApps.isSteamShortcutMissing(context)

    /**
     * Worker thread. The app's compositor takes gamescope's frames in with a Turnip of its own,
     * loaded through adrenotools; the system's Vulkan driver cannot import them and the session
     * stays black. Any Turnip from Drivers serves, so one is only fetched when there is none.
     */
    @JvmStatic
    fun hasCompositorDriver(context: Context): Boolean {
        val drivers = AdrenotoolsManager(context)
        return drivers.enumarateInstalledDrivers().any { drivers.isTurnipDriver(it) }
    }

    /** Whether an installed driver is the Turnip this install fetches for the compositor. */
    @JvmStatic
    fun isCompositorDriver(
        drivers: AdrenotoolsManager,
        driverId: String,
    ): Boolean = drivers.getSourceAsset(driverId) == COMPOSITOR_DRIVER_ASSET

    /** Reads what is on disk, off the calling thread, unless an install is running. */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val seen = synchronized(lock) { if (isWorking) return else generation }
        scope.launch {
            if (isWorking) return@launch
            recoverSwap(appContext)
            if (!isInstalled(appContext)) {
                publishFound(seen) { State.Missing }
                return@launch
            }
            // What is on disk is known at once; whether the release has moved on takes the network.
            publishFound(seen) { it as? State.UpdateAvailable ?: State.Installed }
            val version = pendingUpdate(appContext)
            val found =
                if (version > 0) State.UpdateAvailable(version, preferences(appContext).getLong(DISMISSED_UPDATE, 0L) < version)
                else State.Installed
            publishFound(seen) { found }
        }
    }

    private fun publishFound(
        seen: Int,
        found: (State) -> State,
    ) {
        synchronized(lock) { if (generation == seen && !isWorking) mutableState.value = found(mutableState.value) }
    }

    /** Installs what is missing. Started from an offered update, or retried after one, it updates too. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (isWorking) return
            val current = mutableState.value
            updating = current is State.UpdateAvailable ||
                (updating && current != State.Installed && current != State.Missing)
            val update = updating
            generation++
            mutableState.value = State.Working(Stage.CONNECT, 0, 0)
            job = scope.launch { install(appContext, update) }
        }
    }

    /** UI thread. The offer has been put in front of the user; it is not pressed on them again. */
    fun dismissUpdate(
        context: Context,
        update: State.UpdateAvailable,
    ) {
        synchronized(lock) { if (mutableState.value == update) mutableState.value = update.copy(isNews = false) }
        preferences(context).edit().putLong(DISMISSED_UPDATE, update.version).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun cancel() {
        synchronized(lock) { job?.cancel() }
    }

    private suspend fun install(
        context: Context,
        update: Boolean,
    ) {
        val work = File(context.filesDir, WORK_DIR)
        val outcome: State =
            try {
                // Told before a gigabyte is downloaded for a GPU the runtime's driver cannot use.
                if (!WineWaylandSupport.isAdrenoDevice(context)) throw BlockedException(R.string.linux_client_gpu_unsupported)
                recoverSwap(context)
                FileUtils.delete(work)
                if (!work.mkdirs()) throw IOException("Could not create $work")
                // Checked first, so a device that cannot hold the container is told before the download.
                if (gamescopeContainer(context) == null) requireSystemImage(context)
                val updateRuntime = update && runtimeUpdate(context) > 0
                val updateProton = update && protonUpdate(context) > 0
                val updateDriver = update && driverUpdate(context) > 0
                // A session has the runtime's files open and mapped; they cannot change under it.
                if ((updateRuntime || updateProton || updateDriver) && SessionKeepAliveService.isLinuxSessionActive()) {
                    throw BlockedException(R.string.linux_client_update_session_running)
                }
                if (updateRuntime || !LinuxRuntime.isInstalled(context)) installRuntime(context, work)
                if (updateProton || !isProtonInstalled(context)) installProton(context, work)
                if (updateDriver) installDriver(context, work)
                if (!hasCompositorDriver(context)) installCompositorDriver(context, work)
                if (!isSteamInstalled(context)) installSteam(context, work)
                addToLibrary(context)
                State.Installed
            } catch (e: CancellationException) {
                discard(context, work)
                publishSettled(if (isInstalled(context)) State.Installed else State.Missing)
                throw e
            } catch (e: NoSpaceException) {
                State.NoSpace(e.needed, e.available)
            } catch (e: BlockedException) {
                State.Blocked(e.messageRes)
            } catch (e: Exception) {
                Log.w(TAG, "Linux client install failed", e)
                State.Failed
            }
        discard(context, work)
        publishSettled(outcome)
    }

    private fun installedVersion(file: File): Long = runCatching { file.readText().trim().toLong() }.getOrDefault(0L)

    /** 0 when the release cannot be read or, as before versions were published, names none. */
    private fun publishedVersion(infoUrl: String): Long =
        runCatching { JSONObject(fetchText(infoUrl)).optLong("version", 0L) }
            .getOrElse {
                Log.d(TAG, "No published version at $infoUrl", it)
                0L
            }

    /** Each of these: the published version when what is installed is older, else 0. */
    private fun runtimeUpdate(context: Context): Long =
        if (LinuxRuntime.isInstalled(context)) {
            newer(RUNTIME_INFO, installedVersion(File(LinuxRuntime.rootDir(context), RUNTIME_VERSION_FILE)))
        } else {
            0L
        }

    /** Only the copy installed here is updated; a depot of Proton is the client's to keep current. */
    private fun protonUpdate(context: Context): Long {
        val proton = hostPath(context, PROTON_DIR)
        return if (File(proton, "proton").isFile) newer(PROTON_INFO, installedVersion(File(proton, PROTON_VERSION_FILE))) else 0L
    }

    /** A Turnip newer than the one the app ships and any downloaded before. */
    private fun driverUpdate(context: Context): Long =
        newer(DRIVER_INFO, maxOf(LinuxRuntime.TURNIP_BUILD, LinuxRuntime.downloadedDriverVersion(context)))

    private fun newer(
        infoUrl: String,
        installed: Long,
    ): Long = publishedVersion(infoUrl).takeIf { it > installed } ?: 0L

    /** Worker thread. The published version the install is behind, or 0 when it is current. */
    private fun pendingUpdate(context: Context): Long =
        runtimeUpdate(context).takeIf { it > 0 } ?: protonUpdate(context).takeIf { it > 0 } ?: driverUpdate(context)

    private fun publishSettled(outcome: State) {
        synchronized(lock) { mutableState.value = outcome }
    }

    private fun discard(
        context: Context,
        work: File,
    ) {
        FileUtils.delete(work)
        FileUtils.delete(File(context.filesDir, STAGING_DIR))
        FileUtils.delete(File(context.filesDir, PROTON_STAGING_DIR))
        FileUtils.delete(File(context.filesDir, DRIVER_STAGING_DIR))
        FileUtils.delete(File(context.filesDir, RETIRED_DIR))
    }

    private suspend fun installRuntime(
        context: Context,
        work: File,
    ) {
        val staging = File(context.filesDir, STAGING_DIR)
        unpackRelease(context, work, RUNTIME_INFO, RUNTIME_ARCHIVE, staging, Stage.DOWNLOAD_RUNTIME, Stage.INSTALL_RUNTIME)
        replaceRootfs(context, staging)
    }

    /** A Steam depot of the ARM64 Proton serves as well as the copy installed here. */
    private suspend fun installCompositorDriver(
        context: Context,
        work: File,
    ) {
        val archive = File(work, COMPOSITOR_DRIVER_ASSET)
        val digest = download(COMPOSITOR_DRIVER, archive, Meter(Stage.DOWNLOAD_RUNTIME, COMPOSITOR_DRIVER_SIZE))
        if (digest != COMPOSITOR_DRIVER_SHA256) throw IOException("The display driver did not arrive intact")
        if (AdrenotoolsManager(context).installDriver(Uri.fromFile(archive), COMPOSITOR_DRIVER_ASSET).isEmpty()) {
            throw IOException("The display driver could not be installed")
        }
    }

    private fun isProtonInstalled(context: Context): Boolean =
        File(hostPath(context, PROTON_DIR), "proton").isFile ||
            listOf("Proton Experimental (ARM64)", "Proton 11.0 (ARM64)").any {
                File(hostPath(context, STEAM_ROOT), "steamapps/common/$it/proton").isFile
            }

    private suspend fun installProton(
        context: Context,
        work: File,
    ) {
        val staging = File(context.filesDir, PROTON_STAGING_DIR)
        val version =
            unpackRelease(context, work, PROTON_INFO, PROTON_ARCHIVE, staging, Stage.DOWNLOAD_PROTON, Stage.INSTALL_PROTON)
        File(staging, PROTON_VERSION_FILE).writeText("$version\n")
        val target = hostPath(context, PROTON_DIR)
        FileUtils.delete(target)
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create $parent")
        if (!staging.renameTo(target)) throw IOException("Could not move Proton into place")
    }

    /**
     * The driver archive holds the library and the name of its Mesa release; the manifest that
     * points the Vulkan loader at it is written here, where the path it lands at is known.
     */
    private suspend fun installDriver(
        context: Context,
        work: File,
    ) {
        val staging = File(context.filesDir, DRIVER_STAGING_DIR)
        val version =
            unpackRelease(context, work, DRIVER_INFO, DRIVER_ARCHIVE, staging, Stage.DOWNLOAD_RUNTIME, Stage.INSTALL_RUNTIME)
        val target = LinuxRuntime.driverDir(context)
        if (!File(staging, LinuxRuntime.DRIVER_LIBRARY).isFile) throw IOException("The driver archive holds no driver")
        val manifest =
            JSONObject()
                .put("file_format_version", "1.0.0")
                .put(
                    "ICD",
                    JSONObject()
                        .put("api_version", "1.4.0")
                        .put("library_path", File(target, LinuxRuntime.DRIVER_LIBRARY).path),
                )
        File(staging, LinuxRuntime.DRIVER_ICD).writeText(manifest.toString())
        File(staging, LinuxRuntime.DRIVER_VERSION_FILE).writeText("$version\n")
        FileUtils.delete(target)
        if (!staging.renameTo(target)) throw IOException("Could not move the driver into place")
    }

    /**
     * Downloads a release archive, checks it against its published digest and unpacks it into
     * [staging]. Returns the version the release gives it.
     */
    private suspend fun unpackRelease(
        context: Context,
        work: File,
        infoUrl: String,
        archiveUrl: String,
        staging: File,
        downloadStage: Stage,
        installStage: Stage,
    ): Long {
        mutableState.value = State.Working(Stage.CONNECT, 0, 0)
        val info = JSONObject(fetchText(infoUrl))
        val sha256 = info.getString("sha256")
        val size = info.getLong("size")
        val unpacked = info.getLong("unpacked")
        requireSpace(context.filesDir, size + unpacked)

        val archive = File(work, archiveUrl.substringAfterLast('/'))
        val downloaded = Meter(downloadStage, size)
        if (!download(archiveUrl, archive, downloaded).equals(sha256, ignoreCase = true)) {
            throw IOException("${archive.name} does not match its published checksum")
        }

        FileUtils.delete(staging)
        if (!staging.mkdirs()) throw IOException("Could not create $staging")
        val written = Meter(installStage, unpacked)
        val installJob = coroutineContext.job
        val listener =
            object : OnExtractFileListener {
                override fun onExtractFile(
                    destination: File,
                    size: Long,
                ): File = destination

                override fun mapsExtractedFiles(): Boolean = false

                override fun reportsExtractedBytesOnly(): Boolean = true

                override fun onExtractedBytes(size: Long) {
                    if (!installJob.isActive) throw CancellationException()
                    written.add(size)
                }
            }
        val extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, staging, listener)
        coroutineContext.ensureActive()
        if (!extracted) throw IOException("${archive.name} could not be unpacked")
        FileUtils.delete(archive)
        return info.optLong("version", 0L)
    }

    /** Swaps the unpacked tree in, handing [KEPT_PATHS] on from a runtime being replaced. */
    private fun replaceRootfs(
        context: Context,
        staging: File,
    ) {
        val root = LinuxRuntime.rootDir(context)
        if (!root.exists()) {
            if (!staging.renameTo(root)) throw IOException("Could not move the runtime into place")
            return
        }
        val retired = File(context.filesDir, RETIRED_DIR)
        FileUtils.delete(retired)
        synchronized(swapLock) { swap(root, staging, retired) }
        FileUtils.delete(retired)
    }

    private fun swap(
        root: File,
        staging: File,
        retired: File,
    ) {
        val handedOn = ArrayList<Pair<File, File>>()
        try {
            for (path in KEPT_PATHS) {
                val old = File(root, path)
                if (!old.exists()) continue
                val fresh = File(staging, path)
                FileUtils.delete(fresh)
                val parent = fresh.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create $parent")
                if (!old.renameTo(fresh)) throw IOException("Could not keep the runtime's /$path")
                handedOn += old to fresh
            }
            if (!root.renameTo(retired)) throw IOException("Could not move the old runtime aside")
            if (!staging.renameTo(root)) {
                retired.renameTo(root)
                throw IOException("Could not move the runtime into place")
            }
        } catch (e: IOException) {
            // The old runtime stays in use, so it takes back what it had handed on.
            for ((old, fresh) in handedOn.asReversed()) fresh.renameTo(old)
            throw e
        }
    }

    /**
     * Puts right a swap the process did not live to finish, before anything discards the staged
     * tree: by then it may hold what the old runtime handed on. The old runtime is only moved
     * aside once the new one is whole, so with it aside the new one goes in; otherwise the old one
     * takes back whatever it no longer has.
     */
    private fun recoverSwap(context: Context) {
        val root = LinuxRuntime.rootDir(context)
        val staging = File(context.filesDir, STAGING_DIR)
        val retired = File(context.filesDir, RETIRED_DIR)
        synchronized(swapLock) {
            if (!root.exists()) {
                if (retired.isDirectory) (if (staging.isDirectory) staging else retired).renameTo(root)
            } else if (staging.isDirectory) {
                for (path in KEPT_PATHS) {
                    val handedOn = File(staging, path)
                    val home = File(root, path)
                    if (handedOn.exists() && !home.exists()) handedOn.renameTo(home)
                }
            }
        }
    }

    private fun gamescopeContainer(context: Context): Container? = LinuxApps.gamescopeContainer(ContainerManager(context))

    /** Containers live in the system image, so the GameScope container cannot be made without it. */
    private fun requireSystemImage(context: Context) {
        if (!ImageFs.find(context).isUpToDate) throw BlockedException(R.string.setup_wizard_system_image_not_installed)
    }

    /** Creates the GameScope container if there is none and makes sure it carries the Steam entry. */
    private fun addToLibrary(context: Context) {
        mutableState.value = State.Working(Stage.LIBRARY, 0, 0)
        val manager = ContainerManager(context)
        val container =
            LinuxApps.gamescopeContainer(manager) ?: run {
                requireSystemImage(context)
                val contents = ContentsManager(context)
                contents.syncContents()
                val runtime = ContainerCreation.newestInstalledRuntime(contents)
                ContainerCreation.createGamescopeContainer(context, manager, contents, runtime)
                    ?: throw BlockedException(R.string.containers_gamescope_create_failed)
            }
        LinuxApps.ensureSteamShortcut(context, container)
    }

    private fun isSteamInstalled(context: Context): Boolean {
        val steamRoot = hostPath(context, STEAM_ROOT)
        return File(steamRoot, STEAM_STAMP).isFile && File(steamRoot, "steamrtarm64/steam").isFile
    }

    private suspend fun installSteam(
        context: Context,
        work: File,
    ) {
        mutableState.value = State.Working(Stage.CONNECT, 0, 0)
        val manifest = fetchText("$STEAM_CDN/$STEAM_MANIFEST")
        val version = manifestVersion(manifest)
        val parts = manifestParts(manifest)
        val total = parts.sumOf { it.size }
        requireSpace(context.filesDir, total * STEAM_UNPACK_FACTOR)

        val downloaded = Meter(Stage.DOWNLOAD_STEAM, total)
        val zips =
            parts.map { part ->
                val zip = File(work, part.file)
                if (!download("$STEAM_CDN/${part.file}", zip, downloaded).equals(part.sha256, ignoreCase = true)) {
                    throw IOException("${part.name} does not match the checksum in Valve's manifest")
                }
                zip
            }

        val steamRoot = hostPath(context, STEAM_ROOT)
        val unpacked = Meter(Stage.INSTALL_STEAM, zips.sumOf { it.length() })
        val installed = LinkedHashMap<String, Installed>()
        for (zip in zips) {
            unzip(zip, steamRoot, unpacked, installed)
            FileUtils.delete(zip)
        }
        writeInstallRecord(steamRoot, manifest, installed.values)
        finishSteam(context, steamRoot, version)
    }

    private fun manifestVersion(manifest: String): String =
        Regex("^\\s*\"version\"\\s+\"([^\"]+)\"", RegexOption.MULTILINE).find(manifest)?.groupValues?.get(1)
            ?: throw IOException("Valve's manifest names no client version")

    /**
     * Every component in the manifest. The client installs them all, and one left out here is one
     * its updater fetches behind a black screen at first launch.
     */
    private fun manifestParts(manifest: String): List<Part> {
        val token = Regex("\"([^\"]*)\"")
        val blocks = linkedMapOf<String, MutableMap<String, String>>()
        var depth = 0
        var block: String? = null
        for (line in manifest.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed == "{" -> depth++
                trimmed == "}" -> {
                    depth--
                    if (depth <= 1) block = null
                }
                else -> {
                    val values = token.findAll(trimmed).map { it.groupValues[1] }.toList()
                    when {
                        values.size == 1 && depth == 1 -> block = values[0]
                        values.size == 2 && depth == 2 && block != null ->
                            blocks.getOrPut(block) { mutableMapOf() }[values[0]] = values[1]
                    }
                }
            }
        }
        val parts =
            blocks
                .mapNotNull { (name, fields) ->
                    val file = fields["file"] ?: return@mapNotNull null
                    val sha256 = fields["sha2"] ?: return@mapNotNull null
                    Part(name, file, fields["size"]?.toLongOrNull() ?: 0L, sha256)
                }
        if (parts.isEmpty()) throw IOException("Valve's manifest lists no client components")
        return parts
    }

    /**
     * Valve's zips start with a short prefix before the first entry, which Android's own zip reader
     * refuses; this one reads them through the central directory. Some entries are packed with
     * Windows separators and are written where the name means. Each entry is dated as the client
     * dates it and noted in [installed]; a directory two components share is noted as the later
     * one has it, which is why the components keep the manifest's order.
     */
    private suspend fun unzip(
        zip: File,
        steamRoot: File,
        meter: Meter,
        installed: MutableMap<String, Installed>,
    ) {
        val rootPath = steamRoot.canonicalPath + File.separator
        ZipFile.builder().setFile(zip).get().use { archive ->
            val entries = archive.entries
            while (entries.hasMoreElements()) {
                coroutineContext.ensureActive()
                val entry = entries.nextElement()
                val name = entry.name.replace('\\', '/')
                val target = File(steamRoot, name)
                if (!target.canonicalPath.startsWith(rootPath)) {
                    throw IOException("${zip.name} has an entry outside the client directory: $name")
                }
                val modified = clientTime(entry.time)
                if (name.endsWith("/")) {
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("Could not create $target")
                    installed[name] = Installed(name, RECORD_DIRECTORY, modified, 0L)
                } else {
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("Could not create $parent")
                    }
                    if (entry.isUnixSymlink) {
                        link(archive.getUnixSymlink(entry), target)
                        installed[name] = Installed(name, RECORD_LINK, modified, entry.crc)
                    } else {
                        archive.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        // The client's updater leaves every file it unpacks executable.
                        target.setExecutable(true, true)
                        target.setLastModified(modified * 1000L)
                        installed[name] = Installed(name, entry.size, modified, entry.crc)
                    }
                }
                meter.add(entry.compressedSize.coerceAtLeast(0L))
            }
        }
    }

    /**
     * The client reads a zip's clock fields as Pacific Standard Time all year round. [zipTime] is
     * those fields already read in this device's zone, so the zone's offset is taken back out.
     */
    private fun clientTime(zipTime: Long): Long =
        (zipTime + TimeZone.getDefault().getOffset(zipTime)) / 1000L + CLIENT_ZONE_SECONDS

    /**
     * What the client's updater leaves in `package/` once it has installed a manifest: the manifest,
     * and every entry it unpacked, ordered without regard to case and closed by a SHA-1 of the lines
     * before it. With both in place the client starts from the files here rather than fetching and
     * unpacking them all again.
     */
    private fun writeInstallRecord(
        steamRoot: File,
        manifest: String,
        installed: Collection<Installed>,
    ) {
        val body =
            buildString {
                for (entry in installed.sortedBy { it.name.uppercase(Locale.ROOT) }) {
                    append(entry.name).append(',').append(entry.size).append(';')
                    append(entry.modified).append(';').append(entry.crc).append('\n')
                }
                append("OSVER=").append(RECORD_OS_VERSION).append("\nVERSION=").append(RECORD_VERSION).append('\n')
            }
        val digest = MessageDigest.getInstance("SHA-1").digest(body.toByteArray(Charsets.UTF_8))
        val sha1 = digest.joinToString("") { "%02X".format(it) }
        val packageDir = File(steamRoot, "package")
        if (!packageDir.isDirectory && !packageDir.mkdirs()) throw IOException("Could not create $packageDir")
        File(packageDir, "$STEAM_MANIFEST.manifest").writeText(manifest)
        File(packageDir, "$STEAM_MANIFEST.installed").writeText("${body}SHA1=$sha1\n")
    }

    /** What winnative-steam-install and Valve's steam.sh set up around the client. */
    private fun finishSteam(
        context: Context,
        steamRoot: File,
        version: String,
    ) {
        val packageDir = File(steamRoot, "package")
        if (!packageDir.isDirectory && !packageDir.mkdirs()) throw IOException("Could not create $packageDir")
        File(packageDir, "beta").writeText("publicbeta\n")
        link("$STEAM_ROOT/steamrtarm64", File(steamRoot, "steamrtarm32"))

        val dotSteam = hostPath(context, "/root/.steam")
        if (!dotSteam.isDirectory && !dotSteam.mkdirs()) throw IOException("Could not create $dotSteam")
        link(STEAM_ROOT, File(dotSteam, "root"))
        link(STEAM_ROOT, File(dotSteam, "steam"))
        link("$STEAM_ROOT/steamrtarm64", File(dotSteam, "bin64"))
        link("$STEAM_ROOT/steamrtarm64", File(dotSteam, "binarm64"))
        link("$STEAM_ROOT/linux64", File(dotSteam, "sdk64"))
        link("$STEAM_ROOT/linux32", File(dotSteam, "sdk32"))
        link("$STEAM_ROOT/linuxarm64", File(dotSteam, "sdkarm64"))
        File(steamRoot, STEAM_STAMP).writeText("$version\n")
    }

    /** A link whose target is a path inside the runtime, which proot resolves against its root. */
    private fun link(
        target: String,
        file: File,
    ) {
        try {
            if (FileUtils.isSymlink(file) || file.exists()) FileUtils.delete(file)
            Os.symlink(target, file.path)
        } catch (e: ErrnoException) {
            throw IOException("Could not link $file to $target", e)
        }
    }

    private fun hostPath(
        context: Context,
        guestPath: String,
    ): File = File(LinuxRuntime.rootDir(context), guestPath.removePrefix("/"))

    private fun requireSpace(
        dir: File,
        bytes: Long,
    ) {
        val needed = bytes + SPACE_MARGIN
        val available = StatFs(dir.path).availableBytes
        if (available < needed) throw NoSpaceException(needed, available)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WinNative")
        }

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $url")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads [url] into [target] and returns the SHA-256 of what arrived. */
    private suspend fun download(
        url: String,
        target: File,
        meter: Meter,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $url")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        meter.add(read.toLong())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Publishes one stage's progress, no more often than the dialog can show it. */
    private class Meter(
        private val stage: Stage,
        private val total: Long,
    ) {
        private var done = 0L
        private var published = 0L

        init {
            mutableState.value = State.Working(stage, 0, total)
        }

        fun add(bytes: Long) {
            done += bytes
            val now = SystemClock.uptimeMillis()
            if (now - published >= PROGRESS_INTERVAL_MS || done >= total) {
                published = now
                mutableState.value = State.Working(stage, done.coerceAtMost(total), total)
            }
        }
    }
}
