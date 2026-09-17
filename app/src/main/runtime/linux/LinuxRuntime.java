package com.winlator.cmod.runtime.linux;

import android.content.Context;
import android.os.Process;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The glibc arm64 rootfs at {@code files/linuxfs} and the proot invocation that runs a program in it
 * as this app's own uid. proot is packaged as {@code libproot.so} so the installer places it, with
 * its loader, in the native library directory where it may be executed.
 */
public final class LinuxRuntime {
  public static final String DIR = "linuxfs";
  public static final String SESSION_SCRIPT = "/usr/local/bin/winnative-session";
  public static final String MODE_DESKTOP = "desktop";
  public static final String MODE_STEAM = "steam";
  public static final String MODE_RUN = "run";

  private LinuxRuntime() {}

  public static File rootDir(Context context) {
    return new File(context.getFilesDir(), DIR);
  }

  public static File prootBinary(Context context) {
    return new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so");
  }

  public static File prootLoader(Context context) {
    return new File(context.getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
  }

  /** The rootfs is present with gamescope and the session script the launcher hands control to. */
  public static boolean isInstalled(Context context) {
    File root = rootDir(context);
    return new File(root, "usr/bin/gamescope").isFile()
        && new File(root, SESSION_SCRIPT.substring(1)).isFile()
        && prootBinary(context).isFile()
        && prootLoader(context).isFile();
  }

  /** The Vulkan ICD manifest the rootfs ships for the device GPU, or null when it has none. */
  public static File vulkanIcd(Context context) {
    File icdDir = new File(rootDir(context), "usr/share/vulkan/icd.d");
    File[] manifests = icdDir.listFiles((dir, name) -> name.endsWith(".json"));
    if (manifests == null) return null;
    for (File manifest : manifests) {
      if (manifest.getName().contains("freedreno")) return manifest;
    }
    return manifests.length > 0 ? manifests[0] : null;
  }

  /**
   * The proot command line running {@code guestCommand} inside the rootfs. Host paths the session
   * needs (the app's files directory for the compositor and audio sockets, external storage for the
   * user's games) are bound at their own paths so nothing on either side needs translating. Android
   * has no /dev/shm; a directory under the cache stands in for it, which glibc's shm_open and
   * Chromium's shared memory are content with.
   */
  public static List<String> command(
      Context context, ImageFs imageFs, File runtimeDir, File externalStorage, List<String> guestCommand) {
    File root = rootDir(context);
    List<String> cmd = new ArrayList<>();
    cmd.add(prootBinary(context).getPath());
    cmd.add("--kill-on-exit");
    cmd.add("-r");
    cmd.add(root.getPath());
    cmd.add("-w");
    cmd.add("/root");
    bind(cmd, "/dev");
    bind(cmd, "/proc");
    bind(cmd, "/sys");
    bind(cmd, "/dev/urandom:/dev/random");
    bind(cmd, "/proc/self/fd:/dev/fd");
    bind(cmd, "/proc/self/fd/0:/dev/stdin");
    bind(cmd, "/proc/self/fd/1:/dev/stdout");
    bind(cmd, "/proc/self/fd/2:/dev/stderr");
    bind(cmd, new File(root, "etc/winnative/empty").getPath() + ":/sys/fs/selinux");
    bind(cmd, context.getFilesDir().getPath());
    bind(cmd, context.getCacheDir().getPath());
    bind(cmd, runtimeDir.getPath());
    bind(cmd, imageFs.getRootDir().getPath());
    if (externalStorage != null && externalStorage.isDirectory()) {
      bind(cmd, externalStorage.getPath());
    }
    File shm = new File(context.getCacheDir(), "shm");
    shm.mkdirs();
    bind(cmd, shm.getPath() + ":/dev/shm");
    // Android denies apps these; glibc, Steam and libcap read them at startup.
    File fakeProc = new File(root, "etc/winnative/proc");
    String[][] procFiles = {
      {"stat", "/proc/stat"},
      {"version", "/proc/version"},
      {"loadavg", "/proc/loadavg"},
      {"uptime", "/proc/uptime"},
      {"vmstat", "/proc/vmstat"},
      {"cap_last_cap", "/proc/sys/kernel/cap_last_cap"},
    };
    for (String[] entry : procFiles) {
      File fake = new File(fakeProc, entry[0]);
      if (fake.isFile() && !new File(entry[1]).canRead()) {
        bind(cmd, fake.getPath() + ":" + entry[1]);
      }
    }
    cmd.addAll(guestCommand);
    return cmd;
  }

  private static void bind(List<String> cmd, String spec) {
    cmd.add("-b");
    cmd.add(spec);
  }

  public static int uid() {
    return Process.myUid();
  }
}
