package com.winlator.cmod.runtime.display.environment;

import android.content.Context;
import android.util.Log;
import com.winlator.cmod.runtime.audio.alsaserver.ALSAClient;
import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.runtime.display.environment.components.PulseAudioComponent;
import com.winlator.cmod.runtime.system.LogManager;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public class XEnvironment implements Iterable<EnvironmentComponent> {
  private static final String TAG = "XEnvironment";
  private Context context;
  private final ImageFs imageFs;
  private final ArrayList<EnvironmentComponent> components = new ArrayList<>();

  public XEnvironment(Context context, ImageFs imageFs) {
    this.context = context;
    this.imageFs = imageFs;
  }

  public Context getContext() {
    return context;
  }

  public void setContext(Context context) {
    this.context = context;
  }

  public ImageFs getImageFs() {
    return imageFs;
  }

  public void addComponent(EnvironmentComponent environmentComponent) {
    environmentComponent.environment = this;
    components.add(environmentComponent);
  }

  public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
    for (EnvironmentComponent component : components) {
      if (component.getClass() == componentClass) return (T) component;
    }
    return null;
  }

  @Override
  public Iterator<EnvironmentComponent> iterator() {
    return components.iterator();
  }

  public File getTmpDir() {
    File tmpDir = new File(context.getFilesDir(), "tmp");
    if (!tmpDir.isDirectory()) {
      tmpDir.mkdirs();
      FileUtils.chmod(tmpDir, 0771);
    }
    return tmpDir;
  }

  public void startEnvironmentComponents() {
    FileUtils.clear(getTmpDir());
    LogManager.log(TAG, "Starting " + components.size() + " environment component(s)");

    // GuestProgramLauncherComponent forks the game process and reads every
    // other component's sockets/state — it must start LAST and remain serial.
    // Every preceding component (XServer, audio, shm, network info, Steam
    // client) is a self-contained service; start them in parallel.
    ArrayList<EnvironmentComponent> parallelStart = new ArrayList<>();
    EnvironmentComponent launcher = null;
    for (EnvironmentComponent c : components) {
      if (c instanceof GuestProgramLauncherComponent) {
        launcher = c;
      } else {
        parallelStart.add(c);
      }
    }

    if (parallelStart.size() <= 1) {
      for (EnvironmentComponent c : parallelStart) {
        LogManager.log(TAG, "Starting component " + c.getClass().getSimpleName());
        c.start();
      }
    } else {
      String lastCompName = "Empty";
      ExecutorService pool = Executors.newFixedThreadPool(parallelStart.size());
      ArrayList<Future<?>> futures = new ArrayList<>(parallelStart.size());
      for (EnvironmentComponent c : parallelStart) {
        final EnvironmentComponent comp = c;
        lastCompName = comp.getClass().getSimpleName();
        futures.add(pool.submit(() -> {
          LogManager.log(TAG, "Starting component " + comp.getClass().getSimpleName());
          comp.start();
        }));
      }
      pool.shutdown();
      for (Future<?> f : futures) {
        try { f.get(); } catch (Throwable t) {
          LogManager.logE(TAG, "Component start failed: " + lastCompName + " (guest will still launch)", t);
        }
      }
    }

    if (launcher != null) {
      LogManager.log(TAG, "Starting component " + launcher.getClass().getSimpleName());
      launcher.start();
    }
    LogManager.log(TAG, "Environment component startup finished");
  }

  public void stopEnvironmentComponents() {
    // Stop in reverse order so dependent components (guest launcher) tear down before
    // their underlying services (audio sockets, XServer, shm).
    Log.d(TAG, "Stopping " + components.size() + " environment component(s)");
    RuntimeException firstFailure = null;
    for (int i = components.size() - 1; i >= 0; i--) {
      EnvironmentComponent component = components.get(i);
      String name = component.getClass().getSimpleName();
      try {
        Log.d(TAG, "Stopping component " + name);
        component.stop();
        Log.d(TAG, "Stopped component " + name);
      } catch (RuntimeException e) {
        Log.e(TAG, "Component stop failed for " + name, e);
        if (firstFailure == null) firstFailure = e;
      }
    }
    if (firstFailure != null) {
      Log.e(TAG, "Environment component shutdown finished with failure(s)", firstFailure);
    }
    Log.d(TAG, "Environment component shutdown finished");
  }

  public void onPause() {
    ALSAClient.setOutputSuspended(true);
    PulseAudioComponent pulseAudio = getComponent(PulseAudioComponent.class);
    if (pulseAudio != null) pulseAudio.suspend();
  }

  public void onResume() {
    PulseAudioComponent pulseAudio = getComponent(PulseAudioComponent.class);
    if (pulseAudio != null) pulseAudio.resume();
    ALSAClient.setOutputSuspended(false);
  }
}
