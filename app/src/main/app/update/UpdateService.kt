package com.winlator.cmod.app.update

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object UpdateService {
    private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
    private const val MANUAL_COOLDOWN_MS = 30 * 1000L
    private const val POST_GAME_DELAY_MS = 10 * 1000L
    private const val STARTUP_DELAY_MS = 4 * 1000L

    sealed class Stage {
        data object Idle : Stage()

        data object Checking : Stage()

        data class Downloading(
            val fraction: Float,
            val bytes: Long,
            val total: Long,
        ) : Stage()

        data class Working(
            val label: String,
        ) : Stage()
    }

    var available: UpdateRelease? by mutableStateOf(null)
        private set

    var dialogVisible: Boolean by mutableStateOf(false)
        private set

    var stage: Stage by mutableStateOf(Stage.Idle)
        private set

    var lastError: String? by mutableStateOf(null)
        private set

    var upToDateNotice: Boolean by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checking = AtomicBoolean(false)
    private val lastManualCheck = AtomicLong(0L)
    private val main = Handler(Looper.getMainLooper())

    private var hourlyRunnable: Runnable? = null
    private var postGameRunnable: Runnable? = null

    /**
     * A pull request build follows its own pull request and an official install follows the
     * official releases. It is the build that decides, not a setting: the two are signed with
     * different keys, so an update from the other one could not be installed anyway.
     */
    fun channel(): UpdateChannel =
        if (UpdateRelease.isPullRequestBuild()) UpdateChannel.PULL_REQUEST else UpdateChannel.OFFICIAL

    fun isSupported(context: Context): Boolean =
        when (channel()) {
            // A CI build is signed with whatever key CI had, so the official certificate is not
            // asked for; what it is replaced with still has to match it, which install checks.
            UpdateChannel.PULL_REQUEST -> UpdateRelease.installedBuildRef().isNotEmpty()
            UpdateChannel.OFFICIAL -> UpdateRelease.isReleaseBuild() && UpdateApkInstaller.isOfficialInstall(context)
        }

    fun installedVersionName(): String =
        if (UpdateRelease.isPullRequestBuild()) {
            "PR #${UpdateRelease.installedPullRequest()} · ${UpdateRelease.shortRef(UpdateRelease.installedBuildRef())}"
        } else {
            com.winlator.cmod.BuildConfig.VERSION_NAME
        }

    /** What this install follows, for the settings screen to show. */
    fun updateSourceName(): String =
        if (UpdateRelease.isPullRequestBuild()) "PR #${UpdateRelease.installedPullRequest()}" else ""

    fun isEnabled(context: Context): Boolean = isSupported(context) && UpdateStore.isEnabled(context)

    fun onAppStarted(context: Context) {
        val appContext = context.applicationContext
        scope.launch { UpdateDownloader.clear(appContext) }
        startHourlyLoop(appContext)
        main.postDelayed({ checkNow(appContext, manual = false, force = true) }, STARTUP_DELAY_MS)
    }

    fun onGameFinished(context: Context) {
        val appContext = context.applicationContext
        cancelPostGameCheck()
        if (!isEnabled(appContext)) return
        postGameRunnable = Runnable { checkNow(appContext, manual = false, force = true) }
        main.postDelayed(postGameRunnable!!, POST_GAME_DELAY_MS)
    }

    fun cancelPostGameCheck() {
        postGameRunnable?.let { main.removeCallbacks(it) }
        postGameRunnable = null
    }

    fun startHourlyLoop(context: Context) {
        stopHourlyLoop()
        if (!isEnabled(context)) return
        val appContext = context.applicationContext
        val runnable =
            object : Runnable {
                override fun run() {
                    checkNow(appContext, manual = false)
                    main.postDelayed(this, CHECK_INTERVAL_MS)
                }
            }
        hourlyRunnable = runnable
        main.postDelayed(runnable, CHECK_INTERVAL_MS)
    }

    fun stopHourlyLoop() {
        hourlyRunnable?.let { main.removeCallbacks(it) }
        hourlyRunnable = null
    }

    fun manualCheckCooldownSeconds(): Int {
        val remaining = MANUAL_COOLDOWN_MS - (System.currentTimeMillis() - lastManualCheck.get())
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
    }

    fun checkNow(
        context: Context,
        manual: Boolean,
        force: Boolean = manual,
    ): Boolean {
        val appContext = context.applicationContext
        if (!isSupported(appContext)) return false
        if (!manual && !isEnabled(appContext)) return false
        if (!manual && inGame()) return false
        if (manual) {
            val now = System.currentTimeMillis()
            if (now - lastManualCheck.get() < MANUAL_COOLDOWN_MS) return false
            lastManualCheck.set(now)
        }
        if (!force && System.currentTimeMillis() - UpdateStore.lastCheck(appContext) < CHECK_INTERVAL_MS) {
            return false
        }
        if (!checking.compareAndSet(false, true)) return false

        if (manual) {
            stage = Stage.Checking
            upToDateNotice = false
            lastError = null
        }

        scope.launch {
            try {
                val release = fetch(appContext)
                UpdateStore.markChecked(appContext)
                withContext(Dispatchers.Main) {
                    if (manual) stage = Stage.Idle
                    if (release == null) {
                        available = null
                        if (manual) upToDateNotice = true
                        return@withContext
                    }
                    available = release
                    val ignored = UpdateStore.ignoredKey(appContext) == release.key
                    if (manual || !ignored) dialogVisible = true
                }
            } catch (e: Exception) {
                Timber.w(e, "Update check failed")
                withContext(Dispatchers.Main) {
                    if (manual) {
                        stage = Stage.Idle
                        lastError = e.message ?: "Update check failed"
                    }
                }
            } finally {
                checking.set(false)
            }
        }
        return true
    }

    private fun fetch(context: Context): UpdateRelease? =
        when (channel()) {
            UpdateChannel.PULL_REQUEST -> fetchPullRequest()
            UpdateChannel.OFFICIAL -> fetchOfficial()
        }

    private fun fetchOfficial(): UpdateRelease? {
        val installed = UpdateRelease.installedVersion() ?: return null
        val json = UpdateDownloader.fetchText(UpdateRelease.RELEASES_URL)
        val release = UpdateRelease.pick(json) ?: return null
        if (release.version <= installed) return null
        return release
    }

    /**
     * A pull request keeps one release for its whole life and replaces the assets under it, so
     * there is no version to compare: the build under the tag is newer when its commit is not the
     * one installed. A pull request that has been merged or closed takes its release with it,
     * which reads as nothing to install rather than as a failure.
     */
    private fun fetchPullRequest(): UpdateRelease? {
        val pullRequest = UpdateRelease.installedPullRequest()
        if (pullRequest <= 0) return null
        val json = UpdateDownloader.fetchTextOrNull(UpdateRelease.ciReleaseUrl(pullRequest)) ?: return null
        val release = UpdateRelease.pickPullRequest(json, pullRequest) ?: return null
        if (UpdateRelease.isSameBuild(UpdateRelease.installedBuildRef(), release.buildRef)) return null
        return release
    }

    private fun inGame(): Boolean = PluviaApp.currentForegroundActivity is XServerDisplayActivity

    fun dismissDialog(
        context: Context,
        ignoreUntilNext: Boolean,
    ) {
        val release = available
        if (ignoreUntilNext && release != null) {
            UpdateStore.ignore(context.applicationContext, release.key)
        }
        dialogVisible = false
    }

    fun dismissUpToDateNotice() {
        upToDateNotice = false
    }

    fun startInstall(context: Context) {
        val release = available ?: return
        val appContext = context.applicationContext
        if (stage !is Stage.Idle && stage !is Stage.Checking) return

        if (!UpdateApkInstaller.canInstall(appContext)) {
            lastError = "Allow WinNative to install apps, then press Update again."
            runCatching {
                appContext.startActivity(
                    UpdateApkInstaller
                        .unknownSourcesIntent(appContext)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }

        lastError = null
        stage = Stage.Working("Preparing")

        scope.launch {
            val result =
                UpdateDownloader.downloadApk(appContext, release) { bytes, total ->
                    val target = if (total > 0) total else release.apkSize
                    main.post {
                        stage =
                            Stage.Downloading(
                                fraction = if (target > 0) (bytes.toFloat() / target).coerceIn(0f, 1f) else 0f,
                                bytes = bytes,
                                total = target,
                            )
                    }
                }
            withContext(Dispatchers.Main) {
                result
                    .onSuccess { apk ->
                        stage = Stage.Working("Verifying")
                        if (!UpdateApkInstaller.matchesInstalledSignature(appContext, apk)) {
                            stage = Stage.Idle
                            lastError = "That build is not signed with the same key as this install."
                            UpdateDownloader.clear(appContext)
                            return@onSuccess
                        }
                        stage = Stage.Working("Installing")
                        UpdateApkInstaller.install(appContext, apk) { message ->
                            main.post {
                                stage = Stage.Idle
                                lastError = message
                            }
                        }
                    }.onFailure { error ->
                        Timber.e(error, "Update download failed")
                        stage = Stage.Idle
                        lastError = error.message ?: "Download failed"
                        UpdateDownloader.clear(appContext)
                    }
            }
        }
    }
}
