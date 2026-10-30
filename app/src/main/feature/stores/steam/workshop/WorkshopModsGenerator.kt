package com.winlator.cmod.feature.stores.steam.workshop

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.winlator.cmod.feature.stores.steam.utils.KeyValue
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Manages installed Steam Workshop item content and generates the
 * `steam_settings/mods.json` manifest that gbe_fork's emulated ISteamUGC reads.
 *
 * Downloaded content is staged persistently, OUTSIDE `steam_settings`, so it
 * survives the per-launch regeneration of that directory:
 *
 *   <externalFiles>/wn_workshop/<appId>/<id>/             depot content
 *   <externalFiles>/wn_workshop/<appId>/<id>.meta.json    install marker + metadata
 *   <externalFiles>/wn_workshop/<appId>/<id>.preview.jpg  preview image
 *
 * At launch [generate] links each staged item into the game's `steam_settings`
 * the way gbe_fork expects:
 *
 *   steam_settings/mods/<id>            -> symlink to the staged content
 *   steam_settings/mod_images/<id>/preview.jpg
 *   steam_settings/mods.json           the manifest, keyed by published-file-id
 */
object WorkshopModsGenerator {
    private const val TAG = "WorkshopModsGen"
    const val PREVIEW_FILENAME = "preview.jpg"

    /** Persistent per-app staging root for downloaded Workshop content. */
    fun stagingDir(context: Context, appId: Int): File =
        File(context.getExternalFilesDir(null), "wn_workshop/$appId")

    fun contentDir(context: Context, appId: Int, publishedFileId: Long): File =
        File(stagingDir(context, appId), publishedFileId.toString())

    fun metaFile(context: Context, appId: Int, publishedFileId: Long): File =
        File(stagingDir(context, appId), "$publishedFileId.meta.json")

    fun previewFile(context: Context, appId: Int, publishedFileId: Long): File =
        File(stagingDir(context, appId), "$publishedFileId.preview.jpg")

    /**
     * Published-file-ids that are fully staged — a meta marker AND a content
     * directory both present (a marker without content is a torn install and
     * is not reported as installed).
     */
    fun installedItemIds(context: Context, appId: Int): Set<Long> {
        val files = stagingDir(context, appId).listFiles() ?: return emptySet()
        return files
            .mapNotNull { f ->
                if (f.isFile && f.name.endsWith(".meta.json")) {
                    f.name.removeSuffix(".meta.json").toLongOrNull()
                } else {
                    null
                }
            }
            .filter { contentDir(context, appId, it).isDirectory }
            .toSet()
    }

    /**
     * Remove one staged Workshop item — its content directory, install marker
     * and preview image. After this [installedItemIds] no longer reports the
     * item and the next [generate] pass drops its `mods.json` entry and prunes
     * the stale `steam_settings` link. The item can simply be installed again.
     * Returns true once the item is no longer staged.
     */
    fun uninstall(context: Context, appId: Int, publishedFileId: Long): Boolean {
        runCatching { metaFile(context, appId, publishedFileId).delete() }
        runCatching { previewFile(context, appId, publishedFileId).delete() }
        runCatching { contentDir(context, appId, publishedFileId).deleteRecursively() }
        val stillInstalled = publishedFileId in installedItemIds(context, appId)
        if (stillInstalled) {
            Timber.tag(TAG).w("Workshop item %d still staged after uninstall", publishedFileId)
        } else {
            Timber.tag(TAG).i("Workshop item %d uninstalled for app %d", publishedFileId, appId)
        }
        return !stillInstalled
    }

    /**
     * Build `steam_settings/mods.json` from the staged Workshop items and link
     * their content + preview images into [settingsDir]. Returns the number of
     * mods written. An empty install set writes `{}` (or removes a stale file).
     */
    fun generate(context: Context, appId: Int, settingsDir: File): Int {
        val ids = installedItemIds(context, appId)
        val modsJsonFile = File(settingsDir, "mods.json")
        val modsDir = File(settingsDir, "mods")
        val imagesDir = File(settingsDir, "mod_images")
        if (ids.isEmpty()) {
            // Drop every stale link/image — nothing is installed.
            pruneOrphans(modsDir, emptySet())
            pruneOrphans(imagesDir, emptySet())
            if (modsJsonFile.exists()) modsJsonFile.writeText("{}", Charsets.UTF_8)
            return 0
        }
        modsDir.mkdirs()
        imagesDir.mkdirs()
        val modsJson = JSONObject()
        val writtenIds = HashSet<Long>()
        for (id in ids) {
            val content = contentDir(context, appId, id)
            if (!content.isDirectory) continue
            val meta =
                try {
                    JSONObject(metaFile(context, appId, id).readText())
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Skipping workshop item %d — unreadable meta", id)
                    continue
                }

            // Link the staged content into steam_settings/mods/<id>.
            linkContent(content, File(modsDir, id.toString()))

            // Copy the preview into steam_settings/mod_images/<id>/ (gbe_fork
            // always resolves preview images relative to steam_settings).
            val preview = previewFile(context, appId, id)
            val hasPreview = preview.isFile
            if (hasPreview) {
                val destDir = File(imagesDir, id.toString()).apply { mkdirs() }
                runCatching { preview.copyTo(File(destDir, PREVIEW_FILENAME), overwrite = true) }
                    .onFailure { Timber.tag(TAG).w(it, "Preview copy failed for %d", id) }
            }

            modsJson.put(
                id.toString(),
                JSONObject().apply {
                    put("title", meta.optString("title", id.toString()))
                    put("preview_filename", if (hasPreview) PREVIEW_FILENAME else "")
                    put("total_files_sizes", meta.optLong("fileSize", 0L))
                    put("primary_filesize", meta.optLong("fileSize", 0L))
                    put("time_created", meta.optLong("timeUpdated", 0L))
                    put("time_updated", meta.optLong("timeUpdated", 0L))
                    put(
                        "workshop_item_url",
                        "https://steamcommunity.com/sharedfiles/filedetails/?id=$id",
                    )
                },
            )
            writtenIds.add(id)
        }
        // Prune by what was actually written this pass — so a torn item
        // (vanished content / unreadable meta) doesn't keep a stale link.
        pruneOrphans(modsDir, writtenIds)
        pruneOrphans(imagesDir, writtenIds)
        modsJsonFile.writeText(modsJson.toString(2), Charsets.UTF_8)
        Timber.tag(TAG).i("Wrote mods.json with %d mod(s) for app %d", writtenIds.size, appId)
        return writtenIds.size
    }

    fun syncToSteamLibrary(context: Context, appId: Int, steamappsDir: File): Int {
        val stagedIds = installedItemIds(context, appId)
        val workshopDir = File(steamappsDir, "workshop")
        val libraryContentDir = File(workshopDir, "content/$appId")
        val managedIds = symlinkedIds(libraryContentDir) + stagedIds
        pruneLinks(libraryContentDir, stagedIds)
        val entries = LinkedHashMap<Long, JSONObject>()
        if (stagedIds.isNotEmpty()) {
            libraryContentDir.mkdirs()
            for (id in stagedIds) {
                val content = contentDir(context, appId, id)
                if (!content.isDirectory) continue
                val meta =
                    try {
                        JSONObject(metaFile(context, appId, id).readText())
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Skipping workshop item %d — unreadable meta", id)
                        continue
                    }
                linkContent(content, File(libraryContentDir, id.toString()))
                entries[id] = meta
            }
        }
        if (managedIds.isEmpty()) return entries.size
        val acfFile = File(workshopDir, "appworkshop_$appId.acf")
        if (writeAppWorkshopAcf(appId, acfFile, managedIds, entries)) {
            Timber.tag(TAG).i("Synced %d workshop item(s) into %s", entries.size, libraryContentDir)
        }
        return entries.size
    }

    private fun writeAppWorkshopAcf(
        appId: Int,
        acfFile: File,
        managedIds: Set<Long>,
        entries: Map<Long, JSONObject>,
    ): Boolean {
        val text = if (acfFile.isFile) runCatching { acfFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } else null
        val root =
            if (text == null) {
                KeyValue("AppWorkshop").also { it.isSection = true }
            } else {
                val parsed = if (text.trimStart().startsWith("\"")) KeyValue.loadFromString(text) else null
                if (parsed == null || !"AppWorkshop".equals(parsed.name, ignoreCase = true)) {
                    Timber.tag(TAG).w("Leaving %s untouched — unparseable", acfFile.name)
                    return false
                }
                parsed
            }
        root.isSection = true
        setLeaf(root, "appid", appId.toString())
        setLeaf(root, "NeedsUpdate", "0")
        setLeaf(root, "NeedsDownload", "0")
        if (root["TimeLastAppRan"].asString() == null) setLeaf(root, "TimeLastAppRan", "0")

        val installed = resolveOrCreateSection(root, "WorkshopItemsInstalled")
        val details = resolveOrCreateSection(root, "WorkshopItemDetails")
        for (id in managedIds) {
            val key = id.toString()
            installed.children.removeAll { it.name == key }
            details.children.removeAll { it.name == key }
            val meta = entries[id] ?: continue
            val size = meta.optLong("fileSize", 0L)
            val timeUpdated = meta.optLong("timeUpdated", 0L)
            val manifest = meta.optLong("manifestId", 0L)
            installed.children.add(
                KeyValue(key).also { node ->
                    node.isSection = true
                    node.children.add(KeyValue("size", size.toString()))
                    node.children.add(KeyValue("timeupdated", timeUpdated.toString()))
                    node.children.add(KeyValue("manifest", manifest.toString()))
                },
            )
            details.children.add(
                KeyValue(key).also { node ->
                    node.isSection = true
                    node.children.add(KeyValue("manifest", manifest.toString()))
                    node.children.add(KeyValue("timeupdated", timeUpdated.toString()))
                    node.children.add(KeyValue("timetouched", timeUpdated.toString()))
                },
            )
        }
        val sizeOnDisk = installed.children.sumOf { it["size"].asString()?.toLongOrNull() ?: 0L }
        setLeaf(root, "SizeOnDisk", sizeOnDisk.toString())
        val updated =
            installed.children.maxOfOrNull { it["timeupdated"].asString()?.toLongOrNull() ?: 0L } ?: 0L
        val stamp = if (updated > 0) updated else System.currentTimeMillis() / 1000
        setLeaf(root, "TimeLastUpdated", stamp.toString())
        acfFile.parentFile?.mkdirs()
        return runCatching { acfFile.writeText(root.serialize(), Charsets.UTF_8) }.isSuccess
    }

    private fun setLeaf(parent: KeyValue, name: String, value: String) {
        val leaf = parent.children.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (leaf != null) {
            leaf.value = value
        } else {
            parent.children.add(KeyValue(name, value))
        }
    }

    private fun resolveOrCreateSection(parent: KeyValue, name: String): KeyValue {
        val existing = parent.children.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing
        return KeyValue(name).also {
            it.isSection = true
            parent.children.add(it)
        }
    }

    private fun symlinkedIds(dir: File): Set<Long> =
        dir
            .listFiles()
            .orEmpty()
            .filter { isSymlink(it) }
            .mapNotNull { it.name.toLongOrNull() }
            .toSet()

    private fun pruneLinks(dir: File, ids: Set<Long>) {
        val keep = ids.mapTo(HashSet()) { it.toString() }
        dir.listFiles()?.forEach { child ->
            if (child.name !in keep && isSymlink(child)) {
                runCatching { Os.remove(child.absolutePath) }
            }
        }
    }

    private fun isSymlink(path: File): Boolean {
        val st = runCatching { Os.lstat(path.absolutePath) }.getOrNull() ?: return false
        return OsConstants.S_ISLNK(st.st_mode)
    }

    /**
     * Point [link] at [content] with a symlink so the (possibly large) staged
     * content is not duplicated. Falls back to a recursive copy on filesystems
     * that reject symlinks.
     */
    private fun linkContent(content: File, link: File) {
        clearPath(link)
        val linked = runCatching { Os.symlink(content.absolutePath, link.absolutePath) }.isSuccess
        if (!linked) {
            runCatching { content.copyRecursively(link, overwrite = true) }
                .onFailure { Timber.tag(TAG).w(it, "Failed to stage mod content into %s", link) }
        }
    }

    /** Remove every child of [dir] whose name is not a currently-installed id. */
    private fun pruneOrphans(dir: File, ids: Set<Long>) {
        val keep = ids.mapTo(HashSet()) { it.toString() }
        dir.listFiles()?.forEach { child ->
            if (child.name !in keep) clearPath(child)
        }
    }

    /**
     * Remove [path] whether it is a symlink, a file, or a real directory —
     * WITHOUT ever recursing through a symlink. `lstat` (not `stat`) inspects
     * the link itself, so a symlink is only ever unlinked, never followed:
     * `deleteRecursively` is reached solely for a genuine directory.
     */
    private fun clearPath(path: File) {
        val st = runCatching { Os.lstat(path.absolutePath) }.getOrNull() ?: return
        when {
            OsConstants.S_ISLNK(st.st_mode) || OsConstants.S_ISREG(st.st_mode) ->
                runCatching { Os.remove(path.absolutePath) }
            OsConstants.S_ISDIR(st.st_mode) ->
                path.deleteRecursively()
        }
    }
}
