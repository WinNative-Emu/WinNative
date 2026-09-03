package com.winlator.cmod.feature.library
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Speed
import android.os.Environment
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.scale
import com.winlator.cmod.R
import com.winlator.cmod.runtime.reshade.ReshadeCatalog
import com.winlator.cmod.runtime.reshade.ReshadeCatalogEntry
import com.winlator.cmod.runtime.reshade.ReshadeDownloader
import com.winlator.cmod.runtime.reshade.ReshadeLoadout
import com.winlator.cmod.runtime.reshade.ReshadeManager
import com.winlator.cmod.shared.framegen.FrameGenPreset
import com.winlator.cmod.shared.theme.GameSettingsStyle
import com.winlator.cmod.runtime.wine.WineThemeManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.lsfg.LosslessScaling
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.ui.dialog.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.winlator.cmod.shared.ui.focus.controllerFocusBorder
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerSliderEscape
import com.winlator.cmod.shared.ui.focus.controllerTextFieldEscape
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.PANE_DIR_ACTIVATE
import com.winlator.cmod.shared.ui.nav.PANE_DIR_DOWN
import com.winlator.cmod.shared.ui.nav.PANE_DIR_LEFT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_RIGHT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_UP
import com.winlator.cmod.shared.ui.nav.paneHighlight
import com.winlator.cmod.shared.ui.nav.paneNavItem
import com.winlator.cmod.shared.ui.widget.EnvVarsView
import com.winlator.cmod.shared.ui.widget.chasingBorder
import java.io.File
import kotlin.math.roundToInt

private val BgDeep = GameSettingsStyle.BgDeep
private val SidebarBg = GameSettingsStyle.SidebarBg
private val ContentBg = GameSettingsStyle.ContentBg
private val CardSurface = GameSettingsStyle.CardSurface
private val CardBorder = GameSettingsStyle.CardBorder
private val InputSurface = GameSettingsStyle.InputSurface
private val InputBorder = GameSettingsStyle.InputBorder
private val AccentBlue = GameSettingsStyle.AccentBlue
private val TextPrimary = GameSettingsStyle.TextPrimary
private val TextSecondary = GameSettingsStyle.TextSecondary
private val TextDim = GameSettingsStyle.TextDim
private val DividerColor = GameSettingsStyle.Divider
private val CheckBorder = GameSettingsStyle.CheckBorder
private val SliderInactive = GameSettingsStyle.SliderInactive
private val ChipSurface = GameSettingsStyle.ChipSurface
private val ChipBorder = GameSettingsStyle.ChipBorder
private val DangerRed = GameSettingsStyle.DangerRed
private val WarningAmber = GameSettingsStyle.WarningAmber
private val SelectableDriveLetters = ('D'..'Y').filter { it != 'E' }.map { "$it" }
private val NavHighlight = GameSettingsStyle.NavHighlight

@Stable
class GameSettingsNav {
    var active by mutableStateOf(false)
    var inContent by mutableStateOf(false)
    var sidebarIndex by mutableStateOf(0)
    var sidebarCount by mutableStateOf(0)
    var actionCol by mutableStateOf(0)
    var contentSignal by mutableStateOf(0)
        private set
    var contentDir by mutableStateOf(0)
        private set
    var contentResetSignal by mutableStateOf(0)
        private set
    var onSelectSection: ((Int) -> Unit)? = null
    var onSave: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onContentBack: (() -> Boolean)? = null

    val onActionRow: Boolean get() = sidebarIndex >= sidebarCount

    private fun pushContent(dir: Int) {
        contentDir = dir
        contentSignal++
    }

    fun dpad(dir: Int) {
        if (!active) {
            active = true
            return
        }
        if (inContent) {
            pushContent(dir)
            return
        }
        when (dir) {
            PANE_DIR_UP -> moveSidebar(-1)
            PANE_DIR_DOWN -> moveSidebar(1)
            PANE_DIR_LEFT -> if (onActionRow && actionCol == 1) actionCol = 0
            PANE_DIR_RIGHT ->
                if (onActionRow) {
                    if (actionCol == 0) actionCol = 1
                } else {
                    enterContent()
                }
            PANE_DIR_ACTIVATE ->
                if (onActionRow) {
                    if (actionCol == 0) onCancel?.invoke() else onSave?.invoke()
                } else {
                    enterContent()
                }
        }
    }

    private fun moveSidebar(delta: Int) {
        val next = (sidebarIndex + delta).coerceIn(0, sidebarCount)
        sidebarIndex = next
        if (next < sidebarCount) onSelectSection?.invoke(next)
    }

    fun enterContent() {
        inContent = true
        contentResetSignal++
    }

    fun exitToSidebar() {
        inContent = false
    }

    fun tapSection(index: Int) {
        active = false
        inContent = false
        sidebarIndex = index
        onSelectSection?.invoke(index)
    }

    fun tapAction(col: Int) {
        active = false
        inContent = false
        sidebarIndex = sidebarCount
        actionCol = col
    }

    fun tapContent() {
        active = false
        inContent = true
    }
}

private val SettingGroupCorner = 12.dp
private val SettingGroupPadding = 12.dp
private val SettingFieldCorner = 8.dp
private val SettingFieldHorizontalPadding = 12.dp
private val SettingFieldVerticalPadding = 8.dp
private val EnvVarControlHeight = 36.dp
private val SettingItemGap = 10.dp
private val SettingSectionGap = 12.dp
private val SettingTightGap = 4.dp
private val SettingIconSize = 18.dp
private val SettingControlIconSize = 16.dp
private val SettingSliderHeight = 24.dp

private fun graphicsCardExpandEnter() =
    fadeIn(tween(200)) +
        expandVertically(
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
        )

private fun graphicsCardExpandExit() =
    fadeOut(tween(140)) +
        shrinkVertically(
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
        )
private val SettingSliderThumbSize = 18.dp
private const val SettingSliderTrackScaleY = 0.5f
private val SettingLabelSize = 11.sp
private val SettingLabelRowHeight = 22.dp
private val SettingValueSize = 12.sp
private val SettingSectionLabelSize = 12.sp
private val SmartDropdownPressStartInset = 28.dp

@Composable
private fun rememberSmartDropdownOffset(): MutableState<DpOffset> =
    remember { mutableStateOf(DpOffset.Zero) }

@Composable
private fun Modifier.smartDropdownAnchor(
    enabled: Boolean = true,
    offset: MutableState<DpOffset>,
    onOpen: () -> Unit,
): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .onKeyEvent { e ->
            if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                (
                    e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                )
            ) {
                offset.value = DpOffset.Zero
                onOpen()
                true
            } else {
                false
            }
        }.focusable(interactionSource = interactionSource)
        .pointerInput(enabled, density, onOpen) {
            detectTapGestures { tapOffset ->
                offset.value =
                    with(density) {
                        val tapX = tapOffset.x.toDp()
                        DpOffset(
                            if (tapX > SmartDropdownPressStartInset) tapX - SmartDropdownPressStartInset else 0.dp,
                            0.dp,
                        )
                    }
                onOpen()
            }
        }
        .paneNavItem(
            cornerRadius = SettingFieldCorner,
            onActivate = { offset.value = DpOffset.Zero; onOpen() },
            highlightColor = NavHighlight,
            tapToSelect = true,
        )
}

data class WinComponentItem(val key: String, val label: String, val selectedIndex: Int)
data class EnvVarItem(val key: String, val value: String)

// Row-preserving parse: duplicate names stay as separate rows (EnvVars would collapse them into a map).
fun parseEnvVarItems(envVarsStr: String?): List<EnvVarItem> =
    envVarsStr.orEmpty().split(" ").mapNotNull { part ->
        val index = part.indexOf('=')
        if (index <= 0) null else EnvVarItem(part.substring(0, index), part.substring(index + 1))
    }
data class ExtraArgGroup(val header: String, val args: List<String>)
data class DriveItem(
    val letter: String,
    val path: String,
    val canChangeLetter: Boolean = false,
)

class GameSettingsStateHolder {
    val currentSection = mutableIntStateOf(0)

    // Container edits expose container-only fields and hide shortcut fields.
    val isContainerEditMode = mutableStateOf(false)
    val wineVersionEditable = mutableStateOf(false)

    val name = mutableStateOf("")
    val launchExePath = mutableStateOf("")
    val launchExeDisplayPath = mutableStateOf("")
    val containerEntries = mutableStateOf<List<String>>(emptyList())
    val selectedContainer = mutableIntStateOf(0)
    val screenSizeEntries = mutableStateOf<List<String>>(emptyList())
    val selectedScreenSize = mutableIntStateOf(0)
    val customWidth = mutableStateOf("")
    val customHeight = mutableStateOf("")
    val gameCardArtworkSelected = mutableStateOf(false)
    val gameCardArtworkSummary = mutableStateOf("")
    val iconArtworkSelected = mutableStateOf(false)
    val iconArtworkSummary = mutableStateOf("")
    val refreshRateEntries = mutableStateOf<List<String>>(emptyList())
    val selectedRefreshRate = mutableIntStateOf(0)
    val fpsLimit = mutableIntStateOf(0)

    // Display
    val graphicsDriverEntries = mutableStateOf<List<String>>(emptyList())
    val selectedGraphicsDriver = mutableIntStateOf(0)
    val isArm64EC = mutableStateOf(false)
    val zinkModeEntries = mutableStateOf<List<String>>(emptyList())
    val selectedZinkMode = mutableIntStateOf(0)
    val graphicsDriverVersion = mutableStateOf("")
    val dxWrapperEntries = mutableStateOf<List<String>>(emptyList())
    val selectedDxWrapper = mutableIntStateOf(0)
    val surfaceEffectEntries = mutableStateOf<List<String>>(emptyList())
    val selectedSurfaceEffect = mutableIntStateOf(0)
    val sgsrEnabled = mutableStateOf(false)
    val sgsrUpscaleMode = mutableIntStateOf(1)
    val sgsrSharpness = mutableIntStateOf(100)

    val frameGenEnabled = mutableStateOf(false)
    val frameGenMultiplier = mutableIntStateOf(2)
    val frameGenTargetRate = mutableIntStateOf(0)
    val frameGenFlowScale = mutableIntStateOf(70)
    val frameGenShaderState = mutableIntStateOf(FRAMEGEN_SHADERS_CHECKING)
    val frameGenSourceName = mutableStateOf("")

    // scanned drop-in pool + ordered loadout; saved as a reshadeLoadout array plus nested reshadeParams object.
    val reshadeEffects = mutableStateOf<List<ReshadeManager.ReshadeEffect>>(emptyList())
    val reshadeLoadout = ReshadeLoadoutState()

    // Graphics Driver Configuration (inline card)
    val gfxConfigExpanded = mutableStateOf(false)
    val gfxVulkanVersionEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedVulkanVersion = mutableIntStateOf(0)
    val gfxDriverVersionEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedDriverVersion = mutableIntStateOf(0)
    val gfxAvailableExtensions = mutableStateOf<List<String>>(emptyList())
    val gfxBlacklistedExtensions = mutableStateOf<Set<String>>(emptySet())
    val gfxGpuNameEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedGpuName = mutableIntStateOf(0)
    val gfxMaxDeviceMemoryEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedMaxDeviceMemory = mutableIntStateOf(0)
    val gfxPresentModeEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedPresentMode = mutableIntStateOf(0)
    val gfxCompositorPresentModeEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedCompositorPresentMode = mutableIntStateOf(0)
    val gfxResourceTypeEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedResourceType = mutableIntStateOf(0)
    val gfxBcnEmulationEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedBcnEmulation = mutableIntStateOf(0)
    val gfxBcnEmulationTypeEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedBcnEmulationType = mutableIntStateOf(0)
    val gfxBcnEmulationCacheEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedBcnEmulationCache = mutableIntStateOf(0)
    val gfxTranscoderEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedTranscoder = mutableIntStateOf(0)
    val gfxQualityEntries = mutableStateOf<List<String>>(emptyList())
    val gfxSelectedQuality = mutableIntStateOf(0)
    val gfxSyncFrame = mutableStateOf(false)
    val gfxDisablePresentWait = mutableStateOf(false)

    // DXVK Configuration (inline card)
    val dxvkConfigExpanded = mutableStateOf(false)
    val dxvkVkd3dVersionEntries = mutableStateOf<List<String>>(emptyList())
    val dxvkSelectedVkd3dVersion = mutableIntStateOf(0)
    val dxvkVkd3dFeatureLevelEntries = mutableStateOf<List<String>>(emptyList())
    val dxvkSelectedVkd3dFeatureLevel = mutableIntStateOf(0)
    val dxvkVersionEntries = mutableStateOf<List<String>>(emptyList())
    val dxvkSelectedVersion = mutableIntStateOf(0)
    val dxvkAsync = mutableStateOf(false)
    val dxvkAsyncCache = mutableStateOf(false)
    val dxvkDdrawWrapperEntries = mutableStateOf<List<String>>(emptyList())
    val dxvkSelectedDdrawWrapper = mutableIntStateOf(0)

    // WineD3D Configuration (inline card)
    val wined3dConfigExpanded = mutableStateOf(false)
    val wined3dCsmtEntries = mutableStateOf<List<String>>(emptyList())
    val wined3dSelectedCsmt = mutableIntStateOf(0)
    val wined3dGpuNameEntries = mutableStateOf<List<String>>(emptyList())
    val wined3dSelectedGpuName = mutableIntStateOf(0)
    val wined3dVideoMemorySizeEntries = mutableStateOf<List<String>>(emptyList())
    val wined3dSelectedVideoMemorySize = mutableIntStateOf(0)
    val wined3dStrictShaderMathEntries = mutableStateOf<List<String>>(emptyList())
    val wined3dSelectedStrictShaderMath = mutableIntStateOf(0)
    val wined3dOffscreenRenderingModeEntries = mutableStateOf(listOf("fbo", "backbuffer"))
    val wined3dSelectedOffscreenRenderingMode = mutableIntStateOf(0)
    val wined3dRendererEntries = mutableStateOf(listOf("gl", "vulkan", "gdi"))
    val wined3dSelectedRenderer = mutableIntStateOf(0)

    // Audio
    val audioDriverEntries = mutableStateOf<List<String>>(emptyList())
    val selectedAudioDriver = mutableIntStateOf(0)
    val midiSoundFontEntries = mutableStateOf<List<String>>(emptyList())
    val selectedMidiSoundFont = mutableIntStateOf(0)

    // Wine — emulator32/64Entries are arch-filtered views of emulatorEntries; selectedEmulator/selectedEmulator64 index into them.
    val emulatorEntries = mutableStateOf<List<String>>(emptyList())
    val emulator32Entries = mutableStateOf<List<String>>(emptyList())
    val emulator64Entries = mutableStateOf<List<String>>(emptyList())
    val selectedEmulator = mutableIntStateOf(0)
    val selectedEmulator64 = mutableIntStateOf(0)
    val wineVersionDisplay = mutableStateOf("")
    val emulatorsEnabled = mutableStateOf(true)
    val lcAll = mutableStateOf("")
    val localeOptions = mutableStateOf<List<String>>(emptyList())
    val desktopThemeEntries = mutableStateOf<List<String>>(emptyList())
    val selectedDesktopTheme = mutableIntStateOf(0)

    // Container-only fields. MouseWarpOverride is stored in .wine/user.reg.
    val wineVersionEntries = mutableStateOf<List<String>>(emptyList())
    val selectedWineVersion = mutableIntStateOf(0)
    val mouseWarpOverrideEntries = mutableStateOf<List<String>>(emptyList())
    val selectedMouseWarpOverride = mutableIntStateOf(0)
    val desktopBackgroundTypeEntries = mutableStateOf<List<String>>(emptyList())
    val selectedDesktopBackgroundType = mutableIntStateOf(0)
    val desktopBackgroundColor = mutableStateOf("#0277bd")
    val desktopWallpaperSelected = mutableStateOf(false)
    val drivesList = mutableStateOf<List<DriveItem>>(emptyList())
    val containerExclusiveInput = mutableStateOf(false)
    val shortcutExclusiveXInput = mutableStateOf(true)

    // Steam (visible only for Steam games)
    val isSteamGame = mutableStateOf(false)
    val steamLauncher = mutableStateOf(true)
    // Single toggle driving the ColdClient launcher + SteamStub DRM unpacking (persisted as "useColdClient" + "unpackFiles").
    val useLegacyLauncher = mutableStateOf(false)
    val useSteamInput = mutableStateOf(false)
    val steamOfflineMode = mutableStateOf(false)
    val runtimePatcher = mutableStateOf(false)

    // Components
    val winComponentEntries = mutableStateOf<List<String>>(emptyList())
    val directXComponents = mutableStateOf<List<WinComponentItem>>(emptyList())
    val generalComponents = mutableStateOf<List<WinComponentItem>>(emptyList())

    // Variables
    val envVars = mutableStateOf<List<EnvVarItem>>(emptyList())

    val controlsProfileEntries = mutableStateOf<List<String>>(emptyList())
    val selectedControlsProfile = mutableIntStateOf(0)
    val numControllersEntries = mutableStateOf<List<String>>(emptyList())
    val selectedNumControllers = mutableIntStateOf(0)
    val disableXInput = mutableStateOf(false)
    val simTouchScreen = mutableStateOf(false)
    val screenTouchMode = mutableIntStateOf(0)
    val gestureProfileEntries = mutableStateOf<List<String>>(emptyList())
    val gestureProfileIds = mutableStateOf<List<Int>>(emptyList())
    val selectedGestureProfile = mutableIntStateOf(0)
    val sdl2Compatibility = mutableStateOf(false)
    val enableXInput = mutableStateOf(false)
    val enableDInput = mutableStateOf(false)
    val dInputMapperTypeEntries = mutableStateOf<List<String>>(emptyList())
    val selectedDInputMapperType = mutableIntStateOf(0)

    // Advanced - Box64
    val showBox64Frame = mutableStateOf(false)
    val box64VersionEntries = mutableStateOf<List<String>>(emptyList())
    val selectedBox64Version = mutableIntStateOf(0)
    val box64PresetEntries = mutableStateOf<List<String>>(emptyList())
    val selectedBox64Preset = mutableIntStateOf(0)

    // Advanced - FEXCore
    val showFexcoreFrame = mutableStateOf(false)
    val fexcoreVersionEntries = mutableStateOf<List<String>>(emptyList())
    val selectedFexcoreVersion = mutableIntStateOf(0)
    val fexcorePresetEntries = mutableStateOf<List<String>>(emptyList())
    val selectedFexcorePreset = mutableIntStateOf(0)
    val useUnixLibs = mutableStateOf(true)

    // Advanced - System
    val startupSelectionEntries = mutableStateOf<List<String>>(emptyList())
    val selectedStartupSelection = mutableIntStateOf(0)
    val execArgs = mutableStateOf("")
    val fullscreenStretched = mutableStateOf(false)

    // Advanced - CPU
    val cpuCount = mutableIntStateOf(Runtime.getRuntime().availableProcessors())
    val cpuChecked = mutableStateOf<List<Boolean>>(
        List(Runtime.getRuntime().availableProcessors()) { true }
    )
    val cpuCheckedWoW64 = mutableStateOf<List<Boolean>>(
        List(Runtime.getRuntime().availableProcessors()) { true }
    )

    // Advanced - Drives
    val drives = mutableStateOf("")

    val isLoaded = mutableStateOf(false)
}

interface GameSettingsCallbacks {
    fun onConfirm()
    fun onDismiss()
    fun onAddToHomeScreen()

    fun onScrapeGameArtwork(gameName: String) {}
    fun onPickGameCardArtwork() {}
    fun onRemoveGameCardArtwork() {}
    fun onPickIconArtwork() {}
    fun onRemoveIconArtwork() {}
    fun onOpenArtworkSource(gameName: String) {}
    fun onRemoveEnvVar(index: Int)
    fun onUpdateWinComponent(isDirectX: Boolean, index: Int, newValue: Int)
    fun onSelectExe() {}
    fun onGfxDriverVersionChanged(versionIndex: Int) {}
    fun onDxvkVersionChanged(versionIndex: Int) {}
    fun onDxvkVkd3dVersionChanged(versionIndex: Int) {}
    fun onContainerChanged(containerIndex: Int) {}
    fun onEmulatorChanged() {}
    fun onWineVersionChanged(versionIndex: Int) {}
    fun onAddDrive() {}
    fun onDriveLetterChanged(index: Int, newLetter: String) {}
    fun onRemoveDrive(index: Int) {}
    fun onPickDrivePath(index: Int) {}
    fun onPickWallpaper() {}
    fun onExportSaves() {}
    fun onImportSaves() {}
}

private val ExtraArgPresets = listOf(
    ExtraArgGroup(
        "Unity", listOf(
            "-force-d3d9", "-force-d3d11", "-force-d3d12", "-force-vulkan",
            "-force-glcore", "-force-gfx-direct", "-force-d3d11-singlethreaded",
            "-screen-fullscreen 0", "-screen-fullscreen 1", "-popupwindow", "-nolog"
        )
    ),
    ExtraArgGroup(
        "Unreal", listOf(
            "-WINDOWED", "-FULLSCREEN", "-dx11", "-dx12", "-vulkan",
            "-NOSPLASH", "-NOSOUND"
        )
    ),
    ExtraArgGroup(
        "Source", listOf(
            "-sw", "-novid", "-nojoy", "-console", "-nosound"
        )
    ),
    ExtraArgGroup(
        "General", listOf(
            "-windowed", "-fullscreen", "-nointro", "-skipvideos", "-novsync", "/d3d9"
        )
    ),
    // Steam-style launch options: KEY=VALUE before %command% become env vars; args after go to the game.
    ExtraArgGroup(
        "Steam (%command%)", listOf(
            "%command%", "%command% -windowed", "DXVK_HUD=fps %command%", "WINEDEBUG=-all %command%"
        )
    )
)

private data class SidebarSection(
    val icon: ImageVector,
    val labelResId: Int
)

const val FRAMEGEN_SHADERS_CHECKING = 0
const val FRAMEGEN_SHADERS_READY = 1
const val FRAMEGEN_SHADERS_IMPORTING = 2
const val FRAMEGEN_SHADERS_MISSING = 3
const val FRAMEGEN_SHADERS_FAILED = 4
const val FRAMEGEN_SHADERS_UPDATED = 5
const val FRAMEGEN_SHADERS_NOT_OWNED = 6

val FrameGenMultiplierOptions = listOf(2, 3, 4)
val FrameGenTargetOptions = listOf(0, 60, 90, 120, 144)

private const val SEC_GENERAL = 0
private const val SEC_STEAM = 1
private const val SEC_DISPLAY = 2
private const val SEC_RESHADE = 3
private const val SEC_WINE = 4
private const val SEC_COMPONENTS = 5
private const val SEC_VARIABLES = 6
private const val SEC_INPUT = 7
private const val SEC_ADVANCED = 8
private const val SEC_DRIVES = 9
private const val SEC_SAVES = 10

private fun buildSections(isSteam: Boolean, isContainer: Boolean): List<Pair<Int, SidebarSection>> {
    val list = mutableListOf<Pair<Int, SidebarSection>>()
    list += SEC_GENERAL to SidebarSection(Icons.Outlined.Tune, R.string.settings_general_title)
    if (isSteam) list += SEC_STEAM to SidebarSection(Icons.Outlined.Science, R.string.steam_section_title)
    list += SEC_DISPLAY to SidebarSection(Icons.Outlined.Monitor, R.string.common_ui_graphics)
    list += SEC_ADVANCED to SidebarSection(Icons.Outlined.Settings, R.string.common_ui_advanced)
    list += SEC_RESHADE to SidebarSection(Icons.Outlined.AutoAwesome, R.string.reshade_section_title)
    list += SEC_VARIABLES to SidebarSection(Icons.Outlined.Code, R.string.container_config_variables)
    list += SEC_INPUT to SidebarSection(Icons.Outlined.SportsEsports, R.string.common_ui_input_controls)
    list += SEC_COMPONENTS to SidebarSection(Icons.Outlined.Extension, R.string.settings_content_components)
    if (isContainer) {
        list += SEC_DRIVES to SidebarSection(Icons.Outlined.Storage, R.string.container_config_drives)
    }
    list += SEC_WINE to SidebarSection(Icons.Outlined.Science, R.string.container_wine_title)
    if (isContainer) {
        list += SEC_SAVES to SidebarSection(Icons.Outlined.Inventory, R.string.saves_import_export_title)
    }
    return list
}

@Composable
fun GameSettingsContent(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks,
    nav: GameSettingsNav? = null
) {
    val isSteam by state.isSteamGame
    val isContainer by state.isContainerEditMode
    val sections = remember(isSteam, isContainer) { buildSections(isSteam, isContainer) }
    val selectedIdx by state.currentSection
    val currentSectionId = sections.getOrNull(selectedIdx)?.first ?: SEC_GENERAL
    val saveEnabled by state.isLoaded

    if (nav != null) {
        SideEffect {
            nav.sidebarCount = sections.size
            nav.onSelectSection = { state.currentSection.intValue = it }
            nav.onSave = { if (saveEnabled) callbacks.onConfirm() }
            nav.onCancel = { callbacks.onDismiss() }
        }
    }

    val portrait = isPortraitLayout()

    val contentPane: @Composable (Modifier) -> Unit = { contentModifier ->
        Box(
            modifier = contentModifier
                .background(ContentBg)
                .then(
                    if (nav != null) {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    if (ev.type == PointerEventType.Press) nav.tapContent()
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            SectionContent(currentSectionId, state, callbacks, nav)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(BgDeep)
    ) {
        if (portrait) {
            Column(modifier = Modifier.fillMaxSize()) {
                PortraitSettingsHeader(
                    title = state.name.value,
                    sections = sections.map { it.second },
                    currentIndex = selectedIdx,
                    onSectionSelected = { state.currentSection.intValue = it },
                    saveEnabled = saveEnabled,
                    onSave = { callbacks.onConfirm() },
                    onCancel = { callbacks.onDismiss() },
                    nav = nav,
                )
                contentPane(Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Sidebar(
                    title = state.name.value,
                    sections = sections.map { it.second },
                    currentIndex = selectedIdx,
                    onSectionSelected = { state.currentSection.intValue = it },
                    saveEnabled = saveEnabled,
                    onSave = { callbacks.onConfirm() },
                    onCancel = { callbacks.onDismiss() },
                    nav = nav,
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(DividerColor)
                )

                contentPane(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingPairRow(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SettingItemGap),
        verticalArrangement = Arrangement.spacedBy(SettingItemGap),
        maxItemsInEachRow = if (isPortraitLayout()) 1 else Int.MAX_VALUE,
        content = content,
    )
}

@Composable
private fun PortraitSettingsHeader(
    title: String,
    sections: List<SidebarSection>,
    currentIndex: Int,
    onSectionSelected: (Int) -> Unit,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    nav: GameSettingsNav? = null,
) {
    val cancelHighlighted = nav != null && nav.active && !nav.inContent && nav.onActionRow && nav.actionCol == 0
    val saveHighlighted = nav != null && nav.active && !nav.inContent && nav.onActionRow && nav.actionCol == 1
    val chipListState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        runCatching { chipListState.animateScrollToItem(currentIndex) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SidebarBg)
            .padding(top = 12.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = SettingLabelSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                    .background(CardSurface)
                    .paneHighlight(cancelHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
                    .clickable { nav?.tapAction(0); onCancel() }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.common_ui_cancel),
                    color = TextSecondary,
                    fontSize = SettingLabelSize,
                    fontWeight = FontWeight.Medium
                )
            }
            SaveButton(
                enabled = saveEnabled,
                onClick = { nav?.tapAction(1); onSave() },
                height = 32.dp,
                corner = 8.dp,
                fontSize = SettingLabelSize,
                navHighlighted = saveHighlighted,
            )
        }

        Spacer(Modifier.height(10.dp))

        LazyRow(
            state = chipListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(sections) { index, section ->
                val isSelected = currentIndex == index
                val chipHighlighted =
                    nav != null && nav.active && !nav.inContent && !nav.onActionRow && nav.sidebarIndex == index
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (isSelected) AccentBlue.copy(alpha = 0.16f) else CardSurface)
                        .border(
                            1.dp,
                            if (isSelected) AccentBlue.copy(alpha = 0.6f) else CardBorder,
                            RoundedCornerShape(17.dp)
                        )
                        .paneHighlight(chipHighlighted, cornerRadius = 17.dp, highlightColor = NavHighlight)
                        .clickable { if (nav != null) nav.tapSection(index) else onSectionSelected(index) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        tint = if (isSelected) AccentBlue else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(section.labelResId),
                        color = if (isSelected) TextPrimary else TextSecondary,
                        fontSize = SettingLabelSize,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 7.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DividerColor)
        )
    }
}

@Composable
private fun SectionContent(
    sectionId: Int,
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks,
    nav: GameSettingsNav? = null
) {
    AnimatedContent(
        targetState = sectionId,
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (slideInHorizontally(
                animationSpec = tween(220)
            ) { direction * it / 6 } + fadeIn(tween(200)))
                .togetherWith(
                    slideOutHorizontally(
                        animationSpec = tween(180)
                    ) { -direction * it / 6 } + fadeOut(tween(120))
                )
        },
        label = "SectionTransition"
    ) { id ->
        val scrollState = rememberScrollState()
        val contentNav = remember(nav) {
            nav?.let { PaneNavRegistry(initialSignal = it.contentSignal) }
        }
        val isCurrent = id == sectionId
        if (nav != null && contentNav != null) {
            contentNav.controllerActive = nav.active && nav.inContent && isCurrent
            contentNav.onEdgeLeft = { nav.exitToSidebar() }
            if (isCurrent) {
                nav.onContentBack = {
                    if (contentNav.overlay != null) {
                        contentNav.overlayClose?.invoke()
                        true
                    } else {
                        false
                    }
                }
            }
            LaunchedEffect(nav.contentSignal) {
                if (isCurrent) contentNav.processNav(nav.contentSignal, nav.contentDir)
            }
            LaunchedEffect(nav.contentResetSignal) {
                if (isCurrent) contentNav.reset()
            }
        }
        val density = LocalDensity.current
        var viewportTop by remember { mutableStateOf(0f) }
        var viewportHeight by remember { mutableIntStateOf(0) }
        if (contentNav != null) {
            LaunchedEffect(contentNav.activeRow, contentNav.activeCol, viewportHeight) {
                if (!contentNav.controllerActive) return@LaunchedEffect
                if (viewportHeight == 0) return@LaunchedEffect
                val bounds = contentNav.activeItemBounds() ?: return@LaunchedEffect
                if (bounds.second <= bounds.first) return@LaunchedEffect
                val margin = with(density) { 16.dp.toPx() }
                val vpTop = viewportTop
                val vpBottom = viewportTop + viewportHeight
                val delta = when {
                    bounds.second + margin > vpBottom -> bounds.second + margin - vpBottom
                    bounds.first - margin < vpTop -> bounds.first - margin - vpTop
                    else -> 0f
                }
                if (delta != 0f) runCatching { scrollState.animateScrollBy(delta) }
            }
        }
        val sectionBody: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (contentNav != null) {
                            Modifier.onGloballyPositioned {
                                viewportTop = it.positionInWindow().y
                                viewportHeight = it.size.height
                            }
                        } else {
                            Modifier
                        }
                    )
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                when (id) {
                    SEC_GENERAL -> GeneralSection(state, callbacks)
                    SEC_STEAM -> SteamSection(state)
                    SEC_DISPLAY -> DisplaySection(state, callbacks)
                    SEC_RESHADE -> ReshadeSection(state)
                    SEC_WINE -> WineSection(state, callbacks)
                    SEC_COMPONENTS -> ComponentsSection(state, callbacks)
                    SEC_VARIABLES -> VariablesSection(state, callbacks)
                    SEC_INPUT -> InputSection(state)
                    SEC_ADVANCED -> AdvancedSection(state, callbacks)
                    SEC_DRIVES -> DrivesSection(state, callbacks)
                    SEC_SAVES -> SavesSection(state, callbacks)
                }
                Spacer(Modifier.height(SettingSectionGap))
            }
        }
        if (contentNav != null) {
            CompositionLocalProvider(LocalPaneNav provides contentNav) { sectionBody() }
        } else {
            sectionBody()
        }
    }
}

@Composable
private fun Sidebar(
    title: String,
    sections: List<SidebarSection>,
    currentIndex: Int,
    onSectionSelected: (Int) -> Unit,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    nav: GameSettingsNav? = null,
    modifier: Modifier = Modifier
) {
    val cancelHighlighted = nav != null && nav.active && !nav.inContent && nav.onActionRow && nav.actionCol == 0
    val saveHighlighted = nav != null && nav.active && !nav.inContent && nav.onActionRow && nav.actionCol == 1
    Column(
        modifier = modifier
            .background(SidebarBg)
            .padding(top = 14.dp, bottom = 12.dp)
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = SettingLabelSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 10.dp)
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DividerColor)
            )
            Spacer(Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            sections.forEachIndexed { index, section ->
                SidebarItem(
                    icon = section.icon,
                    label = stringResource(section.labelResId),
                    isSelected = currentIndex == index,
                    navHighlighted = nav != null && nav.active && !nav.inContent && !nav.onActionRow && nav.sidebarIndex == index,
                    onClick = {
                        if (nav != null) nav.tapSection(index) else onSectionSelected(index)
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(DividerColor)
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                    .background(CardSurface)
                    .paneHighlight(cancelHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
                    .clickable { nav?.tapAction(0); onCancel() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.common_ui_cancel),
                    color = TextSecondary,
                    fontSize = SettingLabelSize,
                    fontWeight = FontWeight.Medium
                )
            }
            SaveButton(
                enabled = saveEnabled,
                onClick = { nav?.tapAction(1); onSave() },
                height = 30.dp,
                corner = 8.dp,
                fontSize = SettingLabelSize,
                navHighlighted = saveHighlighted,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SaveButton(
    enabled: Boolean,
    onClick: () -> Unit,
    height: Dp,
    corner: Dp,
    fontSize: TextUnit,
    navHighlighted: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(corner))
            .border(
                1.dp,
                if (enabled) AccentBlue.copy(alpha = 0.5f) else CardBorder,
                RoundedCornerShape(corner)
            )
            .background(
                if (enabled) AccentBlue.copy(alpha = 0.1f) else CardSurface
            )
            .paneHighlight(navHighlighted, cornerRadius = corner, highlightColor = NavHighlight)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.common_ui_save),
            color = if (enabled) AccentBlue else TextDim,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    navHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(navHighlighted) {
        if (navHighlighted) runCatching { bringIntoView.bringIntoView() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .chasingBorder(isFocused = isSelected, cornerRadius = 8.dp, borderWidth = 2.dp)
            .paneHighlight(navHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) AccentBlue else TextDim,
                modifier = Modifier.size(SettingIconSize)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                color = if (isSelected) TextPrimary else TextSecondary,
                fontSize = SettingValueSize,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun GeneralSection(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks
) {
    val isContainer = state.isContainerEditMode.value

    @Composable
    fun ArtworkPickerRow(
        title: String,
        summary: String,
        selected: Boolean,
        onPick: () -> Unit,
        onRemove: () -> Unit,
    ) {
        @Composable
        fun ActionButton(
            text: String,
            tint: Color,
            onClick: () -> Unit,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint.copy(alpha = 0.08f))
                    .border(1.dp, tint.copy(alpha = 0.2f), RoundedCornerShape(9.dp))
                    .paneNavItem(cornerRadius = 9.dp, onActivate = { onClick() }, highlightColor = NavHighlight)
                    .clickable { onClick() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = text,
                    color = tint,
                    fontSize = SettingLabelSize,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SettingGroupCorner))
                .background(InputSurface)
                .border(1.dp, InputBorder, RoundedCornerShape(SettingGroupCorner))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontSize = SettingValueSize,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (!selected && summary.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))

                        Text(
                            text = summary,
                            color = TextSecondary,
                            fontSize = SettingLabelSize,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!selected) {
                        ActionButton(
                            text = stringResource(R.string.shortcuts_library_artwork_set),
                            tint = AccentBlue,
                            onClick = onPick
                        )
                    }

                    if (selected) {
                        ActionButton(
                            text = stringResource(R.string.common_ui_remove),
                            tint = DangerRed,
                            onClick = onRemove
                        )
                    }
                }
            }
        }
    }

    SettingGroup {
        SettingTextField(
            label = stringResource(R.string.common_ui_name),
            value = state.name.value,
            onValueChange = { state.name.value = it }
        )

        if (!isContainer) {
            val launchExeDisplayText = state.launchExeDisplayPath.value.ifBlank { state.launchExePath.value }
            val hasLaunchExePath = launchExeDisplayText.isNotEmpty()
            Spacer(Modifier.height(SettingItemGap))
            Text(
                stringResource(R.string.common_ui_select_exe),
                color = TextSecondary,
                fontSize = SettingLabelSize,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(SettingTightGap))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SettingFieldCorner))
                    .border(1.dp, InputBorder, RoundedCornerShape(SettingFieldCorner))
                    .background(InputSurface)
                    .paneNavItem(cornerRadius = SettingFieldCorner, onActivate = { callbacks.onSelectExe() }, highlightColor = NavHighlight)
                    .clickable { callbacks.onSelectExe() }
                    .padding(horizontal = SettingFieldHorizontalPadding, vertical = SettingFieldVerticalPadding)
            ) {
                Text(
                    text = launchExeDisplayText.ifEmpty { stringResource(R.string.common_ui_select_exe) },
                    color = if (hasLaunchExePath) TextPrimary else TextDim,
                    fontSize = if (hasLaunchExePath) 10.sp else SettingValueSize,
                    maxLines = if (hasLaunchExePath) Int.MAX_VALUE else 1,
                    overflow = if (hasLaunchExePath) TextOverflow.Visible else TextOverflow.Ellipsis
                )
            }
        }

        if (!isContainer && state.containerEntries.value.isNotEmpty()) {
            Spacer(Modifier.height(SettingItemGap))
            SettingDropdown(
                label = stringResource(R.string.shortcuts_list_select_a_container),
                entries = state.containerEntries.value,
                selectedIndex = state.selectedContainer.intValue,
                onSelected = {
                    state.selectedContainer.intValue = it
                    callbacks.onContainerChanged(it)
                }
            )
        }

        if (isContainer && state.wineVersionEntries.value.isNotEmpty()) {
            Spacer(Modifier.height(SettingItemGap))
            SettingDropdown(
                label = stringResource(R.string.container_wine_version),
                entries = state.wineVersionEntries.value,
                selectedIndex = state.selectedWineVersion.intValue,
                onSelected = {
                    state.selectedWineVersion.intValue = it
                    callbacks.onWineVersionChanged(it)
                },
                enabled = state.wineVersionEditable.value
            )
        }

        if (!isContainer) {
            Spacer(Modifier.height(SettingItemGap))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(AccentBlue.copy(alpha = 0.08f))
                    .border(1.dp, AccentBlue.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .paneNavItem(cornerRadius = 10.dp, onActivate = { callbacks.onAddToHomeScreen() }, highlightColor = NavHighlight)
                    .clickable { callbacks.onAddToHomeScreen() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Home,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(SettingIconSize)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.shortcuts_list_add_to_home_screen),
                        color = AccentBlue,
                        fontSize = SettingValueSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    if (!isContainer) {
        Spacer(Modifier.height(SettingSectionGap))

        SettingGroup {
            val artworkPortrait = isPortraitLayout()

            val artworkLabel: @Composable () -> Unit = {
                Text(
                    text = stringResource(R.string.shortcuts_library_artwork_title),
                    color = TextSecondary,
                    fontSize = SettingSectionLabelSize,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
            }

            val scrapeButton: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentBlue.copy(alpha = 0.08f))
                        .border(1.dp, AccentBlue.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .paneNavItem(cornerRadius = 10.dp, onActivate = { callbacks.onScrapeGameArtwork(state.name.value) }, highlightColor = NavHighlight)
                        .clickable { callbacks.onScrapeGameArtwork(state.name.value) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.library_games_scrape_artwork),
                            color = AccentBlue,
                            fontSize = SettingValueSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            val openSourceButton: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentBlue.copy(alpha = 0.08f))
                        .border(1.dp, AccentBlue.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .paneNavItem(cornerRadius = 10.dp, onActivate = { callbacks.onOpenArtworkSource(state.name.value) }, highlightColor = NavHighlight)
                        .clickable { callbacks.onOpenArtworkSource(state.name.value) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(SettingIconSize)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.shortcuts_library_artwork_open_source),
                            color = AccentBlue,
                            fontSize = SettingValueSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            if (artworkPortrait) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    artworkLabel()
                    Spacer(Modifier.height(8.dp))
                    SettingPairRow(modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) { scrapeButton() }
                        Box(Modifier.weight(1f)) { openSourceButton() }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    artworkLabel()
                    scrapeButton()
                    openSourceButton()
                }
            }

            Spacer(Modifier.height(SettingItemGap))

            SettingPairRow {
                Box(Modifier.weight(1f)) {
                    ArtworkPickerRow(
                        title = stringResource(R.string.shortcuts_library_artwork_icon_title),
                        summary = state.iconArtworkSummary.value,
                        selected = state.iconArtworkSelected.value,
                        onPick = callbacks::onPickIconArtwork,
                        onRemove = callbacks::onRemoveIconArtwork
                    )
                }
                Box(Modifier.weight(1f)) {
                    ArtworkPickerRow(
                        title = stringResource(R.string.shortcuts_library_artwork_hero_title),
                        summary = state.gameCardArtworkSummary.value,
                        selected = state.gameCardArtworkSelected.value,
                        onPick = callbacks::onPickGameCardArtwork,
                        onRemove = callbacks::onRemoveGameCardArtwork
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(SettingSectionGap))

    SettingGroup {
        SettingPairRow {
            Box(Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.container_config_screen_size),
                    entries = state.screenSizeEntries.value,
                    selectedIndex = state.selectedScreenSize.intValue,
                    onSelected = { state.selectedScreenSize.intValue = it }
                )
            }
            Box(Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.settings_general_refresh_rate),
                    entries = state.refreshRateEntries.value,
                    selectedIndex = state.selectedRefreshRate.intValue,
                    onSelected = { state.selectedRefreshRate.intValue = it }
                )
            }
        }

        // Custom resolution fields when "Custom" is selected (index 0)
        if (state.selectedScreenSize.intValue == 0) {
            Spacer(Modifier.height(SettingItemGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    SettingTextField(
                        label = stringResource(R.string.common_ui_width),
                        value = state.customWidth.value,
                        onValueChange = { state.customWidth.value = it },
                        keyboardType = KeyboardType.Number
                    )
                }
                Box(Modifier.weight(1f)) {
                    SettingTextField(
                        label = stringResource(R.string.common_ui_height),
                        value = state.customHeight.value,
                        onValueChange = { state.customHeight.value = it },
                        keyboardType = KeyboardType.Number
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(SettingSectionGap))

    SettingGroup {
        SettingPairRow {
            Box(Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.container_config_audio_driver),
                    entries = state.audioDriverEntries.value,
                    selectedIndex = state.selectedAudioDriver.intValue,
                    onSelected = { state.selectedAudioDriver.intValue = it }
                )
            }
            Box(Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.settings_audio_midi_sound_font),
                    entries = state.midiSoundFontEntries.value,
                    selectedIndex = state.selectedMidiSoundFont.intValue,
                    onSelected = { state.selectedMidiSoundFont.intValue = it }
                )
            }
        }
    }

    if (!isContainer) {
        Spacer(Modifier.height(SettingSectionGap))
        SettingGroup(verticalPadding = SettingTightGap) {
            val fpsMin = 15
            // Cap the slider at the panel's highest supported refresh rate (parsed from entries like "120 Hz"); fall back to 60.
            val supportedMax = state.refreshRateEntries.value
                .mapNotNull { it.trim().substringBefore(" ").toIntOrNull() }
                .maxOrNull() ?: 60
            val maxFps = supportedMax.coerceAtLeast(fpsMin)
            val enabled = state.fpsLimit.intValue > 0
            // Remember the last enabled value so off→on restores it; re-seed when the supported max changes.
            var lastFps by remember(maxFps) {
                mutableStateOf(
                    (if (state.fpsLimit.intValue > 0) state.fpsLimit.intValue else 60)
                        .coerceIn(fpsMin, maxFps)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingSwitch(
                    label = "FPS Limiter",
                    checked = enabled,
                    onCheckedChange = { on -> state.fpsLimit.intValue = if (on) lastFps else 0 }
                )
                AnimatedVisibility(
                    visible = enabled,
                    enter = graphicsCardExpandEnter(),
                    exit = graphicsCardExpandExit()
                ) {
                    SettingSlider(
                        label = "Limit",
                        value = lastFps,
                        range = fpsMin..maxFps,
                        valueText = "$lastFps FPS",
                        steps = (maxFps - fpsMin - 1).coerceAtLeast(0),
                        onValueChange = {
                            val v = it.coerceIn(fpsMin, maxFps)
                            lastFps = v
                            state.fpsLimit.intValue = v
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplaySection(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks
) {

    SettingGroup {
        SettingPairRow {
            Box(Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.container_graphics_driver),
                    entries = state.graphicsDriverEntries.value,
                    selectedIndex = state.selectedGraphicsDriver.intValue,
                    onSelected = { state.selectedGraphicsDriver.intValue = it }
                )
            }
            Box(Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.container_surface_effect),
                    entries = state.surfaceEffectEntries.value,
                    selectedIndex = state.selectedSurfaceEffect.intValue,
                    onSelected = { state.selectedSurfaceEffect.intValue = it }
                )
            }
        }

        Spacer(Modifier.height(SettingSectionGap))

        SettingPairRow {
            Box(Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.container_wine_dxwrapper),
                    entries = state.dxWrapperEntries.value,
                    selectedIndex = state.selectedDxWrapper.intValue,
                    onSelected = { state.selectedDxWrapper.intValue = it }
                )
            }
            Box(Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.container_graphics_compositor_present_mode),
                    entries = state.gfxCompositorPresentModeEntries.value.map { mode ->
                        when (mode.lowercase()) {
                            "fifo" -> "FIFO"
                            "mailbox" -> "Mailbox"
                            "immediate" -> "Immediate"
                            else -> mode.replaceFirstChar { it.uppercase() }
                        }
                    },
                    selectedIndex = state.gfxSelectedCompositorPresentMode.intValue,
                    onSelected = { state.gfxSelectedCompositorPresentMode.intValue = it }
                )
            }
        }
    }

    Spacer(Modifier.height(SettingItemGap))

    GraphicsDriverConfigCard(state, callbacks)

    Spacer(Modifier.height(SettingItemGap))

    FrameGenerationCard(state)

    Spacer(Modifier.height(SettingItemGap))

    val dxWrapperEntries = state.dxWrapperEntries.value
    val dxWrapperIdx = state.selectedDxWrapper.intValue
    val selectedDxWrapper = if (dxWrapperIdx in dxWrapperEntries.indices)
        com.winlator.cmod.shared.util.StringUtils.parseIdentifier(dxWrapperEntries[dxWrapperIdx])
    else ""

    if (selectedDxWrapper.contains("dxvk")) {
        DXVKConfigCard(state, callbacks)
    } else {
        WineD3DConfigCard(state)
    }

}

@Composable
private fun FrameGenerationCard(state: GameSettingsStateHolder) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (state.frameGenShaderState.intValue != FRAMEGEN_SHADERS_CHECKING) return@LaunchedEffect
        state.frameGenShaderState.intValue = FRAMEGEN_SHADERS_IMPORTING
        val outcome = withContext(Dispatchers.IO) { LosslessAutoImport.sync(context) }
        state.frameGenSourceName.value = outcome.sourceName
        state.frameGenShaderState.intValue = frameGenStateFor(outcome.result)
    }

    val scope = rememberCoroutineScope()
    val pickDll = {
        val activity = context.findActivity()
        if (activity != null) {
            val imagefsRoot = ImageFs.find(context).rootDir
            DirectoryPickerDialog.showFile(
                activity,
                title = context.getString(R.string.settings_frame_generation_locate),
                allowedExtensions = setOf("dll"),
                extraRoots =
                    listOf(
                        DirectoryPickerDialog.ManagedRoot("C:", File(imagefsRoot, "home").absolutePath),
                        DirectoryPickerDialog.ManagedRoot("Z:", imagefsRoot.absolutePath),
                        DirectoryPickerDialog.ManagedRoot(
                            "D:",
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
                        ),
                        DirectoryPickerDialog.ManagedRoot(
                            "Internal",
                            Environment.getExternalStorageDirectory().absolutePath,
                        ),
                    ),
            ) { pickedPath ->
                state.frameGenShaderState.intValue = FRAMEGEN_SHADERS_IMPORTING
                scope.launch {
                    val outcome =
                        withContext(Dispatchers.IO) { LosslessAutoImport.importFrom(context, File(pickedPath)) }
                    state.frameGenSourceName.value = outcome.sourceName
                    state.frameGenShaderState.intValue = frameGenStateFor(outcome.result)
                }
            }
        }
    }

    val shaders = state.frameGenShaderState.intValue
    val ready = shaders == FRAMEGEN_SHADERS_READY || shaders == FRAMEGEN_SHADERS_UPDATED
    val busy = shaders == FRAMEGEN_SHADERS_IMPORTING || shaders == FRAMEGEN_SHADERS_CHECKING
    val enabled = ready && state.frameGenEnabled.value
    val targetRate = state.frameGenTargetRate.intValue

    SettingGroup {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Speed,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(SettingIconSize),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.settings_frame_generation_title),
                color = TextPrimary,
                fontSize = SettingValueSize,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(SettingItemGap))

        SettingSwitch(
            label = stringResource(R.string.session_drawer_frame_generation_enable),
            checked = enabled,
            enabled = ready,
            onCheckedChange = { state.frameGenEnabled.value = it },
        )


        Text(
            text =
                when (shaders) {
                    FRAMEGEN_SHADERS_IMPORTING, FRAMEGEN_SHADERS_CHECKING ->
                        stringResource(R.string.settings_frame_generation_importing)
                    FRAMEGEN_SHADERS_READY ->
                        if (state.frameGenSourceName.value.isEmpty()) {
                            stringResource(R.string.settings_frame_generation_ready)
                        } else {
                            stringResource(
                                R.string.settings_frame_generation_imported,
                                state.frameGenSourceName.value,
                            )
                        }
                    FRAMEGEN_SHADERS_UPDATED ->
                        stringResource(
                            R.string.settings_frame_generation_updated,
                            state.frameGenSourceName.value,
                        )
                    FRAMEGEN_SHADERS_NOT_OWNED -> stringResource(R.string.settings_frame_generation_not_owned)
                    FRAMEGEN_SHADERS_FAILED -> stringResource(R.string.settings_frame_generation_failed)
                    else -> stringResource(R.string.settings_frame_generation_not_found)
                },
            color = TextSecondary,
            fontSize = SettingLabelSize,
            lineHeight = SettingLabelSize * 1.4f,
        )

        if (shaders != FRAMEGEN_SHADERS_NOT_OWNED) {
            Spacer(Modifier.height(SettingItemGap))
            SettingActionButton(
                label = stringResource(R.string.settings_frame_generation_locate),
                enabled = !busy,
                onClick = { pickDll() },
            )
        }

        if (enabled) {
            Spacer(Modifier.height(SettingItemGap))

            SettingPairRow {
                Box(Modifier.weight(1f)) {
                    SettingDropdown(
                        label = stringResource(R.string.session_drawer_frame_generation_target),
                        entries =
                            FrameGenTargetOptions.map { rate ->
                                if (rate == 0) {
                                    stringResource(R.string.session_drawer_frame_generation_target_off)
                                } else {
                                    stringResource(R.string.session_drawer_frame_generation_target_value, rate)
                                }
                            },
                        selectedIndex = FrameGenTargetOptions.indexOf(targetRate).coerceAtLeast(0),
                        onSelected = { state.frameGenTargetRate.intValue = FrameGenTargetOptions[it] },
                    )
                }
                Box(Modifier.weight(1f)) {
                    SettingDropdown(
                        label = stringResource(R.string.session_drawer_frame_generation_multiplier),
                        entries =
                            FrameGenMultiplierOptions.map { multiplier ->
                                stringResource(
                                    R.string.session_drawer_frame_generation_multiplier_value,
                                    multiplier,
                                )
                            },
                        selectedIndex =
                            FrameGenMultiplierOptions
                                .indexOf(state.frameGenMultiplier.intValue)
                                .coerceAtLeast(0),
                        onSelected = {
                            state.frameGenMultiplier.intValue = FrameGenMultiplierOptions[it]
                        },
                        enabled = targetRate == 0,
                    )
                }
            }

            Spacer(Modifier.height(SettingItemGap))

            FrameGenPresetSlider(
                selected = FrameGenPreset.fromFlowScale(state.frameGenFlowScale.intValue),
                onSelected = { state.frameGenFlowScale.intValue = it.flowScale },
            )

            Spacer(Modifier.height(SettingItemGap))

            Text(
                text = stringResource(R.string.session_drawer_frame_generation_note),
                color = TextSecondary,
                fontSize = SettingLabelSize,
                lineHeight = SettingLabelSize * 1.4f,
            )
        }
    }
}

@Composable
private fun GraphicsDriverConfigCard(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks
) {
    val expanded by state.gfxConfigExpanded

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingGroupCorner))
            .background(CardSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(SettingGroupCorner))
    ) {
        // Header row — tap to expand/collapse.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { state.gfxConfigExpanded.value = !expanded }
                .paneNavItem(
                    cornerRadius = SettingGroupCorner,
                    onActivate = { state.gfxConfigExpanded.value = !expanded },
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(SettingGroupPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(SettingIconSize)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.container_graphics_configuration),
                color = TextPrimary,
                fontSize = SettingValueSize,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (state.graphicsDriverVersion.value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentBlue.copy(alpha = 0.1f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        state.graphicsDriverVersion.value,
                        color = AccentBlue,
                        fontSize = SettingLabelSize,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = TextDim,
                modifier = Modifier.size(SettingIconSize)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = graphicsCardExpandEnter(),
            exit = graphicsCardExpandExit()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_graphics_vulkan_version),
                            entries = state.gfxVulkanVersionEntries.value,
                            selectedIndex = state.gfxSelectedVulkanVersion.intValue,
                            onSelected = { state.gfxSelectedVulkanVersion.intValue = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_graphics_version),
                            entries = state.gfxDriverVersionEntries.value,
                            selectedIndex = state.gfxSelectedDriverVersion.intValue,
                            onSelected = {
                                state.gfxSelectedDriverVersion.intValue = it
                                callbacks.onGfxDriverVersionChanged(it)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f)) {
                        ExtensionsMultiSelect(state)
                    }
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_wine_gpu_name),
                            entries = state.gfxGpuNameEntries.value,
                            selectedIndex = state.gfxSelectedGpuName.intValue,
                            onSelected = { state.gfxSelectedGpuName.intValue = it }
                        )
                    }
                }

                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_graphics_max_device_memory),
                            entries = state.gfxMaxDeviceMemoryEntries.value,
                            selectedIndex = state.gfxSelectedMaxDeviceMemory.intValue,
                            onSelected = { state.gfxSelectedMaxDeviceMemory.intValue = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_graphics_present_modes),
                            entries = state.gfxPresentModeEntries.value,
                            selectedIndex = state.gfxSelectedPresentMode.intValue,
                            onSelected = { state.gfxSelectedPresentMode.intValue = it }
                        )
                    }
                }

                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_graphics_resource_type),
                            entries = state.gfxResourceTypeEntries.value,
                            selectedIndex = state.gfxSelectedResourceType.intValue,
                            onSelected = { state.gfxSelectedResourceType.intValue = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_graphics_bcn_emulation),
                            entries = state.gfxBcnEmulationEntries.value,
                            selectedIndex = state.gfxSelectedBcnEmulation.intValue,
                            onSelected = { state.gfxSelectedBcnEmulation.intValue = it }
                        )
                    }
                }

                val bcnEmulationActive = !state.gfxBcnEmulationEntries.value
                    .getOrElse(state.gfxSelectedBcnEmulation.intValue) { "" }
                    .equals("none", ignoreCase = true)
                if (bcnEmulationActive) {
                    Spacer(Modifier.height(SettingItemGap))

                    SettingPairRow {
                        Box(Modifier.weight(1f)) {
                            SettingDropdown(
                                label = stringResource(R.string.container_graphics_bcn_emulation_type),
                                entries = state.gfxBcnEmulationTypeEntries.value,
                                selectedIndex = state.gfxSelectedBcnEmulationType.intValue,
                                onSelected = { state.gfxSelectedBcnEmulationType.intValue = it }
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            SettingDropdown(
                                label = stringResource(R.string.container_graphics_bcn_emulation_cache),
                                entries = state.gfxBcnEmulationCacheEntries.value,
                                selectedIndex = state.gfxSelectedBcnEmulationCache.intValue,
                                onSelected = { state.gfxSelectedBcnEmulationCache.intValue = it }
                            )
                        }
                    }
                }

                val gamenativeWrapperActive = state.graphicsDriverEntries.value
                    .getOrElse(state.selectedGraphicsDriver.intValue) { "" }
                    .equals("Wrapper-Gamenative", ignoreCase = true)
                if (gamenativeWrapperActive) {
                    Spacer(Modifier.height(SettingItemGap))

                    SettingPairRow {
                        Box(Modifier.weight(1f)) {
                            SettingDropdown(
                                label = stringResource(R.string.container_graphics_transcoder),
                                entries = state.gfxTranscoderEntries.value,
                                selectedIndex = state.gfxSelectedTranscoder.intValue,
                                onSelected = { state.gfxSelectedTranscoder.intValue = it }
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            SettingDropdown(
                                label = stringResource(R.string.container_graphics_quality),
                                entries = state.gfxQualityEntries.value,
                                selectedIndex = state.gfxSelectedQuality.intValue,
                                onSelected = { state.gfxSelectedQuality.intValue = it }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f)) {
                        SettingCheckbox(
                            label = stringResource(R.string.container_graphics_sync_frame),
                            checked = state.gfxSyncFrame.value,
                            onCheckedChange = { state.gfxSyncFrame.value = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SettingCheckbox(
                            label = stringResource(R.string.container_graphics_disable_present_wait),
                            checked = state.gfxDisablePresentWait.value,
                            onCheckedChange = { state.gfxDisablePresentWait.value = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionsMultiSelect(state: GameSettingsStateHolder) {
    val extensions = state.gfxAvailableExtensions.value
    val blacklisted = state.gfxBlacklistedExtensions.value
    var showDialog by remember { mutableStateOf(false) }
    val enabledCount = extensions.size - blacklisted.size

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.container_graphics_available_extensions),
            color = TextSecondary,
            fontSize = SettingLabelSize,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(bottom = SettingTightGap)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SettingFieldCorner))
                .background(InputSurface)
                .border(1.dp, InputBorder, RoundedCornerShape(SettingFieldCorner))
                .clickable(enabled = extensions.isNotEmpty()) { showDialog = true }
                .then(
                    if (extensions.isNotEmpty()) {
                        Modifier.paneNavItem(
                            cornerRadius = SettingFieldCorner,
                            onActivate = { showDialog = true },
                            highlightColor = NavHighlight,
                            tapToSelect = true,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = SettingFieldHorizontalPadding, vertical = SettingFieldVerticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (extensions.isEmpty()) "—"
                else stringResource(R.string.container_graphics_extensions_enabled_summary, enabledCount, extensions.size),
                color = TextPrimary,
                fontSize = SettingValueSize,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = TextDim,
                modifier = Modifier.size(SettingIconSize)
            )
        }
    }

    if (showDialog && extensions.isNotEmpty()) {
        ExtensionsPickerDialog(
            extensions = extensions,
            blacklisted = blacklisted,
            onToggle = { ext, enabled ->
                state.gfxBlacklistedExtensions.value = if (enabled) {
                    blacklisted - ext
                } else {
                    blacklisted + ext
                }
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun ExtensionsPickerDialog(
    extensions: List<String>,
    blacklisted: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxHeight(0.70f)
                .clip(RoundedCornerShape(SettingGroupCorner))
                .background(BgDeep)
                .border(1.dp, CardBorder, RoundedCornerShape(SettingGroupCorner))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.container_graphics_available_extensions),
                    color = TextPrimary,
                    fontSize = SettingValueSize,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                val enabledCount = extensions.size - blacklisted.size
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentBlue.copy(alpha = 0.1f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        stringResource(R.string.container_graphics_extensions_enabled_summary, enabledCount, extensions.size),
                        color = AccentBlue,
                        fontSize = SettingLabelSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                extensions.forEach { ext ->
                    val isEnabled = ext !in blacklisted
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .paneNavItem(cornerRadius = 8.dp, onActivate = { onToggle(ext, !isEnabled) }, highlightColor = NavHighlight)
                            .clickable { onToggle(ext, !isEnabled) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isEnabled,
                            onCheckedChange = { onToggle(ext, it) },
                            modifier = Modifier.size(20.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = AccentBlue,
                                uncheckedColor = CheckBorder,
                                checkmarkColor = Color.White
                            )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            ext,
                            color = if (isEnabled) TextPrimary else TextDim,
                            fontSize = SettingValueSize,
                            maxLines = 1
                        )
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .paneNavItem(cornerRadius = 8.dp, onActivate = { onDismiss() }, highlightColor = NavHighlight)
                    .clickable { onDismiss() }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(android.R.string.ok),
                    color = AccentBlue,
                    fontSize = SettingValueSize,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun DXVKConfigCard(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks
) {
    val expanded by state.dxvkConfigExpanded

    val dxvkVersions = state.dxvkVersionEntries.value
    val selectedIdx = state.dxvkSelectedVersion.intValue
    val selectedVersion = if (selectedIdx in dxvkVersions.indices) dxvkVersions[selectedIdx] else ""
    val isGplAsync = selectedVersion.contains("gplasync")
    val isAsync = selectedVersion.contains("async")
    val asyncEnabled = isAsync || isGplAsync
    val asyncCacheEnabled = isGplAsync

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingGroupCorner))
            .background(CardSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(SettingGroupCorner))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { state.dxvkConfigExpanded.value = !expanded }
                .paneNavItem(
                    cornerRadius = SettingGroupCorner,
                    onActivate = { state.dxvkConfigExpanded.value = !expanded },
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(SettingGroupPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(SettingIconSize)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.container_wine_dxvk_config_title),
                color = TextPrimary,
                fontSize = SettingValueSize,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = TextDim,
                modifier = Modifier.size(SettingIconSize)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = graphicsCardExpandEnter(),
            exit = graphicsCardExpandExit()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_wine_vkd3d_version),
                            entries = state.dxvkVkd3dVersionEntries.value,
                            selectedIndex = state.dxvkSelectedVkd3dVersion.intValue,
                            onSelected = {
                                state.dxvkSelectedVkd3dVersion.intValue = it
                                callbacks.onDxvkVkd3dVersionChanged(it)
                            }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_wine_vkd3d_feature_level),
                            entries = state.dxvkVkd3dFeatureLevelEntries.value,
                            selectedIndex = state.dxvkSelectedVkd3dFeatureLevel.intValue,
                            onSelected = { state.dxvkSelectedVkd3dFeatureLevel.intValue = it }
                        )
                    }
                }

                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_wine_dxvk_version),
                            entries = state.dxvkVersionEntries.value,
                            selectedIndex = state.dxvkSelectedVersion.intValue,
                            onSelected = {
                                state.dxvkSelectedVersion.intValue = it
                                callbacks.onDxvkVersionChanged(it)
                            }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_wine_ddraw_wrapper),
                            entries = state.dxvkDdrawWrapperEntries.value,
                            selectedIndex = state.dxvkSelectedDdrawWrapper.intValue,
                            onSelected = { state.dxvkSelectedDdrawWrapper.intValue = it }
                        )
                    }
                }

                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f).alpha(if (asyncEnabled) 1f else 0.35f)) {
                        SettingCheckbox(
                            label = stringResource(R.string.container_wine_enabled_async),
                            checked = state.dxvkAsync.value && asyncEnabled,
                            onCheckedChange = { if (asyncEnabled) state.dxvkAsync.value = it }
                        )
                    }
                    Box(Modifier.weight(1f).alpha(if (asyncCacheEnabled) 1f else 0.35f)) {
                        SettingCheckbox(
                            label = stringResource(R.string.container_wine_enabled_async_cache),
                            checked = state.dxvkAsyncCache.value && asyncCacheEnabled,
                            onCheckedChange = { if (asyncCacheEnabled) state.dxvkAsyncCache.value = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WineD3DConfigCard(state: GameSettingsStateHolder) {
    val expanded by state.wined3dConfigExpanded

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingGroupCorner))
            .background(CardSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(SettingGroupCorner))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { state.wined3dConfigExpanded.value = !expanded }
                .paneNavItem(
                    cornerRadius = SettingGroupCorner,
                    onActivate = { state.wined3dConfigExpanded.value = !expanded },
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(SettingGroupPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(SettingIconSize)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.container_wine_wined3d_config_title),
                color = TextPrimary,
                fontSize = SettingValueSize,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = TextDim,
                modifier = Modifier.size(SettingIconSize)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = graphicsCardExpandEnter(),
            exit = graphicsCardExpandExit()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_wine_csmt),
                            entries = state.wined3dCsmtEntries.value,
                            selectedIndex = state.wined3dSelectedCsmt.intValue,
                            onSelected = { state.wined3dSelectedCsmt.intValue = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_wine_gpu_name),
                            entries = state.wined3dGpuNameEntries.value,
                            selectedIndex = state.wined3dSelectedGpuName.intValue,
                            onSelected = { state.wined3dSelectedGpuName.intValue = it }
                        )
                    }
                }

                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_wine_video_memory_size),
                            entries = state.wined3dVideoMemorySizeEntries.value,
                            selectedIndex = state.wined3dSelectedVideoMemorySize.intValue,
                            onSelected = { state.wined3dSelectedVideoMemorySize.intValue = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_wine_strict_shader_math),
                            entries = state.wined3dStrictShaderMathEntries.value,
                            selectedIndex = state.wined3dSelectedStrictShaderMath.intValue,
                            onSelected = { state.wined3dSelectedStrictShaderMath.intValue = it }
                        )
                    }
                }

                Spacer(Modifier.height(SettingItemGap))

                SettingPairRow {
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_wine_offscreen_rendering_mode),
                            entries = state.wined3dOffscreenRenderingModeEntries.value,
                            selectedIndex = state.wined3dSelectedOffscreenRenderingMode.intValue,
                            onSelected = { state.wined3dSelectedOffscreenRenderingMode.intValue = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SettingDropdown(
                            label = stringResource(R.string.container_config_renderer),
                            entries = state.wined3dRendererEntries.value,
                            selectedIndex = state.wined3dSelectedRenderer.intValue,
                            onSelected = { state.wined3dSelectedRenderer.intValue = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReshadeSection(state: GameSettingsStateHolder) {
    val loadout = state.reshadeLoadout
    var showCatalog by remember { mutableStateOf(false) }

    SubsectionLabel(stringResource(R.string.reshade_section_title))
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.reshade_section_hint),
        color = TextSecondary
    )
    Spacer(Modifier.height(SettingSectionGap))

    SettingGroup {
        if (loadout.order.isEmpty()) {
            Text(
                text = stringResource(R.string.reshade_loadout_empty),
                color = TextSecondary
            )
            Spacer(Modifier.height(SettingItemGap))
        } else {
            // chain order = apply order; every listed effect is compiled into the vkBasalt chain at launch.
            loadout.order.forEachIndexed { index, name ->
                val effect = state.reshadeEffects.value.firstOrNull { it.name == name }
                ReshadeEffectEditorRow(
                    state = state,
                    index = index,
                    name = name,
                    params = effect?.params ?: emptyList(),
                    isFirst = index == 0,
                    isLast = index == loadout.order.lastIndex,
                )
                Spacer(Modifier.height(SettingItemGap))
            }
        }

        val full = loadout.isFull()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SettingFieldCorner))
                .then(
                    if (full) Modifier
                    else Modifier
                        .paneNavItem(
                            cornerRadius = SettingFieldCorner,
                            onActivate = { showCatalog = true },
                            highlightColor = NavHighlight,
                        )
                        .clickable { showCatalog = true }
                )
                .padding(horizontal = SettingFieldHorizontalPadding, vertical = SettingFieldVerticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = if (full) TextSecondary else AccentBlue,
                modifier = Modifier.size(SettingIconSize)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (full) R.string.reshade_loadout_full else R.string.reshade_loadout_add),
                color = if (full) TextSecondary else AccentBlue,
                fontSize = SettingValueSize,
                fontWeight = FontWeight.Medium
            )
        }
    }

    if (loadout.order.isNotEmpty()) {
        Spacer(Modifier.height(SettingSectionGap))
        SettingGroup {
            ReshadeModeSelector(loadout.mode) { loadout.changeMode(it) }
        }
    }

    if (showCatalog) {
        ReshadeCatalogDialog(state = state, onDismiss = { showCatalog = false })
    }
}

// in solo mode enabling one effect bypasses the rest; ReshadeLoadoutState enforces the exclusivity.
@Composable
private fun ReshadeEffectEditorRow(
    state: GameSettingsStateHolder,
    index: Int,
    name: String,
    params: List<ReshadeManager.ReshadeParam>,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val loadout = state.reshadeLoadout
    val enabled = loadout.isEnabled(name)
    var expanded by remember(name) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .paneNavItem(cornerRadius = 6.dp, onActivate = { loadout.setEnabled(name, !enabled) }, highlightColor = NavHighlight, tapToSelect = true)
        ) {
            Switch(
                checked = enabled,
                onCheckedChange = { loadout.setEnabled(name, it) },
                colors = outlinedSwitchColors(accentColor = AccentBlue, textSecondaryColor = TextSecondary),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            color = TextPrimary,
            fontSize = SettingLabelSize,
            fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .paneNavItem(cornerRadius = 6.dp, onActivate = { expanded = !expanded }, highlightColor = NavHighlight, tapToSelect = true)
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
        )
        if (loadout.mode == ReshadeLoadout.MODE_SOLO && enabled) {
            Text(
                text = stringResource(R.string.reshade_effect_active),
                color = AccentBlue,
                fontSize = SettingLabelSize,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
        ReshadeRowIcon(Icons.Outlined.KeyboardArrowUp, enabled = !isFirst) { loadout.move(index, index - 1) }
        ReshadeRowIcon(Icons.Outlined.KeyboardArrowDown, enabled = !isLast) { loadout.move(index, index + 1) }
        ReshadeRowIcon(Icons.Outlined.Close, enabled = true) { loadout.remove(name) }
    }

    if (expanded) {
        if (params.isEmpty()) {
            Text(
                text = stringResource(R.string.reshade_drawer_no_params),
                color = TextSecondary,
                fontSize = SettingLabelSize,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.reshade_params_reset),
                    color = AccentBlue,
                    fontSize = SettingLabelSize,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .paneNavItem(cornerRadius = 6.dp, onActivate = { resetReshadeEffectParams(loadout, name, params) }, highlightColor = NavHighlight, tapToSelect = true)
                        .clickable { resetReshadeEffectParams(loadout, name, params) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            for (p in params) {
                Spacer(Modifier.height(SettingItemGap))
                ReshadeEffectParamControl(loadout, name, p)
            }
        }
    }
}

@Composable
private fun ReshadeRowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (!enabled) Modifier
                else Modifier
                    .paneNavItem(cornerRadius = 6.dp, onActivate = onClick, highlightColor = NavHighlight, tapToSelect = true)
                    .clickable { onClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.3f),
            modifier = Modifier.size(SettingIconSize)
        )
    }
}

@Composable
private fun ReshadeModeSelector(mode: String, onChange: (String) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ReshadeLoadout.MODE_SOLO to stringResource(R.string.reshade_mode_solo),
                ReshadeLoadout.MODE_STACK to stringResource(R.string.reshade_mode_stack),
            ).forEach { (value, label) ->
                val selected = mode == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) AccentBlue.copy(alpha = 0.16f) else Color.Transparent)
                        .border(1.dp, if (selected) AccentBlue else CardBorder, RoundedCornerShape(8.dp))
                        .paneNavItem(cornerRadius = 8.dp, onActivate = { onChange(value) }, highlightColor = NavHighlight, tapToSelect = true)
                        .clickable { onChange(value) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) AccentBlue else TextPrimary,
                        fontSize = SettingLabelSize,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(SettingTightGap))
        Text(
            text = if (mode == ReshadeLoadout.MODE_SOLO) stringResource(R.string.reshade_mode_solo_hint)
            else stringResource(R.string.reshade_mode_stack_hint),
            color = TextSecondary,
            fontSize = SettingLabelSize
        )
    }
}

// call on the main thread with an already-scanned list; do the disk scan off-thread.
private fun rescanReshadeEffects(
    state: GameSettingsStateHolder,
    effects: List<ReshadeManager.ReshadeEffect>,
) {
    state.reshadeEffects.value = effects
    state.reshadeLoadout.reconcile(effects)
}

// Restore one effect's params to its .fx defaults (re-seed with no saved overrides).
private fun resetReshadeEffectParams(
    loadout: ReshadeLoadoutState,
    name: String,
    params: List<ReshadeManager.ReshadeParam>,
) {
    val tmp = LinkedHashMap<String, Float>()
    for (p in params) ReshadeManager.seedValues(p, null, tmp)
    for ((k, v) in tmp) loadout.setParam(name, k, v)
}

private fun reshadeColorComponentLabel(c: Int, components: Int): String = when {
    components >= 4 && c == 3 -> "A"
    c == 0 -> "R"
    c == 1 -> "G"
    c == 2 -> "B"
    else -> (c + 1).toString()
}

// values are keyed "<effect>::<uniform>" so effects sharing a uniform name never collide.
@Composable
private fun ReshadeEffectParamControl(
    loadout: ReshadeLoadoutState,
    effect: String,
    p: ReshadeManager.ReshadeParam,
) {
    when (p.type) {
        ReshadeManager.ParamType.BOOL -> {
            val v = loadout.paramValue(effect, p.name, p.defaultValue)
            SettingCheckbox(
                label = p.label,
                checked = v != 0f,
                onCheckedChange = { loadout.setParam(effect, p.name, if (it) 1f else 0f) }
            )
        }
        ReshadeManager.ParamType.COMBO -> {
            val options = p.options ?: emptyList()
            val v = loadout.paramValue(effect, p.name, p.defaultValue).roundToInt()
            SettingDropdown(
                label = p.label,
                entries = options,
                selectedIndex = v.coerceIn(0, (options.size - 1).coerceAtLeast(0)),
                onSelected = { loadout.setParam(effect, p.name, it.toFloat()) }
            )
        }
        ReshadeManager.ParamType.COLOR -> ReshadeEffectColorControl(loadout, effect, p)
        ReshadeManager.ParamType.INT ->
            ReshadeFloatSlider(
                label = p.label,
                value = loadout.paramValue(effect, p.name, p.defaultValue),
                min = p.min,
                max = p.max,
                step = if (p.step > 0f) p.step else 1f,
                whole = true,
                onValueChange = { loadout.setParam(effect, p.name, it) }
            )
        else -> // FLOAT
            ReshadeFloatSlider(
                label = p.label,
                value = loadout.paramValue(effect, p.name, p.defaultValue),
                min = p.min,
                max = p.max,
                step = p.step,
                whole = false,
                onValueChange = { loadout.setParam(effect, p.name, it) }
            )
    }
}

// swatch collapses the per-channel sliders, keeping color-grading effects (many float3s) readable.
@Composable
private fun ReshadeEffectColorControl(
    loadout: ReshadeLoadoutState,
    effect: String,
    p: ReshadeManager.ReshadeParam,
) {
    val expanded = remember(effect, p.name) { mutableStateOf(false) }

    fun comp(c: Int): Float =
        loadout.paramValue(effect, p.name + "_" + c, p.componentDefaults?.getOrNull(c) ?: 0f)
    val r = comp(0)
    val g = if (p.components > 1) comp(1) else r
    val b = if (p.components > 2) comp(2) else r
    val swatch = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .paneNavItem(
                cornerRadius = 8.dp,
                onActivate = { expanded.value = !expanded.value },
                highlightColor = NavHighlight,
                tapToSelect = true,
            )
            .clickable { expanded.value = !expanded.value }
            .padding(vertical = 4.dp)
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(swatch)
                .border(1.dp, CardBorder, RoundedCornerShape(5.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = p.label,
            color = TextPrimary,
            fontSize = SettingLabelSize,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded.value) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = TextSecondary
        )
    }
    if (expanded.value) {
        for (c in 0 until p.components) {
            val key = p.name + "_" + c
            Spacer(Modifier.height(SettingTightGap))
            ReshadeFloatSlider(
                label = reshadeColorComponentLabel(c, p.components),
                value = loadout.paramValue(effect, key, p.componentDefaults?.getOrNull(c) ?: 0f),
                min = 0f,
                max = 1f,
                step = 0.01f,
                whole = false,
                onValueChange = { loadout.setParam(effect, key, it) }
            )
        }
    }
}

// [whole] snaps to integers (INT), otherwise snaps to [step].
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReshadeFloatSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    whole: Boolean,
    onValueChange: (Float) -> Unit
) {
    val lo = min
    val hi = if (max > min) max else min + 1f
    val inc = if (whole) 1f else (if (step > 0f) step else (hi - lo) / 100f)
    val valueText = if (whole) value.roundToInt().toString()
        else String.format(java.util.Locale.US, "%.2f", value)

    fun snap(raw: Float): Float {
        val v = when {
            whole -> raw.roundToInt().toFloat()
            step > 0f -> lo + Math.round((raw - lo) / step) * step
            else -> raw
        }
        return v.coerceIn(lo, hi)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = TextSecondary,
                fontSize = SettingLabelSize,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AccentBlue.copy(alpha = 0.1f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    valueText,
                    color = AccentBlue,
                    fontSize = SettingLabelSize,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(SettingTightGap))
        Slider(
            value = value.coerceIn(lo, hi),
            onValueChange = { onValueChange(snap(it)) },
            valueRange = lo..hi,
            modifier = Modifier
                .fillMaxWidth()
                .height(SettingSliderHeight)
                .controllerSliderEscape()
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onAdjust = { d -> onValueChange((value + d * inc).coerceIn(lo, hi)) },
                    highlightColor = NavHighlight,
                ),
            colors = settingSliderColors(),
            track = { SettingSliderTrack(it) },
            thumb = {
                Box(
                    modifier = Modifier
                        .size(SettingSliderThumbSize)
                        .clip(RoundedCornerShape(50))
                        .background(AccentBlue)
                        .border(2.dp, CardSurface, RoundedCornerShape(50))
                )
            }
        )
    }
}


// top-level Dialog because the settings screen is itself inside a dialog.
@Composable
private fun ReshadeCatalogDialog(
    state: GameSettingsStateHolder,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    // own registry, registered as the parent's overlay, else items pile into the settings pane registry.
    val parentNav = LocalPaneNav.current
    val catalogNav = remember { PaneNavRegistry().apply { stableCursor = true } }
    catalogNav.controllerActive = parentNav?.controllerActive == true
    DisposableEffect(Unit) {
        catalogNav.reset()
        parentNav?.overlay = catalogNav
        parentNav?.overlayClose = onDismiss
        onDispose {
            if (parentNav?.overlay === catalogNav) {
                parentNav.overlay = null
                parentNav.overlayClose = null
            }
        }
    }

    var catalog by remember { mutableStateOf<List<ReshadeCatalogEntry>>(emptyList()) }
    var installed by remember { mutableStateOf(setOf<String>()) }
    var source by remember { mutableStateOf(ReshadeCatalog.Source.NONE) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var phaseLabel by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ReshadeCatalogEntry?>(null) }
    // id of the just-downloaded row; it must be the sole nav entry (it also gates Done's isEntry) or two
    // entries fight over the cursor.
    var refocusId by remember { mutableStateOf<String?>(null) }
    var reseatPending by remember { mutableStateOf(false) }

    // separate window, so UnifiedActivity.dispatchKeyEvent never sees its D-pad events; the root holds
    // focus and previews them into catalogNav instead of letting default focus traversal take them.
    val rootFocus = remember { FocusRequester() }
    // hoisted out of ReshadeCatalogSearchField so the preview handler can stand down while typing.
    var searchEditing by remember { mutableStateOf(false) }

    LaunchedEffect(searchEditing) {
        if (!searchEditing) runCatching { rootFocus.requestFocus() }
    }

    val downloadingLabel = stringResource(R.string.reshade_catalog_downloading)
    val installingLabel = stringResource(R.string.reshade_catalog_installing)

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { ReshadeCatalog.loadCached(context) }
        catalog = result.entries
        source = result.source
        installed = withContext(Dispatchers.IO) { ReshadeManager.scanEffectNames(context).toSet() }
        loading = false
    }

    // Only a live NETWORK load can fetch not-yet-installed effects; CACHE/NONE = offline.
    val offline = source != ReshadeCatalog.Source.NETWORK

    val inLoadout = state.reshadeLoadout.order.toSet()

    // catalog entries plus locally-installed effects absent from the catalog (user-dropped).
    val groups = remember(catalog, installed, query) {
        val q = query.trim()
        val catIds = catalog.map { it.id }.toSet()
        val extras = installed.filter { it !in catIds }
            .map { ReshadeCatalogEntry(it, it, "", "Installed", "", "", "", 0L, "", 1) }
        val all = (catalog + extras).filter { e ->
            q.isEmpty() || e.name.contains(q, true) || e.author.contains(q, true) || e.category.contains(q, true)
        }
        val byName = compareBy<ReshadeCatalogEntry> { it.name.lowercase() }
        Pair(
            all.filter { it.id in installed }.sortedWith(byName),
            all.filter { it.id !in installed }.sortedWith(byName),
        )
    }
    val installedRows = groups.first
    val availableRows = groups.second

    // one-shot re-seat of the nav cursor onto the downloaded row after it re-composes under Installed;
    // refocusId is deliberately kept so that row stays the entry until the next download.
    LaunchedEffect(reseatPending, installedRows) {
        val id = refocusId ?: return@LaunchedEffect
        if (reseatPending && installedRows.any { it.id == id }) {
            catalogNav.reset()
            reseatPending = false
        }
    }

    fun startDownload(entry: ReshadeCatalogEntry) {
        downloadingId = entry.id
        phaseLabel = downloadingLabel; progress = 0f; errorMsg = null
        scope.launch {
            val ok = ReshadeDownloader.install(context, entry) { phase, f ->
                mainHandler.post {
                    phaseLabel = if (phase == ReshadeDownloader.Phase.EXTRACT) installingLabel else downloadingLabel
                    progress = f
                }
            }
            downloadingId = null
            if (ok) {
                installed = installed + entry.id
                val effects = withContext(Dispatchers.IO) { ReshadeManager.scanEffects(context) }
                rescanReshadeEffects(state, effects)
                effects.firstOrNull { it.name.equals(entry.id, ignoreCase = true) || it.name.equals(entry.name, ignoreCase = true) }
                    ?.let { state.reshadeLoadout.add(it, null) }
                refocusId = entry.id
                reseatPending = true
            } else {
                errorMsg = context.getString(R.string.reshade_catalog_download_failed, entry.name)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
      CompositionLocalProvider(LocalPaneNav provides catalogNav) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(SettingGroupCorner))
                .background(ContentBg)
                .border(1.dp, CardBorder, RoundedCornerShape(SettingGroupCorner))
                // onPreviewKeyEvent must sit before focusRequester/focusable or the keys go elsewhere.
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // while editing, let the field consume typing + directions; only B/Back exits editing.
                    if (searchEditing) {
                        return@onPreviewKeyEvent when (ev.key) {
                            Key.ButtonB, Key.Back -> { searchEditing = false; true }
                            else -> false
                        }
                    }
                    when (ev.key) {
                        Key.DirectionUp -> { catalogNav.navDir(PANE_DIR_UP); true }
                        Key.DirectionDown -> { catalogNav.navDir(PANE_DIR_DOWN); true }
                        Key.DirectionLeft -> { catalogNav.navDir(PANE_DIR_LEFT); true }
                        Key.DirectionRight -> { catalogNav.navDir(PANE_DIR_RIGHT); true }
                        Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            catalogNav.navDir(PANE_DIR_ACTIVATE); true
                        }
                        Key.ButtonB, Key.Back -> { onDismiss(); true }
                        else -> false
                    }
                }
                .focusRequester(rootFocus)
                .focusable()
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.reshade_catalog_title),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.reshade_catalog_done),
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (offline && !loading) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        if (source == ReshadeCatalog.Source.CACHE) R.string.reshade_catalog_offline_cache
                        else R.string.reshade_catalog_offline_none
                    ),
                    color = WarningAmber,
                    fontSize = SettingLabelSize
                )
            }

            Spacer(Modifier.height(10.dp))
            ReshadeCatalogSearchField(
                query = query,
                onQueryChange = { query = it },
                editing = searchEditing,
                onEditingChange = { searchEditing = it },
            )
            Spacer(Modifier.height(8.dp))

            errorMsg?.let {
                Text(it, color = DangerRed, fontSize = SettingLabelSize, modifier = Modifier.padding(vertical = 4.dp))
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.reshade_catalog_downloading), color = TextSecondary, fontSize = SettingValueSize)
                }
            } else {
                // NOT LazyColumn: it only composes visible rows, so paneNav can't register past them.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (installedRows.isNotEmpty()) {
                        ReshadeGroupHeader(stringResource(R.string.reshade_catalog_installed, installedRows.size))
                        installedRows.forEach { entry ->
                            key("i_${entry.id}") {
                                val effect = state.reshadeEffects.value.firstOrNull {
                                    it.name.equals(entry.id, ignoreCase = true) || it.name.equals(entry.name, ignoreCase = true)
                                }
                                val member = effect != null && state.reshadeLoadout.contains(effect.name)
                                ReshadeCatalogRow(
                                    entry = entry,
                                    isInstalled = true,
                                    isSelected = member,
                                    isBusy = downloadingId == entry.id,
                                    offline = offline,
                                    phaseLabel = if (downloadingId == entry.id) phaseLabel else "",
                                    progress = if (downloadingId == entry.id) progress else null,
                                    installingLabel = installingLabel,
                                    isEntry = entry.id == refocusId,
                                    onClick = {
                                        // stays open so several effects can be added in one pass.
                                        if (effect != null) {
                                            when {
                                                member -> state.reshadeLoadout.remove(effect.name)
                                                state.reshadeLoadout.isFull() ->
                                                    errorMsg = context.getString(R.string.reshade_loadout_full)
                                                else -> state.reshadeLoadout.add(effect, null)
                                            }
                                        }
                                    },
                                    onDelete = { pendingDelete = entry }
                                )
                            }
                        }
                    }
                    if (availableRows.isNotEmpty()) {
                        ReshadeGroupHeader(stringResource(R.string.reshade_catalog_available, availableRows.size))
                        availableRows.forEach { entry ->
                            key("a_${entry.id}") {
                                ReshadeCatalogRow(
                                    entry = entry,
                                    isInstalled = false,
                                    isSelected = false,
                                    isBusy = downloadingId == entry.id,
                                    offline = offline,
                                    phaseLabel = if (downloadingId == entry.id) phaseLabel else "",
                                    progress = if (downloadingId == entry.id) progress else null,
                                    installingLabel = installingLabel,
                                    onClick = {
                                        when {
                                            downloadingId != null -> {}                 // one at a time
                                            offline -> errorMsg = context.getString(R.string.reshade_catalog_needs_connection)
                                            else -> startDownload(entry)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    if (installedRows.isEmpty() && availableRows.isEmpty()) {
                        Text(
                            if (query.isNotBlank()) stringResource(R.string.reshade_catalog_empty_query, query)
                            else stringResource(R.string.reshade_catalog_empty),
                            color = TextSecondary,
                            fontSize = SettingValueSize,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
        }
      }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.reshade_catalog_delete_title, entry.name)) },
            text = { Text(stringResource(R.string.reshade_catalog_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = entry.id
                    pendingDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ReshadeManager.deleteEffect(context, id) }
                        if (ok) {
                            installed = installed - id                 // row drops back to "Available"
                            val effects = withContext(Dispatchers.IO) { ReshadeManager.scanEffects(context) }
                            state.reshadeLoadout.order
                                .filter { it.equals(id, ignoreCase = true) || it.equals(entry.name, ignoreCase = true) }
                                .toList()
                                .forEach { state.reshadeLoadout.remove(it) }
                            rescanReshadeEffects(state, effects)
                        } else {
                            errorMsg = context.getString(R.string.reshade_catalog_delete_failed, entry.name)
                        }
                    }
                }) {
                    Text(stringResource(R.string.reshade_catalog_delete_confirm), color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.reshade_catalog_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun ReshadeCatalogSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val tapInteraction = remember { MutableInteractionSource() }
    // focusable only while explicitly editing; otherwise a recompose that disposes the focused node
    // hands IME focus here and pops the keyboard.
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        textStyle = TextStyle(color = TextPrimary, fontSize = SettingValueSize),
        cursorBrush = SolidColor(AccentBlue),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .height(EnvVarControlHeight)
            .paneNavItem(cornerRadius = 8.dp, onActivate = { onEditingChange(true) }, highlightColor = NavHighlight)
            .focusRequester(focusRequester)
            .focusProperties { canFocus = editing }
            .onFocusChanged { if (!it.isFocused) onEditingChange(false) }
            .controllerTextFieldEscape(),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(InputSurface)
                    .border(1.dp, InputBorder, RoundedCornerShape(8.dp))
                    // tap must arm editing first, since canFocus is gated off until then.
                    .clickable(interactionSource = tapInteraction, indication = null) { onEditingChange(true) }
                    .padding(horizontal = SettingFieldHorizontalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = TextDim, modifier = Modifier.size(SettingIconSize))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.reshade_catalog_search), color = TextDim, fontSize = SettingValueSize)
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Close, contentDescription = null, tint = TextDim,
                        modifier = Modifier
                            .size(SettingIconSize)
                            .paneNavItem(cornerRadius = 6.dp, onActivate = { onQueryChange("") }, highlightColor = NavHighlight)
                            .clickable { onQueryChange("") }
                    )
                }
            }
        }
    )
}

@Composable
private fun ReshadeGroupHeader(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = SettingSectionLabelSize,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

@Composable
private fun ReshadeCatalogRow(
    entry: ReshadeCatalogEntry,
    isInstalled: Boolean,
    isSelected: Boolean,
    isBusy: Boolean,
    offline: Boolean,
    phaseLabel: String,
    progress: Float?,
    installingLabel: String,
    isEntry: Boolean = false,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            .paneNavItem(cornerRadius = 12.dp, onActivate = { if (!isBusy) onClick() }, isEntry = isEntry, highlightColor = NavHighlight)
            .clickable(enabled = !isBusy, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, color = TextPrimary, fontSize = SettingValueSize, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = buildString {
                    if (entry.category.isNotBlank()) append(entry.category)
                    if (entry.author.isNotBlank()) { if (isNotEmpty()) append(" · "); append(entry.author) }
                    if (entry.license.isNotBlank()) { if (isNotEmpty()) append(" · "); append(entry.license) }
                }
                if (sub.isNotBlank()) {
                    Text(sub, color = TextSecondary, fontSize = SettingLabelSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (entry.description.isNotBlank()) {
                    Text(entry.description, color = TextDim, fontSize = SettingLabelSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!isBusy) {
                Spacer(Modifier.width(10.dp))
                when {
                    isInstalled && onDelete != null -> {
                        if (isSelected) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DangerRed.copy(alpha = 0.14f))
                                .border(1.dp, DangerRed.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                                .paneNavItem(cornerRadius = 8.dp, onActivate = onDelete, highlightColor = NavHighlight, tapToSelect = true)
                                .clickable(onClick = onDelete),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.reshade_catalog_delete_cd, entry.name),
                                tint = DangerRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    offline -> Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = TextDim, modifier = Modifier.size(18.dp))
                    else -> {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentBlue.copy(alpha = 0.14f))
                                .border(1.dp, AccentBlue.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.common_ui_download), color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        if (isBusy) {
            Spacer(Modifier.height(6.dp))
            val frac = progress?.coerceIn(0f, 1f) ?: 0f
            val barColor = if (phaseLabel == installingLabel) Color(0xFF4CAF50) else AccentBlue
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(phaseLabel, color = TextSecondary, fontSize = SettingLabelSize)
                Text("${(frac * 100).toInt()}%", color = TextSecondary, fontSize = SettingLabelSize)
            }
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SliderInactive)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor)
                )
            }
        }
    }
}

@Composable
private fun SteamSection(state: GameSettingsStateHolder) {

    // Steam Launcher is the default path; enabling it unchecks every other Steam mode (mutually exclusive launch paths).
    val onSteamLauncherChange: (Boolean) -> Unit = { enabled ->
        state.steamLauncher.value = enabled
        if (enabled) {
            state.useLegacyLauncher.value = false
            state.runtimePatcher.value = false
            state.steamOfflineMode.value = false
        }
    }

    SubsectionLabel(stringResource(R.string.steam_section_real_client))
    Spacer(Modifier.height(8.dp))
    SettingGroup {
        SettingCheckbox(
            label = "Steam Launcher",
            checked = state.steamLauncher.value,
            onCheckedChange = onSteamLauncherChange
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Run the game through the in-Wine Steam Launcher (recommended). Disables other Steam launch modes.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }

    Spacer(Modifier.height(SettingItemGap))

    SubsectionLabel(stringResource(R.string.steam_section_emulator))
    Spacer(Modifier.height(8.dp))
    SettingGroup {
        SettingCheckbox(
            label = stringResource(R.string.shortcuts_properties_use_legacy_launcher),
            checked = state.useLegacyLauncher.value,
            onCheckedChange = {
                state.useLegacyLauncher.value = it
                if (it) {
                    state.steamLauncher.value = false
                }
            }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.shortcuts_properties_use_legacy_launcher_description),
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(SettingItemGap))

        // Use Steam Input — hidden in the UI for now (state/persistence kept intact).
        /*
        SettingCheckbox(
            label = stringResource(R.string.shortcuts_properties_use_steam_input),
            checked = state.useSteamInput.value,
            onCheckedChange = {
                state.useSteamInput.value = it
                if (it) state.steamLauncher.value = false
            }
        )
        Spacer(Modifier.height(SettingItemGap))
        */

        SettingCheckbox(
            label = stringResource(R.string.shortcuts_properties_steam_offline_mode),
            checked = state.steamOfflineMode.value,
            onCheckedChange = {
                state.steamOfflineMode.value = it
                if (it) state.steamLauncher.value = false
            },
            enabled = state.useLegacyLauncher.value
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.shortcuts_properties_steam_offline_mode_description),
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.alpha(if (state.useLegacyLauncher.value) 1f else 0.4f)
        )
        Spacer(Modifier.height(SettingItemGap))

        SettingCheckbox(
            label = stringResource(R.string.shortcuts_properties_runtime_patcher),
            checked = state.runtimePatcher.value,
            onCheckedChange = {
                state.runtimePatcher.value = it
                if (it) {
                    state.steamLauncher.value = false
                }
            },
            enabled = state.useLegacyLauncher.value
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.shortcuts_properties_runtime_patcher_description),
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.alpha(if (state.useLegacyLauncher.value) 1f else 0.4f)
        )
    }
}

// Section 3: Wine
@Composable
private fun WineSection(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks
) {
    val isContainer = state.isContainerEditMode.value

    SettingGroup {
        // LC_ALL with locale picker. Emulator selection lives in Advanced.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.weight(1f)) {
                SettingTextField(
                    label = stringResource(R.string.container_config_lc_all),
                    value = state.lcAll.value,
                    onValueChange = { state.lcAll.value = it }
                )
            }
            Spacer(Modifier.width(8.dp))
            var showLocalePicker by remember { mutableStateOf(false) }
            val localeMenuOffset = rememberSmartDropdownOffset()
            Box(
                modifier = Modifier.padding(top = 22.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(InputSurface)
                        .border(1.dp, InputBorder, RoundedCornerShape(8.dp))
                        .paneNavItem(cornerRadius = 8.dp, onActivate = { showLocalePicker = true }, highlightColor = NavHighlight)
                        .smartDropdownAnchor(offset = localeMenuOffset) { showLocalePicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showLocalePicker,
                    onDismissRequest = { showLocalePicker = false },
                    offset = localeMenuOffset.value,
                    shape = RoundedCornerShape(8.dp),
                    containerColor = CardSurface,
                    modifier = Modifier.height(300.dp)
                ) {
                    state.localeOptions.value.forEach { locale ->
                        DropdownMenuItem(
                            text = {
                                Text(locale, color = TextPrimary, fontSize = SettingValueSize)
                            },
                            onClick = {
                                state.lcAll.value = locale
                                showLocalePicker = false
                            }
                        )
                    }
                }
            }
        }
    }

    if (state.desktopThemeEntries.value.isNotEmpty()) {
        Spacer(Modifier.height(SettingItemGap))
        SettingGroup {
            SettingDropdown(
                label = stringResource(R.string.settings_general_theme),
                entries = state.desktopThemeEntries.value,
                selectedIndex = state.selectedDesktopTheme.intValue,
                onSelected = { state.selectedDesktopTheme.intValue = it }
            )

            if (isContainer && state.desktopBackgroundTypeEntries.value.isNotEmpty()) {
                Spacer(Modifier.height(SettingItemGap))
                SettingDropdown(
                    label = stringResource(R.string.settings_general_background),
                    entries = state.desktopBackgroundTypeEntries.value,
                    selectedIndex = state.selectedDesktopBackgroundType.intValue,
                    onSelected = { state.selectedDesktopBackgroundType.intValue = it }
                )

                val bgType = WineThemeManager.BackgroundType.values()
                    .getOrNull(state.selectedDesktopBackgroundType.intValue)
                when (bgType) {
                    WineThemeManager.BackgroundType.COLOR -> {
                        Spacer(Modifier.height(SettingItemGap))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Box(Modifier.weight(1f)) {
                                SettingTextField(
                                    label = stringResource(R.string.settings_general_background_color_hex),
                                    value = state.desktopBackgroundColor.value,
                                    onValueChange = { state.desktopBackgroundColor.value = it }
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            val previewColor = remember(state.desktopBackgroundColor.value) {
                                runCatching {
                                    Color(android.graphics.Color.parseColor(state.desktopBackgroundColor.value))
                                }.getOrDefault(Color(0xFF0277BD))
                            }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(previewColor)
                                    .border(1.dp, InputBorder, RoundedCornerShape(8.dp))
                            )
                        }
                    }
                    WineThemeManager.BackgroundType.IMAGE -> {
                        Spacer(Modifier.height(SettingItemGap))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(SettingFieldCorner))
                                .border(1.dp, InputBorder, RoundedCornerShape(SettingFieldCorner))
                                .background(InputSurface)
                                .clickable { callbacks.onPickWallpaper() }
                                .paneNavItem(
                                    cornerRadius = SettingFieldCorner,
                                    onActivate = { callbacks.onPickWallpaper() },
                                    highlightColor = NavHighlight,
                                    tapToSelect = true,
                                )
                                .padding(horizontal = SettingFieldHorizontalPadding, vertical = SettingFieldVerticalPadding),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (state.desktopWallpaperSelected.value) {
                                    stringResource(R.string.settings_general_wallpaper_selected)
                                } else {
                                    stringResource(R.string.settings_general_select_wallpaper)
                                },
                                color = if (state.desktopWallpaperSelected.value) TextPrimary else TextSecondary,
                                fontSize = SettingLabelSize,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.desktopWallpaperSelected.value) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    if (isContainer && state.mouseWarpOverrideEntries.value.isNotEmpty()) {
        Spacer(Modifier.height(SettingItemGap))
        SettingGroup {
            SettingDropdown(
                label = stringResource(R.string.container_wine_mouse_warp_override),
                entries = state.mouseWarpOverrideEntries.value,
                selectedIndex = state.selectedMouseWarpOverride.intValue,
                onSelected = { state.selectedMouseWarpOverride.intValue = it }
            )
        }
    }
}

@Composable
private fun ComponentsSection(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks
) {

    if (state.directXComponents.value.isNotEmpty()) {
        SubsectionLabel(stringResource(R.string.container_wine_directx))
        Spacer(Modifier.height(8.dp))
        SettingGroup {
            val items = state.directXComponents.value
            items.chunked(2).forEachIndexed { rowIndex, pair ->
                if (rowIndex > 0) Spacer(Modifier.height(SettingItemGap))
                SettingPairRow {
                    pair.forEachIndexed { colIndex, component ->
                        val index = rowIndex * 2 + colIndex
                        Box(Modifier.weight(1f)) {
                            SettingDropdown(
                                label = component.label,
                                entries = state.winComponentEntries.value,
                                selectedIndex = component.selectedIndex,
                                onSelected = { newVal ->
                                    callbacks.onUpdateWinComponent(true, index, newVal)
                                }
                            )
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(SettingSectionGap))
    }

    if (state.generalComponents.value.isNotEmpty()) {
        SubsectionLabel(stringResource(R.string.settings_general_title))
        Spacer(Modifier.height(8.dp))
        SettingGroup {
            val items = state.generalComponents.value
            val showZink = state.isArm64EC.value
            val totalCells = items.size + if (showZink) 1 else 0
            val rowCount = (totalCells + 1) / 2
            for (rowIndex in 0 until rowCount) {
                if (rowIndex > 0) Spacer(Modifier.height(SettingItemGap))
                SettingPairRow {
                    for (colIndex in 0..1) {
                        val cell = rowIndex * 2 + colIndex
                        Box(Modifier.weight(1f)) {
                            if (cell < items.size) {
                                val component = items[cell]
                                SettingDropdown(
                                    label = component.label,
                                    entries = state.winComponentEntries.value,
                                    selectedIndex = component.selectedIndex,
                                    onSelected = { newVal ->
                                        callbacks.onUpdateWinComponent(false, cell, newVal)
                                    }
                                )
                            } else if (cell == items.size && showZink) {
                                SettingDropdown(
                                    label = stringResource(R.string.container_zink_mode),
                                    entries = state.zinkModeEntries.value,
                                    selectedIndex = state.selectedZinkMode.intValue,
                                    onSelected = { state.selectedZinkMode.intValue = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun findKnownEnvVar(name: String): Array<String>? =
    EnvVarsView.knownEnvVars.firstOrNull { it[0] == name }

private fun defaultValueForEnvVar(name: String): String? {
    val known = findKnownEnvVar(name) ?: return null
    return if (known.getOrNull(1) == "TEXT") known.getOrNull(2) else null
}

@Composable
private fun VariablesSection(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks
) {
    val isContainer = state.isContainerEditMode.value
    val hasDraftEnvVar = state.envVars.value.any { it.key.isBlank() }

    // Ensure the required toggles exist upon entering the variables section
    LaunchedEffect(Unit) {
        val current = state.envVars.value.toMutableList()
        var changed = false
        if (current.none { it.key == "WINEESYNC" }) {
            current.add(EnvVarItem("WINEESYNC", "1"))
            changed = true
        }
        if (current.none { it.key == "WINENTSYNC" }) {
            current.add(EnvVarItem("WINENTSYNC", "0"))
            changed = true
        }
        if (changed) {
            state.envVars.value = current
        }
    }

    if (isContainer) {
        SubsectionLabel(stringResource(R.string.container_config_variables))
        Spacer(Modifier.height(8.dp))
    }

    SettingGroup {
        if (state.envVars.value.isEmpty()) {
            Text(
                stringResource(R.string.common_ui_none),
                color = TextDim,
                fontSize = SettingValueSize,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            state.envVars.value.forEachIndexed { index, envVar ->
                if (index > 0) {
                    Spacer(Modifier.height(1.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(DividerColor)
                    )
                    Spacer(Modifier.height(1.dp))
                }
                EnvVarRow(
                    name = envVar.key,
                    value = envVar.value,
                    onNameChange = { newKey ->
                        val normalizedKey = newKey.trim()
                        val list = state.envVars.value.toMutableList()
                        if (index in list.indices) {
                            val newValue = envVar.value.ifBlank {
                                defaultValueForEnvVar(normalizedKey) ?: ""
                            }
                            list[index] = EnvVarItem(normalizedKey, newValue)
                            state.envVars.value = list
                        }
                    },
                    onValueChange = { v ->
                        val list = state.envVars.value.toMutableList()
                        list[index] = EnvVarItem(envVar.key, v)
                        state.envVars.value = list
                    },
                    onRemove = { callbacks.onRemoveEnvVar(index) }
                )
            }
        }

        Spacer(Modifier.height(SettingItemGap))

        if (!hasDraftEnvVar) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentBlue.copy(alpha = 0.08f))
                    .border(1.dp, AccentBlue.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .clickable {
                        state.envVars.value = state.envVars.value + EnvVarItem("", "")
                    }
                    .paneNavItem(
                        cornerRadius = 8.dp,
                        onActivate = {
                            state.envVars.value = state.envVars.value + EnvVarItem("", "")
                        },
                        highlightColor = NavHighlight,
                        tapToSelect = true,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(SettingIconSize)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.common_ui_add),
                        color = AccentBlue,
                        fontSize = SettingValueSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

}

@Composable
private fun DrivesSection(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks
) {
    SettingGroup {
        val drives = state.drivesList.value
        if (drives.isEmpty()) {
            Text(
                stringResource(R.string.common_ui_none),
                color = TextDim,
                fontSize = SettingValueSize,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            drives.forEachIndexed { index, drive ->
                val otherLetters =
                    drives
                        .mapIndexedNotNull { otherIndex, otherDrive ->
                            otherDrive.letter.takeUnless { otherIndex == index }?.uppercase()
                        }.toSet()
                val availableLetters =
                    SelectableDriveLetters.filter { letter ->
                        letter.equals(drive.letter, ignoreCase = true) || letter !in otherLetters
                    }

                if (index > 0) {
                    Spacer(Modifier.height(1.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
                    Spacer(Modifier.height(1.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DriveLetterSelector(
                        selectedLetter = drive.letter.uppercase(),
                        canChangeLetter = drive.canChangeLetter,
                        availableLetters = availableLetters,
                        onSelected = { callbacks.onDriveLetterChanged(index, it) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(InputSurface)
                            .border(1.dp, InputBorder, RoundedCornerShape(8.dp))
                            .clickable { callbacks.onPickDrivePath(index) }
                            .paneNavItem(
                                cornerRadius = 8.dp,
                                onActivate = { callbacks.onPickDrivePath(index) },
                                highlightColor = NavHighlight,
                                tapToSelect = true,
                            )
                            .padding(horizontal = SettingFieldHorizontalPadding, vertical = SettingFieldVerticalPadding)
                    ) {
                        Text(
                            drive.path.ifEmpty { stringResource(R.string.common_ui_select_folder) },
                            color = if (drive.path.isEmpty()) TextDim else TextPrimary,
                            fontSize = SettingLabelSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(DangerRed.copy(alpha = 0.1f))
                            .clickable { callbacks.onRemoveDrive(index) }
                            .paneNavItem(
                                cornerRadius = 6.dp,
                                onActivate = { callbacks.onRemoveDrive(index) },
                                highlightColor = NavHighlight,
                                tapToSelect = true,
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = null,
                            tint = DangerRed,
                            modifier = Modifier.size(SettingControlIconSize)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(SettingItemGap))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(AccentBlue.copy(alpha = 0.08f))
                .border(1.dp, AccentBlue.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .clickable { callbacks.onAddDrive() }
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = { callbacks.onAddDrive() },
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(SettingIconSize)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.common_ui_add),
                    color = AccentBlue,
                    fontSize = SettingValueSize,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun DriveLetterSelector(
    selectedLetter: String,
    canChangeLetter: Boolean,
    availableLetters: List<String>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember(selectedLetter, availableLetters) { mutableStateOf(false) }
    val menuOffset = rememberSmartDropdownOffset()
    val showDropdown = canChangeLetter && availableLetters.size > 1

    Box {
        Row(
            modifier =
                Modifier
                    .widthIn(min = 64.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AccentBlue.copy(alpha = 0.1f))
                    .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .paneNavItem(cornerRadius = 6.dp, onActivate = { expanded = true }, highlightColor = NavHighlight)
                    .smartDropdownAnchor(enabled = showDropdown, offset = menuOffset) { expanded = true }
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "$selectedLetter:",
                color = AccentBlue,
                fontSize = SettingValueSize,
                fontWeight = FontWeight.SemiBold,
            )
            if (showDropdown) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        DropdownMenu(
            expanded = showDropdown && expanded,
            onDismissRequest = { expanded = false },
            offset = menuOffset.value,
            shape = RoundedCornerShape(8.dp),
            containerColor = CardSurface,
            modifier = Modifier.widthIn(min = 88.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                availableLetters.forEach { letter ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "$letter:",
                                color = if (letter == selectedLetter) AccentBlue else TextPrimary,
                                fontSize = SettingValueSize,
                                fontWeight =
                                    if (letter == selectedLetter) FontWeight.SemiBold
                                    else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            onSelected(letter)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EnvVarRow(
    name: String,
    value: String,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null
) {
    var nameMenuExpanded by remember { mutableStateOf(false) }
    val nameMenuOffset = rememberSmartDropdownOffset()
    var isCustomMode by remember(name) {
        mutableStateOf(name.isNotEmpty() && findKnownEnvVar(name) == null)
    }
    var customText by remember(name) { mutableStateOf(if (isCustomMode) name else "") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Name dropdown or custom text field
        Box(modifier = Modifier.weight(1.6f)) {
            if (isCustomMode) {
                BasicTextField(
                    value = customText,
                    onValueChange = { newText ->
                        customText = newText
                        onNameChange(newText.trim())
                    },
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = SettingValueSize
                    ),
                    cursorBrush = SolidColor(AccentBlue),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EnvVarControlHeight)
                        .controllerTextFieldEscape()
                        .clip(RoundedCornerShape(8.dp))
                        .background(InputSurface)
                        .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = SettingFieldHorizontalPadding),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (customText.isEmpty()) {
                                Text(
                                    stringResource(R.string.container_config_new_env_var),
                                    color = TextDim,
                                    fontSize = SettingValueSize
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EnvVarControlHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(InputSurface)
                        .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .paneNavItem(cornerRadius = 8.dp, onActivate = { nameMenuExpanded = true }, highlightColor = NavHighlight)
                        .smartDropdownAnchor(offset = nameMenuOffset) { nameMenuExpanded = true }
                        .padding(horizontal = SettingFieldHorizontalPadding),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (name.isEmpty()) stringResource(R.string.container_config_new_env_var) else name,
                            color = if (name.isEmpty()) TextDim else TextPrimary,
                            fontSize = SettingValueSize,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(SettingControlIconSize)
                        )
                    }
                }
            }
            DropdownMenu(
                expanded = nameMenuExpanded,
                onDismissRequest = { nameMenuExpanded = false },
                offset = nameMenuOffset.value,
                shape = RoundedCornerShape(8.dp),
                containerColor = CardSurface,
                modifier = Modifier
                    .height(360.dp)
                    .width(260.dp)
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.common_ui_custom),
                            color = AccentBlue,
                            fontSize = SettingValueSize,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    onClick = {
                        isCustomMode = true
                        customText = ""
                        onNameChange("")
                        nameMenuExpanded = false
                    }
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))

                EnvVarsView.knownEnvVars
                    .map { it[0] }
                    .sortedBy { it.uppercase() }
                    .forEach { knownName ->
                        DropdownMenuItem(
                            text = {
                                Text(knownName, color = TextPrimary, fontSize = SettingValueSize)
                            },
                            onClick = {
                                isCustomMode = false
                                customText = ""
                                onNameChange(knownName)
                                nameMenuExpanded = false
                            }
                        )
                    }
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f)) {
            EnvVarValueEditor(
                name = name,
                value = value,
                onValueChange = onValueChange
            )
        }
        if (onRemove != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DangerRed.copy(alpha = 0.1f))
                    .clickable { onRemove() }
                    .paneNavItem(
                        cornerRadius = 6.dp,
                        onActivate = { onRemove() },
                        highlightColor = NavHighlight,
                        tapToSelect = true,
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    tint = DangerRed,
                    modifier = Modifier.size(SettingControlIconSize)
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
private fun EnvVarValueEditor(
    name: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    val known = findKnownEnvVar(name)
    val type = known?.getOrNull(1) ?: "TEXT"
    when (type) {
        "CHECKBOX" -> {
            val off = known!![2]
            val on = known[3]
            val isOn = value == on || value == "1" || value.equals("true", ignoreCase = true)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Switch(
                    checked = isOn,
                    onCheckedChange = { onValueChange(if (it) on else off) },
                    modifier = Modifier.scale(0.78f),
                    colors = outlinedSwitchColors(
                        accentColor = AccentBlue,
                        textSecondaryColor = TextSecondary
                    )
                )
            }
        }
        "SELECT" -> {
            val options = known!!.drop(2)
            EnvValueDropdown(
                current = if (value.isEmpty()) options.firstOrNull() ?: "" else value,
                options = options,
                onSelected = onValueChange
            )
        }
        "SELECT_CUSTOM" -> {
            val options = known!!.drop(2)
            EnvValueDropdownWithCustom(
                current = value,
                options = options,
                onChanged = onValueChange
            )
        }
        "SELECT_MULTIPLE" -> {
            val options = known!!.drop(2)
            EnvValueMultiDropdown(
                current = value,
                options = options,
                onChanged = onValueChange
            )
        }
        "NUMBER" -> EnvValueTextField(value, onValueChange, numeric = true)
        "DECIMAL" -> EnvValueTextField(value, onValueChange, decimal = true)
        else -> EnvValueTextField(value, onValueChange, numeric = false)
    }
}

@Composable
private fun EnvValueDropdown(
    current: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val menuOffset = rememberSmartDropdownOffset()
    Box {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(EnvVarControlHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(InputSurface)
                .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = { expanded = true }, highlightColor = NavHighlight)
                .smartDropdownAnchor(offset = menuOffset) { expanded = true }
                .padding(horizontal = SettingFieldHorizontalPadding),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current,
                    color = TextPrimary,
                    fontSize = SettingValueSize,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = menuOffset.value,
            shape = RoundedCornerShape(8.dp),
            containerColor = CardSurface,
            modifier = Modifier.width(220.dp)
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(opt, color = TextPrimary, fontSize = SettingValueSize)
                    },
                    onClick = {
                        onSelected(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun EnvValueDropdownWithCustom(
    current: String,
    options: List<String>,
    onChanged: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val menuOffset = rememberSmartDropdownOffset()
    var customMode by remember { mutableStateOf(current.isNotEmpty() && current !in options) }
    Box {
        if (customMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    EnvValueTextField(current, onChanged)
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(EnvVarControlHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(InputSurface)
                        .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .paneNavItem(cornerRadius = 8.dp, onActivate = { expanded = true }, highlightColor = NavHighlight)
                        .smartDropdownAnchor(offset = menuOffset) { expanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(EnvVarControlHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(InputSurface)
                    .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .paneNavItem(cornerRadius = 8.dp, onActivate = { expanded = true }, highlightColor = NavHighlight)
                    .smartDropdownAnchor(offset = menuOffset) { expanded = true }
                    .padding(horizontal = SettingFieldHorizontalPadding),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (current.isEmpty()) options.firstOrNull().orEmpty() else current,
                        color = TextPrimary,
                        fontSize = SettingValueSize,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = menuOffset.value,
            shape = RoundedCornerShape(8.dp),
            containerColor = CardSurface,
            modifier = Modifier.width(220.dp)
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt, color = TextPrimary, fontSize = SettingValueSize) },
                    onClick = {
                        customMode = false
                        onChanged(opt)
                        expanded = false
                    }
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.common_ui_custom),
                        color = AccentBlue,
                        fontSize = SettingValueSize,
                        fontWeight = FontWeight.Medium
                    )
                },
                onClick = {
                    customMode = true
                    onChanged("")
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun EnvValueMultiDropdown(
    current: String,
    options: List<String>,
    onChanged: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val menuOffset = rememberSmartDropdownOffset()
    val selectedSet = remember(current) {
        current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
    }
    Box {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(EnvVarControlHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(InputSurface)
                .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = { expanded = true }, highlightColor = NavHighlight)
                .smartDropdownAnchor(offset = menuOffset) { expanded = true }
                .padding(horizontal = SettingFieldHorizontalPadding),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selectedSet.isEmpty()) "—" else selectedSet.joinToString(","),
                    color = if (selectedSet.isEmpty()) TextDim else TextPrimary,
                    fontSize = SettingValueSize,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = menuOffset.value,
            shape = RoundedCornerShape(8.dp),
            containerColor = CardSurface,
            modifier = Modifier
                .height(320.dp)
                .width(260.dp)
        ) {
            options.forEach { opt ->
                val checked = opt in selectedSet
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AccentBlue,
                                    uncheckedColor = TextSecondary,
                                    checkmarkColor = Color.White
                                )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(opt, color = TextPrimary, fontSize = SettingValueSize)
                        }
                    },
                    onClick = {
                        if (checked) selectedSet.remove(opt) else selectedSet.add(opt)
                        onChanged(selectedSet.joinToString(","))
                    }
                )
            }
        }
    }
}

@Composable
private fun EnvValueTextField(
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
    decimal: Boolean = false
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var isEditing by remember { mutableStateOf(false) }
    LaunchedEffect(isEditing) {
        if (isEditing) {
            keyboard?.show()
            isEditing = false
        }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(color = TextPrimary, fontSize = SettingValueSize),
        cursorBrush = SolidColor(AccentBlue),
        singleLine = true,
        keyboardOptions = when {
            numeric -> KeyboardOptions(keyboardType = KeyboardType.Number)
            decimal -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
            else -> KeyboardOptions.Default
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(EnvVarControlHeight)
            .paneNavItem(
                cornerRadius = 8.dp,
                onActivate = { isEditing = true },
                highlightColor = NavHighlight,
            )
            .controllerTextFieldEscape(),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(InputSurface)
                    .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(horizontal = SettingFieldHorizontalPadding),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(stringResource(R.string.common_ui_value), color = TextDim, fontSize = SettingValueSize)
                }
                innerTextField()
            }
        }
    )
}

@Composable
private fun InputSection(state: GameSettingsStateHolder) {
    val isContainer = state.isContainerEditMode.value

    SubsectionLabel(stringResource(R.string.common_ui_input_controls))
    Spacer(Modifier.height(8.dp))
    SettingGroup {
        if (!isContainer) {
            SettingPairRow {
                Box(Modifier.weight(1f)) {
                    SettingDropdown(
                        label = stringResource(R.string.common_ui_profile),
                        entries = state.controlsProfileEntries.value,
                        selectedIndex = state.selectedControlsProfile.intValue,
                        onSelected = { state.selectedControlsProfile.intValue = it }
                    )
                }
                Box(Modifier.weight(1f)) {
                    SettingDropdown(
                        label = stringResource(R.string.num_controllers),
                        entries = state.numControllersEntries.value,
                        selectedIndex = state.selectedNumControllers.intValue,
                        onSelected = { state.selectedNumControllers.intValue = it }
                    )
                }
            }

            Spacer(Modifier.height(SettingItemGap))
        }

        val exclusiveChecked = if (isContainer) state.containerExclusiveInput.value
        else state.shortcutExclusiveXInput.value
        SettingPairRow {
            Box(Modifier.weight(1f)) {
                SettingCheckbox(
                    label = stringResource(R.string.shortcuts_properties_exclusive_input),
                    checked = exclusiveChecked,
                    onCheckedChange = { enabled ->
                        if (isContainer) {
                            state.containerExclusiveInput.value = enabled
                        } else {
                            state.shortcutExclusiveXInput.value = enabled
                        }
                        if (!enabled) {
                            state.enableXInput.value = true
                            state.enableDInput.value = true
                        } else if (state.enableXInput.value && state.enableDInput.value) {
                            state.enableDInput.value = false
                        }
                    }
                )
            }
            Box(Modifier.weight(1f)) {
                SettingCheckbox(
                    label = stringResource(R.string.container_config_sdl2_compatibility),
                    checked = state.sdl2Compatibility.value,
                    onCheckedChange = { state.sdl2Compatibility.value = it }
                )
            }
        }

        if (!isContainer) {
            Spacer(Modifier.height(4.dp))

            SettingCheckbox(
                label = stringResource(R.string.shortcuts_properties_disable_xinput),
                checked = state.disableXInput.value,
                onCheckedChange = { state.disableXInput.value = it }
            )

            Spacer(Modifier.height(4.dp))

            // Touch input mode (Trackpad / Touchscreen / Map to Right Stick)
            val gesturesOff = state.selectedGestureProfile.intValue == 0
            val onSelectMode: (Int) -> Unit = { mode ->
                state.screenTouchMode.intValue = mode
                state.simTouchScreen.value = (mode == 1)
                state.selectedGestureProfile.intValue = 0
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SettingCheckbox(
                        label = stringResource(R.string.session_drawer_touch_trackpad),
                        checked = state.screenTouchMode.intValue == 0 && gesturesOff,
                        onCheckedChange = { if (it) onSelectMode(0) }
                    )
                }
                Box(Modifier.weight(1f)) {
                    SettingCheckbox(
                        label = stringResource(R.string.session_drawer_touch_touchscreen),
                        checked = state.screenTouchMode.intValue == 1 && gesturesOff,
                        onCheckedChange = { onSelectMode(if (it) 1 else 0) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SettingCheckbox(
                label = stringResource(R.string.session_drawer_touch_map_right_stick),
                checked = state.screenTouchMode.intValue == 2 && gesturesOff,
                onCheckedChange = { onSelectMode(if (it) 2 else 0) }
            )

            if (state.gestureProfileEntries.value.isNotEmpty()) {
                Spacer(Modifier.height(SettingItemGap))
                SettingDropdown(
                    label = stringResource(R.string.session_gesture_profile_section),
                    entries = state.gestureProfileEntries.value,
                    selectedIndex = state.selectedGestureProfile.intValue,
                    onSelected = {
                        state.selectedGestureProfile.intValue = it
                        if (it != 0) {
                            state.screenTouchMode.intValue = 0
                            state.simTouchScreen.value = false
                        }
                    }
                )
            }
        }
    }

    Spacer(Modifier.height(SettingSectionGap))

    SubsectionLabel(stringResource(R.string.session_gamepad_game_controller))
    Spacer(Modifier.height(8.dp))
    SettingGroup {
        // DInput Mapper Type (only visible when DInput enabled)
        if (state.enableDInput.value) {
            SettingDropdown(
                label = stringResource(R.string.container_config_directinput_mapper_type),
                entries = state.dInputMapperTypeEntries.value,
                selectedIndex = state.selectedDInputMapperType.intValue,
                onSelected = { state.selectedDInputMapperType.intValue = it }
            )
            Spacer(Modifier.height(SettingItemGap))
        }

        // Enable XInput with help — only toggleable when Exclusive Input is on.
        val inputApisLocked = if (isContainer) !state.containerExclusiveInput.value
        else !state.shortcutExclusiveXInput.value
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                SettingCheckbox(
                    label = stringResource(R.string.container_config_enable_xinput),
                    checked = state.enableXInput.value,
                    onCheckedChange = {
                        state.enableXInput.value = it
                        if (!inputApisLocked && it && state.enableDInput.value) state.enableDInput.value = false
                    },
                    enabled = !inputApisLocked
                )
            }
            var showXInputHelp by remember { mutableStateOf(false) }
            val xInputHelpOffset = rememberSmartDropdownOffset()
            Box {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(InputSurface)
                        .border(1.dp, InputBorder, RoundedCornerShape(6.dp))
                        .paneNavItem(cornerRadius = 6.dp, onActivate = { showXInputHelp = !showXInputHelp }, highlightColor = NavHighlight)
                        .smartDropdownAnchor(offset = xInputHelpOffset) { showXInputHelp = !showXInputHelp },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showXInputHelp,
                    onDismissRequest = { showXInputHelp = false },
                    offset = xInputHelpOffset.value,
                    shape = RoundedCornerShape(8.dp),
                    containerColor = CardSurface,
                    modifier = Modifier
                        .padding(10.dp)
                        .width(280.dp)
                ) {
                    HtmlText(
                        stringResource(R.string.container_config_help_xinput),
                        color = TextPrimary,
                        fontSize = SettingLabelSize,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                SettingCheckbox(
                    label = stringResource(R.string.container_config_enable_dinput),
                    checked = state.enableDInput.value,
                    onCheckedChange = {
                        state.enableDInput.value = it
                        if (!inputApisLocked && it && state.enableXInput.value) state.enableXInput.value = false
                    },
                    enabled = !inputApisLocked
                )
            }
            var showDInputHelp by remember { mutableStateOf(false) }
            val dInputHelpOffset = rememberSmartDropdownOffset()
            Box {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(InputSurface)
                        .border(1.dp, InputBorder, RoundedCornerShape(6.dp))
                        .paneNavItem(cornerRadius = 6.dp, onActivate = { showDInputHelp = !showDInputHelp }, highlightColor = NavHighlight)
                        .smartDropdownAnchor(offset = dInputHelpOffset) { showDInputHelp = !showDInputHelp },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showDInputHelp,
                    onDismissRequest = { showDInputHelp = false },
                    offset = dInputHelpOffset.value,
                    shape = RoundedCornerShape(8.dp),
                    containerColor = CardSurface,
                    modifier = Modifier
                        .padding(10.dp)
                        .width(280.dp)
                ) {
                    HtmlText(
                        stringResource(R.string.container_config_help_dinput),
                        color = TextPrimary,
                        fontSize = SettingLabelSize,
                        lineHeight = 16.sp
                    )
                }
            }
        }

    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedSection(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks
) {

    // Wine/Proton version (read-only); shown only on existing containers where it isn't editable (new ones pick it in General).
    val wineVersionDisplay = state.wineVersionDisplay.value
    if (wineVersionDisplay.isNotEmpty() && !state.wineVersionEditable.value) {
        SubsectionLabel(stringResource(R.string.container_wine_version))
        Spacer(Modifier.height(8.dp))
        SettingGroup {
            Text(
                text = wineVersionDisplay,
                color = TextPrimary,
                fontSize = SettingValueSize,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(SettingSectionGap))
    }

    // Emulator selection (mirrors the Wine tab dropdowns)
    SubsectionLabel(stringResource(R.string.container_config_emulator_section))
    Spacer(Modifier.height(8.dp))
    SettingGroup {
        SettingPairRow {
            Box(Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.container_config_emulator_64bit),
                    entries = state.emulator64Entries.value,
                    selectedIndex = state.selectedEmulator64.intValue,
                    onSelected = {
                        state.selectedEmulator64.intValue = it
                        callbacks.onEmulatorChanged()
                    },
                    enabled = state.emulator64Entries.value.isNotEmpty()
                )
            }
            Box(Modifier.weight(1f)) {
                SettingDropdown(
                    label = stringResource(R.string.container_config_dll_emulator),
                    entries = state.emulator32Entries.value,
                    selectedIndex = state.selectedEmulator.intValue,
                    onSelected = {
                        state.selectedEmulator.intValue = it
                        callbacks.onEmulatorChanged()
                    },
                    enabled = state.emulator32Entries.value.isNotEmpty()
                )
            }
        }
    }
    Spacer(Modifier.height(SettingSectionGap))

    // FEXCore — hidden when FEXCore isn't explicitly in either slot.
    if (state.showFexcoreFrame.value) {
        val fexVersionUnix = state.fexcoreVersionEntries.value
            .getOrNull(state.selectedFexcoreVersion.intValue)
            ?.contains("unix", ignoreCase = true) == true
        val protonUnix = state.wineVersionDisplay.value.contains("unix", ignoreCase = true) ||
            state.wineVersionEntries.value.getOrNull(state.selectedWineVersion.intValue)
                ?.contains("unix", ignoreCase = true) == true
        val unixCompatible = fexVersionUnix && protonUnix

        val fexcoreUsage = emulatorUsageLabel(state, setOf("fexcore"))
        EmulatorSectionHeader(stringResource(R.string.container_fexcore_config), fexcoreUsage)
        Spacer(Modifier.height(8.dp))
        SettingGroup {
            SettingPairRow {
                Box(Modifier.weight(1f)) {
                    SettingDropdown(
                        label = stringResource(R.string.container_fexcore_version),
                        entries = state.fexcoreVersionEntries.value,
                        selectedIndex = state.selectedFexcoreVersion.intValue,
                        onSelected = { state.selectedFexcoreVersion.intValue = it },
                        labelTrailing = {
                            UnixLibsChip(
                                compatible = unixCompatible,
                                on = state.useUnixLibs.value,
                                onClick = { state.useUnixLibs.value = !state.useUnixLibs.value }
                            )
                        }
                    )
                }
                Box(Modifier.weight(1f)) {
                    SettingDropdown(
                        label = stringResource(R.string.container_fexcore_preset),
                        entries = state.fexcorePresetEntries.value,
                        selectedIndex = state.selectedFexcorePreset.intValue,
                        onSelected = { state.selectedFexcorePreset.intValue = it }
                    )
                }
            }
        }
        Spacer(Modifier.height(SettingSectionGap))
    }

    // Box64 / Wowbox64 — title switches between Box64/Wowbox64/both based on selection.
    if (state.showBox64Frame.value) {
        val box64Usage = emulatorUsageLabel(state, setOf("box64", "wowbox64"))
        val box64Id32 = state.emulator32Entries.value
            .getOrNull(state.selectedEmulator.intValue)
            ?.let { com.winlator.cmod.shared.util.StringUtils.parseIdentifier(it) } ?: ""
        val box64Id64 = state.emulator64Entries.value
            .getOrNull(state.selectedEmulator64.intValue)
            ?.let { com.winlator.cmod.shared.util.StringUtils.parseIdentifier(it) } ?: ""
        val usesPlainBox64 = box64Id32 == "box64" || box64Id64 == "box64"
        val usesWowbox64 = box64Id32 == "wowbox64" || box64Id64 == "wowbox64"
        val box64Title = when {
            usesPlainBox64 && usesWowbox64 -> stringResource(R.string.container_box64_wowbox64_title)
            usesWowbox64 -> stringResource(R.string.container_wowbox64_title)
            else -> stringResource(R.string.container_box64_title)
        }
        EmulatorSectionHeader(box64Title, box64Usage)
        Spacer(Modifier.height(8.dp))
        SettingGroup {
            SettingPairRow {
                Box(Modifier.weight(1f)) {
                    SettingDropdown(
                        label = stringResource(R.string.container_box64_version),
                        entries = state.box64VersionEntries.value,
                        selectedIndex = state.selectedBox64Version.intValue,
                        onSelected = { state.selectedBox64Version.intValue = it }
                    )
                }
                Box(Modifier.weight(1f)) {
                    SettingDropdown(
                        label = stringResource(R.string.container_box64_preset),
                        entries = state.box64PresetEntries.value,
                        selectedIndex = state.selectedBox64Preset.intValue,
                        onSelected = { state.selectedBox64Preset.intValue = it }
                    )
                }
            }
        }
        Spacer(Modifier.height(SettingSectionGap))
    }

    SubsectionLabel(stringResource(R.string.common_ui_system))
    Spacer(Modifier.height(8.dp))
    SettingGroup {
        SettingDropdown(
            label = stringResource(R.string.container_config_startup_selection),
            entries = state.startupSelectionEntries.value,
            selectedIndex = state.selectedStartupSelection.intValue,
            onSelected = { state.selectedStartupSelection.intValue = it }
        )

        Spacer(Modifier.height(SettingItemGap))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.weight(1f)) {
                SettingTextField(
                    label = stringResource(R.string.shortcuts_properties_exec_arguments),
                    value = state.execArgs.value,
                    onValueChange = { state.execArgs.value = it }
                )
            }
            Spacer(Modifier.width(8.dp))
            ExecArgsHelper(
                onArgSelected = { arg ->
                    val current = state.execArgs.value
                    state.execArgs.value = if (current.isBlank()) arg
                    else "$current $arg"
                }
            )
        }

        Spacer(Modifier.height(SettingItemGap))

        SettingCheckbox(
            label = stringResource(R.string.session_display_fullscreen_stretched),
            checked = state.fullscreenStretched.value,
            onCheckedChange = { state.fullscreenStretched.value = it }
        )
    }

    Spacer(Modifier.height(SettingSectionGap))

    SubsectionLabel(stringResource(R.string.container_config_processor_affinity))
    Spacer(Modifier.height(8.dp))
    SettingGroup {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val checkedList = state.cpuChecked.value
            for (i in 0 until state.cpuCount.intValue) {
                val isChecked = checkedList.getOrElse(i) { true }
                CpuChip(
                    index = i,
                    isChecked = isChecked,
                    onClick = {
                        // Block unchecking the last core: zero-selected and all-selected serialize identically, and runtime skips affinity for a zero mask.
                        val wouldLeaveNone = isChecked && checkedList.count { it } <= 1
                        if (!wouldLeaveNone) {
                            val mutable = checkedList.toMutableList()
                            mutable[i] = !isChecked
                            state.cpuChecked.value = mutable
                        }
                    }
                )
            }
        }
    }

    Spacer(Modifier.height(SettingSectionGap))

    SubsectionLabel(stringResource(R.string.container_config_processor_affinity_32bit))
    Spacer(Modifier.height(8.dp))
    SettingGroup {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val checkedList = state.cpuCheckedWoW64.value
            for (i in 0 until state.cpuCount.intValue) {
                val isChecked = checkedList.getOrElse(i) { true }
                CpuChip(
                    index = i,
                    isChecked = isChecked,
                    onClick = {
                        val wouldLeaveNone = isChecked && checkedList.count { it } <= 1
                        if (!wouldLeaveNone) {
                            val mutable = checkedList.toMutableList()
                            mutable[i] = !isChecked
                            state.cpuCheckedWoW64.value = mutable
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ExecArgsHelper(onArgSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val menuOffset = rememberSmartDropdownOffset()

    Box(modifier = Modifier.padding(top = 22.dp)) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(InputSurface)
                .border(1.dp, InputBorder, RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = { expanded = true }, highlightColor = NavHighlight)
                .smartDropdownAnchor(offset = menuOffset) { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = menuOffset.value,
            shape = RoundedCornerShape(8.dp),
            containerColor = CardSurface,
            modifier = Modifier
                .height(360.dp)
                .width(240.dp)
        ) {
            ExtraArgPresets.forEach { group ->
                DropdownMenuItem(
                    text = {
                        Text(
                            group.header,
                            color = AccentBlue,
                            fontSize = SettingLabelSize,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    },
                    onClick = {},
                    enabled = false
                )
                group.args.forEach { arg ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                arg,
                                color = TextPrimary,
                                fontSize = SettingValueSize
                            )
                        },
                        onClick = {
                            onArgSelected(arg)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CpuChip(
    index: Int,
    isChecked: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isChecked) AccentBlue.copy(alpha = 0.15f) else ChipSurface
    val borderColor = if (isChecked) AccentBlue.copy(alpha = 0.4f) else ChipBorder
    val textColor = if (isChecked) AccentBlue else TextDim

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .paneNavItem(
                cornerRadius = 8.dp,
                onActivate = onClick,
                highlightColor = NavHighlight,
                tapToSelect = true,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "CPU $index",
            color = textColor,
            fontSize = SettingLabelSize,
            fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun UnixLibsChip(
    compatible: Boolean,
    on: Boolean,
    onClick: () -> Unit
) {
    val active = compatible && on
    val bgColor = if (active) AccentBlue.copy(alpha = 0.15f) else ChipSurface
    val borderColor = if (active) AccentBlue.copy(alpha = 0.4f) else ChipBorder
    val textColor = if (active) AccentBlue else TextDim

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .then(
                if (compatible) {
                    Modifier
                        .clickable(onClick = onClick)
                        .paneNavItem(
                            cornerRadius = 6.dp,
                            onActivate = onClick,
                            highlightColor = NavHighlight,
                            tapToSelect = true,
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "UnixLibs",
            color = textColor,
            fontSize = SettingLabelSize,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}


@Composable
private fun HtmlText(
    html: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit
) {
    val spanned = remember(html) {
        android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
    }
    val annotated = remember(spanned) {
        buildAnnotatedString {
            val str = spanned.toString().trim()
            append(str)
            for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span).coerceAtMost(str.length)
                if (start >= str.length) continue
                when (span) {
                    is android.text.style.StyleSpan -> {
                        when (span.style) {
                            android.graphics.Typeface.BOLD ->
                                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                            android.graphics.Typeface.ITALIC ->
                                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                            android.graphics.Typeface.BOLD_ITALIC ->
                                addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), start, end)
                        }
                    }
                }
            }
        }
    }
    Text(
        text = annotated,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight
    )
}

@Composable
private fun SubsectionLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = SettingSectionLabelSize,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp
    )
}

// Returns the architecture badge for the slots currently using one of [ids].
@Composable
private fun emulatorUsageLabel(
    state: GameSettingsStateHolder,
    ids: Set<String>
): String? {
    val entries32 = state.emulator32Entries.value
    val entries64 = state.emulator64Entries.value
    val id32 = entries32.getOrNull(state.selectedEmulator.intValue)?.lowercase() ?: ""
    val id64 = entries64.getOrNull(state.selectedEmulator64.intValue)?.lowercase() ?: ""
    val used32 = id32 in ids
    val used64 = id64 in ids
    return when {
        used32 && used64 -> stringResource(R.string.common_ui_64_bit_and_32_bit)
        used64 -> stringResource(R.string.common_ui_64_bit)
        used32 -> stringResource(R.string.common_ui_32_bit)
        else -> null
    }
}

@Composable
private fun EmulatorSectionHeader(title: String, usage: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = TextSecondary,
            fontSize = SettingSectionLabelSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
        if (usage != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AccentBlue.copy(alpha = 0.15f))
                    .border(1.dp, AccentBlue.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = usage,
                    color = AccentBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun SettingActionButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(10.dp))
            .background(AccentBlue.copy(alpha = 0.08f))
            .border(1.dp, AccentBlue.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .then(
                if (enabled) {
                    Modifier
                        .paneNavItem(cornerRadius = 10.dp, onActivate = onClick, highlightColor = NavHighlight)
                        .clickable { onClick() }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = AccentBlue,
            fontSize = SettingValueSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

private fun frameGenStateFor(result: Int): Int =
    when (result) {
        LosslessAutoImport.RESULT_READY, LosslessAutoImport.RESULT_IMPORTED -> FRAMEGEN_SHADERS_READY
        LosslessAutoImport.RESULT_UPDATED -> FRAMEGEN_SHADERS_UPDATED
        LosslessAutoImport.RESULT_NOT_OWNED -> FRAMEGEN_SHADERS_NOT_OWNED
        LosslessAutoImport.RESULT_NOT_FOUND -> FRAMEGEN_SHADERS_MISSING
        else -> FRAMEGEN_SHADERS_FAILED
    }

@Composable
private fun SettingGroup(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = SettingGroupPadding,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingGroupCorner))
            .background(CardSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(SettingGroupCorner))
            .padding(horizontal = SettingGroupPadding, vertical = verticalPadding)
    ) {
        content()
    }
}

@Composable
private fun SettingDropdown(
    label: String,
    entries: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    enabled: Boolean = true,
    disabledAlpha: Float = 0.4f,
    labelTrailing: (@Composable () -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val menuOffset = rememberSmartDropdownOffset()
    val selectedText = entries.getOrElse(selectedIndex) { "" }
    val alpha = if (enabled) 1f else disabledAlpha
    val parentNav = LocalPaneNav.current
    val optionRegistry = remember { PaneNavRegistry() }
    LaunchedEffect(expanded) {
        if (expanded) {
            optionRegistry.reset()
            optionRegistry.controllerActive = true
            parentNav?.overlay = optionRegistry
            parentNav?.overlayClose = { expanded = false }
        } else if (parentNav?.overlay === optionRegistry) {
            parentNav.overlay = null
            parentNav.overlayClose = null
        }
    }

    Column(modifier = Modifier.fillMaxWidth().alpha(alpha)) {
        // Fixed height keeps side-by-side dropdown fields on the same controller nav row.
        Row(
            modifier = Modifier.fillMaxWidth().height(SettingLabelRowHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = TextSecondary,
                fontSize = SettingLabelSize,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (labelTrailing != null) {
                Spacer(Modifier.weight(1f))
                labelTrailing()
            }
        }
        Spacer(Modifier.height(SettingTightGap))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SettingFieldCorner))
                    .background(InputSurface)
                    .border(1.dp, InputBorder, RoundedCornerShape(SettingFieldCorner))
                    .paneNavItem(
                        cornerRadius = SettingFieldCorner,
                        onActivate = { if (enabled) expanded = true },
                        highlightColor = NavHighlight,
                    )
                    .smartDropdownAnchor(enabled = enabled, offset = menuOffset) { expanded = true }
                    .padding(horizontal = SettingFieldHorizontalPadding, vertical = SettingFieldVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    selectedText,
                    color = TextPrimary,
                    fontSize = SettingValueSize,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextDim,
                    modifier = Modifier.size(SettingIconSize)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = menuOffset.value,
                shape = RoundedCornerShape(8.dp),
                containerColor = CardSurface,
                properties = PopupProperties(focusable = false),
            ) {
                CompositionLocalProvider(LocalPaneNav provides optionRegistry) {
                    entries.forEachIndexed { index, entry ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    entry,
                                    color = if (index == selectedIndex) AccentBlue else TextPrimary,
                                    fontSize = SettingValueSize,
                                    fontWeight = if (index == selectedIndex) FontWeight.Medium else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onSelected(index)
                                expanded = false
                            },
                            modifier = (if (index == selectedIndex) Modifier.background(AccentBlue.copy(alpha = 0.06f)) else Modifier)
                                .paneNavItem(
                                    cornerRadius = 6.dp,
                                    onActivate = {
                                        onSelected(index)
                                        expanded = false
                                    },
                                    isEntry = index == selectedIndex,
                                    highlightColor = NavHighlight,
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var isEditing by remember { mutableStateOf(false) }
    LaunchedEffect(isEditing) {
        if (isEditing) {
            keyboard?.show()
            isEditing = false
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = TextSecondary,
            fontSize = SettingLabelSize,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(bottom = SettingTightGap)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = SettingValueSize
            ),
            cursorBrush = SolidColor(AccentBlue),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .paneNavItem(
                    cornerRadius = SettingFieldCorner,
                    onActivate = { if (enabled) isEditing = true },
                    highlightColor = NavHighlight,
                )
                .controllerTextFieldEscape()
                .clip(RoundedCornerShape(SettingFieldCorner))
                .background(InputSurface)
                .border(1.dp, InputBorder, RoundedCornerShape(SettingFieldCorner))
                .padding(horizontal = SettingFieldHorizontalPadding, vertical = SettingFieldVerticalPadding)
        )
    }
}

@Composable
private fun SettingCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .then(
                if (enabled) {
                    Modifier.paneNavItem(
                        cornerRadius = 8.dp,
                        onActivate = { onCheckedChange(!checked) },
                        highlightColor = NavHighlight,
                        tapToSelect = true,
                    )
                } else {
                    Modifier
                }
            )
            .padding(vertical = SettingTightGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = AccentBlue,
                uncheckedColor = CheckBorder,
                checkmarkColor = Color.White
            )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = TextPrimary,
            fontSize = SettingValueSize
        )
    }
}

@Composable
private fun settingSliderColors() =
    SliderDefaults.colors(
        thumbColor = AccentBlue,
        activeTrackColor = AccentBlue,
        inactiveTrackColor = SliderInactive,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSliderTrack(sliderState: SliderState) {
    SliderDefaults.Track(
        sliderState = sliderState,
        colors = settingSliderColors(),
        modifier = Modifier.scale(scaleX = 1f, scaleY = SettingSliderTrackScaleY),
        drawStopIndicator = null,
        drawTick = { _, _ -> },
        thumbTrackGapSize = 0.dp,
        trackInsideCornerSize = 0.dp,
    )
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.4f
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onCheckedChange(!checked) }
                } else {
                    Modifier
                }
            )
            .then(
                if (enabled) {
                    Modifier.paneNavItem(
                        cornerRadius = 8.dp,
                        onActivate = { onCheckedChange(!checked) },
                        highlightColor = NavHighlight,
                        tapToSelect = true,
                    )
                } else {
                    Modifier
                }
            )
            .padding(vertical = SettingTightGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = TextPrimary,
            fontSize = SettingValueSize,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = outlinedSwitchColors(
                accentColor = AccentBlue,
                textSecondaryColor = TextSecondary
            )
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FrameGenPresetSlider(
    selected: FrameGenPreset,
    onSelected: (FrameGenPreset) -> Unit,
) {
    val presets = FrameGenPreset.values()
    val index = presets.indexOf(selected).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.frame_generation_preset),
                color = TextSecondary,
                fontSize = SettingLabelSize,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AccentBlue.copy(alpha = 0.1f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    stringResource(selected.labelRes),
                    color = AccentBlue,
                    fontSize = SettingLabelSize,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(SettingTightGap))

        Slider(
            value = index.toFloat(),
            onValueChange = { onSelected(FrameGenPreset.atIndex(it.roundToInt())) },
            valueRange = 0f..(presets.size - 1).toFloat(),
            steps = presets.size - 2,
            modifier = Modifier
                .fillMaxWidth()
                .height(SettingSliderHeight)
                .controllerSliderEscape()
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onAdjust = { d -> onSelected(FrameGenPreset.atIndex(index + d)) },
                    highlightColor = NavHighlight,
                ),
            colors = settingSliderColors(),
            track = { SettingSliderTrack(it) },
            thumb = {
                Box(
                    modifier = Modifier
                        .size(SettingSliderThumbSize)
                        .clip(RoundedCornerShape(50))
                        .background(AccentBlue)
                        .border(2.dp, CardSurface, RoundedCornerShape(50))
                )
            }
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            presets.forEachIndexed { i, preset ->
                Text(
                    text = stringResource(preset.shortLabelRes),
                    color = if (i == index) AccentBlue else TextSecondary,
                    fontSize = SettingLabelSize * 0.9f,
                    fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        presets.size - 1 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(SettingTightGap))

        Text(
            text = stringResource(selected.descriptionRes),
            color = TextSecondary,
            fontSize = SettingLabelSize,
            lineHeight = SettingLabelSize * 1.4f,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingSlider(
    label: String,
    value: Int,
    range: IntRange,
    valueText: String = "$value%",
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = TextSecondary,
                fontSize = SettingLabelSize,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AccentBlue.copy(alpha = 0.1f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    valueText,
                    color = AccentBlue,
                    fontSize = SettingLabelSize,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(SettingTightGap))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(SettingSliderHeight)
                .controllerSliderEscape()
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onAdjust = { d -> onValueChange((value + d).coerceIn(range.first, range.last)) },
                    highlightColor = NavHighlight,
                ),
            colors = settingSliderColors(),
            track = { SettingSliderTrack(it) },
            thumb = {
                Box(
                    modifier = Modifier
                        .size(SettingSliderThumbSize)
                        .clip(RoundedCornerShape(50))
                        .background(AccentBlue)
                        .border(2.dp, CardSurface, RoundedCornerShape(50))
                )
            }
        )
    }
}

@Composable
private fun SavesSection(
    state: GameSettingsStateHolder,
    callbacks: GameSettingsCallbacks
) {
    Column {
        SavesActionCard(
            title = stringResource(R.string.common_ui_export),
            description = stringResource(R.string.saves_export_path_summary),
            icon = Icons.Outlined.Upload,
            accentColor = AccentBlue,
            onClick = { callbacks.onExportSaves() }
        )

        Spacer(Modifier.height(SettingItemGap))

        SavesActionCard(
            title = stringResource(R.string.common_ui_import),
            description = stringResource(R.string.saves_import_warning_title),
            icon = Icons.Outlined.Download,
            accentColor = WarningAmber,
            onClick = { callbacks.onImportSaves() }
        )
    }
}

@Composable
private fun SavesActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingGroupCorner))
            .background(InputSurface)
            .border(1.dp, InputBorder, RoundedCornerShape(SettingGroupCorner))
            .clickable { onClick() }
            .paneNavItem(
                cornerRadius = SettingGroupCorner,
                onActivate = onClick,
                highlightColor = NavHighlight,
                tapToSelect = true,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.1f))
                    .border(1.dp, accentColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = SettingValueSize,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
