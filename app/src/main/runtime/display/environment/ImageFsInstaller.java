package com.winlator.cmod.runtime.display.environment;

import android.content.Context;
import android.util.Log;
import com.winlator.cmod.R;
import com.winlator.cmod.app.config.SettingsConfig;
import com.winlator.cmod.feature.stores.steam.enums.Marker;
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils;
import com.winlator.cmod.runtime.compat.SteamBridge;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.ContainerManager;
import com.winlator.cmod.runtime.content.AdrenotoolsManager;
import com.winlator.cmod.runtime.wine.WineInfo;
import com.winlator.cmod.shared.android.AppUtils;
import com.winlator.cmod.shared.android.HostPlatform;
import com.winlator.cmod.shared.ui.toast.WinToast;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.shared.io.TarCompressorUtils;
import com.winlator.cmod.shared.ui.dialog.DownloadProgressDialog;
import com.winlator.cmod.shared.util.OnExtractFileListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ImageFsInstaller {
  public static final byte LATEST_VERSION = 22;
  private static final String IMAGEFS_ARCHIVE = "imagefs.tzst";
  private static final String IMAGEFS_ARCHIVE_X86_64 = "imagefs-x86_64.tzst";
  private static final TarCompressorUtils.Type IMAGEFS_ARCHIVE_TYPE = TarCompressorUtils.Type.ZSTD;
  private static final long IMAGEFS_EXTRACTED_BYTES = 869024992L;
  private static final int XZ_PROGRESS_COMPRESSION_RATIO = 22;
  private static final long DEFAULT_WINE_EXTRACTED_BYTES = 100000000L;
  private static final long DEFAULT_GUEST_EXTRAS_EXTRACTED_BYTES = 30000000L;
  private static final long DRIVER_INSTALL_PROGRESS_BYTES = 25000000L;
  private static final long FINALIZE_PROGRESS_BYTES = 5000000L;
  private static final long PROGRESS_STEP_DELAY_MS = 10L;

  /**
   * Progress callback for installing ImageFS from assets. Lets callers drive a custom UI (e.g.
   * Jetpack Compose) instead of the legacy dialog. All callbacks are invoked on a background
   * thread; marshal to UI thread as needed.
   */
  public interface ProgressListener {
    void onProgress(int percent);

    void onFinished(boolean success);
  }

  private static void resetContainerImgVersions(Context context) {
    ContainerManager manager = new ContainerManager(context);
    for (Container container : manager.getContainers()) {
      String imgVersion = container.getExtra("imgVersion");
      String wineVersion = container.getWineVersion();
      if (!imgVersion.isEmpty()
          && WineInfo.isMainWineVersion(wineVersion)
          && Short.parseShort(imgVersion) <= 5) {
        container.putExtra("wineprefixNeedsUpdate", "t");
      }

      container.putExtra("imgVersion", null);
      container.saveData();
    }
  }

  public static void installWineFromAssets(final android.app.Activity activity) {
    installWineFromAssetsAsync(activity, null);
  }

  private static boolean installWineFromAssetsAsync(
      final android.app.Activity activity, OnExtractFileListener listener) {
    String[] versions = activity.getResources().getStringArray(R.array.wine_entries);
    File rootDir = ImageFs.find(activity).getRootDir();
    List<Future<Boolean>> futures = new ArrayList<>();
    for (String version : versions) {
      File outFile = new File(rootDir, "/opt/" + version);
      outFile.mkdirs();
      futures.add(
          TarCompressorUtils.extractAsync(
              TarCompressorUtils.Type.XZ, activity, version + ".txz", outFile, listener));
    }
    return waitForExtractions(futures);
  }

  private static final class InstallProgressTracker {
    private final ProgressListener listener;
    private final long totalWorkBytes;
    private final AtomicLong completedBytes = new AtomicLong();
    private int lastPercent = -1;

    InstallProgressTracker(long totalWorkBytes, ProgressListener listener) {
      this.totalWorkBytes = Math.max(1L, totalWorkBytes);
      this.listener = listener;
    }

    void start() {
      emit(0L, false);
    }

    OnExtractFileListener asExtractListener() {
      return new OnExtractFileListener() {
        @Override
        public File onExtractFile(File file, long size) {
          return file;
        }

        @Override
        public void onExtractFileProgress(File file, long size) {
          addWork(size);
        }

        @Override
        public boolean mapsExtractedFiles() {
          return false;
        }

        @Override
        public boolean reportsExtractedBytesOnly() {
          return true;
        }

        @Override
        public void onExtractedBytes(long size) {
          addWork(size);
        }
      };
    }

    void addWork(long bytes) {
      if (bytes <= 0) return;
      emit(completedBytes.addAndGet(bytes), false);
    }

    void finish() {
      emit(totalWorkBytes, true);
    }

    private synchronized void emit(long completed, boolean complete) {
      if (listener == null) return;
      int maxPercent = complete ? 100 : 99;
      int percent =
          (int) Math.min(maxPercent, Math.floor((completed * 100.0) / totalWorkBytes));
      if (percent <= lastPercent) return;
      for (int nextPercent = lastPercent + 1; nextPercent <= percent; nextPercent++) {
        lastPercent = nextPercent;
        listener.onProgress(nextPercent);
        if (nextPercent < percent) {
          try {
            Thread.sleep(PROGRESS_STEP_DELAY_MS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
    }
  }

  private static long estimateXzExtractedBytes(Context context, String assetFile, long fallback) {
    long compressedSize = FileUtils.getSize(context, assetFile);
    if (compressedSize <= 0) return fallback;
    return Math.max(1L, (compressedSize * 100L) / XZ_PROGRESS_COMPRESSION_RATIO);
  }

  private static long estimateZstdExtractedBytes(Context context, String assetFile, long fallback) {
    long compressedSize = FileUtils.getSize(context, assetFile);
    if (compressedSize <= 0) return fallback;
    return Math.max(compressedSize, compressedSize * 4L);
  }

  private static long estimateOptionalZstdExtractedBytes(Context context, String assetFile) {
    return FileUtils.getSize(context, assetFile) > 0
        ? estimateZstdExtractedBytes(context, assetFile, 0L)
        : 0L;
  }

  private static long estimateWineAssetsExtractedBytes(Context context) {
    long total = 0L;
    String[] versions = context.getResources().getStringArray(R.array.wine_entries);
    for (String version : versions) {
      total += estimateXzExtractedBytes(context, version + ".txz", DEFAULT_WINE_EXTRACTED_BYTES);
    }
    return total;
  }

  private static long estimateGuestExtrasExtractedBytes(Context context) {
    return estimateOptionalZstdExtractedBytes(context, "redirect.tzst")
        + estimateZstdExtractedBytes(context, "extras.tzst", DEFAULT_GUEST_EXTRAS_EXTRACTED_BYTES);
  }

  private static long estimateInstallWorkBytes(Context context, boolean includeDrivers) {
    return IMAGEFS_EXTRACTED_BYTES
        + estimateWineAssetsExtractedBytes(context)
        + estimateGuestExtrasExtractedBytes(context)
        + (includeDrivers ? DRIVER_INSTALL_PROGRESS_BYTES : 0L)
        + FINALIZE_PROGRESS_BYTES;
  }

  public static void installDriversFromAssets(final android.app.Activity activity) {
    AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(activity);
    String[] adrenotoolsAssetDrivers =
        activity.getResources().getStringArray(R.array.wrapper_graphics_driver_version_entries);

    for (String driver : adrenotoolsAssetDrivers)
      adrenotoolsManager.extractDriverFromResources(driver);
  }

  public static void installFromAssets(final android.app.Activity activity) {
    final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
    dialog.show(R.string.setup_wizard_installing_system_files);
    installFromAssets(
        activity,
        new ProgressListener() {
          @Override
          public void onProgress(int percent) {
            activity.runOnUiThread(() -> dialog.setProgress(percent));
          }

          @Override
          public void onFinished(boolean success) {
            activity.runOnUiThread(
                () -> {
                  if (success) {
                    activity.getWindow().getDecorView().postDelayed(dialog::close, 500L);
                  } else {
                    dialog.close();
                  }
                });
          }
        });
  }

  public static void installFromAssets(
      final android.app.Activity activity, final ProgressListener listener) {
    AppUtils.keepScreenOn(activity);
    ImageFs imageFs = ImageFs.find(activity);
    File rootDir = imageFs.getRootDir();
    InstallProgressTracker progressTracker =
        new InstallProgressTracker(estimateInstallWorkBytes(activity, true), listener);

    SettingsConfig.resetEmulatorsVersion(activity);

    Executors.newSingleThreadExecutor()
        .execute(
            () -> {
              progressTracker.start();
              clearRootDir(rootDir);

              boolean success =
                  extractImageFs(activity, rootDir, progressTracker.asExtractListener());

              if (success) {
                ExecutorService pool = Executors.newFixedThreadPool(3);
                CountDownLatch latch = new CountDownLatch(3);
                AtomicBoolean postInstallSuccess = new AtomicBoolean(true);
                pool.execute(
                    () -> {
                      try {
                        postInstallSuccess.compareAndSet(
                            true,
                            installWineFromAssetsAsync(activity, progressTracker.asExtractListener()));
                      } finally {
                        latch.countDown();
                      }
                    });
                pool.execute(
                    () -> {
                      try {
                        installDriversFromAssets(activity);
                      } finally {
                        progressTracker.addWork(DRIVER_INSTALL_PROGRESS_BYTES);
                        latch.countDown();
                      }
                    });
                pool.execute(
                    () -> {
                      try {
                        installGuestExtras(activity, rootDir, progressTracker.asExtractListener());
                      } finally {
                        latch.countDown();
                      }
                    });
                try {
                  latch.await();
                } catch (InterruptedException ignored) {
                }
                pool.shutdown();
                success = postInstallSuccess.get();
                if (success) {
                  clearSteamDllMarkers(activity);
                  imageFs.createImgVersionFile(LATEST_VERSION);
                  resetContainerImgVersions(activity);
                  progressTracker.addWork(FINALIZE_PROGRESS_BYTES);
                  progressTracker.finish();
                }
              } else {
                activity.runOnUiThread(
                    () -> WinToast.show(activity, R.string.setup_wizard_unable_to_install_system_files));
              }

              if (listener != null) listener.onFinished(success);
            });
  }

  public static void installIfNeeded(final android.app.Activity activity) {
    ImageFs imageFs = ImageFs.find(activity);
    if (!imageFs.isUpToDate()) installFromAssets(activity);
  }

  /**
   * Version that works from any Activity (e.g. UnifiedActivity). Shows a simple progress dialog and
   * installs ImageFS from assets if needed.
   */
  public static void installIfNeededFromAny(final android.app.Activity activity) {
    ImageFs imageFs = ImageFs.find(activity);
    if (!imageFs.isUpToDate()) installFromAssets(activity);
  }

  private static void clearOptDir(File optDir) {
    File[] files = optDir.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.getName().equals("installed-wine")) continue;
        FileUtils.delete(file);
      }
    }
  }

  private static void clearRootDir(File rootDir) {
    if (rootDir.isDirectory()) {
      File[] files = rootDir.listFiles();
      if (files != null) {
        for (File file : files) {
          if (file.isDirectory()) {
            String name = file.getName();
            if (name.equals("home")) {
              continue;
            }
          }
          FileUtils.delete(file);
        }
      }
    } else rootDir.mkdirs();
  }

  private static void installGuestExtras(Context context, File rootDir) {
    installGuestExtras(context, rootDir, null);
  }

  private static void installGuestExtras(
      Context context, File rootDir, OnExtractFileListener listener) {
    try {
      TarCompressorUtils.extract(
          TarCompressorUtils.Type.ZSTD, context, "redirect.tzst", rootDir, listener);
    } catch (Exception e) {
      Log.w(
          "ImageFsInstaller",
          "redirect.tzst not found or failed to extract; continuing without redirect libs");
    }

    try {
      TarCompressorUtils.extract(
          TarCompressorUtils.Type.ZSTD, context, "extras.tzst", rootDir, listener);
    } catch (Exception e) {
      Log.w(
          "ImageFsInstaller",
          "extras.tzst not found or failed to extract; Steamless assets may be missing");
      return;
    }

    chmodIfExists(new File(rootDir, "generate_interfaces_file.exe"));
    chmodIfExists(new File(rootDir, "Steamless/Steamless.CLI.exe"));
    // chmod any Mono MSI that was bundled in extras.tzst
    File monoDir = new File(rootDir, "opt/mono-gecko-offline");
    if (monoDir.isDirectory()) {
      File[] msiFiles = monoDir.listFiles();
      if (msiFiles != null) {
        for (File msi : msiFiles) {
          if (msi.getName().startsWith("wine-mono-") && msi.getName().endsWith("-x86.msi")) {
            chmodIfExists(msi);
          }
        }
      }
    }
    chmodIfExists(new File(rootDir, "usr/lib/libredirect.so"));
    chmodIfExists(new File(rootDir, "usr/lib/libredirect-bionic.so"));
  }

  private static void chmodIfExists(File file) {
    if (file.exists()) {
      FileUtils.chmod(file, 0755);
    }
  }

  private static boolean waitForExtractions(List<Future<Boolean>> futures) {
    boolean success = true;
    for (Future<Boolean> future : futures) {
      success &= waitForExtraction(future);
    }
    return success;
  }

  private static boolean waitForExtraction(Future<Boolean> future) {
    try {
      return Boolean.TRUE.equals(future.get());
    } catch (Exception e) {
      Log.e("ImageFsInstaller", "Async extraction failed", e);
      return false;
    }
  }

  /**
   * arm64 keeps its historical asset names. x86_64 ships its own image, because the rootfs holds
   * native binaries and an arm64 one is useless there.
   */
  private static String imageFsArchive() {
    return HostPlatform.isX86_64() ? IMAGEFS_ARCHIVE_X86_64 : IMAGEFS_ARCHIVE;
  }

  private static String imageFsShardPrefix() {
    return HostPlatform.isX86_64() ? "imagefs-x86_64.part" : "imagefs.part";
  }

  private static boolean assetExists(Context context, String name) {
    try (java.io.InputStream ignored = context.getAssets().open(name)) {
      return true;
    } catch (java.io.IOException e) {
      return false;
    }
  }

  private static boolean extractImageFs(
      android.app.Activity activity, File rootDir, OnExtractFileListener listener) {
    String[] shards = listImageFsShards(activity);
    if (shards.length == 0) {
      if (HostPlatform.isX86_64() && !assetExists(activity, imageFsArchive())) {
        Log.e(
            "ImageFsInstaller",
            "No runtime image for "
                + HostPlatform.processAbi()
                + ": asset '"
                + imageFsArchive()
                + "' is not packaged in this build. See docs/X86_64-WSA.md.");
        return false;
      }
      return waitForExtraction(
          TarCompressorUtils.extractAsync(
              IMAGEFS_ARCHIVE_TYPE, activity, imageFsArchive(), rootDir, listener));
    }
    int threads = Math.max(2, Math.min(shards.length, Runtime.getRuntime().availableProcessors()));
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (String shard : shards) {
        futures.add(
            pool.submit(
                () ->
                    TarCompressorUtils.extract(
                        IMAGEFS_ARCHIVE_TYPE, activity, shard, rootDir, listener)));
      }
      return waitForExtractions(futures);
    } finally {
      pool.shutdown();
    }
  }

  private static String[] listImageFsShards(Context context) {
    List<String> shards = new ArrayList<>();
    try {
      String[] names = context.getAssets().list("");
      if (names != null) {
        for (String name : names) {
          if (name.startsWith(imageFsShardPrefix()) && name.endsWith(".tzst")) shards.add(name);
        }
      }
    } catch (Exception e) {
      Log.e("ImageFsInstaller", "Failed to list imagefs shards", e);
    }
    return shards.toArray(new String[0]);
  }

  /**
   * Remove Steam DLL state markers after reinstalling ImageFS so future launches re-apply
   * replacements when needed.
   */
  private static void clearSteamDllMarkers(Context context) {
    try {
      ContainerManager manager = new ContainerManager(context);
      for (Container container : manager.getContainers()) {
        try {
          int gameId = container.id;
          String appDirPath = SteamBridge.getAppDirPath(gameId);
          MarkerUtils.INSTANCE.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED);
          MarkerUtils.INSTANCE.removeMarker(appDirPath, Marker.STEAM_DLL_RESTORED);
          MarkerUtils.INSTANCE.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED);
          MarkerUtils.INSTANCE.removeMarker(appDirPath, Marker.STEAM_DRM_PATCHED);
          Log.i(
              "ImageFsInstaller",
              "Cleared Steam markers for container "
                  + container.getName()
                  + " (ID: "
                  + container.id
                  + ")");
        } catch (Exception e) {
          Log.w("ImageFsInstaller", "Failed to clear markers for container ID " + container.id, e);
        }
      }
    } catch (Exception e) {
      Log.e("ImageFsInstaller", "Error clearing Steam DLL markers", e);
    }
  }
}
