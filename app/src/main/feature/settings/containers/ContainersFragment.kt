package com.winlator.cmod.feature.settings
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.winlator.cmod.R
import com.winlator.cmod.app.shell.UnifiedActivity
import com.winlator.cmod.feature.settings.ContainerSettingsComposeDialog
import com.winlator.cmod.feature.shortcuts.ShortcutsFragment
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerCreation
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.ui.dialog.ContainerProgressPopup
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt

class ContainersFragment : Fragment() {
    private lateinit var manager: ContainerManager

    private var screenState by mutableStateOf(ContainersScreenState())
    private var storageScanToken = 0L

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
                    ContainersScreen(
                        state = screenState,
                        onAddContainer = ::openAddContainer,
                        onAddGamescope = ::addGamescopeContainer,
                        onRunContainer = ::runContainer,
                        onEditContainer = ::editContainer,
                        onDuplicateContainer = ::duplicateContainer,
                        onInstallComponents = ::openComponentInstaller,
                        onRemoveContainer = ::removeContainer,
                        onShowInfo = ::showContainerInfo,
                        onDismissDialog = ::dismissDialog,
                        onConfirmDuplicateDialog = ::performDuplicateContainer,
                        onConfirmRemoveDialog = ::performRemoveContainer,
                        onClearCacheDialog = ::clearContainerCache,
                        bridge = (requireActivity() as? UnifiedActivity)?.settingsNavBridge,
                    )
                }
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.common_ui_containers)
        loadContainersList()
    }

    override fun onResume() {
        super.onResume()
        loadContainersList()
    }

    private fun loadContainersList() {
        val context = context ?: return
        manager = ContainerManager(context)
        val all = manager.containers.toList()
        screenState =
            screenState.copy(
                containers = all.filter { !it.isGamescopeRuntime },
                gamescope = all.firstOrNull { it.isGamescopeRuntime },
            )
    }

    private fun addGamescopeContainer() {
        val context = context ?: return
        if (!ImageFs.find(context).isUpToDate) {
            WinToast.show(context, R.string.setup_wizard_system_image_not_installed, Toast.LENGTH_LONG)
            return
        }
        val contentsManager = ContentsManager(context)
        contentsManager.syncContents()
        val runtime = ContainerCreation.newestInstalledRuntime(contentsManager)
        if (runtime == null) {
            WinToast.show(context, R.string.container_no_wine_installed, Toast.LENGTH_LONG)
            return
        }
        val creatingPopup = ContainerProgressPopup(requireActivity(), R.string.containers_list_creating)
        creatingPopup.show()
        ContainerCreation.createGamescopeContainerAsync(context, manager, contentsManager, runtime) { created ->
            creatingPopup.close()
            if (!isAdded) return@createGamescopeContainerAsync
            if (created == null) {
                WinToast.show(context, R.string.containers_gamescope_create_failed, Toast.LENGTH_LONG)
            }
            loadContainersList()
        }
    }

    private fun openAddContainer() {
        val context = context ?: return
        if (!ImageFs.find(context).isUpToDate) {
            WinToast.show(context, R.string.setup_wizard_system_image_not_installed, Toast.LENGTH_LONG)
            return
        }

        Thread {
            val ctx = context ?: return@Thread
            val installed = ContentsManager.hasInstalledRuntimes(ctx)
            if (!isAdded) return@Thread

            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (!installed) {
                    WinToast.show(ctx, R.string.container_no_wine_installed, Toast.LENGTH_LONG)
                    return@runOnUiThread
                }
                ContainerSettingsComposeDialog(requireActivity(), null, ::loadContainersList).show()
            }
        }.start()
    }

    private fun runContainer(container: Container) {
        val ctx = context ?: return
        startActivity(
            Intent(ctx, XServerDisplayActivity::class.java).apply {
                putExtra("container_id", container.id)
            },
        )
    }

    private fun editContainer(container: Container) {
        ContainerSettingsComposeDialog(requireActivity(), container, ::loadContainersList).show()
    }

    private fun duplicateContainer(container: Container) {
        screenState = screenState.copy(dialog = ContainersDialogUiState.ConfirmDuplicate(container))
    }

    private fun openComponentInstaller(container: Container) {
        screenState = screenState.copy(dialog = ContainersDialogUiState.ComponentInstaller(container))
    }

    private fun removeContainer(container: Container) {
        screenState = screenState.copy(dialog = ContainersDialogUiState.ConfirmRemove(container))
    }

    private fun showContainerInfo(container: Container) {
        screenState =
            screenState.copy(
                dialog =
                    ContainersDialogUiState.StorageInfo(
                        ContainerStorageInfoUiState(container = container),
                    ),
            )
        startStorageScan(container)
    }

    private fun performDuplicateContainer(container: Container) {
        dismissDialog()
        val duplicatingPopup = ContainerProgressPopup(
            requireActivity(),
            R.string.containers_list_duplicating,
            indeterminate = false,
        )
        duplicatingPopup.show()
        manager.duplicateContainerAsync(container, { progress ->
            duplicatingPopup.setProgress(progress)
        }) { success ->
            if (success) {
                duplicatingPopup.setProgress(100)
                duplicatingPopup.closeWithDelay(600)
                Handler(Looper.getMainLooper()).postDelayed({
                    loadContainersList()
                }, 600)
            } else {
                duplicatingPopup.close()
                context?.let {
                    WinToast.show(it, R.string.containers_list_duplicate_failed, Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun performRemoveContainer(container: Container) {
        val ctx = context ?: return
        dismissDialog()
        val removingPopup =
            ContainerProgressPopup(requireActivity(), R.string.containers_list_removing)
        removingPopup.show()
        for (shortcut in manager.loadShortcuts()) {
            if (shortcut.container == container) {
                ShortcutsFragment.disableShortcutOnScreen(ctx, shortcut)
            }
        }
        manager.removeContainerAsync(container) {
            removingPopup.close()
            loadContainersList()
        }
    }

    private fun clearContainerCache(container: Container) {
        val cacheDir = File(container.rootDir, ".cache")
        Thread {
            FileUtils.clear(cacheDir)
            container.putExtra("desktopTheme", null)
            container.saveData()
            if (!isAdded) return@Thread
            Handler(Looper.getMainLooper()).post {
                if (!isAdded) return@post
                showContainerInfo(container)
            }
        }.start()
    }

    private fun dismissDialog() {
        storageScanToken += 1L
        screenState = screenState.copy(dialog = ContainersDialogUiState.None)
    }

    private fun startStorageScan(container: Container) {
        val token = System.nanoTime()
        storageScanToken = token
        Thread {
            val driveCBytes = calculateDirectorySize(File(container.rootDir, ".wine/drive_c"))
            val cacheBytes = calculateDirectorySize(File(container.rootDir, ".cache"))
            val totalBytes = driveCBytes + cacheBytes
            val internalStorage = FileUtils.getInternalStorageSize().coerceAtLeast(1L)
            val usedPercent =
                ((totalBytes.toDouble() / internalStorage.toDouble()) * 100.0)
                    .toFloat()
                    .coerceIn(0f, 100f)

            if (!isAdded) return@Thread
            Handler(Looper.getMainLooper()).post {
                if (!isAdded || storageScanToken != token) return@post
                val dialog = screenState.dialog as? ContainersDialogUiState.StorageInfo ?: return@post
                if (dialog.data.container.id != container.id) return@post
                screenState =
                    screenState.copy(
                        dialog =
                            ContainersDialogUiState.StorageInfo(
                                dialog.data.copy(
                                    driveCBytes = driveCBytes,
                                    cacheBytes = cacheBytes,
                                    totalBytes = totalBytes,
                                    usedPercent = usedPercent,
                                    isLoading = false,
                                ),
                            ),
                    )
            }
        }.start()
    }

    private fun calculateDirectorySize(root: File?): Long {
        if (root == null || !root.exists()) return 0L
        if (root.isFile) return root.length()

        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val files = current.listFiles() ?: continue
            for (file in files) {
                if (Files.isSymbolicLink(file.toPath())) continue
                if (file.isDirectory) {
                    stack.add(file)
                } else {
                    total += file.length()
                }
            }
        }
        return total
    }
}
