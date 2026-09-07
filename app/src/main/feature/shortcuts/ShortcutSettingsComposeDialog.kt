package com.winlator.cmod.feature.shortcuts
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import com.winlator.cmod.runtime.display.environment.components.NetworkingSettings
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.feature.library.DriveItem
import com.winlator.cmod.feature.library.EnvVarItem
import com.winlator.cmod.feature.library.parseEnvVarItems
import androidx.compose.runtime.getValue
import com.winlator.cmod.feature.library.GameSettingsCallbacks
import com.winlator.cmod.feature.library.GameSettingsContent
import com.winlator.cmod.feature.library.GameSettingsNav
import com.winlator.cmod.shared.ui.nav.PANE_DIR_ACTIVATE
import com.winlator.cmod.shared.ui.nav.PaneNavWindowHandlers
import com.winlator.cmod.shared.ui.nav.bindPaneNav
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import com.winlator.cmod.shared.ui.focus.controllerMenuInput
import com.winlator.cmod.feature.library.GameSettingsStateHolder
import com.winlator.cmod.feature.library.WinComponentItem
import com.winlator.cmod.feature.settings.DXVKConfigUtils
import com.winlator.cmod.feature.settings.GraphicsDriverConfigUtils
import com.winlator.cmod.feature.settings.WineD3DConfigUtils
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.runtime.audio.directaudio.DirectAudioDriver
import com.winlator.cmod.runtime.compat.box64.Box64Preset
import com.winlator.cmod.runtime.compat.box64.Box64PresetManager
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.shared.android.AppUtils
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.android.ImageUtils
import com.winlator.cmod.shared.io.AssetPaths
import com.winlator.cmod.runtime.wine.EnvVars
import com.winlator.cmod.runtime.wine.WineUtils
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.util.KeyValueSet
import com.winlator.cmod.shared.android.RefreshRateUtils
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.util.StringUtils
import com.winlator.cmod.runtime.wine.WineInfo
import com.winlator.cmod.runtime.compat.fexcore.FEXCoreManager
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePreset
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePresetManager
import com.winlator.cmod.feature.settings.OtherSettingsFragment
import com.winlator.cmod.feature.shortcuts.ShortcutsFragment
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import com.winlator.cmod.runtime.input.controls.GestureProfileManager
import com.winlator.cmod.runtime.input.controls.InputControlsManager
import com.winlator.cmod.runtime.audio.midi.MidiManager
import com.winlator.cmod.runtime.display.winhandler.WinHandler
import com.winlator.cmod.feature.artwork.SteamArtworkScraper
import java.io.File
import java.lang.reflect.Field
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.Executors

private enum class LibraryArtworkTarget {
    GAME_CARD,
    ICON_ART,
}

// Only what BitmapFactory can decode.
private val ARTWORK_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif", "ico")

class ShortcutSettingsComposeDialog private constructor(
    private val activity: Activity,
    private val shortcut: Shortcut,
    private val fragment: ShortcutsFragment?
) {
    private val context: Context = activity

    constructor(fragment: ShortcutsFragment, shortcut: Shortcut) :
        this(fragment.requireActivity(), shortcut, fragment)

    constructor(activity: Activity, shortcut: Shortcut) :
        this(activity, shortcut, null)
    private val dialog: Dialog
    private val state = GameSettingsStateHolder()
    private val nav = GameSettingsNav()
    private var restorePaneNav: (() -> Unit)? = null

    // Java interop references
    private var inputControlsManager: InputControlsManager = InputControlsManager(context)
    private var gestureProfileManager: GestureProfileManager = GestureProfileManager(context)
    private var contentsManager: ContentsManager = ContentsManager(context)
    private var isArm64EC = false


    // Preset ID lists (parallel to display name lists)
    private var box64PresetIds = mutableListOf<String>()
    private var fexcorePresetIds = mutableListOf<String>()
    private var shouldRefreshLibraryOnSave = false

    // SDL2 Compatibility env vars — must match ContainerDetailFragment.SDL2_ENV_VARS.
    private val sdl2EnvVars = listOf(
        "SDL_JOYSTICK_WGI" to "0",
        "SDL_XINPUT_ENABLED" to "1",
        "SDL_JOYSTICK_RAWINPUT" to "0",
        "SDL_JOYSTICK_HIDAPI" to "0",
        "SDL_GAMECONTROLLER_ALLOW_STEAM_VIRTUAL_GAMEPAD" to "1",
        "SDL_DIRECTINPUT_ENABLED" to "0",
        "SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS" to "1",
        "SDL_HINT_FORCE_RAISEWINDOW" to "0",
        "SDL_ALLOW_TOPMOST" to "0",
        "SDL_MOUSE_FOCUS_CLICKTHROUGH" to "1"
    )

    // Container list for container selection
    private var containerList = mutableListOf<Container>()

    init {
        state.wined3dCsmtEntries.value =
            listOf(context.getString(R.string.common_ui_enabled), context.getString(R.string.common_ui_disabled))
        state.wined3dStrictShaderMathEntries.value =
            listOf(context.getString(R.string.common_ui_enabled), context.getString(R.string.common_ui_disabled))
        dialog = Dialog(activity, R.style.ContentDialog).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(true)
            setCanceledOnTouchOutside(false)
            setOwnerActivity(activity)
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                setDimAmount(0.5f)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    isNavigationBarContrastEnforced = false
                }
                // Blur-behind is applied in show() post-attach to avoid flicker.
            }
            setOnDismissListener {
                restorePaneNav?.invoke()
                restorePaneNav = null
            }
        }

        loadInitialData()
        loadResourceArrays()

        val composeView = ComposeView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewTreeLifecycleOwner(activity as LifecycleOwner)
            setViewTreeSavedStateRegistryOwner(activity as SavedStateRegistryOwner)
            setContent {
                WinNativeTheme {
                    val defaultDensity = LocalDensity.current
                    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                    androidx.compose.runtime.LaunchedEffect(
                        configuration.orientation,
                        configuration.screenWidthDp,
                        configuration.screenHeightDp,
                    ) {
                        val w = dialog.window
                        w?.applyDialogLayout()
                        w?.decorView?.post { w.applyDialogLayout() }
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(defaultDensity.density, fontScale = 1f)
                    ) {
                        val callbacks = createCallbacks()
                        GameSettingsContent(state = state, callbacks = callbacks, nav = nav)
                    }
                }
            }
        }
        dialog.setContentView(composeView)

        // Auto-dismiss when activity is destroyed.
        (activity as LifecycleOwner).lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (dialog.isShowing) dialog.dismiss()
            }
        })

        loadContentsAsync()
    }

    private fun createCallbacks(): GameSettingsCallbacks {
        return object : GameSettingsCallbacks {
            override fun onConfirm() {
                saveSettings()
                emitLibraryRefreshIfNeeded()
                dismiss()
            }

            override fun onDismiss() {
                dismiss()
            }

            override fun onAddToHomeScreen() {
                val result = if (fragment != null) {
                    fragment.addShortcutToScreen(shortcut)
                } else {
                    addShortcutToScreen(shortcut)
                }
                if (result == ShortcutsFragment.PinShortcutResult.REUSED_EXISTING) {
                    WinToast.show(context, R.string.shortcuts_list_readded_existing, shortcut.icon, dialog.window?.decorView)
                } else if (result == ShortcutsFragment.PinShortcutResult.FAILED) {
                    WinToast.show(
                        context,
                        context.getString(
                            R.string.library_games_failed_to_create_shortcut,
                            shortcut.name
                        ),
                        dialog.window?.decorView,
                    )
                }
            }

            override fun onScrapeGameArtwork(gameName: String) {
                WinToast.show(context, context.getString(R.string.library_games_scraping_artwork), Toast.LENGTH_LONG, dialog.window?.decorView)
                CoroutineScope(Dispatchers.IO).launch {
                    val artworkInfo = SteamArtworkScraper(context).getGameArtwork(gameName)
                    withContext(Dispatchers.Main) {
                        var saved = false
                        artworkInfo.forEach { (slotSuffix, file) ->
                            val slot =
                                LibraryShortcutArtwork.LibraryArtworkSlot.entries
                                    .find { it.fileSuffix == slotSuffix }
                            if (slot != null && saveScrapedLibraryArtwork(file.toUri(), slot)) {
                                saved = true
                            }
                            file.delete()
                        }
                        if (saved) {
                            shortcut.saveData()
                            shouldRefreshLibraryOnSave = true
                            syncLibraryArtworkState()
                            emitLibraryRefreshIfNeeded()
                        }
                        WinToast.show(
                            context,
                            context.getString(if (saved) R.string.common_ui_done else R.string.common_ui_failed),
                            Toast.LENGTH_LONG,
                            dialog.window?.decorView,
                        )
                    }
                }
            }

            override fun onPickGameCardArtwork() {
                pickLibraryArtwork(LibraryArtworkTarget.GAME_CARD)
            }

            override fun onRemoveGameCardArtwork() {
                clearLibraryArtwork(LibraryArtworkTarget.GAME_CARD)
            }

            override fun onPickIconArtwork() {
                pickLibraryArtwork(LibraryArtworkTarget.ICON_ART)
            }

            override fun onRemoveIconArtwork() {
                clearLibraryArtwork(LibraryArtworkTarget.ICON_ART)
            }

            override fun onOpenArtworkSource(gameName: String) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            String.format("https://www.steamgriddb.com/search/grids?term=%s", Uri.encode(gameName)).toUri()
                        ),
                    )
                }
            }

            override fun onRemoveEnvVar(index: Int) {
                val currentVars = state.envVars.value.toMutableList()
                if (index in currentVars.indices) {
                    currentVars.removeAt(index)
                    state.envVars.value = currentVars
                }
            }

            override fun onGfxDriverVersionChanged(versionIndex: Int) {
                loadExtensionsForVersion(versionIndex)
                val versions = state.gfxDriverVersionEntries.value
                state.graphicsDriverVersion.value = versions.getOrElse(versionIndex) { "" }
            }

            override fun onDxvkVersionChanged(versionIndex: Int) {
                handleDxvkVersionChanged(versionIndex)
            }

            override fun onDxvkVkd3dVersionChanged(versionIndex: Int) {
                handleDxvkVkd3dVersionChanged(versionIndex)
            }

            override fun onContainerChanged(containerIndex: Int) {
                handleContainerChanged(containerIndex)
            }

            override fun onEmulatorChanged() {
                updateEmulatorFrameVisibility()
            }

            override fun onSelectExe() {
                DirectoryPickerDialog.showFile(
                    activity = activity,
                    initialPath = resolveExePickerInitialPath(),
                    title = context.getString(R.string.common_ui_select_exe),
                    allowedExtensions = DirectoryPickerDialog.ExecutableExtensions,
                    dimAmount = 0.5f,
                    preserveBackdropBlur = true,
                ) { path ->
                    applySelectedExePath(path)
                }
            }

            override fun onUpdateWinComponent(isDirectX: Boolean, index: Int, newValue: Int) {
                if (isDirectX) {
                    val components = state.directXComponents.value.toMutableList()
                    if (index in components.indices) {
                        components[index] = components[index].copy(selectedIndex = newValue)
                        state.directXComponents.value = components
                    }
                } else {
                    val components = state.generalComponents.value.toMutableList()
                    if (index in components.indices) {
                        components[index] = components[index].copy(selectedIndex = newValue)
                        state.generalComponents.value = components
                    }
                }
            }
        }
    }

    private fun loadInitialData() {
        val container = shortcut.container

        state.name.value = shortcut.getExtra("custom_name", shortcut.name).ifBlank { shortcut.name }
        state.launchExePath.value = resolveInitialLaunchExePath()
        state.launchExeDisplayPath.value = resolveLaunchExeDisplayPath(state.launchExePath.value)
        syncLibraryArtworkState()

        val inputType = Integer.parseInt(
            getShortcutSetting("inputType", container.getInputType().toString())
        )
        state.enableXInput.value =
            (inputType and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) == WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
        state.enableDInput.value =
            (inputType and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) == WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
        state.selectedDInputMapperType.intValue =
            if ((inputType and WinHandler.FLAG_DINPUT_MAPPER_STANDARD.toInt()) == WinHandler.FLAG_DINPUT_MAPPER_STANDARD.toInt()) 0 else 1
        state.disableXInput.value = shortcut.getExtra("disableXinput", "0") == "1"
        state.shortcutExclusiveXInput.value = shortcut.getExtra("exclusiveXInput", "").let {
            if (it.isEmpty()) container.isExclusiveXInput() else it == "1"
        }
        state.simTouchScreen.value = shortcut.getExtra("simTouchScreen", "0") == "1"
        state.screenTouchMode.intValue = shortcut.getExtra(
            "screenTouchMode",
            if (shortcut.getExtra("simTouchScreen", "0") == "1") "1" else "0"
        ).toIntOrNull() ?: 0
        val gestureProfiles = gestureProfileManager.profiles
        state.gestureProfileEntries.value =
            listOf(context.getString(R.string.common_ui_none)) + gestureProfiles.map { it.name }
        state.gestureProfileIds.value = listOf(0) + gestureProfiles.map { it.id }
        val gid = shortcut.getExtra("gestureProfileId", "0").toIntOrNull() ?: 0
        state.selectedGestureProfile.intValue =
            state.gestureProfileIds.value.indexOf(gid).let { if (it >= 0) it else 0 }

        // Steam options
        val gameSource = shortcut.getExtra("game_source", "")
        state.isSteamGame.value = gameSource == "STEAM" || gameSource == "steam"
        if (state.isSteamGame.value) {
            state.steamLauncher.value =
                com.winlator.cmod.feature.stores.steam.utils.PrefManager.wnPlanW
            // Legacy Launcher is on if either underlying setting was previously on.
            state.useLegacyLauncher.value =
                getShortcutSetting("useColdClient", if (container.isUseColdClient) "1" else "0") == "1" ||
                getShortcutSetting("unpackFiles", if (container.isUnpackFiles) "1" else "0") == "1"
            state.useSteamInput.value = shortcut.getExtra("useSteamInput", "0") == "1"
            state.steamOfflineMode.value = getShortcutSetting(
                "steamOfflineMode", if (container.isSteamOfflineMode) "1" else "0") == "1"
            state.runtimePatcher.value = getShortcutSetting(
                "runtimePatcher", if (container.isRuntimePatcher) "1" else "0") == "1"
        }

        // Desktop Theme
        val desktopTheme = getShortcutSetting("desktopTheme", container.getDesktopTheme())
        // Will be used when entries are loaded

        // Advanced - System
        state.execArgs.value = shortcut.getExtra("execArgs", "")
        val fullscreenStretched = getShortcutSetting(
            "fullscreenStretched",
            if (container.isFullscreenStretched) "1" else "0"
        )
        state.fullscreenStretched.value = fullscreenStretched == "1"
        state.useUnixLibs.value = getShortcutSetting(
            "useUnixLibs",
            if (container.isUseUnixLibs) "1" else "0"
        ) == "1"

        // LC_ALL
        state.lcAll.value = getShortcutSetting("lc_all", container.getLC_ALL())

        // CPU Affinity
        val cpuList = getShortcutSetting("cpuList", container.getCPUList(true))
        val cpuCount = Runtime.getRuntime().availableProcessors()
        state.cpuCount.intValue = cpuCount
        val checked = MutableList(cpuCount) { true }
        if (cpuList.isNotEmpty()) {
            // Reset all to false, then enable specified CPUs
            for (i in checked.indices) checked[i] = false
            cpuList.split(",").forEach { cpuStr ->
                val idx = cpuStr.trim().replace("CPU", "").toIntOrNull()
                if (idx != null && idx in checked.indices) checked[idx] = true
            }
        }
        state.cpuChecked.value = checked

        // CPU Affinity (32-bit / WoW64)
        val cpuListWoW64 = getShortcutSetting("cpuListWoW64", container.getCPUListWoW64(true))
        val checkedWoW64 = MutableList(cpuCount) { true }
        if (cpuListWoW64.isNotEmpty()) {
            for (i in checkedWoW64.indices) checkedWoW64[i] = false
            cpuListWoW64.split(",").forEach { cpuStr ->
                val idx = cpuStr.trim().replace("CPU", "").toIntOrNull()
                if (idx != null && idx in checkedWoW64.indices) checkedWoW64[idx] = true
            }
        }
        state.cpuCheckedWoW64.value = checkedWoW64

        // Win Components
        loadWinComponents()

        // Env Vars
        loadEnvVars()
    }

    private fun loadResourceArrays() {
        val container = shortcut.container

        // Screen sizes
        val screenSizeArr =
            context.resources.getStringArray(R.array.screen_size_entries).toList()
        state.screenSizeEntries.value = screenSizeArr
        val screenSize = getShortcutSetting("screenSize", container.getScreenSize())
        selectScreenSize(screenSize)

        // Container selection
        loadContainerList()

        // Refresh rate
        try {
            val refreshEntries = OtherSettingsFragment.buildRefreshRateEntries(activity)
            state.refreshRateEntries.value = refreshEntries
            val savedRate = shortcut.getExtra("refreshRate", "0")
            if (savedRate.isNullOrEmpty() || savedRate == "0") {
                state.selectedRefreshRate.intValue = 0
            } else {
                val target = "$savedRate Hz"
                val idx = refreshEntries.indexOfFirst { it == target }
                state.selectedRefreshRate.intValue = if (idx >= 0) idx else 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading refresh rate entries", e)
        }

        // FPS Limit
        val savedFpsLimit = shortcut.getExtra("fpsLimit", "0")
        state.fpsLimit.intValue = savedFpsLimit.toIntOrNull() ?: 0

        // SGSR 1 per-game shortcut settings
        state.sgsrEnabled.value = shortcut.getExtra("sgsrEnabled", "0") == "1"
        state.sgsrUpscaleMode.intValue =
            shortcut.getExtra("sgsrUpscaleMode", shortcut.getExtra("sgsr_upscale_mode", "1"))
                .toIntOrNull()
                ?.coerceIn(1, 6)
                ?: 1
        state.sgsrSharpness.intValue =
            shortcut.getExtra("sgsrSharpness", shortcut.getExtra("sgsr_sharpness", "100"))
                .toIntOrNull()
                ?.coerceIn(0, 100)
                ?: 100

        state.netDriverEntries.value = listOf(
            context.getString(R.string.networking_driver_winnative),
            context.getString(R.string.networking_driver_none)
        )
        state.selectedNetDriver.intValue =
            if (NetworkingSettings.driverOrDefault(
                    getShortcutSetting(
                        NetworkingSettings.EXTRA_DRIVER,
                        container.getExtra(NetworkingSettings.EXTRA_DRIVER, NetworkingSettings.DEFAULT_DRIVER)
                    )
                ) == NetworkingSettings.DRIVER_NONE) 1 else 0
        state.netMac.value =
            getShortcutSetting(NetworkingSettings.EXTRA_MAC, container.getExtra(NetworkingSettings.EXTRA_MAC, ""))
        state.netMacAuto.value = NetworkingSettings.automaticMac(context)

        state.frameGenEnabled.value =
            getShortcutSetting("frameGen", container.getExtra("frameGen", "0")) == "1"
        state.frameGenMultiplier.intValue =
            getShortcutSetting("frameGenMultiplier", container.getExtra("frameGenMultiplier", "2"))
                .toIntOrNull()
                ?.coerceIn(2, 4)
                ?: 2
        state.frameGenTargetRate.intValue =
            getShortcutSetting("frameGenTargetRate", container.getExtra("frameGenTargetRate", "0"))
                .toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
        state.frameGenFlowScale.intValue =
            getShortcutSetting("frameGenFlowScale", container.getExtra("frameGenFlowScale", "70"))
                .toIntOrNull()
                ?.coerceIn(25, 100)
                ?: 70

        // shortcut override else container value; legacy single reshadeEffect/flat params migrated in parse
        val reshadeEffects = com.winlator.cmod.runtime.reshade.ReshadeManager.scanEffects(context)
        state.reshadeEffects.value = reshadeEffects
        state.reshadeLoadout.init(
            reshadeEffects,
            getShortcutSetting(
                com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_LOADOUT,
                container.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_LOADOUT, "")
            ).ifEmpty { null },
            getShortcutSetting(
                com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_MODE,
                container.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_MODE, "solo")
            ),
            getShortcutSetting(
                com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_PARAMS,
                container.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_PARAMS, "")
            ).ifEmpty { null },
            getShortcutSetting(
                com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_EFFECT,
                container.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_EFFECT, "None")
            ),
        )

        // Graphics driver (basic entries - will be updated after contents sync)
        val graphicsDriverArr =
            context.resources.getStringArray(R.array.graphics_driver_entries).toList()
        state.graphicsDriverEntries.value = graphicsDriverArr
        selectByIdentifier(
            graphicsDriverArr,
            getShortcutSetting("graphicsDriver", container.getGraphicsDriver()),
            state.selectedGraphicsDriver
        )

        state.zinkModeEntries.value = context.resources.getStringArray(R.array.zink_mode_entries).toList()
        state.selectedZinkMode.intValue =
            if (getShortcutSetting("zinkMode", container.getZinkMode()) == "windows") 1 else 0

        // DX Wrapper
        val dxWrapperArr =
            context.resources.getStringArray(R.array.dxwrapper_entries).toList()
        state.dxWrapperEntries.value = dxWrapperArr
        selectByIdentifier(
            dxWrapperArr,
            getShortcutSetting("dxwrapper", container.getDXWrapper()),
            state.selectedDxWrapper
        )

        // Surface Effect
        val surfaceEffectArr = context.resources.getStringArray(R.array.surface_effect_entries).toList()
        state.surfaceEffectEntries.value = surfaceEffectArr
        state.selectedSurfaceEffect.intValue = if (getShortcutSetting("swapRB", container.getExtra("swapRB", "0")) == "1") 1 else 0

        // Audio driver
        val audioDriverArr =
            context.resources.getStringArray(R.array.audio_driver_entries).toList()
                .filter {
                    DirectAudioDriver.isSupportedFor(container.wineVersion) ||
                        !it.equals("DirectAudio", true)
                }
        state.audioDriverEntries.value = audioDriverArr
        selectByIdentifier(
            audioDriverArr,
            getShortcutSetting("audioDriver", container.getAudioDriver()),
            state.selectedAudioDriver
        )
        state.directAudioMic.value = DirectAudioDriver.isMicEnabled(
            getShortcutSetting(
                DirectAudioDriver.EXTRA_MIC,
                container.getExtra(DirectAudioDriver.EXTRA_MIC, "0")
            )
        )

        // MIDI sound fonts
        loadMidiSoundFonts()

        // Detect wine arch synchronously so filtered emulator dropdowns
        // render before the async content sync.
        val emulatorArr =
            context.resources.getStringArray(R.array.emulator_entries).toList()
        state.emulatorEntries.value = emulatorArr

        val wineVersionStr = if (shortcut.usesContainerDefaults())
            container.getWineVersion()
        else shortcut.getExtra("wineVersion", container.getWineVersion())
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersionStr)
        isArm64EC = wineInfo.isArm64EC
        state.isArm64EC.value = isArm64EC
        state.wineVersionDisplay.value = formatWineVersionDisplay(wineInfo)

        rebuildEmulatorLists()
        selectByIdentifier(
            state.emulator32Entries.value,
            getShortcutSetting("emulator", container.getEmulator()),
            state.selectedEmulator
        )
        selectByIdentifier(
            state.emulator64Entries.value,
            getShortcutSetting("emulator64", container.getEmulator64()),
            state.selectedEmulator64
        )

        // Locales
        val locales = context.resources.getStringArray(R.array.some_lc_all).toList()
        state.localeOptions.value = locales

        // Win component entries
        val winCompEntries =
            context.resources.getStringArray(R.array.wincomponent_entries).toList()
        state.winComponentEntries.value = winCompEntries

        // DInput mapper type entries
        val dInputArr =
            context.resources.getStringArray(R.array.dinput_mapper_type_entries).toList()
        state.dInputMapperTypeEntries.value = dInputArr

        val numControllersArr =
            context.resources.getStringArray(R.array.num_controllers_entries).toList()
        state.numControllersEntries.value = numControllersArr
        val numControllers = shortcut.getExtra("numControllers", "1").toIntOrNull() ?: 1
        state.selectedNumControllers.intValue =
            (numControllers - 1).coerceIn(0, (numControllersArr.size - 1).coerceAtLeast(0))

        // Startup selection
        val startupArr =
            context.resources.getStringArray(R.array.startup_selection_entries).toList()
        state.startupSelectionEntries.value = startupArr
        state.selectedStartupSelection.intValue = Integer.parseInt(
            getShortcutSetting(
                "startupSelection",
                container.getStartupSelection().toString()
            )
        ).coerceIn(0, startupArr.size - 1)

        // Controls profiles
        loadControlsProfiles()

        // Box64 presets
        loadBox64Presets()

        // FEXCore presets
        loadFexcorePresets()

        // Graphics driver configuration (inline card state)
        loadGraphicsDriverConfigState()

        // Desktop theme entries
        val desktopThemeArr =
            context.resources.getStringArray(R.array.desktop_theme_entries).toList()
        state.desktopThemeEntries.value = desktopThemeArr
        // Desktop theme is stored as compound "THEME,TYPE,COLOR" — extract theme name
        val savedDesktopTheme = getShortcutSetting("desktopTheme", container.getDesktopTheme())
        val themePart = savedDesktopTheme.split(",").firstOrNull()?.trim() ?: ""
        // Match case-insensitively: enum is "LIGHT"/"DARK", entries are "Light"/"Dark"
        val themeIdx = desktopThemeArr.indexOfFirst { it.equals(themePart, ignoreCase = true) }
        state.selectedDesktopTheme.intValue = if (themeIdx >= 0) themeIdx else 0

        // Show Box64/FEXCore frames based on saved emulator selection immediately,
        // before the async content sync runs
        updateEmulatorFrameVisibility()
    }

    private fun loadContentsAsync() {
        Executors.newSingleThreadExecutor().execute {
            try {
                contentsManager.syncContents()
                activity.runOnUiThread {
                    try {
                        populateContentsDependentData()
                    } finally {
                        state.isLoaded.value = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing contents", e)
                activity.runOnUiThread {
                    state.isLoaded.value = true
                }
            }
        }
    }

    private fun populateContentsDependentData() {
        val container = shortcut.container
        val wineVersionStr = if (shortcut.usesContainerDefaults())
            container.getWineVersion()
        else shortcut.getExtra("wineVersion", container.getWineVersion())

        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersionStr)
        val archChanged = isArm64EC != wineInfo.isArm64EC
        isArm64EC = wineInfo.isArm64EC
        state.isArm64EC.value = isArm64EC
        state.wineVersionDisplay.value = formatWineVersionDisplay(wineInfo)

        rebuildEmulatorLists()
        state.emulatorsEnabled.value = true

        // Arch flipped after async sync resolved the wine profile — re-apply
        // the shortcut's saved emulator against the rebuilt lists.
        if (archChanged) {
            selectByIdentifier(
                state.emulator32Entries.value,
                getShortcutSetting("emulator", container.getEmulator()),
                state.selectedEmulator
            )
            selectByIdentifier(
                state.emulator64Entries.value,
                getShortcutSetting("emulator64", container.getEmulator64()),
                state.selectedEmulator64
            )
        }

        loadBox64Versions()
        loadFexcoreVersions()
        updateEmulatorFrameVisibility()
        loadDxvkConfigState()
        loadWineD3DConfigState()
    }

    // Helper load methods

    private fun loadMidiSoundFonts() {
        // Use a temporary Spinner to leverage MidiManager.loadSFSpinner
        val tempSpinner = android.widget.Spinner(context)
        MidiManager.loadSFSpinner(tempSpinner)
        val adapter = tempSpinner.adapter
        val filesName = mutableListOf<String>()
        if (adapter != null) {
            for (i in 0 until adapter.count) {
                filesName.add(adapter.getItem(i).toString())
            }
        }

        state.midiSoundFontEntries.value = filesName
        val savedFont = getShortcutSetting("midiSoundFont", shortcut.container.getMIDISoundFont())
        if (savedFont.isEmpty()) {
            state.selectedMidiSoundFont.intValue = 0
        } else {
            val idx = filesName.indexOfFirst { it == savedFont }
            state.selectedMidiSoundFont.intValue = if (idx >= 0) idx else 0
        }
    }

    private fun loadContainerList() {
        try {
            val manager = ContainerManager(context)
            containerList.clear()
            containerList.addAll(manager.getContainers())
            val names = containerList.map { it.getName() }
            state.containerEntries.value = names

            // Select the current container
            val currentContainerId = shortcut.container.id
            val idx = containerList.indexOfFirst { it.id == currentContainerId }
            state.selectedContainer.intValue = if (idx >= 0) idx else 0
        } catch (e: Exception) {
            Log.e(TAG, "Error loading container list", e)
        }
    }

    private fun loadControlsProfiles() {
        val profiles = inputControlsManager.getProfiles(true)
        val values = mutableListOf(context.getString(R.string.common_ui_none))
        for (profile in profiles) values.add(profile.getName())
        state.controlsProfileEntries.value = values

        val selectedId =
            Integer.parseInt(shortcut.getExtra("controlsProfile", "0"))
        var selectedPos = 0
        for (i in profiles.indices) {
            if (profiles[i].id == selectedId) {
                selectedPos = i + 1
                break
            }
        }
        state.selectedControlsProfile.intValue = selectedPos
    }

    // Shortcut extras apply only to the shortcut's own container.
    private fun shouldUseShortcutOverrides(container: Container): Boolean =
        container === shortcut.container

    private fun loadBox64Presets(container: Container = shortcut.container) {
        val presets = Box64PresetManager.getPresets("box64", context)
        val names = mutableListOf<String>()
        val ids = mutableListOf<String>()
        for (preset in presets) {
            names.add(preset.name)
            ids.add(preset.id)
        }
        state.box64PresetEntries.value = names
        box64PresetIds = ids

        val savedPreset = if (shouldUseShortcutOverrides(container))
            getShortcutSetting("box64Preset", container.getBox64Preset())
        else
            container.getBox64Preset()
        val idx = ids.indexOfFirst { it == savedPreset }
        state.selectedBox64Preset.intValue = if (idx >= 0) idx else 0
    }

    private fun loadFexcorePresets(container: Container = shortcut.container) {
        val presets = FEXCorePresetManager.getPresets(context)
        val names = mutableListOf<String>()
        val ids = mutableListOf<String>()
        for (preset in presets) {
            names.add(preset.name)
            ids.add(preset.id)
        }
        state.fexcorePresetEntries.value = names
        fexcorePresetIds = ids

        val savedPreset = if (shouldUseShortcutOverrides(container))
            getShortcutSetting("fexcorePreset", container.getFEXCorePreset())
        else
            container.getFEXCorePreset()
        val idx = ids.indexOfFirst { it == savedPreset }
        state.selectedFexcorePreset.intValue = if (idx >= 0) idx else 0
    }

    private fun loadBox64Versions(container: Container = shortcut.container) {
        val itemList: MutableList<String> = if (isArm64EC) {
            context.resources.getStringArray(R.array.wowbox64_version_entries).toMutableList()
        } else {
            context.resources.getStringArray(R.array.box64_version_entries).toMutableList()
        }

        val profileType = if (isArm64EC)
            ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
        else ContentProfile.ContentType.CONTENT_TYPE_BOX64

        for (profile in contentsManager.getProfiles(profileType)) {
            val entryName = ContentsManager.getEntryName(profile)
            val firstDash = entryName.indexOf('-')
            if (firstDash >= 0) itemList.add(entryName.substring(firstDash + 1))
        }

        state.box64VersionEntries.value = itemList

        val currentVersion = if (shouldUseShortcutOverrides(container))
            getShortcutSetting("box64Version", container.getBox64Version())
        else
            container.getBox64Version()
        if (currentVersion != null) {
            selectByValue(itemList, currentVersion, state.selectedBox64Version)
        } else {
            selectByValue(itemList, "", state.selectedBox64Version)
        }

        // Show/hide Box64 frame
        updateEmulatorFrameVisibility()
    }

    private fun loadFexcoreVersions(container: Container = shortcut.container) {
        val items = mutableListOf<String>()
        val defaultEntries =
            context.resources.getStringArray(R.array.fexcore_version_entries)
        items.addAll(defaultEntries)

        for (profile in contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)) {
            val entryName = ContentsManager.getEntryName(profile)
            val firstDash = entryName.indexOf('-')
            if (firstDash >= 0) items.add(entryName.substring(firstDash + 1))
        }

        state.fexcoreVersionEntries.value = items
        val savedVersion = if (shouldUseShortcutOverrides(container))
            getShortcutSetting("fexcoreVersion", container.getFEXCoreVersion())
        else
            container.getFEXCoreVersion()
        selectByValue(items, savedVersion, state.selectedFexcoreVersion)
    }

    private fun updateEmulatorFrameVisibility() {
        val emulator32Entries = state.emulator32Entries.value
        val emulator64Entries = state.emulator64Entries.value
        val emulator32 = if (state.selectedEmulator.intValue in emulator32Entries.indices)
            StringUtils.parseIdentifier(emulator32Entries[state.selectedEmulator.intValue]) else ""
        val emulator64 = if (state.selectedEmulator64.intValue in emulator64Entries.indices)
            StringUtils.parseIdentifier(emulator64Entries[state.selectedEmulator64.intValue]) else ""

        // Wowbox64 reuses Box64 presets.
        val usesWowbox64 = emulator32.equals("wowbox64", true) || emulator64.equals("wowbox64", true)

        state.showBox64Frame.value =
            emulator32.equals("box64", true) || emulator64.equals("box64", true) || usesWowbox64
        state.showFexcoreFrame.value =
            emulator32.equals("fexcore", true) || emulator64.equals("fexcore", true)
    }

    private fun formatWineVersionDisplay(wineInfo: WineInfo): String {
        val base = wineInfo.toString()
        val archLabel = when (wineInfo.arch?.lowercase()) {
            "arm64ec" -> "ARM64EC"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> wineInfo.arch ?: ""
        }
        return if (archLabel.isNotEmpty()) "$base ($archLabel)" else base
    }

    // ARM64EC -> 64=FEXCore, 32=FEXCore|Wowbox64.
    // x86_64 -> 64=Box64, 32=Box64.
    private fun rebuildEmulatorLists() {
        val fullList = state.emulatorEntries.value
        val hasWowbox64 = hasInstalledWowbox64()
        fun entryById(id: String): String? = fullList.firstOrNull {
            StringUtils.parseIdentifier(it).equals(id, ignoreCase = true)
        }

        val prev32 = state.emulator32Entries.value
        val prev64 = state.emulator64Entries.value
        val prev32Id = prev32.getOrNull(state.selectedEmulator.intValue)
            ?.let { StringUtils.parseIdentifier(it) } ?: ""
        val prev64Id = prev64.getOrNull(state.selectedEmulator64.intValue)
            ?.let { StringUtils.parseIdentifier(it) } ?: ""

        if (isArm64EC) {
            state.emulator64Entries.value = listOfNotNull(entryById("fexcore"))
            state.emulator32Entries.value =
                listOfNotNull(
                    entryById("fexcore"),
                    if (hasWowbox64) entryById("wowbox64") else null
                )
        } else {
            state.emulator64Entries.value = listOfNotNull(entryById("box64"))
            state.emulator32Entries.value = listOfNotNull(entryById("box64"))
        }

        val new32 = state.emulator32Entries.value
        val new32Idx = new32.indexOfFirst {
            StringUtils.parseIdentifier(it).equals(prev32Id, ignoreCase = true)
        }
        state.selectedEmulator.intValue = if (new32Idx >= 0) new32Idx else 0

        val new64 = state.emulator64Entries.value
        val new64Idx = new64.indexOfFirst {
            StringUtils.parseIdentifier(it).equals(prev64Id, ignoreCase = true)
        }
        state.selectedEmulator64.intValue = if (new64Idx >= 0) new64Idx else 0
    }

    private fun hasInstalledWowbox64(): Boolean {
        return contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64)
            ?.any { it.isInstalled } == true
    }

    private fun loadWinComponents() {
        val container = shortcut.container
        val wincomponentsStr =
            getShortcutSetting("wincomponents", container.getWinComponents())
        val directX = mutableListOf<WinComponentItem>()
        val general = mutableListOf<WinComponentItem>()

        for (component in KeyValueSet(wincomponentsStr)) {
            val key = component[0]
            val value = component[1]
            val label = StringUtils.getString(context, key) ?: key
            val selectedIdx = try {
                Integer.parseInt(value)
            } catch (e: NumberFormatException) {
                0
            }
            val item = WinComponentItem(key, label, selectedIdx)
            if (key.startsWith("direct")) {
                directX.add(item)
            } else {
                general.add(item)
            }
        }

        state.directXComponents.value = directX
        state.generalComponents.value = general
    }

    private fun loadEnvVars() {
        val container = shortcut.container
        val envVarsStr = getShortcutSetting(
            "envVars",
            container?.getEnvVars() ?: Container.DEFAULT_ENV_VARS
        )
        val items = parseEnvVarItems(envVarsStr)
        state.envVars.value = items

        // Hide SDL2 keys from the user-visible list when the toggle is on.
        state.sdl2Compatibility.value = EnvVars(envVarsStr).get("SDL_XINPUT_ENABLED") == "1"
        if (state.sdl2Compatibility.value) {
            state.envVars.value = items.filterNot { item ->
                sdl2EnvVars.any { it.first == item.key }
            }
        }

        if (!state.shortcutExclusiveXInput.value) {
            state.enableXInput.value = true
            state.enableDInput.value = true
        }
    }

    private fun selectScreenSize(screenSize: String) {
        val entries = state.screenSizeEntries.value
        // Try to match by identifier
        val idx = entries.indexOfFirst {
            StringUtils.parseIdentifier(it) == StringUtils.parseIdentifier(screenSize)
        }
        if (idx >= 0) {
            state.selectedScreenSize.intValue = idx
        } else {
            // Custom screen size
            state.selectedScreenSize.intValue = 0 // "Custom" is at index 0
            val parts = screenSize.split("x")
            if (parts.size == 2) {
                state.customWidth.value = parts[0]
                state.customHeight.value = parts[1]
            }
        }
    }


    private fun saveSettings() {
        // Compare against the target container (post-switch) so unchanged
        // values aren't written as overrides.
        val selectedContainerIdxEarly = state.selectedContainer.intValue
        val container: Container = if (selectedContainerIdxEarly in containerList.indices)
            containerList[selectedContainerIdxEarly]
        else
            shortcut.container
        val name = state.name.value.trim()
        val nameChanged = shortcut.getExtra("custom_name", shortcut.name) != name && name.isNotEmpty()

        if (nameChanged) {
            shortcut.putExtra("custom_name", name)
            shouldRefreshLibraryOnSave = true
        }

        if (true) {
            var hasContainerOverride = false

            // Screen size
            val screenSize = getScreenSizeFromState()
            hasContainerOverride =
                hasContainerOverride or saveOverride("screenSize", screenSize, container.getScreenSize())

            // Graphics driver
            val graphicsDriver = getIdentifierFromEntries(
                state.graphicsDriverEntries.value, state.selectedGraphicsDriver.intValue
            )
            hasContainerOverride =
                hasContainerOverride or saveOverride("graphicsDriver", graphicsDriver, container.getGraphicsDriver())

            val zinkMode = if (state.selectedZinkMode.intValue == 1) "windows" else "unix"
            hasContainerOverride =
                hasContainerOverride or saveOverride("zinkMode", zinkMode, container.getZinkMode())

            val graphicsDriverConfig = buildGraphicsDriverConfigFromState()
            hasContainerOverride = hasContainerOverride or saveOverride(
                "graphicsDriverConfig", graphicsDriverConfig, container.getGraphicsDriverConfig()
            )

            // DX Wrapper
            val dxwrapper = getIdentifierFromEntries(
                state.dxWrapperEntries.value, state.selectedDxWrapper.intValue
            )
            hasContainerOverride =
                hasContainerOverride or saveOverride("dxwrapper", dxwrapper, container.getDXWrapper())

            val dxwrapperConfig = if (dxwrapper.contains("dxvk"))
                buildDxvkConfigFromState() else buildWineD3DConfigFromState()
            hasContainerOverride = hasContainerOverride or saveOverride(
                "dxwrapperConfig", dxwrapperConfig, container.getDXWrapperConfig()
            )

            // Surface Effect
            val swapRBStr = if (state.selectedSurfaceEffect.intValue == 1) "1" else "0"
            hasContainerOverride = hasContainerOverride or saveOverride("swapRB", swapRBStr, container.getExtra("swapRB", "0"))

            // Audio
            val audioDriver = getIdentifierFromEntries(
                state.audioDriverEntries.value, state.selectedAudioDriver.intValue
            )
            hasContainerOverride =
                hasContainerOverride or saveOverride("audioDriver", audioDriver, container.getAudioDriver())
            val directAudioMicStr = if (state.directAudioMic.value) "1" else "0"
            hasContainerOverride = hasContainerOverride or saveOverride(
                DirectAudioDriver.EXTRA_MIC,
                directAudioMicStr,
                container.getExtra(DirectAudioDriver.EXTRA_MIC, "0")
            )

            // Emulators
            val emulator = getIdentifierFromEntries(
                state.emulator32Entries.value, state.selectedEmulator.intValue
            )
            val emulator64 = getIdentifierFromEntries(
                state.emulator64Entries.value, state.selectedEmulator64.intValue
            )
            hasContainerOverride =
                hasContainerOverride or saveOverride("emulator", emulator, container.getEmulator())
            hasContainerOverride =
                hasContainerOverride or saveOverride("emulator64", emulator64, container.getEmulator64())

            // MIDI
            val midiSoundFontEntries = state.midiSoundFontEntries.value
            val midiIdx = state.selectedMidiSoundFont.intValue
            val midiSoundFont =
                if (midiIdx <= 0 || midiIdx >= midiSoundFontEntries.size) ""
                else midiSoundFontEntries[midiIdx]
            hasContainerOverride =
                hasContainerOverride or saveOverride("midiSoundFont", midiSoundFont, container.getMIDISoundFont())

            // LC_ALL
            hasContainerOverride =
                hasContainerOverride or saveOverride("lc_all", state.lcAll.value, container.getLC_ALL())

            // Fullscreen stretched
            hasContainerOverride = hasContainerOverride or saveOverride(
                "fullscreenStretched",
                if (state.fullscreenStretched.value) "1" else "0",
                if (container.isFullscreenStretched) "1" else "0"
            )

            // Use UnixLibs
            hasContainerOverride = hasContainerOverride or saveOverride(
                "useUnixLibs",
                if (state.useUnixLibs.value) "1" else "0",
                if (container.isUseUnixLibs) "1" else "0"
            )

            // Win components
            val wincomponents = buildWinComponentsString()
            hasContainerOverride =
                hasContainerOverride or saveOverride("wincomponents", wincomponents, container.getWinComponents())

            // Env vars
            val envVarsStr = buildEnvVarsString()
            hasContainerOverride =
                hasContainerOverride or saveOverride("envVars", envVarsStr, container.getEnvVars())

            // FEXCore
            val fexcoreVersionEntries = state.fexcoreVersionEntries.value
            val fexcoreVersionIdx = state.selectedFexcoreVersion.intValue
            val fexcoreVersion =
                if (fexcoreVersionIdx in fexcoreVersionEntries.indices) fexcoreVersionEntries[fexcoreVersionIdx] else ""
            hasContainerOverride = hasContainerOverride or saveOverride(
                "fexcoreVersion", fexcoreVersion, container.getFEXCoreVersion()
            )

            val fexcorePreset =
                if (state.selectedFexcorePreset.intValue in fexcorePresetIds.indices)
                    fexcorePresetIds[state.selectedFexcorePreset.intValue]
                else FEXCorePreset.COMPATIBILITY
            hasContainerOverride = hasContainerOverride or saveOverride(
                "fexcorePreset", fexcorePreset, container.getFEXCorePreset()
            )

            // Box64
            val box64VersionEntries = state.box64VersionEntries.value
            val box64VersionIdx = state.selectedBox64Version.intValue
            val box64Version =
                if (box64VersionIdx in box64VersionEntries.indices) box64VersionEntries[box64VersionIdx] else ""
            hasContainerOverride = hasContainerOverride or saveOverride(
                "box64Version", box64Version, container.getBox64Version()
            )

            val box64Preset =
                if (state.selectedBox64Preset.intValue in box64PresetIds.indices)
                    box64PresetIds[state.selectedBox64Preset.intValue]
                else Box64Preset.COMPATIBILITY
            hasContainerOverride = hasContainerOverride or saveOverride(
                "box64Preset", box64Preset, container.getBox64Preset()
            )

            // Startup selection
            val startupSelection = state.selectedStartupSelection.intValue
            hasContainerOverride = hasContainerOverride or saveOverride(
                "startupSelection",
                startupSelection.toString(),
                container.getStartupSelection().toInt().toString()
            )

            // Controls profile
            val profiles = inputControlsManager.getProfiles(true)
            val controlsProfile =
                if (state.selectedControlsProfile.intValue > 0)
                    profiles[state.selectedControlsProfile.intValue - 1].id
                else 0
            shortcut.putExtra(
                "controlsProfile",
                if (controlsProfile > 0) controlsProfile.toString() else null
            )

            val numControllerEntries = state.numControllersEntries.value
            val numControllerIndex = state.selectedNumControllers.intValue
            val numControllers =
                if (numControllerIndex in numControllerEntries.indices) {
                    numControllerEntries[numControllerIndex].toIntOrNull() ?: (numControllerIndex + 1)
                } else {
                    1
                }
            shortcut.putExtra("numControllers", numControllers.toString())

            // CPU list
            val cpuList = buildCpuListString(state.cpuChecked.value)
            hasContainerOverride =
                hasContainerOverride or saveOverride("cpuList", cpuList, container.getCPUList(true))

            // CPU list (WoW64)
            val cpuListWoW64 = buildCpuListString(state.cpuCheckedWoW64.value)
            hasContainerOverride =
                hasContainerOverride or saveOverride("cpuListWoW64", cpuListWoW64, container.getCPUListWoW64(true))

            // Input type
            var finalInputType = 0
            if (state.enableXInput.value) finalInputType =
                finalInputType or WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
            if (state.enableDInput.value) finalInputType =
                finalInputType or WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
            finalInputType = finalInputType or (
                if (state.selectedDInputMapperType.intValue == 0)
                    WinHandler.FLAG_DINPUT_MAPPER_STANDARD.toInt()
                else WinHandler.FLAG_DINPUT_MAPPER_XINPUT.toInt()
            )
            hasContainerOverride = hasContainerOverride or saveOverride(
                "inputType",
                finalInputType.toString(),
                container.getInputType().toString()
            )

            // Exclusive Input — flip hasContainerOverride so runtime's
            // getShortcutSetting doesn't mask the extra via container-defaults.
            val disableXinputValue = if (state.disableXInput.value) "1" else null
            shortcut.putExtra("disableXinput", disableXinputValue)
            if (disableXinputValue != null) hasContainerOverride = true

            shortcut.putExtra("exclusiveXInput", if (state.shortcutExclusiveXInput.value) "1" else "0")
            if (state.shortcutExclusiveXInput.value != container.isExclusiveXInput()) hasContainerOverride = true

            // Touchscreen mode
            val mode = state.screenTouchMode.intValue
            shortcut.putExtra("simTouchScreen", if (mode == 1) "1" else "0")
            shortcut.putExtra("screenTouchMode", mode.toString())
            if (state.gestureProfileIds.value.isNotEmpty()) {
                val gpid = state.gestureProfileIds.value.getOrNull(state.selectedGestureProfile.intValue) ?: 0
                shortcut.putExtra("gestureProfileId", if (gpid > 0) gpid.toString() else null)
            }

            // Launch EXE path
            val launchExePath = normalizeLaunchExeForShortcut(state.launchExePath.value)
            if (launchExePath.isNotEmpty()) {
                shortcut.putExtra("launch_exe_path", launchExePath)
                val gameSource = shortcut.getExtra("game_source", "")
                if (gameSource == "CUSTOM") {
                    shortcut.putExtra("custom_exe", launchExePath)
                    resolveLaunchExeFile(launchExePath)?.takeIf { it.isFile }?.let { exeFile ->
                        val gameFolder = LibraryShortcutUtils.detectCustomGameFolder(exeFile)
                        shortcut.putExtra("custom_game_folder", gameFolder.absolutePath)
                        updateCustomShortcutExecLine(container, gameFolder, exeFile)
                    }
                } else if (gameSource == "EPIC" || gameSource == "GOG") {
                    updateStoreShortcutExecLine(container, gameSource, launchExePath)
                }
            }

            // Exec args
            val execArgs = state.execArgs.value
            hasContainerOverride = hasContainerOverride or saveOverride(
                "execArgs", execArgs, container.getExecArgs()
            )

            // Refresh rate
            val refreshRateEntries = state.refreshRateEntries.value
            val refreshIdx = state.selectedRefreshRate.intValue
            if (refreshIdx in refreshRateEntries.indices) {
                val selectedRate =
                    RefreshRateUtils.parseRefreshRateLabel(refreshRateEntries[refreshIdx])
                if (selectedRate <= 0) {
                    shortcut.putExtra("refreshRate", null)
                } else {
                    shortcut.putExtra("refreshRate", selectedRate.toString())
                }
            }

            // FPS Limit
            val fpsLimit = state.fpsLimit.intValue
            shortcut.putExtra("fpsLimit", if (fpsLimit > 0) fpsLimit.toString() else null)

            // SGSR 1 is a shortcut-only setting, not a container override.
            if (state.sgsrEnabled.value) {
                shortcut.putExtra("sgsrEnabled", "1")
                shortcut.putExtra("sgsrUpscaleMode", state.sgsrUpscaleMode.intValue.coerceIn(1, 6).toString())
                shortcut.putExtra("sgsrSharpness", state.sgsrSharpness.intValue.coerceIn(0, 100).toString())
            } else {
                shortcut.putExtra("sgsrEnabled", null)
                shortcut.putExtra("sgsrUpscaleMode", null)
                shortcut.putExtra("sgsrSharpness", null)
            }

            hasContainerOverride = hasContainerOverride or saveOverride(
                NetworkingSettings.EXTRA_DRIVER,
                if (state.selectedNetDriver.intValue == 1) NetworkingSettings.DRIVER_NONE else NetworkingSettings.DRIVER_WINNATIVE,
                NetworkingSettings.driverOrDefault(container.getExtra(NetworkingSettings.EXTRA_DRIVER, NetworkingSettings.DEFAULT_DRIVER)),
            )
            hasContainerOverride = hasContainerOverride or saveOverride(
                NetworkingSettings.EXTRA_MAC,
                NetworkingSettings.normalizeMac(state.netMac.value),
                NetworkingSettings.normalizeMac(container.getExtra(NetworkingSettings.EXTRA_MAC, "")),
            )
            hasContainerOverride = hasContainerOverride or saveOverride(
                "frameGen",
                if (state.frameGenEnabled.value) "1" else "0",
                container.getExtra("frameGen", "0"),
            )
            hasContainerOverride = hasContainerOverride or saveOverride(
                "frameGenMultiplier",
                state.frameGenMultiplier.intValue.coerceIn(2, 4).toString(),
                container.getExtra("frameGenMultiplier", "2"),
            )
            hasContainerOverride = hasContainerOverride or saveOverride(
                "frameGenTargetRate",
                state.frameGenTargetRate.intValue.coerceAtLeast(0).toString(),
                container.getExtra("frameGenTargetRate", "0"),
            )
            hasContainerOverride = hasContainerOverride or saveOverride(
                "frameGenFlowScale",
                state.frameGenFlowScale.intValue.coerceIn(25, 100).toString(),
                container.getExtra("frameGenFlowScale", "70"),
            )

            // saveOverride not putExtra: putExtra leaves hasContainerOverride false, so a reshade-only shortcut gets use_container_defaults=1 and reads back the container's extras
            run {
                val loadoutJson = state.reshadeLoadout.loadoutJsonOrNull() ?: ""
                hasContainerOverride = hasContainerOverride or saveOverride(
                    com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_LOADOUT,
                    loadoutJson,
                    container.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_LOADOUT, "")
                )
                hasContainerOverride = hasContainerOverride or saveOverride(
                    com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_MODE,
                    if (loadoutJson.isEmpty()) "" else state.reshadeLoadout.mode,
                    // "solo" is how launch resolves an unset mode; matching it avoids a spurious reshadeMode override
                    if (loadoutJson.isEmpty()) "" else container.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_MODE, "solo")
                )
                hasContainerOverride = hasContainerOverride or saveOverride(
                    com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_PARAMS,
                    if (loadoutJson.isEmpty()) "" else (state.reshadeLoadout.paramsJsonOrNull() ?: ""),
                    container.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_PARAMS, "")
                )
                hasContainerOverride = hasContainerOverride or saveOverride(
                    com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_EFFECT,
                    if (loadoutJson.isEmpty()) "" else state.reshadeLoadout.firstEffectName(),
                    container.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_EFFECT, "")
                )
            }

            // Desktop Theme — stored as compound "THEME,TYPE,COLOR" string
            if (state.desktopThemeEntries.value.isNotEmpty()) {
                val desktopThemeEntries = state.desktopThemeEntries.value
                val dtIdx = state.selectedDesktopTheme.intValue
                val selectedLabel = if (dtIdx in desktopThemeEntries.indices) desktopThemeEntries[dtIdx] else ""
                val themeName = selectedLabel.uppercase()
                // Preserve existing compound value, only replace the theme portion
                val existing = getShortcutSetting("desktopTheme", container.getDesktopTheme())
                val parts = existing.split(",").toMutableList()
                if (parts.isNotEmpty()) parts[0] = themeName
                val desktopTheme = parts.joinToString(",")
                hasContainerOverride = hasContainerOverride or saveOverride(
                    "desktopTheme", desktopTheme, container.getDesktopTheme()
                )
            }

            // Steam options
            if (state.isSteamGame.value) {
                com.winlator.cmod.feature.stores.steam.utils.PrefManager.wnPlanW =
                    state.steamLauncher.value
                shortcut.putExtra("launchRealSteam", null)
                shortcut.putExtra("steamType", null)
                // "Use Legacy Launcher" drives both the ColdClient launcher and
                // SteamStub DRM unpacking; persist both keys from the one toggle.
                hasContainerOverride = hasContainerOverride or saveOverride(
                    "useColdClient",
                    if (state.useLegacyLauncher.value) "1" else "0",
                    if (container.isUseColdClient) "1" else "0"
                )
                hasContainerOverride = hasContainerOverride or saveOverride(
                    "useSteamInput",
                    if (state.useSteamInput.value) "1" else "0",
                    container.getExtra("useSteamInput", "0")
                )
                hasContainerOverride = hasContainerOverride or saveOverride(
                    "steamOfflineMode",
                    if (state.steamOfflineMode.value) "1" else "0",
                    if (container.isSteamOfflineMode) "1" else "0"
                )
                hasContainerOverride = hasContainerOverride or saveOverride(
                    "unpackFiles",
                    if (state.useLegacyLauncher.value) "1" else "0",
                    if (container.isUnpackFiles) "1" else "0"
                )
                hasContainerOverride = hasContainerOverride or saveOverride(
                    "runtimePatcher",
                    if (state.runtimePatcher.value) "1" else "0",
                    if (container.isRuntimePatcher) "1" else "0"
                )
            }

            // Container defaults flag
            shortcut.putExtra(
                EXTRA_USE_CONTAINER_DEFAULTS,
                if (hasContainerOverride) "0" else "1"
            )

            Log.d(
                TAG,
                "Saving shortcut name='${shortcut.name}' path='${shortcut.path}'" +
                    " usesContainerDefaults=${if (hasContainerOverride) "0" else "1"}" +
                    " swapRB='${shortcut.getExtra("swapRB")}'" +
                    " box64Preset='${shortcut.getExtra("box64Preset")}'" +
                    " fexcorePreset='${shortcut.getExtra("fexcorePreset")}'" +
                    " wineVersion='${shortcut.getExtra("wineVersion")}'" +
                    " graphicsDriver='${shortcut.getExtra("graphicsDriver")}'" +
                    " graphicsDriverConfig='${shortcut.getExtra("graphicsDriverConfig")}'" +
                    " dxwrapper='${shortcut.getExtra("dxwrapper")}'" +
                    " dxwrapperConfig='${shortcut.getExtra("dxwrapperConfig")}'" +
                    " audioDriver='${shortcut.getExtra("audioDriver")}'" +
                    " emulator='${shortcut.getExtra("emulator")}'" +
                    " screenSize='${shortcut.getExtra("screenSize")}'" +
                    " startupSelection='${shortcut.getExtra("startupSelection")}'" +
                    " envVars='${shortcut.getExtra("envVars")}'" +
                    " cpuList='${shortcut.getExtra("cpuList")}'" +
                    " cpuListWoW64='${shortcut.getExtra("cpuListWoW64")}'"
            )

            // Container change
            val originalContainer = shortcut.container
            if (container.id != originalContainer.id) {
                shortcut.putExtra("container_id", container.id.toString())
                shortcut.putExtra("cloud_force_download", "1")
                shortcut.saveData()

                val newDesktopDir = container.getDesktopDir()
                if (!newDesktopDir.exists()) newDesktopDir.mkdirs()
                val newShortcutFile = File(newDesktopDir, shortcut.file.name)
                com.winlator.cmod.shared.io.FileUtils.copy(shortcut.file, newShortcutFile)
                shortcut.file.delete()
                
                // Also move the original .lnk file if it exists to prevent ghost shortcuts
                val lnkFileName = shortcut.file.name.substringBeforeLast(".desktop") + ".lnk"
                val oldLnkFile = File(shortcut.file.parentFile, lnkFileName)
                if (oldLnkFile.exists()) {
                    val newLnkFile = File(newDesktopDir, lnkFileName)
                    com.winlator.cmod.shared.io.FileUtils.copy(oldLnkFile, newLnkFile)
                    oldLnkFile.delete()
                }
            } else {
                shortcut.saveData()
            }
            com.winlator.cmod.app.shell.UnifiedActivity.refreshLibrary()
        }
    }

    // Helper methods

    private fun addShortcutToScreen(shortcut: Shortcut): ShortcutsFragment.PinShortcutResult {
        if (shortcut.getExtra("uuid").isEmpty()) shortcut.genUUID()
        val shortcutUuid = shortcut.getExtra("uuid")
        val shortcutPath = shortcut.file.absolutePath
        val shortcutIds = ShortcutsFragment.buildPinnedShortcutIds(shortcut.container.id, shortcutUuid, shortcutPath)
        if (shortcutIds.isEmpty()) return ShortcutsFragment.PinShortcutResult.FAILED

        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            ?: return ShortcutsFragment.PinShortcutResult.FAILED
        if (!shortcutManager.isRequestPinShortcutSupported) return ShortcutsFragment.PinShortcutResult.FAILED

        val shortcutIcon = buildPinnedShortcutIcon()

        val info = ShortcutInfo.Builder(context, shortcutIds.last())
            .setShortLabel(shortcut.name)
            .setLongLabel(shortcut.name)
            .setIcon(shortcutIcon)
            .setIntent(
                ShortcutsFragment.buildShortcutLaunchIntent(
                    context,
                    shortcut.container.id,
                    shortcutPath,
                    shortcut.name,
                    shortcutUuid
                )
            )
            .build()

        return ShortcutsFragment.pinOrUpdateShortcut(
            shortcutManager,
            info,
            shortcutIds,
            null
        )
    }

    private fun resolveInitialLaunchExePath(): String {
        val gameSource = shortcut.getExtra("game_source", "")
        if (gameSource == "CUSTOM") {
            val customExe = shortcut.getExtra("custom_exe")
            if (customExe.isNotEmpty()) return customExe
        }

        val storedPath = shortcut.getExtra("launch_exe_path")
        if (storedPath.isNotEmpty()) return storedPath

        return ""
    }

    private fun resolveExePickerInitialPath(): String? {
        val currentPath =
            state.launchExePath.value.ifBlank {
                if (shortcut.getExtra("game_source", "") == "CUSTOM") {
                    shortcut.getExtra("custom_exe").ifBlank { shortcut.getExtra("launch_exe_path") }
                } else {
                    shortcut.getExtra("launch_exe_path")
                }
            }
        if (currentPath.isBlank()) {
            return shortcut.getExtra("game_install_path")
                .takeIf { it.isNotBlank() && File(it).isDirectory }
        }

        val currentFile = resolveLaunchExeFile(currentPath)
        return when {
            currentFile?.isFile == true -> currentFile.absolutePath
            currentFile?.isDirectory == true -> currentFile.absolutePath
            else -> shortcut.getExtra("game_install_path")
                .takeIf { it.isNotBlank() && File(it).isDirectory }
        }
    }

    private fun applySelectedExePath(path: String) {
        val exeFile = File(path)
        if (!exeFile.isFile || exeFile.extension.lowercase() !in DirectoryPickerDialog.ExecutableExtensions) {
            WinToast.show(context, context.getString(R.string.common_ui_select_valid_exe_file), Toast.LENGTH_SHORT, dialog.window?.decorView)
            return
        }

        state.launchExePath.value =
            if (shortcut.getExtra("game_source", "") == "STEAM") {
                relativePathWithinGameInstall(exeFile) ?: exeFile.absolutePath
            } else {
                exeFile.absolutePath
            }
        state.launchExeDisplayPath.value = exeFile.absolutePath
    }

    private fun normalizeLaunchExeForShortcut(path: String): String {
        if (path.isBlank()) return ""
        val exeFile = resolveLaunchExeFile(path)
        val gameSource = shortcut.getExtra("game_source", "")

        return when {
            gameSource == "STEAM" && exeFile?.isFile == true ->
                relativePathWithinGameInstall(exeFile) ?: exeFile.absolutePath
            exeFile?.isFile == true ->
                exeFile.absolutePath
            else ->
                path
        }
    }

    private fun resolveLaunchExeFile(path: String): File? {
        if (path.isBlank()) return null

        val directFile = File(path)
        if (directFile.isAbsolute) return directFile

        val installPath = shortcut.getExtra("game_install_path")
        if (installPath.isNotBlank()) {
            return File(installPath, path.replace("\\", File.separator))
        }

        return directFile
    }

    private fun resolveLaunchExeDisplayPath(path: String): String {
        if (path.isBlank()) return ""

        val directFile = File(path)
        if (directFile.isAbsolute) return directFile.absolutePath

        val installPath = shortcut.getExtra("game_install_path")
        if (installPath.isNotBlank()) {
            return File(installPath, path.replace("\\", File.separator)).absolutePath
        }

        return path
    }

    private fun relativePathWithinGameInstall(file: File): String? {
        val installPath = shortcut.getExtra("game_install_path")
        if (installPath.isBlank()) return null

        val installDir = File(installPath).takeIf { it.isDirectory } ?: return null
        val canonicalInstall = canonicalPath(installDir)
        val canonicalFile = canonicalPath(file)
        val prefix = canonicalInstall.trimEnd(File.separatorChar) + File.separator
        if (!canonicalFile.startsWith(prefix)) return null

        return canonicalFile
            .substring(prefix.length)
            .replace(File.separatorChar, '/')
    }

    private fun updateStoreShortcutExecLine(
        targetContainer: Container,
        gameSource: String,
        launchExePath: String,
    ) {
        val exeFile = File(launchExePath).takeIf { it.isFile } ?: return
        val gameInstallPath = shortcut.getExtra("game_install_path")
        val mappedPath =
            gameInstallPath
                .takeIf { it.isNotBlank() && File(it).isDirectory }
                ?.let { WineUtils.getDriveCGameWindowsPath(targetContainer, gameSource, it, exeFile.absolutePath) }
                ?.takeIf { it.isNotBlank() }
                ?: WineUtils.hostPathToRootWinePath(targetContainer, exeFile.absolutePath)
                    .takeIf { it.isNotBlank() }
                ?: return

        val content = StringBuilder()
        var replaced = false
        for (line in FileUtils.readLines(shortcut.file)) {
            if (line.startsWith("Exec=")) {
                content.append("Exec=wine \"").append(mappedPath).append("\"\n")
                replaced = true
            } else {
                content.append(line).append('\n')
            }
        }
        if (!replaced) {
            content.append("Exec=wine \"").append(mappedPath).append("\"\n")
        }
        FileUtils.writeString(shortcut.file, content.toString())
    }

    private fun updateCustomShortcutExecLine(
        targetContainer: Container,
        gameFolder: File,
        exeFile: File,
    ) {
        val mappedPath =
            WineUtils.resolveGameExeWindowsPath(
                targetContainer,
                "CUSTOM",
                gameFolder.absolutePath,
                exeFile.absolutePath,
            )?.takeIf { it.isNotBlank() }
                ?: WineUtils.hostPathToRootWinePath(targetContainer, exeFile.absolutePath)
                    .takeIf { it.isNotBlank() }
                ?: return

        updateShortcutExecLine(mappedPath)
    }

    private fun updateShortcutExecLine(windowsPath: String) {
        val content = StringBuilder()
        var replaced = false
        for (line in FileUtils.readLines(shortcut.file)) {
            if (line.startsWith("Exec=")) {
                content.append("Exec=wine \"").append(windowsPath).append("\"\n")
                replaced = true
            } else {
                content.append(line).append('\n')
            }
        }
        if (!replaced) {
            content.append("Exec=wine \"").append(windowsPath).append("\"\n")
        }
        FileUtils.writeString(shortcut.file, content.toString())
    }

    private fun canonicalPath(file: File): String =
        try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }

    private fun syncLibraryArtworkState() {
        syncLibraryArtworkSlotState(
            target = LibraryArtworkTarget.GAME_CARD,
        )
        syncLibraryArtworkSlotState(
            target = LibraryArtworkTarget.ICON_ART,
        )
    }

    private fun syncLibraryArtworkSlotState(
        target: LibraryArtworkTarget,
    ) {
        val hasArtwork =
            when (target) {
                LibraryArtworkTarget.ICON_ART -> LibraryShortcutArtwork.findIconArtworkPath(shortcut) != null
                LibraryArtworkTarget.GAME_CARD ->
                    getLibraryArtworkExtraKey(target)
                        ?.let { shortcut.getExtra(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::File)
                        ?.isFile() == true
            }

        when (target) {
            LibraryArtworkTarget.GAME_CARD -> {
                state.gameCardArtworkSelected.value = hasArtwork
                state.gameCardArtworkSummary.value = ""
            }
            LibraryArtworkTarget.ICON_ART -> {
                state.iconArtworkSelected.value = hasArtwork
                state.iconArtworkSummary.value = ""
            }
        }
    }

    private fun pickLibraryArtwork(target: LibraryArtworkTarget) {
        DirectoryPickerDialog.showFile(
            activity = activity,
            title = context.getString(R.string.shortcuts_library_artwork_set),
            allowedExtensions = ARTWORK_EXTENSIONS,
            dimAmount = 0.5f,
            preserveBackdropBlur = true,
        ) { path ->
            saveSelectedLibraryArtwork(Uri.fromFile(File(path)), target)
        }
    }

    // Each view gets its own shape, so slots are written individually and only
    // replaced when the new image lands.
    private fun saveScrapedLibraryArtwork(
        uri: Uri,
        slot: LibraryShortcutArtwork.LibraryArtworkSlot,
    ): Boolean {
        val bitmap = ImageUtils.getBitmapFromUri(context, uri, 1024) ?: return false
        val previousPath = shortcut.getExtra(slot.extraKey)
        val outputFile = LibraryShortcutArtwork.buildManagedViewArtworkFile(context, shortcut, slot)
        if (!FileUtils.saveBitmapToFile(bitmap, outputFile)) return false
        if (previousPath.isNotBlank() && previousPath != outputFile.absolutePath) {
            LibraryShortcutArtwork.deleteManagedArtwork(context, previousPath)
        }
        shortcut.putExtra(slot.extraKey, outputFile.absolutePath)
        return true
    }

    private fun saveSelectedLibraryArtwork(
        uri: Uri,
        target: LibraryArtworkTarget,
    ): Boolean {
        val bitmap = ImageUtils.getBitmapFromUri(context, uri, 1024)
        if (bitmap == null) {
            WinToast.show(context, context.getString(R.string.shortcuts_library_artwork_failed), Toast.LENGTH_SHORT, dialog.window?.decorView)
            return false
        }

        val extraKey = getLibraryArtworkExtraKey(target) ?: return false
        val previousPath = shortcut.getExtra(extraKey)
        val slot = getLibraryArtworkSlot(target) ?: return false
        val outputFile = LibraryShortcutArtwork.buildManagedViewArtworkFile(context, shortcut, slot)
        if (!FileUtils.saveBitmapToFile(bitmap, outputFile)) {
            WinToast.show(context, context.getString(R.string.shortcuts_library_artwork_failed), Toast.LENGTH_SHORT, dialog.window?.decorView)
            return false
        }

        if (previousPath.isNotBlank() && previousPath != outputFile.absolutePath) {
            LibraryShortcutArtwork.deleteManagedArtwork(context, previousPath)
        }

        shortcut.putExtra(extraKey, outputFile.absolutePath)
        clearLibraryArtworkSlots(getLibraryArtworkSlots(target).filter { it.extraKey != extraKey })
        shortcut.saveData()
        shouldRefreshLibraryOnSave = true
        syncLibraryArtworkState()
        // Artwork lands on disk at pick time, so refresh now instead of at confirm.
        emitLibraryRefreshIfNeeded()
        return true
    }

    private fun clearLibraryArtwork(target: LibraryArtworkTarget) {
        clearLibraryArtworkSlots(getLibraryArtworkSlots(target))
        shortcut.saveData()
        shouldRefreshLibraryOnSave = true
        syncLibraryArtworkState()
        // Artwork lands on disk at pick time, so refresh now instead of at confirm.
        emitLibraryRefreshIfNeeded()
    }

    private fun clearLibraryArtworkSlots(slots: List<LibraryShortcutArtwork.LibraryArtworkSlot>) {
        slots.forEach { slot ->
            LibraryShortcutArtwork.deleteManagedArtwork(context, shortcut.getExtra(slot.extraKey))
            shortcut.putExtra(slot.extraKey, null)
        }
    }

    private fun getLibraryArtworkExtraKey(target: LibraryArtworkTarget): String? = getLibraryArtworkSlot(target)?.extraKey

    private fun getLibraryArtworkSlot(target: LibraryArtworkTarget): LibraryShortcutArtwork.LibraryArtworkSlot? =
        getLibraryArtworkSlots(target).firstOrNull()

    // Icon art writes GRID; the trailing slots only exist to clear images saved before the merge.
    private fun getLibraryArtworkSlots(target: LibraryArtworkTarget): List<LibraryShortcutArtwork.LibraryArtworkSlot> =
        when (target) {
            LibraryArtworkTarget.GAME_CARD -> listOf(LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD)
            LibraryArtworkTarget.ICON_ART ->
                listOf(
                    LibraryShortcutArtwork.LibraryArtworkSlot.GRID,
                    LibraryShortcutArtwork.LibraryArtworkSlot.CAROUSEL,
                    LibraryShortcutArtwork.LibraryArtworkSlot.LIST,
                )
        }

    private fun emitLibraryRefreshIfNeeded() {
        if (!shouldRefreshLibraryOnSave) {
            return
        }
        shouldRefreshLibraryOnSave = false
        PluviaApp.events.emit(AndroidEvent.LibraryArtworkChanged)
    }

    private fun refreshPinnedHomeShortcutIfNeeded() {
        if (!LibraryShortcutUtils.hasPinnedHomeShortcut(context, shortcut)) {
            return
        }
        addShortcutToScreen(shortcut)
    }

    private fun buildPinnedShortcutIcon(): Icon {
        val preferredIconBitmap =
            LibraryShortcutArtwork
                .findPreferredHomeIconFile(context, shortcut)
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?: shortcut.coverArt
                ?: shortcut.icon

        return preferredIconBitmap?.let { Icon.createWithBitmap(it) }
            ?: Icon.createWithResource(context, R.drawable.icon_shortcut)
    }

    private fun getShortcutSetting(key: String, containerValue: String): String {
        return shortcut.getSettingExtra(key, containerValue)
    }

    private fun getIdentifierFromEntries(entries: List<String>, index: Int): String {
        return if (index in entries.indices) StringUtils.parseIdentifier(entries[index]) else ""
    }

    private fun selectByIdentifier(
        entries: List<String>,
        identifier: String,
        target: androidx.compose.runtime.MutableIntState
    ) {
        val idx =
            entries.indexOfFirst { StringUtils.parseIdentifier(it) == identifier }
        target.intValue = if (idx >= 0) idx else 0
    }

    private fun selectByValue(
        entries: List<String>,
        value: String,
        target: androidx.compose.runtime.MutableIntState
    ) {
        val idx = entries.indexOfFirst { it == value }
        target.intValue = if (idx >= 0) idx else 0
    }

    private fun saveOverride(
        extraName: String,
        newValue: String,
        containerValue: String
    ): Boolean {
        val normNew = newValue ?: ""
        val normContainer = containerValue ?: ""
        return if (normNew != normContainer) {
            shortcut.putExtra(extraName, normNew)
            true
        } else {
            shortcut.putExtra(extraName, null)
            false
        }
    }

    private fun getScreenSizeFromState(): String {
        val entries = state.screenSizeEntries.value
        val selectedIdx = state.selectedScreenSize.intValue
        if (selectedIdx !in entries.indices) return Container.DEFAULT_SCREEN_SIZE

        val selectedValue = entries[selectedIdx]
        return if (selectedValue.equals("custom", ignoreCase = true)) {
            val w = state.customWidth.value.trim()
            val h = state.customHeight.value.trim()
            if (w.matches(Regex("[0-9]+")) && h.matches(Regex("[0-9]+"))) {
                // Ensure even numbers
                val width = (w.toInt() / 2) * 2
                val height = (h.toInt() / 2) * 2
                "${width}x${height}"
            } else {
                Container.DEFAULT_SCREEN_SIZE
            }
        } else {
            StringUtils.parseIdentifier(selectedValue)
        }
    }

    private fun buildWinComponentsString(): String {
        val parts = mutableListOf<String>()
        for (comp in state.directXComponents.value) {
            parts.add("${comp.key}=${comp.selectedIndex}")
        }
        for (comp in state.generalComponents.value) {
            parts.add("${comp.key}=${comp.selectedIndex}")
        }
        return parts.joinToString(",")
    }

    private fun buildEnvVarsString(): String {
        // Keep the SDL2 keys in sync with the toggle.
        val sdl2Keys = sdl2EnvVars.map { it.first }.toSet()
        val filtered = state.envVars.value
            .filter { it.key.isNotBlank() }
            .filterNot { it.key in sdl2Keys }
        val merged = if (state.sdl2Compatibility.value) {
            filtered + sdl2EnvVars.map { EnvVarItem(it.first, it.second) }
        } else {
            filtered
        }
        return merged.joinToString(" ") { "${it.key}=${it.value}" }
    }

    // Emit the enumerated list even when all cores are checked. Returning "" for
    // all-checked collides with the "fallback / no override" sentinel used by
    // Container.setCPUList*, Shortcut.getSettingExtra, and the WoW64 fallback
    // (which is only upper-half cores) — so a user's "all cores" selection
    // would silently decay on reload.
    private fun buildCpuListString(checked: List<Boolean>): String {
        return checked.mapIndexedNotNull { i, isChecked ->
            if (isChecked) "$i" else null
        }.joinToString(",")
    }

    private fun buildGraphicsDriverConfigFromState(): String {
        val vulkanVersion = state.gfxVulkanVersionEntries.value.getOrElse(state.gfxSelectedVulkanVersion.intValue) { "1.4" }
        val version = state.gfxDriverVersionEntries.value.getOrElse(state.gfxSelectedDriverVersion.intValue) { "" }
        val blacklisted = state.gfxBlacklistedExtensions.value.joinToString(",")
        val gpuName = state.gfxGpuNameEntries.value.getOrElse(state.gfxSelectedGpuName.intValue) { "Device" }
        val maxDeviceMemory = StringUtils.parseNumber(
            state.gfxMaxDeviceMemoryEntries.value.getOrElse(state.gfxSelectedMaxDeviceMemory.intValue) { "0" }
        )
        val presentMode = state.gfxPresentModeEntries.value.getOrElse(state.gfxSelectedPresentMode.intValue) { "mailbox" }
        val compositorPresentMode = state.gfxCompositorPresentModeEntries.value.getOrElse(state.gfxSelectedCompositorPresentMode.intValue) { "fifo" }
        val syncFrame = if (state.gfxSyncFrame.value) "1" else "0"
        val disablePresentWait = if (state.gfxDisablePresentWait.value) "1" else "0"
        val resourceType = state.gfxResourceTypeEntries.value.getOrElse(state.gfxSelectedResourceType.intValue) { "auto" }
        val bcnEmulation = state.gfxBcnEmulationEntries.value.getOrElse(state.gfxSelectedBcnEmulation.intValue) { "auto" }
        val bcnEmulationType = state.gfxBcnEmulationTypeEntries.value.getOrElse(state.gfxSelectedBcnEmulationType.intValue) { "compute" }
        val bcnEmulationCache = state.gfxBcnEmulationCacheEntries.value.getOrElse(state.gfxSelectedBcnEmulationCache.intValue) { "0" }
        val transcoder = state.gfxTranscoderEntries.value.getOrElse(state.gfxSelectedTranscoder.intValue) { "cpu" }
        val quality = state.gfxQualityEntries.value.getOrElse(state.gfxSelectedQuality.intValue) { "low" }

        return "vulkanVersion=$vulkanVersion;version=$version;blacklistedExtensions=$blacklisted;" +
                "maxDeviceMemory=$maxDeviceMemory;presentMode=$presentMode;syncFrame=$syncFrame;" +
                "disablePresentWait=$disablePresentWait;resourceType=$resourceType;" +
                "bcnEmulation=$bcnEmulation;bcnEmulationType=$bcnEmulationType;" +
                "bcnEmulationCache=$bcnEmulationCache;gpuName=$gpuName;" +
                "compositorPresentMode=$compositorPresentMode;" +
                "transcoder=$transcoder;quality=$quality"
    }

    private fun buildDxvkConfigFromState(): String {
        val entries = state.dxvkVersionEntries.value
        val idx = state.dxvkSelectedVersion.intValue
        val version = if (idx in entries.indices) entries[idx] else ""
        val isGplAsync = version.contains("gplasync")
        val isAsync = version.contains("async")
        val async = if (state.dxvkAsync.value && (isAsync || isGplAsync)) "1" else "0"
        val asyncCache = if (state.dxvkAsyncCache.value && (isAsync || isGplAsync)) "1" else "0"

        val vkd3dEntries = state.dxvkVkd3dVersionEntries.value
        val vkd3dIdx = state.dxvkSelectedVkd3dVersion.intValue
        val vkd3dVersion = if (vkd3dIdx in vkd3dEntries.indices) vkd3dEntries[vkd3dIdx] else "None"

        val vkd3dLevel = state.dxvkVkd3dFeatureLevelEntries.value.getOrElse(state.dxvkSelectedVkd3dFeatureLevel.intValue) { "12_0" }
        val ddrawWrapper = StringUtils.parseIdentifier(
            state.dxvkDdrawWrapperEntries.value.getOrElse(state.dxvkSelectedDdrawWrapper.intValue) { Container.DEFAULT_DDRAWRAPPER }
        )

        return "version=$version,async=$async,asyncCache=$asyncCache," +
                "vkd3dVersion=$vkd3dVersion,vkd3dLevel=$vkd3dLevel,ddrawrapper=$ddrawWrapper"
    }

    private fun loadGraphicsDriverConfigState(container: Container = shortcut.container) {
        val configStr = if (shouldUseShortcutOverrides(container))
            getShortcutSetting("graphicsDriverConfig", container.getGraphicsDriverConfig())
        else
            container.getGraphicsDriverConfig()
        val config = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(configStr)

        state.gfxVulkanVersionEntries.value = context.resources.getStringArray(R.array.vulkan_version_entries).toList()
        state.gfxMaxDeviceMemoryEntries.value = context.resources.getStringArray(R.array.device_memory_entries).toList()
        state.gfxPresentModeEntries.value = context.resources.getStringArray(R.array.present_mode_entries).toList()
        state.gfxCompositorPresentModeEntries.value = context.resources.getStringArray(R.array.compositor_present_mode_entries).toList()
        state.gfxResourceTypeEntries.value = context.resources.getStringArray(R.array.resource_type_entries).toList()
        state.gfxBcnEmulationEntries.value = context.resources.getStringArray(R.array.bcn_emulation_entries).toList()
        state.gfxBcnEmulationTypeEntries.value = context.resources.getStringArray(R.array.bcn_emulation_type_entries).toList()
        state.gfxBcnEmulationCacheEntries.value = context.resources.getStringArray(R.array.bcn_emulation_cache_entries).toList()
        state.gfxTranscoderEntries.value = context.resources.getStringArray(R.array.wrapper_transcoder_entries).toList()
        state.gfxQualityEntries.value = context.resources.getStringArray(R.array.wrapper_quality_entries).toList()

        val gpuNames = mutableListOf("Device")
        try {
            val gpuNameList = FileUtils.readString(context, AssetPaths.GPU_CARDS)
            if (!gpuNameList.isNullOrEmpty()) {
                val jarray = org.json.JSONArray(gpuNameList)
                for (i in 0 until jarray.length()) {
                    gpuNames.add(jarray.getJSONObject(i).getString("name"))
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading gpu_cards.json", e)
        }
        state.gfxGpuNameEntries.value = gpuNames

        // Load driver versions (will be populated after contents sync)
        loadGraphicsDriverVersions(container)

        selectByValue(state.gfxVulkanVersionEntries.value, config["vulkanVersion"] ?: "1.4", state.gfxSelectedVulkanVersion)
        selectByValue(state.gfxGpuNameEntries.value, config["gpuName"] ?: "Device", state.gfxSelectedGpuName)
        selectByNumber(state.gfxMaxDeviceMemoryEntries.value, config["maxDeviceMemory"] ?: "0", state.gfxSelectedMaxDeviceMemory)
        selectByValue(state.gfxPresentModeEntries.value, config["presentMode"] ?: "mailbox", state.gfxSelectedPresentMode)
        selectByValue(state.gfxCompositorPresentModeEntries.value, config["compositorPresentMode"] ?: "fifo", state.gfxSelectedCompositorPresentMode)
        selectByValue(state.gfxResourceTypeEntries.value, config["resourceType"] ?: "auto", state.gfxSelectedResourceType)
        selectByValue(state.gfxBcnEmulationEntries.value, config["bcnEmulation"] ?: "none", state.gfxSelectedBcnEmulation)
        selectByValue(state.gfxBcnEmulationTypeEntries.value, config["bcnEmulationType"] ?: "compute", state.gfxSelectedBcnEmulationType)
        selectByValue(state.gfxBcnEmulationCacheEntries.value, config["bcnEmulationCache"] ?: "0", state.gfxSelectedBcnEmulationCache)
        selectByValue(state.gfxTranscoderEntries.value, config["transcoder"] ?: "cpu", state.gfxSelectedTranscoder)
        selectByValue(state.gfxQualityEntries.value, config["quality"] ?: "low", state.gfxSelectedQuality)

        state.gfxSyncFrame.value = config["syncFrame"] == "1"
        state.gfxDisablePresentWait.value = config["disablePresentWait"] == "1"

        state.graphicsDriverVersion.value = config["version"] ?: ""
    }

    private fun loadGraphicsDriverVersions(container: Container = shortcut.container) {
        val versions = mutableListOf<String>()
        try {
            val defaults = context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries)
            for (ver in defaults) {
                try {
                    if (com.winlator.cmod.runtime.system.GPUInformation.isDriverSupported(ver, context))
                        versions.add(ver)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error checking driver support: $ver", e)
                }
            }
            try {
                val adrenoManager = com.winlator.cmod.runtime.content.AdrenotoolsManager(context)
                val installed = adrenoManager.enumarateInstalledDrivers()
                if (installed != null) versions.addAll(installed)
            } catch (e: Throwable) {
                Log.w(TAG, "Error loading Adrenotools drivers", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading wrapper versions", e)
        }
        if (versions.isEmpty()) versions.add("System")

        state.gfxDriverVersionEntries.value = versions

        val configStr = if (shouldUseShortcutOverrides(container))
            getShortcutSetting("graphicsDriverConfig", container.getGraphicsDriverConfig())
        else
            container.getGraphicsDriverConfig()
        val config = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(configStr)
        val initialVersion = config["version"] ?: ""
        if (initialVersion.isNotEmpty()) {
            val idx = versions.indexOfFirst { it.equals(initialVersion, ignoreCase = true) }
            if (idx >= 0) state.gfxSelectedDriverVersion.intValue = idx
        }

        loadExtensionsForVersion(state.gfxSelectedDriverVersion.intValue)
    }

    private fun loadExtensionsForVersion(versionIndex: Int) {
        val versions = state.gfxDriverVersionEntries.value
        val version = versions.getOrElse(versionIndex) { return }
        try {
            val extensions = com.winlator.cmod.runtime.system.GPUInformation.enumerateExtensions(version, context)
            if (extensions != null) {
                state.gfxAvailableExtensions.value = extensions.toList()

                // On initial load, set blacklisted from config; on version change, clear blacklist
                val configStr = getShortcutSetting("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig())
                val config = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(configStr)
                val savedVersion = config["version"] ?: ""
                if (version == savedVersion) {
                    val bl = config["blacklistedExtensions"] ?: ""
                    state.gfxBlacklistedExtensions.value = if (bl.isNotEmpty()) bl.split(",").toSet() else emptySet()
                } else {
                    state.gfxBlacklistedExtensions.value = emptySet()
                }
            } else {
                state.gfxAvailableExtensions.value = emptyList()
                state.gfxBlacklistedExtensions.value = emptySet()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading extensions for $version", e)
            state.gfxAvailableExtensions.value = emptyList()
            state.gfxBlacklistedExtensions.value = emptySet()
        }
    }

    private fun loadDxvkConfigState(container: Container = shortcut.container) {
        val configStr = if (shouldUseShortcutOverrides(container))
            getShortcutSetting("dxwrapperConfig", container.getDXWrapperConfig())
        else
            container.getDXWrapperConfig()
        val config = DXVKConfigUtils.parseConfig(configStr)

        // Feature levels
        state.dxvkVkd3dFeatureLevelEntries.value = DXVKConfigUtils.VKD3D_FEATURE_LEVEL.toList()

        // DDraw wrapper from resources
        val ddrawWrapperItems = context.resources.getStringArray(R.array.ddrawrapper_entries).toMutableList()
        for (profile in contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_D7VK)) {
            ddrawWrapperItems.add(ContentsManager.getEntryName(profile))
        }
        if (!isArm64EC) ddrawWrapperItems.removeAll { it.contains("arm64ec") }
        state.dxvkDdrawWrapperEntries.value = ddrawWrapperItems

        loadDxvkVersions(container)

        loadVkd3dVersions(container)

        selectByIdentifier(state.dxvkVkd3dFeatureLevelEntries.value, config.get("vkd3dLevel"), state.dxvkSelectedVkd3dFeatureLevel)
        selectByIdentifier(state.dxvkDdrawWrapperEntries.value, config.get("ddrawrapper"), state.dxvkSelectedDdrawWrapper)

        state.dxvkAsync.value = config.get("async") == "1"
        state.dxvkAsyncCache.value = config.get("asyncCache") == "1"
    }

    private fun loadDxvkVersions(container: Container = shortcut.container) {
        val originalItems = context.resources.getStringArray(R.array.dxvk_version_entries).toMutableList()

        for (profile in contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            val entryName = ContentsManager.getEntryName(profile)
            val firstDash = entryName.indexOf('-')
            originalItems.add(entryName.substring(firstDash + 1))
        }

        if (!isArm64EC) {
            originalItems.removeAll { it.contains("arm64ec") }
        }

        state.dxvkVersionEntries.value = originalItems

        val configStr = if (shouldUseShortcutOverrides(container))
            getShortcutSetting("dxwrapperConfig", container.getDXWrapperConfig())
        else
            container.getDXWrapperConfig()
        val config = DXVKConfigUtils.parseConfig(configStr)
        selectByIdentifier(originalItems, config.get("version"), state.dxvkSelectedVersion)
    }

    private fun loadVkd3dVersions(container: Container = shortcut.container) {
        val items = mutableListOf<String>()
        val predefined = context.resources.getStringArray(R.array.vkd3d_version_entries)
        items.addAll(predefined)

        // Build identifiers matching VKD3DVersionItem format: "verName-verCode" for profiles
        for (profile in contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            val identifier = profile.verName + "-" + profile.verCode
            items.add(identifier)
        }

        state.dxvkVkd3dVersionEntries.value = items

        val configStr = if (shouldUseShortcutOverrides(container))
            getShortcutSetting("dxwrapperConfig", container.getDXWrapperConfig())
        else
            container.getDXWrapperConfig()
        val config = DXVKConfigUtils.parseConfig(configStr)
        selectByIdentifier(items, config.get("vkd3dVersion"), state.dxvkSelectedVkd3dVersion)
    }

    private fun selectByNumber(
        entries: List<String>,
        number: String,
        target: androidx.compose.runtime.MutableIntState
    ) {
        val idx = entries.indexOfFirst {
            StringUtils.parseNumber(it) == number
        }
        target.intValue = if (idx >= 0) idx else 0
    }

    private fun handleDxvkVkd3dVersionChanged(versionIndex: Int) {
        val vkd3dEntries = state.dxvkVkd3dVersionEntries.value
        val selectedVkd3d = if (versionIndex in vkd3dEntries.indices) vkd3dEntries[versionIndex] else "None"

        if (selectedVkd3d != "None") {
            val allVersions = state.dxvkVersionEntries.value
            val semver = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
            val filtered = allVersions.filter { v ->
                val match = semver.find(v)
                if (match != null) {
                    val major = match.groupValues[1].toIntOrNull() ?: 0
                    major >= 2
                } else true
            }
            state.dxvkVersionEntries.value = filtered

            // Re-select current or default
            val currentDxvk = state.dxvkVersionEntries.value.getOrElse(state.dxvkSelectedVersion.intValue) { "" }
            val curMajor = semver.find(currentDxvk)?.groupValues?.get(1)?.toIntOrNull()
            if (curMajor != null && curMajor >= 2) {
                selectByIdentifier(filtered, currentDxvk, state.dxvkSelectedVersion)
            } else {
                selectByIdentifier(filtered, "", state.dxvkSelectedVersion)
            }
        } else {
            // Reload all DXVK versions
            loadDxvkVersions()
        }
    }

    private fun handleDxvkVersionChanged(versionIndex: Int) {
        val dxvkEntries = state.dxvkVersionEntries.value
        val selectedDxvk = dxvkEntries.getOrElse(versionIndex) { "" }
        val normalized = selectedDxvk.lowercase()
        val isGplAsync = normalized.contains("gplasync")
        val isAsync = normalized.contains("async") || isGplAsync

        state.dxvkAsync.value = isAsync
        state.dxvkAsyncCache.value = isGplAsync
    }

    private fun handleContainerChanged(containerIndex: Int) {
        if (containerIndex !in containerList.indices) return
        val newContainer = containerList[containerIndex]

        val wineVersionStr = newContainer.getWineVersion()
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersionStr)
        isArm64EC = wineInfo.isArm64EC
        state.isArm64EC.value = isArm64EC
        state.wineVersionDisplay.value = formatWineVersionDisplay(wineInfo)
        rebuildEmulatorLists()

        selectByIdentifier(
            state.emulator32Entries.value,
            newContainer.getEmulator(),
            state.selectedEmulator
        )
        selectByIdentifier(
            state.emulator64Entries.value,
            newContainer.getEmulator64(),
            state.selectedEmulator64
        )

        state.emulatorsEnabled.value = true

        loadBox64Versions(newContainer)
        loadFexcoreVersions(newContainer)
        loadBox64Presets(newContainer)
        loadFexcorePresets(newContainer)
        updateEmulatorFrameVisibility()

        // Reset container-derived state to the new container. Shortcut-only
        // fields (name, launchExePath, execArgs, refreshRate, controlsProfile,
        // numControllers, disableXInput, simTouchScreen) travel with the shortcut and are not
        // touched here.
        applyContainerDefaultsToState(newContainer)
        loadGraphicsDriverConfigState(newContainer)
        loadDxvkConfigState(newContainer)
        loadWineD3DConfigState(newContainer)
    }

    private fun applyContainerDefaultsToState(container: Container) {
        selectScreenSize(container.getScreenSize())

        selectByIdentifier(
            state.graphicsDriverEntries.value,
            container.getGraphicsDriver(),
            state.selectedGraphicsDriver
        )
        selectByIdentifier(
            state.dxWrapperEntries.value,
            container.getDXWrapper(),
            state.selectedDxWrapper
        )
        selectByIdentifier(
            state.audioDriverEntries.value,
            container.getAudioDriver(),
            state.selectedAudioDriver
        )

        state.selectedSurfaceEffect.intValue = if (container.getExtra("swapRB", "0") == "1") 1 else 0

        val midiFont = container.getMIDISoundFont()
        val midiEntries = state.midiSoundFontEntries.value
        if (midiFont.isEmpty()) {
            state.selectedMidiSoundFont.intValue = 0
        } else {
            val idx = midiEntries.indexOfFirst { it == midiFont }
            state.selectedMidiSoundFont.intValue = if (idx >= 0) idx else 0
        }

        state.lcAll.value = container.getLC_ALL()
        state.fullscreenStretched.value = container.isFullscreenStretched
        state.useUnixLibs.value = container.isUseUnixLibs

        val startupEntries = state.startupSelectionEntries.value
        state.selectedStartupSelection.intValue = container.getStartupSelection().toInt()
            .coerceIn(0, (startupEntries.size - 1).coerceAtLeast(0))

        // Desktop theme is stored as compound "THEME,TYPE,COLOR".
        val desktopThemeArr = state.desktopThemeEntries.value
        if (desktopThemeArr.isNotEmpty()) {
            val themePart = container.getDesktopTheme().split(",").firstOrNull()?.trim() ?: ""
            val themeIdx = desktopThemeArr.indexOfFirst { it.equals(themePart, ignoreCase = true) }
            state.selectedDesktopTheme.intValue = if (themeIdx >= 0) themeIdx else 0
        }

        val directX = mutableListOf<WinComponentItem>()
        val general = mutableListOf<WinComponentItem>()
        for (component in KeyValueSet(container.getWinComponents())) {
            val key = component[0]
            val value = component[1]
            val label = StringUtils.getString(context, key) ?: key
            val selectedIdx = try { Integer.parseInt(value) } catch (e: NumberFormatException) { 0 }
            val item = WinComponentItem(key, label, selectedIdx)
            if (key.startsWith("direct")) directX.add(item) else general.add(item)
        }
        state.directXComponents.value = directX
        state.generalComponents.value = general

        val containerEnvVarsStr = container.getEnvVars() ?: Container.DEFAULT_ENV_VARS
        val items = parseEnvVarItems(containerEnvVarsStr)
        state.sdl2Compatibility.value = EnvVars(containerEnvVarsStr).get("SDL_XINPUT_ENABLED") == "1"
        state.envVars.value = if (state.sdl2Compatibility.value) {
            items.filterNot { item -> sdl2EnvVars.any { it.first == item.key } }
        } else items

        val cpuCount = state.cpuCount.intValue
        state.cpuChecked.value = parseCpuList(container.getCPUList(true), cpuCount)
        state.cpuCheckedWoW64.value = parseCpuList(container.getCPUListWoW64(true), cpuCount)

        val inputType = container.getInputType().toInt()
        state.enableXInput.value =
            (inputType and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) == WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
        state.enableDInput.value =
            (inputType and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) == WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
        state.selectedDInputMapperType.intValue =
            if ((inputType and WinHandler.FLAG_DINPUT_MAPPER_STANDARD.toInt()) == WinHandler.FLAG_DINPUT_MAPPER_STANDARD.toInt()) 0 else 1
        state.shortcutExclusiveXInput.value = container.isExclusiveXInput()
        if (!state.shortcutExclusiveXInput.value) {
            state.enableXInput.value = true
            state.enableDInput.value = true
        }

        if (state.isSteamGame.value) {
            state.steamLauncher.value =
                com.winlator.cmod.feature.stores.steam.utils.PrefManager.wnPlanW
            state.useLegacyLauncher.value = container.isUseColdClient || container.isUnpackFiles
            state.steamOfflineMode.value = container.isSteamOfflineMode
            state.runtimePatcher.value = container.isRuntimePatcher
            state.useSteamInput.value = container.getExtra("useSteamInput", "0") == "1"
        }
    }

    private fun parseCpuList(cpuList: String, cpuCount: Int): List<Boolean> {
        val checked = MutableList(cpuCount) { true }
        if (cpuList.isNotEmpty()) {
            for (i in checked.indices) checked[i] = false
            cpuList.split(",").forEach { cpuStr ->
                val idx = cpuStr.trim().replace("CPU", "").toIntOrNull()
                if (idx != null && idx in checked.indices) checked[idx] = true
            }
        }
        return checked
    }

    private fun loadWineD3DConfigState(container: Container = shortcut.container) {
        val configStr = if (shouldUseShortcutOverrides(container))
            getShortcutSetting("dxwrapperConfig", container.getDXWrapperConfig())
        else
            container.getDXWrapperConfig()
        val config = WineD3DConfigUtils.parseConfig(configStr)

        // Video memory size from resources
        state.wined3dVideoMemorySizeEntries.value =
            context.resources.getStringArray(R.array.video_memory_size_entries).toList()

        // GPU names from gpu_cards.json
        val gpuNames = mutableListOf<String>()
        try {
            val gpuNameList = FileUtils.readString(context, AssetPaths.GPU_CARDS)
            if (!gpuNameList.isNullOrEmpty()) {
                val jarray = org.json.JSONArray(gpuNameList)
                for (i in 0 until jarray.length()) {
                    gpuNames.add(jarray.getJSONObject(i).getString("name"))
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading gpu_cards.json for WineD3D", e)
        }
        state.wined3dGpuNameEntries.value = gpuNames

        state.wined3dSelectedCsmt.intValue = if (config.get("csmt") == "3") 0 else 1
        state.wined3dSelectedStrictShaderMath.intValue = if (config.get("strict_shader_math") == "1") 0 else 1
        selectByValue(state.wined3dOffscreenRenderingModeEntries.value, config.get("OffscreenRenderingMode"), state.wined3dSelectedOffscreenRenderingMode)
        selectByValue(state.wined3dGpuNameEntries.value, config.get("gpuName"), state.wined3dSelectedGpuName)
        selectByValue(state.wined3dRendererEntries.value, config.get("renderer"), state.wined3dSelectedRenderer)
        selectByNumber(state.wined3dVideoMemorySizeEntries.value, config.get("videoMemorySize"), state.wined3dSelectedVideoMemorySize)
    }

    private fun buildWineD3DConfigFromState(): String {
        val csmt = if (state.wined3dSelectedCsmt.intValue == 0) "3" else "0"
        val gpuName = state.wined3dGpuNameEntries.value.getOrElse(state.wined3dSelectedGpuName.intValue) { "" }
        val videoMemorySize = StringUtils.parseNumber(
            state.wined3dVideoMemorySizeEntries.value.getOrElse(state.wined3dSelectedVideoMemorySize.intValue) { "0" }
        )
        val strictShaderMath = if (state.wined3dSelectedStrictShaderMath.intValue == 0) "1" else "0"
        val offscreenRenderingMode = state.wined3dOffscreenRenderingModeEntries.value.getOrElse(
            state.wined3dSelectedOffscreenRenderingMode.intValue
        ) { "fbo" }
        val renderer = state.wined3dRendererEntries.value.getOrElse(state.wined3dSelectedRenderer.intValue) { "gl" }

        return "csmt=$csmt,gpuName=$gpuName,videoMemorySize=$videoMemorySize," +
                "strict_shader_math=$strictShaderMath,OffscreenRenderingMode=$offscreenRenderingMode," +
                "renderer=$renderer"
    }


    // Show / Dismiss

    fun show() {
        if (com.winlator.cmod.feature.retro.RetroShortcuts.isRetroShortcut(shortcut)) {
            com.winlator.cmod.feature.retro
                .RetroSettingsDialog(activity, shortcut)
                .show()
            return
        }
        dialog.show()
        restorePaneNav?.invoke()
        restorePaneNav = dialog.window?.bindPaneNav(
            PaneNavWindowHandlers(
                onDir = { nav.dpad(it) },
                onActivate = { nav.dpad(PANE_DIR_ACTIVATE) },
                onDismiss = { if (nav.onContentBack?.invoke() != true) dialog.dismiss() },
                onStart = { nav.onSave?.invoke() },
            )
        )
        dialog.window?.apply {
            applyDialogLayout()
            decorView.post { applyDialogLayout() }

            // Post-attach blur: set flag + radius in one setAttributes call so
            // WindowManager applies them atomically (otherwise blur can flicker).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val params = attributes
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = 10
                attributes = params
            }
        }
    }

    private fun Window.applyDialogLayout() {
        val dm = activity.resources.displayMetrics
        val hostView = activity.window?.decorView
        val hostWidth = hostView?.width?.takeIf { it > 0 }
        val hostHeight = hostView?.height?.takeIf { it > 0 }
        val bounds =
            if (hostWidth != null && hostHeight != null) {
                hostWidth to hostHeight
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowBounds = activity.windowManager.currentWindowMetrics.bounds
                windowBounds.width() to windowBounds.height()
            } else {
                dm.widthPixels to dm.heightPixels
            }

        val screenWidthDp = bounds.first / dm.density
        val needsNearFullWidth = screenWidthDp < 820f
        val widthFactor = if (needsNearFullWidth) 0.96f else 0.88f
        val heightFactor = if (needsNearFullWidth) 0.90f else 0.88f
        val navInsets =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars()
                )
            } else {
                null
            }
        val cutoutInsets =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.displayCutout()
                )
            } else {
                null
            }
        val edgePaddingPx = (12f * dm.density).toInt().coerceAtLeast(1)
        val cutoutPaddingCapPx = (8f * dm.density).toInt()
        val leftInsetPx = maxOf(navInsets?.left ?: 0, (cutoutInsets?.left ?: 0).coerceAtMost(cutoutPaddingCapPx))
        val rightInsetPx = maxOf(navInsets?.right ?: 0, (cutoutInsets?.right ?: 0).coerceAtMost(cutoutPaddingCapPx))
        val topInsetPx = maxOf(navInsets?.top ?: 0, (cutoutInsets?.top ?: 0).coerceAtMost(cutoutPaddingCapPx))
        val bottomInsetPx = maxOf(navInsets?.bottom ?: 0, (cutoutInsets?.bottom ?: 0).coerceAtMost(cutoutPaddingCapPx))
        val horizontalInsetPx = maxOf(leftInsetPx, rightInsetPx)
        val verticalInsetPx = maxOf(topInsetPx, bottomInsetPx)
        val maxDialogWidth = (bounds.first - ((horizontalInsetPx + edgePaddingPx) * 2)).coerceAtLeast(1)
        val maxDialogHeight = (bounds.second - ((verticalInsetPx + edgePaddingPx) * 2)).coerceAtLeast(1)

        setLayout(
            (bounds.first * widthFactor).toInt().coerceAtMost(maxDialogWidth),
            (bounds.second * heightFactor).toInt().coerceAtMost(maxDialogHeight),
        )
    }

    fun dismiss() {
        AppUtils.hideKeyboard(activity)
        dialog.dismiss()
    }

    companion object {
        private const val TAG = "ShortcutSettingsCompose"
        private const val EXTRA_USE_CONTAINER_DEFAULTS = "use_container_defaults"

        /**
         * Creates a minimal `.desktop` file on the preferred game container and returns a
         * [Shortcut] pointing at it. Used when the user taps Settings on a library game
         * that has no shortcut yet. The shortcut is persisted to disk immediately; if the
         * user dismisses the dialog without saving, the file remains (and shows up in the
         * Shortcuts tab from then on).
         *
         * @param source one of "STEAM", "EPIC", "GOG"
         * @param appId  numeric app id (for GOG use the pseudo id)
         * @param gogId  GOG id string — required when `source == "GOG"`, ignored otherwise
         */
        @JvmStatic
        fun createLibraryShortcut(
            context: Context,
            containerManager: ContainerManager,
            source: String,
            appId: Int,
            gogId: String?,
            appName: String,
        ): Shortcut? {
            val container = SetupWizardActivity.getPreferredGameContainer(context, containerManager)
            if (container == null) {
                SetupWizardActivity.promptToInstallWineOrCreateContainer(context)
                return null
            }
            val desktopDir = container.desktopDir
            if (!desktopDir.exists()) desktopDir.mkdirs()
            val safeName = appName.replace("/", "_").replace("\\", "_")
            val shortcutFile = File(desktopDir, "$safeName.desktop")
            val iconKey = when (source) {
                "STEAM" -> "steam_icon_$appId"
                "EPIC" -> "epic_icon_$appId"
                "GOG" -> "gog_icon_$gogId"
                else -> ""
            }
            val exec = if (source == "STEAM") {
                "wine \"C:\\\\Program Files (x86)\\\\Steam\\\\steamclient_loader_x64.exe\""
            } else {
                "wine \"D:\\\\\""
            }
            val sb = StringBuilder()
            sb.append("[Desktop Entry]\n")
            sb.append("Type=Application\n")
            sb.append("Name=$appName\n")
            sb.append("Exec=$exec\n")
            sb.append("Icon=$iconKey\n")
            sb.append("\n[Extra Data]\n")
            sb.append("game_source=$source\n")
            sb.append("app_id=$appId\n")
            if (source == "GOG" && !gogId.isNullOrEmpty()) {
                sb.append("gog_id=$gogId\n")
            }
            sb.append("container_id=${container.id}\n")
            sb.append("use_container_defaults=1\n")
            FileUtils.writeString(shortcutFile, sb.toString())
            return Shortcut(container, shortcutFile)
        }
    }
}
