package com.winlator.cmod.feature.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import com.winlator.cmod.R
import com.winlator.cmod.app.shell.UnifiedActivity
import com.winlator.cmod.feature.settings.DriversFragment
import com.winlator.cmod.feature.settings.ContainerSettingsComposeDialog
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerCreation
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import com.winlator.cmod.runtime.content.Downloader
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.environment.ImageFsInstaller
import com.winlator.cmod.runtime.wine.WineInfo
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.android.FixedFontScaleFragmentActivity
import com.winlator.cmod.shared.ui.widget.chasingBorder
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.content.edit
import timber.log.Timber

private data class Particle(
    val x: Float,
    val speed: Float,
    val size: Float,
    val phaseOffset: Float,
)

private val SetupDownloadChaseGradientStops =
    arrayOf(
        0.00f to Color(0xFF2196F3),
        0.125f to Color(0xFF29B6F6),
        0.25f to Color(0xFF00E5FF),
        0.375f to Color(0xFF29B6F6),
        0.50f to Color(0xFF2196F3),
        0.625f to Color(0xFF29B6F6),
        0.75f to Color(0xFF00E5FF),
        0.875f to Color(0xFF29B6F6),
        1.00f to Color(0xFF2196F3),
    )

private const val SetupGlassSurfaceAlpha = 0.03f
private const val SetupGlassActiveSurfaceAlpha = 0.075f
private const val SetupGlassCompletedSurfaceAlpha = 0.065f
private const val SetupGlassBorderAlpha = 0.24f
private const val SetupGlassCompletedBorderAlpha = 0.82f
private const val SetupGlassCompletedSoftBorderAlpha = 0.62f
private const val SetupGlassTransferAlpha = 0.78f

private data class TabInfo(
    val key: String,
    val label: String,
    val indicatorColor: Color,
    val highlight: Boolean = false,
)

private val NavHighlightAccent = Color(0xFF57CBDE)

private fun Modifier.navHighlight(highlighted: Boolean, cornerRadius: Dp): Modifier =
    drawBehind {
        if (highlighted) {
            val cr = cornerRadius.toPx()
            drawRoundRect(
                color = NavHighlightAccent.copy(alpha = 0.18f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr, cr),
            )
            drawRoundRect(
                color = NavHighlightAccent,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr, cr),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
            )
        }
    }

private const val REGION_TABS = 0
private const val REGION_CONTENT = 1
private const val REGION_NAV = 2

private suspend fun LazyListState.scrollToSelected(index: Int) {
    if (index < 0) return
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return
    val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
    val fullyVisible =
        visible != null &&
            visible.offset >= info.viewportStartOffset &&
            visible.offset + visible.size <= info.viewportEndOffset
    if (!fullyVisible) runCatching { animateScrollToItem(index) }
}

class SetupWizardActivity : FixedFontScaleFragmentActivity() {
    companion object {
        private const val PREFS_NAME = "winnative_setup"
        private const val EXTRA_FORCE_SHOW = "force_show"
        private const val EXTRA_RETURN_TO_CALLER = "return_to_caller"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_RECOMMENDED_COMPONENTS_DONE = "recommended_components_done"
        private const val KEY_DRIVERS_VISITED = "drivers_visited"
        private const val KEY_DEFAULT_X86_CONTAINER_ID = "default_x86_container_id"
        private const val KEY_DEFAULT_ARM64_CONTAINER_ID = "default_arm64_container_id"
        private const val KEY_DEFAULT_X86_SETTINGS_DONE = "default_x86_settings_done"
        private const val KEY_DEFAULT_ARM64_SETTINGS_DONE = "default_arm64_settings_done"
        private const val KEY_LAST_DRIVER_ID = "last_driver_id"
        private const val KEY_LAST_CONTENT_PREFIX = "last_content_"
        private const val KEY_DEFAULT_JSON_CACHE = "default_json_cache"
        private const val DEFAULT_JSON_URL =
            "https://github.com/nicholasx417/WinNative-Components/blob/main/default.json"

        @JvmStatic
        fun isSetupComplete(context: Context): Boolean = prefs(context).getBoolean(KEY_SETUP_COMPLETE, false)

        @JvmStatic
        fun markSetupComplete(context: Context) {
            prefs(context).edit().putBoolean(KEY_SETUP_COMPLETE, true).apply()
        }

        @JvmStatic
        fun createManualRerunIntent(context: Context): Intent =
            Intent(context, SetupWizardActivity::class.java)
                .putExtra(EXTRA_FORCE_SHOW, true)
                .putExtra(EXTRA_RETURN_TO_CALLER, true)

        @JvmStatic
        fun getPreferredGameContainer(
            context: Context,
            containerManager: ContainerManager,
        ): Container? {
            val contentsManager = ContentsManager(context)
            contentsManager.syncContents()
            val preferredId = getDefaultX86ContainerId(context)
            if (preferredId > 0) {
                containerManager.getContainerById(preferredId)?.let {
                    if (isContainerUsable(contentsManager, it)) return it
                }
            }
            return containerManager.containers.firstOrNull { isContainerUsable(contentsManager, it) }
        }

        @JvmStatic
        fun getDefaultX86ContainerId(context: Context): Int = prefs(context).getInt(KEY_DEFAULT_X86_CONTAINER_ID, 0)

        @JvmStatic
        fun getDefaultArm64ContainerId(context: Context): Int = prefs(context).getInt(KEY_DEFAULT_ARM64_CONTAINER_ID, 0)

        @JvmStatic
        fun saveDefaultX86ContainerId(
            context: Context,
            containerId: Int,
        ) {
            prefs(context).edit().putInt(KEY_DEFAULT_X86_CONTAINER_ID, containerId).apply()
        }

        @JvmStatic
        fun saveDefaultArm64ContainerId(
            context: Context,
            containerId: Int,
        ) {
            prefs(context).edit().putInt(KEY_DEFAULT_ARM64_CONTAINER_ID, containerId).apply()
        }

        @JvmStatic
        fun recordInstalledDriver(
            context: Context,
            driverId: String,
        ) {
            prefs(context).edit().putString(KEY_LAST_DRIVER_ID, driverId).apply()
        }

        @JvmStatic
        fun getLastInstalledDriverId(context: Context): String {
            val lastId = prefs(context).getString(KEY_LAST_DRIVER_ID, "") ?: ""
            if (lastId.isBlank() || lastId == "System") return ""

            val manager = AdrenotoolsManager(context)
            val installed = manager.enumarateInstalledDrivers()
            if (installed.contains(lastId)) return lastId

            return if (installed.isNotEmpty()) {
                val first = installed[0]
                recordInstalledDriver(context, first)
                first
            } else {
                recordInstalledDriver(context, "")
                ""
            }
        }

        @JvmStatic
        fun recordInstalledContent(
            context: Context,
            profile: ContentProfile,
        ) {
            val key = KEY_LAST_CONTENT_PREFIX + profile.type.toString().lowercase()
            prefs(context).edit().putString(key, contentVersionIdentifier(profile)).apply()
        }

        @JvmStatic
        fun isWineVersionInstalled(
            context: Context,
            wineVersion: String?,
        ): Boolean {
            if (wineVersion.isNullOrBlank() || WineInfo.isMainWineVersion(wineVersion)) {
                return true
            }
            val contentsManager = ContentsManager(context)
            contentsManager.syncContents()
            return isWineVersionInstalled(contentsManager, wineVersion)
        }

        @JvmStatic
        fun isContainerUsable(
            context: Context,
            container: Container?,
        ): Boolean {
            if (container == null) return false
            val contentsManager = ContentsManager(context)
            contentsManager.syncContents()
            return isContainerUsable(contentsManager, container)
        }

        @JvmStatic
        fun promptToInstallWineOrCreateContainer(
            context: Context,
            missingWineVersion: String? = null,
        ) {
            val runtimeLabel = resolveWineVersionLabel(context, missingWineVersion)
            val message =
                if (runtimeLabel.isNotBlank()) {
                    context.getString(R.string.container_wine_error_not_installed, runtimeLabel)
                } else {
                    "Download a Wine/Proton package and create a container before launching games."
                }
            WinToast.show(context, message, Toast.LENGTH_LONG)

            val intent =
                when {
                    !isSetupComplete(context) -> {
                        Intent(context, SetupWizardActivity::class.java)
                    }

                    hasInstalledRuntimes(context) -> {
                        Intent(context, UnifiedActivity::class.java)
                            .putExtra("selected_menu_item_id", R.id.main_menu_containers)
                    }

                    else -> {
                        Intent(context, UnifiedActivity::class.java)
                            .putExtra("selected_menu_item_id", R.id.main_menu_contents)
                    }
                }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun isWineVersionInstalled(
            contentsManager: ContentsManager,
            wineVersion: String?,
        ): Boolean {
            if (wineVersion.isNullOrBlank() || WineInfo.isMainWineVersion(wineVersion)) {
                return true
            }
            return contentsManager.getProfileByEntryName(wineVersion)?.isInstalled == true
        }

        private fun isContainerUsable(
            contentsManager: ContentsManager,
            container: Container,
        ): Boolean = isWineVersionInstalled(contentsManager, container.wineVersion)

        private fun hasInstalledRuntimes(context: Context): Boolean = ContentsManager.hasInstalledRuntimes(context)

        private fun resolveWineVersionLabel(
            context: Context,
            wineVersion: String?,
        ): String {
            if (wineVersion.isNullOrBlank()) return ""
            val contentsManager = ContentsManager(context)
            contentsManager.syncContents()
            contentsManager.getProfileByEntryName(wineVersion)?.let { return it.verName }

            val firstDash = wineVersion.indexOf('-')
            val lastDash = wineVersion.lastIndexOf('-')
            return if (firstDash >= 0 && lastDash > firstDash) {
                wineVersion.substring(firstDash + 1, lastDash)
            } else {
                wineVersion
            }
        }

        val provider =
            GoogleFont.Provider(
                providerAuthority = "com.google.android.gms.fonts",
                providerPackage = "com.google.android.gms",
                certificates = R.array.com_google_android_gms_fonts_certs,
            )

        val InterFont =
            FontFamily(
                Font(googleFont = GoogleFont("Inter"), fontProvider = provider),
            )

        val SyncopateFont =
            FontFamily(
                Font(googleFont = GoogleFont("Syncopate"), fontProvider = provider),
            )
    }

    private data class PackageSpec(
        val label: String,
        val type: ContentProfile.ContentType,
        val url: String,
    )

    private data class RuntimeSpec(
        val label: String,
        val fallbackType: ContentProfile.ContentType,
        val fallbackUrl: String,
    )

    private data class RemotePackageSpec(
        val type: ContentProfile.ContentType,
        val verName: String,
        val remoteUrl: String,
    )

    private data class TransferState(
        val title: String,
        val detail: String,
        val currentIndex: Int,
        val total: Int,
        val progress: Float? = null,
    )

    private val recommendedComponents =
        listOf(
            PackageSpec(
                label = "DXVK 2.7.1 GPLAsync",
                type = ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                url = "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-Dxvk/Dxvk-2.7.1-gplasync.wcp",
            ),
            PackageSpec(
                label = "DXVK 2.7.1 ARM64EC GPLAsync",
                type = ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                url = "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-Arm64ec-Dxvk/Dxvk-2.7.1-arm64ec-gplasync.wcp",
            ),
            PackageSpec(
                label = "VKD3D Proton 3.0.1",
                type = ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
                url = "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-VKD3D/Vkd3d-proton-3.0.1.wcp",
            ),
            PackageSpec(
                label = "VKD3D ARM64EC 3.0.1",
                type = ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
                url = "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-arm64ec-VKD3D/Vkd3d-arm64ec-3.0.1.wcp",
            ),
            PackageSpec(
                label = "DXVK 2.4.1 pre-reg",
                type = ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                url = "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-Dxvk/Dxvk-2.4.1-pre-reg.wcp",
            ),
            PackageSpec(
                label = "FEX 2605",
                type = ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                url = "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-FEX/FEX-2605.wcp",
            ),
            PackageSpec(
                label = "Box64 0.4.2",
                type = ContentProfile.ContentType.CONTENT_TYPE_BOX64,
                url = "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-Box64/Box64-0.4.2.wcp",
            ),
            PackageSpec(
                label = "Wowbox64 0.4.2",
                type = ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64,
                url = "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-wowbox64/Wowbox64-0.4.2.wcp",
            ),
        )

    private val x86ProtonSpec =
        RuntimeSpec(
            label = "Recommended x86-64",
            fallbackType = ContentProfile.ContentType.CONTENT_TYPE_WINE,
            fallbackUrl = "https://github.com/nicholasx417/WinNative-Components/releases/download/Wine/wine-9.20-x86_64.wcp",
        )

    private val recommendedUrlsState = mutableStateOf<Set<String>>(emptySet())

    private val fallbackRecommendedUrls: Set<String> by lazy {
        buildSet {
            recommendedComponents.forEach { add(it.url) }
            add(x86ProtonSpec.fallbackUrl)
            add(arm64ProtonSpec.fallbackUrl)
        }
    }

    private fun isRecommendedSpec(spec: RemotePackageSpec): Boolean {
        val remote = recommendedUrlsState.value
        return if (remote.isNotEmpty()) {
            spec.remoteUrl in remote
        } else {
            spec.remoteUrl in fallbackRecommendedUrls
        }
    }

    private val arm64ProtonSpec =
        RuntimeSpec(
            label = "Recommended ARM64EC",
            fallbackType = ContentProfile.ContentType.CONTENT_TYPE_PROTON,
            fallbackUrl = "https://github.com/nicholasx417/WinNative-Components/releases/download/Proton/Proton-10-arm64ec-coffincolors.wcp",
        )

    private val TAG = "SetupWizardActivity";

    private val storageGranted = mutableStateOf(false)
    private val notifGranted = mutableStateOf(false)
    private val notifDenied = mutableStateOf(false)
    private val backgroundSessionEnabled = mutableStateOf(false)

    private val pageIndex = mutableIntStateOf(0)
    private val navRegion = mutableIntStateOf(REGION_CONTENT)
    private val navIndex = mutableIntStateOf(0)
    private val activateSignal = mutableIntStateOf(0)
    private val controllerConnected = mutableStateOf(false)
    private var tabCount = 0
    private var contentCount = 0
    private var contentColumns = 1
    private var navCount = 2
    private var wideLayout = false
    private var lastTabIndex = 0
    private var lastContentIndex = 0
    private var onTabIndexChange: ((Int) -> Unit)? = null
    private val imageFsInstalling = mutableStateOf(false)
    private val imageFsProgress = mutableIntStateOf(0)
    private val imageFsDone = mutableStateOf(false)
    private val defaultX86SettingsDone = mutableStateOf(false)
    private val defaultArmSettingsDone = mutableStateOf(false)
    private val defaultX86ContainerName = mutableStateOf("")
    private val defaultArmContainerName = mutableStateOf("")
    private val wizardError = mutableStateOf<String?>(null)
    private val transferState = mutableStateOf<TransferState?>(null)
    private var lastInstallFailureMessage: String? = null
    private val creatingContainer = mutableStateOf(false)
    private val advancedProfiles = mutableStateListOf<RemotePackageSpec>()
    private val advancedInstalledSet = mutableStateListOf<String>()
    private val advancedContainerNames = mutableStateListOf<String>()
    private val advancedSizeCache = mutableStateMapOf<String, Long>()
    private val advancedSizeFetchesInFlight = mutableSetOf<String>()

    private enum class QueueItemStatus { QUEUED, ACTIVE, FAILED }

    private val installQueue = mutableStateListOf<RemotePackageSpec>()
    private val queueStatus = mutableStateMapOf<String, QueueItemStatus>()
    private var queueWorkerRunning = false
    private var queueTotal = 0
    private var queueCompleted = 0

    private var returnToCaller = false
    private var recommendedPackageRefreshInFlight = false

    private val manageStorageLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            storageGranted.value = hasStoragePermission()
        }
    private val notifPermLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            notifGranted.value = granted
            notifDenied.value = !granted
            if (granted) {
                backgroundSessionEnabled.value = true
                prefs(this).edit { putBoolean("enable_background_session", true) }
            } else if (Build.VERSION.SDK_INT >= 33 &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                openNotificationSettings()
            }
        }

    private val legacyStoragePermLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            storageGranted.value =
                permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true ||
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }

    private var stickEngaged = 0

    private fun advanceWizardPage() {
        val page = pageIndex.intValue
        if (page < 2) {
            val canGoNext = if (page == 0) storageGranted.value && imageFsDone.value else true
            if (canGoNext) pageIndex.intValue += 1
        } else if (!creatingContainer.value && transferState.value == null) {
            finishWizard()
        }
    }

    private fun resetNav(region: Int = REGION_CONTENT) {
        navRegion.intValue = if (region == REGION_TABS && tabCount == 0) REGION_CONTENT else region
        navIndex.intValue = 0
        lastTabIndex = 0
        lastContentIndex = 0
    }

    private fun regionSize(r: Int) =
        when (r) {
            REGION_TABS -> tabCount
            REGION_CONTENT -> contentCount
            REGION_NAV -> navCount
            else -> 0
        }

    private fun clampNav() {
        val max = (regionSize(navRegion.intValue) - 1).coerceAtLeast(0)
        if (navIndex.intValue > max) navIndex.intValue = max
    }

    private fun setNav(region: Int, index: Int) {
        navRegion.intValue = region
        navIndex.intValue = index.coerceAtLeast(0)
        clampNav()
        if (region == REGION_TABS) onTabIndexChange?.invoke(navIndex.intValue)
    }

    fun setTabCount(n: Int) {
        tabCount = n.coerceAtLeast(0)
        clampNav()
    }

    fun setContentLayout(count: Int, columns: Int) {
        contentCount = count.coerceAtLeast(0)
        contentColumns = columns.coerceAtLeast(1)
        clampNav()
    }

    private fun navLeft() {
        if (wideLayout) {
            when (navRegion.intValue) {
                REGION_CONTENT ->
                    if (contentColumns > 1 && navIndex.intValue % contentColumns != 0) {
                        navIndex.intValue -= 1
                    } else if (tabCount > 0) {
                        lastContentIndex = navIndex.intValue
                        setNav(REGION_TABS, lastTabIndex.coerceAtMost(tabCount - 1))
                    }
                REGION_NAV ->
                    when {
                        navIndex.intValue > 0 -> navIndex.intValue -= 1
                        contentCount > 0 -> { navRegion.intValue = REGION_CONTENT; navIndex.intValue = lastContentIndex.coerceAtMost(contentCount - 1) }
                        tabCount > 0 -> setNav(REGION_TABS, lastTabIndex.coerceAtMost(tabCount - 1))
                    }
            }
            return
        }
        when (navRegion.intValue) {
            REGION_TABS -> if (navIndex.intValue > 0) setNav(REGION_TABS, navIndex.intValue - 1)
            else -> if (navIndex.intValue > 0) navIndex.intValue -= 1
        }
    }

    private fun navRight() {
        if (wideLayout) {
            when (navRegion.intValue) {
                REGION_TABS ->
                    when {
                        contentCount > 0 -> { lastTabIndex = navIndex.intValue; navRegion.intValue = REGION_CONTENT; navIndex.intValue = lastContentIndex.coerceAtMost(contentCount - 1) }
                        navCount > 0 -> { navRegion.intValue = REGION_NAV; navIndex.intValue = navCount - 1 }
                    }
                REGION_CONTENT ->
                    if (navIndex.intValue % contentColumns != contentColumns - 1 && navIndex.intValue < contentCount - 1) {
                        navIndex.intValue += 1
                    } else if (navCount > 0) {
                        lastContentIndex = navIndex.intValue
                        navRegion.intValue = REGION_NAV
                        navIndex.intValue = navCount - 1
                    }
                REGION_NAV -> if (navIndex.intValue < navCount - 1) navIndex.intValue += 1
            }
            return
        }
        val size = regionSize(navRegion.intValue)
        if (navRegion.intValue == REGION_TABS) {
            if (navIndex.intValue < size - 1) setNav(REGION_TABS, navIndex.intValue + 1)
        } else if (navIndex.intValue < size - 1) {
            navIndex.intValue += 1
        }
    }

    private fun navUp() {
        if (wideLayout) {
            when (navRegion.intValue) {
                REGION_TABS -> if (navIndex.intValue > 0) setNav(REGION_TABS, navIndex.intValue - 1)
                REGION_CONTENT -> if (navIndex.intValue >= contentColumns) navIndex.intValue -= contentColumns
                REGION_NAV ->
                    when {
                        contentCount > 0 -> { navRegion.intValue = REGION_CONTENT; navIndex.intValue = lastContentIndex.coerceAtMost(contentCount - 1) }
                        tabCount > 0 -> setNav(REGION_TABS, lastTabIndex.coerceAtMost(tabCount - 1))
                    }
            }
            return
        }
        when (navRegion.intValue) {
            REGION_CONTENT ->
                if (navIndex.intValue < contentColumns) {
                    if (tabCount > 0) setNav(REGION_TABS, navIndex.intValue.coerceAtMost(tabCount - 1))
                } else {
                    navIndex.intValue -= contentColumns
                }
            REGION_NAV ->
                when {
                    contentCount > 0 -> { navRegion.intValue = REGION_CONTENT; navIndex.intValue = contentCount - 1 }
                    tabCount > 0 -> setNav(REGION_TABS, 0)
                }
        }
    }

    private fun navDown() {
        if (wideLayout) {
            when (navRegion.intValue) {
                REGION_TABS -> if (navIndex.intValue < tabCount - 1) setNav(REGION_TABS, navIndex.intValue + 1)
                REGION_CONTENT -> {
                    val below = navIndex.intValue + contentColumns
                    if (below < contentCount) navIndex.intValue = below
                    else if (navCount > 0) { lastContentIndex = navIndex.intValue; navRegion.intValue = REGION_NAV; navIndex.intValue = navCount - 1 }
                }
            }
            return
        }
        when (navRegion.intValue) {
            REGION_TABS ->
                when {
                    contentCount > 0 -> { navRegion.intValue = REGION_CONTENT; navIndex.intValue = lastContentIndex.coerceAtMost(contentCount - 1) }
                    navCount > 0 -> { navRegion.intValue = REGION_NAV; navIndex.intValue = 0 }
                }
            REGION_CONTENT -> {
                val below = navIndex.intValue + contentColumns
                if (below < contentCount) {
                    navIndex.intValue = below
                } else if (navCount > 0) {
                    navRegion.intValue = REGION_NAV
                    navIndex.intValue = 0
                }
            }
        }
    }

    private fun navActivate() { activateSignal.intValue++ }

    private fun applyNavDir(code: Int) {
        when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> navLeft()
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> navRight()
            android.view.KeyEvent.KEYCODE_DPAD_UP -> navUp()
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> navDown()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (com.winlator.cmod.runtime.input.controls.ExternalController.isGameController(event.device)) {
            controllerConnected.value = true
        }
        val down = event.action == android.view.KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            -> {
                if (down) applyNavDir(event.keyCode)
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_A,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                if (down) navActivate()
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_B -> {
                if (down && pageIndex.intValue > 0) {
                    pageIndex.intValue -= 1
                }
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_START -> {
                if (down) advanceWizardPage()
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_X,
            android.view.KeyEvent.KEYCODE_BUTTON_Y,
            android.view.KeyEvent.KEYCODE_BUTTON_L1,
            android.view.KeyEvent.KEYCODE_BUTTON_R1,
            -> return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if ((event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK &&
            event.action == android.view.MotionEvent.ACTION_MOVE
        ) {
            controllerConnected.value = true
            val x = event.getAxisValue(android.view.MotionEvent.AXIS_X)
            val y = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
            val code =
                when {
                    x < -0.5f || hx < -0.5f -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    x > 0.5f || hx > 0.5f -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    y < -0.5f || hy < -0.5f -> android.view.KeyEvent.KEYCODE_DPAD_UP
                    y > 0.5f || hy > 0.5f -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    else -> 0
                }
            if (code != 0) {
                if (stickEngaged == 0) {
                    stickEngaged = code
                    applyNavDir(code)
                }
                return true
            }
            if (kotlin.math.abs(x) < 0.35f && kotlin.math.abs(y) < 0.35f &&
                kotlin.math.abs(hx) < 0.35f && kotlin.math.abs(hy) < 0.35f
            ) {
                stickEngaged = 0
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
        )
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        returnToCaller = intent?.getBooleanExtra(EXTRA_RETURN_TO_CALLER, false) == true
        val forceShow = intent?.getBooleanExtra(EXTRA_FORCE_SHOW, false) == true

        supportFragmentManager.setFragmentResultListener(
            SetupWizardDriversDialogFragment.RESULT_KEY,
            this,
        ) { _, _ ->
            prefs(this).edit().putBoolean(KEY_DRIVERS_VISITED, true).apply()
            refreshWizardState()
        }

        if (!forceShow && isSetupComplete(this) && ImageFs.find(this).isUpToDate) {
            launchApp()
            return
        }

        storageGranted.value = hasStoragePermission()
        notifGranted.value = hasNotificationPermissionSilently()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {  // Enable background protection by default on Android 13+.
            if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).contains("enable_background_session")) {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .edit { putBoolean("enable_background_session", true) }
                Timber.d("Android 14+ detected, background session protection enabled")
            }
        }
        backgroundSessionEnabled.value = prefs(this).getBoolean("enable_background_session", false)
        if (Build.VERSION.SDK_INT >= 36) {
            Timber.d("Android 16+ detected")
            // If wakeLock preference isn't saved, enable it by default on Android 16+.
            if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).contains("enable_background_wakelock")) {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit {
                    putBoolean("enable_background_wakelock", true)
                }
                Timber.d("Android 16+ wakeLock preference enabled")
            }
        }
        refreshWizardState()
        loadAdvancedProfiles()

        setContent {
            WinNativeTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFF57CBDE),
                        secondary = Color(0xFF3B82F6),
                        background = Color(0xFF141B24),
                        surface = Color(0xFF1E252E),
                    ),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
                ) {
                    SetupWizardScreen()
                }
            }
        }
    }

    private fun isAnyControllerConnected(): Boolean {
        for (id in android.view.InputDevice.getDeviceIds()) {
            val dev = android.view.InputDevice.getDevice(id)
            if (dev != null && com.winlator.cmod.runtime.input.controls.ExternalController.isGameController(dev)) {
                return true
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        controllerConnected.value = isAnyControllerConnected()
        storageGranted.value = hasStoragePermission()
        val notificationsEnabled = hasNotificationPermissionSilently()
        notifGranted.value = notificationsEnabled
        if (notificationsEnabled) {
            notifDenied.value = false
        }
        backgroundSessionEnabled.value = prefs(this).getBoolean("enable_background_session", false)
        refreshWizardState()
        refreshRecommendedPackageCache()
    }

    private fun refreshWizardState() {
        val imageFs = ImageFs.find(this)
        imageFsDone.value = imageFs.isUpToDate

        val preferences = prefs(this)
        val containerManager = ContainerManager(this)
        val x86Container =
            containerManager
                .getContainerById(getDefaultX86ContainerId(this))
                ?.takeIf { isContainerUsable(this, it) }
        val armContainer =
            containerManager
                .getContainerById(getDefaultArm64ContainerId(this))
                ?.takeIf { isContainerUsable(this, it) }

        defaultX86ContainerName.value = x86Container?.name ?: ""
        defaultArmContainerName.value = armContainer?.name ?: ""

        defaultX86SettingsDone.value =
            preferences.getBoolean(KEY_DEFAULT_X86_SETTINGS_DONE, false) && x86Container != null
        defaultArmSettingsDone.value =
            preferences.getBoolean(KEY_DEFAULT_ARM64_SETTINGS_DONE, false) && armContainer != null
    }

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasNotificationPermissionSilently(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            }
        } else {
            val preferences = prefs(this)
            val hasRequestedOnce = preferences.getBoolean("storage_requested_once", false)
            val shouldShowRationale =
                shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)

            if (hasRequestedOnce && !shouldShowRationale) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            } else {
                preferences.edit().putBoolean("storage_requested_once", true).apply()
                legacyStoragePermLauncher.launch(
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                    ),
                )
            }
        }
    }

    private fun requestNotifications() {
        if (hasNotificationPermissionSilently()) {
            backgroundSessionEnabled.value = true
            prefs(this).edit { putBoolean("enable_background_session", true) }
            return
        }

        if (Build.VERSION.SDK_INT >= 33 && applicationInfo.targetSdkVersion >= 33) {
            if (notifDenied.value) {
                openNotificationSettings()
            } else {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun installImageFs() {
        if (imageFsInstalling.value || imageFsDone.value) return

        wizardError.value = null
        imageFsInstalling.value = true
        imageFsProgress.intValue = 0

        val keepAliveTag = "imagefs_install"
        com.winlator.cmod.runtime.system.SessionKeepAliveService.startDownload(this, keepAliveTag)

        ImageFsInstaller.installFromAssets(
            this,
            object : ImageFsInstaller.ProgressListener {
                override fun onProgress(percent: Int) {
                    runOnUiThread {
                        imageFsProgress.intValue = percent.coerceIn(0, 100)
                    }
                }

                override fun onFinished(success: Boolean) {
                    runOnUiThread {
                        imageFsProgress.intValue = if (success) 100 else imageFsProgress.intValue
                        if (success) {
                            window.decorView.postDelayed(
                                {
                                    imageFsInstalling.value = false
                                    imageFsDone.value = true
                                    refreshWizardState()
                                },
                                500L,
                            )
                        } else {
                            imageFsInstalling.value = false
                            wizardError.value = "ImageFS install failed. Check available storage and try again."
                        }
                        com.winlator.cmod.runtime.system.SessionKeepAliveService.stopDownload(
                            applicationContext,
                            keepAliveTag,
                        )
                    }
                }
            },
        )
    }

    private suspend fun downloadFileToCache(
        label: String,
        url: String,
        currentIndex: Int,
        total: Int,
        title: String = label,
    ): File? =
        withContext(Dispatchers.IO) {
            val sanitized = label.lowercase().replace(Regex("[^a-z0-9]+"), "_")
            val output = File(cacheDir, "wizard_${System.currentTimeMillis()}_$sanitized.wcp")
            val listener =
                Downloader.DownloadListener { downloadedBytes, totalBytes ->
                    updateTransferState(
                        TransferState(
                            title = title,
                            detail = getString(R.string.setup_wizard_downloading, label),
                            currentIndex = currentIndex,
                            total = total,
                            progress =
                                if (totalBytes > 0) {
                                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    null
                                },
                        ),
                    )
                }
            val success = Downloader.downloadFileWinNativeFirst(url, output, listener)
            if (success) output else null
        }

    private fun updateTransferState(next: TransferState?) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            transferState.value = next
        } else {
            runOnUiThread { transferState.value = next }
        }
    }

    private fun updateWizardError(message: String?) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            wizardError.value = message
        } else {
            runOnUiThread { wizardError.value = message }
        }
    }

    private fun updateRecommendedUrls(urls: Set<String>) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            recommendedUrlsState.value = urls
        } else {
            runOnUiThread { recommendedUrlsState.value = urls }
        }
    }

    private fun installDownloadedPackage(
        file: File,
        sourceUrl: String,
    ): ContentProfile? {
        val manager = ContentsManager(this)
        manager.syncContents()

        lastInstallFailureMessage = null
        var extractedProfile: ContentProfile? = null
        var installedProfile: ContentProfile? = null
        var failed = false
        var failureReason: ContentsManager.InstallFailedReason? = null
        var failureException: Exception? = null

        val callback =
            object : ContentsManager.OnInstallFinishedCallback {
                private var extracting = true

                override fun onFailed(
                    reason: ContentsManager.InstallFailedReason,
                    e: Exception?,
                ) {
                    if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST && extractedProfile != null) {
                        manager.registerRemoteProfileAlias(sourceUrl, extractedProfile)
                        manager.syncContents()
                        installedProfile = manager.getProfileByEntryName(
                            ContentsManager.getEntryName(extractedProfile),
                        ) ?: extractedProfile?.apply { isInstalled = true }
                        return
                    }
                    failed = true
                    failureReason = reason
                    failureException = e
                }

                override fun onSucceed(profile: ContentProfile) {
                    if (extracting) {
                        extracting = false
                        extractedProfile = profile
                        manager.finishInstallContent(profile, this)
                        return
                    }
                    manager.registerRemoteProfileAlias(sourceUrl, profile)
                    manager.syncContents()
                    recordInstalledContent(this@SetupWizardActivity, profile)
                    installedProfile = profile
                }
            }

        manager.extraContentFile(Uri.fromFile(file), callback)
        if (failed) {
            val reason = failureReason?.name ?: getString(R.string.common_ui_unknown_error)
            val baseMessage = getString(R.string.setup_wizard_install_failed_reason, reason)
            lastInstallFailureMessage =
                failureException?.message
                    ?.takeIf { it.isNotBlank() }
                    ?.let { getString(R.string.setup_wizard_install_failed_detail, baseMessage, it) }
                    ?: baseMessage
            Log.e("SetupWizardActivity", lastInstallFailureMessage, failureException)
        }
        return if (failed) null else installedProfile
    }

    private fun loadAdvancedProfiles() {
        if (advancedProfiles.isNotEmpty()) return
        lifecycleScope.launch {
            val profiles =
                withContext(Dispatchers.IO) {
                    // 1. Fetch recommended (default.json)
                    val recommended = fetchRecommendedPackages()

                    // 2. Fetch full catalog (content.json)
                    val fullCatalog =
                        parseRecommendedPackages(
                            Downloader.downloadString(ContentsManager.REMOTE_PROFILES),
                        )

                    // 3. Merge: start with recommended, then add any full catalog entries not already present
                    val seen = recommended.map { it.remoteUrl }.toMutableSet()
                    val merged = recommended.toMutableList()
                    for (spec in fullCatalog) {
                        if (spec.remoteUrl !in seen) {
                            seen.add(spec.remoteUrl)
                            merged.add(spec)
                        }
                    }

                    // 4. Emergency Fallback: If everything failed and we have no profiles, use hardcoded ones
                    if (merged.isEmpty()) {
                        merged.addAll(getFallbackRemoteSpecs())
                    }
                    merged
                }
            advancedProfiles.clear()
            advancedProfiles.addAll(profiles)
            refreshAdvancedInstalledSet()
        }
    }

    private fun fetchAdvancedSizes(specs: List<RemotePackageSpec>) {
        val urls =
            specs
                .map { it.remoteUrl }
                .filter { it.isNotEmpty() && it !in advancedSizeCache && it !in advancedSizeFetchesInFlight }
                .distinct()
        if (urls.isEmpty()) return
        advancedSizeFetchesInFlight.addAll(urls)
        urls.forEach { url ->
            lifecycleScope.launch {
                val size = withContext(Dispatchers.IO) { Downloader.fetchContentLength(url) }
                advancedSizeFetchesInFlight.remove(url)
                if (size > 0L) advancedSizeCache[url] = size
            }
        }
    }

    private fun formatSizeMb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))

    private fun getFallbackRemoteSpecs(): List<RemotePackageSpec> =
        buildList {
            recommendedComponents.forEach {
                add(RemotePackageSpec(it.type, it.label, it.url))
            }
            add(RemotePackageSpec(x86ProtonSpec.fallbackType, x86ProtonSpec.label, x86ProtonSpec.fallbackUrl))
            add(RemotePackageSpec(arm64ProtonSpec.fallbackType, arm64ProtonSpec.label, arm64ProtonSpec.fallbackUrl))
        }

    private fun fetchRecommendedPackages(): List<RemotePackageSpec> {
        val json = Downloader.downloadString(resolveJsonDownloadUrl(DEFAULT_JSON_URL))
        if (!json.isNullOrBlank()) {
            prefs(this).edit().putString(KEY_DEFAULT_JSON_CACHE, json).apply()
            val specs = parseRecommendedPackages(json)
            if (specs.isNotEmpty()) {
                updateRecommendedUrls(specs.map { it.remoteUrl }.toSet())
            }
            return specs
        }
        val cached = getCachedRecommendedPackages()
        if (cached.isNotEmpty()) {
            updateRecommendedUrls(cached.map { it.remoteUrl }.toSet())
        }
        return cached
    }

    private fun refreshRecommendedPackageCache() {
        if (recommendedPackageRefreshInFlight) return

        recommendedPackageRefreshInFlight = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    fetchRecommendedPackages()
                }
            } finally {
                recommendedPackageRefreshInFlight = false
                refreshWizardState()
            }
        }
    }

    private fun getCachedRecommendedPackages(): List<RemotePackageSpec> {
        val cachedJson = prefs(this).getString(KEY_DEFAULT_JSON_CACHE, null)
        return parseRecommendedPackages(cachedJson)
    }

    private fun parseRecommendedPackages(json: String?): List<RemotePackageSpec> {
        if (json.isNullOrBlank()) return emptyList()

        return runCatching {
            val entries = JSONArray(json)
            buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val type = ContentProfile.ContentType.getTypeByName(item.optString("type")) ?: continue
                    val verName = item.optString("verName")
                    val remoteUrl = item.optString("remoteUrl")
                    if (verName.isBlank() || remoteUrl.isBlank()) continue
                    add(RemotePackageSpec(type, verName, remoteUrl))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun ensureContainerForProfile(
        profile: ContentProfile,
        desiredName: String,
    ): Container {
        val containerManager = ContainerManager(this)
        val contentsManager = ContentsManager(this)
        contentsManager.syncContents()

        return requireNotNull(
            ContainerCreation.getOrCreateContainerForProfile(
                this,
                containerManager,
                contentsManager,
                profile,
                desiredName,
            ),
        ) {
            "Unable to create container for ${profile.verName}"
        }
    }

    private fun openDrivers() {
        if (supportFragmentManager.findFragmentByTag(SetupWizardDriversDialogFragment.TAG) == null) {
            SetupWizardDriversDialogFragment().show(
                supportFragmentManager,
                SetupWizardDriversDialogFragment.TAG,
            )
        }
    }

    private fun refreshAdvancedInstalledSet() {
        val manager = ContentsManager(this)
        manager.syncContents()
        advancedInstalledSet.clear()
        advancedProfiles.forEach { spec ->
            val installedByName =
                manager.getProfiles(spec.type).orEmpty().any {
                    it.isInstalled && it.verName.equals(spec.verName, ignoreCase = true)
                }
            val installedByUrl = manager.isRemoteUrlInstalled(spec.remoteUrl)
            if (installedByName || installedByUrl) advancedInstalledSet.add(spec.verName)
        }
        // Also refresh container names for default settings page
        val containerManager = ContainerManager(this)
        advancedContainerNames.clear()
        containerManager.containers.forEach {
            advancedContainerNames.add(it.name)
        }
    }

    private fun enqueueAllRecommended() {
        advancedProfiles
            .filter { isRecommendedSpec(it) && it.verName !in advancedInstalledSet }
            .forEach { enqueueAdvancedComponent(it) }
    }

    private fun startInstallQueue() {
        if (queueWorkerRunning) return
        queueWorkerRunning = true
        val keepAliveTag = "install_queue_${System.currentTimeMillis()}"
        com.winlator.cmod.runtime.system.SessionKeepAliveService.startDownload(this, keepAliveTag)
        lifecycleScope.launch {
            updateWizardError(null)
            try {
                while (installQueue.isNotEmpty()) {
                    val spec = installQueue.removeAt(0)
                    val key = spec.remoteUrl
                    if (spec.verName in advancedInstalledSet) {
                        queueStatus.remove(key)
                        queueCompleted += 1
                        continue
                    }
                    queueStatus[key] = QueueItemStatus.ACTIVE
                    val position = queueCompleted + 1
                    val total = queueTotal
                    val title = spec.verName
                    val profile =
                        withContext(Dispatchers.IO) {
                            try {
                                updateTransferState(
                                    TransferState(
                                        title = title,
                                        detail = getString(R.string.downloads_queue_preparing_download),
                                        currentIndex = position,
                                        total = total,
                                        progress = 0f,
                                    ),
                                )
                                val downloaded =
                                    downloadFileToCache(
                                        label = spec.verName,
                                        url = spec.remoteUrl,
                                        currentIndex = position,
                                        total = total,
                                        title = title,
                                    ) ?: return@withContext null

                                updateTransferState(
                                    TransferState(
                                        title = title,
                                        detail = getString(R.string.setup_wizard_downloading, spec.verName),
                                        currentIndex = position,
                                        total = total,
                                        progress = 1f,
                                    ),
                                )
                                kotlinx.coroutines.delay(500)

                                updateTransferState(
                                    TransferState(
                                        title = title,
                                        detail = getString(R.string.setup_wizard_installing),
                                        currentIndex = position,
                                        total = total,
                                        progress = null,
                                    ),
                                )

                                val installed = installDownloadedPackage(downloaded, spec.remoteUrl)
                                downloaded.delete()
                                if (installed == null) {
                                    updateWizardError(
                                        lastInstallFailureMessage
                                            ?: getString(R.string.setup_wizard_install_failed_reason, spec.verName),
                                    )
                                }
                                installed
                            } catch (e: Exception) {
                                updateWizardError(
                                    getString(
                                        R.string.setup_wizard_install_failed_reason,
                                        e.message ?: getString(R.string.common_ui_unknown_error),
                                    ),
                                )
                                null
                            }
                        }
                    queueCompleted += 1
                    if (profile != null) {
                        queueStatus.remove(key)
                        if (spec.verName !in advancedInstalledSet) {
                            advancedInstalledSet.add(spec.verName)
                        }
                    } else {
                        queueStatus[key] = QueueItemStatus.FAILED
                    }
                }
                refreshAdvancedInstalledSet()
                refreshWizardState()
            } finally {
                updateTransferState(null)
                queueWorkerRunning = false
                queueTotal = 0
                queueCompleted = 0
                com.winlator.cmod.runtime.system.SessionKeepAliveService.stopDownload(
                    applicationContext,
                    keepAliveTag,
                )
            }
        }
    }

    private fun enqueueAdvancedComponent(spec: RemotePackageSpec) {
        if (spec.verName in advancedInstalledSet) return
        val key = spec.remoteUrl
        val status = queueStatus[key]
        if (status == QueueItemStatus.QUEUED || status == QueueItemStatus.ACTIVE) return
        queueStatus[key] = QueueItemStatus.QUEUED
        installQueue.add(spec)
        queueTotal += 1
        startInstallQueue()
    }

    private fun openContainerDefaultSettings(
        containerId: Int,
        type: String,
    ) {
        val container = ContainerManager(this).getContainerById(containerId)
        if (container == null) {
            refreshWizardState()
            return
        }

        ContainerSettingsComposeDialog(this, container) {
            when (type) {
                "x86" -> prefs(this).edit().putBoolean(KEY_DEFAULT_X86_SETTINGS_DONE, true).apply()
                "arm64" -> prefs(this).edit().putBoolean(KEY_DEFAULT_ARM64_SETTINGS_DONE, true).apply()
            }
            refreshAdvancedInstalledSet()
            refreshWizardState()
        }.show()
    }

    private fun finishWizard() {
        markSetupComplete(this)
        if (returnToCaller) {
            finish()
        } else {
            launchApp()
        }
    }

    private fun launchApp() {
        startActivity(
            Intent(this, UnifiedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        com.winlator.cmod.shared.android.AppUtils
            .applyOpenActivityTransition(this, 0, 0)
        finish()
    }

    @Composable
    private fun SetupWizardScreen() {
        val page by pageIndex
        val totalPages = 3
        val pageTitle =
            when (page) {
                0 -> stringResource(R.string.setup_wizard_required_access)
                1 -> stringResource(R.string.setup_wizard_select_components)
                2 -> stringResource(R.string.setup_wizard_containers)
                else -> ""
            }
        val canGoNext =
            when (page) {
                0 -> storageGranted.value && imageFsDone.value
                else -> true
            }
        val lastPage = totalPages - 1

        LaunchedEffect(page) {
            onTabIndexChange = null
            resetNav(REGION_CONTENT)
        }

        val region by navRegion
        val navIdx by navIndex
        val activate by activateSignal
        val controller by controllerConnected

        LaunchedEffect(activate) {
            if (activate == 0) return@LaunchedEffect
            if (region == REGION_NAV) {
                when (navIdx) {
                    0 -> if (page > 0) pageIndex.intValue -= 1
                    1 -> advanceWizardPage()
                }
            }
        }

        // Particle seeds — stable across recomposition
        val particles =
            remember {
                List(20) { i ->
                    val hash = ((i * 7919 + 104729) % 10000) / 10000f
                    Particle(
                        x = ((i * 3571 + 7321) % 10000) / 10000f,
                        speed = 0.6f + hash * 0.4f,
                        size = 1f + hash * 1.5f,
                        phaseOffset = hash * 6.2832f,
                    )
                }
            }

        // Page-reactive orb anchors — orbs smoothly migrate when page changes
        val orb1TargetX =
            when (page) {
                0 -> 0.25f
                1 -> 0.50f
                else -> 0.75f
            }
        val orb1TargetY =
            when (page) {
                0 -> 0.30f
                1 -> 0.20f
                else -> 0.25f
            }
        val orb2TargetX =
            when (page) {
                0 -> 0.70f
                1 -> 0.30f
                else -> 0.20f
            }
        val orb2TargetY =
            when (page) {
                0 -> 0.70f
                1 -> 0.55f
                else -> 0.75f
            }
        val orb3TargetX =
            when (page) {
                0 -> 0.50f
                1 -> 0.75f
                else -> 0.50f
            }
        val orb3TargetY =
            when (page) {
                0 -> 0.50f
                1 -> 0.80f
                else -> 0.50f
            }

        val orbAnim = tween<Float>(2000, easing = EaseInOut)
        val o1x by animateFloatAsState(orb1TargetX, orbAnim, label = "o1x")
        val o1y by animateFloatAsState(orb1TargetY, orbAnim, label = "o1y")
        val o2x by animateFloatAsState(orb2TargetX, orbAnim, label = "o2x")
        val o2y by animateFloatAsState(orb2TargetY, orbAnim, label = "o2y")
        val o3x by animateFloatAsState(orb3TargetX, orbAnim, label = "o3x")
        val o3y by animateFloatAsState(orb3TargetY, orbAnim, label = "o3y")

        val infiniteTransition = rememberInfiniteTransition(label = "bgGlow")
        val phase1 =
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 6.2832f,
                animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing), RepeatMode.Restart),
                label = "phase1",
            )
        val phase2 =
            infiniteTransition.animateFloat(
                initialValue = 6.2832f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(tween(38000, easing = LinearEasing), RepeatMode.Restart),
                label = "phase2",
            )
        val pulse =
            infiniteTransition.animateFloat(
                initialValue = 0.85f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(8000, easing = EaseInOut), RepeatMode.Reverse),
                label = "pulse",
            )
        val particlePhase =
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Restart),
                label = "particlePhase",
            )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF111822))
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        val p1 = phase1.value
                        val p2 = phase2.value
                        val p = pulse.value

                        // Orb 1 — cyan, page-reactive + gentle drift
                        val c1 =
                            Offset(
                                w * (o1x + 0.04f * cos(p1)),
                                h * (o1y + 0.03f * sin(p1)),
                            )
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(
                                            Color(0xFF57CBDE).copy(alpha = 0.04f * p),
                                            Color(0xFF57CBDE).copy(alpha = 0.015f * p),
                                            Color.Transparent,
                                        ),
                                    center = c1,
                                    radius = w * 0.6f,
                                ),
                            radius = w * 0.6f,
                            center = c1,
                        )

                        // Orb 2 — blue, page-reactive + gentle drift
                        val c2 =
                            Offset(
                                w * (o2x + 0.04f * cos(p2)),
                                h * (o2y + 0.03f * sin(p2)),
                            )
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(
                                            Color(0xFF3B82F6).copy(alpha = 0.035f * p),
                                            Color(0xFF3B82F6).copy(alpha = 0.01f * p),
                                            Color.Transparent,
                                        ),
                                    center = c2,
                                    radius = w * 0.55f,
                                ),
                            radius = w * 0.55f,
                            center = c2,
                        )

                        // Orb 3 — teal accent, page-reactive + gentle drift
                        val c3 =
                            Offset(
                                w * (o3x + 0.03f * sin(p1 * 0.7f)),
                                h * (o3y + 0.03f * cos(p2 * 0.6f)),
                            )
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(
                                            Color(0xFF2DD4BF).copy(alpha = 0.02f * p),
                                            Color.Transparent,
                                        ),
                                    center = c3,
                                    radius = w * 0.45f,
                                ),
                            radius = w * 0.45f,
                            center = c3,
                        )

                        // Floating particles
                        val pp = particlePhase.value
                        particles.forEach { pt ->
                            val t = (pp * pt.speed + pt.phaseOffset) % 1f
                            val py = h * (1f - t)
                            val px = w * pt.x + w * 0.02f * sin((t * 2f * PI).toFloat() + pt.phaseOffset)
                            // Fade in at bottom, fade out at top
                            val alpha =
                                when {
                                    t < 0.15f -> t / 0.15f
                                    t > 0.85f -> (1f - t) / 0.15f
                                    else -> 1f
                                } * 0.12f
                            drawCircle(
                                color = Color(0xFF57CBDE).copy(alpha = alpha),
                                radius = pt.size.dp.toPx(),
                                center = Offset(px, py),
                            )
                        }
                    },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "WinNative",
                            color = Color(0xFFE6EDF3),
                            fontFamily = SyncopateFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.setup_wizard_title).uppercase(),
                                color = Color(0xFF57CBDE),
                                fontFamily = InterFont,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp,
                                letterSpacing = 1.5.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier =
                                    Modifier
                                        .size(3.dp)
                                        .background(Color(0xFF4A5260), RoundedCornerShape(2.dp)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = pageTitle,
                                color = Color(0xFF8B949E),
                                fontFamily = InterFont,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    StepIndicator(current = page, total = totalPages)
                }

                Spacer(Modifier.height(10.dp))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFF222D3D)),
                )
                Spacer(Modifier.height(12.dp))

                BoxWithConstraints(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val isCompact = maxWidth < 500.dp
                    wideLayout = !isCompact
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            val forward = targetState > initialState
                            val enter =
                                slideInHorizontally(
                                    animationSpec = tween(220),
                                ) { if (forward) it / 4 else -it / 4 } +
                                    fadeIn(tween(160, delayMillis = 40))
                            val exit =
                                slideOutHorizontally(
                                    animationSpec = tween(220),
                                ) { if (forward) -it / 4 else it / 4 } +
                                    fadeOut(tween(140))
                            (enter togetherWith exit).using(
                                SizeTransform(clip = false),
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "pageTransition",
                    ) { targetPage ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (targetPage) {
                                0 -> PagePermissions(isCompact)
                                1 -> PageAdvancedComponents(isCompact)
                                2 -> PageDefaultSettings()
                            }
                        }
                    }

                    wizardError.value?.let { message ->
                        Text(
                            text = message,
                            color = Color(0xFFFF7B72),
                            fontFamily = InterFont,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(),
                        )
                    }
                }

                val transferActive = transferState.value != null
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    GhostPillButton(
                        label = stringResource(R.string.common_ui_back),
                        enabled = page > 0,
                        highlighted = controller && region == REGION_NAV && navIdx == 0,
                        onClick = { setNav(REGION_NAV, 0); if (page > 0) pageIndex.intValue -= 1 },
                    )

                    if (page < lastPage) {
                        AccentPillButton(
                            label = stringResource(R.string.setup_wizard_next),
                            enabled = canGoNext,
                            highlighted = controller && region == REGION_NAV && navIdx == 1,
                            onClick = { setNav(REGION_NAV, 1); if (canGoNext) pageIndex.intValue += 1 },
                        )
                    } else {
                        AccentPillButton(
                            label = stringResource(R.string.setup_wizard_finish),
                            enabled = !creatingContainer.value && !transferActive,
                            highlighted = controller && region == REGION_NAV && navIdx == 1,
                            onClick = { setNav(REGION_NAV, 1); finishWizard() },
                        )
                    }
                }
            }

            // Transfer strip — floats at bottom, outside the Column
            val transfer = transferState.value
            if (transfer != null) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    TransferStrip(transfer)
                }
            }
        }
    }

    @Composable
    private fun TransferStrip(state: TransferState) {
        val progressAnim = remember { Animatable(state.progress?.coerceIn(0f, 1f) ?: 0f) }
        LaunchedEffect(state.currentIndex, state.progress) {
            val targetProgress = state.progress?.coerceIn(0f, 1f) ?: 0f
            if (targetProgress < progressAnim.value) {
                progressAnim.snapTo(targetProgress)
            } else {
                progressAnim.animateTo(
                    targetValue = targetProgress,
                    animationSpec = tween(durationMillis = 240, easing = LinearEasing),
                )
            }
        }
        val animatedProgress = progressAnim.value
        val turquoise = Color(0xFF57CBDE)
        val glassShape = RoundedCornerShape(12.dp)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111822).copy(alpha = SetupGlassTransferAlpha), glassShape)
                    .border(1.dp, turquoise.copy(alpha = 0.55f), glassShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(turquoise, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.title,
                        color = Color(0xFFF0F6FC),
                        fontFamily = InterFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (state.total > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${state.currentIndex}/${state.total}",
                            color = turquoise,
                            fontFamily = SyncopateFont,
                            fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = state.detail,
                    color = Color(0xFFB8C5D1),
                    fontFamily = InterFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                if (state.progress != null) {
                    SetupChasingProgressBar(
                        progress = animatedProgress,
                        animate = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                    )
                } else {
                    SetupChasingProgressBar(
                        progress = 1f,
                        animate = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                    )
                }
            }
            if (state.progress != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${(animatedProgress * 100f).toInt()}%",
                    color = turquoise,
                    fontFamily = SyncopateFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
    }

    @Composable
    private fun SetupAnimatedProgressFill(
        modifier: Modifier,
        widthPx: Float,
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "setupTransferProgressGradient")
        val gradientOffset by infiniteTransition.animateFloat(
            initialValue = -widthPx,
            targetValue = 0f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 5000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "setupTransferProgressGradientOffset",
        )

        Box(
            modifier.background(
                Brush.horizontalGradient(
                    colorStops = SetupDownloadChaseGradientStops,
                    startX = gradientOffset,
                    endX = gradientOffset + (widthPx * 2f),
                    tileMode = TileMode.Repeated,
                ),
            ),
        )
    }

    @Composable
    private fun SetupChasingProgressBar(
        progress: Float,
        animate: Boolean,
        modifier: Modifier = Modifier,
    ) {
        BoxWithConstraints(
            modifier =
                modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.34f)),
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
            val clampedProgress = progress.coerceIn(0f, 1f)

            if (clampedProgress > 0f) {
                val fillModifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(clampedProgress.coerceAtLeast(0.015f))
                        .clip(RectangleShape)

                if (animate) {
                    SetupAnimatedProgressFill(fillModifier, widthPx)
                } else {
                    Box(
                        fillModifier.background(
                            Brush.horizontalGradient(
                                colorStops = SetupDownloadChaseGradientStops,
                                endX = widthPx * 2f,
                                tileMode = TileMode.Repeated,
                            ),
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun StepIndicator(
        current: Int,
        total: Int,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until total) {
                val active = i == current
                val completed = i < current
                val reached = active || completed
                val bg = if (reached) Color(0xFF57CBDE) else Color(0xFF111822)
                val borderC = if (reached) Color(0xFF57CBDE) else Color(0xFF323C4A)
                val textC = if (reached) Color(0xFF111822) else Color(0xFF8B949E)
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .background(bg, RoundedCornerShape(12.dp))
                            .border(1.5.dp, borderC, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${i + 1}",
                        color = textC,
                        fontFamily = InterFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                }
                if (i < total - 1) {
                    Box(
                        modifier =
                            Modifier
                                .width(18.dp)
                                .height(2.dp)
                                .background(if (i < current) Color(0xFF57CBDE) else Color(0xFF323C4A)),
                    )
                }
            }
        }
    }

    @Composable
    private fun GhostPillButton(
        label: String,
        enabled: Boolean = true,
        highlighted: Boolean = false,
        onClick: () -> Unit,
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.navHighlight(highlighted, cornerRadius = 12.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (enabled) Color(0xFF434D5C) else Color(0xFF222D3D)),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFE6EDF3),
                    disabledContentColor = Color(0xFF434D5C),
                ),
        ) {
            Text(label, fontFamily = InterFont, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun AccentPillButton(
        label: String,
        enabled: Boolean,
        highlighted: Boolean = false,
        onClick: () -> Unit,
    ) {
        val borderColor by animateColorAsState(
            targetValue = if (enabled) Color(0xFF57CBDE) else Color(0xFF222D3D),
            animationSpec = tween(300),
            label = "accentBorder",
        )
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.navHighlight(highlighted, cornerRadius = 12.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.5.dp, borderColor),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF57CBDE),
                    disabledContentColor = Color(0xFF4A5260),
                ),
        ) {
            Text(label, fontFamily = InterFont, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun PagePermissions(isCompact: Boolean) {
        val region by navRegion
        val navIdx by navIndex
        val activate by activateSignal
        val controller by controllerConnected

        LaunchedEffect(isCompact) {
            setTabCount(0)
            setContentLayout(3, if (isCompact) 1 else 3)
        }
        val lastActivate = remember { mutableStateOf(activate) }
        LaunchedEffect(activate) {
            if (activate == lastActivate.value) return@LaunchedEffect
            lastActivate.value = activate
            if (region != REGION_CONTENT) return@LaunchedEffect
            when (navIdx) {
                0 -> requestFileAccess()
                1 -> requestNotifications()
                2 -> if (!imageFsInstalling.value) installImageFs()
            }
        }

        val fileAccessCard: @Composable (Modifier) -> Unit = { mod ->
            WizardActionCard(
                modifier = mod,
                title = stringResource(R.string.setup_wizard_allow_file_access),
                subtitle = stringResource(R.string.common_ui_required),
                completed = storageGranted.value,
                buttonLabel = stringResource(if (storageGranted.value) R.string.setup_wizard_granted else R.string.setup_wizard_grant),
                highlighted = controller && region == REGION_CONTENT && navIdx == 0,
                onClick = { setNav(REGION_CONTENT, 0); requestFileAccess() },
            )
        }
        val notifCard: @Composable (Modifier) -> Unit = { mod ->
            WizardActionCard(
                modifier = mod,
                title = stringResource(R.string.common_ui_notifications),
                subtitle = stringResource(R.string.common_ui_optional),
                completed = backgroundSessionEnabled.value,
                buttonLabel =
                    when {
                        backgroundSessionEnabled.value -> stringResource(R.string.setup_wizard_granted)
                        notifDenied.value -> stringResource(R.string.setup_wizard_denied)
                        else -> stringResource(R.string.setup_wizard_allow)
                    },
                highlighted = controller && region == REGION_CONTENT && navIdx == 1,
                onClick = { setNav(REGION_CONTENT, 1); requestNotifications() },
            )
        }
        val systemCard: @Composable (Modifier) -> Unit = { mod ->
            WizardActionCard(
                modifier = mod,
                title = stringResource(R.string.setup_wizard_install_system_files),
                subtitle = stringResource(R.string.common_ui_required),
                completed = imageFsDone.value,
                buttonLabel =
                    when {
                        imageFsDone.value -> stringResource(R.string.common_ui_installed)
                        imageFsInstalling.value -> "${imageFsProgress.intValue}%"
                        else -> stringResource(R.string.setup_wizard_install_system_files)
                    },
                highlighted = controller && region == REGION_CONTENT && navIdx == 2,
                onClick = { setNav(REGION_CONTENT, 2); installImageFs() },
                enabled = !imageFsInstalling.value,
                progress = if (imageFsInstalling.value) imageFsProgress.intValue / 100f else null,
            )
        }

        if (isCompact) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                fileAccessCard(Modifier.fillMaxWidth())
                notifCard(Modifier.fillMaxWidth())
                systemCard(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fileAccessCard(Modifier.weight(1f))
                notifCard(Modifier.weight(1f))
                systemCard(Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun PageAdvancedComponents(isCompact: Boolean) {
        val typeOrder =
            listOf(
                ContentProfile.ContentType.CONTENT_TYPE_WINE,
                ContentProfile.ContentType.CONTENT_TYPE_PROTON,
                ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
                ContentProfile.ContentType.CONTENT_TYPE_BOX64,
                ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64,
            )
        val typeLabels =
            mapOf(
                ContentProfile.ContentType.CONTENT_TYPE_WINE to "Wine",
                ContentProfile.ContentType.CONTENT_TYPE_PROTON to "Proton",
                ContentProfile.ContentType.CONTENT_TYPE_DXVK to "DXVK",
                ContentProfile.ContentType.CONTENT_TYPE_VKD3D to "VKD3D",
                ContentProfile.ContentType.CONTENT_TYPE_BOX64 to "Box64",
                ContentProfile.ContentType.CONTENT_TYPE_FEXCORE to "FEXCore",
                ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 to "Wowbox64",
            )
        val recommendedLabel = stringResource(R.string.setup_wizard_recommended_label)
        val driversLabel = stringResource(R.string.settings_drivers_title)
        var selectedTab by remember { mutableStateOf("recommended") }
        val turquoise = Color(0xFF57CBDE)
        val completedTurquoise = Color(0xFF3FAFBE)
        val glassShape = RoundedCornerShape(12.dp)
        val glassSurface = Color.White.copy(alpha = SetupGlassSurfaceAlpha)
        val glassSurfaceActive = turquoise.copy(alpha = SetupGlassActiveSurfaceAlpha)
        val glassBorder = Color.White.copy(alpha = SetupGlassBorderAlpha)
        val mutedDot = Color(0xFF4A5568)

        val tabs =
            buildList {
                add(TabInfo("recommended", recommendedLabel, turquoise, highlight = true))
                add(
                    TabInfo(
                        "drivers",
                        driversLabel,
                        if (selectedTab == "drivers") turquoise else mutedDot,
                    ),
                )
                typeOrder.forEach { type ->
                    val key = type.name
                    val hasInstalled = advancedProfiles.any { it.type == type && it.verName in advancedInstalledSet }
                    val indicator =
                        when {
                            selectedTab == key -> turquoise
                            hasInstalled -> completedTurquoise
                            else -> mutedDot
                        }
                    add(TabInfo(key, typeLabels[type] ?: type.toString(), indicator))
                }
            }

        val region by navRegion
        val navIdx by navIndex
        val activate by activateSignal
        val controller by controllerConnected

        LaunchedEffect(tabs.size) {
            setTabCount(tabs.size)
            onTabIndexChange = { i -> tabs.getOrNull(i)?.let { selectedTab = it.key } }
        }
        LaunchedEffect(selectedTab) {
            lastContentIndex = 0
            if (navRegion.intValue == REGION_CONTENT) navIndex.intValue = 0
            val i = tabs.indexOfFirst { it.key == selectedTab }
            if (i >= 0 && navRegion.intValue == REGION_TABS && navIndex.intValue != i) {
                navIndex.intValue = i
            }
        }

        // Content panel (shared between layouts)
        @Composable
        fun ContentPanel(modifier: Modifier) {
            Box(
                modifier =
                    modifier
                        .background(glassSurface, glassShape)
                        .border(1.dp, glassBorder, glassShape)
                        .padding(10.dp),
            ) {
                when (selectedTab) {
                    "drivers" -> {
                        LaunchedEffect(selectedTab) { setContentLayout(0, 1) }
                        if (!imageFsDone.value) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.setup_wizard_system_image_not_installed),
                                    color = Color(0xFF8B949E),
                                    fontFamily = InterFont,
                                    fontSize = 12.sp,
                                )
                            }
                        } else {
                            val driversFragmentId = remember { android.view.View.generateViewId() }
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    FragmentContainerView(ctx).apply {
                                        id = driversFragmentId
                                    }
                                },
                                update = { view ->
                                    val fm = supportFragmentManager
                                    if (fm.findFragmentById(driversFragmentId) == null) {
                                        fm
                                            .beginTransaction()
                                            .replace(driversFragmentId, DriversFragment())
                                            .commitNowAllowingStateLoss()
                                    }
                                },
                            )
                        }
                    }

                    else -> {
                        val tabProfiles: List<RemotePackageSpec> =
                            if (selectedTab == "recommended") {
                                advancedProfiles
                                    .filter { isRecommendedSpec(it) }
                                    .sortedWith(compareBy({ typeOrder.indexOf(it.type) }, { it.verName }))
                            } else {
                                val type = typeOrder.firstOrNull { it.name == selectedTab }
                                if (type == null) {
                                    emptyList()
                                } else {
                                    advancedProfiles
                                        .filter { it.type == type }
                                        .sortedByDescending { isRecommendedSpec(it) }
                                }
                            }
                        when {
                            advancedProfiles.isEmpty() -> {
                                LaunchedEffect(selectedTab) { setContentLayout(0, 1) }
                                Row(
                                    modifier = Modifier.align(Alignment.Center),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = turquoise,
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.setup_wizard_loading_components),
                                        color = Color(0xFF8B949E),
                                        fontFamily = InterFont,
                                        fontSize = 12.sp,
                                    )
                                }
                            }

                            tabProfiles.isEmpty() -> {
                                LaunchedEffect(selectedTab) { setContentLayout(0, 1) }
                                Text(
                                    text = stringResource(R.string.setup_wizard_no_components_available),
                                    color = Color(0xFF8B949E),
                                    fontFamily = InterFont,
                                    fontSize = 12.sp,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }

                            else -> {
                                val isRecommendedTab = selectedTab == "recommended"
                                val allRecommendedInstalled =
                                    isRecommendedTab &&
                                        tabProfiles.all { it.verName in advancedInstalledSet }
                                val highlightInstallAll =
                                    isRecommendedTab &&
                                        transferState.value == null &&
                                        !allRecommendedInstalled
                                val installAllSlots = if (isRecommendedTab) 1 else 0
                                val listState = rememberLazyListState()

                                LaunchedEffect(selectedTab, tabProfiles.size, installAllSlots) {
                                    setContentLayout(tabProfiles.size + installAllSlots, 1)
                                }
                                LaunchedEffect(tabProfiles) { fetchAdvancedSizes(tabProfiles) }
                                LaunchedEffect(region, navIdx) {
                                    if (region == REGION_CONTENT) runCatching { listState.scrollToSelected(navIdx) }
                                }
                                val lastActivate = remember { mutableStateOf(activate) }
                                LaunchedEffect(activate) {
                                    if (activate == lastActivate.value) return@LaunchedEffect
                                    lastActivate.value = activate
                                    if (region != REGION_CONTENT) return@LaunchedEffect
                                    if (isRecommendedTab && navIdx == 0) {
                                        if (!allRecommendedInstalled) enqueueAllRecommended()
                                    } else {
                                        tabProfiles.getOrNull(navIdx - installAllSlots)?.let { spec ->
                                            if (spec.verName !in advancedInstalledSet) enqueueAdvancedComponent(spec)
                                        }
                                    }
                                }

                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (isRecommendedTab) {
                                        item {
                                            val installAllEnabled = !allRecommendedInstalled
                                            val navHere = controller && region == REGION_CONTENT && navIdx == 0
                                            val installAllShape = RoundedCornerShape(8.dp)
                                            Box(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.CenterEnd,
                                            ) {
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .background(
                                                                color =
                                                                    if (highlightInstallAll) {
                                                                        glassSurfaceActive
                                                                    } else {
                                                                        glassSurface
                                                                    },
                                                                shape = installAllShape,
                                                            ).then(
                                                                if (highlightInstallAll) {
                                                                    Modifier.chasingBorder(
                                                                        isFocused = true,
                                                                        cornerRadius = 8.dp,
                                                                        borderWidth = 1.5.dp,
                                                                        animationDurationMs = 8200,
                                                                    )
                                                                } else {
                                                                    Modifier.border(
                                                                        width = 1.dp,
                                                                        color =
                                                                            if (allRecommendedInstalled) {
                                                                                completedTurquoise
                                                                            } else if (!installAllEnabled) {
                                                                                glassBorder
                                                                            } else {
                                                                                turquoise.copy(alpha = 0.65f)
                                                                            },
                                                                        shape = installAllShape,
                                                                    )
                                                                },
                                                            ).navHighlight(navHere, cornerRadius = 8.dp)
                                                            .clickable(
                                                                enabled = installAllEnabled,
                                                                interactionSource = remember { MutableInteractionSource() },
                                                                indication = null,
                                                            ) { setNav(REGION_CONTENT, 0); enqueueAllRecommended() }
                                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        text =
                                                            stringResource(
                                                                if (allRecommendedInstalled) {
                                                                    R.string.common_ui_installed
                                                                } else {
                                                                    R.string.setup_wizard_install_all_recommended
                                                                },
                                                            ),
                                                        fontFamily = InterFont,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 11.sp,
                                                        color =
                                                            if (highlightInstallAll) {
                                                                Color(0xFFF0F4FF)
                                                            } else if (allRecommendedInstalled) {
                                                                completedTurquoise
                                                            } else if (!installAllEnabled) {
                                                                Color(0xFF4A5260)
                                                            } else {
                                                                turquoise
                                                            },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    itemsIndexed(tabProfiles) { i, spec ->
                                        val installed = spec.verName in advancedInstalledSet
                                        val navHere = controller && region == REGION_CONTENT && navIdx == i + installAllSlots
                                        AdvancedComponentCard(
                                            name = spec.verName,
                                            installed = installed,
                                            highlighted = navHere,
                                            onClick = { setNav(REGION_CONTENT, i + installAllSlots); enqueueAdvancedComponent(spec) },
                                            enabled = !installed,
                                            recommended = isRecommendedSpec(spec),
                                            status = queueStatus[spec.remoteUrl],
                                            sizeBytes = advancedSizeCache[spec.remoteUrl],
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        @Composable
        fun TabItem(
            tab: TabInfo,
            index: Int,
            fillWidth: Boolean,
            fontSize: TextUnit,
        ) {
            val isSelected = selectedTab == tab.key
            val highlighted = controller && region == REGION_TABS && navIdx == index
            val interactionSource = remember { MutableInteractionSource() }
            val bgColor = if (isSelected) glassSurfaceActive else Color.Transparent
            val labelColor =
                when {
                    isSelected -> Color(0xFFE6EDF3)
                    tab.highlight -> turquoise
                    else -> Color(0xFFCDD9E5)
                }
            Row(
                modifier =
                    Modifier
                        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                        .background(bgColor, RoundedCornerShape(8.dp))
                        .navHighlight(highlighted, cornerRadius = 8.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) { setNav(REGION_TABS, index) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .background(tab.indicatorColor, RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.width(if (fillWidth) 8.dp else 6.dp))
                Text(
                    text = tab.label,
                    color = labelColor,
                    fontFamily = InterFont,
                    fontSize = fontSize,
                    fontWeight = if (isSelected || tab.highlight) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }

        if (isCompact) {
            // Compact: horizontal scrolling tab strip on top, content below
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(glassSurface, glassShape)
                            .border(1.dp, glassBorder, glassShape)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tabs.forEachIndexed { i, tab -> TabItem(tab, i, fillWidth = false, fontSize = 12.sp) }
                }

                ContentPanel(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                )
            }
        } else {
            // Wide: side-by-side rail + content
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Left rail
                val tabListState = rememberLazyListState()
                LaunchedEffect(region, navIdx) {
                    if (region == REGION_TABS) runCatching { tabListState.scrollToSelected(navIdx) }
                }
                LazyColumn(
                    state = tabListState,
                    modifier =
                        Modifier
                            .weight(0.38f)
                            .fillMaxHeight()
                            .background(glassSurface, glassShape)
                            .border(1.dp, glassBorder, glassShape)
                            .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(tabs) { i, tab -> TabItem(tab, i, fillWidth = true, fontSize = 13.sp) }
                }

                ContentPanel(
                    modifier =
                        Modifier
                            .weight(0.62f)
                            .fillMaxHeight(),
                )
            }
        }
    }

    @Composable
    private fun AdvancedComponentCard(
        name: String,
        installed: Boolean,
        onClick: () -> Unit,
        enabled: Boolean = true,
        recommended: Boolean = false,
        status: QueueItemStatus? = null,
        highlighted: Boolean = false,
        sizeBytes: Long? = null,
    ) {
        val turquoise = Color(0xFF57CBDE)
        val completedTurquoise = Color(0xFF3FAFBE)
        val cardShape = RoundedCornerShape(12.dp)
        val bgColor =
            if (installed) {
                completedTurquoise.copy(alpha = SetupGlassCompletedSurfaceAlpha)
            } else {
                Color.White.copy(alpha = SetupGlassSurfaceAlpha)
            }
        val outlineColor =
            if (installed) {
                completedTurquoise.copy(alpha = SetupGlassCompletedBorderAlpha)
            } else {
                Color.White.copy(alpha = SetupGlassBorderAlpha)
            }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(bgColor, cardShape)
                    .border(1.dp, outlineColor, cardShape)
                    .navHighlight(highlighted, cornerRadius = 12.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (recommended) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier
                                    .size(4.dp)
                                    .background(if (installed) completedTurquoise else turquoise, RoundedCornerShape(50)),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.setup_wizard_recommended_label),
                            color = if (installed) completedTurquoise else turquoise,
                            fontFamily = InterFont,
                            fontWeight = FontWeight.Medium,
                            fontSize = 9.sp,
                            letterSpacing = 0.5.sp,
                        )
                        sizeBytes?.takeIf { it > 0L }?.let { bytes ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = formatSizeMb(bytes),
                                color = Color(0xFF8B949E),
                                fontFamily = InterFont,
                                fontSize = 9.sp,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                }
                Text(
                    text = name,
                    color = Color(0xFFE6EDF3),
                    fontFamily = InterFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!recommended) {
                    sizeBytes?.takeIf { it > 0L }?.let { bytes ->
                        Text(
                            text = formatSizeMb(bytes),
                            color = Color(0xFF8B949E),
                            fontFamily = InterFont,
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                !installed && status == QueueItemStatus.ACTIVE ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = turquoise,
                        strokeWidth = 2.dp,
                    )

                !installed && status == QueueItemStatus.QUEUED ->
                    Text(
                        text = stringResource(R.string.downloads_queue_phase_queued),
                        color = turquoise,
                        fontFamily = InterFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                    )

                else -> {
                    val isRetry = !installed && status == QueueItemStatus.FAILED
                    Button(
                        onClick = onClick,
                        enabled = enabled && !installed,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    if (installed) completedTurquoise.copy(alpha = 0.14f) else turquoise.copy(alpha = 0.14f),
                                contentColor = if (installed) completedTurquoise else turquoise,
                                disabledContainerColor = if (installed) completedTurquoise.copy(alpha = 0.14f) else turquoise.copy(alpha = 0.16f),
                                disabledContentColor = if (installed) completedTurquoise else turquoise,
                            ),
                    ) {
                        Text(
                            text =
                                stringResource(
                                    when {
                                        installed -> R.string.common_ui_installed
                                        isRetry -> R.string.session_drawer_retry
                                        else -> R.string.common_ui_install
                                    },
                                ),
                            fontFamily = InterFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PageDefaultSettings() {
        val contentsManager =
            remember {
                ContentsManager(this@SetupWizardActivity).also { it.syncContents() }
            }
        val installedRuntimes =
            remember(advancedInstalledSet.toList()) {
                val wineProfiles =
                    contentsManager
                        .getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE)
                        .orEmpty()
                        .filter { it.isInstalled }
                val protonProfiles =
                    contentsManager
                        .getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON)
                        .orEmpty()
                        .filter { it.isInstalled }
                (wineProfiles + protonProfiles)
            }

        if (installedRuntimes.isEmpty()) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 420.dp)
                        .background(Color.White.copy(alpha = SetupGlassSurfaceAlpha), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = SetupGlassBorderAlpha), RoundedCornerShape(12.dp))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.setup_wizard_no_runtime_installed),
                    color = Color(0xFF8B949E),
                    fontFamily = InterFont,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.setup_wizard_no_runtime_hint),
                    color = Color(0xFF6B7580),
                    fontFamily = InterFont,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                val gridColumns = 3
                val compactGrid = maxWidth < 720.dp || maxHeight < 280.dp
                val region by navRegion
                val navIdx by navIndex
                val activate by activateSignal
                val controller by controllerConnected
                LaunchedEffect(installedRuntimes.size, gridColumns) {
                    setTabCount(0)
                    setContentLayout(installedRuntimes.size, gridColumns)
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    modifier =
                        Modifier
                            .widthIn(max = 960.dp)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(if (compactGrid) 8.dp else 10.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compactGrid) 6.dp else 10.dp),
                    contentPadding = PaddingValues(bottom = if (compactGrid) 4.dp else 12.dp),
                ) {
                    gridItemsIndexed(installedRuntimes) { i, profile ->
                        RuntimeContainerCard(
                            profile,
                            compact = compactGrid,
                            highlighted = controller && region == REGION_CONTENT && navIdx == i,
                            activate = activate,
                            onTap = { setNav(REGION_CONTENT, i) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RuntimeContainerCard(
        profile: ContentProfile,
        compact: Boolean = false,
        highlighted: Boolean = false,
        activate: Int = 0,
        onTap: () -> Unit = {},
    ) {
        val entryName = ContentsManager.getEntryName(profile)
        val displayName = ContainerCreation.displayNameForProfile(profile)
        val isArm64 = profile.verName.contains("arm64ec", ignoreCase = true)
        val archLabel = if (isArm64) "ARM64EC" else "x86-64"

        val containerManager = remember { ContainerManager(this@SetupWizardActivity) }
        var existingContainer by remember {
            mutableStateOf(
                containerManager.containers.firstOrNull { it.wineVersion == entryName },
            )
        }
        val creating = creatingContainer.value

        val hasContainer = existingContainer != null
        val turquoise = Color(0xFF57CBDE)
        val completedTurquoise = Color(0xFF3FAFBE)
        val cardShape = RoundedCornerShape(12.dp)
        val bgColor = Color.White.copy(alpha = SetupGlassSurfaceAlpha)
        val activeColor = if (hasContainer) completedTurquoise else Color(0xFF4A5260)
        val outlineColor =
            if (hasContainer) {
                completedTurquoise.copy(alpha = SetupGlassCompletedSoftBorderAlpha)
            } else {
                Color.White.copy(alpha = SetupGlassBorderAlpha)
            }

        val createContainer = {
            if (!creating && transferState.value == null) {
                creatingContainer.value = true
                lifecycleScope.launch {
                    wizardError.value = null
                    val container =
                        withContext(Dispatchers.IO) {
                            try {
                                val c = ensureContainerForProfile(profile, displayName)
                                if (isArm64) {
                                    saveDefaultArm64ContainerId(this@SetupWizardActivity, c.id)
                                } else {
                                    saveDefaultX86ContainerId(this@SetupWizardActivity, c.id)
                                }
                                c
                            } catch (e: Exception) {
                                updateWizardError("Container creation failed: ${e.message}")
                                null
                            }
                        }
                    existingContainer = container
                    creatingContainer.value = false
                    refreshAdvancedInstalledSet()
                    refreshWizardState()
                }
            }
        }
        val openSettings = {
            existingContainer?.let { openContainerDefaultSettings(it.id, if (isArm64) "arm64" else "x86") }
            Unit
        }

        val lastActivate = remember { mutableStateOf(activate) }
        LaunchedEffect(activate) {
            if (activate != lastActivate.value) {
                lastActivate.value = activate
                if (highlighted) {
                    if (existingContainer == null) createContainer() else openSettings()
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(bgColor, cardShape)
                    .border(1.dp, outlineColor, cardShape)
                    .navHighlight(highlighted, cornerRadius = 12.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTap() }
                    .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .background(
                                    activeColor,
                                    RoundedCornerShape(3.dp),
                                ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = archLabel,
                        color = if (hasContainer) activeColor else Color(0xFF8B949E),
                        fontFamily = InterFont,
                        fontSize = if (compact) 8.sp else 9.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
                Text(
                    text = displayName,
                    color = Color(0xFFE6EDF3),
                    fontFamily = InterFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = if (compact) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
            if (existingContainer == null) {
                Button(
                    onClick = { onTap(); createContainer() },
                    enabled = !creating && transferState.value == null,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(if (compact) 24.dp else 28.dp),
                    contentPadding = PaddingValues(horizontal = if (compact) 9.dp else 12.dp, vertical = 0.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = turquoise.copy(alpha = 0.14f),
                            contentColor = turquoise,
                            disabledContainerColor =
                                if (creating) turquoise.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f),
                            disabledContentColor = if (creating) turquoise else Color(0xFF4A5260),
                        ),
                ) {
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (compact) 12.dp else 14.dp),
                            color = turquoise,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
                    }
                    Text(
                        text =
                            stringResource(
                                if (creating) R.string.setup_wizard_creating_container else R.string.setup_wizard_create_container,
                        ),
                        fontFamily = InterFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 9.sp else 10.sp,
                    )
                }
            } else {
                Button(
                    onClick = { onTap(); openSettings() },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(if (compact) 24.dp else 28.dp),
                    contentPadding = PaddingValues(horizontal = if (compact) 9.dp else 12.dp, vertical = 0.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = completedTurquoise.copy(alpha = 0.14f),
                            contentColor = completedTurquoise,
                        ),
                ) {
                    Text(
                        text = stringResource(R.string.setup_wizard_default_settings),
                        fontFamily = InterFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 9.sp else 10.sp,
                    )
                }
            }
        }
    }

    @Composable
    private fun WizardActionCard(
        modifier: Modifier = Modifier,
        title: String,
        subtitle: String,
        completed: Boolean,
        buttonLabel: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        progress: Float? = null,
        highlighted: Boolean = false,
    ) {
        val turquoise = Color(0xFF57CBDE)
        val completedTurquoise = Color(0xFF3FAFBE)
        val glassShape = RoundedCornerShape(12.dp)
        val glassSurface =
            if (completed) {
                completedTurquoise.copy(alpha = SetupGlassCompletedSurfaceAlpha)
            } else {
                Color.White.copy(alpha = SetupGlassSurfaceAlpha)
            }
        val borderColor =
            when {
                completed -> completedTurquoise.copy(alpha = SetupGlassCompletedBorderAlpha)
                progress != null -> turquoise
                else -> Color.White.copy(alpha = SetupGlassBorderAlpha)
            }
        val buttonFocused = highlighted && enabled && !completed
        Column(
            modifier =
                modifier
                    .background(glassSurface, glassShape)
                    .border(1.dp, borderColor, glassShape)
                    .navHighlight(highlighted, cornerRadius = 12.dp)
                    .clickable(
                        enabled = enabled && !completed,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClick() }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            // Status chip
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .background(
                                when {
                                    completed -> completedTurquoise
                                    progress != null -> turquoise
                                    else -> Color(0xFF4A5260)
                                },
                                RoundedCornerShape(3.dp),
                            ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = subtitle.uppercase(),
                    color = if (completed) completedTurquoise else Color(0xFF8B949E),
                    fontFamily = InterFont,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                color = Color(0xFFE6EDF3),
                fontFamily = InterFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress != null) {
                val animatedProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(durationMillis = 400, easing = LinearEasing),
                    label = "cardProgress",
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    color = turquoise,
                    trackColor = Color.White.copy(alpha = 0.12f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onClick,
                enabled = enabled && !completed,
                shape = RoundedCornerShape(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .then(
                            if (buttonFocused) {
                                Modifier.chasingBorder(
                                    cornerRadius = 8.dp,
                                    borderWidth = 1.5.dp,
                                    animationDurationMs = 8200,
                                )
                            } else {
                                Modifier
                            },
                        ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            when {
                                completed -> completedTurquoise.copy(alpha = 0.16f)
                                buttonFocused -> Color(0xFF0E2A38)
                                else -> turquoise
                            },
                        contentColor =
                            when {
                                completed -> completedTurquoise
                                buttonFocused -> Color(0xFFE6EDF3)
                                else -> Color(0xFF111822)
                            },
                        disabledContainerColor =
                            when {
                                completed -> completedTurquoise.copy(alpha = 0.16f)
                                progress != null -> turquoise.copy(alpha = 0.14f)
                                else -> Color.White.copy(alpha = 0.08f)
                            },
                        disabledContentColor =
                            when {
                                completed -> completedTurquoise
                                progress != null -> Color(0xFFE6EDF3)
                                else -> Color(0xFF4A5260)
                            },
                    ),
            ) {
                Text(
                    text = buttonLabel,
                    fontFamily = InterFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun contentVersionIdentifier(profile: ContentProfile): String = ContentsManager.getEntryName(profile).substringAfter('-')

private fun resolveJsonDownloadUrl(url: String): String {
    val githubPrefix = "https://github.com/"
    if (!url.startsWith(githubPrefix) || "/blob/" !in url) {
        return url
    }

    val path = url.removePrefix(githubPrefix)
    val ownerRepo = path.substringBefore("/blob/")
    val blobPath = path.substringAfter("/blob/")
    val branch = blobPath.substringBefore('/')
    val filePath = blobPath.substringAfter('/', "")
    if (ownerRepo.isBlank() || branch.isBlank() || filePath.isBlank()) {
        return url
    }

    return "https://raw.githubusercontent.com/$ownerRepo/$branch/$filePath"
}
