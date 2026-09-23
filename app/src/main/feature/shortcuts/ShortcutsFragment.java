package com.winlator.cmod.feature.shortcuts;

import static androidx.core.content.ContextCompat.getSystemService;
import static com.winlator.cmod.feature.shortcuts.ShortcutsScreenKt.setupShortcutsComposeView;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.R;
import com.winlator.cmod.app.config.SettingsConfig;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.ContainerManager;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.display.XServerDisplayActivity;
import com.winlator.cmod.shared.ui.toast.WinToast;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.shared.ui.dialog.ContentDialog;
import com.winlator.cmod.shared.ui.dialog.WinNativeComposeDialogs;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public class ShortcutsFragment extends Fragment {
  public enum PinShortcutResult {
    FAILED,
    REQUESTED_NEW,
    REUSED_EXISTING
  }

  private ComposeView composeView;
  private ContainerManager manager;
  private List<Shortcut> shortcuts = Collections.emptyList();

  private final ShortcutsActionListener shortcutsActionListener =
      new ShortcutsActionListener() {
        @Override
        public void onRunShortcut(Shortcut shortcut) {
          runFromShortcut(shortcut);
        }

        @Override
        public void onEditShortcut(Shortcut shortcut) {
          try {
            new ShortcutSettingsComposeDialog(ShortcutsFragment.this, shortcut).show();
          } catch (Throwable e) {
            Log.e("ShortcutsFragment", "Error opening shortcut settings", e);
            WinToast.show(getContext(), R.string.shortcuts_list_error_opening_settings);
          }
        }

        @Override
        public void onAddToHomeScreen(Shortcut shortcut) {
          PinShortcutResult result = addShortcutToScreen(shortcut);
          if (result == PinShortcutResult.REUSED_EXISTING) {
            WinToast.show(
                requireContext(), R.string.shortcuts_list_readded_existing, shortcut.icon);
          } else {
            WinToast.show(
                requireContext(),
                result == PinShortcutResult.REQUESTED_NEW
                    ? R.string.shortcuts_list_added
                    : R.string.shortcuts_list_failed_add);
          }
        }

        @Override
        public void onRemoveShortcut(Shortcut shortcut) {
          removeShortcut(shortcut);
        }

        @Override
        public void onExportShortcut(Shortcut shortcut) {
          exportShortcut(shortcut);
        }

        @Override
        public void onCloneShortcut(Shortcut shortcut) {
          cloneShortcut(shortcut);
        }

        @Override
        public void onShowProperties(Shortcut shortcut) {
          showShortcutProperties(shortcut);
        }
      };

  @Nullable @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    composeView = new ComposeView(inflater.getContext());
    renderShortcuts();
    return composeView;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    manager = new ContainerManager(requireContext());
    loadShortcutsList();
    if (getActivity() != null
        && ((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
      ((AppCompatActivity) getActivity())
          .getSupportActionBar()
          .setTitle(R.string.common_ui_shortcuts);
    }
  }

  private void renderShortcuts() {
    if (composeView == null) return;
    setupShortcutsComposeView(composeView, shortcuts, shortcutsActionListener);
  }

  public void loadShortcutsList() {
    if (manager == null && getContext() != null) {
      manager = new ContainerManager(getContext());
    }
    if (manager == null) return;

    ArrayList<Shortcut> loadedShortcuts = manager.loadShortcuts();
    loadedShortcuts.removeIf(
        shortcut -> shortcut == null || shortcut.file == null || shortcut.file.getName().isEmpty());
    shortcuts = loadedShortcuts;
    renderShortcuts();
  }

  private void removeShortcut(Shortcut shortcut) {
    Context context = getContext();
    if (context == null) return;

    ContentDialog.confirm(
        context,
        R.string.shortcuts_list_confirm_remove,
        () -> {
          boolean fileDeleted = shortcut.file.delete();
          File lnkFile =
              new File(
                  shortcut.file.getPath().substring(0, shortcut.file.getPath().lastIndexOf("."))
                      + ".lnk");
          if (lnkFile.exists()) {
            lnkFile.delete();
          }

          if (fileDeleted) {
            com.winlator.cmod.runtime.linux.LinuxSteamShortcuts.removed(requireContext(), shortcut);
            disableShortcutOnScreen(requireContext(), shortcut);
            loadShortcutsList();
            WinToast.show(context, R.string.shortcuts_list_removed);
          } else {
            WinToast.show(context, R.string.shortcuts_list_remove_failed);
          }
        });
  }

  private void cloneShortcut(Shortcut shortcut) {
    Context context = getContext();
    if (context == null) return;

    ContainerManager containerManager = new ContainerManager(context);
    ArrayList<Container> containers = containerManager.getContainers();
    showContainerSelectionDialog(
        containers,
        selectedContainer -> {
          if (shortcut.cloneToContainer(selectedContainer)) {
            WinToast.show(context, R.string.shortcuts_list_cloned);
            loadShortcutsList();
          } else {
            WinToast.show(context, R.string.shortcuts_list_clone_failed);
          }
        });
  }

  private interface OnContainerSelectedListener {
    void onContainerSelected(Container container);
  }

  private void showContainerSelectionDialog(
      ArrayList<Container> containers, OnContainerSelectedListener listener) {
    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle(R.string.shortcuts_list_select_a_container);

    String[] containerNames = new String[containers.size()];
    for (int i = 0; i < containers.size(); i++) {
      containerNames[i] = containers.get(i).getName();
    }

    builder.setItems(
        containerNames, (dialog, which) -> listener.onContainerSelected(containers.get(which)));
    builder.show();
  }

  private void runFromShortcut(Shortcut shortcut) {
    Activity activity = getActivity();
    if (activity == null) return;

    if (com.winlator.cmod.feature.retro.RetroShortcuts.isRetroShortcut(shortcut)) {
      com.winlator.cmod.feature.retro.RetroShortcuts.launch(activity, shortcut);
      return;
    }

    Intent intent = new Intent(activity, XServerDisplayActivity.class);
    intent.putExtra("container_id", shortcut.container.id);
    intent.putExtra("shortcut_path", shortcut.file.getPath());
    intent.putExtra("shortcut_name", shortcut.name);
    intent.putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"));
    activity.startActivity(intent);
  }

  private void exportShortcut(Shortcut shortcut) {
    File exportFile = FrontendExporter.exportOne(getContext(), shortcut);
    if (exportFile != null) {
      WinToast.show(
          getContext(), getString(R.string.shortcuts_list_exported_to, exportFile.getPath()));
    } else {
      WinToast.show(getContext(), R.string.shortcuts_list_failed_export);
    }
  }

  private void showShortcutProperties(Shortcut shortcut) {
    Context context = getContext();
    if (context == null) return;

    SharedPreferences playtimePrefs =
        context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE);
    String playtimeKey = shortcut.name + "_playtime";
    String playCountKey = shortcut.name + "_play_count";

    long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0);
    int playCount = playtimePrefs.getInt(playCountKey, 0);

    long seconds = (totalPlaytime / 1000) % 60;
    long minutes = (totalPlaytime / (1000 * 60)) % 60;
    long hours = (totalPlaytime / (1000 * 60 * 60)) % 24;
    long days = (totalPlaytime / (1000 * 60 * 60 * 24));
    String playtimeFormatted =
        String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);

    String playCountText = getString(R.string.library_games_times_played_label) + playCount;
    String playtimeText = getString(R.string.library_games_playtime_label) + playtimeFormatted;

    if (WinNativeComposeDialogs.showShortcutProperties(
        context,
        playCountText,
        playtimeText,
        () -> {
          playtimePrefs.edit().remove(playtimeKey).remove(playCountKey).apply();
          WinToast.show(getContext(), R.string.shortcuts_properties_properties_reset);
        })) {
      return;
    }
  }

  public static ArrayList<String> buildPinnedShortcutIds(
      int containerId, String uuid, String shortcutPath) {
    LinkedHashSet<String> shortcutIds = new LinkedHashSet<>();
    if (uuid != null && !uuid.isEmpty()) {
      shortcutIds.add(uuid); // Legacy pinned shortcut id.
      if (shortcutPath != null && !shortcutPath.isEmpty() && containerId > 0) {
        int shortcutPathHash = shortcutPath.hashCode();
        shortcutIds.add(
            "shortcut_"
                + containerId
                + "_"
                + uuid
                + "_"
                + Integer.toUnsignedString(shortcutPathHash, 16));
      }
    }
    return new ArrayList<>(shortcutIds);
  }

  public static Intent buildShortcutLaunchIntent(
      Context context, int containerId, String shortcutPath, String shortcutName, String uuid) {
    Intent intent = new Intent(context, XServerDisplayActivity.class);
    intent.setAction(Intent.ACTION_VIEW);
    if (shortcutPath != null && !shortcutPath.isEmpty()) {
      int shortcutPathHash = shortcutPath.hashCode();
      Uri launchData =
          new Uri.Builder()
              .scheme("winnative")
              .authority(BuildConfig.APPLICATION_ID)
              .appendPath("shortcut")
              .appendQueryParameter("uuid", uuid)
              .appendQueryParameter("container", String.valueOf(containerId))
              .appendQueryParameter("hash", String.valueOf(shortcutPathHash))
              .build();
      intent.setData(launchData);
      intent.putExtra("shortcut_path_hash", shortcutPathHash);
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    intent.putExtra("container_id", containerId);
    intent.putExtra("shortcut_path", shortcutPath);
    intent.putExtra("shortcut_name", shortcutName);
    intent.putExtra("shortcut_uuid", uuid);
    intent.putExtra(XServerDisplayActivity.EXTRA_LAUNCHED_FROM_PINNED_SHORTCUT, true);
    return intent;
  }

  private ShortcutInfo buildScreenShortCut(
      String shortcutId, String shortLabel, String longLabel, Intent intent, Icon icon) {
    Context context = requireContext();

    return new ShortcutInfo.Builder(context, shortcutId)
        .setShortLabel(shortLabel)
        .setLongLabel(longLabel)
        .setIcon(icon)
        .setIntent(intent)
        .build();
  }

  public static PinShortcutResult pinOrUpdateShortcut(
      ShortcutManager shortcutManager,
      ShortcutInfo shortcutInfo,
      List<String> shortcutIds,
      @Nullable IntentSender callback) {
    if (shortcutManager == null
        || shortcutInfo == null
        || shortcutIds == null
        || shortcutIds.isEmpty()) return PinShortcutResult.FAILED;

    try {
      for (ShortcutInfo pinnedShortcut : shortcutManager.getPinnedShortcuts()) {
        if (!shortcutIds.contains(pinnedShortcut.getId())) continue;

        shortcutManager.updateShortcuts(Collections.singletonList(shortcutInfo));
        try {
          shortcutManager.enableShortcuts(Collections.singletonList(pinnedShortcut.getId()));
        } catch (Exception ignored) {
        }
        return PinShortcutResult.REUSED_EXISTING;
      }
    } catch (Exception ignored) {
    }

    try {
      return shortcutManager.requestPinShortcut(shortcutInfo, callback)
          ? PinShortcutResult.REQUESTED_NEW
          : PinShortcutResult.FAILED;
    } catch (IllegalArgumentException e) {
      try {
        shortcutManager.updateShortcuts(Collections.singletonList(shortcutInfo));
        shortcutManager.enableShortcuts(Collections.singletonList(shortcutInfo.getId()));
        return PinShortcutResult.REUSED_EXISTING;
      } catch (Exception ignored) {
      }
    } catch (Exception ignored) {
    }

    return PinShortcutResult.FAILED;
  }

  public PinShortcutResult addShortcutToScreen(Shortcut shortcut) {
    if (shortcut == null) return PinShortcutResult.FAILED;
    if (shortcut.getExtra("uuid").equals("")) {
      shortcut.genUUID();
    }
    String shortcutUuid = shortcut.getExtra("uuid");
    String shortcutPath = shortcut.file != null ? shortcut.file.getAbsolutePath() : "";
    ArrayList<String> shortcutIds =
        buildPinnedShortcutIds(shortcut.container.id, shortcutUuid, shortcutPath);
    if (shortcutIds.isEmpty()) return PinShortcutResult.FAILED;

    ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
    if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported())
      return PinShortcutResult.FAILED;

    Bitmap shortcutBitmap = resolveShortcutIcon(shortcut);
    Icon shortcutIcon =
        shortcutBitmap != null
            ? Icon.createWithBitmap(shortcutBitmap)
            : Icon.createWithResource(requireContext(), R.drawable.icon_shortcut);

    return pinOrUpdateShortcut(
        shortcutManager,
        buildScreenShortCut(
            shortcutIds.get(shortcutIds.size() - 1),
            shortcut.name,
            shortcut.name,
            buildShortcutLaunchIntent(
                requireContext(), shortcut.container.id, shortcutPath, shortcut.name, shortcutUuid),
            shortcutIcon),
        shortcutIds,
        null);
  }

  public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
    ShortcutManager shortcutManager = getSystemService(context, ShortcutManager.class);
    if (shortcutManager == null
        || shortcut == null
        || shortcut.container == null
        || shortcut.file == null) return;

    ArrayList<String> shortcutIds =
        buildPinnedShortcutIds(
            shortcut.container.id, shortcut.getExtra("uuid"), shortcut.file.getAbsolutePath());
    if (shortcutIds.isEmpty()) return;

    try {
      shortcutManager.disableShortcuts(
          shortcutIds, context.getString(R.string.shortcuts_list_not_available));
    } catch (Exception ignored) {
    }

    try {
      shortcutManager.removeDynamicShortcuts(shortcutIds);
    } catch (Exception ignored) {
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        shortcutManager.removeLongLivedShortcuts(shortcutIds);
      } catch (Exception ignored) {
      }
    }
  }

  private Bitmap resolveShortcutIcon(Shortcut shortcut) {
    if (shortcut == null) return null;

    File customIconFile = LibraryShortcutArtwork.findPreferredHomeIconFile(requireContext(), shortcut);
    if (customIconFile != null) {
      Bitmap bitmap = BitmapFactory.decodeFile(customIconFile.getAbsolutePath());
      if (bitmap != null) return bitmap;
    }

    if (shortcut.getCoverArt() != null) return shortcut.getCoverArt();
    return shortcut.icon;
  }

  public void updateShortcutOnScreen(
      String shortLabel,
      String longLabel,
      int containerId,
      String shortcutPath,
      Icon icon,
      String uuid) {
    ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
    if (shortcutManager == null) return;

    ArrayList<String> shortcutIds = buildPinnedShortcutIds(containerId, uuid, shortcutPath);
    if (shortcutIds.isEmpty()) return;

    try {
      for (ShortcutInfo shortcutInfo : shortcutManager.getPinnedShortcuts()) {
        if (shortcutIds.contains(shortcutInfo.getId())) {
          shortcutManager.updateShortcuts(
              Collections.singletonList(
                  buildScreenShortCut(
                      shortcutInfo.getId(),
                      shortLabel,
                      longLabel,
                      buildShortcutLaunchIntent(
                          requireContext(), containerId, shortcutPath, shortLabel, uuid),
                      icon)));
          break;
        }
      }
    } catch (Exception ignored) {
    }
  }
}
