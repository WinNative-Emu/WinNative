package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import com.winlator.cmod.shared.io.ArchiveExtractor
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale

object ItchInstaller {
    class UnsupportedArchiveException(
        val archiveName: String,
    ) : IOException("WinNative cannot extract $archiveName")

    data class Result(
        val installDir: File,
        val executable: File?,
    )

    fun install(
        context: Context,
        title: String,
        installDir: File,
        payload: File,
        onProgress: (Float) -> Unit,
        isActive: () -> Boolean,
    ): Result {
        MarkerUtils.addMarker(installDir.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
        MarkerUtils.removeMarker(installDir.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)

        val lowerName = payload.name.lowercase(Locale.ROOT)
        when {
            lowerName.endsWith(".rar") -> {
                val kept = File(installDir, payload.name)
                if (payload.absolutePath != kept.absolutePath) moveInto(payload, kept)
                MarkerUtils.removeMarker(installDir.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                throw UnsupportedArchiveException(payload.name)
            }
            lowerName.endsWith(".exe") -> {
                val kept = File(installDir, payload.name)
                if (payload.absolutePath != kept.absolutePath) moveInto(payload, kept)
                onProgress(1f)
            }
            ArchiveExtractor.isSupported(payload) -> {
                ArchiveExtractor.extract(payload, installDir, onProgress, isActive)
                payload.delete()
                unwrapSingleDirectory(installDir)
            }
            else -> {
                val kept = File(installDir, payload.name)
                if (payload.absolutePath != kept.absolutePath) moveInto(payload, kept)
                onProgress(1f)
            }
        }

        val executable = ItchExecutablePicker.pick(installDir, title)
        MarkerUtils.removeMarker(installDir.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
        MarkerUtils.addMarker(installDir.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)
        Timber.i("[Itch] installed '$title' to ${installDir.absolutePath}, executable=${executable?.name ?: "none"}")
        return Result(installDir, executable)
    }

    fun isInstalled(installDir: File): Boolean =
        installDir.isDirectory &&
            MarkerUtils.hasMarker(installDir.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER) &&
            !MarkerUtils.hasMarker(installDir.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

    private fun moveInto(
        source: File,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            source.delete()
        }
    }

    private fun unwrapSingleDirectory(installDir: File) {
        val children = installDir.listFiles()?.filter { !it.name.startsWith(".") } ?: return
        if (children.size != 1) return
        val wrapper = children.first()
        if (!wrapper.isDirectory) return
        val inner = wrapper.listFiles() ?: return
        if (inner.isEmpty()) return
        inner.forEach { child ->
            val target = File(installDir, child.name)
            if (target.exists()) return
        }
        inner.forEach { child ->
            val target = File(installDir, child.name)
            if (!child.renameTo(target)) {
                Timber.w("[Itch] could not unwrap ${child.name}; leaving archive layout intact")
                return
            }
        }
        wrapper.delete()
    }
}
