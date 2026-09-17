package com.winlator.cmod.feature.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import com.winlator.cmod.R
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.XServerDisplayActivity
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
        if (shortcutFile.exists()) return
        renderSteamIcon(context, File(container.getIconsDir(64), "$STEAM_ICON.png"), 64)
        renderSteamIcon(context, File(context.filesDir, "custom_icons/$STEAM_SHORTCUT_NAME.png"), 512)
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

    /** The library card reads `custom_icons/<name>.png`; the desktop entry reads the container's icon dir. */
    private fun renderSteamIcon(
        context: Context,
        file: File,
        size: Int,
    ) {
        if (file.exists()) return
        val drawable = AppCompatResources.getDrawable(context, R.drawable.library_steam_client) ?: return
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
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
