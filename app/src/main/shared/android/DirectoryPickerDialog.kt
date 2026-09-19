package com.winlator.cmod.shared.android

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.winlator.cmod.R
import com.winlator.cmod.shared.io.ArchiveExtractor
import com.winlator.cmod.shared.theme.WinNativeAccent
import com.winlator.cmod.shared.theme.WinNativeBackground
import com.winlator.cmod.shared.theme.WinNativeFontFamily
import com.winlator.cmod.shared.theme.WinNativeOutline
import com.winlator.cmod.shared.theme.WinNativePanel
import com.winlator.cmod.shared.theme.WinNativeSurface
import com.winlator.cmod.shared.theme.WinNativeTextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.bindPaneNav
import com.winlator.cmod.shared.ui.nav.paneNavHandlers
import com.winlator.cmod.shared.ui.nav.paneNavItem
import com.winlator.cmod.shared.ui.layout.isCompactWidth
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DirectoryPickerDialog {
    private const val ContentEnterMillis = 220
    private val ContentEnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val FooterButtonHeight = 36.dp
    private val FooterChipWidth = 84.dp
    private val DialogHorizontalPadding = 18.dp
    private val DialogCutoutStartPadding = 14.dp
    private val CurrentPathHorizontalPadding = 10.dp
    private val FolderGridCardPadding = 6.dp
    private val BgDark = WinNativeBackground
    private val CardDark = WinNativeSurface
    private val CardBorder = WinNativeOutline
    private val IconBoxBg = Color(0xFF242434)
    private val Accent = WinNativeAccent
    private val NavHighlight = Color(0xFF4FC3F7)
    private val TextPrimary = WinNativeTextPrimary
    private val TextSecondary = WinNativeTextSecondary

    val ExecutableExtensions = setOf("exe", "bat", "cmd", "msi")

    private enum class SelectionMode {
        DIRECTORY,
        FILE,
        MANAGE,
    }

    data class ManagedRoot(
        val label: String,
        val path: String,
    )

    data class ManagedContainer(
        val id: Int,
        val name: String,
    )

    private data class Entry(
        val label: String,
        val target: File,
        val isParent: Boolean = false,
        val isSelectableFile: Boolean = false,
    )

    private enum class ClipMode { COPY, CUT, EXTRACT }

    private data class ClipData(
        val files: List<File>,
        val mode: ClipMode,
    )

    private data class ItemAction(
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val label: String,
        val onClick: () -> Unit,
    )

    @JvmStatic
    fun show(
        activity: Activity,
        initialPath: String? = null,
        title: String = activity.getString(R.string.common_ui_select_folder),
        dimBackground: Boolean = true,
        dimAmount: Float = 0.30f,
        preserveBackdropBlur: Boolean = false,
        extraRoots: List<ManagedRoot> = emptyList(),
        onSelected: (String) -> Unit,
    ) {
        showPicker(
            activity = activity,
            initialPath = initialPath,
            title = title,
            mode = SelectionMode.DIRECTORY,
            allowedExtensions = emptySet(),
            dimBackground = dimBackground,
            dimAmount = dimAmount,
            preserveBackdropBlur = preserveBackdropBlur,
            extraRoots = extraRoots,
            onSelected = onSelected,
        )
    }

    fun showFile(
        activity: Activity,
        initialPath: String? = null,
        title: String = activity.getString(R.string.common_ui_open_file),
        allowedExtensions: Set<String> = emptySet(),
        dimBackground: Boolean = true,
        dimAmount: Float = 0.30f,
        preserveBackdropBlur: Boolean = false,
        extraRoots: List<ManagedRoot> = emptyList(),
        onSelected: (String) -> Unit,
    ) {
        showPicker(
            activity = activity,
            initialPath = initialPath,
            title = title,
            mode = SelectionMode.FILE,
            allowedExtensions = normalizeAllowedExtensions(allowedExtensions),
            dimBackground = dimBackground,
            dimAmount = dimAmount,
            preserveBackdropBlur = preserveBackdropBlur,
            extraRoots = extraRoots,
            onSelected = onSelected,
        )
    }

    fun showManager(
        activity: Activity,
        initialPath: String? = null,
        title: String = activity.getString(R.string.file_manager_title),
        managedRoots: List<ManagedRoot>,
        containers: List<ManagedContainer> = emptyList(),
        onRunFile: ((String, Int) -> Unit)? = null,
        onCreateShortcut: ((String) -> Unit)? = null,
        dimBackground: Boolean = true,
        dimAmount: Float = 0.30f,
        preserveBackdropBlur: Boolean = false,
    ) {
        showPicker(
            activity = activity,
            initialPath = initialPath ?: managedRoots.firstOrNull()?.path,
            title = title,
            mode = SelectionMode.MANAGE,
            allowedExtensions = emptySet(),
            dimBackground = dimBackground,
            dimAmount = dimAmount,
            preserveBackdropBlur = preserveBackdropBlur,
            managedRoots = managedRoots,
            containers = containers,
            onRunFile = onRunFile,
            onCreateShortcut = onCreateShortcut,
            onSelected = {},
        )
    }

    private fun showPicker(
        activity: Activity,
        initialPath: String?,
        title: String,
        mode: SelectionMode,
        allowedExtensions: Set<String>,
        dimBackground: Boolean,
        dimAmount: Float,
        preserveBackdropBlur: Boolean,
        managedRoots: List<ManagedRoot> = emptyList(),
        extraRoots: List<ManagedRoot> = emptyList(),
        containers: List<ManagedContainer> = emptyList(),
        onRunFile: ((String, Int) -> Unit)? = null,
        onCreateShortcut: ((String) -> Unit)? = null,
        onSelected: (String) -> Unit,
    ) {
        if (!ensureAllFilesAccess(activity)) return

        val roots = buildRootDirectories(activity)
        val initialDir = resolveInitialDirectory(initialPath, roots)

        val dialog =
            Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(true)
                setCanceledOnTouchOutside(true)
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                    setWindowAnimations(0)
                    if (dimBackground) {
                        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        setDimAmount(dimAmount.coerceIn(0f, 1f))
                    } else {
                        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        setDimAmount(0f)
                    }
                }
            }

        val composeView =
            ComposeView(activity).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                (activity as? ComponentActivity)?.let {
                    setViewTreeLifecycleOwner(it)
                    setViewTreeSavedStateRegistryOwner(it)
                }
                setContent {
                    val defaultDensity = LocalDensity.current
                    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                    androidx.compose.runtime.LaunchedEffect(
                        configuration.orientation,
                        configuration.screenWidthDp,
                        configuration.screenHeightDp,
                    ) {
                        applyDialogWindowSizing(activity, dialog.window, preserveBackdropBlur)
                        dialog.window?.decorView?.post {
                            applyDialogWindowSizing(activity, dialog.window, preserveBackdropBlur)
                        }
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(defaultDensity.density, fontScale = 1f),
                    ) {
                        WinNativeTheme {
                            CompositionLocalProvider(
                                LocalTextStyle provides
                                    LocalTextStyle.current.merge(
                                        TextStyle(fontFamily = WinNativeFontFamily),
                                    ),
                            ) {
                                fun dismissPicker() {
                                    dialog.dismiss()
                                }

                                DirectoryPickerDialogContent(
                                    window = dialog.window,
                                    title = title,
                                    initialDir = initialDir,
                                    roots = roots,
                                    mode = mode,
                                    allowedExtensions = allowedExtensions,
                                    managedRoots = managedRoots,
                                    extraRoots = extraRoots,
                                    containers = containers,
                                    onRunFile = onRunFile,
                                    onCreateShortcut = onCreateShortcut,
                                    onDismiss = ::dismissPicker,
                                    onSelect = { path ->
                                        onSelected(path)
                                        dismissPicker()
                                    },
                                )
                            }
                        }
                    }
                }
            }

        dialog.setContentView(composeView)
        dialog.show()
        applyDialogWindowSizing(activity, dialog.window, preserveBackdropBlur)
    }

    private fun normalizeAllowedExtensions(allowedExtensions: Set<String>): Set<String> =
        allowedExtensions
            .map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun applyDialogWindowSizing(
        activity: Activity,
        window: Window?,
        preserveBackdropBlur: Boolean,
    ) {
        window?.apply {
            setWindowAnimations(0)
            val dm = activity.resources.displayMetrics
            val screenWidthDp = dm.widthPixels / dm.density
            val widthFraction = (0.82f + 84f / screenWidthDp).coerceIn(0.88f, 0.96f)
            val heightFraction = (0.90f + 24f / screenWidthDp).coerceIn(0.92f, 0.94f)

            setLayout((dm.widthPixels * widthFraction).toInt(), (dm.heightPixels * heightFraction).toInt())
            if (preserveBackdropBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val params = attributes
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = 10
                attributes = params
            }
        }
    }

    @Composable
    private fun DirectoryPickerDialogContent(
        window: Window?,
        title: String,
        initialDir: File,
        roots: List<File>,
        mode: SelectionMode,
        allowedExtensions: Set<String>,
        managedRoots: List<ManagedRoot> = emptyList(),
        extraRoots: List<ManagedRoot> = emptyList(),
        containers: List<ManagedContainer> = emptyList(),
        onRunFile: ((String, Int) -> Unit)? = null,
        onCreateShortcut: ((String) -> Unit)? = null,
        onDismiss: () -> Unit,
        onSelect: (String) -> Unit,
    ) {
        val manage = mode == SelectionMode.MANAGE
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var currentDir by remember(initialDir.absolutePath) { mutableStateOf(initialDir) }
        var selectedFile by remember { mutableStateOf<File?>(null) }
        var showPathInput by remember { mutableStateOf(false) }
        var pathInputText by remember { mutableStateOf("") }
        var rootsExpanded by remember { mutableStateOf(false) }
        var refreshTick by remember { mutableStateOf(0) }
        var clipboard by remember { mutableStateOf<ClipData?>(null) }
        var transferProgress by remember { mutableStateOf<Float?>(null) }
        var transferLabel by remember { mutableStateOf("") }
        var transferJob by remember { mutableStateOf<Job?>(null) }
        var menuTarget by remember { mutableStateOf<File?>(null) }
        var renameTarget by remember { mutableStateOf<File?>(null) }
        var deleteTargets by remember { mutableStateOf<List<File>?>(null) }
        var runTarget by remember { mutableStateOf<File?>(null) }
        var showNewFolder by remember { mutableStateOf(false) }
        var multiSelect by remember { mutableStateOf(false) }
        var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
        val upLabel = activityString(R.string.saves_import_export_up_directory)
        val entries = remember(currentDir.absolutePath, upLabel, mode, allowedExtensions, refreshTick) {
            buildEntries(currentDir, upLabel, mode, allowedExtensions)
        }

        fun refreshEntries() {
            refreshTick++
        }

        fun extractInto(dir: File, archives: List<File>, fromClipboard: Boolean) {
            val archive = archives.firstOrNull() ?: return
            if (transferProgress != null) return
            val destDir = uniqueChild(dir, ArchiveExtractor.baseName(archive))
            transferLabel = context.getString(R.string.file_manager_extracting, archive.name)
            transferProgress = 0f
            transferJob = scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        ArchiveExtractor.extract(
                            source = archive,
                            destDir = destDir,
                            onProgress = { transferProgress = it },
                            isActive = { isActive },
                        )
                    }
                    if (fromClipboard) clipboard = null
                } catch (e: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { destDir.deleteRecursively() }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: context.getString(R.string.file_manager_extract_failed), Toast.LENGTH_SHORT).show()
                } finally {
                    transferProgress = null
                    transferJob = null
                    refreshEntries()
                }
            }
        }

        fun pasteInto(dir: File) {
            val cb = clipboard ?: return
            if (transferProgress != null) return
            if (cb.mode == ClipMode.EXTRACT) {
                extractInto(dir, cb.files, fromClipboard = true)
                return
            }
            val isCut = cb.mode == ClipMode.CUT
            val sources =
                cb.files.filter { src ->
                    val dest = File(dir, src.name)
                    dest.absolutePath != src.absolutePath && !(src.isDirectory && isSameOrDescendant(dest, src))
                }
            if (sources.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.file_manager_paste_into_itself), Toast.LENGTH_SHORT).show()
                return
            }
            transferLabel =
                if (sources.size == 1) {
                    context.getString(
                        if (isCut) R.string.file_manager_moving else R.string.file_manager_copying,
                        sources.first().name,
                    )
                } else {
                    context.getString(
                        if (isCut) R.string.file_manager_moving_count else R.string.file_manager_copying_count,
                        sources.size,
                    )
                }
            transferProgress = 0f
            transferJob = scope.launch {
                var inFlight: File? = null
                try {
                    withContext(Dispatchers.IO) {
                        val totalBytes = sources.sumOf { treeSize(it) }.coerceAtLeast(1L)
                        var doneBytes = 0L
                        for (src in sources) {
                            val dest = File(dir, src.name)
                            val srcBytes = treeSize(src)
                            inFlight = dest
                            transferRecursively(
                                src = src,
                                dest = dest,
                                isCut = isCut,
                                onProgress = { p ->
                                    transferProgress = ((doneBytes + srcBytes * p) / totalBytes).coerceIn(0f, 1f)
                                },
                                isActive = { isActive },
                            )
                            doneBytes += srcBytes
                            inFlight = null
                        }
                    }
                    clipboard = null
                } catch (e: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { inFlight?.takeIf { it.exists() }?.deleteRecursively() }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: context.getString(R.string.file_manager_operation_failed), Toast.LENGTH_SHORT).show()
                } finally {
                    transferProgress = null
                    transferJob = null
                    refreshEntries()
                }
            }
        }

        fun deleteFiles(targets: List<File>) {
            targets.forEach { f ->
                try {
                    f.deleteRecursively()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: context.getString(R.string.file_manager_delete_failed), Toast.LENGTH_SHORT).show()
                }
                if (selectedFile?.absolutePath == f.absolutePath) selectedFile = null
            }
            selectedPaths = selectedPaths - targets.map { it.absolutePath }.toSet()
            refreshEntries()
        }

        fun renameFile(f: File, newName: String) {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) return
            try {
                f.renameTo(File(f.parentFile, trimmed))
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: context.getString(R.string.file_manager_rename_failed), Toast.LENGTH_SHORT).show()
            }
            refreshEntries()
        }

        fun createFolder(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            try {
                File(currentDir, trimmed).mkdirs()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: context.getString(R.string.file_manager_create_folder_failed), Toast.LENGTH_SHORT).show()
            }
            refreshEntries()
        }

        fun buildItemActions(entry: Entry): List<ItemAction> {
            val target = entry.target
            val batch = multiSelect && selectedPaths.isNotEmpty()
            val targets = if (batch) selectedPaths.map(::File) else listOf(target)
            val count = targets.size
            val actions = mutableListOf<ItemAction>()
            if (!batch && isExecutable(target) && onRunFile != null) {
                actions += ItemAction(Icons.Outlined.PlayArrow, context.getString(R.string.file_manager_run_boot)) {
                    runTarget = target
                    menuTarget = null
                }
            }
            if (!batch && isExecutable(target) && onCreateShortcut != null) {
                actions += ItemAction(Icons.Outlined.AddLink, context.getString(R.string.file_manager_create_shortcut)) {
                    onCreateShortcut.invoke(target.absolutePath)
                    menuTarget = null
                }
            }
            if (!batch && ArchiveExtractor.isSupported(target)) {
                actions += ItemAction(Icons.Outlined.Unarchive, context.getString(R.string.file_manager_extract_here)) {
                    extractInto(currentDir, listOf(target), fromClipboard = false)
                    menuTarget = null
                }
                actions += ItemAction(Icons.Outlined.DriveFolderUpload, context.getString(R.string.file_manager_extract_to)) {
                    clipboard = ClipData(listOf(target), ClipMode.EXTRACT)
                    menuTarget = null
                    multiSelect = false
                    selectedPaths = emptySet()
                }
            }
            actions += ItemAction(
                Icons.Outlined.ContentCopy,
                if (batch) context.getString(R.string.file_manager_copy_count, count) else context.getString(R.string.file_manager_copy),
            ) {
                clipboard = ClipData(targets, ClipMode.COPY)
                menuTarget = null
                multiSelect = false
                selectedPaths = emptySet()
            }
            actions += ItemAction(
                Icons.Outlined.ContentCut,
                if (batch) context.getString(R.string.file_manager_cut_count, count) else context.getString(R.string.file_manager_cut),
            ) {
                clipboard = ClipData(targets, ClipMode.CUT)
                menuTarget = null
                multiSelect = false
                selectedPaths = emptySet()
            }
            if (clipboard != null) {
                actions += ItemAction(Icons.Outlined.ContentPaste, context.getString(R.string.file_manager_paste_here)) {
                    pasteInto(currentDir)
                    menuTarget = null
                }
            }
            if (!batch) {
                actions += ItemAction(Icons.Outlined.DriveFileRenameOutline, context.getString(R.string.common_ui_rename)) {
                    renameTarget = target
                    menuTarget = null
                }
            }
            actions += ItemAction(
                Icons.Outlined.Delete,
                if (batch) context.getString(R.string.file_manager_delete_count, count) else context.getString(R.string.file_manager_delete),
            ) {
                deleteTargets = targets
                menuTarget = null
            }
            return actions
        }
        val folderCount = remember(entries) { entries.count { !it.isParent } }
        val selectableFileCount = remember(entries) { entries.count { it.isSelectableFile } }
        val folderOnlyCount = remember(entries) { entries.count { !it.isParent && !it.isSelectableFile } }
        val footerTitle =
            if (mode == SelectionMode.DIRECTORY) {
                activityString(R.string.common_ui_select_folder)
            } else {
                title
            }
        val footerSubtitle =
            if (mode == SelectionMode.DIRECTORY) {
                title
                    .takeUnless { it.equals(footerTitle, ignoreCase = true) }
                    ?: activityString(R.string.common_ui_browse_local_folders_directly)
            } else {
                selectedFile?.absolutePath ?: currentDir.absolutePath
        }
        val entryCountLabel =
            if (mode == SelectionMode.DIRECTORY) {
                activityPlural(R.plurals.common_ui_folder_count, folderCount)
            } else {
                "${activityPlural(R.plurals.common_ui_folder_count, folderOnlyCount)} / " +
                    activityPlural(R.plurals.common_ui_file_count, selectableFileCount)
        }
        var contentVisible by remember { mutableStateOf(false) }
        val contentAlpha by animateFloatAsState(
            targetValue = if (contentVisible) 1f else 0f,
            animationSpec =
                tween(
                    durationMillis = ContentEnterMillis,
                    easing = ContentEnterEasing,
                ),
            label = "directoryPickerContentFade",
        )
        val contentScale by animateFloatAsState(
            targetValue = if (contentVisible) 1f else 0.972f,
            animationSpec =
                tween(
                    durationMillis = ContentEnterMillis,
                    easing = ContentEnterEasing,
                ),
            label = "directoryPickerContentScale",
        )
        val density = LocalDensity.current
        val hiddenTranslationY = with(density) { 8.dp.toPx() }
        val contentTranslationY by animateFloatAsState(
            targetValue = if (contentVisible) 0f else hiddenTranslationY,
            animationSpec =
                tween(
                    durationMillis = ContentEnterMillis,
                    easing = ContentEnterEasing,
                ),
            label = "directoryPickerContentOffset",
        )
        LaunchedEffect(Unit) {
            contentVisible = true
        }

        val contentRegistry = remember { PaneNavRegistry().apply { stableCursor = true } }
        val menuRegistry = remember { PaneNavRegistry() }
        val rootsRegistry = remember { PaneNavRegistry() }
        val footerRegistry = remember { PaneNavRegistry().apply { singleRow = true } }
        val overlayRegistry = remember { PaneNavRegistry() }
        var footerZone by remember { mutableStateOf(false) }
        val gridState = rememberLazyGridState()
        var gridViewportTop by remember { mutableStateOf(0f) }
        var gridViewportHeight by remember { mutableIntStateOf(0) }
        LaunchedEffect(contentRegistry.activeRow, contentRegistry.activeCol, gridViewportHeight, footerZone) {
            if (footerZone || !contentRegistry.controllerActive || contentRegistry.manualSelection) return@LaunchedEffect
            val bounds = contentRegistry.activeItemBounds() ?: return@LaunchedEffect
            val rowH = bounds.second - bounds.first
            val margin = (rowH * 2f + with(density) { 12.dp.toPx() }).coerceAtMost(gridViewportHeight * 0.4f)
            val vpBottom = gridViewportTop + gridViewportHeight
            val delta = when {
                bounds.second + margin > vpBottom -> bounds.second + margin - vpBottom
                bounds.first - margin < gridViewportTop -> bounds.first - margin - gridViewportTop
                else -> 0f
            }
            if (delta != 0f) runCatching { gridState.animateScrollBy(delta) }
        }
        LaunchedEffect(currentDir.absolutePath) {
            selectedFile = null
            selectedPaths = emptySet()
            contentRegistry.reset()
            footerZone = false
        }
        LaunchedEffect(menuTarget) { if (menuTarget != null) menuRegistry.reset() }
        LaunchedEffect(renameTarget, showNewFolder, showPathInput, deleteTargets, runTarget, transferProgress != null) {
            overlayRegistry.controllerActive = false
            overlayRegistry.reset()
        }
        LaunchedEffect(rootsExpanded) { if (rootsExpanded) rootsRegistry.reset() }
        contentRegistry.onEdgeDown = {
            if (gridState.canScrollForward) {
                val b = contentRegistry.activeItemBounds()
                val step = if (b != null) (b.second - b.first) + with(density) { 6.dp.toPx() } else gridViewportHeight * 0.3f
                scope.launch { gridState.animateScrollBy(step) }
            } else {
                footerZone = true
                contentRegistry.controllerActive = false
                footerRegistry.controllerActive = true
                footerRegistry.reset()
            }
        }
        footerRegistry.onEdgeUp = {
            footerZone = false
            footerRegistry.controllerActive = false
            contentRegistry.controllerActive = true
        }
        val pathNotFound = activityString(R.string.common_ui_path_not_found)

        fun applyTypedPath(typed: String) {
            val target = File(typed.trim())
            if (target.isDirectory) {
                currentDir = target
                selectedFile = null
            } else if (target.isFile) {
                currentDir = target.parentFile ?: currentDir
                selectedFile = target
            } else {
                Toast.makeText(context, pathNotFound, Toast.LENGTH_SHORT).show()
            }
            showPathInput = false
        }

        val handlers =
            remember(window) {
                paneNavHandlers(
                    onDismiss = {
                        when {
                            showPathInput -> showPathInput = false
                            renameTarget != null -> renameTarget = null
                            showNewFolder -> showNewFolder = false
                            deleteTargets != null -> deleteTargets = null
                            runTarget != null -> runTarget = null
                            transferProgress != null -> transferJob?.cancel()
                            menuTarget != null -> menuTarget = null
                            rootsExpanded -> rootsExpanded = false
                            multiSelect -> {
                                multiSelect = false
                                selectedPaths = emptySet()
                            }
                            else -> onDismiss()
                        }
                    },
                    onStart = {
                        val overlayOpen =
                            rootsExpanded || menuTarget != null || renameTarget != null ||
                                showNewFolder || showPathInput || deleteTargets != null ||
                                runTarget != null || transferProgress != null
                        if (showPathInput) {
                            applyTypedPath(pathInputText)
                        } else if (!overlayOpen) {
                            if (manage) {
                                if (clipboard != null) pasteInto(currentDir) else onDismiss()
                            } else {
                                val selectedPath =
                                    if (mode == SelectionMode.FILE) selectedFile?.absolutePath else currentDir.absolutePath
                                if (selectedPath != null) onSelect(selectedPath)
                            }
                        }
                    },
                    registry = {
                        when {
                            menuTarget != null -> menuRegistry
                            rootsExpanded -> rootsRegistry
                            renameTarget != null || showNewFolder || showPathInput ||
                                deleteTargets != null || runTarget != null ||
                                transferProgress != null -> overlayRegistry
                            footerZone -> footerRegistry
                            else -> contentRegistry
                        }
                    },
                )
            }
        DisposableEffect(window, handlers) {
            val restore = window?.bindPaneNav(handlers)
            onDispose { restore?.invoke() }
        }

        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = contentAlpha
                        scaleX = contentScale
                        scaleY = contentScale
                        translationY = contentTranslationY
                    }
                    .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val view = LocalView.current
            var hasLeftDisplayCutout by remember { mutableStateOf(false) }
            DisposableEffect(view) {
                fun updateLeftDisplayCutout(insets: android.view.WindowInsets?) {
                    hasLeftDisplayCutout =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            (insets?.displayCutout?.safeInsetLeft ?: 0) > 0
                }

                updateLeftDisplayCutout(view.rootWindowInsets)
                view.setOnApplyWindowInsetsListener { _, insets ->
                    updateLeftDisplayCutout(insets)
                    insets
                }
                view.requestApplyInsets()
                onDispose {
                    view.setOnApplyWindowInsetsListener(null)
                }
            }
            val startPadding =
                if (hasLeftDisplayCutout) {
                    DialogCutoutStartPadding
                } else {
                    DialogHorizontalPadding
            }
            val folderListMinHeight =
                (maxHeight * 0.48f)
                    .coerceIn(240.dp, 420.dp)
            val entryCountMaxWidth = (maxWidth * 0.42f).coerceIn(128.dp, 220.dp)
            val folderGridMinSize = (maxWidth * 0.22f).coerceIn(140.dp, 150.dp)

            CompositionLocalProvider(LocalPaneNav provides contentRegistry) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp),
                shape = RoundedCornerShape(16.dp),
                color = CardDark,
                border = BorderStroke(1.dp, CardBorder),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                start = startPadding,
                                top = 14.dp,
                                end = DialogHorizontalPadding,
                                bottom = 14.dp,
                            ),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = CurrentPathHorizontalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = activityString(R.string.common_ui_current_folder),
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = entryCountLabel,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.widthIn(max = entryCountMaxWidth),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    CurrentPathCard(
                        path = selectedFile?.name ?: currentDir.absolutePath,
                        onClick = {
                            pathInputText = currentDir.absolutePath
                            showPathInput = true
                        },
                    )
                    Spacer(Modifier.height(8.dp))

                    androidx.compose.foundation.layout.Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .heightIn(min = folderListMinHeight)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BgDark)
                                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                                .onGloballyPositioned {
                                    gridViewportTop = it.positionInWindow().y
                                    gridViewportHeight = it.size.height
                                }
                                .padding(horizontal = FolderGridCardPadding, vertical = FolderGridCardPadding),
                    ) {
                        if (entries.isEmpty()) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = activityString(R.string.common_ui_no_folders_available_here),
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                state = gridState,
                                modifier = Modifier.fillMaxWidth(),
                                columns = GridCells.Adaptive(minSize = folderGridMinSize),
                                contentPadding = PaddingValues(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(entries, key = { entry ->
                                    entry.target.absolutePath + entry.isParent + entry.isSelectableFile
                                }) { entry ->
                                    val isMenuOpen =
                                        manage && menuTarget?.absolutePath == entry.target.absolutePath
                                    val path = entry.target.absolutePath
                                    val multiSelectable = manage && multiSelect && !entry.isParent
                                    val openMenu = {
                                        if (multiSelectable && path !in selectedPaths) selectedPaths = selectedPaths + path
                                        menuTarget = entry.target
                                    }
                                    EntryTile(
                                        entry = entry,
                                        selected =
                                            if (manage && multiSelect) {
                                                path in selectedPaths
                                            } else {
                                                selectedFile?.absolutePath == path
                                            },
                                        checked = multiSelectable && path in selectedPaths,
                                        isEntry = entry === entries.first(),
                                        onClick = {
                                            when {
                                                multiSelectable ->
                                                    selectedPaths =
                                                        if (path in selectedPaths) selectedPaths - path else selectedPaths + path
                                                entry.isSelectableFile -> selectedFile = entry.target
                                                else -> currentDir = entry.target
                                            }
                                        },
                                        onLongClick =
                                            if (manage && !entry.isParent) {
                                                openMenu
                                            } else {
                                                null
                                            },
                                        onSecondary =
                                            if (manage && !entry.isParent) {
                                                openMenu
                                            } else {
                                                {}
                                            },
                                        menuExpanded = isMenuOpen,
                                        onMenuDismiss = { menuTarget = null },
                                        actions = if (isMenuOpen) buildItemActions(entry) else emptyList(),
                                        menuRegistry = menuRegistry,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = CardBorder, thickness = 1.dp)
                    Spacer(Modifier.height(10.dp))

                    if (manage) {
                        // The path label plus three fixed chips, the root selector and Close need
                        // about 505 dp of minimum width. In one row at phone width the selector and
                        // Close are pushed off the right edge and the path collapses to nothing, so
                        // below the compact threshold the label goes on its own line and the controls
                        // wrap. None of the controls uses a Row weight, so the same lambda serves both.
                        val compactFooter = isCompactWidth()
                        val manageFooterControls: @Composable () -> Unit = {
                            clipboard?.let { cb ->
                                val extracting = cb.mode == ClipMode.EXTRACT
                                SecondaryActionChip(
                                    label =
                                        activityString(
                                            if (extracting) R.string.file_manager_extract else R.string.file_manager_paste,
                                        ),
                                    icon = if (extracting) Icons.Outlined.Unarchive else Icons.Outlined.ContentPaste,
                                    accent = true,
                                    compact = true,
                                    modifier = Modifier.width(FooterChipWidth),
                                    onClick = { pasteInto(currentDir) },
                                )
                            }
                            SecondaryActionChip(
                                label = activityString(R.string.file_manager_new_folder_short),
                                icon = Icons.Outlined.CreateNewFolder,
                                accent = true,
                                compact = true,
                                modifier = Modifier.width(FooterChipWidth),
                                onClick = { showNewFolder = true },
                            )
                            SecondaryActionChip(
                                label = activityString(R.string.file_manager_multi_select),
                                icon = Icons.Outlined.Checklist,
                                accent = true,
                                active = multiSelect,
                                compact = true,
                                modifier = Modifier.width(FooterChipWidth),
                                onClick = {
                                    multiSelect = !multiSelect
                                    if (!multiSelect) selectedPaths = emptySet()
                                },
                            )
                            ManageRootSelector(
                                managedRoots = managedRoots,
                                currentDir = currentDir,
                                expanded = rootsExpanded,
                                onExpandedChange = { rootsExpanded = it },
                                onRootSelected = {
                                    currentDir = File(it)
                                    rootsExpanded = false
                                },
                                navRegistry = rootsRegistry,
                                modifier = Modifier.widthIn(min = 150.dp, max = 182.dp),
                            )
                            FooterActionButton(
                                label = activityString(R.string.common_ui_close),
                                modifier = Modifier.height(FooterButtonHeight),
                                onClick = onDismiss,
                            )
                        }
                        CompositionLocalProvider(LocalPaneNav provides footerRegistry) {
                            if (compactFooter) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FooterInfo(
                                        title = title,
                                        subtitle = selectedFile?.absolutePath ?: currentDir.absolutePath,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        manageFooterControls()
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FooterInfo(
                                        title = title,
                                        subtitle = selectedFile?.absolutePath ?: currentDir.absolutePath,
                                        modifier = Modifier.weight(1f),
                                    )
                                    manageFooterControls()
                                }
                            }
                        }
                        return@Column
                    }

                    val rootSelector: @Composable (Modifier) -> Unit = { modifier ->
                        RootSelector(
                            roots = roots,
                            currentDir = currentDir,
                            expanded = rootsExpanded,
                            onExpandedChange = { rootsExpanded = it },
                            onRootSelected = {
                                currentDir = it
                                rootsExpanded = false
                            },
                            navRegistry = rootsRegistry,
                            modifier = modifier,
                            extraRoots = extraRoots,
                        )
                    }
                    val footerActions: @Composable () -> Unit = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            FooterActionButton(
                                label = activityString(R.string.common_ui_cancel),
                                modifier = Modifier.height(FooterButtonHeight),
                                onClick = onDismiss,
                            )
                            FooterActionButton(
                                label = activityString(R.string.common_ui_ok),
                                modifier = Modifier.height(FooterButtonHeight),
                                onClick = {
                                    val selectedPath =
                                        if (mode == SelectionMode.FILE) {
                                            selectedFile?.absolutePath ?: return@FooterActionButton
                                        } else {
                                            currentDir.absolutePath
                                        }
                                    onSelect(selectedPath)
                                },
                            )
                        }
                    }

                    CompositionLocalProvider(LocalPaneNav provides footerRegistry) {
                    // Same problem as the manage footer: the root selector's 158 dp minimum plus
                    // the two action buttons leave the title and path nothing to occupy.
                    if (isCompactWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FooterInfo(
                                title = footerTitle,
                                subtitle = footerSubtitle,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                rootSelector(Modifier.weight(1f))
                                footerActions()
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FooterInfo(
                                title = footerTitle,
                                subtitle = footerSubtitle,
                                modifier = Modifier.weight(1f),
                            )
                            rootSelector(Modifier.widthIn(min = 158.dp, max = 182.dp))
                            footerActions()
                        }
                    }
                    }
                }
            }
            }

            if (manage) {
                CompositionLocalProvider(LocalPaneNav provides overlayRegistry) {
                renameTarget?.let { target ->
                    TextInputOverlay(
                        modifier = Modifier.matchParentSize(),
                        title = activityString(R.string.common_ui_rename),
                        initial = target.name,
                        confirmLabel = activityString(R.string.common_ui_rename),
                        onConfirm = {
                            renameFile(target, it)
                            renameTarget = null
                        },
                        onDismiss = { renameTarget = null },
                    )
                }
                if (showNewFolder) {
                    TextInputOverlay(
                        modifier = Modifier.matchParentSize(),
                        title = activityString(R.string.file_manager_new_folder),
                        initial = "",
                        confirmLabel = activityString(R.string.file_manager_create),
                        onConfirm = {
                            createFolder(it)
                            showNewFolder = false
                        },
                        onDismiss = { showNewFolder = false },
                    )
                }
                deleteTargets?.let { targets ->
                    ConfirmOverlay(
                        modifier = Modifier.matchParentSize(),
                        title = activityString(R.string.file_manager_delete),
                        message =
                            if (targets.size == 1) {
                                activityString(R.string.file_manager_delete_confirm, targets.first().name)
                            } else {
                                activityString(R.string.file_manager_delete_confirm_multi, targets.size)
                            },
                        confirmLabel = activityString(R.string.file_manager_delete),
                        onConfirm = {
                            deleteFiles(targets)
                            deleteTargets = null
                        },
                        onDismiss = { deleteTargets = null },
                    )
                }
                runTarget?.let { target ->
                    ContainerPickerOverlay(
                        modifier = Modifier.matchParentSize(),
                        containers = containers,
                        onPick = { id ->
                            onRunFile?.invoke(target.absolutePath, id)
                            runTarget = null
                        },
                        onDismiss = { runTarget = null },
                    )
                }
                transferProgress?.let { p ->
                    TransferProgressOverlay(
                        modifier = Modifier.matchParentSize(),
                        label = transferLabel,
                        progress = p,
                        onCancel = { transferJob?.cancel() },
                    )
                }
                }
            }

            if (showPathInput) {
                CompositionLocalProvider(LocalPaneNav provides overlayRegistry) {
                    TextInputOverlay(
                        modifier = Modifier.matchParentSize(),
                        title = activityString(R.string.common_ui_enter_path),
                        initial = pathInputText,
                        confirmLabel = activityString(R.string.common_ui_ok),
                        onTextChange = { pathInputText = it },
                        onConfirm = { applyTypedPath(it) },
                        onDismiss = { showPathInput = false },
                    )
                }
            }
        }
    }

    @Composable
    private fun FooterInfo(
        title: String,
        subtitle: String,
        modifier: Modifier = Modifier,
    ) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(IconBoxBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun CurrentPathCard(
        path: String,
        onClick: () -> Unit,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .paneNavItem(cornerRadius = 12.dp, onActivate = onClick)
                    .background(BgDark)
                    .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    ).padding(horizontal = CurrentPathHorizontalPadding, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = path,
                color = TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun RootSelector(
        roots: List<File>,
        currentDir: File,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onRootSelected: (File) -> Unit,
        modifier: Modifier = Modifier,
        extraRoots: List<ManagedRoot> = emptyList(),
        navRegistry: PaneNavRegistry? = null,
    ) {
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = tween(durationMillis = 180),
            label = "storageRootChevronRotation",
        )

        Box(modifier = modifier) {
            SecondaryActionChip(
                label = activityString(R.string.common_ui_storage_roots),
                icon = Icons.Outlined.Storage,
                trailing = Icons.Outlined.KeyboardArrowDown,
                trailingRotationDegrees = chevronRotation,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onExpandedChange(true) },
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                offset = DpOffset(x = 0.dp, y = (-8).dp),
                shape = RoundedCornerShape(10.dp),
                containerColor = Color(0xFF24243B),
                border = BorderStroke(1.dp, CardBorder),
                properties = PopupProperties(focusable = false),
                modifier = Modifier.widthIn(min = 220.dp, max = 420.dp),
            ) {
                @Suppress("DEPRECATION")
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalPaneNav provides navRegistry) {
                    Column(
                        modifier =
                            Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        extraRoots.forEachIndexed { index, root ->
                            val selected = isSameOrDescendant(currentDir, File(root.path))
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = root.label,
                                            color = if (selected) Accent else TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = root.path,
                                            color = TextSecondary,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                onClick = { onRootSelected(File(root.path)) },
                                modifier =
                                    Modifier
                                        .paneNavItem(
                                            cornerRadius = 6.dp,
                                            onActivate = { onRootSelected(File(root.path)) },
                                            isEntry = index == 0,
                                        ).background(
                                            if (selected) Accent.copy(alpha = 0.08f) else Color.Transparent,
                                        ),
                            )
                        }
                        roots.forEachIndexed { index, root ->
                            val selected = isSameOrDescendant(currentDir, root)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = root.absolutePath,
                                        color = if (selected) Accent else TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = { onRootSelected(root) },
                                modifier =
                                    Modifier
                                        .paneNavItem(
                                            cornerRadius = 6.dp,
                                            onActivate = { onRootSelected(root) },
                                            isEntry = extraRoots.isEmpty() && index == 0,
                                        ).background(
                                            if (selected) Accent.copy(alpha = 0.08f) else Color.Transparent,
                                        ),
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun EntryTile(
        entry: Entry,
        selected: Boolean,
        onClick: () -> Unit,
        checked: Boolean = false,
        isEntry: Boolean = false,
        onLongClick: (() -> Unit)? = null,
        onSecondary: () -> Unit = {},
        menuExpanded: Boolean = false,
        onMenuDismiss: () -> Unit = {},
        actions: List<ItemAction> = emptyList(),
        menuRegistry: PaneNavRegistry? = null,
        onHighlighted: () -> Unit = {},
    ) {
        val interaction = remember { MutableInteractionSource() }
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                selected -> Accent.copy(alpha = 0.35f)
                                entry.isParent -> Accent.copy(alpha = 0.1f)
                                else -> CardDark
                            },
                        )
                        .border(
                            width = 1.dp,
                            color =
                                when {
                                    selected -> Accent
                                    entry.isParent -> Accent.copy(alpha = 0.24f)
                                    else -> CardBorder
                                },
                            shape = RoundedCornerShape(10.dp),
                        )
                        .let { base ->
                            if (onLongClick != null) {
                                base.combinedClickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    onClick = onClick,
                                    onLongClick = onLongClick,
                                )
                            } else {
                                base.clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    onClick = onClick,
                                )
                            }
                        }
                        .paneNavItem(
                            cornerRadius = 10.dp,
                            onActivate = onClick,
                            onSecondary = onSecondary,
                            isEntry = isEntry,
                            onHighlighted = onHighlighted,
                            highlightColor = NavHighlight,
                        ).padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector =
                            when {
                                entry.isParent -> Icons.Outlined.KeyboardArrowUp
                                entry.isSelectableFile -> Icons.Outlined.Description
                                else -> Icons.Outlined.Folder
                            },
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = entry.label,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (checked) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onMenuDismiss,
                shape = RoundedCornerShape(10.dp),
                containerColor = Color(0xFF24243B),
                border = BorderStroke(1.dp, CardBorder),
                properties = PopupProperties(focusable = false),
                modifier = Modifier.widthIn(min = 180.dp, max = 240.dp),
            ) {
                @Suppress("DEPRECATION")
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalPaneNav provides menuRegistry) {
                    Column(
                        modifier =
                            Modifier
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        actions.forEachIndexed { index, action ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = action.icon,
                                            contentDescription = null,
                                            tint = Accent,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = action.label,
                                            color = TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                },
                                onClick = action.onClick,
                                modifier =
                                    Modifier.paneNavItem(
                                        cornerRadius = 6.dp,
                                        onActivate = action.onClick,
                                        isEntry = index == 0,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SecondaryActionChip(
        label: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        trailing: androidx.compose.ui.graphics.vector.ImageVector? = null,
        trailingRotationDegrees: Float = 0f,
        modifier: Modifier = Modifier,
        accent: Boolean = false,
        active: Boolean = false,
        compact: Boolean = false,
        onClick: () -> Unit,
    ) {
        val highlight = accent || active
        val chipBackground =
            when {
                active -> Accent.copy(alpha = 0.22f)
                accent -> Color.Transparent
                else -> WinNativePanel
            }
        val chipBorder = if (highlight) Accent.copy(alpha = if (active) 0.9f else 0.4f) else CardBorder
        val iconTint = if (highlight) Accent else TextSecondary
        val labelColor = if (highlight) Accent else TextPrimary
        Row(
            modifier =
                modifier
                    .clip(RoundedCornerShape(10.dp))
                    .paneNavItem(cornerRadius = 10.dp, onActivate = onClick)
                    .background(chipBackground)
                    .border(1.dp, chipBorder, RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = if (highlight) ripple(color = Accent) else null,
                        onClick = onClick,
                    ).padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 6.dp),
            horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = labelColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (compact) Modifier.weight(1f, fill = false) else Modifier,
            )
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = trailing,
                    contentDescription = null,
                    tint = iconTint,
                    modifier =
                        Modifier
                            .size(15.dp)
                            .rotate(trailingRotationDegrees),
                )
            }
        }
    }

    @Composable
    private fun FooterActionButton(
        label: String,
        modifier: Modifier = Modifier,
        tone: Color = Accent,
        onClick: () -> Unit,
    ) {
        Box(
                modifier =
                    modifier
                    .widthIn(min = 74.dp)
                    .height(FooterButtonHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .paneNavItem(cornerRadius = 10.dp, onActivate = onClick)
                    .border(1.dp, tone.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = tone),
                        onClick = onClick,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = tone,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    private fun buildEntries(
        currentDir: File,
        upLabel: String,
        mode: SelectionMode,
        allowedExtensions: Set<String>,
    ): List<Entry> {
        val entries = mutableListOf<Entry>()

        currentDir.parentFile
            ?.takeIf { canBrowse(it) }
            ?.let {
                entries += Entry(label = upLabel, target = it, isParent = true)
            }

        val children =
            currentDir
                .listFiles()
                .orEmpty()
                .asSequence()
                .sortedWith(compareBy<File>({ it.isHidden }, { it.name.lowercase(Locale.ROOT) }))
                .toList()

        children
            .filter { it.isDirectory && canBrowse(it) }
            .forEach { child ->
                entries += Entry(label = entryLabel(child), target = child)
            }

        if (mode == SelectionMode.FILE || mode == SelectionMode.MANAGE) {
            children
                .filter { it.isFile && canSelectFile(it, allowedExtensions) }
                .forEach { file ->
                    entries += Entry(
                        label = entryLabel(file),
                        target = file,
                        isSelectableFile = true,
                    )
                }
        }

        return entries
    }

    private fun uniqueChild(
        parent: File,
        name: String,
    ): File {
        var candidate = File(parent, name)
        var index = 2
        while (candidate.exists()) {
            candidate = File(parent, "$name ($index)")
            index++
        }
        return candidate
    }

    private fun treeSize(file: File): Long =
        if (file.isDirectory) {
            file.walkTopDown().filter { it.isFile }.fold(0L) { acc, f -> acc + f.length() }
        } else {
            file.length()
        }

    private fun transferRecursively(
        src: File,
        dest: File,
        isCut: Boolean,
        onProgress: (Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        if (isCut && runCatching { src.renameTo(dest) }.getOrDefault(false)) {
            onProgress(1f)
            return
        }
        val total =
            src.walkTopDown()
                .filter { it.isFile }
                .fold(0L) { acc, f -> acc + f.length() }
                .coerceAtLeast(1L)
        var copied = 0L
        var lastPercent = -1
        val buffer = ByteArray(1 shl 16)
        src.walkTopDown().forEach { f ->
            if (!isActive()) throw CancellationException()
            val target = if (f == src) dest else File(dest, f.relativeTo(src).path)
            if (f.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                f.inputStream().use { input ->
                    target.outputStream().use { output ->
                        while (true) {
                            if (!isActive()) throw CancellationException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val percent = ((copied * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress((percent / 100f).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        }
        if (isCut) src.deleteRecursively()
    }

    private fun entryLabel(file: File): String = file.name.ifBlank { file.absolutePath }

    private fun resolveInitialDirectory(
        initialPath: String?,
        roots: List<File>,
    ): File {
        val requested =
            initialPath
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.let { if (it.isDirectory) it else it.parentFile }
                ?.takeIf { canBrowse(it) }
        if (requested != null) return requested

        return roots.firstOrNull() ?: Environment.getExternalStorageDirectory()
    }

    private fun buildRootDirectories(activity: Activity): List<File> =
        StoragePathUtils.buildBrowsableStorageRoots(activity)

    private fun isSameOrDescendant(
        candidate: File,
        root: File,
    ): Boolean {
        return StoragePathUtils.isSameOrDescendant(candidate, root)
    }

    private fun canBrowse(dir: File?): Boolean = StoragePathUtils.canBrowse(dir)

    private fun isExecutable(file: File): Boolean =
        file.isFile && ExecutableExtensions.contains(file.extension.lowercase(Locale.ROOT))

    private fun canSelectFile(
        file: File,
        allowedExtensions: Set<String>,
    ): Boolean {
        if (!file.canRead()) return false
        if (allowedExtensions.isEmpty()) return true
        return allowedExtensions.contains(file.extension.lowercase(Locale.ROOT))
    }

    private fun ensureAllFilesAccess(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            return true
        }

        WinToast.show(
            activity,
            activity.getString(R.string.common_ui_grant_all_files_access_browse),
            Toast.LENGTH_LONG,
        )

        val appSpecificIntent =
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
        val appDetailsIntent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
        val allFilesIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

        if (!tryStartActivity(activity, appSpecificIntent) &&
            !tryStartActivity(activity, appDetailsIntent)
        ) {
            tryStartActivity(activity, allFilesIntent)
        }
        return false
    }

    private fun tryStartActivity(
        activity: Activity,
        intent: Intent,
    ): Boolean =
        try {
            activity.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }

    @Composable
    private fun activityString(resId: Int): String = androidx.compose.ui.res.stringResource(id = resId)

    @Composable
    private fun activityString(
        resId: Int,
        vararg formatArgs: Any,
    ): String = androidx.compose.ui.res.stringResource(id = resId, formatArgs = formatArgs)

    @Composable
    private fun activityPlural(
        resId: Int,
        quantity: Int,
    ): String = androidx.compose.ui.res.pluralStringResource(id = resId, count = quantity, quantity)

    @Composable
    private fun ManageRootSelector(
        managedRoots: List<ManagedRoot>,
        currentDir: File,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onRootSelected: (String) -> Unit,
        navRegistry: PaneNavRegistry? = null,
        modifier: Modifier = Modifier,
    ) {
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = tween(durationMillis = 180),
            label = "manageRootChevronRotation",
        )
        Box(modifier = modifier) {
            SecondaryActionChip(
                label = activityString(R.string.common_ui_storage_roots),
                icon = Icons.Outlined.Storage,
                trailing = Icons.Outlined.KeyboardArrowDown,
                trailingRotationDegrees = chevronRotation,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onExpandedChange(true) },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                offset = DpOffset(x = 0.dp, y = (-8).dp),
                shape = RoundedCornerShape(10.dp),
                containerColor = Color(0xFF24243B),
                border = BorderStroke(1.dp, CardBorder),
                properties = PopupProperties(focusable = false),
                modifier = Modifier.widthIn(min = 200.dp, max = 420.dp),
            ) {
                @Suppress("DEPRECATION")
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalPaneNav provides navRegistry) {
                    Column(
                        modifier =
                            Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        managedRoots.forEachIndexed { index, root ->
                            val selected = isSameOrDescendant(currentDir, File(root.path))
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = root.label,
                                            color = if (selected) Accent else TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = root.path,
                                            color = TextSecondary,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                onClick = { onRootSelected(root.path) },
                                modifier =
                                    Modifier
                                        .paneNavItem(
                                            cornerRadius = 6.dp,
                                            onActivate = { onRootSelected(root.path) },
                                            isEntry = index == 0,
                                        ).background(
                                            if (selected) Accent.copy(alpha = 0.08f) else Color.Transparent,
                                        ),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ContainerPickerOverlay(
        modifier: Modifier,
        containers: List<ManagedContainer>,
        onPick: (Int) -> Unit,
        onDismiss: () -> Unit,
    ) {
        Box(
            modifier =
                modifier
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(0.72f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardDark)
                        .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ).padding(16.dp),
            ) {
                Text(
                    text = activityString(R.string.file_manager_run_in_container),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                if (containers.isEmpty()) {
                    Text(
                        text = activityString(R.string.file_manager_no_containers),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        containers.forEachIndexed { index, c ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .paneNavItem(
                                            cornerRadius = 10.dp,
                                            onActivate = { onPick(c.id) },
                                            isEntry = index == 0,
                                        ).background(WinNativePanel)
                                        .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { onPick(c.id) },
                                        ).padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Storage,
                                    contentDescription = null,
                                    tint = Accent,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "${c.name} (#${c.id})",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    FooterActionButton(
                        label = activityString(R.string.common_ui_cancel),
                        modifier = Modifier.height(FooterButtonHeight),
                        onClick = onDismiss,
                    )
                }
            }
        }
    }

    @Composable
    private fun TextInputOverlay(
        modifier: Modifier,
        title: String,
        initial: String,
        confirmLabel: String,
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit,
        onTextChange: (String) -> Unit = {},
    ) {
        var text by remember { mutableStateOf(initial) }
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        Box(
            modifier =
                modifier
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(0.86f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardDark)
                        .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ).padding(16.dp),
            ) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onTextChange(it)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    FooterActionButton(
                        label = activityString(R.string.common_ui_cancel),
                        modifier = Modifier.height(FooterButtonHeight),
                        onClick = onDismiss,
                    )
                    FooterActionButton(
                        label = confirmLabel,
                        modifier = Modifier.height(FooterButtonHeight),
                        onClick = { onConfirm(text) },
                    )
                }
            }
        }
    }

    @Composable
    private fun ConfirmOverlay(
        modifier: Modifier,
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val danger = Color(0xFFFF6B6B)
        Box(
            modifier =
                modifier
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(0.86f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardDark)
                        .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ).padding(16.dp),
            ) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    FooterActionButton(
                        label = activityString(R.string.common_ui_cancel),
                        modifier = Modifier.height(FooterButtonHeight),
                        onClick = onDismiss,
                    )
                    FooterActionButton(
                        label = confirmLabel,
                        tone = danger,
                        modifier = Modifier.height(FooterButtonHeight),
                        onClick = onConfirm,
                    )
                }
            }
        }
    }

    @Composable
    private fun TransferProgressOverlay(
        modifier: Modifier,
        label: String,
        progress: Float,
        onCancel: () -> Unit,
    ) {
        Box(
            modifier =
                modifier
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(0.86f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardDark)
                        .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ).padding(16.dp),
            ) {
                Text(
                    text = label,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    color = Accent,
                    trackColor = WinNativePanel,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    FooterActionButton(
                        label = activityString(R.string.common_ui_cancel),
                        modifier = Modifier.height(FooterButtonHeight),
                        onClick = onCancel,
                    )
                }
            }
        }
    }
}
