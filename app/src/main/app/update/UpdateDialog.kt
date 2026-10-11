package com.winlator.cmod.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SystemUpdate
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.WinNativeAccent
import com.winlator.cmod.shared.theme.WinNativeDanger
import com.winlator.cmod.shared.theme.WinNativeOutline
import com.winlator.cmod.shared.theme.WinNativePanel
import com.winlator.cmod.shared.theme.WinNativeSurface
import com.winlator.cmod.shared.theme.WinNativeTextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary
import java.util.Locale

private val SectionAccents =
    listOf(
        Color(0xFF4FC3F7),
        Color(0xFF9CCC65),
        Color(0xFFFFB74D),
        Color(0xFFBA68C8),
        Color(0xFF4DD0E1),
    )

@Composable
fun UpdateAvailableDialog(
    release: UpdateRelease,
    stage: UpdateService.Stage,
    error: String?,
    onClose: (Boolean) -> Unit,
    onUpdate: () -> Unit,
) {
    var ignoreUntilNext by remember { mutableStateOf(false) }
    val busy = stage !is UpdateService.Stage.Idle && stage !is UpdateService.Stage.Checking

    Dialog(
        onDismissRequest = { if (!busy) onClose(ignoreUntilNext) },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !busy,
                dismissOnClickOutside = false,
            ),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .clip(RoundedCornerShape(16.dp))
                        .background(WinNativeSurface)
                        .border(1.dp, WinNativeOutline, RoundedCornerShape(16.dp)),
            ) {
                UpdateDialogHeader(release)

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(WinNativeOutline),
                )

                UpdateNotesList(
                    release = release,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(WinNativeOutline),
                )

                UpdateDialogFooter(
                    stage = stage,
                    error = error,
                    busy = busy,
                    ignoreUntilNext = ignoreUntilNext,
                    onIgnoreChanged = { ignoreUntilNext = it },
                    onClose = { onClose(ignoreUntilNext) },
                    onUpdate = onUpdate,
                )
            }
        }
    }
}

@Composable
private fun UpdateDialogHeader(release: UpdateRelease) {
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(WinNativePanel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = WinNativeAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_available_title),
                    color = WinNativeTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = release.name,
                    color = WinNativeTextSecondary,
                    fontSize = 11.sp,
                )
            }
            UpdateChannelBadge(release)
        }

        val meta = buildMetaLine(release)
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            Text(text = meta, color = WinNativeTextSecondary, fontSize = 11.sp)
        }

        Spacer(Modifier.height(9.dp))
        Text(
            text = "${UpdateService.installedVersionName()}  →  ${release.displayVersion}",
            color = WinNativeAccent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun UpdateChannelBadge(release: UpdateRelease) {
    val pullRequest = release.channel == UpdateChannel.PULL_REQUEST
    val tint = if (pullRequest) Color(0xFFFFB74D) else WinNativeAccent
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(tint.copy(alpha = 0.14f))
                .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = if (pullRequest) release.tag.uppercase() else "OFFICIAL",
            color = tint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun UpdateNotesList(
    release: UpdateRelease,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (release.sections.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.update_no_notes),
                    color = WinNativeTextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        release.sections.forEachIndexed { index, section ->
            item(key = "section-$index") {
                UpdateNotesSection(section, SectionAccents[index % SectionAccents.size])
            }
        }
        item(key = "apk-note") {
            run {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(WinNativePanel)
                            .border(1.dp, WinNativeOutline, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = stringResource(R.string.update_apk_notice),
                        color = WinNativeTextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateNotesSection(
    section: UpdateNoteSection,
    accent: Color,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(width = 3.dp, height = 13.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = section.title.uppercase(Locale.US),
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = section.entries.size.toString(),
                color = WinNativeTextSecondary,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            section.entries.forEach { entry ->
                UpdateNoteRow(entry, accent)
            }
        }
    }
}

@Composable
private fun UpdateNoteRow(
    entry: UpdateNoteEntry,
    accent: Color,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier =
                Modifier
                    .padding(top = 6.dp)
                    .size(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = 0.65f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.text,
                color = WinNativeTextPrimary.copy(alpha = 0.9f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            val credit = buildCredit(entry)
            if (credit.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(text = credit, color = WinNativeTextSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun UpdateDialogFooter(
    stage: UpdateService.Stage,
    error: String?,
    busy: Boolean,
    ignoreUntilNext: Boolean,
    onIgnoreChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    onUpdate: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        if (error != null) {
            Text(text = error, color = WinNativeDanger, fontSize = 11.sp, lineHeight = 15.sp)
            Spacer(Modifier.height(10.dp))
        }

        when (stage) {
            is UpdateService.Stage.Downloading -> {
                UpdateProgressRow(
                    label = "Downloading  ${formatBytes(stage.bytes)} / ${formatBytes(stage.total)}",
                    fraction = stage.fraction,
                )
                Spacer(Modifier.height(12.dp))
            }
            is UpdateService.Stage.Working -> {
                UpdateProgressRow(label = stage.label, fraction = null)
                Spacer(Modifier.height(12.dp))
            }
            else -> Unit
        }

        if (!busy) {
            IgnoreUntilNextRow(checked = ignoreUntilNext, onCheckedChange = onIgnoreChanged)
            Spacer(Modifier.height(13.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                UpdateButton(
                    label = stringResource(R.string.common_ui_close),
                    textColor = WinNativeTextSecondary,
                    onClick = onClose,
                )
                Spacer(Modifier.width(10.dp))
                UpdateButton(
                    label = stringResource(R.string.update_action_update),
                    textColor = WinNativeTextPrimary,
                    backgroundColor = WinNativeAccent,
                    borderColor = WinNativeAccent,
                    onClick = onUpdate,
                )
            }
        }
    }
}

@Composable
private fun UpdateProgressRow(
    label: String,
    fraction: Float?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = WinNativeTextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(7.dp))
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                color = WinNativeAccent,
                trackColor = WinNativePanel,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                color = WinNativeAccent,
                trackColor = WinNativePanel,
                gapSize = 0.dp,
            )
        }
    }
}

@Composable
private fun IgnoreUntilNextRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onCheckedChange(!checked) },
                ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(17.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (checked) WinNativeAccent else Color.Transparent)
                    .border(
                        1.dp,
                        if (checked) WinNativeAccent else WinNativeOutline,
                        RoundedCornerShape(5.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = WinNativeTextPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.update_ignore_until_next),
            color = WinNativeTextSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun UpdateButton(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    backgroundColor: Color = WinNativePanel,
    borderColor: Color = WinNativeOutline,
) {
    Box(
        modifier =
            Modifier
                .widthIn(min = 88.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(backgroundColor)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun buildMetaLine(release: UpdateRelease): String {
    val parts = mutableListOf<String>()
    parts += release.tag
    release.displayDate.takeIf { it.isNotBlank() }?.let { parts += it }
    if (release.apkSize > 0) parts += formatBytes(release.apkSize)
    return parts.joinToString("  ·  ")
}

private fun buildCredit(entry: UpdateNoteEntry): String {
    val parts = mutableListOf<String>()
    if (entry.pullRequest > 0) parts += "#${entry.pullRequest}"
    if (entry.author.isNotBlank()) parts += "@${entry.author}"
    return parts.joinToString("  ·  ")
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) {
        "${bytes} ${units[unit]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
