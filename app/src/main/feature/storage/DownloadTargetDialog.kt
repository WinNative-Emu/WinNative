package com.winlator.cmod.feature.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.dialog.PopupTextAction
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem

enum class DownloadTarget {
    INTERNAL,
    EXTERNAL,
}

private val Accent = Color(0xFF1A9FFF)
private val OptionBg = Color(0xFF1C1C2A)
private val OptionBorder = Color(0xFF2A2A3A)
private val IconBoxBg = Color(0xFF242434)
private val TextPrimary = Color(0xFFF0F4FF)
private val TextSecondary = Color(0xFF93A6BC)

@Composable
internal fun DownloadTargetDialog(
    internalPath: String,
    externalPath: String?,
    externalLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (DownloadTarget) -> Unit,
) {
    val nav = remember { PaneNavRegistry() }
    var selected by remember { mutableStateOf(DownloadTarget.INTERNAL) }
    val externalAvailable = !externalPath.isNullOrBlank()
    val noExternalText = stringResource(R.string.external_storage_download_target_none)

    Dialog(onDismissRequest = onDismiss) {
        DialogPaneNav(nav, onDismiss = onDismiss)
        CompositionLocalProvider(LocalPaneNav provides nav) {
            PopupDialog(
                title = stringResource(R.string.external_storage_download_target_title),
                message = stringResource(R.string.external_storage_download_target_message),
                icon = Icons.Outlined.Storage,
                accentColor = Accent,
                modifier = Modifier.widthIn(min = 280.dp, max = 380.dp),
                content = {
                    TargetOption(
                        icon = Icons.Outlined.PhoneAndroid,
                        title = stringResource(R.string.external_storage_download_target_internal),
                        subtitle = internalPath,
                        selected = selected == DownloadTarget.INTERNAL,
                        enabled = true,
                        isEntry = true,
                        onClick = { selected = DownloadTarget.INTERNAL },
                    )
                    Spacer(Modifier.height(2.dp))
                    TargetOption(
                        icon = Icons.Outlined.Storage,
                        title = stringResource(R.string.external_storage_download_target_external),
                        subtitle =
                            if (externalAvailable) {
                                listOf(externalLabel, externalPath.orEmpty())
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                            } else {
                                noExternalText
                            },
                        selected = externalAvailable && selected == DownloadTarget.EXTERNAL,
                        enabled = externalAvailable,
                        isEntry = false,
                        onClick = { if (externalAvailable) selected = DownloadTarget.EXTERNAL },
                    )
                },
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PopupTextAction(
                            label = stringResource(R.string.common_ui_cancel),
                            textColor = TextSecondary,
                            onClick = onDismiss,
                        )
                        PopupTextAction(
                            label = stringResource(R.string.common_ui_confirm),
                            textColor = Accent,
                            onClick = {
                                onConfirm(
                                    if (externalAvailable) selected else DownloadTarget.INTERNAL,
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun TargetOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    isEntry: Boolean,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    val borderColor = if (selected) Accent.copy(alpha = 0.6f) else OptionBorder

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(OptionBg)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .paneNavItem(
                    cornerRadius = 10.dp,
                    onActivate = onClick,
                    tapToSelect = true,
                    isEntry = isEntry,
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(IconBoxBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Accent.copy(alpha = contentAlpha),
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary.copy(alpha = contentAlpha),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = TextSecondary.copy(alpha = contentAlpha),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier =
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .border(
                        1.dp,
                        if (selected) Accent else TextSecondary.copy(alpha = 0.4f),
                        CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier =
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Accent),
                )
            }
        }
    }
}
