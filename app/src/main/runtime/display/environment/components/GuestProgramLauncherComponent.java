package com.winlator.cmod.runtime.display.environment.components;

import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Process;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.runtime.compat.box64.Box64Preset;
import com.winlator.cmod.runtime.compat.box64.Box64PresetManager;
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePreset;
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePresetManager;
import com.winlator.cmod.runtime.audio.directaudio.DirectAudioDriver;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.content.ContentProfile;
import com.winlator.cmod.runtime.content.ContentsManager;
import com.winlator.cmod.runtime.display.connector.UnixSocketConfig;
import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.input.controls.FakeInputWriter;
import com.winlator.cmod.runtime.system.GPUInformation;
import com.winlator.cmod.runtime.system.ProcessHelper;
import com.winlator.cmod.runtime.wine.EnvVars;
import com.winlator.cmod.runtime.wine.WineInfo;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.shared.util.Callback;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
  private static final String TAG = "GuestProgramLauncherComponent";
  private static final String DEPENDENCY_LOG_TAG = "CurlDeps";
  private static final long DEPENDENCY_CHECK_TIMEOUT_MS = 1500L;
  private static final Object lock = new Object();
  private static final AtomicBoolean dependencyCheckScheduled = new AtomicBoolean(false);
  private String guestExecutable;
  private int pid = -1;
  private int launchGeneration = 0;
  private String[] bindingPaths;
  private EnvVars envVars;
  private WineInfo wineInfo;
  private String box64Preset = Box64Preset.PERFORMANCE;
  private String fexcorePreset = FEXCorePreset.PERFORMANCE_TSO;
  private Callback<Integer> terminationCallback;
  private final ContentsManager contentsManager;
  private final ContentProfile wineProfile;
  private Container container;
  private final Shortcut shortcut;
  private File workingDir;
  private Runnable preUnpackCallback;
  private boolean fexUnixLibsActive = false;

  public static File ensureImageFsNativeLibrary(
      Context context, ImageFs imageFs, String libraryName) {
    File destFile = new File(imageFs.getLibDir(), libraryName);
    String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
    File sourceFile = new File(nativeLibDir, libraryName);

    long apkInstalledAt = new File(context.getApplicationInfo().sourceDir).lastModified();
    boolean destIsStale =
        destFile.exists()
            && (destFile.length() != sourceFile.length()
                || destFile.lastModified() < apkInstalledAt);

    if (sourceFile.exists() && (!destFile.exists() || destIsStale)) {
      Log.d(
          "GuestLauncher",
          "Copying "
              + libraryName
              + " from nativeLibDir to imagefs (dest exists="
              + destFile.exists()
              + ")");
      try {
        FileUtils.copy(sourceFile, destFile);
        Log.d(
            "GuestLauncher",
            "Successfully copied " + libraryName + " to " + destFile.getAbsolutePath());
      } catch (Exception e) {
        Log.e("GuestLauncher", "Failed to copy " + libraryName, e);
      }
    } else if (!destFile.exists()) {
      Log.d(
          "GuestLauncher",
          "Extracting " + libraryName + " from APK (not found in nativeLibDir or imagefs)");
      try (java.util.zip.ZipFile apk =
          new java.util.zip.ZipFile(context.getApplicationInfo().sourceDir)) {
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        String entryName = "lib/" + abi + "/" + libraryName;
        java.util.zip.ZipEntry entry = apk.getEntry(entryName);
        if (entry != null) {
          try (InputStream is = apk.getInputStream(entry);
              java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
              fos.write(buf, 0, len);
            }
          }
          destFile.setExecutable(true, false);
          Log.d(
              "GuestLauncher",
              "Successfully extracted "
                  + libraryName
                  + " from APK to "
                  + destFile.getAbsolutePath());
        } else {
          Log.w("GuestLauncher", libraryName + " not found in APK at " + entryName);
        }
      } catch (Exception e) {
        Log.e("GuestLauncher", "Failed to extract " + libraryName, e);
      }
    } else {
      Log.d(
          "GuestLauncher",
          libraryName
              + " already exists at "
              + destFile.getAbsolutePath()
              + " (size="
              + destFile.length()
              + ")");
    }

    boolean result = destFile.exists();
    if (!result) {
      Log.e(
          "GuestLauncher",
          libraryName + " is NOT available after ensure - this will cause issues!");
    }
    return result ? destFile : null;
  }

  public void setWorkingDir(File workingDir) {
    this.workingDir = workingDir;
  }

  public void setWineInfo(WineInfo wineInfo) {
    this.wineInfo = wineInfo;
  }

  public WineInfo getWineInfo() {
    return this.wineInfo;
  }

  public Container getContainer() {
    return this.container;
  }

  public void setContainer(Container container) {
    this.container = container;
  }

  public String execShellCommand(String command) {
    return execShellCommand(command, true);
  }

  public String execShellCommand(String command, boolean includeStderr) {
    if (environment == null) return "";

    Context context = environment.getContext();
    ImageFs imageFs = ImageFs.find(context);
    File rootDir = imageFs.getRootDir();
    StringBuilder output = new StringBuilder();

    // Clone the instance envVars by copying the map directly. Using
    // new EnvVars(this.envVars.toString()) would fail because toString() joins
    // with spaces and putAll(String) splits on spaces, destroying values that
    // contain spaces (e.g., driver paths like "Turnip MTR v3.2.2-p Axxx/").
    EnvVars envVars = new EnvVars();
    if (this.envVars != null) {
      envVars.putAll(this.envVars);
    }

    envVars.put("HOME", imageFs.home_path);
    envVars.put("USER", ImageFs.USER);
    envVars.put("TMPDIR", imageFs.getRootDir().getPath() + "/tmp");
    envVars.put("DISPLAY", ":0");

    String winePath =
        wineProfile == null
            ? imageFs.getWinePath() + "/bin"
            : ContentsManager.getSourceFile(context, wineProfile, wineProfile.wineBinPath)
                .getAbsolutePath();
    envVars.put(
        "PATH",
        winePath
            + ":"
            + imageFs.getRootDir().getPath()
            + "/usr/bin:"
            + imageFs.getRootDir().getPath()
            + "/usr/local/bin");
    envVars.put("LD_LIBRARY_PATH", imageFs.getRootDir().getPath() + "/usr/lib");
    envVars.put(
        "BOX64_LD_LIBRARY_PATH", imageFs.getRootDir().getPath() + "/usr/lib/x86_64-linux-gnu");
    envVars.put(
        "ANDROID_SYSVSHM_SERVER",
        imageFs.getRootDir().getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
    envVars.put("FONTCONFIG_PATH", imageFs.getRootDir().getPath() + "/usr/etc/fonts");
    // Env vars required for shell commands under the Bionic program launcher
    envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
    envVars.put("PREFIX", imageFs.getRootDir().getPath() + "/usr");
    envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
    envVars.put("SteamGameId", "0");

    File libDir = imageFs.getLibDir();
    File sysvshm64 = ensureImageFsNativeLibrary(context, imageFs, "libandroid-sysvshm.so");
    File libredirect64 = new File(libDir, "libredirect.so");
    Log.d(
        "GuestLauncher",
        "execShellCommand LD_PRELOAD setup: sysvshm="
            + (sysvshm64 != null)
            + " libredirect="
            + libredirect64.exists());
    if ((sysvshm64 != null && sysvshm64.exists()) || libredirect64.exists()) {
      StringBuilder ldPreload = new StringBuilder();
      if (libredirect64.exists()) ldPreload.append(libredirect64.getPath());
      if (sysvshm64 != null && sysvshm64.exists()) {
        if (ldPreload.length() > 0) ldPreload.append(" ");
        ldPreload.append(sysvshm64.getPath());
      }
      envVars.put("LD_PRELOAD", ldPreload.toString());
      Log.d("GuestLauncher", "execShellCommand LD_PRELOAD=" + ldPreload.toString());
    }
    if (!"1".equals(envVars.get("PROTON_NO_ESYNC"))) envVars.put("WINEESYNC_WINLATOR", "1");
    mergeExternalEnvVars(
        envVars,
        envVars.get("LD_PRELOAD"),
        envVars.get("FAKE_EVDEV_DIR"),
        envVars.get("FAKE_EVDEV_MEMFD_PATHS"),
        envVars.get("FAKE_UDEV_DATA_DIR"));
    FEXCorePresetManager.normalizeSmcChecksEnvVars(envVars, this.envVars);

    // For arm64ec Wine builds the wine binary is native ARM64 — call it directly
    // with a fully-qualified path. Wrapping with box64 causes it to fail ELF
    // header detection and adds overhead.
    // For non-arm64ec, box64 translates the x86_64 Wine binary.
    String finalCommand;
    if (wineInfo != null && wineInfo.isArm64EC()) {
      // Resolve bare "wine" or "wineserver" to full path under the Wine bin directory
      if (command.startsWith("wine ") || command.equals("wine")) {
        finalCommand = winePath + "/wine" + command.substring(4);
      } else if (command.startsWith("wineserver ") || command.equals("wineserver")) {
        finalCommand = winePath + "/wineserver" + command.substring(10);
      } else {
        finalCommand = command;
      }
    } else {
      String box64Path = rootDir.getPath() + "/usr/bin/box64";
      if (!new File(box64Path).exists()) {
        box64Path = rootDir.getPath() + "/usr/local/bin/box64";
      }
      finalCommand = box64Path + " " + command;
    }
    try {
      Log.d("GuestProgramLauncherComponent", "Shell command is " + finalCommand);
      final java.lang.Process process =
          Runtime.getRuntime()
              .exec(
                  finalCommand,
                  envVars.toStringArray(),
                  workingDir != null ? workingDir : imageFs.getRootDir());

      // stderr MUST be drained concurrently with stdout. Wine emits a steady
      // stream of `fixme:`/`err:` lines; if nothing reads stderr, the kernel
      // pipe buffer (64 KiB) fills and the child blocks forever on its next
      // stderr write — even if we're only interested in stdout. Sequential
      // "drain stdout, then stderr" deadlocks the same way, just less often.
      final boolean captureStderr = includeStderr;
      Thread stderrPump =
          new Thread(
              () -> {
                try (BufferedReader er =
                    new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                  String l;
                  while ((l = er.readLine()) != null) {
                    if (captureStderr) {
                      synchronized (output) {
                        output.append(l).append('\n');
                      }
                    }
                  }
                } catch (IOException ignored) {
                  // child closed stderr or we were interrupted — both are fine
                }
              },
              "execShellCommand-stderr-pump");
      stderrPump.setDaemon(true);
      stderrPump.start();

      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          synchronized (output) {
            output.append(line).append('\n');
          }
        }
      }
      process.waitFor();
      // Stderr pump exits on its own when the child closes its stderr fd
      // (which happens at process exit). Give it a brief grace period to
      // append any tail lines before we return.
      stderrPump.join(200);
    } catch (Exception e) {
      output.append("Error: ").append(e.getMessage());
    }

    return output.toString().trim();
  }

  private void extractBox64Files() {
    ImageFs imageFs = environment.getImageFs();

    // Use the configured runtime version; legacy containers may only have the app default.
    String box64Version = container.getBox64Version();
    if (box64Version == null) box64Version = "";

    if (shortcut != null) box64Version = shortcut.getSettingExtra("box64Version", box64Version);

    Log.i(
        "GuestProgramLauncherComponent",
        "Launch runtime selected: Box64 version=" + box64Version);

    File rootDir = imageFs.getRootDir();
    boolean box64Missing = !new File(rootDir, "usr/bin/box64").exists();

    if (box64Missing || !box64Version.equals(container.getExtra("box64Version"))) {
      if (box64Version.isEmpty()) {
        Log.w("GuestProgramLauncherComponent", "No Box64 version selected; skipping content extraction");
      } else {
        ContentProfile profile = contentsManager.getProfileByEntryName("box64-" + box64Version);
        if (profile != null) {
          Log.i(
              "GuestProgramLauncherComponent",
              "Loading Box64 content profile: version=" + box64Version);
          contentsManager.applyContent(profile);
        } else {
          Log.w(
              "GuestProgramLauncherComponent",
              "Box64 content profile not installed; no bundled Box64 archive will be loaded: version="
                  + box64Version);
        }
      }
      container.putExtra("box64Version", box64Version);
      container.saveData();
    } else {
      Log.i(
          "GuestProgramLauncherComponent",
          "Box64 already loaded for launch: version=" + box64Version);
    }

    // Set execute permissions for box64 just in case
    File box64File = new File(rootDir, "usr/bin/box64");
    if (box64File.exists()) {
      FileUtils.chmod(box64File, 0755);
    }
  }

  private void extractEmulatorsDlls() {
    File rootDir = environment.getImageFs().getRootDir();
    File system32dir = new File(rootDir + "/home/xuser/.wine/drive_c/windows/system32");
    boolean containerDataChanged = false;

    String emulator = container.getEmulator();
    String emulator64 = container.getEmulator64();
    String wowbox64Version = container.getBox64Version();
    String fexcoreVersion = container.getFEXCoreVersion();

    // Use configured runtime versions; legacy containers may only have app defaults.
    if (emulator == null) emulator = "";
    if (emulator64 == null) emulator64 = "";
    if (wowbox64Version == null) wowbox64Version = "";
    if (fexcoreVersion == null) fexcoreVersion = "";

    boolean unixLibsPref = container.isUseUnixLibs();
    if (shortcut != null) {
      emulator = shortcut.getSettingExtra("emulator", emulator);
      emulator64 = shortcut.getSettingExtra("emulator64", emulator64);
      wowbox64Version = shortcut.getSettingExtra("box64Version", wowbox64Version);
      fexcoreVersion = shortcut.getSettingExtra("fexcoreVersion", fexcoreVersion);
      unixLibsPref = "1".equals(shortcut.getSettingExtra("useUnixLibs", unixLibsPref ? "1" : "0"));
    }

    boolean usesWowbox64 = emulator.equalsIgnoreCase("wowbox64");
    boolean usesFexcore =
        emulator.equalsIgnoreCase("fexcore")
            || emulator64.equalsIgnoreCase("fexcore")
            || !usesWowbox64;

    fexUnixLibsActive =
        usesFexcore && unixLibsPref && contentsManager.fexcoreVersionHasUnixLibs(fexcoreVersion);

    Log.i(
        "GuestProgramLauncherComponent",
        "Launch runtime selected: emulator="
            + emulator
            + " emulator64="
            + emulator64
            + " WowBox64 version="
            + wowbox64Version
            + " FEXCore version="
            + fexcoreVersion);

    // Check if critical FEXCore DLLs actually exist on disk (they may be missing even if version
    // matches)
    boolean fexcoreDllsMissing =
        !new File(system32dir, "libwow64fex.dll").exists()
            || !new File(system32dir, "libarm64ecfex.dll").exists();
    boolean wowbox64DllMissing = !new File(system32dir, "wowbox64.dll").exists();

    if (usesFexcore && fexcoreDllsMissing) {
      Log.w(
          "GuestProgramLauncherComponent",
          "FEXCore DLLs missing from system32 (libwow64fex.dll or libarm64ecfex.dll), forcing re-extraction");
    }
    if (usesWowbox64 && wowbox64DllMissing) {
      Log.w(
          "GuestProgramLauncherComponent",
          "wowbox64.dll missing from system32, forcing re-extraction");
    }

    if (usesWowbox64
        && (wowbox64DllMissing || !wowbox64Version.equals(container.getExtra("box64Version")))) {
      if (wowbox64Version.isEmpty()) {
        Log.w("GuestProgramLauncherComponent", "No WowBox64 version selected; skipping content extraction");
      } else {
        ContentProfile profile = contentsManager.getProfileByEntryName("wowbox64-" + wowbox64Version);
        if (profile != null) {
          Log.i(
              "GuestProgramLauncherComponent",
              "Loading WowBox64 content profile: version=" + wowbox64Version);
          contentsManager.applyContent(profile);
        } else {
          Log.w(
              "GuestProgramLauncherComponent",
              "WowBox64 content profile not installed; no bundled WowBox64 archive will be loaded: version="
                  + wowbox64Version);
        }
      }
      container.putExtra("box64Version", wowbox64Version);
      containerDataChanged = true;
    } else if (usesWowbox64) {
      Log.i(
          "GuestProgramLauncherComponent",
          "WowBox64 already loaded for launch: version=" + wowbox64Version);
    }

    if (usesFexcore) {
      ContentProfile profile =
          fexcoreVersion.isEmpty() ? null : contentsManager.getProfileByEntryName("fexcore-" + fexcoreVersion);
      String wantMode = fexUnixLibsActive ? "unixlibs" : "dll";
      boolean modeChanged = !wantMode.equals(container.getExtra("fexcoreMode"));
      boolean versionChanged = !fexcoreVersion.equals(container.getExtra("fexcoreVersion"));
      boolean needsExtraction =
          versionChanged || modeChanged || (!fexUnixLibsActive && fexcoreDllsMissing);

      if (needsExtraction) {
        if (fexcoreVersion.isEmpty()) {
          Log.w("GuestProgramLauncherComponent", "No FEXCore version selected; skipping content extraction");
        } else if (profile != null) {
          Log.i(
              "GuestProgramLauncherComponent",
              "Loading FEXCore content profile: version=" + fexcoreVersion + " mode=" + wantMode);
          contentsManager.applyContent(profile);
          if (!fexUnixLibsActive) contentsManager.removeAppliedUnixLibs(profile);
        } else {
          Log.w(
              "GuestProgramLauncherComponent",
              "FEXCore content profile not installed; no bundled FEXCore archive will be loaded: version="
                  + fexcoreVersion);
        }
        container.putExtra("fexcoreVersion", fexcoreVersion);
        container.putExtra("fexcoreMode", wantMode);
        containerDataChanged = true;
      } else {
        Log.i(
            "GuestProgramLauncherComponent",
            "FEXCore already loaded for launch: version=" + fexcoreVersion + " mode=" + wantMode);
      }

      if (profile != null) {
        File wineUnixDir = new File(environment.getImageFs().getWinePath(), "lib/wine/aarch64-unix");
        if (fexUnixLibsActive) contentsManager.copyUnixLibsToDir(profile, wineUnixDir);
        else contentsManager.deleteUnixLibsFromDir(profile, wineUnixDir);
      }
    }
    if (containerDataChanged) container.saveData();
  }

  public GuestProgramLauncherComponent(
      ContentsManager contentsManager, ContentProfile wineProfile, Shortcut shortcut) {
    this.contentsManager = contentsManager;
    this.wineProfile = wineProfile;
    this.shortcut = shortcut;
  }

  private int getConfiguredControllerCount() {
    int numControllers = 1;
    if (shortcut != null) {
      try {
        numControllers = Integer.parseInt(shortcut.getExtra("numControllers", "1"));
      } catch (NumberFormatException e) {
        numControllers = 1;
      }
    }
    return Math.max(1, Math.min(numControllers, 4));
  }

  @Override
  public void start() {
    synchronized (lock) {
      long startTime = System.currentTimeMillis();
      if (wineInfo.isArm64EC()) {
        extractEmulatorsDlls();
      } else extractBox64Files();
      copyDefaultBox64RCFile();
      scheduleDependencyCheck();

      // Run Steamless DRM stripping if configured (must happen after box64 is ready
      // but before the game exe is launched)
      if (preUnpackCallback != null) {
        try {
          Log.d(TAG, "Running preUnpack callback (Steamless DRM stripping)");
          preUnpackCallback.run();
        } catch (Exception e) {
          Log.e(TAG, "preUnpack callback failed", e);
        }
      }

      launchGeneration++;
      pid = execGuestProgram();
      Log.d(
          TAG,
          "Guest process started with pid="
              + pid
              + " after launch prep "
              + (System.currentTimeMillis() - startTime)
              + "ms");
    }
  }

  private void scheduleDependencyCheck() {
    if (!dependencyCheckScheduled.compareAndSet(false, true)) return;

    Thread thread =
        new Thread(
            () -> Log.d(DEPENDENCY_LOG_TAG, checkDependencies()),
            "GuestDependencyCheck");
    thread.setDaemon(true);
    thread.start();
  }

  private void copyDefaultBox64RCFile() {
    Context context = environment.getContext();
    ImageFs imageFs = ImageFs.find(context);
    File rootDir = imageFs.getRootDir();
    String assetPath = "box86_64/default.box64rc";
    FileUtils.copy(context, assetPath, new File(rootDir, "/etc/config.box64rc"));
  }

  private String checkDependencies() {
    String curlPath = environment.getImageFs().getRootDir().getPath() + "/usr/lib/libXau.so";
    StringBuilder output = new StringBuilder("Checking Curl dependencies...\n");
    java.lang.Process process = null;
    Thread stdoutPump = null;
    Thread stderrPump = null;

    try {
      process = Runtime.getRuntime().exec(new String[] {"ldd", curlPath});
      stdoutPump = createDependencyPump(process.getInputStream(), output);
      stderrPump = createDependencyPump(process.getErrorStream(), output);

      stdoutPump.start();
      stderrPump.start();

      long deadline = System.currentTimeMillis() + DEPENDENCY_CHECK_TIMEOUT_MS;
      while (true) {
        try {
          int exitCode = process.exitValue();
          synchronized (output) {
            output.append("ldd exit code: ").append(exitCode).append("\n");
          }
          break;
        } catch (IllegalThreadStateException ignored) {
          if (System.currentTimeMillis() >= deadline) {
            synchronized (output) {
              output
                  .append("Timed out after ")
                  .append(DEPENDENCY_CHECK_TIMEOUT_MS)
                  .append("ms; skipping blocking dependency probe\n");
            }
            process.destroy();
            break;
          }

          try {
            Thread.sleep(50L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (output) {
              output.append("Dependency probe interrupted\n");
            }
            process.destroy();
            break;
          }
        }
      }
    } catch (Exception e) {
      output.append("Error running ldd: ").append(e.getMessage());
    } finally {
      joinDependencyPump(stdoutPump);
      joinDependencyPump(stderrPump);
    }

    return output.toString();
  }

  private Thread createDependencyPump(InputStream stream, StringBuilder output) {
    Thread pump =
        new Thread(
            () -> {
              try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  synchronized (output) {
                    output.append(line).append("\n");
                  }
                }
              } catch (IOException e) {
                synchronized (output) {
                  output.append("Error reading dependency output: ").append(e.getMessage()).append("\n");
                }
              }
            },
            "GuestDependencyPump");
    pump.setDaemon(true);
    return pump;
  }

  private void joinDependencyPump(Thread pump) {
    if (pump == null) return;
    try {
      pump.join(200L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void stop() {
    synchronized (lock) {
      if (pid != -1) {
        Log.d("GuestProgramLauncherComponent", "Stopping guest process pid=" + pid);
        Process.killProcess(pid);
        pid = -1;
      } else {
        Log.d("GuestProgramLauncherComponent", "Stop requested with no tracked guest process");
      }
      dependencyCheckScheduled.set(false);
    }
  }

  public Callback<Integer> getTerminationCallback() {
    return terminationCallback;
  }

  public void setTerminationCallback(Callback<Integer> terminationCallback) {
    this.terminationCallback = terminationCallback;
  }

  public void setPreUnpack(Runnable callback) {
    this.preUnpackCallback = callback;
  }

  public String getGuestExecutable() {
    return guestExecutable;
  }

  public void setGuestExecutable(String guestExecutable) {
    this.guestExecutable = guestExecutable;
  }

  public String[] getBindingPaths() {
    return bindingPaths;
  }

  public void setBindingPaths(String[] bindingPaths) {
    this.bindingPaths = bindingPaths;
  }

  public EnvVars getEnvVars() {
    return envVars;
  }

  public void setEnvVars(EnvVars envVars) {
    this.envVars = envVars;
  }

  public String getBox64Preset() {
    return box64Preset;
  }

  public void setBox64Preset(String box64Preset) {
    this.box64Preset = box64Preset;
  }

  public void setFEXCorePreset(String fexcorePreset) {
    this.fexcorePreset = fexcorePreset;
  }

  private static String mergePreloadValue(String baseValue, String overrideValue) {
    if (overrideValue == null || overrideValue.isEmpty()) {
      return baseValue == null ? "" : baseValue;
    }
    if (baseValue == null || baseValue.isEmpty()) {
      return overrideValue;
    }
    if (overrideValue.equals(baseValue)) {
      return baseValue;
    }
    return baseValue + ":" + overrideValue;
  }

  private static String appendFirstExistingPreload(String ldPreload, File[] candidates) {
    for (File candidate : candidates) {
      if (candidate.exists()) {
        return mergePreloadValue(ldPreload, candidate.getAbsolutePath());
      }
    }
    return ldPreload;
  }

  private void mergeExternalEnvVars(
      EnvVars envVars,
      String protectedLdPreload,
      String protectedFakeEvdevDir,
      String protectedFakeEvdevMemfdPaths,
      String protectedFakeUdevDataDir) {
    if (this.envVars == null) {
      return;
    }

    if (this.envVars.has("MANGOHUD")) {
      this.envVars.remove("MANGOHUD");
    }

    if (this.envVars.has("MANGOHUD_CONFIG")) {
      this.envVars.remove("MANGOHUD_CONFIG");
    }

    String overrideLdPreload = this.envVars.get("LD_PRELOAD");
    String overrideFakeEvdevDir = this.envVars.get("FAKE_EVDEV_DIR");
    String overrideFakeEvdevMemfdPaths = this.envVars.get("FAKE_EVDEV_MEMFD_PATHS");
    String overrideFakeUdevDataDir = this.envVars.get("FAKE_UDEV_DATA_DIR");

    envVars.putAll(this.envVars);

    if (protectedLdPreload != null && !protectedLdPreload.isEmpty()) {
      envVars.put("LD_PRELOAD", mergePreloadValue(protectedLdPreload, overrideLdPreload));
    }

    if (protectedFakeEvdevDir != null && !protectedFakeEvdevDir.isEmpty()) {
      envVars.put("FAKE_EVDEV_DIR", protectedFakeEvdevDir);
    } else if (overrideFakeEvdevDir != null && !overrideFakeEvdevDir.isEmpty()) {
      envVars.put("FAKE_EVDEV_DIR", overrideFakeEvdevDir);
    }

    if (protectedFakeEvdevMemfdPaths != null && !protectedFakeEvdevMemfdPaths.isEmpty()) {
      envVars.put("FAKE_EVDEV_MEMFD_PATHS", protectedFakeEvdevMemfdPaths);
    } else if (overrideFakeEvdevMemfdPaths != null && !overrideFakeEvdevMemfdPaths.isEmpty()) {
      envVars.put("FAKE_EVDEV_MEMFD_PATHS", overrideFakeEvdevMemfdPaths);
    }

    if (protectedFakeUdevDataDir != null && !protectedFakeUdevDataDir.isEmpty()) {
      envVars.put("FAKE_UDEV_DATA_DIR", protectedFakeUdevDataDir);
    } else if (overrideFakeUdevDataDir != null && !overrideFakeUdevDataDir.isEmpty()) {
      envVars.put("FAKE_UDEV_DATA_DIR", overrideFakeUdevDataDir);
    }
  }

  private void prepareFakeInputUdevMetadata(ImageFs imageFs, File devInputDir, EnvVars envVars) {
    File rootDir = imageFs.getRootDir();
    File udevDataDir = new File(rootDir, "run/udev/data");
    if (!udevDataDir.exists() && !udevDataDir.mkdirs()) {
      Log.w("GuestLauncher", "Failed to create fake udev data directory: " + udevDataDir);
      return;
    }

    envVars.put("FAKE_UDEV_DATA_DIR", udevDataDir.getAbsolutePath());

    File byIdDir = new File(devInputDir, "by-id");
    if (!byIdDir.exists()) byIdDir.mkdirs();

    int numControllers = getConfiguredControllerCount();
    for (int slot = 0; slot < numControllers; slot++) {
      int vendorId = 0x1234 + slot;
      int productId = 0x5678 + slot;
      int eventMinor = 64 + slot;
      String name = "Generic HID Gamepad " + slot;
      File udevData = new File(udevDataDir, "c13:" + eventMinor);
      String vendor = String.format(java.util.Locale.US, "%04x", vendorId);
      String product = String.format(java.util.Locale.US, "%04x", productId);
      String symlink = "input/by-id/usb-WinNative_Generic_HID_Gamepad_" + slot + "-event-joystick";
      String content =
          "I:"
              + slot
              + "\n"
              + "N:input/event"
              + slot
              + "\n"
              + "S:"
              + symlink
              + "\n"
              + "E:DEVNAME=/dev/input/event"
              + slot
              + "\n"
              + "E:ID_INPUT=1\n"
              + "E:ID_INPUT_JOYSTICK=1\n"
              + "E:ID_BUS=usb\n"
              + "E:ID_VENDOR=WinNative\n"
              + "E:ID_VENDOR_ID="
              + vendor
              + "\n"
              + "E:ID_MODEL=Generic_HID_Gamepad_"
              + slot
              + "\n"
              + "E:ID_MODEL_ID="
              + product
              + "\n"
              + "E:ID_SERIAL=WinNative_Generic_HID_Gamepad_"
              + slot
              + "\n"
              + "E:NAME=\""
              + name
              + "\"\n"
              + "E:TAGS=:uaccess:\n";
      FileUtils.writeString(udevData, content);

      File eventNode = new File(devInputDir, "event" + slot);
      if (!eventNode.exists()) {
        try {
          eventNode.createNewFile();
        } catch (Exception e) {
          Log.w("GuestLauncher", "Failed to create fake input node " + eventNode, e);
        }
      }

      File byIdLink = new File(byIdDir, "usb-WinNative_Generic_HID_Gamepad_" + slot + "-event-joystick");
      if (!byIdLink.exists()) {
        try {
          FileUtils.symlink("../event" + slot, byIdLink.getPath());
        } catch (Exception e) {
          Log.w("GuestLauncher", "Failed to create fake input by-id symlink " + byIdLink, e);
        }
      }
    }
  }

  private int execGuestProgram() {
    final int gen = launchGeneration;
    Context context = environment.getContext();
    ImageFs imageFs = environment.getImageFs();
    File rootDir = imageFs.getRootDir();

    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
    boolean emulatorLogs = preferences.getBoolean("enable_emulator_logs", false);
    boolean fexLogActive = wineInfo != null && wineInfo.isArm64EC()
        && (container == null || !"wowbox64".equalsIgnoreCase(container.getEmulator()));
    boolean enableBox64Logs = emulatorLogs && !fexLogActive;
    boolean enableFexcoreLogs = emulatorLogs && fexLogActive;
    boolean openWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
    boolean shareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);

    if (openWithAndroidBrowser) envVars.put("WINE_OPEN_WITH_ANDROID_BROWSER", "1");
    if (shareAndroidClipboard) {
      envVars.put("WINE_FROM_ANDROID_CLIPBOARD", "1");
      envVars.put("WINE_TO_ANDROID_CLIPBOARD", "1");
    }

    EnvVars envVars = new EnvVars();

    addBox64EnvVars(envVars, enableBox64Logs);
    envVars.putAll(FEXCorePresetManager.getEnvVars(context, fexcorePreset));

    if (enableFexcoreLogs) {
      // FEXCore is silent by default. Enable logging to stderr so its output is
      // captured by the in-game logs pane and the per-session fexcore_*.txt log.
      envVars.put("FEX_SILENTLOG", "0");
      envVars.put("FEX_OUTPUTLOG", "stderr");
    }

    String renderer = GPUInformation.getRenderer(null, null);

    if (renderer.contains("Mali")) envVars.put("BOX64_MMAP32", "0");

    if (envVars.get("BOX64_MMAP32").equals("1") && !wineInfo.isArm64EC()) {
      Log.d("GuestProgramLauncherComponent", "Disabling map memory placed");
      envVars.put("WRAPPER_DISABLE_PLACED", "1");
    }

    // Setting up essential environment variables for Wine
    envVars.put("HOME", imageFs.home_path);
    envVars.put("USER", ImageFs.USER);
    envVars.put("TMPDIR", rootDir.getPath() + "/usr/tmp");
    envVars.put("XDG_DATA_DIRS", rootDir.getPath() + "/usr/share");
    envVars.put("LD_LIBRARY_PATH", rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64");
    envVars.put("XDG_CONFIG_DIRS", rootDir.getPath() + "/usr/etc/xdg");
    envVars.put("GST_PLUGIN_PATH", rootDir.getPath() + "/usr/lib/gstreamer-1.0");
    envVars.put("FONTCONFIG_PATH", rootDir.getPath() + "/usr/etc/fonts");
    envVars.put(
        "VK_LAYER_PATH",
        rootDir.getPath()
            + "/usr/share/vulkan/implicit_layer.d"
            + ":"
            + rootDir.getPath()
            + "/usr/share/vulkan/explicit_layer.d");
    envVars.put("WRAPPER_LAYER_PATH", rootDir.getPath() + "/usr/lib");
    envVars.put("WRAPPER_CACHE_PATH", rootDir.getPath() + "/usr/var/cache");
    envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
    envVars.put("PREFIX", rootDir.getPath() + "/usr");
    envVars.put("DISPLAY", ":0");
    envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
    envVars.put("GST_PLUGIN_FEATURE_RANK", "ximagesink:3000");
    envVars.put(
        "ALSA_CONFIG_PATH",
        rootDir.getPath()
            + "/usr/share/alsa/alsa.conf"
            + ":"
            + rootDir.getPath()
            + "/usr/etc/alsa/conf.d/android_aserver.conf");
    envVars.put("ALSA_PLUGIN_DIR", rootDir.getPath() + "/usr/lib/alsa-lib");
    envVars.put("OPENSSL_CONF", rootDir.getPath() + "/usr/etc/tls/openssl.cnf");
    envVars.put("SSL_CERT_FILE", rootDir.getPath() + "/usr/etc/tls/cert.pem");
    envVars.put("SSL_CERT_DIR", rootDir.getPath() + "/usr/etc/tls/certs");
    envVars.put("WINE_X11FORCEGLX", "1");
    envVars.put("WINE_GST_NO_GL", "1");
    envVars.put("SteamGameId", "0");
    envVars.put("PROTON_AUDIO_CONVERT", "0");
    envVars.put("PROTON_VIDEO_CONVERT", "0");
    envVars.put("PROTON_DEMUX", "0");

    String winePath = imageFs.getWinePath() + "/bin";

    Log.d("GuestProgramLauncherComponent", "WinePath is " + winePath);

    envVars.put("PATH", winePath + ":" + rootDir.getPath() + "/usr/bin");

    envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);

    String primaryDNS = "8.8.4.4";
    java.util.List<String> orderedDns = new java.util.ArrayList<>();
    try {
      ConnectivityManager connectivityManager =
          (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
      android.net.Network activeNetwork =
          connectivityManager != null ? connectivityManager.getActiveNetwork() : null;
      android.net.LinkProperties linkProps =
          activeNetwork != null ? connectivityManager.getLinkProperties(activeNetwork) : null;
      java.util.List<InetAddress> dnsServers =
          linkProps != null ? linkProps.getDnsServers() : null;
      if (dnsServers != null && !dnsServers.isEmpty()) {
        for (InetAddress dns : dnsServers) {
          if (dns instanceof java.net.Inet4Address) {
            String a = dns.getHostAddress();
            if (a != null && !a.isEmpty()) orderedDns.add(a);
          }
        }
        for (InetAddress dns : dnsServers) {
          if (!(dns instanceof java.net.Inet4Address)) {
            String a = dns.getHostAddress();
            if (a != null && !a.isEmpty()) {
              int pct = a.indexOf('%');
              if (pct >= 0) a = a.substring(0, pct);
              orderedDns.add(a);
            }
          }
        }
        if (!orderedDns.isEmpty()) primaryDNS = orderedDns.get(0);
      }
    } catch (Exception e) {
      Log.w("GuestLauncher", "DNS capture failed, using fallback " + primaryDNS, e);
    }
    if (orderedDns.isEmpty()) {
      orderedDns.add("8.8.4.4");
      orderedDns.add("1.1.1.1");
    }
    envVars.put("ANDROID_RESOLV_DNS", primaryDNS);
    envVars.put("WINE_NEW_NDIS", "1");

    // Refresh /usr/etc/resolv.conf and /usr/etc/hosts on every launch.
    // The shipped imagefs ships stale DNS and a broken localhost mapping; rewrite
    // them here so Linux-side tools inside the prefix (curl, openssl, busybox) and
    // anything else that consults hosts/resolv.conf see the correct values.
    try {
      File etcDir = imageFs.getEtcDir();
      if (etcDir.isDirectory() || etcDir.mkdirs()) {
        StringBuilder resolv = new StringBuilder();
        resolv.append("# Generated at launch by WinNative from Android DNS.\n");
        for (String dns : orderedDns) {
          resolv.append("nameserver ").append(dns).append('\n');
        }
        resolv.append("options edns0 timeout:2 attempts:2\n");
        FileUtils.writeString(new File(etcDir, "resolv.conf"), resolv.toString());

        FileUtils.writeString(
            new File(etcDir, "hosts"),
            "127.0.0.1\tlocalhost\n"
                + "::1\t\tlocalhost ip6-localhost ip6-loopback\n"
                + "fe00::0\t\tip6-localnet\n"
                + "ff00::0\t\tip6-mcastprefix\n"
                + "ff02::1\t\tip6-allnodes\n"
                + "ff02::2\t\tip6-allrouters\n");
      }
    } catch (Exception e) {
      Log.w("GuestLauncher", "Failed to refresh resolv.conf/hosts in imagefs", e);
    }

    String ld_preload;
    if (!new File(imageFs.getLibDir(), "libandroid-sysvshm.so").exists()) {
      ld_preload = "";
    } else {
      ld_preload = imageFs.getLibDir() + "/libandroid-sysvshm.so";
    }
    File fakeinputDest = new File(imageFs.getLibDir(), "libfakeinput.so");
    String nativeLibDir = environment.getContext().getApplicationInfo().nativeLibraryDir;
    File fakeinputSrc = new File(nativeLibDir, "libfakeinput.so");
    Log.d("GuestLauncher", "nativeLibDir: " + nativeLibDir);
    Log.d("GuestLauncher", "fakeinputSrc exists: " + fakeinputSrc.exists());
    Log.d("GuestLauncher", "fakeinputDest: " + fakeinputDest.getAbsolutePath());
    try {
      if (fakeinputSrc.exists()) {
        // Refresh every launch so the guest reader can never skew from the
        // app-side ring writer (a same-size rebuild defeats length checks).
        FileUtils.copy(fakeinputSrc, fakeinputDest);
        Log.d("GuestLauncher", "Copied libfakeinput.so to imagefs");
      } else if (!fakeinputDest.exists()) {
        Log.e(
            "GuestLauncher",
            "libfakeinput.so NOT FOUND in APK: " + fakeinputSrc.getAbsolutePath());
      }
    } catch (Exception e) {
      Log.e("GuestLauncher", "Failed to copy libfakeinput.so: " + e.getMessage());
      e.printStackTrace();
    }
    Log.d("GuestLauncher", "fakeinputDest exists after copy: " + fakeinputDest.exists());

    // Some Proton builds dlopen "libSDL2-2.0.so.0" (standard SONAME on desktop Linux).
    // The imagefs ships "libSDL2-2.0.so" without the .0 suffix.  Create a symlink so
    // any winebus.so build can find SDL regardless of the exact name it tries.
    File sdlSo = new File(imageFs.getLibDir(), "libSDL2-2.0.so");
    File sdlSoLink = new File(imageFs.getLibDir(), "libSDL2-2.0.so.0");
    if (sdlSo.exists() && !sdlSoLink.exists()) {
      FileUtils.symlink(sdlSo.getName(), sdlSoLink.getPath());
      Log.d("GuestLauncher", "Created SDL symlink: " + sdlSoLink.getPath());
    }

    if (fakeinputDest.exists()) {
      if (!ld_preload.isEmpty()) {
        ld_preload = ld_preload + ":";
      }
      ld_preload = ld_preload + fakeinputDest.getAbsolutePath();
    }

    // Preload OEM Vulkan ICD deps that otherwise fail lazy symbol resolution.
    // Keep paths within default linker namespace permitted_paths.
    File[] jpegCandidates = new File[] {
        new File("/system/lib64/libjpeg.so"),
        new File("/system_ext/lib64/libjpeg.so"),
    };
    ld_preload = appendFirstExistingPreload(ld_preload, jpegCandidates);

    File[] cryptoCandidates = new File[] {
        new File("/system/lib64/libcrypto.so"),
        new File("/system_ext/lib64/libcrypto.so"),
        new File(imageFs.getLibDir(), "libcrypto.so.3"),
    };
    ld_preload = appendFirstExistingPreload(ld_preload, cryptoCandidates);


    File devInputDir = new File(imageFs.getRootDir(), "dev/input");
    devInputDir.mkdirs();
    FakeInputWriter.prepareRingSlots(devInputDir, 4);
    String fakeEvdevRingPaths = FakeInputWriter.getRingEnv(devInputDir);
    if (!fakeEvdevRingPaths.isEmpty()) {
      envVars.put("FAKE_EVDEV_MEMFD_PATHS", fakeEvdevRingPaths);
    }
    envVars.put("FAKE_EVDEV_DIR", devInputDir.getAbsolutePath());
    prepareFakeInputUdevMetadata(imageFs, devInputDir, envVars);
    envVars.put("FAKE_EVDEV_VIBRATION", "1");

    // Ensure Proton-flavoured winebus.sys uses the evdev/SDL path that
    // libfakeinput.so hooks, and does not filter out our fake gamepad.
    envVars.put("PROTON_ENABLE_HIDRAW", "0");
    envVars.put("SDL_GAMECONTROLLER_ALLOW_STEAM_VIRTUAL_GAMEPAD", "1");
    envVars.put("SDL_JOYSTICK_HIDAPI", "0");

    File netshimDest = ensureImageFsNativeLibrary(context, imageFs, "libnetshim.so");
    if (netshimDest != null && netshimDest.exists()) {
      ld_preload = ld_preload.isEmpty()
          ? netshimDest.getAbsolutePath()
          : netshimDest.getAbsolutePath() + ":" + ld_preload;
      String macSeed = android.provider.Settings.Secure.getString(
          context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
      if (macSeed == null || macSeed.isEmpty()) macSeed = "winnative";
      envVars.put("WN_NET_MAC_SEED", macSeed);
      File netshimLog = new File(imageFs.getRootDir(), "tmp/wn-netshim.log");
      netshimLog.getParentFile().mkdirs();
      netshimLog.delete();
      envVars.put("WN_NET_LOG", netshimLog.getAbsolutePath());
      if (new File(imageFs.getRootDir(), "tmp/wn-net-tap.on").exists()) {
        envVars.put("WN_NET_TAP", "1");
      }
      Log.d("GuestLauncher", "netshim preloaded; synthetic adapter MAC seed installed");
    } else {
      Log.w("GuestLauncher", "libnetshim.so missing — GetAdaptersInfo will report no adapters "
          + "and titles that derive a hardware id from the MAC (e.g. Brawlhalla) will be rejected");
    }

    String effectiveAudioDriver = container.getAudioDriver();
    if (shortcut != null) {
      String shortcutAudioDriver = shortcut.getExtra("audioDriver");
      if (shortcutAudioDriver != null && !shortcutAudioDriver.isEmpty()) {
        effectiveAudioDriver = shortcutAudioDriver;
      }
    }
    if (DirectAudioDriver.INSTANCE.isSelected(effectiveAudioDriver)) {
      File audioShimDest = ensureImageFsNativeLibrary(context, imageFs, "libwaudio.so");
      if (audioShimDest != null && audioShimDest.exists()) {
        Log.d("GuestLauncher", "DirectAudio: libwaudio shim staged in usr/lib");
      } else {
        Log.w("GuestLauncher", "DirectAudio selected but libwaudio.so missing — audio may be silent");
      }
    }

    Log.d("GuestLauncher", "Final LD_PRELOAD: " + ld_preload);
    envVars.put("LD_PRELOAD", ld_preload);

    // Preserve the launcher-owned preload/input paths while restoring the
    // full env built upstream in XServerDisplayActivity (driver, DXVK, Vulkan, etc).
    mergeExternalEnvVars(
        envVars,
        envVars.get("LD_PRELOAD"),
        envVars.get("FAKE_EVDEV_DIR"),
        envVars.get("FAKE_EVDEV_MEMFD_PATHS"),
        envVars.get("FAKE_UDEV_DATA_DIR"));
    FEXCorePresetManager.normalizeSmcChecksEnvVars(envVars, this.envVars);

    String emulator = container.getEmulator();
    String emulator64 = container.getEmulator64();
    if (shortcut != null) {
      emulator = shortcut.getSettingExtra("emulator", container.getEmulator());
      emulator64 = shortcut.getSettingExtra("emulator64", container.getEmulator64());
    }

    if (wineInfo.isArm64EC()) {
      emulator64 = container.getEmulator64();
      if (shortcut != null) {
        emulator64 = shortcut.getSettingExtra("emulator64", container.getEmulator64());
      }
    }

    repairRuntimeExecutablePermissions(context, imageFs);

    String command = "";
    String overriddenCommand = envVars.get("GUEST_PROGRAM_LAUNCHER_COMMAND");
    if (overriddenCommand.isEmpty()) {
      if (wineInfo.isArm64EC()) {
        command = winePath + "/" + guestExecutable;
        // Normalize defensively in case a stale/legacy emulator value slipped past
        // Container.normalizeEmulatorFieldsForArch (e.g. external write to
        // .container JSON). Treat anything that isn't literally "wowbox64" — case
        // and whitespace insensitive — as fexcore.
        String emu32 = (emulator == null) ? "" : emulator.trim().toLowerCase(java.util.Locale.ROOT);
        if ("wowbox64".equals(emu32)) {
          envVars.put("HODLL", "wowbox64.dll");
        } else {
          if (!"fexcore".equals(emu32)) {
            Log.w("GuestProgramLauncherComponent",
                    "Unrecognized arm64ec 32-bit emulator='" + emulator
                            + "', defaulting HODLL=libwow64fex.dll");
          }
          envVars.put("HODLL", "libwow64fex.dll");
        }
      } else {
        command = imageFs.getBinDir() + "/box64 " + guestExecutable;
      }
    } else {
      String[] parts = overriddenCommand.split(";");
      for (String part : parts) {
        command = command + part + " ";
      }
      command = command.trim();
    }

    File box64File = new File(rootDir, "/usr/bin/box64");
    if (box64File.exists()) {
      FileUtils.chmod(box64File, 0755);
    }

    Log.d(
        "GuestProgramLauncherComponent",
        "Launch env excerpt: "
            + "ADRENOTOOLS_DRIVER_PATH="
            + envVars.get("ADRENOTOOLS_DRIVER_PATH")
            + " "
            + "ADRENOTOOLS_DRIVER_NAME="
            + envVars.get("ADRENOTOOLS_DRIVER_NAME")
            + " "
            + "VK_ICD_FILENAMES="
            + envVars.get("VK_ICD_FILENAMES")
            + " "
            + "WRAPPER_VK_VERSION="
            + envVars.get("WRAPPER_VK_VERSION")
            + " "
            + "GALLIUM_DRIVER="
            + envVars.get("GALLIUM_DRIVER")
            + " "
            + "MESA_VK_WSI_DEBUG="
            + envVars.get("MESA_VK_WSI_DEBUG"));

    return ProcessHelper.exec(
        command,
        envVars.toStringArray(),
        workingDir != null ? workingDir : rootDir,
        (status) -> {
          synchronized (lock) {
            if (gen == launchGeneration) pid = -1;
          }

          ProcessHelper.drainDeadChildren("guest process termination callback");
          ProcessHelper.scheduleDeadChildReapSweep("guest process termination callback", 3000, 200);

          if (terminationCallback != null) terminationCallback.call(status);
        });
  }

  private void addBox64EnvVars(EnvVars envVars, boolean enableLogs) {
    envVars.put("BOX64_NOBANNER", ProcessHelper.PRINT_DEBUG && enableLogs ? "0" : "1");
    envVars.put("BOX64_DYNAREC", "1");

    if (enableLogs) {
      envVars.put("BOX64_LOG", "1");
      envVars.put("BOX64_DYNAREC_MISSING", "1");
    }

    envVars.putAll(Box64PresetManager.getEnvVars("box64", environment.getContext(), box64Preset));
    envVars.put("BOX64_X11GLX", "1");
    // Load ONLY our custom rc file (per-game Steam overrides, ZINK_CONTEXT_THREADED, etc.)
    // BOX64_NORCFILES=1 skips default system rc files to avoid conflicts,
    // BOX64_RCFILE points to our custom file so it still gets loaded.
    envVars.put("BOX64_NORCFILES", "1");
    ImageFs imageFs = ImageFs.find(environment.getContext());
    envVars.put("BOX64_RCFILE", imageFs.getRootDir().getPath() + "/etc/config.box64rc");

    if (container != null) {
      String cpuList = container.getCPUList(true);
      if (cpuList != null && !cpuList.isEmpty()) {
        envVars.put("BOX64_CPULIST", cpuList);
        envVars.put("BOX86_CPULIST", cpuList);
      }
    }
  }

  private void repairRuntimeExecutablePermissions(Context context, ImageFs imageFs) {
    try {
      if (wineProfile != null) {
        ContentsManager.repairInstalledContentPermissions(context, wineProfile);
      } else {
        File wineBinDir = new File(imageFs.getWinePath(), "bin");
        if (wineBinDir.isDirectory()) {
          File[] binaries = wineBinDir.listFiles();
          if (binaries != null) {
            for (File file : binaries) {
              if (file.isFile()) FileUtils.chmod(file, 0755);
            }
          }
        }
      }
    } catch (Exception e) {
      Log.w("GuestProgramLauncherComponent", "Failed to repair runtime executable permissions", e);
    }
  }

  public void suspendProcess() {
    synchronized (lock) {
      if (pid != -1) ProcessHelper.suspendProcess(pid);
    }
  }

  public void resumeProcess() {
    synchronized (lock) {
      if (pid != -1) ProcessHelper.resumeProcess(pid);
    }
  }

  private String pinWineLoader(String command, String wineLoader) {
    if (command == null || command.isEmpty()) return command;
    if (command.equals("wine") || command.equals("wine64")) return wineLoader;
    if (command.startsWith("wine ")) return wineLoader + command.substring(4);
    if (command.startsWith("wine64 ")) return wineLoader + command.substring(6);
    return command;
  }
}
