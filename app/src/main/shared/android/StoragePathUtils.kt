package com.winlator.cmod.shared.android

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

object StoragePathUtils {
    @JvmStatic
    fun normalize(file: File?): File? =
        file?.let {
            try {
                it.canonicalFile
            } catch (_: Exception) {
                it.absoluteFile
            }
        }

    @JvmStatic
    fun normalizePath(path: String?): String =
        path
            ?.takeIf { it.isNotBlank() }
            ?.let { normalize(File(it))?.path }
            .orEmpty()

    @JvmStatic
    fun samePath(
        a: File,
        b: File,
    ): Boolean = normalizePath(a.path).trimEnd('/') == normalizePath(b.path).trimEnd('/')

    @JvmStatic
    fun isSameOrDescendant(
        candidate: File,
        root: File,
    ): Boolean {
        val candidatePath = normalizePath(candidate.path).trimEnd('/')
        val rootPath = normalizePath(root.path).trimEnd('/')
        return candidatePath == rootPath || candidatePath.startsWith("$rootPath/")
    }

    @JvmStatic
    fun canBrowse(dir: File?): Boolean = dir != null && dir.exists() && dir.isDirectory && dir.canRead()

    @JvmStatic
    fun isReadableMountedState(state: String?): Boolean =
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    @JvmStatic
    fun resolveStorageRootFromExternalFilesDir(dir: File?): File? {
        val absolute = dir?.absoluteFile ?: return null
        val androidDir =
            generateSequence(absolute) { it.parentFile }
                .firstOrNull { it.name.equals("Android", ignoreCase = true) }
        if (androidDir?.parentFile != null) {
            return androidDir.parentFile
        }

        return generateSequence(absolute) { it.parentFile }
            .drop(4)
            .firstOrNull()
    }

    @JvmStatic
    fun getMountedStorageRoots(
        context: Context?,
        includePrimary: Boolean,
        includeMediaRw: Boolean,
        requireBrowsable: Boolean,
    ): List<File> {
        val roots = linkedMapOf<String, File>()

        fun addRoot(root: File?) {
            val normalized = normalize(root) ?: return
            if (requireBrowsable && !canBrowse(normalized)) return
            roots[normalized.path.trimEnd('/')] = normalized
        }

        val externalFilesDirs =
            context
                ?.getExternalFilesDirs(null)
                .orEmpty()
                .filterNotNull()
                .filter { isReadableMountedState(Environment.getExternalStorageState(it)) }

        context?.getSystemService(StorageManager::class.java)?.let { storageManager ->
            storageManager.storageVolumes
                .filter { isReadableVolume(it) && (includePrimary || !it.isPrimary) }
                .forEach { volume ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        addRoot(volume.directory)
                    }
                    volume.uuid?.takeIf { it.isNotBlank() }?.let { uuid ->
                        addRoot(File("/storage/$uuid"))
                        if (includeMediaRw) addRoot(File("/mnt/media_rw/$uuid"))
                    }
                    externalFilesDirs
                        .filter { belongsToVolume(storageManager, it, volume) }
                        .mapNotNull(::resolveStorageRootFromExternalFilesDir)
                        .forEach(::addRoot)
                }
        }

        File("/storage").listFiles().orEmpty().forEach { child ->
            if (!child.isDirectory || child.name == "self") return@forEach
            if (child.name == "emulated") {
                if (includePrimary) addRoot(File(child, "0"))
            } else {
                addRoot(child)
            }
        }

        if (includeMediaRw) {
            File("/mnt/media_rw").listFiles().orEmpty().forEach { child ->
                if (child.isDirectory) addRoot(child)
            }
        }

        externalFilesDirs
            .mapNotNull(::resolveStorageRootFromExternalFilesDir)
            .forEach(::addRoot)

        return roots.values.toList()
    }

    /**
     * The app's own games directory, offered next to the device's volumes.
     *
     * Shared storage is not a filesystem here: it is served over FUSE by the media provider, which
     * refuses a symlink, drops the mode a chmod asks for, and is mounted noexec. A game library
     * needs all three - the client sets the executable bit after an install, Proton's prefixes and
     * the Steam runtime are built out of symlinks, and a native title is an ELF that has to run.
     * The app's own directory is on the same partition as shared storage, so a library kept here
     * costs no extra space and behaves like a real disk.
     */
    @JvmStatic
    fun appPrivateGamesRoot(context: Context): File? {
        val root = File(context.dataDir, "Games")
        if (!root.isDirectory && !root.mkdirs()) return null
        return root.takeIf(::canBrowse)
    }

    @JvmStatic
    fun buildBrowsableStorageRoots(context: Context): List<File> {
        val roots = linkedMapOf<String, File>()
        val storageManager = context.getSystemService(StorageManager::class.java)
        val externalFilesDirs =
            context
                .getExternalFilesDirs(null)
                .filterNotNull()
                .filter { isReadableMountedState(Environment.getExternalStorageState(it)) }

        fun addRoot(dir: File?) {
            val normalized = dir?.absoluteFile ?: return
            if (!canBrowse(normalized)) return
            roots.putIfAbsent(normalized.absolutePath, normalized)
        }

        fun addResolvedRoot(
            root: File?,
            browseSeed: File? = null,
        ) {
            resolveBrowsableRoot(root, browseSeed)?.let(::addRoot)
        }

        addResolvedRoot(Environment.getExternalStorageDirectory())

        storageManager?.storageVolumes.orEmpty()
            .filter(::isReadableVolume)
            .forEach { volume ->
                val volumeExternalDirs =
                    externalFilesDirs.filter { dir ->
                        storageManager != null && belongsToVolume(storageManager, dir, volume)
                    }

                val volumeCandidates =
                    buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            add(volume.directory)
                        }
                        volume.uuid?.let { uuid ->
                            add(File("/storage/$uuid"))
                            add(File("/mnt/media_rw/$uuid"))
                        }
                        volumeExternalDirs.forEach { dir ->
                            add(resolveStorageRootFromExternalFilesDir(dir))
                        }
                    }

                var resolvedRoot: File? = null
                for (candidate in volumeCandidates) {
                    resolvedRoot =
                        volumeExternalDirs
                            .asSequence()
                            .mapNotNull { dir -> resolveBrowsableRoot(candidate, dir) }
                            .firstOrNull()
                            ?: resolveBrowsableRoot(candidate)
                    if (resolvedRoot != null) break
                }

                if (resolvedRoot == null) {
                    resolvedRoot =
                        volumeExternalDirs
                            .asSequence()
                            .mapNotNull(::resolveFallbackBrowsableRootFromExternalFilesDir)
                            .firstOrNull()
                }

                addRoot(resolvedRoot)
            }

        File("/storage").listFiles().orEmpty().forEach { child ->
            if (!child.isDirectory || child.name == "self") return@forEach
            if (child.name == "emulated") {
                addResolvedRoot(File(child, "0"))
            } else {
                addResolvedRoot(child)
            }
        }

        externalFilesDirs
            .mapNotNull(::resolveStorageRootFromExternalFilesDir)
            .forEach { root ->
                addResolvedRoot(root)
            }

        externalFilesDirs
            .mapNotNull(::resolveFallbackBrowsableRootFromExternalFilesDir)
            .forEach(::addRoot)

        return roots.values.toList()
    }

    private fun resolveBrowsableRoot(
        root: File?,
        browseSeed: File? = null,
    ): File? {
        val normalizedRoot = root?.absoluteFile ?: return null
        if (canBrowse(normalizedRoot)) return normalizedRoot
        return highestBrowsablePathWithinRoot(normalizedRoot, browseSeed)
    }

    private fun resolveFallbackBrowsableRootFromExternalFilesDir(dir: File): File? {
        val absolute = dir.absoluteFile
        val resolvedRoot = resolveStorageRootFromExternalFilesDir(absolute)
        return when {
            resolvedRoot != null -> highestBrowsablePathWithinRoot(resolvedRoot, absolute)
            canBrowse(absolute) -> absolute
            else ->
                generateSequence(absolute.parentFile) { it.parentFile }
                    .firstOrNull(::canBrowse)
        }
    }

    private fun highestBrowsablePathWithinRoot(
        root: File,
        browseSeed: File?,
    ): File? {
        val normalizedRoot = root.absoluteFile
        val normalizedSeed = browseSeed?.absoluteFile ?: return null
        var current: File? = normalizedSeed
        var best: File? = null
        while (current != null && isSameOrDescendant(current, normalizedRoot)) {
            if (canBrowse(current)) {
                best = current
            }
            current = current.parentFile
        }
        return best
    }

    private fun belongsToVolume(
        storageManager: StorageManager,
        dir: File,
        volume: StorageVolume,
    ): Boolean {
        val dirVolume = storageManager.getStorageVolume(dir) ?: return false
        if (dirVolume.isPrimary != volume.isPrimary) return false

        val dirUuid = dirVolume.uuid
        val volumeUuid = volume.uuid
        return if (!dirUuid.isNullOrBlank() || !volumeUuid.isNullOrBlank()) {
            dirUuid == volumeUuid
        } else {
            true
        }
    }

    private fun isReadableVolume(volume: StorageVolume): Boolean = isReadableMountedState(volume.state)
}
