package com.winlator.cmod.shared.ui.dialog

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.paneNavItem

private val DefaultAccent = Color(0xFFFF6B6B)
private val DefaultCard = Color(0xFF12121B)
private val DefaultTrack = Color(0xFF1F2230)
private val DefaultTextPrimary = Color(0xFFF0F4FF)
private val DefaultTextSecondary = Color(0xFF93A6BC)
private val DefaultBorder = Color.White.copy(alpha = 0.14f)

/**
 * Self-contained popup card. Mode is picked from the params:
 * - Confirm: [confirmLabel] + [onConfirm] + [onCancel] → Cancel/Confirm row (swaps to spinner + [progressLabel] mid-flight).
 * - Progress: [progress] in 0f..1f → linear bar + %; `Float.NaN` / negative → indeterminate bar + [progressLabel] or "Working".
 *
 * [icon] is any Material 3 outline icon (or `null`). Pass [content] to render a custom body between the
 * message and the footer; pass [footer] to override the auto-picked footer entirely (e.g. for dialogs
 * that need bespoke action rows like "Clear Cache" + "OK").
 */
@Composable
fun PopupDialog(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = Icons.Outlined.Warning,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    progressLabel: String? = null,
    progress: Float? = null,
    accentColor: Color = DefaultAccent,
    confirmButtonColor: Color = accentColor,
    cardColor: Color = DefaultCard,
    trackColor: Color = DefaultTrack,
    borderColor: Color = DefaultBorder,
    textPrimaryColor: Color = DefaultTextPrimary,
    textSecondaryColor: Color = DefaultTextSecondary,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    var isConfirming by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cardColor)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = title,
                color = textPrimaryColor,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (!message.isNullOrEmpty()) {
            Text(
                text = message,
                color = textSecondaryColor,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }

        content?.invoke(this)

        when {
            footer != null ->
                footer()

            progress != null ->
                PopupProgressBar(
                    progress = progress,
                    progressLabel = progressLabel,
                    accentColor = accentColor,
                    trackColor = trackColor,
                    textSecondaryColor = textSecondaryColor,
                )

            isConfirming ->
                SpinnerFooter(
                    progressLabel = progressLabel,
                    confirmButtonColor = confirmButtonColor,
                    textPrimaryColor = textPrimaryColor,
                )

            confirmLabel != null && onConfirm != null ->
                ConfirmFooter(
                    confirmLabel = confirmLabel,
                    confirmButtonColor = confirmButtonColor,
                    textSecondaryColor = textSecondaryColor,
                    onCancel = onCancel,
                    onConfirm = {
                        isConfirming = true
                        onConfirm()
                    },
                )
        }
    }
}

/** The popup's progress bar: [progress] in 0f..1f shows a percentage, `Float.NaN` or negative an indeterminate bar. */
@Composable
fun PopupProgressBar(
    progress: Float,
    progressLabel: String?,
    accentColor: Color = DefaultAccent,
    trackColor: Color = DefaultTrack,
    textSecondaryColor: Color = DefaultTextSecondary,
) {
    val barHeight = 5.dp
    val barShape = RoundedCornerShape(3.dp)
    val isIndeterminate = progress.isNaN() || progress < 0f

    if (isIndeterminate) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(barShape),
            color = accentColor,
            trackColor = trackColor,
        )
    } else {
        val smoothed by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            label = "popupProgress",
        )
        LinearProgressIndicator(
            progress = { smoothed },
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(barShape),
            color = accentColor,
            trackColor = trackColor,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
    }

    val rightText = when {
        isIndeterminate -> progressLabel ?: stringResource(R.string.common_ui_working)
        else -> "${(progress * 100).toInt().coerceIn(0, 100)}%"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = rightText,
            color = textSecondaryColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SpinnerFooter(
    progressLabel: String?,
    confirmButtonColor: Color,
    textPrimaryColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = confirmButtonColor,
            strokeWidth = 2.dp,
            modifier = Modifier.size(20.dp),
        )
        if (progressLabel != null) {
            Text(
                text = progressLabel,
                color = textPrimaryColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ConfirmFooter(
    confirmLabel: String,
    confirmButtonColor: Color,
    textSecondaryColor: Color,
    onCancel: (() -> Unit)?,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (onCancel != null) Arrangement.SpaceBetween else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCancel != null) {
            PopupTextAction(
                label = stringResource(R.string.common_ui_cancel),
                textColor = textSecondaryColor,
                onClick = onCancel,
            )
        }
        PopupTextAction(
            label = confirmLabel,
            textColor = confirmButtonColor,
            onClick = onConfirm,
        )
    }
}

@Composable
fun PopupTextAction(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    isEntry: Boolean = false,
) {
    val nav = LocalPaneNav.current
    val clickModifier =
        if (nav != null) {
            Modifier.paneNavItem(cornerRadius = 8.dp, onActivate = onClick, tapToSelect = true, isEntry = isEntry)
        } else {
            Modifier.clickable(onClick = onClick)
        }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(clickModifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
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
