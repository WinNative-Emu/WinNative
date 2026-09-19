package com.winlator.cmod.runtime.linux

import android.content.Context
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
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import com.winlator.cmod.shared.util.OnExtractFileListener
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
 * published as a release asset of WinNative's Components repository, then the arm64 Steam client
 * from Valve's update servers, and finally the GameScope container with its Steam library entry.
 * Valve's client is not redistributable, so it always comes from Valve. winnative-steam-install performs the same client steps inside a session and skips them
 * once the stamp written here exists.
 */
object LinuxClientInstaller {
    private const val TAG = "LinuxClientInstaller"
    private const val RELEASE = "https://github.com/WinNative-Emu/Components/releases/download/Assets"
    private const val RUNTIME_ARCHIVE = "$RELEASE/linuxfs.tar.zst"
    private const val RUNTIME_INFO = "$RELEASE/linuxfs.json"
    private const val STEAM_CDN = "https://client-update.fastly.steamstatic.com"
    private const val STEAM_MANIFEST = "steam_client_publicbeta_linuxarm64"
    private const val STEAM_ROOT = "/root/.local/share/Steam"
    private const val STEAM_STAMP = "package/winnative-installed"
    private const val WORK_DIR = "linux-client-download"
    private const val STAGING_DIR = "linuxfs.staging"
    private const val RETIRED_DIR = "linuxfs.old"
    private const val SPACE_MARGIN = 512L shl 20
    private const val STEAM_UNPACK_FACTOR = 3L
    private const val PROGRESS_INTERVAL_MS = 100L

    enum class Stage { CONNECT, DOWNLOAD_RUNTIME, INSTALL_RUNTIME, DOWNLOAD_STEAM, INSTALL_STEAM, LIBRARY }

    sealed interface State {
        data object Checking : State

        data object Missing : State

        data object Installed : State

        data class Working(val stage: Stage, val done: Long, val total: Long) : State

        data object Failed : State

        data class NoSpace(val needed: Long, val available: Long) : State

        /** The GameScope container cannot be created; [message] says what is missing. */
        data class Blocked(@StringRes val message: Int) : State
    }

    private class NoSpaceException(val needed: Long, val available: Long) : IOException()

    private class BlockedException(@StringRes val messageRes: Int) : IOException()

    private class Part(val name: String, val file: String, val size: Long, val sha256: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val mutableState = MutableStateFlow<State>(State.Checking)
    private var generation = 0
    private var job: Job? = null

    val state: StateFlow<State> = mutableState.asStateFlow()

    val isWorking: Boolean get() = mutableState.value is State.Working

    /** Worker thread. */
    fun isInstalled(context: Context): Boolean =
        LinuxRuntime.isInstalled(context) && isSteamInstalled(context) && !LinuxApps.isSteamShortcutMissing(context)

    /** Reads what is on disk, off the calling thread, unless an install is running. */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val seen = synchronized(lock) { if (isWorking) return else generation }
        scope.launch {
            val found = if (isInstalled(appContext)) State.Installed else State.Missing
            synchronized(lock) { if (generation == seen && !isWorking) mutableState.value = found }
        }
    }

    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (isWorking) return
            generation++
            mutableState.value = State.Working(Stage.CONNECT, 0, 0)
            job = scope.launch { install(appContext) }
        }
    }

    fun cancel() {
        synchronized(lock) { job?.cancel() }
    }

    private suspend fun install(context: Context) {
        val work = File(context.filesDir, WORK_DIR)
        val outcome: State =
            try {
                FileUtils.delete(work)
                if (!work.mkdirs()) throw IOException("Could not create $work")
                // Checked first, so a device that cannot hold the container is told before the download.
                if (gamescopeContainer(context) == null) requireSystemImage(context)
                if (!LinuxRuntime.isInstalled(context)) installRuntime(context, work)
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

    private fun publishSettled(outcome: State) {
        synchronized(lock) { mutableState.value = outcome }
    }

    private fun discard(
        context: Context,
        work: File,
    ) {
        FileUtils.delete(work)
        FileUtils.delete(File(context.filesDir, STAGING_DIR))
    }

    private suspend fun installRuntime(
        context: Context,
        work: File,
    ) {
        val info = JSONObject(fetchText(RUNTIME_INFO))
        val sha256 = info.getString("sha256")
        val size = info.getLong("size")
        val unpacked = info.getLong("unpacked")
        requireSpace(context.filesDir, size + unpacked)

        val archive = File(work, "linuxfs.tar.zst")
        val downloaded = Meter(Stage.DOWNLOAD_RUNTIME, size)
        if (!download(RUNTIME_ARCHIVE, archive, downloaded).equals(sha256, ignoreCase = true)) {
            throw IOException("The runtime archive does not match its published checksum")
        }

        val staging = File(context.filesDir, STAGING_DIR)
        FileUtils.delete(staging)
        if (!staging.mkdirs()) throw IOException("Could not create $staging")
        val written = Meter(Stage.INSTALL_RUNTIME, unpacked)
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
        if (!extracted) throw IOException("The runtime archive could not be unpacked")
        FileUtils.delete(archive)
        replaceRootfs(context, staging)
    }

    /** Swaps the unpacked tree in, keeping the home directory of a runtime being replaced. */
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
        if (!root.renameTo(retired)) throw IOException("Could not move the old runtime aside")
        val home = File(retired, "root")
        if (home.isDirectory) {
            val fresh = File(staging, "root")
            FileUtils.delete(fresh)
            if (!home.renameTo(fresh)) throw IOException("Could not keep the runtime's home directory")
        }
        if (!staging.renameTo(root)) {
            retired.renameTo(root)
            throw IOException("Could not move the runtime into place")
        }
        FileUtils.delete(retired)
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
        for (zip in zips) {
            unzip(zip, steamRoot, unpacked)
            FileUtils.delete(zip)
        }
        finishSteam(context, steamRoot, version)
    }

    private fun manifestVersion(manifest: String): String =
        Regex("^\\s*\"version\"\\s+\"([^\"]+)\"", RegexOption.MULTILINE).find(manifest)?.groupValues?.get(1)
            ?: throw IOException("Valve's manifest names no client version")

    /** The native client's components: every `*_all` block and every `*_linuxarm64_linuxarm64` one. */
    private fun manifestParts(manifest: String): List<Part> {
        val token = Regex("\"([^\"]*)\"")
        val blocks = sortedMapOf<String, MutableMap<String, String>>()
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
                .filterKeys { it.endsWith("_all") || it.endsWith("_linuxarm64_linuxarm64") }
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
     * Windows separators and are written where the name means.
     */
    private suspend fun unzip(
        zip: File,
        steamRoot: File,
        meter: Meter,
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
                if (name.endsWith("/")) {
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("Could not create $target")
                } else {
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("Could not create $parent")
                    }
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                meter.add(entry.compressedSize.coerceAtLeast(0L))
            }
        }
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
        File(steamRoot, "steamrtarm64/steam").setExecutable(true, false)
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
