package com.winlator.cmod.runtime.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.feature.library.LinuxApps
import com.winlator.cmod.feature.settings.GraphicsDriverConfigUtils
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import java.io.File

/**
 * The Vulkan driver each GameScope game draws with. A session draws with the driver of the entry
 * that started it, and a game the client starts inherits that, so `winnative-proton-launch` reads
 * this file as the game starts: the driver its shortcut's settings chose, else the container's `*`.
 */
object LinuxDriverChoices {
    private const val TAG = "LinuxDriverChoices"
    private const val CHOICES = "etc/winnative/driver-choices"
    private val lock = Any()

    /** Worker thread. */
    @JvmStatic
    fun write(context: Context) {
        synchronized(lock) {
            if (!LinuxRuntime.isInstalled(context)) return
            val file = File(LinuxRuntime.rootDir(context), CHOICES)
            try {
                val container = LinuxApps.gamescopeContainer(ContainerManager(context))
                if (container == null) {
                    file.delete()
                    return
                }
                val containerConfig = container.graphicsDriverConfig.orEmpty()
                val lines = ArrayList<String>()
                icd(context, containerConfig)?.let { lines += "* $it" }
                for (entry in container.desktopDir.listFiles { f -> f.name.endsWith(".desktop") }.orEmpty()) {
                    val shortcut = Shortcut(container, entry)
                    if (LinuxApps.isLinuxShortcut(shortcut)) continue
                    val appId = LinuxSteamShortcuts.clientAppId(context, shortcut) ?: continue
                    val config = shortcut.getSettingExtra("graphicsDriverConfig", containerConfig).orEmpty()
                    icd(context, config)?.let { lines += "$appId $it" }
                }
                if (lines.isEmpty()) {
                    file.delete()
                } else {
                    LinuxSteamVdf.replace(file) { it.writeText(lines.joinToString("\n", postfix = "\n")) }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not record the drivers", error)
            }
        }
    }

    /** [write] off the caller's thread, for a settings screen that has just saved. */
    @JvmStatic
    fun update(context: Context) {
        val app = context.applicationContext
        Thread({ write(app) }, "LinuxDriverChoices").start()
    }

    /** The manifest a session given [config] would draw with, as the session resolves it. */
    private fun icd(context: Context, config: String): String? {
        val settings = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(Container.DEFAULT_GRAPHICSDRIVERCONFIG)
        settings.putAll(GraphicsDriverConfigUtils.parseGraphicsDriverConfig(config))
        return LinuxRuntime.vulkanIcd(context, settings["version"])?.path?.takeUnless { '\n' in it }
    }
}
