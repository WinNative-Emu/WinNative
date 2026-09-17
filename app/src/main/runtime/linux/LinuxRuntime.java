package com.winlator.cmod.runtime.linux;

import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
  private static final String KGSL_DEVICE = "/dev/kgsl-3d0";

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
   * user's games) are bound at their own paths so nothing on either side needs translating; @binds
   * adds {@code host:guest} pairs. Android has no /dev/shm; a directory under the cache stands in
   * for it, which glibc's shm_open and Chromium's shared memory are content with.
   */
  public static List<String> command(
      Context context,
      ImageFs imageFs,
      File runtimeDir,
      File externalStorage,
      File inputDir,
      List<String> binds,
      List<String> guestCommand) {
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
    // Apps may not list /dev/input; the fake evdev nodes the input rings back stand in for it.
    if (inputDir != null && inputDir.isDirectory()) {
      bind(cmd, inputDir.getPath() + ":/dev/input");
    }
    for (String spec : binds) {
      bind(cmd, spec);
    }
    // Android denies apps these; glibc, Steam and libcap read them at startup.
    File fakeProc = new File(root, "etc/winnative/proc");
    String[][] procFiles = {
      {"stat", "/proc/stat"},
      {"version", "/proc/version"},
      {"loadavg", "/proc/loadavg"},
      {"uptime", "/proc/uptime"},
      {"vmstat", "/proc/vmstat"},
      {"cap_last_cap", "/proc/sys/kernel/cap_last_cap"},
      {"overflowuid", "/proc/sys/kernel/overflowuid"},
      {"overflowgid", "/proc/sys/kernel/overflowgid"},
    };
    for (String[] entry : procFiles) {
      File fake = new File(fakeProc, entry[0]);
      if (fake.isFile() && !new File(entry[1]).canRead()) {
        bind(cmd, fake.getPath() + ":" + entry[1]);
      }
    }
    bindGpuNode(context, cmd);
    cmd.addAll(guestCommand);
    return cmd;
  }

  /**
   * Apps may not touch {@code /dev/dri} or read sysfs, yet libdrm and the compositors built on it
   * identify a GPU by a DRM render node. The KGSL device Turnip drives stands in: it appears as a
   * render node with the sysfs entries libdrm reads, and Turnip reports the same device numbers.
   */
  private static void bindGpuNode(Context context, List<String> cmd) {
    StructStat st;
    try {
      st = Os.stat(KGSL_DEVICE);
    } catch (ErrnoException e) {
      return;
    }
    long dev = st.st_rdev;
    long major = ((dev >> 8) & 0xfff) | ((dev >> 32) & ~0xfffL);
    long minor = (dev & 0xff) | ((dev >> 12) & ~0xffL);
    String node = "renderD" + minor;
    File base = new File(context.getCacheDir(), "drm");
    File dri = new File(base, "dri");
    File device = new File(base, "sys/" + major + ":" + minor + "/device");
    File drm = new File(device, "drm/" + node);
    try {
      if ((!dri.isDirectory() && !dri.mkdirs()) || (!drm.isDirectory() && !drm.mkdirs())) {
        return;
      }
      new File(dri, node).createNewFile();
      Files.write(new File(drm, "dev").toPath(), (major + ":" + minor + "\n").getBytes(StandardCharsets.UTF_8));
      Files.write(new File(device, "uevent").toPath(),
          "DRIVER=kgsl-3d0\nMODALIAS=platform:kgsl-3d0\n".getBytes(StandardCharsets.UTF_8));
      File subsystem = new File(device, "subsystem");
      if (!Files.isSymbolicLink(subsystem.toPath())) {
        Os.symlink("/sys/bus/platform", subsystem.getPath());
      }
    } catch (IOException | ErrnoException e) {
      return;
    }
    bind(cmd, new File(base, "sys").getPath() + ":/sys/dev/char");
    bind(cmd, dri.getPath() + ":/dev/dri");
    bind(cmd, KGSL_DEVICE + ":/dev/dri/" + node);
  }

  private static void bind(List<String> cmd, String spec) {
    cmd.add("-b");
    cmd.add(spec);
  }

  /** X access control and Steam look the session user up by uid: the app uid is root inside. */
  public static void writeAccounts(Context context) throws IOException {
    File root = rootDir(context);
    int uid = Process.myUid();
    Files.write(new File(root, "etc/passwd").toPath(),
        ("root:x:" + uid + ":" + uid + ":root:/root:/bin/bash\n").getBytes(StandardCharsets.UTF_8));
    Files.write(new File(root, "etc/group").toPath(),
        ("root:x:" + uid + ":\n").getBytes(StandardCharsets.UTF_8));
  }
}
