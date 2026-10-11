package com.winlator.cmod.feature.library

import android.content.Context
import android.util.Log
import com.winlator.cmod.R
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.storage.ExternalStorage
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.steam.data.AppInfo
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.service.configuredDownloadRoot
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.utils.SteamUtils
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.android.StoragePathUtils
import com.winlator.cmod.shared.io.FileUtils
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

object LinuxSteamLibrary {
    const val KEY_CLIENT_INSTALL = "linux_client_install"
    private const val TAG = "LinuxSteamLibrary"
    private const val CLIENT_STEAMAPPS = "root/.local/share/Steam/steamapps"
    private const val LEGACY_STEAMAPPS = "mnt/winnative/steamapps"
    private const val GUEST_LIBRARIES = "/mnt/winnative-lib"
    private const val HOST_LIBRARY = ".winnative-steam"
    private const val LIBRARY_LIST = "etc/winnative/steam-libraries"
    private const val STATE_FULLY_INSTALLED = 4
    private const val STATE_UNINSTALLING = 2048
    private val BUILD_ID = Regex("^\\s*\"buildid\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val STATE_FLAGS = Regex("^\\s*\"StateFlags\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val INSTALL_DIR = Regex("^(\\s*\"installdir\"\\s*\")([^\"]*)(\")", RegexOption.MULTILINE)
    private val APP_NAME = Regex("^\\s*\"name\"\\s*\"([^\"]+)\"", RegexOption.MULTILINE)
    private val MANIFEST_NAME = Regex("^appmanifest_(\\d+)\\.acf$")
    private val DEPOT_MANIFEST = Regex("\"(\\d+)\"\\s*\\{[^{}]*?\"manifest\"\\s*\"(\\d+)\"")
    private val TOOL_APP_IDS = setOf(228980, 1070560, 1391110, 1493710, 3127680, 4183110, 4185400, 4427310, 4628740)
    private val lock = Any()

    private class Library(
        val root: File,
        val steamapps: File,
    )

    private class Drive(
        val root: File,
        val label: String,
    )

    @JvmStatic
    fun prepare(
        context: Context,
        rootfs: File,
    ): List<String> =
        synchronized(lock) {
            val legacy = File(rootfs, LEGACY_STEAMAPPS)
            val compatdata = File(legacy, "compatdata")
            val shadercache = File(legacy, "shadercache")
            if (!ensureDir(compatdata) || !ensureDir(shadercache)) return emptyList()
            val recorded = recordedAppIds(context)
            val games = LinkedHashMap<Int, File>()
            for (appId in recorded) {
                if (!SteamService.isAppInstalled(appId)) continue
                val dir = File(SteamService.getAppDirPath(appId)).absoluteFile
                if (dir.isDirectory) games[appId] = dir
            }
            val preferred = preferredRoot()
            val drives = connectedDrives(context)
            val clientCommon = File(rootfs, "$CLIENT_STEAMAPPS/common")
            val legacyCommon = File(legacy, "common")
            val candidates = candidateRoots(context, preferred, drives, games.values.mapNotNull { it.parentFile } + legacyCommon)
            val known =
                buildList {
                    add(Library(clientCommon, File(rootfs, CLIENT_STEAMAPPS)))
                    add(Library(legacyCommon, legacy))
                    candidates.forEach { add(Library(it, hostSteamapps(it))) }
                }
            val retired = Library(legacyCommon, legacy)
            for (manifest in legacy.listFiles().orEmpty()) {
                val appId = MANIFEST_NAME.find(manifest.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (appId in games) continue
                if (isStale(retired, manifest, null) || !isInstalled(manifest)) continue
                val target = File(hostSteamapps(legacyCommon), manifest.name)
                if (!ensureDir(target.parentFile!!)) continue
                runCatching { if (!target.isFile) manifest.copyTo(target) }
                    .onSuccess { manifest.delete() }
                    .onFailure { Log.w(TAG, "Could not carry the manifest of $appId out of the retired library", it) }
            }
            val language = PrefManager.containerLanguage.ifBlank { "english" }
            val prefixManifests = File(ImageFs.find(context).wineprefix, "drive_c/Program Files (x86)/Steam/steamapps")
            for ((appId, gameDir) in games) {
                val root = gameDir.parentFile ?: continue
                if (StoragePathUtils.samePath(root, clientCommon)) continue
                val home = Library(root, hostSteamapps(root))
                if (!ensureDir(home.steamapps)) continue
                val name = manifestName(appId)
                val runtimeManifest = File(home.steamapps, name)
                val strays =
                    known
                        .filterNot { StoragePathUtils.samePath(it.steamapps, home.steamapps) }
                        .mapNotNull { library ->
                            File(library.steamapps, name).takeIf { isStale(library, it, gameDir) }
                        }
                if (!runtimeManifest.isFile) {
                    strays.maxByOrNull(::buildId)?.let { stray ->
                        runCatching { stray.copyTo(runtimeManifest, overwrite = true) }
                            .onFailure { Log.w(TAG, "Could not carry the manifest of $appId to $root", it) }
                    }
                }
                for (stray in strays) {
                    claimPrefix(appId, stray.parentFile, compatdata)
                    stray.delete()
                }
                val runtimeBuild = buildId(runtimeManifest)
                if (runtimeBuild > 0L && runtimeBuild >= PrefManager.getInstalledBuildId(appId)) {
                    adopt(appId, runtimeManifest, runtimeBuild, gameDir)
                }
                SteamUtils.createAppManifest(context, appId, language)
                val manifest = File(prefixManifests, name)
                if (manifest.isFile && (runtimeBuild == 0L || buildId(manifest) > runtimeBuild)) {
                    runCatching { manifest.copyTo(runtimeManifest, overwrite = true) }
                        .onFailure { Log.w(TAG, "Could not write the manifest of $appId", it) }
                }
                if (runtimeManifest.isFile) setInstallDir(runtimeManifest, gameDir.name)
            }
            for (library in known) {
                if (StoragePathUtils.samePath(library.root, clientCommon)) continue
                for (manifest in library.steamapps.listFiles() ?: continue) {
                    val appId = MANIFEST_NAME.find(manifest.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (appId !in games && isStale(library, manifest, null)) manifest.delete()
                }
            }
            legacyCommon.listFiles()?.forEach { if (it.isDirectory) it.delete() }
            val active = LinkedHashSet<File>()
            if (hostSteamapps(legacyCommon).isDirectory) active.add(legacyCommon)
            if (ensureDir(preferred)) active.add(preferred)
            drives.forEach { if (ensureDir(it.root)) active.add(it.root) }
            games.values.mapNotNull { it.parentFile }
                .filterNot { StoragePathUtils.samePath(it, clientCommon) }
                .forEach { active.add(it) }
            candidates.filter { it.isDirectory && hostSteamapps(it).isDirectory }.forEach { active.add(it) }
            val binds = ArrayList<String>()
            val listed = StringBuilder()
            for (root in active.distinctBy { key(it) }) {
                val steamapps = hostSteamapps(root)
                val mounts = listOf("common", "compatdata", "shadercache").map { File(steamapps, it) }
                if (!mounts.all(::ensureDir)) continue
                val guest = "$GUEST_LIBRARIES/${key(root)}"
                if (!ensureDir(File(rootfs, guest.removePrefix("/")))) continue
                binds.add("${steamapps.parentFile!!.path}:$guest")
                binds.add("${root.path}:$guest/steamapps/common")
                binds.add("${compatdata.path}:$guest/steamapps/compatdata")
                binds.add("${shadercache.path}:$guest/steamapps/shadercache")
                listed.append(guest).append('\t').append(labelOf(context, root, preferred, drives)).append('\n')
            }
            val list = File(rootfs, LIBRARY_LIST)
            val staged = File(list.path + ".tmp")
            runCatching {
                check(ensureDir(list.parentFile!!)) { "no ${list.parent}" }
                FileUtils.writeString(staged, listed.toString())
                if (!staged.renameTo(list)) error("rename failed")
            }.onFailure { Log.w(TAG, "Could not publish the Steam library list", it) }
            return binds
        }

    @JvmStatic
    fun adoptClientInstalls(
        context: Context,
        rootfs: File,
    ): Boolean =
        synchronized(lock) {
            val manager = ContainerManager(context)
            val container = LinuxApps.gamescopeContainer(manager) ?: return false
            PrefManager.init(context)
            val recordedDirs =
                recordedAppIds(context).mapNotNull { appId ->
                    File(SteamService.getAppDirPath(appId)).takeIf { it.isDirectory }
                }
            val libraries =
                buildList {
                    add(Library(File(rootfs, "$CLIENT_STEAMAPPS/common"), File(rootfs, CLIENT_STEAMAPPS)))
                    add(Library(File(rootfs, "$LEGACY_STEAMAPPS/common"), File(rootfs, LEGACY_STEAMAPPS)))
                    val roots = recordedDirs.mapNotNull { it.parentFile } + File(rootfs, "$LEGACY_STEAMAPPS/common")
                    candidateRoots(context, preferredRoot(), connectedDrives(context), roots)
                        .forEach { add(Library(it, hostSteamapps(it))) }
                }
            val present = HashSet<Int>()
            var changed = false
            for (library in libraries) {
                val manifests = library.steamapps.listFiles() ?: continue
                for (manifest in manifests) {
                    val appId = MANIFEST_NAME.find(manifest.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (appId in TOOL_APP_IDS) continue
                    val text = runCatching { manifest.readText() }.getOrNull() ?: continue
                    val flags = STATE_FLAGS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if ((flags and STATE_FULLY_INSTALLED) == 0 || (flags and STATE_UNINSTALLING) != 0) continue
                    val installDir = INSTALL_DIR.find(text)?.groupValues?.get(2)?.ifBlank { null } ?: continue
                    val gameDir = File(library.root, installDir)
                    if (!gameDir.isDirectory || gameDir.list().isNullOrEmpty()) continue
                    present.add(appId)
                    if (SteamService.isAppInstalled(appId)) continue
                    val name =
                        SteamService.getAppInfoOf(appId)?.name?.ifBlank { null }
                            ?: APP_NAME.find(text)?.groupValues?.get(1)
                            ?: installDir
                    if (!record(context, appId, gameDir, buildId(manifest))) continue
                    relink(manager, appId, gameDir)
                    writeEntry(container, appId, name, gameDir)
                    Log.i(TAG, "Adopted the client's install of $appId at $gameDir")
                    changed = true
                }
            }
            val entries = container.desktopDir.listFiles { f -> f.name.endsWith(".desktop") } ?: return changed
            val storage = ExternalStorage.state.value
            for (file in entries) {
                val shortcut = Shortcut(container, file)
                if (shortcut.getExtra(KEY_CLIENT_INSTALL) != "1") continue
                val appId = shortcut.getExtra("app_id").toIntOrNull() ?: continue
                if (appId in present || SteamService.isAppInstalled(appId)) continue
                val recordedPath = SteamService.getInstalledApp(appId)?.installPath.orEmpty()
                if (recordedPath.isNotEmpty() && storage.isOnDisconnectedDrive(recordedPath)) continue
                if (release(context, appId) && file.delete()) {
                    Log.i(TAG, "Dropped the entry for $appId, which the client no longer has installed")
                    changed = true
                }
            }
            return changed
        }

    @JvmStatic
    fun relink(
        manager: ContainerManager,
        appId: Int,
        gameDir: File,
    ) {
        val path = gameDir.absolutePath
        synchronized(lock) {
            for (shortcut in manager.loadShortcuts()) {
                if (!shortcut.getExtra("game_source").equals(InstallStore.STEAM.id, ignoreCase = true)) continue
                if (shortcut.getExtra("app_id").toIntOrNull() != appId) continue
                if (shortcut.getExtra("game_install_path") == path) continue
                shortcut.putExtra("game_install_path", path)
                shortcut.saveData()
            }
        }
    }

    private fun recordedAppIds(context: Context): List<Int> =
        runCatching {
            runBlocking(Dispatchers.IO) { PluviaDatabase.getInstance(context).appInfoDao().getAllInstalledAppIds() }
        }.getOrElse {
            Log.w(TAG, "Installed Steam games unavailable", it)
            emptyList()
        }

    private fun preferredRoot(): File {
        val chosen = runCatching { SteamService.defaultAppInstallPath }.getOrNull().orEmpty()
        val root = File(chosen.ifBlank { SteamService.internalAppInstallPath }).absoluteFile
        return if (root.isDirectory || root.mkdirs()) root else File(SteamService.internalAppInstallPath).absoluteFile
    }

    private fun connectedDrives(context: Context): List<Drive> {
        if (!ExternalStorage.state.value.scanned) {
            runCatching { runBlocking { ExternalStorage.refreshNow(context) } }
                .onFailure { Log.w(TAG, "External drives unavailable", it) }
        }
        return ExternalStorage.state.value.connectedDrives.map { status ->
            Drive(
                File(ExternalStorage.storeInstallRoot(status.drive.downloadPath, InstallStore.STEAM)).absoluteFile,
                status.drive.label,
            )
        }
    }

    private fun candidateRoots(
        context: Context,
        preferred: File,
        drives: List<Drive>,
        roots: Collection<File>,
    ): List<File> {
        val found = ArrayList<File>()
        fun add(path: String?) {
            val normalized = StoragePathUtils.normalizePath(path).trimEnd('/')
            if (normalized.isNotEmpty()) found.add(File(normalized))
        }
        add(preferred.path)
        drives.forEach { add(it.root.path) }
        SteamService.allInstallPaths.forEach(::add)
        add(SteamService.configuredDownloadRoot())
        add(LibraryStorageMove.appStorageRoot(context)?.path)
        roots.forEach { add(it.path) }
        return found.distinctBy { key(it) }
    }

    private fun hostSteamapps(root: File): File = File(root, "$HOST_LIBRARY/steamapps")

    private fun manifestName(appId: Int): String = "appmanifest_$appId.acf"

    private fun key(root: File): String {
        val normalized = StoragePathUtils.normalizePath(root.path).trimEnd('/')
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun labelOf(
        context: Context,
        root: File,
        preferred: File,
        drives: List<Drive>,
    ): String {
        if (StoragePathUtils.samePath(root, preferred)) return context.getString(R.string.linux_steam_library_preferred)
        val place =
            drives.firstOrNull { StoragePathUtils.samePath(it.root, root) }?.label
                ?: when {
                    StoragePathUtils.isSameOrDescendant(root, context.dataDir) ->
                        context.getString(R.string.linux_steam_library_internal)
                    root.name.isBlank() || root.name.equals("common", ignoreCase = true) ->
                        context.getString(R.string.linux_steam_library_device_storage)
                    else -> root.name
                }
        return context.getString(R.string.linux_steam_library_named, place).replace('\t', ' ').replace('\n', ' ')
    }

    private fun ensureDir(dir: File): Boolean = dir.isDirectory || dir.mkdirs()

    private fun isStale(
        library: Library,
        manifest: File,
        gameDir: File?,
    ): Boolean {
        if (!manifest.isFile) return false
        val text = runCatching { manifest.readText() }.getOrNull() ?: return false
        val flags = STATE_FLAGS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        if ((flags and STATE_FULLY_INSTALLED) == 0) return false
        val installDir = INSTALL_DIR.find(text)?.groupValues?.get(2)?.ifBlank { null } ?: return false
        val folder = File(library.root, installDir)
        if (gameDir != null && StoragePathUtils.samePath(folder, gameDir)) return true
        return !folder.isDirectory || folder.list().isNullOrEmpty()
    }

    private fun isInstalled(manifest: File): Boolean {
        val text = runCatching { manifest.readText() }.getOrNull() ?: return false
        val flags = STATE_FLAGS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return (flags and STATE_FULLY_INSTALLED) != 0 && (flags and STATE_UNINSTALLING) == 0
    }

    private fun claimPrefix(
        appId: Int,
        steamapps: File,
        compatdata: File,
    ) {
        val source = File(steamapps, "compatdata/$appId")
        val target = File(compatdata, appId.toString())
        if (StoragePathUtils.samePath(source, target) || source.list().isNullOrEmpty() || target.exists()) return
        if (!source.renameTo(target)) Log.w(TAG, "Could not carry the prefix of $appId to $target")
    }

    private fun setInstallDir(
        manifest: File,
        folder: String,
    ) {
        val text = runCatching { manifest.readText() }.getOrNull() ?: return
        val match = INSTALL_DIR.find(text) ?: return
        if (match.groupValues[2] == folder) return
        val escaped = folder.replace("\\", "\\\\").replace("\"", "\\\"")
        val updated = text.replaceRange(match.groups[2]!!.range, escaped)
        runCatching { manifest.writeText(updated) }.onFailure { Log.w(TAG, "Could not point ${manifest.name} at $folder", it) }
    }

    private fun buildId(manifest: File): Long {
        if (!manifest.isFile) return 0L
        val text = runCatching { manifest.readText() }.getOrElse { return 0L }
        return BUILD_ID.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    private fun adopt(
        appId: Int,
        manifest: File,
        build: Long,
        gameDir: File,
    ) {
        val text = runCatching { manifest.readText() }.getOrElse { return }
        PrefManager.setInstalledBuildId(appId, build)
        val start = text.indexOf("\"InstalledDepots\"")
        if (start < 0) return
        val depots = DEPOT_MANIFEST.findAll(text, start).associate { it.groupValues[1] to it.groupValues[2] }
        if (depots.isEmpty()) return
        val configDir = File(gameDir, ".DepotDownloader")
        val configFile = File(configDir, "depot.config")
        runCatching {
            val config = if (configFile.isFile) JSONObject(configFile.readText()) else JSONObject()
            val ids = config.optJSONObject("installedManifestIDs") ?: JSONObject()
            for ((depot, gid) in depots) ids.put(depot, gid.toLongOrNull() ?: continue)
            config.put("installedManifestIDs", ids)
            if (configDir.isDirectory || configDir.mkdirs()) configFile.writeText(config.toString())
        }.onFailure { Log.w(TAG, "Could not record the client's build of $appId", it) }
    }

    private fun record(
        context: Context,
        appId: Int,
        gameDir: File,
        build: Long,
    ): Boolean {
        val dir = gameDir.path
        if (!InstallOwnership.claim(dir, InstallStore.STEAM)) return false
        if (!MarkerUtils.addMarker(dir, Marker.DOWNLOAD_COMPLETE_MARKER)) return false
        return runCatching {
            runBlocking(Dispatchers.IO) {
                val db = PluviaDatabase.getInstance(context)
                db.steamAppDao().findApp(appId)?.let { app -> db.steamAppDao().update(app.copy(installDir = dir)) }
                val existing = db.appInfoDao().get(appId)
                if (existing != null) {
                    db.appInfoDao().update(existing.copy(isDownloaded = true, installPath = dir))
                } else {
                    db.appInfoDao().insert(AppInfo(id = appId, isDownloaded = true, installPath = dir))
                }
            }
            if (build > 0L) PrefManager.setInstalledBuildId(appId, build)
            true
        }.getOrElse {
            Log.w(TAG, "Could not record the client's install of $appId", it)
            false
        }
    }

    private fun release(
        context: Context,
        appId: Int,
    ): Boolean =
        runCatching {
            runBlocking(Dispatchers.IO) {
                val db = PluviaDatabase.getInstance(context)
                db.appInfoDao().get(appId)?.let { db.appInfoDao().update(it.copy(isDownloaded = false)) }
                db.steamAppDao().findApp(appId)?.let { db.steamAppDao().update(it.copy(installDir = "")) }
            }
            true
        }.getOrElse {
            Log.w(TAG, "Could not release the client's install of $appId", it)
            false
        }

    private fun writeEntry(
        container: Container,
        appId: Int,
        name: String,
        gameDir: File,
    ) {
        val desktopDir = container.desktopDir
        if (!desktopDir.exists() && !desktopDir.mkdirs()) return
        val safeName = name.replace("/", "_").replace("\\", "_")
        val file = File(desktopDir, "$safeName.desktop")
        if (file.exists() && file.length() > 0L) return
        val content =
            buildString {
                append("[Desktop Entry]\n")
                append("Type=Application\n")
                append("Name=$name\n")
                append("Exec=${LinuxApps.EXEC}\n")
                append("Icon=steam_icon_$appId\n")
                append("\n[Extra Data]\n")
                append("game_source=STEAM\n")
                append("app_id=$appId\n")
                append("container_id=${container.id}\n")
                append("game_install_path=${gameDir.path}\n")
                append("use_container_defaults=1\n")
                append("$KEY_CLIENT_INSTALL=1\n")
            }
        runCatching { FileUtils.writeString(file, content) }
            .onFailure { Log.w(TAG, "Could not write the entry for $appId", it) }
    }
}
