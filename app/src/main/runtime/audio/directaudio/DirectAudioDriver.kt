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
    private const val UNIX_CALL_TABLE = "__wine_unix_call_funcs"
    private const val SHT_DYNSYM = 11

    // Newest first: the first bundled build whose unixlib table matches wins.
    private val ABI_TAGS = listOf("wine11", "wine10")

    // Every Proton ships at least one of these; they carry the same mmdevapi
    // unixlib table the DirectAudio driver has to plug into.
    private val HOST_UNIX_PROBES =
        listOf("aarch64-unix/winealsa.so", "aarch64-unix/winepulse.so")

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

    fun isSupportedFor(wineVersion: String?): Boolean =
        wineVersion?.contains(ARM64EC, ignoreCase = true) == true

    // The driver plugs into mmdevapi's unixlib table, so the build that fits is
    // decided by the Proton layer's own ABI, not by a version parsed from its name:
    // custom, GE and beta builds carry names no version table can predict, and a
    // Proton whose table changed would otherwise be handed a mismatched driver.
    internal fun unixCallEntries(elf: ByteArray): Int {
        if (elf.size < 64) return 0
        if (elf[0] != 0x7F.toByte() || elf[1] != 'E'.code.toByte() ||
            elf[2] != 'L'.code.toByte() || elf[3] != 'F'.code.toByte()) return 0
        if (elf[4].toInt() != 2 || elf[5].toInt() != 1) return 0
        fun u16(o: Int) = (elf[o].toInt() and 0xFF) or ((elf[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        fun u64(o: Int) = u32(o) or (u32(o + 4) shl 32)
        val sectionOffset = u64(0x28).toInt()
        val sectionSize = u16(0x3A)
        val sectionCount = u16(0x3C)
        if (sectionOffset <= 0 || sectionCount == 0) return 0
        for (i in 0 until sectionCount) {
            val section = sectionOffset + i * sectionSize
            if (section + sectionSize > elf.size) return 0
            if (u32(section + 4).toInt() != SHT_DYNSYM) continue
            val symbols = u64(section + 24).toInt()
            val symbolsSize = u64(section + 32).toInt()
            val entrySize = u64(section + 56).toInt().let { if (it > 0) it else 24 }
            val strings = u64(sectionOffset + u32(section + 40).toInt() * sectionSize + 24).toInt()
            var sym = symbols
            while (sym + entrySize <= symbols + symbolsSize && sym + entrySize <= elf.size) {
                val at = strings + u32(sym).toInt()
                if (at in 0 until elf.size) {
                    var end = at
                    while (end < elf.size && elf[end] != 0.toByte()) end++
                    if (String(elf, at, end - at, Charsets.US_ASCII) == UNIX_CALL_TABLE) {
                        return (u64(sym + 16) / 8L).toInt()
                    }
                }
                sym += entrySize
            }
        }
        return 0
    }

    private fun hostUnixAbi(wineLibDir: File): Int {
        for (probe in HOST_UNIX_PROBES) {
            val file = File(wineLibDir, probe)
            if (!file.isFile) continue
            val entries = try {
                unixCallEntries(file.readBytes())
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "could not read %s", file)
                0
            }
            if (entries > 0) return entries
        }
        return 0
    }

    internal fun driverUnixAbi(context: Context, asset: String): Int =
        try {
            context.assets.open(asset).use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entries = 0
                    while (entries == 0) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == "aarch64-unix/$SO_NAME") {
                            entries = unixCallEntries(zip.readBytes())
                        }
                        zip.closeEntry()
                    }
                    entries
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "could not read the unixlib in %s", asset)
            0
        }

    internal fun assetForAbi(context: Context, abi: Int): String? {
        if (abi <= 0) return null
        for (tag in ABI_TAGS) {
            val asset = "$ASSET_DIR/directaudio-$tag-$ARM64EC-${pageSizeTag()}.zip"
            if (driverUnixAbi(context, asset) == abi) return asset
        }
        return null
    }

    // Installs both PE halves plus the unixlib: which PE loads is decided by the
    // guest game's bitness, not the device. Stamped, so it runs once per layer.
    fun install(context: Context, imageFs: ImageFs, wineVersion: String?): Boolean {
        if (!isSupportedFor(wineVersion)) {
            Timber.tag(TAG).w("wineVersion=%s is not arm64ec; not installing", wineVersion)
            return false
        }

        val wineLibDir = File(imageFs.winePath, "lib/wine")
        val abi = hostUnixAbi(wineLibDir)
        val asset = assetForAbi(context, abi)
        if (asset == null) {
            Timber.tag(TAG).w(
                "no bundled driver matches the mmdevapi unixlib ABI of %s (%d entries)",
                imageFs.winePath, abi)
            removeFromPrefix(imageFs)
            return false
        }
        val stampId = "$BUNDLED_VERSION-abi$abi-${pageSizeTag()}"
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
        if (!patchDirectAudioNeeded(File(wineLibDir, "aarch64-unix/$SO_NAME"))) return false
        return mirrorIntoPrefix(imageFs, wineLibDir)
    }

    private fun removeFromPrefix(imageFs: ImageFs) {
        val windowsDir = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        for (dir in listOf("system32", "syswow64")) {
            val stale = File(windowsDir, "$dir/$DRV_NAME")
            if (stale.isFile && stale.delete()) {
                Timber.tag(TAG).i("removed unusable %s from %s", DRV_NAME, dir)
            }
        }
    }

    private fun mirrorIntoPrefix(imageFs: ImageFs, wineLibDir: File): Boolean {
        val windowsDir = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        val win64 = copyIfChanged(
            File(wineLibDir, "aarch64-windows/$DRV_NAME"), File(windowsDir, "system32/$DRV_NAME"))
        val win32 = copyIfChanged(
            File(wineLibDir, "i386-windows/$DRV_NAME"), File(windowsDir, "syswow64/$DRV_NAME"))
        return win64 && win32
    }

    private fun copyIfChanged(src: File, dst: File): Boolean {
        if (!src.isFile) {
            Timber.tag(TAG).e("no driver PE at %s; mmdevapi will not find it", src)
            return false
        }
        if (dst.isFile && dst.length() == src.length()) return true
        return try {
            dst.parentFile?.mkdirs()
            src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
            dst.setExecutable(true, false)
            Timber.tag(TAG).i("staged %s into %s", src.name, dst.parentFile?.name)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "failed to stage %s into the prefix", src.name)
            false
        }
    }

    private fun patchDirectAudioNeeded(soFile: File): Boolean {
        if (!soFile.isFile) {
            Timber.tag(TAG).e("no unixlib at %s", soFile)
            return false
        }
        return try {
            val bytes = soFile.readBytes()
            val repl = "libwaudio.so".toByteArray(Charsets.US_ASCII)
            if (indexOfNeeded(bytes, repl) >= 0) return true
            val idx = indexOfNeeded(bytes, "libaaudio.so".toByteArray(Charsets.US_ASCII))
            if (idx < 0) {
                Timber.tag(TAG).e("neither libaaudio.so nor libwaudio.so is a NEEDED of %s", soFile.name)
                return false
            }
            System.arraycopy(repl, 0, bytes, idx, repl.size)
            soFile.writeBytes(bytes)
            Timber.tag(TAG).i("patched winedirectaudio.so NEEDED libaaudio.so -> libwaudio.so")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "failed to patch winedirectaudio.so NEEDED")
            false
        }
    }

    private fun indexOfNeeded(bytes: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..bytes.size - needle.size - 1) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            if (bytes[i + needle.size] == 0.toByte()) return i
        }
        return -1
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
