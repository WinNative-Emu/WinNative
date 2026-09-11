package com.winlator.cmod.feature.settings
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.winlator.cmod.app.shell.UnifiedActivity
import com.winlator.cmod.R
import com.winlator.cmod.runtime.display.winhandler.WinHandler
import com.winlator.cmod.runtime.input.ControllerHelper
import com.winlator.cmod.runtime.input.ControlsEditorActivity
import com.winlator.cmod.runtime.input.controls.Binding
import com.winlator.cmod.runtime.input.controls.ControlElement
import com.winlator.cmod.runtime.input.controls.ControlsProfile
import com.winlator.cmod.runtime.input.controls.ExternalController
import com.winlator.cmod.runtime.input.controls.ExternalControllerBinding
import com.winlator.cmod.runtime.input.controls.GestureProfileManager
import com.winlator.cmod.runtime.input.controls.InputControlsManager
import com.winlator.cmod.runtime.input.ui.InputControlsView
import com.winlator.cmod.runtime.input.ui.TouchGestureConfig
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.HttpUtils
import com.winlator.cmod.shared.math.Mathf
import com.winlator.cmod.shared.ui.dialog.ContentDialog
import com.winlator.cmod.shared.theme.WinNativeTheme
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class InputControlsFragment : Fragment() {
    private lateinit var manager: InputControlsManager
    private lateinit var gestureProfileManager: GestureProfileManager
    private lateinit var preferences: SharedPreferences

    private var screenState by mutableStateOf(InputControlsScreenState())
    private var dialogState: InputControlsDialogUiState by mutableStateOf(InputControlsDialogUiState.None)
    private var pendingConfirmAction: (() -> Unit)? = null
    private var pendingPromptAction: ((String) -> Unit)? = null
    private var pendingChoiceAction: ((Int) -> Unit)? = null
    private var pendingMultiChoiceAction: ((Set<Int>) -> Unit)? = null

    private var currentProfile: ControlsProfile? = null
    private var selectedGestureProfileId = -1
    private var gestureEditorExpanded = false
    private var triggerTypeExpanded = false
    private var gyroscopeExpanded = false
    private val expandedControllerIds = mutableSetOf<String>()
    private val visibleControllers = mutableListOf<ExternalController>()

    private var activeBindingController: ExternalController? = null
    private var activeBindingL2WasPressed = false
    private var activeBindingR2WasPressed = false

    private var gyroSensorManager: SensorManager? = null
    private var gyroListener: SensorEventListener? = null
    private var gyroPreviewView: InputControlsView? = null
    private val remoteProfileRequestInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = InputControlsManager(requireContext())
        gestureProfileManager = GestureProfileManager(requireContext())
        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val deepLink = activity?.intent?.getIntExtra("gesture_profile_id", -1) ?: -1
        activity?.intent?.removeExtra("gesture_profile_id")
        selectedGestureProfileId = deepLink
        gestureEditorExpanded = deepLink >= 0

        val selectedProfileId = arguments?.getInt(ARG_SELECTED_PROFILE_ID, 0) ?: 0
        val profileId =
            if (selectedProfileId > 0) {
                selectedProfileId
            } else {
                preferences.getInt(PREF_SELECTED_PROFILE_ID, 0)
            }
        currentProfile = if (profileId > 0) manager.getProfile(profileId) else null
        refreshVisibleControllers()
        publishUiState()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinNativeTheme(
                    colorScheme =
                        darkColorScheme(
                            primary = Color(0xFF1A9FFF),
                            background = Color(0xFF18181D),
                            surface = Color(0xFF1C1C2A),
                        ),
                ) {
                    InputControlsScreen(
                        state = screenState,
                        bridge = (requireActivity() as? UnifiedActivity)?.settingsNavBridge,
                        actions =
                            InputControlsScreenActions(
                                onSelectProfile = ::showProfilePicker,
                                onOpenEditor = ::openControlsEditor,
                                onAddProfile = ::addProfile,
                                onEditProfile = ::editProfile,
                                onDuplicateProfile = ::duplicateProfile,
                                onResetProfile = ::resetProfile,
                                onRemoveProfile = ::removeProfile,
                                onDismissDialog = ::dismissComposeDialog,
                                onConfirmDialog = ::confirmComposeDialog,
                                onPromptDialogConfirm = ::confirmPromptDialog,
                                onChoiceDialogSelect = ::selectChoiceDialog,
                                onMultiChoiceDialogConfirm = ::confirmMultiChoiceDialog,
                                onOverlayOpacityChanged = ::setOverlayOpacity,
                                onAutoHideTouchOnControllerChanged = { enabled ->
                                    preferences.edit()
                                        .putBoolean("auto_hide_touch_on_controller", enabled)
                                        .apply()
                                    publishUiState()
                                },
                                onGyroscopeEnabledChanged = { enabled ->
                                    val editor = preferences.edit()
                                    editor.putBoolean("gyro_enabled", enabled)
                                    editor.apply()
                                    publishUiState()
                                },
                                onGyroscopeModeSelected = { mode ->
                                    preferences.edit().putInt("gyro_mode", mode).apply()
                                    publishUiState()
                                },
                                onGyroOrientationModeChanged = { enabled ->
                                    preferences.edit().putBoolean("gyro_orientation_enabled", enabled).apply()
                                    publishUiState()
                                },
                                onGyroscopeActivatorClick = ::showActivatorPicker,
                                onRightStickGyroChanged = { enabled ->
                                    preferences.edit().putBoolean("process_gyro_with_left_trigger", enabled).apply()
                                    publishUiState()
                                },
                                onGyroMouseEnabledChanged = { enabled ->
                                    preferences.edit().putBoolean("mouse_gyro_enabled", enabled).apply()
                                    publishUiState()
                                },
                                onGyroMouseScaleChanged = { value ->
                                    preferences.edit().putFloat("gyro_mouse_scale", value.toFloat()).apply()
                                    publishUiState()
                                },
                                onGyroscopeExpandedChanged = { expanded ->
                                    gyroscopeExpanded = expanded
                                    if (!expanded) detachGyroPreview()
                                    publishUiState()
                                },
                                onGyroXSensitivityChanged = { value -> setFloatPreference("gyro_x_sensitivity", value) },
                                onGyroYSensitivityChanged = { value -> setFloatPreference("gyro_y_sensitivity", value) },
                                onGyroSmoothingChanged = { value -> setFloatPreference("gyro_smoothing", value) },
                                onGyroDeadzoneChanged = { value -> setFloatPreference("gyro_deadzone", value) },
                                onInvertGyroXChanged = { enabled ->
                                    preferences.edit().putBoolean("invert_gyro_x", enabled).apply()
                                    publishUiState()
                                },
                                onInvertGyroYChanged = { enabled ->
                                    preferences.edit().putBoolean("invert_gyro_y", enabled).apply()
                                    publishUiState()
                                },
                                onResetGyroPreview = {
                                    gyroPreviewView?.resetStickPosition()
                                    gyroPreviewView?.invalidate()
                                },
                                onAttachGyroPreview = ::attachGyroPreview,
                                onDetachGyroPreview = ::detachGyroPreview,
                                onSelectGestureProfile = ::showGestureProfilePicker,
                                onToggleGestureEditor = ::toggleGestureEditor,
                                onGestureConfigChanged = ::saveSelectedGestureConfig,
                                onNewGestureProfile = ::addGestureProfile,
                                onRenameGestureProfile = ::renameSelectedGestureProfile,
                                onDuplicateGestureProfile = ::duplicateSelectedGestureProfile,
                                onDeleteGestureProfile = ::removeSelectedGestureProfile,
                                onImportGestureProfile = ::promptImportGestureProfile,
                                onExportGestureProfile = ::exportSelectedGestureProfile,
                                onTriggerTypeSelected = { index ->
                                    preferences.edit().putInt("trigger_type", index).apply()
                                    publishUiState()
                                },
                                onTriggerCardExpandedChanged = { expanded ->
                                    triggerTypeExpanded = expanded
                                    publishUiState()
                                },
                                onImportProfile = { promptImportProfile() },
                                onDownloadProfile = ::downloadProfileList,
                                onExportProfile = ::exportProfile,
                                onControllerExpandedToggle = ::toggleControllerExpanded,
                                onRemoveController = ::removeController,
                                onBindingTypeClick = ::showBindingTypePicker,
                                onBindingValueClick = ::showBindingValuePicker,
                                onRemoveBinding = ::removeBinding,
                            ),
                    )
                }
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.common_ui_input_controls)
    }

    override fun onResume() {
        super.onResume()
        refreshVisibleControllers()
        publishUiState()
    }

    override fun onDestroyView() {
        detachGyroPreview()
        stopControllerInputCapture()
        super.onDestroyView()
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val controller = activeBindingController ?: return false
        if (event.deviceId != controller.deviceId) return false
        if (event.repeatCount != 0) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            onControllerButtonPressed(controller, event.keyCode)
        }
        return true
    }

    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val controller = activeBindingController ?: return false
        if (event.deviceId != controller.deviceId) return false
        if (!controller.updateStateFromMotionEvent(event)) return false

        val l2Pressed =
            controller.state.isPressed(ExternalController.IDX_BUTTON_L2.toInt()) || controller.state.triggerL > 0.5f
        val r2Pressed =
            controller.state.isPressed(ExternalController.IDX_BUTTON_R2.toInt()) || controller.state.triggerR > 0.5f
        if (l2Pressed && !activeBindingL2WasPressed) {
            onControllerButtonPressed(controller, KeyEvent.KEYCODE_BUTTON_L2)
        }
        if (r2Pressed && !activeBindingR2WasPressed) {
            onControllerButtonPressed(controller, KeyEvent.KEYCODE_BUTTON_R2)
        }
        activeBindingL2WasPressed = l2Pressed
        activeBindingR2WasPressed = r2Pressed

        val axes =
            intArrayOf(
                MotionEvent.AXIS_X,
                MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z,
                MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X,
                MotionEvent.AXIS_HAT_Y,
            )
        val values =
            floatArrayOf(
                controller.state.thumbLX,
                controller.state.thumbLY,
                controller.state.thumbRX,
                controller.state.thumbRY,
                controller.state.getDPadX().toFloat(),
                controller.state.getDPadY().toFloat(),
            )

        for (index in axes.indices) {
            val sign = Mathf.sign(values[index])
            if (sign.toInt() != 0) {
                val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[index], sign)
                onControllerButtonPressed(controller, axisKeyCode)
            }
        }
        return true
    }

    private fun publishUiState() {
        val profile = currentProfile
        val bindingTypeEntries = resources.getStringArray(R.array.binding_type_entries)
        val triggerDescription =
            HtmlCompat
                .fromHtml(
                    getString(R.string.session_gamepad_help_trigger_mode).trim(),
                    HtmlCompat.FROM_HTML_MODE_LEGACY,
                ).toString()
                .trim()

        screenState =
            InputControlsScreenState(
                selectedProfileName = profile?.name,
                selectedProfileElementCount = profile?.elementCountFromFile ?: 0,
                selectedProfileCanReset = profile != null && manager.canResetProfile(profile),
                overlayOpacity = (preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY) * 100).toInt(),
                autoHideTouchOnController = preferences.getBoolean("auto_hide_touch_on_controller", false),
                gyroscopeEnabled = preferences.getBoolean("gyro_enabled", false),
                gyroscopeModeIndex = preferences.getInt("gyro_mode", 0),
                gyroOrientationEnabled = preferences.getBoolean("gyro_orientation_enabled", false),
                gyroscopeActivatorLabel = currentGyroActivatorLabel(),
                rightStickGyroEnabled = preferences.getBoolean("process_gyro_with_left_trigger", false),
                gyroMouseEnabled = preferences.getBoolean("mouse_gyro_enabled", false),
                gyroMouseScale = preferences.getFloat("gyro_mouse_scale", 50.0f).toInt(),
                gyroscopeExpanded = gyroscopeExpanded,
                gyroXSensitivity = (preferences.getFloat("gyro_x_sensitivity", 1.0f) * 100).toInt(),
                gyroYSensitivity = (preferences.getFloat("gyro_y_sensitivity", 1.0f) * 100).toInt(),
                gyroSmoothing = (preferences.getFloat("gyro_smoothing", 0.5f) * 100).toInt(),
                gyroDeadzone = (preferences.getFloat("gyro_deadzone", 0.05f) * 100).toInt(),
                invertGyroX = preferences.getBoolean("invert_gyro_x", false),
                invertGyroY = preferences.getBoolean("invert_gyro_y", false),
                selectedGestureProfileName = selectedGestureProfile().name,
                gestureEditorExpanded = gestureEditorExpanded,
                selectedGestureConfig = TouchGestureConfig.fromJson(selectedGestureProfile().configJson),
                triggerTypeIndex = preferences.getInt("trigger_type", ExternalController.TRIGGER_IS_AXIS.toInt()),
                triggerCardExpanded = triggerTypeExpanded,
                triggerDescription = triggerDescription,
                controllerCards =
                    visibleControllers.map { controller ->
                        InputControllerCardState(
                            controllerId = controller.id,
                            name = controller.name,
                            bindingCount = controller.controllerBindingCount,
                            connected = controller.isConnected,
                            expanded = profile != null && controller.id in expandedControllerIds,
                            showBindings = profile != null,
                            bindings =
                                if (profile != null && controller.id in expandedControllerIds) {
                                    buildBindingState(controller, bindingTypeEntries)
                                } else {
                                    emptyList()
                                },
                        )
                    },
                dialog = dialogState,
            )
    }

    private fun buildBindingState(
        controller: ExternalController,
        bindingTypeEntries: Array<String>,
    ): List<InputControllerBindingState> =
        buildList {
            for (index in 0 until controller.controllerBindingCount) {
                val binding = controller.getControllerBindingAt(index)
                val rawLabel = binding.toString()
                val isPlayStation =
                    controller.deviceId >= 0 && ControllerHelper.isPlayStationControllerById(controller.deviceId)
                add(
                    InputControllerBindingState(
                        keyCode = binding.keyCodeForAxis,
                        label =
                            if (isPlayStation) {
                                ExternalControllerBinding.getPlayStationLabel(rawLabel)
                            } else {
                                rawLabel
                            },
                        typeLabel = bindingTypeEntries[bindingTypeIndex(binding)],
                        bindingLabel = binding.binding.toString(),
                    ),
                )
            }
        }

    private fun refreshVisibleControllers() {
        val activeId = activeBindingController?.id
        visibleControllers.clear()

        val profile = currentProfile
        if (profile != null) {
            val profileControllers = profile.loadControllers()
            val liveControllers = ExternalController.getControllers()
            
            for (pController in profileControllers) {
                val liveMatch = liveControllers.find { it.id == pController.id }
                if (liveMatch != null) {
                    for (i in 0 until pController.controllerBindingCount) {
                        liveMatch.addControllerBinding(pController.getControllerBindingAt(i))
                    }
                    visibleControllers.add(liveMatch)
                } else {
                    visibleControllers.add(pController)
                }
            }
            
            for (controller in liveControllers) {
                if (visibleControllers.none { it.id == controller.id }) {
                    visibleControllers.add(controller)
                }
            }
        } else {
            visibleControllers.addAll(ExternalController.getControllers())
        }

        activeBindingController =
            activeId?.let { id ->
                visibleControllers.firstOrNull { it.id == id }
            }
        if (activeBindingController == null) {
            stopControllerInputCapture()
        }
    }

    private fun persistSelectedProfileId() {
        preferences.edit().putInt(PREF_SELECTED_PROFILE_ID, currentProfile?.id ?: 0).apply()
    }

    private fun setOverlayOpacity(value: Int) {
        preferences.edit().putFloat("overlay_opacity", value / 100.0f).apply()
        publishUiState()
    }

    private fun setFloatPreference(
        key: String,
        value: Int,
    ) {
        preferences.edit().putFloat(key, value / 100.0f).apply()
        publishUiState()
    }

    private fun showProfilePicker() {
        val profiles = manager.profiles
        if (profiles.isEmpty()) {
            WinToast.show(requireContext(), R.string.input_controls_editor_no_profile_found)
            return
        }

        val names = profiles.map { it.name }.toTypedArray()
        val checkedIndex = profiles.indexOfFirst { it.id == currentProfile?.id }.let { if (it >= 0) it else 0 }
        showChoiceDialog(
            title = getString(R.string.input_controls_editor_select_profile),
            items = names,
            checkedIndex = checkedIndex,
        ) { which ->
            currentProfile = profiles[which]
            persistSelectedProfileId()
            expandedControllerIds.clear()
            stopControllerInputCapture()
            refreshVisibleControllers()
            publishUiState()
        }
    }

    private fun openControlsEditor() {
        val profile = currentProfile
        if (profile != null) {
            startActivity(
                Intent(requireContext(), ControlsEditorActivity::class.java).apply {
                    putExtra("profile_id", profile.id)
                },
            )
        } else {
            WinToast.show(requireContext(), R.string.input_controls_editor_no_profile_selected)
        }
    }

    private fun addProfile() {
        showPromptDialog(
            title = getString(R.string.input_controls_editor_profile_name),
            initialValue = "",
            confirmLabel = getString(R.string.common_ui_ok),
        ) { name ->
            currentProfile = manager.createProfile(name)
            persistSelectedProfileId()
            refreshVisibleControllers()
            publishUiState()
        }
    }

    private fun editProfile() {
        val profile = currentProfile
        if (profile != null) {
            showPromptDialog(
                title = getString(R.string.input_controls_editor_profile_name),
                initialValue = profile.name,
                confirmLabel = getString(R.string.common_ui_ok),
            ) { name ->
                profile.name = name
                profile.save()
                publishUiState()
            }
        } else {
            WinToast.show(requireContext(), R.string.input_controls_editor_no_profile_selected)
        }
    }

    private fun duplicateProfile() {
        val profile = currentProfile
        if (profile != null) {
            showConfirmDialog(
                message = getString(R.string.input_controls_editor_confirm_duplicate_profile),
                confirmLabel = getString(R.string.common_ui_duplicate),
                tone = InputDialogTone.Accent,
            ) {
                currentProfile = manager.duplicateProfile(profile)
                persistSelectedProfileId()
                expandedControllerIds.clear()
                stopControllerInputCapture()
                refreshVisibleControllers()
                publishUiState()
            }
        } else {
            WinToast.show(requireContext(), R.string.input_controls_editor_no_profile_selected)
        }
    }

    private fun resetProfile() {
        val profile = currentProfile
        if (profile == null) {
            WinToast.show(requireContext(), R.string.input_controls_editor_no_profile_selected)
            return
        }
        if (!manager.canResetProfile(profile)) {
            WinToast.show(requireContext(), R.string.input_controls_editor_reset_unavailable)
            return
        }
        showConfirmDialog(
            message = getString(R.string.input_controls_editor_confirm_reset_profile),
            confirmLabel = getString(R.string.common_ui_reset),
            tone = InputDialogTone.Danger,
        ) {
            currentProfile = manager.resetProfile(profile)
            persistSelectedProfileId()
            expandedControllerIds.clear()
            stopControllerInputCapture()
            refreshVisibleControllers()
            publishUiState()
            WinToast.show(requireContext(), R.string.input_controls_editor_profile_reset)
        }
    }

    private fun removeProfile() {
        val profile = currentProfile
        if (profile != null) {
            showConfirmDialog(
                message = getString(R.string.input_controls_editor_confirm_remove_profile),
                confirmLabel = getString(R.string.common_ui_remove),
                tone = InputDialogTone.Danger,
            ) {
                manager.removeProfile(profile)
                currentProfile = null
                persistSelectedProfileId()
                expandedControllerIds.clear()
                stopControllerInputCapture()
                refreshVisibleControllers()
                publishUiState()
            }
        } else {
            WinToast.show(requireContext(), R.string.input_controls_editor_no_profile_selected)
        }
    }

    private fun inputControlsUrl(): String =
        com.winlator.cmod.shared.io.DownloadSource
            .mirroredUrl(requireContext(), INPUT_CONTROLS_URL)

    private fun downloadProfileList() {
        val activity = activity ?: return
        if (!remoteProfileRequestInFlight.compareAndSet(false, true)) return
        HttpUtils.download(String.format(inputControlsUrl(), "index.txt")) { content ->
            if (!isAdded) {
                remoteProfileRequestInFlight.set(false)
                return@download
            }
            activity.runOnUiThread {
                remoteProfileRequestInFlight.set(false)
                if (content != null) {
                    val items =
                        content
                            .split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toTypedArray()
                    if (items.isNotEmpty()) {
                        val installedNames = installedProfileKeys()
                        val disabledIndices =
                            items
                                .mapIndexedNotNull { index, item ->
                                    if (installedNames.contains(normalizeProfileKey(item))) index else null
                                }.toSet()
                        showMultiChoiceDialog(
                            title = getString(R.string.input_controls_editor_download_profile),
                            items = items,
                            disabledIndices = disabledIndices,
                            confirmLabel = getString(R.string.common_ui_download),
                        ) { selectedIndices ->
                            if (selectedIndices.isNotEmpty()) {
                                downloadSelectedProfiles(items, selectedIndices.sorted())
                            }
                        }
                    } else {
                        WinToast.show(activity, R.string.input_controls_editor_unable_to_load_list)
                    }
                } else {
                    WinToast.show(activity, R.string.input_controls_editor_unable_to_load_list)
                }
            }
        }
    }

    private fun downloadSelectedProfiles(
        items: Array<String>,
        positions: List<Int>,
    ) {
        val activity = activity ?: return
        if (positions.isEmpty()) return
        val installedNames = installedProfileKeys()
        val eligiblePositions =
            positions.filterNot { index ->
                installedNames.contains(normalizeProfileKey(items[index]))
            }
        if (eligiblePositions.isEmpty()) return
        if (!remoteProfileRequestInFlight.compareAndSet(false, true)) return
        currentProfile = null
        persistSelectedProfileId()
        expandedControllerIds.clear()
        stopControllerInputCapture()
        val processedCount = AtomicInteger()
        val importedCount = AtomicInteger()

        for (position in eligiblePositions) {
            val itemName = items[position].trim()
            HttpUtils.download(buildRemoteProfileUrl(itemName)) { content ->
                try {
                    if (content != null) {
                        val profileData =
                            JSONObject(content).apply {
                                put("downloadSource", itemName)
                            }
                        if (manager.importProfile(profileData) != null) {
                            importedCount.incrementAndGet()
                        } else {
                            Log.e("InputControls", "Import failed for remote profile: $itemName")
                        }
                    } else {
                        Log.e("InputControls", "Download failed for remote profile: $itemName")
                    }
                } catch (e: Exception) {
                    Log.e("InputControls", "Exception importing remote profile: $itemName", e)
                }
                if (processedCount.incrementAndGet() == eligiblePositions.size) {
                    if (!isAdded) {
                        remoteProfileRequestInFlight.set(false)
                        return@download
                    }
                    activity.runOnUiThread {
                        remoteProfileRequestInFlight.set(false)
                        refreshVisibleControllers()
                        publishUiState()
                        WinToast.show(
                            activity,
                            if (importedCount.get() > 0) {
                                R.string.settings_content_download_complete
                            } else {
                                R.string.input_controls_editor_unable_to_import
                            },
                        )
                    }
                }
            }
        }
    }

    private fun buildRemoteProfileUrl(itemName: String): String {
        val remoteName = if (itemName.endsWith(".icp", ignoreCase = true)) itemName else "$itemName.icp"
        return String.format(inputControlsUrl(), android.net.Uri.encode(remoteName))
    }

    private fun installedProfileKeys(): Set<String> {
        val context = context ?: return emptySet()
        return manager
            .getProfiles(true)
            .flatMap { profile ->
                buildList {
                    normalizeProfileKey(profile.name).takeIf { it.isNotEmpty() }?.let(::add)
                    readStoredDownloadSource(context, profile.id)
                        ?.let(::normalizeProfileKey)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(::add)
                }
            }.toSet()
    }

    private fun normalizeProfileKey(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val withoutExtension =
            if (trimmed.endsWith(".icp", ignoreCase = true)) {
                trimmed.dropLast(4)
            } else {
                trimmed
            }
        return withoutExtension.trim().lowercase()
    }

    private fun readStoredDownloadSource(
        context: android.content.Context,
        profileId: Int,
    ): String? {
        val file = ControlsProfile.getProfileFile(context, profileId)
        if (!file.isFile) return null
        val json = FileUtils.readString(file) ?: return null
        return runCatching {
            JSONObject(json).let { data ->
                if (data.has("downloadSource")) data.optString("downloadSource") else null
            }
        }.getOrNull()
    }

    private fun promptImportProfile() {
        val activity = activity ?: return
        DirectoryPickerDialog.showFile(
            activity = activity,
            title = getString(R.string.input_controls_editor_import_profile),
            allowedExtensions = setOf("icp"),
        ) { path ->
            if (!isAdded) return@showFile
            importProfileFromJson(FileUtils.readString(File(path)))
        }
    }

    private fun importProfileFromJson(jsonString: String?) {
        try {
            if (jsonString.isNullOrBlank()) {
                WinToast.show(
                    requireContext(),
                    getString(R.string.input_controls_editor_unable_to_import) + ": Empty file",
                )
                return
            }

            val imported =
                runCatching {
                    manager.importProfile(JSONObject(jsonString))
                }.getOrNull()

            if (imported != null) {
                currentProfile = imported
                persistSelectedProfileId()
                refreshVisibleControllers()
                publishUiState()
            } else {
                manager.loadProfiles(false)
                refreshVisibleControllers()
                publishUiState()
                WinToast.show(
                    requireContext(),
                    getString(R.string.input_controls_editor_unable_to_import) + ": Invalid profile data",
                )
            }
        } catch (e: Exception) {
            WinToast.show(
                requireContext(),
                getString(R.string.input_controls_editor_unable_to_import) + ": " + e.message,
            )
        }
    }

    private fun exportProfile() {
        val profile = currentProfile
        if (profile != null) {
            val exportedFile = manager.exportProfile(profile)
            if (exportedFile != null) {
                WinToast.show(
                    requireContext(),
                    getString(R.string.input_controls_editor_profile_exported_to) + " " + exportedFile.path,
                )
            }
        } else {
            WinToast.show(requireContext(), R.string.input_controls_editor_no_profile_selected)
        }
    }

    private fun selectedGestureProfile() =
        gestureProfileManager.getProfile(selectedGestureProfileId)
            ?: gestureProfileManager.profiles.firstOrNull()
            ?: gestureProfileManager.defaultProfile

    private fun showGestureProfilePicker() {
        val profiles =
            gestureProfileManager.profiles.ifEmpty { listOf(gestureProfileManager.defaultProfile) }
        val names = profiles.map { it.name }.toTypedArray()
        val current = selectedGestureProfile().id
        val checkedIndex = profiles.indexOfFirst { it.id == current }.let { if (it >= 0) it else 0 }
        showChoiceDialog(
            title = getString(R.string.input_controls_editor_select_profile),
            items = names,
            checkedIndex = checkedIndex,
        ) { which ->
            selectedGestureProfileId = profiles[which].id
            publishUiState()
        }
    }

    private fun toggleGestureEditor() {
        gestureEditorExpanded = !gestureEditorExpanded
        publishUiState()
    }

    private fun saveSelectedGestureConfig(config: TouchGestureConfig) {
        val profile = selectedGestureProfile()
        profile.configJson = config.toJson()
        gestureProfileManager.saveProfile(profile)
        selectedGestureProfileId = profile.id
        publishUiState()
    }

    private fun addGestureProfile() {
        showPromptDialog(
            title = getString(R.string.input_controls_editor_profile_name),
            initialValue = "",
            confirmLabel = getString(R.string.common_ui_ok),
        ) { name ->
            val profile = gestureProfileManager.createProfile(name)
            selectedGestureProfileId = profile.id
            gestureEditorExpanded = true
            publishUiState()
        }
    }

    private fun renameSelectedGestureProfile() {
        val profile = selectedGestureProfile()
        showPromptDialog(
            title = getString(R.string.input_controls_editor_profile_name),
            initialValue = profile.name,
            confirmLabel = getString(R.string.common_ui_ok),
        ) { name ->
            gestureProfileManager.renameProfile(profile, name)
            publishUiState()
        }
    }

    private fun duplicateSelectedGestureProfile() {
        val duplicate = gestureProfileManager.duplicateProfile(selectedGestureProfile())
        selectedGestureProfileId = duplicate.id
        publishUiState()
    }

    private fun removeSelectedGestureProfile() {
        val profile = selectedGestureProfile()
        showConfirmDialog(
            message = getString(R.string.input_controls_editor_confirm_remove_profile),
            confirmLabel = getString(R.string.common_ui_remove),
            tone = InputDialogTone.Danger,
        ) {
            gestureProfileManager.removeProfile(profile)
            selectedGestureProfileId = gestureProfileManager.profiles.firstOrNull()?.id ?: -1
            publishUiState()
        }
    }

    private fun promptImportGestureProfile() {
        val activity = activity ?: return
        DirectoryPickerDialog.showFile(
            activity = activity,
            title = getString(R.string.gesture_profile_import),
            allowedExtensions = setOf("gcp"),
        ) { path ->
            if (!isAdded) return@showFile
            importGestureProfileFromJson(FileUtils.readString(File(path)))
        }
    }

    private fun importGestureProfileFromJson(jsonString: String?) {
        try {
            if (jsonString.isNullOrBlank()) {
                WinToast.show(
                    requireContext(),
                    getString(R.string.input_controls_editor_unable_to_import) + ": Empty file",
                )
                return
            }
            val imported =
                runCatching {
                    gestureProfileManager.importProfile(JSONObject(jsonString))
                }.getOrNull()
            if (imported != null) {
                selectedGestureProfileId = imported.id
                publishUiState()
            } else {
                publishUiState()
                WinToast.show(
                    requireContext(),
                    getString(R.string.input_controls_editor_unable_to_import) + ": Invalid profile data",
                )
            }
        } catch (e: Exception) {
            WinToast.show(
                requireContext(),
                getString(R.string.input_controls_editor_unable_to_import) + ": " + e.message,
            )
        }
    }

    private fun exportSelectedGestureProfile() {
        val profile = selectedGestureProfile()
        val exportedFile = gestureProfileManager.exportProfile(profile)
        if (exportedFile != null) {
            WinToast.show(
                requireContext(),
                getString(R.string.input_controls_editor_profile_exported_to) + " " + exportedFile.path,
            )
        }
    }

    private fun currentGyroActivatorLabel(): String {
        if (preferences.getBoolean("mouse_gyro_enabled", false)) {
            return WinHandler.getGyroMouseActivator(preferences).toString()
        }
        val names = resources.getStringArray(R.array.button_options)
        val keycodes = resources.getIntArray(R.array.button_keycodes)
        val currentKeycode = preferences.getInt("gyro_trigger_button", KeyEvent.KEYCODE_BUTTON_L1)
        val index = keycodes.indexOf(currentKeycode)
        return names.getOrElse(index.takeIf { it >= 0 } ?: 6) { names[0] }
    }

    private fun showActivatorPicker() {
        if (preferences.getBoolean("mouse_gyro_enabled", false)) {
            showMouseGyroActivatorPicker()
            return
        }
        val names = resources.getStringArray(R.array.button_options)
        val keycodes = resources.getIntArray(R.array.button_keycodes)
        val currentKeycode = preferences.getInt("gyro_trigger_button", KeyEvent.KEYCODE_BUTTON_L1)
        val checkedIndex = keycodes.indexOf(currentKeycode).let { if (it >= 0) it else 6 }
        showSingleChoiceDialog(
            title = getString(R.string.session_gyroscope_activator_button),
            items = names,
            checkedIndex = checkedIndex,
        ) { which ->
            preferences.edit().putInt("gyro_trigger_button", keycodes[which]).apply()
            publishUiState()
        }
    }

    private fun showMouseGyroActivatorPicker() {
        val bindings = Binding.activatorBindingValues()
        val current = WinHandler.getGyroMouseActivator(preferences)
        showSingleChoiceDialog(
            title = getString(R.string.session_gyroscope_activator_button),
            items = bindings.map { it.toString() }.toTypedArray(),
            checkedIndex = bindings.indexOf(current).coerceAtLeast(0),
        ) { which ->
            preferences.edit().putString("gyro_mouse_trigger_binding", bindings[which].name).apply()
            publishUiState()
        }
    }

    private fun attachGyroPreview(view: InputControlsView) {
        if (gyroPreviewView === view) return
        gyroPreviewView = view
        view.setEditMode(false)
        view.post {
            if (gyroPreviewView !== view) return@post
            if (view.stickElement == null) {
                val centerX = view.width / 2f
                val centerY = view.height / 2f
                view.initializeStickElement(centerX, centerY, 1.5f)
                view.stickElement?.setType(ControlElement.Type.STICK)
                view.invalidate()
            }
            if (gyroscopeExpanded) startGyroSensor()
        }
    }

    private fun detachGyroPreview() {
        stopGyroSensor()
        gyroPreviewView = null
    }

    private fun startGyroSensor() {
        stopGyroSensor()
        val preview = gyroPreviewView ?: return
        val sensorManager = requireContext().getSystemService(Activity.SENSOR_SERVICE) as SensorManager
        val gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return

        val smoothGyroX = floatArrayOf(0f)
        val smoothGyroY = floatArrayOf(0f)

        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val smoothingFactor = preferences.getFloat("gyro_smoothing", 0.5f)
                    val gyroDeadzone = preferences.getFloat("gyro_deadzone", 0.05f)
                    val invertGyroX = preferences.getBoolean("invert_gyro_x", false)
                    val invertGyroY = preferences.getBoolean("invert_gyro_y", false)
                    val gyroSensitivityX = preferences.getFloat("gyro_x_sensitivity", 1.0f)
                    val gyroSensitivityY = preferences.getFloat("gyro_y_sensitivity", 1.0f)
                    var rawGyroX = event.values[0]
                    var rawGyroY = event.values[1]

                    if (kotlin.math.abs(rawGyroX) < gyroDeadzone) rawGyroX = 0f
                    if (kotlin.math.abs(rawGyroY) < gyroDeadzone) rawGyroY = 0f
                    if (invertGyroX) rawGyroX = -rawGyroX
                    if (invertGyroY) rawGyroY = -rawGyroY

                    rawGyroX *= gyroSensitivityX
                    rawGyroY *= gyroSensitivityY
                    smoothGyroX[0] = smoothGyroX[0] * smoothingFactor + rawGyroX * (1 - smoothingFactor)
                    smoothGyroY[0] = smoothGyroY[0] * smoothingFactor + rawGyroY * (1 - smoothingFactor)

                    val stickElement = preview.stickElement ?: return
                    val stickCenterX = stickElement.x
                    val stickCenterY = stickElement.y
                    val stickRadius = 100

                    var newX = stickElement.currentPosition.x + smoothGyroX[0]
                    var newY = stickElement.currentPosition.y + smoothGyroY[0]

                    val deltaX = newX - stickCenterX
                    val deltaY = newY - stickCenterY
                    val distance = kotlin.math.sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
                    if (distance > stickRadius) {
                        val scaleFactor = stickRadius / distance
                        newX = stickCenterX + deltaX * scaleFactor
                        newY = stickCenterY + deltaY * scaleFactor
                    }

                    preview.updateStickPosition(newX, newY)
                    preview.invalidate()
                }

                override fun onAccuracyChanged(
                    sensor: Sensor,
                    accuracy: Int,
                ) = Unit
            }

        sensorManager.registerListener(listener, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME)
        gyroSensorManager = sensorManager
        gyroListener = listener
    }

    private fun stopGyroSensor() {
        gyroListener?.let { listener ->
            gyroSensorManager?.unregisterListener(listener)
        }
        gyroListener = null
        gyroSensorManager = null
    }

    private fun toggleControllerExpanded(controllerId: String) {
        val profile = currentProfile
        if (profile == null) {
            WinToast.show(requireContext(), R.string.input_controls_editor_no_profile_selected)
            return
        }

        val controller = findVisibleController(controllerId) ?: return
        if (controllerId in expandedControllerIds) {
            expandedControllerIds.remove(controllerId)
            if (activeBindingController?.id == controllerId) stopControllerInputCapture()
        } else {
            expandedControllerIds.clear()
            stopControllerInputCapture()
            expandedControllerIds.add(controllerId)
            profile.putController(controller)
            startControllerInputCapture(controller)
        }
        publishUiState()
    }

    private fun removeController(controllerId: String) {
        val profile = currentProfile ?: return
        val controller = findVisibleController(controllerId) ?: return
        ContentDialog.confirm(requireContext(), R.string.session_gamepad_confirm_remove_controller) {
            expandedControllerIds.remove(controllerId)
            if (activeBindingController?.id == controllerId) stopControllerInputCapture()
            profile.removeController(controller)
            lifecycleScope.launch(Dispatchers.IO) {
                profile.save()
                launch(Dispatchers.Main) {
                    refreshVisibleControllers()
                    publishUiState()
                }
            }
        }
    }

    private fun startControllerInputCapture(controller: ExternalController) {
        activeBindingController = controller
        activeBindingL2WasPressed = false
        activeBindingR2WasPressed = false
    }

    private fun stopControllerInputCapture() {
        activeBindingController = null
        activeBindingL2WasPressed = false
        activeBindingR2WasPressed = false
    }

    private fun onControllerButtonPressed(
        controller: ExternalController,
        keyCode: Int,
    ) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        val profile = currentProfile ?: return

        var binding = controller.getControllerBinding(keyCode)
        if (binding == null) {
            binding =
                ExternalControllerBinding().apply {
                    setKeyCode(keyCode)
                    setBinding(Binding.NONE)
                }
            controller.addControllerBinding(binding)
            profile.putController(controller)
            lifecycleScope.launch(Dispatchers.IO) {
                profile.save()
                launch(Dispatchers.Main) {
                    publishUiState()
                }
            }
        }
    }

    private fun showBindingTypePicker(
        controllerId: String,
        keyCode: Int,
    ) {
        val (controller, binding) = findBinding(controllerId, keyCode) ?: return
        val entries = resources.getStringArray(R.array.binding_type_entries)
        showSingleChoiceDialog(
            title = getString(R.string.input_controls_editor_type),
            items = entries,
            checkedIndex = bindingTypeIndex(binding),
        ) { which ->
            binding.binding =
                when (which) {
                    0 -> Binding.keyboardBindingValues().getOrNull(1) ?: Binding.NONE
                    1 -> Binding.mouseBindingValues().getOrNull(1) ?: Binding.NONE
                    2 -> Binding.gamepadBindingValues().getOrNull(1) ?: Binding.NONE
                    else -> Binding.NONE
                }
            currentProfile?.putController(controller)
            lifecycleScope.launch(Dispatchers.IO) {
                currentProfile?.save()
                launch(Dispatchers.Main) {
                    publishUiState()
                }
            }
        }
    }

    private fun showBindingValuePicker(
        controllerId: String,
        keyCode: Int,
    ) {
        val (controller, binding) = findBinding(controllerId, keyCode) ?: return
        val entries =
            when {
                binding.binding.isKeyboard -> Binding.keyboardBindingLabels()
                binding.binding.isMouse -> Binding.mouseBindingLabels()
                binding.binding.isGamepad -> Binding.gamepadBindingLabels()
                else -> Binding.keyboardBindingLabels()
            }
        val values =
            when {
                binding.binding.isKeyboard -> Binding.keyboardBindingValues()
                binding.binding.isMouse -> Binding.mouseBindingValues()
                binding.binding.isGamepad -> Binding.gamepadBindingValues()
                else -> Binding.keyboardBindingValues()
            }
        val checkedIndex = values.indexOfFirst { it == binding.binding }.let { if (it >= 0) it else 0 }
        showSingleChoiceDialog(
            title = getString(R.string.input_controls_editor_binding),
            items = entries,
            checkedIndex = checkedIndex,
        ) { which ->
            binding.binding = values[which]
            currentProfile?.putController(controller)
            lifecycleScope.launch(Dispatchers.IO) {
                currentProfile?.save()
                launch(Dispatchers.Main) {
                    publishUiState()
                }
            }
        }
    }

    private fun removeBinding(
        controllerId: String,
        keyCode: Int,
    ) {
        val (controller, binding) = findBinding(controllerId, keyCode) ?: return
        controller.removeControllerBinding(binding)
        currentProfile?.putController(controller)
        lifecycleScope.launch(Dispatchers.IO) {
            currentProfile?.save()
            launch(Dispatchers.Main) {
                publishUiState()
            }
        }
    }

    private fun findVisibleController(controllerId: String): ExternalController? = visibleControllers.firstOrNull { it.id == controllerId }

    private fun findBinding(
        controllerId: String,
        keyCode: Int,
    ): Pair<ExternalController, ExternalControllerBinding>? {
        val controller = findVisibleController(controllerId) ?: return null
        val binding = controller.getControllerBinding(keyCode) ?: return null
        return controller to binding
    }

    private fun bindingTypeIndex(binding: ExternalControllerBinding): Int =
        when {
            binding.binding.isKeyboard -> 0
            binding.binding.isMouse -> 1
            binding.binding.isGamepad -> 2
            else -> 0
        }

    private fun showSingleChoiceDialog(
        title: String,
        items: Array<String>,
        checkedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        showChoiceDialog(title, items, checkedIndex, onSelected)
    }

    private fun showPromptDialog(
        title: String,
        initialValue: String,
        confirmLabel: String,
        onConfirmed: (String) -> Unit,
    ) {
        pendingConfirmAction = null
        pendingChoiceAction = null
        pendingMultiChoiceAction = null
        pendingPromptAction = onConfirmed
        dialogState =
            InputControlsDialogUiState.Prompt(
                title = title,
                initialValue = initialValue,
                confirmLabel = confirmLabel,
            )
        publishUiState()
    }

    private fun showConfirmDialog(
        message: String,
        confirmLabel: String,
        tone: InputDialogTone,
        onConfirmed: () -> Unit,
    ) {
        pendingPromptAction = null
        pendingChoiceAction = null
        pendingMultiChoiceAction = null
        pendingConfirmAction = onConfirmed
        dialogState =
            InputControlsDialogUiState.Confirm(
                message = message,
                confirmLabel = confirmLabel,
                tone = tone,
            )
        publishUiState()
    }

    private fun showChoiceDialog(
        title: String,
        items: Array<String>,
        checkedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        pendingPromptAction = null
        pendingConfirmAction = null
        pendingMultiChoiceAction = null
        pendingChoiceAction = onSelected
        dialogState =
            InputControlsDialogUiState.Choice(
                title = title,
                options = items.toList(),
                selectedIndex = checkedIndex,
            )
        publishUiState()
    }

    private fun showMultiChoiceDialog(
        title: String,
        items: Array<String>,
        disabledIndices: Set<Int> = emptySet(),
        confirmLabel: String,
        onConfirmed: (Set<Int>) -> Unit,
    ) {
        pendingPromptAction = null
        pendingConfirmAction = null
        pendingChoiceAction = null
        pendingMultiChoiceAction = onConfirmed
        dialogState =
            InputControlsDialogUiState.MultiChoice(
                title = title,
                options = items.toList(),
                disabledIndices = disabledIndices,
                confirmLabel = confirmLabel,
            )
        publishUiState()
    }

    private fun dismissComposeDialog() {
        pendingConfirmAction = null
        pendingPromptAction = null
        pendingChoiceAction = null
        pendingMultiChoiceAction = null
        dialogState = InputControlsDialogUiState.None
        publishUiState()
    }

    private fun confirmComposeDialog() {
        val action = pendingConfirmAction
        dismissComposeDialog()
        action?.invoke()
    }

    private fun confirmPromptDialog(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            dismissComposeDialog()
            return
        }
        val action = pendingPromptAction
        dismissComposeDialog()
        action?.invoke(trimmed)
    }

    private fun selectChoiceDialog(index: Int) {
        val action = pendingChoiceAction
        dismissComposeDialog()
        action?.invoke(index)
    }

    private fun confirmMultiChoiceDialog(indices: Set<Int>) {
        val action = pendingMultiChoiceAction
        dismissComposeDialog()
        action?.invoke(indices)
    }

    companion object {
        private const val ARG_SELECTED_PROFILE_ID = "selectedProfileId"
        private const val INPUT_CONTROLS_URL =
            "https://raw.githubusercontent.com/nicholasx417/WinNative-Components/main/Profiles/%s"
        private const val PREF_SELECTED_PROFILE_ID = "input_controls_selected_profile_id"

        fun newInstance(profileId: Int = 0): InputControlsFragment =
            InputControlsFragment().apply {
                arguments =
                    Bundle().apply {
                        putInt(ARG_SELECTED_PROFILE_ID, profileId)
                    }
            }
    }
}
