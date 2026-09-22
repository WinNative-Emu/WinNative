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
            + ", SDK " + android.os.Build.VERSION.SDK_INT);
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
            record("Linux session exited with status " + status);
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

  private void record(String message) {
    Log.i(TAG, message);
    try {
      Files.write(logFile.toPath(), (message + "\n").getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      Log.w(TAG, "Could not write Linux session log", e);
    }
  }

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
