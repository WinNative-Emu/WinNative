package com.winlator.cmod.feature.retro

import android.content.Context
import android.widget.Toast
import com.winlator.cmod.R

object RetroControlsMenu {
    class Host(
        val context: Context,
        val overlay: RetroInputView?,
        val menu: RetroMenuController,
        val systemId: String?,
        val touchControls: () -> Boolean,
        val onTouchControls: (Boolean) -> Unit,
        val adaptiveSticks: () -> Boolean,
        val onAdaptiveSticks: (Boolean) -> Unit,
        val orientationLabel: () -> String,
        val onCloseMenu: () -> Unit,
        val showStickInversion: Boolean = false,
        val onShellBackground: (Boolean) -> Unit = {},
    )

    fun l3r3PrefKey(systemId: String?) = "retro_show_l3r3_" + (systemId ?: "default")

    fun build(host: Host): List<RetroMenuEntry> =
        buildList {
            val context = host.context
            add(
                RetroMenuEntry.Toggle(
                    context.getString(R.string.retro_lr_on_screen_controls),
                    checked = host.touchControls(),
                ) { value ->
                    host.onTouchControls(value)
                    host.menu.rebuild()
                },
            )
            add(
                RetroMenuEntry.Toggle(
                    context.getString(R.string.retro_lr_shell_background),
                    subtitle = context.getString(R.string.retro_lr_shell_background_subtitle),
                    checked = RetroControlLayouts.shellBackground(context, host.systemId),
                ) { value ->
                    RetroControlLayouts.saveShellBackground(context, host.systemId, value)
                    host.overlay?.shellBackground = value
                    host.onShellBackground(value)
                    host.menu.rebuild()
                },
            )
            if (host.showStickInversion) {
                val invPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                val sys = host.systemId ?: "default"
                fun invToggle(label: String, key: String) =
                    RetroMenuEntry.Toggle(label, checked = invPrefs.getBoolean(key, false)) { value ->
                        invPrefs.edit().putBoolean(key, value).apply()
                        host.overlay?.loadStickInversion()
                        host.menu.rebuild()
                    }
                add(invToggle(context.getString(R.string.retro_lr_left_stick_invert_x), "retro_inv_lx_$sys"))
                add(invToggle(context.getString(R.string.retro_lr_left_stick_invert_y), "retro_inv_ly_$sys"))
                add(invToggle(context.getString(R.string.retro_lr_right_stick_invert_x), "retro_inv_rx_$sys"))
                add(invToggle(context.getString(R.string.retro_lr_right_stick_invert_y), "retro_inv_ry_$sys"))
            }
            add(
                RetroMenuEntry.Toggle(
                    context.getString(R.string.retro_lr_adaptive_sticks),
                    checked = host.adaptiveSticks(),
                ) { value ->
                    host.onAdaptiveSticks(value)
                    host.overlay?.adaptiveSticks = value
                    host.menu.rebuild()
                },
            )
            if (host.overlay?.supportsStickButtons == true) {
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                val key = l3r3PrefKey(host.systemId)
                add(
                    RetroMenuEntry.Toggle(
                        context.getString(R.string.retro_ps2_show_l3r3),
                        checked = prefs.getBoolean(key, true),
                    ) { value ->
                        prefs.edit().putBoolean(key, value).apply()
                        host.overlay.showL3R3 = value
                        host.menu.rebuild()
                    },
                )
            }
            add(
                RetroMenuEntry.Slider(
                    label = context.getString(R.string.retro_lr_haptic_feedback),
                    valueText = host.overlay?.hapticStrength?.let { "${(it * 100).toInt()}%" } ?: "0%",
                    value = host.overlay?.hapticStrength ?: 0f,
                    min = 0f,
                    max = 1f,
                    step = 0.05f,
                ) { value ->
                    host.overlay?.hapticStrength = value
                    androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(context)
                        .edit()
                        .putFloat("retro_haptic_strength", value)
                        .apply()
                    host.menu.rebuild()
                },
            )
            val orientationLabel = host.orientationLabel()
            add(
                RetroMenuEntry.Action(
                    context.getString(R.string.retro_lr_edit_layout, orientationLabel),
                    RetroDrawerIcons.EditLayout,
                ) {
                    host.onCloseMenu()
                    host.overlay?.let {
                        it.visibility = android.view.View.VISIBLE
                        it.enterEdit()
                    }
                },
            )
            add(
                RetroMenuEntry.Action(
                    context.getString(R.string.retro_lr_reset_layout, orientationLabel),
                    RetroDrawerIcons.Reset,
                ) {
                    host.overlay?.resetLayout()
                    Toast
                        .makeText(context, context.getString(R.string.retro_lr_layout_reset, orientationLabel), Toast.LENGTH_SHORT)
                        .show()
                },
            )
            val colors = RetroControlLayouts.loadColors(context, host.systemId)
            fun persistColors(mutate: (RetroCustomColors) -> Unit) {
                mutate(colors)
                RetroControlLayouts.saveColors(context, host.systemId, colors)
                host.overlay?.setCustomColors(colors)
                host.menu.rebuild()
            }
            add(
                RetroMenuEntry.ColorPick(context.getString(R.string.retro_lr_button_color), colors.button) { value ->
                    persistColors { it.button = value }
                },
            )
            add(
                RetroMenuEntry.ColorPick(context.getString(R.string.retro_lr_letter_color), colors.text) { value ->
                    persistColors { it.text = value }
                },
            )
            add(
                RetroMenuEntry.ColorPick(context.getString(R.string.retro_lr_shadow_color), colors.shadow) { value ->
                    persistColors { it.shadow = value }
                },
            )
            add(
                RetroMenuEntry.ColorPick(context.getString(R.string.retro_lr_background_color), colors.body) { value ->
                    persistColors { it.body = value }
                },
            )
            add(
                RetroMenuEntry.Slider(
                    label = context.getString(R.string.retro_lr_button_opacity),
                    valueText = "${(colors.opacity * 100).toInt()}%",
                    value = colors.opacity,
                    min = 0.2f,
                    max = 1.0f,
                    step = 0.05f,
                ) { value ->
                    persistColors { it.opacity = value }
                },
            )
            add(
                RetroMenuEntry.Action(context.getString(R.string.retro_lr_reset_colors), RetroDrawerIcons.Reset) {
                    val fresh = RetroCustomColors()
                    RetroControlLayouts.saveColors(context, host.systemId, fresh)
                    host.overlay?.setCustomColors(fresh)
                    host.menu.rebuild()
                    Toast.makeText(context, context.getString(R.string.retro_lr_colors_reset), Toast.LENGTH_SHORT).show()
                },
            )
        }
}