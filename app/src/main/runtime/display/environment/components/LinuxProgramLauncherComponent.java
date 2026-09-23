package com.winlator.cmod.runtime.display.environment.components;

import android.util.Log;
import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.runtime.wine.EnvVars;
import com.winlator.cmod.shared.util.Callback;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class LinuxProgramLauncherComponent extends EnvironmentComponent {
  private static final String TAG = "LinuxLauncher";
  /** What every session runs under proot, and so where its options stop. */
  private static final String GUEST_COMMAND_HEAD = "/usr/bin/env";
  private final List<String> command;
  private final EnvVars envVars;
  private final File workingDir;
  private final File logFile;
  private final Callback<Integer> terminationCallback;
  private final Object lock = new Object();
  private Process process;

  public LinuxProgramLauncherComponent(
      List<String> command, EnvVars envVars, File workingDir, File logFile,
      Callback<Integer> terminationCallback) {
    this.command = command;
    this.envVars = envVars;
    this.workingDir = workingDir;
    this.logFile = logFile;
    this.terminationCallback = terminationCallback;
  }

  @Override
  public void start() {
    synchronized (lock) {
      stop();
      try {
        File parent = logFile.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        record("Linux session starting: " + android.os.Build.MANUFACTURER + " "
            + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE
            + ", SDK " + android.os.Build.VERSION.SDK_INT
            + ", WinNative " + com.winlator.cmod.BuildConfig.VERSION_NAME);
        record("proot options: " + prootOptions());
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDir);
        for (String entry : envVars.toStringArray()) {
          int separator = entry.indexOf('=');
          if (separator > 0) builder.environment().put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        Process started = builder.start();
        process = started;
        new Thread(() -> {
          try {
            int status = started.waitFor();
            synchronized (lock) {
              if (process != started) return;
              process = null;
            }
            record("Linux session exited with status " + status + describeSignal(status));
            if (terminationCallback != null) terminationCallback.call(status);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }, "LinuxSessionWait").start();
      } catch (IOException e) {
        Log.e(TAG, "Linux process could not start", e);
        record("Linux process could not start: " + e);
        if (terminationCallback != null) {
          new Thread(() -> terminationCallback.call(127), "LinuxSessionFailure").start();
        }
      }
    }
  }

  /**
   * A process taken down by a signal is reported as 128 plus that signal, so the statuses that
   * mean Android stopped the session read like an exit code it chose.
   */
  private static String describeSignal(int status) {
    switch (status) {
      case 137: return " (SIGKILL: stopped from outside, usually by Android)";
      case 143: return " (SIGTERM: asked to stop)";
      default: return "";
    }
  }

  /**
   * proot's own options, which the app writes and a reader of a failed session needs. The guest
   * command that follows carries the container's environment, where a user may have typed
   * anything, so it is left out and an unrecognised command line reports nothing rather than
   * risking that content in a log made to be shared.
   */
  private String prootOptions() {
    int guest = command.indexOf(GUEST_COMMAND_HEAD);
    if (guest < 1) return "not recognised";
    return String.join(" ", command.subList(1, guest));
  }

  private void record(String message) {
    Log.i(TAG, message);
    try {
      Files.write(logFile.toPath(), (message + "\n").getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      Log.w(TAG, "Could not write Linux session log", e);
    }
  }

  /**
   * Ends the session, where it stands. Asking proot first would do nothing - it answers SIG_IGN to
   * every signal but the fatal few - and a kill is enough on its own: its tracees are traced with
   * {@code PTRACE_O_EXITKILL}, so the kernel takes gamescope and everything under it down with it.
   * Killing on a thread of its own would let the exit path reach {@code killProcess} first and
   * leave that tree running after the app is gone.
   */
  @Override
  public void stop() {
    synchronized (lock) {
      if (process != null) {
        process.destroyForcibly();
        process = null;
      }
    }
  }
}
