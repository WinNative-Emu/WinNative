package com.winlator.cmod.feature.retro

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.shared.io.FileUtils
import java.io.File
import java.util.UUID

object RetroShortcuts {
    const val KEY_SYSTEM = "retro_system"
    const val KEY_ROM = "rom_path"
    const val KEY_CORE = "retro_core"
    const val KEY_SHADER = "retro_shader"
    const val KEY_UPSCALE = "retro_upscale"
    const val KEY_SGSR = "retro_sgsr"
    const val KEY_TOUCH_CONTROLS = "retro_touch_controls"
    const val KEY_ADAPTIVE_STICKS = "retro_adaptive_sticks"
    const val KEY_HDD_IMAGE = "retro_ps2_hdd_image"
    const val KEY_HDD_ENABLE = "retro_ps2_hdd_enable"
    const val KEY_AUDIO = "retro_audio"
    const val KEY_HUD = "retro_hud"

    const val EXTRA_PS2_ROM_PATH = "wn_ps2_rom_path"
    const val EXTRA_PS2_GAME_NAME = "wn_ps2_game_name"
    const val EXTRA_PS2_SHORTCUT_PATH = "wn_ps2_shortcut_path"
    const val EXTRA_PS2_CONTAINER_ID = "wn_ps2_container_id"
    const val VAR_PREFIX = "retro_var_"

    fun coreVariables(shortcut: Shortcut): HashMap<String, String> {
        val vars = HashMap<String, String>()
        val extras = shortcut.extraData
        val keys = extras.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith(VAR_PREFIX)) {
                val value = shortcut.getExtra(key)
                if (value.isNotEmpty()) vars[key.removePrefix(VAR_PREFIX)] = value
            }
        }
        return vars
    }

    fun resolvedCoreVariables(
        context: Context,
        shortcut: Shortcut,
    ): HashMap<String, String> {
        val vars = coreVariables(shortcut)
        val system = systemForShortcut(shortcut) ?: return vars
        RetroCoreOptions.forSystem(system).forEach { option ->
            if (!vars.containsKey(option.key)) {
                vars[option.key] = RetroDefaults.coreOption(context, system.id, option.key, option.defaultValue)
            }
        }
        if (RetroCoreManager.usesDolphinCore(system)) {
            RetroCoreOptions.sanitizeDolphinVariables(vars)
        }
        return vars
    }

    @JvmStatic
    fun isRetroShortcut(shortcut: Shortcut): Boolean = shortcut.getExtra(KEY_SYSTEM).isNotEmpty()

    fun systemForShortcut(shortcut: Shortcut): RetroSystem? = RetroSystems.fromId(shortcut.getExtra(KEY_SYSTEM))

    fun romPath(shortcut: Shortcut): String = shortcut.getExtra(KEY_ROM)

    data class LibraryCapabilities(
        val systemId: String? = null,
        val romPath: String? = null,
        val system: RetroSystem? = null,
    ) {
        val isRetro: Boolean get() = system != null
        val isExternal: Boolean get() = system?.isExternal == true
        val showBootToDesktop: Boolean get() = !isRetro
        val showSaveTransfer: Boolean get() = isRetro && !isExternal
        val showCheats: Boolean get() = isRetro && !isExternal
        val showAchievements: Boolean
            get() = system != null && RetroAchievementsManager.consoleId(system.id) != 0
        val sourceLabel: String?
            get() = system?.badgeLabel
    }

    fun libraryCapabilities(shortcut: Shortcut?): LibraryCapabilities {
        if (shortcut == null || !isRetroShortcut(shortcut)) return LibraryCapabilities()
        val system = systemForShortcut(shortcut)
        return LibraryCapabilities(
            systemId = system?.id,
            romPath = romPath(shortcut).takeIf { it.isNotEmpty() },
            system = system,
        )
    }

    fun libraryCapabilitiesForSystemId(
        systemId: String?,
        romPath: String? = null,
    ): LibraryCapabilities {
        val system = RetroSystems.fromId(systemId) ?: return LibraryCapabilities()
        return LibraryCapabilities(
            systemId = system.id,
            romPath = romPath?.takeIf { it.isNotEmpty() },
            system = system,
        )
    }

    fun create(
        context: Context,
        name: String,
        romPath: String,
        system: RetroSystem,
    ): Boolean {
        val container = ContainerManager(context).retroContainer

        val desktopDir = container.desktopDir
        if (!desktopDir.exists()) desktopDir.mkdirs()

        val safeName = name.replace("/", "_").replace("\\", "_")
        val shortcutFile = File(desktopDir, "$safeName.desktop")
        val shortcutUuid = UUID.randomUUID().toString()

        val content =
            buildString {
                append("[Desktop Entry]\n")
                append("Type=Application\n")
                append("Name=$name\n")
                append("Exec=retro:${system.id}\n")
                append("Icon=custom_game\n")
                append("\n[Extra Data]\n")
                append("game_source=CUSTOM\n")
                append("custom_name=$name\n")
                append("$KEY_SYSTEM=${system.id}\n")
                append("$KEY_ROM=$romPath\n")
                append("$KEY_CORE=${system.coreFileName}\n")
                append("uuid=$shortcutUuid\n")
                append("container_id=${container.id}\n")
                append("use_container_defaults=1\n")
            }

        FileUtils.writeString(shortcutFile, content)
        RetroBoxart.ensureArtworkAsync(context)
        return true
    }

    @JvmStatic
    fun launch(
        context: Context,
        shortcut: Shortcut,
    ) {
        val system = systemForShortcut(shortcut)
        if (!RetroBundle.requireInstalled(context)) return

        if (system != null && system.isExternal) {
            recordLaunchStats(context, shortcut.getExtra("custom_name", shortcut.name))
            launchEmbeddedPs2(context, shortcut)
            return
        }
        if (Gen1EmbedLaunch.isEnabled(shortcut)) {
            recordLaunchStats(context, shortcut.getExtra("custom_name", shortcut.name))
            kotlin.concurrent.thread(name = "WnEngine3DLaunch") {
                val intent =
                    Gen1EmbedLaunch.launchIntentIfSupported(context, shortcut)
                        ?: launchIntent(context, shortcut)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (context !is android.app.Activity) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
            return
        }
        if (RetroCoreManager.usesDolphinCore(system) && embeddedDolphinEnabled(context)) {
            recordLaunchStats(context, shortcut.getExtra("custom_name", shortcut.name))
            DolphinEmbedLaunch.launch(context, shortcut)
            return
        }
        context.startActivity(launchIntent(context, shortcut))
    }

    fun usesEmbeddedLauncher(
        context: Context,
        shortcut: Shortcut,
    ): Boolean {
        val system = systemForShortcut(shortcut)
        if (system != null && system.isExternal) return true
        if (RetroCoreManager.usesDolphinCore(system) && embeddedDolphinEnabled(context)) return true
        return Gen1EmbedLaunch.isEnabled(shortcut)
    }

    fun embeddedDolphinEnabled(context: Context): Boolean =
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean("wn.gc.embedded", true)

    private fun recordLaunchStats(
        context: Context,
        gameName: String,
    ) {
        val prefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("${gameName}_play_count", prefs.getInt("${gameName}_play_count", 0) + 1)
            .putLong("${gameName}_last_played", System.currentTimeMillis())
            .apply()
    }

    private fun launchEmbeddedPs2(
        context: Context,
        shortcut: Shortcut,
    ) {
        val rom = File(romPath(shortcut))
        if (!rom.isFile) {
            Toast.makeText(context, context.getString(com.winlator.cmod.R.string.retro_rom_missing), Toast.LENGTH_LONG).show()
            return
        }
        if (!ps2GpuSupported(context)) {
            Toast.makeText(
                context,
                context.getString(com.winlator.cmod.R.string.retro_ps2_device_unsupported),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        Ps2GameOverlay.install()
        kotlin.concurrent.thread(name = "WnPs2Launch") {
            val biosPath = ensurePs2Bios(context)
            if (biosPath == null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        context.getString(
                            com.winlator.cmod.R.string.retro_lr_bios_required_title,
                            RetroSystems.PS2.shortName,
                        ) + "\n" + context.getString(com.winlator.cmod.R.string.retro_scr_ps2_bios_format),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@thread
            }
            val prefs = context.getSharedPreferences("ARMSX2", Context.MODE_PRIVATE)
            val touchControls =
                shortcut.getExtra(KEY_TOUCH_CONTROLS)
                    .ifEmpty { if (RetroDefaults.touchControls(context, RetroSystems.PS2.id)) "1" else "0" } != "0"
            val adaptiveSticks =
                shortcut.getExtra(KEY_ADAPTIVE_STICKS)
                    .ifEmpty { if (RetroDefaults.adaptiveSticks(context, RetroSystems.PS2.id)) "1" else "0" } == "1"
            val driverPref = (prefs.getString("wn.ps2.driver", "") ?: "").trim()
            val customDriverId =
                if (driverPref.isEmpty() || driverPref.equals("system", ignoreCase = true)) "" else driverPref
            prefs.edit().apply {
                putBoolean("setupComplete", true)
                putBoolean("wn.controls", true)
                putBoolean("wn.ps2.touchcontrols", touchControls)
                putBoolean("wn.ps2.adaptivesticks", adaptiveSticks)
                putString("wn.ps2.hddimage", shortcut.getExtra(KEY_HDD_IMAGE))
                putBoolean("wn.ps2.hdd", shortcut.getExtra(KEY_HDD_ENABLE) == "1")
                putString("romsDirs", org.json.JSONArray().put(rom.parent ?: "").toString())
                putString("bios", biosPath)
                putString("customDriverId", customDriverId)
                commit()
            }
            val uri =
                try {
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.tileprovider", rom)
                } catch (e: IllegalArgumentException) {
                    android.net.Uri.fromFile(rom)
                }
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setClassName(context, "com.armsx2.Main")
                    setDataAndType(uri, "application/octet-stream")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(EXTRA_PS2_ROM_PATH, rom.absolutePath)
                    putExtra(EXTRA_PS2_GAME_NAME, shortcut.getExtra("custom_name", shortcut.name))
                    putExtra(EXTRA_PS2_SHORTCUT_PATH, shortcut.file.absolutePath)
                    putExtra(EXTRA_PS2_CONTAINER_ID, shortcut.container.id)
                    RetroFrameGen.writeInto(context, this, shortcut, RetroSystems.PS2.id)
                }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { context.startActivity(intent) }
            }
        }
    }

    private fun ps2GpuSupported(context: Context): Boolean =
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.deviceConfigurationInfo.glEsVersion.toDouble()
        }.getOrDefault(Double.MAX_VALUE) >= 3.1

    private fun ensurePs2Bios(context: Context): String? {
        val biosDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "bios")
        if (!biosDir.exists()) biosDir.mkdirs()
        val existing = biosDir.listFiles()?.firstOrNull { it.isFile && it.length() >= 3L * 1024 * 1024 }
        if (existing != null) return existing.absolutePath
        val sources =
            listOf(
                File("/storage/emulated/0/Download/bios"),
                File(RetroCoreManager.systemDir(context), "bios"),
                File("/storage/emulated/0/Download"),
            )
        for (dir in sources) {
            val candidate =
                dir.listFiles()?.firstOrNull {
                    it.isFile && it.length() in (3L * 1024 * 1024)..(8L * 1024 * 1024) &&
                        (it.name.endsWith(".bin", true) || it.name.endsWith(".BIN") || it.name.contains("ROM0"))
                } ?: continue
            val target = File(biosDir, candidate.name)
            runCatching { candidate.copyTo(target, overwrite = true) }
            if (target.isFile) return target.absolutePath
        }
        return null
    }

    @JvmStatic
    fun launchIntent(
        context: Context,
        shortcut: Shortcut,
    ): Intent =
        Intent(context, RetroActivity::class.java).apply {
            val sysId = shortcut.getExtra(KEY_SYSTEM)
            putExtra(RetroActivity.EXTRA_ROM_PATH, shortcut.getExtra(KEY_ROM))
            putExtra(RetroActivity.EXTRA_SYSTEM_ID, sysId)
            putExtra(RetroActivity.EXTRA_GAME_NAME, shortcut.getExtra("custom_name", shortcut.name))
            putExtra(RetroActivity.EXTRA_SHORTCUT_PATH, shortcut.file.absolutePath)
            putExtra(RetroActivity.EXTRA_CONTAINER_ID, shortcut.container.id)
            putExtra(
                RetroActivity.EXTRA_SHADER,
                shortcut.getExtra(KEY_SHADER).ifEmpty { RetroDefaults.shader(context, sysId) },
            )
            putExtra(
                RetroActivity.EXTRA_UPSCALE,
                shortcut.getExtra(KEY_UPSCALE).ifEmpty { RetroDefaults.upscale(context, sysId) },
            )
            putExtra(
                RetroActivity.EXTRA_SGSR,
                shortcut.getExtra(KEY_SGSR).ifEmpty { if (RetroDefaults.sgsr(context, sysId)) "1" else "0" } == "1",
            )
            putExtra(
                RetroActivity.EXTRA_TOUCH_CONTROLS,
                shortcut.getExtra(KEY_TOUCH_CONTROLS).ifEmpty { if (RetroDefaults.touchControls(context, sysId)) "1" else "0" } != "0",
            )
            putExtra(
                RetroActivity.EXTRA_ADAPTIVE_STICKS,
                shortcut.getExtra(KEY_ADAPTIVE_STICKS).ifEmpty { if (RetroDefaults.adaptiveSticks(context, sysId)) "1" else "0" } == "1",
            )
            putExtra(
                RetroActivity.EXTRA_AUDIO,
                shortcut.getExtra(KEY_AUDIO).ifEmpty { if (RetroDefaults.audio(context, sysId)) "1" else "0" } != "0",
            )
            putExtra(RetroActivity.EXTRA_HUD, RetroHudSupport.hudEnabled(context, shortcut, sysId))
            putExtra(RetroActivity.EXTRA_VARIABLES, resolvedCoreVariables(context, shortcut))
            RetroFrameGen.writeInto(context, this, shortcut, sysId)
        }
}
