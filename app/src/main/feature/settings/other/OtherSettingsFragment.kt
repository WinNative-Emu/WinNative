/* Settings > Other fragment — hosts OtherSettingsScreen via ComposeView. */
package com.winlator.cmod.feature.settings
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.app.config.SettingsConfig
import com.winlator.cmod.app.shell.UnifiedActivity
import com.winlator.cmod.app.update.UpdateChannel
import com.winlator.cmod.app.update.UpdateService
import com.winlator.cmod.feature.shortcuts.FrontendExporter
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.runtime.audio.midi.MidiManager
import com.winlator.cmod.runtime.display.environment.ImageFsInstaller
import com.winlator.cmod.runtime.system.ProcessHelper
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.android.LocaleHelper
import com.winlator.cmod.shared.android.RefreshRateUtils
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.ui.dialog.ContentDialog
import com.winlator.cmod.shared.ui.dialog.PreloaderDialog
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File

class OtherSettingsFragment : Fragment() {
    private lateinit var preferences: SharedPreferences
    private var uiState by mutableStateOf(OtherSettingsState())

    private val installSoundFontLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            installSoundFont(uri)
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.common_ui_other)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(ctx)
        refresh()

        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinNativeTheme(
                    colorScheme =
                        darkColorScheme(
                            primary = Color(0xFF1A9FFF),
                            background = Color(0xFF141B24),
                            surface = Color(0xFF1E252E),
                        ),
                ) {
                    OtherSettingsScreen(
                        bridge = (requireActivity() as? UnifiedActivity)?.settingsNavBridge,
                        state = uiState,
                        onCheckForUpdatesChanged = { checked ->
                            preferences.edit { putBoolean("check_for_updates", checked) }
                            if (checked) {
                                UpdateService.startHourlyLoop(ctx)
                            } else {
                                UpdateService.stopHourlyLoop()
                                UpdateService.cancelPostGameCheck()
                            }
                            refresh()
                        },
                        onCheckForUpdatesNow = {
                            val started = UpdateService.checkNow(ctx, manual = true)
                            if (!UpdateService.isSupported(ctx)) {
                                WinToast.show(ctx, R.string.update_unsupported_build)
                            } else if (started) {
                                WinToast.show(ctx, R.string.settings_other_checking_for_updates)
                            } else {
                                val seconds = UpdateService.manualCheckCooldownSeconds()
                                WinToast.show(
                                    ctx,
                                    getString(R.string.settings_other_update_check_cooldown, seconds),
                                )
                            }
                        },
                        onUpdateChannelSelected = { index ->
                            UpdateService.setChannel(ctx, UpdateChannel.entries[index])
                            refresh()
                        },
                        onLanguageSelected = { index ->
                            val currentIndex =
                                LocaleHelper.indexForTag(
                                    LocaleHelper.getAppliedLanguageTag(),
                                )
                            if (index != currentIndex) {
                                LocaleHelper.applyLanguageTag(LocaleHelper.tagForIndex(index))
                                // AppCompatDelegate recreates attached activities automatically.
                            }
                        },
                        onSoundFontSelected = { index ->
                            // Selection is display-only; no persistence in legacy code.
                            uiState = uiState.copy(soundFontIndex = index)
                        },
                        onInstallSoundFont = {
                            val intent =
                                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                }
                            installSoundFontLauncher.launch(intent)
                        },
                        onRemoveSoundFont = { removeSelectedSoundFont() },
                        onPickWinlatorPath = { pickStoredFolder("winlator_path_uri", SettingsConfig.DEFAULT_WINLATOR_PATH) },
                        onPickShortcutExportPath = {
                            pickStoredFolder(
                                "shortcuts_export_path_uri",
                                SettingsConfig.DEFAULT_SHORTCUT_EXPORT_PATH,
                            )
                        },
                        onExportAll = { exportAllShortcuts(ctx) },
                        onCursorSpeedChanged = { percent ->
                            preferences.edit { putFloat("cursor_speed", percent / 100f) }
                            refresh()
                        },
                        onCursorLockChanged = { checked ->
                            preferences.edit { putBoolean("cursor_lock", checked) }
                            refresh()
                        },
                        onXinputDisabledChanged = { checked ->
                            preferences.edit { putBoolean("xinput_toggle", checked) }
                            refresh()
                        },
                        onEnableFileProviderChanged = { checked ->
                            preferences.edit { putBoolean("enable_file_provider", checked) }
                            WinToast.show(ctx, R.string.settings_general_take_effect_next_startup)
                            refresh()
                        },
                        onAutoScrapingChanged = { checked ->
                            preferences.edit { putBoolean("enable_auto_scraping", checked) }
                            WinToast.show(ctx, R.string.settings_general_take_effect_next_startup)
                            refresh()
                        },
                        onOpenInBrowserChanged = { checked ->
                            preferences.edit { putBoolean("open_with_android_browser", checked) }
                            refresh()
                        },
                        onShareClipboardChanged = { checked ->
                            preferences.edit { putBoolean("share_android_clipboard", checked) }
                            refresh()
                        },
                        onEnableBackgroundSessionChanged = { checked ->
                            preferences.edit { putBoolean("enable_background_session", checked) }
                            // This is true if Store services are already running, but it isn't when is auto-enabled on wizard.
                            WinToast.show(ctx, R.string.settings_general_take_effect_next_startup)
                            refresh()
                        },
                        onEnableAutoPauseChanged = { checked ->
                            preferences.edit { putBoolean("enable_auto_pause_when_background", checked) }
                            refresh()
                        },
                        onUseBackgroundWakelockChanged = { checked ->
                            preferences.edit { putBoolean("enable_background_wakelock", checked) }
                            refresh()
                        },
                        onHeartbeatFrequencyChanged = { seconds ->
                            preferences.edit { putInt("background_heartbeat_frequency", seconds) }
                            refresh()
                        },
                        onBackgroundPauseModeChanged = { mode ->
                            preferences.edit { putString("background_pause_mode", mode.prefValue) }
                            ProcessHelper.setBackgroundPauseMode(mode)
                            refresh()
                        },
                        onExternalDisplayOutputChanged = { checked ->
                            preferences.edit { putBoolean("external_display_output", checked) }
                            refresh()
                        },
                        onRunSetupWizard = {
                            startActivity(SetupWizardActivity.createManualRerunIntent(ctx))
                        },
                        onReinstallImagefs = { startImagefsReinstall() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // Helpers
    private fun refresh() {
        val ctx = context ?: return

        // Language entries: "System default" + native names, and currently-applied index
        val languageLabels =
            buildList {
                add(getString(R.string.settings_other_language_system_default))
                addAll(LocaleHelper.NATIVE_LANGUAGE_NAMES)
            }
        val languageIndex = LocaleHelper.indexForTag(LocaleHelper.getAppliedLanguageTag())

        // Sound font files
        val soundFontFiles = loadSoundFontFiles(ctx)
        val soundFontIndex = uiState.soundFontIndex.coerceIn(0, (soundFontFiles.size - 1).coerceAtLeast(0))

        // Paths
        val winlatorPath =
            resolvePathString(
                preferences.getString("winlator_path_uri", null),
                SettingsConfig.DEFAULT_WINLATOR_PATH,
                ctx,
            )
        val shortcutExportPath =
            resolvePathString(
                preferences.getString("shortcuts_export_path_uri", null),
                SettingsConfig.DEFAULT_SHORTCUT_EXPORT_PATH,
                ctx,
            )

        uiState =
            OtherSettingsState(
                checkForUpdates = preferences.getBoolean("check_for_updates", true),
                updateChannelIndex = UpdateChannel.entries.indexOf(UpdateService.channel(ctx)),
                languageLabels = languageLabels,
                languageIndex = languageIndex,
                soundFontFiles = soundFontFiles,
                soundFontIndex = soundFontIndex,
                winlatorPath = winlatorPath,
                shortcutExportPath = shortcutExportPath,
                cursorSpeedPercent =
                    (preferences.getFloat("cursor_speed", 1.0f) * 100)
                        .toInt()
                        .coerceIn(10, 300),

                cursorLock = preferences.getBoolean("cursor_lock", false),
                xinputDisabled = preferences.getBoolean("xinput_toggle", false),
                enableAutoScraping = preferences.getBoolean("enable_auto_scraping", false),
                enableFileProvider = preferences.getBoolean("enable_file_provider", true),
                openInBrowser = preferences.getBoolean("open_with_android_browser", false),
                shareClipboard = preferences.getBoolean("share_android_clipboard", false),
                enableBackgroundSession = preferences.getBoolean("enable_background_session", false),
                enableAutoPause = preferences.getBoolean("enable_auto_pause_when_background", false),
                useBackgroundWakelock = preferences.getBoolean("enable_background_wakelock", false),
                heartbeatFrequency = preferences.getInt("background_heartbeat_frequency", 0),
                backgroundPauseMode = ProcessHelper.BackgroundPauseMode.fromPrefValue(
                    preferences.getString("background_pause_mode", ProcessHelper.BackgroundPauseMode.GAME_ONLY.prefValue)
                ),
                externalDisplayOutput = preferences.getBoolean("external_display_output", false),
                imagefsInstallProgress = uiState.imagefsInstallProgress,
            )
    }

    private fun loadSoundFontFiles(ctx: Context): List<String> {
        val files = mutableListOf(MidiManager.DEFAULT_SF2_FILE)
        MidiManager.getSoundFontDir(ctx).listFiles()?.forEach { file ->
            if (file.isFile && file.name != MidiManager.DEFAULT_SF2_FILE) {
                files += file.name
            }
        }
        return files
    }

    private fun resolvePathString(
        uriStr: String?,
        fallback: String,
        ctx: Context,
    ): String {
        if (uriStr.isNullOrEmpty()) return fallback
        return try {
            val uri = Uri.parse(uriStr)
            FileUtils.getFilePathFromUri(ctx, uri) ?: uriStr
        } catch (_: Exception) {
            uriStr
        }
    }

    private fun startImagefsReinstall() {
        val act = activity ?: return
        // Seed the dialog immediately at 0% so the Compose progress dialog shows up.
        uiState = uiState.copy(imagefsInstallProgress = 0)
        val main = Handler(Looper.getMainLooper())
        ImageFsInstaller.installFromAssets(
            act,
            object : ImageFsInstaller.ProgressListener {
                override fun onProgress(percent: Int) {
                    main.post {
                        uiState = uiState.copy(imagefsInstallProgress = percent.coerceIn(0, 100))
                    }
                }

                override fun onFinished(success: Boolean) {
                    main.post {
                        if (success) {
                            uiState = uiState.copy(imagefsInstallProgress = 100)
                            main.postDelayed(
                                { uiState = uiState.copy(imagefsInstallProgress = null) },
                                500L,
                            )
                        } else {
                            uiState = uiState.copy(imagefsInstallProgress = null)
                        }
                    }
                }
            },
        )
    }

    private fun pickStoredFolder(
        preferenceKey: String,
        fallbackPath: String,
    ) {
        val hostActivity = activity ?: return
        val currentPath = resolvePathString(preferences.getString(preferenceKey, null), fallbackPath, hostActivity)
        DirectoryPickerDialog.show(
            activity = hostActivity,
            initialPath = currentPath,
        ) { path ->
            preferences.edit {
                putString(preferenceKey, Uri.fromFile(File(path)).toString())
            }
            refresh()
        }
    }

    private fun exportAllShortcuts(ctx: Context) {
        Thread {
            val count = FrontendExporter.exportAll(ctx)
            val dir = FrontendExporter.resolveExportDir(ctx)
            activity?.runOnUiThread {
                if (count > 0) {
                    WinToast.show(ctx, getString(R.string.shortcuts_export_all_done, count, dir?.path ?: ""))
                } else {
                    WinToast.show(ctx, R.string.shortcuts_export_all_none)
                }
            }
        }.start()
    }

    private fun installSoundFont(uri: Uri) {
        val ctx = context ?: return
        val dialog = PreloaderDialog(requireActivity())
        dialog.showOnUiThread(R.string.settings_audio_installing_soundfont)
        MidiManager.installSF2File(
            ctx,
            uri,
            object : MidiManager.OnSoundFontInstalledCallback {
                override fun onSuccess() {
                    dialog.closeOnUiThread()
                    requireActivity().runOnUiThread {
                        ContentDialog.alert(ctx, R.string.settings_audio_sound_font_installed_success, null)
                        refresh()
                    }
                }

                override fun onFailed(reason: Int) {
                    dialog.closeOnUiThread()
                    val resId =
                        when (reason) {
                            MidiManager.ERROR_BADFORMAT -> R.string.settings_audio_sound_font_bad_format
                            MidiManager.ERROR_EXIST -> R.string.settings_audio_sound_font_already_exist
                            else -> R.string.settings_audio_sound_font_installed_failed
                        }
                    requireActivity().runOnUiThread {
                        ContentDialog.alert(ctx, resId, null)
                    }
                }
            },
        )
    }

    private fun removeSelectedSoundFont() {
        val ctx = context ?: return
        val idx = uiState.soundFontIndex
        if (idx == 0) {
            WinToast.show(ctx, R.string.settings_audio_cannot_remove_default)
            return
        }
        val fileName = uiState.soundFontFiles.getOrNull(idx) ?: return
        ContentDialog.confirm(ctx, R.string.settings_audio_confirm_remove_sound_font) {
            if (MidiManager.removeSF2File(ctx, fileName)) {
                WinToast.show(ctx, R.string.settings_audio_sound_font_removed_success)
                uiState = uiState.copy(soundFontIndex = 0)
                refresh()
            } else {
                WinToast.show(ctx, R.string.settings_audio_sound_font_removed_failed)
            }
        }
    }

    companion object {
        /**
         * Build refresh rate entries for per-game shortcut spinners.
         * Includes "Default (Global)" as the first entry.
         */
        @JvmStatic
        fun buildRefreshRateEntries(activity: Activity): List<String> =
            RefreshRateUtils.buildRefreshRateEntryLabels(
                activity,
                activity.getString(R.string.container_config_refresh_rate_default_global),
            )
    }
}
