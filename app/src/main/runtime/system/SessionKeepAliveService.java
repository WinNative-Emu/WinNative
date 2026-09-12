package com.winlator.cmod.runtime.system;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.app.shell.UnifiedActivity;
import com.winlator.cmod.feature.stores.steam.utils.PrefManager;
import com.winlator.cmod.runtime.display.XServerDisplayActivity;
import com.winlator.cmod.runtime.display.environment.XEnvironment;
import com.winlator.cmod.runtime.display.xserver.XServer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.winlator.cmod.shared.android.NotificationHelper;

import timber.log.Timber;

/**
 * Foreground service that keeps the WinNative process alive while a wine
 * session is in the background or while a component download/install is
 * running. Without it, Android can reap the app process when the screen is
 * locked, taking the wine container (and any in-flight download) with it.
 *
 * Reasons are reference-counted via static helpers. The service stops itself
 * once no reasons remain. On task removal (user swipe-away) it does a
 * defensive wine cleanup and lets the process exit, matching the previous
 * "swipe = close" behaviour.
 */
public class SessionKeepAliveService extends Service {

    private static volatile SessionKeepAliveService instance;
    private static final String TAG = "SessionKeepAlive";
    private static final String ACTION_ENSURE_FOREGROUND =
            "com.winlator.cmod.action.ENSURE_FOREGROUND";

    private static final String ACTION_SESSION_START = "com.winlator.cmod.action.SESSION_START";
    private static final String ACTION_SESSION_STOP = "com.winlator.cmod.action.SESSION_STOP";

    public static final String COMPONENT_STEAM = "Steam";
    public static final String COMPONENT_EPIC = "Epic";
    public static final String COMPONENT_GOG = "GOG";

    private static final AtomicBoolean sessionActive = new AtomicBoolean(false);
    private static final HashSet<String> activeDownloads = new HashSet<>();
    private static final AtomicBoolean serviceRunning = new AtomicBoolean(false);
    private static volatile boolean serviceStopping = false;

    private static volatile XEnvironment activeEnvironment;
    private static volatile XServer activeXServer;

    private static volatile boolean isContainerPaused = false;

    // ── App visibility state ──────────────────────────────────────────────
    // isAppInBackground: true when no Activity is STARTED (ProcessLifecycleOwner).
    // isScreenLocked: true after ACTION_SCREEN_OFF, cleared by ACTION_USER_PRESENT.
    // Both are updated from the main thread; volatile is sufficient for reads.
    private static volatile boolean isAppInBackground = false;
    private static volatile boolean isScreenLocked = false;
    public static volatile boolean exitingFromNotification = false;
    private BroadcastReceiver screenStateReceiver;
    private static final AtomicBoolean appInPipMode = new AtomicBoolean(false);

    private PowerManager.WakeLock wakeLock;
    //    private WifiManager.WifiLock wifiLock;

    private static volatile SharedPreferences prefs;
    private static final String PREF_USE_WAKELOCK = "enable_background_wakelock";
    private static final String PREF_HEARTBEAT_FREQUENCY = "background_heartbeat_frequency";
    private static long heartbeat_interval_ms = 2 * 60 * 1000L; // 2 minutes
    private static long hb_frequency = 0;
    private HandlerThread screenReceiverThread;
    private Handler screenReceiverHandler;

    private NotificationHelper notificationHelper;

    // Dedicated thread replaces Handler.postDelayed() — not subject to
    // main-looper message-queue deferral in low-power states.
    private volatile Thread heartbeatThread;
    private volatile AtomicBoolean heartbeatSignal;
    private int notificationId = -1;
    private static final String NOTIFICATION_ID_NAME = "winnative.keepAlive";

    // Tracks active components and their notification messages.
    // We use a synchronized WeakHashMap for the inner map to prevent Context/Activity leaks.
    private static final Map<String, Map<Object, String>> activeComponents = new ConcurrentHashMap<>();

    // ===================================================================
    // Container / game session lifecycle
    // ===================================================================

    public static void startSession(Context ctx) {
        if (ctx == null) return;
        prefs = PreferenceManager.getDefaultSharedPreferences(ctx.getApplicationContext());
        if (prefs != null) {
            hb_frequency = prefs.getInt(PREF_HEARTBEAT_FREQUENCY, 0);
            if (hb_frequency > 0) {
                if (hb_frequency < 5)
                    heartbeat_interval_ms = 5 * 1000L;
                else
                    heartbeat_interval_ms = hb_frequency * 1000L;
            }
        }

        sessionActive.set(true);
        isContainerPaused = false;
        exitingFromNotification = false;
        LogManager.log(TAG, "startSession", ctx);
        updateForegroundState(ctx);
    }

    public static void onPauseSession(Context ctx) {
        if (ctx == null) return;
        if (!sessionActive.get()) {
            LogManager.logW(TAG, "onPauseSession called with no active session; ignoring", null, ctx);
            return;
        }
        isContainerPaused = true;
        LogManager.log(TAG, "onPauseSession", ctx);
        if (instance != null) {
//            instance.acquireWakeLock();       // Moved to onCreate > screenStateReceiver > Screen off event
            instance.startHeartbeat();
        }
        updateForegroundState(ctx);
    }

    public static void onResumeSession(Context ctx) {
        if (ctx == null) return;
        if (!sessionActive.get()) {
            LogManager.logW(TAG, "onResumeSession called with no active session; ignoring", null, ctx);
            return;
        }
        isContainerPaused = false;
        LogManager.log(TAG, "onResumeSession", ctx);
        if (instance != null) {
            instance.stopHeartbeat();
            instance.releaseWakeLock();
        }
        updateForegroundState(ctx);
    }

    public static void stopSession(Context ctx) {
        if (ctx == null) return;
        if (!sessionActive.compareAndSet(true, false)) return;
        isContainerPaused = false;
        if (instance != null) {
            instance.stopHeartbeat();
            instance.releaseWakeLock();
        }
        teardownEnvironmentAsync();
        updateForegroundState(ctx);
        LogManager.log(TAG, "Stopping game session in keep-alive service. Request by: " + Objects.requireNonNull(ctx.getClass().getName()), ctx);
    }

    public static boolean isSessionActive() {
        return sessionActive.get();
    }

    // Capture-then-null before handing off, so a second stopSession() call,
    // or a racing reader, can never observe a half-torn-down environment.
    private static void teardownEnvironmentAsync() {
        final XEnvironment env = activeEnvironment;
        activeEnvironment = null;
        activeXServer = null;
        if (env == null) return;
        new Thread(() -> {
            try {
                env.stopEnvironmentComponents();
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to stop environment components during session stop", e);
            }
        }, "XServerTeardown").start();
    }

    public static XEnvironment getActiveEnvironment() {
        return activeEnvironment;
    }

    public static void setActiveEnvironment(XEnvironment environment) {
        activeEnvironment = environment;
    }

    public static XServer getActiveXServer() {
        return activeXServer;
    }

    public static void setActiveXServer(XServer xServer) {
        activeXServer = xServer;
    }

    public static void setPipMode(boolean inPip) {
        appInPipMode.set(inPip);
        if (instance != null) {
            instance.ensureForeground();
            LogManager.logI(TAG, "setPipMode: " + inPip, instance);
        }
    }

    private static boolean isInPictureInPictureMode() {
        return appInPipMode.get();
    }

    // ===================================================================
    // Store component tracking (Steam/Epic/GOG "I'm doing background work")
    // ===================================================================

    public static void startComponent(Context ctx, String componentName, String message) {
        if (ctx == null || componentName == null) return;

        // Use a synchronized WeakHashMap to ensure Contexts aren't pinned in memory
        Map<Object, String> owners = activeComponents.get(componentName);
        if (owners == null) {
            owners = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
            activeComponents.put(componentName, owners);
        }

        owners.put(ctx, message != null ? message : "");
        LogManager.log(TAG, "startComponent: " + componentName, ctx);
        updateForegroundState(ctx);
    }

    public static void stopComponent(Context ctx, String componentName) {
        if (ctx == null || componentName == null) return;
        Map<Object, String> owners = activeComponents.get(componentName);
        if (owners != null) {
            if (owners.remove(ctx) != null) {
                if (owners.isEmpty()) {
                    activeComponents.remove(componentName);
                }
                LogManager.log(TAG, "stopComponent: " + componentName + " (owner: " + ctx + ")", ctx);
                updateForegroundState(ctx);
            }
        }
    }

    public static boolean isAppInBackground()  { return isAppInBackground;  }
    public static boolean isDeviceLocked()     { return isScreenLocked;      }

    public static boolean isAppNotVisible() {
        return isAppInBackground || isScreenLocked;
    }

    // ===================================================================
    // Background download tracking
    // ===================================================================

    public static void startDownload(Context ctx, String tag) {
        if (ctx == null) return;
        String key = tag == null ? "default" : tag;
        boolean added;
        synchronized (activeDownloads) { added = activeDownloads.add(key); }
        if (added) {
            Timber.tag(TAG).d("startDownload: %s", key);
            updateForegroundState(ctx);
        }
    }

    public static void stopDownload(Context ctx, String tag) {
        if (ctx == null) return;
        String key = tag == null ? "default" : tag;
        boolean removed;
        synchronized (activeDownloads) { removed = activeDownloads.remove(key); }
        if (removed) {
            Timber.tag(TAG).d("stopDownload: %s", key);
            updateForegroundState(ctx);
        }
    }

    // ===================================================================
    // Foreground validation logic
    // ===================================================================

    private static boolean hasReason() {
        boolean hasDownload;
        synchronized (activeDownloads) {
            hasDownload = !activeDownloads.isEmpty();
        }

        return sessionActive.get() || hasDownload || hasActiveComponent() ||
                (isAppNotVisible() && PrefManager.INSTANCE.getChatStayRunningOnExit());
    }

    private static boolean hasActiveComponent() {
        for (Map<Object, String> owners : activeComponents.values()) {
            if (!owners.isEmpty()) return true;
        }
        return false;
    }

    // Single chokepoint for every caller (session, components, downloads).
    // Mutates state first, then either talks to the already-alive instance
    // directly (no Intent, no restriction — it's just a method call) or, only
    // if the service doesn't exist yet, asks the OS to create it.
    private static synchronized void updateForegroundState(Context ctx) {
        SessionKeepAliveService svc = instance;

        if (hasReason()) {
            if (svc != null && !serviceStopping) {
                svc.ensureForeground();
            } else {
                Context app = ctx.getApplicationContext();
                Intent intent = new Intent(app, SessionKeepAliveService.class);
                intent.setAction(ACTION_ENSURE_FOREGROUND);
                try {
                    androidx.core.content.ContextCompat.startForegroundService(app, intent);
                } catch (Exception e) {
                    LogManager.logW(TAG, "Failed to start keep-alive service", e, ctx);
                }
            }
        } else if (svc != null) {
            LogManager.log(TAG, "No active reason remains; stopping keep-alive service", ctx);
            serviceStopping = true;
            serviceRunning.set(false);
            svc.stopForegroundCompat();
            svc.stopSelf();
        }
    }

    // ===================================================================
    // Foreground class logic
    // ===================================================================

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize the helper using the application context
        notificationHelper = new NotificationHelper(getApplicationContext());
        notificationHelper.createNotificationChannel(); // Replace ensureChannel() method.

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WinNative:SessionKeepAlive"
            );
            // setReferenceCounted(false): acquire/release are idempotent —
            // calling release() without a matching acquire() won't throw.
            wakeLock.setReferenceCounted(false);
        }

        // Now that fields are ready, publish the instance
        instance = this;

        // Seed initial state from current lifecycle rather than assuming foreground.
        isAppInBackground = !androidx.lifecycle.ProcessLifecycleOwner.get()
                .getLifecycle().getCurrentState()
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);

        androidx.lifecycle.ProcessLifecycleOwner.get()
                .getLifecycle()
                .addObserver(appLifecycleObserver);

        // Dispatch onReceive() on a dedicated background Looper instead of the main
        // thread, so a synchronous WakeLock Binder call to a contended system_server
        // can never delay the broadcast past the ANR timeout. Also serializes
        // SCREEN_OFF / USER_PRESENT / SCREEN_ON handling in arrival order.
        screenReceiverThread = new HandlerThread("SessionKeepAlive-ScreenReceiver");
        screenReceiverThread.start();
        screenReceiverHandler = new Handler(screenReceiverThread.getLooper());

        // Screen-lock detection. ACTION_SCREEN_OFF/USER_PRESENT are protected
        // broadcasts — dynamic registration only, no manifest entry needed.
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    isScreenLocked = true;
                    acquireWakeLock();
                    LogManager.log(TAG, "Screen turned off / device locked");
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    isScreenLocked = false;
                    releaseWakeLock();
                    LogManager.log(TAG, "Device unlocked (user present)");
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    // Screen on but keyguard may still be showing.
                    KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                    isScreenLocked = km != null && km.isKeyguardLocked();
                }
                updateForegroundState(context);
            }
        };

        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenStateReceiver, screenFilter, null, screenReceiverHandler);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        // Reset the stopping flag as we've received a new start command (or are resuming)
        serviceStopping = false;

        // Always promote to foreground first so Android does not consider
        // the start a violation (and so the notification reflects current
        // reasons), even if the command immediately tells us to stop.
        ensureForeground();
        serviceRunning.set(true);

        // Handle the Exit button from the notification
        if (ACTION_SESSION_STOP.equals(action)) {
            exitingFromNotification = true;
            boolean chatStayAlive = PrefManager.INSTANCE.getChatStayRunningOnExit();

            // If a game is running, and we want to keep chat alive, only stop the session.
            // updateForegroundState() will be called inside stopSession,
            // and it will update the notification to the "Chat" state.
            if (sessionActive.get() && chatStayAlive) {
                stopSession(this);

                // Move the blocking cleanup to a background thread to prevent ANRs.
                // We use a small delay to allow stopSession's environment teardown to start.
                new Thread(() -> {
                    try {
                        Thread.sleep(1000L);
                        cleanUpSession(getApplicationContext(), "Exit button pressed");
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        LogManager.logW(TAG, "Background session cleanup failed", t, getApplicationContext());
                    }
                }, "SessionExitCleanup").start();
            } else {
                // Otherwise, perform a full app shutdown.
                closeApp(this);
            }
            return START_NOT_STICKY;
        }

        if (!hasReason()) {
            Timber.tag(TAG).d("onStartCommand found no active reason; stopping immediately");
            serviceStopping = true;
            stopForegroundCompat();
            stopSelf();
            serviceRunning.set(false);
        }
        return START_NOT_STICKY;
    }

    private void ensureForeground() {
        boolean containerActive = sessionActive.get();
        // Only show Exit button if app is in background AND container is running or user wants to keep steam chat alive.
//        boolean showExit = isAppNotVisible() && (containerActive || PrefManager.INSTANCE.getChatStayRunningOnExit());    // Disabled because container "Exit" causes too much issues, ANR crash for example.
        boolean showExit = isAppNotVisible() && (!containerActive && PrefManager.INSTANCE.getChatStayRunningOnExit());

        // Determine target activity: Game screen if active, else Main menu
        Class<?> targetActivity = containerActive ? XServerDisplayActivity.class : UnifiedActivity.class;

        Notification n = notificationHelper.createForegroundNotification(
                getNotificationContent(),
                "WinNative",
                SessionKeepAliveService.class,
                showExit ? ACTION_SESSION_STOP : null, // Exit only for backgrounded app
                targetActivity // Activity class for the 'Open' (notification tap) action
        );

        // Cache the ID: repeated startForeground() calls with the same ID update
        // the existing notification instead of risking a fresh one each time
        // pause/resume/component state changes.
        if (notificationId == -1) {
            notificationId = notificationHelper.generateNotificationId(this, NOTIFICATION_ID_NAME);
        }

        try {
            // Only call startForeground the first time. Use notify() for updates.
            if (!serviceRunning.get()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                }
                else {
                    startForeground(notificationId, n);
                }
            }
            else {
                // Standard notification update
                notificationHelper.notify(notificationId, n);
            }
        } catch (Exception e) {
            LogManager.logW(TAG, "Failed to startForeground", e, this);
        }
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            LogManager.logW(TAG, "Failed to stopForeground", e, this);
        }
    }

    @Override
    public void onTimeout(int startId, int fstype) {
        /*
         * Note: This callback is unreachable while targetSdk is 28 (requires API 34+).
         * If the SDK is bumped in the future, we must route through stopSession to ensure
         * that environment components are torn down and wine processes are not leaked,
         * rather than just stopping the service while they are in a protected/frozen state.
         */
        super.onTimeout(startId, fstype);
        Timber.tag(TAG).w("Service reached 6-hour limit for dataSync. Stopping gracefully.");

        // Stop the service and cleanup
        stopSession(this);
        stopForegroundCompat();
        stopSelf();
    }

    // ===================================================================
    // Cleaning methods
    // ===================================================================

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        LogManager.logI(TAG, "Task removed (user swipe). Tearing down session and exiting process.", this);

        resetLocalState();

        performDefensiveCleanupAndExit(this);
    }

    @Override
    public void onDestroy() {
        // Null the instance immediately to close the window for other threads
        instance = null;
        serviceStopping = false;
        serviceRunning.set(false);

//        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
//        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopHeartbeat();
        releaseWakeLock();

        androidx.lifecycle.ProcessLifecycleOwner.get()
                .getLifecycle()
                .removeObserver(appLifecycleObserver);

        if (screenStateReceiver != null) {
            try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}
            screenStateReceiver = null;
        }
        if (screenReceiverThread != null) screenReceiverThread.quitSafely();

        if (notificationHelper != null) {
            notificationHelper.cancel(notificationId);
        }
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ===================================================================
    // Utility methods
    // ===================================================================

    private void acquireWakeLock() {
        if (wakeLock == null) return;
        if (prefs == null) return;
        if (!prefs.getBoolean(PREF_USE_WAKELOCK, false)) return;
        try {
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
                Timber.tag(TAG).d("WakeLock acquired");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to acquire wake lock", e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Timber.tag(TAG).d("WakeLock released");
        }
    }

    private void startHeartbeat() {
        if (prefs == null) return;
        if (heartbeatThread != null || !prefs.getBoolean("enable_background_wakelock", false) || hb_frequency <= 0) return;

        synchronized (this) {
            if (heartbeatThread != null) return;

            final AtomicBoolean signal = new AtomicBoolean(true);
            heartbeatSignal = signal;

            Thread t = new Thread(() -> {
                try {
                    while (signal.get() && sessionActive.get() && isContainerPaused) {
                        Thread.sleep(heartbeat_interval_ms);

                        // Verify state again after waking up from sleep
                        if (!signal.get() || !isContainerPaused) break;

                        LogManager.log(TAG, "Heartbeat: Keeping container alive...", getApplicationContext());
                        runOomSweepInternal();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "SessionHeartbeat");
            t.setDaemon(true);
            heartbeatThread = t;
            t.start();
        }
    }

    private void stopHeartbeat() {
        synchronized (this) {
            // Signal the thread to stop its loop
            if (heartbeatSignal != null) {
                heartbeatSignal.set(false);
                heartbeatSignal = null;
            }

            // Interrupt the thread if it's currently sleeping or blocked in OOM sweep
            if (heartbeatThread != null) {
                heartbeatThread.interrupt();
                heartbeatThread = null;
                LogManager.log(TAG, "Heartbeat stopped", this);
            }
        }
    }

    private void runOomSweepInternal() {
        try {
            ProcessHelper.protectAllWineProcesses();
        } catch (Exception e) {
            LogManager.logE(TAG, "OOM protection sweep failed", e, this);
        }
    }

    @NonNull
    private static String getNotificationContent() {
        // Capture a local reference to avoid NPE if instance is nulled concurrently
        SessionKeepAliveService svc = instance;
        if (svc == null) return "WinNative is running in the background";

        // 1. HIGHEST PRIORITY: The game/container
        if (sessionActive.get()) {
            boolean isReallyPaused = isContainerPaused && ProcessHelper.areProcessesPaused();
            return (isReallyPaused
                    && (isDeviceLocked() || !isInPictureInPictureMode()))
                    ? svc.getString(R.string.fg_keep_alive_notification_content_container_paused)
                    : svc.getString(R.string.fg_keep_alive_notification_content_container_running);
        }

        // 2. MEDIUM PRIORITY: Downloads
        synchronized (activeDownloads) {
            if (!activeDownloads.isEmpty()) return svc.getString(R.string.fg_keep_alive_notification_content_downloading_installing);
        }

        // 3. MEDIUM PRIORITY: Steam friends (if enabled)
        if (PrefManager.INSTANCE.getChatStayRunningOnExit() && isAppNotVisible())
            return svc.getString(R.string.fg_keep_alive_notification_content_steam_chat_running);

        // 4. LOW PRIORITY: Active store services
        // Filter out keys that might have been cleared by Garbage Collection
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Map<Object, String>> entry : activeComponents.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                names.add(entry.getKey());
            }
        }

        if (!activeComponents.isEmpty()) {
            // Define the priority order
            List<String> priority = Arrays.asList(COMPONENT_STEAM, COMPONENT_EPIC, COMPONENT_GOG);
            names.sort((a, b) -> {
                int idxA = priority.indexOf(a);
                int idxB = priority.indexOf(b);
                if (idxA != -1 && idxB != -1) return Integer.compare(idxA, idxB);
                if (idxA != -1) return -1;
                if (idxB != -1) return 1;
                return a.compareTo(b);
            });

            String joinedNames = names.get(0);
            int size = names.size();

            switch (size) {
                case 0:
                    break;
                case 1:
                    joinedNames = names.get(0);
                    break;
                case 2:
                    joinedNames = names.get(0) + ' ' + instance.getString(R.string.general_and) + ' ' + names.get(1);
                    break;
                default:
                    // Future-proof: "A, B, and C"
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < size; i++) {
                        sb.append(names.get(i));
                        if (i < size - 2) {
                            sb.append(", ");
                        } else if (i == size - 2) {
                            sb.append(", " + instance.getString(R.string.general_and) + ' ');
                        }
                    }
                    joinedNames = sb.toString();
            }

            String suffix = (size == 1) ? svc.getString(R.string.fg_keep_alive_notification_content_store_service_active) : svc.getString(R.string.fg_keep_alive_notification_content_store_services_active);
            return joinedNames + suffix;
        }
        return "WinNative is running in the background";
    }

    private final androidx.lifecycle.DefaultLifecycleObserver appLifecycleObserver =
            new androidx.lifecycle.DefaultLifecycleObserver() {
                @Override
                public void onStart(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                    isAppInBackground = false;
                    LogManager.log(TAG, "App came to foreground (ProcessLifecycleOwner)");
                    updateForegroundState(SessionKeepAliveService.this);
                }

                @Override
                public void onStop(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                    isAppInBackground = true;
                    LogManager.log(TAG, "App went to background (ProcessLifecycleOwner)");
                    updateForegroundState(SessionKeepAliveService.this);
                }
            };

    private static void resetLocalState() {
        sessionActive.set(false);
        isContainerPaused = false;
        synchronized (activeDownloads) { activeDownloads.clear(); }
        activeComponents.clear();
    }

    /**
     * Performs a deep cleanup of native processes and terminates the app PID.
     * This is the shared logic between swiping away and clicking "Exit".
     */
    private static void performDefensiveCleanupAndExit(Context ctx) {
        // Give the activity's own onDestroy → performForcedSessionCleanup a
        // chance to run first; then defensively clean any wine processes that
        // might still be alive, and exit the process so swipe/exit button behaves like the
        // pre-existing "swipe-away closes everything" flow.
        new Thread(() -> {
            try {
                Thread.sleep(1500L);
                cleanUpSession(ctx, "session keep-alive shutdown");
            } catch (Throwable t) {
                LogManager.logW(TAG, "Defensive cleanup failed", t, ctx);
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                serviceRunning.set(false);
                if (instance != null) {
                    instance.stopForegroundCompat();
                    instance.stopSelf();
                }
                // Final kill
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> android.os.Process.killProcess(android.os.Process.myPid()), 500L);
            });
        }, "SessionCleanupAndExit").start();
    }

    private static void cleanUpSession(Context ctx, String reason) {
        if (com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.isBionicHandoffActive()) {
            try {
                boolean kicked = com.winlator.cmod.feature.stores.steam.service.SteamService
                        .Companion.bionicHandoffReleaseAndKickPlayingSessionBlocking(true, 2500L);
                LogManager.logI(TAG, "Task removal/Exit button - Steam cleanup: kickedPlayingSession=" + kicked, ctx);
            } catch (Throwable t) {
                LogManager.logW(TAG, "Task removal/Exit button - Steam cleanup failed", t, ctx);
            }
        }
        ProcessHelper.terminateSessionProcessesAndWait(1500, true);
        ProcessHelper.drainDeadChildren(reason);
    }

    public static void stopAll(Context ctx) {
        stopSession(ctx);
        resetLocalState();

        // Stop Steam specifically
        com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.stop();

        // Finally stop the master service
        SessionKeepAliveService svc = instance;
        if (svc != null) {
            svc.stopForegroundCompat();
            svc.stopSelf();
        }
    }

    // Stop everything and kill the app process.
    public static void closeApp(Context ctx) {
        stopAll(ctx);
        performDefensiveCleanupAndExit(ctx);
    }
}
