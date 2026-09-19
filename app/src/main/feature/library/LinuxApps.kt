package com.winlator.cmod.feature.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.Toast
import com.winlator.cmod.R
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import com.winlator.cmod.runtime.linux.LinuxClientInstaller
import com.winlator.cmod.runtime.linux.LinuxRuntime
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.ui.toast.WinToast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Library entries for Linux programs. They are `.desktop` files like every other shortcut, marked
 * with `runtime=linux` and `Exec=linux:native`, and start in the Linux runtime rather than Wine.
 */
object LinuxApps {
    const val KEY_RUNTIME = "runtime"
    const val RUNTIME_LINUX = "linux"
    const val EXEC = "linux:native"
    const val KEY_SESSION = "linux_session"
    const val SESSION_STEAM = "steam"
    const val STEAM_SHORTCUT_NAME = "Steam"
    private const val STEAM_ICON = "steam_client"
    private const val STEAM_GROUND = 0xFF1B2838.toInt()
    private const val STEAM_INK = 0xFFE7EEF5.toInt()

    /** Extensions Linux programs ship with. A bare ELF with no extension is not recognised. */
    val Extensions = setOf("appimage", "sh", "run", "bin", "elf", "x86_64", "x86", "aarch64", "arm64")

    @JvmStatic
    fun isLinuxExecutable(file: File): Boolean =
        file.isFile && file.extension.lowercase(Locale.ROOT) in Extensions

    @JvmStatic
    fun isLinuxShortcut(shortcut: Shortcut): Boolean = shortcut.getExtra(KEY_RUNTIME) == RUNTIME_LINUX

    /** The library entry that opens the native Steam client. */
    @JvmStatic
    fun isSteamClientShortcut(shortcut: Shortcut): Boolean = shortcut.getExtra(KEY_SESSION) == SESSION_STEAM

    @JvmStatic
    fun gamescopeContainer(manager: ContainerManager): Container? =
        manager.containers.firstOrNull { it.isGamescopeRuntime }

    /** Worker thread. Whether the Library lacks the Steam entry, also when there is no GameScope container yet. */
    @JvmStatic
    fun isSteamShortcutMissing(context: Context): Boolean {
        val container = gamescopeContainer(ContainerManager(context)) ?: return true
        return !File(container.desktopDir, "$STEAM_SHORTCUT_NAME.desktop").exists()
    }

    /**
     * Writes the Steam entry into the GameScope container's desktop directory if it is missing,
     * with an icon rendered from the app's own drawable.
     */
    @JvmStatic
    fun ensureSteamShortcut(
        context: Context,
        container: Container,
    ) {
        val desktopDir = container.desktopDir
        if (!desktopDir.exists()) desktopDir.mkdirs()
        val shortcutFile = File(desktopDir, "$STEAM_SHORTCUT_NAME.desktop")
        // The artwork is refreshed even for an entry that already exists, so an upgrade picks it up.
        val stamp = File(context.filesDir, "custom_icons/.steam_art")
        renderSteamIcon(File(container.getIconsDir(64), "$STEAM_ICON.png"), 64, stamp)
        renderSteamCover(File(context.filesDir, "custom_icons/$STEAM_SHORTCUT_NAME.png"), stamp)
        runCatching { stamp.writeText(ART_VERSION.toString()) }
        if (shortcutFile.exists()) return
        val content =
            buildString {
                append("[Desktop Entry]\n")
                append("Type=Application\n")
                append("Name=$STEAM_SHORTCUT_NAME\n")
                append("Exec=$EXEC\n")
                append("Icon=$STEAM_ICON\n")
                append("\n[Extra Data]\n")
                append("game_source=CUSTOM\n")
                append("custom_name=$STEAM_SHORTCUT_NAME\n")
                append("$KEY_RUNTIME=$RUNTIME_LINUX\n")
                append("$KEY_SESSION=$SESSION_STEAM\n")
                append("${LibraryItemType.EXTRA_KEY}=${LibraryItemType.APPLICATION.key}\n")
                append("uuid=${UUID.randomUUID()}\n")
                append("container_id=${container.id}\n")
                append("use_container_defaults=1\n")
            }
        FileUtils.writeString(shortcutFile, content)
    }

    /**
     * The library artwork. Valve's marks are theirs to license, so the entry is drawn as the plain
     * word instead of the client's logo: bold, letter-spaced, centred on the client's dark ground.
     *
     * [stamp] is what tells an install whose artwork was drawn by an older build to redraw it; the
     * files are otherwise written once and left alone.
     */
    private const val ART_VERSION = 2

    private fun steamArtIsCurrent(stamp: File): Boolean =
        runCatching { stamp.readText().trim().toInt() }.getOrNull() == ART_VERSION

    private fun drawSteamWordmark(
        canvas: Canvas,
        width: Int,
        height: Int,
    ) {
        canvas.drawColor(STEAM_GROUND)
        val text = "STEAM"
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = STEAM_INK
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.18f
                textSize = height * 0.42f
            }
        // Shrink to fit rather than clip, so the word reads whole at every size it is drawn at.
        val maxWidth = width * 0.78f
        val measured = paint.measureText(text)
        if (measured > maxWidth) paint.textSize *= maxWidth / measured
        val metrics = paint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        // Centring accounts for the trailing letter space the paint adds after the last glyph.
        canvas.drawText(text, width / 2f + paint.letterSpacing * paint.textSize / 2f, baseline, paint)
    }

    /** The wide card the library draws. */
    private fun renderSteamCover(
        file: File,
        stamp: File,
    ) {
        if (file.exists() && steamArtIsCurrent(stamp)) return
        file.parentFile?.mkdirs()
        val width = 920
        val height = 430
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawSteamWordmark(Canvas(bitmap), width, height)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The library card reads `custom_icons/<name>.png`; the desktop entry reads the container's icon dir. */
    private fun renderSteamIcon(
        file: File,
        size: Int,
        stamp: File,
    ) {
        if (file.exists() && steamArtIsCurrent(stamp)) return
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawSteamWordmark(Canvas(bitmap), size, size)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Worker thread. Writes the shortcut into the GameScope container's desktop directory. */
    fun create(
        context: Context,
        name: String,
        exePath: String,
        type: LibraryItemType,
    ): Boolean {
        val container = gamescopeContainer(ContainerManager(context))
        if (container == null) {
            WinToast.show(context, R.string.linux_apps_need_gamescope_container, Toast.LENGTH_LONG)
            return false
        }
        val desktopDir = container.desktopDir
        if (!desktopDir.exists()) desktopDir.mkdirs()
        val safeName = name.replace("/", "_").replace("\\", "_")
        val shortcutFile = File(desktopDir, "$safeName.desktop")
        val content =
            buildString {
                append("[Desktop Entry]\n")
                append("Type=Application\n")
                append("Name=$name\n")
                append("Exec=$EXEC\n")
                append("Icon=custom_game\n")
                append("\n[Extra Data]\n")
                append("game_source=CUSTOM\n")
                append("custom_name=$name\n")
                append("custom_exe=$exePath\n")
                append("custom_game_folder=${File(exePath).parent.orEmpty()}\n")
                append("$KEY_RUNTIME=$RUNTIME_LINUX\n")
                append("${LibraryItemType.EXTRA_KEY}=${type.key}\n")
                append("uuid=${UUID.randomUUID()}\n")
                append("container_id=${container.id}\n")
                append("use_container_defaults=1\n")
            }
        FileUtils.writeString(shortcutFile, content)
        return true
    }

    /** UI thread. Starts a session in the Linux runtime, or says why it cannot. */
    fun launch(
        context: Context,
        shortcut: Shortcut,
    ) {
        val name = shortcut.getExtra("custom_name").ifEmpty { shortcut.name }
        if (LinuxClientInstaller.isWorking) {
            WinToast.show(context, context.getString(R.string.linux_client_busy), Toast.LENGTH_LONG)
            return
        }
        if (!LinuxRuntime.isInstalled(context)) {
            WinToast.show(context, context.getString(R.string.linux_runtime_not_installed, name), Toast.LENGTH_LONG)
            return
        }
        val intent =
            Intent(context, XServerDisplayActivity::class.java)
                .putExtra("container_id", shortcut.container.id)
                .putExtra("shortcut_path", shortcut.file.path)
                .putExtra("shortcut_name", name)
        context.startActivity(intent)
    }
}
