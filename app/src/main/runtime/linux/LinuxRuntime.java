package com.winlator.cmod.runtime.linux;

import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.runtime.wine.EnvVars;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
  /** The Mesa release tools/linuxfs/build-turnip.sh builds the shipped driver from. */
  public static final String TURNIP_VERSION = "26.2.2";
  /**
   * The shipped driver's place among the published ones, in the release's version numbering. A
   * downloaded driver is used only while it is newer, so an app update that ships a later driver
   * takes over from it.
   */
  public static final long TURNIP_BUILD = 202609220000L;
  /** A driver the Linux Client install downloaded, beside the rootfs so a new runtime leaves it be. */
  public static final String DRIVER_DIR = "linux-driver";
  public static final String DRIVER_LIBRARY = "libvulkan_freedreno.so";
  public static final String DRIVER_ICD = "freedreno_icd.json";
  public static final String DRIVER_VERSION_FILE = "version";
  public static final String DRIVER_NAME_FILE = "name";
  /** Refreshed into the rootfs at every session start; see {@link #syncSessionFiles}. */

  private static final String[] SESSION_FILES = {
    "usr/lib/libvulkan_freedreno.so",
    "usr/local/lib/libwninput.so",
    "usr/local/lib/libwnsession.so",
    "usr/local/bin/winnative-directaudio",
    "usr/local/bin/winnative-epic-launch",
    "usr/local/bin/winnative-netmanager",
    "usr/local/bin/winnative-proton-launch",
    "usr/local/bin/winnative-seed-redists",
    "usr/local/bin/winnative-session",
    "usr/local/bin/winnative-steam-compat",
    "usr/local/bin/winnative-steam-install",
    "usr/local/bin/winnative-steam-library",
    "usr/local/share/winnative/system-bus.conf",
  };

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

  public static EnvVars hostEnvironment(Context context, EnvVars sessionEnv) {
    EnvVars host = new EnvVars();
    host.put("PROOT_LOADER", prootLoader(context).getPath());
    host.put("PROOT_TMP_DIR", context.getCacheDir().getPath());
    String noSeccomp = sessionEnv.get("PROOT_NO_SECCOMP");
    if ("1".equals(noSeccomp) || "true".equalsIgnoreCase(noSeccomp) || "on".equalsIgnoreCase(noSeccomp)) {
      host.put("PROOT_NO_SECCOMP", "1");
    }
    return host;
  }

  /** The rootfs is present with gamescope and the session script the launcher hands control to. */
  public static boolean isInstalled(Context context) {
    File root = rootDir(context);
    return new File(root, "usr/bin/gamescope").isFile()
        && new File(root, SESSION_SCRIPT.substring(1)).isFile()
        && prootBinary(context).isFile()
        && prootLoader(context).isFile();
  }

  public static File driverDir(Context context) {
    return new File(context.getFilesDir(), DRIVER_DIR);
  }

  /** Worker thread. The downloaded driver's version, or 0 when there is none that can be used. */
  public static long downloadedDriverVersion(Context context) {
    File dir = driverDir(context);
    if (!new File(dir, DRIVER_LIBRARY).isFile() || !new File(dir, DRIVER_ICD).isFile()) return 0;
    String version = FileUtils.readString(new File(dir, DRIVER_VERSION_FILE));
    if (version == null) return 0;
    try {
      return Long.parseLong(version.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Worker thread. The Mesa release of the driver sessions draw with. */
  public static String driverName(Context context) {
    return com.winlator.cmod.runtime.content.DriverPackages.selectedLinuxName(context);
  }

  public static File vulkanIcd(Context context) {
    return vulkanIcd(context, null);
  }

  public static File vulkanIcd(Context context, String selection) {
    File selected = com.winlator.cmod.runtime.content.DriverPackages.selectedLinuxIcd(context, selection);
    if (selected != null) return selected;
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
    // The last two the rootfs does not ship, so they are written here: an install that already has
    // a rootfs would never receive them from the overlay.
    //
    // libpci picks its procfs backend on whether it can read the /proc/bus/pci directory, which the
    // app can, then die()s - exit(1) - on the devices file inside it, which the app cannot. Chromium
    // loads libpci in its GPU process to name the video card, so that exit took the GPU process with
    // it and the Steam UI ran at half the frame rate. An empty list is the truthful answer from in
    // here: nothing the app can see is on a PCI bus.
    stage(new File(fakeProc, "pci_devices"), "");
    // GE-Proton and CachyOS Proton import protonfixes from the top of their proton script, and its
    // esync check opens file-max with nothing catching a failure, so the PermissionError ended the
    // launch before the game started and Steam fell back to the Play button. The check only warns
    // below 8192; what matters is that the read succeeds.
    stage(new File(fakeProc, "file_max"), "1048576\n");
    String[][] procFiles = {
      {"stat", "/proc/stat"},
      {"version", "/proc/version"},
      {"loadavg", "/proc/loadavg"},
      {"uptime", "/proc/uptime"},
      {"vmstat", "/proc/vmstat"},
      {"pci_devices", "/proc/bus/pci/devices"},
      {"file_max", "/proc/sys/fs/file-max"},
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

  /** Writes a stand-in /proc file the rootfs does not carry, leaving one already there alone. */
  private static void stage(File file, String contents) {
    if (file.isFile()) return;
    try {
      Files.write(file.toPath(), contents.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // Absent, it fails the isFile() test in command() and the session runs as it did before.
    }
  }

  /**
   * Empties the directory standing in for /dev/shm. On a real system nothing in it outlives the
   * processes that made it; here the files stay, and the client alone leaves some fifty megabytes
   * of streams behind every time it runs.
   */
  public static void clearSharedMemory(Context context) {
    File shm = new File(context.getCacheDir(), "shm");
    if (shm.isDirectory()) FileUtils.clear(shm);
  }

  /**
   * The session's own files, refreshed from the app's copies: the Vulkan driver, the preload
   * libraries that {@code /etc/ld.so.preload} names, and the scripts the session runs.
   *
   * They have to match the build that starts the session - a rootfs installed by an earlier one
   * carries older copies, and the device has no way to replace them from outside the app. They are
   * copied in whole rather than compared: the driver's 15 MB takes well under a second, and each
   * copy lands through a rename, so a library another session still has mapped keeps the file
   * it opened.
   */
  public static void syncSessionFiles(Context context) throws IOException {
    File root = rootDir(context);
    for (String path : SESSION_FILES) {
      File target = new File(root, path);
      File dir = target.getParentFile();
      if (!dir.isDirectory() && !dir.mkdirs()) {
        throw new IOException("could not create " + dir.getPath());
      }
      File staged = new File(dir, target.getName() + ".staged");
      boolean installed = false;
      try {
        try (InputStream in = context.getAssets().open(DIR + "/" + path);
            OutputStream out = new FileOutputStream(staged)) {
          byte[] buffer = new byte[1 << 16];
          for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
          }
        }
        installed = staged.setExecutable(true, false) && staged.renameTo(target);
      } finally {
        if (!installed) staged.delete();
      }
      if (!installed) throw new IOException("could not install " + path);
    }
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
