package com.winlator.cmod.feature.retro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import com.winlator.cmod.R
import com.winlator.cmod.shared.framegen.FrameGenOptions
import com.winlator.cmod.shared.framegen.FrameGenPreset
import com.winlator.cmod.shared.theme.GameSettingsStyle
import com.winlator.cmod.shared.ui.settings.SharedGroupTitle
import com.winlator.cmod.shared.ui.settings.SharedInfoRow
import com.winlator.cmod.shared.ui.settings.SharedSettingGroup
import com.winlator.cmod.shared.ui.settings.SharedSettingSwitch
import com.winlator.cmod.feature.library.GameSettingsNav
import com.winlator.cmod.feature.shortcuts.LibraryShortcutArtwork
import com.winlator.cmod.runtime.container.Shortcut
import java.io.File
import com.winlator.cmod.shared.ui.dialog.findActivity
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneHighlight
import com.winlator.cmod.shared.ui.nav.paneNavItem
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import com.winlator.cmod.shared.ui.widget.chasingBorder
import kotlinx.coroutines.launch

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
private val NavHighlight = GameSettingsStyle.NavHighlight
private val DangerRed = GameSettingsStyle.DangerRed

private val LabelSize = 11.sp
private val ValueSize = 12.sp
private val GroupCorner = 12.dp
private val GroupPadding = 12.dp
private val FieldCorner = 8.dp
private val ItemGap = 10.dp
private val TightGap = 4.dp

private val SHADER_KEYS = listOf("default", "crt", "lcd", "sharp")
private val UPSCALE_KEYS = listOf("2x", "4x", "native")

class RetroSettingsState(
    val shortcut: Shortcut,
    private val context: android.content.Context? = null,
) {
    val system: RetroSystem? = RetroShortcuts.systemForShortcut(shortcut)
    val coreOptions: List<RetroCoreOption> = RetroCoreOptions.forSystem(system)
    val name: String = shortcut.getExtra("custom_name", shortcut.name)
    val romPath: String = shortcut.getExtra(RetroShortcuts.KEY_ROM)

    private val sysId: String? = system?.id

    var shader by mutableStateOf(
        shortcut.getExtra(RetroShortcuts.KEY_SHADER)
            .ifEmpty { if (context != null && sysId != null) RetroDefaults.shader(context, sysId) else "default" }
            .lowercase().let { if (it in SHADER_KEYS) it else "default" },
    )
    var sgsr by mutableStateOf(
        when {
            shortcut.getExtra(RetroShortcuts.KEY_SGSR).isNotEmpty() -> shortcut.getExtra(RetroShortcuts.KEY_SGSR) == "1"
            shortcut.getExtra(RetroShortcuts.KEY_SHADER, "default").lowercase() == "sgsr" -> true
            context != null && sysId != null -> RetroDefaults.sgsr(context, sysId)
            else -> false
        },
    )
    var upscale by mutableStateOf(
        shortcut.getExtra(RetroShortcuts.KEY_UPSCALE)
            .ifEmpty { if (context != null && sysId != null) RetroDefaults.upscale(context, sysId) else "native" }
            .lowercase().let { if (it in UPSCALE_KEYS) it else "native" },
    )
    var engine3d by mutableStateOf(shortcut.getExtra(Gen1EmbedLaunch.KEY_ENGINE_3D) == "1")

    val engineValues: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String> =
        androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
            if (context != null) {
                Gen1EngineSettings.cached(context).forEach { row ->
                    Gen1EngineSettings.selection(shortcut, row)?.let { put(row.id, it) }
                }
            }
        }
    var touchControls by mutableStateOf(
        shortcut.getExtra(RetroShortcuts.KEY_TOUCH_CONTROLS)
            .ifEmpty { if (context != null && sysId != null) (if (RetroDefaults.touchControls(context, sysId)) "1" else "0") else "1" } != "0",
    )
    var adaptiveSticks by mutableStateOf(
        shortcut.getExtra(RetroShortcuts.KEY_ADAPTIVE_STICKS)
            .ifEmpty { if (context != null && sysId != null) (if (RetroDefaults.adaptiveSticks(context, sysId)) "1" else "0") else "0" } == "1",
    )
    var hddImage by mutableStateOf(shortcut.getExtra(RetroShortcuts.KEY_HDD_IMAGE))
    var hddEnable by mutableStateOf(shortcut.getExtra(RetroShortcuts.KEY_HDD_ENABLE) == "1")
    var audio by mutableStateOf(
        shortcut.getExtra(RetroShortcuts.KEY_AUDIO)
            .ifEmpty { if (context != null && sysId != null) (if (RetroDefaults.audio(context, sysId)) "1" else "0") else "1" } != "0",
    )
    var frameGen by mutableStateOf(
        if (context != null) {
            RetroFrameGen.enabled(context, shortcut, sysId)
        } else {
            shortcut.getExtra(FrameGenOptions.KEY_ENABLED) == "1"
        },
    )
    var frameGenMultiplier by mutableIntStateOf(
        if (context != null) {
            RetroFrameGen.multiplier(context, shortcut, sysId)
        } else {
            FrameGenOptions.DEFAULT_MULTIPLIER
        },
    )
    var frameGenTargetRate by mutableIntStateOf(
        if (context != null) RetroFrameGen.targetRate(context, shortcut, sysId) else 0,
    )
    var frameGenFlowScale by mutableIntStateOf(
        if (context != null) {
            RetroFrameGen.flowScale(context, shortcut, sysId)
        } else {
            FrameGenOptions.DEFAULT_FLOW_SCALE
        },
    )
    var hud by mutableStateOf(
        shortcut.getExtra(RetroShortcuts.KEY_HUD)
            .ifEmpty {
                if (context != null && sysId != null) {
                    if (RetroHudSupport.consoleHudDefault(context, sysId)) "1" else "0"
                } else {
                    "0"
                }
            } == "1",
    )

    val optionValues =
        mutableStateMapOf<String, String>().apply {
            coreOptions.forEach { option ->
                put(
                    option.key,
                    shortcut.getExtra(RetroShortcuts.VAR_PREFIX + option.key).ifEmpty {
                        if (context != null && sysId != null) {
                            RetroDefaults.coreOption(context, sysId, option.key, option.defaultValue)
                        } else {
                            option.defaultValue
                        }
                    },
                )
            }
        }
    val artworkSelected = mutableStateMapOf<LibraryShortcutArtwork.LibraryArtworkSlot, Boolean>()
    var currentSection by mutableIntStateOf(0)
    var biosRefresh by mutableIntStateOf(0)

    var hddRefresh by mutableIntStateOf(0)
    var hddImporting by mutableStateOf(false)
    var requestImportHdd: (() -> Unit)? = null

    val gameSerial: String? by lazy {
        if (context != null && system?.id == RetroSystems.PS2.id) Ps2IsoSerial.serialOf(File(romPath)) else null
    }
    var cheatsRefresh by mutableIntStateOf(0)
    var pendingImportIsPatch = false
    var requestImportCheatFile: ((isPatch: Boolean) -> Unit)? = null

    init {
        syncArtwork()
    }

    fun syncArtwork() {
        LibraryShortcutArtwork.LibraryArtworkSlot.entries.forEach { slot ->
            val path = shortcut.getExtra(slot.extraKey)
            artworkSelected[slot] = path.isNotBlank() && File(path).isFile
        }
    }

    fun save() {
        shortcut.putExtra(RetroShortcuts.KEY_SHADER, shader)
        shortcut.putExtra(RetroShortcuts.KEY_SGSR, if (sgsr) "1" else "0")
        shortcut.putExtra(RetroShortcuts.KEY_UPSCALE, upscale)
        shortcut.putExtra(Gen1EmbedLaunch.KEY_ENGINE_3D, if (engine3d) "1" else "0")
        if (context != null) {
            Gen1EngineSettings.cached(context).forEach { row ->
                shortcut.putExtra(RetroShortcuts.VAR_PREFIX + row.id, engineValues[row.id])
            }
        }
        shortcut.putExtra(RetroShortcuts.KEY_TOUCH_CONTROLS, if (touchControls) "1" else "0")
        shortcut.putExtra(RetroShortcuts.KEY_ADAPTIVE_STICKS, if (adaptiveSticks) "1" else "0")
        shortcut.putExtra(RetroShortcuts.KEY_HDD_IMAGE, hddImage)
        shortcut.putExtra(RetroShortcuts.KEY_HDD_ENABLE, if (hddEnable) "1" else "0")
        shortcut.putExtra(RetroShortcuts.KEY_AUDIO, if (audio) "1" else "0")
        shortcut.putExtra(RetroShortcuts.KEY_HUD, if (hud) "1" else "0")
        shortcut.putExtra(FrameGenOptions.KEY_ENABLED, if (frameGen) "1" else "0")
        shortcut.putExtra(FrameGenOptions.KEY_MULTIPLIER, frameGenMultiplier.toString())
        shortcut.putExtra(FrameGenOptions.KEY_TARGET_RATE, frameGenTargetRate.toString())
        shortcut.putExtra(FrameGenOptions.KEY_FLOW_SCALE, frameGenFlowScale.toString())
        coreOptions.forEach { option ->
            val consoleDefault =
                if (context != null && sysId != null) {
                    RetroDefaults.coreOption(context, sysId, option.key, option.defaultValue)
                } else {
                    option.defaultValue
                }
            val chosen = optionValues[option.key] ?: consoleDefault
            shortcut.putExtra(
                RetroShortcuts.VAR_PREFIX + option.key,
                if (chosen == consoleDefault) null else chosen,
            )
        }
        shortcut.saveData()
    }
}

private enum class RetroSectionId {
    GENERAL,
    GRAPHICS,
    FRAMEGEN,
    PERFORMANCE,
    HUD,
    INPUT,
    AUDIO,
    ONLINE,
    CHEATS,
}

private data class RetroSection(
    val id: RetroSectionId,
    val icon: ImageVector,
    val labelRes: Int,
)

private fun buildRetroSections(state: RetroSettingsState): List<RetroSection> {
    val sections = mutableListOf<RetroSection>()
    val systemId = state.system?.id
    sections += RetroSection(RetroSectionId.GENERAL, Icons.Outlined.Tune, R.string.retro_gs_section_general)
    sections += RetroSection(RetroSectionId.GRAPHICS, Icons.Outlined.Monitor, R.string.retro_gs_section_graphics)
    sections += RetroSection(
        RetroSectionId.FRAMEGEN,
        Icons.Outlined.AutoAwesomeMotion,
        R.string.session_drawer_frame_generation,
    )
    if (state.system?.isExternal == true) {
        sections += RetroSection(RetroSectionId.PERFORMANCE, Icons.Outlined.Bolt, R.string.retro_gs_section_performance)
    }
    sections += RetroSection(RetroSectionId.HUD, Icons.Outlined.Speed, R.string.retro_gs_section_hud)
    sections += RetroSection(RetroSectionId.INPUT, Icons.Outlined.SportsEsports, R.string.retro_gs_section_input)
    sections += RetroSection(RetroSectionId.AUDIO, Icons.AutoMirrored.Outlined.VolumeUp, R.string.retro_gs_section_audio)
    if (RetroOnlineSupport.supportsDev9(systemId) ||
        RetroOnlineSupport.supportsMultiplayerUi(systemId) ||
        RetroOnlineSupport.supportsDolphinNetplay(systemId)
    ) {
        sections += RetroSection(RetroSectionId.ONLINE, Icons.Outlined.Public, R.string.retro_gs_section_online)
    }
    if (state.system?.isExternal == true) {
        sections += RetroSection(RetroSectionId.CHEATS, Icons.Outlined.Code, R.string.retro_gs_group_cheats)
    }
    return sections
}

@Composable
fun RetroGameSettingsContent(
    state: RetroSettingsState,
    nav: GameSettingsNav? = null,
    onPickArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
    onRemoveArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
    onImportBios: (() -> Unit)? = null,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val sections = remember(state) { buildRetroSections(state) }
    val selectedIdx = state.currentSection

    if (nav != null) {
        SideEffect {
            nav.sidebarCount = sections.size
            nav.onSelectSection = { state.currentSection = it }
            nav.onSave = onSave
            nav.onCancel = onCancel
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(BgDeep),
    ) {
        val retroPortrait = isPortraitLayout()

        val retroContent: @Composable (Modifier) -> Unit = { contentModifier ->
            Box(modifier = contentModifier.background(ContentBg)) {
                RetroSectionContent(
                    sectionIndex = selectedIdx,
                    state = state,
                    nav = nav,
                    onPickArtwork = onPickArtwork,
                    onRemoveArtwork = onRemoveArtwork,
                    onImportBios = onImportBios,
                )
            }
        }

        if (retroPortrait) {
            Column(modifier = Modifier.fillMaxSize()) {
                RetroPortraitHeader(
                    title = state.name,
                    subtitle = state.system?.displayName ?: "",
                    sections = sections,
                    currentIndex = selectedIdx,
                    onSectionSelected = { state.currentSection = it },
                    onSave = onSave,
                    onCancel = onCancel,
                    nav = nav,
                )
                retroContent(Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                RetroSidebar(
                    title = state.name,
                    subtitle = state.system?.displayName ?: "",
                    sections = sections,
                    currentIndex = selectedIdx,
                    onSectionSelected = { state.currentSection = it },
                    onSave = onSave,
                    onCancel = onCancel,
                    nav = nav,
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                )
                Box(Modifier.width(1.dp).fillMaxHeight().background(DividerColor))
                retroContent(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun RetroPortraitHeader(
    title: String,
    subtitle: String,
    sections: List<RetroSection>,
    currentIndex: Int,
    onSectionSelected: (Int) -> Unit,
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
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SidebarBg)
                .padding(top = 12.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = LabelSize,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = LabelSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier =
                    Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                        .background(CardSurface)
                        .paneHighlight(cancelHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
                        .clickable { nav?.tapAction(0); onCancel() }
                        .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.common_ui_cancel),
                    color = TextSecondary,
                    fontSize = LabelSize,
                    fontWeight = FontWeight.Medium,
                )
            }
            Box(
                modifier =
                    Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, AccentBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .background(AccentBlue.copy(alpha = 0.1f))
                        .paneHighlight(saveHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
                        .clickable { nav?.tapAction(1); onSave() }
                        .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.common_ui_save),
                    color = AccentBlue,
                    fontSize = LabelSize,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyRow(
            state = chipListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(sections) { index, section ->
                val isSelected = currentIndex == index
                val chipHighlighted =
                    nav != null && nav.active && !nav.inContent && !nav.onActionRow && nav.sidebarIndex == index
                Row(
                    modifier =
                        Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(if (isSelected) AccentBlue.copy(alpha = 0.16f) else CardSurface)
                            .border(
                                1.dp,
                                if (isSelected) AccentBlue.copy(alpha = 0.6f) else CardBorder,
                                RoundedCornerShape(17.dp),
                            )
                            .paneHighlight(chipHighlighted, cornerRadius = 17.dp, highlightColor = NavHighlight)
                            .clickable { if (nav != null) nav.tapSection(index) else onSectionSelected(index) }
                            .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        tint = if (isSelected) AccentBlue else TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(section.labelRes),
                        color = if (isSelected) TextPrimary else TextSecondary,
                        fontSize = LabelSize,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
    }
}

@Composable
private fun RetroSectionContent(
    sectionIndex: Int,
    state: RetroSettingsState,
    nav: GameSettingsNav? = null,
    onPickArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
    onRemoveArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
    onImportBios: (() -> Unit)? = null,
) {
    run {
        val idx = sectionIndex
        val contentNav = remember(nav) { nav?.let { PaneNavRegistry(initialSignal = it.contentSignal) } }
        val isCurrent = true
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
        val sections = remember(state) { buildRetroSections(state) }
        val sectionBody: @Composable () -> Unit = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                when (sections.getOrNull(idx)?.id) {
                    RetroSectionId.GENERAL -> RetroGeneralSection(state, onPickArtwork, onRemoveArtwork, onImportBios)
                    RetroSectionId.GRAPHICS -> RetroGraphicsSection(state)
                    RetroSectionId.FRAMEGEN -> RetroFrameGenerationGroup(state)
                    RetroSectionId.PERFORMANCE -> RetroPs2PerformanceSection()
                    RetroSectionId.HUD ->
                        if (state.system?.isExternal == true) {
                            RetroPs2HudSection(state)
                        } else {
                            RetroLibretroHudSection(state.hud) { state.hud = it }
                        }
                    RetroSectionId.INPUT -> RetroInputSection(state)
                    RetroSectionId.AUDIO -> RetroAudioSection(state)
                    RetroSectionId.ONLINE -> {
                        when {
                            RetroOnlineSupport.supportsDev9(state.system?.id) ->
                                RetroPs2OnlineSection(state)
                            RetroOnlineSupport.supportsDolphinNetplay(state.system?.id) ->
                                RetroDolphinNetplaySection(state)
                            else -> RetroNetplaySection(state)
                        }
                    }
                    RetroSectionId.CHEATS -> RetroPs2CheatsSection(state)
                    null -> Unit
                }
                Spacer(Modifier.height(12.dp))
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
private fun RetroSidebar(
    title: String,
    subtitle: String,
    sections: List<RetroSection>,
    currentIndex: Int,
    onSectionSelected: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    nav: GameSettingsNav? = null,
    modifier: Modifier = Modifier,
) {
    val cancelHighlighted = nav != null && nav.active && !nav.inContent && nav.onActionRow && nav.actionCol == 0
    val saveHighlighted = nav != null && nav.active && !nav.inContent && nav.onActionRow && nav.actionCol == 1
    Column(
        modifier =
            modifier
                .background(SidebarBg)
                .padding(top = 14.dp, bottom = 12.dp),
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = LabelSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 2.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 10.dp),
            )
        }
        Box(
            modifier =
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DividerColor),
        )
        Spacer(Modifier.height(8.dp))

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            sections.forEachIndexed { index, section ->
                RetroSidebarItem(
                    icon = section.icon,
                    label = stringResource(section.labelRes),
                    isSelected = currentIndex == index,
                    navHighlighted =
                        nav != null && nav.active && !nav.inContent && !nav.onActionRow &&
                            nav.sidebarIndex == index,
                    onClick = {
                        if (nav != null) nav.tapSection(index) else onSectionSelected(index)
                    },
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DividerColor),
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                        .background(CardSurface)
                        .paneHighlight(cancelHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
                        .clickable {
                            nav?.tapAction(0)
                            onCancel()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.retro_gs_cancel), color = TextSecondary, fontSize = LabelSize, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, AccentBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .background(AccentBlue.copy(alpha = 0.1f))
                        .paneHighlight(saveHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
                        .clickable {
                            nav?.tapAction(1)
                            onSave()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.retro_gs_save), color = AccentBlue, fontSize = LabelSize, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RetroSidebarItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    navHighlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .chasingBorder(isFocused = isSelected, cornerRadius = 8.dp, borderWidth = 2.dp)
                .paneHighlight(navHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) AccentBlue else TextDim,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                color = if (isSelected) TextPrimary else TextSecondary,
                fontSize = ValueSize,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun RetroSettingGroup(content: @Composable () -> Unit) {
    SharedSettingGroup(content)
}

@Composable
internal fun RetroGroupTitle(text: String) {
    SharedGroupTitle(text)
}

@Composable
internal fun RetroInfoRow(
    label: String,
    value: String,
    singleLineValue: Boolean = false,
) {
    SharedInfoRow(label, value, singleLineValue = singleLineValue)
}

@Composable
internal fun RetroSettingDropdown(
    label: String,
    entries: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = entries.getOrElse(selectedIndex) { "" }
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

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = ItemGap)) {
        Text(
            label,
            color = TextSecondary,
            fontSize = LabelSize,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(bottom = TightGap),
        )
        Box {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FieldCorner))
                        .background(InputSurface)
                        .border(1.dp, InputBorder, RoundedCornerShape(FieldCorner))
                        .paneNavItem(
                            cornerRadius = FieldCorner,
                            onActivate = { expanded = true },
                            highlightColor = NavHighlight,
                        )
                        .clickable { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedText,
                    color = TextPrimary,
                    fontSize = ValueSize,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextDim,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
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
                                    fontSize = ValueSize,
                                    fontWeight = if (index == selectedIndex) FontWeight.Medium else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                onSelected(index)
                                expanded = false
                            },
                            modifier =
                                (
                                    if (index == selectedIndex) {
                                        Modifier.background(AccentBlue.copy(alpha = 0.06f))
                                    } else {
                                        Modifier
                                    }
                                ).paneNavItem(
                                    cornerRadius = 6.dp,
                                    onActivate = {
                                        onSelected(index)
                                        expanded = false
                                    },
                                    isEntry = index == selectedIndex,
                                    highlightColor = NavHighlight,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RetroSettingSwitch(
    label: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    SharedSettingSwitch(label, checked, subtitle, onCheckedChange)
}

@Composable
internal fun RetroSettingTextField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }
    if (editing) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text(placeholder) },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onChange(draft.trim())
                    editing = false
                }) { Text(stringResource(R.string.retro_gs_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { editing = false }) { Text(stringResource(R.string.retro_gs_cancel)) }
            },
        )
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { draft = value; editing = true }
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = { draft = value; editing = true },
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(vertical = TightGap + 2.dp),
    ) {
        Text(
            label,
            color = TextSecondary,
            fontSize = LabelSize,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            softWrap = true,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value.ifBlank { placeholder },
            color = if (value.isBlank()) TextSecondary else AccentBlue,
            fontSize = ValueSize,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            softWrap = true,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        )
    }
}

private fun coreDisplayName(system: RetroSystem?): String =
    when {
        system == null -> ""
        system.id == RetroSystems.PS2.id -> "ARMSX2"
        RetroCoreManager.usesDolphinCore(system) -> "Dolphin"
        else -> system.coreFileName
    }

@Composable
private fun RetroConsoleEditPen(onClick: () -> Unit) {
    val green = Color(0xFF3FB950)
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(green.copy(alpha = 0.12f))
                .border(1.dp, green.copy(alpha = 0.55f), RoundedCornerShape(9.dp))
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Edit,
            contentDescription = stringResource(R.string.retro_gs_change_console),
            tint = green,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun RetroConsolePickerDialog(
    current: RetroSystem?,
    onDismiss: () -> Unit,
    onPick: (RetroSystem) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.retro_gs_identify_as)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RetroSystems.ALL.sortedBy { it.displayName }.forEach { sys ->
                    val selected = sys.id == current?.id
                    Text(
                        sys.displayName,
                        color = if (selected) GameSettingsStyle.AccentBlue else GameSettingsStyle.TextPrimary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(sys) }
                                .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.retro_gs_cancel))
            }
        },
    )
}

@Composable
private fun RetroGeneralSection(
    state: RetroSettingsState,
    onPickArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
    onRemoveArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
    onImportBios: (() -> Unit)? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var currentSystem by remember { mutableStateOf(state.system) }
    var showConsolePicker by remember { mutableStateOf(false) }
    SharedSettingGroup {
        SharedGroupTitle(stringResource(R.string.retro_gs_group_game))
        SharedInfoRow(stringResource(R.string.retro_gs_label_name), state.name)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                SharedInfoRow(stringResource(R.string.retro_gs_label_system), currentSystem?.displayName ?: "")
            }
            RetroConsoleEditPen { showConsolePicker = true }
        }
        SharedInfoRow(
            stringResource(R.string.retro_gs_label_emulator_core),
            coreDisplayName(currentSystem),
            singleLineValue = true,
        )
        SharedInfoRow(
            stringResource(R.string.retro_gs_label_rom_path),
            state.romPath,
            singleLineValue = true,
        )
        if (currentSystem?.isExternal == true) {
            val ps2Prefs = remember(ctx) { ctx.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE) }
            var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            @Suppress("UNUSED_EXPRESSION") version
            SharedSettingSwitch(stringResource(R.string.retro_gs_fast_boot), ps2Prefs.getBoolean("wn.ps2.fastboot", true)) {
                ps2Prefs.edit().putBoolean("wn.ps2.fastboot", it).apply(); version++
            }
        }
    }
    if (showConsolePicker) {
        RetroConsolePickerDialog(
            current = currentSystem,
            onDismiss = { showConsolePicker = false },
        ) { picked ->
            showConsolePicker = false
            if (picked.id != currentSystem?.id) {
                currentSystem = picked
                kotlin.concurrent.thread {
                    runCatching {
                        state.shortcut.putExtra(RetroShortcuts.KEY_SYSTEM, picked.id)
                        state.shortcut.saveData()
                    }
                }
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(R.string.retro_gs_console_changed, picked.displayName),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val biosSystem = currentSystem
    if (biosSystem?.needsBios == true && onImportBios != null) {
        val context = ctx
        Spacer(Modifier.height(ItemGap))
        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_gs_group_bios, biosSystem.shortName.uppercase()))
            state.biosRefresh
            val dir = RetroCoreManager.systemDir(context)
            val installed = biosSystem.biosFiles.filter { java.io.File(dir, it).isFile }
            RetroInfoRow(
                stringResource(R.string.retro_gs_label_installed),
                if (installed.isEmpty()) stringResource(R.string.retro_gs_bios_none, biosSystem.shortName) else installed.joinToString(", "),
            )
            androidx.compose.material3.Button(
                onClick = { onImportBios() },
                modifier = Modifier.fillMaxWidth().padding(top = ItemGap),
            ) {
                androidx.compose.material3.Text(stringResource(R.string.retro_gs_import_bios))
            }
            if (installed.isNotEmpty()) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        installed.forEach { java.io.File(dir, it).delete() }
                        state.biosRefresh++
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = ItemGap),
                ) {
                    androidx.compose.material3.Text(stringResource(R.string.retro_gs_remove_bios))
                }
            }
        }
    }
    if (onPickArtwork != null && onRemoveArtwork != null) {
        Spacer(Modifier.height(ItemGap))
        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_gs_group_library_artwork))
            Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
                Box(Modifier.weight(1f)) {
                    RetroArtworkRow(
                        title = stringResource(R.string.retro_gs_artwork_game_card),
                        selected = state.artworkSelected[LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD] == true,
                        onPick = { onPickArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD) },
                        onRemove = { onRemoveArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD) },
                    )
                }
                Box(Modifier.weight(1f)) {
                    RetroArtworkRow(
                        title = stringResource(R.string.retro_gs_artwork_grid),
                        selected = state.artworkSelected[LibraryShortcutArtwork.LibraryArtworkSlot.GRID] == true,
                        onPick = { onPickArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.GRID) },
                        onRemove = { onRemoveArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.GRID) },
                    )
                }
            }
            Spacer(Modifier.height(ItemGap))
            Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
                Box(Modifier.weight(1f)) {
                    RetroArtworkRow(
                        title = stringResource(R.string.retro_gs_artwork_carousel),
                        selected = state.artworkSelected[LibraryShortcutArtwork.LibraryArtworkSlot.CAROUSEL] == true,
                        onPick = { onPickArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.CAROUSEL) },
                        onRemove = { onRemoveArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.CAROUSEL) },
                    )
                }
                Box(Modifier.weight(1f)) {
                    RetroArtworkRow(
                        title = stringResource(R.string.retro_gs_artwork_list),
                        selected = state.artworkSelected[LibraryShortcutArtwork.LibraryArtworkSlot.LIST] == true,
                        onPick = { onPickArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.LIST) },
                        onRemove = { onRemoveArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.LIST) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RetroArtworkRow(
    title: String,
    selected: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(GroupCorner))
                .background(InputSurface)
                .border(1.dp, InputBorder, RoundedCornerShape(GroupCorner))
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = ValueSize,
                    fontWeight = FontWeight.SemiBold,
                )
                if (selected) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.retro_gs_artwork_custom_set),
                        color = TextSecondary,
                        fontSize = LabelSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!selected) {
                    RetroArtworkActionButton(stringResource(R.string.retro_gs_artwork_set_image), AccentBlue, onPick)
                } else {
                    RetroArtworkActionButton(stringResource(R.string.retro_gs_artwork_remove), DangerRed, onRemove)
                }
            }
        }
    }
}

@Composable
private fun RetroArtworkActionButton(
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.08f))
                .border(1.dp, tint.copy(alpha = 0.2f), RoundedCornerShape(9.dp))
                .paneNavItem(cornerRadius = 9.dp, onActivate = { onClick() }, highlightColor = NavHighlight)
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = LabelSize,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun RetroGraphicsSection(state: RetroSettingsState) {
    if (state.system?.isExternal == true) {
        RetroPs2GraphicsSection()
        return
    }
    val engineCtx = androidx.compose.ui.platform.LocalContext.current
    var engine3dSupported by remember { mutableStateOf(false) }
    LaunchedEffect(state.shortcut.file.absolutePath) {
        engine3dSupported =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { Gen1EmbedLaunch.isCompatible(engineCtx, state.shortcut) }
                    .getOrDefault(false)
            }
    }
    if (engine3dSupported) {
        RetroSettingSwitch(
            label = stringResource(R.string.retro_gs_engine_3d),
            checked = state.engine3d,
            subtitle = stringResource(R.string.retro_gs_engine_3d_subtitle),
            onCheckedChange = { state.engine3d = it },
        )
    }
    val shaderLabels = listOf(
        stringResource(R.string.retro_gs_shader_default),
        stringResource(R.string.retro_gs_shader_crt),
        stringResource(R.string.retro_gs_shader_lcd),
        stringResource(R.string.retro_gs_shader_sharp),
    )
    val upscaleLabels = listOf(
        stringResource(R.string.retro_gs_upscale_2x),
        stringResource(R.string.retro_gs_upscale_4x),
        stringResource(R.string.retro_gs_upscale_native),
    )
    val embeddedDolphinContext = androidx.compose.ui.platform.LocalContext.current
    val embeddedDolphin =
        RetroCoreManager.usesDolphinCore(state.system) &&
            RetroShortcuts.embeddedDolphinEnabled(embeddedDolphinContext)
    val voxelEngine = state.engine3d
    if (voxelEngine) {
        Spacer(Modifier.height(12.dp))
        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_gs_engine_3d).uppercase())
            RetroStadiumRomSetting()
        }
        RetroEngineRowSettings(state)
    }
    if (!voxelEngine) {
    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_video))
        if (embeddedDolphin) {
            DolphinGpuDriverDropdown()
        }
        if (!embeddedDolphin) {
            RetroSettingDropdown(
                label = stringResource(R.string.retro_gs_video_filter),
                entries = shaderLabels,
                selectedIndex = SHADER_KEYS.indexOf(state.shader).coerceAtLeast(0),
                onSelected = { state.shader = SHADER_KEYS[it] },
            )
            RetroSettingSwitch(
                label = stringResource(R.string.retro_gs_sgsr),
                checked = state.sgsr,
                onCheckedChange = { state.sgsr = it },
            )
            if (state.sgsr) {
                RetroSettingDropdown(
                    label = stringResource(R.string.retro_gs_sgsr_upscale),
                    entries = upscaleLabels,
                    selectedIndex = UPSCALE_KEYS.indexOf(state.upscale).coerceAtLeast(0),
                    onSelected = { state.upscale = UPSCALE_KEYS[it] },
                )
            }
        }
    }
    }
    if (state.coreOptions.isNotEmpty() && !voxelEngine) {
        Spacer(Modifier.height(12.dp))
        RetroSettingGroup {
            RetroGroupTitle((state.system?.shortName ?: stringResource(R.string.retro_gs_group_core)).uppercase())
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val ordered =
                state.coreOptions.filterNot { it.advanced } + state.coreOptions.filter { it.advanced }
            ordered.forEach { option ->
                val current = state.optionValues[option.key] ?: option.defaultValue
                RetroSettingDropdown(
                    label = option.labelText(ctx),
                    entries = option.valueLabels,
                    selectedIndex = option.values.indexOf(current).coerceAtLeast(0),
                    onSelected = { state.optionValues[option.key] = option.values[it] },
                )
            }
        }
    }
}

@Composable
private fun RetroFrameGenerationGroup(state: RetroSettingsState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var shaderState by remember { mutableIntStateOf(FRAMEGEN_SHADERS_CHECKING) }
    var sourceName by remember { mutableStateOf("") }
    var gpuSupported by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val outcome =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                gpuSupported = RetroFrameGen.supported(context)
                com.winlator.cmod.feature.library.LosslessAutoImport.sync(context)
            }
        sourceName = outcome.sourceName
        shaderState = retroFrameGenStateFor(outcome.result)
    }

    val ready =
        gpuSupported &&
            (shaderState == FRAMEGEN_SHADERS_READY || shaderState == FRAMEGEN_SHADERS_UPDATED)
    val enabled = ready && state.frameGen

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.settings_frame_generation_title).uppercase())
        RetroSettingSwitch(
            label = stringResource(R.string.session_drawer_frame_generation_enable),
            checked = enabled,
            subtitle = stringResource(R.string.retro_gs_frame_generation_subtitle),
            onCheckedChange = { if (ready) state.frameGen = it },
        )
        RetroSettingNote(
            when {
                !gpuSupported -> stringResource(R.string.retro_gs_frame_generation_unsupported)
                shaderState == FRAMEGEN_SHADERS_IMPORTING || shaderState == FRAMEGEN_SHADERS_CHECKING ->
                    stringResource(R.string.settings_frame_generation_importing)
                shaderState == FRAMEGEN_SHADERS_READY ->
                    if (sourceName.isEmpty()) {
                        stringResource(R.string.settings_frame_generation_ready)
                    } else {
                        stringResource(R.string.settings_frame_generation_imported, sourceName)
                    }
                shaderState == FRAMEGEN_SHADERS_UPDATED ->
                    stringResource(R.string.settings_frame_generation_updated, sourceName)
                shaderState == FRAMEGEN_SHADERS_NOT_OWNED ->
                    stringResource(R.string.settings_frame_generation_not_owned)
                shaderState == FRAMEGEN_SHADERS_FAILED ->
                    stringResource(R.string.settings_frame_generation_failed)
                else -> stringResource(R.string.settings_frame_generation_not_found)
            },
        )
        if (gpuSupported && shaderState != FRAMEGEN_SHADERS_NOT_OWNED) {
            RetroFrameGenLocateButton(context) { result, name ->
                sourceName = name
                shaderState = retroFrameGenStateFor(result)
            }
        }
        if (enabled) {
            RetroSettingDropdown(
                label = stringResource(R.string.session_drawer_frame_generation_target),
                entries =
                    FrameGenOptions.TARGET_OPTIONS.map { rate ->
                        if (rate == 0) {
                            stringResource(R.string.session_drawer_frame_generation_target_off)
                        } else {
                            stringResource(R.string.session_drawer_frame_generation_target_value, rate)
                        }
                    },
                selectedIndex =
                    FrameGenOptions.TARGET_OPTIONS.indexOf(state.frameGenTargetRate).coerceAtLeast(0),
                onSelected = { state.frameGenTargetRate = FrameGenOptions.TARGET_OPTIONS[it] },
            )
            if (state.frameGenTargetRate == 0) {
                RetroSettingDropdown(
                    label = stringResource(R.string.session_drawer_frame_generation_multiplier),
                    entries =
                        FrameGenOptions.MULTIPLIER_OPTIONS.map {
                            stringResource(R.string.session_drawer_frame_generation_multiplier_value, it)
                        },
                    selectedIndex =
                        FrameGenOptions.MULTIPLIER_OPTIONS
                            .indexOf(state.frameGenMultiplier)
                            .coerceAtLeast(0),
                    onSelected = { state.frameGenMultiplier = FrameGenOptions.MULTIPLIER_OPTIONS[it] },
                )
            }
            RetroSettingDropdown(
                label = stringResource(R.string.frame_generation_preset),
                entries = FrameGenPreset.values().map { stringResource(it.labelRes) },
                selectedIndex = FrameGenPreset.fromFlowScale(state.frameGenFlowScale).ordinal,
                onSelected = { state.frameGenFlowScale = FrameGenPreset.atIndex(it).flowScale },
            )
            RetroSettingNote(
                stringResource(FrameGenPreset.fromFlowScale(state.frameGenFlowScale).descriptionRes),
            )
            RetroSettingNote(stringResource(R.string.session_drawer_frame_generation_note))
        }
    }
}

@Composable
private fun RetroFrameGenLocateButton(
    context: android.content.Context,
    onImported: (Int, String) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    OutlinedButton(
        onClick = {
            val activity = context.findActivity() ?: return@OutlinedButton
            val imagefsRoot = com.winlator.cmod.runtime.display.environment.ImageFs.find(context).rootDir
            com.winlator.cmod.shared.android.DirectoryPickerDialog.showFile(
                activity = activity,
                title = context.getString(R.string.settings_frame_generation_locate),
                allowedExtensions = setOf("dll"),
                extraRoots =
                    listOf(
                        com.winlator.cmod.shared.android.DirectoryPickerDialog.ManagedRoot(
                            "C:",
                            java.io.File(imagefsRoot, "home").absolutePath,
                        ),
                        com.winlator.cmod.shared.android.DirectoryPickerDialog.ManagedRoot(
                            "D:",
                            android.os.Environment
                                .getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS,
                                ).absolutePath,
                        ),
                        com.winlator.cmod.shared.android.DirectoryPickerDialog.ManagedRoot(
                            "Internal",
                            android.os.Environment.getExternalStorageDirectory().absolutePath,
                        ),
                    ),
            ) { pickedPath ->
                scope.launch {
                    val outcome =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.winlator.cmod.feature.library.LosslessAutoImport.importFrom(
                                context,
                                java.io.File(pickedPath),
                            )
                        }
                    onImported(outcome.result, outcome.sourceName)
                }
            }
        },
        modifier = Modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(FieldCorner),
        border = BorderStroke(1.dp, InputBorder),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = InputSurface,
                contentColor = TextPrimary,
            ),
    ) {
        Text(
            stringResource(R.string.settings_frame_generation_locate),
            fontSize = ValueSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val FRAMEGEN_SHADERS_CHECKING = 0
private const val FRAMEGEN_SHADERS_IMPORTING = 1
private const val FRAMEGEN_SHADERS_READY = 2
private const val FRAMEGEN_SHADERS_UPDATED = 3
private const val FRAMEGEN_SHADERS_NOT_FOUND = 4
private const val FRAMEGEN_SHADERS_NOT_OWNED = 5
private const val FRAMEGEN_SHADERS_FAILED = 6

private fun retroFrameGenStateFor(result: Int): Int =
    when (result) {
        com.winlator.cmod.feature.library.LosslessAutoImport.RESULT_READY -> FRAMEGEN_SHADERS_READY
        com.winlator.cmod.feature.library.LosslessAutoImport.RESULT_IMPORTED -> FRAMEGEN_SHADERS_READY
        com.winlator.cmod.feature.library.LosslessAutoImport.RESULT_UPDATED -> FRAMEGEN_SHADERS_UPDATED
        com.winlator.cmod.feature.library.LosslessAutoImport.RESULT_NOT_OWNED -> FRAMEGEN_SHADERS_NOT_OWNED
        com.winlator.cmod.feature.library.LosslessAutoImport.RESULT_FAILED -> FRAMEGEN_SHADERS_FAILED
        else -> FRAMEGEN_SHADERS_NOT_FOUND
    }

@Composable
private fun RetroEngineRowSettings(state: RetroSettingsState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rows = remember(context) { Gen1EngineSettings.cached(context) }
    if (rows.isEmpty()) {
        RetroSettingGroup {
            RetroSettingNote(stringResource(R.string.retro_gs_engine_3d_uncached))
        }
        return
    }
    val panes =
        listOf(
            Gen1EngineSettings.PANE_DISPLAY to R.string.retro_gs_section_graphics,
            Gen1EngineSettings.PANE_SOUND to R.string.retro_gs_group_audio,
            Gen1EngineSettings.PANE_PERFORMANCE to R.string.retro_gs_section_performance,
            Gen1EngineSettings.PANE_CONTROLS to R.string.retro_gs_group_input,
            Gen1EngineSettings.PANE_SYSTEM to R.string.retro_gs_section_general,
        )
    panes.forEach { (pane, titleRes) ->
        val paneRows = rows.filter { it.pane == pane }
        if (paneRows.isEmpty()) return@forEach
        Spacer(Modifier.height(12.dp))
        RetroSettingGroup {
            RetroGroupTitle(stringResource(titleRes).uppercase())
            paneRows.forEach { row ->
                val current = state.engineValues[row.id]
                RetroSettingDropdown(
                    label = row.label,
                    entries = listOf(stringResource(R.string.retro_gs_engine_3d_leave)) + row.values,
                    selectedIndex = (row.values.indexOf(current) + 1).coerceAtLeast(0),
                    onSelected = { index ->
                        if (index == 0) {
                            state.engineValues.remove(row.id)
                        } else {
                            state.engineValues[row.id] = row.values[index - 1]
                        }
                    },
                )
            }
        }
    }
    RetroSettingNote(stringResource(R.string.retro_gs_engine_3d_note))
}

@Composable
private fun RetroSettingNote(text: String) {
    Text(
        text = text,
        color = TextDim,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun RetroStadiumRomSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val installed = remember(refresh) { Gen1StadiumRom.isInstalled(context) }
    val staged = remember(refresh) { Gen1StadiumRom.hasStagedPick(context) }

    val picker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            Gen1StadiumRom.stage(context, uri)
                        }
                    result
                        .onSuccess {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.retro_stadium_imported),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                        .onFailure {
                            android.widget.Toast.makeText(
                                context,
                                it.message ?: context.getString(R.string.retro_stadium_import_failed),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    refresh++
                }
            }
        }

    val status =
        when {
            staged -> stringResource(R.string.retro_stadium_building)
            installed -> stringResource(R.string.retro_stadium_ready)
            else -> stringResource(R.string.retro_stadium_import)
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FieldCorner))
                .background(InputSurface)
                .border(1.dp, InputBorder, RoundedCornerShape(FieldCorner))
                .clickable {
                    when {
                        staged -> Unit
                        installed -> confirmDelete = true
                        else -> picker.launch(arrayOf("*/*"))
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.retro_stadium_row),
            color = TextPrimary,
            fontSize = ValueSize,
            modifier = Modifier.weight(1f),
        )
        Text(status, color = if (installed) TextPrimary else TextDim, fontSize = ValueSize)
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.retro_stadium_row)) },
            text = { Text(stringResource(R.string.retro_stadium_delete_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    val gone = Gen1StadiumRom.delete(context)
                    android.widget.Toast.makeText(
                        context,
                        context.getString(
                            if (gone) R.string.retro_stadium_deleted else R.string.retro_stadium_delete_failed,
                        ),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    refresh++
                }) { Text(stringResource(R.string.retro_stadium_delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.retro_stadium_keep))
                }
            },
        )
    }
}

@Composable
private fun DolphinGpuDriverDropdown() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    var driverVersion by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") driverVersion
    val gcDrivers = remember { com.armsx2.CustomDriver.listInstalled(context) }
    val gcDriverIds = remember(gcDrivers) { listOf("") + gcDrivers.map { it.id } }
    val gcDriverLabels = listOf(stringResource(R.string.retro_gpu_driver_system)) + gcDrivers.map { it.name }
    val curGcDriver = (prefs.getString(DolphinEmbedLaunch.DRIVER_PREF, "") ?: "").let { if (it.equals("system", true)) "" else it }
    RetroSettingDropdown(
        label = stringResource(R.string.retro_gpu_driver),
        entries = gcDriverLabels,
        selectedIndex = gcDriverIds.indexOf(curGcDriver).coerceAtLeast(0),
        onSelected = {
            prefs.edit().putString(DolphinEmbedLaunch.DRIVER_PREF, gcDriverIds[it]).apply()
            driverVersion++
        },
    )
}

@Composable
internal fun RetroLibretroHudSection(
    hudOn: Boolean,
    onHud: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") version
    val elements = remember(version) { RetroHudSupport.loadGlobalHudElements(context) }
    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_performance_hud))
        RetroSettingSwitch(stringResource(R.string.retro_lr_performance_hud), hudOn) {
            onHud(it)
            version++
        }
        if (hudOn) {
            RetroHudElementButtons(elements) { index, on ->
                val next = elements.copyOf()
                next[index] = on
                RetroHudSupport.saveGlobalHudElements(context, next)
                version++
            }
        }
    }
}

@Composable
private fun RetroHudElementButtons(
    elements: BooleanArray,
    onToggle: (index: Int, on: Boolean) -> Unit,
) {
    RetroHudSupport.ELEMENT_ORDER.toList().chunked(3).forEach { rowIndices ->
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rowIndices.forEach { index ->
                val on = elements[index]
                HudToggleButton(
                    label = stringResource(RetroHudSupport.ELEMENT_LABEL_RES[index]),
                    on = on,
                    modifier = Modifier.weight(1f),
                ) { onToggle(index, !on) }
            }
            repeat(3 - rowIndices.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun HudToggleButton(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        border =
            BorderStroke(
                1.dp,
                if (on) GameSettingsStyle.AccentBlue else GameSettingsStyle.CardBorder,
            ),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor =
                    if (on) GameSettingsStyle.AccentBlue.copy(alpha = 0.15f) else Color.Transparent,
                contentColor =
                    if (on) GameSettingsStyle.AccentBlue else GameSettingsStyle.TextSecondary,
            ),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal val PS2_TURNIP_FLAGS: List<Pair<String, String>> = listOf(
    "sysmem" to "Force system-memory rendering",
    "gmem" to "Force GMEM (tiled GPU memory) rendering",
    "flushall" to "Flush all caches every draw",
    "nolrz" to "Disable low-resolution Z (LRZ)",
    "nolrzfc" to "Disable LRZ fast-clear",
    "noubwc" to "Disable bandwidth compression (UBWC)",
    "noconform" to "Skip non-conformant fast paths",
    "syncdraw" to "Synchronise every draw",
    "forcebin" to "Force binning (tiled) mode",
)

@Composable
internal fun Ps2TurnipFlags(prefs: android.content.SharedPreferences, refreshKey: Int, bump: () -> Unit) {
    Text(
        stringResource(R.string.retro_ps2_turnip_flags),
        color = TextSecondary,
        fontSize = LabelSize,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = ItemGap, bottom = TightGap),
    )
    val usingTurnip = remember(refreshKey) {
        val driver = (prefs.getString("wn.ps2.driver", "") ?: "").trim()
        driver.isNotEmpty() && !driver.equals("system", ignoreCase = true)
    }
    if (!usingTurnip) {
        Text(
            stringResource(R.string.retro_ps2_turnip_flags_note),
            color = TextDim,
            fontSize = LabelSize,
            modifier = Modifier.padding(bottom = TightGap),
        )
    }
    val active = remember(refreshKey) {
        (prefs.getString("wn.ps2.turnipflags", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    PS2_TURNIP_FLAGS.forEach { (flag, hint) ->
        RetroSettingSwitch(flag, flag in active, subtitle = hint) { on ->
            val next = if (on) active + flag else active - flag
            prefs.edit().putString("wn.ps2.turnipflags", next.joinToString(",")).apply()
            bump()
        }
    }
}

@Composable
private fun RetroPs2GraphicsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE) }
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    @Suppress("UNUSED_EXPRESSION") version

    fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply(); version++ }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); version++ }
    fun putStr(key: String, value: String) { prefs.edit().putString(key, value).apply(); version++ }
    fun putFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).apply(); version++ }

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_video))
        val rendererKeys = listOf("vulkan", "opengl", "software")
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_renderer),
            listOf(
                stringResource(R.string.retro_gs_renderer_vulkan),
                stringResource(R.string.retro_gs_renderer_opengl),
                stringResource(R.string.retro_gs_renderer_software),
            ),
            rendererKeys.indexOf(prefs.getString("wn.ps2.renderer", "vulkan")).coerceAtLeast(0),
        ) { putStr("wn.ps2.renderer", rendererKeys[it]) }
        val ps2Drivers = remember { com.armsx2.CustomDriver.listInstalled(context) }
        val driverIds = remember(ps2Drivers) { listOf("") + ps2Drivers.map { it.id } }
        val driverLabels = listOf(stringResource(R.string.retro_gpu_driver_system)) + ps2Drivers.map { it.name }
        val curDriver = (prefs.getString("wn.ps2.driver", "") ?: "").let { if (it.equals("system", true)) "" else it }
        RetroSettingDropdown(
            stringResource(R.string.retro_gpu_driver),
            driverLabels,
            driverIds.indexOf(curDriver).coerceAtLeast(0),
        ) { putStr("wn.ps2.driver", driverIds[it]) }
        if (ps2Drivers.isEmpty()) {
            Text(
                stringResource(R.string.retro_gpu_driver_hint),
                color = TextDim,
                fontSize = LabelSize,
                modifier = Modifier.padding(top = TightGap),
            )
        }
        Ps2TurnipFlags(prefs, version) { version++ }
        val scales = listOf(1f, 1.5f, 2f, 3f, 4f)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_resolution_scale),
            listOf(
                stringResource(R.string.retro_gs_scale_1x_native),
                stringResource(R.string.retro_gs_scale_1_5x),
                stringResource(R.string.retro_gs_scale_2x),
                stringResource(R.string.retro_gs_scale_3x),
                stringResource(R.string.retro_gs_scale_4x),
            ),
            scales.indexOfFirst { kotlin.math.abs(it - prefs.getFloat("wn.ps2.upscale", 1f)) < 0.01f }.coerceAtLeast(0),
        ) { putFloat("wn.ps2.upscale", scales[it]) }
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_aspect_ratio),
            listOf(
                stringResource(R.string.retro_gs_aspect_stretch),
                stringResource(R.string.retro_gs_aspect_auto_standard),
                stringResource(R.string.retro_gs_aspect_4_3),
                stringResource(R.string.retro_gs_aspect_16_9),
            ),
            prefs.getInt("wn.ps2.aspect", 1).coerceIn(0, 3),
        ) { putInt("wn.ps2.aspect", it) }
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_fmv_aspect_ratio),
            listOf(
                stringResource(R.string.retro_gs_off),
                stringResource(R.string.retro_gs_aspect_auto_standard),
                stringResource(R.string.retro_gs_aspect_4_3),
                stringResource(R.string.retro_gs_aspect_16_9),
            ),
            prefs.getInt("wn.ps2.fmvaspect", 0).coerceIn(0, 3),
        ) { putInt("wn.ps2.fmvaspect", it) }
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_deinterlace_mode),
            listOf(
                stringResource(R.string.retro_gs_deint_auto),
                stringResource(R.string.retro_gs_deint_off),
                stringResource(R.string.retro_gs_deint_weave_tff),
                stringResource(R.string.retro_gs_deint_weave_bff),
                stringResource(R.string.retro_gs_deint_bob_tff),
                stringResource(R.string.retro_gs_deint_bob_bff),
                stringResource(R.string.retro_gs_deint_blend_tff),
                stringResource(R.string.retro_gs_deint_blend_bff),
                stringResource(R.string.retro_gs_deint_adaptive_tff),
                stringResource(R.string.retro_gs_deint_adaptive_bff),
            ),
            prefs.getInt("wn.ps2.deinterlace", 0).coerceIn(0, 9),
        ) { putInt("wn.ps2.deinterlace", it) }
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_display_filter),
            listOf(
                stringResource(R.string.retro_gs_filter_nearest),
                stringResource(R.string.retro_gs_filter_bilinear_smooth),
                stringResource(R.string.retro_gs_filter_bilinear_sharp),
            ),
            prefs.getInt("wn.ps2.displayfilter", 1).coerceIn(0, 2),
        ) { putInt("wn.ps2.displayfilter", it) }
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_texture_filter),
            listOf(
                stringResource(R.string.retro_gs_filter_nearest),
                stringResource(R.string.retro_gs_filter_bilinear_forced),
                stringResource(R.string.retro_gs_filter_bilinear_ps2),
                stringResource(R.string.retro_gs_filter_bilinear_sprites),
            ),
            prefs.getInt("wn.ps2.filter", 2).coerceIn(0, 3),
        ) { putInt("wn.ps2.filter", it) }
        RetroSettingSwitch(stringResource(R.string.retro_gs_mipmapping), prefs.getBoolean("wn.ps2.mipmap", true)) { putBool("wn.ps2.mipmap", it) }
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_blending_accuracy),
            listOf(
                stringResource(R.string.retro_gs_blend_minimum),
                stringResource(R.string.retro_gs_blend_basic),
                stringResource(R.string.retro_gs_blend_medium),
                stringResource(R.string.retro_gs_blend_high),
                stringResource(R.string.retro_gs_blend_full),
                stringResource(R.string.retro_gs_blend_maximum),
            ),
            prefs.getInt("wn.ps2.blend", 1).coerceIn(0, 5),
        ) { putInt("wn.ps2.blend", it) }
        RetroSettingSwitch(stringResource(R.string.retro_gs_anti_blur), prefs.getBoolean("wn.ps2.antiblur", true)) { putBool("wn.ps2.antiblur", it) }
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_crt_tv_shader),
            listOf(
                stringResource(R.string.retro_gs_off),
                stringResource(R.string.retro_gs_tvshader_scanline),
                stringResource(R.string.retro_gs_tvshader_diagonal),
                stringResource(R.string.retro_gs_tvshader_triangular),
                stringResource(R.string.retro_gs_tvshader_wave),
                stringResource(R.string.retro_gs_tvshader_lottes),
                stringResource(R.string.retro_gs_tvshader_4xrgss),
                stringResource(R.string.retro_gs_tvshader_nxagss),
            ),
            prefs.getInt("wn.ps2.tvshader", 0).coerceIn(0, 7),
        ) { putInt("wn.ps2.tvshader", it) }
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_frame_skip),
            listOf(
                stringResource(R.string.retro_gs_off),
                stringResource(R.string.retro_gs_frameskip_1),
                stringResource(R.string.retro_gs_frameskip_2),
                stringResource(R.string.retro_gs_frameskip_3),
            ),
            prefs.getInt("wn.ps2.frameskip", 0).coerceIn(0, 3),
        ) { putInt("wn.ps2.frameskip", it) }
        RetroSettingSwitch(stringResource(R.string.retro_gs_widescreen_patches), prefs.getBoolean("wn.ps2.widescreen", false)) { putBool("wn.ps2.widescreen", it) }
        RetroSettingSwitch(stringResource(R.string.retro_gs_no_interlace_patches), prefs.getBoolean("wn.ps2.nointerlace", false)) { putBool("wn.ps2.nointerlace", it) }
    }
}

@Composable
private fun RetroPs2PerformanceSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE) }
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    @Suppress("UNUSED_EXPRESSION") version

    fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply(); version++ }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); version++ }

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_performance))
        val rateValues = listOf(-3, -2, -1, 0, 1, 2, 3)
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_ee_cycle_rate),
            listOf(
                stringResource(R.string.retro_gs_rate_50),
                stringResource(R.string.retro_gs_rate_60),
                stringResource(R.string.retro_gs_rate_75),
                stringResource(R.string.retro_gs_rate_100_default),
                stringResource(R.string.retro_gs_rate_130),
                stringResource(R.string.retro_gs_rate_180),
                stringResource(R.string.retro_gs_rate_300),
            ),
            rateValues.indexOf(prefs.getInt("wn.ps2.eeRate", 0).coerceIn(-3, 3)).coerceAtLeast(0),
        ) { putInt("wn.ps2.eeRate", rateValues[it]) }
        RetroSettingDropdown(
            stringResource(R.string.retro_gs_ee_cycle_skip),
            listOf(
                stringResource(R.string.retro_gs_off),
                stringResource(R.string.retro_gs_num_1),
                stringResource(R.string.retro_gs_num_2),
                stringResource(R.string.retro_gs_num_3),
            ),
            prefs.getInt("wn.ps2.eeSkip", 0).coerceIn(0, 3),
        ) { putInt("wn.ps2.eeSkip", it) }
        RetroSettingSwitch(stringResource(R.string.retro_gs_mtvu), prefs.getBoolean("wn.ps2.mtvu", true)) { putBool("wn.ps2.mtvu", it) }
        RetroSettingSwitch(stringResource(R.string.retro_gs_instant_vu1), prefs.getBoolean("wn.ps2.instantVu1", true)) { putBool("wn.ps2.instantVu1", it) }
        RetroSettingSwitch(stringResource(R.string.retro_gs_vu_flag_hack), prefs.getBoolean("wn.ps2.vuFlagHack", true)) { putBool("wn.ps2.vuFlagHack", it) }
        RetroSettingSwitch(stringResource(R.string.retro_gs_intc_spin), prefs.getBoolean("wn.ps2.intc", true)) { putBool("wn.ps2.intc", it) }
        RetroSettingSwitch(stringResource(R.string.retro_gs_wait_loop), prefs.getBoolean("wn.ps2.waitloop", true)) { putBool("wn.ps2.waitloop", it) }
        RetroSettingSwitch(stringResource(R.string.retro_gs_fast_cdvd), prefs.getBoolean("wn.ps2.fastCdvd", false)) { putBool("wn.ps2.fastCdvd", it) }
    }
}

@Composable
private fun RetroPs2HudSection(state: RetroSettingsState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    @Suppress("UNUSED_EXPRESSION") version

    val hudOn = state.hud
    val elements = remember(version) { RetroHudSupport.loadPs2Elements(context) }

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_performance_hud))
        RetroSettingSwitch(stringResource(R.string.retro_lr_performance_hud), hudOn) {
            state.hud = it
            version++
        }
        if (hudOn) {
            RetroHudElementButtons(elements) { index, on ->
                val next = elements.copyOf()
                next[index] = on
                RetroHudSupport.savePs2Elements(context, next)
                version++
            }
        }
    }
}

@Composable
private fun RetroNetplaySection(state: RetroSettingsState) {
    val systemId = state.system?.id ?: return
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    RetroNetplaySettingsSection(
        systemId = systemId,
        version = version,
        onChanged = { version++ },
    )
}

@Composable
private fun RetroDolphinNetplaySection(state: RetroSettingsState) {
    val systemId = state.system?.id ?: return
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    DolphinNetplaySettingsSection(
        systemId = systemId,
        version = version,
        onChanged = { version++ },
    )
}

@Composable
private fun RetroPs2OnlineSection(state: RetroSettingsState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE) }
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    @Suppress("UNUSED_EXPRESSION") version

    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); version++ }
    fun putStr(key: String, value: String) { prefs.edit().putString(key, value).apply(); version++ }

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_online_dev9))
        val onlineEnabled = prefs.getBoolean("wn.ps2.net.enable", true)
        RetroSettingSwitch(stringResource(R.string.retro_gs_enable_online), onlineEnabled) { putBool("wn.ps2.net.enable", it) }
        val hddImages = remember(version, state.hddRefresh) { RetroHddImport.installed(context).map { it.name } }
        val hddOptions = listOf(stringResource(R.string.retro_scr_none)) + hddImages
        val hddSelected = (hddImages.indexOf(state.hddImage) + 1).coerceAtLeast(0)
        RetroSettingDropdown(stringResource(R.string.retro_gs_hdd_image), hddOptions, hddSelected) { idx ->
            state.hddImage = if (idx <= 0) "" else hddImages[idx - 1]
            version++
        }
        remember(state.hddImage, state.hddRefresh) { RetroHddImport.imageFile(context, state.hddImage) }?.let { f ->
            Text(
                stringResource(R.string.retro_gs_hdd_image_size, humanSize(f.length())),
                color = TextDim,
                fontSize = LabelSize,
                modifier = Modifier.padding(top = TightGap),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ItemGap),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.OutlinedButton(
                onClick = { if (!state.hddImporting) state.requestImportHdd?.invoke() },
                enabled = !state.hddImporting,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    if (state.hddImporting) stringResource(R.string.retro_scr_importing) else stringResource(R.string.retro_scr_import_hdd_image),
                    fontSize = ValueSize,
                )
            }
            if (state.hddImage.isNotBlank()) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        val name = state.hddImage
                        RetroHddImport.delete(context, name)
                        state.hddImage = ""
                        state.hddRefresh++
                        version++
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                ) { Text(stringResource(R.string.retro_gs_hdd_remove_short), fontSize = ValueSize) }
            }
        }
        RetroSettingSwitch(stringResource(R.string.retro_ps2_hdd), state.hddEnable) { state.hddEnable = it; version++ }
        Text(
            stringResource(R.string.retro_ps2_hdd_desc),
            color = TextDim,
            fontSize = LabelSize,
            modifier = Modifier.padding(top = TightGap),
        )
        if (onlineEnabled) {
            val deviceKeys = listOf("Auto", "Wi-Fi")
            val deviceLabels = listOf(
                stringResource(R.string.retro_gs_net_auto),
                stringResource(R.string.retro_gs_net_wifi),
            )
            RetroSettingDropdown(
                stringResource(R.string.retro_gs_ethernet_device), deviceLabels,
                deviceKeys.indexOf(prefs.getString("wn.ps2.net.ethdevice", "Auto")).coerceAtLeast(0),
            ) { putStr("wn.ps2.net.ethdevice", deviceKeys[it]) }
            val dnsModeKeys = listOf("Manual", "Auto", "Internal")
            val dnsModeLabels = listOf(
                stringResource(R.string.retro_gs_dns_manual),
                stringResource(R.string.retro_gs_net_auto),
                stringResource(R.string.retro_gs_dns_internal),
            )
            RetroSettingDropdown(
                stringResource(R.string.retro_gs_dns_mode), dnsModeLabels,
                dnsModeKeys.indexOf(prefs.getString("wn.ps2.net.dnsmode", "Manual")).coerceAtLeast(0),
            ) { putStr("wn.ps2.net.dnsmode", dnsModeKeys[it]) }
            RetroSettingTextField(stringResource(R.string.retro_gs_primary_dns), prefs.getString("wn.ps2.net.dns1", PS2_DEFAULT_DNS).orEmpty(), PS2_DEFAULT_DNS) { putStr("wn.ps2.net.dns1", it) }
            RetroSettingTextField(stringResource(R.string.retro_gs_secondary_dns), prefs.getString("wn.ps2.net.dns2", "").orEmpty(), stringResource(R.string.retro_gs_dns_optional)) { putStr("wn.ps2.net.dns2", it) }
            RetroSettingSwitch(stringResource(R.string.retro_gs_dnas_bypass), prefs.getBoolean(Ps2DnasBypass.PREF, true), subtitle = stringResource(R.string.retro_gs_dnas_bypass_subtitle)) { putBool(Ps2DnasBypass.PREF, it) }
            RetroSettingSwitch(stringResource(R.string.retro_gs_auto_ip_dhcp), prefs.getBoolean("wn.ps2.net.dhcp", true)) { putBool("wn.ps2.net.dhcp", it) }
        }
    }
}

@Composable
private fun RetroPs2CheatsSection(state: RetroSettingsState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val serial = remember(state) { state.gameSerial }

    if (serial == null) {
        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_gs_group_cheats))
            Text(
                stringResource(R.string.retro_gs_cheats_detect_hint),
                color = TextSecondary,
                fontSize = ValueSize,
                modifier = Modifier.padding(vertical = TightGap),
            )
        }
        return
    }

    var loading by remember { mutableStateOf(true) }
    var cheats by remember { mutableStateOf<List<com.armsx2.PatchRepo.Entry>>(emptyList()) }
    var patches by remember { mutableStateOf<List<com.armsx2.PatchRepo.Entry>>(emptyList()) }
    var selCheats by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selPatches by remember { mutableStateOf<Set<String>>(emptySet()) }
    var repoCheatNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var repoPatchNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dnasEntries by remember { mutableStateOf<List<Ps2DnasBypass.BypassEntry>>(emptyList()) }
    var dnasGlobalOn by remember { mutableStateOf(true) }
    var dnasDisabled by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(serial, state.cheatsRefresh) {
        val stagedC = Ps2CheatStaging.read(context, serial, false)
        val stagedP = Ps2CheatStaging.read(context, serial, true)
        cheats = stagedC
        patches = stagedP
        selCheats = stagedC.map { it.name }.toSet()
        selPatches = stagedP.map { it.name }.toSet()
        dnasEntries = Ps2DnasBypass.bypassEntries(context, serial).filter { it.auto }
        dnasGlobalOn = context.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE).getBoolean(Ps2DnasBypass.PREF, true)
        dnasDisabled = Ps2DnasBypass.ensureSingleDnasEnabled(context, serial, dnasEntries.map { it.name }.toSet())
        loading = false
        val repo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.armsx2.PatchRepo.fetchForSerial(serial) }.getOrNull()
        }
        if (repo != null) {
            val repoCheats = repo.entries.filter { it.source != "patches" }
            val repoPatches = repo.entries.filter { it.source == "patches" }
            repoCheatNames = repoCheats.map { it.name }.toSet()
            repoPatchNames = repoPatches.map { it.name }.toSet()
            cheats = repoCheats + stagedC.filter { s -> repoCheats.none { it.name == s.name } }
            patches = repoPatches + stagedP.filter { s -> repoPatches.none { it.name == s.name } }
        }
    }

    fun toggleDnas(name: String, currentlyOn: Boolean) {
        if (currentlyOn) {
            dnasDisabled = dnasDisabled + name
        } else {
            if (!dnasGlobalOn) { Ps2DnasBypass.setEnabled(context, true); dnasGlobalOn = true }
            val all = dnasEntries.map { it.name }.toSet()
            dnasDisabled = all - name
        }
        Ps2DnasBypass.setDisabledNames(context, serial, dnasDisabled)
    }

    fun persist() {
        Ps2CheatStaging.write(context, serial, false, serial, cheats.filter { it.name in selCheats })
        Ps2CheatStaging.write(context, serial, true, serial, patches.filter { it.name in selPatches })
    }

    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newCodes by remember { mutableStateOf("") }
    var newIsPatch by remember { mutableStateOf(false) }

    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_cheats))
        Text(
            stringResource(R.string.retro_gs_cheats_apply_note),
            color = TextDim,
            fontSize = LabelSize,
            modifier = Modifier.padding(bottom = TightGap),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = ItemGap)) {
            androidx.compose.material3.OutlinedButton(
                onClick = { newName = ""; newCodes = ""; newIsPatch = false; showAdd = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text(stringResource(R.string.retro_scr_add), fontSize = ValueSize) }
            androidx.compose.material3.OutlinedButton(
                onClick = { state.requestImportCheatFile?.invoke(true) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text(stringResource(R.string.retro_scr_import_from_file), fontSize = ValueSize) }
        }
        when {
            loading -> Text(stringResource(R.string.retro_scr_loading), color = TextSecondary, fontSize = ValueSize)
            cheats.isEmpty() && patches.isEmpty() && dnasEntries.isEmpty() ->
                Text(stringResource(R.string.retro_scr_no_cheats_or_patches), color = TextSecondary, fontSize = ValueSize)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dnasEntries.isNotEmpty()) {
                    Ps2SectionHeader(stringResource(R.string.retro_gs_dnas_group))
                    dnasEntries.forEach { e ->
                        val on = dnasGlobalOn && e.name !in dnasDisabled
                        Ps2CheatRow(
                            com.armsx2.PatchRepo.Entry(e.name, stringResource(R.string.retro_gs_dnas_entry_desc), e.body, "dnas"),
                            on, isPatch = true,
                            onToggle = { toggleDnas(e.name, on) },
                        )
                    }
                }
                if (cheats.isNotEmpty()) {
                    Ps2SectionHeader(stringResource(R.string.retro_scr_cheats_section))
                    cheats.forEach { entry ->
                        val isCustom = entry.name !in repoCheatNames
                        Ps2CheatRow(
                            entry, entry.name in selCheats, isPatch = false,
                            onToggle = {
                                selCheats = if (entry.name in selCheats) selCheats - entry.name else selCheats + entry.name
                                persist()
                            },
                            onDelete = if (isCustom) {
                                {
                                    cheats = cheats.filterNot { it.name == entry.name }
                                    selCheats = selCheats - entry.name
                                    persist()
                                }
                            } else null,
                        )
                    }
                }
                if (patches.isNotEmpty()) {
                    Ps2SectionHeader(stringResource(R.string.retro_scr_patches_section))
                    patches.forEach { entry ->
                        val isCustom = entry.name !in repoPatchNames
                        Ps2CheatRow(
                            entry, entry.name in selPatches, isPatch = true,
                            onToggle = {
                                selPatches = if (entry.name in selPatches) selPatches - entry.name else selPatches + entry.name
                                persist()
                            },
                            onDelete = if (isCustom) {
                                {
                                    patches = patches.filterNot { it.name == entry.name }
                                    selPatches = selPatches - entry.name
                                    persist()
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.retro_scr_add_cheat)) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { newIsPatch = false }, modifier = Modifier.weight(1f),
                            colors = if (!newIsPatch)
                                androidx.compose.material3.ButtonDefaults.buttonColors()
                            else androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                        ) { Text(stringResource(R.string.retro_scr_target_cheat)) }
                        androidx.compose.material3.OutlinedButton(
                            onClick = { newIsPatch = true }, modifier = Modifier.weight(1f),
                            colors = if (newIsPatch)
                                androidx.compose.material3.ButtonDefaults.buttonColors()
                            else androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                        ) { Text(stringResource(R.string.retro_scr_target_patch)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.retro_scr_cheat_name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = newCodes, onValueChange = { newCodes = it },
                        label = { Text(stringResource(R.string.retro_scr_cheat_codes)) },
                        placeholder = { Text("2021A268 00000000") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val entry = buildCustomPnachEntry(context, newName, newCodes, if (newIsPatch) "patches" else "custom")
                    if (entry != null) {
                        if (newIsPatch) {
                            patches = patches.filterNot { it.name == entry.name } + entry
                            selPatches = selPatches + entry.name
                        } else {
                            cheats = cheats.filterNot { it.name == entry.name } + entry
                            selCheats = selCheats + entry.name
                        }
                        persist()
                        showAdd = false
                    }
                }) { Text(stringResource(R.string.retro_scr_add)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.retro_scr_cancel)) }
            },
        )
    }
}

@Composable
private fun RetroInputSection(state: RetroSettingsState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs =
        remember(context) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        }
    var haptic by remember { mutableStateOf(prefs.getFloat("retro_haptic_strength", 0.4f)) }
    val ps2 = state.system?.isExternal == true
    val ps2Prefs = if (ps2) remember(context) { context.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE) } else null
    var ps2Ver by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") ps2Ver
    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_input))
        if (ps2 && ps2Prefs != null) {
            RetroSettingSwitch(
                label = stringResource(R.string.retro_gs_on_screen_controls),
                checked = ps2Prefs.getBoolean("wn.ps2.touchcontrols", true),
                onCheckedChange = { ps2Prefs.edit().putBoolean("wn.ps2.touchcontrols", it).apply(); ps2Ver++ },
            )
            RetroSettingSwitch(
                label = stringResource(R.string.retro_lr_shell_background),
                checked = ps2Prefs.getBoolean(Ps2GameOverlay.PREF_SHELL_BG, true),
                subtitle = stringResource(R.string.retro_lr_shell_background_subtitle),
                onCheckedChange = { ps2Prefs.edit().putBoolean(Ps2GameOverlay.PREF_SHELL_BG, it).apply(); ps2Ver++ },
            )
            RetroSettingSwitch(
                label = stringResource(R.string.retro_gs_adaptive_sticks),
                checked = ps2Prefs.getBoolean("wn.ps2.adaptivesticks", false),
                subtitle = stringResource(R.string.retro_gs_adaptive_sticks_subtitle),
                onCheckedChange = { ps2Prefs.edit().putBoolean("wn.ps2.adaptivesticks", it).apply(); ps2Ver++ },
            )
            RetroSettingSwitch(
                label = stringResource(R.string.retro_ps2_show_l3r3),
                checked = ps2Prefs.getBoolean("wn.ps2.showl3r3", true),
                subtitle = stringResource(R.string.retro_ps2_show_l3r3_subtitle),
                onCheckedChange = { ps2Prefs.edit().putBoolean("wn.ps2.showl3r3", it).apply(); ps2Ver++ },
            )
        } else {
            RetroSettingSwitch(
                label = stringResource(R.string.retro_gs_on_screen_controls),
                checked = state.touchControls,
                onCheckedChange = { state.touchControls = it },
            )
            val sysId = state.system?.id
            var shellVer by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            @Suppress("UNUSED_EXPRESSION") shellVer
            RetroSettingSwitch(
                label = stringResource(R.string.retro_lr_shell_background),
                checked = RetroControlLayouts.shellBackground(context, sysId),
                subtitle = stringResource(R.string.retro_lr_shell_background_subtitle),
                onCheckedChange = { RetroControlLayouts.saveShellBackground(context, sysId, it); shellVer++ },
            )
            if (!state.engine3d) {
                RetroSettingSwitch(
                    label = stringResource(R.string.retro_gs_adaptive_sticks),
                    checked = state.adaptiveSticks,
                    subtitle = stringResource(R.string.retro_gs_adaptive_sticks_subtitle),
                    onCheckedChange = { state.adaptiveSticks = it },
                )
            }
            if (sysId == RetroSystems.PSX.id || RetroCoreManager.usesDolphinCore(state.system)) {
                var invVer by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                @Suppress("UNUSED_EXPRESSION") invVer
                listOf(
                    R.string.retro_lr_left_stick_invert_x to "retro_inv_lx_$sysId",
                    R.string.retro_lr_left_stick_invert_y to "retro_inv_ly_$sysId",
                    R.string.retro_lr_right_stick_invert_x to "retro_inv_rx_$sysId",
                    R.string.retro_lr_right_stick_invert_y to "retro_inv_ry_$sysId",
                ).forEach { (labelRes, key) ->
                    RetroSettingSwitch(
                        label = stringResource(labelRes),
                        checked = prefs.getBoolean(key, false),
                        onCheckedChange = { prefs.edit().putBoolean(key, it).apply(); invVer++ },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = TightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.retro_gs_haptic_feedback),
                color = TextPrimary,
                fontSize = ValueSize,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(haptic * 100).toInt()}%",
                color = TextSecondary,
                fontSize = ValueSize,
            )
        }
        androidx.compose.material3.Slider(
            value = haptic,
            onValueChange = { value ->
                haptic = value
                prefs.edit().putFloat("retro_haptic_strength", value).apply()
            },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().height(26.dp),
        )
    }
}

@Composable
private fun RetroAudioSection(state: RetroSettingsState) {
    if (state.system?.isExternal == true) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val prefs = remember(context) { context.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE) }
        var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        @Suppress("UNUSED_EXPRESSION") version
        var vol by remember { mutableStateOf(prefs.getInt("wn.ps2.volume", 100)) }
        RetroSettingGroup {
            RetroGroupTitle(stringResource(R.string.retro_gs_group_audio))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = TightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.retro_gs_volume), color = TextPrimary, fontSize = ValueSize, modifier = Modifier.weight(1f))
                Text("$vol%", color = TextSecondary, fontSize = ValueSize)
            }
            androidx.compose.material3.Slider(
                value = vol.toFloat(),
                onValueChange = { vol = it.toInt() },
                onValueChangeFinished = { prefs.edit().putInt("wn.ps2.volume", vol).apply() },
                valueRange = 0f..200f,
                modifier = Modifier.fillMaxWidth().height(26.dp),
            )
            RetroSettingSwitch(stringResource(R.string.retro_gs_mute), prefs.getBoolean("wn.ps2.muted", false)) {
                prefs.edit().putBoolean("wn.ps2.muted", it).apply(); version++
            }
            RetroSettingSwitch(stringResource(R.string.retro_gs_time_stretch), prefs.getBoolean("wn.ps2.timestretch", true)) {
                prefs.edit().putBoolean("wn.ps2.timestretch", it).apply(); version++
            }
            val bufferValues = listOf(40, 50, 60, 80, 100, 120, 160, 200)
            RetroSettingDropdown(
                stringResource(R.string.retro_gs_audio_buffer),
                bufferValues.map { context.getString(R.string.retro_gs_ms, it) },
                bufferValues.indexOf(prefs.getInt("wn.ps2.audiobuffer", 50)).coerceAtLeast(0),
            ) {
                prefs.edit().putInt("wn.ps2.audiobuffer", bufferValues[it]).apply(); version++
            }
            val latencyValues = listOf(10, 15, 20, 30, 40, 60, 80, 100)
            RetroSettingDropdown(
                stringResource(R.string.retro_gs_audio_latency),
                latencyValues.map { context.getString(R.string.retro_gs_ms, it) },
                latencyValues.indexOf(prefs.getInt("wn.ps2.audiolatency", 20)).coerceAtLeast(0),
            ) {
                prefs.edit().putInt("wn.ps2.audiolatency", latencyValues[it]).apply(); version++
            }
            RetroSettingSwitch(stringResource(R.string.retro_gs_swap_stereo), prefs.getBoolean("wn.ps2.swap", false)) {
                prefs.edit().putBoolean("wn.ps2.swap", it).apply(); version++
            }
        }
        return
    }
    RetroSettingGroup {
        RetroGroupTitle(stringResource(R.string.retro_gs_group_audio))
        if (state.engine3d) {
            RetroSettingNote(stringResource(R.string.retro_gs_engine_3d_audio_note))
        } else {
            RetroSettingSwitch(
                label = stringResource(R.string.retro_gs_sound),
                checked = state.audio,
                onCheckedChange = { state.audio = it },
            )
        }
    }
}
