package com.winlator.cmod.runtime.display;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutManager;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.text.format.DateFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.content.FileProvider;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.view.WindowInsetsCompat;
import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.feature.leaderboard.SessionRecordingController;
import com.winlator.cmod.feature.stores.steam.enums.Marker;
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils;
import com.winlator.cmod.feature.stores.steam.utils.PrefManager;
import com.winlator.cmod.feature.stores.steam.utils.SteamUtils;

import androidx.preference.PreferenceManager;
import com.winlator.cmod.R;
import com.winlator.cmod.app.config.SettingsConfig;
import com.winlator.cmod.app.shell.UnifiedActivity;
import com.winlator.cmod.app.update.UpdateService;
import com.winlator.cmod.feature.settings.DebugFragment;
import com.winlator.cmod.feature.setup.SetupWizardActivity;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.display.environment.components.NetworkingSettings;
import com.winlator.cmod.runtime.container.ContainerManager;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.container.WinComponentSetup;
import com.winlator.cmod.feature.settings.DXVKConfigUtils;
import com.winlator.cmod.feature.settings.GraphicsDriverConfigUtils;
import com.winlator.cmod.feature.shortcuts.ShortcutsFragment;
import com.winlator.cmod.feature.sync.CloudSyncHelper;
import com.winlator.cmod.feature.sync.EpicLaunchCloudSync;
import com.winlator.cmod.feature.sync.GogLaunchCloudSync;
import com.winlator.cmod.feature.steamcloudsync.SteamExitCloudSync;
import com.winlator.cmod.feature.steamcloudsync.SteamLaunchCloudSync;
import com.winlator.cmod.feature.settings.WineD3DConfigUtils;
import com.winlator.cmod.runtime.compat.SteamBridge;
import com.winlator.cmod.runtime.content.ContentProfile;
import com.winlator.cmod.runtime.content.ContentsManager;
import com.winlator.cmod.runtime.content.AdrenotoolsManager;
import com.winlator.cmod.shared.android.AppUtils;
import com.winlator.cmod.shared.android.AppTerminationHelper;
import com.winlator.cmod.shared.ui.toast.WinToast;
import com.winlator.cmod.runtime.wine.EnvVars;
import com.winlator.cmod.runtime.reshade.ReshadeConfigWriter;
import com.winlator.cmod.runtime.reshade.ReshadeManager;
import com.winlator.cmod.runtime.wine.LocaleEnv;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.runtime.system.CPUStatus;
import com.winlator.cmod.runtime.system.GPUInformation;
import com.winlator.cmod.shared.util.KeyValueSet;
import com.winlator.cmod.shared.util.Callback;
import com.winlator.cmod.shared.util.OnExtractFileListener;
import com.winlator.cmod.shared.ui.dialog.PreloaderDialog;
import com.winlator.cmod.runtime.system.ProcessHelper;
import com.winlator.cmod.runtime.system.SessionKeepAliveService;
import com.winlator.cmod.shared.android.RefreshRateUtils;
import com.winlator.cmod.shared.util.StringUtils;
import com.winlator.cmod.shared.io.TarCompressorUtils;
import com.winlator.cmod.runtime.display.renderer.EffectComposer;
import com.winlator.cmod.runtime.display.renderer.effects.ColorAdjustEffect;
import com.winlator.cmod.runtime.display.renderer.effects.ColorBlindEffect;
import com.winlator.cmod.runtime.display.renderer.effects.ColorGradeEffect;
import com.winlator.cmod.runtime.display.renderer.effects.CRTEffect;
import com.winlator.cmod.runtime.display.renderer.effects.HDREffect;
import com.winlator.cmod.runtime.display.renderer.effects.NaturalEffect;
import com.winlator.cmod.runtime.display.renderer.effects.NTSC2Effect;
import com.winlator.cmod.runtime.display.renderer.effects.NTSCEffect;
import com.winlator.cmod.runtime.display.renderer.effects.PixelateEffect;
import com.winlator.cmod.runtime.display.renderer.effects.ScanlinesEffect;
import com.winlator.cmod.runtime.display.renderer.effects.SGSRUpscaler;
import com.winlator.cmod.runtime.display.renderer.effects.SharpenEffect;
import com.winlator.cmod.runtime.display.renderer.effects.ToonEffect;
import com.winlator.cmod.runtime.display.renderer.effects.VividEffect;
import com.winlator.cmod.runtime.wine.WineInfo;
import com.winlator.cmod.runtime.wine.WineRegistryEditor;
import com.winlator.cmod.runtime.wine.WineRequestHandler;
import com.winlator.cmod.runtime.wine.WineStartMenuCreator;
import com.winlator.cmod.runtime.wine.WineThemeManager;
import com.winlator.cmod.runtime.wine.WineUtils;
import com.winlator.cmod.runtime.compat.fexcore.FEXCoreManager;
import com.winlator.cmod.runtime.compat.gamefixes.GameFixes;
import com.winlator.cmod.runtime.audio.alsaserver.ALSAClient;
import com.winlator.cmod.runtime.input.ControllerAssignmentDialog;
import com.winlator.cmod.runtime.input.controls.ControlsProfile;
import com.winlator.cmod.runtime.input.controls.ControllerManager;
import com.winlator.cmod.runtime.input.controls.ExternalController;
import com.winlator.cmod.runtime.input.controls.GestureProfile;
import com.winlator.cmod.runtime.input.controls.GestureProfileManager;
import com.winlator.cmod.runtime.input.controls.InputControlsManager;
import com.winlator.cmod.runtime.input.controls.AccentTheme;
import com.winlator.cmod.runtime.input.controls.VisualStyle;
import com.winlator.cmod.shared.math.Mathf;
import com.winlator.cmod.shared.math.XForm;
import com.winlator.cmod.runtime.audio.midi.MidiHandler;
import com.winlator.cmod.runtime.audio.midi.MidiManager;
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer;
import com.winlator.cmod.runtime.display.ui.FrameRating;
import com.winlator.cmod.runtime.display.ui.MagnifierView;
import com.winlator.cmod.runtime.display.ui.MangoHudView;
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView;
import com.winlator.cmod.shared.android.FixedFontScaleAppCompatActivity;
import com.winlator.cmod.shared.android.SelfManagedOrientationActivity;
import com.winlator.cmod.runtime.input.ui.InputControlsView;
import com.winlator.cmod.runtime.input.ui.TouchpadView;
import com.winlator.cmod.runtime.display.winhandler.MouseEventFlags;
import com.winlator.cmod.runtime.display.winhandler.OnGetProcessInfoListener;
import com.winlator.cmod.runtime.display.winhandler.ProcessInfo;
import com.winlator.cmod.runtime.display.winhandler.WinHandler;
import com.winlator.cmod.runtime.display.connector.UnixSocketConfig;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.display.environment.XEnvironment;
import com.winlator.cmod.feature.stores.steam.SteamClientManager;
import com.winlator.cmod.runtime.audio.directaudio.DirectAudioDriver;
import com.winlator.cmod.runtime.display.environment.components.ALSAServerComponent;
import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.runtime.display.environment.components.NetworkInfoUpdateComponent;
import com.winlator.cmod.runtime.display.environment.components.PulseAudioComponent;
import com.winlator.cmod.runtime.display.environment.components.SteamClientComponent;
import com.winlator.cmod.runtime.display.environment.components.SysVSharedMemoryComponent;
import com.winlator.cmod.runtime.display.environment.components.XServerComponent;
import com.winlator.cmod.runtime.display.xserver.Atom;
import com.winlator.cmod.runtime.display.xserver.Pointer;
import com.winlator.cmod.runtime.display.xserver.Property;
import com.winlator.cmod.runtime.display.xserver.ScreenInfo;
import com.winlator.cmod.runtime.display.xserver.Window;
import com.winlator.cmod.runtime.display.xserver.WindowManager;
import com.winlator.cmod.runtime.display.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;
import static com.winlator.cmod.runtime.display.XServerDisplayUtils.*;

public class XServerDisplayActivity extends FixedFontScaleAppCompatActivity
        implements SelfManagedOrientationActivity {
    private static final long STEAM_TERMINATION_GRACE_MS = 10000L;
    private static final long STEAM_TERMINATION_POLL_MS = 1000L;
    private static final long STEAM_PROCESS_RESPONSE_TIMEOUT_MS = 2000L;
    private static final long STEAM_TERMINATION_TIMEOUT_MS = 30000L;
    private static final String STEAM_REGISTRY_KEY = "Software\\Valve\\Steam";
    private static final String STEAM_ROOT_PATH = "C:\\Program Files (x86)\\Steam";
    private static final String STEAM_EXE_PATH = STEAM_ROOT_PATH + "\\steam.exe";
    private static final String D8VK_ASSET_PATH = "dxwrapper/d8vk-1.0.tzst";
    // bump when extra_libs.tzst is repacked so existing containers re-extract (marker usr/lib/.extra_libs_version)
    private static final int EXTRA_LIBS_VERSION = 1;
    private static final String STEAM_USER_REGISTRY_BACKUP_FILE = "steam_registry_backup.reg";
    private static final String STEAM_SYSTEM_REGISTRY_BACKUP_FILE = "steam_system_registry_backup.reg";
    private static final String STEAM_CLIENT_STORE_RELATIVE_PATH = ".shared/steam-client-store";
    private static final String COLDCLIENT_STORE_RELATIVE_PATH = ".shared/coldclient-store";
    private static final String PREVIOUS_STEAM_CLIENT_STORE_RELATIVE_PATH = ".steam-client-store";
    private static final String PREVIOUS_CONTAINER_STEAM_CLIENT_STORE_RELATIVE_PATH = ".wine/.steam-client-store";
    private static final String LEGACY_STEAM_CLIENT_STORE_RELATIVE_PATH = ".wine/drive_c/WinNative/SteamClient";
    public static final String EXTRA_LAUNCHED_FROM_PINNED_SHORTCUT = "launched_from_pinned_shortcut";

    // CEF GPU flags avoid steamwebhelper taking DXVK's dxgi path in FEX.
    private static final String[] STEAM_SYSTEM_REGISTRY_KEYS = new String[] {
            "Software\\Classes\\steam",
            "Software\\Wow6432Node\\Valve\\Steam"
    };
    private static final String[] STEAM_REGISTRY_LINE_PATTERNS = new String[] {
            "\"sourcemodinstallpath\"",
            "\"steamexe\"",
            "\"steampath\"",
            "\"steamclientdll\"",
            "\"steamclientdll64\"",
            "winnative\\\\steamclient",
            "winnative/steamclient",
            ".shared\\\\steam-client-store",
            ".shared/steam-client-store",
            "steamclient_loader_x64.exe",
            "steamclient_loader_x86.exe",
            "steamclient_loader_x32.exe"
    };

    private static final HashSet<String> STEAM_EXIT_ALLOWLIST = new HashSet<>(Arrays.asList(
            "wineserver",
            "services",
            "start",
            "winhandler",
            "tabtip",
            "explorer",
            "winedevice",
            "svchost",
            "rpcss",
            "plugplay",
            "wineboot",
            "winemenubuilder",
            "conhost",
            "rundll32",
            "cmd"
    ));
    private XServerSurfaceView xServerView;
    private InputControlsView inputControlsView;
    private boolean inputControlsRevealAllowed = false;
    private TouchpadView touchpadView;

    // Auto-hide touchscreen controls while a game controller is connected.
    private InputManager autoHideInputManager;
    private InputManager.InputDeviceListener autoHideDeviceListener;
    private boolean controllerAutoHidden = false;
    private boolean userOverrodeAutoHide = false;
    private XEnvironment environment;
    private com.winlator.cmod.runtime.display.environment.AudioFocusHandler audioFocusHandler;
    private ComposeView displayHostComposeView;
    private FrameLayout xServerDisplayFrame;
    private ContainerManager containerManager;
    protected Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private GestureProfileManager gestureProfileManager;
    private int currentGestureProfileId = 0;
    private ImageFs imageFs;
    private FrameRating frameRating = null;
    private MangoHudView mangoHud = null;
    private boolean effectiveShowFPS = false;
    // Phone gauge HUD (Compose host) shown with touch controls disabled while a physical controller + external display are active.
    private boolean controllerHudMode = false;
    private android.hardware.input.InputManager.InputDeviceListener hudControllerListener;
    private boolean isTapToClickEnabled = true;
    private int runtimeFpsLimit = 0;
    private String lastRendererName = "Vulkan";
    private String lastGpuName = null;
    private boolean gameWindowSeen;
    private int rendererWindowId = -1;
    private boolean rendererWindowPresented;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private volatile String appliedSteamClientVisibility = null;
    private boolean launchedFromPinnedShortcut = false;
    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    private String zinkMode = Container.DEFAULT_ZINK_MODE;
    private HashMap<String, String> graphicsDriverConfig;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String emulator = Container.DEFAULT_EMULATOR;
    private String wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private KeyValueSet dxwrapperConfig;
    private String startupSelection;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    // True when the chosen launch exe differs from Steam's configured entry: launcher skips Steam LaunchApp and CreateProcess'es the selected exe directly. Recomputed per launch.
    private boolean wnSteamDirectExeOverride = false;
    private int wnSteamLaunchOption = -1;
    private String wnSteamUserArgs = "";
    private boolean firstTimeBoot = false;
    private SharedPreferences preferences;
    private boolean isMouseDisabled = false;
    private boolean isPointerCaptureForcedOff = false;
    private boolean isVolumeUpPressed = false;
    private boolean isVolumeDownPressed = false;
    private boolean guideHoldPending = false;
    private long guideMenuOpenedAt = 0L;
    private static final long GUIDE_HOLD_OPEN_MS = 450L;
    private static final long GUIDE_HOLD_TAIL_MS = 1200L;
    private final Runnable guideHoldOpenRunnable = new Runnable() {
        @Override
        public void run() {
            guideHoldPending = false;
            if (drawerStateHolder == null || !drawerStateHolder.isDrawerOpen()) {
                guideMenuOpenedAt = SystemClock.uptimeMillis();
                openDrawerMenu();
            }
        }
    };
    private OnExtractFileListener onExtractFileListener;
    private WinHandler winHandler;
    private WineRequestHandler wineRequestHandler;
    private float globalCursorSpeed = 1.0f;
    private MagnifierView magnifierView;
    private Callback<String> logStreamSink;
    private com.winlator.cmod.runtime.system.SessionLogWriter sessionLogWriter;
    private int taskAffinityMask = 0;
    private int taskAffinityMaskWoW64 = 0;
    private final HashSet<Integer> guestAffinityCheckedPids = new HashSet<>();
    private volatile boolean serviceAffinityStarted = false;
    private static final String[] SERVICE_AFFINITY_PROCESSES = {
        "services.exe", "rpcss.exe", "svchost.exe", "winedevice.exe",
        "plugplay.exe", "conhost.exe", "start.exe", "steamservice.exe"
    };
    private static final String[] SHELL_AFFINITY_PROCESSES = {
        "explorer.exe", "steamwebhelper.exe", "steam.exe"
    };
    // assemblyIdentity names marking the SxS activeCodePage manifests we deploy; anything else is game-owned and never touched.
    private static final String UTF8_MANIFEST_MARKER = "WinNative.Utf8CodePage";
    private static final String LOCALE_MANIFEST_MARKER = "WinNative.LocaleCodePage";
    private static final String UTF8_ACTIVE_CODEPAGE_MANIFEST = codePageManifest(UTF8_MANIFEST_MARKER, "UTF-8");
    private static final Pattern ACTIVE_CODE_PAGE_PATTERN =
        Pattern.compile("<activeCodePage[^>]*>([^<]*)</activeCodePage>", Pattern.CASE_INSENSITIVE);
    // Shown once the game window is up; a toast raised during setup would sit behind the preloader dialog.
    private final AtomicReference<String> pendingLocaleManifestWarning = new AtomicReference<>();
    private int frameRatingWindowId = -1;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;
    private final float[] xform = XForm.getInstance();
    private ContentsManager contentsManager;
    private boolean navigationFocused = false;
    private int drawerStickDir = 0;
    private final android.os.Handler drawerStickHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable drawerStickRepeat = new Runnable() {
        @Override
        public void run() {
            if (drawerStickDir == 0 || drawerStateHolder == null
                    || !(drawerStateHolder.isDrawerOpen() || drawerStateHolder.isPaneOpen())) {
                return;
            }
            fireDrawerStickDir(drawerStickDir);
            drawerStickHandler.postDelayed(this, 110);
        }
    };

    private void fireDrawerStickDir(int dir) {
        if (drawerStateHolder == null) return;
        if (!drawerStateHolder.isPaneOpen()) {
            if (dir == 1) drawerStateHolder.menuNavLeft();
            else if (dir == 2) drawerStateHolder.menuNavRight();
            else if (dir == 3) drawerStateHolder.menuNavUp();
            else drawerStateHolder.menuNavDown();
        } else {
            if (dir == 1) drawerStateHolder.paneNavLeft();
            else if (dir == 2) drawerStateHolder.paneNavRight();
            else if (dir == 3) drawerStateHolder.paneNavUp();
            else drawerStateHolder.paneNavDown();
        }
    }

    private boolean hasExternalMouse() {
        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        for (int deviceId : inputManager.getInputDeviceIds()) {
            InputDevice device = inputManager.getInputDevice(deviceId);
            if (device != null && !device.isVirtual() && (device.getSources() & InputDevice.SOURCE_MOUSE) != 0) {
                return true;
            }
        }
        return false;
    }

    private void tryCapturePointer() {
        if (touchpadView != null && (drawerStateHolder == null || !drawerStateHolder.isDrawerOpen())) {
            touchpadView.postDelayed(() -> {
                if (touchpadView != null) {
                    updatePointerCapture();
                }
            }, 100);
        }
    }

    private MidiHandler midiHandler;
    private String midiSoundFont = "";
    private String lc_all = "";
    PreloaderDialog preloaderDialog = null;
    private com.winlator.cmod.feature.stores.steam.wnsteam.WnLauncherStatusTailer wnLauncherStatusTailer = null;
    private final java.util.concurrent.atomic.AtomicBoolean wnLauncherDrivesDismiss =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private Runnable configChangedCallback = null;
    private boolean isPaused = false;
    private boolean reusingSession = false;
    private boolean isRelativeMouseMovement = false;
    private boolean isRefactorSizeEnabled = false;
    private int screenTouchMode = 0;
    private boolean rtsGesturesEnabled = false;
    private static final long REFACTOR_SIZE_EXE_BYTES = 17408L;
    private static final long REFACTOR_SIZE_UNSTAGE_DELAY_MS = 3000L;
    private static final long GRAPHICS_TEST_32_EXE_BYTES = 2333245L;
    private static final long GRAPHICS_TEST_64_EXE_BYTES = 2361407L;
    private static final long INPUT_TEST_32_EXE_BYTES = 289656L;
    private static final long INPUT_TEST_64_EXE_BYTES = 280952L;
    private String bootExePath;
    private String bootExeArgs;
    private boolean isDependencyInstall;
    private volatile int dependencyExitStatus = 0;

    public boolean isPaused() { return isPaused; }
    public boolean isInputSuspended() {
        return isPaused;
    }

    private boolean isAnyControllerConnected() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice dev = android.view.InputDevice.getDevice(id);
            if (dev != null && ExternalController.isGameController(dev)) return true;
        }
        return false;
    }
    private boolean isNativeRenderingEnabled = true;

    private float hudTransparency = 1.0f;
    private boolean hudBackgroundAlphaDecoupled = false;
    private float hudBackgroundTransparency = 1.0f;
    private float hudScale = 1.0f;
    private boolean[] hudElements = new boolean[]{true, true, true, true, true, true, true, true, false};
    private boolean dualSeriesBattery = false;
    private boolean frametimeNumericMode = false;
    private boolean hudCardExpanded = false;
    private boolean screenEffectsCardExpanded = false;
    private boolean frameGenEnabled = false;
    private int frameGenMultiplier = 2;
    private int frameGenTargetRate = 0;
    private int frameGenFlowScale = 70;
    private String frameGenCachePath = null;
    private float frameGenRefreshRate = 0f;
    private boolean sgsrEnabled = false;
    private boolean sgsrRuntimeEnabled = false;
    private int sgsrUpscaleMode = 1;
    private int sgsrSharpness = 100;
    private String sgsrBaseScreenSize = Container.DEFAULT_SCREEN_SIZE;
    private boolean vividEnabled = false;
    private int vividStrength = 100;
    private int colorProfile = 0;
    private int brightness = 0;
    private int contrast = 0;
    private int gammaPercent = 100;
    private int scaleFilter = 0;
    private int saturation = 100;
    private int temperature = 0;
    private int tint = 0;
    private boolean sharpenEnabled = false;
    private int sharpenStrength = 50;
    private boolean scanlinesEnabled = false;
    private int scanlinesIntensity = 50;
    private boolean pixelateEnabled = false;
    private int pixelateBlock = 6;
    private int colorBlind = 0;
    // loadout membership is fixed at launch; toggling enabled only flips a live gate, no recompile
    private boolean reshadeSessionAvailable = false;
    private boolean reshadeMasterEnabled = true;
    private String reshadeMode = com.winlator.cmod.runtime.reshade.ReshadeLoadout.MODE_SOLO;
    private final java.util.ArrayList<ReshadeLiveEffect> reshadeLive = new java.util.ArrayList<>();

    private static final long RESHADE_LIVE_DEBOUNCE_MS = 120;
    private android.os.HandlerThread reshadeLiveThread;
    private android.os.Handler reshadeLiveHandler;
    private volatile ReshadeLiveSnapshot pendingReshadeWrite;

    private static final class ReshadeLiveSnapshot {
        final java.util.ArrayList<com.winlator.cmod.runtime.reshade.ReshadeLoadout.Entry> entries;
        final String loadoutJson;
        final String paramsJson;
        final String firstEffect;
        final String mode;
        final boolean masterEnabled;

        ReshadeLiveSnapshot(java.util.ArrayList<com.winlator.cmod.runtime.reshade.ReshadeLoadout.Entry> entries,
                            String loadoutJson, String paramsJson, String firstEffect, String mode, boolean masterEnabled) {
            this.entries = entries;
            this.loadoutJson = loadoutJson;
            this.paramsJson = paramsJson;
            this.firstEffect = firstEffect;
            this.mode = mode;
            this.masterEnabled = masterEnabled;
        }
    }

    // values keys follow ReshadeManager.seedValues: "<uniform>", or "<uniform>_<c>" for COLOR
    private static class ReshadeLiveEffect {
        final String name;
        boolean enabled;
        final java.util.List<ReshadeManager.ReshadeParam> defs;
        final java.util.LinkedHashMap<String, Float> values;
        ReshadeLiveEffect(String name, boolean enabled, java.util.List<ReshadeManager.ReshadeParam> defs,
                          java.util.LinkedHashMap<String, Float> values) {
            this.name = name; this.enabled = enabled; this.defs = defs; this.values = values;
        }
    }
    private boolean gyroscopeCardExpanded = false;
    private XServerDrawerStateHolder drawerStateHolder;
    private XServerDrawerActionListener drawerActionListener;
    private ExternalDisplayController externalDisplayController;
    private com.winlator.cmod.runtime.display.recording.GameRecorder screenRecorder;
    private int savedRenderMode = XServerSurfaceView.RENDERMODE_WHEN_DIRTY;
    private Timer taskManagerTimer;
    private final ArrayList<TaskManagerProcess> taskManagerAccum = new ArrayList<>();
    private boolean taskManagerCpuExpanded = false;
    private boolean taskManagerPaneVisible = false;
    private CPUStatus.AppCpuSample prevTaskCpuSample;
    private boolean drawerEdgeGesturePossible = false;
    private float drawerEdgeGestureStartX = 0f;
    private float drawerEdgeGestureStartY = 0f;
    private int drawerEdgeGesturePointerId = -1;

    private SensorManager sensorManager;
    private Sensor gyroSensor;
    private Sensor gyroRotationSensor;
    private final float[] gyroRotationMatrix = new float[9];
    private final float[] gyroRemappedMatrix = new float[9];
    private final float[] gyroOrientationAngles = new float[3];
    private ExternalController controller;

    private long startTime;
    private SharedPreferences playtimePrefs;
    private String shortcutName;
    private String cachedPreloaderTitle = "";
    private String cachedPreloaderBadge = "";
    private String cachedPreloaderSubtitle = "";
    private Handler handler;
    private Runnable savePlaytimeRunnable;
    private android.hardware.display.DisplayManager displayManager;
    private android.hardware.display.DisplayManager.DisplayListener displayListener;
    private int lastKnownMaxRefreshRate;
    private static final long SAVE_INTERVAL_MS = 1000;
    private static final int EXIT_CLOUD_UPLOAD_MAX_ATTEMPTS = 3;
    private static final long EXIT_CLOUD_UPLOAD_RETRY_DELAY_MS = 1000L;

    private Handler  timeoutHandler = new Handler(Looper.getMainLooper());
    private static final long POINTER_ACTIVITY_REARM_MS = 1000L;
    private long lastPointerActivityAt = 0L;
    private Runnable hideControlsRunnable;

    private volatile boolean startFullscreenStretched;

    private final AtomicBoolean exitRequested = new AtomicBoolean(false);
    private final AtomicBoolean steamExitWatchRunning = new AtomicBoolean(false);
    private final AtomicBoolean activityDestroyed = new AtomicBoolean(false);
    private final AtomicBoolean steamStateSanitizedForClose = new AtomicBoolean(false);
    private final AtomicBoolean sessionCleanupStarted = new AtomicBoolean(false);
    private final AtomicBoolean switchLaunchInProgress = new AtomicBoolean(false);
    private final AtomicBoolean winHandlerStopped = new AtomicBoolean(false);

    private SessionRecordingController perfController;

    private boolean isDarkMode;
    private boolean enableLogsMenu;

    private GuestProgramLauncherComponent guestProgramLauncherComponent;
    private EnvVars overrideEnvVars;

    private Runnable controllerAutoSwitchRunnable;

    private final SensorEventListener gyroListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (winHandler == null) {
                return;
            }
            int type = event.sensor.getType();
            if (type == Sensor.TYPE_GYROSCOPE) {
                winHandler.updateGyroData(event.values[0], event.values[1]);
            } else if (type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                computeGyroOrientation(event.values);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sharedPreferences, key) -> {
        if ("gyro_enabled".equals(key) || "mouse_gyro_enabled".equals(key) || "gyro_orientation_enabled".equals(key)) {
            boolean gyroEnabled = sharedPreferences.getBoolean("gyro_enabled", false);
            if (gyroEnabled) {
                registerGyroSensorIfEnabled();
            } else if (sensorManager != null) {
                sensorManager.unregisterListener(gyroListener);
            }
        } else if ("cursor_speed".equals(key)) {
            globalCursorSpeed = sharedPreferences.getFloat("cursor_speed", 1.0f);
            if (touchpadView != null) {
                float profileSpeed = 1.0f;
                if (inputControlsView != null) {
                    ControlsProfile profile = inputControlsView.getProfile();
                    if (profile != null) profileSpeed = profile.getCursorSpeed();
                }
                touchpadView.setSensitivity(profileSpeed * globalCursorSpeed);
            }
        } else if ("touchscreen_toggle".equals(key)) {
            if (touchpadView != null) {
                touchpadView.setSimTouchScreen(sharedPreferences.getBoolean("touchscreen_toggle", false));
            }
        }
    };

    // Registers rotation-vector (orientation mode) or gyroscope (rate mode); unregisters first.
    private void registerGyroSensorIfEnabled() {
        if (sensorManager == null) {
            return;
        }
        if (!preferences.getBoolean("gyro_enabled", false)) {
            return;
        }
        sensorManager.unregisterListener(gyroListener);
        boolean orientationMode = preferences.getBoolean("gyro_orientation_enabled", false);
        boolean mouseMode = preferences.getBoolean("mouse_gyro_enabled", false);
        // Gyro-mouse (rate-based) needs the gyroscope and wins over orientation mode; orientation uses the rotation vector, falling back to gyroscope if absent.
        Sensor sensor =
                (orientationMode && !mouseMode && gyroRotationSensor != null)
                        ? gyroRotationSensor
                        : gyroSensor;
        if (sensor != null) {
            sensorManager.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    // Rotation-vector sample -> yaw/pitch (radians), remapped for display rotation (landscape).
    private void computeGyroOrientation(float[] rotationVector) {
        if (winHandler == null) {
            return;
        }
        SensorManager.getRotationMatrixFromVector(gyroRotationMatrix, rotationVector);
        int axisX = SensorManager.AXIS_X;
        int axisY = SensorManager.AXIS_Y;
        switch (getDisplayRotationForSensors()) {
            case android.view.Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case android.view.Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;
            case android.view.Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            default:
                break;
        }
        SensorManager.remapCoordinateSystem(gyroRotationMatrix, axisX, axisY, gyroRemappedMatrix);
        SensorManager.getOrientation(gyroRemappedMatrix, gyroOrientationAngles);
        // gyroOrientationAngles = [azimuth(yaw), pitch, roll]
        winHandler.updateGyroOrientation(gyroOrientationAngles[0], gyroOrientationAngles[1]);
    }

    private int getDisplayRotationForSensors() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.Display display = getDisplay();
                if (display != null) {
                    return display.getRotation();
                }
            }
        } catch (Exception ignored) {
        }
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (configChangedCallback != null) {
            configChangedCallback.run();
            configChangedCallback = null;
        }
    }

    private int getRefreshRateOverride() {
        int perGameRate = getPerGameRefreshRateOverride();
        if (perGameRate > 0) return perGameRate;
        int containerRate = getContainerRefreshRateOverride();
        return containerRate > 0 ? containerRate : getGlobalRefreshRateOverride();
    }

    private int getPerGameRefreshRateOverride() {
        if (shortcut == null) return 0;
        return parsePositiveInt(shortcut.getExtra("refreshRate", ""));
    }

    private int getContainerRefreshRateOverride() {
        if (container == null) return 0;
        return parsePositiveInt(container.getExtra("refreshRate", ""));
    }

    private int getGlobalRefreshRateOverride() {
        if (preferences == null) return 0;
        return Math.max(0, preferences.getInt("refresh_rate_override", 0));
    }

    private int parsePositiveInt(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(parsed, 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean shortcutUsesContainerDefaults() {
        return shortcut != null && shortcut.usesContainerDefaults();
    }

    private String getShortcutSetting(String key, String containerValue) {
        return shortcut != null ? shortcut.getSettingExtra(key, containerValue) : containerValue;
    }

    private String getFrameGenSetting(String key, String containerValue) {
        if (shortcut == null) return containerValue;
        return shortcut.getSettingExtra(key, containerValue);
    }

    private String frameGenContainerValue(String key, String fallback) {
        Container base = shortcut != null ? shortcut.container : container;
        return base != null ? base.getExtra(key, fallback) : fallback;
    }

    private boolean saveFrameGenOverride(String key, String value, String fallback) {
        if (value.equals(frameGenContainerValue(key, fallback))) {
            shortcut.putExtra(key, null);
            return false;
        }
        shortcut.putExtra(key, value);
        return true;
    }

    private void applyFrameGenerationSettings(VulkanRenderer renderer, Container container) {
        if (renderer == null) return;

        String containerValue = container != null ? container.getExtra("frameGen", "0") : "0";
        String containerMultiplier = container != null ? container.getExtra("frameGenMultiplier", "2") : "2";
        String containerTargetRate = container != null ? container.getExtra("frameGenTargetRate", "0") : "0";
        String containerFlowScale = container != null ? container.getExtra("frameGenFlowScale", "70") : "70";

        frameGenEnabled = "1".equals(getFrameGenSetting("frameGen", containerValue));
        frameGenMultiplier = clampFrameGenMultiplier(
                parseSettingInt(getFrameGenSetting("frameGenMultiplier", containerMultiplier), 2));
        frameGenTargetRate = Math.max(0,
                parseSettingInt(getFrameGenSetting("frameGenTargetRate", containerTargetRate), 0));
        frameGenFlowScale = clampFrameGenFlowScale(
                parseSettingInt(getFrameGenSetting("frameGenFlowScale", containerFlowScale), 70));

        if (frameGenEnabled
                || !com.winlator.cmod.runtime.display.lsfg.LosslessScaling.isInstalled(this)) {
            int result = com.winlator.cmod.feature.library.LosslessAutoImport.INSTANCE.sync(this).getResult();
            if (result != com.winlator.cmod.feature.library.LosslessAutoImport.RESULT_READY) {
                Log.i("XServerDisplayActivity", "Lossless shader sync at launch: result=" + result);
            }
        }

        java.io.File cache = com.winlator.cmod.runtime.display.lsfg.LosslessScaling
                .resolveCacheFile(this, true);
        frameGenCachePath = cache != null ? cache.getAbsolutePath() : null;
        if (frameGenCachePath == null) {
            if (frameGenEnabled) {
                Log.w("XServerDisplayActivity", "frameGen requested but no Lossless shader cache");
            }
            frameGenEnabled = false;
        }

        applyFrameGeneration(renderer);
    }

    private void applyFrameGeneration(VulkanRenderer renderer) {
        if (renderer == null) return;

        if (!frameGenEnabled || frameGenCachePath == null) {
            renderer.setFrameGenerationEnabled(false);
            syncFrameGenerationHud();
            return;
        }

        renderer.setFrameGenerationShaders(frameGenCachePath);
        float refreshRate = applyFrameGenerationDisplayMode();
        renderer.setFrameGenerationMode(frameGenMultiplier, frameGenTargetRate, frameGenFlowScale);
        frameGenRefreshRate = refreshRate;
        renderer.setFrameGenerationRefreshRate(refreshRate);
        renderer.setFrameGenerationEnabled(true);
        syncFrameGenerationHud();
        Log.i("XServerDisplayActivity", "Frame generation on: multiplier=" + frameGenMultiplier
                + " targetRate=" + frameGenTargetRate + " flowScale=" + frameGenFlowScale
                + " refreshRate=" + refreshRate);
    }

    private void syncFrameGenerationHud() {
        boolean active = frameGenEnabled && frameGenCachePath != null;
        FrameRating.OutputFrameSource source = frameGenEnabled ? frameGenOutputSource : null;
        if (frameRating != null) {
            frameRating.setOutputFrameSource(source);
            frameRating.setFrameGenerationActive(active);
        }
        if (mangoHud != null) {
            mangoHud.setOutputFrameSource(source);
            mangoHud.setFrameGenerationActive(active);
        }
    }

    private final FrameRating.OutputFrameSource frameGenOutputSource =
            new FrameRating.OutputFrameSource() {
                @Override
                public long getPresentedFrameCount() {
                    VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
                    return renderer != null ? renderer.getPresentedFrameCount() : 0L;
                }

                @Override
                public long getGeneratedFrameCount() {
                    VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
                    return renderer != null ? renderer.getGeneratedFrameCount() : 0L;
                }
            };

    private float applyFrameGenerationDisplayMode() {
        android.view.Window window = getWindow();
        if (window == null) return 0f;

        android.view.WindowManager.LayoutParams params = window.getAttributes();
        if (!frameGenEnabled) {
            if (params.preferredDisplayModeId != 0) {
                params.preferredDisplayModeId = 0;
                window.setAttributes(params);
            }
            return 0f;
        }

        android.view.Display display = getDisplayCompat();
        if (display == null) return 0f;

        android.view.Display.Mode active = display.getMode();
        int wanted;
        if (frameGenTargetRate > 0) {
            wanted = frameGenTargetRate;
        } else if (runtimeFpsLimit > 0) {
            wanted = frameGenMultiplier * runtimeFpsLimit;
        } else {
            wanted = Integer.MAX_VALUE;
        }

        android.view.Display.Mode best = null;
        for (android.view.Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() != active.getPhysicalWidth()
                    || mode.getPhysicalHeight() != active.getPhysicalHeight()) {
                continue;
            }
            if (best == null || betterFrameGenMode(mode, best, wanted, runtimeFpsLimit)) best = mode;
        }
        if (best == null) return active.getRefreshRate();
        if (best.getModeId() == params.preferredDisplayModeId && params.preferredRefreshRate == 0f) {
            return best.getRefreshRate();
        }

        params.preferredDisplayModeId = best.getModeId();
        params.preferredRefreshRate = 0f;
        window.setAttributes(params);
        Log.i("XServerDisplayActivity", "Frame generation display mode: wanted "
                + (wanted == Integer.MAX_VALUE ? "highest" : wanted + "Hz")
                + ", selected " + Math.round(best.getRefreshRate()) + "Hz (mode "
                + best.getModeId() + ") fpsLimit=" + runtimeFpsLimit + " cadenceOk="
                + (runtimeFpsLimit <= 0
                        || RefreshRateUtils.isFrameCadenceCompatible(
                                best.getRefreshRate(), runtimeFpsLimit)));
        return best.getRefreshRate();
    }

    private static boolean betterFrameGenMode(android.view.Display.Mode candidate,
                                              android.view.Display.Mode current, int wanted,
                                              int fpsLimit) {
        float a = candidate.getRefreshRate();
        float b = current.getRefreshRate();
        boolean aMeets = a + 0.5f >= wanted;
        boolean bMeets = b + 0.5f >= wanted;
        if (aMeets != bMeets) return aMeets;
        if (!aMeets) return a > b;
        if (fpsLimit > 0) {
            boolean aCadence = RefreshRateUtils.isFrameCadenceCompatible(a, fpsLimit);
            boolean bCadence = RefreshRateUtils.isFrameCadenceCompatible(b, fpsLimit);
            if (aCadence != bCadence) return aCadence;
        }
        return a < b;
    }

    private android.view.Display getDisplayCompat() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.Display d = getDisplay();
            if (d != null) return d;
        }
        android.view.WindowManager wm = getWindowManager();
        return wm != null ? wm.getDefaultDisplay() : null;
    }

    private void applyFrameGenerationLive() {
        applyFrameGeneration(xServerView != null ? xServerView.getRenderer() : null);
        if (!frameGenEnabled || frameGenCachePath == null) applyPreferredRefreshRate();
        saveFrameGenerationSettings();
        renderDrawerMenu();
    }

    private void saveFrameGenerationSettings() {
        if (shortcut != null) {
            boolean overridden = saveFrameGenOverride("frameGen", frameGenEnabled ? "1" : "0", "0");
            overridden |= saveFrameGenOverride("frameGenMultiplier",
                    String.valueOf(frameGenMultiplier), "2");
            overridden |= saveFrameGenOverride("frameGenTargetRate",
                    String.valueOf(frameGenTargetRate), "0");
            overridden |= saveFrameGenOverride("frameGenFlowScale",
                    String.valueOf(frameGenFlowScale), "70");
            if (overridden) shortcut.putExtra("use_container_defaults", "0");
            shortcut.saveData();
        } else if (container != null) {
            container.putExtra("frameGen", frameGenEnabled ? "1" : "0");
            container.putExtra("frameGenMultiplier", String.valueOf(frameGenMultiplier));
            container.putExtra("frameGenTargetRate", String.valueOf(frameGenTargetRate));
            container.putExtra("frameGenFlowScale", String.valueOf(frameGenFlowScale));
            container.saveData();
        }
    }

    private static int clampFrameGenMultiplier(int value) {
        return Math.max(2, Math.min(4, value));
    }

    private static int clampFrameGenFlowScale(int value) {
        return Math.max(25, Math.min(100, value));
    }

    private static int parseSettingInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // paramsJson is nested {"<effect>":{uniform:value}} when nested, else the flat legacy map
    private static class ResolvedReshade {
        java.util.List<com.winlator.cmod.runtime.reshade.ReshadeLoadout.Entry> loadout;
        String mode;
        String paramsJson;
        boolean nested;
        String legacyEffect;
        boolean masterEnabled = true;
    }

    // container config stays authoritative until the shortcut has both own-settings and a reshade extra,
    // so a loadout is never mixed half-shortcut half-container
    private boolean reshadeShortcutOwns() {
        if (shortcut == null || shortcut.usesContainerDefaults()) return false;
        return shortcut.getExtra(ReshadeConfigWriter.EXTRA_LOADOUT, null) != null
                || shortcut.getExtra(ReshadeConfigWriter.EXTRA_EFFECT, null) != null;
    }

    // read as one unit from a single source; ReshadeLoadout.parse migrates legacy single-effect saves
    private ResolvedReshade resolveReshade() {
        ResolvedReshade r = new ResolvedReshade();
        r.mode = com.winlator.cmod.runtime.reshade.ReshadeLoadout.MODE_SOLO;
        r.legacyEffect = "None";
        if (container == null) {
            r.loadout = new java.util.ArrayList<>();
            r.nested = false;
            return r;
        }
        String loadoutJson, mode, paramsJson, legacyEffect;
        if (reshadeShortcutOwns()) {
            loadoutJson  = emptyToNull(shortcut.getExtra(ReshadeConfigWriter.EXTRA_LOADOUT, null));
            mode         = shortcut.getExtra(ReshadeConfigWriter.EXTRA_MODE, "solo");
            paramsJson   = emptyToNull(shortcut.getExtra(ReshadeConfigWriter.EXTRA_PARAMS, null));
            legacyEffect = shortcut.getExtra(ReshadeConfigWriter.EXTRA_EFFECT, "None");
            r.masterEnabled = !"0".equals(shortcut.getExtra(ReshadeConfigWriter.EXTRA_MASTER, "1"));
        } else {
            loadoutJson  = emptyToNull(container.getExtra(ReshadeConfigWriter.EXTRA_LOADOUT, null));
            mode         = container.getExtra(ReshadeConfigWriter.EXTRA_MODE, "solo");
            paramsJson   = emptyToNull(container.getExtra(ReshadeConfigWriter.EXTRA_PARAMS, null));
            legacyEffect = container.getExtra(ReshadeConfigWriter.EXTRA_EFFECT, "None");
            r.masterEnabled = !"0".equals(container.getExtra(ReshadeConfigWriter.EXTRA_MASTER, "1"));
        }
        r.nested = loadoutJson != null && !loadoutJson.isEmpty();
        r.loadout = com.winlator.cmod.runtime.reshade.ReshadeLoadout.parse(loadoutJson, legacyEffect);
        r.mode = com.winlator.cmod.runtime.reshade.ReshadeLoadout.normalizeMode(mode);
        r.paramsJson = paramsJson;
        r.legacyEffect = legacyEffect;
        com.winlator.cmod.runtime.reshade.ReshadeLoadout.enforceSolo(r.loadout, r.mode);
        return r;
    }

    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }

    // swallowed: a reshade failure must never break a launch
    private void applyReshadeEnv(EnvVars envVars) {
        try {
            if (container == null || imageFs == null) return;
            ResolvedReshade rr = resolveReshade();
            boolean vulkanWrapper = ReshadeConfigWriter.supportedFor(this.dxwrapper);
            boolean applied = ReshadeConfigWriter.applyLoadout(this, imageFs, rr.loadout, rr.paramsJson,
                    rr.nested, rr.legacyEffect, rr.masterEnabled, vulkanWrapper, envVars);
            reshadeSessionAvailable = applied;
            reshadeMasterEnabled = rr.masterEnabled;
            reshadeMode = rr.mode;
            if (applied) seedReshadeLive(rr);
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "ReShade env injection failed (ignored)", e);
        }
    }

    // only effects present in the drop-in folder become tunable
    private void seedReshadeLive(ResolvedReshade rr) {
        reshadeLive.clear();
        for (com.winlator.cmod.runtime.reshade.ReshadeLoadout.Entry entry : rr.loadout) {
            ReshadeManager.ReshadeEffect effect = ReshadeManager.findEffect(this, entry.name);
            if (effect == null) continue;
            org.json.JSONObject saved = com.winlator.cmod.runtime.reshade.ReshadeLoadout.paramsForEffect(
                    rr.paramsJson, effect.name, rr.nested, rr.legacyEffect);
            java.util.LinkedHashMap<String, Float> values = new java.util.LinkedHashMap<>();
            for (ReshadeManager.ReshadeParam p : effect.params) ReshadeManager.seedValues(p, saved, values);
            reshadeLive.add(new ReshadeLiveEffect(effect.name, entry.enabled, effect.params, values));
        }
    }

    private java.util.ArrayList<ReshadeLoadoutItem> buildReshadeItems() {
        java.util.ArrayList<ReshadeLoadoutItem> items = new java.util.ArrayList<>();
        for (ReshadeLiveEffect e : reshadeLive) {
            items.add(new ReshadeLoadoutItem(e.name, e.enabled, e.defs, new java.util.LinkedHashMap<>(e.values)));
        }
        return items;
    }

    // conf rewrite bumps mtime -> live reload without restage; a defaults-following shortcut must persist
    // to the container so use_container_defaults is not flipped mid-session
    private void applyReshadeLive() {
        try {
            if (imageFs == null) return;
            java.util.ArrayList<com.winlator.cmod.runtime.reshade.ReshadeLoadout.Entry> entries = new java.util.ArrayList<>();
            org.json.JSONObject nestedParams = new org.json.JSONObject();
            for (ReshadeLiveEffect e : reshadeLive) {
                entries.add(new com.winlator.cmod.runtime.reshade.ReshadeLoadout.Entry(e.name, e.enabled));
                if (!e.values.isEmpty()) {
                    org.json.JSONObject eff = new org.json.JSONObject();
                    for (java.util.Map.Entry<String, Float> v : e.values.entrySet()) eff.put(v.getKey(), v.getValue().doubleValue());
                    nestedParams.put(e.name, eff);
                }
            }
            String loadoutJson = com.winlator.cmod.runtime.reshade.ReshadeLoadout.serialize(entries);
            String paramsJson = nestedParams.length() == 0 ? null : nestedParams.toString();
            String firstEffect = entries.isEmpty() ? null : entries.get(0).name;

            pendingReshadeWrite = new ReshadeLiveSnapshot(entries, loadoutJson, paramsJson, firstEffect,
                    reshadeMode, reshadeMasterEnabled);

            if (reshadeLiveHandler == null) {
                reshadeLiveThread = new android.os.HandlerThread("reshade-live");
                reshadeLiveThread.start();
                reshadeLiveHandler = new android.os.Handler(reshadeLiveThread.getLooper());
            }
            reshadeLiveHandler.removeCallbacks(reshadeLiveWriteTask);
            reshadeLiveHandler.postDelayed(reshadeLiveWriteTask, RESHADE_LIVE_DEBOUNCE_MS);
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "applyReshadeLive failed (ignored)", e);
        }
    }

    private final Runnable reshadeLiveWriteTask = () -> {
        ReshadeLiveSnapshot s = pendingReshadeWrite;
        if (s == null || imageFs == null) return;
        try {
            if (shortcut != null && !shortcut.usesContainerDefaults()) {
                shortcut.putExtra(ReshadeConfigWriter.EXTRA_LOADOUT, s.entries.isEmpty() ? null : s.loadoutJson);
                shortcut.putExtra(ReshadeConfigWriter.EXTRA_MODE, s.mode);
                shortcut.putExtra(ReshadeConfigWriter.EXTRA_PARAMS, s.paramsJson);
                shortcut.putExtra(ReshadeConfigWriter.EXTRA_EFFECT, s.firstEffect);
                shortcut.putExtra(ReshadeConfigWriter.EXTRA_MASTER, s.masterEnabled ? null : "0");
                shortcut.saveData();
            } else if (container != null) {
                container.putExtra(ReshadeConfigWriter.EXTRA_LOADOUT, s.entries.isEmpty() ? null : s.loadoutJson);
                container.putExtra(ReshadeConfigWriter.EXTRA_MODE, s.mode);
                container.putExtra(ReshadeConfigWriter.EXTRA_PARAMS, s.paramsJson);
                container.putExtra(ReshadeConfigWriter.EXTRA_EFFECT, s.firstEffect);
                container.putExtra(ReshadeConfigWriter.EXTRA_MASTER, s.masterEnabled ? "1" : "0");
                container.saveData();
            }

            // masterEnabled writes enableOnLaunch; per-effect flags ride each <ei>_enabled gate
            ReshadeConfigWriter.writeMergedConfig(this, imageFs, s.entries, s.paramsJson, s.paramsJson != null,
                    s.firstEffect, s.masterEnabled, false);
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "applyReshadeLive failed (ignored)", e);
        }
    };

    private boolean getBooleanSessionOption(String key, boolean defaultValue) {
        boolean fallback = preferences != null ? preferences.getBoolean(key, defaultValue) : defaultValue;
        if (shortcut == null) return fallback;
        String rawValue = shortcut.getExtra(key, String.valueOf(fallback));
        return parseBoolean(rawValue);
    }

    private void setBooleanSessionOption(String key, boolean value) {
        if (shortcut != null) {
            shortcut.putExtra(key, String.valueOf(value));
            shortcut.saveData();
        } else if (preferences != null) {
            preferences.edit().putBoolean(key, value).apply();
        }
    }

    private String getShortcutWineVersionOverride() {
        if (shortcut == null || shortcutUsesContainerDefaults()) return "";
        return shortcut.getExtra("wineVersion");
    }

    private void applyPreferredRefreshRate() {
        Runnable applyRefresh = () -> {
            if (isFinishing() || isDestroyed()) return;

            if (frameGenEnabled && frameGenCachePath != null) {
                float refreshRate = applyFrameGenerationDisplayMode();
                VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
                frameGenRefreshRate = refreshRate;
                if (renderer != null) renderer.setFrameGenerationRefreshRate(refreshRate);
                return;
            }
            RefreshRateUtils.applyPreferredRefreshRate(this, getRefreshRateOverride(), runtimeFpsLimit);
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyRefresh.run();
        } else {
            runOnUiThread(applyRefresh);
        }
    }

    /** Watch for display refresh-rate / mode changes while a game runs so the FPS-limiter ceiling and chosen limit aren't stranded above what the panel can present. */
    private void registerDisplayChangeListener() {
        if (displayListener != null) return;
        displayManager = (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) return;
        lastKnownMaxRefreshRate = RefreshRateUtils.getMaxSupportedRefreshRate(this);
        displayListener = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                handleDisplayCapabilitiesChanged();
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                handleDisplayCapabilitiesChanged();
            }

            @Override
            public void onDisplayChanged(int displayId) {
                handleDisplayCapabilitiesChanged();
            }
        };
        // Callbacks are delivered on the main thread via this handler.
        displayManager.registerDisplayListener(displayListener, handler);
    }

    private void unregisterDisplayChangeListener() {
        if (displayManager != null && displayListener != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (Exception ignored) {}
        }
        displayListener = null;
    }

    private void handleDisplayCapabilitiesChanged() {
        if (isFinishing() || isDestroyed()) return;

        int maxRate = RefreshRateUtils.getMaxSupportedRefreshRate(this);
        boolean maxChanged = maxRate != lastKnownMaxRefreshRate;
        lastKnownMaxRefreshRate = maxRate;

        // If the panel can no longer reach the configured limit, cap it so we don't render above what it can show.
        if (runtimeFpsLimit > 0 && runtimeFpsLimit > maxRate) {
            runtimeFpsLimit = maxRate;
            if (xServerView != null && xServerView.getRenderer() != null) {
                xServerView.getRenderer().setFpsLimit(runtimeFpsLimit);
            }
            if (shortcut != null) {
                shortcut.putExtra("fpsLimit", String.valueOf(runtimeFpsLimit));
                shortcut.saveData();
            }
            applyPreferredRefreshRate();
        }

        syncFrameGenerationRefreshRate();

        // Sync the in-drawer slider ceiling, but only if the drawer was opened (otherwise the next open rebuilds state fresh).
        if (maxChanged && drawerStateHolder != null) {
            renderDrawerMenu();
        }
    }

    private void syncFrameGenerationRefreshRate() {
        if (!frameGenEnabled || frameGenCachePath == null) return;

        android.view.Display display = getDisplayCompat();
        if (display == null) return;

        float active = display.getMode().getRefreshRate();
        if (active <= 0f || Math.abs(active - frameGenRefreshRate) < 0.5f) return;

        Log.i("XServerDisplayActivity", "Frame generation panel changed: "
                + Math.round(frameGenRefreshRate) + "Hz -> " + Math.round(active) + "Hz");
        frameGenRefreshRate = active;
        VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
        if (renderer != null) renderer.setFrameGenerationRefreshRate(active);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;

        if ("com.winnative.cmod.DEBUG_INJECT_TAP".equals(intent.getAction())) {
            handleDebugInjectTap(intent);
            return;
        }

        if ("com.winnative.cmod.DEBUG_INJECT_KEY".equals(intent.getAction())) {
            handleDebugInjectKey(intent);
            return;
        }

        String incomingShortcutPath = intent.getStringExtra("shortcut_path");
        String incomingShortcutUuid = intent.getStringExtra("shortcut_uuid");
        int incomingContainerId = intent.getIntExtra("container_id", 0);
        String incomingBootExe = intent.getStringExtra("boot_exe");
        String currentShortcutPath = shortcut != null ? shortcut.file.getAbsolutePath() : "";
        String currentShortcutUuid = shortcut != null ? shortcut.getExtra("uuid") : "";
        int currentContainerId = container != null ? container.id : 0;
        String currentBootExe = bootExePath != null ? bootExePath : "";

        setIntent(intent);
        launchedFromPinnedShortcut = isPinnedShortcutLaunchIntent(intent);

        boolean shortcutChanged = incomingShortcutPath != null
                && !incomingShortcutPath.isEmpty()
                && !incomingShortcutPath.equals(currentShortcutPath);
        boolean shortcutUuidChanged = incomingShortcutUuid != null
                && !incomingShortcutUuid.isEmpty()
                && !incomingShortcutUuid.equals(currentShortcutUuid);
        boolean containerChanged = incomingContainerId != 0 && incomingContainerId != currentContainerId;
        boolean bootExeChanged = !(incomingBootExe != null ? incomingBootExe : "").equals(currentBootExe);

        if (shortcutChanged || shortcutUuidChanged || containerChanged || bootExeChanged) {
            Log.d("XServerDisplayActivity", "onNewIntent: launch target changed, cleaning up before recreation");
            switchLaunchTargetAfterCleanup(intent);
        }
    }

    private void handleDebugInjectKey(Intent intent) {
        if (xServer == null) {
            Log.w("XServerDisplayActivity", "DEBUG_INJECT_KEY: xServer not ready");
            return;
        }
        String key = intent.getStringExtra("key");
        if (key == null || key.isEmpty()) {
            Log.w("XServerDisplayActivity", "DEBUG_INJECT_KEY: missing `key` extra");
            return;
        }
        com.winlator.cmod.runtime.display.xserver.XKeycode kc;
        try {
            kc = com.winlator.cmod.runtime.display.xserver.XKeycode
                    .valueOf("KEY_" + key.toUpperCase());
        } catch (IllegalArgumentException e) {
            Log.w("XServerDisplayActivity", "DEBUG_INJECT_KEY: unknown key " + key);
            return;
        }
        int holdMs = intent.getIntExtra("hold_ms", 80);
        Log.i("XServerDisplayActivity", "DEBUG_INJECT_KEY: " + kc + " hold=" + holdMs);
        xServer.injectKeyPress(kc);
        final com.winlator.cmod.runtime.display.xserver.XKeycode finalKc = kc;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> xServer.injectKeyRelease(finalKc), holdMs);
    }

    private void handleDebugInjectTap(Intent intent) {
        if (xServer == null) {
            Log.w("XServerDisplayActivity", "DEBUG_INJECT_TAP: xServer not ready");
            return;
        }
        int x = intent.getIntExtra("x", -1);
        int y = intent.getIntExtra("y", -1);
        float nx = intent.getFloatExtra("nx", -1f);
        float ny = intent.getFloatExtra("ny", -1f);
        if (nx >= 0f && ny >= 0f) {
            x = (int) (nx * xServer.screenInfo.width);
            y = (int) (ny * xServer.screenInfo.height);
        }
        String button = intent.getStringExtra("button");
        if (x < 0 || y < 0) {
            Log.w("XServerDisplayActivity", "DEBUG_INJECT_TAP: bad coords x=" + x + " y=" + y);
            return;
        }
        final Pointer.Button btn = "right".equalsIgnoreCase(button)
                ? Pointer.Button.BUTTON_RIGHT
                : Pointer.Button.BUTTON_LEFT;
        int pressDelay = intent.getIntExtra("press_delay", 80);
        int holdMs = intent.getIntExtra("hold_ms", 80);
        boolean useWinHandler = intent.getBooleanExtra("winhandler", true);
        final int btnDown = btn == Pointer.Button.BUTTON_RIGHT
                ? MouseEventFlags.RIGHTDOWN
                : MouseEventFlags.LEFTDOWN;
        final int btnUp = btn == Pointer.Button.BUTTON_RIGHT
                ? MouseEventFlags.RIGHTUP
                : MouseEventFlags.LEFTUP;
        Log.i("XServerDisplayActivity",
                "DEBUG_INJECT_TAP: x=" + x + " y=" + y + " btn=" + btn
                        + " screen=" + xServer.screenInfo.width + "x" + xServer.screenInfo.height
                        + " press_delay=" + pressDelay + " hold_ms=" + holdMs
                        + " winhandler=" + useWinHandler);
        xServer.injectPointerMove(x, y);
        if (useWinHandler && xServer.getWinHandler() != null) {
            xServer.getWinHandler().mouseEvent(
                    MouseEventFlags.MOVE | MouseEventFlags.ABSOLUTE, x, y, 0);
        }
        final int finalX = x;
        final int finalY = y;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> {
            xServer.injectPointerButtonPress(btn);
            if (useWinHandler && xServer.getWinHandler() != null) {
                xServer.getWinHandler().mouseEvent(
                        btnDown | MouseEventFlags.ABSOLUTE, finalX, finalY, 0);
            }
            h.postDelayed(() -> {
                xServer.injectPointerButtonRelease(btn);
                if (useWinHandler && xServer.getWinHandler() != null) {
                    xServer.getWinHandler().mouseEvent(
                            btnUp | MouseEventFlags.ABSOLUTE, finalX, finalY, 0);
                }
            }, holdMs);
        }, pressDelay);
    }

    private void switchLaunchTargetAfterCleanup(Intent intent) {
        if (!switchLaunchInProgress.compareAndSet(false, true)) {
            Log.d("XServerDisplayActivity", "Switch launch already in progress; ignoring duplicate target intent");
            return;
        }

        Intent relaunchIntent = new Intent(intent);
        relaunchIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        setIntent(relaunchIntent);
        exitRequested.set(true);

        if (preloaderDialog != null) {
            preloaderDialog.showOnUiThread(getString(R.string.preloader_initializing));
        }

        new Thread(() -> {
            performForcedSessionCleanup("switch launch target");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    Log.w("XServerDisplayActivity", "Switch cleanup finished after activity was destroyed");
                    return;
                }
                setIntent(relaunchIntent);
                recreate();
            });
        }, "XServerSwitchCleanup").start();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) isPaused = savedInstanceState.getBoolean("isPaused", false);
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                android.app.KeyguardManager km = (android.app.KeyguardManager)
                    getSystemService(Context.KEYGUARD_SERVICE);
                if (km != null && km.isKeyguardLocked()) {
                    km.requestDismissKeyguard(this, null);
                }
            } catch (Throwable t) {
                Log.w("XServerDisplayActivity",
                    "requestDismissKeyguard failed: " + t.getMessage());
            }
        }
        DebugFragment.Companion.cleanupSharedLogs();
        com.winlator.cmod.runtime.system.LogManager.prepareForNewSession(this);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        com.winlator.cmod.runtime.system.ApplicationLogGate.refresh(this);
        applyPreferredRefreshRate();
        launchedFromPinnedShortcut = isPinnedShortcutLaunchIntent(getIntent());
        
        setContentView(R.layout.xserver_display_activity);
        xServerDisplayFrame = new FrameLayout(this);
        xServerDisplayFrame.setId(R.id.FLXServerDisplay);
        xServerDisplayFrame.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ControllerManager.getInstance().init(this);
        registerControllerAutoHideListener();

        preloaderDialog = new PreloaderDialog(this);

        try {
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("winnative-xserver");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to acquire MulticastLock", e);
        }

        dualSeriesBattery = preferences.getBoolean(FrameRating.PREF_HUD_DUAL_SERIES_BATTERY, false);
        frametimeNumericMode = preferences.getBoolean(FrameRating.PREF_HUD_FRAMETIME_NUMERIC, false);

        isDarkMode = preferences.getBoolean("dark_mode", false);
        isTapToClickEnabled = true;
        // Force the touchscreen-controls overlay on at each session start (profile default stays none).
        preferences.edit().putBoolean("show_touchscreen_controls_enabled", true).apply();
        boolean isOpenWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean isShareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);

        winHandler = new WinHandler(this);
        winHandlerStopped.set(false);
        winHandler.initializeController();
        controller = winHandler.getCurrentController();

        if (isOpenWithAndroidBrowser || isShareAndroidClipboard)
            wineRequestHandler = new WineRequestHandler(this);

        if (controller != null) {
            // Only force a type when explicitly chosen; else keep the auto-detected value.
            int triggerType = preferences.getInt("trigger_type", -1);
            if (triggerType != -1) {
                controller.setTriggerType((byte) triggerType);
            }
        }



        boolean xinputDisabledFromShortcut = false;




        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gyroRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        preferences.registerOnSharedPreferenceChangeListener(prefListener);

        registerGyroSensorIfEnabled();



        startTime = System.currentTimeMillis();

        handler = new Handler(Looper.getMainLooper());

        savePlaytimeRunnable = new Runnable() {
            @Override
            public void run() {
                savePlaytimeData();
                handler.postDelayed(this, SAVE_INTERVAL_MS);
            }
        };
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);

        registerDisplayChangeListener();

        hideControlsRunnable = () -> {
            if (!isMouseDisabled && xServer != null && xServer.getRenderer() != null
                    && xServer.getRenderer().isCursorVisible()) {
                xServer.getRenderer().setCursorVisible(false);
                Log.d("XServerDisplayActivity", "Mouse cursor hidden after inactivity.");
            }
        };


        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();

        displayHostComposeView = findViewById(R.id.XServerDisplayHost);
        displayHostComposeView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            WindowInsetsCompat compatInsets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets, view);
            WindowInsetsCompat clearedInsets = new WindowInsetsCompat.Builder(compatInsets)
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
                    .build();
            android.view.WindowInsets platformInsets = clearedInsets.toWindowInsets();
            return platformInsets != null ? platformInsets : windowInsets;
        });

        enableLogsMenu = preferences.getBoolean("enable_wine_debug", false)
                || preferences.getBoolean("enable_emulator_logs", false);
        // Native rendering (DRI3) is always on; the toggle was removed. Hardcoded so stale "use_dri3=false" prefs can't disable it.
        isNativeRenderingEnabled = true;
        displayHostComposeView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_ARROW));
        displayHostComposeView.setFocusable(true);
        displayHostComposeView.setFocusableInTouchMode(true);
        displayHostComposeView.setOnFocusChangeListener((v, hasFocus) -> navigationFocused = hasFocus);
        renderDrawerMenu();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleNavigationBackPressed();
            }
        });

        // Limit Android gesture exclusion to the drawer edge swipe zone.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            final android.view.View gestureExclusionView = displayHostComposeView;
            final int edgePx = (int) (XServerDisplayHostKt.XSERVER_DRAWER_EDGE_SWIPE_DP * getResources().getDisplayMetrics().density);
            final Runnable applyExclusion = () -> {
                if (gestureExclusionView.getHeight() <= 0) return;
                gestureExclusionView.setSystemGestureExclusionRects(
                        java.util.Collections.singletonList(
                                new android.graphics.Rect(0, 0, edgePx, gestureExclusionView.getHeight())));
            };
            gestureExclusionView.post(applyExclusion);
            gestureExclusionView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> applyExclusion.run());
        }

        imageFs = ImageFs.find(this);
        GuestProgramLauncherComponent.ensureImageFsNativeLibrary(this, imageFs, "libfakeinput.so");
        GuestProgramLauncherComponent.ensureImageFsNativeLibrary(this, imageFs, "libandroid-sysvshm.so");
        File devInputDir = new File(imageFs.getRootDir(), "dev/input");
        boolean reattachingLiveSession = SessionKeepAliveService.isSessionActive()
                && SessionKeepAliveService.getActiveEnvironment() != null
                && SessionKeepAliveService.getActiveXServer() != null;
        if (devInputDir.exists() || devInputDir.mkdirs()) {
            // On reattach the running guest still holds these nodes; deleting them breaks its live input mapping.
            if (!reattachingLiveSession) {
                for (int i = 0; i < 4; i++) {
                    File eventFile = new File(devInputDir, "event" + i);
                    if (eventFile.exists()) {
                        eventFile.delete();
                    }
                }
            }
        }
        winHandler.setFakeInputPath(devInputDir.getAbsolutePath());

        String screenSize = Container.DEFAULT_SCREEN_SIZE;
        containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
        loadHUDSettings();

        int containerId = getIntent().getIntExtra("container_id", 0);
        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        String shortcutUuid = getIntent().getStringExtra("shortcut_uuid");
        int shortcutPathHash = getIntent().getIntExtra("shortcut_path_hash", 0);
        bootExePath = getIntent().getStringExtra("boot_exe");
        bootExeArgs = getIntent().getStringExtra("boot_exe_args");
        isDependencyInstall = getIntent().getBooleanExtra("is_dependency_installer", false);

        android.net.Uri launchData = getIntent().getData();
        if (launchData != null) {
            try {
                String uriUuid = launchData.getQueryParameter("uuid");
                String uriContainer = launchData.getQueryParameter("container");
                String uriHash = launchData.getQueryParameter("hash");

                if ((shortcutUuid == null || shortcutUuid.isEmpty()) && uriUuid != null && !uriUuid.isEmpty()) {
                    shortcutUuid = uriUuid;
                }
                if (containerId == 0 && uriContainer != null && !uriContainer.isEmpty()) {
                    try {
                        containerId = Integer.parseInt(uriContainer);
                    } catch (NumberFormatException ignored) {}
                }
                if (shortcutPathHash == 0 && uriHash != null && !uriHash.isEmpty()) {
                    try {
                        shortcutPathHash = Integer.parseInt(uriHash);
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception e) {
                Log.e("XServerDisplayActivity", "Failed to parse shortcut URI fallback", e);
            }
        }

        if ((shortcutPath == null || shortcutPath.isEmpty()) && launchData != null) {
            String dataPath = resolveDesktopPathFromUri(launchData);
            if (dataPath != null && !dataPath.isEmpty()) {
                shortcutPath = dataPath;
                Log.d("XServerDisplayActivity", "Resolved shortcut path from VIEW data: " + shortcutPath);
            }
        }

        Shortcut resolvedShortcut = null;
        if (shortcutUuid != null && !shortcutUuid.isEmpty()) {
            resolvedShortcut = findShortcutByUuid(shortcutUuid, containerId);
        }
        if (resolvedShortcut == null && shortcutPathHash != 0) {
            resolvedShortcut = findShortcutByPathHash(shortcutPathHash, containerId);
        }
        if (resolvedShortcut == null && shortcutPath != null && !shortcutPath.isEmpty()) {
            resolvedShortcut = findShortcutByAbsolutePath(shortcutPath, containerId);
        }
        if (resolvedShortcut != null) {
            shortcutPath = resolvedShortcut.file.getAbsolutePath();
            containerId = resolvedShortcut.container.id;
            Log.d("XServerDisplayActivity", "Resolved launch target from shortcut identity: " + shortcutPath + " (container " + containerId + ")");
        } else {
            File shortcutPathFile = (shortcutPath != null && !shortcutPath.isEmpty()) ? new File(shortcutPath) : null;
            boolean hasUsablePath = shortcutPathFile != null && shortcutPathFile.isFile();
            if (!hasUsablePath) {
                Log.w("XServerDisplayActivity", "Shortcut path from intent is not usable and no shortcut identity match was found");
                boolean launchedFromShortcutIdentity = (shortcutUuid != null && !shortcutUuid.isEmpty())
                        || shortcutPathHash != 0
                        || (shortcutPath != null && !shortcutPath.isEmpty());
                if (launchedFromShortcutIdentity) {
                    disableUnavailablePinnedShortcut(containerId, shortcutUuid, shortcutPath, shortcutPathHash);
                    WinToast.show(this, R.string.shortcuts_list_not_available);
                    finish();
                    return;
                }
            }
        }

        Log.d("XServerDisplayActivity", "Shortcut Path: " + shortcutPath);
        Log.d("XServerDisplayActivity", "Shortcut UUID: " + shortcutUuid + ", pathHash=" + shortcutPathHash);
        Log.d("XServerDisplayActivity", "Container ID from Intent: " + containerId);
        if (containerId == 0) {
            Log.d("XServerDisplayActivity", "Container ID is 0, attempting to parse from .desktop file");
        }


        if (containerId == 0 && shortcutPath != null && !shortcutPath.isEmpty()) {
            File shortcutFile = new File(shortcutPath);
            containerId = parseContainerIdFromDesktopFile(shortcutFile);
            Log.d("XServerDisplayActivity", "Parsed Container ID from .desktop file: " + containerId);
        }

        playtimePrefs = getSharedPreferences("playtime_stats", MODE_PRIVATE);
        shortcutName = getIntent().getStringExtra("shortcut_name");

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            if (shortcutName == null || shortcutName.isEmpty()) {
                shortcutName = parseShortcutNameFromDesktopFile(new File(shortcutPath));
                Log.d("XServerDisplayActivity", "Parsed Shortcut Name from .desktop file: " + shortcutName);
            }
        } else {
            Log.d("XServerDisplayActivity", "No shortcut path provided, skipping shortcut parsing.");
        }

        if (shortcutName != null) {
            shortcutName = shortcutName.replaceAll("[^A-Za-z0-9 _-]", "");
        }

        incrementPlayCount();

        Log.d("XServerDisplayActivity", "Final Container ID: " + containerId);

        container = containerManager.getContainerById(containerId);

        if (container == null) {
            Log.e("XServerDisplayActivity", "Failed to retrieve container with ID: " + containerId);
            finish();
            return;
        }

        if (!containerManager.activateContainer(container)) {
            Log.e("XServerDisplayActivity", "Failed to activate container with ID: " + containerId);
            finish();
            return;
        }

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            shortcut = new Shortcut(container, new File(shortcutPath));
        }

        if (shortcut != null
                && com.winlator.cmod.feature.retro.RetroShortcuts.isRetroShortcut(shortcut)) {
            com.winlator.cmod.feature.retro.RetroShortcuts.launch(this, shortcut);
            finish();
            return;
        }

        loadScreenEffectsSettings();

        boolean recordToFile = preferences.getBoolean("hud_record_to_file", false);
        perfController = new SessionRecordingController(this);
        // Only sample per-frame stats when recording to file is on; nothing else consumes them.
        if (recordToFile) perfController.start(shortcut, container, recordToFile);

        int numControllers = 1;
        if (shortcut != null) {
            try {
                numControllers = Integer.parseInt(shortcut.getExtra("numControllers", "1"));
            } catch (NumberFormatException e) {
                numControllers = 1;
            }
        }
        numControllers = Math.max(1, Math.min(numControllers, 4));
        for (int i = 0; i < numControllers; i++) {
            try {
                new File(devInputDir, "event" + i).createNewFile();
            } catch (Exception e) {
            }
        }

        String containerCpuList = container.getCPUList(true);
        String containerCpuListWoW64 = container.getCPUListWoW64(true);
        String effectiveCpuList = containerCpuList;
        String effectiveCpuListWoW64 = containerCpuListWoW64;
        taskAffinityMask = ProcessHelper.getAffinityMask(containerCpuList);
        taskAffinityMaskWoW64 = ProcessHelper.getAffinityMask(containerCpuListWoW64);

        String rawShortcutCpuList = "";
        String rawShortcutCpuListWoW64 = "";
        if (shortcut != null) {
            boolean cpuShortcutUsesDefaults = shortcutUsesContainerDefaults();
            rawShortcutCpuList = cpuShortcutUsesDefaults ? "" : shortcut.getExtra("cpuList");
            rawShortcutCpuListWoW64 = cpuShortcutUsesDefaults ? "" : shortcut.getExtra("cpuListWoW64");
            effectiveCpuList = getShortcutSetting("cpuList", containerCpuList);
            effectiveCpuListWoW64 = getShortcutSetting("cpuListWoW64", containerCpuListWoW64);
            taskAffinityMask = ProcessHelper.getAffinityMask(effectiveCpuList);
            taskAffinityMaskWoW64 = ProcessHelper.getAffinityMask(effectiveCpuListWoW64);
        }
        Log.d("XServerDisplayActivity", "CPUList source=shortcutOrContainer shortcutRaw='" +
                rawShortcutCpuList + "' container='" + containerCpuList +
                "' effective='" + effectiveCpuList + "' affinityMask=0x" +
                Integer.toHexString(taskAffinityMask & 0xFFFF));
        Log.d("XServerDisplayActivity", "CPUListWoW64 source=shortcutOrContainer shortcutRaw='" +
                rawShortcutCpuListWoW64 + "' container='" + containerCpuListWoW64 +
                "' effective='" + effectiveCpuListWoW64 + "' affinityMask=0x" +
                Integer.toHexString(taskAffinityMaskWoW64 & 0xFFFF));

        String wmClass = shortcut != null ? shortcut.getExtra("wmClass", "") : "";
        Log.d("XServerDisplayActivity", "Startup wmClass: " + wmClass);

        firstTimeBoot = container.getExtra("appVersion").isEmpty();

        String containerWineVersion = container.getWineVersion();
        wineVersion = containerWineVersion;
        String rawShortcutWineVersion = "";
        if (shortcut != null) {
            String shortcutWineVersion = getShortcutWineVersionOverride();
            rawShortcutWineVersion = shortcutWineVersion != null ? shortcutWineVersion : "";
            if (shortcutWineVersion != null && !shortcutWineVersion.isEmpty()) {
                wineVersion = shortcutWineVersion;
            }
        }
        Log.d("XServerDisplayActivity", "WineVersion source=shortcutOrContainer shortcutRaw='" +
                rawShortcutWineVersion + "' container='" + containerWineVersion +
                "' effective='" + wineVersion + "'");
        if (!ensureRequestedWineVersionInstalled()) {
            return;
        }
        wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersion);

        imageFs.setWinePath(wineInfo.path);

        ProcessHelper.removeAllDebugCallbacks();
        if (enableLogsMenu) {
            attachLogStreamSink();
        }

        graphicsDriver = container.getGraphicsDriver();
        zinkMode = container.getZinkMode();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriver = container.getAudioDriver();
        emulator = container.getEmulator();
        midiSoundFont = container.getMIDISoundFont();
        dxwrapper = container.getDXWrapper();
        String dxwrapperConfig = container.getDXWrapperConfig();
        screenSize = container.getScreenSize();
        winHandler.setInputType((byte) container.getInputType());
        lc_all = container.getLC_ALL();

        Intent intent = getIntent();
        Log.d("XServerDisplayActivity", "Intent Extras: " + intent.getExtras());

        if (shortcut != null) {
            String containerIdOverride = shortcut.getExtra("container_id");
            if (!containerIdOverride.isEmpty()) {
                int newContainerId = Integer.parseInt(containerIdOverride);
                if (newContainerId != container.id) {
                    container = containerManager.getContainerById(newContainerId);
                    if (container == null) {
                        Log.e("XServerDisplayActivity", "Failed to retrieve overridden container with ID: " + newContainerId);
                        finish();
                        return;
                    }
                    if (!containerManager.activateContainer(container)) {
                        Log.e("XServerDisplayActivity", "Failed to activate overridden container with ID: " + newContainerId);
                        finish();
                        return;
                    }
                    Log.d("XServerDisplayActivity", "Container overridden to ID: " + newContainerId);

                    String reevalContainerWineVersion = container.getWineVersion();
                    wineVersion = reevalContainerWineVersion;
                    String shortcutWineVersion = getShortcutWineVersionOverride();
                    String reevalRawShortcutWineVersion = shortcutWineVersion != null ? shortcutWineVersion : "";
                    if (shortcutWineVersion != null && !shortcutWineVersion.isEmpty()) {
                        wineVersion = shortcutWineVersion;
                    }
                    Log.d("XServerDisplayActivity", "WineVersion (post container-override) source=shortcutOrContainer shortcutRaw='" +
                            reevalRawShortcutWineVersion + "' container='" + reevalContainerWineVersion +
                            "' effective='" + wineVersion + "'");
                    if (!ensureRequestedWineVersionInstalled()) {
                        return;
                    }
                    wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersion);
                    imageFs.setWinePath(wineInfo.path);
                }
            }

            String gameSource = shortcut.getExtra("game_source");
            if ("STEAM".equals(gameSource)) {
                String appIdStr = shortcut.getExtra("app_id");
                if (!appIdStr.isEmpty()) {
                    String gameInstallPath = resolveSteamGameInstallPath(Integer.parseInt(appIdStr));
                    if (new File(gameInstallPath).exists()) {
                        shortcut.putExtra("game_install_path", gameInstallPath);
                        shortcut.saveData();
                    }
                }
            } else if ("EPIC".equals(gameSource)) {
                String gameInstallPath = shortcut.getExtra("game_install_path");
                if (gameInstallPath.isEmpty()) {
                    String appIdStr = shortcut.getExtra("app_id");
                    if (!appIdStr.isEmpty()) {
                        try {
                            com.winlator.cmod.feature.stores.epic.data.EpicGame epicGame = com.winlator.cmod.feature.stores.epic.service.EpicService.Companion.getEpicGameOf(Integer.parseInt(appIdStr));
                            if (epicGame != null) {
                                String resolved = epicGame.getInstallPath();
                                if (resolved == null || resolved.isEmpty()) {
                                    resolved = com.winlator.cmod.feature.stores.epic.service.EpicConstants.INSTANCE.getGameInstallPath(this, epicGame.getAppName());
                                }
                                if (resolved != null && !resolved.isEmpty()) {
                                    gameInstallPath = resolved;
                                    shortcut.putExtra("game_install_path", gameInstallPath);
                                    shortcut.saveData();
                                    Log.d("XServerDisplayActivity", "Resolved missing Epic install path from service: " + gameInstallPath);
                                }
                            }
                        } catch (Exception e) {
                            Log.e("XServerDisplayActivity", "Failed to resolve Epic install path from app_id", e);
                        }
                    }
                }
                if (!gameInstallPath.isEmpty() && new File(gameInstallPath).exists()) {
                    shortcut.putExtra("game_install_path", gameInstallPath);
                    shortcut.saveData();
                } else {
                    Log.e("XServerDisplayActivity", "EPIC install path missing or invalid: '" + gameInstallPath + "'");
                }
            } else if ("GOG".equals(gameSource)) {
                String gameInstallPath = shortcut.getExtra("game_install_path");
                if (gameInstallPath.isEmpty()) {
                    String gogId = shortcut.getExtra("gog_id");
                    if (!gogId.isEmpty()) {
                        try {
                            com.winlator.cmod.feature.stores.gog.data.GOGGame gogGame = com.winlator.cmod.feature.stores.gog.service.GOGService.Companion.getGOGGameOf(gogId);
                            if (gogGame != null) {
                                String resolved = gogGame.getInstallPath();
                                if (resolved == null || resolved.isEmpty()) {
                                    resolved = com.winlator.cmod.feature.stores.gog.service.GOGConstants.INSTANCE.getGameInstallPath(gogGame.getTitle());
                                }
                                if (resolved != null && !resolved.isEmpty()) {
                                    gameInstallPath = resolved;
                                    shortcut.putExtra("game_install_path", gameInstallPath);
                                    shortcut.saveData();
                                }
                            }
                        } catch (Exception e) {
                            Log.e("XServerDisplayActivity", "Failed to resolve GOG install path", e);
                        }
                    }
                }
                if (!gameInstallPath.isEmpty() && new File(gameInstallPath).exists()) {
                    shortcut.putExtra("game_install_path", gameInstallPath);
                    shortcut.saveData();
                } else {
                    Log.e("XServerDisplayActivity", "GOG install path missing or invalid: '" + gameInstallPath + "'");
                }
            } else if ("CUSTOM".equals(gameSource)) {
                String customMountPath = resolveCustomMountPath(shortcut);
                if (!customMountPath.isEmpty() && new File(customMountPath).isDirectory()) {
                    if (shortcut.getExtra("custom_game_folder").isEmpty() || shortcut.getExtra("game_install_path").isEmpty()) {
                        if (shortcut.getExtra("custom_game_folder").isEmpty()) {
                            shortcut.putExtra("custom_game_folder", customMountPath);
                        }
                        if (shortcut.getExtra("game_install_path").isEmpty()) {
                            shortcut.putExtra("game_install_path", customMountPath);
                        }
                        shortcut.saveData();
                    }
                } else {
                    Log.w("XServerDisplayActivity", "CUSTOM mount path missing/invalid. custom_game_folder='"
                            + shortcut.getExtra("custom_game_folder") + "' launch_exe_path='"
                            + shortcut.getExtra("launch_exe_path") + "' custom_exe='"
                            + shortcut.getExtra("custom_exe") + "' shortcut.path='" + shortcut.path + "'");
                }
            }

            boolean shortcutUsesDefaults = shortcutUsesContainerDefaults();
            String rawShortcutGraphicsDriver = shortcutUsesDefaults ? "" : shortcut.getExtra("graphicsDriver");
            String rawShortcutGraphicsDriverConfig = shortcutUsesDefaults ? "" : shortcut.getExtra("graphicsDriverConfig");
            String rawShortcutAudioDriver = shortcutUsesDefaults ? "" : shortcut.getExtra("audioDriver");
            String rawShortcutEmulator = shortcutUsesDefaults ? "" : shortcut.getExtra("emulator");
            String rawShortcutDxwrapper = shortcutUsesDefaults ? "" : shortcut.getExtra("dxwrapper");

            graphicsDriver = getShortcutSetting("graphicsDriver", container.getGraphicsDriver());
            zinkMode = getShortcutSetting("zinkMode", container.getZinkMode());
            graphicsDriverConfig = getShortcutSetting("graphicsDriverConfig", container.getGraphicsDriverConfig());
            audioDriver = getShortcutSetting("audioDriver", container.getAudioDriver());
            emulator = getShortcutSetting("emulator", container.getEmulator());
            dxwrapper = getShortcutSetting("dxwrapper", container.getDXWrapper());
            String rawShortcutDxwrapperConfig = shortcutUsesDefaults ? "" : shortcut.getExtra("dxwrapperConfig");
            dxwrapperConfig = getShortcutSetting("dxwrapperConfig", container.getDXWrapperConfig());

            Log.d("XServerDisplayActivity", "GraphicsDriver source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutGraphicsDriver + "' container='" + container.getGraphicsDriver() +
                    "' effective='" + graphicsDriver + "'");
            Log.d("XServerDisplayActivity", "GraphicsDriverConfig source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutGraphicsDriverConfig + "' container='" + container.getGraphicsDriverConfig() +
                    "' effective='" + graphicsDriverConfig + "'");
            Log.d("XServerDisplayActivity", "AudioDriver source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutAudioDriver + "' container='" + container.getAudioDriver() +
                    "' effective='" + audioDriver + "'");
            Log.d("XServerDisplayActivity", "Emulator source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutEmulator + "' container='" + container.getEmulator() +
                    "' effective='" + emulator + "'");
            Log.d("XServerDisplayActivity", "DXWrapper (version) source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutDxwrapper + "' container='" + container.getDXWrapper() +
                    "' effective='" + dxwrapper + "'");
            Log.d("XServerDisplayActivity", "DXVK launch config source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutDxwrapperConfig + "' container='" + container.getDXWrapperConfig() +
                    "' effective='" + dxwrapperConfig + "'");
            String rawShortcutScreenSize = shortcutUsesDefaults ? "" : shortcut.getExtra("screenSize");
            String rawShortcutLcAll = shortcutUsesDefaults ? "" : shortcut.getExtra("lc_all");
            String rawShortcutMidiSoundFont = shortcutUsesDefaults ? "" : shortcut.getExtra("midiSoundFont");
            String rawShortcutStartupSelection = shortcutUsesDefaults ? "" : shortcut.getExtra("startupSelection");

            screenSize = getShortcutSetting("screenSize", container.getScreenSize());
            lc_all = getShortcutSetting("lc_all", container.getLC_ALL());
            midiSoundFont = getShortcutSetting("midiSoundFont", container.getMIDISoundFont());

            Log.d("XServerDisplayActivity", "ScreenSize source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutScreenSize + "' container='" + container.getScreenSize() +
                    "' effective='" + screenSize + "'");
            Log.d("XServerDisplayActivity", "LC_ALL source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutLcAll + "' container='" + container.getLC_ALL() +
                    "' effective='" + lc_all + "'");
            Log.d("XServerDisplayActivity", "MIDISoundFont source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutMidiSoundFont + "' container='" + container.getMIDISoundFont() +
                    "' effective='" + midiSoundFont + "'");

            String inputType = shortcutUsesDefaults ? "" : shortcut.getExtra("inputType");
            if (!inputType.isEmpty()) winHandler.setInputType((byte)Integer.parseInt(inputType));
            String xinputDisabledString = getShortcutSetting("disableXinput", "false");
            xinputDisabledFromShortcut = parseBoolean(xinputDisabledString);
            winHandler.setXInputDisabled(xinputDisabledFromShortcut);
            Log.d("XServerDisplayActivity", "XInput Disabled from Shortcut: " + xinputDisabledFromShortcut);

            startupSelection = getShortcutSetting("startupSelection", String.valueOf(container.getStartupSelection()));
            Log.d("XServerDisplayActivity", "StartupSelection source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutStartupSelection + "' container='" + container.getStartupSelection() +
                    "' effective='" + startupSelection + "'");
        } else {
            startupSelection = String.valueOf(container.getStartupSelection());
            Log.d("XServerDisplayActivity", "StartupSelection source=container (no shortcut) effective='" +
                    startupSelection + "'");
        }

        this.graphicsDriverConfig = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(graphicsDriverConfig);
        this.dxwrapperConfig = DXVKConfigUtils.parseConfig(dxwrapperConfig);
        Log.i("XServerDisplayActivity", "Launch DX wrapper selected: dxwrapper='" +
                dxwrapper + "' dxvkVersion='" + this.dxwrapperConfig.get("version") +
                "' vkd3dVersion='" + this.dxwrapperConfig.get("vkd3dVersion") +
                "' ddrawrapper='" + this.dxwrapperConfig.get("ddrawrapper") + "'");
        applyPreferredRefreshRate();

        if (!wineInfo.isWin64()) {
            onExtractFileListener = (file, size) -> {
                String path = file.getPath();
                if (path.contains("system32/")) return null;
                return new File(path.replace("syswow64/", "system32/"));
            };
        }

        cachedPreloaderBadge = shortcut != null ? shortcut.getExtra("game_source") : "";
        if (cachedPreloaderBadge == null) cachedPreloaderBadge = "";
        cachedPreloaderTitle = (shortcutName != null && !shortcutName.isEmpty())
                ? shortcutName
                : getString(R.string.preloader_default_name);
        cachedPreloaderSubtitle = container != null ? container.getName() : "";
        showLaunchPreloader(getString(R.string.preloader_initializing));

        // Dependency-install sessions must not become background/reattachable sessions.
        if (!isDependencyInstall && preferences.getBoolean("enable_background_session", false)) {
            SessionKeepAliveService.startSession(this);
        }

        inputControlsManager = new InputControlsManager(this);
        gestureProfileManager = new GestureProfileManager(this);
        sgsrBaseScreenSize = screenSize;
        String effectiveScreenSize =
                SGSRResolutionUtils.applyRenderScale(screenSize, sgsrEnabled, sgsrUpscaleMode);
        if (!effectiveScreenSize.equals(screenSize)) {
            Log.i("XServerDisplayActivity", "SGSR render scale active: container='" + screenSize +
                    "' effective='" + effectiveScreenSize + "' mode=" + sgsrUpscaleMode);
        }
        xServer = new XServer(new ScreenInfo(effectiveScreenSize), isNativeRenderingEnabled);
        sgsrRuntimeEnabled = sgsrEnabled;
        xServer.setWinHandler(winHandler);

        boolean[] winStarted = {false};

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (!winStarted[0] && window.isApplicationWindow()) {
                    if (!isMouseDisabled) {
                        touchpadView.setMouseEnabled(true);
                    } else {
                        xServerView.getRenderer().setCursorVisible(false);
                    }
                    if (!wnLauncherDrivesDismiss.get()) {
                        preloaderDialog.closeOnUiThread();
                        stopWnLauncherStatusTailer();
                    }
                    winStarted[0] = true;
                    runOnUiThread(() -> {
                        inputControlsRevealAllowed = true;
                        if (inputControlsView != null) {
                            ControlsProfile activeProfile = inputControlsView.getProfile();
                            if (activeProfile != null) showInputControls(activeProfile);
                            else startTouchscreenTimeout();
                        }
                        String localeWarning = pendingLocaleManifestWarning.getAndSet(null);
                        if (localeWarning != null) WinToast.show(XServerDisplayActivity.this, localeWarning);
                    });
                    if (startFullscreenStretched) {
                        timeoutHandler.post(() -> {
                            if (activityDestroyed.get()) return;
                            VulkanRenderer r = xServerView != null ? xServerView.getRenderer() : null;
                            if (r != null && !r.isFullscreen()) {
                                r.toggleFullscreen();
                                touchpadView.toggleFullscreen();
                                renderDrawerMenu();
                            }
                        });
                    }
                }
            }
           
            @Override
            public void onMapWindow(Window window) {
                assignTaskAffinity(window);
                pinServiceAffinity();
                if ((effectiveShowFPS || controllerHudMode) && frameRating != null) {
                    syncFrameRatingWithExistingWindows();
                }
            }

            @Override
            public void onModifyWindowProperty(Window window, Property property) {
                changeFrameRatingVisibility(window, property);
            }    

            @Override
            public void onFramePresented(Window window, WindowManager.FrameSource source, int serial) {
                if (window != null && window.id == rendererWindowId) rendererWindowPresented = true;
                if (shouldRecordFpsFrame(window, source)) {
                    frameRating.recordGameFrame(source == WindowManager.FrameSource.PRESENT, serial);
                    if (mangoHud != null) mangoHud.recordGameFrame(source == WindowManager.FrameSource.PRESENT);
                }
            }

            @Override
            public void onDestroyWindow(Window window) {
                changeFrameRatingVisibility(window, null);
            }
        });

        if (!midiSoundFont.equals("")) {
            InputStream in = null;
            InputStream finalIn = in;
            MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() {
                @Override
                public void onSuccess(SF2Soundbank soundbank) {
                    midiHandler = new MidiHandler();
                    midiHandler.setSoundBank(soundbank);
                    midiHandler.start();
                }

                @Override
                public void onFailed(Exception e) {
                    try {
                        finalIn.close();
                    } catch (Exception e2) {}
                }
            };
            try {
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    in = getAssets().open(MidiManager.SF2_ASSETS_DIR + "/" + midiSoundFont);
                    MidiManager.load(in, callback);
                } else
                    MidiManager.load(new File(MidiManager.getSoundFontDir(this), midiSoundFont), callback);
            } catch (Exception e) {}
        }

        String controlsProfile = shortcut != null ? shortcut.getExtra("controlsProfile", "") : "";

        Runnable runnable = () -> {
            setupUI();
            if (controlsProfile.isEmpty()) {
                simulateConfirmInputControlsDialog();
            }
            Executors.newSingleThreadExecutor().execute(() -> {
                boolean sessionToReuse = SessionKeepAliveService.isSessionActive() &&
                        SessionKeepAliveService.getActiveEnvironment() != null &&
                        SessionKeepAliveService.getActiveXServer() != null;

                UpdateService.INSTANCE.cancelPostGameCheck();

                if (!sessionToReuse) {
                    if (isSteamShortcut()) {
                        try {
                            setSteamClientVisibility(true, isColdClientEnabledForShortcut());
                        } catch (Throwable t) {
                            Log.w("XServerDisplayActivity",
                                    "Failed to select Steam client store before cloud sync", t);
                        }
                    }

                    // Parallel prep (cloud sync + Steam prefix DLL/asset setup), joined before setupXEnvironment so the launcher sees a complete prefix.
                    java.util.concurrent.ExecutorService prepExec =
                            java.util.concurrent.Executors.newFixedThreadPool(2);
                    java.util.concurrent.Future<?> cloudFuture = prepExec.submit(() -> {
                        try {
                            if (steamCloudHandledByAgent()) {
                                Log.i("XServerDisplayActivity",
                                        "Steam cloud pre-launch sync skipped — the Steam Launcher "
                                                + "agent runs RunAutoCloudOnAppLaunch inside the "
                                                + "prefix");
                            } else {
                                SteamLaunchCloudSync.syncBeforeLaunch(
                                        this, shortcut, isCloudSyncEnabledForShortcut(),
                                        this::showLaunchPreloader);
                            }
                            EpicLaunchCloudSync.syncBeforeLaunch(
                                    this, shortcut, isCloudSyncEnabledForShortcut(),
                                    this::showLaunchPreloader);
                            GogLaunchCloudSync.syncBeforeLaunch(
                                    this, shortcut, isCloudSyncEnabledForShortcut(),
                                    this::showLaunchPreloader);
                        } catch (Throwable t) {
                            Log.w("XServerDisplayActivity",
                                    "Pre-launch cloud sync failed", t);
                        }
                    });
                    java.util.concurrent.Future<?> steamFuture = isSteamShortcut()
                            ? prepExec.submit(() -> {
                                try {
                                    setupSteamGameFiles();
                                } catch (Throwable t) {
                                    Log.w("XServerDisplayActivity",
                                            "Pre-launch Steam game setup failed", t);
                                }
                            })
                            : null;
                    prepExec.shutdown();

                    if (preloaderDialog != null && isSteamShortcut()) {
                        preloaderDialog.setStepOnUiThread(R.string.preloader_preparing_steam_environment);
                    }
                    setupWineSystemFiles();
                    extractGraphicsDriverFiles();
                    changeWineAudioDriver();

                    try {
                        if (steamFuture != null) steamFuture.get();
                    } catch (Throwable t) {
                        Log.w("XServerDisplayActivity",
                                "Steam game setup wait interrupted", t);
                    }
                    try {
                        cloudFuture.get();
                    } catch (Throwable t) {
                        Log.w("XServerDisplayActivity",
                                "Cloud sync wait interrupted", t);
                    }
                } else {
                    Log.i("XServerDisplayActivity", "Skipping pre-game setup for active background session");
                    applyPreferredRefreshRate();
                }

                try {
                    setupXEnvironment();
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });
        };

        boolean targetPortrait = xServer.screenInfo.height > xServer.screenInfo.width;
        int targetOrientation = targetPortrait
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        int currentOrientation = getResources().getConfiguration().orientation;
        boolean alreadyTargetOrientation = targetPortrait
                ? currentOrientation == Configuration.ORIENTATION_PORTRAIT
                : currentOrientation == Configuration.ORIENTATION_LANDSCAPE;

        setRequestedOrientation(targetOrientation);
        if (alreadyTargetOrientation) {
            runnable.run();
        } else {
            configChangedCallback = runnable;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (configChangedCallback == runnable) {
                    configChangedCallback.run();
                    configChangedCallback = null;
                }
            }, 1000);
        }
    }

    private String resolveDesktopPathFromUri(android.net.Uri uri) {
        if (uri == null) return null;
        try {
            String scheme = uri.getScheme();
            if ("file".equalsIgnoreCase(scheme)) {
                return uri.getPath();
            }
            if ("content".equalsIgnoreCase(scheme)) {
                return com.winlator.cmod.shared.io.FileUtils.getFilePathFromUri(this, uri);
            }
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Failed to resolve .desktop path from URI", e);
        }
        return null;
    }

    private int parseContainerIdFromDesktopFile(File desktopFile) {
        int containerId = 0;
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("container_id:") || trimmed.startsWith("container_id=")) {
                        int sep = trimmed.indexOf(':');
                        if (sep == -1) sep = trimmed.indexOf('=');
                        if (sep != -1 && sep + 1 < trimmed.length()) {
                            containerId = Integer.parseInt(trimmed.substring(sep + 1).trim());
                            break;
                        }
                    }
                }
            } catch (IOException | NumberFormatException e) {
                Log.e("XServerDisplayActivity", "Error parsing container_id from .desktop file", e);
            }
        }
        return containerId;
    }

    @Nullable private Shortcut findShortcutByUuid(String uuid, int preferredContainerId) {
        if (uuid == null || uuid.isEmpty() || containerManager == null) return null;
        try {
            Shortcut fallback = null;
            for (Shortcut sc : containerManager.loadShortcuts()) {
                if (!uuid.equals(sc.getExtra("uuid"))) continue;
                if (preferredContainerId > 0 && sc.container != null && sc.container.id == preferredContainerId) {
                    return sc;
                }
                if (fallback == null) fallback = sc;
            }
            return fallback;
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Failed to resolve shortcut by uuid: " + uuid, e);
        }
        return null;
    }

    @Nullable private Shortcut findShortcutByPathHash(int pathHash, int preferredContainerId) {
        if (pathHash == 0 || containerManager == null) return null;
        try {
            Shortcut fallback = null;
            for (Shortcut sc : containerManager.loadShortcuts()) {
                if (sc.file == null || sc.file.getAbsolutePath().hashCode() != pathHash) continue;
                if (preferredContainerId > 0 && sc.container != null && sc.container.id == preferredContainerId) {
                    return sc;
                }
                if (fallback == null) fallback = sc;
            }
            return fallback;
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Failed to resolve shortcut by path hash: " + pathHash, e);
        }
        return null;
    }

    @Nullable private Shortcut findShortcutByAbsolutePath(String absolutePath, int preferredContainerId) {
        if (absolutePath == null || absolutePath.isEmpty() || containerManager == null) return null;
        try {
            Shortcut fallback = null;
            for (Shortcut sc : containerManager.loadShortcuts()) {
                if (sc.file == null) continue;
                if (!absolutePath.equals(sc.file.getAbsolutePath())) continue;
                if (preferredContainerId > 0 && sc.container != null && sc.container.id == preferredContainerId) {
                    return sc;
                }
                if (fallback == null) fallback = sc;
            }
            return fallback;
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Failed to resolve shortcut by absolute path", e);
        }
        return null;
    }

    private void disableUnavailablePinnedShortcut(int containerId, @Nullable String shortcutUuid, @Nullable String shortcutPath, int shortcutPathHash) {
        ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
        if (shortcutManager == null) return;

        ArrayList<String> shortcutIds = ShortcutsFragment.buildPinnedShortcutIds(containerId, shortcutUuid, shortcutPath);
        if ((shortcutPath == null || shortcutPath.isEmpty()) && shortcutUuid != null && !shortcutUuid.isEmpty()
                && containerId > 0 && shortcutPathHash != 0) {
            shortcutIds.add(
                    "shortcut_" + containerId + "_" + shortcutUuid + "_" + Integer.toUnsignedString(shortcutPathHash, 16)
            );
        }
        if (shortcutIds.isEmpty()) return;

        try {
            shortcutManager.disableShortcuts(shortcutIds, getString(R.string.shortcuts_list_not_available));
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to disable unavailable pinned shortcut", e);
        }

        try {
            shortcutManager.removeDynamicShortcuts(shortcutIds);
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to remove dynamic shortcut metadata", e);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                shortcutManager.removeLongLivedShortcuts(shortcutIds);
            } catch (Exception e) {
                Log.w("XServerDisplayActivity", "Failed to remove long-lived shortcut metadata", e);
            }
        }
    }

    private String resolveCustomMountPath(@NonNull Shortcut shortcut) {
        String customGameFolder = shortcut.getExtra("custom_game_folder");
        if (!customGameFolder.isEmpty() && new File(customGameFolder).isDirectory()) {
            return customGameFolder;
        }

        String gameInstallPath = shortcut.getExtra("game_install_path");
        if (!gameInstallPath.isEmpty() && new File(gameInstallPath).isDirectory()) {
            return gameInstallPath;
        }

        String launchExePath = shortcut.getExtra("launch_exe_path");
        String inferredFromLaunchExe = inferCustomMountPathFromExe(shortcut.path, launchExePath);
        if (!inferredFromLaunchExe.isEmpty()) return inferredFromLaunchExe;

        String customExePath = shortcut.getExtra("custom_exe");
        String inferredFromCustomExe = inferCustomMountPathFromExe(shortcut.path, customExePath);
        if (!inferredFromCustomExe.isEmpty()) return inferredFromCustomExe;

        return "";
    }

    private String inferCustomMountPathFromExe(String shortcutWinPath, String hostExePath) {
        if (hostExePath == null || hostExePath.isEmpty()) return "";
        File hostExeFile = new File(hostExePath);

        if (hostExeFile.isDirectory()) return hostExeFile.getAbsolutePath();
        if (!hostExeFile.isFile()) return "";

        if (shortcutWinPath != null && !shortcutWinPath.isEmpty()) {
            String normalizedWinPath = shortcutWinPath.replace("/", "\\");
            if (normalizedWinPath.matches("^[A-Za-z]:\\\\.*")) {
                String relativeWinPath = normalizedWinPath.substring(3);
                while (relativeWinPath.startsWith("\\")) relativeWinPath = relativeWinPath.substring(1);
                if (!relativeWinPath.isEmpty()) {
                    String relativeFsPath = relativeWinPath.replace("\\", File.separator);
                    String normalizedHostExe = hostExeFile.getAbsolutePath().replace("\\", File.separator);
                    if (normalizedHostExe.endsWith(relativeFsPath)) {
                        String root = normalizedHostExe.substring(0, normalizedHostExe.length() - relativeFsPath.length());
                        while (root.endsWith(File.separator)) {
                            root = root.substring(0, root.length() - 1);
                        }
                        if (!root.isEmpty() && new File(root).isDirectory()) {
                            return root;
                        }
                    }
                }
            }
        }

        File parent = hostExeFile.getParentFile();
        return (parent != null && parent.isDirectory()) ? parent.getAbsolutePath() : "";
    }

    private String resolveCustomExecutableWinPath(@NonNull Shortcut shortcut) {
        String customGameFolder = resolveCustomMountPath(shortcut);
        String customExe = shortcut.getExtra("custom_exe");
        if (customExe != null && !customExe.isEmpty()) {
            File customExeFile = new File(customExe);
            if (customExeFile.isFile()) {
                return mapCustomExecutableWinPath(customGameFolder, customExeFile);
            }
            return WineUtils.hostPathToRootWinePath(container, customExeFile.getAbsolutePath());
        }

        String launchExePath = shortcut.getExtra("launch_exe_path");
        if (launchExePath != null && !launchExePath.isEmpty()) {
            File launchExeFile = new File(launchExePath);
            if (launchExeFile.isFile()) {
                return mapCustomExecutableWinPath(customGameFolder, launchExeFile);
            }
            return WineUtils.hostPathToRootWinePath(container, launchExeFile.getAbsolutePath());
        }

        if (!customGameFolder.isEmpty()) {
            File exeFile = findGameExe(new File(customGameFolder));
            if (exeFile != null) {
                if ((shortcut.getExtra("launch_exe_path") == null || shortcut.getExtra("launch_exe_path").isEmpty())) {
                    shortcut.putExtra("launch_exe_path", exeFile.getAbsolutePath());
                    shortcut.saveData();
                }
                return mapCustomExecutableWinPath(customGameFolder, exeFile);
            }
        }
        return shortcut.path;
    }

    private String mapCustomExecutableWinPath(String customGameFolder, @NonNull File exeFile) {
        if (container != null && customGameFolder != null && !customGameFolder.isEmpty()) {
            String mappedPath =
                    WineUtils.resolveGameExeWindowsPath(
                            container, "CUSTOM", customGameFolder, exeFile.getAbsolutePath());
            if (mappedPath != null && !mappedPath.isEmpty()) {
                return mappedPath;
            }
        }
        return WineUtils.hostPathToRootWinePath(container, exeFile.getAbsolutePath());
    }

    private void updateShortcutExecLine(@NonNull String windowsPath) {
        if (shortcut == null) return;

        String execLine = "Exec=wine \"" + windowsPath + "\"";
        StringBuilder content = new StringBuilder();
        boolean replaced = false;
        for (String line : FileUtils.readLines(shortcut.file)) {
            if (line.startsWith("Exec=")) {
                content.append(execLine).append("\n");
                replaced = true;
            } else {
                content.append(line).append("\n");
            }
        }
        if (!replaced) {
            content.append(execLine).append("\n");
        }
        FileUtils.writeString(shortcut.file, content.toString());
    }

    private String repairStoreExecutableWinPath(String source, String gameInstallPath, String currentPath) {
        if (container == null
                || source == null
                || source.isEmpty()
                || gameInstallPath == null
                || gameInstallPath.isEmpty()
                || currentPath == null
                || currentPath.isEmpty()) {
            return currentPath;
        }

        String relativePath = extractRelativeDriveCGameExecutablePath(currentPath, source);
        if (relativePath == null || relativePath.isEmpty()) return currentPath;

        File nativeExe = new File(gameInstallPath, relativePath.replace("\\", File.separator));
        if (!nativeExe.isFile()) return currentPath;

        String repairedPath =
                WineUtils.getDriveCGameWindowsPath(
                        container, source, gameInstallPath, nativeExe.getAbsolutePath());
        if (repairedPath == null || repairedPath.isEmpty() || repairedPath.equals(currentPath)) {
            return currentPath;
        }

        updateShortcutExecLine(repairedPath);
        return repairedPath;
    }

    private String extractRelativeDriveCGameExecutablePath(String windowsPath, String source) {
        String prefix = "C:\\WinNative\\Games\\" + source + "\\";
        if (!windowsPath.regionMatches(true, 0, prefix, 0, prefix.length())) return null;

        String remainder = windowsPath.substring(prefix.length());
        int aliasSeparator = remainder.indexOf("\\");
        if (aliasSeparator < 0 || aliasSeparator + 1 >= remainder.length()) return null;
        return remainder.substring(aliasSeparator + 1);
    }

    private String getActiveGameDirectoryPath() {
        if (shortcut == null) return null;

        String[] candidatePaths = new String[] {
                shortcut.getExtra("game_install_path"),
                shortcut.getExtra("custom_game_folder"),
                shortcut.getExtra("custom_mount_path")
        };

        for (String candidatePath : candidatePaths) {
            if (candidatePath == null || candidatePath.isEmpty()) continue;
            return new File(candidatePath).getAbsolutePath();
        }

        return null;
    }

    private boolean isSuspiciousSteamGameInstallDir(String path) {
        if (path == null || path.isEmpty()) return false;

        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith("/steamapps/common") || lower.endsWith("/steamapps");
    }

    private boolean isSuspiciousSteamInstallLeaf(String value) {
        if (value == null || value.isEmpty()) return false;

        String normalized = value.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < normalized.length()) {
            normalized = normalized.substring(lastSlash + 1);
        }

        return "common".equalsIgnoreCase(normalized) || "steamapps".equalsIgnoreCase(normalized);
    }

    private String normalizeRelativeExeCandidate(String value) {
        if (value == null) return "";

        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.matches("^[A-Za-z]:/.*")) {
            normalized = normalized.substring(3);
        }
        return normalized;
    }

    private File resolveImmediateChildCaseInsensitive(File parent, String childName) {
        if (parent == null || childName == null || childName.isEmpty() || !parent.isDirectory()) {
            return null;
        }

        File directChild = new File(parent, childName);
        if (directChild.exists()) return directChild;

        File[] children = parent.listFiles();
        if (children == null) return null;

        for (File child : children) {
            if (childName.equalsIgnoreCase(child.getName())) {
                return child;
            }
        }
        return null;
    }

    private boolean hasKnownGameExeUnderDir(File dir, java.util.List<String> relativeExeCandidates) {
        if (dir == null || !dir.isDirectory()) return false;

        for (String relativeExe : relativeExeCandidates) {
            File candidateExe = resolvePathCaseInsensitive(dir, relativeExe);
            if (candidateExe != null && candidateExe.isFile()) {
                return true;
            }
        }

        return findGameExe(dir) != null;
    }

    private String recoverSteamGameInstallPath(int appId, String libraryRootPath) {
        if (libraryRootPath == null || libraryRootPath.isEmpty()) return null;

        File libraryRoot = new File(libraryRootPath);
        if (!libraryRoot.isDirectory()) return null;

        java.util.LinkedHashSet<String> relativeExeCandidates = new java.util.LinkedHashSet<>();
        String shortcutLaunchExe = shortcut != null ? shortcut.getExtra("launch_exe_path") : "";
        String containerLaunchExe = container != null ? container.getExecutablePath() : "";
        String installedExe = SteamBridge.getInstalledExe(appId);

        for (String candidate : new String[]{shortcutLaunchExe, containerLaunchExe, installedExe}) {
            String normalized = normalizeRelativeExeCandidate(candidate);
            if (!normalized.isEmpty()) {
                relativeExeCandidates.add(normalized);
            }
        }

        java.util.LinkedHashSet<String> preferredDirNames = new java.util.LinkedHashSet<>();
        if (shortcut != null && shortcut.name != null && !shortcut.name.trim().isEmpty()) {
            preferredDirNames.add(shortcut.name.trim());
        }

        String serviceInstallPath = SteamBridge.getAppDirPath(appId);
        if (serviceInstallPath != null && !serviceInstallPath.isEmpty()) {
            String serviceLeaf = new File(serviceInstallPath).getName();
            if (!serviceLeaf.isEmpty()) {
                preferredDirNames.add(serviceLeaf);
            }
        }

        java.util.ArrayList<String> relativeExeList = new java.util.ArrayList<>(relativeExeCandidates);
        for (String preferredDirName : preferredDirNames) {
            if (isSuspiciousSteamInstallLeaf(preferredDirName)) continue;

            File preferredDir = resolveImmediateChildCaseInsensitive(libraryRoot, preferredDirName);
            if (preferredDir != null && preferredDir.isDirectory()
                    && hasKnownGameExeUnderDir(preferredDir, relativeExeList)) {
                return getCanonicalPathOrAbsolute(preferredDir);
            }
        }

        if (relativeExeList.isEmpty()) return null;

        java.util.ArrayList<File> matches = new java.util.ArrayList<>();
        File[] children = libraryRoot.listFiles();
        if (children == null) return null;

        for (File child : children) {
            if (!child.isDirectory()) continue;
            if (hasKnownGameExeUnderDir(child, relativeExeList)) {
                matches.add(child);
            }
        }

        if (matches.size() == 1) {
            return getCanonicalPathOrAbsolute(matches.get(0));
        }

        if (matches.size() > 1) {
            Log.w("XServerDisplayActivity",
                    "Ambiguous Steam install-path recovery for appId=" + appId
                            + " libraryRoot=" + libraryRootPath
                            + " matches=" + matches.size());
        }
        return null;
    }

    private String sanitizeSteamGameInstallPath(int appId, String candidatePath, String source) {
        if (candidatePath == null || candidatePath.isEmpty()) return candidatePath;

        File candidateDir = new File(candidatePath);
        if (!candidateDir.isDirectory()) return candidatePath;

        String canonicalPath = getCanonicalPathOrAbsolute(candidateDir);
        if (!isSuspiciousSteamGameInstallDir(canonicalPath)) {
            return canonicalPath;
        }

        String recoveredPath = recoverSteamGameInstallPath(appId, canonicalPath);
        if (recoveredPath != null && !recoveredPath.isEmpty()) {
            Log.w("XServerDisplayActivity",
                    "Recovered Steam game install path from " + source
                            + " root " + canonicalPath + " -> " + recoveredPath
                            + " for appId=" + appId);
            return recoveredPath;
        }

        Log.w("XServerDisplayActivity",
                "Ignoring suspicious Steam game install path from " + source
                        + " for appId=" + appId + ": " + canonicalPath);
        return null;
    }

    private String resolveSteamGameInstallPath(int appId) {
        if (shortcut != null) {
            String shortcutInstallPath = shortcut.getExtra("game_install_path");
            String resolvedShortcutInstallPath =
                    sanitizeSteamGameInstallPath(appId, shortcutInstallPath, "shortcut");
            if (resolvedShortcutInstallPath != null && !resolvedShortcutInstallPath.isEmpty()) {
                if (!resolvedShortcutInstallPath.equals(shortcutInstallPath)) {
                    shortcut.putExtra("game_install_path", resolvedShortcutInstallPath);
                    shortcut.saveData();
                }
                return resolvedShortcutInstallPath;
            }
        }

        String serviceInstallPath = SteamBridge.getAppDirPath(appId);
        if (serviceInstallPath == null || serviceInstallPath.isEmpty()) return serviceInstallPath;

        String resolvedServiceInstallPath =
                sanitizeSteamGameInstallPath(appId, serviceInstallPath, "service");
        if (resolvedServiceInstallPath == null || resolvedServiceInstallPath.isEmpty()) {
            return serviceInstallPath;
        }

        File serviceInstallDir = new File(resolvedServiceInstallPath);
        if (serviceInstallDir.isDirectory() && shortcut != null) {
            String shortcutInstallPath = shortcut.getExtra("game_install_path");
            String canonicalInstallPath = getCanonicalPathOrAbsolute(serviceInstallDir);
            if (!canonicalInstallPath.equals(shortcutInstallPath)) {
                shortcut.putExtra("game_install_path", canonicalInstallPath);
                shortcut.saveData();
            }
        }
        return getCanonicalPathOrAbsolute(serviceInstallDir);
    }

    private boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        return false;
    }

    private void handleCapturedPointer(MotionEvent event) {
        if (isMouseDisabled) {
            return;
        }
        if (xServer.getRenderer() != null) {
            xServer.getRenderer().setCursorVisible(true);
        }
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable);
            timeoutHandler.postDelayed(hideControlsRunnable, 5000);
        }
        boolean handled = false;

        int actionButton = event.getActionButton();
        switch (event.getAction()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE);
                }
                handled = true;
                break;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
                }
                handled = true;
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                int[] delta = getCapturedPointerDelta(event);
                if (delta[0] == 0 && delta[1] == 0) break;
                if (xServer.isRelativeMouseMovement()) {
                    xServer.updatePointerForDisplayDelta(delta[0], delta[1]);
                    xServer.getWinHandler().mouseMoveDelta(delta[0], delta[1]);
                } else {
                    xServer.injectPointerMoveDelta(delta[0], delta[1]);
                }
                handled = true;
                break;
            case MotionEvent.ACTION_SCROLL:
                float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY <= -1.0f) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                } else if (scrollY >= 1.0f) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                }
                handled = true;
                break;
        }
    }

    private int[] getCapturedPointerDelta(MotionEvent event) {
        // Sum batched samples; skipping history drops movement at low refresh rates.
        final int historySize = event.getHistorySize();
        float dx = 0.0f;
        float dy = 0.0f;
        for (int i = 0; i < historySize; i++) {
            dx += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, i);
            dy += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, i);
        }
        dx += event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        dy += event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
        if (dx == 0.0f && dy == 0.0f) {
            for (int i = 0; i < historySize; i++) {
                dx += event.getHistoricalX(i);
                dy += event.getHistoricalY(i);
            }
            dx += event.getX();
            dy += event.getY();
        }
        dx *= globalCursorSpeed;
        dy *= globalCursorSpeed;
        return new int[]{
                (int)(xform[0] * dx + xform[2] * dy),
                (int)(xform[1] * dx + xform[3] * dy)
        };
    }

    private void ensureAudioFocusHandler() {
        if (audioFocusHandler != null) return;
        audioFocusHandler =
                new com.winlator.cmod.runtime.display.environment.AudioFocusHandler(
                        this,
                        () -> { if (environment != null) environment.onPause(); },
                        () -> { if (environment != null) environment.onResume(); });
    }

    @Override
    public void onResume() {
        super.onResume();
        com.winlator.cmod.feature.stores.steam.service.GameSessionState.setInGame(this, true);
        applyPreferredRefreshRate();
        registerGyroSensorIfEnabled();

        boolean cleaningUp = exitRequested.get() || sessionCleanupStarted.get() || activityDestroyed.get();

        if (!cleaningUp && environment != null) {
            xServerView.onResume();
            environment.onResume();
            ensureAudioFocusHandler();
            if (audioFocusHandler != null) audioFocusHandler.request();
        }

        if (inputControlsView != null && touchpadView != null) {
            ControlsProfile activeProfile = inputControlsView.getProfile();
            if (activeProfile == null) activeProfile = resolvePreferredStartupProfile();
            if (activeProfile != null) showInputControls(activeProfile);
            else startTouchscreenTimeout();
            evaluateControllerAutoHide();
        }

        startTime = System.currentTimeMillis();
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);

        if (!cleaningUp && !isPaused) {
            ProcessHelper.resumeAllWineProcesses();
            SessionKeepAliveService.onResumeSession(this);
        }

        if (taskManagerPaneVisible && taskManagerTimer == null) {
            startTaskManagerPolling();
        }

        if (externalDisplayController != null) externalDisplayController.start();
    }

    @Override
    public void onPause() {
        super.onPause();
        isVolumeUpPressed = false;
        isVolumeDownPressed = false;
        guideHoldPending = false;
        handler.removeCallbacks(guideHoldOpenRunnable);
        boolean gyroEnabled = preferences.getBoolean("gyro_enabled", false);

        if (gyroEnabled) {
            sensorManager.unregisterListener(gyroListener);
        }

        boolean cleaningUp = exitRequested.get() || sessionCleanupStarted.get() || activityDestroyed.get();

        if (!cleaningUp && !isInPictureInPictureMode()) {
            if (environment != null) {
                environment.onPause();
                xServerView.onPause();
            }
        }

        if (touchpadView != null) {
            touchpadView.resetInputState();
        }
        if (inputControlsView != null) {
            inputControlsView.cancelActiveTouches();
        }

        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);

        if (taskManagerTimer != null) {
            taskManagerTimer.cancel();
            taskManagerTimer = null;
            if (winHandler != null) winHandler.setOnGetProcessInfoListener(null);
            taskManagerAccum.clear();
        }

        if (externalDisplayController != null) externalDisplayController.stop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isPaused", isPaused);
    }

    private void savePlaytimeData() {
        savePlaytimeData(false);
    }

    private void savePlaytimeData(boolean synchronous) {
        long endTime = System.currentTimeMillis();
        long playtime = endTime - startTime;

        if (playtime < 0) {
            playtime = 0;
        }

        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playtimeKey = shortcutName + "_playtime";

        long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0) + playtime;
        editor.putLong(playtimeKey, totalPlaytime);
        if (synchronous) {
            editor.commit();
        } else {
            editor.apply();
        }

        startTime = System.currentTimeMillis();
    }


    private void incrementPlayCount() {
        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playCountKey = shortcutName + "_play_count";
        int playCount = playtimePrefs.getInt(playCountKey, 0) + 1;
        editor.putInt(playCountKey, playCount);
        editor.putLong(shortcutName + "_last_played", System.currentTimeMillis());
        editor.apply();
    }

    private boolean isSteamShortcut() {
        return shortcut != null && "STEAM".equals(shortcut.getExtra("game_source"));
    }

    private String normalizeProcessName(String name) {
        if (name == null) return "";

        String normalized = name.trim().replace("\"", "");
        int slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        if (slashIndex >= 0 && slashIndex + 1 < normalized.length()) {
            normalized = normalized.substring(slashIndex + 1);
        }

        normalized = normalized.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".exe")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }

    @Nullable private ArrayList<ProcessInfo> captureWinHandlerProcessSnapshot() {
        WinHandler snapshotWinHandler = winHandler;
        if (snapshotWinHandler == null) return null;

        final CountDownLatch latch = new CountDownLatch(1);
        final Object snapshotLock = new Object();
        final ArrayList<ProcessInfo> currentList = new ArrayList<>();
        final int[] expectedCount = {0};
        final OnGetProcessInfoListener previousListener = snapshotWinHandler.getOnGetProcessInfoListener();

        OnGetProcessInfoListener listener = (index, count, processInfo) -> {
            if (previousListener != null) {
                previousListener.onGetProcessInfo(index, count, processInfo);
            }

            synchronized (snapshotLock) {
                if (count == 0 && processInfo == null) {
                    latch.countDown();
                    return;
                }

                if (index == 0) {
                    currentList.clear();
                    expectedCount[0] = count;
                }

                if (processInfo != null) {
                    currentList.add(processInfo);
                }

                if (expectedCount[0] == 0 || currentList.size() >= expectedCount[0]) {
                    latch.countDown();
                }
            }
        };

        snapshotWinHandler.setOnGetProcessInfoListener(listener);
        try {
            snapshotWinHandler.listProcesses();
            if (!latch.await(STEAM_PROCESS_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w("XServerDisplayActivity", "Timed out waiting for WinHandler process snapshot");
                return null;
            }

            synchronized (snapshotLock) {
                return new ArrayList<>(currentList);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w("XServerDisplayActivity", "Interrupted while waiting for WinHandler process snapshot", e);
            return null;
        } finally {
            snapshotWinHandler.setOnGetProcessInfoListener(previousListener);
        }
    }

    private boolean shouldWatchSteamTermination(int status) {
        if (!isSteamShortcut() || winHandler == null) return false;

        if (!steamExitWatchRunning.compareAndSet(false, true)) {
            Log.d("XServerDisplayActivity", "Steam exit watch already running; ignoring duplicate termination callback");
            return true;
        }

        Log.d("XServerDisplayActivity",
                "Steam wrapper terminated with status " + status + "; watching Wine processes before exiting");

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                long startTime = System.currentTimeMillis();
                long lastNonCoreSeenAt = -1L;

                while (!exitRequested.get()
                        && !activityDestroyed.get()
                        && (System.currentTimeMillis() - startTime) < STEAM_TERMINATION_TIMEOUT_MS) {
                    
                    if (isPaused) {
                        startTime += STEAM_TERMINATION_POLL_MS;
                        if (lastNonCoreSeenAt > 0) lastNonCoreSeenAt += STEAM_TERMINATION_POLL_MS;
                        Thread.sleep(STEAM_TERMINATION_POLL_MS);
                        continue;
                    }

                    ArrayList<ProcessInfo> snapshot = captureWinHandlerProcessSnapshot();
                    if (snapshot != null) {
                        ArrayList<String> activeNames = new ArrayList<>();
                        boolean hasNonCoreProcess = false;

                        for (ProcessInfo processInfo : snapshot) {
                            String normalized = normalizeProcessName(processInfo.name);
                            if (normalized.isEmpty()) continue;

                            activeNames.add(normalized);
                            if (!STEAM_EXIT_ALLOWLIST.contains(normalized)) {
                                hasNonCoreProcess = true;
                            }
                        }

                        Log.d("XServerDisplayActivity", "Steam exit watch snapshot: " + activeNames);

                        long now = System.currentTimeMillis();
                        if (hasNonCoreProcess) {
                            lastNonCoreSeenAt = now;
                        } else if (lastNonCoreSeenAt > 0L && now - lastNonCoreSeenAt >= STEAM_TERMINATION_POLL_MS) {
                            Log.d("XServerDisplayActivity", "Steam/game processes drained; exiting session");
                            requestExitOnUiThread("steam/game processes drained");
                            return;
                        } else if (lastNonCoreSeenAt < 0L && now - startTime >= STEAM_TERMINATION_GRACE_MS) {
                            Log.d("XServerDisplayActivity",
                                    "No non-core Steam/game process appeared after wrapper exit; exiting session");
                            requestExitOnUiThread("steam wrapper exited without spawning a game");
                            return;
                        }
                    }

                    Thread.sleep(STEAM_TERMINATION_POLL_MS);
                }

                if (!exitRequested.get() && !activityDestroyed.get()) {
                    Log.d("XServerDisplayActivity", "Steam exit watch timed out; exiting session");
                    requestExitOnUiThread("steam exit watch timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w("XServerDisplayActivity", "Steam exit watch interrupted", e);
                if (!exitRequested.get() && !activityDestroyed.get()) {
                    requestExitOnUiThread("steam exit watch interrupted");
                }
            } finally {
                steamExitWatchRunning.set(false);
            }
        });

        return true;
    }

    private void cleanupLingeringSessionProcesses(String reason) {
        if (SessionKeepAliveService.isSessionActive()) {
            Log.d("XServerDisplayActivity", "Skipping lingering process cleanup from " + reason + " — session is active in background");
            return;
        }
        ArrayList<String> before = ProcessHelper.listRunningWineProcesses();
        if (before.isEmpty()) return;

        Log.w("XServerDisplayActivity", "Cleaning lingering session processes before " + reason + ": "
                + ProcessHelper.listRunningWineProcessDetails());
        ArrayList<String> remaining = ProcessHelper.terminateSessionProcessesAndWait(2000, true);
        ProcessHelper.drainDeadChildren("pre-launch cleanup");
        ProcessHelper.scheduleDeadChildReapSweep("pre-launch cleanup", 2000, 200);
        if (!remaining.isEmpty()) {
            Log.e("XServerDisplayActivity", "Session cleanup still has remaining processes after " + reason + ": "
                    + ProcessHelper.listRunningWineProcessDetails());
        } else {
            Log.i("XServerDisplayActivity", "No lingering session processes remain after " + reason);
        }
    }

    private void requestExitOnUiThread(String reason) {
        runOnUiThread(() -> {
            if (activityDestroyed.get() || isFinishing() || isDestroyed()) {
                Log.d("XServerDisplayActivity", "Skipping exit request after teardown: " + reason);
                return;
            }
            exit();
        });
    }

    private boolean beginSessionCleanup(String trigger) {
        if (sessionCleanupStarted.compareAndSet(false, true)) {
            Log.d("XServerDisplayActivity", "Starting session cleanup from " + trigger);
            try {
                if (perfController != null) perfController.stop();
            } catch (Throwable t) {
                Log.w("XServerDisplayActivity", "perfController.stop() failed", t);
            }
            return true;
        }
        Log.d("XServerDisplayActivity", "Session cleanup already in progress; ignoring " + trigger);
        return false;
    }

    private void cleanupActivityCallbacks(String trigger) {
        activityDestroyed.set(true);

        try {
            if (preferences != null) {
                preferences.unregisterOnSharedPreferenceChangeListener(prefListener);
            }
        } catch (Exception e) {
            Log.w("XServerLeakCheck", "Failed to unregister preference listener during " + trigger, e);
        }

        try {
            if (handler != null) {
                if (savePlaytimeRunnable != null) {
                    handler.removeCallbacks(savePlaytimeRunnable);
                }
                if (controllerAutoSwitchRunnable != null) {
                    handler.removeCallbacks(controllerAutoSwitchRunnable);
                }
            }
        } catch (Exception e) {
            Log.w("XServerLeakCheck", "Failed to remove handler callbacks during " + trigger, e);
        }

        try {
            if (timeoutHandler != null && hideControlsRunnable != null) {
                timeoutHandler.removeCallbacks(hideControlsRunnable);
            }
        } catch (Exception e) {
            Log.w("XServerLeakCheck", "Failed to remove pointer timeout during " + trigger, e);
        }

        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(gyroListener);
            }
        } catch (Exception e) {
            Log.w("XServerLeakCheck", "Failed to unregister sensor listener during " + trigger, e);
        }

        try {
            if (touchpadView != null) {
                touchpadView.resetInputState();
                if (touchpadView.hasPointerCapture()) touchpadView.releasePointerCapture();
                touchpadView.setOnCapturedPointerListener(null);
            }
        } catch (Exception e) {
            Log.w("XServerLeakCheck", "Failed to release pointer capture during " + trigger, e);
        }

        try {
            if (inputControlsView != null) {
                inputControlsView.cancelActiveTouches();
            }
        } catch (Exception e) {
            Log.w("XServerLeakCheck", "Failed to cancel active touches during " + trigger, e);
        }
    }

    private void stopWinHandler(String trigger) {
        WinHandler handler = winHandler;
        if (handler == null) return;
        if (!winHandlerStopped.compareAndSet(false, true)) {
            Log.d("XServerDisplayActivity", "WinHandler already stopped; ignoring duplicate request from " + trigger);
            return;
        }

        try {
            handler.stop();
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Failed to stop WinHandler from " + trigger, e);
        }
    }

    private void attachLogStreamSink() {
        boolean wineDebugEnabled = preferences.getBoolean("enable_wine_debug", false);
        boolean emulatorLogsEnabled = preferences.getBoolean("enable_emulator_logs", false);
        boolean arm64ec = wineInfo != null && wineInfo.isArm64EC();
        String emulator = container != null ? container.getEmulator() : null;
        boolean usesWowbox64 = emulator != null && emulator.equalsIgnoreCase("wowbox64");
        boolean fexActive = arm64ec && !usesWowbox64;
        boolean box64Active = !fexActive;
        boolean box64LogsEnabled = emulatorLogsEnabled && box64Active;
        boolean fexLogsEnabled = emulatorLogsEnabled && fexActive;

        sessionLogWriter = com.winlator.cmod.runtime.system.SessionLogWriter.create(
                this,
                getExecutable(),
                box64LogsEnabled,
                fexLogsEnabled,
                wineDebugEnabled,
                box64Active,
                fexActive);

        Callback<String> sink = new Callback<String>() {
            private long cachedSecond = -1;
            private String cachedPrefix = "";

            @Override
            public synchronized void call(String line) {
                long second = System.currentTimeMillis() / 1000L;
                if (second != cachedSecond) {
                    cachedSecond = second;
                    cachedPrefix = "[" + DateFormat.format("HH:mm:ss", second * 1000L) + "]  ";
                }
                String stamped = cachedPrefix + line.replace("\n", "");
                XServerDrawerStateHolder holder = drawerStateHolder;
                if (holder != null) holder.appendLogLine(stamped);
                com.winlator.cmod.runtime.system.SessionLogWriter writer = sessionLogWriter;
                if (writer != null) writer.write(stamped);
            }
        };
        logStreamSink = sink;
        ProcessHelper.addDebugCallback(sink);
    }

    private void shareLogStream() {
        new Thread(() -> {
            try {
                com.winlator.cmod.runtime.system.SessionLogWriter writer = sessionLogWriter;
                if (writer != null) writer.flush();

                File shareDir = new File(getCacheDir(), "log_shares");
                if (!shareDir.exists()) shareDir.mkdirs();
                String stamp = (String) DateFormat.format("yyyy-MM-dd_HH-mm-ss", new Date());

                File[] logFiles = com.winlator.cmod.runtime.system.LogManager.getShareableLogFiles(this);

                final File shareFile;
                final String mimeType;

                if (logFiles != null && logFiles.length > 0) {
                    File zipFile = new File(shareDir, "session_logs_" + stamp + ".zip");
                    try (java.util.zip.ZipOutputStream zos =
                                 new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile))) {
                        for (File file : logFiles) {
                            if (file == null || !file.isFile()) continue;
                            zos.putNextEntry(new java.util.zip.ZipEntry(file.getName()));
                            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                            }
                            zos.closeEntry();
                        }
                    }
                    shareFile = zipFile;
                    mimeType = "application/zip";
                } else {
                    XServerDrawerStateHolder holder = drawerStateHolder;
                    List<String> lines = holder != null ? holder.snapshotLogLines() : new ArrayList<>();
                    if (lines.isEmpty()) {
                        runOnUiThread(() ->
                                WinToast.show(this, getString(R.string.session_drawer_logs_share_empty)));
                        return;
                    }
                    File textFile = new File(shareDir, "session_logs_" + stamp + ".txt");
                    try (BufferedWriter out = new BufferedWriter(new FileWriter(textFile))) {
                        for (String line : lines) {
                            out.write(line);
                            out.write("\n");
                        }
                    }
                    shareFile = textFile;
                    mimeType = "text/plain";
                }

                runOnUiThread(() -> {
                    try {
                        String authority = getPackageName() + ".tileprovider";
                        Uri uri = FileProvider.getUriForFile(this, authority, shareFile);
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType(mimeType);
                        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.session_drawer_logs_share_subject));
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.session_drawer_logs_share_chooser)));
                    } catch (Exception e) {
                        Log.w("XServerLogs", "Failed to share log stream", e);
                        WinToast.show(this, getString(R.string.session_drawer_logs_share_failed));
                    }
                });
            } catch (Exception e) {
                Log.w("XServerLogs", "Failed to share log stream", e);
                runOnUiThread(() ->
                        WinToast.show(this, getString(R.string.session_drawer_logs_share_failed)));
            }
        }).start();
    }

    private void cleanupDebugDialog(String trigger) {
        Callback<String> sink = logStreamSink;
        if (sink != null) {
            try {
                ProcessHelper.removeDebugCallback(sink);
            } catch (Exception e) {
                Log.w("XServerLeakCheck", "Failed to remove log sink during " + trigger, e);
            }
            logStreamSink = null;
        }
        com.winlator.cmod.runtime.system.SessionLogWriter writer = sessionLogWriter;
        if (writer != null) {
            writer.close();
            sessionLogWriter = null;
        }
    }

    private void stopXServer(String trigger) {
        try {
            if (xServer != null) {
                xServer.stop();
            }
        } catch (Exception e) {
            Log.w("XServerLeakCheck", "Failed to stop XServer during " + trigger, e);
        }
    }

    private long sessionTerminateGraceMs() {
        try {
            if (isSteamShortcut()
                    && com.winlator.cmod.feature.stores.steam.utils.PrefManager
                            .INSTANCE.getWnPlanW()) {
                return 800L;
            }
        } catch (Throwable ignored) {}
        return 2000L;
    }

    private void performForcedSessionCleanup(String trigger) {
        if (!beginSessionCleanup(trigger)) {
            Log.d("XServerLeakCheck", "Forced session cleanup already ran; skipping duplicate request from " + trigger);
            return;
        }

        Log.w("XServerLeakCheck", "Starting forced session cleanup from " + trigger);
        Log.d("XServerLeakCheck", "Forced cleanup initial process snapshot: "
                + ProcessHelper.listRunningWineProcessDetails());

        try {
            if (playtimePrefs != null) {
                savePlaytimeData(true);
            }
        } catch (Exception e) {
            Log.w("XServerLeakCheck", "Failed to flush playtime during forced cleanup", e);
        }
        cleanupActivityCallbacks("forced cleanup (" + trigger + ")");

        try {
            if (preloaderDialog != null) preloaderDialog.close();
        } catch (Exception e) {
            Log.w("XServerLeakCheck", "Failed to close preloader during forced cleanup", e);
        }

        new Thread(() -> {
            performForcedEpicCloudUpload("forced cleanup (" + trigger + ")");
            performForcedGogCloudUpload("forced cleanup (" + trigger + ")");
            sanitizeSteamStateForNextSession("forced cleanup (" + trigger + ")", true);

            try {
                AppTerminationHelper.stopManagedServices(getApplicationContext(), "xserver_forced_cleanup_" + trigger);
            } catch (Exception e) {
                Log.w("XServerLeakCheck", "Failed to stop managed services during forced cleanup", e);
            }

            try {
                if (midiHandler != null) {
                    midiHandler.stop();
                    midiHandler = null;
                }
            } catch (Exception e) {
                Log.e("XServerLeakCheck", "Failed to stop MidiHandler during forced cleanup", e);
            }

            try {
                stopWinHandler("forced cleanup (" + trigger + ")");
            } catch (Exception e) {
                Log.e("XServerLeakCheck", "Failed to stop WinHandler during forced cleanup", e);
            }

            try {
                if (wineRequestHandler != null) {
                    wineRequestHandler.stop();
                    wineRequestHandler = null;
                }
            } catch (Exception e) {
                Log.e("XServerLeakCheck", "Failed to stop WineRequestHandler during forced cleanup", e);
            }

            ArrayList<String> remaining = ProcessHelper.terminateSessionProcessesAndWait(sessionTerminateGraceMs(), true);
            ProcessHelper.drainDeadChildren("forced cleanup (" + trigger + ")");
            ProcessHelper.scheduleDeadChildReapSweep("forced cleanup (" + trigger + ")", 4000, 200);

            try {
                if (environment != null) {
                    environment.stopEnvironmentComponents();
                    environment = null;
                }
            } catch (Exception e) {
                Log.e("XServerLeakCheck", "Failed to stop environment during forced cleanup", e);
            }

            stopXServer("forced cleanup (" + trigger + ")");
            xServer = null;
            xServerView = null;

            if (remaining.isEmpty()) {
                Log.i("XServerLeakCheck", "Forced session cleanup finished cleanly after " + trigger);
            } else {
                Log.e("XServerLeakCheck", "Remaining leaked session processes after forced cleanup from " + trigger + ": "
                        + ProcessHelper.listRunningWineProcessDetails());
            }
            
            runOnUiThread(() -> cleanupDebugDialog("forced cleanup (" + trigger + ")"));
        }, "XServerForcedCleanup").start();
    }

    private void exit() {
        if (activityDestroyed.get() || isFinishing() || isDestroyed()) {
            Log.d("XServerDisplayActivity", "Ignoring exit() on torn-down activity");
            return;
        }
        if (!exitRequested.compareAndSet(false, true)) {
            Log.d("XServerDisplayActivity", "Exit already in progress; ignoring duplicate request");
            return;
        }

        if (shortcutName != null && !shortcutName.isEmpty()) {
            preloaderDialog.showOnUiThread(getString(R.string.preloader_closing, shortcutName));
        } else {
            preloaderDialog.showOnUiThread(
                    getString(R.string.preloader_closing, getString(R.string.preloader_default_name)));
        }
        
        syncStoreCloudOnExit(() -> {
            handler.postDelayed(() -> {
                if (!beginSessionCleanup("exit")) {
                    return;
                }
                savePlaytimeData(true);
                cleanupActivityCallbacks("exit");
                // Teardown blocks for seconds (cloud upload + clean-shutdown wait); run off the UI thread or the closing splash freezes. UI-touching calls are marshalled back.
                new Thread(() -> {
                    sanitizeSteamStateForNextSession("exit", true);
                    if (midiHandler != null) midiHandler.stop();
                    stopWinHandler("exit");
                    if (wineRequestHandler != null) wineRequestHandler.stop();
                    ArrayList<String> remaining = ProcessHelper.terminateSessionProcessesAndWait(sessionTerminateGraceMs(), true);
                    ProcessHelper.drainDeadChildren("activity exit cleanup");
                    ProcessHelper.scheduleDeadChildReapSweep("activity exit cleanup", 4000, 200);
                    if (!remaining.isEmpty()) {
                        Log.e("XServerDisplayActivity", "Exit cleanup still has remaining session processes: " + remaining);
                    }
                    if (environment != null) {
                        environment.stopEnvironmentComponents();
                        environment = null;
                    }
                    Log.d("XServerDisplayActivity", "Process snapshot after environment stop: "
                            + ProcessHelper.listRunningWineProcessDetails());
                    stopXServer("exit");
                    wineRequestHandler = null;
                    midiHandler = null;
                    xServer = null;
                    xServerView = null;
                    SessionKeepAliveService.stopSession(XServerDisplayActivity.this);
                    runOnUiThread(() -> {
                        if (preloaderDialog != null && preloaderDialog.isShowing()) preloaderDialog.closeOnUiThread();
                        cleanupDebugDialog("exit");
                        closeAfterSessionExit();
                    });
                }, "XServerExitCleanup").start();
            }, 1000);
        });
    }

    private void closeAfterSessionExit() {
        if (launchedFromPinnedShortcut) {
            AppTerminationHelper.exitApplication(this, "shortcut_session_exit");
            return;
        }

        returnToUnifiedActivity();
    }

    private void returnToUnifiedActivity() {
        Intent intent = new Intent(this, UnifiedActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private boolean isPinnedShortcutLaunchIntent(@Nullable Intent intent) {
        if (intent == null) return false;
        if (intent.getBooleanExtra(EXTRA_LAUNCHED_FROM_PINNED_SHORTCUT, false)) return true;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return false;

        android.net.Uri data = intent.getData();
        return data != null
                && "winnative".equals(data.getScheme())
                && BuildConfig.APPLICATION_ID.equals(data.getAuthority())
                && data.getPathSegments().contains("shortcut");
    }
    
    private void syncStoreCloudOnExit(Runnable onComplete) {
        if (shortcut == null) {
            onComplete.run();
            return;
        }

        if (!isCloudSyncEnabledForShortcut() || com.winlator.cmod.feature.sync.CloudSyncHelper.isOfflineMode(shortcut)) {
            onComplete.run();
            return;
        }

        Runnable afterStoreSync = onComplete;

        String gameSource = shortcut.getExtra("game_source");
        if ("STEAM".equals(gameSource)) {
            syncSteamCloudOnExit(afterStoreSync);
            return;
        }

        if ("EPIC".equals(gameSource)) {
            syncEpicCloudOnExit(afterStoreSync);
            return;
        }

        if ("GOG".equals(gameSource)) {
            syncGogCloudOnExit(afterStoreSync);
            return;
        }

        onComplete.run();
    }

    private void runExitUploadWithRetries(
            String uploadName,
            String retryStatusMessage,
            ExitUploadAction action,
            Runnable onComplete) {
        runExitUploadWithRetries(uploadName, retryStatusMessage, 1, action, onComplete);
    }

    private void runExitUploadWithRetries(
            String uploadName,
            String retryStatusMessage,
            int attempt,
            ExitUploadAction action,
            Runnable onComplete) {
        if (activityDestroyed.get() || isFinishing() || isDestroyed()) {
            Log.w("XServerDisplayActivity", "Skipping " + uploadName + " because activity is already torn down");
            onComplete.run();
            return;
        }

        try {
            action.start(result -> {
                if (result.success) {
                    if (result.message.isEmpty()) {
                        Log.i("XServerDisplayActivity", uploadName + " succeeded on attempt " + attempt);
                    } else {
                        Log.i(
                                "XServerDisplayActivity",
                                uploadName + " succeeded on attempt " + attempt + ": " + result.message);
                    }
                    onComplete.run();
                    return;
                }

                String failureMessage = result.message.isEmpty() ? "No error message provided." : result.message;
                if (result.retryable && attempt < EXIT_CLOUD_UPLOAD_MAX_ATTEMPTS) {
                    int nextAttempt = attempt + 1;
                    long delayMs = EXIT_CLOUD_UPLOAD_RETRY_DELAY_MS * attempt;
                    Log.w(
                            "XServerDisplayActivity",
                            uploadName
                                    + " failed on attempt "
                                    + attempt
                                    + "/"
                                    + EXIT_CLOUD_UPLOAD_MAX_ATTEMPTS
                                    + ": "
                                    + failureMessage
                                    + ". Retrying in "
                                    + delayMs
                                    + "ms.");
                    if (preloaderDialog != null && retryStatusMessage != null && !retryStatusMessage.isEmpty()) {
                        preloaderDialog.showOnUiThread(
                                getString(
                                        R.string.preloader_cloud_sync_retry,
                                        retryStatusMessage,
                                        nextAttempt,
                                        EXIT_CLOUD_UPLOAD_MAX_ATTEMPTS));
                    }
                    Handler activeHandler = handler != null ? handler : new Handler(Looper.getMainLooper());
                    activeHandler.postDelayed(
                            () -> runExitUploadWithRetries(uploadName, retryStatusMessage, nextAttempt, action, onComplete),
                            delayMs);
                    return;
                }

                if (result.retryable) {
                    Log.e(
                            "XServerDisplayActivity",
                            uploadName
                                    + " failed after "
                                    + EXIT_CLOUD_UPLOAD_MAX_ATTEMPTS
                                    + " attempts: "
                                    + failureMessage);
                } else {
                    Log.w("XServerDisplayActivity", uploadName + " failed without retry: " + failureMessage);
                }
                onComplete.run();
            });
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", uploadName + " threw before upload could start", e);
            onComplete.run();
        }
    }

    private void runBlockingExitUpload(
            String workerName,
            ExitUploadBlockingAction action,
            ExitUploadCallback callback) {
        new Thread(() -> {
            ExitUploadResult result;
            try {
                result = action.run();
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : (workerName + " failed");
                result = new ExitUploadResult(false, message, true);
            }

            if (activityDestroyed.get() || isFinishing() || isDestroyed()) {
                Log.w("XServerDisplayActivity", workerName + " finished after activity teardown");
                return;
            }

            final ExitUploadResult finalResult = result;
            runOnUiThread(() -> callback.onComplete(finalResult));
        }, workerName).start();
    }

    private boolean isCloudSyncEnabledForShortcut() {
        return shortcut == null || !"1".equals(shortcut.getExtra("cloud_sync_disabled", "0"));
    }

    private boolean isOfflineModeForShortcut() {
        return shortcut != null && "1".equals(shortcut.getExtra("offline_mode", "0"));
    }

    private static final boolean STEAM_AGENT_CLOUD_ENABLED = true;

    private boolean steamCloudHandledByAgent() {
        return STEAM_AGENT_CLOUD_ENABLED
                && isSteamShortcut()
                && isBionicSteamEnabledForShortcut()
                && com.winlator.cmod.feature.stores.steam.utils.PrefManager.INSTANCE.getWnPlanW();
    }

    private void syncSteamCloudOnExit(Runnable onComplete) {
        if (steamCloudHandledByAgent()) {
            Log.i("XServerDisplayActivity",
                    "Steam cloud exit sync skipped — the Steam Launcher agent runs "
                            + "RunAutoCloudOnAppExit inside the prefix and blocks until it "
                            + "reports synchronized");
            onComplete.run();
            return;
        }

        String appId = shortcut.getExtra("app_id");
        if (appId == null || appId.isEmpty()) {
            onComplete.run();
            return;
        }

        runExitUploadWithRetries(
                "Steam cloud sync for appId=" + appId,
                getString(R.string.preloader_uploading_cloud),
                callback ->
                        SteamExitCloudSync.syncOnExit(
                                this,
                                shortcut,
                                container,
                                text -> preloaderDialog.showOnUiThread(text),
                                result ->
                                        callback.onComplete(
                                                new ExitUploadResult(
                                                        result.getSuccess(),
                                                        result.getMessage(),
                                                        result.getRetryable()))),
                onComplete);
    }

    private void syncEpicCloudOnExit(Runnable onComplete) {
        if (shortcut != null && !shortcut.getExtra("cloud_force_download").isEmpty()) {
            Log.i("XServerDisplayActivity",
                    "Epic cloud sync skipped because a container-swap download is pending");
            onComplete.run();
            return;
        }

        String appIdStr = shortcut.getExtra("app_id");
        if (appIdStr == null || appIdStr.isEmpty()) {
            onComplete.run();
            return;
        }

        final int appId;
        try {
            appId = Integer.parseInt(appIdStr);
        } catch (NumberFormatException e) {
            Log.w("XServerDisplayActivity", "Failed to parse Epic app_id for cloud sync", e);
            onComplete.run();
            return;
        }

        final Integer targetContainerId = container != null ? Integer.valueOf(container.id) : null;

        // Skip permanent no-ops: unsupported cloud saves, signed-out user, or no saves.
        if (!com.winlator.cmod.feature.stores.epic.service.EpicCloudSavesManager
                .canAttemptExitUpload(this, appId, targetContainerId)) {
            Log.i("XServerDisplayActivity",
                    "Epic cloud sync skipped for appId=" + appId
                            + " (game does not support cloud saves, user signed out, or no local save files)");
            onComplete.run();
            return;
        }

        try {
            Log.d("XServerDisplayActivity", "Syncing Epic cloud saves for appId=" + appId);
            preloaderDialog.showOnUiThread(getString(R.string.preloader_checking_cloud));

            runExitUploadWithRetries(
                    "Epic cloud sync for appId=" + appId,
                    getString(R.string.preloader_checking_cloud),
                    callback -> runBlockingExitUpload(
                            "EpicExitCloudSync",
                            () -> {
                                Object pendingAction = kotlinx.coroutines.BuildersKt.runBlocking(
                                        kotlinx.coroutines.Dispatchers.getIO(),
                                        (scope, continuation) -> com.winlator.cmod.feature.stores.epic.service.EpicCloudSavesManager.INSTANCE.getPendingExitSyncAction(
                                                this,
                                                appId,
                                                targetContainerId,
                                                continuation
                                        )
                                );
                                if (pendingAction == com.winlator.cmod.feature.stores.epic.service.EpicCloudSavesManager.SyncAction.UPLOAD) {
                                    runOnUiThread(() -> preloaderDialog.showOnUiThread(getString(R.string.preloader_uploading_cloud)));
                                }

                                Boolean syncSuccess = (Boolean) kotlinx.coroutines.BuildersKt.runBlocking(
                                        kotlinx.coroutines.Dispatchers.getIO(),
                                        (scope, continuation) -> com.winlator.cmod.feature.stores.epic.service.EpicCloudSavesManager.INSTANCE.syncCloudSaves(
                                                this,
                                                appId,
                                                "exit_upload",
                                                targetContainerId,
                                                continuation
                                        )
                                );
                                boolean success = Boolean.TRUE.equals(syncSuccess);
                                return new ExitUploadResult(
                                        success,
                                        success
                                                ? "Epic cloud sync completed."
                                                : "Epic cloud sync reported failure.",
                                        true);
                            },
                            callback),
                    onComplete);
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to start Epic cloud sync", e);
            onComplete.run();
        }
    }

    private void performForcedEpicCloudUpload(String reason) {
        if (shortcut == null || !"EPIC".equals(shortcut.getExtra("game_source"))) return;
        if (!isCloudSyncEnabledForShortcut() || CloudSyncHelper.isOfflineMode(shortcut)) return;
        if (!shortcut.getExtra("cloud_force_download").isEmpty()) {
            Log.i("XServerDisplayActivity",
                    "Forced Epic cloud upload skipped because a container-swap download is pending during " + reason);
            return;
        }

        String appIdStr = shortcut.getExtra("app_id");
        if (appIdStr == null || appIdStr.isEmpty()) return;

        final int appId;
        try {
            appId = Integer.parseInt(appIdStr);
        } catch (NumberFormatException e) {
            Log.w("XServerDisplayActivity", "Failed to parse Epic app_id for forced cloud sync", e);
            return;
        }

        try {
            final Integer targetContainerId = container != null ? Integer.valueOf(container.id) : null;
            if (!com.winlator.cmod.feature.stores.epic.service.EpicCloudSavesManager
                    .canAttemptExitUpload(this, appId, targetContainerId)) {
                Log.i("XServerDisplayActivity", "Forced Epic cloud upload skipped for appId=" + appId + " during " + reason);
                return;
            }

            Log.i("XServerDisplayActivity", "Attempting forced Epic cloud upload for appId=" + appId + " during " + reason);
            Boolean syncSuccess = (Boolean) kotlinx.coroutines.BuildersKt.runBlocking(
                    kotlinx.coroutines.Dispatchers.getIO(),
                    (scope, continuation) -> com.winlator.cmod.feature.stores.epic.service.EpicCloudSavesManager.INSTANCE.syncCloudSaves(
                            this,
                            appId,
                            "exit_upload",
                            targetContainerId,
                            continuation
                    )
            );
            Log.i("XServerDisplayActivity", "Forced Epic cloud upload result for appId=" + appId + ": " + syncSuccess);
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Forced Epic cloud upload failed during " + reason, e);
        }
    }

    private void syncGogCloudOnExit(Runnable onComplete) {
        if (shortcut != null && !shortcut.getExtra("cloud_force_download").isEmpty()) {
            Log.i("XServerDisplayActivity",
                    "GOG cloud sync skipped because a container-swap download is pending");
            onComplete.run();
            return;
        }

        String gogId = shortcut.getExtra("gog_id");
        if (gogId == null || gogId.isEmpty()) {
            onComplete.run();
            return;
        }
        final String appId = "GOG_" + gogId;
        final Integer targetContainerId = container != null ? Integer.valueOf(container.id) : null;

        if (!com.winlator.cmod.feature.stores.gog.service.GOGService
                .canAttemptExitUpload(this, appId, targetContainerId)) {
            Log.i("XServerDisplayActivity",
                    "GOG cloud sync skipped for " + appId
                            + " (no cloud-save locations, user signed out, or no local save files)");
            onComplete.run();
            return;
        }

        Log.d("XServerDisplayActivity", "Syncing GOG cloud saves for gogId=" + gogId);
        preloaderDialog.showOnUiThread(getString(R.string.preloader_checking_cloud));

        runExitUploadWithRetries(
                "GOG cloud sync for gogId=" + gogId,
                getString(R.string.preloader_checking_cloud),
                callback -> runBlockingExitUpload(
                        "GogExitCloudSync",
                        () -> {
                            Object pendingAction = kotlinx.coroutines.BuildersKt.runBlocking(
                                    kotlinx.coroutines.Dispatchers.getIO(),
                                    (scope, continuation) -> com.winlator.cmod.feature.stores.gog.service.GOGService.Companion
                                            .getPendingExitSyncAction(
                                                    this,
                                                    appId,
                                                    targetContainerId,
                                                    continuation
                                            )
                            );
                            if (pendingAction == com.winlator.cmod.feature.stores.gog.service.GOGCloudSavesManager.SyncAction.UPLOAD) {
                                runOnUiThread(() -> preloaderDialog.showOnUiThread(getString(R.string.preloader_uploading_cloud)));
                            }

                            Boolean syncSuccess = (Boolean) kotlinx.coroutines.BuildersKt.runBlocking(
                                    kotlinx.coroutines.Dispatchers.getIO(),
                                    (scope, continuation) -> com.winlator.cmod.feature.stores.gog.service.GOGService.Companion.syncCloudSaves(
                                            this,
                                            appId,
                                            "exit_upload",
                                            targetContainerId,
                                            continuation
                                    )
                            );
                            boolean success = Boolean.TRUE.equals(syncSuccess);
                            return new ExitUploadResult(
                                    success,
                                    success
                                            ? "GOG cloud upload completed."
                                            : "GOG cloud upload reported failure.",
                                    true);
                        },
                        callback),
                onComplete);
    }

    private void performForcedGogCloudUpload(String reason) {
        if (shortcut == null || !"GOG".equals(shortcut.getExtra("game_source"))) return;
        if (!isCloudSyncEnabledForShortcut() || CloudSyncHelper.isOfflineMode(shortcut)) return;
        if (!shortcut.getExtra("cloud_force_download").isEmpty()) {
            Log.i("XServerDisplayActivity",
                    "Forced GOG cloud upload skipped because a container-swap download is pending during " + reason);
            return;
        }

        String gogId = shortcut.getExtra("gog_id");
        if (gogId == null || gogId.isEmpty()) return;
        final String appId = "GOG_" + gogId;

        try {
            final Integer targetContainerId = container != null ? Integer.valueOf(container.id) : null;
            if (!com.winlator.cmod.feature.stores.gog.service.GOGService
                    .canAttemptExitUpload(this, appId, targetContainerId)) {
                Log.i("XServerDisplayActivity", "Forced GOG cloud upload skipped for " + appId + " during " + reason);
                return;
            }

            Log.i("XServerDisplayActivity", "Attempting forced GOG cloud upload for " + appId + " during " + reason);
            Boolean syncSuccess = (Boolean) kotlinx.coroutines.BuildersKt.runBlocking(
                    kotlinx.coroutines.Dispatchers.getIO(),
                    (scope, continuation) -> com.winlator.cmod.feature.stores.gog.service.GOGService.Companion.syncCloudSaves(
                            this,
                            appId,
                            "exit_upload",
                            targetContainerId,
                            continuation
                    )
            );
            Log.i("XServerDisplayActivity", "Forced GOG cloud upload result for " + appId + ": " + syncSuccess);
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Forced GOG cloud upload failed during " + reason, e);
        }
    }

    private void showLaunchPreloader(String text) {
        if (preloaderDialog == null) return;
        preloaderDialog.showOnUiThread(
                text,
                cachedPreloaderTitle,
                cachedPreloaderBadge,
                cachedPreloaderSubtitle);
    }

    private void showLaunchPreloaderProgress(String text, int percent) {
        if (preloaderDialog == null) return;
        preloaderDialog.showProgressOnUiThread(
                text,
                cachedPreloaderTitle,
                cachedPreloaderBadge,
                cachedPreloaderSubtitle,
                Math.max(0, Math.min(100, percent))
        );
    }

    private void stopWnLauncherStatusTailer() {
        wnLauncherDrivesDismiss.set(false);
        if (wnLauncherStatusTailer == null) return;
        wnLauncherStatusTailer.stop();
        wnLauncherStatusTailer = null;
    }

    private String steamAppNameForId(Container container, int appId) {
        if (container == null || appId <= 0) return null;
        File acf = new File(container.getRootDir(),
                ".wine/drive_c/Program Files (x86)/Steam/steamapps/appmanifest_" + appId + ".acf");
        if (!acf.isFile()) return null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(acf))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("\"name\"")) continue;
                int first = trimmed.indexOf('"', 6);
                if (first < 0) continue;
                int second = trimmed.indexOf('"', first + 1);
                if (second < 0) continue;
                String name = trimmed.substring(first + 1, second).trim();
                if (!name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void writeSteamBlockedAnswer(Container container, String answer) {
        File out = new File(container.getRootDir(), ".wine/drive_c/wn-steam-blocked-answer.txt");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false)) {
            fos.write(answer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            Log.w("XServerDisplayActivity",
                    "Steam Launcher: failed to write blocked answer: " + e.getMessage());
        }
    }

    private void writeSteamCloudConflictAnswer(Container container, String answer) {
        steamCloudConflictShown.set(false);
        File out = new File(container.getRootDir(),
                ".wine/drive_c/wn-steam-cloud-conflict-answer.txt");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false)) {
            fos.write(answer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            Log.w("XServerDisplayActivity",
                    "Steam Launcher: failed to write cloud-conflict answer: " + e.getMessage());
        }
    }

    private String steamCloudConflictLabel(long unixSeconds) {
        if (unixSeconds <= 0L) return getString(R.string.steam_cloud_conflict_unknown_time);
        return java.text.DateFormat
                .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(new java.util.Date(unixSeconds * 1000L));
    }

    private void backupDiscardedSteamSave(
            boolean keepBackup,
            com.winlator.cmod.feature.sync.google.GameSaveBackupManager.BackupOrigin origin,
            Runnable then) {
        if (!keepBackup) {
            then.run();
            return;
        }
        if (preloaderDialog != null) {
            preloaderDialog.setStepOnUiThread(getString(R.string.steam_cloud_conflict_backing_up));
        }
        new Thread(() -> {
            try {
                com.winlator.cmod.feature.steamcloudsync.SteamCloudConflictBackup.INSTANCE
                        .backupDiscardedSave(this, shortcut, origin);
            } catch (Throwable t) {
                Log.w("XServerDisplayActivity",
                        "Steam Launcher: cloud-conflict backup failed; continuing without it", t);
            }
            then.run();
        }, "SteamCloudConflictBackup").start();
    }

    private final java.util.concurrent.atomic.AtomicBoolean steamCloudConflictShown =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void showSteamCloudConflictDialog(Container container, int appId,
                                              long localTime, long remoteTime) {
        Log.i("XServerDisplayActivity",
                "Steam Launcher: cloud conflict for appId=" + appId
                        + " local=" + localTime + " remote=" + remoteTime);
        if (isFinishing() || isDestroyed()) {
            Log.w("XServerDisplayActivity",
                    "Steam Launcher: activity is going away — answering the cloud conflict with "
                            + "\"local\" so the agent is not left blocking on a prompt nobody "
                            + "can see; the cloud copy is left untouched");
            writeSteamCloudConflictAnswer(container, "local");
            return;
        }
        if (!steamCloudConflictShown.compareAndSet(false, true)) {
            Log.w("XServerDisplayActivity",
                    "Steam Launcher: a cloud-conflict dialog is already up; ignoring the repeat");
            return;
        }
        if (preloaderDialog != null) {
            preloaderDialog.setStepOnUiThread(getString(R.string.steam_cloud_conflict_waiting));
        }
        com.winlator.cmod.feature.steamcloudsync.SteamCloudConflictTimestamps timestamps =
                new com.winlator.cmod.feature.steamcloudsync.SteamCloudConflictTimestamps(
                        steamCloudConflictLabel(localTime),
                        steamCloudConflictLabel(remoteTime));
        com.winlator.cmod.feature.steamcloudsync.SteamCloudConflictDialog.show(
                this,
                timestamps,
                keepBackup -> {
                    backupDiscardedSteamSave(keepBackup,
                            com.winlator.cmod.feature.sync.google.GameSaveBackupManager
                                    .BackupOrigin.LOCAL,
                            () -> writeSteamCloudConflictAnswer(container, "cloud"));
                    return kotlin.Unit.INSTANCE;
                },
                keepBackup -> {
                    backupDiscardedSteamSave(keepBackup,
                            com.winlator.cmod.feature.sync.google.GameSaveBackupManager
                                    .BackupOrigin.CLOUD,
                            () -> writeSteamCloudConflictAnswer(container, "local"));
                    return kotlin.Unit.INSTANCE;
                });
    }

    private void showSteamBlockedDialog(Container container, String targetName,
                                        String kind, int blockingAppId) {
        String blockingName = steamAppNameForId(container, blockingAppId);
        if (blockingName == null || blockingName.isEmpty()) {
            blockingName = getString(R.string.steam_blocked_unknown_game);
        }
        boolean other = "othersession".equals(kind);
        boolean sameGame = !other && blockingName.equalsIgnoreCase(targetName);
        String message = sameGame
                ? getString(R.string.steam_blocked_same_game_message, blockingName)
                : getString(other
                                ? R.string.steam_blocked_other_session_message
                                : R.string.steam_blocked_running_message,
                        blockingName, targetName);
        Log.i("XServerDisplayActivity",
                "Steam Launcher: launch blocked (" + kind + ") by appId=" + blockingAppId
                        + " \"" + blockingName + "\"");
        Runnable onConfirm = () -> {
            writeSteamBlockedAnswer(container, "stop");
            if (preloaderDialog != null) {
                preloaderDialog.setStepOnUiThread(getString(R.string.steam_blocked_waiting));
            }
        };
        Runnable onCancel = () -> {
            writeSteamBlockedAnswer(container, "cancel");
            stopWnLauncherStatusTailer();
            WinToast.show(this, getString(R.string.steam_blocked_declined));
            if (preloaderDialog != null) preloaderDialog.closeWithDelay(0L);
            exit();
        };
        boolean shown = com.winlator.cmod.shared.ui.dialog.WinNativeComposeDialogs
                .showSteamSessionBlocked(this, getString(R.string.steam_blocked_title), message,
                        getString(R.string.steam_blocked_confirm), onConfirm, onCancel);
        if (!shown) {
            com.winlator.cmod.shared.ui.dialog.ContentDialog dialog =
                    new com.winlator.cmod.shared.ui.dialog.ContentDialog(this);
            dialog.setTitle(getString(R.string.steam_blocked_title));
            dialog.setMessage(message);
            dialog.setOnConfirmCallback(onConfirm);
            dialog.setOnCancelCallback(onCancel);
            dialog.show();
        }
    }

    private void resetWnLauncherLog(File launcherLog) {
        if (launcherLog == null) return;
        try {
            File parent = launcherLog.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (launcherLog.exists() && !launcherLog.delete()) {
                new FileWriter(launcherLog, false).close();
            }
            Log.d("XServerDisplayActivity",
                    "Steam Launcher: reset launch log at " + launcherLog.getPath());
        } catch (Exception e) {
            Log.w("XServerDisplayActivity",
                    "Steam Launcher: failed to reset launch log at "
                            + launcherLog.getPath(), e);
        }
    }

    private void scrubPlanWBridgeFilesForNextSession() {
        if (container == null) return;
        try {
            int scrubbed = 0;
            File sys32Bridge = new File(container.getRootDir(),
                    ".wine/drive_c/windows/system32/lsteamclient.dll");
            File syswow64Bridge = new File(container.getRootDir(),
                    ".wine/drive_c/windows/syswow64/lsteamclient.dll");
            if (sys32Bridge.exists() && sys32Bridge.delete()) scrubbed++;
            if (syswow64Bridge.exists() && syswow64Bridge.delete()) scrubbed++;
            Log.i("XServerDisplayActivity",
                    "Steam Launcher: scrubbed " + scrubbed
                            + " bridge file(s) during close cleanup");
        } catch (Exception e) {
            Log.w("XServerDisplayActivity",
                    "Steam Launcher: failed to scrub bridge files during close cleanup", e);
        }
    }

    private void sanitizeSteamStateForNextSession(String trigger, boolean waitForPlayingSessionClear) {
        if (!steamStateSanitizedForClose.compareAndSet(false, true)) {
            Log.d("XServerDisplayActivity",
                    "Steam cleanup already ran; skipping duplicate request from " + trigger);
            return;
        }

        stopWnLauncherStatusTailer();
        if (!isSteamShortcut()) return;

        boolean bionicSteam = false;
        try {
            bionicSteam = isBionicSteamEnabledForShortcut();
        } catch (Throwable t) {
            Log.w("XServerDisplayActivity",
                    "Steam cleanup: failed to resolve bionic/PlanW state during " + trigger, t);
        }

        boolean planWActive = com.winlator.cmod.feature.stores.steam.utils
                .PrefManager.INSTANCE.getWnPlanW();

        // Ask the launcher to log off Steam cleanly before kill; a SIGKILL'd launcher stays registered "running" (~40s) so the next launch hits AlreadyRunning. No-op until the armed marker is advertised.
        if (bionicSteam && planWActive) {
            try {
                signalPlanWLauncherCleanShutdown(trigger);
            } catch (Throwable t) {
                Log.w("XServerDisplayActivity",
                        "Steam cleanup: launcher clean-shutdown handshake failed during "
                                + trigger, t);
            }
        }

        if (!bionicSteam) return;

        try {
            if (waitForPlayingSessionClear) {
                // Fire-and-forget: blocking on the kick wasted ~4s every exit; the release is synchronous and the kick retries in the background after reconnect.
                com.winlator.cmod.feature.stores.steam.service.SteamService
                        .Companion.bionicHandoffReleaseAndKickPlayingSessionAsync(true);
            } else {
                com.winlator.cmod.feature.stores.steam.service.SteamService
                        .Companion.bionicHandoffRelease();
            }
            Log.i("XServerDisplayActivity",
                    "Steam cleanup: release issued from " + trigger
                            + " planW=" + planWActive
                            + " (playing-session kick dispatched async)");
        } catch (Throwable t) {
            Log.w("XServerDisplayActivity",
                    "Steam cleanup: release/kick failed during " + trigger, t);
        }

        try {
            clearBionicActiveProcessRegistry();
        } catch (Throwable t) {
            Log.w("XServerDisplayActivity",
                    "Steam cleanup: failed to clear ActiveProcess registry during "
                            + trigger, t);
        }

        if (planWActive) {
            scrubPlanWBridgeFilesForNextSession();
        }

        try {
            uploadSavesIfAgentExitSyncLeftThemLocal(trigger);
        } catch (Throwable t) {
            Log.w("XServerDisplayActivity",
                    "Steam cloud: app-side exit upload fallback failed during " + trigger, t);
        }
    }

    // ---- Plan-W launcher clean-shutdown handshake ---------------------------
    // Hand the launcher a file sentinel; on seeing it the launcher does a clean Steam_LogOff + teardown and exits, reaping the session so the next launch doesn't hit AlreadyRunning. No-op until the armed marker appears in wn-launcher.log.
    private static final String WN_LAUNCHER_SHUTDOWN_SENTINEL = "wn-launcher.shutdown";
    private static final String WN_LAUNCHER_ARMED_MARKER = "[wn-launcher] clean-shutdown armed";
    private static final String WN_LAUNCHER_LOGOFF_DONE_MARKER = "[wn-launcher] clean logoff complete";
    private static final String WN_LAUNCHER_GRACEFUL_CLOSE_MARKER = "graceful close \"";
    private static final String WN_LAUNCHER_CLOUD_EXIT_START_MARKER = "cloud: RunAutoCloudOnAppExit";
    private static final String WN_LAUNCHER_CLOUD_EXIT_DONE_MARKER = "cloud: exit sync COMPLETE";
    private static final String WN_LAUNCHER_CLOUD_EXIT_LOCAL_AHEAD_MARKER =
            "cloud: exit sync left local saves ahead of Steam Cloud";
    // Ceiling; returns early once the "clean logoff complete" marker appears. Must cover the agent's RunAutoCloudOnAppExit budget (60s) plus the logoff flush, or the container is torn down mid-upload and the cloud save is lost.
    private static final long WN_LAUNCHER_SHUTDOWN_TIMEOUT_MS = 90000L;
    private static final long WN_LAUNCHER_SHUTDOWN_POLL_MS = 150L;

    private void signalPlanWLauncherCleanShutdown(String trigger) {
        if (container == null) return;
        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        File log = new File(driveC, "wn-launcher.log");
        if (!wnLauncherLogContains(log, WN_LAUNCHER_ARMED_MARKER)) {
            Log.i("XServerDisplayActivity",
                    "Plan-W launcher clean-shutdown: launcher did not advertise support ("
                            + WN_LAUNCHER_ARMED_MARKER + " absent) — skipping handshake from "
                            + trigger);
            return;
        }
        File sentinel = new File(driveC, WN_LAUNCHER_SHUTDOWN_SENTINEL);
        try {
            FileUtils.writeString(sentinel, Long.toString(System.currentTimeMillis()));
        } catch (Exception e) {
            Log.w("XServerDisplayActivity",
                    "Plan-W launcher clean-shutdown: failed to write sentinel "
                            + sentinel.getAbsolutePath(), e);
            return;
        }
        Log.i("XServerDisplayActivity",
                "Plan-W launcher clean-shutdown: wrote " + sentinel.getAbsolutePath()
                        + " from " + trigger + " — waiting up to "
                        + WN_LAUNCHER_SHUTDOWN_TIMEOUT_MS + "ms for clean logoff");

        long deadline = System.currentTimeMillis() + WN_LAUNCHER_SHUTDOWN_TIMEOUT_MS;
        boolean cleanLogoff = false;
        boolean launcherExited = false;
        boolean shownClosing = false;
        boolean shownUploading = false;
        boolean shownUploaded = false;
        final String closingName = (shortcutName != null && !shortcutName.isEmpty())
                ? shortcutName : getString(R.string.preloader_default_name);
        while (System.currentTimeMillis() < deadline) {
            if (!shownUploaded && wnLauncherLogContains(log, WN_LAUNCHER_CLOUD_EXIT_DONE_MARKER)) {
                shownUploaded = true;
                preloaderDialog.showOnUiThread(getString(R.string.preloader_cloud_upload_done));
            } else if (!shownUploading
                    && wnLauncherLogContains(log, WN_LAUNCHER_CLOUD_EXIT_START_MARKER)) {
                shownUploading = true;
                preloaderDialog.showOnUiThread(getString(R.string.preloader_uploading_cloud));
            } else if (!shownClosing
                    && wnLauncherLogContains(log, WN_LAUNCHER_GRACEFUL_CLOSE_MARKER)) {
                shownClosing = true;
                preloaderDialog.showOnUiThread(
                        getString(R.string.preloader_closing_game_waiting, closingName));
            }
            if (wnLauncherLogContains(log, WN_LAUNCHER_LOGOFF_DONE_MARKER)) {
                cleanLogoff = true;
                break;
            }
            if (!isSteamExeRunning()) {
                launcherExited = true;
                break;
            }
            try {
                Thread.sleep(WN_LAUNCHER_SHUTDOWN_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.i("XServerDisplayActivity",
                "Plan-W launcher clean-shutdown: done from " + trigger
                        + " (cleanLogoff=" + cleanLogoff + " launcherExited=" + launcherExited
                        + " elapsedMs=" + (WN_LAUNCHER_SHUTDOWN_TIMEOUT_MS
                                - Math.max(0, deadline - System.currentTimeMillis())) + ")");
        try {
            if (sentinel.exists()) sentinel.delete();
        } catch (Exception ignored) {}
    }

    private static final int WN_CLOUD_FALLBACK_RECONNECT_TIMEOUT_MS = 20000;
    private static final int WN_CLOUD_FALLBACK_RECONNECT_POLL_MS = 250;

    private void uploadSavesIfAgentExitSyncLeftThemLocal(String trigger) {
        if (container == null) return;
        if (shortcut == null || !"STEAM".equals(shortcut.getExtra("game_source"))) return;
        if (!isCloudSyncEnabledForShortcut() || isOfflineModeForShortcut()) return;

        File log = new File(container.getRootDir(), ".wine/drive_c/wn-launcher.log");
        if (!wnLauncherLogContains(log, WN_LAUNCHER_CLOUD_EXIT_LOCAL_AHEAD_MARKER)) return;

        Log.i("XServerDisplayActivity",
                "Steam cloud: the agent's exit sync left local saves ahead of the cloud during "
                        + trigger + " — waiting for the app-side Steam client so the session is "
                        + "not lost");
        if (preloaderDialog != null) {
            preloaderDialog.showOnUiThread(getString(R.string.preloader_uploading_cloud));
        }

        long deadline = System.currentTimeMillis() + WN_CLOUD_FALLBACK_RECONNECT_TIMEOUT_MS;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (!com.winlator.cmod.feature.stores.steam.service.SteamService
                        .Companion.isBionicHandoffActive()
                        && com.winlator.cmod.feature.stores.steam.service.SteamService
                                .Companion.isLoggedIn()) {
                    ready = true;
                    break;
                }
            } catch (Throwable ignored) {}
            try {
                Thread.sleep(WN_CLOUD_FALLBACK_RECONNECT_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!ready) {
            Log.w("XServerDisplayActivity",
                    "Steam cloud: the app-side Steam client did not reconnect within "
                            + WN_CLOUD_FALLBACK_RECONNECT_TIMEOUT_MS + "ms — leaving the local "
                            + "saves in place for the next launch to upload");
            return;
        }

        boolean uploaded = false;
        try {
            uploaded = com.winlator.cmod.feature.steamcloudsync.SteamCloudSyncHelper
                    .uploadLocalSavesBlocking(this, shortcut);
        } catch (Throwable t) {
            Log.w("XServerDisplayActivity", "Steam cloud: app-side exit upload failed", t);
        }
        Log.i("XServerDisplayActivity",
                "Steam cloud: app-side exit upload " + (uploaded ? "succeeded" : "did NOT succeed"));
        if (uploaded && preloaderDialog != null) {
            preloaderDialog.showOnUiThread(getString(R.string.preloader_cloud_upload_done));
        }
    }

    @Override
    protected void onDestroy() {
        activityDestroyed.set(true);
        if (reshadeLiveHandler != null) {
            reshadeLiveHandler.removeCallbacks(reshadeLiveWriteTask);
            reshadeLiveHandler.post(reshadeLiveWriteTask);
            reshadeLiveThread.quitSafely();
            reshadeLiveHandler = null;
            reshadeLiveThread = null;
        }
        com.winlator.cmod.feature.stores.steam.service.GameSessionState.setInGame(this, false);
        // Finalize any in-progress recording before the renderer tears down.
        if (screenRecorder != null && screenRecorder.isRecording()) {
            stopScreenRecording();
        }
        if (hudControllerListener != null) {
            android.hardware.input.InputManager im =
                    (android.hardware.input.InputManager) getSystemService(Context.INPUT_SERVICE);
            if (im != null) im.unregisterInputDeviceListener(hudControllerListener);
            hudControllerListener = null;
        }
        if (externalDisplayController != null) {
            externalDisplayController.release();
            externalDisplayController = null;
        }
        if (audioFocusHandler != null) {
            audioFocusHandler.release();
            audioFocusHandler = null;
        }
        if (isDependencyInstall) {
            com.winlator.cmod.runtime.content.component.DependencyInstallBridge.complete(dependencyExitStatus);
        }
        unregisterDisplayChangeListener();
        unregisterControllerAutoHideListener();
        if (preloaderDialog != null) {
            preloaderDialog.close();
        }
        stopWnLauncherStatusTailer();
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
            } catch (Exception ignored) {}
        }

        // Don't call renderer.destroy() here — onSurfaceDestroyed already drives nativeDestroy; a UI-thread destroy races and SIGABRTs in Surface::disconnect.

        if (exitRequested.get()) {
            SessionKeepAliveService.stopSession(this);
        }

        super.onDestroy();
        if (!switchLaunchInProgress.get()) {
            UpdateService.INSTANCE.onGameFinished(this);
        }

        if (!sessionCleanupStarted.get()) {
            if (exitRequested.get() || !preferences.getBoolean("enable_background_session", false)) {
                performForcedSessionCleanup("onDestroy");
            }
        }

        String tag = "XServerLeakCheck";
        if (!exitRequested.get()) {
            Log.w(tag, "onDestroy called without exit() — activity may have been killed by system");
        }
        ArrayList<String> remainingProcesses = ProcessHelper.listRunningWineProcesses();
        if (!remainingProcesses.isEmpty()) {
            Log.e(tag, "Wine processes still running: " + ProcessHelper.listRunningWineProcessDetails());
        } else {
            Log.i(tag, "No Wine/session processes remain at onDestroy leak check");
        }
        if (environment != null) {
            Log.w(tag, "Environment not null — components may not have been stopped");
        }
        if (winHandler != null && winHandler.getSocket() != null && !winHandler.getSocket().isClosed()) {
            Log.e(tag, "WinHandler socket still open");
        }
        if (wineRequestHandler != null && wineRequestHandler.getServerSocket() != null && !wineRequestHandler.getServerSocket().isClosed()) {
            Log.e(tag, "WineRequestHandler server socket still open");
        }
        if (midiHandler != null && midiHandler.getSocket() != null && !midiHandler.getSocket().isClosed()) {
            Log.e(tag, "MidiHandler socket still open");
        }
        cleanupDebugDialog("onDestroy");

        // Ownership tokens are session-scoped.
        if (shortcut != null && "EPIC".equals(shortcut.getExtra("game_source"))) {
            try {
                com.winlator.cmod.feature.stores.epic.service.EpicService.Companion
                        .cleanupLaunchTokens(getApplicationContext(), container);
            } catch (Exception e) {
                Log.w("EPIC", "Failed to cleanup ownership tokens on game exit", e);
            }
        }
    }

    private boolean isCustomShortcut() {
        return shortcut != null
                && "CUSTOM".equals(shortcut.getExtra("game_source", "CUSTOM"))
                && !isSteamShortcut();
    }

    private boolean isRealSteamLaunchEnabledForShortcut() {
        return false;
    }

    private boolean isBionicSteamEnabledForShortcut() {
        if (!isSteamShortcut()) return false;
        if (isRealSteamLaunchEnabledForShortcut()) return false;
        boolean cold = shortcut != null
                ? parseBoolean(getShortcutSetting("useColdClient", container.isUseColdClient() ? "1" : "0"))
                : container != null && container.isUseColdClient();
        if (cold) return false;
        boolean explicit = shortcut != null
                ? parseBoolean(getShortcutSetting("launchBionicSteam", container.isLaunchBionicSteam() ? "1" : "0"))
                : container != null && container.isLaunchBionicSteam();
        if (explicit) return true;

        boolean planW = com.winlator.cmod.feature.stores.steam.utils
                .PrefManager.INSTANCE.getWnPlanW();
        if (planW) {
            String tok = com.winlator.cmod.feature.stores.steam.utils
                    .PrefManager.INSTANCE.getRefreshToken();
            long sid = com.winlator.cmod.feature.stores.steam.utils
                    .PrefManager.INSTANCE.getSteamUserSteamId64();
            if (tok != null && !tok.isEmpty() && sid > 0) {
                Log.i("XServerDisplayActivity",
                        "Steam Launcher auto-promote: Steam shortcut without explicit "
                        + "Bionic flag, user signed in — routing through Steam Launcher "
                        + "(set wn_plan_w=false to opt out)");
                return true;
            } else {
                Log.w("XServerDisplayActivity",
                        "Steam Launcher auto-promote: skipped (signed-in check failed) "
                        + "tokEmpty=" + (tok == null || tok.isEmpty())
                        + " sidIsZero=" + (sid == 0));
            }
        }
        return false;
    }

    private boolean isColdClientEnabledForShortcut() {
        if (!isSteamShortcut()) return false;
        if (isRealSteamLaunchEnabledForShortcut()) return false; // mutually exclusive
        return shortcut != null
                ? parseBoolean(getShortcutSetting("useColdClient", container.isUseColdClient() ? "1" : "0"))
                : container != null && container.isUseColdClient();
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
        if (!sessionCleanupStarted.get() && isFinishing() && !isChangingConfigurations()) {
            performForcedSessionCleanup("onStop finishing");
        }
    }

    private void handleNavigationBackPressed() {
        if (environment != null) {
            if (drawerStateHolder != null && drawerStateHolder.consumeOverlayBack()) {
                return;
            }
            if (drawerStateHolder != null && drawerStateHolder.isPaneOpen()) {
                drawerStateHolder.closeOpenPane();
                return;
            }
            if (drawerStateHolder == null || !drawerStateHolder.isDrawerOpen()) {
                openDrawerMenu();
            }
            else closeDrawerMenu();
        }
    }

    private void openDrawerMenu() {
        releasePointerCapture();
        renderDrawerMenu();
        if (drawerStateHolder != null) {
            drawerStateHolder.openDrawer();
        }
        if (touchpadView != null) {
            touchpadView.setOnCapturedPointerListener(null);
        }
    }

    private void closeDrawerMenu() {
        if (drawerStateHolder != null) {
            drawerStateHolder.closeDrawer();
        }
        tryCapturePointer();
    }

    private String currentGyroActivatorLabel() {
        if (preferences.getBoolean("mouse_gyro_enabled", false)) {
            return WinHandler.getGyroMouseActivator(preferences).toString();
        }
        String[] names = getResources().getStringArray(R.array.button_options);
        int[] keycodes = getResources().getIntArray(R.array.button_keycodes);
        int currentKeycode = preferences.getInt("gyro_trigger_button", KeyEvent.KEYCODE_BUTTON_L1);
        int index = -1;
        for (int i = 0; i < keycodes.length; i++) {
            if (keycodes[i] == currentKeycode) {
                index = i;
                break;
            }
        }
        if (index == -1) index = 6; // Default to L1
        return index < names.length ? names[index] : names[0];
    }

    private void renderDrawerMenu() {
        if (displayHostComposeView == null || xServerDisplayFrame == null) return;

        ControlsProfile activeProfile = inputControlsView != null ? inputControlsView.getProfile() : null;
        ArrayList<ControlsProfile> inputProfiles = getVisibleControlsProfiles();
        ArrayList<String> inputProfileNames = new ArrayList<>();
        int inputSelectedIndex = 0;
        inputProfileNames.add("-- " + getString(R.string.common_ui_disabled) + " --");
        for (int i = 0; i < inputProfiles.size(); i++) {
            ControlsProfile profile = inputProfiles.get(i);
            if (activeProfile != null && profile.id == activeProfile.id) inputSelectedIndex = i + 1;
            inputProfileNames.add(profile.getName());
        }

        ArrayList<String> styleNames = new ArrayList<>();
        if (activeProfile != null) {
            styleNames.add(getString(R.string.input_controls_style_slate));
            styleNames.add(getString(R.string.input_controls_style_gamehub));
            styleNames.add(getString(R.string.input_controls_style_halo));
            styleNames.add(getString(R.string.input_controls_style_glint));
            styleNames.add(getString(R.string.input_controls_style_shadow));
            styleNames.add(getString(R.string.input_controls_style_reticle));
            styleNames.add(getString(R.string.input_controls_style_neon));
            styleNames.add(getString(R.string.input_controls_style_lumina));
            styleNames.add(getString(R.string.input_controls_style_original));
        }
        VisualStyle currentStyle = inputControlsView != null && inputControlsView.getVisualStyle() != null
                ? inputControlsView.getVisualStyle() : VisualStyle.SLATE;
        int selectedStyleIndex = currentStyle.ordinal();

        ArrayList<String> accentThemeNames = new ArrayList<>();
        int selectedAccentThemeIndex = 0;
        if (activeProfile != null && inputControlsView != null) {
            accentThemeNames.addAll(Arrays.asList(AccentTheme.displayNames()));
            selectedAccentThemeIndex = inputControlsView.getAccentTheme().ordinal();
        }

        List<String> gestureProfileNames = new ArrayList<>();
        int gestureSelectedIndex = 0;
        try {
            gestureProfileNames = gestureProfileManager.getProfileNames();
            gestureSelectedIndex = Math.max(0, gestureProfileManager.indexOfProfile(selectedGestureProfileId()));
        } catch (Throwable t) {
            android.util.Log.e("XServerDisplayActivity", "gesture drawer names failed", t);
        }

        XServerDrawerState state = XServerDrawerMenuKt.buildXServerDrawerState(
                this,
                isRelativeMouseMovement,
                isMouseDisabled,
                frameRating != null && frameRating.getVisibility() == View.VISIBLE,
                isPaused,
                true,
                magnifierView != null,
                enableLogsMenu,
                hudTransparency,
                hudBackgroundAlphaDecoupled,
                hudBackgroundTransparency,
                hudScale,
                // Fresh array each build so a toggled HUD element yields a changed state and the chips recompose.
                hudElements.clone(),
                dualSeriesBattery,
                frametimeNumericMode,
                hudCardExpanded,
                preferences.getBoolean("gyro_enabled", false),
                preferences.getInt("gyro_mode", 0),
                preferences.getBoolean("gyro_orientation_enabled", false),
                currentGyroActivatorLabel(),
                preferences.getBoolean("process_gyro_with_left_trigger", false),
                preferences.getBoolean("mouse_gyro_enabled", false),
                preferences.getFloat("gyro_mouse_scale", 50.0f),
                preferences.getFloat("gyro_x_sensitivity", 1.0f),
                preferences.getFloat("gyro_y_sensitivity", 1.0f),
                preferences.getFloat("gyro_smoothing", 0.5f),
                preferences.getFloat("gyro_deadzone", 0.05f),
                preferences.getBoolean("invert_gyro_x", false),
                preferences.getBoolean("invert_gyro_y", false),
                gyroscopeCardExpanded,
                xServerView != null ? xServerView.getRenderer().getFpsLimit() : 0,
                screenEffectsCardExpanded,
                sgsrEnabled,
                sgsrSharpness,
                vividEnabled,
                vividStrength,
                colorProfile,
                brightness,
                contrast,
                gammaPercent,
                scaleFilter,
                saturation,
                temperature,
                tint,
                sharpenEnabled,
                sharpenStrength,
                scanlinesEnabled,
                scanlinesIntensity,
                pixelateEnabled,
                pixelateBlock,
                colorBlind,
                inputProfileNames,
                inputSelectedIndex,
                styleNames,
                selectedStyleIndex,
                accentThemeNames,
                selectedAccentThemeIndex,
                preferences.getBoolean("show_touchscreen_controls_enabled", false),
                isTapToClickEnabled,
                preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY),
                preferences.getBoolean("touchscreen_haptics_enabled", false),
                preferences.getBoolean(ControllerManager.PREF_VIBRATION_GLOBAL, true),
                preferences.getString(
                    com.winlator.cmod.runtime.input.rumble.GcmRumbleMode.PREF_KEY,
                    com.winlator.cmod.runtime.input.rumble.GcmRumbleMode.DISABLED.toPrefValue()),
                preferences.getBoolean("reverse_binding_order", false),
                globalCursorSpeed,
                xServerView != null && xServerView.getRenderer() != null && xServerView.getRenderer().isFullscreen(),
                RefreshRateUtils.getMaxSupportedRefreshRate(this),
                isRefactorSizeEnabled,
                screenRecorder != null && screenRecorder.isRecording(),
                buildRecordConfig(),
                screenTouchMode,
                rtsGesturesEnabled,
                gestureProfileNames,
                gestureSelectedIndex,
                preferences.getFloat("right_stick_sensitivity", 1.0f),
                preferences.getFloat("screen_touch_rs_sensitivity", 1.25f),
                preferences.getBoolean(MangoHudView.PREF_ENABLED, false),
                MangoHudView.elementsFromPrefs(preferences),
                MangoHudView.alphaFromPrefs(preferences),
                MangoHudView.bgAlphaFromPrefs(preferences),
                MangoHudView.scaleFromPrefs(preferences),
                MangoHudView.lockedFromPrefs(preferences)
        );

        state = XServerDrawerMenuKt.withFrameGenState(
                state,
                frameGenCachePath != null,
                frameGenEnabled,
                frameGenMultiplier,
                frameGenTargetRate,
                frameGenFlowScale,
                getString(R.string.session_drawer_frame_generation));

        // Always-present "Output" tab (live controls while swapped, otherwise a Cast entry point).
        if (externalDisplayController != null) {
            boolean swapped = externalDisplayController.isSwapActive();
            state = XServerDrawerMenuKt.withOutputState(
                    state,
                    swapped,
                    swapped ? externalDisplayController.getDisplayName()
                            : externalDisplayController.getAvailableDisplayName(),
                    externalDisplayController.getResolutionLabels(),
                    externalDisplayController.getSelectedResolutionIndex(),
                    externalDisplayController.getRefreshRateLabels(),
                    externalDisplayController.getSelectedRefreshRateIndex(),
                    externalDisplayController.getFillMode(),
                    externalDisplayController.isGameModeSupported(),
                    externalDisplayController.isGameModeEnabled(),
                    externalDisplayController.isPanelScaling(),
                    externalDisplayController.getPanelNativeSummary(),
                    externalDisplayController.hasExternalDisplay(),
                    getString(R.string.session_drawer_rail_label_output));
            if (externalDisplayController.isVitureConnected()) {
                state = XServerDrawerMenuKt.withVitureState(
                        state,
                        externalDisplayController.getVitureName(),
                        externalDisplayController.vitureSupportsBrightness(),
                        externalDisplayController.getVitureBrightness(),
                        externalDisplayController.getVitureBrightnessMax(),
                        externalDisplayController.vitureSupportsFilm(),
                        externalDisplayController.vitureFilmStepped(),
                        externalDisplayController.getVitureFilm(),
                        externalDisplayController.vitureSupports3D(),
                        externalDisplayController.isViture3D(),
                        externalDisplayController.vitureSupportsVolume(),
                        externalDisplayController.getVitureVolume(),
                        externalDisplayController.getVitureVolumeMax());
            }
        }

        if (reshadeSessionAvailable) {
            state = XServerDrawerMenuKt.withReshadeState(
                    state,
                    reshadeMasterEnabled,
                    reshadeMode,
                    buildReshadeItems(),
                    getString(R.string.reshade_section_title));
        }

        if (drawerActionListener == null) {
            drawerActionListener = new XServerDrawerActionListener() {
                    @Override
                    public void onActionSelected(int itemId) {
                        handleDrawerAction(itemId);
                    }

                    @Override
                    public void onHUDElementToggled(int index, boolean enabled) {
                        hudElements[index] = enabled;
                        if (frameRating != null) frameRating.toggleElement(index, enabled);
                        com.winlator.cmod.runtime.display.PerformanceHudState.updateEnabled(hudElements);
                        saveHUDSettings();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onHUDTransparencyChanged(float transparency) {
                        hudTransparency = transparency;
                        if (!hudBackgroundAlphaDecoupled) {
                            hudBackgroundTransparency = clampHudAlpha(transparency * FrameRating.BACKDROP_BASE_ALPHA);
                        }
                        if (frameRating != null) frameRating.setHudAlpha(transparency);
                        saveHUDSettings();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onHUDBackgroundAlphaDecoupledChanged(boolean enabled) {
                        hudBackgroundAlphaDecoupled = enabled;
                        hudBackgroundTransparency = clampHudAlpha(hudTransparency * FrameRating.BACKDROP_BASE_ALPHA);
                        if (frameRating != null) {
                            frameRating.setHudBackgroundAlpha(hudBackgroundTransparency);
                            frameRating.setBackgroundAlphaDecoupled(enabled);
                        }
                        saveHUDSettings();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onHUDBackgroundTransparencyChanged(float transparency) {
                        hudBackgroundTransparency = transparency;
                        if (frameRating != null) frameRating.setHudBackgroundAlpha(transparency);
                        saveHUDSettings();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onHUDScaleChanged(float scale) {
                        hudScale = scale;
                        if (frameRating != null) frameRating.setHudScale(scale);
                        saveHUDSettings();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onDualSeriesBatteryChanged(boolean enabled) {
                        dualSeriesBattery = enabled;
                        preferences.edit().putBoolean(FrameRating.PREF_HUD_DUAL_SERIES_BATTERY, enabled).apply();
                        if (frameRating != null) frameRating.setDualSeriesBattery(enabled);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onFrametimeNumericChanged(boolean enabled) {
                        frametimeNumericMode = enabled;
                        preferences.edit().putBoolean(FrameRating.PREF_HUD_FRAMETIME_NUMERIC, enabled).apply();
                        if (frameRating != null) frameRating.setFrametimeNumericMode(enabled);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onMangoHudChanged(boolean enabled) {
                        preferences.edit().putBoolean(MangoHudView.PREF_ENABLED, enabled).apply();
                        if (enabled && mangoHud == null && xServerDisplayFrame != null) {
                            mangoHud = new MangoHudView(XServerDisplayActivity.this);
                            xServerDisplayFrame.addView(mangoHud);
                        }
                        if (mangoHud != null) {
                            mangoHud.setEngineName(mangoEngineLabel());
                            mangoHud.setSessionInfo(
                                    xServer != null ? xServer.screenInfo.width + "x" + xServer.screenInfo.height : null,
                                    wineInfo != null ? String.valueOf(wineInfo) : null);
                            mangoHud.setHudVisible(enabled);
                            syncFrameGenerationHud();
                        }
                        renderDrawerMenu();
                    }

                    @Override
                    public void onMangoHudElementToggled(int index, boolean enabled) {
                        MangoHudView.saveElement(preferences, index, enabled);
                        if (mangoHud != null) mangoHud.setElementEnabled(index, enabled);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onMangoHudAlphaChanged(float alpha) {
                        MangoHudView.saveAlpha(preferences, alpha);
                        if (mangoHud != null) mangoHud.setTextAlphaValue(alpha);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onMangoHudBackgroundAlphaChanged(float alpha) {
                        MangoHudView.saveBgAlpha(preferences, alpha);
                        if (mangoHud != null) mangoHud.setBackgroundAlphaValue(alpha);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onMangoHudLockChanged(boolean locked) {
                        MangoHudView.saveLocked(preferences, locked);
                        if (mangoHud != null) mangoHud.setLockedValue(locked);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onMangoHudScaleChanged(float scale) {
                        // No drawer rebuild: the slider owns its value while dragging.
                        MangoHudView.saveScale(preferences, scale);
                        if (mangoHud != null) mangoHud.setScaleValue(scale);
                    }

                    @Override
                    public void onHUDCardExpandedChanged(boolean expanded) {
                        hudCardExpanded = expanded;
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroscopeEnabledChanged(boolean enabled) {
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putBoolean("gyro_enabled", enabled);
                        editor.apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroscopeModeSelected(int mode) {
                        preferences.edit().putInt("gyro_mode", mode).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroOrientationModeChanged(boolean enabled) {
                        preferences.edit().putBoolean("gyro_orientation_enabled", enabled).apply();
                        // Swap the active sensor (rate <-> orientation) live.
                        registerGyroSensorIfEnabled();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroscopeActivatorSelected(int keycode) {
                        preferences.edit().putInt("gyro_trigger_button", keycode).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroscopeActivatorBindingSelected(String bindingName) {
                        preferences.edit().putString("gyro_mouse_trigger_binding", bindingName).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onRightStickGyroChanged(boolean enabled) {
                        preferences.edit().putBoolean("process_gyro_with_left_trigger", enabled).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroMouseEnabledChanged(boolean enabled) {
                        preferences.edit().putBoolean("mouse_gyro_enabled", enabled).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroMouseScaleChanged(float scale) {
                        preferences.edit().putFloat("gyro_mouse_scale", scale).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroXSensitivityChanged(float sensitivity) {
                        preferences.edit().putFloat("gyro_x_sensitivity", sensitivity).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroYSensitivityChanged(float sensitivity) {
                        preferences.edit().putFloat("gyro_y_sensitivity", sensitivity).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroSmoothingChanged(float smoothing) {
                        preferences.edit().putFloat("gyro_smoothing", smoothing).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroDeadzoneChanged(float deadzone) {
                        preferences.edit().putFloat("gyro_deadzone", deadzone).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInvertGyroXChanged(boolean enabled) {
                        preferences.edit().putBoolean("invert_gyro_x", enabled).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInvertGyroYChanged(boolean enabled) {
                        preferences.edit().putBoolean("invert_gyro_y", enabled).apply();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGyroscopeCardExpandedChanged(boolean expanded) {
                        gyroscopeCardExpanded = expanded;
                        renderDrawerMenu();
                    }

                    @Override
                    public void onFPSLimitChanged(int limit) {
                        runtimeFpsLimit = Math.max(0, limit);
                        if (xServerView != null) {
                            xServerView.getRenderer().setFpsLimit(runtimeFpsLimit);
                        }
                        applyPreferredRefreshRate();
                        if (shortcut != null) {
                            shortcut.putExtra("fpsLimit", runtimeFpsLimit > 0 ? String.valueOf(runtimeFpsLimit) : null);
                            shortcut.saveData();
                        }
                        renderDrawerMenu();
                    }

                    @Override
                    public void onScreenEffectsCardExpandedChanged(boolean expanded) {
                        screenEffectsCardExpanded = expanded;
                        renderDrawerMenu();
                    }

                    @Override
                    public void onOutputResolutionSelected(int index) {
                        if (externalDisplayController != null) {
                            externalDisplayController.selectResolution(index);
                            renderDrawerMenu();
                        }
                    }

                    @Override
                    public void onOutputRefreshRateSelected(int index) {
                        if (externalDisplayController != null) {
                            externalDisplayController.selectRefreshRate(index);
                            renderDrawerMenu();
                        }
                    }

                    @Override
                    public void onOutputAspectModeSelected(int mode) {
                        if (externalDisplayController != null) {
                            externalDisplayController.selectFillMode(mode);
                            renderDrawerMenu();
                        }
                    }

                    @Override
                    public void onOutputGameModeToggled(boolean enabled) {
                        if (externalDisplayController != null) {
                            externalDisplayController.setGameMode(enabled);
                            renderDrawerMenu();
                        }
                    }

                    @Override
                    public void onOutputVitureBrightness(int level) {
                        if (externalDisplayController != null) externalDisplayController.setVitureBrightness(level);
                    }

                    @Override
                    public void onOutputVitureFilm(int level) {
                        if (externalDisplayController != null) {
                            externalDisplayController.setVitureFilm(level);
                            renderDrawerMenu();
                        }
                    }

                    @Override
                    public void onOutputViture3D(boolean enabled) {
                        if (externalDisplayController != null) {
                            externalDisplayController.setViture3D(enabled);
                            renderDrawerMenu();
                        }
                    }

                    @Override
                    public void onOutputVitureVolume(int level) {
                        if (externalDisplayController != null) externalDisplayController.setVitureVolume(level);
                    }

                    @Override
                    public void onOutputReturnToPhone() {
                        if (externalDisplayController != null) {
                            externalDisplayController.exitSwap();
                            renderDrawerMenu();
                        }
                    }

                    @Override
                    public void onOutputSwapToDisplay() {
                        if (externalDisplayController != null) {
                            externalDisplayController.enterSwap();
                            renderDrawerMenu();
                            android.widget.Toast.makeText(XServerDisplayActivity.this,
                                    R.string.display_output_swapped_toast,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onOutputCastClick() {
                        launchWirelessDisplayPicker();
                    }

                    @Override
                    public void onFrameGenEnabledChanged(boolean enabled) {
                        if (enabled && frameGenCachePath == null) return;
                        frameGenEnabled = enabled;
                        applyFrameGenerationLive();
                    }

                    @Override
                    public void onFrameGenMultiplierSelected(int multiplier) {
                        frameGenMultiplier = clampFrameGenMultiplier(multiplier);
                        applyFrameGenerationLive();
                    }

                    @Override
                    public void onFrameGenTargetRateSelected(int rate) {
                        frameGenTargetRate = Math.max(0, rate);
                        applyFrameGenerationLive();
                    }

                    @Override
                    public void onFrameGenFlowScaleChanged(int percent) {
                        frameGenFlowScale = clampFrameGenFlowScale(percent);
                        applyFrameGenerationLive();
                    }


                    @Override
                    public void onSGSREnabledChanged(boolean enabled) {
                        boolean wasEnabled = sgsrEnabled;
                        boolean wasRuntimeEnabled = sgsrRuntimeEnabled;
                        sgsrEnabled = enabled;
                        saveSGSRShortcutSettings();
                        if (!enabled) {
                            sgsrRuntimeEnabled = false;
                            logDeferredSGSRRestoreIfNeeded(wasEnabled || wasRuntimeEnabled);
                        } else if (!wasEnabled) {
                            sgsrRuntimeEnabled = canEnableSGSRLiveWithoutResize();
                            if (sgsrRuntimeEnabled) {
                                Log.i("SGSRResize", "SGSR enabled mid-session without XServer resize");
                            } else {
                                Log.i("SGSRResize", "SGSR enabled mid-session; live SGSR pass and render-size reduction are deferred until next launch");
                            }
                        }
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onSGSRSharpnessChanged(int sharpness) {
                        sgsrSharpness = Math.max(0, Math.min(100, sharpness));
                        saveSGSRShortcutSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onVividEnabledChanged(boolean enabled) {
                        vividEnabled = enabled;
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onVividStrengthChanged(int strength) {
                        vividStrength = strength;
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onColorProfileSelected(int profile) {
                        colorProfile = profile;
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onBrightnessChanged(int value) {
                        brightness = Math.max(-100, Math.min(100, value));
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onContrastChanged(int value) {
                        contrast = Math.max(-100, Math.min(100, value));
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGammaChanged(int value) {
                        gammaPercent = Math.max(50, Math.min(250, value));
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onScaleFilterSelected(int mode) {
                        scaleFilter = mode;
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onSaturationChanged(int value) {
                        saturation = Math.max(0, Math.min(200, value));
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onTemperatureChanged(int value) {
                        temperature = Math.max(-100, Math.min(100, value));
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onTintChanged(int value) {
                        tint = Math.max(-100, Math.min(100, value));
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onSharpenEnabledChanged(boolean enabled) {
                        sharpenEnabled = enabled;
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onSharpenStrengthChanged(int value) {
                        sharpenStrength = Math.max(0, Math.min(100, value));
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onScanlinesEnabledChanged(boolean enabled) {
                        scanlinesEnabled = enabled;
                        if (enabled) pixelateEnabled = false;
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onScanlinesIntensityChanged(int value) {
                        scanlinesIntensity = Math.max(0, Math.min(100, value));
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onPixelateEnabledChanged(boolean enabled) {
                        pixelateEnabled = enabled;
                        if (enabled) scanlinesEnabled = false;
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onPixelateBlockChanged(int value) {
                        pixelateBlock = Math.max(2, Math.min(14, value));
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onColorBlindSelected(int mode) {
                        colorBlind = mode;
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onResetEffects() {
                        vividEnabled = false;
                        vividStrength = 100;
                        colorProfile = 0;
                        brightness = 0;
                        contrast = 0;
                        gammaPercent = 100;
                        scaleFilter = 0;
                        saturation = 100;
                        temperature = 0;
                        tint = 0;
                        sharpenEnabled = false;
                        sharpenStrength = 50;
                        scanlinesEnabled = false;
                        scanlinesIntensity = 50;
                        pixelateEnabled = false;
                        pixelateBlock = 6;
                        colorBlind = 0;
                        saveScreenEffectsSettings();
                        applyScreenEffects();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onReshadeMasterEnabledChanged(boolean enabled) {
                        // compiled loadout untouched; only enableOnLaunch flips
                        reshadeMasterEnabled = enabled;
                        applyReshadeLive();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onReshadeEffectEnabledChanged(int index, boolean enabled) {
                        if (index < 0 || index >= reshadeLive.size()) return;
                        if (com.winlator.cmod.runtime.reshade.ReshadeLoadout.MODE_SOLO.equals(reshadeMode) && enabled) {
                            for (int i = 0; i < reshadeLive.size(); i++) reshadeLive.get(i).enabled = (i == index);
                        } else {
                            reshadeLive.get(index).enabled = enabled;
                        }
                        applyReshadeLive();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onReshadeModeChanged(String mode) {
                        reshadeMode = com.winlator.cmod.runtime.reshade.ReshadeLoadout.normalizeMode(mode);
                        if (com.winlator.cmod.runtime.reshade.ReshadeLoadout.MODE_SOLO.equals(reshadeMode)) {
                            // Collapse to a single active effect (keep the first enabled one).
                            boolean seen = false;
                            for (ReshadeLiveEffect e : reshadeLive) {
                                if (e.enabled && !seen) seen = true; else e.enabled = false;
                            }
                        }
                        applyReshadeLive();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onReshadeParamChanged(int index, String key, float value) {
                        if (index < 0 || index >= reshadeLive.size()) return;
                        reshadeLive.get(index).values.put(key, value);
                        applyReshadeLive();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onReshadeReset(int index) {
                        if (index < 0 || index >= reshadeLive.size()) return;
                        ReshadeLiveEffect e = reshadeLive.get(index);
                        e.values.clear();
                        for (ReshadeManager.ReshadeParam p : e.defs) ReshadeManager.seedValues(p, null, e.values);
                        applyReshadeLive();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInputControlsProfileSelected(int index) {
                        if (index <= 0) {
                            hideInputControls();
                        } else {
                            ArrayList<ControlsProfile> profiles = getVisibleControlsProfiles();
                            if (index - 1 < profiles.size()) {
                                ControlsProfile profile = profiles.get(index - 1);
                                showInputControls(profile);
                            }
                        }
                        renderDrawerMenu();
                    }
                    @Override
                    public void onInputControlsStyleSelected(int index) {
                        VisualStyle[] all = VisualStyle.values();
                        if (index < 0 || index >= all.length) return;
                        VisualStyle chosen = all[index];
                        if (inputControlsView != null) inputControlsView.setVisualStyle(chosen);
                        persistSelectedStyle(chosen);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInputControlsAccentThemeSelected(int index) {
                        AccentTheme[] all = AccentTheme.values();
                        if (index < 0 || index >= all.length) return;
                        AccentTheme chosen = all[index];
                        if (inputControlsView != null) inputControlsView.setAccentTheme(chosen);
                        persistSelectedAccentTheme(chosen);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInputControlsShowOverlayChanged(boolean enabled) {
                        preferences.edit().putBoolean("show_touchscreen_controls_enabled", enabled).commit();
                        // Manual re-enable while a controller is connected wins over auto-hide.
                        if (enabled && isAnyGameControllerConnected()) {
                            userOverrodeAutoHide = true;
                            controllerAutoHidden = false;
                        }
                        applyTouchscreenOverlayPreference();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInputControlsTapToClickChanged(boolean enabled) {
                        isTapToClickEnabled = enabled;
                        if (touchpadView != null) touchpadView.setTapToClickEnabled(enabled);
                        preferences.edit().putBoolean("tap_to_click_enabled", enabled).commit();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInputControlsOverlayOpacityChanged(float opacity) {
                        if (inputControlsView != null) inputControlsView.setOverlayOpacity(opacity);
                        preferences.edit().putFloat("overlay_opacity", opacity).commit();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInputControlsTouchscreenHapticsChanged(boolean enabled) {
                        preferences.edit().putBoolean("touchscreen_haptics_enabled", enabled).commit();
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInputControlsGamepadVibrationChanged(boolean enabled) {
                        preferences.edit().putBoolean(ControllerManager.PREF_VIBRATION_GLOBAL, enabled).commit();
                        if (winHandler != null) winHandler.setGlobalVibrationEnabled(enabled);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onCursorSpeedChanged(float speed) {
                        globalCursorSpeed = speed;
                        preferences.edit().putFloat("cursor_speed", speed).apply();
                        if (touchpadView != null) {
                            float profileSpeed = 1.0f;
                            if (inputControlsView != null) {
                                ControlsProfile profile = inputControlsView.getProfile();
                                if (profile != null) profileSpeed = profile.getCursorSpeed();
                            }
                            touchpadView.setSensitivity(profileSpeed * globalCursorSpeed);
                        }
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInputControlsGcmRumbleModeChanged(String mode) {
                        com.winlator.cmod.runtime.input.rumble.GcmRumbleMode gcmMode =
                            com.winlator.cmod.runtime.input.rumble.GcmRumbleMode.fromPrefValue(mode);
                        preferences
                            .edit()
                            .putString(
                                com.winlator.cmod.runtime.input.rumble.GcmRumbleMode.PREF_KEY,
                                gcmMode.toPrefValue())
                            .commit();
                        if (winHandler != null) winHandler.setGcmRumbleMode(gcmMode);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInputControlsReverseBindingOrderChanged(boolean enabled) {
                        preferences.edit().putBoolean("reverse_binding_order", enabled).commit();
                        if (inputControlsView != null) inputControlsView.setReverseBindingOrder(enabled);
                        renderDrawerMenu();
                    }

                    @Override
                    public void onInputControlsEditClick() {
                        ControlsProfile activeProfile = inputControlsView != null ? inputControlsView.getProfile() : null;
                        Intent intent = new Intent(XServerDisplayActivity.this, UnifiedActivity.class);
                        intent.putExtra("edit_input_controls", true);
                        intent.putExtra("selected_profile_id", activeProfile != null ? activeProfile.id : 0);
                        intent.putExtra("return_to_game_on_back", true);
                        final ControlsProfile editingProfile = activeProfile;
                        editInputControlsCallback = () -> {
                            boolean wasShowingTouch = preferences.getBoolean("show_touchscreen_controls_enabled", false);
                            hideInputControls();
                            if (inputControlsManager != null) inputControlsManager.loadProfiles(true);
                            ControlsProfile reactivated = editingProfile != null && inputControlsManager != null ? inputControlsManager.getProfile(editingProfile.id) : null;
                            if (reactivated != null) {
                                showInputControls(reactivated);
                                if (wasShowingTouch) {
                                    preferences.edit().putBoolean("show_touchscreen_controls_enabled", true).apply();
                                    applyTouchscreenOverlayPreference();
                                }
                            }
                            renderDrawerMenu();
                        };
                        controlsEditorActivityResultLauncher.launch(intent);
                    }

                    @Override
                    public void onScreenTouchModeChanged(int mode) {
                        screenTouchMode = mode;
                        rtsGesturesEnabled = false;
                        if (touchpadView != null) {
                            touchpadView.setScreenTouchMode(mode);
                            touchpadView.setRtsGesturesEnabled(false);
                        }
                        if (winHandler != null) winHandler.setScreenTouchStickActive(mode == 2);
                        if (shortcut != null) {
                            shortcut.putExtra("screenTouchMode", String.valueOf(mode));
                            shortcut.putExtra("simTouchScreen", mode == 1 ? "1" : "0");
                            shortcut.putExtra("rtsGestures", "0");
                            shortcut.saveData();
                        }
                        renderDrawerMenu();
                    }

                    @Override
                    public void onRtsGesturesToggled(boolean enabled) {
                        rtsGesturesEnabled = enabled;
                        screenTouchMode = 0;
                        if (touchpadView != null) {
                            touchpadView.setRtsGesturesEnabled(enabled);
                            touchpadView.setScreenTouchMode(0);
                        }
                        if (winHandler != null) winHandler.setScreenTouchStickActive(false);
                        if (enabled) pushSelectedGestureConfig();
                        if (shortcut != null) {
                            shortcut.putExtra("rtsGestures", enabled ? "1" : "0");
                            shortcut.putExtra("screenTouchMode", "0");
                            shortcut.putExtra("simTouchScreen", "0");
                            shortcut.saveData();
                        }
                        renderDrawerMenu();
                    }

                    @Override
                    public void onGestureProfileSelected(int index) {
                        ArrayList<GestureProfile> profiles = gestureProfileManager.getProfiles();
                        if (index < 0 || index >= profiles.size()) return;
                        GestureProfile p = profiles.get(index);
                        currentGestureProfileId = p.id;
                        if (touchpadView != null) touchpadView.setGestureConfig(p.getConfigJson());
                        renderDrawerMenu();
                    }

                    @Override
                    public void onRtsGesturesEditClick() {
                        ControlsProfile activeProfile = inputControlsView != null ? inputControlsView.getProfile() : null;
                        Intent intent = new Intent(XServerDisplayActivity.this, UnifiedActivity.class);
                        intent.putExtra("edit_input_controls", true);
                        intent.putExtra("selected_profile_id", activeProfile != null ? activeProfile.id : 0);
                        intent.putExtra("gesture_profile_id", selectedGestureProfileId());
                        intent.putExtra("return_to_game_on_back", true);
                        final ControlsProfile editingProfile = activeProfile;
                        editInputControlsCallback = () -> {
                            gestureProfileManager.loadProfiles();
                            int gid = selectedGestureProfileId();
                            GestureProfile gp = gid != 0 ? gestureProfileManager.getProfile(gid) : gestureProfileManager.getDefaultProfile();
                            if (gp == null) gp = gestureProfileManager.getDefaultProfile();
                            if (touchpadView != null) touchpadView.setGestureConfig(gp.getConfigJson());
                            hideInputControls();
                            if (inputControlsManager != null) inputControlsManager.loadProfiles(true);
                            ControlsProfile reactivated = editingProfile != null && inputControlsManager != null ? inputControlsManager.getProfile(editingProfile.id) : null;
                            if (reactivated != null) showInputControls(reactivated);
                            renderDrawerMenu();
                        };
                        controlsEditorActivityResultLauncher.launch(intent);
                    }

                    @Override
                    public void onRightStickSensitivityChanged(float sensitivity) {
                        if (screenTouchMode == 2) {
                            preferences.edit().putFloat("screen_touch_rs_sensitivity", sensitivity).apply();
                        } else {
                            preferences.edit().putFloat("right_stick_sensitivity", sensitivity).apply();
                            if (winHandler != null) winHandler.setRightStickSensitivity(sensitivity);
                        }
                        renderDrawerMenu();
                    }

                    @Override
                    public void onTaskManagerVisibilityChanged(boolean visible) {
                        taskManagerPaneVisible = visible;
                        if (visible) startTaskManagerPolling();
                        else stopTaskManagerPolling();
                    }

                    @Override
                    public void onTaskManagerCpuExpandedChanged(boolean expanded) {
                        taskManagerCpuExpanded = expanded;
                        pushTaskManagerSystemStats();
                    }

                    @Override
                    public void onTaskManagerEndProcess(String name) {
                        if (winHandler != null) winHandler.killProcess(name);
                    }

                    @Override
                    public void onTaskManagerBringToFront(String name) {
                        if (winHandler != null) winHandler.bringToFront(name);
                        closeDrawerMenu();
                    }

                    @Override
                    public void onTaskManagerSetAffinity(int pid, int affinityMask) {
                        if (winHandler != null) {
                            winHandler.setProcessAffinity(pid, affinityMask);
                            winHandler.listProcesses();
                        }
                    }

                    @Override
                    public void onTaskManagerNewTask(String command) {
                        if (winHandler != null) winHandler.exec(command);
                    }

                    @Override
                    public void onLogsClear() {
                        if (drawerStateHolder != null) drawerStateHolder.clearLogLines();
                    }

                    @Override
                    public void onLogsPauseChanged(boolean paused) {
                        if (drawerStateHolder != null) drawerStateHolder.setLogsPaused(paused);
                    }

                    @Override
                    public void onLogsPaneVisibilityChanged(boolean visible) {
                        if (drawerStateHolder != null) drawerStateHolder.setLogsPaneVisible(visible);
                    }

                    @Override
                    public void onLogsShare() {
                        shareLogStream();
                    }

                    @Override
                    public void onRecordStart(int fpsIndex, int resolutionIndex, int quality, boolean recordUI) {
                        startRecordingWithSettings(fpsIndex, resolutionIndex, quality, recordUI);
                    }
                };
        }

        if (drawerStateHolder == null) {
            drawerStateHolder = new XServerDrawerStateHolder(state);
            XServerDisplayHostKt.setupXServerDisplayHost(
                    displayHostComposeView,
                    xServerDisplayFrame,
                    drawerStateHolder,
                    drawerActionListener,
                    new XServerDisplayHostCallbacks() {
                        @Override
                        public void onDrawerSlide() {
                            // Per frame: avoid hideSystemUI's relayout unless bars actually showed.
                            AppUtils.hideSystemUIIfVisible(XServerDisplayActivity.this);
                        }

                        @Override
                        public void onDrawerOpened() {
                            releasePointerCapture();
                            if (winHandler != null) {
                                winHandler.neutralizeControllers();
                            }
                            renderDrawerMenu();
                            if (drawerStateHolder != null) {
                                drawerStateHolder.resetMenuNav();
                                drawerStateHolder.updateControllerConnected(isAnyControllerConnected());
                            }
                            AppUtils.hideSystemUI(XServerDisplayActivity.this);
                        }

                        @Override
                        public void onDrawerClosed() {
                            drawerStickHandler.removeCallbacks(drawerStickRepeat);
                            drawerStickDir = 0;
                            if (hudCardExpanded) {
                                hudCardExpanded = false;
                                renderDrawerMenu();
                            }
                            updatePointerCapture();
                            AppUtils.hideSystemUI(XServerDisplayActivity.this);
                        }

                        @Override
                        public void onDrawerGestureClaimed() {
                            if (touchpadView != null) {
                                touchpadView.resetInputState();
                            }
                            if (inputControlsView != null) {
                                inputControlsView.cancelActiveTouches();
                            }
                        }

                        @Override
                        public void onDialogVisibilityChanged(boolean visible) {
                            if (visible && displayHostComposeView != null) {
                                displayHostComposeView.requestFocus();
                            }
                        }

                        @Override
                        public boolean isControllerConnected() {
                            return isAnyControllerConnected();
                        }
                    }
            );
            return;
        }

        drawerStateHolder.setState(state);
    }

    private void startTaskManagerPolling() {
        if (winHandler == null) return;
        stopTaskManagerPolling();
        winHandler.setOnGetProcessInfoListener(new OnGetProcessInfoListener() {
            @Override
            public void onGetProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
                runOnUiThread(() -> handleTaskManagerProcessInfo(index, numProcesses, processInfo));
            }
        });

        Timer timer = new Timer();
        taskManagerTimer = timer;
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> {
                    if (winHandler != null) winHandler.listProcesses();
                    pushTaskManagerSystemStats();
                });
            }
        }, 0, 1000);
    }

    private void stopTaskManagerPolling() {
        if (taskManagerTimer != null) {
            taskManagerTimer.cancel();
            taskManagerTimer = null;
        }
        if (winHandler != null) winHandler.setOnGetProcessInfoListener(null);
        taskManagerAccum.clear();
        taskManagerCpuExpanded = false;
        prevTaskCpuSample = null;
        if (drawerStateHolder != null) {
            drawerStateHolder.setTaskManagerState(new TaskManagerPaneState(
                    new ArrayList<>(), 0, 0, new ArrayList<>(), 0, ""));
        }
    }

    private void handleTaskManagerProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        if (drawerStateHolder == null) return;

        if (index == 0) taskManagerAccum.clear();

        if (numProcesses == 0) {
            taskManagerAccum.clear();
            TaskManagerPaneState current = drawerStateHolder.getTaskManagerState();
            drawerStateHolder.setTaskManagerState(new TaskManagerPaneState(
                    new ArrayList<>(),
                    current.getCpuPercent(),
                    current.getCpuCoreCount(),
                    current.getCpuCorePercents(),
                    current.getMemoryPercent(),
                    current.getMemoryDetail()));
            return;
        }

        taskManagerAccum.add(new TaskManagerProcess(
                processInfo.pid,
                processInfo.name,
                processInfo.getFormattedMemoryUsage(),
                processInfo.affinityMask,
                processInfo.wow64Process));

        if (index == numProcesses - 1) {
            TaskManagerPaneState current = drawerStateHolder.getTaskManagerState();
            drawerStateHolder.setTaskManagerState(new TaskManagerPaneState(
                    new ArrayList<>(taskManagerAccum),
                    current.getCpuPercent(),
                    current.getCpuCoreCount(),
                    current.getCpuCorePercents(),
                    current.getMemoryPercent(),
                    current.getMemoryDetail()));
        }
    }

    private void pushTaskManagerSystemStats() {
        if (drawerStateHolder == null) return;

        CPUStatus.AppCpuSample cpuSample = CPUStatus.readAppCpuSample();
        int cpuPercent = -1;
        if (cpuSample != null) {
            if (prevTaskCpuSample != null) cpuPercent = cpuSample.percentSince(prevTaskCpuSample);
            prevTaskCpuSample = cpuSample;
        } else {
            prevTaskCpuSample = null;
        }
        if (cpuPercent < 0) cpuPercent = CPUStatus.getClockFreqLoadPercent();
        if (cpuPercent < 0) cpuPercent = 0;

        short[] clocks = CPUStatus.getCurrentClockSpeeds();
        int coreCount = clocks != null ? clocks.length : 0;
        ArrayList<Integer> corePercents = new ArrayList<>();
        if (taskManagerCpuExpanded) {
            for (int i = 0; i < coreCount; i++) {
                corePercents.add(CPUStatus.getClockFreqCorePercent(i));
            }
        }

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        long usedMem = memInfo.totalMem - memInfo.availMem;
        int memPercent = (int) (((double) usedMem / memInfo.totalMem) * 100.0f);
        String memDetail = StringUtils.formatBytes(usedMem, false) + "/" + StringUtils.formatBytes(memInfo.totalMem);

        TaskManagerPaneState current = drawerStateHolder.getTaskManagerState();
        drawerStateHolder.setTaskManagerState(new TaskManagerPaneState(
                current.getProcesses(),
                cpuPercent,
                coreCount,
                corePercents,
                memPercent,
                memDetail));
    }

    private void saveSGSRShortcutSettings() {
        if (shortcut != null) {
            if (sgsrEnabled) {
                shortcut.putExtra("sgsrEnabled", "1");
                shortcut.putExtra("sgsrUpscaleMode", String.valueOf(normalizeSGSRShortcutUpscaleMode(sgsrUpscaleMode)));
                shortcut.putExtra("sgsrSharpness", String.valueOf(Math.max(0, Math.min(100, sgsrSharpness))));
            } else {
                shortcut.putExtra("sgsrEnabled", null);
                shortcut.putExtra("sgsrUpscaleMode", null);
                shortcut.putExtra("sgsrSharpness", null);
            }
            shortcut.saveData();
        } else if (preferences != null) {
            preferences.edit()
                    .putBoolean("sgsr_enabled", sgsrEnabled)
                    .putInt("sgsr_upscale_mode", clampSGSRUpscaleMode(sgsrUpscaleMode))
                    .putInt("sgsr_sharpness", Math.max(0, Math.min(100, sgsrSharpness)))
                    .apply();
        }
    }

    private boolean canEnableSGSRLiveWithoutResize() {
        if (xServer == null || sgsrBaseScreenSize == null || sgsrBaseScreenSize.isEmpty()) {
            return false;
        }

        String targetScreenSize =
                SGSRResolutionUtils.applyRenderScale(sgsrBaseScreenSize, true, sgsrUpscaleMode);
        String currentScreenSize = xServer.screenInfo.toString();
        boolean canEnable = targetScreenSize.equals(currentScreenSize);
        if (!canEnable) {
            Log.i("SGSRResize", "SGSR live enable blocked: current='" + currentScreenSize +
                    "' target='" + targetScreenSize + "' base='" + sgsrBaseScreenSize +
                    "' mode=" + sgsrUpscaleMode);
        }
        return canEnable;
    }

    private void logDeferredSGSRRestoreIfNeeded(boolean wasActive) {
        if (!wasActive || xServer == null || sgsrBaseScreenSize == null || sgsrBaseScreenSize.isEmpty()) {
            return;
        }
        final String currentScreenSize = xServer.screenInfo.toString();
        if (!sgsrBaseScreenSize.equals(currentScreenSize)) {
            Log.i("SGSRResize", "SGSR disabled mid-session; native XServer restore is deferred until next launch: current='" +
                    currentScreenSize + "' base='" + sgsrBaseScreenSize + "' mode=" + sgsrUpscaleMode);
        } else {
            Log.i("SGSRResize", "SGSR disabled mid-session; XServer already at native size '" +
                    currentScreenSize + "'");
        }
    }

    private void applyScreenEffects() {
        VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
        if (renderer == null) return;
        EffectComposer composer = renderer.getEffectComposer();
        if (composer == null) return;

        SGSRUpscaler sgsr = composer.getEffect(SGSRUpscaler.class);
        if (sgsrRuntimeEnabled) {
            if (sgsr == null) {
                sgsr = new SGSRUpscaler();
            }
            sgsr.setSharpness(sgsrSharpness / 100.0f);
            composer.addEffectFirst(sgsr);
            Log.d("XServerDisplayActivity", "SGSR active mode=" + sgsrUpscaleMode
                    + " sharpness=" + sgsrSharpness);
        } else if (sgsr != null) {
            composer.removeEffect(sgsr);
            Log.d("XServerDisplayActivity", "SGSR inactive");
        }

        // Rebuilt in a fixed order each call so toggle sequence can't reorder the chain.
        composer.removeEffect(composer.getEffect(ColorAdjustEffect.class));
        composer.removeEffect(composer.getEffect(ColorGradeEffect.class));
        composer.removeEffect(composer.getEffect(PixelateEffect.class));
        composer.removeEffect(composer.getEffect(SharpenEffect.class));
        composer.removeEffect(composer.getEffect(HDREffect.class));
        composer.removeEffect(composer.getEffect(NaturalEffect.class));
        composer.removeEffect(composer.getEffect(CRTEffect.class));
        composer.removeEffect(composer.getEffect(ToonEffect.class));
        composer.removeEffect(composer.getEffect(NTSCEffect.class));
        composer.removeEffect(composer.getEffect(NTSC2Effect.class));
        composer.removeEffect(composer.getEffect(VividEffect.class));
        composer.removeEffect(composer.getEffect(ColorBlindEffect.class));
        composer.removeEffect(composer.getEffect(ScanlinesEffect.class));

        if (brightness != 0 || contrast != 0 || gammaPercent != 100) {
            ColorAdjustEffect colorAdj = new ColorAdjustEffect();
            colorAdj.set(brightness / 100.0f, contrast / 100.0f, gammaPercent / 100.0f);
            composer.addEffect(colorAdj);
        }

        if (saturation != 100 || temperature != 0 || tint != 0) {
            ColorGradeEffect colorGrade = new ColorGradeEffect();
            colorGrade.set(saturation / 100.0f, temperature / 100.0f, tint / 100.0f);
            composer.addEffect(colorGrade);
        }

        if (pixelateEnabled) {
            PixelateEffect pixelate = new PixelateEffect();
            pixelate.setBlockSize(pixelateBlock);
            composer.addEffect(pixelate);
        }

        if (sharpenEnabled) {
            SharpenEffect sharpen = new SharpenEffect();
            sharpen.setStrength(sharpenStrength / 100.0f);
            composer.addEffect(sharpen);
        }

        switch (colorProfile) {
            case 1: composer.addEffect(new HDREffect()); break;
            case 2: composer.addEffect(new NaturalEffect()); break;
            case 3: composer.addEffect(new CRTEffect()); break;
            case 4: composer.addEffect(new ToonEffect()); break;
            case 5: composer.addEffect(new NTSCEffect()); break;
            case 6: composer.addEffect(new NTSC2Effect()); break;
        }

        if (vividEnabled) {
            VividEffect vivid = new VividEffect();
            vivid.setLevel((vividStrength / 25.0f) + 1.0f);
            composer.addEffect(vivid);
        }

        if (colorBlind != 0) {
            ColorBlindEffect colorBlindEffect = new ColorBlindEffect();
            colorBlindEffect.setMode(colorBlind);
            composer.addEffect(colorBlindEffect);
        }

        if (scanlinesEnabled) {
            ScanlinesEffect scanlines = new ScanlinesEffect();
            scanlines.setIntensity(scanlinesIntensity / 100.0f);
            composer.addEffect(scanlines);
        }

        renderer.setScaleFilter(scaleFilter);
    }

    private void loadScreenEffectsSettings() {
        if (preferences == null) return;
        boolean legacyEnabled = preferences.getBoolean("fsr_enabled", false);
        int legacyMode = preferences.getInt("fsr_mode", 0);
        int legacyStrength = preferences.getInt("fsr_sharpness", 100);
        if (shortcut != null) {
            sgsrEnabled = parseBoolean(shortcut.getExtra("sgsrEnabled", shortcut.getExtra("sgsr_enabled", "0")));
            sgsrUpscaleMode = normalizeSGSRShortcutUpscaleMode(parsePositiveInt(
                    shortcut.getExtra("sgsrUpscaleMode", shortcut.getExtra("sgsr_upscale_mode", "1"))));
            sgsrSharpness = Math.max(0, Math.min(100, parsePositiveInt(
                    shortcut.getExtra("sgsrSharpness", shortcut.getExtra("sgsr_sharpness", "100")))));
        } else {
            sgsrEnabled = preferences.contains("sgsr_enabled")
                    ? preferences.getBoolean("sgsr_enabled", false)
                    : legacyEnabled && legacyMode == 0;
            sgsrUpscaleMode = clampSGSRUpscaleMode(preferences.getInt("sgsr_upscale_mode", 1));
            sgsrSharpness = preferences.getInt("sgsr_sharpness", legacyStrength);
        }
        loadScreenEffects();
    }

    private void loadScreenEffects() {
        vividEnabled = false;
        vividStrength = 100;
        colorProfile = 0;
        brightness = 0;
        contrast = 0;
        gammaPercent = 100;
        scaleFilter = 0;
        saturation = 100;
        temperature = 0;
        tint = 0;
        sharpenEnabled = false;
        sharpenStrength = 50;
        scanlinesEnabled = false;
        scanlinesIntensity = 50;
        pixelateEnabled = false;
        pixelateBlock = 6;
        colorBlind = 0;
        String json = null;
        if (shortcut != null) {
            String fromShortcut = shortcut.getExtra("screenEffectsSettings", "");
            if (!fromShortcut.isEmpty()) json = fromShortcut;
        } else if (preferences != null) {
            json = preferences.getString("screenEffectsSettings", null);
        }
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            vividEnabled = o.optBoolean("vividEnabled", false);
            vividStrength = Math.max(0, Math.min(100, o.optInt("vividStrength", 100)));
            colorProfile = o.optInt("colorProfile", 0);
            brightness = Math.max(-100, Math.min(100, o.optInt("brightness", 0)));
            contrast = Math.max(-100, Math.min(100, o.optInt("contrast", 0)));
            gammaPercent = Math.max(50, Math.min(250, o.optInt("gammaPercent", 100)));
            scaleFilter = o.optInt("scaleFilter", 0);
            saturation = Math.max(0, Math.min(200, o.optInt("saturation", 100)));
            temperature = Math.max(-100, Math.min(100, o.optInt("temperature", 0)));
            tint = Math.max(-100, Math.min(100, o.optInt("tint", 0)));
            sharpenEnabled = o.optBoolean("sharpenEnabled", false);
            sharpenStrength = Math.max(0, Math.min(100, o.optInt("sharpenStrength", 50)));
            scanlinesEnabled = o.optBoolean("scanlinesEnabled", false);
            scanlinesIntensity = Math.max(0, Math.min(100, o.optInt("scanlinesIntensity", 50)));
            pixelateEnabled = o.optBoolean("pixelateEnabled", false);
            pixelateBlock = Math.max(2, Math.min(14, o.optInt("pixelateBlock", 6)));
            colorBlind = Math.max(0, Math.min(3, o.optInt("colorBlind", 0)));
        } catch (JSONException e) {
            Log.e("XServerDisplayActivity", "Failed to load screen effects", e);
        }
    }

    private void saveScreenEffectsSettings() {
        if (shortcut == null && preferences == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("vividEnabled", vividEnabled);
            o.put("vividStrength", vividStrength);
            o.put("colorProfile", colorProfile);
            o.put("brightness", brightness);
            o.put("contrast", contrast);
            o.put("gammaPercent", gammaPercent);
            o.put("scaleFilter", scaleFilter);
            o.put("saturation", saturation);
            o.put("temperature", temperature);
            o.put("tint", tint);
            o.put("sharpenEnabled", sharpenEnabled);
            o.put("sharpenStrength", sharpenStrength);
            o.put("scanlinesEnabled", scanlinesEnabled);
            o.put("scanlinesIntensity", scanlinesIntensity);
            o.put("pixelateEnabled", pixelateEnabled);
            o.put("pixelateBlock", pixelateBlock);
            o.put("colorBlind", colorBlind);
            String json = o.toString();
            if (shortcut != null) {
                shortcut.putExtra("screenEffectsSettings", json);
                shortcut.saveData();
            } else if (preferences != null) {
                preferences.edit().putString("screenEffectsSettings", json).apply();
            }
        } catch (JSONException e) {
            Log.e("XServerDisplayActivity", "Failed to save screen effects", e);
        }
    }

    private void loadHUDSettings() {
        if (container == null) return;
        String json = container.getExtra("hudSettings");
        if (json != null && !json.isEmpty()) {
            try {
                JSONObject obj = new JSONObject(json);
                hudTransparency = (float) obj.optDouble("transparency", 1.0);
                hudBackgroundAlphaDecoupled = obj.optBoolean("backgroundAlphaDecoupled", false);
                hudBackgroundTransparency = (float) obj.optDouble("backgroundTransparency",
                        clampHudAlpha(hudTransparency * FrameRating.BACKDROP_BASE_ALPHA));
                if (!hudBackgroundAlphaDecoupled) {
                    hudBackgroundTransparency = clampHudAlpha(hudTransparency * FrameRating.BACKDROP_BASE_ALPHA);
                }
                hudScale = (float) obj.optDouble("scale", 1.0);
                boolean legacyCpuRam = obj.optBoolean("showCpuRam", true);
                boolean legacyBattTemp = obj.optBoolean("showBattTemp", true);
                hudElements[0] = obj.optBoolean("showFPS", true);
                hudElements[1] = obj.optBoolean("showRenderer", true);
                hudElements[2] = obj.optBoolean("showGPU", true);
                hudElements[3] = obj.optBoolean("showCPU", legacyCpuRam);
                hudElements[4] = obj.optBoolean("showRAM", legacyCpuRam);
                hudElements[5] = obj.optBoolean("showBattery", legacyBattTemp);
                hudElements[6] = obj.optBoolean("showTemp", legacyBattTemp);
                hudElements[7] = obj.optBoolean("showGraph", true);
                hudElements[8] = obj.optBoolean("showCpuTemp", false);
            } catch (JSONException e) {
                Log.e("XServerDisplayActivity", "Failed to load HUD settings", e);
            }
        }
        com.winlator.cmod.runtime.display.PerformanceHudState.updateEnabled(hudElements);
    }

    private void saveHUDSettings() {
        if (container == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("transparency", hudTransparency);
            obj.put("backgroundAlphaDecoupled", hudBackgroundAlphaDecoupled);
            obj.put("backgroundTransparency", hudBackgroundTransparency);
            obj.put("scale", hudScale);
            obj.put("showFPS", hudElements[0]);
            obj.put("showRenderer", hudElements[1]);
            obj.put("showGPU", hudElements[2]);
            obj.put("showCPU", hudElements[3]);
            obj.put("showRAM", hudElements[4]);
            obj.put("showBattery", hudElements[5]);
            obj.put("showTemp", hudElements[6]);
            obj.put("showGraph", hudElements[7]);
            obj.put("showCpuTemp", hudElements[8]);
            container.putExtra("hudSettings", obj.toString());
            container.saveData();
        } catch (JSONException e) {
            Log.e("XServerDisplayActivity", "Failed to save HUD settings", e);
        }
    }

    private void applyHUDSettings() {
        if (frameRating != null) {
            frameRating.setHudAlpha(hudTransparency);
            frameRating.setHudBackgroundAlpha(hudBackgroundTransparency);
            frameRating.setBackgroundAlphaDecoupled(hudBackgroundAlphaDecoupled);
            frameRating.setHudScale(hudScale);
            frameRating.setDualSeriesBattery(dualSeriesBattery);
            frameRating.setFrametimeNumericMode(frametimeNumericMode);
            frameRating.setIsNative(isNativeRenderingEnabled);
            for (int i = 0; i < hudElements.length; i++) {
                frameRating.toggleElement(i, hudElements[i]);
            }
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private boolean handleDrawerAction(int itemId) {
        // Startup can open the drawer before display and input views exist.
        if (requiresDisplayReady(itemId) && !isDisplayReady()) {
            renderDrawerMenu();
            return false;
        }

        final VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
        switch (itemId) {
            case R.id.main_menu_gyroscope_reset:
                if (winHandler != null) {
                    winHandler.recenterGyroOrientation();
                    winHandler.updateGyroData(0, 0);
                }
                break;
            case R.id.main_menu_keyboard:
                AppUtils.showKeyboard(this);
                closeDrawerMenu();
                break;
            case R.id.main_menu_controller_manager:
                ControllerAssignmentDialog.show(this, winHandler);
                closeDrawerMenu();
                break;
            case R.id.main_menu_fps_monitor:
                if (frameRating == null) {
                    FrameLayout rootView = xServerDisplayFrame;
                    frameRating = new FrameRating(this, graphicsDriverConfig);
                    frameRating.setRenderer(lastRendererName);
                    if (lastGpuName != null) frameRating.setGpuName(lastGpuName);
                    frameRating.setVisibility(View.GONE);
                    applyHUDSettings();
                    syncFrameGenerationHud();
                    rootView.addView(frameRating);
                    if (perfController != null) perfController.attachToFrameRating(frameRating);
                }
                boolean isFpsVisible = frameRating.getVisibility() == View.VISIBLE;
                boolean becomingVisible = !isFpsVisible;
                frameRating.setVisibility(becomingVisible ? View.VISIBLE : View.GONE);
                if (becomingVisible) {
                    frameRating.reset();
                    syncFrameRatingWithExistingWindows();
                    applyHUDSettings();
                }
                updateHUDRenderMode();
                
                preferences.edit().putBoolean("fps_monitor_enabled", becomingVisible).apply();
                effectiveShowFPS = becomingVisible;
                renderDrawerMenu();
                break;
            case R.id.main_menu_relative_mouse_movement:
                isRelativeMouseMovement = !isRelativeMouseMovement;
                xServer.setRelativeMouseMovement(isRelativeMouseMovement);
                updatePointerCapture();
                renderDrawerMenu();
                break;
            case R.id.main_menu_disable_mouse:
                isMouseDisabled = !isMouseDisabled;
                touchpadView.setMouseEnabled(!isMouseDisabled);
                renderDrawerMenu();
                break;
            case R.id.main_menu_toggle_fullscreen:
                renderer.toggleFullscreen();
                touchpadView.toggleFullscreen();
                renderDrawerMenu();
                break;
            case R.id.main_menu_refactor_size:
                isRefactorSizeEnabled = !isRefactorSizeEnabled;
                applyRefactorSize(isRefactorSizeEnabled);
                renderDrawerMenu();
                break;
            case R.id.main_menu_pause:
                if (isPaused) {
                    ProcessHelper.resumeAllWineProcesses();
                    SessionKeepAliveService.onResumeSession(this);
                }
                else {
                    ProcessHelper.pauseAllWineProcesses();
                    SessionKeepAliveService.onPauseSession(this);
                    if (touchpadView != null) touchpadView.resetInputState();
                    if (inputControlsView != null) inputControlsView.cancelActiveTouches();
                }
                isPaused = !isPaused;
                renderDrawerMenu();
                break;
            case R.id.main_menu_pip_mode:
                enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().build());
                closeDrawerMenu();
                break;
            case R.id.main_menu_magnifier:
                if (magnifierView != null) {
                    xServerDisplayFrame.removeView(magnifierView);
                    magnifierView = null;
                    renderer.setMagnifierZoom(1.0f);
                    renderer.setMagnifierUIActive(false);
                } else {
                    FrameLayout container = xServerDisplayFrame;
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback(value -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                        renderer.setMagnifierZoom(1.0f);
                        renderer.setMagnifierUIActive(false);
                        renderDrawerMenu();
                    });
                    container.addView(magnifierView);
                    renderer.setMagnifierUIActive(true);
                }
                renderDrawerMenu();
                break;
            case R.id.main_menu_record:
                // Starting is handled by the popup (onRecordStart); reaching here means stop.
                if (screenRecorder != null && screenRecorder.isRecording()) stopScreenRecording();
                renderDrawerMenu();
                break;
            case R.id.main_menu_exit:
                closeDrawerMenu();
                exit();
                break;
        }
        return true;
    }

    private static final int[] RECORD_FPS_TIERS = {30, 60, 90, 120, 144, 165};
    private static final int[] RECORD_RES_TIERS = {2160, 1440, 1080, 720}; // short-side heights

    /** FPS options the panel supports (ascending), e.g. a 120Hz panel → [30,60,90,120]. */
    private java.util.List<Integer> recordFpsOptions() {
        int max = Math.max(30, RefreshRateUtils.getMaxSupportedRefreshRate(this));
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int f : RECORD_FPS_TIERS) if (f <= max + 1) out.add(f);
        if (out.isEmpty()) out.add(60);
        return out;
    }

    /** The native (full-res) capture short side — min of the composited image dimensions. */
    private int recordNativeShortSide() {
        VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
        int w = renderer != null ? renderer.getRecordWidth() : 0;
        int h = renderer != null ? renderer.getRecordHeight() : 0;
        if (w <= 0 || h <= 0) {
            w = xServerView != null ? xServerView.getSurfaceWidth() : 0;
            h = xServerView != null ? xServerView.getSurfaceHeight() : 0;
        }
        if (w <= 0 || h <= 0) return 0;
        return Math.min(w, h);
    }

    /** Resolution labels: Native first, then standard tiers strictly below the panel's native res. */
    private java.util.List<String> recordResolutionLabels(int nativeShort) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("Native");
        if (nativeShort > 0) {
            for (int t : RECORD_RES_TIERS) {
                if (t < nativeShort) out.add(resTierLabel(t));
            }
        }
        return out;
    }

    // Build the popup config with persisted selections mapped to current indices.
    private RecordUiConfig buildRecordConfig() {
        java.util.List<Integer> fps = recordFpsOptions();
        int nativeShort = recordNativeShortSide();
        java.util.List<String> res = recordResolutionLabels(nativeShort);

        int savedFps = preferences.getInt("record_fps", 60);
        int fpsIndex = fps.indexOf(savedFps);
        if (fpsIndex < 0) { // nearest supported
            fpsIndex = 0;
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < fps.size(); i++) {
                int d = Math.abs(fps.get(i) - savedFps);
                if (d < best) { best = d; fpsIndex = i; }
            }
        }
        int resIndex = preferences.getInt("record_res_index", 0);
        if (resIndex < 0 || resIndex >= res.size()) resIndex = 0;
        int quality = preferences.getInt("record_quality", 2);
        boolean recordUI = preferences.getBoolean("record_ui", false);
        return new RecordUiConfig(fps, res, fpsIndex, resIndex, quality, recordUI);
    }

    // Start recording with the popup's chosen settings, persisting them for next time.
    private void startRecordingWithSettings(int fpsIndex, int resolutionIndex, int quality, boolean recordUI) {
        if (screenRecorder != null && screenRecorder.isRecording()) return;
        VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
        if (renderer == null || xServerView == null) {
            android.widget.Toast.makeText(this, R.string.session_record_failed, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // Native composited size (swapchain extent), else the SurfaceView size.
        int nativeW = renderer.getRecordWidth();
        int nativeH = renderer.getRecordHeight();
        if (nativeW <= 0 || nativeH <= 0) {
            nativeW = xServerView.getSurfaceWidth();
            nativeH = xServerView.getSurfaceHeight();
        }
        if (nativeW <= 0 || nativeH <= 0) {
            android.widget.Toast.makeText(this, R.string.session_record_failed, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        java.util.List<Integer> fpsOptions = recordFpsOptions();
        int nativeShort = Math.min(nativeW, nativeH);
        java.util.List<String> resLabels = recordResolutionLabels(nativeShort);
        fpsIndex = Math.max(0, Math.min(fpsIndex, fpsOptions.size() - 1));
        resolutionIndex = Math.max(0, Math.min(resolutionIndex, resLabels.size() - 1));
        quality = Math.max(0, Math.min(quality, 2));

        int fps = fpsOptions.get(fpsIndex);

        // Resolution: index 0 = Native; otherwise scale so the short side hits the chosen tier.
        int encW = nativeW, encH = nativeH;
        if (resolutionIndex > 0) {
            int tierShort = tierShortForLabel(resLabels.get(resolutionIndex));
            if (tierShort > 0 && tierShort < nativeShort) {
                double scale = (double) tierShort / nativeShort;
                encW = (int) Math.round(nativeW * scale) & ~1;
                encH = (int) Math.round(nativeH * scale) & ~1;
            }
        }

        int orientationHint = renderer.getRecordOrientationHint();
        int bitRate = recordBitrate(encW, encH, fps, quality);

        // Persist selections for next time.
        preferences.edit()
                .putInt("record_fps", fps)
                .putInt("record_res_index", resolutionIndex)
                .putInt("record_quality", quality)
                .putBoolean("record_ui", recordUI)
                .apply();

        screenRecorder = new com.winlator.cmod.runtime.display.recording.GameRecorder(this);
        android.view.Surface encoderSurface = screenRecorder.start(encW, encH, fps, orientationHint, bitRate);
        if (encoderSurface == null || !renderer.startRecording(encoderSurface, fps, recordUI)) {
            screenRecorder.stop();
            screenRecorder = null;
            android.widget.Toast.makeText(this, R.string.session_record_failed, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Force continuous frames while recording (renderer is otherwise on-demand).
        savedRenderMode = xServerView.getRenderMode();
        xServerView.setRenderMode(XServerSurfaceView.RENDERMODE_CONTINUOUSLY);
        if (recordUI) startRecordUiCapture(encW, encH, orientationHint);
        renderDrawerMenu();
        android.widget.Toast.makeText(this, R.string.session_record_started, android.widget.Toast.LENGTH_SHORT).show();
    }

    // Record UI: snapshot the overlay views and feed them to the native composite.
    private android.os.Handler recordUiHandler;
    private Runnable recordUiSnapshot;
    private android.graphics.Bitmap recordUiBitmap;
    private int[] recordUiPixels;
    private java.nio.ByteBuffer recordUiBuffer;
    private int recordUiW, recordUiH, recordUiRotation;

    private void startRecordUiCapture(int w, int h, int orientationHint) {
        stopRecordUiCapture();
        recordUiW = w;
        recordUiH = h;
        // Pre-rotate the upright screen-space UI into the recording's frame to match the game.
        recordUiRotation = ((360 - (orientationHint % 360)) % 360);
        try {
            recordUiBitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            recordUiPixels = new int[w * h];
            recordUiBuffer = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        } catch (Throwable t) {
            Log.e("XServerDisplayActivity", "Record UI buffer alloc failed", t);
            return;
        }
        recordUiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        recordUiSnapshot = new Runnable() {
            @Override
            public void run() {
                if (screenRecorder == null || !screenRecorder.isRecording()) return;
                snapshotRecordUi();
                if (recordUiHandler != null) recordUiHandler.postDelayed(this, 100); // ~10 fps overlay refresh
            }
        };
        recordUiHandler.post(recordUiSnapshot);
    }

    private void snapshotRecordUi() {
        try {
            VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
            View root = xServerView != null ? xServerView.getRootView() : null;
            if (renderer == null || root == null || recordUiBitmap == null) return;
            int sw = root.getWidth();
            int sh = root.getHeight();
            if (sw <= 0 || sh <= 0) return;

            recordUiBitmap.eraseColor(0); // transparent — the game area (SurfaceView) stays see-through
            android.graphics.Canvas c = new android.graphics.Canvas(recordUiBitmap);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(recordUiRotation, sw / 2f, sh / 2f);
            android.graphics.RectF r = new android.graphics.RectF(0, 0, sw, sh);
            m.mapRect(r);
            m.postTranslate(-r.left, -r.top);
            float s = Math.min(recordUiW / r.width(), recordUiH / r.height());
            m.postScale(s, s);
            c.setMatrix(m);
            root.draw(c);

            recordUiBitmap.getPixels(recordUiPixels, 0, recordUiW, 0, 0, recordUiW, recordUiH);
            recordUiBuffer.clear();
            recordUiBuffer.asIntBuffer().put(recordUiPixels); // little-endian int → BGRA bytes
            recordUiBuffer.position(0);
            renderer.updateRecordUITexture(recordUiBuffer, recordUiW, recordUiH);
        } catch (Throwable t) {
            Log.e("XServerDisplayActivity", "Record UI snapshot failed", t);
        }
    }

    private void stopRecordUiCapture() {
        if (recordUiHandler != null && recordUiSnapshot != null) {
            recordUiHandler.removeCallbacks(recordUiSnapshot);
        }
        recordUiHandler = null;
        recordUiSnapshot = null;
        if (recordUiBitmap != null) {
            try { recordUiBitmap.recycle(); } catch (Exception ignore) {}
        }
        recordUiBitmap = null;
        recordUiPixels = null;
        recordUiBuffer = null;
    }

    private void stopScreenRecording() {
        if (screenRecorder == null) return;
        stopRecordUiCapture();
        if (xServerView != null) xServerView.setRenderMode(savedRenderMode);
        VulkanRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
        if (renderer != null) renderer.stopRecording();
        screenRecorder.stop();
        screenRecorder = null;
        android.widget.Toast.makeText(this, R.string.session_record_saved, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void applyRefactorSize(boolean enabled) {
        if (winHandler == null || container == null) return;
        if (enabled) stageRefactorSizeHelper();
        winHandler.exec("\"C:\\WinNative\\refactorsize.exe\" " + (enabled ? "on" : "off"));
        if (!enabled) unstageRefactorSizeHelper();
    }

    private void stageRefactorSizeHelper() {
        try {
            File dir = new File(container.getRootDir(), ".wine/drive_c/WinNative");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File dst = new File(dir, "refactorsize.exe");
            if (dst.exists() && dst.length() == REFACTOR_SIZE_EXE_BYTES) return;
            try (java.io.InputStream in = getAssets().open("winnative/refactorsize.exe");
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Log.i("XServerDisplayActivity",
                  "Refactor Size: staged refactorsize.exe (" + dst.length() + " B) at " + dst.getPath());
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Refactor Size: helper staging failed", e);
        }
    }

    private void stageBundledTestExes() {
        if (container == null) return;
        File dir = new File(container.getRootDir(), ".wine/drive_c/ProgramData/Microsoft/Windows");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        stageBundledExe(dir, "Graphics-Test-32bit.exe", GRAPHICS_TEST_32_EXE_BYTES);
        stageBundledExe(dir, "Graphics-Test-64bit.exe", GRAPHICS_TEST_64_EXE_BYTES);
        stageBundledExe(dir, "InputControl32.exe", INPUT_TEST_32_EXE_BYTES);
        stageBundledExe(dir, "InputControl64.exe", INPUT_TEST_64_EXE_BYTES);
    }

    private void stageBundledExe(File dir, String name, long expectedBytes) {
        File dst = new File(dir, name);
        if (dst.exists() && dst.length() == expectedBytes) return;
        try (java.io.InputStream in = getAssets().open("winnative/" + name);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Failed to stage " + name, e);
        }
    }

    private void unstageRefactorSizeHelper() {
        final File dst = new File(container.getRootDir(), ".wine/drive_c/WinNative/refactorsize.exe");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!isRefactorSizeEnabled && dst.exists()) dst.delete();
        }, REFACTOR_SIZE_UNSTAGE_DELAY_MS);
    }

    private boolean isDisplayReady() {
        return xServer != null
                && xServerView != null
                && xServerView.getRenderer() != null
                && touchpadView != null
                && inputControlsView != null;
    }

    private boolean requiresDisplayReady(int itemId) {
        switch (itemId) {
            case R.id.main_menu_input_controls:
            case R.id.main_menu_fps_monitor:
            case R.id.main_menu_relative_mouse_movement:
            case R.id.main_menu_disable_mouse:
            case R.id.main_menu_toggle_fullscreen:
            case R.id.main_menu_magnifier:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && shouldUsePointerCapture()) {
            updatePointerCapture();
        }
        else if (!hasFocus) {
            releasePointerCapture();
        }
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
        if (xServer != null) {
            xServer.setPointerCaptureActive(hasCapture);
        }
    }

    private boolean shouldUsePointerCapture() {
        return !isPointerCaptureForcedOff && (drawerStateHolder == null || !drawerStateHolder.isDrawerOpen());
    }

    private void updatePointerCapture() {
        if (touchpadView == null) return;
        if (shouldUsePointerCapture()) {
            touchpadView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                @Override
                public boolean onCapturedPointer(View view, MotionEvent event) {
                    handleCapturedPointer(event);
                    return true;
                }
            });
            if (!touchpadView.hasPointerCapture()) {
                touchpadView.requestFocus();
                touchpadView.requestPointerCapture();
            }
        } else {
            releasePointerCapture();
        }
    }

    private void releasePointerCapture() {
        boolean hadPointerCapture = touchpadView != null && touchpadView.hasPointerCapture();
        if (touchpadView != null) {
            if (hadPointerCapture) {
                touchpadView.resetInputState();
                touchpadView.releasePointerCapture();
            }
            touchpadView.setOnCapturedPointerListener(null);
        }
        if (inputControlsView != null) {
            if (hadPointerCapture) {
                inputControlsView.cancelActiveTouches();
            }
            else {
                inputControlsView.cancelContinuousMouseMove();
            }
        }
    }

    private void cancelMousePointerTimeout() {
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable);
        }
    }

    // Pointer movement that bypasses touch/mouse events (gyro) still counts as activity.
    public void notifyPointerActivity() {
        if (isMouseDisabled || xServer == null || xServer.getRenderer() == null) return;
        boolean hidden = !xServer.getRenderer().isCursorVisible();
        long now = SystemClock.uptimeMillis();
        if (!hidden && now - lastPointerActivityAt < POINTER_ACTIVITY_REARM_MS) return;
        lastPointerActivityAt = now;
        if (hidden) xServer.getRenderer().setCursorVisible(true);
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable);
            timeoutHandler.postDelayed(hideControlsRunnable, 5000);
        }
    }

    private boolean isPointerMotionEvent(MotionEvent event) {
        int source = event.getSource();
        boolean isPointerClass =
                (source & InputDevice.SOURCE_CLASS_POINTER) == InputDevice.SOURCE_CLASS_POINTER;
        return isPointerClass && !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN);
    }

    private boolean isControllerMotionEvent(MotionEvent event) {
        int source = event.getSource();
        boolean isGamepad =
                (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        boolean isJoystick =
                (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        return (isGamepad || isJoystick) && !isPointerMotionEvent(event);
    }

    // Steam prefix setup (DLL injection / bridge install / asset staging); independent of setupWineSystemFiles so it runs as a parallel Future.
    private void setupSteamGameFiles() {
        if (!isSteamShortcut()) return;
        try {
            int appId = Integer.parseInt(shortcut.getExtra("app_id"));
            String gameInstallPath = resolveSteamGameInstallPath(appId);
            File gameDir = new File(gameInstallPath);
            String language = PrefManager.INSTANCE.getContainerLanguage();
            String containerLang = container.getExtra("containerLanguage", null);
            if (containerLang != null && !containerLang.isEmpty()) {
                language = containerLang;
            }
            boolean isOfflineMode = parseBoolean(
                    getShortcutSetting("steamOfflineMode",
                            container.isSteamOfflineMode() ? "1" : "0"));
            boolean useSteamInput = parseBoolean(
                    getShortcutSetting("useSteamInput",
                            container.getExtra("useSteamInput", "0")));
            boolean unpackFiles = parseBoolean(
                    getShortcutSetting("unpackFiles",
                            container.isUnpackFiles() ? "1" : "0"));
            boolean runtimePatcher = parseBoolean(
                    getShortcutSetting("runtimePatcher",
                            container.isRuntimePatcher() ? "1" : "0"));

            boolean wnPlanWActive = com.winlator.cmod.feature.stores.steam.utils
                    .PrefManager.INSTANCE.getWnPlanW();
            String ticketBase64 = null;
            if (!wnPlanWActive) {
                try {
                    ticketBase64 = SteamBridge.getEncryptedAppTicketBase64(appId);
                } catch (Exception e) {
                    Log.w("XServerDisplayActivity", "Failed to get encrypted app ticket", e);
                }
            }

            if (!gameDir.exists()) return;

            syncContainerSteamExecutableFromShortcut(appId, gameInstallPath);
            boolean useColdClient = isColdClientEnabledForShortcut();

            if (useColdClient) {
                clearBionicActiveProcessRegistry();
                new File(container.getRootDir(),
                        ".wine/drive_c/windows/system32/lsteamclient.dll").delete();
                new File(container.getRootDir(),
                        ".wine/drive_c/windows/syswow64/lsteamclient.dll").delete();

                MarkerUtils.INSTANCE.removeMarker(gameInstallPath, Marker.STEAM_DLL_REPLACED);
                MarkerUtils.INSTANCE.removeMarker(gameInstallPath, Marker.STEAM_DLL_RESTORED);

                boolean sidecarReady = ensureColdClientStore();
                if (!sidecarReady) {
                    Log.w("XServerDisplayActivity", "ColdClient sidecar store not ready — loader/Goldberg stubs missing");
                }

                boolean coldClientProvisioned =
                        MarkerUtils.INSTANCE.hasMarker(gameInstallPath, Marker.STEAM_COLDCLIENT_USED);
                if (!coldClientProvisioned) {
                    SteamUtils.putBackSteamDlls(gameInstallPath);
                    SteamUtils.restoreUnpackedExecutable(this, appId);
                    generateSteamInterfacesForGame(gameDir);
                } else {
                    Log.d("XServerDisplayActivity", "ColdClient prefix already provisioned for appId=" + appId);
                }

                File steamDir = new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam");
                steamDir.mkdirs();
                SteamUtils.writeCompleteSettingsDir(steamDir, appId, language, isOfflineMode, useSteamInput, ticketBase64);
                SteamUtils.enrichSteamSettings(this, appId, new File(steamDir, "steam_settings"));
                setupSteamSettingsForAllDirs(gameDir, appId, language, isOfflineMode, useSteamInput, ticketBase64);

                File steamappsDir = new File(steamDir, "steamapps");
                new File(steamappsDir, "common").mkdirs();
                WineUtils.ensureSteamappsCommonSymlink(container, gameInstallPath);

                String relativeExeForIni = resolveRelativeGameExe(appId, gameInstallPath);
                if (!relativeExeForIni.isEmpty()) {
                    String gameDirNameForIni = new File(gameInstallPath).getName();
                    writeColdClientIniDirect(appId, gameDirNameForIni, relativeExeForIni, runtimePatcher);
                    Log.d("XServerDisplayActivity", "ColdClient INI: exe=" + relativeExeForIni);
                } else {
                    Log.w("XServerDisplayActivity", "Could not find game exe for ColdClient INI, appId=" + appId);
                }

                MarkerUtils.INSTANCE.addMarker(gameInstallPath, Marker.STEAM_COLDCLIENT_USED);
                com.winlator.cmod.feature.stores.steam.utils.SteamUtils
                        .invalidateInstallScan(this, appId, gameDir);
            } else if (isBionicSteamEnabledForShortcut()) {
                MarkerUtils.INSTANCE.removeMarker(gameInstallPath, Marker.STEAM_DLL_REPLACED);
                MarkerUtils.INSTANCE.removeMarker(gameInstallPath, Marker.STEAM_COLDCLIENT_USED);
                MarkerUtils.INSTANCE.removeMarker(gameInstallPath, Marker.STEAM_DRM_PATCHED);
                MarkerUtils.INSTANCE.removeMarker(gameInstallPath, Marker.STEAM_DRM_UNPACK_CHECKED);

                SteamUtils.restoreOriginalExecutable(this, appId);

                boolean bionicRuntimeOk = com.winlator.cmod.feature.stores.steam.wnsteam
                        .WnSteamAssetsInstaller.INSTANCE.installBionicRuntime(this);
                boolean bionicBridgeOk;
                if (wnPlanWActive) {
                    File sys32Bridge = new File(container.getRootDir(),
                            ".wine/drive_c/windows/system32/lsteamclient.dll");
                    File syswow64Bridge = new File(container.getRootDir(),
                            ".wine/drive_c/windows/syswow64/lsteamclient.dll");
                    int scrubbed = 0;
                    if (sys32Bridge.exists() && sys32Bridge.delete()) scrubbed++;
                    if (syswow64Bridge.exists() && syswow64Bridge.delete()) scrubbed++;
                    Log.i("XServerDisplayActivity",
                            "Steam Launcher: scrubbed " + scrubbed
                            + " stale lsteamclient.dll bridge file(s) from system32/syswow64");
                    bionicBridgeOk = false;
                } else {
                    bionicBridgeOk = com.winlator.cmod.feature.stores.steam.wnsteam
                            .WnSteamAssetsInstaller.INSTANCE
                            .installSteamclientBridgeIntoContainer(this, container);
                }

                if (wnPlanWActive) {
                    if (com.winlator.cmod.feature.stores.steam.utils.SteamUtils
                            .scanInstall(this, appId, gameDir).hasSteamApiOrig) {
                        restoreSteamApiDlls(gameDir);
                        com.winlator.cmod.feature.stores.steam.utils.SteamUtils
                                .invalidateInstallScan(this, appId, gameDir);
                    } else {
                        Log.d("XServerDisplayActivity",
                                "Steam Launcher: install has no steam_api .orig backups — skipping restore walk");
                    }
                } else {
                    int steampipeSwapped = com.winlator.cmod.feature.stores.steam.wnsteam
                            .WnSteamAssetsInstaller.INSTANCE
                            .installSteampipeBridgeIntoApp(this, gameDir);
                    com.winlator.cmod.feature.stores.steam.utils.SteamUtils
                            .invalidateInstallScan(this, appId, gameDir);
                    Log.d("XServerDisplayActivity",
                            "Bionic Steam: " + steampipeSwapped
                            + " steam_api*.dll(s) replaced with steampipe bridge");
                }

                writeBionicActiveProcessRegistry();

                File bionicSteamDir = new File(container.getRootDir(),
                        ".wine/drive_c/Program Files (x86)/Steam");
                bionicSteamDir.mkdirs();
                WineUtils.ensureSteamappsCommonSymlink(container, gameInstallPath,
                        canonicalSteamInstallDir(appId));

                boolean bionicOverlayOk = false;
                if (!wnPlanWActive) {
                    bionicOverlayOk = installBionicSteamPathOverlay(container, bionicSteamDir);
                }

                boolean planWValveOk = false;
                boolean planWLauncherOk = false;
                boolean planWServiceOk = false;
                if (wnPlanWActive) {
                    try {
                        planWValveOk = com.winlator.cmod.feature.stores.steam.wnsteam
                                .WnSteamAssetsInstaller.INSTANCE
                                .installPlanWValveSteam(this, container);
                        planWLauncherOk = com.winlator.cmod.feature.stores.steam.wnsteam
                                .WnSteamAssetsInstaller.INSTANCE
                                .installPlanWLauncher(this, container);
                        planWServiceOk = com.winlator.cmod.feature.stores.steam.wnsteam
                                .WnSteamAssetsInstaller.INSTANCE
                                .installPlanWSteamService(this, container);
                        Log.i("XServerDisplayActivity",
                                "Steam Launcher asset stage: valveSteam=" + planWValveOk
                                + " launcher=" + planWLauncherOk
                                + " service=" + planWServiceOk);
                    } catch (Exception e) {
                        Log.e("XServerDisplayActivity",
                                "Steam Launcher asset stage failed", e);
                    }
                }

                Log.d("XServerDisplayActivity",
                        "Bionic Steam game-side setup complete for appId=" + appId
                                + " runtime=" + bionicRuntimeOk
                                + " bridge=" + bionicBridgeOk
                                + " steamPathOverlay=" + bionicOverlayOk
                                + " planWValve=" + planWValveOk
                                + " planWLauncher=" + planWLauncherOk);
            } else {
                if (MarkerUtils.INSTANCE.hasMarker(gameInstallPath, Marker.STEAM_COLDCLIENT_USED)) {
                    SteamUtils.restoreSteamclientFiles(this, appId);
                    MarkerUtils.INSTANCE.removeMarker(gameInstallPath, Marker.STEAM_COLDCLIENT_USED);
                    Log.d("XServerDisplayActivity", "Restored steamclient DLLs from prior ColdClient mode");
                }

                if (!MarkerUtils.INSTANCE.hasMarker(gameInstallPath, Marker.STEAM_DLL_REPLACED)) {
                    MarkerUtils.INSTANCE.removeMarker(gameInstallPath, Marker.STEAM_DLL_RESTORED);

                    replaceSteamApiDlls(gameDir, gameInstallPath, language, isOfflineMode, useSteamInput, ticketBase64);

                    if (unpackFiles) {
                        SteamUtils.restoreUnpackedExecutable(this, appId);
                    } else {
                        SteamUtils.restoreOriginalExecutable(this, appId);
                    }

                    SteamUtils.restoreSteamclientFiles(this, appId);
                    SteamUtils.enrichSteamSettings(this, appId,
                            new File(gameInstallPath, "steam_settings"));

                    MarkerUtils.INSTANCE.addMarker(gameInstallPath, Marker.STEAM_DLL_REPLACED);
                } else {
                    boolean hasSteamApiDll = hasSteamApiDllInTree(gameDir);
                    if (!hasSteamApiDll) {
                        Log.w("XServerDisplayActivity",
                                "STEAM_DLL_REPLACED marker set but no steam_api DLL found — clearing marker and re-injecting");
                        MarkerUtils.INSTANCE.removeMarker(gameInstallPath, Marker.STEAM_DLL_REPLACED);
                        replaceSteamApiDlls(gameDir, gameInstallPath, language, isOfflineMode, useSteamInput, ticketBase64);
                        MarkerUtils.INSTANCE.addMarker(gameInstallPath, Marker.STEAM_DLL_REPLACED);
                    } else {
                        setupSteamSettingsForAllDirs(gameDir, appId, language, isOfflineMode, useSteamInput, ticketBase64);
                    }
                    SteamUtils.enrichSteamSettings(this, appId,
                            new File(gameInstallPath, "steam_settings"));
                    copySteamclientStubs(gameDir);
                }
            }

            setupSteamEnvironment(appId, gameDir);
            SteamUtils.syncGoldbergAchievementsAndStats(this, appId);
            cleanupEmbeddedSteamRuntime(gameDir);

            Log.d("XServerDisplayActivity", "Steam environment physical readiness verified for appId=" + appId);
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Failed to set up Steam environment", e);
        }
    }

    private void setupWineSystemFiles() {
        Log.d("ContainerLaunch", "=== setupWineSystemFiles START === container=" + container.id +
                " wine=" + wineVersion + " arch=" + (wineInfo != null ? wineInfo.getArch() : "null") +
                " rootDir=" + container.getRootDir().getAbsolutePath());

        ensureWinePrefixReady();
        ensureLaunchRuntimeFilesReady();

        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String imgVersion = String.valueOf(imageFs.getVersion());
        boolean containerDataChanged = false;

        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("imgVersion").equals(imgVersion)) {
            Log.d("ContainerLaunch", "Version mismatch, applying general patches (app=" + appVersion + " img=" + imgVersion + ")");
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("imgVersion", imgVersion);
            firstTimeBoot = true;
            containerDataChanged = true;
        }

        ensureWinePrefixEssentialFiles();

        String dxwrapper = shortcut != null ? getShortcutSetting("dxwrapper", this.dxwrapper) : this.dxwrapper;
        String dxwrapperConfig =
                shortcut != null
                        ? getShortcutSetting("dxwrapperConfig", this.dxwrapperConfig.toString())
                        : this.dxwrapperConfig.toString();
        KeyValueSet currentDXWrapperConfig = DXVKConfigUtils.parseConfig(dxwrapperConfig);

        if (dxwrapper.contains("dxvk")) {
            String dxvkWrapper = "dxvk-" + currentDXWrapperConfig.get("version");
            String vkd3dWrapper = "vkd3d-" + currentDXWrapperConfig.get("vkd3dVersion");
            String ddrawrapper = currentDXWrapperConfig.get("ddrawrapper");
            Log.i("XServerDisplayActivity", "Launch DX wrapper files selected: dxvk='" +
                    dxvkWrapper + "' vkd3d='" + vkd3dWrapper + "' ddrawrapper='" +
                    ddrawrapper + "'");
            dxwrapper = dxvkWrapper + ";" + vkd3dWrapper + ";" + ddrawrapper;
        } else {
            String vkd3dVersion = currentDXWrapperConfig.get("vkd3dVersion");
            if (hasSelectedVkd3dVersion(vkd3dVersion)) {
                String vkd3dWrapper = "vkd3d-" + vkd3dVersion;
                Log.i("XServerDisplayActivity", "Launch VKD3D-only wrapper files selected: vkd3d='" +
                        vkd3dWrapper + "'");
                dxwrapper = dxwrapper + ";" + vkd3dWrapper;
            }
        }

        String wincomponents = shortcut != null ? getShortcutSetting("wincomponents", container.getWinComponents()) : container.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents")) || firstTimeBoot) {
            WinComponentSetup.applyWinComponents(
                    this,
                    imageFs,
                    wineInfo,
                    container,
                    wincomponents,
                    container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS),
                    firstTimeBoot,
                    onExtractFileListener);
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String wineArchKey = wineVersion != null && wineVersion.contains("arm64ec") ? "arm64ec" : "x86_64";
        String dxwrapperGateKey = dxwrapper + "|arch=" + wineArchKey;
        boolean forceWrapperApply = bootExePath != null && !bootExePath.isEmpty();
        if (!dxwrapperGateKey.equals(container.getExtra("dxwrapper")) || firstTimeBoot || forceWrapperApply) {
            Log.i("XServerDisplayActivity",
                    "DXVK/VKD3D extract: gate fired (key='" + dxwrapperGateKey
                            + "' prev='" + container.getExtra("dxwrapper")
                            + "' firstTimeBoot=" + firstTimeBoot + " forced=" + forceWrapperApply + ")");
            wipeDxwrapperDllsForReextract();
            extractDXWrapperFiles(dxwrapper);
            container.putExtra("dxwrapper", dxwrapperGateKey);
            containerDataChanged = true;
        }

        boolean isSteamGame = isSteamShortcut();
        boolean isCustomGame = isCustomShortcut();
        boolean coldClientSetup = isColdClientEnabledForShortcut();

        if (isSteamGame) {
            setSteamClientVisibility(true, coldClientSetup);
        } else if (isCustomGame) {
            setSteamClientVisibility(false);
        }

        boolean steamLauncherActive = com.winlator.cmod.feature.stores.steam.utils
                .PrefManager.INSTANCE.getWnPlanW();
        if (isSteamGame && !steamLauncherActive) {
            Log.d("XServerDisplayActivity", "Preparing Steam support");
            SteamBridge.ensureColdClientSupportReady(this);
            verifySteamClientFiles(true);
        }

        if (xServer == null || isFinishing() || isDestroyed()) {
            Log.w("XServerDisplayActivity",
                    "setupWineSystemFiles: activity torn down mid-setup (xServer="
                    + (xServer == null ? "null" : "ok")
                    + " finishing=" + isFinishing()
                    + " destroyed=" + isDestroyed()
                    + ") — aborting stale background setup, no crash");
            return;
        }

        String desktopTheme = shortcut != null ? getShortcutSetting("desktopTheme", container.getDesktopTheme()) : container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        stageBundledTestExes();
        WineUtils.createDosdevicesSymlinks(container, getActiveGameDirectoryPath(), isSteamShortcut());

        int inputType = container.getInputType();
        if (shortcut != null) {
            String shortcutInputType = shortcut.getExtra("inputType");
            if (!shortcutInputType.isEmpty()) {
                inputType = Byte.parseByte(shortcutInputType);
            }
        }
        boolean dinputEnabled = (inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) == WinHandler.FLAG_INPUT_TYPE_DINPUT;
        boolean exclusiveXInput = container.isExclusiveXInput();
        if (shortcut != null) {
            String extra = shortcut.getExtra("exclusiveXInput");
            if (!extra.isEmpty()) {
                exclusiveXInput = extra.equals("1");
            }
        }
        if (shortcut != null)
            startupSelection = getShortcutSetting("startupSelection", String.valueOf(container.getStartupSelection()));
        else
            startupSelection = String.valueOf(container.getStartupSelection());

        if (WineUtils.applyLaunchRegistryPolicy(
                container, startupSelection, dinputEnabled, exclusiveXInput, firstTimeBoot)) {
            containerDataChanged = true;
        }
        if (!startupSelection.equals(container.getExtra("startupSelection"))) {
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }
        if (containerDataChanged) {
            Log.d("XServerDisplayActivity", "Saving container data id=" + container.id +
                    " dxwrapperConfigField='" + container.getDXWrapperConfig() +
                    "' dxwrapperExtra='" + container.getExtra("dxwrapper") + "'");
            container.saveData();
        }
        Log.d("ContainerLaunch", "=== setupWineSystemFiles END === container=" + container.id + " firstTimeBoot=" + firstTimeBoot);
    }

    private void ensureLaunchRuntimeFilesReady() {
        if (container == null || wineInfo == null || imageFs == null || contentsManager == null) return;

        if (wineInfo.isArm64EC()) {
            ensureArm64EcRuntimeDllsReady();
        } else {
            ensureBox64RuntimeReady();
        }
    }

    private void ensureBox64RuntimeReady() {
        File rootDir = imageFs.getRootDir();
        boolean box64Missing = !new File(rootDir, "usr/bin/box64").exists();
        String box64Version = shortcut != null
                ? getShortcutSetting("box64Version", container.getBox64Version())
                : container.getBox64Version();
        if (box64Version == null || box64Version.isEmpty()) {
            box64Version = pickNewestInstalledContentVersion(ContentProfile.ContentType.CONTENT_TYPE_BOX64);
            if (!box64Version.isEmpty()) container.setBox64Version(box64Version);
        }

        if (!box64Missing && box64Version.equals(container.getExtra("box64Version"))) return;

        if (box64Version.isEmpty()) {
            Log.w("ContainerLaunch", "No Box64 version selected before first boot; runtime extraction skipped");
            return;
        }

        ContentProfile profile = resolveContentProfile(ContentProfile.ContentType.CONTENT_TYPE_BOX64, box64Version);
        if (profile == null) {
            Log.w("ContainerLaunch", "Box64 content profile not installed for version: " + box64Version);
            return;
        }

        Log.i("ContainerLaunch", "Preparing Box64 before Wine setup: version=" + box64Version);
        contentsManager.applyContent(profile);
        container.putExtra("box64Version", box64Version);
        container.saveData();
    }

    private void ensureArm64EcRuntimeDllsReady() {
        File system32Dir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows/system32");
        boolean fexcoreDllsMissing =
                !new File(system32Dir, "libwow64fex.dll").exists()
                        || !new File(system32Dir, "libarm64ecfex.dll").exists();
        boolean wowbox64DllMissing = !new File(system32Dir, "wowbox64.dll").exists();

        String emulator = shortcut != null
                ? getShortcutSetting("emulator", container.getEmulator())
                : container.getEmulator();
        String emulator64 = shortcut != null
                ? getShortcutSetting("emulator64", container.getEmulator64())
                : container.getEmulator64();
        String wowbox64Version = shortcut != null
                ? getShortcutSetting("box64Version", container.getBox64Version())
                : container.getBox64Version();
        String fexcoreVersion = shortcut != null
                ? getShortcutSetting("fexcoreVersion", container.getFEXCoreVersion())
                : container.getFEXCoreVersion();

        boolean usesWowbox64 = "wowbox64".equalsIgnoreCase(emulator);
        boolean usesFexcore =
                "fexcore".equalsIgnoreCase(emulator)
                        || "fexcore".equalsIgnoreCase(emulator64)
                        || !usesWowbox64;

        boolean changed = false;
        if (usesWowbox64 && (wowbox64DllMissing || !safeEquals(wowbox64Version, container.getExtra("box64Version")))) {
            if (wowbox64Version == null || wowbox64Version.isEmpty()) {
                wowbox64Version = pickNewestInstalledContentVersion(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64);
                if (!wowbox64Version.isEmpty()) container.setBox64Version(wowbox64Version);
            }
            changed |= applyRuntimeContentBeforeBoot(
                    ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64,
                    wowbox64Version,
                    "WowBox64",
                    "box64Version"
            );
        }

        if (usesFexcore && (fexcoreDllsMissing || !safeEquals(fexcoreVersion, container.getExtra("fexcoreVersion")))) {
            if (fexcoreVersion == null || fexcoreVersion.isEmpty()) {
                fexcoreVersion = pickNewestInstalledContentVersion(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE);
                if (!fexcoreVersion.isEmpty()) container.setFEXCoreVersion(fexcoreVersion);
            }
            changed |= applyRuntimeContentBeforeBoot(
                    ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                    fexcoreVersion,
                    "FEXCore",
                    "fexcoreVersion"
            );
        }

        if (changed) container.saveData();
    }

    private boolean applyRuntimeContentBeforeBoot(
            ContentProfile.ContentType type,
            String version,
            String label,
            String extraKey) {
        if (version == null || version.isEmpty()) {
            Log.w("ContainerLaunch", "No " + label + " version selected before first boot; runtime extraction skipped");
            return false;
        }

        ContentProfile profile = resolveContentProfile(type, version);
        if (profile == null) {
            Log.w("ContainerLaunch", label + " content profile not installed for version: " + version);
            return false;
        }

        Log.i("ContainerLaunch", "Preparing " + label + " before Wine setup: version=" + version);
        contentsManager.applyContent(profile);
        container.putExtra(extraKey, version);
        return true;
    }

    private ContentProfile resolveContentProfile(ContentProfile.ContentType type, String version) {
        ContentProfile profile = contentsManager.getProfileByEntryName(type.toString() + "-" + version);
        if (profile != null) return profile;

        List<ContentProfile> profiles = contentsManager.getProfiles(type);
        if (profiles == null) return null;
        for (ContentProfile candidate : profiles) {
            if (version.equals(contentVersionIdentifier(candidate))) return candidate;
        }
        return null;
    }

    private String pickNewestInstalledContentVersion(ContentProfile.ContentType type) {
        List<ContentProfile> profiles = contentsManager.getProfiles(type);
        if (profiles == null || profiles.isEmpty()) return "";

        ContentProfile best = null;
        for (ContentProfile profile : profiles) {
            if (!profile.isInstalled) continue;
            if (best == null
                    || profile.verCode > best.verCode
                    || (profile.verCode == best.verCode
                    && profile.verName != null
                    && best.verName != null
                    && profile.verName.compareToIgnoreCase(best.verName) > 0)) {
                best = profile;
            }
        }
        return best != null ? contentVersionIdentifier(best) : "";
    }

    private void setupXEnvironment() throws PackageManager.NameNotFoundException {
        // Never reattach for a dependency install: the boot exe must run in a fresh
        // environment, and a stale activeEnvironment from a just-closed session would
        // swallow the launch (black screen, bridge never completes).
        if (!isDependencyInstall && SessionKeepAliveService.isSessionActive()) {
            XEnvironment existingEnv = SessionKeepAliveService.getActiveEnvironment();
            XServer existingXServer = SessionKeepAliveService.getActiveXServer();
            if (existingEnv != null && existingXServer != null) {
                Log.i("XServerDisplayActivity", "Re-attaching to existing background session environment");
                this.environment = existingEnv;
                this.xServer = existingXServer;
                this.environment.setContext(this);
                this.reusingSession = true;

                // Free the old handler's socket (keep its writers/rings) and mark the fresh one initialized so the guest's input keeps flowing.
                WinHandler previousWinHandler = this.xServer.getWinHandler();
                if (previousWinHandler != null && previousWinHandler != winHandler) {
                    previousWinHandler.stopForReattach();
                }
                this.xServer.setWinHandler(winHandler);
                winHandler.markSessionInitialized();

                this.guestProgramLauncherComponent = environment.getComponent(GuestProgramLauncherComponent.class);
                return;
            }
        }

        cleanupLingeringSessionProcesses("new launch");

        // Pinned to C.UTF-8: the imagefs ships no locale data, so a real locale makes setlocale()
        // fall back to ASCII-only "C" and mangles non-ASCII paths on the wine command line.
        envVars.put("LC_ALL", LocaleEnv.normalize());
        envVars.put("LANG", LocaleEnv.normalizeLang(lc_all));
        String winePrefix = (shortcut != null && container != null && shortcut.path != null && shortcut.path.matches("^[cC]:.*")) ? new File(container.getRootDir(), ".wine").getAbsolutePath() : imageFs.wineprefix;
        envVars.put("WINEPREFIX", winePrefix);

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsConfig.DEFAULT_WINE_DEBUG_CHANNELS);
        String wineDebugClasses = preferences.getString("wine_debug_classes", SettingsConfig.DEFAULT_WINE_DEBUG_CLASSES);
        String wineDebugValue = enableWineDebug ? buildWineDebug(wineDebugClasses, wineDebugChannels) : "-all";
        envVars.put("WINEDEBUG", wineDebugValue);
        Log.i("XServerDisplayActivity",
                "WINEDEBUG resolved: enable=" + enableWineDebug
                        + " classes='" + wineDebugClasses + "' channels='" + wineDebugChannels
                        + "' value='" + wineDebugValue + "'");

        String rootPath = imageFs.getRootDir().getPath();
        FileUtils.clear(imageFs.getTmpDir());


        guestProgramLauncherComponent = new GuestProgramLauncherComponent(
                contentsManager,
                contentsManager.getProfileByEntryName(wineVersion),
                shortcut
        );

        if (container != null) {
                guestProgramLauncherComponent.setContainer(this.container);
                guestProgramLauncherComponent.setWineInfo(this.wineInfo);

                GameFixes.applyForLaunch(container, shortcut);

                // Must run before the guest program starts.
                ensureGameLocaleCodePageManifest();

                String wineStartCmd = getWineStartCommand(guestProgramLauncherComponent);
                String guestExecutable;
            
            // Launcher resolves Wine vs ARM64EC execution internally.
            guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " + wineStartCmd;

            Log.d("XServerDisplayActivity", "=== GAME LAUNCH DEBUG ===");
            Log.d("XServerDisplayActivity", "Wine start command: " + wineStartCmd);
            Log.d("XServerDisplayActivity", "Full guest executable: " + guestExecutable);
            Log.d("XServerDisplayActivity", "Wine info: " + wineInfo.identifier() + " arch=" + wineInfo.getArch());
            Log.d("XServerDisplayActivity", "Container drives: " + container.getDrives());
            if (shortcut != null) {
                Log.d("XServerDisplayActivity", "Shortcut path: " + shortcut.path);
                Log.d("XServerDisplayActivity", "Shortcut game_source: " + shortcut.getExtra("game_source"));
                Log.d("XServerDisplayActivity", "Shortcut app_id: " + shortcut.getExtra("app_id"));
            }

            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            String rawShortcutEnvVars = (shortcut != null && !shortcutUsesContainerDefaults())
                    ? shortcut.getExtra("envVars") : "";
            String effectiveCustomEnvVars = shortcut != null
                    ? getShortcutSetting("envVars", container.getEnvVars())
                    : container.getEnvVars();
            Log.d("XServerDisplayActivity", "Custom envVars source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutEnvVars + "' container='" + container.getEnvVars() +
                    "' effective='" + effectiveCustomEnvVars + "'");
            envVars.putAll(effectiveCustomEnvVars);

            // must run after custom env: a user-set ENABLE_VKBASALT wins and this backs off
            applyReshadeEnv(envVars);

            // Steam-style launch options: KEY=VALUE tokens before %command% become env vars.
            String launchOptsForEnv = shortcut != null
                    ? getShortcutSetting("execArgs", container.getExecArgs())
                    : container.getExecArgs();
            java.util.Map<String, String> steamOptEnv =
                    com.winlator.cmod.feature.stores.steam.utils.SteamLaunchOptions.parseEnvVars(launchOptsForEnv);
            for (java.util.Map.Entry<String, String> e : steamOptEnv.entrySet()) {
                envVars.put(e.getKey(), e.getValue());
            }

            normalizeSyncEnvVars(envVars);

            ArrayList<String> bindingPaths = new ArrayList<>();
            String drives = WineUtils.resolveEffectiveDrives(container);
            for (String[] drive : Container.drivesIterator(drives)) {
                bindingPaths.add(drive[1]);
            }

            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));

            String rawShortcutBox64Preset = (shortcut != null && !shortcutUsesContainerDefaults())
                    ? shortcut.getExtra("box64Preset") : "";
            String effectiveBox64Preset = shortcut != null
                    ? getShortcutSetting("box64Preset", container.getBox64Preset())
                    : container.getBox64Preset();
            Log.d("XServerDisplayActivity", "Box64 preset source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutBox64Preset + "' container='" + container.getBox64Preset() +
                    "' effective='" + effectiveBox64Preset + "'");
            guestProgramLauncherComponent.setBox64Preset(effectiveBox64Preset);

            String rawShortcutFEXCorePreset = (shortcut != null && !shortcutUsesContainerDefaults())
                    ? shortcut.getExtra("fexcorePreset") : "";
            String effectiveFEXCorePreset = shortcut != null
                    ? getShortcutSetting("fexcorePreset", container.getFEXCorePreset())
                    : container.getFEXCorePreset();
            Log.d("XServerDisplayActivity", "FEXCore preset source=shortcutOrContainer shortcutRaw='" +
                    rawShortcutFEXCorePreset + "' container='" + container.getFEXCorePreset() +
                    "' effective='" + effectiveFEXCorePreset + "'");
            guestProgramLauncherComponent.setFEXCorePreset(effectiveFEXCorePreset);

                // Steam preUnpack installs prerequisites before game launch.
                String prereqGameSource = shortcut != null ? shortcut.getExtra("game_source") : null;
                boolean isSteamGameForUnpack = "STEAM".equals(prereqGameSource);
                if (isSteamGameForUnpack) {
                    guestProgramLauncherComponent.setPreUnpack(() -> {
                        try {
                            if (isBionicSteamEnabledForShortcut()) {
                                return;
                            }

                            boolean currentUnpackFiles = shortcut != null
                                    ? parseBoolean(getShortcutSetting("unpackFiles", container.isUnpackFiles() ? "1" : "0"))
                                    : container.isUnpackFiles();
                            runPreGameSetup(
                                    guestProgramLauncherComponent,
                                    container.isNeedsUnpacking(),
                                    currentUnpackFiles);
                        } catch (Exception e) {
                            Log.e("XServerDisplayActivity", "preUnpack failed", e);
                        }
                    });
                } else if ("GOG".equals(prereqGameSource) || "EPIC".equals(prereqGameSource)) {
                    guestProgramLauncherComponent.setPreUnpack(() -> {
                        try {
                            installMonoIfNeeded(guestProgramLauncherComponent);
                            installGeckoIfNeeded(guestProgramLauncherComponent);
                        } catch (Exception e) {
                            Log.e("XServerDisplayActivity", "preUnpack failed", e);
                        }
                    });
                }
        }

        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars.clear();
        }

        environment = new XEnvironment(this, imageFs);
        environment.addComponent(
                new SysVSharedMemoryComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)
                )
        );
        environment.addComponent(
                new XServerComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)
                )
        );

        if (audioDriver.equals("alsa")) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");
            environment.addComponent(
                    new ALSAServerComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH),
                            ALSAClient.Options.fromEnvVars(envVars)
                    )
            );
        } else if (audioDriver.equals("pulseaudio")) {
            PulseAudioComponent.Options pulseOptions = PulseAudioComponent.Options.fromEnvVars(envVars);
            if (!envVars.has("PULSE_LATENCY_MSEC")) {
                envVars.put("PULSE_LATENCY_MSEC", pulseOptions.latencyMillis);
            }
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(
                    new PulseAudioComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH),
                            pulseOptions
                    )
            );
        } else if (DirectAudioDriver.INSTANCE.isSelected(audioDriver)) {
            // No daemon to start; the driver talks AAudio from Wine's unixlib.
            // wineInfo matches imageFs.winePath, so the ABI follows the layer being written.
            String daWineVersion =
                    wineInfo != null ? wineInfo.fullVersion() : container.getWineVersion();
            if (!DirectAudioDriver.INSTANCE.install(this, imageFs, daWineVersion)) {
                Log.w("XServerDisplayActivity", "DirectAudio install failed for wine '"
                        + daWineVersion + "'; audio may be silent");
            }

            boolean micRequested = DirectAudioDriver.INSTANCE.isMicEnabled(
                    getShortcutSetting(
                            DirectAudioDriver.EXTRA_MIC,
                            container.getExtra(DirectAudioDriver.EXTRA_MIC)));
            if (DirectAudioDriver.INSTANCE.shouldExposeMic(this, micRequested)) {
                envVars.put(DirectAudioDriver.ENV_MIC, "1");
            }
            Log.d("XServerDisplayActivity", "DirectAudio: micRequested=" + micRequested +
                    " micExposed=" + envVars.has(DirectAudioDriver.ENV_MIC));
        }

        // Wine cannot enumerate Android network interfaces; Steam treats that as offline.
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (shortcut != null && "STEAM".equals(shortcut.getExtra("game_source"))) {
            Log.d("XServerDisplayActivity", "Adding SteamClientComponent for Steam game");
            environment.addComponent(new SteamClientComponent());
        }

        if (isBionicSteamEnabledForShortcut()) {
            try {
                long bsSteamId = com.winlator.cmod.feature.stores.steam.utils
                        .PrefManager.INSTANCE.getSteamUserSteamId64();
                String bsUser = com.winlator.cmod.feature.stores.steam.utils
                        .PrefManager.INSTANCE.getUsername();
                int bsAppId = -1;
                try {
                    if (shortcut != null) bsAppId = Integer.parseInt(shortcut.getExtra("app_id"));
                } catch (Exception ignored) {}
                envVars.put("Steam3Master", "127.0.0.1:57343");
                envVars.put("SteamClientService", "127.0.0.1:57344");
                String bridgeLib = com.winlator.cmod.feature.stores.steam.wnsteam
                        .WnSteamAssetsInstaller.INSTANCE.bridgeLibPath(this).getAbsolutePath();
                envVars.put("WINESTEAMCLIENTPATH64", bridgeLib);
                envVars.put("WINESTEAMCLIENTPATH", bridgeLib);
                envVars.put("_STEAM_SETENV_MANAGER", "1");
                String steamRootLinux = imageFs.wineprefix
                        + "/drive_c/Program Files (x86)/Steam";
                envVars.put("STEAM_BASE_FOLDER", steamRootLinux);
                File bsBreakpad = new File(imageFs.getRootDir(), "usr/tmp/breakpad");
                bsBreakpad.mkdirs();
                envVars.put("BREAKPAD_DUMP_LOCATION", bsBreakpad.getAbsolutePath());
                envVars.put("STEAMVIDEOTOKEN", "1");
                envVars.put("ENABLE_VK_LAYER_VALVE_steam_overlay_1", "0");
                envVars.put("SteamEnv", "1");
                envVars.put("SteamClientLaunch", "1");
                if (bsUser != null && !bsUser.isEmpty()) {
                    envVars.put("SteamUser", bsUser);
                    envVars.put("SteamAppUser", bsUser);
                }
                if (bsSteamId > 0) envVars.put("STEAMID", String.valueOf(bsSteamId));
                if (bsAppId > 0) {
                    envVars.put("SteamAppId", String.valueOf(bsAppId));
                    envVars.put("SteamGameId", String.valueOf(bsAppId));
                }
                envVars.put("SteamPath", "C:\\Program Files (x86)\\Steam");
                envVars.put("ValvePlatformMutex", "c:\\Program Files (x86)\\Steam/");
                String currentWineDebug = envVars.get("WINEDEBUG");
                if (enableWineDebug
                        && (currentWineDebug == null || currentWineDebug.equals("-all"))) {
                    String steamClasses = preferences.getString(
                            "wine_debug_classes", SettingsConfig.DEFAULT_WINE_DEBUG_CLASSES);
                    envVars.put("WINEDEBUG", buildWineDebug(steamClasses, "module,loaddll"));
                }
                Log.i("XServerDisplayActivity",
                        "Bionic Steam: published bridge env (Steam3Master=127.0.0.1:57343, appId="
                                + bsAppId + ", steamBase=" + steamRootLinux
                                + ", WINEDEBUG=" + envVars.get("WINEDEBUG") + ")");

                try {
                    File steamDirPrefix = new File(container.getRootDir(),
                            ".wine/drive_c/Program Files (x86)/Steam");
                    if (steamDirPrefix.isDirectory()) {
                        File helperDst = new File(steamDirPrefix, "wn-steam-helper.exe");
                        if (!helperDst.exists() || helperDst.length() != 176128) {
                            try (java.io.InputStream in = getAssets().open(
                                        "wnsteam/bionic/wn-steam-helper.exe");
                                 java.io.FileOutputStream out =
                                        new java.io.FileOutputStream(helperDst)) {
                                byte[] buf = new byte[64 * 1024];
                                int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            }
                            Log.i("XServerDisplayActivity",
                                  "Bionic Steam: installed wn-steam-helper.exe ("
                                  + helperDst.length() + " B) at " + helperDst.getPath());
                        }
                    } else {
                        Log.w("XServerDisplayActivity",
                              "Bionic Steam: container Steam dir missing, helper not staged: "
                              + steamDirPrefix.getPath());
                    }
                } catch (Exception e) {
                    Log.e("XServerDisplayActivity",
                          "Bionic Steam: helper install failed", e);
                }

                if (com.winlator.cmod.feature.stores.steam.utils
                        .PrefManager.INSTANCE.getWnPlanW()) {
                    String currentOverrides = envVars.get("WINEDLLOVERRIDES");
                    String planWOverride = "lsteamclient=";
                    if (currentOverrides == null || currentOverrides.isEmpty()) {
                        envVars.put("WINEDLLOVERRIDES", planWOverride);
                    } else if (!currentOverrides.contains("lsteamclient=")) {
                        envVars.put("WINEDLLOVERRIDES",
                                currentOverrides + ";" + planWOverride);
                    }
                    Log.i("XServerDisplayActivity",
                            "Steam Launcher: WINEDLLOVERRIDES set to '"
                            + envVars.get("WINEDLLOVERRIDES")
                            + "' to disable lsteamclient export-hijack");
                    envVars.put("PROTON_DISABLE_LSTEAMCLIENT", "1");
                    Log.i("XServerDisplayActivity",
                            "Steam Launcher: PROTON_DISABLE_LSTEAMCLIENT=1 "
                            + "(bypass ntdll lsteamclient hooks for Proton 10+)");
                    String planWUser = com.winlator.cmod.feature.stores.steam.utils
                            .PrefManager.INSTANCE.getUsername();
                    String planWSid  = String.valueOf(com.winlator.cmod.feature.stores.steam.utils
                            .PrefManager.INSTANCE.getSteamUserSteamId64());
                    String planWTok = com.winlator.cmod.feature.stores.steam.utils
                            .PrefManager.INSTANCE.getRefreshToken();
                    if (planWTok != null && !planWTok.isEmpty()
                            && planWUser != null && !planWUser.isEmpty()
                            && !planWSid.equals("0")
                            && bsAppId > 0) {
                        envVars.put("WN_STEAM_USERNAME", planWUser);
                        envVars.put("WN_STEAM_STEAMID", planWSid);
                        envVars.put("WN_STEAM_TOKEN", planWTok);
                        envVars.put("WN_STEAM_APPID", String.valueOf(bsAppId));
                        // Pass language for native launcher ACF UserConfig/MountedConfig
                        String acfLang = PrefManager.INSTANCE.getContainerLanguage();
                        String acfContainerLang = container.getExtra("containerLanguage", null);
                        if (acfContainerLang != null && !acfContainerLang.isEmpty()) {
                            acfLang = acfContainerLang;
                        }
                        if (acfLang != null && !acfLang.isEmpty()) {
                            envVars.put("WN_STEAM_LANGUAGE", acfLang);
                        }
                        // Pass DLC depot data for native launcher ACF
                        try {
                            com.winlator.cmod.feature.stores.steam.data.SteamApp depotAppInfo =
                                com.winlator.cmod.feature.stores.steam.service.SteamService.Companion
                                    .getAppInfoOf(bsAppId);
                            if (depotAppInfo != null) {
                                envVars.put("WN_STEAM_APP_NAME", depotAppInfo.getName());
                                String installScript = depotAppInfo.getInstallScript();
                                if (installScript != null && !installScript.isEmpty()) {
                                    StringBuilder installScriptsSb = new StringBuilder();
                                    java.util.Map<Integer, com.winlator.cmod.feature.stores.steam.data.DepotInfo> allDepots =
                                        depotAppInfo.getDepots();
                                    for (java.util.Map.Entry<Integer, com.winlator.cmod.feature.stores.steam.data.DepotInfo> entry : allDepots.entrySet()) {
                                        com.winlator.cmod.feature.stores.steam.data.DepotInfo di = entry.getValue();
                                        if (di.getDlcAppId() == com.winlator.cmod.feature.stores.steam.service.SteamService.INVALID_APP_ID
                                            && di.getDepotFromApp() == com.winlator.cmod.feature.stores.steam.service.SteamService.INVALID_APP_ID) {
                                            if (installScriptsSb.length() > 0) installScriptsSb.append(",");
                                            installScriptsSb.append(entry.getKey()).append(":").append(installScript);
                                        }
                                    }
                                    if (installScriptsSb.length() > 0) {
                                        envVars.put("WN_STEAM_INSTALL_SCRIPTS", installScriptsSb.toString());
                                    }
                                }
                                java.util.Map<Integer, com.winlator.cmod.feature.stores.steam.data.DepotInfo> depots =
                                    depotAppInfo.getDepots();
                                String branch = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion
                                    .resolveSelectedBetaName(bsAppId);
                                if (branch == null || branch.isEmpty()) branch = "public";

                                java.util.Map<String, com.winlator.cmod.feature.stores.steam.data.BranchInfo> branches =
                                    depotAppInfo.getBranches();
                                if (branches == null) branches = java.util.Collections.emptyMap();
                                long recordedBuildId = com.winlator.cmod.feature.stores.steam.utils
                                        .SteamUtils.installedBuildId(bsAppId);
                                long latestBuildId = com.winlator.cmod.feature.stores.steam.service
                                        .SteamBranchSelection.INSTANCE.buildIdForBranch(branches, branch);
                                long buildId = com.winlator.cmod.feature.stores.steam.service
                                        .SteamBranchSelection.INSTANCE.installedBuildId(
                                                recordedBuildId, branches, branch);
                                if (buildId != latestBuildId) {
                                    Log.i("XServerDisplayActivity",
                                            "Steam Launcher: appId=" + bsAppId + " branch='" + branch
                                            + "' installed buildId=" + buildId
                                            + " differs from the published " + latestBuildId
                                            + " — reporting the installed build to Steam");
                                }
                                envVars.put("WN_STEAM_BUILD_ID", String.valueOf(buildId));
                                java.util.Map<Integer, Long> onDiskManifestIds =
                                        com.winlator.cmod.feature.stores.steam.utils.SteamUtils
                                                .installedDepotManifestIds(bsAppId);

                                String gameInstallPath = resolveSteamGameInstallPath(bsAppId);
                                long sizeOnDisk = com.winlator.cmod.feature.stores.steam.utils
                                        .SteamUtils.installSizeOnDisk(this, bsAppId,
                                                gameInstallPath != null ? new java.io.File(gameInstallPath) : null);
                                envVars.put("WN_STEAM_SIZE_ON_DISK", String.valueOf(sizeOnDisk));

                                // Collect installed depot IDs and DLC app IDs (same as Kotlin collectInstalledDepotManifests)
                                java.util.Set<Integer> installedDepotIds = new java.util.HashSet<>();
                                java.util.List<Integer> installedDepotsList = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion
                                    .getInstalledDepotsOf(bsAppId);
                                if (installedDepotsList != null) installedDepotIds.addAll(installedDepotsList);

                                java.util.Set<Integer> installedDlcAppIds = new java.util.HashSet<>();
                                java.util.List<Integer> installedDlcList = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion
                                    .getInstalledDlcDepotsOf(bsAppId);
                                if (installedDlcList != null) installedDlcAppIds.addAll(installedDlcList);

                                // Collect all known depots (app depots + downloadable depots)
                                java.util.LinkedHashMap<Integer, com.winlator.cmod.feature.stores.steam.data.DepotInfo> allKnownDepots = new java.util.LinkedHashMap<>();
                                allKnownDepots.putAll(depots);
                                java.util.Map<Integer, com.winlator.cmod.feature.stores.steam.data.DepotInfo> downloadableDepots = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion
                                    .getDownloadableDepots(bsAppId, acfLang != null ? acfLang : "");
                                if (downloadableDepots != null) allKnownDepots.putAll(downloadableDepots);

                                // Also add DLC depots from getOwnedAppDlc (matches Kotlin collectInstalledDepotManifests)
                                if (installedDlcList != null) {
                                    for (Integer dlcAppId : installedDlcList) {
                                        try {
                                            @SuppressWarnings("unchecked")
                                            java.util.Map<Integer, com.winlator.cmod.feature.stores.steam.data.DepotInfo> ownedDlc =
                                                (java.util.Map<Integer, com.winlator.cmod.feature.stores.steam.data.DepotInfo>)
                                                    kotlinx.coroutines.BuildersKt.runBlocking(
                                                        kotlinx.coroutines.Dispatchers.getIO(),
                                                        (scope, continuation) -> com.winlator.cmod.feature.stores.steam.service.SteamService.Companion
                                                            .getOwnedAppDlc(dlcAppId, continuation)
                                                    );
                                            if (ownedDlc != null) allKnownDepots.putAll(ownedDlc);
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                        } catch (Exception ignored) {}
                                    }
                                }

                                StringBuilder depotSb = new StringBuilder();
                                StringBuilder sharedSb = new StringBuilder();
                                long totalBytesToDownload = 0L;
                                long totalBytesToStage = 0L;
                                for (java.util.Map.Entry<Integer, com.winlator.cmod.feature.stores.steam.data.DepotInfo> entry : allKnownDepots.entrySet()) {
                                    int depotId = entry.getKey();
                                    com.winlator.cmod.feature.stores.steam.data.DepotInfo di = entry.getValue();

                                    // Shared depots are excluded from InstalledDepots entirely (match Kotlin
                                    // collectInstalledDepotManifests); emit to SharedDepots only when the source app is known.
                                    if (di.getSharedInstall()) {
                                        if (di.getDepotFromApp() != com.winlator.cmod.feature.stores.steam.service.SteamService.INVALID_APP_ID) {
                                            if (sharedSb.length() > 0) sharedSb.append(",");
                                            sharedSb.append(depotId).append(":").append(di.getDepotFromApp());
                                        }
                                        continue;
                                    }

                                    // Match Kotlin collectInstalledDepotManifests: include if depot is installed,
                                    // or its DLC is installed
                                    boolean shouldInclude = installedDepotIds.contains(depotId)
                                        || (di.getDlcAppId() != com.winlator.cmod.feature.stores.steam.service.SteamService.INVALID_APP_ID
                                            && installedDlcAppIds.contains(di.getDlcAppId()));
                                    if (!shouldInclude) continue;

                                    com.winlator.cmod.feature.stores.steam.data.ManifestInfo manifest = null;
                                    java.util.Map<String, com.winlator.cmod.feature.stores.steam.data.ManifestInfo> manifests = di.getManifests();
                                    if (manifests.containsKey(branch)) manifest = manifests.get(branch);
                                    else if (!branch.equals("public") && manifests.containsKey("public")) manifest = manifests.get("public");

                                    if (manifest != null && manifest.getGid() == 0L) manifest = null;
                                    com.winlator.cmod.feature.stores.steam.data.ManifestInfo installedManifest =
                                        com.winlator.cmod.feature.stores.steam.service.SteamBranchSelection.INSTANCE
                                            .installedManifest(manifest, onDiskManifestIds.get(depotId), branch);
                                    if (installedManifest != null) {
                                        if (depotSb.length() > 0) depotSb.append(",");
                                        depotSb.append(depotId).append(":")
                                               .append(installedManifest.getGid()).append(":")
                                               .append(installedManifest.getSize());
                                        int dlcAppId = di.getDlcAppId();
                                        if (dlcAppId != com.winlator.cmod.feature.stores.steam.service.SteamService.INVALID_APP_ID) {
                                            depotSb.append(":").append(dlcAppId);
                                        } else if (installedDlcAppIds.contains(depotId)) {
                                            // Mirror Kotlin createAppManifest: fall back to depotId as the dlcappid
                                            // when the depot carries no dlcAppId but is itself a tracked DLC.
                                            depotSb.append(":").append(depotId);
                                        }
                                        if (manifest != null) {
                                            totalBytesToDownload += manifest.getDownload();
                                            totalBytesToStage += manifest.getSize();
                                        }
                                    }
                                }
                                if (depotSb.length() > 0) {
                                    envVars.put("WN_STEAM_DEPOTS", depotSb.toString());
                                }
                                if (sharedSb.length() > 0) {
                                    envVars.put("WN_STEAM_SHARED_DEPOTS", sharedSb.toString());
                                }
                                envVars.put("WN_STEAM_BYTES_TO_DOWNLOAD", String.valueOf(totalBytesToDownload));
                                envVars.put("WN_STEAM_BYTES_TO_STAGE", String.valueOf(totalBytesToStage));
                                Log.i("XServerDisplayActivity",
                                    "Steam Launcher: depots=" + depotSb + " shared=" + sharedSb
                                    + " buildId=" + buildId + " sizeOnDisk=" + sizeOnDisk
                                    + " dlBytes=" + totalBytesToDownload + " stageBytes=" + totalBytesToStage);
                            }
                        } catch (Exception depotIgnored) {
                            Log.w("XServerDisplayActivity",
                                    "Steam Launcher: Could not query depot data", depotIgnored);
                        }
                        if (!isCloudSyncEnabledForShortcut() || isOfflineModeForShortcut()) {
                            envVars.put("WN_STEAM_AGENT_CLOUD", "0");
                            Log.i("XServerDisplayActivity",
                                    "Steam Launcher: WN_STEAM_AGENT_CLOUD=0 — cloud saves are "
                                    + "turned off for this shortcut, so the agent skips "
                                    + "RunAutoCloudOnAppLaunch and RunAutoCloudOnAppExit");
                        }
                        if (wnSteamLaunchOption >= 0) {
                            envVars.put("WN_STEAM_LAUNCH_OPTION", String.valueOf(wnSteamLaunchOption));
                        }
                        if (wnSteamUserArgs != null && !wnSteamUserArgs.isEmpty()) {
                            envVars.put("WN_STEAM_USER_ARGS", wnSteamUserArgs);
                            Log.i("XServerDisplayActivity",
                                    "Steam Launcher: passing user launch options to LaunchApp: "
                                    + wnSteamUserArgs);
                        }
                        if (wnSteamDirectExeOverride) {
                            envVars.put("WN_STEAM_DIRECT_EXE", "1");
                            Log.i("XServerDisplayActivity",
                                    "Steam Launcher: WN_STEAM_DIRECT_EXE=1 — user-overridden "
                                    + "launch exe; launcher will CreateProcess the selected exe "
                                    + "directly (Steam LaunchApp skipped)");
                        }
                        File planWCa = new File(container.getRootDir(),
                                ".wine/drive_c/Program Files (x86)/Steam/wnsteam_cacert.pem");
                        if (planWCa.exists() && planWCa.length() > 0) {
                            envVars.put("STEAM_SSL_CERT_FILE",
                                    "C:\\Program Files (x86)\\Steam\\wnsteam_cacert.pem");
                            Log.i("XServerDisplayActivity",
                                    "Steam Launcher: STEAM_SSL_CERT_FILE -> staged CA bundle ("
                                    + planWCa.length() + " bytes)");
                        } else {
                            Log.w("XServerDisplayActivity",
                                    "Steam Launcher: CA bundle not staged at " + planWCa.getPath()
                                    + " — launcher CM logon may fail TLS verification");
                        }
                        Log.i("XServerDisplayActivity",
                                "Steam Launcher: token+identity published (user=" + planWUser
                                + " sid=" + planWSid
                                + " appId=" + bsAppId
                                + " tokenLen=" + planWTok.length() + ")");
                    } else {
                        Log.w("XServerDisplayActivity",
                                "Steam Launcher: refresh token / user / steamId missing "
                                + "(user='" + planWUser + "' sidIsZero="
                                + planWSid.equals("0") + " tokenEmpty="
                                + (planWTok == null || planWTok.isEmpty())
                                + " bsAppId=" + bsAppId
                                + ") — launcher will refuse to start; "
                                + "sign into Steam first");
                    }
                }
            } catch (Exception e) {
                Log.e("XServerDisplayActivity", "Bionic Steam: failed to publish bridge env", e);
            }
        }

        if (com.winlator.cmod.feature.stores.steam.utils.PrefManager.INSTANCE.getWnPlanW()
                && shortcut != null && "STEAM".equals(shortcut.getExtra("game_source"))) {
            try {
                java.io.File launcherLog = new java.io.File(
                        container.getRootDir(), ".wine/drive_c/wn-launcher.log");
                String gameName = shortcut != null && !shortcut.name.isEmpty()
                        ? shortcut.name : "game";
                resetWnLauncherLog(launcherLog);
                stopWnLauncherStatusTailer();
                wnLauncherStatusTailer = new com.winlator.cmod.feature.stores.steam.wnsteam
                        .WnLauncherStatusTailer(
                            this,
                            launcherLog,
                            gameName,
                            100L,
                            (phaseText) -> {
                                if (preloaderDialog != null) {
                                    preloaderDialog.setStepOnUiThread(phaseText);
                                }
                                return kotlin.Unit.INSTANCE;
                            },
                            () -> {
                                stopWnLauncherStatusTailer();
                                if (preloaderDialog != null) {
                                    preloaderDialog.closeOnUiThread();
                                }
                                return kotlin.Unit.INSTANCE;
                            },
                            (reason) -> {
                                // Invalidate the broken Steam-dir staging so the next launch re-runs installPlanW* through a fresh symlink, restoring shared-store DLL visibility.
                                try {
                                    File brokenStage = new File(container.getRootDir(),
                                            ".wine/drive_c/Program Files (x86)/Steam/.wn-planw-stage.stamp");
                                    if (brokenStage.exists()) brokenStage.delete();
                                    File brokenLauncher = new File(container.getRootDir(),
                                            ".wine/drive_c/Program Files (x86)/Steam/.wn-planw-launcher.stamp");
                                    if (brokenLauncher.exists()) brokenLauncher.delete();
                                } catch (Exception ignored) {}
                                try {
                                    com.winlator.cmod.feature.stores.steam.service.SteamService
                                            .Companion.bionicHandoffRelease();
                                } catch (Throwable t) {
                                    Log.w("XServerDisplayActivity",
                                            "Steam Launcher: Bionic hand-off release failed after launch failure", t);
                                }
                                runOnUiThread(() -> {
                                    stopWnLauncherStatusTailer();
                                    WinToast.show(this, reason);
                                    if (preloaderDialog != null) preloaderDialog.closeWithDelay(0L);
                                    exit();
                                });
                                return kotlin.Unit.INSTANCE;
                            },
                            (kind, blockingAppId, pid) -> {
                                showSteamBlockedDialog(container, gameName, kind, blockingAppId);
                                return kotlin.Unit.INSTANCE;
                            },
                            () -> {
                                Log.w("XServerDisplayActivity",
                                        "Steam Launcher: game started via CreateProcess fallback — "
                                                + "session is NOT VAC-secure");
                                runOnUiThread(() -> WinToast.show(this,
                                        getString(R.string.steam_launch_insecure_warning, gameName)));
                                return kotlin.Unit.INSTANCE;
                            },
                            (conflictAppId, localTime, remoteTime) -> {
                                showSteamCloudConflictDialog(container, conflictAppId,
                                        localTime, remoteTime);
                                return kotlin.Unit.INSTANCE;
                            });
                wnLauncherStatusTailer.start();
                wnLauncherDrivesDismiss.set(true);
                Log.i("XServerDisplayActivity",
                        "Steam Launcher: status tailer attached to " + launcherLog.getPath());
            } catch (Exception e) {
                stopWnLauncherStatusTailer();
                Log.w("XServerDisplayActivity",
                        "Steam Launcher: failed to start status tailer: " + e.getMessage());
            }
        }

        NetworkingSettings.applyEnv(envVars, this,
                getShortcutSetting(NetworkingSettings.EXTRA_DRIVER,
                        container.getExtra(NetworkingSettings.EXTRA_DRIVER, NetworkingSettings.DEFAULT_DRIVER)),
                getShortcutSetting(NetworkingSettings.EXTRA_MAC, container.getExtra(NetworkingSettings.EXTRA_MAC, "")));
        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            Log.d("XServerDisplayActivity", "Guest process terminated with status: " + status);
            stopWnLauncherStatusTailer();

            if (isDependencyInstall) {
                // Signal completion only after the session window is fully torn down (onDestroy); releasing early would let the next queued install launch into this still-alive activity.
                dependencyExitStatus = status;
                exit();
                return;
            }


            boolean planWActiveTerm = com.winlator.cmod.feature.stores.steam.utils
                    .PrefManager.INSTANCE.getWnPlanW();
            if (isBionicSteamEnabledForShortcut() && planWActiveTerm) {
                try {
                    com.winlator.cmod.feature.stores.steam.service.SteamService
                            .Companion.bionicHandoffReleaseAndKickPlayingSessionAsync(true);
                    Log.d("XServerDisplayActivity",
                            "Steam Launcher: game exited — released Bionic hand-off "
                                    + "and scheduled kickPlayingSessionIfReady");
                } catch (Throwable t) {
                    Log.w("XServerDisplayActivity",
                            "Steam Launcher: Bionic hand-off release/kick failed", t);
                }
            } else if (isBionicSteamEnabledForShortcut()
                    && !com.winlator.cmod.feature.stores.steam.utils
                            .PrefManager.INSTANCE.getWnHybridMode()) {
                try {
                    com.winlator.cmod.feature.stores.steam.service.SteamService
                            .Companion.bionicHandoffRelease();
                } catch (Throwable t) {
                    Log.w("XServerDisplayActivity", "Bionic hand-off release failed", t);
                }
            } else if (isBionicSteamEnabledForShortcut()) {
                Log.d("XServerDisplayActivity",
                        "Hybrid mode: keeping bootstrap alive past game end "
                                + "(release skipped — bootstrap is the SOLE "
                                + "Steam session for this app run)");
            }

            if (shouldWatchSteamTermination(status)) {
                return;
            }

            exit();
        });

        environment.addComponent(guestProgramLauncherComponent);

        FEXCoreManager.ensureAppConfigOverrides(this);

        winHandler.preAssignConnectedControllers();

        if (!reusingSession) {
            if (preloaderDialog != null) {
                preloaderDialog.setStepOnUiThread(R.string.preloader_starting_wine);
            }
            environment.startEnvironmentComponents();
            if (!isDependencyInstall) {
                SessionKeepAliveService.setActiveEnvironment(environment);
                SessionKeepAliveService.setActiveXServer(xServer);
            }
        }

        winHandler.start();
        if (wineRequestHandler != null) wineRequestHandler.start();

        dxwrapperConfig = null;
        
    }

    private void createWrapperScript(String path, String content) {
        File scriptFile = new File(path);
        FileUtils.writeString(scriptFile, content);
        scriptFile.setExecutable(true);
    }

    private void setupUI() {
        FrameLayout rootView = xServerDisplayFrame;
        xServerView = new XServerSurfaceView(this, xServer);
        final VulkanRenderer renderer = xServerView.getRenderer();
        // Match guest libvulkan so imported AHB tiling matches the producer.
        String compositorGraphicsDriver =
                graphicsDriverConfig != null ? graphicsDriverConfig.get("version") : null;
        if (compositorGraphicsDriver == null || compositorGraphicsDriver.isEmpty()) {
            compositorGraphicsDriver = "System";
        }
        Log.i("XServerDisplayActivity", "Compositor graphics driver='"
                + compositorGraphicsDriver + "' from graphicsDriver='" + graphicsDriver + "'");
        renderer.setGraphicsDriver(compositorGraphicsDriver);
        renderer.setCursorVisible(false);
        renderer.setNativeMode(isNativeRenderingEnabled);
        renderer.setPresentMode(VulkanRenderer.parsePresentMode(
                graphicsDriverConfig != null ? graphicsDriverConfig.get("compositorPresentMode") : null));

        String containerSwapRB = container != null ? container.getExtra("swapRB", "0") : "0";
        renderer.setSwapRB("1".equals(getShortcutSetting("swapRB", containerSwapRB)));

        applyFrameGenerationSettings(renderer, container);

        if (shortcut != null || (bootExePath != null && !bootExePath.isEmpty())) {
            renderer.setUnviewableWMClasses("explorer.exe");
        }
        if (shortcut != null) {
            String savedFpsLimit = shortcut.getExtra("fpsLimit", "0");
            try {
                runtimeFpsLimit = Integer.parseInt(savedFpsLimit);
            } catch (NumberFormatException e) {
                runtimeFpsLimit = 0;
            }
        }
        renderer.setFpsLimit(runtimeFpsLimit);

        applyScreenEffects();
        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        touchpadView = new TouchpadView(this, xServer, timeoutHandler, hideControlsRunnable);
        touchpadView.setTapToClickEnabled(isTapToClickEnabled);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMouseEnabled(!isMouseDisabled);
        touchpadView.setFourFingersTapCallback(() -> {
            if (drawerStateHolder == null || !drawerStateHolder.isDrawerOpen()) {
                openDrawerMenu();
            }
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this, timeoutHandler, hideControlsRunnable);
        inputControlsView.setInputControlsManager(inputControlsManager);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setReverseBindingOrder(preferences.getBoolean("reverse_binding_order", false));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        applyTouchscreenOverlayPreference();
        applyInputVisualStylePreferences();
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);


        effectiveShowFPS = preferences.getBoolean("fps_monitor_enabled", false);
        // Always create FrameRating so it feeds the phone gauge HUD; its on-screen overlay shows only when the FPS monitor is enabled.
        frameRating = new FrameRating(this, graphicsDriverConfig);
        frameRating.setRenderer(lastRendererName);
        if (lastGpuName != null) frameRating.setGpuName(lastGpuName);
        frameRating.setVisibility(effectiveShowFPS ? View.VISIBLE : View.GONE);
        applyHUDSettings();
        syncFrameGenerationHud();
        updateHUDRenderMode();
        rootView.addView(frameRating);
        if (perfController != null) perfController.attachToFrameRating(frameRating);

        if (preferences.getBoolean(MangoHudView.PREF_ENABLED, false)) {
            mangoHud = new MangoHudView(this);
            mangoHud.setEngineName(mangoEngineLabel());
            mangoHud.setSessionInfo(
                    xServer != null ? xServer.screenInfo.width + "x" + xServer.screenInfo.height : null,
                    wineInfo != null ? String.valueOf(wineInfo) : null);
            // Deferred: adding while the frame is detached puts this view inside the frame's later
            // attach walk, which FrameRating's onAttachedToWindow bringToFront() reorders mid-walk —
            // the child that slides into the already-visited slot never gets attached. post() runs
            // after that walk completes, so the add always lands on an attached parent.
            rootView.post(() -> {
                if (mangoHud != null && mangoHud.getParent() == null) rootView.addView(mangoHud);
            });
            syncFrameGenerationHud();
        }

        setupControllerHudDetection();

        startFullscreenStretched = "1".equals(getShortcutSetting("fullscreenStretched",
                container != null && container.isFullscreenStretched() ? "1" : "0"));

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }

            String simTouchScreen = shortcut.getExtra("simTouchScreen");
            screenTouchMode = Integer.parseInt(shortcut.getExtra("screenTouchMode", simTouchScreen.equals("1") ? "1" : "0"));
            touchpadView.setScreenTouchMode(screenTouchMode);
            if (winHandler != null) winHandler.setScreenTouchStickActive(screenTouchMode == 2);
            rtsGesturesEnabled = shortcut.getExtra("rtsGestures", "0").equals("1");
            touchpadView.setRtsGesturesEnabled(rtsGesturesEnabled);
        }

        if (rtsGesturesEnabled) pushSelectedGestureConfig();

        if (winHandler != null) winHandler.setRightStickSensitivity(preferences.getFloat("right_stick_sensitivity", 1.0f));

        startTouchscreenTimeout();

        // Detect a connected external display and offer to move the game onto it (controls stay here).
        externalDisplayController = new ExternalDisplayController(
                this, xServerDisplayFrame, xServerView,
                new ExternalDisplayController.Callbacks() {
                    @Override
                    public void onExternalDisplayConnected(android.view.Display display) {
                        // Automatic swap: the game shows only on the external display, controls stay on the phone.
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed() || externalDisplayController == null) return;
                            boolean outputEnabled = preferences != null
                                    && preferences.getBoolean("external_display_output", false);
                            boolean swap = !externalDisplayController.isSwapActive()
                                    && (externalDisplayController.isVitureSinkAvailable() || outputEnabled);
                            if (swap) {
                                externalDisplayController.enterSwap();
                                android.widget.Toast.makeText(XServerDisplayActivity.this,
                                        R.string.display_output_swapped_toast,
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                            // Re-render even when not swapping so an open Output pane shows the toggle.
                            renderDrawerMenu();
                        });
                    }

                    @Override
                    public void onExternalDisplayDisconnected() {
                        runOnUiThread(() -> {
                            android.widget.Toast.makeText(XServerDisplayActivity.this,
                                    R.string.display_output_restored_toast,
                                    android.widget.Toast.LENGTH_SHORT).show();
                            renderDrawerMenu();
                            AppUtils.hideSystemUI(XServerDisplayActivity.this);
                        });
                    }

                    @Override
                    public void onSwapStateChanged(boolean swapActive) {
                        runOnUiThread(() -> {
                            // On return-to-phone, re-measure the display frame to reclaim full size.
                            if (!swapActive && drawerStateHolder != null) {
                                drawerStateHolder.requestPhoneRelayout();
                            }
                            evaluateControllerHudMode();
                            renderDrawerMenu();
                        });
                    }
                });
        externalDisplayController.start();

        AppUtils.observeSoftKeyboardVisibility(displayHostComposeView, renderer::setScreenOffsetYRelativeToCursor);
    }

    // Open the system Cast / wireless-display picker; a connected display flows through the swap path.
    private void launchWirelessDisplayPicker() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_CAST_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS));
            } catch (Exception ignore) {
                android.widget.Toast.makeText(this, R.string.display_output_cast_unavailable,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }



    private ActivityResultLauncher<Intent> controlsEditorActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (editInputControlsCallback != null) {
                    editInputControlsCallback.run();
                    editInputControlsCallback = null;
                }
            }
    );

    private String parseShortcutNameFromDesktopFile(File desktopFile) {
        String shortcutName = "";
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Name=")) {
                        shortcutName = line.split("=")[1].trim();
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e("XServerDisplayActivity", "Error reading shortcut name from .desktop file", e);
            }
        }
        return shortcutName;
    }

    private void setTextColorForDialog(ViewGroup viewGroup, int color) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                setTextColorForDialog((ViewGroup) child, color);
            } else if (child instanceof TextView) {
                ((TextView) child).setTextColor(color);
            }
        }
    }

    private void ensureWinePrefixReady() {
        if (container == null || wineInfo == null) return;

        File containerDir = container.getRootDir();
        boolean prefixInvalid = !WineUtils.isPrefixValid(containerDir);
        String storedPrefixArch = container.getExtra("wineprefixArch");
        boolean archMismatch = !storedPrefixArch.isEmpty() && !storedPrefixArch.equalsIgnoreCase(wineInfo.getArch());
        boolean prefixNeedsUpdate = "t".equalsIgnoreCase(container.getExtra("wineprefixNeedsUpdate"));
        Log.d("ContainerLaunch", "ensureWinePrefixReady: prefixInvalid=" + prefixInvalid +
                " archMismatch=" + archMismatch + " storedArch=" + storedPrefixArch +
                " targetArch=" + wineInfo.getArch() + " needsUpdate=" + prefixNeedsUpdate);

        if (!prefixInvalid && !archMismatch && !prefixNeedsUpdate) {
            if (storedPrefixArch.isEmpty()) {
                container.putExtra("wineprefixArch", wineInfo.getArch());
                container.putExtra("wineprefixNeedsUpdate", null);
                container.saveData();
            }
            return;
        }

        Log.w("XServerDisplayActivity", "Repairing Wine prefix for container " + container.id +
                " invalid=" + prefixInvalid +
                " archMismatch=" + archMismatch +
                " storedArch=" + storedPrefixArch +
                " targetArch=" + wineInfo.getArch() +
                " needsUpdate=" + prefixNeedsUpdate);

        boolean repaired = containerManager.repairContainerWinePrefix(container, wineVersion, contentsManager, onExtractFileListener);
        if (repaired) {
            firstTimeBoot = true;
            Log.i("XServerDisplayActivity", "Wine prefix repaired successfully for container " + container.id);
        } else {
            Log.e("XServerDisplayActivity", "Wine prefix repair failed for container " + container.id);
        }
    }

    private void ensureWinePrefixEssentialFiles() {
        if (container == null) return;
        File containerWindowsDir = new File(container.getRootDir(), ".wine/drive_c/windows");
        String[] essentialFiles = {"winhandler.exe", "wfm.exe"};

        StringBuilder status = new StringBuilder("ensureWinePrefixEssentialFiles:");
        boolean anyMissing = false;
        for (String filename : essentialFiles) {
            boolean exists = new File(containerWindowsDir, filename).exists();
            status.append(" ").append(filename).append("=").append(exists);
            if (!exists) anyMissing = true;
        }
        Log.d("ContainerLaunch", status.toString());

        if (anyMissing) {
            File homeDir = new File(imageFs.getRootDir(), "home");
            File[] homeDirs = homeDir.listFiles();
            File sourceWindowsDir = null;
            if (homeDirs != null) {
                Log.d("ContainerLaunch", "Searching " + homeDirs.length + " dirs in home/ for essential files");
                for (File dir : homeDirs) {
                    if (!dir.isDirectory()) continue;
                    if (dir.getName().equals(ImageFs.USER)) continue;
                    if (dir.getAbsolutePath().equals(container.getRootDir().getAbsolutePath())) continue;
                    File candidate = new File(dir, ".wine/drive_c/windows");
                    if (new File(candidate, "winhandler.exe").exists()) {
                        sourceWindowsDir = candidate;
                        Log.d("ContainerLaunch", "Found essential files source: " + dir.getName());
                        break;
                    }
                }
            }

            if (sourceWindowsDir != null) {
                for (String filename : essentialFiles) {
                    File dest = new File(containerWindowsDir, filename);
                    if (!dest.exists()) {
                        File source = new File(sourceWindowsDir, filename);
                        if (source.exists()) {
                            Log.d("ContainerLaunch", "Copying " + filename + " from " + sourceWindowsDir.getParent());
                            FileUtils.copy(source, dest);
                        }
                    }
                }
            } else {
                Log.w("ContainerLaunch", "No source container found, extracting from container_pattern_common.tzst");
                containerWindowsDir.mkdirs();
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                        "container_pattern_common.tzst", imageFs.getRootDir(), onExtractFileListener);
                for (String filename : essentialFiles) {
                    Log.d("ContainerLaunch", filename + " exists after extraction: " + new File(containerWindowsDir, filename).exists());
                }
            }
        }

        ensureUtf8CodePageManifests(containerWindowsDir, essentialFiles);
    }

    /**
     * winhandler.exe/wfm.exe are ANSI binaries (GetCommandLineA argv, ShellExecuteA launch): under a
     * legacy codepage every non-ASCII path character becomes '?' and ShellExecuteA fails with
     * ERROR_INVALID_NAME. The UTF-8 activeCodePage manifest makes Wine use UTF-8 for their A-variant APIs.
     */
    private void ensureUtf8CodePageManifests(File windowsDir, String[] exeNames) {
        if (windowsDir == null || !windowsDir.isDirectory()) return;
        for (String exeName : exeNames) {
            try {
                File manifestFile = new File(windowsDir, exeName + ".manifest");
                String current = manifestFile.isFile() ? FileUtils.readString(manifestFile) : null;
                if (UTF8_ACTIVE_CODEPAGE_MANIFEST.equals(current)) continue;
                if (FileUtils.writeString(manifestFile, UTF8_ACTIVE_CODEPAGE_MANIFEST)) {
                    Log.d("ContainerLaunch", "Deployed UTF-8 activeCodePage manifest for " + exeName);
                } else {
                    Log.w("ContainerLaunch", "Failed to deploy UTF-8 activeCodePage manifest for " + exeName);
                }
            } catch (Exception e) {
                Log.w("ContainerLaunch", "Error deploying UTF-8 activeCodePage manifest for " + exeName, e);
            }
        }
    }

    /** External SxS manifest setting the process activeCodePage; Wine 10 reads it via the activation context. */
    private static String codePageManifest(String identity, String codePage) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<assembly xmlns=\"urn:schemas-microsoft-com:asm.v1\" manifestVersion=\"1.0\">\n"
            + "  <assemblyIdentity type=\"win32\" name=\"" + identity + "\" version=\"1.0.0.0\"/>\n"
            + "  <application xmlns=\"urn:schemas-microsoft-com:asm.v3\">\n"
            + "    <windowsSettings>\n"
            + "      <activeCodePage xmlns=\"http://schemas.microsoft.com/SMI/2019/WindowsSettings\">"
            + codePage + "</activeCodePage>\n"
            + "    </windowsSettings>\n"
            + "  </application>\n"
            + "</assembly>\n";
    }

    /**
     * Wine never sees a real guest locale (see LocaleEnv) so its ANSI code page is stuck at 1252, and legacy
     * Shift-JIS/GBK games render as '?'. Wine 10 — unlike Windows — accepts a locale name in the SxS
     * activeCodePage setting (ja-JP -> 932), so deploying "&lt;game&gt;.exe.manifest" is the Locale Emulator
     * equivalent. Only manifests carrying our marker are managed; clearing LC_ALL removes ours again.
     */
    private void ensureGameLocaleCodePageManifest() {
        if (shortcut == null || container == null) return;
        String gamePath = shortcut.path;
        if (gamePath == null || gamePath.isEmpty()) return;
        if (!gamePath.toLowerCase().endsWith(".exe")) return;

        String localeName = LocaleEnv.toBcp47(lc_all);
        try {
            File exeFile = WineUtils.getNativePath(container, imageFs, gamePath);
            if (exeFile == null || !exeFile.isFile()) {
                Log.w("ContainerLaunch", "Locale manifest: game exe not found for " + gamePath);
                return;
            }
            File manifestFile = new File(exeFile.getParentFile(), exeFile.getName() + ".manifest");
            String current = manifestFile.isFile() ? FileUtils.readString(manifestFile) : null;

            if (localeName.isEmpty()) {
                if (current != null && current.contains(LOCALE_MANIFEST_MARKER) && manifestFile.delete()) {
                    Log.d("ContainerLaunch", "Removed locale activeCodePage manifest for " + gamePath);
                }
                return;
            }

            // An embedded RT_MANIFEST wins over the external file, so the override may not take effect.
            String embeddedCodePage = embeddedManifestCodePage(exeFile);
            if (embeddedCodePage != null) {
                if (!embeddedCodePage.isEmpty()) {
                    Log.w("ContainerLaunch", "Game exe embeds activeCodePage " + embeddedCodePage
                            + "; the external " + localeName + " manifest is ignored for " + gamePath);
                    pendingLocaleManifestWarning.set(getString(
                            R.string.session_locale_manifest_embedded_codepage, embeddedCodePage));
                } else {
                    Log.w("ContainerLaunch", "Game exe has an embedded manifest; the external "
                            + localeName + " activeCodePage manifest may be ignored for " + gamePath);
                    pendingLocaleManifestWarning.set(getString(
                            R.string.session_locale_manifest_embedded, localeName));
                }
            }

            String manifest = codePageManifest(LOCALE_MANIFEST_MARKER, localeName);
            if (manifest.equals(current)) return;
            if (current != null && !current.contains(LOCALE_MANIFEST_MARKER)
                    && !current.contains(UTF8_MANIFEST_MARKER)) {
                Log.w("ContainerLaunch", "Game ships its own manifest, not overriding: " + manifestFile);
                pendingLocaleManifestWarning.set(getString(R.string.session_locale_manifest_external));
                return;
            }
            if (FileUtils.writeString(manifestFile, manifest)) {
                Log.d("ContainerLaunch", "Deployed " + localeName + " activeCodePage manifest for " + gamePath);
            } else {
                Log.w("ContainerLaunch", "Failed to deploy " + localeName + " activeCodePage manifest for " + gamePath);
            }
        } catch (Exception e) {
            Log.w("ContainerLaunch", "Error deploying locale manifest for " + gamePath, e);
        }
    }

    /** File offset of the PE resource directory (data directory index 2), or -1. */
    private static long exeResourceDirOffset(java.io.RandomAccessFile raf) throws java.io.IOException {
        raf.seek(0);
        if (raf.read() != 'M' || raf.read() != 'Z') return -1;

        raf.seek(0x3C);
        int peOffset = Integer.reverseBytes(raf.readInt());
        raf.seek(peOffset);
        if (Integer.reverseBytes(raf.readInt()) != 0x00004550) return -1; // "PE\0\0"

        raf.skipBytes(2); // machine
        int numSections = Short.reverseBytes(raf.readShort()) & 0xFFFF;
        raf.skipBytes(12); // TimeDateStamp, PointerToSymbolTable, NumberOfSymbols
        int optHeaderSize = Short.reverseBytes(raf.readShort()) & 0xFFFF;
        raf.skipBytes(2); // characteristics

        long optHeaderPos = raf.getFilePointer();
        int magic = Short.reverseBytes(raf.readShort()) & 0xFFFF;
        boolean pe32Plus = magic == 0x20B;

        // Resource table is data directory entry index 2
        int ddOffset = pe32Plus ? 112 : 96;
        raf.seek(optHeaderPos + ddOffset + 2L * 8);
        int resRva = Integer.reverseBytes(raf.readInt());
        int resSize = Integer.reverseBytes(raf.readInt());
        if (resRva == 0 || resSize == 0) return -1;

        // Map RVA -> file offset via the section table
        long sectionStart = optHeaderPos + optHeaderSize;
        for (int i = 0; i < numSections; i++) {
            raf.seek(sectionStart + i * 40L + 12); // VirtualAddress
            int va = Integer.reverseBytes(raf.readInt());
            int rawSize = Integer.reverseBytes(raf.readInt());
            int rawPtr = Integer.reverseBytes(raf.readInt());
            if (resRva >= va && resRva < va + rawSize) {
                return (long) rawPtr + (resRva - va);
            }
        }
        return -1;
    }

    /**
     * Data blob of the first resource of the given type (RT_MANIFEST is 24), or null.
     * Walks the three-level resource directory (type -> name -> language) to its data entry.
     */
    private static byte[] exeResourceData(File exeFile, int typeId) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(exeFile, "r")) {
            long resBase = exeResourceDirOffset(raf);
            if (resBase < 0) return null;

            // Level 1 (type): {id, offset} pairs after the 16-byte header plus any named entries.
            raf.seek(resBase + 12);
            int named = Short.reverseBytes(raf.readShort()) & 0xFFFF;
            int ided = Short.reverseBytes(raf.readShort()) & 0xFFFF;
            long typeOff = -1;
            raf.seek(resBase + 16 + named * 8L);
            for (int i = 0; i < ided; i++) {
                int id = Integer.reverseBytes(raf.readInt());
                int off = Integer.reverseBytes(raf.readInt());
                if (id == typeId) {
                    typeOff = resBase + (off & 0x7FFFFFFFL);
                    break;
                }
            }
            if (typeOff < 0 || typeOff == resBase) return null;

            // Levels 2 (name) and 3 (language): take the first entry of each.
            long dir = typeOff;
            for (int level = 0; level < 2; level++) {
                raf.seek(dir + 12);
                int n = Short.reverseBytes(raf.readShort()) & 0xFFFF;
                int m = Short.reverseBytes(raf.readShort()) & 0xFFFF;
                if (n + m == 0) return null;
                raf.seek(dir + 16);
                raf.skipBytes(4); // entry name
                int off = Integer.reverseBytes(raf.readInt());
                dir = resBase + (off & 0x7FFFFFFFL);
                if (dir == resBase) return null;
            }

            // Leaf: IMAGE_RESOURCE_DATA_ENTRY {OffsetToData(RVA), Size, ...}
            raf.seek(dir);
            int rva = Integer.reverseBytes(raf.readInt());
            int size = Integer.reverseBytes(raf.readInt());
            if (size <= 0 || size > (1 << 20)) return null;

            // Map the RVA through the section table
            raf.seek(0x3C);
            int peOffset = Integer.reverseBytes(raf.readInt());
            raf.seek(peOffset + 4);
            raf.skipBytes(2); // machine
            int numSections = Short.reverseBytes(raf.readShort()) & 0xFFFF;
            raf.skipBytes(12);
            int optHeaderSize = Short.reverseBytes(raf.readShort()) & 0xFFFF;
            long sectionStart = peOffset + 4 + 20 + optHeaderSize;
            for (int i = 0; i < numSections; i++) {
                raf.seek(sectionStart + i * 40L + 12); // VirtualAddress
                int va = Integer.reverseBytes(raf.readInt());
                int rawSize = Integer.reverseBytes(raf.readInt());
                int rawPtr = Integer.reverseBytes(raf.readInt());
                if (rva >= va && rva < va + rawSize) {
                    byte[] blob = new byte[size];
                    raf.seek((long) rawPtr + (rva - va));
                    raf.readFully(blob);
                    return blob;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * activeCodePage declared by the exe's embedded RT_MANIFEST (type 24): null when there is no
     * embedded manifest, "" when it embeds one that sets no code page.
     */
    private static String embeddedManifestCodePage(File exeFile) {
        byte[] blob = exeResourceData(exeFile, 24);
        if (blob == null) return null;
        Matcher matcher = ACTIVE_CODE_PAGE_PATTERN.matcher(decodeManifest(blob));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String decodeManifest(byte[] blob) {
        if (blob.length >= 2 && (blob[0] & 0xFF) == 0xFF && (blob[1] & 0xFF) == 0xFE) {
            return new String(blob, 2, blob.length - 2, java.nio.charset.StandardCharsets.UTF_16LE);
        }
        boolean utf8Bom = blob.length >= 3 && (blob[0] & 0xFF) == 0xEF
                && (blob[1] & 0xFF) == 0xBB && (blob[2] & 0xFF) == 0xBF;
        int start = utf8Bom ? 3 : 0;
        return new String(blob, start, blob.length - start, java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean ensureRequestedWineVersionInstalled() {
        if (SetupWizardActivity.isWineVersionInstalled(this, wineVersion)) {
            return true;
        }
        Log.e("XServerDisplayActivity", "Requested Wine/Proton is not installed: " + wineVersion);
        SetupWizardActivity.promptToInstallWineOrCreateContainer(this, wineVersion);
        finish();
        return false;
    }

    private void closeLaunchAttempt() {
        runOnUiThread(() -> {
            if (preloaderDialog != null && preloaderDialog.isShowing()) {
                preloaderDialog.close();
            }
            if (launchedFromPinnedShortcut) {
                AppTerminationHelper.exitApplication(this, "shortcut_launch_cancelled");
                return;
            }
            Intent intent = new Intent(this, UnifiedActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
    }



    private ControlsProfile findFirstVirtualProfile() {
        ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
        for (ControlsProfile profile : profiles) {
            if (profile != null && profile.isVirtualGamepad()) return profile;
        }
        return null;
    }

    private boolean hasActiveTouchscreenProfile() {
        return inputControlsView != null && inputControlsView.getProfile() != null;
    }

    private void setupControllerHudDetection() {
        android.hardware.input.InputManager im =
                (android.hardware.input.InputManager) getSystemService(Context.INPUT_SERVICE);
        if (im == null) return;
        hudControllerListener = new android.hardware.input.InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int id) { evaluateControllerHudMode(); }
            @Override public void onInputDeviceRemoved(int id) { evaluateControllerHudMode(); }
            @Override public void onInputDeviceChanged(int id) { evaluateControllerHudMode(); }
        };
        im.registerInputDeviceListener(hudControllerListener,
                new android.os.Handler(android.os.Looper.getMainLooper()));
        evaluateControllerHudMode();
    }

    private void evaluateControllerHudMode() {
        boolean controller =
                com.winlator.cmod.runtime.input.ControllerHelper.INSTANCE.isControllerConnected();
        boolean externalDisplay =
                externalDisplayController != null && externalDisplayController.isSwapActive();
        updateControllerHudMode(controller && externalDisplay);
    }

    // Physical controller present -> hide touch controls and show the gauge HUD, else restore them; the trackpad (touchpadView) stays either way.
    private void updateControllerHudMode(boolean connected) {
        if (connected == controllerHudMode) return;
        controllerHudMode = connected;
        runOnUiThread(() -> {
            com.winlator.cmod.runtime.display.PerformanceHudState.setVisible(connected);
            if (frameRating != null) frameRating.setHudMirrorActive(connected);
            if (connected) {
                if (inputControlsView != null) inputControlsView.setVisibility(View.GONE);
                if (frameRating != null) frameRating.setVisibility(View.GONE);
                // Lock onto the game window now so FPS/renderer come from it (it's on the external display).
                syncFrameRatingWithExistingWindows();
            } else {
                if (effectiveShowFPS && frameRating != null) frameRating.setVisibility(View.VISIBLE);
                if (inputControlsView != null && hasActiveTouchscreenProfile()
                        && preferences.getBoolean("show_touchscreen_controls_enabled", false)) {
                    inputControlsView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void applyTouchscreenOverlayPreference() {
        if (inputControlsView == null || touchpadView == null) return;

        boolean showTouchscreenControls =
                preferences.getBoolean("show_touchscreen_controls_enabled", false);
        inputControlsView.setShowTouchscreenControls(showTouchscreenControls && !controllerAutoHidden);
    }

    private void registerControllerAutoHideListener() {
        if (autoHideDeviceListener != null) return;
        autoHideInputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (autoHideInputManager == null) return;

        autoHideDeviceListener = new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                evaluateControllerAutoHide();
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                evaluateControllerAutoHide();
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {}
        };
        autoHideInputManager.registerInputDeviceListener(autoHideDeviceListener, null);
    }

    private void unregisterControllerAutoHideListener() {
        if (autoHideInputManager != null && autoHideDeviceListener != null) {
            autoHideInputManager.unregisterInputDeviceListener(autoHideDeviceListener);
        }
        autoHideDeviceListener = null;
    }

    private boolean isAnyGameControllerConnected() {
        InputManager im = autoHideInputManager != null
                ? autoHideInputManager
                : (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (im == null) return false;
        for (int deviceId : im.getInputDeviceIds()) {
            if (ExternalController.isGameController(im.getInputDevice(deviceId))) return true;
        }
        return false;
    }

    private void evaluateControllerAutoHide() {
        if (inputControlsView == null) return;

        if (!preferences.getBoolean("auto_hide_touch_on_controller", false)) {
            if (controllerAutoHidden) {
                controllerAutoHidden = false;
                applyTouchscreenOverlayPreference();
            }
            return;
        }

        if (isAnyGameControllerConnected()) {
            if (userOverrodeAutoHide) return;
            if (!controllerAutoHidden) {
                controllerAutoHidden = true;
                applyTouchscreenOverlayPreference();
            }
        } else {
            userOverrodeAutoHide = false;
            if (controllerAutoHidden) {
                controllerAutoHidden = false;
                applyTouchscreenOverlayPreference();
            }
        }
    }

    private void persistSelectedProfile(ControlsProfile profile) {
        if (shortcut == null) return;
        String newVal = profile != null ? String.valueOf(profile.id) : "";
        if (!newVal.equals(shortcut.getExtra("controlsProfile", ""))) {
            shortcut.putExtra("controlsProfile", newVal.isEmpty() ? null : newVal);
            shortcut.saveData();
        }
    }

    // Style/theme picks persist like controlsProfile: on the shortcut if present, else globally.
    private void persistSelectedStyle(VisualStyle style) {
        if (shortcut != null) {
            if (!style.name().equals(shortcut.getExtra("controlsStyle", ""))) {
                shortcut.putExtra("controlsStyle", style.name());
                shortcut.saveData();
            }
        } else {
            preferences.edit().putString("input_visual_style", style.name()).apply();
        }
    }

    private void persistSelectedAccentTheme(AccentTheme theme) {
        if (shortcut != null) {
            if (!theme.name().equals(shortcut.getExtra("controlsAccentTheme", ""))) {
                shortcut.putExtra("controlsAccentTheme", theme.name());
                shortcut.saveData();
            }
        } else {
            preferences.edit().putString("input_accent_theme", theme.name()).apply();
        }
    }

    private void pushSelectedGestureConfig() {
        try {
            int gid = selectedGestureProfileId();
            GestureProfile gp = gid != 0 ? gestureProfileManager.getProfile(gid) : gestureProfileManager.getDefaultProfile();
            if (gp == null) gp = gestureProfileManager.getDefaultProfile();
            if (gp != null && touchpadView != null) touchpadView.setGestureConfig(gp.getConfigJson());
        } catch (Throwable t) {
            android.util.Log.e("XServerDisplayActivity", "gesture resolve failed", t);
        }
    }

    private int selectedGestureProfileId() {
        if (currentGestureProfileId != 0) return currentGestureProfileId;
        int id = 0;
        if (shortcut != null) {
            try {
                id = Integer.parseInt(shortcut.getExtra("gestureProfileId", "0"));
            } catch (NumberFormatException e) {
                id = 0;
            }
        }
        if (id != 0) return id;
        GestureProfile def = gestureProfileManager != null ? gestureProfileManager.getDefaultProfile() : null;
        return def != null ? def.id : 0;
    }

    private ArrayList<ControlsProfile> getVisibleControlsProfiles() {
        return inputControlsManager != null
                ? inputControlsManager.getProfiles(true)
                : new ArrayList<>();
    }

    private void applyInputVisualStylePreferences() {
        if (inputControlsView == null || preferences == null) return;
        String style = shortcut != null ? shortcut.getExtra("controlsStyle", "") : "";
        if (style.isEmpty()) style = preferences.getString("input_visual_style", VisualStyle.SLATE.name());
        inputControlsView.setVisualStyle(VisualStyle.fromPreference(style));
        String theme = shortcut != null ? shortcut.getExtra("controlsAccentTheme", "") : "";
        if (theme.isEmpty()) theme = preferences.getString("input_accent_theme", AccentTheme.CYAN.name());
        inputControlsView.setAccentTheme(AccentTheme.fromPreference(theme));
    }

    private ControlsProfile resolvePreferredStartupProfile() {
        if (shortcut == null) return null;
        String cp = shortcut.getExtra("controlsProfile", "");
        if (cp.isEmpty()) return null;
        ControlsProfile selectedProfile;
        try {
            selectedProfile = inputControlsManager.getProfile(Integer.parseInt(cp));
        } catch (NumberFormatException e) {
            return null;
        }

        if (selectedProfile != null) {
            Log.d(
                    "XServerDisplayActivity",
                    "Resolved startup profile="
                            + selectedProfile.getName()
                            + " id="
                            + selectedProfile.id
                            + " virtual="
                            + selectedProfile.isVirtualGamepad());
        }
        return selectedProfile;
    }

    private void simulateConfirmInputControlsDialog() {
        boolean isShowTouchscreenControls = preferences.getBoolean("show_touchscreen_controls_enabled", false);
        inputControlsView.setShowTouchscreenControls(isShowTouchscreenControls);

        boolean isHapticsEnabled = preferences.getBoolean("touchscreen_haptics_enabled", false);

        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
        editor.apply();

        ControlsProfile startupProfile = resolvePreferredStartupProfile();
        if (startupProfile != null) showInputControls(startupProfile);
        else hideInputControls();

        startTouchscreenTimeout();

        Log.d("XServerDisplayActivity", "Input controls simulated confirmation executed. startupProfile=" + (startupProfile != null ? startupProfile.getName() : "none"));

        evaluateControllerAutoHide();
        controllerAutoSwitchRunnable = null;
    }

    private void startTouchscreenTimeout() {
        if (inputControlsView == null || touchpadView == null) return;
        touchpadView.setOnTouchListener(null);
        if (!controllerHudMode && inputControlsRevealAllowed && hasActiveTouchscreenProfile()) {
            inputControlsView.setVisibility(View.VISIBLE);
        }
    }

    private void showInputControls(ControlsProfile profile) {
        if (profile == null) {
            hideInputControls();
            return;
        }
        if (inputControlsRevealAllowed) {
            inputControlsView.setVisibility(View.VISIBLE);
            inputControlsView.requestFocus();
        }
        inputControlsView.setProfile(profile);
        applyTouchscreenOverlayPreference();
        persistSelectedProfile(profile);
        Log.d("XServerDisplayActivity", "showInputControls: profile=" + profile.getName() + " id=" + profile.id + " virtual=" + profile.isVirtualGamepad());

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        inputControlsView.invalidate();
        if (winHandler != null) {
            winHandler.sendGamepadState();
        }
        startTouchscreenTimeout();
        // In controller-HUD mode the on-screen controls stay hidden even though the profile is set.
        if (controllerHudMode) inputControlsView.setVisibility(View.GONE);
    }

    private void hideInputControls() {
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);
        applyTouchscreenOverlayPreference();
        persistSelectedProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        inputControlsView.invalidate();
        if (winHandler != null) {
            winHandler.sendGamepadState();
        }
        startTouchscreenTimeout();
    }

    private void extractGraphicsDriverFiles() {
        String adrenoToolsDriverId = graphicsDriverConfig.get("version");
        Log.i("GraphicsDriverExtraction", "Launch graphics driver selected: graphicsDriver='" +
                graphicsDriver + "' driverId='" + adrenoToolsDriverId + "'");

        applyPreferredRefreshRate();

        File rootDir = imageFs.getRootDir();

        if (dxwrapper.contains("dxvk")) {
            DXVKConfigUtils.setEnvVars(this, dxwrapperConfig, envVars);
            String version = dxwrapperConfig.get("version");
            if (version.equals("1.11.1-sarek")) {
                Log.d("GraphicsDriverExtraction", "Disabling Wrapper PATCH_OPCONSTCOMP SPIR-V pass");
                envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
            }
        }
        else {
            WineD3DConfigUtils.setEnvVars(this, dxwrapperConfig, envVars);
        }

        envVars.put("GALLIUM_DRIVER", "zink");
        // Kopper off only on the Steam path; normal GL needs it on to present.
        if (isSteamShortcut()) {
            envVars.put("LIBGL_KOPPER_DISABLE", "true");
        }

        if (firstTimeBoot) {
            Log.d("XServerDisplayActivity", "First time container boot, re-extracting libs");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper" + ".tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "layers" + ".tzst", rootDir);
            // extra_libs.tzst handled by the version-aware block below (covers first boot too).
        }

        // safe to re-extract: the tzst holds only usr/lib/*.so + usr/share/vulkan/*, no home/drive_c
        try {
            File extraLibsMarker = new File(rootDir, "usr/lib/.extra_libs_version");
            int installedExtraLibsVer = -1;
            if (extraLibsMarker.exists()) {
                try { installedExtraLibsVer = Integer.parseInt(FileUtils.readString(extraLibsMarker).trim()); }
                catch (Exception ignored) {}
            }
            if (installedExtraLibsVer != EXTRA_LIBS_VERSION) {
                Log.i("XServerDisplayActivity", "extra_libs outdated (installed=" + installedExtraLibsVer
                        + " bundled=" + EXTRA_LIBS_VERSION + ") — re-extracting");
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs.tzst", rootDir);
                FileUtils.writeString(extraLibsMarker, String.valueOf(EXTRA_LIBS_VERSION));
            }
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "extra_libs version check failed", e);
        }

        // FFmpeg 8 libs for Wine's winedmo media path (arm64ec native Wine only; these
        // aarch64 libs are inert under x86_64/box64). Extract into usr/lib when absent
        // so existing containers get it without a full imagefs reinstall.
        if (wineInfo != null && wineInfo.isArm64EC()
                && !new File(rootDir, "usr/lib/libavcodec.so.62").exists()) {
            try {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ffmpeg8.tzst", rootDir);
            } catch (Exception e) {
                Log.w("XServerDisplayActivity", "ffmpeg8.tzst extraction failed", e);
            }
        }

        boolean wantLeegao = "wrapper-leegao".equals(graphicsDriver);
        boolean wantGamenative = "wrapper-gamenative".equals(graphicsDriver);
        File leegaoMarker = new File(rootDir, "usr/lib/.wrapper_leegao");
        File gamenativeMarker = new File(rootDir, "usr/lib/.wrapper_gamenative");
        if (wantLeegao) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper-leegao.tzst", rootDir);
            try { leegaoMarker.createNewFile(); } catch (IOException ignored) {}
            gamenativeMarker.delete();
        } else if (wantGamenative) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper-gamenative.tzst", rootDir);
            try { gamenativeMarker.createNewFile(); } catch (IOException ignored) {}
            leegaoMarker.delete();
        } else if (leegaoMarker.exists() || gamenativeMarker.exists()) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper" + ".tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "layers" + ".tzst", rootDir);
            leegaoMarker.delete();
            gamenativeMarker.delete();
        }

        // libgallium_wgl.dll is present only while Windows Zink is installed — use as marker.
        if (wineInfo != null && wineInfo.isArm64EC()
                && !GPUInformation.getRenderer(null, null).contains("Mali")) {
            File winWindowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");
            File zinkMarker = new File(winWindowsDir, "system32/libgallium_wgl.dll");
            if ("windows".equals(zinkMode)) {
                if (!zinkMarker.exists()) {
                    try {
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink_dlls.tzst", winWindowsDir);
                    } catch (Exception e) {
                        Log.w("XServerDisplayActivity", "zink_dlls.tzst extraction failed", e);
                    }
                }
            } else if (zinkMarker.exists()) {
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, "opengl32.dll", "libgallium_wgl.dll");
            }
        }

        if (adrenoToolsDriverId != null && !adrenoToolsDriverId.isEmpty()
                && !adrenoToolsDriverId.equals("System")) {
            AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(this);
            String driverDisplayName = adrenotoolsManager.getDriverName(adrenoToolsDriverId);
            String driverVersion = adrenotoolsManager.getDriverVersion(adrenoToolsDriverId);
            String driverLibrary = adrenotoolsManager.getLibraryName(adrenoToolsDriverId);
            Log.i("GraphicsDriverExtraction", "Loading graphics/Turnip driver: id='" +
                    adrenoToolsDriverId + "' name='" + driverDisplayName +
                    "' version='" + driverVersion + "' library='" + driverLibrary + "'");
            adrenotoolsManager.setDriverById(envVars, imageFs, adrenoToolsDriverId);
            if (wantLeegao) envVars.put("ADRENOTOOLS_HOOKS_PATH", imageFs.getLibDir().getPath());
            Log.i("GraphicsDriverExtraction", "Loaded graphics/Turnip driver env: id='" +
                    adrenoToolsDriverId + "' path=" +
                    envVars.get("ADRENOTOOLS_DRIVER_PATH") + " name=" +
                    envVars.get("ADRENOTOOLS_DRIVER_NAME") + " hooks=" +
                    envVars.get("ADRENOTOOLS_HOOKS_PATH"));
        } else {
            String gameSource = (shortcut != null) ? shortcut.getExtra("game_source") : "";
            Log.w("GraphicsDriverExtraction", "No Adrenotools driver applied (id='"
                    + adrenoToolsDriverId + "' graphicsDriver='" + graphicsDriver
                    + "' gameSource='" + gameSource + "') - system Vulkan driver will be used");
        }

        envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/wrapper_icd.aarch64.json");

        String vulkanVersion = graphicsDriverConfig.get("vulkanVersion");
        if (vulkanVersion == null) vulkanVersion = "1.4";
        try {
            String fullVkVersion = GPUInformation.getVulkanVersion(adrenoToolsDriverId, this);
            if (fullVkVersion != null && fullVkVersion.contains(".")) {
                String[] parts = fullVkVersion.split("\\.");
                if (parts.length >= 3) {
                    // Never advertise a minor the driver does not implement.
                    if (Integer.parseInt(parts[1]) < Integer.parseInt(vulkanVersion.split("\\.")[1])) {
                        Log.i("GraphicsDriverExtraction", "Clamping Vulkan " + vulkanVersion
                                + " to driver-supported " + parts[0] + "." + parts[1]);
                        vulkanVersion = parts[0] + "." + parts[1];
                    }
                    vulkanVersion = vulkanVersion + "." + parts[2];
                }
            }
        } catch (Throwable e) {
            Log.w("GraphicsDriverExtraction", "Error getting Vulkan version patch", e);
        }
        envVars.put("WRAPPER_VK_VERSION", vulkanVersion);

        String blacklistedExtensions = graphicsDriverConfig.get("blacklistedExtensions");
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions);

        String gpuName = graphicsDriverConfig.get("gpuName");
        String dxvkVersion = dxwrapperConfig.get("version");
        if (gpuName != null && !gpuName.equals("Device") && dxvkVersion != null && !dxvkVersion.equals("1.11.1-sarek")) {
            envVars.put("WRAPPER_DEVICE_NAME", gpuName);
            envVars.put("WRAPPER_DEVICE_ID", WineD3DConfigUtils.getDeviceIdFromGPUName(this, gpuName));
            envVars.put("WRAPPER_VENDOR_ID", WineD3DConfigUtils.getVendorIdFromGPUName(this, gpuName));
        }

        String maxDeviceMemory = graphicsDriverConfig.get("maxDeviceMemory");
        if (maxDeviceMemory != null && Integer.parseInt(maxDeviceMemory) > 0)
            envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory);

        String presentMode = graphicsDriverConfig.get("presentMode");
        if (presentMode == null || presentMode.isEmpty()) presentMode = "mailbox";
        if (presentMode.contains("immediate")) {
            envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1");
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);

        String resourceType = graphicsDriverConfig.get("resourceType");
        envVars.put("WRAPPER_RESOURCE_TYPE", resourceType);

        ArrayList<String> wsiDebugFlags = new ArrayList<>();
        String syncFrame = graphicsDriverConfig.get("syncFrame");
        if ("1".equals(syncFrame)) {
            wsiDebugFlags.add("forcesync");
        }
        if (!wsiDebugFlags.isEmpty()) {
            envVars.put("MESA_VK_WSI_DEBUG", String.join(",", wsiDebugFlags));
        }

        String disablePresentWait = graphicsDriverConfig.get("disablePresentWait");
        envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait);

        String bcnEmulation = graphicsDriverConfig.get("bcnEmulation");
        String bcnEmulationType = graphicsDriverConfig.get("bcnEmulationType");

        int gpuVendorId = GPUInformation.getVendorID(null, null);
        // BCn compute shaders are unsupported on Adreno and crash on Xclipse with this wrapper
        boolean excludeBcnCompute = gpuVendorId == 20803 || (wantGamenative && gpuVendorId == 0x144D);

        switch (bcnEmulation) {
            case "auto" -> {
                if ("compute".equals(bcnEmulationType) && !excludeBcnCompute) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "1");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "3");
            }
            case "full" -> {
                if ("compute".equals(bcnEmulationType) && !excludeBcnCompute) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "0");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "2");
            }
            case "none" -> envVars.put("WRAPPER_EMULATE_BCN", "0");
            default -> envVars.put("WRAPPER_EMULATE_BCN", "1");
        }

        if (wantGamenative) {
            String transcoder = graphicsDriverConfig.get("transcoder");
            envVars.put("WRAPPER_BCN_GPU", "gpu".equalsIgnoreCase(transcoder) ? "1" : "0");
            String wrapperQuality = graphicsDriverConfig.get("quality");
            envVars.put("WRAPPER_ASTC_BLOCK", "high".equalsIgnoreCase(wrapperQuality) ? "4x4" : "8x8");
        }

        String bcnEmulationCache = graphicsDriverConfig.get("bcnEmulationCache");
        envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache);

    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        handleDrawerEdgeSwipe(event);

        // Drop paused input after the drawer edge-swipe check to avoid ANRs.
        if (isInputSuspended() && (drawerStateHolder == null ||
                (!drawerStateHolder.isDrawerOpen() && !drawerStateHolder.isPaneOpen()))) {

            return true;
        }

        return super.dispatchTouchEvent(event);
    }

    private void handleDrawerEdgeSwipe(MotionEvent event) {
        if (drawerStateHolder == null
                || drawerStateHolder.isDrawerOpen()
                || displayHostComposeView == null) {
            resetDrawerEdgeGesture();
            return;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                drawerEdgeGestureStartX = event.getX();
                drawerEdgeGestureStartY = event.getY();
                drawerEdgeGesturePointerId = event.getPointerId(0);
                drawerEdgeGesturePossible =
                        drawerEdgeGestureStartX <= getDrawerEdgeSwipePx()
                                && !isTouchInsideMagnifier(drawerEdgeGestureStartX, drawerEdgeGestureStartY);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!drawerEdgeGesturePossible) return;
                int pointerIndex = event.findPointerIndex(drawerEdgeGesturePointerId);
                if (pointerIndex < 0) {
                    resetDrawerEdgeGesture();
                    return;
                }

                float dx = event.getX(pointerIndex) - drawerEdgeGestureStartX;
                float dy = event.getY(pointerIndex) - drawerEdgeGestureStartY;
                int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();

                if (dx > getDrawerOpenTriggerPx()
                        && dx > Math.abs(dy) * XServerDisplayHostKt.XSERVER_DRAWER_OPEN_HORIZONTAL_RATIO) {
                    if (touchpadView != null) {
                        touchpadView.resetInputState();
                    }
                    if (inputControlsView != null) {
                        inputControlsView.cancelActiveTouches();
                    }
                    openDrawerMenu();
                    resetDrawerEdgeGesture();
                } else if (Math.abs(dy) > slop && Math.abs(dy) > dx) {
                    resetDrawerEdgeGesture();
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                resetDrawerEdgeGesture();
                break;
        }
    }

    private int getDrawerEdgeSwipePx() {
        return (int) (XServerDisplayHostKt.XSERVER_DRAWER_EDGE_SWIPE_DP * getResources().getDisplayMetrics().density);
    }

    private int getDrawerOpenTriggerPx() {
        return (int) (XServerDisplayHostKt.XSERVER_DRAWER_OPEN_TRIGGER_DP * getResources().getDisplayMetrics().density);
    }

    private boolean isTouchInsideMagnifier(float x, float y) {
        return magnifierView != null
                && magnifierView.getParent() != null
                && x >= magnifierView.getX()
                && x <= magnifierView.getX() + magnifierView.getWidth()
                && y >= magnifierView.getY()
                && y <= magnifierView.getY() + magnifierView.getHeight();
    }

    private void resetDrawerEdgeGesture() {
        drawerEdgeGesturePossible = false;
        drawerEdgeGesturePointerId = -1;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (drawerStateHolder != null && (drawerStateHolder.isDrawerOpen() || drawerStateHolder.isPaneOpen())
                && isControllerMotionEvent(event)) {
            float ax = event.getAxisValue(MotionEvent.AXIS_X);
            float ay = event.getAxisValue(MotionEvent.AXIS_Y);
            float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            int dir = 0;
            if (ax < -0.5f || hx < -0.5f) dir = 1;
            else if (ax > 0.5f || hx > 0.5f) dir = 2;
            else if (ay < -0.5f || hy < -0.5f) dir = 3;
            else if (ay > 0.5f || hy > 0.5f) dir = 4;
            if (dir != drawerStickDir) {
                drawerStickDir = dir;
                drawerStickHandler.removeCallbacks(drawerStickRepeat);
                if (dir != 0) {
                    fireDrawerStickDir(dir);
                    drawerStickHandler.postDelayed(drawerStickRepeat, 350);
                }
            }
            return true;
        }
        if (isInputSuspended() && (drawerStateHolder == null ||
                (!drawerStateHolder.isDrawerOpen() && !drawerStateHolder.isPaneOpen()))) {

            return true;
        }

        boolean handledByWinHandler = false;
        boolean handledByTouchpadView = false;

        if (!isInputSuspended() && isPointerMotionEvent(event) && touchpadView != null) {
            if (shouldUsePointerCapture() && !touchpadView.hasPointerCapture()) {
                updatePointerCapture();
            }
            handledByTouchpadView = touchpadView.onExternalMouseEvent(event);
        }

        if (handledByTouchpadView) {
            return true;
        }

        if (!isInputSuspended() && isControllerMotionEvent(event)) {
            cancelMousePointerTimeout();
            if (touchpadView != null) {
                touchpadView.cancelMousePointerTimeout();
            }
            if (winHandler != null) {
                handledByWinHandler = winHandler.onGenericMotionEvent(event);
            }
            if (inputControlsView != null) {
                inputControlsView.onGenericMotionEvent(event);
            }
            if (handledByWinHandler) return true;
        }

        boolean handledBySuper = super.dispatchGenericMotionEvent(event);

        return handledByWinHandler || handledByTouchpadView || handledBySuper;
    }


    private static final int RECAPTURE_DELAY_MS = 10000; // 10 seconds

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (drawerStateHolder != null && (drawerStateHolder.isDrawerOpen() || drawerStateHolder.isPaneOpen())
                && ExternalController.isGameController(event.getDevice())) {
            drawerStateHolder.updateControllerConnected(true);
            int kc = event.getKeyCode();
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (kc == KeyEvent.KEYCODE_BUTTON_MODE) {
                // Menu open: a fresh press closes it; suppress the tail of the hold that opened it.
                if (down && event.getEventTime() - guideMenuOpenedAt > GUIDE_HOLD_TAIL_MS) {
                    guideMenuOpenedAt = 0L;
                    handleNavigationBackPressed();
                }
                return true;
            }
            if (kc == KeyEvent.KEYCODE_BUTTON_B) {
                if (down) handleNavigationBackPressed();
                return true;
            }
            if (down) {
                if (!drawerStateHolder.isPaneOpen()) {
                    if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
                        drawerStateHolder.menuNavLeft();
                    } else if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        drawerStateHolder.menuNavRight();
                    } else if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                        drawerStateHolder.menuNavUp();
                    } else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                        drawerStateHolder.menuNavDown();
                    } else if (kc == KeyEvent.KEYCODE_BUTTON_A || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
                        drawerStateHolder.menuActivate();
                    }
                } else {
                    if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                        drawerStateHolder.paneNavUp();
                    } else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                        drawerStateHolder.paneNavDown();
                    } else if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
                        drawerStateHolder.paneNavLeft();
                    } else if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        drawerStateHolder.paneNavRight();
                    } else if (kc == KeyEvent.KEYCODE_BUTTON_A || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
                        drawerStateHolder.paneActivate();
                    } else if (kc == KeyEvent.KEYCODE_BUTTON_X) {
                        drawerStateHolder.paneSecondary();
                    }
                }
            }
            return true;
        }
        if (isInputSuspended()) return super.dispatchKeyEvent(event);
        if (ExternalController.isGameController(event.getDevice())) {
            cancelMousePointerTimeout();
            if (touchpadView != null) {
                touchpadView.cancelMousePointerTimeout();
            }
        }

        boolean handled = inputControlsView.onKeyEvent(event);
        if (winHandler != null) {
            handled |= winHandler.onKeyEvent(event);
        }
        if (xServer != null && xServer.keyboard != null) {
            handled |= xServer.keyboard.onKeyEvent(event);
        }

        if (handled) return true;

        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolumeUpPressed = true;
                else isVolumeDownPressed = true;

                if (isVolumeUpPressed && isVolumeDownPressed) {
                    isPointerCaptureForcedOff = !isPointerCaptureForcedOff;
                    updatePointerCapture();
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolumeUpPressed = false;
                else isVolumeDownPressed = false;
            }
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE) {
            // Menu closed: hold the guide button to open (a quick tap does nothing). Timer-based, so a
            // missed release can never leave it stuck.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (!guideHoldPending) {
                    guideHoldPending = true;
                    handler.removeCallbacks(guideHoldOpenRunnable);
                    handler.postDelayed(guideHoldOpenRunnable, GUIDE_HOLD_OPEN_MS);
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                guideHoldPending = false;
                handler.removeCallbacks(guideHoldOpenRunnable);
            }
            return true;
        }


        if (event.getAction() == KeyEvent.ACTION_DOWN &&
                (event.getKeyCode() == KeyEvent.KEYCODE_HOME ||
                 event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_SELECT)) {
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private static final String TAG = "DXWrapperExtraction";

    private static final String[] DXWRAPPER_DLLS = {
            "d3d10.dll", "d3d10_1.dll", "d3d10core.dll",
            "d3d11.dll", "d3d12.dll", "d3d12core.dll",
            "d3d8.dll", "d3d9.dll", "dxgi.dll",
            "ddraw.dll", "d3dimm.dll"
    };

    private void wipeDxwrapperDllsForReextract() {
        if (imageFs == null) return;
        File rootDir = imageFs.getRootDir();
        File system32 = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/system32");
        File syswow64 = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/syswow64");
        int deleted = 0;
        for (String name : DXWRAPPER_DLLS) {
            if (name.equals("d3d10.dll") || name.equals("d3d10_1.dll")
                    || name.equals("d3d8.dll") || name.equals("d3dimm.dll")) continue;
            File a = new File(system32, name);
            File b = new File(syswow64, name);
            if (a.exists() && a.delete()) deleted++;
            if (b.exists() && b.delete()) deleted++;
        }
        if (deleted > 0) {
            Log.i("XServerDisplayActivity",
                    "DXVK/VKD3D pre-extract wipe removed " + deleted + " stale DLL(s) from system32/syswow64");
        }
    }

    private void extractDXWrapperFiles(String dxwrapper) {
        final String[] dlls = DXWRAPPER_DLLS;
        final String[] d3d12Dlls = {"d3d12.dll", "d3d12core.dll"};
        final String[] nonD3D12WrapperDlls = {"d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll", "d3dimm.dll"};

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        if (dxwrapper.contains("dxvk")) {
            Log.d(TAG, "Extracting DXVK wrapper files, version: " + dxwrapper);

            String dxvkWrapper = dxwrapper.split(";")[0];
            String vkd3dWrapper = dxwrapper.split(";")[1];
            String ddrawrapper = dxwrapper.split(";")[2];
            
            if (hasSelectedDxvkWrapper(dxvkWrapper)) {
                ContentProfile dxvkProfile = contentsManager.getProfileByEntryName(dxvkWrapper);
                if (dxvkProfile != null) {
                    Log.d(TAG, "Applying user-defined DXVK content profile: " + dxvkWrapper);
                    contentsManager.applyContent(dxvkProfile);
                    extractD8VKIfNeeded(dxvkWrapper, windowsDir);
                } else {
                    Log.w(TAG, "DXVK content profile not installed; no bundled DXVK archive will be loaded: " + dxvkWrapper);
                }
            } else {
                Log.i(TAG, "Launch DXVK selected: None; restoring non-D3D12 wrapper files");
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, nonD3D12WrapperDlls);
            }

            if (vkd3dWrapper.contains("None")) {
                Log.i(TAG, "Launch VKD3D selected: None; restoring original d3d12");
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, d3d12Dlls);
            }
            else {
                applyVkd3dWrapper(vkd3dWrapper);
            }

            Log.d(TAG, "Extracting nglide wrapper");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/nglide.tzst", windowsDir, onExtractFileListener);

            // Clear any stale D7VK passthrough DLL left from a previous selection.
            File syswow64Dir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/syswow64");
            File system32Dir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/system32");
            new File(syswow64Dir, "ddraw_.dll").delete();
            new File(system32Dir, "ddraw_.dll").delete();

            ContentProfile d7vkProfile = findD7vkProfileForDdrawrapper(ddrawrapper);
            if (d7vkProfile != null) {
                Log.d(TAG, "Applying D7VK ddraw wrapper: " + ddrawrapper);
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, "ddraw.dll", "d3dimm.dll");
                File origDdraw = new File(syswow64Dir, "ddraw.dll");
                File renamedDdraw = new File(syswow64Dir, "ddraw_.dll");
                if (origDdraw.exists()) FileUtils.copy(origDdraw, renamedDdraw);
                contentsManager.applyContent(d7vkProfile);
            }
            else if (ddrawrapper.equalsIgnoreCase("none") || ddrawrapper.contains("None")) {
                Log.d(TAG, "No DDRaw wrapper has been selected, restoring original ddraw files");
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, "ddraw.dll", "d3dimm.dll");
            }
            else {
                if (ddrawrapper.equals("cnc-ddraw"))
                    envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");

                Log.d(TAG, "Extracting ddrawrapper " + ddrawrapper);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/" + ddrawrapper + ".tzst", windowsDir, onExtractFileListener);
            }

            Log.d(TAG, "Finished extraction of DXVK wrapper files, version: " + dxwrapper);
        } else if (dxwrapper.contains("wined3d")) {
            String vkd3dWrapper = findDelimitedWrapper(dxwrapper, "vkd3d-");
            if (vkd3dWrapper != null) {
                Log.d(TAG, "Restoring non-D3D12 wrapper files for WineD3D+VKD3D.");
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, nonD3D12WrapperDlls);
                applyVkd3dWrapper(vkd3dWrapper);
            } else {
                Log.d(TAG, "Restoring original DLL files for wined3d.");
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, dlls);
            }
        }
    }

    private void applyVkd3dWrapper(String vkd3dWrapper) {
        if (vkd3dWrapper == null || vkd3dWrapper.contains("None")) {
            Log.i(TAG, "Launch VKD3D selected: None; restoring original d3d12");
            WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, "d3d12.dll", "d3d12core.dll");
            return;
        }

        ContentProfile vkd3dProfile = contentsManager.getProfileByEntryName(vkd3dWrapper);
        if (vkd3dProfile != null) {
            Log.i(TAG, "Loading VKD3D content profile: " + vkd3dWrapper);
            contentsManager.applyContent(vkd3dProfile);
        } else {
            Log.w(TAG, "VKD3D content profile not installed; no bundled VKD3D archive will be loaded: " + vkd3dWrapper);
        }
    }

    private ContentProfile findD7vkProfileForDdrawrapper(String ddrawrapper) {
        if (ddrawrapper == null || contentsManager == null) return null;
        List<ContentProfile> profiles = contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_D7VK);
        if (profiles == null) return null;
        for (ContentProfile profile : profiles) {
            if (StringUtils.parseIdentifier(ContentsManager.getEntryName(profile)).equals(ddrawrapper))
                return profile;
        }
        return null;
    }

    private void extractD8VKIfNeeded(String dxvkWrapper, File windowsDir) {
        if (compareVersion(dxvkWrapper, "2.4") >= 0) return;

        Log.d(TAG, "Extracting d8vk as part of DXVK version " + dxvkWrapper);
        TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                this,
                D8VK_ASSET_PATH,
                windowsDir,
                onExtractFileListener
        );
    }

    // .msi and .bat/.cmd aren't PE images, so CreateProcess can't start them; run them through their interpreter.
    private static String buildGuestProgramArgs(String windowsPath) {
        String lower = windowsPath.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".msi")) {
            return "\"C:\\windows\\system32\\msiexec.exe\" /i \"" + windowsPath + "\" /passive /norestart";
        }
        if (lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            return "\"C:\\windows\\system32\\cmd.exe\" /c \"" + windowsPath + "\"";
        }
        return "\"" + windowsPath + "\"";
    }

    private String canonicalSteamInstallDir(int appId) {
        try {
            com.winlator.cmod.feature.stores.steam.data.SteamApp info =
                    com.winlator.cmod.feature.stores.steam.service.SteamService.Companion
                            .getAppInfoOf(appId);
            String dirName = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion
                    .getAppDirName(info);
            return (dirName != null && !dirName.trim().isEmpty()) ? dirName.trim() : null;
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "canonicalSteamInstallDir failed for appId=" + appId, e);
            return null;
        }
    }

    private String writePlanWGameSpec(String gameExeWinPath, int appId) {
        if (gameExeWinPath == null || gameExeWinPath.isEmpty()) return null;
        File spec = new File(container.getRootDir(), ".wine/drive_c/wn-steam-game.spec");
        try {
            File parent = spec.getParentFile();
            if (parent != null) parent.mkdirs();
            FileUtils.writeString(spec, gameExeWinPath + "\n" + appId + "\n");
            Log.d("XServerDisplayActivity",
                    "Steam Launcher: wrote game spec " + spec.getAbsolutePath()
                    + " (appId=" + appId + ")");
            return "C:\\wn-steam-game.spec";
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to write Steam Launcher game spec", e);
            return null;
        }
    }

    private String getWineStartCommand(GuestProgramLauncherComponent launcherComponent) {
        EnvVars envVars = getOverrideEnvVars();
        String args = "";

        if (bootExePath != null && !bootExePath.isEmpty()) {
            args = buildGuestProgramArgs(bootExePath);
            if (bootExeArgs != null && !bootExeArgs.isEmpty()) args += " " + bootExeArgs;
        } else if (shortcut != null) {
            String path = shortcut.path;
            String gameSource = shortcut.getExtra("game_source", "CUSTOM");
            Log.d("XServerDisplayActivity", "getWineStartCommand: gameSource=" + gameSource + " shortcut.path=" + path);

            if (path != null && path.matches("^[A-Z]:[^\\\\/].*")) {
                path = path.substring(0, 2) + "\\" + path.substring(2);
            }

            if (gameSource.equals("STEAM")) {
                int appId = Integer.parseInt(shortcut.getExtra("app_id"));
                // Reset per launch; set below once the launch exe is resolved.
                wnSteamDirectExeOverride = false;
                wnSteamLaunchOption = -1;
                wnSteamUserArgs = "";
                String steamExtraArgs = appendSteamJoinConnect(
                        com.winlator.cmod.feature.stores.steam.utils.SteamLaunchOptions
                                .gameArgs(shortcut.getSettingExtra("execArgs", container.getExecArgs())));
                wnSteamUserArgs = (steamExtraArgs != null) ? steamExtraArgs.trim() : "";
                steamExtraArgs = (steamExtraArgs != null && !steamExtraArgs.isEmpty()) ? " " + steamExtraArgs : "";

                boolean useColdClient = parseBoolean(getShortcutSetting("useColdClient", container.isUseColdClient() ? "1" : "0"));
                boolean launchBionicSteam = isBionicSteamEnabledForShortcut();
                if (useColdClient) {
                    launchBionicSteam = false;
                } else if (launchBionicSteam) {
                    useColdClient = false;
                }

                String gameInstPath = resolveSteamGameInstallPath(appId);
                if (gameInstPath != null && new File(gameInstPath).exists()) {
                    WineUtils.ensureSteamappsCommonSymlink(container, gameInstPath,
                            canonicalSteamInstallDir(appId));
                }

                File containerSteamDir = new File(container.getRootDir(),
                        ".wine/drive_c/Program Files (x86)/Steam");

                if (useColdClient) {
                    // ColdClient needs the game exe dir for relative assets.
                    File coldClientWorkDir = null;
                    String gameDirNameCC = (gameInstPath != null) ? new File(gameInstPath).getName() : "";
                    String relativeExeCC = resolveRelativeGameExe(appId, gameInstPath);
                    if (!gameDirNameCC.isEmpty()) {
                        File containerGameDirCC = new File(containerSteamDir, "steamapps/common/" + gameDirNameCC);
                        try { coldClientWorkDir = containerGameDirCC.getCanonicalFile(); }
                        catch (IOException e) { coldClientWorkDir = containerGameDirCC; }
                        if (!relativeExeCC.isEmpty()) {
                            String exeRelNativeCC = relativeExeCC.replace("\\", "/");
                            int lastSlashCC = exeRelNativeCC.lastIndexOf("/");
                            if (lastSlashCC > 0) {
                                File exeParentDirCC = new File(coldClientWorkDir, exeRelNativeCC.substring(0, lastSlashCC));
                                if (exeParentDirCC.exists()) coldClientWorkDir = exeParentDirCC;
                            }
                        }
                    }
                    if (coldClientWorkDir != null && coldClientWorkDir.exists()) {
                        launcherComponent.setWorkingDir(coldClientWorkDir);
                        Log.d("XServerDisplayActivity", "ColdClient working dir: " + coldClientWorkDir.getPath());
                    } else if (containerSteamDir.exists()) {
                        launcherComponent.setWorkingDir(containerSteamDir);
                        Log.w("XServerDisplayActivity", "ColdClient: game dir unresolved, falling back to Steam dir");
                    }
                    args = "\"C:\\Program Files (x86)\\Steam\\steamclient_loader_x64.exe\"";
                    Log.d("XServerDisplayActivity", "ColdClient launch via steamclient_loader_x64.exe for appId=" + appId);
                } else {
                    // Goldberg launches through steamapps/common to avoid drive-letter drift.
                    String onDiskDirName = (gameInstPath != null) ? new File(gameInstPath).getName() : "";
                    String canonicalDirName = canonicalSteamInstallDir(appId);
                    String gameDirName = (canonicalDirName != null && !canonicalDirName.isEmpty())
                            ? canonicalDirName
                            : onDiskDirName;
                    String relativeExe = resolveRelativeGameExe(appId, gameInstPath);
                    // If the resolved exe isn't Steam's configured launch entry the user overrode it; tell the launcher to skip LaunchApp and start the selected exe directly.
                    wnSteamDirectExeOverride = isUserOverriddenSteamExe(appId, relativeExe);
                    wnSteamLaunchOption = com.winlator.cmod.feature.stores.steam.utils
                            .SteamUtils.steamLaunchOptionFor(appId, relativeExe);
                    String launcherExe = XServerDisplayUtils.preferLauncherExe(relativeExe, gameInstPath);
                    if (!launcherExe.equals(relativeExe)) {
                        Log.i("XServerDisplayActivity",
                                "Steam Launcher: watch exe '" + relativeExe + "' -> launcher '"
                                + launcherExe + "' (Steam spawns the launcher; the arch exe is its child)");
                        relativeExe = launcherExe;
                    }
                    Log.i("XServerDisplayActivity",
                            "Steam Launcher: launch option for appId=" + appId + " exe='"
                            + relativeExe + "' -> " + wnSteamLaunchOption);

                    if (!relativeExe.isEmpty() && !gameDirName.isEmpty()) {
                        String steamGameExe = "C:\\Program Files (x86)\\Steam\\steamapps\\common\\"
                                + gameDirName + "\\" + relativeExe.replace("/", "\\");

                        File containerGameDir = new File(containerSteamDir, "steamapps/common/" + gameDirName);
                        File actualWorkDir;
                        try { actualWorkDir = containerGameDir.getCanonicalFile(); }
                        catch (IOException e) { actualWorkDir = containerGameDir; }
                        String exeRelNative = relativeExe.replace("\\", "/");
                        int lastSlash = exeRelNative.lastIndexOf("/");
                        if (lastSlash > 0) {
                            File exeParentDir = new File(actualWorkDir, exeRelNative.substring(0, lastSlash));
                            if (exeParentDir.exists()) actualWorkDir = exeParentDir;
                        }
                        if (actualWorkDir.exists()) {
                            launcherComponent.setWorkingDir(actualWorkDir);
                            Log.d("XServerDisplayActivity", "Goldberg working dir: " + actualWorkDir.getPath());
                        }

                        int lastBackslash = steamGameExe.lastIndexOf("\\");
                        if (lastBackslash > 0) {
                            envVars.put("WINEPATH", steamGameExe.substring(0, lastBackslash));
                        }
                        if (launchBionicSteam) {
                            boolean planW = com.winlator.cmod.feature.stores.steam.utils
                                    .PrefManager.INSTANCE.getWnPlanW();
                            String wrapperExe = planW
                                    ? "steam.exe" : "wn-steam-helper.exe";
                            String launchArg = steamGameExe;
                            if (planW) {
                                String specPath = writePlanWGameSpec(steamGameExe, appId);
                                if (specPath != null) {
                                    launchArg = specPath;
                                    envVars.put("WN_STEAM_GAMEEXE_FILE", specPath);
                                }
                            }
                            args = "\"C:\\Program Files (x86)\\Steam\\" + wrapperExe
                                    + "\" \"" + launchArg + "\"" + steamExtraArgs;
                            Log.d("XServerDisplayActivity",
                                    "Bionic Steam launch via " + wrapperExe
                                    + " (planW=" + planW + "): " + steamGameExe
                                    + " arg=" + launchArg);
                        } else {
                            args = "\"" + steamGameExe + "\"" + steamExtraArgs;
                            Log.d("XServerDisplayActivity", "Goldberg launch: " + steamGameExe);
                        }
                    } else {
                        String gameExeWinPath = findGameExeWinPath(appId,
                                new File(gameInstPath != null ? gameInstPath : ""));
                        if (gameExeWinPath != null) {
                            int lastBackslash = gameExeWinPath.lastIndexOf("\\");
                            String dir = lastBackslash >= 0 ? gameExeWinPath.substring(0, lastBackslash) : "C:\\";
                            args = "\"" + gameExeWinPath + "\"" + steamExtraArgs;
                            envVars.put("WINEPATH", dir);
                            Log.d("XServerDisplayActivity", "Goldberg fallback launch: " + gameExeWinPath);
                        } else {
                            Log.e("XServerDisplayActivity", "Could not find game exe for appId=" + appId
                                    + " gameInstPath=" + gameInstPath + " relativeExe=" + relativeExe);
                            args = "\"wfm.exe\"";
                        }
                    }
                }
            } else if (gameSource.equals("EPIC") || gameSource.equals("GOG")) {
                String extraArgs = shortcut.getSettingExtra("execArgs", container.getExecArgs());
                if (extraArgs == null || extraArgs.isEmpty()) {
                    extraArgs = getIntent().getStringExtra("extra_exec_args");
                }
                extraArgs = (extraArgs != null && !extraArgs.isEmpty()) ? " " + extraArgs : "";
                String gameInstallPath = shortcut.getExtra("game_install_path");

                String storeInstallPath = shortcut.getExtra("game_install_path");
                if (storeInstallPath != null && !storeInstallPath.isEmpty()
                        && new File(storeInstallPath).exists()) {
                    WineUtils.ensureDriveCGameSymlink(container, gameSource, storeInstallPath);
                }

                boolean needsAutoDetect = path == null || path.isEmpty()
                        || "D:\\".equals(path) || "D:\\\\".equals(path)
                        || "A:\\".equals(path) || "A:\\\\".equals(path);
                if (needsAutoDetect) {
                    if ((gameInstallPath == null || gameInstallPath.isEmpty()) && gameSource.equals("GOG")) {
                        String gogId = shortcut.getExtra("gog_id");
                        if (!gogId.isEmpty()) {
                            try {
                                com.winlator.cmod.feature.stores.gog.data.GOGGame gogGame = com.winlator.cmod.feature.stores.gog.service.GOGService.Companion.getGOGGameOf(gogId);
                                if (gogGame != null) {
                                    gameInstallPath = gogGame.getInstallPath();
                                    if ((gameInstallPath == null || gameInstallPath.isEmpty()) && gogGame.getTitle() != null && !gogGame.getTitle().isEmpty()) {
                                        gameInstallPath = com.winlator.cmod.feature.stores.gog.service.GOGConstants.INSTANCE.getGameInstallPath(gogGame.getTitle());
                                    }
                                }
                            } catch (Exception e) {
                                Log.e("XServerDisplayActivity", "Failed to resolve GOG install path for auto-detect", e);
                            }
                        }
                    }

                    if (gameInstallPath != null && !gameInstallPath.isEmpty()) {
                        File gameDir = new File(gameInstallPath);
                        String detectedPath = findGameExeWinPath(0, gameDir);
                        if (detectedPath != null && !detectedPath.isEmpty()) {
                            path = detectedPath;
                            updateShortcutExecLine(detectedPath);
                        }
                    }
                }
                path = repairStoreExecutableWinPath(gameSource, gameInstallPath, path);
                
                String filename = path;
                String dir = null;
                
                if (path != null && path.contains("\\")) {
                    int lastBackslash = path.lastIndexOf("\\");
                    filename = path.substring(lastBackslash + 1);
                    dir = path.substring(0, lastBackslash);
                    if (dir.endsWith(":")) dir += "\\";
                } else if (path != null && path.contains(":")) {
                    filename = path.substring(path.indexOf(":") + 1);
                    dir = path.substring(0, path.indexOf(":") + 1) + "\\";
                }
                if ((dir == null || dir.isEmpty()) && gameInstallPath != null && !gameInstallPath.isEmpty()) {
                    dir = com.winlator.cmod.runtime.wine.WineUtils.hostPathToRootWinePath(container, gameInstallPath);
                    if (dir != null && dir.endsWith(":")) dir += "\\";
                }
                if (dir == null || dir.isEmpty()) {
                    dir = "F:\\";
                }

                File nativeDir = com.winlator.cmod.runtime.wine.WineUtils.getNativePath(imageFs, dir);
                if (nativeDir != null && nativeDir.exists()) {
                    launcherComponent.setWorkingDir(nativeDir);
                    Log.d("XServerDisplayActivity", "Set native working dir for store process: " + nativeDir.getPath());
                }

                String storeCommand = dir + (dir.endsWith("\\") ? "" : "\\") + filename;
                args = "\"" + storeCommand + "\"" + extraArgs;
                Log.d("XServerDisplayActivity", gameSource + " game launch: " + args);
            } else {
                String extraArgs = shortcut.getSettingExtra("execArgs", container.getExecArgs());
                extraArgs = (extraArgs != null && !extraArgs.isEmpty()) ? " " + extraArgs : "";
                String customResolvedPath = resolveCustomExecutableWinPath(shortcut);
                if (customResolvedPath != null && !customResolvedPath.isEmpty()) {
                    path = customResolvedPath;
                }

                if (path != null && (path.startsWith("explorer") || path.contains(" /desktop"))) {
                    return path + extraArgs;
                } else if (path != null) {
                    String nativeDirPath = getActiveGameDirectoryPath();
                    if (nativeDirPath != null) {
                        File nativeDir = new File(nativeDirPath);
                        launcherComponent.setWorkingDir(nativeDir);
                        Log.d("XServerDisplayActivity", "Set native working dir for Custom process: " + nativeDir.getPath());
                    } else {
                        int lastBackslash = path.lastIndexOf("\\");
                        if (lastBackslash >= 0) {
                            String dir = path.substring(0, lastBackslash);
                            if (dir.endsWith(":")) dir += "\\";

                            File nativeDir = com.winlator.cmod.runtime.wine.WineUtils.getNativePath(this.container, imageFs, dir);
                            if (nativeDir != null) {
                                launcherComponent.setWorkingDir(nativeDir);
                                Log.d("XServerDisplayActivity", "Set native working dir for Custom process: " + nativeDir.getPath());
                            }
                        }
                    }

                    args = buildGuestProgramArgs(path) + extraArgs;
                } else {
                    args = "\"wfm.exe\"";
                }
            }
        } else {
            if (envVars.has("EXTRA_EXEC_ARGS")) {
                args = envVars.get("EXTRA_EXEC_ARGS");
                envVars.remove("EXTRA_EXEC_ARGS");
            } else {
                args = "\"wfm.exe\"";
            }
        }

        if (!args.isEmpty() && !args.startsWith("winhandler.exe") && !args.startsWith("explorer")) {
            return "winhandler.exe " + args;
        } else {
            return args;
        }
    }

    private String getExecutable() {
        String filename = "";
        if (shortcut != null) {
            filename = FileUtils.getName(shortcut.path);
        }
        else
            filename = "wfm.exe";
        return filename;
    }

    private boolean verifySteamClientFiles(boolean requireColdClientSupport) {
        File steamDir = new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam");
        String[] criticalFiles = requireColdClientSupport
                ? new String[] {
                    "steam.exe",
                    "Steam.dll",
                    "steamclient.dll",
                    "steamclient64.dll",
                    "SteamUI.dll",
                    "steam.signatures",
                    "steamclient_loader_x64.exe",
                    "extra_dlls/StubDRM64.dll"
                }
                : new String[] {
                    "steam.exe",
                    "Steam.dll",
                    "steamclient.dll",
                    "steamclient64.dll",
                    "SteamUI.dll",
                    "steam.signatures",
                    "tier0_s.dll",
                    "tier0_s64.dll",
                    "vstdlib_s.dll",
                    "vstdlib_s64.dll"
                };

        boolean allPresent = areSteamFilesPresent(steamDir, criticalFiles);

        if (!allPresent) {
            Log.w("XServerDisplayActivity", "Steam client files missing in container, forcing re-extraction");
            try {
                File steamFile = new File(getFilesDir(), "steam.tzst");
                File expFile = new File(getFilesDir(), "experimental-drm.tzst");
                if (!requireColdClientSupport && steamFile.exists()) {
                    com.winlator.cmod.shared.io.TarCompressorUtils.extract(
                            com.winlator.cmod.shared.io.TarCompressorUtils.Type.ZSTD,
                            steamFile, imageFs.getRootDir(), null);
                }
                if (requireColdClientSupport && expFile.exists()) {
                    com.winlator.cmod.shared.io.TarCompressorUtils.extract(
                            com.winlator.cmod.shared.io.TarCompressorUtils.Type.ZSTD,
                            expFile, imageFs.getRootDir(), null);
                }
                Log.d("XServerDisplayActivity", "Re-extracted Steam client files to container");
            } catch (Exception e) {
                Log.e("XServerDisplayActivity", "Failed to re-extract Steam files", e);
            }

            allPresent = areSteamFilesPresent(steamDir, criticalFiles);
            if (!allPresent) {
                Log.e("XServerDisplayActivity", "Steam client verification still failed after re-extraction");
            }
        }
        return allPresent;
    }

    private boolean areSteamFilesPresent(File steamDir, String[] relativePaths) {
        if (!steamDir.exists()) return false;

        for (String relativePath : relativePaths) {
            File f = new File(steamDir, relativePath);
            if (!f.exists() || f.length() == 0) {
                Log.w("XServerDisplayActivity", "Missing Steam client file: " + relativePath);
                return false;
            }
        }
        return true;
    }

    private void generateSteamInterfacesForGame(File gameDir) {
        if (gameDir == null || !gameDir.exists()) return;
        File[] files = gameDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory() && !file.getName().equals("steam_settings")) {
                generateSteamInterfacesForGame(file);
            } else if (file.isFile()) {
                String name = file.getName().toLowerCase();
                if (name.equals("steam_api.dll") || name.equals("steam_api64.dll")) {
                    generateSteamInterfacesFromDll(file.getParentFile(), file);
                }
            }
        }
    }

    private void generateSteamInterfacesFromDll(File dir, File dllFile) {
        File interfacesFile = new File(dir, "steam_interfaces.txt");
        if (interfacesFile.exists()) return;

        if (!dllFile.exists()) return;

        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(dllFile.toPath());
            java.util.TreeSet<String> interfaces = new java.util.TreeSet<>();

            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                int ch = b & 0xFF;
                if (ch >= 0x20 && ch <= 0x7E) {
                    sb.append((char) ch);
                } else {
                    if (sb.length() >= 10) {
                        String candidate = sb.toString();
                        if (candidate.matches("^Steam[A-Za-z]+[0-9]{3}$")) {
                            interfaces.add(candidate);
                        }
                    }
                    sb.setLength(0);
                }
            }
            if (sb.length() >= 10) {
                String candidate = sb.toString();
                if (candidate.matches("^Steam[A-Za-z]+[0-9]{3}$")) {
                    interfaces.add(candidate);
                }
            }

            if (!interfaces.isEmpty()) {
                StringBuilder content = new StringBuilder();
                for (String iface : interfaces) {
                    content.append(iface).append("\n");
                }
                FileUtils.writeString(interfacesFile, content.toString());
                Log.d("XServerDisplayActivity", "Generated steam_interfaces.txt with " + interfaces.size() + " interfaces in " + dir.getName());
            }
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to generate steam_interfaces.txt from " + dllFile.getName(), e);
        }
    }

    private void writeColdClientIniDirect(int appId, String gameDirName, String relativeExe, boolean runtimePatcher) {
        File iniFile = new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/ColdClientLoader.ini");
        iniFile.getParentFile().mkdirs();

        String exePath = "steamapps\\common\\" + gameDirName + "\\" + relativeExe.replace("/", "\\");
        String exeRunDir = exePath;
        int lastSep = exePath.lastIndexOf("\\");
        if (lastSep > 0) {
            exeRunDir = exePath.substring(0, lastSep);
        }

        String perGameExecArgs = shortcut != null ? shortcut.getSettingExtra("execArgs", container.getExecArgs()) : container.getExecArgs();
        String exeCommandLine = appendSteamJoinConnect(com.winlator.cmod.feature.stores.steam.utils.SteamLaunchOptions.gameArgs(perGameExecArgs));

        String iniContent = buildColdClientIni(appId, exePath, exeRunDir, exeCommandLine, runtimePatcher);

        FileUtils.writeString(iniFile, iniContent);
        if (runtimePatcher) ensureExtraDllsLoadOrder();
        Log.d("XServerDisplayActivity",
                "Wrote ColdClientLoader.ini: Exe=" + exePath + " ExeRunDir=" + exeRunDir
                        + " AppId=" + appId + " runtimePatcher=" + runtimePatcher);
    }

    // Appends a friend's join connect string to the game's launch arguments.
    private String appendSteamJoinConnect(String args) {
        String joinConnect = getIntent().getStringExtra("steam_join_connect");
        if (joinConnect == null || joinConnect.trim().isEmpty()) return args != null ? args : "";
        joinConnect = joinConnect.trim();
        if (args == null || args.trim().isEmpty()) return joinConnect;
        return args.trim() + " " + joinConnect;
    }

    private String buildColdClientIni(int appId, String exePath, String exeRunDir,
                                       String exeCommandLine, boolean runtimePatcher) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("[SteamClient]\n\n");
        sb.append("Exe=").append(exePath).append('\n');
        sb.append("ExeRunDir=").append(exeRunDir).append('\n');
        sb.append("ExeCommandLine=").append(exeCommandLine).append('\n');
        sb.append("AppId=").append(appId).append('\n');
        sb.append('\n');
        sb.append("# path to the steamclient dlls, both must be set, absolute paths or relative to the loader directory\n");
        sb.append("SteamClientDll=steamclient.dll\n");
        sb.append("SteamClient64Dll=steamclient64.dll\n");
        sb.append('\n');
        sb.append("[Injection]\n");
        sb.append("ForceInjectSteamClient=0\n");
        sb.append("ForceInjectGameOverlayRenderer=0\n");
        if (runtimePatcher) {
            sb.append("DllsToInjectFolder=C:\\Program Files (x86)\\Steam\\extra_dlls\n");
        }
        sb.append("IgnoreInjectionError=1\n");
        sb.append("IgnoreLoaderArchDifference=1\n");
        sb.append('\n');
        sb.append("[Persistence]\n");
        sb.append("Mode=0\n");
        sb.append('\n');
        sb.append("[Debug]\n");
        sb.append("ResumeByDebugger=0\n");
        return sb.toString();
    }

    private void ensureExtraDllsLoadOrder() {
        File extraDlls = new File(container.getRootDir(),
                ".wine/drive_c/Program Files (x86)/Steam/extra_dlls");
        File stubDrm = new File(extraDlls, "StubDRM64.dll");
        if (!stubDrm.exists()) return;
        File loadOrder = new File(extraDlls, "load_order.txt");
        String body = "StubDRM64.dll\n";
        if (loadOrder.exists() && body.equals(FileUtils.readString(loadOrder))) return;
        FileUtils.writeString(loadOrder, body);
        Log.d("XServerDisplayActivity", "Wrote " + loadOrder.getAbsolutePath());
    }

    private void writeColdClientIniForLaunch(int appId, String gameInstallPath, String gameExeWinPath, boolean runtimePatcher) {
        File iniFile = new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/ColdClientLoader.ini");
        iniFile.getParentFile().mkdirs();

        String exePath;
        String gameDirName = new File(gameInstallPath).getName();

        if (gameExeWinPath != null) {
            String relativeExe = getRelativeGameExePath(gameExeWinPath, new File(gameInstallPath));
            exePath = "steamapps\\common\\" + gameDirName + "\\" + relativeExe;
        } else {
            exePath = "";
        }

        String exeRunDir = "steamapps\\common\\" + gameDirName;
        if (!exePath.isEmpty()) {
            int lastSeparator = exePath.lastIndexOf("\\");
            if (lastSeparator > 0) {
                exeRunDir = exePath.substring(0, lastSeparator);
            }
        }

        String perGameExecArgs = shortcut != null ? shortcut.getSettingExtra("execArgs", container.getExecArgs()) : container.getExecArgs();
        String exeCommandLine = appendSteamJoinConnect(com.winlator.cmod.feature.stores.steam.utils.SteamLaunchOptions.gameArgs(perGameExecArgs));

        String iniContent = buildColdClientIni(appId, exePath, exeRunDir, exeCommandLine, runtimePatcher);

        FileUtils.writeString(iniFile, iniContent);
        if (runtimePatcher) ensureExtraDllsLoadOrder();
        Log.d("XServerDisplayActivity", "Wrote ColdClientLoader.ini: Exe=" + exePath + " ExeRunDir=" + exeRunDir + " AppId=" + appId + " runtimePatcher=" + runtimePatcher);

        // Embedded Steam/ copies can shadow the main loader config.
        if (gameInstallPath != null) {
            File gameSteamDir = new File(gameInstallPath, "Steam");
            File gameSteamIni = new File(gameSteamDir, "ColdClientLoader.ini");
            if (gameSteamDir.exists() && gameSteamIni.exists()) {
                FileUtils.writeString(gameSteamIni, iniContent);
                Log.d("XServerDisplayActivity", "Also updated ColdClientLoader.ini in game's Steam/ dir: " + gameSteamIni.getAbsolutePath());
            }
        }
    }
    
    private String getRelativeGameExePath(String gameExeWinPath, File gameDir) {
        if (gameExeWinPath == null || gameExeWinPath.isEmpty()) return "";

        File nativeGameExe = com.winlator.cmod.runtime.wine.WineUtils.getNativePath(imageFs, gameExeWinPath);
        if (nativeGameExe != null && gameDir != null) {
            String gameDirPath = getCanonicalPathOrAbsolute(gameDir);
            String nativePath = getCanonicalPathOrAbsolute(nativeGameExe);
            if (nativePath.equals(gameDirPath) || nativePath.startsWith(gameDirPath + File.separator)) {
                String relativePath = nativePath.substring(gameDirPath.length());
                if (relativePath.startsWith(File.separator)) relativePath = relativePath.substring(1);
                return relativePath.replace("/", "\\");
            }
        }

        String normalizedPath = gameExeWinPath.replace("/", "\\");
        if (normalizedPath.matches("^[A-Za-z]:\\\\.*")) {
            return normalizedPath.substring(3);
        }
        return normalizedPath;
    }

    private void syncContainerSteamExecutableFromShortcut(int appId, String gameInstallPath) {
        String shortcutExePath = resolveShortcutSteamExecutablePath(gameInstallPath);
        if (shortcutExePath.isEmpty() || container == null) return;

        String currentPath = container.getExecutablePath();
        if (!shortcutExePath.equals(currentPath)) {
            container.setExecutablePath(shortcutExePath);
            container.saveData();
            Log.d("XServerDisplayActivity", "Synced Steam executable from shortcut for appId="
                    + appId + ": " + shortcutExePath);
        }
    }

    private String resolveShortcutSteamExecutablePath(String gameInstallPath) {
        if (shortcut == null || gameInstallPath == null || gameInstallPath.isEmpty()) return "";

        String launchExePath = shortcut.getExtra("launch_exe_path");
        if (launchExePath == null || launchExePath.isEmpty()) return "";

        File gameDir = new File(gameInstallPath);
        if (!gameDir.isDirectory()) return "";

        File configuredFile = new File(launchExePath);
        if (configuredFile.isAbsolute()) {
            if (!configuredFile.isFile()) return "";

            String configuredAbsolutePath = getCanonicalPathOrAbsolute(configuredFile);
            String gameInstallCanonicalPath = getCanonicalPathOrAbsolute(gameDir);
            String gameInstallPrefix = gameInstallCanonicalPath.endsWith(File.separator)
                    ? gameInstallCanonicalPath
                    : gameInstallCanonicalPath + File.separator;
            if (!configuredAbsolutePath.startsWith(gameInstallPrefix)) return "";

            return configuredAbsolutePath
                    .substring(gameInstallPrefix.length())
                    .replace(File.separatorChar, '/');
        }

        String relativePath = launchExePath.replace('\\', '/');
        while (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        if (relativePath.isEmpty() || relativePath.matches("^[A-Za-z]:/.*")) return "";

        File resolvedFile = resolvePathCaseInsensitive(gameDir, relativePath);
        if (resolvedFile == null || !resolvedFile.isFile()) return "";

        String resolvedAbsolutePath = getCanonicalPathOrAbsolute(resolvedFile);
        String gameInstallCanonicalPath = getCanonicalPathOrAbsolute(gameDir);
        String gameInstallPrefix = gameInstallCanonicalPath.endsWith(File.separator)
                ? gameInstallCanonicalPath
                : gameInstallCanonicalPath + File.separator;
        if (!resolvedAbsolutePath.startsWith(gameInstallPrefix)) return relativePath;

        return resolvedAbsolutePath
                .substring(gameInstallPrefix.length())
                .replace(File.separatorChar, '/');
    }

    private boolean isUserOverriddenSteamExe(int appId, String resolvedRelativeExe) {
        if (resolvedRelativeExe == null || resolvedRelativeExe.isEmpty()) return false;
        String steamDefaultExe = SteamBridge.getInstalledExe(appId);
        if (steamDefaultExe == null || steamDefaultExe.isEmpty()) return false;
        String resolved = exeBaseName(resolvedRelativeExe);
        String configured = exeBaseName(steamDefaultExe);
        if (resolved.isEmpty() || configured.isEmpty()) return false;
        if (XServerDisplayUtils.sameExeFamily(resolved, configured)) return false;
        Log.i("XServerDisplayActivity",
                "Steam Launcher: resolved exe '" + resolved + "' is not in the family of Steam's "
                + "configured entry '" + configured + "' — treating as a user override");
        return true;
    }

    private String resolveRelativeGameExe(int appId, String gameInstPath) {
        // Per-game launch_exe_path wins over the shared container cache.
        String shortcutExePath = resolveShortcutSteamExecutablePath(gameInstPath);
        if (!shortcutExePath.isEmpty()) {
            if (container != null && !shortcutExePath.equals(container.getExecutablePath())) {
                container.setExecutablePath(shortcutExePath);
                container.saveData();
            }
            Log.d("XServerDisplayActivity", "resolveRelativeGameExe: found via shortcut.launch_exe_path: " + shortcutExePath);
            return shortcutExePath;
        }

        String exePath = container.getExecutablePath();
        if (exePath != null && !exePath.isEmpty() && gameInstPath != null) {
            File test = new File(gameInstPath, exePath.replace("\\", "/"));
            if (test.isFile()) {
                Log.d("XServerDisplayActivity", "resolveRelativeGameExe: found via container.executablePath: " + exePath);
                return exePath;
            }
        }

        String steamExe = SteamBridge.getInstalledExe(appId);
        if (steamExe != null && !steamExe.isEmpty() && gameInstPath != null) {
            File test = new File(gameInstPath, steamExe.replace("\\", "/"));
            if (test.isFile()) {
                Log.d("XServerDisplayActivity", "resolveRelativeGameExe: found via SteamBridge.getInstalledExe: " + steamExe);
                container.setExecutablePath(steamExe);
                container.saveData();
                if (shortcut != null && (shortcut.getExtra("launch_exe_path") == null || shortcut.getExtra("launch_exe_path").isEmpty())) {
                    shortcut.putExtra("launch_exe_path", steamExe);
                    shortcut.saveData();
                }
                return steamExe;
            }
        }

        if (gameInstPath != null) {
            File gameDir = new File(gameInstPath);
            if (gameDir.exists()) {
                String canonicalGameDir = getCanonicalPathOrAbsolute(gameDir);
                if (isSuspiciousSteamGameInstallDir(canonicalGameDir)) {
                    Log.w("XServerDisplayActivity",
                            "resolveRelativeGameExe: refusing auto-detect inside shared Steam library root: "
                                    + canonicalGameDir + " for appId=" + appId);
                    return "";
                }
                File detected = findGameExe(gameDir);
                if (detected != null) {
                    String absPath = getCanonicalPathOrAbsolute(detected);
                    String basePath = canonicalGameDir;
                    if (absPath.startsWith(basePath)) {
                        String relative = absPath.substring(basePath.length());
                        if (relative.startsWith(File.separator)) relative = relative.substring(1);
                        Log.d("XServerDisplayActivity", "resolveRelativeGameExe: auto-detected: " + relative);
                        container.setExecutablePath(relative);
                        container.saveData();
                        if (shortcut != null && (shortcut.getExtra("launch_exe_path") == null || shortcut.getExtra("launch_exe_path").isEmpty())) {
                            shortcut.putExtra("launch_exe_path", relative);
                            shortcut.saveData();
                        }
                        return relative;
                    }
                }
            }
        }

        Log.w("XServerDisplayActivity", "resolveRelativeGameExe: all strategies failed for appId=" + appId
                + " gameInstPath=" + gameInstPath);
        return "";
    }

    private String findGameExeWinPath(int appId, File gameDir) {
        if (gameDir == null || !gameDir.exists()) return null;

        String gameInstallPath = getCanonicalPathOrAbsolute(gameDir);

        if (appId > 0) {
            String resolvedRelativePath = resolveShortcutSteamExecutablePath(gameInstallPath);

            if (resolvedRelativePath.isEmpty()) {
                resolvedRelativePath = SteamBridge.getInstalledExe(appId);
            }

            if (resolvedRelativePath != null && !resolvedRelativePath.isEmpty()) {
                if (shortcut != null && (shortcut.getExtra("launch_exe_path") == null || shortcut.getExtra("launch_exe_path").isEmpty())) {
                    shortcut.putExtra("launch_exe_path", resolvedRelativePath);
                    shortcut.saveData();
                }
                File resolvedExeFile = resolvePathCaseInsensitive(gameDir, resolvedRelativePath);
                if (resolvedExeFile != null && resolvedExeFile.isFile()) {
                    return com.winlator.cmod.runtime.wine.WineUtils.hostPathToRootWinePath(
                            container, getCanonicalPathOrAbsolute(resolvedExeFile));
                }
            }
        }

        if (shortcut != null && shortcut.path != null && !shortcut.path.isEmpty()
                && !shortcut.path.contains("steamclient_loader")
                && shortcut.path.contains("\\")) {
            String safePath = shortcut.path;
            if (safePath.matches("^[A-Z]:[^\\\\/].*")) {
                safePath = safePath.substring(0, 2) + "\\" + safePath.substring(2);
            }
            return safePath;
        }

        File exeFile = findGameExe(gameDir);
        if (exeFile != null) {
            return com.winlator.cmod.runtime.wine.WineUtils.hostPathToRootWinePath(container, exeFile.getAbsolutePath());
        }

        return null;
    }

    private String getCanonicalPathOrAbsolute(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private File resolvePathCaseInsensitive(File baseDir, String relativePath) {
        if (baseDir == null || relativePath == null || relativePath.isEmpty()) {
            return null;
        }

        String normalizedPath = relativePath.replace('\\', '/');
        File directFile = new File(baseDir, normalizedPath);
        if (directFile.exists()) {
            return directFile;
        }

        File currentDir = baseDir;
        String[] segments = normalizedPath.split("/");
        for (String segment : segments) {
            if (segment == null || segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                currentDir = currentDir.getParentFile();
                if (currentDir == null) {
                    return null;
                }
                continue;
            }

            File[] entries = currentDir.listFiles();
            if (entries == null) {
                return null;
            }

            File matched = null;
            for (File entry : entries) {
                if (entry.getName().equalsIgnoreCase(segment)) {
                    matched = entry;
                    break;
                }
            }
            if (matched == null) {
                return null;
            }
            currentDir = matched;
        }

        return currentDir;
    }

    private void injectSteamApiIfMissing(File gameDir, String appDirPath, String language,
            boolean isOffline, boolean useSteamInput, String ticketBase64, java.util.List<String> backupPaths) {
        Log.w("XServerDisplayActivity", "No steam_api DLLs found in game directory — injecting Goldberg steam_api next to game exe");
        try {
            String exePath = resolveShortcutSteamExecutablePath(getCanonicalPathOrAbsolute(gameDir));
            if ((exePath == null || exePath.isEmpty()) && shortcut != null) {
                exePath = shortcut.getExtra("launch_exe_path");
            }
            File gameExe = null;
            if (exePath != null && !exePath.isEmpty()) {
                File candidate = new File(exePath);
                if (!candidate.isAbsolute()) candidate = new File(gameDir, exePath);
                if (candidate.exists()) gameExe = candidate;
            }
            if (gameExe == null) {
                File[] rootFiles = gameDir.listFiles();
                if (rootFiles != null) {
                    for (File f : rootFiles) {
                        if (f.isFile() && f.getName().toLowerCase().endsWith(".exe")
                                && !f.getName().toLowerCase().contains("crash")
                                && !f.getName().toLowerCase().contains("unins")
                                && !f.getName().toLowerCase().contains("redist")) {
                            gameExe = f;
                            break;
                        }
                    }
                }
            }

            if (gameExe != null && gameExe.exists()) {
                File exeDir = gameExe.getParentFile();
                boolean isX64 = isExe64Bit(gameExe);
                String dllName = isX64 ? "steam_api64.dll" : "steam_api.dll";
                String assetName = isX64 ? "steampipe/steam_api64.dll" : "steampipe/steam_api.dll";
                String stubAsset = isX64 ? "steampipe/steamclient64.dll" : "steampipe/steamclient.dll";
                String stubName = isX64 ? "steamclient64.dll" : "steamclient.dll";

                File targetDll = new File(exeDir, dllName);
                if (!targetDll.exists()) {
                    try (InputStream is = getAssets().open(assetName);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(targetDll)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) >= 0) fos.write(buf, 0, len);
                    }
                    // Empty .orig means restore should delete this injected DLL.
                    new File(targetDll.getAbsolutePath() + ".orig").createNewFile();
                    Log.d("XServerDisplayActivity",
                            "Injected Goldberg " + dllName + " next to " + gameExe.getName());
                }

                File stubFile = new File(exeDir, stubName);
                if (!stubFile.exists()) {
                    try (InputStream is = getAssets().open(stubAsset);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(stubFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) >= 0) fos.write(buf, 0, len);
                    }
                    Log.d("XServerDisplayActivity",
                            "Injected steamclient stub " + stubName + " next to " + gameExe.getName());
                }

                // Some games bypass search order with LoadLibrary("Steam\\steamclient64.dll").
                File gameSteamDir = new File(exeDir, "Steam");
                if (gameSteamDir.exists() && gameSteamDir.isDirectory()) {
                    File embeddedClient = new File(gameSteamDir, stubName);
                    if (embeddedClient.exists()) {
                        File backupClient = new File(gameSteamDir, stubName + ".orig");
                        if (!backupClient.exists()) {
                            FileUtils.copy(embeddedClient, backupClient);
                        }
                        
                        embeddedClient.delete();
                        try (InputStream is = getAssets().open(stubAsset);
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(embeddedClient)) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = is.read(buf)) >= 0) fos.write(buf, 0, len);
                        }
                        Log.w("XServerDisplayActivity", "Intercepted explicit embedded Steam client: " + embeddedClient.getAbsolutePath());
                        
                        if (backupPaths != null && appDirPath != null) {
                            String relPath = backupClient.getAbsolutePath();
                            if (relPath.startsWith(appDirPath)) {
                                relPath = relPath.substring(appDirPath.length());
                                if (relPath.startsWith("/")) relPath = relPath.substring(1);
                            }
                            backupPaths.add(relPath);
                        }
                        
                        SteamUtils.writeCompleteSettingsDir(gameSteamDir,
                                Integer.parseInt(shortcut.getExtra("app_id")),
                                language, isOffline, useSteamInput, ticketBase64);
                    }
                }

                SteamUtils.writeCompleteSettingsDir(exeDir,
                        Integer.parseInt(shortcut.getExtra("app_id")),
                        language, isOffline, useSteamInput, ticketBase64);

                if (backupPaths != null && appDirPath != null) {
                    String relPath = targetDll.getAbsolutePath();
                    if (relPath.startsWith(appDirPath)) {
                        relPath = relPath.substring(appDirPath.length());
                        if (relPath.startsWith("/")) relPath = relPath.substring(1);
                    }
                    backupPaths.add(relPath);
                }
            } else {
                Log.w("XServerDisplayActivity", "Could not find game exe to inject steam_api DLL");
            }
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Failed to inject steam_api DLL for no-DLL game", e);
        }
    }

    private void replaceSteamApiDlls(File gameDir, String appDirPath, String language,
            boolean isOffline, boolean useSteamInput, String ticketBase64) {
        if (gameDir == null || !gameDir.exists()) return;

        java.util.List<String> backupPaths = new java.util.ArrayList<>();
        replaceSteamApiDllsRecursive(gameDir, appDirPath, language, isOffline,
                useSteamInput, ticketBase64, backupPaths);

        // Games without steam_api*.dll need an injected hook next to the exe.
        if (backupPaths.isEmpty()) {
            injectSteamApiIfMissing(gameDir, appDirPath, language, isOffline, useSteamInput, ticketBase64, backupPaths);
        }

        if (!backupPaths.isEmpty()) {
            try {
                java.util.Collections.sort(backupPaths);
                File origPathFile = new File(appDirPath, "orig_dll_path.txt");
                FileUtils.writeString(origPathFile, android.text.TextUtils.join(System.lineSeparator(), backupPaths));
                Log.d("XServerDisplayActivity", "Wrote " + backupPaths.size() + " DLL backup paths to orig_dll_path.txt");
            } catch (Exception e) {
                Log.w("XServerDisplayActivity", "Failed to write orig_dll_path.txt", e);
            }
        }
    }

    private boolean hasSteamApiDllInTree(File dir) {
        if (dir == null || !dir.exists()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().equals("steam_settings") && hasSteamApiDllInTree(file)) return true;
            } else {
                String name = file.getName().toLowerCase();
                if (name.equals("steam_api.dll") || name.equals("steam_api64.dll")) return true;
            }
        }
        return false;
    }

    private boolean isExe64Bit(File exeFile) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(exeFile, "r")) {
            raf.seek(0x3C);
            int peOffset = Integer.reverseBytes(raf.readInt());
            raf.seek(peOffset + 4);
            int machine = Short.reverseBytes(raf.readShort()) & 0xFFFF;
            return machine == 0x8664 || machine == 0xAA64;
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Could not determine exe architecture, assuming x64", e);
            return true;
        }
    }

    private void replaceSteamApiDllsRecursive(File dir, String appDirPath, String language,
            boolean isOffline, boolean useSteamInput, String ticketBase64,
            java.util.List<String> backupPaths) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        boolean hasSteamDll = false;
        for (File file : files) {
            if (file.isDirectory()) continue;
            String name = file.getName().toLowerCase();
            if (!name.equals("steam_api.dll") && !name.equals("steam_api64.dll")) continue;

            hasSteamDll = true;
            String assetName = name.equals("steam_api64.dll")
                    ? "steampipe/steam_api64.dll"
                    : "steampipe/steam_api.dll";

            try {
                generateSteamInterfacesFromDll(dir, file);

                File backup = new File(file.getParent(), file.getName() + ".orig");
                if (!backup.exists()) {
                    FileUtils.copy(file, backup);
                    Log.d("XServerDisplayActivity", "Backed up original: " + file.getName() + " as .orig");
                }
                String relPath = backup.getAbsolutePath();
                if (relPath.startsWith(appDirPath)) {
                    relPath = relPath.substring(appDirPath.length());
                    if (relPath.startsWith("/")) relPath = relPath.substring(1);
                }
                backupPaths.add(relPath);

                file.delete();
                file.createNewFile();
                try (InputStream is = getAssets().open(assetName);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) >= 0) fos.write(buf, 0, len);
                }
                Log.d("XServerDisplayActivity", "Replaced " + file.getName() + " at " + file.getAbsolutePath());

                // Experimental steam_api DLLs need matching steamclient stubs.
                String stubAsset = name.equals("steam_api64.dll")
                        ? "steampipe/steamclient64.dll"
                        : "steampipe/steamclient.dll";
                String stubName = name.equals("steam_api64.dll")
                        ? "steamclient64.dll"
                        : "steamclient.dll";
                File stubFile = new File(dir, stubName);
                if (!stubFile.exists()) {
                    try (InputStream stubIs = getAssets().open(stubAsset);
                         java.io.FileOutputStream stubFos = new java.io.FileOutputStream(stubFile)) {
                        byte[] stubBuf = new byte[8192];
                        int stubLen;
                        while ((stubLen = stubIs.read(stubBuf)) >= 0) stubFos.write(stubBuf, 0, stubLen);
                    }
                    Log.d("XServerDisplayActivity", "Copied steamclient stub " + stubName + " next to " + file.getName());
                }
            } catch (Exception e) {
                Log.e("XServerDisplayActivity", "Failed to replace " + file.getName(), e);
            }
        }

        if (hasSteamDll) {
            SteamUtils.writeCompleteSettingsDir(dir,
                    Integer.parseInt(shortcut.getExtra("app_id")),
                    language, isOffline, useSteamInput, ticketBase64);
        }

        for (File file : files) {
            if (file.isDirectory() && !file.getName().equals("steam_settings")) {
                replaceSteamApiDllsRecursive(file, appDirPath, language, isOffline,
                        useSteamInput, ticketBase64, backupPaths);
            }
        }
    }

    private void setupSteamSettingsForAllDirs(File dir, int appId, String language,
            boolean isOffline, boolean useSteamInput, String ticketBase64) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        boolean hasSteamDll = false;
        for (File file : files) {
            if (!file.isDirectory()) {
                String name = file.getName().toLowerCase();
                if (name.equals("steam_api.dll") || name.equals("steam_api64.dll")) {
                    hasSteamDll = true;
                }
            }
        }

        if (hasSteamDll) {
            SteamUtils.writeCompleteSettingsDir(dir, appId, language, isOffline, useSteamInput, ticketBase64);
        }

        for (File file : files) {
            if (file.isDirectory() && !file.getName().equals("steam_settings")) {
                setupSteamSettingsForAllDirs(file, appId, language, isOffline, useSteamInput, ticketBase64);
            }
        }
    }

    // Backfill steamclient stubs for older steam_api replacements.
    private void copySteamclientStubs(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().equals("steam_settings")) copySteamclientStubs(file);
                continue;
            }
            String name = file.getName().toLowerCase();
            if (!name.equals("steam_api.dll") && !name.equals("steam_api64.dll")) continue;

            String stubAsset = name.equals("steam_api64.dll")
                    ? "steampipe/steamclient64.dll" : "steampipe/steamclient.dll";
            String stubName = name.equals("steam_api64.dll")
                    ? "steamclient64.dll" : "steamclient.dll";
            File stubFile = new File(dir, stubName);
            if (!stubFile.exists()) {
                try (InputStream is = getAssets().open(stubAsset);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(stubFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) >= 0) fos.write(buf, 0, len);
                    Log.d("XServerDisplayActivity", "Copied missing steamclient stub " + stubName + " to " + dir.getAbsolutePath());
                } catch (Exception e) {
                    Log.e("XServerDisplayActivity", "Failed to copy steamclient stub " + stubName, e);
                }
            }
        }
    }

    // Restore real steam_api DLLs when leaving Goldberg for ColdClient.
    private void restoreSteamApiDlls(File gameDir) {
        if (gameDir == null || !gameDir.exists()) return;

        File[] files = gameDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().equals("steam_settings")) {
                    restoreSteamApiDlls(file);
                }
            } else {
                String name = file.getName().toLowerCase();
                if (name.equals("steam_api.dll.orig") || name.equals("steam_api64.dll.orig")) {
                    try {
                        String originalName = file.getName().substring(0, file.getName().length() - ".orig".length());
                        File target = new File(file.getParent(), originalName);

                        if (target.exists()) target.delete();
                        if (file.length() == 0) {
                            // 0-byte .orig means delete the injected DLL.
                            Log.d("XServerDisplayActivity", "Removed injected target " + originalName);
                        } else {
                            FileUtils.copy(file, target);
                        }

                        String stubName = name.equals("steam_api64.dll.orig")
                                ? "steamclient64.dll" : "steamclient.dll";
                        File stub = new File(file.getParent(), stubName);
                        if (stub.exists() && stub.length() < 200_000) {
                            stub.delete();
                            Log.d("XServerDisplayActivity", "Removed steamclient stub " + stubName);
                        }

                        Log.d("XServerDisplayActivity", "Restored original " + originalName + " from .orig backup");
                    } catch (Exception e) {
                        Log.e("XServerDisplayActivity", "Failed to restore " + file.getName(), e);
                    }
                }
            }
        }
    }

    private void normalizeSyncEnvVars(com.winlator.cmod.runtime.wine.EnvVars envVars) {
        envVars.remove("WINEFSYNC");
        envVars.put("PROTON_NO_FSYNC", "1");

        String esyncVal = envVars.get("WINEESYNC");
        boolean esyncOff = "0".equals(esyncVal) || "false".equalsIgnoreCase(esyncVal);
        boolean ntSync = "1".equals(envVars.get("WINENTSYNC"))
                || "1".equals(envVars.get("PROTON_USE_NTSYNC"));

        if (ntSync) {
            envVars.put("WINENTSYNC", "1");
            envVars.put("PROTON_USE_NTSYNC", "1");
        } else {
            envVars.remove("WINENTSYNC");
            envVars.remove("PROTON_USE_NTSYNC");
        }

        if (esyncOff) {
            envVars.remove("WINEESYNC");
            envVars.remove("WINEESYNC_WINLATOR");
            envVars.put("PROTON_NO_ESYNC", "1");
        } else {
            envVars.put("WINEESYNC", "1");
            envVars.remove("PROTON_NO_ESYNC");
        }
    }

    private void runPreGameSetup(GuestProgramLauncherComponent launcher,
                                  boolean needsUnpacking, boolean unpackFiles) {
        boolean monoReady = installMonoIfNeeded(launcher);

        installGeckoIfNeeded(launcher);

        installRedistributablesIfNeeded(launcher);

        if (!unpackFiles) {
            Log.d("XServerDisplayActivity",
                    "Skipping Steamless: 'Unpack Files' shortcut toggle is OFF");
            return;
        }
        if (!monoReady) {
            Log.w("XServerDisplayActivity", "Skipping Steamless — Mono not installed yet, will retry next launch");
            return;
        }
        if (isSteamUnpackAlreadyHandled()) {
            Log.d("XServerDisplayActivity", "Skipping Steamless/unpack check; executable already handled");
            return;
        }
        if (doesUnpackedExeExist()) {
            ensureUnpackedExeActive();
        } else {
            runSteamlessOnExe(launcher);
        }
    }

    private boolean isSteamUnpackAlreadyHandled() {
        if (shortcut == null || !"STEAM".equals(shortcut.getExtra("game_source"))) return false;
        try {
            int appId = Integer.parseInt(shortcut.getExtra("app_id"));
            String gameInstallPath = resolveSteamGameInstallPath(appId);
            if (gameInstallPath == null || gameInstallPath.isEmpty()) return false;

            SteamExecutableInfo executableInfo = resolveSteamExecutableInfo(appId, gameInstallPath);
            if (executableInfo == null) return false;

            File unpackedExe = new File(gameInstallPath, executableInfo.relativePath + ".unpacked.exe");
            File originalExe = new File(gameInstallPath, executableInfo.relativePath + ".original.exe");
            if (MarkerUtils.INSTANCE.hasMarker(gameInstallPath, Marker.STEAM_DRM_PATCHED)
                    && unpackedExe.exists()
                    && originalExe.exists()) {
                ensureUnpackedExeActive();
                return true;
            }

            File checkedMarker = new File(gameInstallPath, Marker.STEAM_DRM_UNPACK_CHECKED.getFileName());
            String expectedSignature = buildSteamUnpackSignature(executableInfo);
            String actualSignature = checkedMarker.exists() ? FileUtils.readString(checkedMarker) : null;
            return expectedSignature.equals(actualSignature);
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Steamless handled-state check failed", e);
            return false;
        }
    }

    private void markSteamUnpackChecked(int appId, String gameInstallPath, String executablePath) {
        try {
            File exe = new File(gameInstallPath, executablePath.replace('\\', '/'));
            if (!exe.exists()) return;
            SteamExecutableInfo executableInfo =
                    new SteamExecutableInfo(executablePath.replace('\\', '/'), exe);
            File checkedMarker = new File(gameInstallPath, Marker.STEAM_DRM_UNPACK_CHECKED.getFileName());
            FileUtils.writeString(checkedMarker, buildSteamUnpackSignature(executableInfo));
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to write Steamless checked marker", e);
        }
    }

    private SteamExecutableInfo resolveSteamExecutableInfo(int appId, String gameInstallPath) {
        String executablePath = resolveShortcutSteamExecutablePath(gameInstallPath);
        if (executablePath == null || executablePath.isEmpty()) {
            executablePath = container.getExecutablePath();
        }
        if (executablePath == null || executablePath.isEmpty()) {
            executablePath = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getInstalledExe(appId);
        }
        if (executablePath == null || executablePath.isEmpty()) return null;

        String relativePath = executablePath.replace('\\', '/');
        File exe = new File(gameInstallPath, relativePath);
        if (!exe.exists()) return null;
        return new SteamExecutableInfo(relativePath, exe);
    }

    private String buildSteamUnpackSignature(SteamExecutableInfo executableInfo) {
        return executableInfo.relativePath + "\n"
                + executableInfo.file.length() + "\n"
                + executableInfo.file.lastModified() + "\n";
    }

    private void ensureUnpackedExeActive() {
        if (shortcut == null || !"STEAM".equals(shortcut.getExtra("game_source"))) return;
        try {
            int appId = Integer.parseInt(shortcut.getExtra("app_id"));
            String gameInstallPath = resolveSteamGameInstallPath(appId);
            if (gameInstallPath == null || gameInstallPath.isEmpty()) return;

            String executablePath = resolveShortcutSteamExecutablePath(gameInstallPath);
            if (executablePath == null || executablePath.isEmpty()) {
                executablePath = container.getExecutablePath();
            }
            if (executablePath == null || executablePath.isEmpty()) {
                executablePath = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getInstalledExe(appId);
            }
            if (executablePath == null || executablePath.isEmpty()) return;

            String unixPath = executablePath.replace('\\', '/');
            File exe = new File(gameInstallPath, unixPath);
            File unpackedExe = new File(gameInstallPath, unixPath + ".unpacked.exe");
            File originalExe = new File(gameInstallPath, unixPath + ".original.exe");

            // Mode switches can restore the original; file-size checks are unreliable here.
            if (unpackedExe.exists() && originalExe.exists()) {
                java.nio.file.Files.copy(unpackedExe.toPath(), exe.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Log.d("XServerDisplayActivity", "Restored unpacked exe (was reverted by mode switch)");
            }
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "ensureUnpackedExeActive failed", e);
        }
    }

    private boolean doesUnpackedExeExist() {
        if (shortcut == null || !"STEAM".equals(shortcut.getExtra("game_source"))) return false;
        try {
            int appId = Integer.parseInt(shortcut.getExtra("app_id"));
            String gameInstallPath = resolveSteamGameInstallPath(appId);
            if (gameInstallPath == null || gameInstallPath.isEmpty()) return false;

            String executablePath = resolveShortcutSteamExecutablePath(gameInstallPath);
            if (executablePath == null || executablePath.isEmpty()) {
                executablePath = container.getExecutablePath();
            }
            if (executablePath == null || executablePath.isEmpty()) {
                executablePath = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getInstalledExe(appId);
            }
            if (executablePath == null || executablePath.isEmpty()) return false;

            String unixPath = executablePath.replace('\\', '/');
            File unpackedExe = new File(gameInstallPath, unixPath + ".unpacked.exe");
            return unpackedExe.exists();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean installMonoIfNeeded(GuestProgramLauncherComponent launcher) {
        // Any installed Mono is kept as-is; prefix repair clears the marker to force a reinstall.
        String installedVersion = container.getExtra("mono_version", null);
        if (installedVersion != null) {
            Log.d("XServerDisplayActivity", "Mono v" + installedVersion + " already installed in container " + container.id + ", skipping");
            return true;
        }
        if (hasInstalledComponentPrefix("mono")) {
            Log.d("XServerDisplayActivity", "Mono already installed via components in container " + container.id + ", skipping");
            return true;
        }

        String winePath = wineInfo != null ? wineInfo.path : null;

        String requiredVersion = SteamClientManager.detectRequiredMonoVersion(this, winePath);
        if (requiredVersion == null) {
            Log.w("XServerDisplayActivity", "Could not detect required Mono version, skipping");
            return false;
        }

        String monoWinePath = SteamClientManager.getMonoMsiWinePath(this, winePath);
        if (monoWinePath == null) {
            Log.w("XServerDisplayActivity", "Mono MSI not available (no internet?), will retry next launch");
            return false;
        }

        // The MSI actually resolved may be a fallback version; record what really got installed.
        String actualVersion = requiredVersion;
        java.util.regex.Matcher monoMsiMatcher =
                java.util.regex.Pattern.compile("wine-mono-(\\d+\\.\\d+\\.\\d+)").matcher(monoWinePath);
        if (monoMsiMatcher.find()) actualVersion = monoMsiMatcher.group(1);
        if (!actualVersion.equals(requiredVersion)) {
            Log.w("XServerDisplayActivity", "Mono fallback: required v" + requiredVersion
                    + " but installing v" + actualVersion + " (" + monoWinePath + ")");
        }

        try {
            Log.d("XServerDisplayActivity", "Installing Wine Mono v" + actualVersion
                    + " (" + monoWinePath + ") in container " + container.id + "...");
            String monoCmd = "wine msiexec /i " + monoWinePath + " && wineserver -k";
            launcher.execShellCommand(monoCmd);
            container.putExtra("mono_installed", "true");
            container.putExtra("mono_version", actualVersion);
            container.saveData();
            Log.d("XServerDisplayActivity", "Mono v" + actualVersion + " installed in container " + container.id);
            return true;
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Mono msiexec failed, will retry next launch", e);
            return false;
        }
    }

    private boolean hasInstalledComponentPrefix(String prefix) {
        for (String name : com.winlator.cmod.runtime.content.component.ComponentInstaller
                .installedComponents(container)) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private void installGeckoIfNeeded(GuestProgramLauncherComponent launcher) {
        String installedGecko = container.getExtra("gecko_version", null);
        if (installedGecko != null) {
            Log.d("XServerDisplayActivity", "Gecko v" + installedGecko + " already installed in container "
                    + container.id + ", skipping");
            return;
        }
        if (hasInstalledComponentPrefix("gecko")) {
            Log.d("XServerDisplayActivity", "Gecko already installed via components in container "
                    + container.id + ", skipping");
            return;
        }
        String geckoVersion = SteamClientManager.GECKO_VERSION;

        java.util.List<String> geckoWinePaths = SteamClientManager.getGeckoMsiWinePaths(this);
        if (geckoWinePaths.size() < 2) {
            Log.w("XServerDisplayActivity", "Gecko MSIs not available (no internet?), will retry next launch");
            return;
        }

        try {
            Log.d("XServerDisplayActivity", "Installing Wine Gecko v" + geckoVersion
                    + " in container " + container.id + "...");
            StringBuilder geckoCmd = new StringBuilder();
            for (String p : geckoWinePaths) {
                if (geckoCmd.length() > 0) geckoCmd.append(" && ");
                geckoCmd.append("wine msiexec /i ").append(p);
            }
            geckoCmd.append(" && wineserver -k");
            launcher.execShellCommand(geckoCmd.toString());
            container.putExtra("gecko_version", geckoVersion);
            container.saveData();
            Log.d("XServerDisplayActivity", "Gecko v" + geckoVersion + " installed in container " + container.id);
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Gecko msiexec failed, will retry next launch", e);
        }
    }

    // Installs _CommonRedist once per game/container.
    private void installRedistributablesIfNeeded(GuestProgramLauncherComponent launcher) {
        if (shortcut == null || !"STEAM".equals(shortcut.getExtra("game_source"))) return;
        if (steamCloudHandledByAgent()) {
            Log.i("XServerDisplayActivity",
                    "Redistributables skipped here — the Steam Launcher agent runs the app's "
                            + "installscript.vdf entries inside the prefix, honours their "
                            + "hasrunkey, and reads installer exit codes");
            return;
        }

        int appId;
        try {
            appId = Integer.parseInt(shortcut.getExtra("app_id"));
        } catch (Exception e) {
            return;
        }

        String redistKey = "redist_" + appId;
        String redistInstalled = container.getExtra(redistKey, "false");
        if ("true".equals(redistInstalled)) {
            Log.d("XServerDisplayActivity", "Redistributables for appId=" + appId
                    + " already installed in container " + container.id + ", skipping");
            return;
        }

        String gameInstallPath = resolveSteamGameInstallPath(appId);
        if (gameInstallPath == null || gameInstallPath.isEmpty()) return;

        File commonRedistDir = new File(gameInstallPath, "_CommonRedist");
        if (!commonRedistDir.exists() || !commonRedistDir.isDirectory()) {
            Log.d("XServerDisplayActivity", "No _CommonRedist found for appId=" + appId
                    + " at " + commonRedistDir.getPath());
            container.putExtra(redistKey, "true");
            container.saveData();
            return;
        }

        Log.d("XServerDisplayActivity", "Installing redistributables for appId=" + appId
                + " in container " + container.id + "...");

        int installed = 0;
        try {
            File[] categories = commonRedistDir.listFiles();
            if (categories != null) {
                for (File category : categories) {
                    if (!category.isDirectory()) continue;
                    File[] versions = category.listFiles();
                    if (versions == null) continue;
                    for (File versionDir : versions) {
                        if (!versionDir.isDirectory()) continue;
                        File[] exes = versionDir.listFiles((dir, name) ->
                                name.toLowerCase().endsWith(".exe"));
                        if (exes == null || exes.length == 0) continue;

                        for (File exe : exes) {
                            String exeName = exe.getName().toLowerCase();
                            if (exeName.startsWith("unins") || exeName.equals("detect.exe")) continue;

                            String winPath = WineUtils.getWindowsPath(container, exe.getAbsolutePath());

                            try {
                                Log.d("XServerDisplayActivity", "Running redistributable: " + winPath);
                                String cmd;
                                if (exeName.contains("dxsetup")) {
                                    cmd = "wine \"" + winPath + "\" /silent";
                                } else if (exeName.contains("vc_redist") || exeName.contains("vcredist")) {
                                    cmd = "wine \"" + winPath + "\" /quiet /norestart";
                                } else if (exeName.endsWith(".msi")) {
                                    cmd = "wine msiexec /i \"" + winPath + "\" /quiet /norestart";
                                } else {
                                    cmd = "wine \"" + winPath + "\" /quiet /norestart";
                                }
                                launcher.execShellCommand(cmd);
                                installed++;
                            } catch (Exception e) {
                                Log.w("XServerDisplayActivity",
                                        "Redistributable install failed: " + winPath, e);
                            }
                        }
                    }
                }
            }

            if (installed > 0) {
                try {
                    launcher.execShellCommand("wineserver -k");
                } catch (Exception e) {
                    Log.w("XServerDisplayActivity", "wineserver -k failed after redist install", e);
                }
            }

            Log.d("XServerDisplayActivity", "Installed " + installed
                    + " redistributable(s) for appId=" + appId + " in container " + container.id);
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Redistributable installation failed", e);
        }

        container.putExtra(redistKey, "true");
        container.saveData();
    }

    private void runSteamlessOnExe(GuestProgramLauncherComponent launcher) {
        if (shortcut == null || !"STEAM".equals(shortcut.getExtra("game_source"))) return;
        int appId;
        try {
            appId = Integer.parseInt(shortcut.getExtra("app_id"));
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Invalid app_id for Steamless", e);
            return;
        }

        String gameInstallPath = resolveSteamGameInstallPath(appId);
        if (gameInstallPath == null || gameInstallPath.isEmpty()) return;

        File steamlessDir = new File(imageFs.getRootDir(), "Steamless");
        File steamlessCli = new File(steamlessDir, "Steamless.CLI.exe");
        File pluginsDir = new File(steamlessDir, "Plugins");
        if (!steamlessCli.exists() || !pluginsDir.exists()) {
            try {
                steamlessDir.mkdirs();
                TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD,
                        this, "extras.tzst", imageFs.getRootDir());
                com.winlator.cmod.shared.io.FileUtils.chmod(steamlessCli, 0755);
                Log.d("XServerDisplayActivity", "Extracted Steamless CLI + Plugins to " + steamlessDir);
            } catch (Exception e) {
                Log.e("XServerDisplayActivity", "Failed to extract Steamless", e);
                return;
            }
        }

        if (!pluginsDir.exists() || pluginsDir.list() == null || pluginsDir.list().length == 0) {
            Log.e("XServerDisplayActivity", "Steamless Plugins/ directory is missing or empty — cannot unpack");
            return;
        }

        String executablePath = resolveShortcutSteamExecutablePath(gameInstallPath);
        if (executablePath == null || executablePath.isEmpty()) {
            executablePath = container.getExecutablePath();
        }
        if (executablePath == null || executablePath.isEmpty()) {
            executablePath = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getInstalledExe(appId);
        }
        if (executablePath == null || executablePath.isEmpty()) {
            Log.w("XServerDisplayActivity", "No executable path found for Steamless");
            return;
        }

        File batchFile = null;
        try {
            File hostExe = new File(gameInstallPath, executablePath.replace('\\', '/'));

            String windowsPath = com.winlator.cmod.runtime.wine.WineUtils.getDriveCGameWindowsPath(
                    container, "STEAM", gameInstallPath, hostExe.getAbsolutePath());
            if (windowsPath == null || windowsPath.isEmpty()) {
                windowsPath = com.winlator.cmod.runtime.wine.WineUtils.hostPathToRootWinePath(container, hostExe.getAbsolutePath());
            }
            Log.d("XServerDisplayActivity", "Steamless: resolved windowsPath=" + windowsPath
                    + " (hostExe=" + hostExe.getAbsolutePath() + ")");

            batchFile = new File(imageFs.getRootDir(), "tmp/steamless_wrapper.bat");
            batchFile.getParentFile().mkdirs();
            String batchContent = "@echo off\r\n"
                    + "z:\\Steamless\\Steamless.CLI.exe \"" + windowsPath + "\"\r\n"
                    + "echo STEAMLESS_EXIT_CODE=%ERRORLEVEL%\r\n";
            com.winlator.cmod.shared.io.FileUtils.writeString(batchFile, batchContent);

            Log.d("XServerDisplayActivity", "Steamless: running on " + windowsPath + " (exe=" + executablePath + ")");
            String slCmd = "wine z:\\tmp\\steamless_wrapper.bat";
            String slOutput = launcher.execShellCommand(slCmd);
            Log.d("XServerDisplayActivity", "Steamless CLI output: " + slOutput);

            boolean steamlessSuccess = slOutput != null
                    && slOutput.toLowerCase().contains("successfully unpacked");

            String unixPath = executablePath.replace('\\', '/');
            File exe = new File(gameInstallPath, unixPath);
            File unpackedExe = new File(gameInstallPath, unixPath + ".unpacked.exe");
            File originalExe = new File(gameInstallPath, unixPath + ".original.exe");

            Log.d("XServerDisplayActivity", "Steamless: checking exe=" + exe.getAbsolutePath()
                    + " exists=" + exe.exists() + " unpacked=" + unpackedExe.getAbsolutePath()
                    + " exists=" + unpackedExe.exists() + " cliSuccess=" + steamlessSuccess);

            if (steamlessSuccess && exe.exists() && unpackedExe.exists()) {
                if (!originalExe.exists()) {
                    java.nio.file.Files.copy(exe.toPath(), originalExe.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Log.d("XServerDisplayActivity", "Steamless: backed up original exe as " + originalExe.getName());
                }
                java.nio.file.Files.copy(unpackedExe.toPath(), exe.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Log.d("XServerDisplayActivity", "Steamless: swapped exe with unpacked version");

                com.winlator.cmod.feature.stores.steam.utils.MarkerUtils.INSTANCE.addMarker(
                        gameInstallPath, com.winlator.cmod.feature.stores.steam.enums.Marker.STEAM_DRM_PATCHED);

                launcher.execShellCommand("wineserver -k");
                container.setNeedsUnpacking(false);
                container.saveData();
            } else if (!steamlessSuccess && !unpackedExe.exists()) {
                // Stop retrying only when Steamless confirms no unpacker applies.
                boolean allUnpackersFailed = slOutput != null
                        && slOutput.toLowerCase().contains("all unpackers failed");

                if (allUnpackersFailed) {
                    Log.w("XServerDisplayActivity",
                            "Steamless: game does not use SteamStub DRM (all unpackers failed). "
                            + "Disabling Legacy DRM for this game to avoid future overhead.");
                    launcher.execShellCommand("wineserver -k");
                    markSteamUnpackChecked(appId, gameInstallPath, executablePath);
                    container.setNeedsUnpacking(false);
                    container.saveData();
                } else {
                    Log.w("XServerDisplayActivity",
                            "Steamless: transient failure (CLI ran but no .unpacked.exe), will retry next launch");
                }
            } else if (!steamlessSuccess && unpackedExe.exists()) {
                if (!originalExe.exists() && exe.exists()) {
                    java.nio.file.Files.copy(exe.toPath(), originalExe.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                java.nio.file.Files.copy(unpackedExe.toPath(), exe.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Log.d("XServerDisplayActivity", "Steamless: used existing .unpacked.exe from prior run");

                com.winlator.cmod.feature.stores.steam.utils.MarkerUtils.INSTANCE.addMarker(
                        gameInstallPath, com.winlator.cmod.feature.stores.steam.enums.Marker.STEAM_DRM_PATCHED);
                launcher.execShellCommand("wineserver -k");
                container.setNeedsUnpacking(false);
                container.saveData();
            }
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Steamless execution failed, will retry next launch", e);
        } finally {
            if (batchFile != null && batchFile.exists()) batchFile.delete();
        }
    }

    public XServer getXServer() {
        return xServer;
    }

    private void generateSteamInterfacesFile(File dir, String dllName) {
        File interfacesFile = new File(dir, "steam_interfaces.txt");
        if (interfacesFile.exists()) return;

        File dllToScan = new File(dir, dllName + ".orig");
        if (!dllToScan.exists()) {
            dllToScan = new File(dir, dllName + ".original");
        }
        if (!dllToScan.exists()) {
            dllToScan = new File(dir, dllName);
        }
        if (!dllToScan.exists()) return;

        generateSteamInterfacesFromDll(dir, dllToScan);
    }
    
    private void setSteamClientVisibility(boolean visible) {
        setSteamClientVisibility(visible, false);
    }

    private void setSteamClientVisibility(boolean visible, boolean coldClientMode) {
        if (container == null) return;
        String requested = container.id + ":" + visible + ":" + coldClientMode;
        if (requested.equals(appliedSteamClientVisibility)) {
            Log.d("XServerDisplayActivity",
                    "Steam client visibility already applied this session (" + requested + "), skipping");
            return;
        }
        appliedSteamClientVisibility = requested;
        updateSteamDirectoryVisibility(visible, coldClientMode);
        updateSteamRegistryVisibility(visible);
    }

    private void updateSteamDirectoryVisibility(boolean visible) {
        updateSteamDirectoryVisibility(visible, false);
    }

    private void updateSteamDirectoryVisibility(boolean visible, boolean coldClientMode) {
        if (container == null) return;

        File steamLink = new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam");
        File pristineSteamStore = getSharedSteamStore();
        File coldClientStore = getSharedColdClientStore();
        File target = coldClientMode ? coldClientStore : pristineSteamStore;
        File previousSteamStore = new File(imageFs.getRootDir(), PREVIOUS_STEAM_CLIENT_STORE_RELATIVE_PATH);
        File previousContainerSteamStore = new File(container.getRootDir(), PREVIOUS_CONTAINER_STEAM_CLIENT_STORE_RELATIVE_PATH);
        File legacySteamStore = new File(container.getRootDir(), LEGACY_STEAM_CLIENT_STORE_RELATIVE_PATH);

        try {
            moveSteamDirectoryIntoBackingStore(steamLink, pristineSteamStore);
            migrateLegacySteamStoreIfNeeded(previousSteamStore, pristineSteamStore);
            migrateLegacySteamStoreIfNeeded(previousContainerSteamStore, pristineSteamStore);
            migrateLegacySteamStoreIfNeeded(legacySteamStore, pristineSteamStore);

            if (visible) {
                if (!target.exists()) {
                    target.mkdirs();
                }
                if (steamLink.exists()) {
                    FileUtils.delete(steamLink);
                }
                FileUtils.symlink(target, steamLink);
                Log.d("XServerDisplayActivity",
                        "Steam symlink → " + (coldClientMode ? "coldclient-store" : "steam-client-store")
                                + " at " + steamLink.getAbsolutePath());
            } else {
                if (steamLink.exists()) {
                    FileUtils.delete(steamLink);
                    Log.d("XServerDisplayActivity", "Removed visible Steam root for non-Steam launch");
                }
            }
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Error updating Steam directory visibility", e);
        }
    }

    private File getSharedSteamStore() {
        if (imageFs != null) {
            return new File(imageFs.getRootDir(), STEAM_CLIENT_STORE_RELATIVE_PATH);
        }
        return new File(getFilesDir(), "imagefs/" + STEAM_CLIENT_STORE_RELATIVE_PATH);
    }

    private File getSharedColdClientStore() {
        if (imageFs != null) {
            return new File(imageFs.getRootDir(), COLDCLIENT_STORE_RELATIVE_PATH);
        }
        return new File(getFilesDir(), "imagefs/" + COLDCLIENT_STORE_RELATIVE_PATH);
    }

    private boolean ensureColdClientStore() {
        File cstore = getSharedColdClientStore();
        File loader = new File(cstore, "steamclient_loader_x64.exe");
        File stub = new File(cstore, "steamclient64.dll");
        if (loader.exists() && loader.length() > 0 && stub.exists() && stub.length() > 0) {
            return true;
        }

        if (!SteamBridge.ensureColdClientSupportReady(this)) {
            Log.w("XServerDisplayActivity", "ensureColdClientStore: experimental-drm.tzst not available");
            return false;
        }
        File expFile = new File(getFilesDir(), "experimental-drm.tzst");
        if (!expFile.exists()) {
            Log.w("XServerDisplayActivity", "ensureColdClientStore: experimental-drm.tzst missing from filesDir");
            return false;
        }

        cstore.mkdirs();
        try {
            com.winlator.cmod.shared.io.TarCompressorUtils.extract(
                    com.winlator.cmod.shared.io.TarCompressorUtils.Type.ZSTD,
                    expFile, imageFs.getRootDir(), null);
            Log.d("XServerDisplayActivity",
                    "ensureColdClientStore: extracted experimental-drm.tzst into coldclient sidecar");
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "ensureColdClientStore: extraction failed", e);
            return false;
        }

        return loader.exists() && stub.exists();
    }

    private void migrateLegacySteamStoreIfNeeded(File legacySteamStore, File steamStore) {
        if (legacySteamStore == null || steamStore == null || !legacySteamStore.exists()) return;

        File parentDir = steamStore.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        if (!steamStore.exists()) {
            if (legacySteamStore.renameTo(steamStore)) {
                Log.d("XServerDisplayActivity", "Migrated legacy Steam backing store to hidden location");
                return;
            }

            if (!steamStore.mkdirs()) {
                Log.w("XServerDisplayActivity", "Failed to create hidden Steam backing store during legacy migration");
                return;
            }
        }

        if (!steamStore.isDirectory()) {
            Log.w("XServerDisplayActivity", "Hidden Steam backing store is not a directory");
            return;
        }

        if (!FileUtils.copy(legacySteamStore, steamStore)) {
            Log.w("XServerDisplayActivity", "Failed to copy legacy Steam backing store into hidden location");
            return;
        }

        if (FileUtils.delete(legacySteamStore)) {
            Log.d("XServerDisplayActivity", "Removed legacy Windows-visible Steam backing store");
        } else {
            Log.w("XServerDisplayActivity", "Failed to remove legacy Windows-visible Steam backing store");
        }
    }

    private void moveSteamDirectoryIntoBackingStore(File steamLink, File steamStore) {
        if (steamLink == null || steamStore == null) return;
        if (!steamLink.exists() || FileUtils.isSymlink(steamLink)) return;

        File parentDir = steamStore.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        if (!steamStore.exists()) {
            if (steamLink.renameTo(steamStore)) {
                Log.d("XServerDisplayActivity", "Migrated Steam directory to backing store: " + steamStore.getAbsolutePath());
                return;
            }
            Log.w("XServerDisplayActivity", "Failed to rename Steam directory into backing store, falling back to copy");
        }

        if (!steamStore.exists() && !steamStore.mkdirs()) {
            Log.w("XServerDisplayActivity", "Unable to create Steam backing store: " + steamStore.getAbsolutePath());
            return;
        }

        if (!steamStore.isDirectory()) {
            Log.w("XServerDisplayActivity", "Steam backing store is not a directory: " + steamStore.getAbsolutePath());
            return;
        }

        if (!FileUtils.copy(steamLink, steamStore)) {
            Log.w("XServerDisplayActivity", "Failed to copy Steam directory contents into backing store");
            return;
        }

        if (FileUtils.delete(steamLink)) {
            Log.d("XServerDisplayActivity", "Collapsed visible Steam directory into backing store");
        } else {
            Log.w("XServerDisplayActivity", "Failed to remove visible Steam directory after backing-store copy");
        }
    }

    private void updateSteamRegistryVisibility(boolean visible) {
        if (container == null) return;
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");
        File userBackupFile = new File(container.getRootDir(), ".wine/" + STEAM_USER_REGISTRY_BACKUP_FILE);
        File systemBackupFile = new File(container.getRootDir(), ".wine/" + STEAM_SYSTEM_REGISTRY_BACKUP_FILE);
        if (!visible) {
            try {
                forceHideSteamRegistry(userRegFile, userBackupFile, STEAM_REGISTRY_KEY);
                forceHideSteamRegistry(systemRegFile, systemBackupFile, STEAM_SYSTEM_REGISTRY_KEYS);
            } catch (Exception e) {
                Log.e("XServerDisplayActivity", "Error updating Steam registry visibility", e);
            }
            return;
        }

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (visible) {
                restoreRegistrySubtrees(userRegFile, userBackupFile, STEAM_REGISTRY_KEY);
                restoreRegistrySubtrees(systemRegFile, systemBackupFile, STEAM_SYSTEM_REGISTRY_KEYS);
                registryEditor.removeKey(STEAM_REGISTRY_KEY, true);
                String backupContent = userBackupFile.isFile() ? FileUtils.readString(userBackupFile) : null;
                if (backupContent != null && !backupContent.trim().isEmpty()) {
                    if (registryEditor.appendRawContent(backupContent)) {
                        Log.d("XServerDisplayActivity", "Restored Steam registry subtree from backup");
                    } else {
                        Log.w("XServerDisplayActivity", "Failed to restore Steam registry subtree from backup");
                    }
                } else {
                    registryEditor.setCreateKeyIfNotExist(true);
                    registryEditor.setStringValue(STEAM_REGISTRY_KEY, "SteamExe", STEAM_EXE_PATH);
                    registryEditor.setStringValue(STEAM_REGISTRY_KEY, "SteamPath", STEAM_ROOT_PATH);
                    registryEditor.setStringValue(STEAM_REGISTRY_KEY, "InstallPath", STEAM_ROOT_PATH);

                    String autoLoginUser = PrefManager.INSTANCE.getUsername();
                    if (autoLoginUser != null && !autoLoginUser.isEmpty()) {
                        registryEditor.setStringValue(STEAM_REGISTRY_KEY, "AutoLoginUser", autoLoginUser);
                    } else {
                        registryEditor.removeValue(STEAM_REGISTRY_KEY, "AutoLoginUser");
                    }
                    Log.d("XServerDisplayActivity", "Created default Steam registry subtree");
                }
            }
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Error updating Steam registry visibility", e);
        }
    }

    private void forceHideSteamRegistry(File registryFile, File backupFile, String... keys) {
        String rawRegistry = FileUtils.readString(registryFile);
        if (rawRegistry == null) rawRegistry = "";

        String backupContent = extractRegistrySubtrees(rawRegistry, keys);
        if (!backupContent.trim().isEmpty()) {
            FileUtils.writeString(backupFile, backupContent.trim() + "\n");
            Log.d("XServerDisplayActivity", "Backed up Steam registry subtrees from " + registryFile.getName());
        }

        String sanitizedRegistry = sanitizeSteamRegistryContent(rawRegistry, keys);
        FileUtils.writeString(registryFile, sanitizedRegistry);
        Log.d("XServerDisplayActivity", "Force-sanitized Steam registry state in " + registryFile.getName());
    }

    private String sanitizeSteamRegistryContent(String registryContent, String... keys) {
        String sanitized = removeRegistrySubtrees(registryContent, keys);
        return scrubRegistryLinePatterns(sanitized, STEAM_REGISTRY_LINE_PATTERNS);
    }

    private String scrubRegistryLinePatterns(String content, String... patterns) {
        if (content == null || content.isEmpty() || patterns == null || patterns.length == 0) {
            return content != null ? content : "";
        }
        String[] lines = content.split("\n", -1);
        StringBuilder rebuilt = new StringBuilder();
        for (String line : lines) {
            boolean remove = false;
            String normalizedLine = line.toLowerCase(Locale.ROOT);
            for (String pattern : patterns) {
                if (normalizedLine.contains(pattern)) {
                    remove = true;
                    break;
                }
            }
            if (!remove) {
                rebuilt.append(line).append('\n');
            }
        }
        return rebuilt.toString();
    }

    private void hideRegistrySubtrees(File registryFile, File backupFile, String... keys) {
        String rawRegistry = FileUtils.readString(registryFile);
        if (rawRegistry == null) rawRegistry = "";

        String backupContent = extractRegistrySubtrees(rawRegistry, keys);
        if (!backupContent.trim().isEmpty()) {
            FileUtils.writeString(backupFile, backupContent.trim() + "\n");
            Log.d("XServerDisplayActivity", "Backed up Steam registry subtrees from " + registryFile.getName());
        }

        String strippedRegistry = removeRegistrySubtrees(rawRegistry, keys);
        if (!strippedRegistry.equals(rawRegistry)) {
            FileUtils.writeString(registryFile, strippedRegistry);
            Log.d("XServerDisplayActivity", "Removed Steam registry subtrees from " + registryFile.getName());
        } else {
            Log.d("XServerDisplayActivity", "Steam registry subtrees already hidden in " + registryFile.getName());
        }
    }

    private void restoreRegistrySubtrees(File registryFile, File backupFile, String... keys) {
        String rawRegistry = FileUtils.readString(registryFile);
        if (rawRegistry == null) rawRegistry = "";

        String strippedRegistry = removeRegistrySubtrees(rawRegistry, keys);
        if (!strippedRegistry.equals(rawRegistry)) {
            FileUtils.writeString(registryFile, strippedRegistry);
        }

        if (!backupFile.isFile()) return;
        String backupContent = FileUtils.readString(backupFile);
        if (backupContent == null || backupContent.trim().isEmpty()) return;

        String merged = FileUtils.readString(registryFile);
        if (merged == null) merged = "";
        if (!merged.endsWith("\n") && !merged.isEmpty()) merged += "\n";
        merged += backupContent.trim() + "\n";
        FileUtils.writeString(registryFile, merged);
    }

    private String extractRegistrySubtrees(String registryContent, String... keys) {
        if (registryContent == null || registryContent.isEmpty() || keys == null || keys.length == 0) {
            return "";
        }

        StringBuilder extracted = new StringBuilder();
        for (String key : keys) {
            String subtree = extractRegistrySubtree(registryContent, key);
            if (subtree != null && !subtree.trim().isEmpty()) {
                if (extracted.length() > 0 && extracted.charAt(extracted.length() - 1) != '\n') {
                    extracted.append('\n');
                }
                extracted.append(subtree.trim()).append('\n');
            }
        }
        return extracted.toString();
    }

    private String removeRegistrySubtrees(String registryContent, String... keys) {
        String updated = registryContent != null ? registryContent : "";
        if (keys == null) return updated;
        for (String key : keys) {
            updated = removeRegistrySubtree(updated, key);
        }
        return updated;
    }

    private String extractRegistrySubtree(String registryContent, String key) {
        if (registryContent == null || registryContent.isEmpty() || key == null || key.isEmpty()) {
            return "";
        }

        String escapedKey = key.replace("\\", "\\\\");
        String prefix = "[" + escapedKey;
        StringBuilder extracted = new StringBuilder();
        boolean capturing = false;
        String[] lines = registryContent.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith("[")) {
                if (capturing && !line.startsWith(prefix)) {
                    break;
                }
                if (!capturing && line.startsWith(prefix)) {
                    capturing = true;
                }
            }
            if (capturing) {
                extracted.append(line).append('\n');
            }
        }
        return extracted.toString();
    }

    private String removeRegistrySubtree(String registryContent, String key) {
        if (registryContent == null || registryContent.isEmpty() || key == null || key.isEmpty()) {
            return registryContent != null ? registryContent : "";
        }

        String escapedKey = key.replace("\\", "\\\\");
        String prefix = "[" + escapedKey;
        StringBuilder rebuilt = new StringBuilder();
        boolean capturing = false;
        String[] lines = registryContent.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith("[")) {
                if (capturing && !line.startsWith(prefix)) {
                    capturing = false;
                }
                if (!capturing && line.startsWith(prefix)) {
                    capturing = true;
                }
            }
            if (!capturing) {
                rebuilt.append(line).append('\n');
            }
        }
        return rebuilt.toString();
    }

    private void writeBionicActiveProcessRegistry() {
        try {
            long steamId64 = com.winlator.cmod.feature.stores.steam.utils
                    .PrefManager.INSTANCE.getSteamUserSteamId64();
            int accountId = (int) (steamId64 & 0xFFFFFFFFL);
            File userReg = new File(container.getRootDir(), ".wine/user.reg");
            int steamPid = android.os.Process.myPid();
            try (com.winlator.cmod.runtime.wine.WineRegistryEditor editor =
                         new com.winlator.cmod.runtime.wine.WineRegistryEditor(userReg)) {
                editor.setCreateKeyIfNotExist(true);
                String key = "Software\\Valve\\Steam\\ActiveProcess";
                editor.setDwordValue(key, "ActiveUser", accountId);
                editor.setDwordValue(key, "pid", steamPid);
                editor.setStringValue(key, "SteamClientDll",
                        "C:\\windows\\syswow64\\lsteamclient.dll");
                editor.setStringValue(key, "SteamClientDll64",
                        "C:\\windows\\system32\\lsteamclient.dll");
                editor.setStringValue(key, "Universe", "Public");
            }
            Log.d("XServerDisplayActivity",
                    "Bionic: wrote ActiveProcess registry (ActiveUser=" + accountId
                            + " pid=" + steamPid + ")");
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Bionic: ActiveProcess registry write failed", e);
        }
    }

    private boolean installBionicSteamPathOverlay(Container container, File bionicSteamDir) {
        try {
            File sharedStore = getSharedSteamStore();
            if (!sharedStore.isDirectory()) {
                Log.w("XServerDisplayActivity",
                        "installBionicSteamPathOverlay: shared steam-client-store missing at "
                                + sharedStore.getAbsolutePath()
                                + " — falling back to bridge-in-system32 only (game may fail to "
                                + "find steamclient64.dll because stock steam_api64.dll searches "
                                + "SteamPath first)");
                return false;
            }
            File bridge64Src = new File(container.getRootDir(),
                    ".wine/drive_c/windows/system32/lsteamclient.dll");
            File bridge32Src = new File(container.getRootDir(),
                    ".wine/drive_c/windows/syswow64/lsteamclient.dll");
            if (!bridge64Src.exists()) {
                Log.w("XServerDisplayActivity",
                        "installBionicSteamPathOverlay: bridge missing at "
                                + bridge64Src.getAbsolutePath());
                return false;
            }
            java.nio.file.Path bionicPath = bionicSteamDir.toPath();
            if (java.nio.file.Files.isSymbolicLink(bionicPath)) {
                java.nio.file.Files.delete(bionicPath);
            }
            if (!bionicSteamDir.exists()) {
                bionicSteamDir.mkdirs();
            }
            File[] storeEntries = sharedStore.listFiles();
            int symlinkedCount = 0;
            if (storeEntries != null) {
                for (File entry : storeEntries) {
                    String name = entry.getName();
                    if (name.equalsIgnoreCase("steamclient.dll")
                            || name.equalsIgnoreCase("steamclient64.dll")
                            || name.equalsIgnoreCase("steamapps")) {
                        continue;
                    }
                    File dest = new File(bionicSteamDir, name);
                    if (dest.exists() || java.nio.file.Files.isSymbolicLink(dest.toPath())) {
                        continue;
                    }
                    java.nio.file.Files.createSymbolicLink(
                            dest.toPath(), entry.toPath().toAbsolutePath());
                    ++symlinkedCount;
                }
            }
            File dest64 = new File(bionicSteamDir, "steamclient64.dll");
            FileUtils.copy(bridge64Src, dest64);
            File dest32 = new File(bionicSteamDir, "steamclient.dll");
            if (bridge32Src.exists()) {
                FileUtils.copy(bridge32Src, dest32);
            } else {
                FileUtils.copy(bridge64Src, dest32);
            }
            Log.d("XServerDisplayActivity",
                    "installBionicSteamPathOverlay: " + symlinkedCount + " store entries"
                            + " symlinked, bridge written as steamclient64.dll ("
                            + dest64.length() + "B) + steamclient.dll ("
                            + dest32.length() + "B)");
            return true;
        } catch (Exception e) {
            Log.e("XServerDisplayActivity",
                    "installBionicSteamPathOverlay failed", e);
            return false;
        }
    }

    private void clearBionicActiveProcessRegistry() {
        try {
            File userReg = new File(container.getRootDir(), ".wine/user.reg");
            if (!userReg.exists()) return;
            try (com.winlator.cmod.runtime.wine.WineRegistryEditor editor =
                         new com.winlator.cmod.runtime.wine.WineRegistryEditor(userReg)) {
                String key = "Software\\Valve\\Steam\\ActiveProcess";
                editor.removeValue(key, "SteamClientDll");
                editor.removeValue(key, "SteamClientDll64");
                editor.removeValue(key, "ActiveUser");
                editor.removeValue(key, "pid");
                editor.removeValue(key, "Universe");
            }
            Log.d("XServerDisplayActivity", "Cleared Bionic ActiveProcess registry redirector");
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Failed to clear Bionic ActiveProcess registry", e);
        }
    }

    private void setupSteamEnvironment(int appId, File gameDir) {
        try {
            File winePrefix = container.getRootDir();
            File steamDir = new File(winePrefix, ".wine/drive_c/Program Files (x86)/Steam");
            steamDir.mkdirs();

            File steamappsDir = new File(steamDir, "steamapps");
            File commonDir = new File(steamappsDir, "common");
            commonDir.mkdirs();
            WineUtils.ensureSteamappsCommonSymlink(container, gameDir.getAbsolutePath(),
                    canonicalSteamInstallDir(appId));

            String acfLanguage = PrefManager.INSTANCE.getContainerLanguage();
            String containerLang = container.getExtra("containerLanguage", null);
            if (containerLang != null && !containerLang.isEmpty()) {
                acfLanguage = containerLang;
            }
            SteamUtils.createAppManifest(this, appId, acfLanguage);

            File defaultAcf = new File(imageFs.getRootDir(),
                    ImageFs.WINEPREFIX + "/drive_c/Program Files (x86)/Steam/steamapps/appmanifest_" + appId + ".acf");
            File containerAcf = new File(steamappsDir, "appmanifest_" + appId + ".acf");
            // Refresh the container manifest from the freshly generated one on every launch so
            // newly installed DLC / language changes propagate. The generated manifest is the
            // source of truth (the native launcher rewrites this same file too), so a stale
            // container copy must not be left in place.
            if (defaultAcf.exists()) {
                try {
                    java.nio.file.Files.copy(defaultAcf.toPath(), containerAcf.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Log.d("XServerDisplayActivity", "Synced ACF manifest to container steamapps dir");
                } catch (Exception e) {
                    Log.w("XServerDisplayActivity", "Failed to copy ACF to container steamapps", e);
                }
            }

            ensureSteamLibraryFoldersConfig(steamDir, steamappsDir);

            File steamworksAcf = new File(steamappsDir, "appmanifest_228980.acf");
            if (!steamworksAcf.exists()) {
                String steamworksAcfContent = "\"AppState\"\n" +
                        "{\n" +
                        "\t\"appid\"\t\t\"228980\"\n" +
                        "\t\"universe\"\t\t\"1\"\n" +
                        "\t\"name\"\t\t\"Steamworks Common Redistributables\"\n" +
                        "\t\"StateFlags\"\t\t\"4\"\n" +
                        "\t\"installdir\"\t\t\"Steamworks Shared\"\n" +
                        "\t\"buildid\"\t\t\"1\"\n" +
                        "\t\"BytesToDownload\"\t\t\"0\"\n" +
                        "\t\"BytesDownloaded\"\t\t\"0\"\n" +
                        "}\n";
                FileUtils.writeString(steamworksAcf, steamworksAcfContent);
            }

            long steamIdLong = com.winlator.cmod.feature.stores.steam.utils.PrefManager.INSTANCE.getSteamUserSteamId64();
            String steamId64 = steamIdLong > 0 ? String.valueOf(steamIdLong) : "76561198000000000";
            int steamAccountId = com.winlator.cmod.feature.stores.steam.utils.PrefManager.INSTANCE.getSteamUserAccountId();
            String steamUserDataId = steamAccountId > 0 ? String.valueOf(steamAccountId) : steamId64;

            // Stamp-cache the registry/userdata/local-config edits so warm launches skip the per-launch file-copy / VDF-parse work. Stamp key appId|userDataId — change either and it re-runs.
            File steamEnvStamp = new File(winePrefix,
                    ".wine/drive_c/.wn-steamenv-" + appId + "-" + steamUserDataId + ".stamp");
            String expectedStamp = "v1|" + appId + "|" + steamUserDataId;
            String existingStamp = steamEnvStamp.exists()
                    ? FileUtils.readString(steamEnvStamp).trim() : "";
            boolean steamEnvWarm = expectedStamp.equals(existingStamp);

            if (!steamEnvWarm) {
                try {
                    SteamUtils.autoLoginUserChanges(imageFs);
                    Log.d("XServerDisplayActivity", "autoLoginUserChanges complete");
                } catch (Exception e) {
                    Log.w("XServerDisplayActivity", "autoLoginUserChanges failed, falling back", e);
                }

                skipFirstTimeSteamSetup(winePrefix);
                reconcileSteamUserdata(steamDir, steamUserDataId, steamId64);
                SteamUtils.updateOrModifyLocalConfig(imageFs, container, String.valueOf(appId), steamUserDataId);
                setupLightweightSteamConfig(steamDir, steamUserDataId);

                try {
                    FileUtils.writeString(steamEnvStamp, expectedStamp);
                } catch (Exception e) {
                    Log.w("XServerDisplayActivity",
                            "Failed to write steam-env stamp at " + steamEnvStamp.getPath(), e);
                }
            } else {
                Log.d("XServerDisplayActivity",
                        "Steam env warm-cache hit (appId=" + appId
                                + ", userId=" + steamUserDataId + ") — skipping reconcile + autoLogin");
            }

            boolean planWActiveBootstrapSkip = com.winlator.cmod.feature.stores.steam.utils
                    .PrefManager.INSTANCE.getWnPlanW();
            if (isBionicSteamEnabledForShortcut() && planWActiveBootstrapSkip) {
                try {
                    boolean kicked = com.winlator.cmod.feature.stores.steam.service.SteamService
                            .Companion.kickPlayingSessionIfReadyBlocking(true);
                    Log.i("XServerDisplayActivity",
                            "Steam Launcher: pre-launch kickPlayingSessionIfReady fired="
                                    + kicked);
                } catch (Throwable t) {
                    Log.w("XServerDisplayActivity",
                            "Steam Launcher: pre-launch kickPlayingSessionIfReady failed", t);
                }
                try {
                    com.winlator.cmod.feature.stores.steam.service.SteamService
                            .Companion.bionicHandoffAcquire();
                    Log.i("XServerDisplayActivity",
                            "Steam Launcher: suspended Android wn-session before PlanW launch");
                } catch (Throwable t) {
                    Log.w("XServerDisplayActivity",
                            "Steam Launcher: failed to suspend Android wn-session", t);
                }
                Log.i("XServerDisplayActivity",
                        "Steam Launcher: skipping Android-side WnSteamBootstrap + stage2 "
                        + "diagnostics — Wine-side steam.exe is the sole Steam "
                        + "session (avoids double-logon + the listAchievements "
                        + "native crash)");
            } else if (isBionicSteamEnabledForShortcut()) {
                try {
                    boolean staged = com.winlator.cmod.feature.stores.steam.wnsteam
                            .WnSteamAssetsInstaller.INSTANCE.install(this, container);
                    File libSteamClientSo =
                            new File(imageFs.getRootDir(), "usr/lib/libsteamclient.so");
                    Log.d("XServerDisplayActivity",
                            "Bionic Steam bootstrap: staged=" + staged
                                    + " libsteamclient.so exists=" + libSteamClientSo.exists()
                                    + " (" + libSteamClientSo.getAbsolutePath() + ")");
                    if (libSteamClientSo.exists()) {
                        String bsAccount = com.winlator.cmod.feature.stores.steam.utils
                                .PrefManager.INSTANCE.getUsername();
                        String bsToken = com.winlator.cmod.feature.stores.steam.utils
                                .PrefManager.INSTANCE.getRefreshToken();
                        long bsSteamId = com.winlator.cmod.feature.stores.steam.utils
                                .PrefManager.INSTANCE.getSteamUserSteamId64();
                        File bsHome = new File(imageFs.getRootDir(), "home");
                        Log.d("XServerDisplayActivity",
                                "Bionic Steam bootstrap: account=" + bsAccount
                                        + " tokenLen="
                                        + (bsToken == null ? 0 : bsToken.length())
                                        + " steamId=" + bsSteamId);
                        int rc = com.winlator.cmod.feature.stores.steam.wnsteam
                                .WnSteamBootstrap.INSTANCE.start(
                                        this,
                                        libSteamClientSo.getAbsolutePath(),
                                        bsHome.getAbsolutePath(),
                                        "127.0.0.1:57343",
                                        "127.0.0.1:57344",
                                        new String[0],
                                        bsAccount,
                                        bsToken,
                                        bsSteamId,
                                        appId);
                        Log.d("XServerDisplayActivity",
                                "Bionic Steam bootstrap: start() rc=" + rc
                                        + " appId=" + appId);
                        com.winlator.cmod.feature.stores.steam.wnsteam
                                .WnLibSteamClient.INSTANCE.setAppId(appId);
                        try {
                            com.winlator.cmod.feature.stores.steam.service.SteamService
                                    .prepareLibSteamClientForLaunchBlocking(appId);
                        } catch (Throwable t) {
                            Log.w("XServerDisplayActivity",
                                    "Bionic Steam: prepareLibSteamClientForLaunch failed for app "
                                            + appId, t);
                        }
                        com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamBootstrap bs =
                                com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamBootstrap.INSTANCE;
                        long liveSid = bs.liveSteamId();
                        int  liveApp = bs.currentAppId();
                        Log.d("XServerDisplayActivity",
                                "Bionic Steam bootstrap: live ISteamUser.steamId="
                                        + liveSid + " (prefmgr=" + bsSteamId
                                        + " match=" + (liveSid == bsSteamId)
                                        + ") ISteamUtils.appId=" + liveApp);

                        try {
                            boolean subscribed = bs.isSubscribedApp(appId);
                            int     license   = bs.userHasLicenseForApp(liveSid, appId);
                            boolean installed = bs.isAppInstalled(appId);
                            String  installDir = bs.appInstallDir(appId);
                            int[]   depots    = bs.installedDepots(appId);
                            String  lang      = bs.currentGameLanguage();
                            boolean publicLogged = bs.loggedOnPublic();
                            Log.d("XServerDisplayActivity",
                                    "Bionic stage2 apps/user: subscribed=" + subscribed
                                            + " license=" + license + " (0=ok 1=no 2=noauth)"
                                            + " installed=" + installed
                                            + " installDir=" + installDir
                                            + " depots=" + (depots == null ? 0 : depots.length)
                                            + " lang=" + lang
                                            + " loggedOnPublic=" + publicLogged);

                            boolean cloudAcct = bs.cloudEnabledForAccount();
                            boolean cloudApp  = bs.cloudEnabledForApp();
                            int     cloudCnt  = bs.cloudFileCount();
                            long[]  cloudQ    = bs.cloudQuota();
                            Log.d("XServerDisplayActivity",
                                    "Bionic stage2 cloud: account=" + cloudAcct
                                            + " app=" + cloudApp
                                            + " files=" + cloudCnt
                                            + " quota=" + cloudQ[1] + "/" + cloudQ[0]);

                            int numAch = bs.numAchievements();
                            java.util.List<String> achNames = bs.listAchievements();
                            String firstAch = achNames.isEmpty() ? "(none)" : achNames.get(0);
                            Log.d("XServerDisplayActivity",
                                    "Bionic stage2 stats: numAch=" + numAch
                                            + " firstName=" + firstAch);

                            String  pname  = bs.personaName();
                            int     pstate = bs.personaState();
                            int     fcount = bs.friendCount(
                                    com.winlator.cmod.feature.stores.steam.wnsteam
                                            .WnSteamBootstrap.FriendFlags.Immediate);
                            Log.d("XServerDisplayActivity",
                                    "Bionic stage2 friends: personaName=" + pname
                                            + " personaState=" + pstate
                                            + " friendCount(immediate)=" + fcount);

                            int  purchaseTime = bs.earliestPurchaseUnixTime(appId);
                            int  numDlc       = bs.dlcCount(appId);
                            long owner        = bs.appOwner();
                            boolean famShared = bs.isSubscribedFromFamilySharing();
                            Log.d("XServerDisplayActivity",
                                    "Bionic stage2 perApp: earliestPurchase=" + purchaseTime
                                            + " dlcCount=" + numDlc
                                            + " appOwner=" + owner
                                            + " (familySharing=" + famShared + ")");
                        } catch (Throwable t) {
                            Log.w("XServerDisplayActivity",
                                    "Bionic stage2 diagnostic failed", t);
                        }
                    } else {
                        Log.w("XServerDisplayActivity",
                                "Bionic Steam bootstrap: libsteamclient.so missing, "
                                        + "skipping nativeInit");
                    }
                } catch (Throwable t) {
                    Log.e("XServerDisplayActivity", "Bionic Steam bootstrap failed", t);
                }
            }

            Log.d("XServerDisplayActivity", "Steam environment setup complete for appId=" + appId);
        } catch (Exception e) {
            Log.e("XServerDisplayActivity", "Failed to setup Steam environment", e);
        }
    }

    private void setupLightweightSteamConfig(File steamDir, String steamId64) {
        try {
            File userDataPath = new File(steamDir, "userdata/" + steamId64);
            File configPath = new File(userDataPath, "config");
            File remotePath = new File(userDataPath, "7/remote");
            configPath.mkdirs();
            remotePath.mkdirs();

            File localConfigFile = new File(configPath, "localconfig.vdf");
            if (!localConfigFile.exists()) {
                String localConfigContent = "\"UserLocalConfigStore\"\n" +
                        "{\n" +
                        "  \"Software\"\n" +
                        "  {\n" +
                        "    \"Valve\"\n" +
                        "    {\n" +
                        "      \"Steam\"\n" +
                        "      {\n" +
                        "        \"SmallMode\"                      \"1\"\n" +
                        "        \"LibraryDisableCommunityContent\" \"1\"\n" +
                        "        \"LibraryLowBandwidthMode\"        \"1\"\n" +
                        "        \"LibraryLowPerfMode\"             \"1\"\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "  \"friends\"\n" +
                        "  {\n" +
                        "    \"SignIntoFriends\" \"0\"\n" +
                        "  }\n" +
                        "}\n";
                FileUtils.writeString(localConfigFile, localConfigContent);
            }

            File sharedConfigFile = new File(remotePath, "sharedconfig.vdf");
            if (!sharedConfigFile.exists()) {
                String sharedConfigContent = "\"UserRoamingConfigStore\"\n" +
                        "{\n" +
                        "  \"Software\"\n" +
                        "  {\n" +
                        "    \"Valve\"\n" +
                        "    {\n" +
                        "      \"Steam\"\n" +
                        "      {\n" +
                        "        \"SteamDefaultDialog\" \"#app_games\"\n" +
                        "        \"FriendsUI\"\n" +
                        "        {\n" +
                        "          \"FriendsUIJSON\" \"{\\\"bSignIntoFriends\\\":false,\\\"bAnimatedAvatars\\\":false,\\\"PersonaNotifications\\\":0,\\\"bDisableRoomEffects\\\":true}\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}\n";
                FileUtils.writeString(sharedConfigFile, sharedConfigContent);
            }
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to setup lightweight Steam configuration", e);
        }
    }

    private void reconcileSteamUserdata(File steamDir, String steamUserDataId, String steamId64) {
        if (steamDir == null || !steamDir.exists() || steamUserDataId == null || steamUserDataId.isEmpty()) {
            return;
        }

        File userdataDir = new File(steamDir, "userdata");
        if (!userdataDir.exists()) userdataDir.mkdirs();

        File activeUserDir = new File(userdataDir, steamUserDataId);
        if (!activeUserDir.exists()) activeUserDir.mkdirs();

        String fallbackUserId = "76561198000000000";
        if (fallbackUserId.equals(steamUserDataId) || fallbackUserId.equals(steamId64)) {
            return;
        }

        File staleUserDir = new File(userdataDir, fallbackUserId);
        if (!staleUserDir.exists()) {
            return;
        }

        try {
            File staleLocalConfig = new File(staleUserDir, "config/localconfig.vdf");
            File activeLocalConfig = new File(activeUserDir, "config/localconfig.vdf");
            if (staleLocalConfig.exists() && !activeLocalConfig.exists()) {
                activeLocalConfig.getParentFile().mkdirs();
                FileUtils.copy(staleLocalConfig, activeLocalConfig);
            }

            File staleSharedConfig = new File(staleUserDir, "7/remote/sharedconfig.vdf");
            File activeSharedConfig = new File(activeUserDir, "7/remote/sharedconfig.vdf");
            if (staleSharedConfig.exists() && !activeSharedConfig.exists()) {
                activeSharedConfig.getParentFile().mkdirs();
                FileUtils.copy(staleSharedConfig, activeSharedConfig);
            }

            if (FileUtils.delete(staleUserDir)) {
                Log.d("XServerDisplayActivity",
                        "Removed stale fallback Steam userdata profile " + fallbackUserId + " in favor of " + steamUserDataId);
            }
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to reconcile stale Steam userdata", e);
        }
    }

    private void ensureSteamLibraryFoldersConfig(File steamDir, File steamappsDir) {
        if (steamDir == null || steamappsDir == null) {
            return;
        }

        try {
            File configDir = new File(steamDir, "config");
            if (!configDir.exists()) {
                configDir.mkdirs();
            }

            java.util.Set<String> installedAppIds = new java.util.TreeSet<>();
            File[] manifests = steamappsDir.listFiles((dir, name) ->
                    name != null && name.startsWith("appmanifest_") && name.endsWith(".acf"));
            if (manifests != null) {
                for (File manifest : manifests) {
                    String name = manifest.getName();
                    String appId = name.substring("appmanifest_".length(), name.length() - ".acf".length());
                    if (!appId.isEmpty()) {
                        installedAppIds.add(appId);
                    }
                }
            }

            StringBuilder content = new StringBuilder();
            content.append("\"libraryfolders\"\n");
            content.append("{\n");
            content.append("\t\"0\"\n");
            content.append("\t{\n");
            content.append("\t\t\"path\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\"\n");
            content.append("\t\t\"label\"\t\t\"\"\n");
            content.append("\t\t\"contentid\"\t\t\"0\"\n");
            content.append("\t\t\"totalsize\"\t\t\"0\"\n");
            content.append("\t\t\"update_clean_bytes_tally\"\t\t\"0\"\n");
            content.append("\t\t\"time_last_update_verified\"\t\t\"")
                    .append(System.currentTimeMillis() / 1000L)
                    .append("\"\n");
            content.append("\t\t\"apps\"\n");
            content.append("\t\t{\n");
            for (String appId : installedAppIds) {
                content.append("\t\t\t\"").append(appId).append("\"\t\t\"0\"\n");
            }
            content.append("\t\t}\n");
            content.append("\t}\n");
            content.append("}\n");

            File libraryFolders = new File(configDir, "libraryfolders.vdf");
            FileUtils.writeString(libraryFolders, content.toString());
            Log.d("XServerDisplayActivity", "Updated Steam libraryfolders.vdf with " + installedAppIds.size() + " app(s)");
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to update Steam libraryfolders.vdf", e);
        }
    }

    private void copySteamRuntimeIntoGameDir(File gameDir) {
        File gameSteamDir = new File(gameDir, "Steam");
        if (gameSteamDir.exists()) {
            return;
        }

        try {
            gameSteamDir.mkdirs();
            File steamDirSrc = new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam");
            File[] steamChildren = steamDirSrc.listFiles();
            if (steamChildren != null) {
                for (File child : steamChildren) {
                    String name = child.getName().toLowerCase();
                    if (name.equals("dumps") || name.equals("steamapps") || name.equals("userdata")) continue;

                    File targetChild = new File(gameSteamDir, child.getName());
                    com.winlator.cmod.shared.io.FileUtils.copy(child, targetChild);
                }
            }
            Log.d("XServerDisplayActivity", "Physically copied Steam client files to " + gameSteamDir.getAbsolutePath());
        } catch (Exception copyEx) {
            Log.e("XServerDisplayActivity", "Failed to copy Steam client files to game dir", copyEx);
        }
    }

    private void cleanupEmbeddedSteamRuntime(File gameDir) {
        File embeddedSteamDir = new File(gameDir, "Steam");
        if (!embeddedSteamDir.exists() || !embeddedSteamDir.isDirectory()) {
            return;
        }

        boolean looksLikeCopiedSteamRuntime =
                new File(embeddedSteamDir, "steam.exe").exists()
                || new File(embeddedSteamDir, "steamclient.dll").exists()
                || new File(embeddedSteamDir, "steamclient_loader_x64.exe").exists()
                || new File(embeddedSteamDir, "ColdClientLoader.ini").exists();
        if (!looksLikeCopiedSteamRuntime) {
            return;
        }

        try {
            if (FileUtils.delete(embeddedSteamDir)) {
                Log.d("XServerDisplayActivity", "Removed embedded Steam runtime from game directory " + embeddedSteamDir.getAbsolutePath());
            } else {
                Log.w("XServerDisplayActivity", "Failed to remove embedded Steam runtime from game directory " + embeddedSteamDir.getAbsolutePath());
            }
        } catch (Throwable e) {
            Log.w("XServerDisplayActivity", "Failed to remove embedded Steam runtime", e);
        }
    }

    private void skipFirstTimeSteamSetup(File containerDir) {
        File systemRegFile = new File(containerDir, ".wine/system.reg");
        if (!systemRegFile.exists()) return;

        String[][] redistributables = {
            {"DirectX\\Jun2010", "DXSetup"},
            {".NET\\3.5", "3.5 SP1"},
            {".NET\\3.5 Client Profile", "3.5 Client Profile SP1"},
            {".NET\\4.0", "4.0"},
            {".NET\\4.0 Client Profile", "4.0 Client Profile"},
            {".NET\\4.5.1", "4.5.1"},
            {".NET\\4.5.2", "4.5.2"},
            {".NET\\4.6", "4.6"},
            {".NET\\4.6.1", "4.6.1"},
            {".NET\\4.6.2", "4.6.2"},
            {".NET\\4.7", "4.7"},
            {".NET\\4.7.1", "4.7.1"},
            {".NET\\4.7.2", "4.7.2"},
            {".NET\\4.8", "4.8"},
            {".NET\\4.8.1", "4.8.1"},
            {"XNA\\3.0", "3.0"},
            {"XNA\\3.1", "3.1"},
            {"XNA\\4.0", "4.0"},
            {"OpenAL\\2.0.7.0", "2.0.7.0"},
        };

        try (WineRegistryEditor reg = new WineRegistryEditor(systemRegFile)) {
            for (String[] entry : redistributables) {
                String regPath = "Software\\Valve\\Steam\\Apps\\CommonRedist\\" + entry[0];
                String regPathWow = "Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\" + entry[0];
                reg.setDwordValue(regPath, entry[1], 1);
                reg.setDwordValue(regPathWow, entry[1], 1);
            }
            Log.d("XServerDisplayActivity", "Marked " + redistributables.length + " redistributables as installed");
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Failed to set redistributable registry entries", e);
        }
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerSurfaceView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) {
            overrideEnvVars = new EnvVars();
        }
        return overrideEnvVars;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = imageFs.getRootDir();
            File userRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals("alsa")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals("pulseaudio")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
                else if (DirectAudioDriver.INSTANCE.isSelected(audioDriver)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio",
                            DirectAudioDriver.IDENTIFIER);
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = imageFs.getRootDir();
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "container_pattern_common.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    private void assignTaskAffinity(Window window) {
        if (taskAffinityMask == 0 && taskAffinityMaskWoW64 == 0) return;
        int processId = window.getProcessId();
        String className = window.getClassName();

        if (processId > 0) {
            if (taskAffinityMask == taskAffinityMaskWoW64) {
                winHandler.setProcessAffinity(processId, taskAffinityMask);
            } else {
                applyGuestResolvedAffinity(processId, window.isWoW64());
            }
        }
        else if (!className.isEmpty()) {
            int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;
            if (processAffinity != 0) winHandler.setProcessAffinity(className, processAffinity);
        }
    }

    // Background service processes have no windows and never pass through
    // assignTaskAffinity. One complete pass over the guest process list at the
    // first window map pins every matching instance BY PID (by-name requests
    // can't address duplicates like the two winedevice hosts). Children spawned
    // afterwards inherit their parent's mask, so a single early pass keeps the
    // policy without ever re-sending — manual task manager changes stick.
    // Services go to the efficiency cores (lower half), shell/UI to the 64-bit
    // list, winhandler to all cores.
    private void pinServiceAffinity() {
        if (serviceAffinityStarted || winHandler == null) return;
        serviceAffinityStarted = true;
        new Thread(() -> {
            final WinHandler wh = winHandler;
            if (wh == null) return;
            int coreCount = Runtime.getRuntime().availableProcessors();
            final int littleMask = ProcessHelper.getAffinityMask(0, coreCount / 2);
            final int fullMask = ProcessHelper.getAffinityMask(0, coreCount);
            final CountDownLatch latch = new CountDownLatch(1);
            final OnGetProcessInfoListener previous = wh.getOnGetProcessInfoListener();
            final OnGetProcessInfoListener pinner = (index, numProcesses, processInfo) -> {
                if (previous != null) previous.onGetProcessInfo(index, numProcesses, processInfo);
                if (processInfo != null) {
                    int desired = serviceMaskForProcess(processInfo.name, littleMask, fullMask);
                    if (desired != 0 && processInfo.affinityMask != desired) {
                        wh.setProcessAffinity(processInfo.pid, desired);
                    }
                }
                if (processInfo == null || index == numProcesses - 1) latch.countDown();
            };
            wh.setOnGetProcessInfoListener(pinner);
            try {
                wh.listProcesses();
                if (!latch.await(3000, TimeUnit.MILLISECONDS)) serviceAffinityStarted = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (wh.getOnGetProcessInfoListener() == pinner) {
                    wh.setOnGetProcessInfoListener(previous);
                }
            }
        }, "ServiceAffinityPin").start();
    }

    private int serviceMaskForProcess(String name, int littleMask, int fullMask) {
        if (name == null) return 0;
        if (name.equalsIgnoreCase("winhandler.exe")) return fullMask;
        for (String service : SERVICE_AFFINITY_PROCESSES) {
            if (name.equalsIgnoreCase(service)) return littleMask;
        }
        for (String shell : SHELL_AFFINITY_PROCESSES) {
            if (name.equalsIgnoreCase(shell)) return taskAffinityMask;
        }
        return 0;
    }

    // _NET_WM_WOW64 can be absent on 32-bit guest windows, so when the two masks
    // differ, resolve the wow64 flag from the guest process list and apply once.
    // The window property is only the fallback if the guest doesn't answer.
    private void applyGuestResolvedAffinity(final int pid, final boolean windowSaysWoW64) {
        synchronized (guestAffinityCheckedPids) {
            if (!guestAffinityCheckedPids.add(pid)) return;
        }
        new Thread(() -> {
            final WinHandler handler = winHandler;
            if (handler == null) return;
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] resolved = new boolean[1];
            final OnGetProcessInfoListener previous = handler.getOnGetProcessInfoListener();
            final OnGetProcessInfoListener resolver = (index, numProcesses, processInfo) -> {
                if (previous != null) previous.onGetProcessInfo(index, numProcesses, processInfo);
                if (processInfo != null && processInfo.pid == pid) {
                    int desired = processInfo.wow64Process ? taskAffinityMaskWoW64 : taskAffinityMask;
                    Log.d("XServerDisplayActivity", "Guest affinity resolve pid=" + pid
                            + " wow64=" + processInfo.wow64Process
                            + " mask=0x" + Integer.toHexString(desired));
                    if (desired != 0) handler.setProcessAffinity(pid, desired);
                    resolved[0] = true;
                    latch.countDown();
                } else if (processInfo == null || index == numProcesses - 1) {
                    latch.countDown();
                }
            };
            handler.setOnGetProcessInfoListener(resolver);
            try {
                handler.listProcesses();
                latch.await(3000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (handler.getOnGetProcessInfoListener() == resolver) {
                    handler.setOnGetProcessInfoListener(previous);
                }
            }
            if (!resolved[0]) {
                int fallback = windowSaysWoW64 ? taskAffinityMaskWoW64 : taskAffinityMask;
                if (fallback != 0) handler.setProcessAffinity(pid, fallback);
                synchronized (guestAffinityCheckedPids) {
                    guestAffinityCheckedPids.remove(pid);
                }
            }
        }, "GuestAffinityResolve").start();
    }

    private void changeFrameRatingVisibility(Window window, Property property) {
        if (property != null) {
            String propName = property.nameAsString();
            boolean isRendererProp = propName.contains("_MESA_DRV_ENGINE_NAME") || propName.contains("_UTIL_LAYER") || propName.contains("_MESA_DRV_RENDERER");

            if (isRendererProp || propName.contains("_MESA_DRV_GPU_NAME")) {
                syncFrameRatingWithExistingWindows();
                return;
            }

        } else {
            syncFrameRatingWithExistingWindows();
            if (frameRatingWindowId == -1 && !effectiveShowFPS) {
                Log.d("XServerDisplayActivity", "Hiding hud as no renderer windows remain.");
                if (frameRating != null) {
                    runOnUiThread(() -> {
                        frameRating.setVisibility(View.GONE);
                        frameRating.reset();
                    });
                }
            }
        }
    }

    private void syncFrameRatingWithExistingWindows() {
        if (xServer == null || frameRating == null) return;
        Window bestWindow = null;
        String bestRenderer = null;
        String bestGpu = null;
        int bestScore = -1;

        for (Window window : xServer.windowManager.getWindows()) {
            if (window.id == xServer.windowManager.rootWindow.id) continue;

            Property prop = window.getProperty(Atom.getId("_MESA_DRV_ENGINE_NAME"));
            if (prop == null) prop = window.getProperty(Atom.getId("_MESA_DRV_RENDERER"));
            if (prop == null) prop = window.getProperty(Atom.getId("_UTIL_LAYER"));

            if (prop != null) {
                boolean isApp = window.isApplicationWindow();
                boolean isMapped = window.attributes.isMapped();
                int area = window.getWidth() * window.getHeight();

                int score = 0;
                if (isApp) score += 100000000;
                if (isMapped) score += 10000000;

                String rName = prop.toString().toLowerCase();
                if (rName.contains("vkd3d")) {
                    score += 6000000;
                } else if (rName.contains("dxvk")) {
                    score += 5000000;
                } else if (rName.contains("zink")) {
                    // Prefer the GL renderer window over a bare-Vulkan probe window.
                    score += 4500000;
                } else if (rName.contains("vulkan") || rName.contains("turnip")) {
                    score += 4000000;
                }

                score += Math.min(area, 3000000);

                if (score > bestScore) {
                    bestScore = score;
                    bestWindow = window;
                    bestRenderer = prop.toString();
                    Property gpuProp = window.getProperty(Atom.getId("_MESA_DRV_GPU_NAME"));
                    bestGpu = gpuProp != null ? gpuProp.toString() : null;
                }
            }
        }

        boolean hasApp = hasNonShellAppWindow();
        if (hasApp) gameWindowSeen = true;

        boolean windowChanged = bestWindow != null && frameRatingWindowId != bestWindow.id;
        if (bestWindow != null) {
            lastRendererName = bestRenderer;
            lastGpuName = bestGpu;
            if (bestWindow.id != rendererWindowId) {
                rendererWindowId = bestWindow.id;
                rendererWindowPresented = false;
            }
            frameRatingWindowId = bestWindow.id;
        } else {
            if (rendererWindowPresented || (gameWindowSeen && !hasApp)) {
                lastRendererName = "Vulkan";
                lastGpuName = null;
                gameWindowSeen = false;
                rendererWindowId = -1;
                rendererWindowPresented = false;
            }
            frameRatingWindowId = -1;
        }

        runOnUiThread(() -> {
            frameRating.setRenderer(lastRendererName);
            frameRating.setGpuName(lastGpuName);
            if (mangoHud != null) {
                mangoHud.setEngineName(mangoEngineLabel());
                // New game window: drop loading/menu frames from the averages.
                if (windowChanged) mangoHud.resetMetrics();
            }
            updateHUDRenderMode();
        });
    }

    /** True while an application window other than the desktop shell is mapped. */
    private boolean hasNonShellAppWindow() {
        if (xServer == null) return false;
        for (Window w : xServer.windowManager.getWindows()) {
            if (w.id == xServer.windowManager.rootWindow.id) continue;
            if (!w.isApplicationWindow()) continue;
            String cls = w.getClassName();
            if (cls == null || !cls.toLowerCase().contains("explorer")) return true;
        }
        return false;
    }

    /** Engine label for the Mango HUD: renderer name plus the DXVK version when running DXVK. */
    private String mangoEngineLabel() {
        String name = lastRendererName != null ? lastRendererName : "Vulkan";
        if (name.toLowerCase().contains("dxvk") && dxwrapperConfig != null) {
            String version = dxwrapperConfig.get("version");
            if (version != null && !version.isEmpty()) return "DXVK " + version;
        }
        return name;
    }

    private boolean shouldRecordFpsFrame(Window window, WindowManager.FrameSource source) {
        // Perf recording is driven solely by the record-to-file toggle, independent of HUD/controller.
        boolean recording = perfController != null && perfController.isActive();
        boolean mangoVisible = mangoHud != null && mangoHud.getVisibility() == View.VISIBLE;
        if ((!effectiveShowFPS && !controllerHudMode && !recording && !mangoVisible) || frameRating == null || window == null) return false;
        if (source == WindowManager.FrameSource.UNKNOWN) return false;
        if (frameRatingWindowId == window.id) return true;
        if (isRelatedToFrameRatingWindow(window)) return true;
        return frameRatingWindowId == -1 || isLikelyGameFrameWindow(window);
    }

    private boolean isRelatedToFrameRatingWindow(Window window) {
        if (xServer == null || frameRatingWindowId == -1 || window == null) return false;
        Window target = xServer.windowManager.getWindow(frameRatingWindowId);
        if (target == null) return false;

        Window cursor = window;
        while (cursor != null) {
            if (cursor == target) return true;
            cursor = cursor.getParent();
        }

        cursor = target;
        while (cursor != null) {
            if (cursor == window) return true;
            cursor = cursor.getParent();
        }

        return false;
    }

    private boolean isLikelyGameFrameWindow(Window window) {
        if (xServer == null || window == null || window == xServer.windowManager.rootWindow) return false;
        if (!window.isInputOutput() || !window.attributes.isMapped()) return false;
        int area = window.getWidth() * window.getHeight();
        int screenArea = xServer.screenInfo.width * xServer.screenInfo.height;
        return window.isApplicationWindow() || area >= Math.max(1, screenArea / 4);
    }

    private void updateHUDRenderMode() {
    }

    private File findGameExe(File dir) {
        if (dir == null || !dir.exists()) return null;
        
        java.util.LinkedList<File[]> queue = new java.util.LinkedList<>();
        queue.add(new File[]{dir});
        int depth = 0;
        File fallbackExe = null;
        
        String[] exclusions = {"unins", "redist", "setup", "dotnet", "vcredist", 
                               "dxsetup", "helper", "crash", "ue4prereq", "dxwebsetup", "launcher"};
        
        while (!queue.isEmpty() && depth <= 4) {
            File[] currentDirs = queue.poll();
            java.util.List<File> nextDirs = new java.util.ArrayList<>();
            java.util.List<File> candidates = new java.util.ArrayList<>();
            
            for (File d : currentDirs) {
                File[] children = d.listFiles();
                if (children == null) continue;
                
                for (File f : children) {
                    if (f.isDirectory()) {
                        nextDirs.add(f);
                    } else if (f.getName().toLowerCase().endsWith(".exe")) {
                        String name = f.getName().toLowerCase();
                        boolean excluded = false;
                        for (String exclusion : exclusions) {
                            if (name.contains(exclusion)) {
                                excluded = true;
                                break;
                            }
                        }
                        if (!excluded) candidates.add(f);
                    }
                }
            }

            for (File cand : candidates) {
                if (cand.getName().toLowerCase().contains("64") || 
                    (cand.getParentFile() != null && cand.getParentFile().getName().toLowerCase().contains("64"))) {
                    return cand;
                }
            }

            if (fallbackExe == null && !candidates.isEmpty()) {
                fallbackExe = candidates.get(0);
            }
            
            if (!nextDirs.isEmpty()) queue.add(nextDirs.toArray(new File[0]));
            depth++;
        }
        return fallbackExe;
    }
}
