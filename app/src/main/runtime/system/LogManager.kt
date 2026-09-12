package com.winlator.cmod.runtime.system
import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import com.winlator.cmod.app.config.SettingsConfig
import com.winlator.cmod.shared.io.FileUtils
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.util.Date
import java.util.Locale
import androidx.core.content.edit
import androidx.core.net.toUri
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object LogManager {
    private const val TAG = "LogManager"
    private const val APP_LOG_FILE = "app_filtered-logs.log"
    private const val EXIT_REASONS_FILE = "exit_reasons.log"
    private const val CRASH_FILE = "crash.log"
    private const val POST_MORTEM_FILE = "post_mortem.log"

    private var logcatProcess: Process? = null
    private var appLogProcess: Process? = null
    private var eventWatchProcess: Process? = null

    private val logTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    enum class Level(val prefix: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    enum class TagFilterMode { ALL, INCLUDE, EXCLUDE }

    // Fixed diagnostic baseline always present in an event-watch capture,
    // independent of the app-tag filter — these are system components, not
    // app classes, so they don't belong in the same selectable list.
    // Key = display name / selectable tag; value = logcat priority level.
    // Stored as a map so the priority suffix is only applied when building
    // the filterspec, not shown in the UI.
    private val BASELINE_SYSTEM_TAGS: Map<String, String> = linkedMapOf(
        "ActivityManager"   to "I",
        "ActivityTaskManager" to "I",
        "OomAdjuster"       to "I",
        "lmkd"              to "I",
        "Process"           to "I",
    )

    // Developer-curated tags that always appear in the selectable list,
    // supplementing GeneratedLogTags (auto-discovered via Gradle) and
    // user-added custom tags. Add entries here for tags that matter for
    // debugging but may not be auto-discovered (e.g. tags in native code
    // or tags used only in rarely-executed paths).
    private val DEVELOPER_TAGS: Set<String> = setOf(
        "WinlatorLifecycle",
        "OomProtectCheck",
        "GuestProgramLauncherComponent",
        "XServerLeakCheck",
    )

    private const val EVENT_WATCH_TIMEOUT_MS = 90 * 60 * 1000L // 1.5 hours
    private val watchTimeoutHandler = Handler(Looper.getMainLooper())
    private val stopWatchTask = Runnable {
        Timber.i("Event watch timeout reached. Stopping.")
        stopEventWatch()

        // Auto-disable the toggle in preferences/UI just in case the user may have forgotten
        // that the logger was enabled, to avoid wasting battery life.
        appContext?.let { context ->
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putBoolean(PREF_ENABLE_EVENT_WATCH_LOG, false)
            }
        }
    }


    private const val PREF_ENABLE_APP_DEBUG = "enable_app_debug"
    private const val PREF_ENABLE_FILTERED_LOG = "enable_filtered_logs"
    private const val PREF_ENABLE_EXIT_REASON_LOG = "enable_exit_reason_log"
    private const val PREF_ENABLE_CRASH_LOG = "enable_crash_log"
    private const val PREF_ENABLE_EVENT_WATCH_LOG = "enable_event_watch_log"
    private const val PREF_TAG_FILTER_MODE = "log_tag_filter_mode"
    private const val PREF_SELECTED_TAGS = "app_debug_tags"
    private const val PREF_CUSTOM_TAGS = "app_debug_custom_tags"
    private const val PREF_LOGGED_EXIT_KEYS = "logged_exit_keys"
    private const val PREF_POST_MORTEM_KEYS = "post_mortem_keys"

    // ── Cached state ──────────────────────────────────────────────────
    //
    // The whole point of this section: nothing below should ever hit
    // SharedPreferences or resolve a URI on a per-log-call basis. Both are
    // read once and kept current by a listener, so a disabled or filtered-out
    // call costs one volatile-field read, not a disk lookup.

    @Volatile private var appContext: Context? = null
    @Volatile
    var cachedAppDebugEnabled = false
    @Volatile
    var cachedFilteredLogEnabled = false
    @Volatile private var cachedExitReasonLogEnabled = false
    @Volatile private var cachedCrashLogEnabled = false
    @Volatile var cachedEventWatchEnabled = false
    @Volatile private var cachedTagFilterMode = TagFilterMode.ALL
    @Volatile private var cachedSelectedTags: Set<String> = emptySet()
    @Volatile private var cachedCustomTags: Set<String> = emptySet()

    @Volatile private var manualTextFilter: String? = null
    @Volatile private var manualTextFilterPattern: Regex? = null

    @Volatile private var cachedPrivateLogsDir: File? = null
    @Volatile private var cachedExternalLogsDir: File? = null

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val RELEVANT_KEYS = setOf(
        PREF_ENABLE_APP_DEBUG, PREF_ENABLE_FILTERED_LOG, PREF_ENABLE_EXIT_REASON_LOG,
        PREF_ENABLE_CRASH_LOG, PREF_ENABLE_EVENT_WATCH_LOG, PREF_TAG_FILTER_MODE,
        PREF_SELECTED_TAGS, PREF_CUSTOM_TAGS, "winlator_path_uri",
    )

    /** Cheap, public, and the recommended guard for any genuinely expensive log message. */
    @JvmStatic
    val isDebugEnabled: Boolean get() = cachedAppDebugEnabled || cachedFilteredLogEnabled || cachedEventWatchEnabled
    @JvmStatic
    val isEventWatchEnabled: Boolean get() = cachedEventWatchEnabled

    private var crashHandlerInitialized = false

    private fun resolveContext(context: Context?): Context? = context?.applicationContext ?: appContext

    // Every log file carries the date/time it was created, the same way SessionLogWriter names the
    // box64/fex/wine logs. Resolved once per process so appends keep landing in the same file.
    private val sessionStamp: String by lazy {
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US))
    }
    private val stampedNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Exit reasons that constitute an abnormal termination and trigger a
     * post-mortem report. Add entries here to cover new cases to be auto-reported.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private val POST_MORTEM_REASONS: Set<Int> = setOf(
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        /*ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,*/
    )

    /**
     * Call once, ideally from PluviaApp.onCreate(), so every later call
     * site — including ones with no Context of their own — has a fallback,
     * and so the debug/path-dependent caches above are primed before
     * anything tries to log.
     */
    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        refreshCaches(app)

        if (prefsListener == null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key in RELEVANT_KEYS) refreshCaches(app)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            prefsListener = listener
        }

//        Log.d(TAG, "LogManager initialized, context name=${app.javaClass.name}, appContext=$appContext")

        // Set up uncaught exception handler
        if (!crashHandlerInitialized) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                if (cachedCrashLogEnabled) {
                    logCrash(app, thread, throwable)
                }
                // Call the original handler to maintain default behavior
                defaultHandler?.uncaughtException(thread, throwable)
            }
            crashHandlerInitialized = true
        }

        // Move heavy I/O processing (exit reasons, ANR traces, post-mortems)
        // to a dedicated background thread to prevent ANRs in onCreate.
        Thread({
            logLastExitReasons(app)
            runPostMortemIfNeeded(app)      // Capture previous exit reasons if toggle is enabled.
        }, "LogManager-StartupIO").start()    // Capture unexpected crashes at start if crash toggle is disabled.
    }

    private fun refreshCaches(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        cachedAppDebugEnabled = prefs.getBoolean(PREF_ENABLE_APP_DEBUG, false)
        cachedFilteredLogEnabled = prefs.getBoolean(PREF_ENABLE_FILTERED_LOG, false)
        cachedExitReasonLogEnabled = prefs.getBoolean(PREF_ENABLE_EXIT_REASON_LOG, false)
        cachedCrashLogEnabled = prefs.getBoolean(PREF_ENABLE_CRASH_LOG, false)
        cachedEventWatchEnabled = prefs.getBoolean(PREF_ENABLE_EVENT_WATCH_LOG, false)
        cachedTagFilterMode = runCatching {
            TagFilterMode.valueOf(prefs.getString(PREF_TAG_FILTER_MODE, null) ?: TagFilterMode.ALL.name)
        }.getOrDefault(TagFilterMode.ALL)
        cachedSelectedTags = splitPref(prefs, PREF_SELECTED_TAGS)
        cachedCustomTags = splitPref(prefs, PREF_CUSTOM_TAGS)
        cachedPrivateLogsDir = resolveLogsDir(context, prefs)
        cachedExternalLogsDir = resolveLogsDir(context, prefs, true)
    }

    private fun splitPref(prefs: SharedPreferences, key: String): Set<String> =
        prefs.getString(key, null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    // ── Logs directory ───────────────────────────────────────────────

    @JvmStatic
    fun getLogsDir(context: Context, isExternal: Boolean = false): File {
        if (isExternal)
            cachedExternalLogsDir?.let { return it }
        else
            cachedPrivateLogsDir?.let { return it }

        val ctx = resolveContext(context) ?: return File(SettingsConfig.DEFAULT_WINLATOR_PATH, "logs").also {
            // No context available anywhere yet (init() never called and none
            // passed in) — fall back without caching, since we can't listen
            // for preference changes without one.
            if (!it.exists()) it.mkdirs()
        }

        val dir = resolveLogsDir(ctx, PreferenceManager.getDefaultSharedPreferences(ctx), isExternal)
        if (isExternal) cachedExternalLogsDir = dir else cachedPrivateLogsDir = dir

        Timber.tag(TAG).d("Logs dir: $dir")

        return dir
    }

    private fun resolveLogsDir(context: Context, prefs: SharedPreferences, isExternal: Boolean = false): File {
        val currentPath: File = if (isExternal) {
            File(
                resolvePathString(
                    prefs.getString("winlator_path_uri", null),
                    SettingsConfig.DEFAULT_WINLATOR_PATH,
                    context
                )
            )
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }

        Timber.d("Winnative pathString: $currentPath")

        val dir = File(currentPath, "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun resolvePathString(uriStr: String?, fallback: String, ctx: Context): String {
        if (uriStr.isNullOrEmpty()) return fallback
        return try {
            val uri = uriStr.toUri()
            FileUtils.getFilePathFromUri(ctx, uri) ?: fallback
        } catch (e: Exception) {
            logW(TAG,e, ctx) { "Failed to resolve winlator_path_uri ($uriStr): ${e.message}" }
            fallback
        }
    }

    fun isAnyLoggingEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("enable_wine_debug", false) ||
            prefs.getBoolean("enable_emulator_logs", false) ||
            prefs.getBoolean("enable_steam_logs", false) ||
            prefs.getBoolean("enable_input_logs", false) ||
            prefs.getBoolean("enable_download_logs", false) ||
            cachedFilteredLogEnabled ||
            cachedEventWatchEnabled ||
            cachedExitReasonLogEnabled ||
            cachedCrashLogEnabled ||
            cachedAppDebugEnabled
    }

    fun updateLoggingState(context: Context) {
        if (!isAnyLoggingEnabled(context)) {
            stopLogging()
        }
    }

    // Container boot only (XServerDisplayActivity), never app startup: a booting session starts
    // from clean logs, while a plain cold start keeps whatever the previous runs wrote.
    @JvmStatic
    fun prepareForNewSession(context: Context) {
        stopAppLogging()
        val logsDir = getLogsDir(context)
        logsDir.listFiles()?.filter { it.name.endsWith(".log") }?.forEach { it.delete() }
        startAppLogging(context, reset = true)
    }

    private fun stamped(baseName: String): String {
        return stampedNames.getOrPut(baseName) {
            val dot = baseName.lastIndexOf('.')
            if (dot > 0) {
                "${baseName.substring(0, dot)}_$sessionStamp${baseName.substring(dot)}"
            } else {
                "${baseName}_$sessionStamp.log"
            }
        }
    }

    /** True when any run's copy of [baseName] exists, since the name now carries a timestamp. */
    private fun anyLogExists(logsDir: File, baseName: String): Boolean {
        val dot = baseName.lastIndexOf('.')
        val prefix = if (dot > 0) baseName.substring(0, dot) else baseName
        return logsDir.listFiles()?.any { it.isFile && it.name.startsWith("${prefix}_") } == true
    }

    // ── Tag management (settings UI surface) ──────────────────────────

    /** Union of build-time-discovered tags and user-added custom ones, sorted for display. */
    @JvmStatic
    fun getAllKnownTags(): List<String> =
        (GeneratedLogTags.TAGS + DEVELOPER_TAGS + BASELINE_SYSTEM_TAGS.keys + cachedCustomTags)
            .distinct()
            .sorted()

    @JvmStatic
    fun getCachedCustomTags(): List<String> = cachedCustomTags.sorted()

    @JvmStatic
    fun addCustomTag(context: Context, tag: String) {
        val cleaned = tag.trim()
        if (cleaned.isEmpty()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val updated = cachedCustomTags + cleaned
        prefs.edit { putString(PREF_CUSTOM_TAGS, updated.joinToString(",")) }
    }

    @JvmStatic
    fun removeCustomTag(context: Context, tag: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val updatedCustomTags = cachedCustomTags - tag
        val updatedSelectedTags = cachedSelectedTags - tag // deselect too — a removed tag can't stay selected
        prefs.edit {
            putString(PREF_CUSTOM_TAGS, updatedCustomTags.joinToString(","))
                .putString(PREF_SELECTED_TAGS, updatedSelectedTags.joinToString(","))
        }
    }

    @JvmStatic
    fun setSelectedTags(context: Context, tags: Set<String>) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_SELECTED_TAGS, tags.joinToString(","))
        }
    }

    @JvmStatic
    fun getSelectedTags(): Set<String> = cachedSelectedTags

    @JvmStatic
    fun setTagFilterMode(context: Context, mode: TagFilterMode) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_TAG_FILTER_MODE, mode.name)
        }
    }

    @JvmStatic
    fun getTagFilterMode(): TagFilterMode = cachedTagFilterMode

    @JvmStatic
    fun getSystemTags(): Set<String> = BASELINE_SYSTEM_TAGS.keys.toSet()

    /** Transient only — never written to SharedPreferences. Pass null/blank to clear. */
    @JvmStatic
    fun setManualTextFilter(text: String?) {
        val input = text?.trim()?.takeIf { it.isNotEmpty() }
        manualTextFilter = input
        manualTextFilterPattern = input?.let {
            try {
                // Attempt to compile as a case-insensitive regex
                Regex(it, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                // If regex is invalid (e.g. "C++"), treat as a literal by escaping metacharacters
                Regex(Regex.escape(it), RegexOption.IGNORE_CASE)
            }
        }
    }

    @JvmStatic
    fun getManualTextFilter(): String = manualTextFilter ?: ""

    @JvmStatic
    fun clearManualTextFilter() = setManualTextFilter(null)

    private fun passesTagFilter(tag: String): Boolean = when (cachedTagFilterMode) {
        TagFilterMode.ALL -> true
        TagFilterMode.INCLUDE -> tag in cachedSelectedTags
        TagFilterMode.EXCLUDE -> tag !in cachedSelectedTags
    }

    // ── Wine/Box64 Logcat Capture ────────────────────────────────────

    fun startLogging(context: Context) {
        if (!isAnyLoggingEnabled(context)) {
            stopLogging()
            return
        }

        val logFile = File(getLogsDir(context), stamped("logcat.log"))

        try {
            stopLogcat()
            runBlockingLogcatCommand(arrayOf("logcat", "-c"))
            logcatProcess =
                Runtime.getRuntime().exec(
                    arrayOf("logcat", "-f", logFile.absolutePath, "-r", "16384", "-n", "4", "*:D"),
                )
            closeProcessStdin(logcatProcess)
        } catch (e: Exception) {
            logE(TAG,e, context) { "Failed to start logcat: ${e.message}" }
        }
    }

    fun stopLogging() {
        stopLogcat()
        stopAppLogging()
    }

    private fun stopLogcat() {
        try {
            logcatProcess?.let(::destroyProcess)
            logcatProcess = null
        } catch (e: Exception) {
            logE(TAG,e) { "Failed to stop logcat: ${e.message}" }
        }
    }

    fun clearLogs(context: Context) {
        // Clean both directories
        getLogsDir(context, true).listFiles()?.forEach { it.delete() }
        getLogsDir(context, false).listFiles()?.forEach { it.delete() }
    }

    @JvmStatic
    @JvmOverloads
    fun startAppLogging(context: Context, reset: Boolean = false) {
        if (!cachedAppDebugEnabled) return
        val logFile = File(getLogsDir(context), stamped("application.log"))

        try {
            stopAppLogging()
            if (reset) {
                logFile.delete()
                runBlockingLogcatCommand(arrayOf("logcat", "-c"))
            }
            val pid = android.os.Process.myPid()
            appLogProcess =
                Runtime.getRuntime().exec(
                    arrayOf("logcat", "-f", logFile.absolutePath, "-r", "8192", "-n", "2", "--pid=$pid", "*:W"),
                )
            closeProcessStdin(appLogProcess)
            Timber.i("Application debug logging started (PID=$pid)")
        } catch (e: Exception) {
            logE(TAG,e) { "Failed to start application logging: ${e.message}" }
        }
    }

    @JvmStatic
    fun stopAppLogging() {
        try {
            appLogProcess?.let(::destroyProcess)
            appLogProcess = null
        } catch (e: Exception) {
            logE(TAG,e) { "Failed to stop application logging: ${e.message}" }
        }
    }

    private fun runBlockingLogcatCommand(command: Array<String>) {
        val process = Runtime.getRuntime().exec(command)
        try {
            process.waitFor()
        } finally {
            destroyProcess(process)
        }
    }

    private fun destroyProcess(process: Process) {
        closeProcessStdin(process)
        closeQuietly(process.inputStream)
        closeQuietly(process.errorStream)
        process.destroy()
    }

    private fun closeProcessStdin(process: Process?) {
        closeQuietly(process?.outputStream)
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun getShareableLogFiles(context: Context): Array<File> {
        val filter: (File) -> Boolean = {
            it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".txt") || it.name.endsWith(".csv"))
        }
        // Aggregate files from both the internal and external directories
        val external = getLogsDir(context, true).listFiles()?.filter(filter) ?: emptyList()
        val private = getLogsDir(context, false).listFiles()?.filter(filter) ?: emptyList()
        return (external + private).toTypedArray()
    }

    /** Total bytes of all shareable log files. */
    @JvmStatic
    fun getShareableLogsSize(context: Context): Long {
        // Sum sizes from both internal and external directories
        val internalSize = getLogsDir(context, false).walk().filter { it.isFile }.sumOf { it.length() }
        val externalSize = getLogsDir(context, true).walk().filter { it.isFile }.sumOf { it.length() }
        return internalSize + externalSize
    }

    /** Deletes all shareable log files; returns the count removed. */
    @JvmStatic
    fun deleteShareableLogs(context: Context): Int = getShareableLogFiles(context).count { it.delete() }

    // ── Custom breadcrumbs, callable from anywhere ───────────────
    //
    // Writes directly to disk (open → write → flush → close on every
    // call) instead of going through a buffered writer. This is
    // deliberate: if the process gets killed seconds after this call,
    // an open-but-unflushed buffer would lose exactly the line need.
    // A few extra file opens per session is a non-issue.
    //
    // Message arguments are still evaluated eagerly by the caller
    // for the plain String overloads — for anything expensive to build,
    // guard it with `if (LogManager.isDebugEnabled)`, or use the lambda
    // overload below from Kotlin.

    // ToDo: For the TAG filters to work across the entire app, the following methods must be used to replace Timber or Log lines.

    @JvmStatic @JvmOverloads
    fun log(tag: String, message: String, context: Context? = null) =
        baseLog(Level.DEBUG, tag, message, null, context)

    @JvmStatic @JvmOverloads
    fun logI(tag: String, message: String, context: Context? = null) =
        baseLog(Level.INFO, tag, message, null, context)

    @JvmStatic @JvmOverloads
    fun logW(tag: String, message: String, t: Throwable? = null, context: Context? = null) =
        baseLog(Level.WARN, tag, message, t, context)

    @JvmStatic @JvmOverloads
    fun logE(tag: String, message: String, t: Throwable? = null, context: Context? = null) =
        baseLog(Level.ERROR, tag, message, t, context)

    /**
     * Kotlin-only sugar for genuinely expensive messages: [message] is never
     * invoked at all when debug logging is off. Not exposed to Java —
     * inline functions with function-type parameters don't cross that
     * boundary cleanly; Java callers should use the isDebugEnabled guard
     * instead.
     */
    inline fun log(tag: String, context: Context? = null, message: () -> String) {
        if (!cachedAppDebugEnabled && !cachedFilteredLogEnabled && !cachedEventWatchEnabled) return
        baseLog(Level.DEBUG, tag, message(), null, context)
    }

    inline fun logI(tag: String, context: Context? = null, message: () -> String) {
        if (!cachedAppDebugEnabled && !cachedFilteredLogEnabled && !cachedEventWatchEnabled) return
        baseLog(Level.INFO, tag, message(), null, context)
    }

    inline fun logW(tag: String, t: Throwable? = null, context: Context? = null, message: () -> String) {
        if (!cachedAppDebugEnabled && !cachedFilteredLogEnabled && !cachedEventWatchEnabled) return
        baseLog(Level.WARN, tag, message(), t, context)
    }

    inline fun logE(tag: String, t: Throwable? = null, context: Context? = null, message: () -> String) {
        if (!cachedAppDebugEnabled && !cachedFilteredLogEnabled && !cachedEventWatchEnabled) return
        baseLog(Level.ERROR, tag, message(), t, context)
    }

    fun baseLog(level: Level, tag: String, message: String, t: Throwable?, context: Context?) {
        val hasTree = Timber.forest().isNotEmpty()

        // Mirrors Timber Log so this can drop in for Log.* call sites.
        when (level) {
            Level.DEBUG -> Timber.tag(tag).d(message)
            Level.INFO -> Timber.tag(tag).i(message)
            Level.WARN ->  {
                if (hasTree) {
                    if (t != null) Timber.tag(tag).w(t, message) else Timber.tag(tag).w(message)
                }
                else {
                    if (t != null) Log.w(tag, message, t) else Log.w(tag, message)
                }
            }
            Level.ERROR -> {
                if (hasTree) {
                    if (t != null) Timber.tag(tag).e(t, message) else Timber.tag(tag).e(message)
                }
                else {  // Fallback to Android Log for when Timber is not available
                    if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
                }
            }
        }

        if (!cachedFilteredLogEnabled) return
        if (!passesTagFilter(tag)) return
        manualTextFilterPattern?.let { if (!it.containsMatchIn(message)) return }

        val ctx = resolveContext(context) ?: return
        val fullMessage = if (t != null) "$message :: ${Log.getStackTraceString(t)}" else message
        appendLine(ctx, stamped(APP_LOG_FILE), "${level.prefix}/$tag", fullMessage)
    }

    private fun appendLine(context: Context, fileName: String, level: String, message: String) {
        try {
            val now = LocalDateTime.now().format(logTimestampFormatter)

            // Define high-priority files that go to External storage
            val isExternal =  fileName.startsWith(POST_MORTEM_FILE.substringBeforeLast('.'))

            val dir = getLogsDir(context, isExternal)
            File(dir, fileName).appendText("$now $level: $message\n")
        } catch (e: Exception) {
            // Need to be Log for when baseLog and Timber doesn't work.
            Log.e(TAG, "Failed to append to $fileName: ${e.message}", e)
        }
    }

    // ── Event Watch - window capture ───────────────────────────────
    //
    // Brackets exactly the period you care about: screen-lock to
    // screen-unlock or app backgrounded. Without android.permission.READ_LOGS granted via
    // adb, this only ever sees your own UID's lines (your own Log.*
    // calls, including whatever you route through log()/logWarn()
    // above) — still useful for confirming your own lifecycle order.
    // WITH the permission granted once over adb, it will also surface
        // system lines like ActivityManager's "Killing <proc> (adj N):
    // <reason>" messages, which is the signal of the OS killing a process.

    /**
     * ToDo: This method can be improved so that it can handle multiple events (calls) that the user can selectively manage, similar to how tags work.
     * The best approach would be to use an enum to define what kind of event has been set, replacing the string parameter with the enum (more efficient)
     * and adding new enum values as new events are introduced. In this way, it would be possible to select in the UI exactly which specific event
     * you want to observe. Examples: ContainerBackground, SteamDownloads, Recording, etc.
     */
    @JvmStatic
    fun startEventWatch(context: Context, label: String = "undefined-watcher") {
        if (!cachedEventWatchEnabled) return

        // Verify READ_LOGS permission at runtime
        val hasReadLogs = (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS)
        == PackageManager.PERMISSION_GRANTED)
        if (!hasReadLogs) logI(TAG, context) { "READ_LOGS permission not granted, pause watch may not capture system logs" }

        stopEventWatch()
        Thread({
            var process: Process? = null
            try {
                // Wipe the historical buffer so this file only contains lines from
                // this pause window onward — not hours of unrelated backlog.
                runBlockingLogcatCommand(arrayOf("logcat", "-c"))

                val safeLabel = label.ifBlank { "manual" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US))
                val file = File(getLogsDir(context), "event_${safeLabel}_$stamp.log")
                appendLine(context, file.name, "I/$TAG", "=== event watch started ($safeLabel) ===")

                val command = mutableListOf("logcat", "-v", "threadtime")
                command.addAll(buildLogcatFilterSpecArgs())

                val started = ProcessBuilder(command)
                    .redirectErrorStream(true) // merge stderr so a single reader drains both pipes
                    .start()
                process = started
                eventWatchProcess = started
                closeProcessStdin(started)

                // Schedule a timeout to avoid hanging indefinitely if the user forget to disable this log.
                watchTimeoutHandler.postDelayed(stopWatchTask, EVENT_WATCH_TIMEOUT_MS)

                val textPattern = manualTextFilterPattern // snapshot once for the life of this watch

                started.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    java.io.BufferedWriter(
                        java.io.OutputStreamWriter(java.io.FileOutputStream(file, true), Charsets.UTF_8)
                    ).use { writer ->
                        reader.forEachLine { line ->
                            if (textPattern == null || textPattern.containsMatchIn(line)) {
                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // stopEventWatch() destroys the process to end the read loop above,
                // which normally surfaces here as a routine IOException (e.g.
                // "Stream closed"). Only log when this wasn't an intentional stop.
                if (process == null || eventWatchProcess === process) {
                    logE(TAG, e, context) { "Failed to start event watch: ${e.message}" }
                }
            }
        }, "LogManager-EventWatch").start()
    }

    @JvmStatic
    fun stopEventWatch() {
        // Cancel the pending timeout task
        watchTimeoutHandler.removeCallbacks(stopWatchTask)

        try {
            // Only close stdin and destroy the process here. The capture thread in
            // startEventWatch() owns and actively reads process.inputStream —
            // closing it from this thread too would race with that read.
            // Destroying the process closes its end of the pipe instead, which
            // unblocks the capture thread's read with a clean EOF.
            eventWatchProcess?.let { process ->
                closeProcessStdin(process)
                process.destroy()
            }
            eventWatchProcess = null
        } catch (e: Exception) {
            logE(TAG,e) { "Failed to stop pause watch: ${e.message}" }
        }
    }

    private fun buildLogcatFilterSpecArgs(): List<String> {
        val spec = mutableListOf<String>()
        val selectedBaseline = BASELINE_SYSTEM_TAGS.keys.filter { it in cachedSelectedTags }.toSet()
        val selectedApp = cachedSelectedTags - BASELINE_SYSTEM_TAGS.keys

        when (cachedTagFilterMode) {
            TagFilterMode.ALL -> {
                // Wildcard first as the default floor; explicit baseline rules follow
                // so they take precedence and elevate those tags to their native level.
                // No :S rules anywhere — ALL mode never suppresses anything.
                spec.add("*:D")
                BASELINE_SYSTEM_TAGS.forEach { (tag, priority) -> spec.add("$tag:$priority") }
            }
            TagFilterMode.INCLUDE -> {
                // Wildcard first to suppress everything; selected tags follow to
                // un-suppress themselves by overriding the wildcard.
                spec.add("*:S")
                selectedBaseline.forEach { tag -> spec.add("$tag:${BASELINE_SYSTEM_TAGS[tag]}") }
                selectedApp.forEach { tag -> spec.add("$tag:D") }
            }
            TagFilterMode.EXCLUDE -> {
                // Wildcard first to allow everything; excluded tags follow to suppress
                // themselves, non-excluded baseline tags follow to elevate to native level.
                spec.add("*:D")
                BASELINE_SYSTEM_TAGS.forEach { (tag, priority) ->
                    if (tag in selectedBaseline) spec.add("$tag:S")
                    else spec.add("$tag:$priority")
                }
                selectedApp.forEach { tag -> spec.add("$tag:S") }
            }
        }
        return spec
    }

    // ── Exit/killed reasons | crash trace ──────────────────────────
    //
    // No special permission needed (API 30+). Call once, early, on
    // every app start — it tells you, after the fact, exactly what
    // ended the previous process: REASON_LOW_MEMORY (real LMK kill),
    // REASON_SIGNALED/REASON_OTHER (often an OEM battery manager),
    // REASON_USER_REQUESTED, REASON_CRASH, etc.

    @JvmStatic @JvmOverloads
    fun logLastExitReasons(context: Context? = null) {
        if (!cachedExitReasonLogEnabled && !cachedCrashLogEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ctx = resolveContext(context) ?: return
        val maxExitReasons = 5

        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos: List<ApplicationExitInfo> = am.getHistoricalProcessExitReasons(ctx.packageName, 0, maxExitReasons)

            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val logsDir = getLogsDir(ctx)
            val loggedKeys = getPersistedKeys(prefs, PREF_LOGGED_EXIT_KEYS).toMutableSet()

            // Auto-reset keys when a log file no longer exists — covers manual deletion,
            // clearLogs() calls, and fresh installs without needing external management.
            val originalSize = loggedKeys.size
            if (!anyLogExists(logsDir, EXIT_REASONS_FILE)) loggedKeys.removeAll { it.startsWith("exit_") }
            if (!anyLogExists(logsDir, CRASH_FILE)) loggedKeys.removeAll { it.startsWith("crash_") }

            var wroteSomething = (loggedKeys.size != originalSize)

            if (infos.isEmpty()) {
                // Only write the "no info" placeholder if the file is missing/just cleared.
                if (cachedExitReasonLogEnabled && !anyLogExists(logsDir, EXIT_REASONS_FILE)) {
                    appendLine(ctx, stamped(EXIT_REASONS_FILE), "I/$TAG", "No historical exit info available")
                }
                if (wroteSomething) persistKeys(prefs, PREF_LOGGED_EXIT_KEYS, loggedKeys)
                return
            }

            for ((index, info) in infos.withIndex()) {
                val key = exitKey(info)
                val exitKey = "exit_$key"
                val crashKey = "crash_$key"
                val timestamp = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(info.timestamp), ZoneId.systemDefault()).format(logTimestampFormatter)

                if (cachedExitReasonLogEnabled && exitKey !in loggedKeys) {
                    // Separator line with reason number: 0 = newest/last, larger = older
                    appendLine(
                        ctx, stamped(EXIT_REASONS_FILE), "I/$TAG",
                        "\n---- Exit reason #${index} (0=oldest, ${infos.size-1}=new/last) ----"
                    )

                    appendLine(
                        ctx, stamped(EXIT_REASONS_FILE), "I/$TAG",
                        "pid=${info.pid} reason=${info.reason}-[${getExitReasonName(info.reason)}] status=${info.status} " +
                                "importance=${info.importance} desc=${info.description} timestamp=${timestamp}",
                    )
                    loggedKeys.add(exitKey)
                    wroteSomething = true
                }
                if (cachedCrashLogEnabled && crashKey !in loggedKeys) {
                    val isErrorReport = when (info.reason) {
                        ApplicationExitInfo.REASON_CRASH,
                        ApplicationExitInfo.REASON_CRASH_NATIVE,
                        ApplicationExitInfo.REASON_ANR -> true
                        else -> false
                    }

                    if (isErrorReport) {
                        val type = when (info.reason) {
                            ApplicationExitInfo.REASON_CRASH -> "Java Crash"
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native Crash"
                            ApplicationExitInfo.REASON_ANR -> "ANR"
                            else -> "Critical Error"
                        }

                        try {
                            info.traceInputStream?.use { input ->
                                val rawTrace = input.bufferedReader().readText()
                                val summary = extractTraceExcerpt(rawTrace, info.reason, maxFrames = 20)
                                appendLine(
                                    ctx, stamped(CRASH_FILE), "I/$TAG",
                                    "\n=== Historical $type Detected ===\n" +
                                            "PID: ${info.pid} | Timestamp: ${timestamp}\n" +
                                            "Description: ${info.description}\n" +
                                            "Trace Summary:\n$summary\n" +
                                            "=== End $type Report ==="
                                )
                            } ?: run {
                                // If no stream is available, log what we can
                                appendLine(ctx, stamped(CRASH_FILE), "I/$TAG", "Historical $type (No trace available) pid=${info.pid} desc=${info.description}")
                            }
                        } catch (e: Exception) {
                            logE(TAG,e, context) { "Failed to read historical trace: ${e.message}" }
                        }
                    }
                    // Mark as crash-processed to avoid re-scanning non-crash reasons
                    loggedKeys.add(crashKey)
                    wroteSomething = true
                }
            }

            if (wroteSomething) {
                persistKeys(prefs, PREF_LOGGED_EXIT_KEYS, loggedKeys)
            }
        } catch (e: Exception) {
            logE(TAG,e, context) { "Failed to read exit reasons: ${e.message}" }
        }
    }

    private fun getExitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (LMK)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (KILL)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (e.g. Swipe)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED (Force Stop)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        else -> "UNKNOWN_REASON"
    }

    @JvmStatic
    fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US))
            val fileName = "crashFromThread_$timestamp.log"
            val file = File(getLogsDir(context), fileName)

            val crashInfo = buildString {
                appendLine("=== CRASH DETECTED ===")
                appendLine("Thread: ${thread.name} (ID: ${thread.id})")
                appendLine("Timestamp: ${Date()}")
                appendLine("Exception: ${throwable.javaClass.simpleName}")
                appendLine("Message: ${throwable.message}")
                appendLine("\nStack Trace:")
                appendLine(Log.getStackTraceString(throwable))
                appendLine("\n=== END CRASH ===")
            }

            file.writeText(crashInfo)
            logE(TAG, throwable) { "Crash logged to $fileName" }
        } catch (e: Exception) {
            logE(TAG,e, context) { "Failed to log crash" }
        }
    }

    /**
     * Runs once per app start. Checks whether the previous run ended abnormally
     * and writes a concise diagnostic to post_mortem.log.
     */
    @JvmStatic
    fun runPostMortemIfNeeded(context: Context? = null) {
        if (cachedCrashLogEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ctx = resolveContext(context) ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        // Auto-reset keys when the file no longer exists — covers log deletion,
        // fresh installs, and manual file removal without needing clearLogs().
        if (!anyLogExists(getLogsDir(ctx, true), POST_MORTEM_FILE)) {
            prefs.edit { remove(PREF_POST_MORTEM_KEYS) }
        }

        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
            if (infos.isEmpty()) return

            val loggedKeys = getPersistedKeys(prefs, PREF_POST_MORTEM_KEYS).toMutableSet()
            var wrote = false

            for (info in infos) {
                if (info.reason !in POST_MORTEM_REASONS) continue
                val key = "pm_${exitKey(info)}"
                if (key in loggedKeys) continue

                appendLine(ctx, stamped(POST_MORTEM_FILE), "I/$TAG", buildPostMortemReport(info, ctx))
                loggedKeys.add(key)
                wrote = true
            }
            if (wrote) persistKeys(prefs, PREF_POST_MORTEM_KEYS, loggedKeys)
        } catch (e: Exception) {
            logE(TAG,e, context) { "Post-mortem check failed: ${e.message}" }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildPostMortemReport(info: ApplicationExitInfo, context: Context): String {
        val timestamp = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(info.timestamp), ZoneId.systemDefault()).format(logTimestampFormatter)

        return buildString {
            appendLine("=== POST-MORTEM [${getExitReasonName(info.reason)}] ===")
            appendLine("Time     : $timestamp")
            appendLine("PID      : ${info.pid}")
            appendLine("Reason   : ${info.reason} [${getExitReasonName(info.reason)}]")
            appendLine("Status   : ${info.status}")
            appendLine("Desc     : ${info.description ?: "none"}")
            try {
                val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
                appendLine("Build    : ${pkg.versionName} (${pkg.longVersionCode})")
            } catch (_: Exception) {}
            appendLine("Android  : ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            appendLine("Device   : ${Build.MANUFACTURER} ${Build.MODEL}")
            try {
                info.traceInputStream?.use { stream ->
                    val excerpt = extractTraceExcerpt(
                        stream.bufferedReader().readText(), info.reason, maxFrames = 5
                    )
                    if (excerpt.isNotBlank()) {
                        appendLine("--- Excerpt ---")
                        appendLine(excerpt.trim())
                        appendLine("--- End Excerpt ---")
                    }
                }
            } catch (e: Exception) {
                appendLine("Excerpt  : unavailable (${e.message})")
            }
            append("=== END POST-MORTEM ===")
        }
    }

    // A unique, stable key for one ApplicationExitInfo record.
    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitKey(info: ApplicationExitInfo): String {
        val timestamp = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(info.timestamp), ZoneId.systemDefault()).format(logTimestampFormatter)
        return "${info.pid}_$timestamp"
    }

    private fun getPersistedKeys(prefs: SharedPreferences, prefKey: String): Set<String> =
        prefs.getString(prefKey, null)
            ?.split(",")?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    private fun persistKeys(prefs: SharedPreferences, prefKey: String, keys: Set<String>) {
        prefs.edit {
            // Sort by the numeric timestamp (the last part of the key) instead of alphabetically.
            // This ensures we keep the 20 most recent events regardless of whether they
            // are "exit_" or "crash_".
            val sortedKeys = keys.sortedByDescending { key ->
                key.substringAfterLast('_').toLongOrNull() ?: 0L
            }.take(20)

            putString(prefKey, sortedKeys.joinToString(","))
        }
    }

    // ── Unified trace extraction ──────────────────────────────────────────
    //
    // Used for both the crash log (maxFrames = 20) and the post-mortem
    // (maxFrames = 5). Higher maxFrames → more context; lower → more concise.

    // For avoid useless lines in the crash log.
    private val FRAMEWORK_FRAME_PREFIXES = setOf(
        "at java.", "at kotlin.", "at android.", "at androidx.",
        "at com.android.", "at dalvik.", "at sun.", "at libcore.",
    )

    private fun isFrameworkFrame(line: String): Boolean =
        FRAMEWORK_FRAME_PREFIXES.any { line.trimStart().startsWith(it) }

    private fun extractTraceExcerpt(raw: String, reason: Int, maxFrames: Int = 6): String {
        val lines = raw.lines()
        val concise = maxFrames <= 6

        return when (reason) {
            ApplicationExitInfo.REASON_ANR -> {
                val subject = lines.firstOrNull { it.startsWith("Subject:") }
                // Waiting Channels shows which kernel call each thread is stuck in.
                val channelHeader = lines.firstOrNull { it.contains("Waiting Channels:") }
                val channelLines = lines
                    .dropWhile { !it.contains("Waiting Channels:") }
                    .drop(1)
                    .filter { it.contains("sysTid=") }
                    .take(maxFrames)
                (listOfNotNull(subject, channelHeader) + channelLines).joinToString("\n")
            }

            ApplicationExitInfo.REASON_CRASH -> {
                val out = StringBuilder()
                var inException = false
                var frameCount = 0
                for (line in lines) {
                    val t = line.trimStart()
                    when {
                        !inException && (t.contains("Exception") || t.contains("Error")
                                || t.startsWith("Exception in thread")) -> {
                            inException = true
                            out.appendLine(line)
                        }
                        inException && t.startsWith("at ") && frameCount < maxFrames -> {
                            // Concise: take every frame to stay within maxFrames.
                            // Verbose: skip pure framework frames to highlight app code.
                            if (concise || !isFrameworkFrame(line)) {
                                out.appendLine(line)
                                frameCount++
                            }
                        }
                        inException && t.startsWith("Caused by:") -> {
                            out.appendLine(line)
                            frameCount = 0
                        }
                        inException && t.isBlank() -> break
                    }
                }
                out.toString().trimEnd().ifEmpty { lines.take(maxFrames).joinToString("\n") }
            }

            ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                val signal = lines.firstOrNull { it.startsWith("signal ") }
                val abort  = lines.firstOrNull { it.startsWith("Abort message:") }
                val frames = lines.filter { it.trimStart().startsWith("#") }.take(maxFrames)
                (listOfNotNull(signal, abort) + frames).joinToString("\n")
            }

            // SIGNALED / LOW_MEMORY / EXCESSIVE_RESOURCE_USAGE:
            // no trace content is available; the header fields are sufficient.
            else -> ""
        }
    }
}
