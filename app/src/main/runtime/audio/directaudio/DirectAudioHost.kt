package com.winlator.cmod.runtime.audio.directaudio

import android.content.Context
import android.util.Log
import com.winlator.cmod.runtime.display.environment.EnvironmentComponent
import com.winlator.cmod.shared.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * DirectAudio for a Linux session. Valve's Proton is a glibc program and cannot load Android's
 * AAudio, so the driver built for it (tools/linuxfs/directaudio) has the app open each stream
 * and exchanges the PCM with it through shared memory (cpp/wnaudiohook/wn_aaudio_host.c).
 * PulseAudio keeps running beside it for everything that is not a Windows game.
 */
class DirectAudioHost(
    private val socket: File,
    private val allowCapture: Boolean,
) : EnvironmentComponent() {
    override fun start() {
        val dir = socket.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "Could not create $dir")
            return
        }
        if (!nativeStart(socket.path, allowCapture)) Log.w(TAG, "DirectAudio host did not start")
    }

    override fun stop() {
        nativeStop()
        socket.delete()
    }

    companion object {
        private const val TAG = "DirectAudioHost"
        const val SOCKET_PATH = "/usr/tmp/.directaudio/DA0"
        const val ENV_SOCKET = "WN_DIRECTAUDIO_SOCKET"

        /** Tells the session script to point the client's Proton prefixes at the driver. */
        const val ENV_ENABLED = "WN_DIRECTAUDIO"

        /** Where the session script finds the driver, under the runtime's root. */
        private const val STAGE_DIR = "usr/local/share/winnative/directaudio"

        /**
         * Valve's Proton 11 walks the same mmdevapi call table as the wine11 build. The page size
         * a build is for only shapes its unixlib, which is not taken from here.
         */
        private const val PE_ARCHIVE = "directaudio/directaudio-wine11-arm64ec-sdk28.zip"
        private const val UNIXLIB_ASSET = "directaudio/linux/winedirectaudio.so"
        private val PE_HALVES = listOf("aarch64-windows/winedirectaudio.drv", "i386-windows/winedirectaudio.drv")

        init {
            System.loadLibrary("wnaudiohost")
        }

        @JvmStatic
        private external fun nativeStart(socketPath: String, allowCapture: Boolean): Boolean

        @JvmStatic
        private external fun nativeStop()

        /**
         * Worker thread. Puts the driver where the session script picks it up: the unixlib built
         * for the runtime, and the two PE halves out of the upstream release, which hold no
         * platform code. Each lands through a rename, so a session never sees half a file.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun stage(context: Context, runtimeRoot: File) {
            val stage = File(runtimeRoot, STAGE_DIR)
            val unixlib = File(stage, "aarch64-unix/winedirectaudio.so")
            context.assets.open(UNIXLIB_ASSET).use { place(it.readBytes(), unixlib) }
            val wanted = PE_HALVES.toMutableSet()
            ZipInputStream(context.assets.open(PE_ARCHIVE)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    if (wanted.remove(entry.name)) place(zip.readBytes(), File(stage, entry.name))
                }
            }
            if (wanted.isNotEmpty()) throw IOException("The DirectAudio release holds no $wanted")
        }

        private fun place(bytes: ByteArray, target: File) {
            val dir = target.parentFile
            if (dir != null && !dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create $dir")
            val staged = File(dir, target.name + ".staged")
            try {
                staged.writeBytes(bytes)
                if (!staged.renameTo(target)) throw IOException("Could not place ${target.path}")
            } finally {
                FileUtils.delete(staged)
            }
        }
    }
}
