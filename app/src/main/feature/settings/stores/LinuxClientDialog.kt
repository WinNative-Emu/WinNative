package com.winlator.cmod.feature.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.cmod.R
import com.winlator.cmod.runtime.linux.LinuxClientInstaller
import com.winlator.cmod.runtime.linux.LinuxClientInstaller.Stage
import com.winlator.cmod.runtime.linux.LinuxClientInstaller.State
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.dialog.PopupProgressBar
import com.winlator.cmod.shared.ui.dialog.PopupTextAction
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry

private val InstallBlue = Color(0xFF1A9FFF)
private val InstalledGreen = Color(0xFF3FB950)
private val DialogTextPrimary = Color(0xFFF0F4FF)
private val DialogTextSecondary = Color(0xFF93A6BC)

/**
 * The Linux Steam Client's install window. It only reflects [state]; the install itself runs in
 * [LinuxClientInstaller] and carries on when the window is closed, so reopening it shows where
 * the install has got to.
 */
@Composable
internal fun LinuxClientDialog(
    state: State,
    onStart: () -> Unit,
    onCancelInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val nav = remember { PaneNavRegistry() }
    val installed = state is State.Installed
    val accent = if (installed) InstalledGreen else InstallBlue

    Dialog(onDismissRequest = onDismiss) {
        DialogPaneNav(nav, onDismiss = onDismiss)
        CompositionLocalProvider(LocalPaneNav provides nav) {
            PopupDialog(
                title = stringResource(R.string.linux_client_title),
                message = linuxClientMessage(state),
                icon = if (installed) Icons.Outlined.CheckCircle else Icons.Outlined.ArrowDownward,
                accentColor = accent,
                modifier = Modifier.widthIn(min = 300.dp, max = 420.dp),
                content = {
                    if (state is State.Working) WorkingBody(state)
                },
                footer = {
                    LinuxClientFooter(
                        state = state,
                        accent = accent,
                        onStart = onStart,
                        onCancelInstall = onCancelInstall,
                        onDismiss = onDismiss,
                    )
                },
            )
        }
    }
}

@Composable
internal fun linuxClientMessage(state: State): String? {
    val context = LocalContext.current
    return when (state) {
        State.Checking, State.Missing -> stringResource(R.string.linux_client_install_message)
        State.Installed -> stringResource(R.string.linux_client_installed_message)
        is State.UpdateAvailable -> stringResource(R.string.linux_client_update_message)
        State.Failed -> stringResource(R.string.linux_client_failed_message)
        is State.NoSpace ->
            stringResource(
                R.string.linux_client_no_space_message,
                Formatter.formatShortFileSize(context, state.needed),
                Formatter.formatShortFileSize(context, state.available),
            )
        is State.Blocked -> stringResource(state.message)
        is State.Working -> null
    }
}

@StringRes
internal fun linuxClientStageLabel(stage: Stage): Int =
    when (stage) {
        Stage.CONNECT -> R.string.linux_client_stage_connect
        Stage.DOWNLOAD_RUNTIME -> R.string.linux_client_stage_download_runtime
        Stage.INSTALL_RUNTIME -> R.string.linux_client_stage_install_runtime
        Stage.DOWNLOAD_PROTON -> R.string.linux_client_stage_download_proton
        Stage.INSTALL_PROTON -> R.string.linux_client_stage_install_proton
        Stage.DOWNLOAD_STEAM -> R.string.linux_client_stage_download_steam
        Stage.INSTALL_STEAM -> R.string.linux_client_stage_install_steam
        Stage.LIBRARY -> R.string.linux_client_stage_library
    }

@Composable
private fun WorkingBody(state: State.Working) {
    val context = LocalContext.current
    val stageLabel = stringResource(linuxClientStageLabel(state.stage))
    val known = state.total > 0
    val downloading =
        state.stage == Stage.DOWNLOAD_RUNTIME || state.stage == Stage.DOWNLOAD_PROTON || state.stage == Stage.DOWNLOAD_STEAM
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stageLabel,
            color = DialogTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        PopupProgressBar(
            progress = if (known) state.done.toFloat() / state.total else Float.NaN,
            progressLabel = stageLabel,
            accentColor = InstallBlue,
            textSecondaryColor = DialogTextSecondary,
        )
        if (known && downloading) {
            Text(
                text =
                    stringResource(
                        R.string.linux_client_progress_amount,
                        Formatter.formatShortFileSize(context, state.done),
                        Formatter.formatShortFileSize(context, state.total),
                    ),
                color = DialogTextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun LinuxClientFooter(
    state: State,
    accent: Color,
    onStart: () -> Unit,
    onCancelInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The last step writes the container in one go and cannot be stopped part way.
    val single = state is State.Installed || (state is State.Working && state.stage == Stage.LIBRARY)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (single) Arrangement.End else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            State.Installed ->
                PopupTextAction(stringResource(R.string.common_ui_ok), accent, onDismiss, isEntry = true)
            is State.Working -> {
                if (!single) {
                    PopupTextAction(stringResource(R.string.common_ui_cancel), DialogTextSecondary, onCancelInstall)
                }
                PopupTextAction(stringResource(R.string.common_ui_ok), accent, onDismiss, isEntry = true)
            }
            State.Failed, is State.NoSpace, is State.Blocked -> {
                PopupTextAction(stringResource(R.string.common_ui_cancel), DialogTextSecondary, onDismiss)
                PopupTextAction(stringResource(R.string.linux_client_retry), accent, onStart, isEntry = true)
            }
            State.Checking, State.Missing -> {
                PopupTextAction(stringResource(R.string.common_ui_cancel), DialogTextSecondary, onDismiss)
                PopupTextAction(stringResource(R.string.common_ui_download), accent, onStart, isEntry = true)
            }
            is State.UpdateAvailable -> {
                PopupTextAction(stringResource(R.string.common_ui_cancel), DialogTextSecondary, onDismiss)
                PopupTextAction(stringResource(R.string.update_action_update), accent, onStart, isEntry = true)
            }
        }
    }
}
