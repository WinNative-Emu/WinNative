package com.winlator.cmod.runtime.system;

import android.os.Process;
import com.winlator.cmod.shared.util.StringUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Locale;

/**
 * What is running in a GameScope session, read from {@code /proc}.
 *
 * Wine sessions get their process list from the Wine side over WinHandler, which a Linux session
 * has none of. Everything a GameScope session starts is a child of this app and carries its uid,
 * so the kernel's own view is the list: the session's processes are this uid's, minus this app's.
 */
public final class LinuxTaskList {
  private LinuxTaskList() {}

  public static final class Task {
    public final int pid;
    public final String name;
    public final String memory;
    public final int affinityMask;

    Task(int pid, String name, String memory, int affinityMask) {
      this.pid = pid;
      this.name = name;
      this.memory = memory;
      this.affinityMask = affinityMask;
    }
  }

  /** Worker thread: this reads a few files per process. */
  public static ArrayList<Task> list() {
    ArrayList<Task> tasks = new ArrayList<>();
    File proc = new File("/proc");
    String[] entries = proc.list();
    if (entries == null) return tasks;

    int self = Process.myPid();
    int uid = Process.myUid();
    for (String entry : entries) {
      int pid = parsePid(entry);
      if (pid < 0 || pid == self) continue;

      Status status = readStatus(new File(proc, entry));
      if (status == null || status.uid != uid) continue;

      String[] argv = readCmdline(new File(proc, entry));
      tasks.add(
          new Task(
              pid,
              displayName(argv, status.name),
              StringUtils.formatBytes(status.residentBytes),
              status.affinityMask));
    }
    return tasks;
  }

  private static int parsePid(String entry) {
    for (int i = 0; i < entry.length(); i++) {
      if (entry.charAt(i) < '0' || entry.charAt(i) > '9') return -1;
    }
    try {
      return entry.isEmpty() ? -1 : Integer.parseInt(entry);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static final class Status {
    int uid = -1;
    String name = "";
    long residentBytes;
    int affinityMask;
  }

  private static Status readStatus(File dir) {
    Status status = new Status();
    try (FileInputStream in = new FileInputStream(new File(dir, "status"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("Name:")) {
          status.name = line.substring(5).trim();
        } else if (line.startsWith("Uid:")) {
          String[] fields = line.trim().split("\\s+");
          if (fields.length > 1) status.uid = Integer.parseInt(fields[1]);
        } else if (line.startsWith("VmRSS:")) {
          String[] fields = line.trim().split("\\s+");
          if (fields.length > 1) status.residentBytes = Long.parseLong(fields[1]) * 1024L;
        } else if (line.startsWith("Cpus_allowed:")) {
          status.affinityMask = parseAffinity(line.substring(13).trim());
        }
      }
    } catch (IOException | RuntimeException e) {
      // The process exited while it was being read, or belongs to someone else.
      return null;
    }
    return status.uid < 0 ? null : status;
  }

  /** The kernel prints the mask in comma separated 32 bit groups, least significant last. */
  private static int parseAffinity(String value) {
    String last = value;
    int comma = value.lastIndexOf(',');
    if (comma >= 0) last = value.substring(comma + 1);
    try {
      return (int) Long.parseLong(last, 16);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String[] readCmdline(File dir) {
    byte[] buffer = new byte[4096];
    int length = 0;
    try (FileInputStream in = new FileInputStream(new File(dir, "cmdline"))) {
      int read;
      while (length < buffer.length && (read = in.read(buffer, length, buffer.length - length)) > 0) {
        length += read;
      }
    } catch (IOException e) {
      return new String[0];
    }
    if (length <= 0) return new String[0];
    return new String(buffer, 0, length).split("\0");
  }

  /**
   * The name worth showing. A Windows program is named by its executable wherever one appears in
   * the command line, since everything ahead of it is the emulator and Proton getting there. FEX
   * itself says nothing about what is running, so it is named by the program it was handed.
   */
  private static String displayName(String[] argv, String comm) {
    for (int i = argv.length - 1; i >= 0; i--) {
      if (argv[i].toLowerCase(Locale.ROOT).endsWith(".exe")) return baseName(argv[i]);
    }
    if (argv.length == 0 || argv[0].isEmpty()) return comm;
    String leader = baseName(argv[0]);
    if (leader.equals("FEX") && argv.length > 1) return baseName(argv[1]);
    return leader.isEmpty() ? comm : leader;
  }

  private static String baseName(String path) {
    int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return cut >= 0 ? path.substring(cut + 1) : path;
  }
}
