package com.winlator.cmod.feature.stores.common

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.shared.android.StoragePathUtils
import com.winlator.cmod.shared.io.FileUtils
import java.io.File

object StoreInstallPathSafety {
    data class DeleteCheck(
        val allowed: Boolean,
        val reason: String = "",
    )

    fun checkInstallDirDelete(
        context: Context?,
        targetPath: String,
        protectedRoots: Collection<String> = emptyList(),
    ): DeleteCheck {
        if (targetPath.isBlank()) {
            return DeleteCheck(false, "empty install path")
        }

        val target = StoragePathUtils.normalize(File(targetPath)) ?: File(targetPath).absoluteFile
        val protected = buildProtectedRoots(context, protectedRoots)
        val matchedRoot = protected.firstOrNull { samePath(target, it) }
        if (matchedRoot != null) {
            return DeleteCheck(false, "target is a protected root: ${matchedRoot.path}")
        }

        return DeleteCheck(true)
    }

    fun canDeleteInstallDir(
        context: Context?,
        targetPath: String,
        protectedRoots: Collection<String> = emptyList(),
    ): Boolean = checkInstallDirDelete(context, targetPath, protectedRoots).allowed

    private fun buildProtectedRoots(
        context: Context?,
        extraRoots: Collection<String>,
    ): List<File> {
        val roots = linkedMapOf<String, File>()

        fun add(file: File?) {
            val normalized = StoragePathUtils.normalize(file) ?: return
            if (normalized.path.isBlank()) return
            roots[normalized.path] = normalized
        }

        add(File("/"))
        add(File("/storage"))
        add(File("/mnt/media_rw"))
        add(Environment.getExternalStorageDirectory())

        StoragePathUtils.getMountedStorageRoots(
            context = context,
            includePrimary = true,
            includeMediaRw = true,
            requireBrowsable = false,
        ).forEach(::add)

        configuredDownloadRoots(context).forEach(::add)
        extraRoots.map(::File).forEach(::add)

        return roots.values.toList()
    }

    private fun configuredDownloadRoots(context: Context?): List<File> {
        val values =
            listOf(
                PrefManager.defaultDownloadFolder,
                PrefManager.steamDownloadFolder,
                PrefManager.epicDownloadFolder,
                PrefManager.gogDownloadFolder,
                PrefManager.itchDownloadFolder,
                PrefManager.externalStoragePath,
            )

        return values.mapNotNull { resolveConfiguredPath(context, it) }
    }

    private fun resolveConfiguredPath(
        context: Context?,
        value: String,
    ): File? {
        if (value.isBlank()) return null
        if (context != null) {
            val resolved =
                try {
                    FileUtils.getFilePathFromUri(context, Uri.parse(value))
                } catch (_: Exception) {
                    null
                }
            if (!resolved.isNullOrBlank()) return File(resolved)
        }

        val uriPath =
            try {
                Uri.parse(value).path
            } catch (_: Exception) {
                null
            }
        return File(uriPath ?: value)
    }

    private fun samePath(
        a: File,
        b: File,
    ): Boolean = StoragePathUtils.samePath(a, b)
}
