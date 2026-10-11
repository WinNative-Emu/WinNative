package com.winlator.cmod.runtime.linux;

import com.winlator.cmod.runtime.wine.EnvVars;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * proot's fast path: the session's path lookups answered inside each process by libwnfastpath
 * (tools/linuxfs/fastpath), which issues the translated call from a trampoline page proot's
 * seccomp filter lets through (cpp/proot/patches/0014). A stat or an open costs about a
 * microsecond instead of a 15-60 us round trip through the tracer.
 *
 * The library maps guest paths to host paths itself, so it has to be told the rootfs and the
 * binds exactly as proot was given them. Both sides carry a key derived from that view - proot
 * PROOT_FASTPATH=<key>, the guest PROOT_FP_KEY=<key> - and the library answers nothing unless the
 * process that traces it has the same key: a program that inherited the variables but runs under
 * another proot leaves every call to its own proot.
 *
 * It needs proot's seccomp filter, so PROOT_NO_SECCOMP turns it off as well; PROOT_FP_OFF in the
 * container's variables turns it off alone.
 */
public final class ProotFastPath {
  public static final String LIBRARY = "/usr/local/lib/libwnfastpath.so";
  /** MAX_BINDS in fastpath.c: past it the library turns itself off, so the session keeps it off. */
  public static final int MAX_BINDS = 256;

  private ProotFastPath() {}

  public static boolean enabled(EnvVars sessionEnv) {
    return !LinuxRuntime.noSeccomp(sessionEnv) && !sessionEnv.has("PROOT_FP_OFF");
  }

  /** The key for this rootfs and these binds, or null when the library cannot be told them. */
  public static String key(File root, List<String> binds) {
    if (binds.size() > MAX_BINDS || root.getPath().contains("|")) return null;
    for (String bind : binds) {
      if (bind.contains("|")) return null;
    }
    byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256")
          .digest((root.getPath() + "\n" + String.join("\n", binds)).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
    StringBuilder key = new StringBuilder();
    for (int i = 0; i < 12; i++) key.append(String.format("%02x", digest[i]));
    return key.toString();
  }

  /** The guest's assignments (for {@code env -i}), describing the view proot was given. */
  public static List<String> guestEnv(File root, List<String> binds, String key) {
    List<String> env = new ArrayList<>();
    env.add("PROOT_FP_ROOT=" + root.getPath());
    env.add("PROOT_FP_BINDS=" + String.join("|", binds));
    env.add("PROOT_FP_KEY=" + key);
    return env;
  }
}
