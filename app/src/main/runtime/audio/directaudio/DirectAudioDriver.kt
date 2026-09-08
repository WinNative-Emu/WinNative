package com.winlator.cmod.runtime.audio.directaudio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.system.Os
import android.system.OsConstants
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.winlator.cmod.runtime.display.environment.ImageFs
import timber.log.Timber
import java.io.File
import java.util.zip.ZipInputStream

// Host side of the DirectAudio driver by The412Banner (LGPL-2.1-or-later).
// See docs/direct-audio-integration.md.
object DirectAudioDriver {
    private const val TAG = "DirectAudio"

    const val IDENTIFIER = "directaudio"
    const val EXTRA_MIC = "directAudioMic"
    const val ENV_MIC = "BANNER_AUDIO_DIRECT_MIC"
    const val REQUEST_RECORD_AUDIO = 4120

    private const val ARM64EC = "arm64ec"
    private const val ASSET_DIR = "directaudio"
    private const val DRV_NAME = "winedirectaudio.drv"
    private const val SO_NAME = "winedirectaudio.so"

    // Bump when the bundled driver changes so existing layers re-overlay.
    private const val BUNDLED_VERSION = "1.3.2"

    // Archive entry -> destination dir under lib/wine.
    private val LAYOUT = listOf(
        "aarch64-windows/$DRV_NAME" to "aarch64-windows",
        "i386-windows/$DRV_NAME" to "i386-windows",
        "aarch64-unix/$SO_NAME" to "aarch64-unix",
    )

    fun isSelected(audioDriver: String?): Boolean = IDENTIFIER == audioDriver

    // 16 KB-page kernels need the sdk35 build; the wrong one fails to map.
    fun pageSizeTag(): String =
        try {
            if (Os.sysconf(OsConstants._SC_PAGESIZE) > 4096L) "sdk35" else "sdk28"
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "page size unavailable; assuming 4 KB pages")
            "sdk28"
        }

    // One wine11 build serves every 11.0-x; 10.0-4 needs wine10. Anything else
    // is unsupported rather than guessed at.
    fun wineAbiTag(wineVersion: String?): String? {
        if (wineVersion?.contains(ARM64EC) != true) return null
        val major = Regex("""(\d+)\.\d+""").find(wineVersion)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when (major) {
            11 -> "wine11"
            10 -> "wine10"
            else -> null
        }
    }

    fun isSupportedFor(wineVersion: String?): Boolean = wineAbiTag(wineVersion) != null

    private fun assetFor(wineVersion: String?): String? =
        wineAbiTag(wineVersion)?.let { "$ASSET_DIR/directaudio-$it-$ARM64EC-${pageSizeTag()}.zip" }

    // Installs both PE halves plus the unixlib: which PE loads is decided by the
    // guest game's bitness, not the device. Stamped, so it runs once per layer.
    fun install(context: Context, imageFs: ImageFs, wineVersion: String?): Boolean {
        val asset = assetFor(wineVersion)
        if (asset == null) {
            Timber.tag(TAG).w("no driver build for wineVersion=%s; not installing", wineVersion)
            return false
        }

        val wineLibDir = File(imageFs.winePath, "lib/wine")
        val stampId = "$BUNDLED_VERSION-${wineAbiTag(wineVersion)}-${pageSizeTag()}"
        val stamp = File(wineLibDir, ".directaudio.stamp")
        val installed = LAYOUT.all { (entry, dir) ->
            File(wineLibDir, "$dir/${File(entry).name}").isFile
        }
        if (!installed || !isStampCurrent(stamp, stampId)) {
            val staged = try {
                unzipAsset(context, asset, wineLibDir)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "failed to install %s", asset)
                return false
            }
            if (!staged) return false
            stamp.writeText(stampId)
            Timber.tag(TAG).i("installed DirectAudio %s into %s", stampId, wineLibDir)
        }
        patchDirectAudioNeeded(File(wineLibDir, "aarch64-unix/$SO_NAME"))
        mirrorIntoPrefix(imageFs, wineLibDir)
        return true
    }

    private fun mirrorIntoPrefix(imageFs: ImageFs, wineLibDir: File) {
        val windowsDir = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        copyIfChanged(File(wineLibDir, "aarch64-windows/$DRV_NAME"), File(windowsDir, "system32/$DRV_NAME"))
        copyIfChanged(File(wineLibDir, "i386-windows/$DRV_NAME"), File(windowsDir, "syswow64/$DRV_NAME"))
    }

    private fun copyIfChanged(src: File, dst: File) {
        if (!src.isFile) {
            Timber.tag(TAG).w("no driver PE at %s; mmdevapi will not find it", src)
            return
        }
        if (dst.isFile && dst.length() == src.length()) return
        try {
            dst.parentFile?.mkdirs()
            src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
            dst.setExecutable(true, false)
            Timber.tag(TAG).i("staged %s into %s", src.name, dst.parentFile?.name)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "failed to stage %s into the prefix", src.name)
        }
    }

    private fun patchDirectAudioNeeded(soFile: File) {
        if (!soFile.isFile) return
        try {
            val bytes = soFile.readBytes()
            val old = "libaaudio.so".toByteArray(Charsets.US_ASCII)
            val repl = "libwaudio.so".toByteArray(Charsets.US_ASCII)
            var idx = -1
            outer@ for (i in 0..bytes.size - old.size - 1) {
                for (j in old.indices) if (bytes[i + j] != old[j]) continue@outer
                if (bytes[i + old.size] == 0.toByte()) { idx = i; break }
            }
            if (idx < 0) return
            System.arraycopy(repl, 0, bytes, idx, repl.size)
            soFile.writeBytes(bytes)
            Timber.tag(TAG).i("patched winedirectaudio.so NEEDED libaaudio.so -> libwaudio.so")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "failed to patch winedirectaudio.so NEEDED")
        }
    }

    private fun unzipAsset(context: Context, asset: String, wineLibDir: File): Boolean {
        val wanted = LAYOUT.associate { (entry, dir) -> entry to dir }
        val written = mutableSetOf<String>()

        context.assets.open(asset).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val dir = wanted[entry.name]
                    if (entry.isDirectory || dir == null) {
                        zip.closeEntry()
                        continue
                    }
                    // Destination is built from the known layout, never from the entry path.
                    val dest = File(wineLibDir, "$dir/${File(entry.name).name}").apply {
                        parentFile?.mkdirs()
                    }
                    dest.outputStream().use { out -> zip.copyTo(out) }
                    written += entry.name
                    zip.closeEntry()
                }
            }
        }

        val missing = wanted.keys - written
        if (missing.isNotEmpty()) {
            Timber.tag(TAG).e("%s is incomplete; missing %s", asset, missing.joinToString())
            return false
        }
        return true
    }

    private fun isStampCurrent(stamp: File, id: String): Boolean =
        try {
            stamp.isFile && stamp.readText().trim() == id
        } catch (_: Exception) {
            false
        }

    fun isMicEnabled(value: String?): Boolean = value == "1" || value.equals("true", true)

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun requestMicPermission(context: Context) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
        if (activity == null) {
            Timber.tag(TAG).w("no activity in context chain; cannot request RECORD_AUDIO")
            return
        }
        ActivityCompat.requestPermissions(
            activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    // Without the permission the input cannot open, and an endpoint a game can
    // enumerate but not open black-screens titles that probe the mic on load.
    fun shouldExposeMic(context: Context, micRequested: Boolean): Boolean {
        if (!micRequested) return false
        if (!hasMicPermission(context)) {
            Timber.tag(TAG).w("mic requested but RECORD_AUDIO not granted; keeping capture off")
            return false
        }
        return true
    }
}
