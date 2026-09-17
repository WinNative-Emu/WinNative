package com.winlator.cmod.feature.library

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.winlator.cmod.R
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import com.winlator.cmod.runtime.linux.LinuxRuntime
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.ui.toast.WinToast
import java.io.File
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

    /** Extensions Linux programs ship with. A bare ELF with no extension is not recognised. */
    val Extensions = setOf("appimage", "sh", "run", "bin", "elf", "x86_64", "x86", "aarch64", "arm64")

    @JvmStatic
    fun isLinuxExecutable(file: File): Boolean =
        file.isFile && file.extension.lowercase(Locale.ROOT) in Extensions

    @JvmStatic
    fun isLinuxShortcut(shortcut: Shortcut): Boolean = shortcut.getExtra(KEY_RUNTIME) == RUNTIME_LINUX

    /** Worker thread. Writes the shortcut into the preferred container's desktop directory. */
    fun create(
        context: Context,
        name: String,
        exePath: String,
        type: LibraryItemType,
    ): Boolean {
        val container = SetupWizardActivity.getPreferredGameContainer(context, ContainerManager(context))
        if (container == null) {
            SetupWizardActivity.promptToInstallWineOrCreateContainer(context)
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
