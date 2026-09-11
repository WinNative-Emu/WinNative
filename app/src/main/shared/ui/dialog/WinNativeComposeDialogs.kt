package com.winlator.cmod.shared.ui.dialog
import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialog
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.WinNativeAccent
import com.winlator.cmod.shared.theme.WinNativeBackground
import com.winlator.cmod.shared.theme.WinNativeDanger
import com.winlator.cmod.shared.theme.WinNativeOutline
import com.winlator.cmod.shared.theme.WinNativePanel
import com.winlator.cmod.shared.theme.WinNativeSurface
import com.winlator.cmod.shared.theme.WinNativeTextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.util.Callback
import androidx.compose.ui.window.Dialog as ComposeDialog

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.window.DialogProperties as ComposeDialogProperties

object WinNativeComposeDialogs {
    @JvmStatic
    fun showLoading(
        context: Context,
        message: String,
    ): AppCompatDialog? {
        val activity = context.findActivity() ?: return null
        val dialog = AppCompatDialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        dialog.setContentView(
            composeView(activity) {
                WinNativeTheme {
                    WinNativeLoadingDialog(message = message)
                }
            },
        )
        dialog.show()
        return dialog
    }

    @JvmStatic
    fun showAlert(
        context: Context,
        message: CharSequence?,
        onConfirm: Runnable?,
    ): Boolean {
        val activity = context.findActivity() ?: return false
        val dialog = buildDialog(activity)
        dialog.setContentView(
            composeView(activity) {
                WinNativeTheme {
                    WinNativeMessageDialog(
                        title = null,
                        message = message?.toString().orEmpty(),
                        confirmLabel = activity.getString(R.string.common_ui_ok),
                        confirmColor = WinNativeAccent,
                        showCancel = false,
                        onDismiss = { dialog.dismiss() },
                        onConfirm = {
                            dialog.dismiss()
                            onConfirm?.run()
                        },
                    )
                }
            },
        )
        dialog.show()
        return true
    }

    @JvmStatic
    fun showConfirm(
        context: Context,
        message: CharSequence?,
        onConfirm: Runnable?,
    ): Boolean {
        val activity = context.findActivity() ?: return false
        val dialog = buildDialog(activity)
        dialog.setContentView(
            composeView(activity) {
                WinNativeTheme {
                    WinNativeMessageDialog(
                        title = null,
                        message = message?.toString().orEmpty(),
                        confirmLabel = activity.getString(R.string.common_ui_ok),
                        confirmColor = WinNativeAccent,
                        showCancel = true,
                        onDismiss = { dialog.dismiss() },
                        onConfirm = {
                            dialog.dismiss()
                            onConfirm?.run()
                        },
                    )
                }
            },
        )
        dialog.show()
        return true
    }

    @JvmStatic
    fun showSteamSessionBlocked(
        context: Context,
        title: CharSequence?,
        message: CharSequence?,
        confirmLabel: CharSequence?,
        onConfirm: Runnable?,
        onCancel: Runnable?,
    ): Boolean {
        val activity = context.findActivity() ?: return false
        val dialog = buildDialog(activity)
        var answered = false
        dialog.setCancelable(false)
        dialog.setContentView(
            composeView(activity) {
                WinNativeTheme {
                    WinNativeMessageDialog(
                        title = title?.toString(),
                        message = message?.toString().orEmpty(),
                        confirmLabel = confirmLabel?.toString()
                            ?: activity.getString(R.string.common_ui_ok),
                        confirmColor = WinNativeAccent,
                        showCancel = true,
                        onDismiss = {
                            if (!answered) {
                                answered = true
                                dialog.dismiss()
                                onCancel?.run()
                            }
                        },
                        onConfirm = {
                            if (!answered) {
                                answered = true
                                dialog.dismiss()
                                onConfirm?.run()
                            }
                        },
                    )
                }
            },
        )
        dialog.show()
        return true
    }

    @JvmStatic
    fun showPrompt(
        context: Context,
        title: CharSequence?,
        defaultText: String?,
        callback: Callback<String>,
    ): Boolean {
        val activity = context.findActivity() ?: return false
        val dialog = buildDialog(activity)
        if (dialog.window != null) {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,
            )
        }
        dialog.setContentView(
            composeView(activity) {
                WinNativeTheme {
                    WinNativePromptDialog(
                        title = title?.toString().orEmpty(),
                        initialValue = defaultText.orEmpty(),
                        onDismiss = { dialog.dismiss() },
                        onConfirm = { value ->
                            dialog.dismiss()
                            callback.call(value)
                        },
                    )
                }
            },
        )
        dialog.show()
        return true
    }

    @JvmStatic
    fun showShortcutProperties(
        context: Context,
        playCountText: String,
        playtimeText: String,
        onReset: Runnable,
    ): Boolean {
        val activity = context.findActivity() ?: return false
        val dialog = buildDialog(activity)
        dialog.setContentView(
            composeView(activity) {
                WinNativeTheme {
                    WinNativeShortcutPropertiesDialog(
                        title = activity.getString(R.string.common_ui_properties),
                        playCountText = playCountText,
                        playtimeText = playtimeText,
                        onDismiss = { dialog.dismiss() },
                        onReset = {
                            dialog.dismiss()
                            onReset.run()
                        },
                    )
                }
            },
        )
        dialog.show()
        return true
    }

    private fun buildDialog(activity: Activity): AppCompatDialog =
        AppCompatDialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }

    private fun composeView(
        activity: Activity,
        content: @Composable () -> Unit,
    ): ComposeView =
        ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
}

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
fun WinNativeDialogShell(
    onDismiss: () -> Unit,
    title: String? = null,
    iconRes: Int? = null,
    iconImage: ImageVector? = null,
    maxWidth: Dp = 420.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable () -> Unit,
) {
    ComposeDialog(
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
                        .background(WinNativeSurface)
                        .border(1.dp, WinNativeOutline, RoundedCornerShape(16.dp))
                        .padding(contentPadding),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                ) {
                    if (!title.isNullOrEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (iconImage != null) {
                                Icon(
                                    imageVector = iconImage,
                                    contentDescription = null,
                                    tint = WinNativeTextPrimary,
                                    modifier = Modifier.size(22.dp),
                                )
                            } else if (iconRes != null) {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = null,
                                    tint = WinNativeTextPrimary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            if (iconImage != null || iconRes != null) {
                                androidx.compose.foundation.layout
                                    .Spacer(Modifier.size(12.dp))
                            }
                            Text(
                                text = title,
                                color = WinNativeTextPrimary,
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
                                    .background(WinNativeOutline),
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
fun WinNativeDialogButton(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    backgroundColor: Color = WinNativePanel,
    borderColor: Color = WinNativeOutline,
) {
    Box(
        modifier =
            Modifier
                .widthIn(min = 84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(backgroundColor)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = MutableInteractionSource(),
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 18.dp, vertical = 11.dp),
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
private fun WinNativeMessageDialog(
    title: String?,
    message: String,
    confirmLabel: String,
    confirmColor: Color,
    showCancel: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WinNativeDialogShell(
        onDismiss = onDismiss,
        title = title,
        maxWidth = 420.dp,
    ) {
        Text(
            text = message,
            color = WinNativeTextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(WinNativeOutline),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            if (showCancel) {
                WinNativeDialogButton(
                    label = stringResource(R.string.common_ui_cancel),
                    textColor = WinNativeTextPrimary,
                    onClick = onDismiss,
                )
            }
            WinNativeDialogButton(
                label = confirmLabel,
                textColor = confirmColor,
                backgroundColor = confirmColor.copy(alpha = 0.12f),
                borderColor = confirmColor.copy(alpha = 0.3f),
                onClick = onConfirm,
            )
        }
    }
}

@Composable
private fun WinNativePromptDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialValue) }

    WinNativeDialogShell(
        onDismiss = onDismiss,
        title = title,
        maxWidth = 420.dp,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle =
                androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                    color = WinNativeTextPrimary,
                ),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WinNativeAccent,
                    unfocusedBorderColor = WinNativeOutline,
                    focusedTextColor = WinNativeTextPrimary,
                    unfocusedTextColor = WinNativeTextPrimary,
                    focusedContainerColor = WinNativeBackground,
                    unfocusedContainerColor = WinNativeBackground,
                    focusedLabelColor = WinNativeTextSecondary,
                    unfocusedLabelColor = WinNativeTextSecondary,
                    cursorColor = WinNativeAccent,
                ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(WinNativeOutline),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            WinNativeDialogButton(
                label = stringResource(R.string.common_ui_cancel),
                textColor = WinNativeTextPrimary,
                onClick = onDismiss,
            )
            WinNativeDialogButton(
                label = stringResource(R.string.common_ui_ok),
                textColor = WinNativeAccent,
                backgroundColor = WinNativeAccent.copy(alpha = 0.12f),
                borderColor = WinNativeAccent.copy(alpha = 0.3f),
                onClick = {
                    val trimmed = value.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirm(trimmed)
                    }
                },
            )
        }
    }
}

@Composable
private fun WinNativeShortcutPropertiesDialog(
    title: String,
    playCountText: String,
    playtimeText: String,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    WinNativeDialogShell(
        onDismiss = onDismiss,
        title = title,
        maxWidth = 420.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = playCountText,
                color = WinNativeTextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Text(
                text = playtimeText,
                color = WinNativeTextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
        WinNativeDialogButton(
            label = stringResource(R.string.shortcuts_properties_reset),
            textColor = WinNativeDanger,
            backgroundColor = WinNativeDanger.copy(alpha = 0.12f),
            borderColor = WinNativeDanger.copy(alpha = 0.3f),
            onClick = onReset,
        )
    }
}

@Composable
private fun WinNativeLoadingDialog(message: String) {
    ComposeDialog(
        onDismissRequest = {},
        properties = ComposeDialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 40.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(WinNativeSurface)
                    .border(1.dp, WinNativeOutline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = WinNativeAccent,
                        strokeWidth = 2.5.dp,
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        text = message,
                        color = WinNativeTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
