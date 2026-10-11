package com.winlator.cmod.runtime.linux

import android.content.Context
import android.system.Os
import android.util.Log
import com.winlator.cmod.R
import com.winlator.cmod.feature.library.LinuxApps
import com.winlator.cmod.feature.stores.steam.utils.KeyValue
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

object LinuxProtons {
    data class Build(val id: String, val name: String, val url: String, val sha256: String, val size: Long) {
        val displayName: String get() = when {
            id.startsWith("GE-Proton") -> "GE-Proton ${id.removePrefix("GE-Proton").removeSuffix("-aarch64")} (ARM64)"
            id.startsWith("proton-cachyos-") -> "CachyOS Proton ${id.removePrefix("proton-cachyos-").removeSuffix("-slr-arm64")}"
            else -> name
        }
        fun json() = JSONObject().put("id", id).put("name", name).put("url", url).put("sha256", sha256).put("size", size)
    }
    data class State(val builds: List<Build> = emptyList(), val installed: Set<String> = emptySet(), val loading: Boolean = false, val working: String? = null, val progress: Float = Float.NaN, val stage: Int = R.string.common_ui_downloading_file, val failed: Boolean = false, val removing: Boolean = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val sources = listOf("GloriousEggroll/proton-ge-custom" to "aarch64", "CachyOS/proton-cachyos" to "arm64")
    private const val TAG = "LinuxProtons"
    private const val MARKER = "winnative-proton.json"
    private const val CHOICES = "etc/winnative/proton-choices"
    private val TOOL_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")
    private val choicesLock = Any()
    private const val ORIGINAL_MANIFEST = "toolmanifest.vdf.winnative-original"
    private const val WRAPPER = "winnative-proton-wrap"
    private const val TOOL_MANIFEST = """"manifest"
{
    "version"        "2"
    "commandline"        "/winnative-proton-wrap %verb%"
    "use_sessions"        "1"
    "compatmanager_layer_name"        "proton"
}
"""
    private val WRAPPER_SCRIPT = """#!/bin/sh
here=${'$'}(dirname "${'$'}0")
exec /usr/local/bin/winnative-proton-launch "${'$'}here/proton" "${'$'}@"
"""

    fun directory(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/compatibilitytools.d")
    private fun installed(context: Context) = directory(context).listFiles().orEmpty().mapNotNull { file ->
        runCatching { parse(JSONObject(File(file, MARKER).readText())) }.getOrNull()?.takeIf { it.id == file.name }
    }
    /**
     * Worker thread. What a GameScope game can be set to run under, as tool name to label: WinNative's
     * own Proton Experimental first, then the builds downloaded from Components.
     */
    fun choices(context: Context): List<Pair<String, String>> =
        listOf(Container.LINUX_PROTON_DEFAULT to context.getString(R.string.gamescope_proton_default)) +
            installed(context).sortedBy { it.displayName }.map { it.id to it.displayName }

    /**
     * Worker thread. Settles each GameScope game's Proton between the app and the Steam client,
     * whichever changed it last, and writes what `winnative-proton-launch` reads as the game starts.
     *
     * A game's line keeps the tool the client mapped when the line was written. While the client
     * still maps that tool, the app's choice is the newer one and the launcher follows it; once the
     * client maps something else, the change was made in Steam and the shortcut takes it. With no
     * client running, the app's choice is also written into the client's mapping so Steam shows it.
     * The container's `*` covers games with no shortcut that the client leaves on WinNative's own.
     *
     * Returns each game's tool by app id.
     */
    fun reconcile(context: Context): Map<String, String> = synchronized(choicesLock) {
        if (!LinuxRuntime.isInstalled(context)) return emptyMap()
        val rootfs = LinuxRuntime.rootDir(context)
        val file = File(rootfs, CHOICES)
        val chosen = HashMap<String, String>()
        try {
            val container = LinuxApps.gamescopeContainer(ContainerManager(context))
            if (container == null) {
                file.delete()
                return emptyMap()
            }
            val config = File(LinuxSteamVdf.steamRoot(rootfs), "config/config.vdf")
            val tree = if (config.isFile) LinuxSteamVdf.load(config, "InstallConfigStore") else null
            val mapping = tree?.let { LinuxSteamVdf.section(it, LinuxSteamVdf.STEAM_KEY + "CompatToolMapping") }
            val recorded = readChoices(file)
            val clientIdle = !SessionKeepAliveService.isLinuxSessionActive()
            val fallback = container.getLinuxProton()
            val lines = ArrayList<String>()
            if (fallback != Container.LINUX_PROTON_DEFAULT && fallback.matches(TOOL_NAME)) lines += "* $fallback"
            var pushed = false
            for (entry in container.desktopDir.listFiles { f -> f.name.endsWith(".desktop") }.orEmpty()) {
                val shortcut = Shortcut(container, entry)
                if (LinuxApps.isLinuxShortcut(shortcut)) continue
                val appId = LinuxSteamShortcuts.clientAppId(context, shortcut)?.toString() ?: continue
                val own = if (shortcut.usesContainerDefaults()) "" else shortcut.getExtra(Container.EXTRA_LINUX_PROTON)
                var tool = own.ifEmpty { fallback }
                // Steam's "Default" deletes the game's entry, which leaves it on the "0" one.
                val mapped = mapping?.let { toolOf(it[appId]) ?: toolOf(it["0"]) ?: Container.LINUX_PROTON_DEFAULT }
                val base = recorded[appId]
                val changedInClient =
                    if (base != null) mapped != null && mapped != base
                    // Nothing recorded yet: a tool the user picked in Steam outranks a default.
                    else mapped != null && mapped != Container.LINUX_PROTON_DEFAULT && own.isEmpty()
                if (changedInClient && mapped != tool) {
                    tool = mapped!!
                    if (tool == fallback) {
                        shortcut.putExtra(Container.EXTRA_LINUX_PROTON, null)
                    } else {
                        shortcut.putExtra(Container.EXTRA_LINUX_PROTON, tool)
                        shortcut.putExtra("use_container_defaults", "0")
                    }
                    shortcut.saveData()
                }
                if (!tool.matches(TOOL_NAME)) continue
                var current = mapped ?: Container.LINUX_PROTON_DEFAULT
                if (clientIdle && mapping != null && current != tool) {
                    val item = mapping[appId].takeIf { it !== KeyValue.INVALID } ?: KeyValue(appId).apply {
                        isSection = true
                        children += KeyValue("name", tool)
                        children += KeyValue("config", "")
                        children += KeyValue("priority", "250")
                        mapping.children += this
                    }
                    val name = item["name"]
                    if (name === KeyValue.INVALID) item.children += KeyValue("name", tool) else name.value = tool
                    current = tool
                    pushed = true
                }
                chosen[appId] = tool
                lines += "$appId $tool $current"
            }
            if (pushed && tree != null) LinuxSteamVdf.save(config, tree)
            if (lines.isEmpty()) {
                file.delete()
            } else {
                LinuxSteamVdf.replace(file) { it.writeText(lines.joinToString("\n", postfix = "\n")) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not settle the Proton choices", error)
        }
        chosen
    }

    private fun toolOf(entry: KeyValue): String? = entry["name"].value?.takeIf { it.matches(TOOL_NAME) }

    /** What the last [reconcile] recorded for each game: the tool the client had mapped then. */
    private fun readChoices(file: File): Map<String, String> =
        runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val fields = line.split(' ')
            if (fields.size == 3 && fields[0] != "*") fields[0] to fields[2] else null
        }.toMap()

    /** [reconcile] off the caller's thread, for a settings screen that has just saved. */
    fun updateChoices(context: Context) {
        val app = context.applicationContext
        Thread({ reconcile(app) }, "LinuxProtonChoices").start()
    }

    /**
     * Worker thread. The tool [shortcut] runs its game under once the client's side is taken into
     * account, or null when it is not a game the client starts.
     */
    fun choiceFor(context: Context, shortcut: Shortcut): String? {
        val appId = LinuxSteamShortcuts.clientAppId(context, shortcut)?.toString() ?: return null
        return reconcile(context)[appId]
    }

    private fun parse(json: JSONObject) = Build(json.getString("id"), json.getString("name"), json.getString("url"), json.getString("sha256"), json.getLong("size"))

    @Synchronized
    fun refresh(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        mutable.value = mutable.value.copy(loading = true, failed = false)
        job = scope.launch {
            // An install the system killed part-way leaves its half-unpacked tree behind, and
            // nothing else ever looks at it again: a couple of gigabytes that no screen shows.
            directory(app).listFiles().orEmpty()
                .filter { it.name.startsWith(".") && (it.name.endsWith(".winnative-removing") || it.name.endsWith(".staging")) }
                .forEach { it.deleteRecursively() }
            val local = installed(app)
            val results = sources.map { (repo, arch) -> runCatching { fetch(repo, arch) } }
            val builds = (results.flatMap { it.getOrDefault(emptyList()) } + local).distinctBy { it.id }
            mutable.value = State(builds = builds, installed = local.map { it.id }.toSet(), failed = results.any { it.isFailure })
        }
    }

    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 30000
        setRequestProperty("User-Agent", "WinNative")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun fetch(repo: String, arch: String): List<Build> {
        val connection = connection("https://api.github.com/repos/$repo/releases?per_page=30")
        val releases = try { JSONArray(connection.inputStream.bufferedReader().use { it.readText() }) } finally { connection.disconnect() }
        val result = mutableListOf<Build>()
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val assets = release.getJSONArray("assets")
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                val name = asset.getString("name")
                if (!name.matches(Regex("[A-Za-z0-9._-]+-$arch\\.tar\\.(gz|xz)"))) continue
                val digest = asset.optString("digest").removePrefix("sha256:")
                if (!digest.matches(Regex("[a-fA-F0-9]{64}"))) continue
                val id = name.substringBefore(".tar.")
                result += Build(id, id, asset.getString("browser_download_url"), digest, asset.getLong("size"))
            }
            if (result.size >= 3) break
        }
        check(result.isNotEmpty())
        return result.take(3)
    }

    @Synchronized
    fun install(context: Context, build: Build) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        mutable.value = mutable.value.copy(working = build.id, progress = Float.NaN, failed = false, stage = R.string.common_ui_downloading_file)
        job = scope.launch {
            val archive = File(app.cacheDir, "linux-proton-${build.id}.part")
            SessionKeepAliveService.startDownload(app, "linux_proton")
            try {
                check(LinuxRuntime.isInstalled(app))
                // What the tree unpacks to, against the archive it unpacks from: measured at 3.4x
                // for the gzip builds and 6.5x for the xz ones, and both are on disk at once.
                val expansion = if (build.url.endsWith(".xz")) 8L else 5L
                check(app.filesDir.usableSpace > build.size * expansion + (512L shl 20))
                val connection = connection(build.url)
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var updated = 0L
                try {
                    connection.inputStream.use { input -> archive.outputStream().buffered().use { output ->
                        val buffer = ByteArray(131072)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            done += count
                            check(done <= build.size)
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - updated > 150) {
                                mutable.value = mutable.value.copy(progress = done.toFloat() / build.size)
                                updated = now
                            }
                        }
                    } }
                } finally { connection.disconnect() }
                check(done == build.size && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }.equals(build.sha256, true))
                mutable.value = mutable.value.copy(stage = R.string.linux_client_stage_install_proton, progress = Float.NaN)
                installArchive(app, build, archive)
                mutable.value = mutable.value.copy(installed = installed(app).map { it.id }.toSet(), working = null)
            } catch (error: CancellationException) {
                mutable.value = mutable.value.copy(working = null)
                throw error
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(working = null, failed = true)
            } finally {
                archive.delete()
                SessionKeepAliveService.stopDownload(app, "linux_proton")
            }
        }
    }

    internal suspend fun installArchive(context: Context, build: Build, archive: File) {
        require(build.id.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")))
        val tools = directory(context).apply { mkdirs() }
        val staging = File(tools, ".${build.id}.staging")
        staging.deleteRecursively()
        check(staging.mkdirs())
        try {
            val links = mutableListOf<Triple<File, String, Boolean>>()
            val root = staging.canonicalFile.toPath()
            var unpacked = 0L
            archive.inputStream().buffered().use { input ->
                val decoded = if (build.url.endsWith(".xz")) XZCompressorInputStream(input) else GzipCompressorInputStream(input)
                TarArchiveInputStream(decoded).use { tar ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = tar.nextTarEntry ?: break
                        require(!entry.name.startsWith("/"))
                        val output = File(staging, entry.name).canonicalFile
                        require(output.toPath().startsWith(root))
                        require(output != staging.canonicalFile || entry.isDirectory)
                        when {
                            entry.isDirectory -> check(output.isDirectory || output.mkdirs())
                            entry.isSymbolicLink || entry.isLink -> links += Triple(output, entry.linkName, entry.isLink)
                            entry.isFile -> {
                                unpacked += entry.size
                                check(unpacked <= build.size * 8 + (1L shl 30))
                                output.parentFile!!.mkdirs()
                                output.outputStream().buffered().use { tar.copyTo(it) }
                                Os.chmod(output.path, entry.mode and 511)
                            }
                            else -> error("Unsupported archive entry")
                        }
                    }
                }
            }
            for ((output, target, hard) in links.sortedByDescending { it.third }) {
                require(!File(target).isAbsolute)
                val resolved = File(if (hard) staging else output.parentFile!!, target).canonicalFile
                require(resolved.toPath().startsWith(root))
                require(!output.exists())
                output.parentFile!!.mkdirs()
                if (hard) Os.link(resolved.path, output.path) else Os.symlink(target, output.path)
            }
            val tree = staging.listFiles().orEmpty().single { it.isDirectory && File(it, "proton").isFile }
            val wine = listOf("files/bin-arm64/wine", "files/bin/wine", "files/bin/wine64").map { File(tree, it) }.firstOrNull { file ->
                if (!file.isFile) false else file.inputStream().use { input ->
                    val header = ByteArray(20)
                    input.read(header) == 20 && header.take(4) == listOf<Byte>(127, 69, 76, 70) && header[4] == 2.toByte() && (header[18].toInt() and 255) == 183 && header[19] == 0.toByte()
                }
            }
            check(wine != null)
            val manifest = File(tree, "toolmanifest.vdf")
            check(manifest.isFile && File(tree, "compatibilitytool.vdf").isFile)
            val original = File(tree, ORIGINAL_MANIFEST)
            if (!original.isFile) manifest.copyTo(original, overwrite = false)
            check(Regex(""""commandline"\s+"/proton(?:\s+[^"]*)?"""").containsMatchIn(original.readText()))
            File(tree, WRAPPER).writeText(WRAPPER_SCRIPT)
            Os.chmod(File(tree, WRAPPER).path, 493)
            manifest.writeText(TOOL_MANIFEST)
            File(tree, MARKER).writeText(build.json().toString())
            val target = File(tools, build.id)
            check(!target.exists() && tree.renameTo(target))
        } finally { staging.deleteRecursively() }
    }

    @Synchronized
    fun remove(context: Context, build: Build): Job? {
        if (job?.isActive == true) return null
        val app = context.applicationContext
        mutable.value = mutable.value.copy(working = build.id, progress = Float.NaN, stage = R.string.common_ui_remove, failed = false, removing = true)
        job = scope.launch {
            try {
                check(installed(app).any { it.id == build.id })
                val target = File(directory(app), build.id)
                val retired = File(directory(app), ".${build.id}.winnative-removing")
                check(!retired.exists() || retired.deleteRecursively())
                check(target.renameTo(retired))
                check(retired.deleteRecursively())
                mutable.value = mutable.value.copy(installed = installed(app).map { it.id }.toSet(), working = null, removing = false)
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(installed = installed(app).map { it.id }.toSet(), working = null, removing = false, failed = true)
            }
        }
        return job
    }

    fun cancel() { if (!mutable.value.removing) job?.cancel() }
}
