package com.winlator.cmod.feature.settings
import android.app.Activity
import android.app.Dialog
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.winlator.cmod.runtime.display.environment.components.NetworkingSettings
import com.winlator.cmod.shared.ui.nav.PANE_DIR_ACTIVATE
import com.winlator.cmod.shared.ui.nav.PaneNavWindowHandlers
import com.winlator.cmod.shared.ui.nav.bindPaneNav
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.preference.PreferenceManager
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.winlator.cmod.R
import com.winlator.cmod.feature.library.DriveItem
import com.winlator.cmod.feature.library.EnvVarItem
import com.winlator.cmod.feature.library.GameSettingsCallbacks
import com.winlator.cmod.feature.library.GameSettingsContent
import com.winlator.cmod.feature.library.GameSettingsNav
import com.winlator.cmod.feature.library.GameSettingsStateHolder
import com.winlator.cmod.feature.library.WinComponentItem
import com.winlator.cmod.feature.library.parseEnvVarItems
import com.winlator.cmod.runtime.audio.directaudio.DirectAudioDriver
import com.winlator.cmod.runtime.compat.box64.Box64Preset
import com.winlator.cmod.runtime.compat.box64.Box64PresetManager
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerCreation
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.feature.settings.DXVKConfigUtils
import com.winlator.cmod.feature.settings.GraphicsDriverConfigUtils
import com.winlator.cmod.feature.settings.OtherSettingsFragment
import com.winlator.cmod.feature.settings.WineD3DConfigUtils
import com.winlator.cmod.shared.android.AppUtils
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.android.RefreshRateUtils
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.io.AssetPaths
import com.winlator.cmod.runtime.wine.EnvVars
import com.winlator.cmod.runtime.wine.LocaleEnv
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.util.KeyValueSet
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.util.StringUtils
import com.winlator.cmod.runtime.wine.WineInfo
import com.winlator.cmod.runtime.wine.WineRegistryEditor
import com.winlator.cmod.runtime.wine.WineThemeManager
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePreset
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePresetManager
import com.winlator.cmod.runtime.audio.midi.MidiManager
import com.winlator.cmod.runtime.display.winhandler.WinHandler
import com.winlator.cmod.runtime.display.environment.ImageFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import com.winlator.cmod.shared.ui.dialog.ContainerProgressPopup
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.dialog.WinNativeComposeDialogs
import android.os.Environment

/**
 * Compose replacement for the legacy `ContainerDetailFragment`. Reuses
 * [GameSettingsContent] via [GameSettingsStateHolder.isContainerEditMode].
 * Pass a null container to create a new one; pass an existing container to
 * edit it in place. `onFinished` fires after save or cancel.
 */
class ContainerSettingsComposeDialog @JvmOverloads constructor(
    private val activity: Activity,
    private val container: Container?,
    private val onFinished: Runnable? = null
) {
    private data class DriveDraft(
        val letter: String,
        val path: String,
        val canChangeLetter: Boolean,
    )


    private val context = activity
    private val dialog: Dialog
    private val nav = GameSettingsNav()
    private var restorePaneNav: (() -> Unit)? = null
    private val state = GameSettingsStateHolder()
    private val manager = ContainerManager(context)
    private val contentsManager = ContentsManager(context)
    private var isArm64EC = false
    private val creatingPopup: ContainerProgressPopup =
        ContainerProgressPopup(activity, R.string.containers_list_creating)

    // Parallel id/display lists for presets and wine versions.
    private var box64PresetIds = mutableListOf<String>()
    private var fexcorePresetIds = mutableListOf<String>()
    private var wineVersionIdentifiers = mutableListOf<String>()
    private var autogeneratedContainerName: String? = null

    // Drives working copy, mirrored into state.drivesList via syncDrivesState.
    private val drivesWorking = mutableListOf<DriveDraft>()
    private var pendingDriveIndex: Int = -1
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
            }
            // Centralize cleanup so back-button dismissal follows the same
            // path as Save/Cancel and still fires onFinished (important for
            // the setup wizard launcher that blocks on UnifiedActivity finishing).
            setOnDismissListener {
                restorePaneNav?.invoke()
                restorePaneNav = null
                AppUtils.hideKeyboard(activity)
                scope.cancel()
                onFinished?.run()
            }
        }

        state.isContainerEditMode.value = true
        state.wineVersionEditable.value = (container == null)

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
                // Dismisses synchronously for edits; for new-container
                // creation, dismisses from the createContainerAsync callback.
                saveSettings()
            }

            override fun onDismiss() {
                dismiss()
            }

            override fun onAddToHomeScreen() {}

            override fun onRemoveEnvVar(index: Int) {
                val list = state.envVars.value.toMutableList()
                if (index in list.indices) {
                    list.removeAt(index)
                    state.envVars.value = list
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

            override fun onEmulatorChanged() {
                updateEmulatorFrameVisibility()
            }

            override fun onWineVersionChanged(versionIndex: Int) {
                if (container != null) return // locked on existing containers
                val identifier = wineVersionIdentifiers.getOrNull(versionIndex) ?: return
                val wineInfo = WineInfo.fromIdentifier(context, contentsManager, identifier)
                isArm64EC = wineInfo.isArm64EC()
                state.isArm64EC.value = isArm64EC
                state.wineVersionDisplay.value = formatWineVersionDisplay(wineInfo)
                applyDefaultContainerName(wineInfo, identifier)
                rebuildAudioDriverList(identifier)
                // Box64 list depends on arch (box64 vs wowbox64 entries).
                rebuildEmulatorLists()
                loadBox64Versions()
                loadFexcoreVersions()
                loadBox64Presets()
                loadFexcorePresets()
                updateEmulatorFrameVisibility()
            }

            override fun onAddDrive() {
                val nextLetter = availableDriveLetters().firstOrNull() ?: return
                drivesWorking.add(DriveDraft(nextLetter, "", canChangeLetter = true))
                syncDrivesState()
                pendingDriveIndex = drivesWorking.size - 1
                openDriveDirectoryPicker()
            }

            override fun onDriveLetterChanged(index: Int, newLetter: String) {
                updateDriveLetter(index, newLetter)
            }

            override fun onRemoveDrive(index: Int) {
                if (index in drivesWorking.indices) {
                    drivesWorking.removeAt(index)
                    syncDrivesState()
                }
            }

            override fun onPickDrivePath(index: Int) {
                if (index !in drivesWorking.indices) return
                pendingDriveIndex = index
                openDriveDirectoryPicker()
            }

            override fun onPickWallpaper() {
                openWallpaperFilePicker()
            }

            override fun onExportSaves() {
                showExportSavesConfirmation()
            }

            override fun onImportSaves() {
                showImportSavesConfirmation()
            }
        }
    }

    private fun syncDrivesState() {
        state.drivesList.value =
            drivesWorking.map {
                DriveItem(
                    letter = it.letter,
                    path = it.path,
                    canChangeLetter = it.canChangeLetter,
                )
            }
    }

    private fun availableDriveLetters(excludingIndex: Int = -1): List<String> {
        val usedLetters =
            drivesWorking
                .mapIndexedNotNull { index, drive ->
                    drive.letter.takeUnless { index == excludingIndex }?.uppercase(Locale.ROOT)
                }.toSet()
        return SELECTABLE_DRIVE_LETTERS.filter { it !in usedLetters }
    }

    private fun updateDriveLetter(
        index: Int,
        newLetter: String,
    ) {
        if (index !in drivesWorking.indices) return
        if (!drivesWorking[index].canChangeLetter) return

        val normalizedLetter = newLetter.trim().uppercase(Locale.ROOT)
        if (normalizedLetter !in SELECTABLE_DRIVE_LETTERS) return
        if (normalizedLetter !in availableDriveLetters(index) &&
            !drivesWorking[index].letter.equals(normalizedLetter, ignoreCase = true)
        ) {
            return
        }

        drivesWorking[index] = drivesWorking[index].copy(letter = normalizedLetter)
        syncDrivesState()
    }

    private fun openWallpaperFilePicker() {
        DirectoryPickerDialog.showFile(
            activity,
            title = context.getString(R.string.settings_general_select_wallpaper),
            allowedExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif"),
            dimAmount = 0.5f,
            preserveBackdropBlur = true,
        ) { pickedPath ->
            try {
                val destFile = WineThemeManager.getUserWallpaperFile(context)
                File(pickedPath).inputStream().use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                state.desktopWallpaperSelected.value = true
            } catch (e: Throwable) {
                Log.e(TAG, "Error copying wallpaper", e)
                WinToast.show(
                    context,
                    context.getString(R.string.settings_containers_error_saving_wallpaper),
                    dialog.window?.decorView,
                )
            }
        }
    }

    private fun openDriveDirectoryPicker() {
        val idx = pendingDriveIndex
        if (idx !in drivesWorking.indices) return

        val imagefsRoot = ImageFs.find(context).getRootDir()
        val driveRoots =
            listOf(
                DirectoryPickerDialog.ManagedRoot("C:", File(imagefsRoot, "home").absolutePath),
                DirectoryPickerDialog.ManagedRoot("Z:", imagefsRoot.absolutePath),
                DirectoryPickerDialog.ManagedRoot(
                    "D:",
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
                ),
                DirectoryPickerDialog.ManagedRoot("Internal", Environment.getExternalStorageDirectory().absolutePath),
            )

        DirectoryPickerDialog.show(
            activity = activity,
            initialPath = drivesWorking[idx].path.ifBlank { null },
            dimAmount = 0.5f,
            preserveBackdropBlur = true,
            extraRoots = driveRoots,
        ) { path ->
            val currentIndex = pendingDriveIndex
            if (currentIndex in drivesWorking.indices) {
                drivesWorking[currentIndex] = drivesWorking[currentIndex].copy(path = path)
                syncDrivesState()
            }
            pendingDriveIndex = -1
        }
    }

    private fun loadInitialData() {
        val c = container

        if (c != null) {
            state.name.value = c.getName()
        } else {
            applyDefaultContainerName(
                WineInfo.fromIdentifier(
                    context,
                    contentsManager,
                    WineInfo.MAIN_WINE_VERSION.identifier()
                ),
                force = true
            )
        }

        val inputType = c?.getInputType() ?: WinHandler.DEFAULT_INPUT_TYPE.toInt()
        state.enableXInput.value =
            (inputType and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) == WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
        state.enableDInput.value =
            (inputType and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) == WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
        state.selectedDInputMapperType.intValue =
            if ((inputType and WinHandler.FLAG_DINPUT_MAPPER_STANDARD.toInt()) == WinHandler.FLAG_DINPUT_MAPPER_STANDARD.toInt()) 0 else 1

        state.containerExclusiveInput.value = c?.isExclusiveXInput() ?: true
        if (!state.containerExclusiveInput.value) {
            state.enableXInput.value = true
            state.enableDInput.value = true
        }

        state.fullscreenStretched.value = c?.isFullscreenStretched() ?: false
        state.useUnixLibs.value = c?.isUseUnixLibs() ?: true

        // Steam fields are shortcut-only in the UI; leave any existing steam
        // state on the container untouched — saveSettings() skips them.
        state.isSteamGame.value = false

        state.execArgs.value = c?.getExecArgs() ?: ""
        state.lcAll.value = c?.getLC_ALL().takeUnless { it.isNullOrEmpty() }
            ?: LocaleEnv.deriveFromDevice()

        val cpuCount = Runtime.getRuntime().availableProcessors()
        state.cpuCount.intValue = cpuCount
        state.cpuChecked.value = parseCpuList(c?.getCPUList(true) ?: Container.getFallbackCPUList(), cpuCount)
        state.cpuCheckedWoW64.value = parseCpuList(c?.getCPUListWoW64(true) ?: Container.getFallbackCPUListWoW64(), cpuCount)

        val wincomponentsStr = c?.getWinComponents() ?: Container.DEFAULT_WINCOMPONENTS
        val directX = mutableListOf<WinComponentItem>()
        val general = mutableListOf<WinComponentItem>()
        for (component in KeyValueSet(wincomponentsStr)) {
            val key = component[0]
            val value = component[1]
            val label = StringUtils.getString(context, key) ?: key
            val selectedIdx = try { Integer.parseInt(value) } catch (e: NumberFormatException) { 0 }
            val item = WinComponentItem(key, label, selectedIdx)
            if (key.startsWith("direct")) directX.add(item) else general.add(item)
        }
        state.directXComponents.value = directX
        state.generalComponents.value = general

        val envVarsStr = c?.getEnvVars() ?: Container.DEFAULT_ENV_VARS
        val items = parseEnvVarItems(envVarsStr)
        state.sdl2Compatibility.value = EnvVars(envVarsStr).get("SDL_XINPUT_ENABLED") == "1"
        state.envVars.value = if (state.sdl2Compatibility.value) {
            items.filterNot { it.key in SDL2_KEYS }
        } else items

        drivesWorking.clear()
        val drivesStr = c?.let {
            com.winlator.cmod.runtime.wine.WineUtils.resolveEffectiveDrives(it)
        } ?: Container.DEFAULT_DRIVES
        for (drive in Container.drivesIterator(drivesStr)) {
            drivesWorking.add(
                DriveDraft(
                    letter = drive[0].uppercase(Locale.ROOT),
                    path = drive[1],
                    canChangeLetter = false,
                )
            )
        }
        syncDrivesState()

        val desktopThemeValue = c?.getDesktopTheme() ?: WineThemeManager.DEFAULT_DESKTOP_THEME
        val themeInfo = WineThemeManager.ThemeInfo(desktopThemeValue)
        state.desktopBackgroundColor.value = String.format("#%06X", themeInfo.backgroundColor and 0x00FFFFFF)

        // Mouse warp override lives in .wine/user.reg, not on the container.
        var mouseWarp = "disable"
        val rootDir = c?.getRootDir()
        if (rootDir != null) {
            val userRegFile = File(rootDir, ".wine/user.reg")
            if (userRegFile.exists()) {
                try {
                    WineRegistryEditor(userRegFile).use { reg ->
                        mouseWarp = reg.getStringValue(
                            "Software\\Wine\\DirectInput",
                            "MouseWarpOverride",
                            "disable"
                        )
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Error reading MouseWarpOverride", e)
                }
            }
        }
        pendingMouseWarpValue = mouseWarp.lowercase()
    }

    private var pendingMouseWarpValue = "disable"

    private fun loadResourceArrays() {
        val c = container

        val screenSizeArr = context.resources.getStringArray(R.array.screen_size_entries).toList()
        state.screenSizeEntries.value = screenSizeArr
        selectScreenSize(c?.getScreenSize() ?: Container.DEFAULT_SCREEN_SIZE)

        try {
            val refreshEntries = OtherSettingsFragment.buildRefreshRateEntries(activity)
            state.refreshRateEntries.value = refreshEntries
            val savedRate = c?.getExtra("refreshRate", "0")
            if (savedRate.isNullOrEmpty() || savedRate == "0") {
                state.selectedRefreshRate.intValue = 0
            } else {
                val idx = refreshEntries.indexOfFirst { it == "$savedRate Hz" }
                state.selectedRefreshRate.intValue = if (idx >= 0) idx else 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading refresh rate entries", e)
        }

        val graphicsDriverArr = context.resources.getStringArray(R.array.graphics_driver_entries).toList()
        state.graphicsDriverEntries.value = graphicsDriverArr
        selectByIdentifier(
            graphicsDriverArr,
            c?.getGraphicsDriver() ?: Container.DEFAULT_GRAPHICS_DRIVER,
            state.selectedGraphicsDriver
        )

        state.zinkModeEntries.value = context.resources.getStringArray(R.array.zink_mode_entries).toList()
        state.selectedZinkMode.intValue =
            if ((c?.getZinkMode() ?: Container.DEFAULT_ZINK_MODE) == "windows") 1 else 0

        val dxWrapperArr = context.resources.getStringArray(R.array.dxwrapper_entries).toList()
        state.dxWrapperEntries.value = dxWrapperArr
        selectByIdentifier(
            dxWrapperArr,
            c?.getDXWrapper() ?: Container.DEFAULT_DXWRAPPER,
            state.selectedDxWrapper
        )

        val surfaceEffectArr = context.resources.getStringArray(R.array.surface_effect_entries).toList()
        state.surfaceEffectEntries.value = surfaceEffectArr
        state.selectedSurfaceEffect.intValue = if (c?.getExtra("swapRB", "0") == "1") 1 else 0

        state.frameGenEnabled.value = c?.getExtra("frameGen", "0") == "1"
        state.frameGenMultiplier.intValue =
            c?.getExtra("frameGenMultiplier", "2")?.toIntOrNull()?.coerceIn(2, 4) ?: 2
        state.frameGenTargetRate.intValue =
            c?.getExtra("frameGenTargetRate", "0")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        state.frameGenFlowScale.intValue =
            c?.getExtra("frameGenFlowScale", "70")?.toIntOrNull()?.coerceIn(25, 100) ?: 70

        state.netDriverEntries.value = listOf(
            context.getString(R.string.networking_driver_winnative),
            context.getString(R.string.networking_driver_none)
        )
        state.selectedNetDriver.intValue =
            if (NetworkingSettings.driverOrDefault(c?.getExtra(NetworkingSettings.EXTRA_DRIVER, NetworkingSettings.DEFAULT_DRIVER)) == NetworkingSettings.DRIVER_NONE) 1 else 0
        state.netMac.value = c?.getExtra(NetworkingSettings.EXTRA_MAC, "") ?: ""
        state.netMacAuto.value = NetworkingSettings.automaticMac(context)

        // init() migrates a legacy single reshadeEffect / flat reshadeParams into the loadout model
        val reshadeEffects = com.winlator.cmod.runtime.reshade.ReshadeManager.scanEffects(context)
        state.reshadeEffects.value = reshadeEffects
        state.reshadeLoadout.init(
            reshadeEffects,
            c?.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_LOADOUT, null),
            c?.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_MODE, null),
            c?.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_PARAMS, null),
            c?.getExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_EFFECT, null),
        )

        val audioDriverArr = audioDriverEntriesFor(c?.getWineVersion())
        state.audioDriverEntries.value = audioDriverArr
        selectByIdentifier(
            audioDriverArr,
            c?.getAudioDriver() ?: Container.DEFAULT_AUDIO_DRIVER,
            state.selectedAudioDriver
        )
        state.directAudioMic.value = DirectAudioDriver.isMicEnabled(
            c?.getExtra(DirectAudioDriver.EXTRA_MIC)
        )

        loadMidiSoundFonts()

        val emulatorArr = context.resources.getStringArray(R.array.emulator_entries).toList()
        state.emulatorEntries.value = emulatorArr

        // For existing containers, set up emulator lists now from the saved
        // wine version. For new containers, defer this to
        // populateContentsDependentData() which runs after the installed wine
        // list is populated — that way the emulator options (Box64 vs FEXCore)
        // match the actual wine version the user will see in the General tab.
        if (c != null) {
            val wineInfo = WineInfo.fromIdentifier(context, contentsManager, c.getWineVersion())
            isArm64EC = wineInfo.isArm64EC()
            state.isArm64EC.value = isArm64EC
            state.wineVersionDisplay.value = formatWineVersionDisplay(wineInfo)

            rebuildEmulatorLists()
            selectByIdentifier(
                state.emulator32Entries.value,
                c.getEmulator(),
                state.selectedEmulator
            )
            selectByIdentifier(
                state.emulator64Entries.value,
                c.getEmulator64(),
                state.selectedEmulator64
            )
        }

        state.localeOptions.value = context.resources.getStringArray(R.array.some_lc_all).toList()
        state.winComponentEntries.value =
            context.resources.getStringArray(R.array.wincomponent_entries).toList()
        state.dInputMapperTypeEntries.value =
            context.resources.getStringArray(R.array.dinput_mapper_type_entries).toList()

        val startupArr = context.resources.getStringArray(R.array.startup_selection_entries).toList()
        state.startupSelectionEntries.value = startupArr
        state.selectedStartupSelection.intValue =
            (c?.getStartupSelection()?.toInt() ?: Container.STARTUP_SELECTION_ESSENTIAL.toInt())
                .coerceIn(0, startupArr.size - 1)

        val desktopThemeArr =
            context.resources.getStringArray(R.array.desktop_theme_entries).toList()
        state.desktopThemeEntries.value = desktopThemeArr
        val savedDesktopTheme = c?.getDesktopTheme() ?: WineThemeManager.DEFAULT_DESKTOP_THEME
        val themeInfo = WineThemeManager.ThemeInfo(savedDesktopTheme)
        state.selectedDesktopTheme.intValue =
            themeInfo.theme.ordinal.coerceIn(0, (desktopThemeArr.size - 1).coerceAtLeast(0))

        val bgTypeArr =
            context.resources.getStringArray(R.array.desktop_background_type_entries).toList()
        state.desktopBackgroundTypeEntries.value = bgTypeArr
        state.selectedDesktopBackgroundType.intValue =
            themeInfo.backgroundType.ordinal.coerceIn(0, (bgTypeArr.size - 1).coerceAtLeast(0))

        state.desktopWallpaperSelected.value =
            WineThemeManager.getUserWallpaperFile(context).isFile

        val mouseWarpArr = listOf(
            context.getString(R.string.common_ui_disable),
            context.getString(R.string.common_ui_enable),
            context.getString(R.string.common_ui_force)
        )
        state.mouseWarpOverrideEntries.value = mouseWarpArr
        state.selectedMouseWarpOverride.intValue = when (pendingMouseWarpValue) {
            "enable" -> 1
            "force" -> 2
            else -> 0
        }

        loadBox64Presets()
        loadFexcorePresets()
        loadGraphicsDriverConfigState()
        // DXVK/WineD3D loaders iterate contents profiles; deferred to
        // populateContentsDependentData() after contentsManager.syncContents.
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
        val c = container

        // Wine version list depends on content profile enumeration.
        val versions = mutableListOf<String>()
        val identifiers = mutableListOf<String>()
        for (ver in context.resources.getStringArray(R.array.wine_entries)) {
            versions.add(ver)
            identifiers.add(ver)
        }
        for (profile in contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE)) {
            val name = ContentsManager.getEntryName(profile)
            versions.add(name)
            identifiers.add(name)
        }
        for (profile in contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            val name = ContentsManager.getEntryName(profile)
            versions.add(name)
            identifiers.add(name)
        }
        state.wineVersionEntries.value = versions
        wineVersionIdentifiers = identifiers

        // For existing containers, use their saved wine version.
        // For new containers, use the first actually-installed version from the
        // populated list instead of the hardcoded MAIN_WINE_VERSION default.
        // This ensures that if only an arm64ec Proton is installed, the dialog
        // correctly shows FEXCore/WowBox64 options instead of Box64.
        val currentWine: String
        if (c != null) {
            currentWine = c.getWineVersion()
        } else if (identifiers.isNotEmpty()) {
            currentWine = identifiers[0]
        } else {
            currentWine = WineInfo.MAIN_WINE_VERSION.identifier()
        }
        val idx = identifiers.indexOfFirst { it == currentWine }
        state.selectedWineVersion.intValue = if (idx >= 0) idx else 0

        // Resolve the actual wine info for the selected version and detect
        // architecture changes from the initial default so emulator lists
        // and presets are rebuilt correctly.
        val selectedIdentifier = if (idx >= 0) identifiers[idx]
            else identifiers.getOrElse(0) { currentWine }
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, selectedIdentifier)
        val archChanged = isArm64EC != wineInfo.isArm64EC()
        isArm64EC = wineInfo.isArm64EC()
        state.isArm64EC.value = isArm64EC
        state.wineVersionDisplay.value = formatWineVersionDisplay(wineInfo)
        applyDefaultContainerName(wineInfo, selectedIdentifier)

        rebuildEmulatorLists()
        // Always reset emulator selections when populating for the first time
        // or when the architecture changed from the initial default.
        if (c == null || archChanged) {
            selectByIdentifier(
                state.emulator32Entries.value,
                c?.getEmulator() ?: Container.DEFAULT_EMULATOR,
                state.selectedEmulator
            )
            selectByIdentifier(
                state.emulator64Entries.value,
                c?.getEmulator64() ?: Container.DEFAULT_EMULATOR64,
                state.selectedEmulator64
            )
        }

        loadBox64Versions()
        loadFexcoreVersions()
        loadBox64Presets()
        loadFexcorePresets()
        loadDxvkConfigState()
        loadWineD3DConfigState()
        updateEmulatorFrameVisibility()
    }

    private fun saveSettings() {
        val c = container
        val name = state.name.value.trim()
        if (name.isEmpty()) {
            WinToast.show(context, context.getString(R.string.common_ui_name_cannot_be_empty), dialog.window?.decorView)
            return
        }

        val screenSize = getScreenSizeFromState()
        val graphicsDriver = getIdentifierFromEntries(
            state.graphicsDriverEntries.value, state.selectedGraphicsDriver.intValue
        )
        var graphicsDriverConfig = buildGraphicsDriverConfigFromState()
        val parsedGfx = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(graphicsDriverConfig)
        if (parsedGfx.get("version").isNullOrEmpty()) {
            parsedGfx.put("version", "System")
            graphicsDriverConfig = GraphicsDriverConfigUtils.toGraphicsDriverConfig(parsedGfx)
        }
        val dxwrapper = getIdentifierFromEntries(
            state.dxWrapperEntries.value, state.selectedDxWrapper.intValue
        )
        val dxwrapperConfig = if (dxwrapper.contains("dxvk"))
            buildDxvkConfigFromState() else buildWineD3DConfigFromState()
        val audioDriver = getIdentifierFromEntries(
            state.audioDriverEntries.value, state.selectedAudioDriver.intValue
        )
        val emulator = getIdentifierFromEntries(
            state.emulator32Entries.value, state.selectedEmulator.intValue
        )
        val emulator64 = getIdentifierFromEntries(
            state.emulator64Entries.value, state.selectedEmulator64.intValue
        )

        val midiSoundFontEntries = state.midiSoundFontEntries.value
        val midiIdx = state.selectedMidiSoundFont.intValue
        val midiSoundFont =
            if (midiIdx <= 0 || midiIdx >= midiSoundFontEntries.size) ""
            else midiSoundFontEntries[midiIdx]

        val envVarsStr = buildEnvVarsString()
        val wincomponents = buildWinComponentsString()
        val drivesString = com.winlator.cmod.runtime.wine.WineUtils.normalizePersistentDrives(
            context,
            buildDrivesString(),
            false
        )

        val cpuList = buildCpuListString(state.cpuChecked.value)
        val cpuListWoW64 = buildCpuListString(state.cpuCheckedWoW64.value)

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

        val startupSelection = state.selectedStartupSelection.intValue.toByte()

        val box64VersionEntries = state.box64VersionEntries.value
        val box64Version = box64VersionEntries.getOrElse(state.selectedBox64Version.intValue) { "" }
        val box64Preset = box64PresetIds.getOrElse(state.selectedBox64Preset.intValue) { Box64Preset.COMPATIBILITY }

        val fexcoreVersionEntries = state.fexcoreVersionEntries.value
        val fexcoreVersion = fexcoreVersionEntries.getOrElse(state.selectedFexcoreVersion.intValue) { "" }
        val fexcorePreset = fexcorePresetIds.getOrElse(state.selectedFexcorePreset.intValue) { FEXCorePreset.COMPATIBILITY }

        val desktopTheme = buildDesktopThemeString()

        val selectedWineStr = resolveSelectedWineVersionIdentifier()

        if (c != null) {
            c.setName(name)
            c.setScreenSize(screenSize)
            c.setEnvVars(envVarsStr)
            c.setCPUList(cpuList)
            c.setCPUListWoW64(cpuListWoW64)
            c.setGraphicsDriver(graphicsDriver)
            c.setZinkMode(if (state.selectedZinkMode.intValue == 1) "windows" else "unix")
            c.setGraphicsDriverConfig(graphicsDriverConfig)
            c.setDXWrapper(dxwrapper)
            c.setDXWrapperConfig(dxwrapperConfig)
            c.putExtra("swapRB", if (state.selectedSurfaceEffect.intValue == 1) "1" else "0")
            c.putExtra("refreshRate", getRefreshRateFromState())
            writeFrameGenExtras(c)
            writeNetworkingExtras(c)
            run {
                // reshadeEffect stays coherent (= first effect) for legacy readers; all null when empty
                val loadoutJson = state.reshadeLoadout.loadoutJsonOrNull()
                c.putExtra(com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_LOADOUT, loadoutJson)
                c.putExtra(
                    com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_MODE,
                    if (loadoutJson == null) null else state.reshadeLoadout.mode)
                c.putExtra(
                    com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_PARAMS,
                    if (loadoutJson == null) null else state.reshadeLoadout.paramsJsonOrNull())
                c.putExtra(
                    com.winlator.cmod.runtime.reshade.ReshadeConfigWriter.EXTRA_EFFECT,
                    if (loadoutJson == null) null else state.reshadeLoadout.firstEffectName())
            }
            c.setAudioDriver(audioDriver)
            c.putExtra(DirectAudioDriver.EXTRA_MIC, if (state.directAudioMic.value) "1" else "0")
            c.setEmulator(emulator)
            c.setEmulator64(emulator64)
            c.setWinComponents(wincomponents)
            c.setDrives(drivesString)
            // an existing prefix owns the drive list, so write it there too
            if (java.io.File(c.rootDir, ".wine/dosdevices").isDirectory) {
                com.winlator.cmod.runtime.wine.WineUtils.applyDrivesToPrefix(c, drivesString)
            }
            c.setFullscreenStretched(state.fullscreenStretched.value)
            c.setUseUnixLibs(state.useUnixLibs.value)
            c.setInputType(finalInputType)
            c.setExclusiveXInput(state.containerExclusiveInput.value)
            c.setStartupSelection(startupSelection)
            c.setBox64Version(box64Version)
            c.setBox64Preset(box64Preset)
            c.setFEXCoreVersion(fexcoreVersion)
            c.setFEXCorePreset(fexcorePreset)
            c.setDesktopTheme(desktopTheme)
            c.setMidiSoundFont(midiSoundFont)
            c.setLC_ALL(state.lcAll.value)
            c.setExecArgs(state.execArgs.value)
            // Steam fields are intentionally not written — container edit UI
            // doesn't expose them, and saveData() round-trips the loaded
            // values from c's in-memory state.
            c.saveData()
            saveMouseWarpOverride(c)
            dismiss()
        } else {
            try {
                val data = ContainerCreation.buildLaunchReadyData(
                    context,
                    contentsManager,
                    name,
                    selectedWineStr,
                )
                data.put("name", name)
                data.put("screenSize", screenSize)
                data.put("envVars", envVarsStr)
                data.put("cpuList", cpuList)
                data.put("cpuListWoW64", cpuListWoW64)
                data.put("graphicsDriver", graphicsDriver)
                data.put("graphicsDriverConfig", graphicsDriverConfig)
                data.put("dxwrapper", dxwrapper)
                data.put("dxwrapperConfig", dxwrapperConfig)
                data.put("audioDriver", audioDriver)
                data.put("emulator", emulator)
                data.put("emulator64", emulator64)
                data.put("wincomponents", wincomponents)
                data.put("drives", drivesString)
                data.put("fullscreenStretched", state.fullscreenStretched.value)
                data.put("useUnixLibs", state.useUnixLibs.value)
                data.put("inputType", finalInputType)
                data.put("exclusiveXInput", state.containerExclusiveInput.value)
                data.put("startupSelection", startupSelection.toInt())
                data.put("box64Version", box64Version)
                data.put("box64Preset", box64Preset)
                data.put("fexcoreVersion", fexcoreVersion)
                data.put("fexcorePreset", fexcorePreset)
                data.put("desktopTheme", desktopTheme)
                data.put("wineVersion", selectedWineStr)
                data.put("midiSoundFont", midiSoundFont)
                data.put("lc_all", state.lcAll.value)
                data.put("execArgs", state.execArgs.value)

                creatingPopup.show()
                ImageFs.find(File(context.filesDir, "imagefs"))

                ContainerCreation.createContainerAsync(manager, contentsManager, data) { newContainer ->
                    if (newContainer != null) {
                        // Container.loadData() ignores unknown top-level keys, so extras must be set post-creation.
                        newContainer.putExtra(
                            "swapRB",
                            if (state.selectedSurfaceEffect.intValue == 1) "1" else "0"
                        )
                        getRefreshRateFromState()?.let { newContainer.putExtra("refreshRate", it) }
                        newContainer.putExtra(
                            DirectAudioDriver.EXTRA_MIC,
                            if (state.directAudioMic.value) "1" else "0"
                        )
                        writeFrameGenExtras(newContainer)
                        writeNetworkingExtras(newContainer)
                        newContainer.setZinkMode(if (state.selectedZinkMode.intValue == 1) "windows" else "unix")
                        newContainer.saveData()
                        saveMouseWarpOverride(newContainer)
                    } else {
                        WinToast.show(context, R.string.setup_wizard_unable_to_install_system_files)
                    }
                    creatingPopup.close()
                    dismiss()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error creating container", e)
                creatingPopup.close()
                WinToast.show(
                    context,
                    context.getString(R.string.common_ui_error_with_message, e.message ?: ""),
                )
            }
        }
    }

    private fun resolveSelectedWineVersionIdentifier(): String {
        wineVersionIdentifiers.getOrNull(state.selectedWineVersion.intValue)?.let { return it }

        // Profiles were already synced in loadContentsAsync(); query what's available
        // without re-scanning disk on the main thread.
        val installedProfiles = (
            contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE).orEmpty() +
                contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON).orEmpty()
            )
            .filter { it.isInstalled }
            .sortedWith(
                compareByDescending<ContentProfile> { it.verCode }
                    .thenByDescending { it.verName.lowercase(Locale.ROOT) }
            )

        return installedProfiles.firstOrNull()
            ?.let(ContentsManager::getEntryName)
            ?: WineInfo.MAIN_WINE_VERSION.identifier()
    }

    private fun writeFrameGenExtras(c: Container) {
        c.putExtra("frameGen", if (state.frameGenEnabled.value) "1" else "0")
        c.putExtra("frameGenMultiplier", state.frameGenMultiplier.intValue.coerceIn(2, 4).toString())
        c.putExtra("frameGenTargetRate", state.frameGenTargetRate.intValue.coerceAtLeast(0).toString())
        c.putExtra("frameGenFlowScale", state.frameGenFlowScale.intValue.coerceIn(25, 100).toString())
    }

    private fun writeNetworkingExtras(c: Container) {
        c.putExtra(
            NetworkingSettings.EXTRA_DRIVER,
            if (state.selectedNetDriver.intValue == 1) NetworkingSettings.DRIVER_NONE else NetworkingSettings.DRIVER_WINNATIVE
        )
        c.putExtra(NetworkingSettings.EXTRA_MAC, NetworkingSettings.normalizeMac(state.netMac.value))
    }

    private fun saveMouseWarpOverride(c: Container) {
        val rootDir = c.getRootDir() ?: return
        val userRegFile = File(rootDir, ".wine/user.reg")
        if (!userRegFile.exists()) return
        try {
            WineRegistryEditor(userRegFile).use { reg ->
                val value = when (state.selectedMouseWarpOverride.intValue) {
                    1 -> "enable"
                    2 -> "force"
                    else -> "disable"
                }
                reg.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", value)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error writing MouseWarpOverride", e)
        }
    }

    private fun loadMidiSoundFonts() {
        val tempSpinner = android.widget.Spinner(context)
        MidiManager.loadSFSpinner(tempSpinner)
        val adapter = tempSpinner.adapter
        val filesName = mutableListOf<String>()
        if (adapter != null) {
            for (i in 0 until adapter.count) filesName.add(adapter.getItem(i).toString())
        }
        state.midiSoundFontEntries.value = filesName
        val savedFont = container?.getMIDISoundFont() ?: ""
        state.selectedMidiSoundFont.intValue =
            if (savedFont.isEmpty()) 0 else filesName.indexOfFirst { it == savedFont }.coerceAtLeast(0)
    }

    private fun loadBox64Presets() {
        val presets = Box64PresetManager.getPresets("box64", context)
        val names = mutableListOf<String>()
        val ids = mutableListOf<String>()
        for (p in presets) {
            names.add(p.name); ids.add(p.id)
        }
        state.box64PresetEntries.value = names
        box64PresetIds = ids
        val saved = container?.getBox64Preset()
            ?: PreferenceManager.getDefaultSharedPreferences(context)
                .getString("box64_preset", Box64Preset.PERFORMANCE)
        val idx = ids.indexOfFirst { it == saved }
        state.selectedBox64Preset.intValue = if (idx >= 0) idx else 0
    }

    private fun loadFexcorePresets() {
        val presets = FEXCorePresetManager.getPresets(context)
        val names = mutableListOf<String>()
        val ids = mutableListOf<String>()
        for (p in presets) {
            names.add(p.name); ids.add(p.id)
        }
        state.fexcorePresetEntries.value = names
        fexcorePresetIds = ids
        val saved = container?.getFEXCorePreset()
            ?: PreferenceManager.getDefaultSharedPreferences(context)
                .getString("fexcore_preset", FEXCorePreset.PERFORMANCE_TSO)
        val idx = ids.indexOfFirst { it == saved }
        state.selectedFexcorePreset.intValue = if (idx >= 0) idx else 0
    }

    private fun loadBox64Versions() {
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
        val current = container?.getBox64Version() ?: ""
        selectByValue(itemList, current, state.selectedBox64Version)
        updateEmulatorFrameVisibility()
    }

    private fun loadFexcoreVersions() {
        val items = mutableListOf<String>()
        items.addAll(context.resources.getStringArray(R.array.fexcore_version_entries))
        for (profile in contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)) {
            val entryName = ContentsManager.getEntryName(profile)
            val firstDash = entryName.indexOf('-')
            if (firstDash >= 0) items.add(entryName.substring(firstDash + 1))
        }
        state.fexcoreVersionEntries.value = items
        val saved = container?.getFEXCoreVersion() ?: ""
        selectByValue(items, saved, state.selectedFexcoreVersion)
    }

    private fun updateEmulatorFrameVisibility() {
        val e32 = state.emulator32Entries.value.getOrNull(state.selectedEmulator.intValue)
            ?.let { StringUtils.parseIdentifier(it) } ?: ""
        val e64 = state.emulator64Entries.value.getOrNull(state.selectedEmulator64.intValue)
            ?.let { StringUtils.parseIdentifier(it) } ?: ""
        val usesWowbox64 = e32.equals("wowbox64", true) || e64.equals("wowbox64", true)
        state.showBox64Frame.value =
            e32.equals("box64", true) || e64.equals("box64", true) || usesWowbox64
        state.showFexcoreFrame.value =
            e32.equals("fexcore", true) || e64.equals("fexcore", true)
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

    private fun applyDefaultContainerName(
        wineInfo: WineInfo,
        wineVersionIdentifier: String? = null,
        force: Boolean = false,
    ) {
        if (container != null) return

        val currentName = state.name.value.trim()
        val defaultName =
            wineVersionIdentifier
                ?.let { ContainerCreation.displayNameForWineVersion(context, contentsManager, it) }
                ?: wineInfo.toString()
        val uniqueDefault = ContainerCreation.uniqueName(manager, defaultName)
        if (force || currentName.isEmpty() || currentName == autogeneratedContainerName) {
            state.name.value = uniqueDefault
            autogeneratedContainerName = uniqueDefault
        }
    }

    private fun audioDriverEntriesFor(wineVersion: String?): List<String> {
        val all = context.resources.getStringArray(R.array.audio_driver_entries).toList()
        if (wineVersion == null || DirectAudioDriver.isSupportedFor(wineVersion)) return all
        return all.filter { !it.equals("DirectAudio", true) }
    }

    private fun rebuildAudioDriverList(wineVersion: String?) {
        val current = state.audioDriverEntries.value.getOrNull(state.selectedAudioDriver.intValue)
        val entries = audioDriverEntriesFor(wineVersion)
        state.audioDriverEntries.value = entries
        selectByIdentifier(entries, current ?: Container.DEFAULT_AUDIO_DRIVER, state.selectedAudioDriver)
    }

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

    private fun loadGraphicsDriverConfigState() {
        val configStr = container?.getGraphicsDriverConfig() ?: Container.DEFAULT_GRAPHICSDRIVERCONFIG
        val config = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(configStr)

        state.gfxVulkanVersionEntries.value =
            context.resources.getStringArray(R.array.vulkan_version_entries).toList()
        state.gfxMaxDeviceMemoryEntries.value =
            context.resources.getStringArray(R.array.device_memory_entries).toList()
        state.gfxPresentModeEntries.value =
            context.resources.getStringArray(R.array.present_mode_entries).toList()
        state.gfxCompositorPresentModeEntries.value =
            context.resources.getStringArray(R.array.compositor_present_mode_entries).toList()
        state.gfxResourceTypeEntries.value =
            context.resources.getStringArray(R.array.resource_type_entries).toList()
        state.gfxBcnEmulationEntries.value =
            context.resources.getStringArray(R.array.bcn_emulation_entries).toList()
        state.gfxBcnEmulationTypeEntries.value =
            context.resources.getStringArray(R.array.bcn_emulation_type_entries).toList()
        state.gfxBcnEmulationCacheEntries.value =
            context.resources.getStringArray(R.array.bcn_emulation_cache_entries).toList()
        state.gfxTranscoderEntries.value =
            context.resources.getStringArray(R.array.wrapper_transcoder_entries).toList()
        state.gfxQualityEntries.value =
            context.resources.getStringArray(R.array.wrapper_quality_entries).toList()

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

        loadGraphicsDriverVersions()

        selectByValue(state.gfxVulkanVersionEntries.value, config.get("vulkanVersion") ?: "1.4", state.gfxSelectedVulkanVersion)
        selectByValue(state.gfxGpuNameEntries.value, config.get("gpuName") ?: "Device", state.gfxSelectedGpuName)
        selectByNumber(state.gfxMaxDeviceMemoryEntries.value, config.get("maxDeviceMemory") ?: "0", state.gfxSelectedMaxDeviceMemory)
        selectByValue(state.gfxPresentModeEntries.value, config.get("presentMode") ?: "mailbox", state.gfxSelectedPresentMode)
        selectByValue(state.gfxCompositorPresentModeEntries.value, config.get("compositorPresentMode") ?: "fifo", state.gfxSelectedCompositorPresentMode)
        selectByValue(state.gfxResourceTypeEntries.value, config.get("resourceType") ?: "auto", state.gfxSelectedResourceType)
        selectByValue(state.gfxBcnEmulationEntries.value, config.get("bcnEmulation") ?: "none", state.gfxSelectedBcnEmulation)
        selectByValue(state.gfxBcnEmulationTypeEntries.value, config.get("bcnEmulationType") ?: "compute", state.gfxSelectedBcnEmulationType)
        selectByValue(state.gfxBcnEmulationCacheEntries.value, config.get("bcnEmulationCache") ?: "0", state.gfxSelectedBcnEmulationCache)
        selectByValue(state.gfxTranscoderEntries.value, config.get("transcoder") ?: "cpu", state.gfxSelectedTranscoder)
        selectByValue(state.gfxQualityEntries.value, config.get("quality") ?: "low", state.gfxSelectedQuality)
        state.gfxSyncFrame.value = config.get("syncFrame") == "1"
        state.gfxDisablePresentWait.value = config.get("disablePresentWait") == "1"
        state.graphicsDriverVersion.value = config.get("version") ?: ""
    }

    private fun loadGraphicsDriverVersions() {
        val versions = mutableListOf<String>()
        try {
            val defaults = context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries)
            for (ver in defaults) {
                try {
                    if (com.winlator.cmod.runtime.system.GPUInformation.isDriverSupported(ver, context))
                        versions.add(ver)
                } catch (e: Throwable) {
                    Log.w(TAG, "Driver support check failed: $ver", e)
                }
            }
            try {
                val adreno = com.winlator.cmod.runtime.content.AdrenotoolsManager(context)
                val installed = adreno.enumarateInstalledDrivers()
                if (installed != null) versions.addAll(installed)
            } catch (e: Throwable) {
                Log.w(TAG, "Error loading Adrenotools drivers", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading wrapper versions", e)
        }
        if (versions.isEmpty()) versions.add("System")
        state.gfxDriverVersionEntries.value = versions

        val configStr2 = container?.getGraphicsDriverConfig() ?: Container.DEFAULT_GRAPHICSDRIVERCONFIG
        val config = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(configStr2)
        val initialVersion = config.get("version") ?: ""
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
                val configStr = container?.getGraphicsDriverConfig() ?: Container.DEFAULT_GRAPHICSDRIVERCONFIG
                val config = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(configStr)
                val savedVersion = config.get("version") ?: ""
                if (version == savedVersion) {
                    val bl = config.get("blacklistedExtensions") ?: ""
                    state.gfxBlacklistedExtensions.value =
                        if (bl.isNotEmpty()) bl.split(",").toSet() else emptySet()
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

    private fun initialDxWrapperConfig(): String =
        container?.getDXWrapperConfig()
            ?: ContainerCreation.defaultDxWrapperConfig(contentsManager, isArm64EC)

    private fun loadDxvkConfigState() {
        val configStr = initialDxWrapperConfig()
        val config = DXVKConfigUtils.parseConfig(configStr)
        state.dxvkVkd3dFeatureLevelEntries.value = DXVKConfigUtils.VKD3D_FEATURE_LEVEL.toList()
        val ddrawWrapperItems =
            context.resources.getStringArray(R.array.ddrawrapper_entries).toMutableList()
        for (profile in contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_D7VK)) {
            ddrawWrapperItems.add(ContentsManager.getEntryName(profile))
        }
        if (!isArm64EC) ddrawWrapperItems.removeAll { it.contains("arm64ec") }
        state.dxvkDdrawWrapperEntries.value = ddrawWrapperItems
        loadDxvkVersions()
        loadVkd3dVersions()
        selectByIdentifier(state.dxvkVkd3dFeatureLevelEntries.value, config.get("vkd3dLevel"), state.dxvkSelectedVkd3dFeatureLevel)
        selectByIdentifier(state.dxvkDdrawWrapperEntries.value, config.get("ddrawrapper"), state.dxvkSelectedDdrawWrapper)
        state.dxvkAsync.value = config.get("async") == "1"
        state.dxvkAsyncCache.value = config.get("asyncCache") == "1"
    }

    private fun loadDxvkVersions() {
        val originalItems = context.resources.getStringArray(R.array.dxvk_version_entries).toMutableList()
        for (profile in contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            val entryName = ContentsManager.getEntryName(profile)
            val firstDash = entryName.indexOf('-')
            originalItems.add(entryName.substring(firstDash + 1))
        }
        if (!isArm64EC) originalItems.removeAll { it.contains("arm64ec") }
        state.dxvkVersionEntries.value = originalItems
        val configStr = initialDxWrapperConfig()
        val config = DXVKConfigUtils.parseConfig(configStr)
        selectByIdentifier(originalItems, config.get("version"), state.dxvkSelectedVersion)
    }

    private fun loadVkd3dVersions() {
        val items = mutableListOf<String>()
        items.addAll(context.resources.getStringArray(R.array.vkd3d_version_entries))
        for (profile in contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            items.add(profile.verName + "-" + profile.verCode)
        }
        state.dxvkVkd3dVersionEntries.value = items
        val configStr = initialDxWrapperConfig()
        val config = DXVKConfigUtils.parseConfig(configStr)
        selectByIdentifier(items, config.get("vkd3dVersion"), state.dxvkSelectedVkd3dVersion)
    }

    private fun loadWineD3DConfigState() {
        val configStr = container?.getDXWrapperConfig() ?: Container.DEFAULT_DXWRAPPERCONFIG
        val config = WineD3DConfigUtils.parseConfig(configStr)
        state.wined3dVideoMemorySizeEntries.value =
            context.resources.getStringArray(R.array.video_memory_size_entries).toList()

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
        val bcnEmulation = state.gfxBcnEmulationEntries.value.getOrElse(state.gfxSelectedBcnEmulation.intValue) { "none" }
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

    private fun handleDxvkVkd3dVersionChanged(versionIndex: Int) {
        val vkd3dEntries = state.dxvkVkd3dVersionEntries.value
        val selectedVkd3d = if (versionIndex in vkd3dEntries.indices) vkd3dEntries[versionIndex] else "None"
        if (selectedVkd3d != "None") {
            val allVersions = state.dxvkVersionEntries.value
            val semver = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
            val filtered = allVersions.filter { v ->
                val match = semver.find(v)
                if (match != null) (match.groupValues[1].toIntOrNull() ?: 0) >= 2 else true
            }
            state.dxvkVersionEntries.value = filtered
            val currentDxvk = filtered.getOrElse(state.dxvkSelectedVersion.intValue) { "" }
            val curMajor = semver.find(currentDxvk)?.groupValues?.get(1)?.toIntOrNull()
            if (curMajor != null && curMajor >= 2) {
                selectByIdentifier(filtered, currentDxvk, state.dxvkSelectedVersion)
            } else {
                selectByIdentifier(filtered, "", state.dxvkSelectedVersion)
            }
        } else {
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

    private fun selectScreenSize(screenSize: String) {
        val entries = state.screenSizeEntries.value
        val idx = entries.indexOfFirst {
            StringUtils.parseIdentifier(it) == StringUtils.parseIdentifier(screenSize)
        }
        if (idx >= 0) {
            state.selectedScreenSize.intValue = idx
        } else {
            state.selectedScreenSize.intValue = 0 // "Custom"
            val parts = screenSize.split("x")
            if (parts.size == 2) {
                state.customWidth.value = parts[0]
                state.customHeight.value = parts[1]
            }
        }
    }

    /** Selected rate, or null for "Default" — stored as key absence so it falls through to the phone's auto rate. */
    private fun getRefreshRateFromState(): String? {
        val entries = state.refreshRateEntries.value
        val idx = state.selectedRefreshRate.intValue
        if (idx !in entries.indices) return null
        val rate = RefreshRateUtils.parseRefreshRateLabel(entries[idx])
        return if (rate > 0) rate.toString() else null
    }

    private fun getScreenSizeFromState(): String {
        val entries = state.screenSizeEntries.value
        val idx = state.selectedScreenSize.intValue
        if (idx !in entries.indices) return Container.DEFAULT_SCREEN_SIZE
        val selectedValue = entries[idx]
        return if (selectedValue.equals("custom", ignoreCase = true)) {
            val w = state.customWidth.value.trim()
            val h = state.customHeight.value.trim()
            if (w.matches(Regex("[0-9]+")) && h.matches(Regex("[0-9]+"))) {
                val width = (w.toInt() / 2) * 2
                val height = (h.toInt() / 2) * 2
                "${width}x${height}"
            } else Container.DEFAULT_SCREEN_SIZE
        } else StringUtils.parseIdentifier(selectedValue)
    }

    private fun buildWinComponentsString(): String {
        val parts = mutableListOf<String>()
        for (comp in state.directXComponents.value) parts.add("${comp.key}=${comp.selectedIndex}")
        for (comp in state.generalComponents.value) parts.add("${comp.key}=${comp.selectedIndex}")
        return parts.joinToString(",")
    }

    private fun buildEnvVarsString(): String {
        val filtered = state.envVars.value
            .filter { it.key.isNotBlank() }
            .filterNot { it.key in SDL2_KEYS }
        val merged = if (state.sdl2Compatibility.value) {
            filtered + SDL2_ENV_VARS.map { EnvVarItem(it.first, it.second) }
        } else filtered
        return merged.joinToString(" ") { "${it.key}=${it.value}" }
    }

    // Always emit the enumerated list. An empty string means "no override" at
    // the Container layer and would fall back to getFallbackCPUList* — the
    // WoW64 fallback is only upper-half cores, so saving "all checked" on the
    // 32-bit chip row would silently reopen as half the cores.
    private fun buildCpuListString(checked: List<Boolean>): String {
        return checked.mapIndexedNotNull { i, c -> if (c) "$i" else null }.joinToString(",")
    }

    private fun buildDrivesString(): String {
        val sb = StringBuilder()
        for (drive in drivesWorking) {
            if (drive.path.isNotBlank()) sb.append(drive.letter).append(":").append(drive.path)
        }
        return sb.toString()
    }

    private fun buildDesktopThemeString(): String {
        val themeEntries = state.desktopThemeEntries.value
        val themeIdx = state.selectedDesktopTheme.intValue
        val theme = if (themeIdx in themeEntries.indices) themeEntries[themeIdx].uppercase() else "LIGHT"

        val typeIdx = state.selectedDesktopBackgroundType.intValue
        val type = WineThemeManager.BackgroundType.values().getOrNull(typeIdx)?.name ?: "IMAGE"

        val color = state.desktopBackgroundColor.value
        val base = "$theme,$type,$color"
        return if (type == "IMAGE") {
            val userWallpaperFile = WineThemeManager.getUserWallpaperFile(context)
            val stamp = if (userWallpaperFile.isFile) userWallpaperFile.lastModified().toString() else "0"
            "$base,$stamp"
        } else base
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

    private fun getIdentifierFromEntries(entries: List<String>, index: Int): String {
        return if (index in entries.indices) StringUtils.parseIdentifier(entries[index]) else ""
    }

    private fun selectByIdentifier(
        entries: List<String>,
        identifier: String?,
        target: androidx.compose.runtime.MutableIntState
    ) {
        if (identifier == null) {
            target.intValue = 0; return
        }
        val idx = entries.indexOfFirst { StringUtils.parseIdentifier(it) == identifier }
        target.intValue = if (idx >= 0) idx else 0
    }

    private fun selectByValue(
        entries: List<String>,
        value: String?,
        target: androidx.compose.runtime.MutableIntState
    ) {
        if (value == null) {
            target.intValue = 0; return
        }
        val idx = entries.indexOfFirst { it == value }
        target.intValue = if (idx >= 0) idx else 0
    }

    private fun selectByNumber(
        entries: List<String>,
        number: String?,
        target: androidx.compose.runtime.MutableIntState
    ) {
        if (number == null) {
            target.intValue = 0; return
        }
        val idx = entries.indexOfFirst { StringUtils.parseNumber(it) == number }
        target.intValue = if (idx >= 0) idx else 0
    }

    fun show() {
        dialog.show()
        restorePaneNav?.invoke()
        restorePaneNav = dialog.window?.bindPaneNav(
            PaneNavWindowHandlers(
                onDir = { nav.dpad(it) },
                onActivate = { nav.dpad(PANE_DIR_ACTIVATE) },
                onDismiss = { if (nav.onContentBack?.invoke() != true) dialog.dismiss() },
                onStart = { if (state.isLoaded.value) saveSettings() },
            )
        )
        dialog.window?.apply {
            applyDialogLayout()
            decorView.post { applyDialogLayout() }
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
        // the Dialog.OnDismissListener so back-button dismissal takes the
        // same path as Save/Cancel.
        dialog.dismiss()
    }

    private fun exportSaves(onComplete: () -> Unit) {
        val container = this.container ?: run {
            onComplete()
            return
        }
        val sanitizedName = container.name.replace(" ", "_")
        val date = SimpleDateFormat("MMddyyyy_HHmmss", Locale.US).format(Date())
        val zipName = "${sanitizedName}_${date}.zip"
        val exportDir = File(Environment.getExternalStorageDirectory(), "WinNative/saves")
        if (!exportDir.exists()) exportDir.mkdirs()
        val zipFile = File(exportDir, zipName)

        scope.launch(Dispatchers.IO) {
            try {
                zipFile.outputStream().use { os ->
                    java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(os)).use { zos ->
                        val usersDir = File(container.getRootDir(), ".wine/drive_c/users")
                        if (usersDir.exists()) {
                            zos.putNextEntry(java.util.zip.ZipEntry("${usersDir.name}/"))
                            zos.closeEntry()
                            zipDir(usersDir, usersDir.name, zos)
                        }

                        val programDataDir = File(container.getRootDir(), ".wine/drive_c/ProgramData")
                        if (programDataDir.exists()) {
                            zos.putNextEntry(java.util.zip.ZipEntry("${programDataDir.name}/"))
                            zos.closeEntry()
                            zipDir(programDataDir, programDataDir.name, zos)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    onComplete()
                    WinToast.show(context, context.getString(R.string.saves_export_success_path, "/WinNative/saves"), dialog.window?.decorView)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onComplete()
                    WinToast.show(context, context.getString(R.string.saves_import_export_exported_failed, e.message), dialog.window?.decorView)
                }
            }
        }
    }

    private fun isExcluded(name: String): Boolean {
        if (name.isEmpty()) return false
        val excludedFolders = setOf(
            "Contacts", "Desktop", "Downloads", "Favorites", "Links",
            "Music", "Pictures", "Searches", "Temp", "Videos", "Package Cache", "Templates"
        )
        if (excludedFolders.any { it.equals(name, ignoreCase = true) }) return true
        if (name.contains("Microsoft", ignoreCase = true)) return true
        if (name.contains("Windows", ignoreCase = true)) return true
        return false
    }

    private fun isPathExcluded(path: String): Boolean {
        return path.split('/').any { isExcluded(it) }
    }

    private fun zipDir(
        dir: File,
        baseName: String,
        zos: java.util.zip.ZipOutputStream,
    ) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (isExcluded(child.name)) continue
            val name = if (baseName.isEmpty()) child.name else "$baseName/${child.name}"
            if (child.isDirectory) {
                zos.putNextEntry(java.util.zip.ZipEntry("$name/"))
                zos.closeEntry()
                zipDir(child, name, zos)
            } else {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                child.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun showExportSavesConfirmation() {
        showSavesPopupConfirmation(
            title = context.getString(R.string.saves_export_warning_title),
            message = context.getString(R.string.saves_export_warning_body),
            confirmLabel = context.getString(R.string.common_ui_export),
            progressLabel = context.getString(R.string.common_ui_exporting),
        ) { dismiss ->
            exportSaves(onComplete = dismiss)
        }
    }

    private fun showImportSavesConfirmation() {
        showSavesPopupConfirmation(
            title = context.getString(R.string.saves_import_warning_title),
            message = context.getString(R.string.saves_import_warning_body),
            confirmLabel = context.getString(R.string.common_ui_import),
        ) { dismiss ->
            dismiss()
            val imagefsRoot = ImageFs.find(context).getRootDir()
            val driveRoots =
                listOf(
                    DirectoryPickerDialog.ManagedRoot("C:", File(imagefsRoot, "home").absolutePath),
                    DirectoryPickerDialog.ManagedRoot("Z:", imagefsRoot.absolutePath),
                    DirectoryPickerDialog.ManagedRoot(
                        "D:",
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
                    ),
                    DirectoryPickerDialog.ManagedRoot("Internal", Environment.getExternalStorageDirectory().absolutePath),
                )
            DirectoryPickerDialog.showFile(
                activity,
                title = context.getString(R.string.common_ui_import),
                allowedExtensions = setOf("zip"),
                extraRoots = driveRoots,
            ) { pickedPath ->
                importSaves(File(pickedPath))
            }
        }
    }

    private fun showSavesPopupConfirmation(
        title: String,
        message: String,
        confirmLabel: String,
        progressLabel: String? = null,
        onConfirm: (dismiss: () -> Unit) -> Unit,
    ) {
        val dialog = AppCompatDialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        val dismiss = { dialog.dismiss() }
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                WinNativeTheme {
                    PopupDialog(
                        title = title,
                        message = message,
                        confirmLabel = confirmLabel,
                        progressLabel = progressLabel,
                        modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                        onConfirm = { onConfirm(dismiss) },
                        onCancel = dismiss,
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.show()
    }

    private fun importSaves(zipFile: File) {
        val container = this.container ?: return
        scope.launch(Dispatchers.IO) {
            val loadingDialog = withContext(Dispatchers.Main) {
                WinNativeComposeDialogs.showLoading(context, context.getString(R.string.common_ui_loading))
            }
            try {
                java.util.zip.ZipFile(zipFile).use { zf ->
                    val usersDir = File(container.getRootDir(), ".wine/drive_c/users")
                    val xuserDir = File(usersDir, "xuser")

                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name

                        if (isPathExcluded(name)) continue

                        var destFile: File? = null

                        if (name.startsWith("users/")) {
                            destFile = File(usersDir.parentFile, name)
                        } else if (name.startsWith("ProgramData/")) {
                            destFile = File(usersDir.parentFile, name)
                        } else if (name.startsWith("xuser/")) {
                            destFile = File(usersDir, name)
                        } else if (name.startsWith("Documents/") || name.startsWith("Saved Games/") || name.startsWith("AppData/")) {
                            destFile = File(xuserDir, name)
                        }

                        if (destFile != null) {
                            if (entry.isDirectory) {
                                destFile.mkdirs()
                            } else {
                                destFile.parentFile?.mkdirs()
                                zf.getInputStream(entry).use { input ->
                                    destFile.outputStream().use { input.copyTo(it) }
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    loadingDialog?.dismiss()
                    WinToast.show(context, R.string.saves_import_export_imported, dialog.window?.decorView)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    loadingDialog?.dismiss()
                    WinToast.show(context, context.getString(R.string.saves_import_export_imported_failed, e.message), dialog.window?.decorView)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ContainerSettingsCompose"

        private val SDL2_ENV_VARS = listOf(
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
        private val SDL2_KEYS = SDL2_ENV_VARS.map { it.first }.toSet()
        private val SELECTABLE_DRIVE_LETTERS =
            ('D'..'Y').filter { it != 'E' }.map { "$it" }
    }
}
