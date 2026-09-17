package com.winlator.cmod.runtime.display.environment.components;

import android.util.Log;
import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.runtime.system.ProcessHelper;
import com.winlator.cmod.runtime.wine.EnvVars;
import com.winlator.cmod.shared.util.Callback;
import java.io.File;
import java.util.List;

/**
 * Runs one program in the Linux runtime for the length of the session; the session ends when it
 * exits. The command is a complete proot invocation from {@code LinuxRuntime.command}.
 */
public class LinuxProgramLauncherComponent extends EnvironmentComponent {
  private static final String TAG = "LinuxLauncher";
  private final List<String> command;
  private final EnvVars envVars;
  private final File workingDir;
  private final Callback<Integer> terminationCallback;
  private final Object lock = new Object();
  private int pid = -1;

  public LinuxProgramLauncherComponent(
      List<String> command, EnvVars envVars, File workingDir, Callback<Integer> terminationCallback) {
    this.command = command;
    this.envVars = envVars;
    this.workingDir = workingDir;
    this.terminationCallback = terminationCallback;
  }

  @Override
  public void start() {
    synchronized (lock) {
      stop();
      StringBuilder line = new StringBuilder();
      for (String arg : command) {
        if (line.length() > 0) line.append(' ');
        line.append(arg.replace(" ", "\\ "));
      }
      Log.i(TAG, "exec " + line);
      pid =
          ProcessHelper.exec(
              line.toString(),
              envVars.toStringArray(),
              workingDir,
              (status) -> {
                synchronized (lock) {
                  pid = -1;
                }
                ProcessHelper.drainDeadChildren("linux program termination callback");
                if (terminationCallback != null) terminationCallback.call(status);
              });
    }
  }

  @Override
  public void stop() {
    synchronized (lock) {
      if (pid != -1) {
        android.os.Process.killProcess(pid);
        pid = -1;
      }
    }
  }
}
