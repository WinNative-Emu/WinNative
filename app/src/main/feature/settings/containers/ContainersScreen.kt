package com.winlator.cmod.feature.settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.dialog.PopupTextAction
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import androidx.compose.runtime.CompositionLocalProvider
import java.util.Locale

private val ContainersBg = Color(0xFF11111C)
private val ContainersCard = Color(0xFF1C1C2A)
private val ContainersSubcard = Color(0xFF161622)
private val ContainersOutline = Color(0xFF2A2A3A)
private val ContainersIconBox = Color(0xFF242434)
private val ContainersAccent = Color(0xFF1A9FFF)
private val ContainersNavHighlight = Color(0xFF4FC3F7)
private val ContainersTextPrimary = Color(0xFFF0F4FF)
private val ContainersTextSecondary = Color(0xFF7A8FA8)
private val ContainersDanger = Color(0xFFFF7A88)

// Card shape knob: width : height. Lower = squarer (1f = perfect square), higher = wider/shorter.
private const val ContainerCardAspect = 1.2f

data class ContainersScreenState(
    val containers: List<Container> = emptyList(),
    /** The GameScope container, once it has been created; Linux programs and Steam boot into it. */
    val gamescope: Container? = null,
    val dialog: ContainersDialogUiState = ContainersDialogUiState.None,
)

sealed interface ContainersDialogUiState {
    data object None : ContainersDialogUiState

    data class ConfirmDuplicate(
        val container: Container,
    ) : ContainersDialogUiState

    data class ComponentInstaller(
        val container: Container,
    ) : ContainersDialogUiState

    data class ConfirmRemove(
        val container: Container,
    ) : ContainersDialogUiState

    data class StorageInfo(
        val data: ContainerStorageInfoUiState,
    ) : ContainersDialogUiState

    data class Message(
        val title: String,
        val message: String,
    ) : ContainersDialogUiState
}

data class ContainerStorageInfoUiState(
    val container: Container,
    val driveCBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val usedPercent: Float = 0f,
    val isLoading: Boolean = true,
)

@Composable
fun ContainersScreen(
    state: ContainersScreenState,
    onAddContainer: () -> Unit,
    onAddGamescope: () -> Unit,
    onRunContainer: (Container) -> Unit,
    onEditContainer: (Container) -> Unit,
    onDuplicateContainer: (Container) -> Unit,
    onInstallComponents: (Container) -> Unit,
    onRemoveContainer: (Container) -> Unit,
    onShowInfo: (Container) -> Unit,
    onDismissDialog: () -> Unit,
    onConfirmDuplicateDialog: (Container) -> Unit,
    onConfirmRemoveDialog: (Container) -> Unit,
    onClearCacheDialog: (Container) -> Unit,
    bridge: SettingsNavBridge? = null,
) {
    val layoutDirection = LocalLayoutDirection.current
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBarStartPadding = navBarPadding.calculateStartPadding(layoutDirection)
    val navBarEndPadding = navBarPadding.calculateEndPadding(layoutDirection)
    val navBarBottomPadding = navBarPadding.calculateBottomPadding()
    val contentNav = rememberSettingsContentNav(bridge)

    CompositionLocalProvider(LocalPaneNav provides contentNav) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(ContainersBg)
                    .padding(
                        start = 16.dp + navBarStartPadding,
                        top = 16.dp,
                        end = 16.dp + navBarEndPadding,
                    ),
        ) {
            SectionLabel(text = stringResource(R.string.common_ui_containers))
            Spacer(Modifier.height(6.dp))

            val cardSpacing = 8.dp
            val minCardWidth = 140.dp
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns =
                    maxOf(1, ((maxWidth + cardSpacing) / (minCardWidth + cardSpacing)).toInt())
                val totalCells = state.containers.size + 1
                val rowCount = (totalCells + columns - 1) / columns

                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(cardSpacing),
                ) {
                    for (rowIndex in 0 until rowCount) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(cardSpacing),
                        ) {
                            for (colIndex in 0 until columns) {
                                val cellIndex = rowIndex * columns + colIndex
                                val gxBase = colIndex * 2
                                val gyBase = rowIndex * 2
                                Box(modifier = Modifier.weight(1f)) {
                                    when {
                                        cellIndex == 0 ->
                                            AddContainerCard(
                                                label = stringResource(R.string.containers_list_new),
                                                onClick = onAddContainer,
                                                navRow = gyBase,
                                                navCol = gxBase,
                                            )
                                        cellIndex <= state.containers.size -> {
                                            val container = state.containers[cellIndex - 1]
                                            key(container.id) {
                                                ContainerCard(
                                                    container = container,
                                                    navRow = gyBase,
                                                    navCol = gxBase,
                                                    onRun = { onRunContainer(container) },
                                                    onEdit = { onEditContainer(container) },
                                                    onDuplicate = { onDuplicateContainer(container) },
                                                    onInstallComponents = { onInstallComponents(container) },
                                                    onRemove = { onRemoveContainer(container) },
                                                    onShowInfo = { onShowInfo(container) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    SectionLabel(text = stringResource(R.string.containers_gamescope_section))
                    Text(
                        text = stringResource(R.string.containers_gamescope_help),
                        color = ContainersTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                    )
                    val gamescopeNavRow = rowCount * 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(cardSpacing),
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            val gamescope = state.gamescope
                            if (gamescope == null) {
                                AddContainerCard(
                                    label = stringResource(R.string.containers_gamescope_new),
                                    onClick = onAddGamescope,
                                    navRow = gamescopeNavRow,
                                    navCol = 0,
                                )
                            } else {
                                key(gamescope.id) {
                                    ContainerCard(
                                        container = gamescope,
                                        navRow = gamescopeNavRow,
                                        navCol = 0,
                                        onRun = { onRunContainer(gamescope) },
                                        onEdit = { onEditContainer(gamescope) },
                                        onDuplicate = null,
                                        onInstallComponents = null,
                                        onRemove = { onRemoveContainer(gamescope) },
                                        onShowInfo = { onShowInfo(gamescope) },
                                    )
                                }
                            }
                        }
                        for (i in 1 until columns) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp + navBarBottomPadding))
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        ContainersDialogUiState.None -> {
            Unit
        }

        is ContainersDialogUiState.ConfirmDuplicate -> {
            val nav = remember { PaneNavRegistry() }
            Dialog(
                onDismissRequest = onDismissDialog,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                DialogPaneNav(nav, onDismiss = onDismissDialog)
                CompositionLocalProvider(LocalPaneNav provides nav) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        PopupDialog(
                            title = stringResource(R.string.containers_list_duplicate_title),
                            message = stringResource(R.string.containers_list_confirm_duplicate),
                            accentColor = ContainersAccent,
                            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                            footer = {
                                ConfirmDialogFooter(
                                    confirmLabel = stringResource(R.string.common_ui_duplicate),
                                    confirmColor = ContainersAccent,
                                    onConfirm = { onConfirmDuplicateDialog(dialog.container) },
                                    onCancel = onDismissDialog,
                                )
                            },
                        )
                    }
                }
            }
        }

        is ContainersDialogUiState.ConfirmRemove -> {
            val nav = remember { PaneNavRegistry() }
            Dialog(
                onDismissRequest = onDismissDialog,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                DialogPaneNav(nav, onDismiss = onDismissDialog)
                CompositionLocalProvider(LocalPaneNav provides nav) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        PopupDialog(
                            title = stringResource(R.string.containers_list_remove_title),
                            message = stringResource(R.string.containers_list_confirm_remove),
                            accentColor = ContainersDanger,
                            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                            footer = {
                                ConfirmDialogFooter(
                                    confirmLabel = stringResource(R.string.common_ui_remove),
                                    confirmColor = ContainersDanger,
                                    onConfirm = { onConfirmRemoveDialog(dialog.container) },
                                    onCancel = onDismissDialog,
                                )
                            },
                        )
                    }
                }
            }
        }

        is ContainersDialogUiState.StorageInfo -> {
            ContainerStorageInfoDialog(
                state = dialog.data,
                onDismiss = onDismissDialog,
                onClearCache = { onClearCacheDialog(dialog.data.container) },
            )
        }

        is ContainersDialogUiState.ComponentInstaller -> {
            ComponentInstallerSheet(
                container = dialog.container,
                onDismiss = onDismissDialog,
            )
        }

        is ContainersDialogUiState.Message -> {
            ContainersMessageDialog(
                title = dialog.title,
                message = dialog.message,
                onDismiss = onDismissDialog,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = ContainersTextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun AddContainerCard(
    label: String,
    onClick: () -> Unit,
    navRow: Int,
    navCol: Int,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(ContainerCardAspect)
                .clip(RoundedCornerShape(12.dp))
                .background(ContainersCard)
                .border(1.dp, ContainersOutline, RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(ContainersIconBox)
                    .paneNavItem(
                        cornerRadius = 28.dp,
                        onActivate = onClick,
                        highlightColor = ContainersNavHighlight,
                        tapToSelect = true,
                        isEntry = true,
                        navRow = navRow,
                        navCol = navCol,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = ContainersAccent,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = ContainersTextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ContainerCard(
    container: Container,
    navRow: Int,
    navCol: Int,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: (() -> Unit)?,
    onInstallComponents: (() -> Unit)?,
    onRemove: () -> Unit,
    onShowInfo: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val nameFontSize =
        when {
            container.name.length > 44 -> 9.sp
            container.name.length > 34 -> 10.sp
            container.name.length > 26 -> 11.sp
            else -> 13.sp
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(ContainerCardAspect)
                .clip(RoundedCornerShape(12.dp))
                .background(ContainersCard)
                .border(1.dp, ContainersOutline, RoundedCornerShape(12.dp))
                .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBox(
                image = Icons.Outlined.Inbox,
                tint = ContainersTextSecondary,
            )
            Spacer(Modifier.weight(1f))
            if (onInstallComponents != null) {
                SmallVectorIconButton(
                    image = ComponentContainerIcon,
                    contentDescription = "Install components",
                    tint = ContainersAccent,
                    onClick = onInstallComponents,
                    navRow = navRow,
                    navCol = navCol,
                )
                Spacer(Modifier.width(8.dp))
            }
            Box {
                SmallVectorIconButton(
                    image = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.common_ui_edit),
                    tint = ContainersTextSecondary,
                    onClick = { menuExpanded = true },
                    navRow = navRow,
                    navCol = navCol + 1,
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = ContainersCard,
                ) {
                    if (onDuplicate != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_ui_duplicate), color = ContainersTextPrimary) },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.container_config_storage_info), color = ContainersTextPrimary) },
                        onClick = {
                            menuExpanded = false
                            onShowInfo()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_ui_remove), color = ContainersDanger) },
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = container.name,
                color = ContainersTextPrimary,
                fontSize = nameFontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                modifier = Modifier.weight(1f),
                image = Icons.Outlined.PlayArrow,
                contentDescription = stringResource(R.string.common_ui_run),
                tint = ContainersAccent,
                onClick = onRun,
                navRow = navRow + 1,
                navCol = navCol,
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                image = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.common_ui_edit),
                tint = ContainersAccent,
                onClick = onEdit,
                navRow = navRow + 1,
                navCol = navCol + 1,
            )
        }
    }
}

@Composable
private fun IconBox(
    image: ImageVector,
    tint: Color,
) {
    Box(
        modifier =
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ContainersIconBox),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ContainersDialogShell(
    onDismiss: () -> Unit,
    title: String? = null,
    iconImage: ImageVector? = null,
    maxWidth: androidx.compose.ui.unit.Dp = 460.dp,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .widthIn(max = maxWidth)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ContainersCard)
                        .border(1.dp, ContainersOutline, RoundedCornerShape(16.dp))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                ) {
                    if (title != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (iconImage != null) {
                                Icon(
                                    imageVector = iconImage,
                                    contentDescription = null,
                                    tint = ContainersTextPrimary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                text = title,
                                color = ContainersTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(ContainersOutline),
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun ContainersDialogButton(
    label: String,
    primary: Boolean,
    textColor: Color,
    backgroundColor: Color? = null,
    borderColor: Color? = null,
    onClick: () -> Unit,
) {
    val resolvedBackground = backgroundColor ?: if (primary) ContainersAccent else ContainersSubcard
    val resolvedBorder = borderColor ?: if (primary) ContainersAccent.copy(alpha = 0.5f) else ContainersOutline

    Box(
        modifier =
            Modifier
                .widthIn(min = 84.dp)
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(resolvedBackground)
                .border(
                    1.dp,
                    resolvedBorder,
                    RoundedCornerShape(10.dp),
                ).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ConfirmDialogFooter(
    confirmLabel: String,
    confirmColor: Color,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConfirmFooterAction(
            label = stringResource(R.string.common_ui_cancel),
            textColor = ContainersTextSecondary,
            onClick = onCancel,
            isEntry = true,
        )
        ConfirmFooterAction(
            label = confirmLabel,
            textColor = confirmColor,
            onClick = onConfirm,
        )
    }
}

@Composable
private fun ConfirmFooterAction(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    isEntry: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    highlightColor = ContainersNavHighlight,
                    tapToSelect = true,
                    isEntry = isEntry,
                ).padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ContainersMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    ContainersDialogShell(
        onDismiss = onDismiss,
        title = title,
        maxWidth = 420.dp,
    ) {
        Text(
            text = message,
            color = ContainersTextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ContainersOutline),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            ContainersDialogButton(
                label = stringResource(R.string.common_ui_ok),
                primary = false,
                textColor = ContainersAccent,
                backgroundColor = ContainersAccent.copy(alpha = 0.12f),
                borderColor = ContainersAccent.copy(alpha = 0.3f),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun ContainerStorageInfoDialog(
    state: ContainerStorageInfoUiState,
    onDismiss: () -> Unit,
    onClearCache: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            PopupDialog(
                title = stringResource(R.string.container_config_storage_info),
                icon = Icons.Outlined.Info,
                accentColor = ContainersAccent,
                modifier = Modifier.widthIn(min = 320.dp, max = 500.dp),
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            StorageMetric(
                                label = stringResource(R.string.container_config_drive_c),
                                value = formatBytes(state.driveCBytes),
                            )
                            StorageMetric(
                                label = stringResource(R.string.container_config_cache),
                                value = formatBytes(state.cacheBytes),
                            )
                            StorageMetric(
                                label = stringResource(R.string.container_config_total),
                                value = formatBytes(state.totalBytes),
                            )
                        }
                        Column(
                            modifier = Modifier.widthIn(min = 180.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { state.usedPercent.coerceIn(0f, 100f) / 100f },
                                    modifier = Modifier.size(132.dp),
                                    color = ContainersAccent.copy(alpha = 0.38f),
                                    trackColor = ContainersAccent.copy(alpha = 0.12f),
                                    strokeWidth = 18.dp,
                                )
                                Text(
                                    text = formatUsedPercent(state.usedPercent),
                                    color = ContainersTextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.container_config_estimated_used_space),
                                color = ContainersTextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                },
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        PopupTextAction(
                            label = stringResource(R.string.container_config_clear_cache),
                            textColor = ContainersTextSecondary,
                            onClick = onClearCache,
                        )
                        PopupTextAction(
                            label = stringResource(R.string.common_ui_ok),
                            textColor = ContainersAccent,
                            onClick = onDismiss,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun StorageMetric(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            color = ContainersTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = ContainersAccent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatBytes(bytes: Long): String =
    com.winlator.cmod.shared.util.StringUtils
        .formatBytes(bytes)

private fun formatUsedPercent(percent: Float): String {
    val clamped = percent.coerceIn(0f, 100f)
    return when {
        clamped > 0f && clamped < 1f -> "<1%"
        clamped < 10f -> String.format(Locale.US, "%.1f%%", clamped)
        else -> "${clamped.toInt()}%"
    }
}

@Composable
private fun SmallVectorIconButton(
    image: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    navRow: Int? = null,
    navCol: Int? = null,
) {
    Box(
        modifier =
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ContainersSubcard)
                .border(1.dp, ContainersOutline, RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    highlightColor = ContainersNavHighlight,
                    tapToSelect = true,
                    navRow = navRow,
                    navCol = navCol,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    image: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    navRow: Int? = null,
    navCol: Int? = null,
) {
    Box(
        modifier =
            modifier
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(ContainersSubcard)
                .border(1.dp, ContainersOutline, RoundedCornerShape(9.dp))
                .paneNavItem(
                    cornerRadius = 9.dp,
                    onActivate = onClick,
                    highlightColor = ContainersNavHighlight,
                    tapToSelect = true,
                    navRow = navRow,
                    navCol = navCol,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}
