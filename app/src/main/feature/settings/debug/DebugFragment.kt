// Settings > Debug fragment — hosts DebugScreen via ComposeView.
package com.winlator.cmod.feature.settings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.app.config.SettingsConfig
import com.winlator.cmod.runtime.system.GeneratedLogTags
import com.winlator.cmod.runtime.system.LogManager
import com.winlator.cmod.app.shell.UnifiedActivity
import com.winlator.cmod.shared.io.AssetPaths
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.StorageUtils
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.util.ArrayUtils
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DebugFragment : Fragment() {
    private lateinit var preferences: SharedPreferences
    private var debugState by mutableStateOf(DebugState())
    private var wineChannelOptions: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(ctx)
        wineChannelOptions = loadWineChannelOptions(ctx)
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
                    val fixedDensity = Density(LocalDensity.current.density, fontScale = 1f)
                    CompositionLocalProvider(LocalDensity provides fixedDensity) {
                        DebugScreen(
                            state = debugState,
                            wineChannelOptions = wineChannelOptions,
                            wineClassOptions = SettingsConfig.WINE_DEBUG_CLASSES,
                            onLoggingEnabledChanged = { checked ->
                                preferences.edit {
                                    putBoolean("enable_application_logging", checked)
                                    if (!checked) {
                                        putBoolean("enable_app_debug", false)
                                        putBoolean("enable_input_logs", false)
                                        putBoolean("enable_download_logs", false)
                                        putBoolean("enable_exit_reason_log", false)
                                        putBoolean("enable_crash_log", false)
                                        putBoolean("enable_filtered_logs", false)
                                        putBoolean("enable_event_watch_log", false)
                                    }
                                }
                                if (!checked) {
                                    com.winlator.cmod.feature.stores.steam.utils.PrefManager.enableSteamLogs = false
                                    com.winlator.cmod.runtime.system.ApplicationLogGate
                                        .setEnabled(false)
                                }

                                refresh()
                            },
                            onLogcatGroupExpandedChanged = { expanded ->
                                preferences.edit { putBoolean("debug_group_logcat_expanded", expanded) }
                                refresh()
                            },
                            onAppDebugChanged = { checked ->
                                preferences.edit { putBoolean("enable_app_debug", checked) }
                                com.winlator.cmod.runtime.system.ApplicationLogGate
                                    .setEnabled(checked)
                                if (checked) {
                                    com.winlator.cmod.runtime.system.LogManager
                                        .startAppLogging(ctx, reset = true)
                                } else {
                                    com.winlator.cmod.runtime.system.LogManager
                                        .stopAppLogging()
                                    com.winlator.cmod.runtime.system.LogManager
                                        .updateLoggingState(ctx)
                                }
                                refresh()
                            },
                            onFilteredLogsChanged = { checked ->
                                preferences.edit { putBoolean("enable_filtered_logs", checked) }
                                if (!checked) {
                                    com.winlator.cmod.runtime.system.LogManager
                                        .clearManualTextFilter()
                                    if (!debugState.eventWatchLog) {
                                        com.winlator.cmod.runtime.system.LogManager.clearManualTextFilter()
                                    }
                                }
                                refresh()
                            },
                            onExitReasonLogChanged = { checked ->
                                preferences.edit { putBoolean("enable_exit_reason_log", checked) }
                                if (checked) {
                                    // Capture previous exit reasons, so the user doesn't need
                                    // to restart the app to check previous reasons.
                                    com.winlator.cmod.runtime.system.LogManager
                                        .logLastExitReasons(ctx)
                                }
                                refresh()
                            },
                            onCrashLogChanged = { checked ->
                                preferences.edit { putBoolean("enable_crash_log", checked) }
                                if (checked) {
                                    // Capture previous crashes, so the user doesn't need
                                    // to restart to check previous reasons.
                                    com.winlator.cmod.runtime.system.LogManager
                                        .logLastExitReasons(ctx)
                                }
                                refresh()
                            },
                            onEventWatchLogChanged = { checked ->
                                preferences.edit { putBoolean("enable_event_watch_log", checked) }
                                refresh()
                            },
                            onTagFilterModeChanged = { mode -> LogManager.setTagFilterMode(requireContext(), mode); refresh() },
                            onSelectedTagsChanged = { tags -> LogManager.setSelectedTags(requireContext(), tags.toSet()); refresh() },
                            onAddCustomTag = { tag -> LogManager.addCustomTag(requireContext(), tag); refresh() },
                            onRemoveCustomTag = { tag -> LogManager.removeCustomTag(requireContext(), tag); refresh() },
                            onManualTextFilterChanged = { text -> LogManager.setManualTextFilter(text) }, // no persistence, no refreshState needed
                            allLogTagOptions = remember(debugState.customTags) { LogManager.getAllKnownTags() },
                            onExitGroupExpandedChanged = { expanded ->
                                preferences.edit { putBoolean("debug_group_exit_expanded", expanded) }
                                refresh()
                            },
                            onFilterGroupExpandedChanged = { expanded ->
                                preferences.edit { putBoolean("debug_group_filter_expanded", expanded) }
                                refresh()
                            },
                            onWineDebugChanged = { checked ->
                                preferences.edit { putBoolean("enable_wine_debug", checked) }
                                com.winlator.cmod.runtime.system.LogManager
                                    .updateLoggingState(ctx)
                                refresh()
                            },
                            onWineChannelsChanged = { channels ->
                                preferences.edit { putString("wine_debug_channels", channels.joinToString(",")) }
                                refresh()
                            },
                            onWineClassesChanged = { classes ->
                                preferences.edit { putString("wine_debug_classes", classes.joinToString(",")) }
                                refresh()
                            },
                            onResetWineChannels = {
                                val defaults =
                                    SettingsConfig.DEFAULT_WINE_DEBUG_CHANNELS
                                        .split(",")
                                        .filter { it.isNotBlank() }
                                preferences.edit { putString("wine_debug_channels", defaults.joinToString(",")) }
                                refresh()
                            },
                            onRemoveWineChannel = { channel ->
                                val remaining = debugState.wineChannels.filterNot { it == channel }
                                preferences.edit { putString("wine_debug_channels", remaining.joinToString(",")) }
                                refresh()
                            },
                            onEmulatorLogsChanged = { checked ->
                                preferences.edit { putBoolean("enable_emulator_logs", checked) }
                                com.winlator.cmod.runtime.system.LogManager
                                    .updateLoggingState(ctx)
                                refresh()
                            },
                            onSteamLogsChanged = { checked ->
                                com.winlator.cmod.feature.stores.steam.utils.PrefManager.enableSteamLogs = checked
                                if (checked &&
                                    timber.log.Timber
                                        .forest()
                                        .isEmpty()
                                ) {
                                    timber.log.Timber.plant(timber.log.Timber.DebugTree())
                                }
                                if (checked) ensureAppLoggingOn()
                                com.winlator.cmod.runtime.system.LogManager
                                    .updateLoggingState(ctx)
                                refresh()
                            },
                            onInputLogsChanged = { checked ->
                                preferences.edit { putBoolean("enable_input_logs", checked) }
                                if (checked) ensureAppLoggingOn()
                                com.winlator.cmod.runtime.system.LogManager
                                    .updateLoggingState(ctx)
                                refresh()
                            },
                            onDownloadLogsChanged = { checked ->
                                preferences.edit { putBoolean("enable_download_logs", checked) }
                                if (checked) ensureAppLoggingOn()
                                com.winlator.cmod.runtime.system.LogManager
                                    .updateLoggingState(ctx)
                                refresh()
                            },
                            onRecordPerformanceToFileChanged = { checked ->
                                preferences.edit { putBoolean("hud_record_to_file", checked) }
                                refresh()
                            },
                            onVulkanValidationLayersChanged = { checked ->
                                preferences.edit { putBoolean("enable_vulkan_validation_layers", checked) }
                                refresh()
                            },
                            onWnHybridModeChanged = { checked ->
                                com.winlator.cmod.feature.stores.steam.utils.PrefManager
                                    .wnHybridMode = checked
                                com.winlator.cmod.feature.stores.steam.service
                                    .SteamService
                                    .setHybridModeRuntime(checked)
                                WinToast.show(
                                    ctx,
                                    if (checked) {
                                        "WN Hybrid mode ON — libsteamclient.so is now the Steam session"
                                    } else {
                                        "WN Hybrid mode OFF — wn-steam-client resumed"
                                    },
                                )
                                refresh()
                            },
                            onShareLogs = { shareLogs() },
                            onDownloadLogs = { downloadLogs() },
                            onDeleteLogs = { deleteLogs() },
                            onListLogFiles = { listLogFiles() },
                            onReadLogFile = { entry -> readLogFile(entry) },
                            onShareLogFile = { entry -> shareLogFile(entry) },
                            onDownloadLogFile = { entry -> downloadLogFile(entry) },
                            onDeleteLogFile = { entry -> deleteLogFile(entry) },
                            bridge = (requireActivity() as? UnifiedActivity)?.settingsNavBridge,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val channels =
            preferences
                .getString(
                    "wine_debug_channels",
                    SettingsConfig.DEFAULT_WINE_DEBUG_CHANNELS,
                )?.split(",")
                ?.filter { it.isNotBlank() } ?: emptyList()
        val classes =
            preferences
                .getString(
                    "wine_debug_classes",
                    SettingsConfig.DEFAULT_WINE_DEBUG_CLASSES,
                )?.split(",")
                ?.filter { it.isNotBlank() } ?: emptyList()
        debugState =
            DebugState(
                loggingEnabled = preferences.getBoolean("enable_application_logging", false),
                appDebug = preferences.getBoolean("enable_app_debug", false),
                filteredLogs = preferences.getBoolean("enable_filtered_logs", false),
                exitReasonLog = preferences.getBoolean("enable_exit_reason_log", false),
                crashLog = preferences.getBoolean("enable_crash_log", false),
                eventWatchLog = preferences.getBoolean("enable_event_watch_log", false),
                tagFilterMode = LogManager.getTagFilterMode(),
                selectedTags = LogManager.getSelectedTags().toList(),
                customTags = LogManager.getCachedCustomTags(),

                logcatGroupExpanded = preferences.getBoolean("debug_group_logcat_expanded", false),
                exitGroupExpanded = preferences.getBoolean("debug_group_exit_expanded", false),
                filterGroupExpanded = preferences.getBoolean("debug_group_filter_expanded", false),

                wineDebug = preferences.getBoolean("enable_wine_debug", false),
                wineChannels = channels,
                wineClasses = classes,
                emulatorLogs = preferences.getBoolean("enable_emulator_logs", false),
                steamLogs = com.winlator.cmod.feature.stores.steam.utils.PrefManager.enableSteamLogs,
                inputLogs = preferences.getBoolean("enable_input_logs", false),
                downloadLogs = preferences.getBoolean("enable_download_logs", false),
                recordPerformanceToFile = preferences.getBoolean("hud_record_to_file", false),
                vulkanValidationLayers = preferences.getBoolean("enable_vulkan_validation_layers", false),
                wnHybridMode = com.winlator.cmod.feature.stores.steam.utils.PrefManager.wnHybridMode,
                logsSize =
                    StorageUtils.formatDecimalSize(
                        com.winlator.cmod.runtime.system.LogManager
                            .getShareableLogsSize(requireContext()),
                    ),
            )
    }

    private fun loadWineChannelOptions(ctx: android.content.Context): List<String> {
        val jsonArray =
            runCatching {
                JSONArray(FileUtils.readString(ctx, AssetPaths.WINE_DEBUG_CHANNELS))
            }.getOrNull() ?: return emptyList()
        return ArrayUtils.toStringArray(jsonArray).toList()
    }

    private fun shareLogs() {
        val ctx = requireContext()
        val files =
            com.winlator.cmod.runtime.system.LogManager
                .getShareableLogFiles(ctx)

        if (files.isEmpty()) {
            WinToast.show(ctx, R.string.settings_debug_no_logs_available)
            return
        }

        try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val zipFile = File(ctx.cacheDir, "winnative_logs_$timestamp.zip")
            writeLogsZip(zipFile, files)

            lastSharedLogFile = zipFile

            Handler(Looper.getMainLooper()).postDelayed({
                if (zipFile.exists() && lastSharedLogFile == zipFile) {
                    zipFile.delete()
                    if (lastSharedLogFile == zipFile) lastSharedLogFile = null
                }
            }, 3 * 60 * 1000)

            val authority = "${ctx.packageName}.tileprovider"
            val uri = FileProvider.getUriForFile(ctx, authority, zipFile)
            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_debug_logs_subject, timestamp))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.settings_debug_share_logs)))

            Handler(Looper.getMainLooper()).postDelayed({ cleanupSharedLogs() }, 3 * 60 * 1000L)
        } catch (e: Exception) {
            WinToast.show(ctx, getString(R.string.settings_debug_capture_failed, e.message ?: ""))
        }
    }

    private fun logsDownloadDir(): File {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "WinNative/logs")
        dir.mkdirs()
        return dir
    }

    private fun logDownloadKey(file: File): String = "${file.name}@${file.lastModified()}"

    private fun downloadedLogKeys(): Set<String> =
        preferences.getStringSet(KEY_DOWNLOADED_LOGS, emptySet()) ?: emptySet()

    private fun markLogDownloaded(file: File) {
        val set = HashSet(downloadedLogKeys())
        if (set.add(logDownloadKey(file))) {
            preferences.edit { putStringSet(KEY_DOWNLOADED_LOGS, set) }
        }
    }

    private fun writeLogsZip(
        dest: File,
        files: Array<File>,
    ) {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            files.forEach { file ->
                if (file.isFile) {
                    zos.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun downloadLogs(): String? {
        val ctx = requireContext()
        val files =
            com.winlator.cmod.runtime.system.LogManager
                .getShareableLogFiles(ctx)

        if (files.isEmpty()) {
            WinToast.show(ctx, R.string.settings_debug_no_logs_available)
            return null
        }

        return try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val dest = File(logsDownloadDir(), "winnative_logs_$timestamp.zip")
            writeLogsZip(dest, files)
            "/WinNative/logs/${dest.name}"
        } catch (e: Exception) {
            WinToast.show(ctx, getString(R.string.settings_debug_capture_failed, e.message ?: ""))
            null
        }
    }

    private fun deleteLogs() {
        val ctx = requireContext()
        val files =
            com.winlator.cmod.runtime.system.LogManager
                .getShareableLogFiles(ctx)

        if (files.isEmpty()) {
            WinToast.show(ctx, R.string.settings_debug_no_logs_available)
            return
        }

        com.winlator.cmod.runtime.system.LogManager
            .deleteShareableLogs(ctx)
        preferences.edit { remove(KEY_DOWNLOADED_LOGS) }
        WinToast.show(ctx, R.string.settings_debug_logs_deleted)
        refresh()
    }

    /** Log files, newest first. */
    private fun listLogFiles(): List<LogFileEntry> {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val downloadedKeys = downloadedLogKeys()
        return com.winlator.cmod.runtime.system.LogManager
            .getShareableLogFiles(requireContext())
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                LogFileEntry(
                    name = file.name,
                    sizeText = StorageUtils.formatDecimalSize(file.length()),
                    dateText = dateFormat.format(java.util.Date(file.lastModified())),
                    absolutePath = file.absolutePath,
                    downloaded = downloadedKeys.contains(logDownloadKey(file)),
                )
            }
    }

    /** Reads a log file, capped at the last 512 KB (the tail). */
    private fun readLogFile(entry: LogFileEntry): String {
        val file = File(entry.absolutePath)
        if (!file.isFile) return ""
        val cap = 512L * 1024
        return try {
            if (file.length() <= cap) {
                file.readText()
            } else {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - cap)
                    val bytes = ByteArray(cap.toInt())
                    raf.readFully(bytes)
                    getString(R.string.settings_debug_log_truncated, StorageUtils.formatDecimalSize(cap)) +
                        "\n\n" + String(bytes)
                }
            }
        } catch (e: Exception) {
            getString(R.string.settings_debug_capture_failed, e.message ?: "")
        }
    }

    /** Shares one log file. */
    private fun shareLogFile(entry: LogFileEntry) {
        val ctx = requireContext()
        val file = File(entry.absolutePath)
        if (!file.isFile) {
            WinToast.show(ctx, R.string.settings_debug_no_logs_available)
            return
        }
        try {
            val authority = "${ctx.packageName}.tileprovider"
            val uri = FileProvider.getUriForFile(ctx, authority, file)
            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.settings_debug_share_logs)))
        } catch (e: Exception) {
            WinToast.show(ctx, getString(R.string.settings_debug_capture_failed, e.message ?: ""))
        }
    }

    /** Application logs carry their capture time in the saved name so re-saving after a new
     *  session doesn't overwrite the previous copy. */
    private fun exportFileName(file: File): String {
        if (!file.name.startsWith("application")) return file.name
        val stamp =
            java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date(file.lastModified()))
        val dot = file.name.lastIndexOf('.')
        return if (dot <= 0) {
            "${file.name}_$stamp"
        } else {
            "${file.name.substring(0, dot)}_$stamp${file.name.substring(dot)}"
        }
    }

    private fun downloadLogFile(entry: LogFileEntry): String? {
        val ctx = requireContext()
        val file = File(entry.absolutePath)
        if (!file.isFile) {
            WinToast.show(ctx, R.string.settings_debug_no_logs_available)
            return null
        }
        return try {
            val dest = File(logsDownloadDir(), exportFileName(file))
            file.inputStream().use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
            markLogDownloaded(file)
            "/WinNative/logs/${dest.name}"
        } catch (e: Exception) {
            WinToast.show(ctx, getString(R.string.settings_debug_capture_failed, e.message ?: ""))
            null
        }
    }

    /** Deletes one log file. */
    private fun deleteLogFile(entry: LogFileEntry) {
        val file = File(entry.absolutePath)
        val key = logDownloadKey(file)
        file.delete()
        val set = HashSet(downloadedLogKeys())
        if (set.remove(key)) preferences.edit { putStringSet(KEY_DOWNLOADED_LOGS, set) }
        refresh()
    }

    // Steam/input/download logs are only flags on log statements that land in the application
    // log capture, so they produce nothing unless application logging is running.
    private fun ensureAppLoggingOn() {
        if (preferences.getBoolean("enable_app_debug", false)) return
        preferences.edit { putBoolean("enable_app_debug", true) }
        com.winlator.cmod.runtime.system.ApplicationLogGate
            .setEnabled(true)
        com.winlator.cmod.runtime.system.LogManager
            .startAppLogging(requireContext(), reset = false)
    }

    companion object {
        private const val KEY_DOWNLOADED_LOGS = "downloaded_log_keys"

        @Volatile
        var lastSharedLogFile: File? = null

        /** Call when starting a new game or after 3min timeout to clean up shared logs. */
        fun cleanupSharedLogs() {
            lastSharedLogFile?.let { file ->
                if (file.exists()) file.delete()
                lastSharedLogFile = null
            }
        }
    }
}
