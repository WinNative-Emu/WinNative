package com.winlator.cmod.runtime.container;

import com.winlator.cmod.runtime.system.ProcessHelper;
import android.os.Environment;

import com.winlator.cmod.runtime.compat.box64.Box64Preset;
import com.winlator.cmod.runtime.wine.EnvVars;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.shared.util.KeyValueSet;
import com.winlator.cmod.runtime.wine.WineInfo;
import com.winlator.cmod.runtime.wine.WineThemeManager;
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePreset;
import com.winlator.cmod.runtime.display.winhandler.WinHandler;
import com.winlator.cmod.runtime.display.environment.ImageFs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.Locale;

public class Container {
    public static final String FAST_YIELD_ENV_VAR = "WINE_FAST_YIELD";
    public static final String FAST_YIELD_ENV_VALUE = "1";
    public static final String DEFAULT_ENV_VARS = "WRAPPER_MAX_IMAGE_COUNT=0 VKD3D_SHADER_MODEL=6_6 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true TU_DEBUG=noconform,sysmem " + FAST_YIELD_ENV_VAR + "=" + FAST_YIELD_ENV_VALUE;
    public static final String DEFAULT_SCREEN_SIZE = "1280x720";
    public static final String DEFAULT_GRAPHICS_DRIVER = "wrapper";
    public static final String DEFAULT_ZINK_MODE = "unix";
    public static final String DEFAULT_AUDIO_DRIVER = "pulseaudio";
    public static final String DEFAULT_EMULATOR = "Box64";
    public static final String DEFAULT_EMULATOR64 = "Box64";
    public static final String DEFAULT_DXWRAPPER = "dxvk+vkd3d";
    public static final String DEFAULT_DXWRAPPERCONFIG = "version=,async=1,asyncCache=1" + ",vkd3dVersion=None,vkd3dLevel=12_1" + ",ddrawrapper=" + Container.DEFAULT_DDRAWRAPPER + ",csmt=3" + ",gpuName=NVIDIA GeForce GTX 480" + ",videoMemorySize=4096" + ",strict_shader_math=1" + ",OffscreenRenderingMode=fbo" + ",renderer=gl";
    public static final String DEFAULT_GRAPHICSDRIVERCONFIG =
            "vulkanVersion=1.4" + ";version=" + ";blacklistedExtensions=" + ";maxDeviceMemory=0" + ";presentMode=mailbox" + ";syncFrame=0" + ";disablePresentWait=0" + ";resourceType=auto" + ";bcnEmulation=auto" + ";bcnEmulationType=compute" + ";bcnEmulationCache=0" + ";gpuName=Device" + ";transcoder=cpu" + ";astcTranscoding=off";
    public static final String DEFAULT_DDRAWRAPPER = "none";
    public static final String DEFAULT_WINCOMPONENTS = "direct3d=1,directsound=0,directmusic=0,directshow=0,directplay=0,xaudio=0,dinput8=1,vcrun2010=1";
    public static final String FALLBACK_WINCOMPONENTS = "direct3d=1,directsound=1,directmusic=1,directshow=1,directplay=1,xaudio=1,dinput8=1,vcrun2010=1";
    public static final String DEFAULT_DRIVES = "D:" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "F:" + Environment.getExternalStorageDirectory().getAbsolutePath();
    public static final byte STARTUP_SELECTION_NORMAL = 0;
    public static final byte STARTUP_SELECTION_ESSENTIAL = 1;
    public static final byte STARTUP_SELECTION_AGGRESSIVE = 2;
    public static final byte MAX_DRIVE_LETTERS = 26;
    public final int id;
    private String name;
    private String screenSize = DEFAULT_SCREEN_SIZE;
    private String envVars = DEFAULT_ENV_VARS;
    private String graphicsDriver = DEFAULT_GRAPHICS_DRIVER;
    private String graphicsDriverConfig = DEFAULT_GRAPHICSDRIVERCONFIG;
    private String dxwrapper = DEFAULT_DXWRAPPER;
    private String dxwrapperConfig = "";
    private String wincomponents = DEFAULT_WINCOMPONENTS;
    private String audioDriver = DEFAULT_AUDIO_DRIVER;
    private String drives = DEFAULT_DRIVES;
    private String wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
    private boolean fullscreenStretched;
    private boolean useUnixLibs = true;
    private byte startupSelection = STARTUP_SELECTION_ESSENTIAL;
    private String cpuList;
    private String cpuListWoW64;
    private String desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME;
    private String fexcoreVersion = "";
    private String fexcorePreset = FEXCorePreset.PERFORMANCE_TSO;
    private String box64Preset = Box64Preset.PERFORMANCE;
    private File rootDir;
    private JSONObject extraData;
    private String midiSoundFont = "";
    private int inputType = WinHandler.DEFAULT_INPUT_TYPE;
    private boolean exclusiveXInput = true;
    private String lc_all = "";
    private String box64Version = "";
    private String emulator = DEFAULT_EMULATOR;
    private String emulator64 = DEFAULT_EMULATOR64;
    private String executablePath = "";
    private String execArgs = "";
    private boolean launchBionicSteam;
    private boolean useColdClient = false;
    private boolean allowSteamUpdates;
    private boolean needsUnpacking = true;
    private boolean steamOfflineMode = false;
    private boolean unpackFiles = false;
    private boolean runtimePatcher = false;

    private ContainerManager containerManager;



    public Container(int id) {
        this.id = id;
        this.name = "Container-"+id;
    }

    public Container(int id, ContainerManager containerManager) {
        this.id = id;
        this.name = "Container-"+id;
        this.containerManager = containerManager;
    }

    
    public String getExecutablePath() {
        return executablePath;
    }

    public void setExecutablePath(String executablePath) {
        String newPath = executablePath != null ? executablePath : "";
        // If the executable path changed from a non-empty value, mark as needing unpacking
        // so Steamless DRM stripping will re-run on the new exe
        if (!this.executablePath.isEmpty() && !this.executablePath.equals(newPath)) {
            this.needsUnpacking = true;
        }
        this.executablePath = newPath;
    }

    public String getExecArgs() {
        return execArgs;
    }

    public void setExecArgs(String execArgs) {
        this.execArgs = execArgs != null ? execArgs : "";
    }
    
    public ContainerManager getManager() {
        return containerManager;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScreenSize() {
        return screenSize;
    }

    public void setScreenSize(String screenSize) {
        this.screenSize = screenSize;
    }

    public String getEnvVars() {
        return envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars != null ? envVars : "";
    }

    public String getGraphicsDriver() {
        return graphicsDriver;
    }

    public void setGraphicsDriver(String graphicsDriver) {
        this.graphicsDriver = graphicsDriver;
    }

    public String getZinkMode() {
        return getExtra("zinkMode", DEFAULT_ZINK_MODE);
    }

    public void setZinkMode(String zinkMode) {
        putExtra("zinkMode", zinkMode);
    }

    public String getGraphicsDriverConfig() { return this.graphicsDriverConfig; }

    public void setGraphicsDriverConfig(String graphicsDriverConfig) { this.graphicsDriverConfig = graphicsDriverConfig; }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public String getDXWrapperConfig() {
        return dxwrapperConfig;
    }

    public void setDXWrapperConfig(String dxwrapperConfig) {
        this.dxwrapperConfig = dxwrapperConfig != null ? dxwrapperConfig : "";
    }

    public String getAudioDriver() {
        return audioDriver;
    }

    public void setAudioDriver(String audioDriver) {
        this.audioDriver = audioDriver;
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public String getDrives() {
        return drives;
    }

    public void setDrives(String drives) {
        this.drives = drives;
    }

    public String getLC_ALL() {
        return lc_all;
    }

    public void setLC_ALL(String lc_all) {
        this.lc_all = lc_all;
    }

    public boolean isFullscreenStretched() { return fullscreenStretched; }

    public void setFullscreenStretched(boolean fullscreenStretched) { this.fullscreenStretched = fullscreenStretched; }

    public boolean isUseUnixLibs() { return useUnixLibs; }

    public void setUseUnixLibs(boolean useUnixLibs) { this.useUnixLibs = useUnixLibs; }

    public byte getStartupSelection() {
        return startupSelection;
    }

    public void setStartupSelection(byte startupSelection) {
        this.startupSelection = startupSelection;
    }

    public String getCPUList() {
        return getCPUList(false);
    }

    public String getCPUList(boolean allowFallback) {
        return cpuList != null ? cpuList : (allowFallback ? getFallbackCPUList() : null);
    }

    public void setCPUList(String cpuList) {
        this.cpuList = cpuList != null && !cpuList.isEmpty() ? cpuList : null;
    }

    public String getCPUListWoW64() {
        return getCPUListWoW64(false);
    }

    public String getCPUListWoW64(boolean allowFallback) {
        return cpuListWoW64 != null ? cpuListWoW64 : (allowFallback ? getFallbackCPUListWoW64() : null);
    }

    public void setCPUListWoW64(String cpuListWoW64) {
        this.cpuListWoW64 = cpuListWoW64 != null && !cpuListWoW64.isEmpty() ? cpuListWoW64 : null;
    }

    public void setFEXCoreVersion(String version) {
        this.fexcoreVersion = version;
    }

    public String getFEXCoreVersion() {
        return this.fexcoreVersion;
    }

    public void setFEXCorePreset(String preset) {
        this.fexcorePreset = preset;
    }

    public String getFEXCorePreset() {
        return fexcorePreset;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public String getBox64Version() { return box64Version; }

    public void setBox64Version(String version) { this.box64Version = version; }

    public void setEmulator(String emulator) {
        this.emulator = emulator;
    }

    public String getEmulator() {
        return this.emulator;
    }

    public void setEmulator64(String emulator64) {
        this.emulator64 = emulator64;
    }

    public String getEmulator64() {
        return this.emulator64;
    }

    public File getRootDir() {
        return rootDir;
    }

    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    public synchronized void setExtraData(JSONObject extraData) {
        this.extraData = extraData;
    }

    public String getLanguage() {
        return getExtra("containerLanguage", "english");
    }

    public String getExtra(String key) {
        return getExtra(key, "");
    }

    public synchronized String getExtra(String name, String fallback) {
        try {
            return extraData != null && extraData.has(name) ? extraData.getString(name) : fallback;
        }
        catch (JSONException e) {
            return fallback;
        }
    }

    public synchronized void putExtra(String name, Object value) {
        if (extraData == null) extraData = new JSONObject();
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);
        }
        catch (JSONException e) {}
    }

    public String getWineVersion() {
        return wineVersion;
    }

    public void setWineVersion(String wineVersion) {
        this.wineVersion = wineVersion;
    }

    public File getConfigFile() {
        return new File(rootDir, ".container");
    }

    public File getDesktopDir() {
        return new File(rootDir, ".wine/drive_c/users/"+ImageFs.USER+"/Desktop/");
    }

    public File getStartMenuDir() {
        return new File(rootDir, ".wine/drive_c/ProgramData/Microsoft/Windows/Start Menu/");
    }

    public File getIconsDir(int size) {
        return new File(rootDir, ".local/share/icons/hicolor/"+size+"x"+size+"/apps/");
    }

    public String getDesktopTheme() {
        return desktopTheme;
    }

    public void setDesktopTheme(String desktopTheme) {
        this.desktopTheme = desktopTheme;
    }

    public String getMIDISoundFont() {
        return midiSoundFont;
    }

    public void setMidiSoundFont(String fileName) {
        midiSoundFont = fileName;
    }

    public int getInputType() {
        return inputType;
    }

    public void setInputType(int inputType) {
        this.inputType = inputType;
    }

    public boolean isExclusiveXInput() {
        return exclusiveXInput;
    }

    public void setExclusiveXInput(boolean exclusiveXInput) {
        this.exclusiveXInput = exclusiveXInput;
    }

    public Iterable<String[]> drivesIterator() {
        return drivesIterator(drives);
    }

    public static Iterable<String[]> drivesIterator(final String drives) {
        final int[] index = {drives.indexOf(":")};
        final String[] item = new String[2];
        return () -> new Iterator<String[]>() {
            @Override
            public boolean hasNext() {
                return index[0] != -1;
            }

            @Override
            public String[] next() {
                item[0] = String.valueOf(drives.charAt(index[0]-1));
                int nextIndex = drives.indexOf(":", index[0]+1);
                item[1] = drives.substring(index[0]+1, nextIndex != -1 ? nextIndex-1 : drives.length());
                index[0] = nextIndex;
                return item;
            }
        };
    }

    private final Object saveLock = new Object();

    public void saveData() {
        synchronized (saveLock) {
            String serialized = serializeData();
            if (serialized == null) return;
            FileUtils.writeStringAtomic(getConfigFile(), serialized);
        }
    }

    private synchronized String serializeData() {
        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("screenSize", screenSize);
            data.put("envVars", envVars);
            data.put("cpuList", cpuList);
            data.put("cpuListWoW64", cpuListWoW64);
            data.put("graphicsDriver", graphicsDriver);
            data.put("graphicsDriverConfig", graphicsDriverConfig);
            data.put("emulator", emulator);
            data.put("emulator64", emulator64);
            data.put("executablePath", executablePath);
            data.put("execArgs", execArgs);
            data.put("dxwrapper", dxwrapper);
            if (!dxwrapperConfig.isEmpty()) data.put("dxwrapperConfig", dxwrapperConfig);
            data.put("audioDriver", audioDriver);
            data.put("wincomponents", wincomponents);
            data.put("drives", drives);
            data.put("fullscreenStretched", fullscreenStretched);
            data.put("useUnixLibs", useUnixLibs);
            data.put("inputType", inputType);
            data.put("exclusiveXInput", exclusiveXInput);
            data.put("startupSelection", startupSelection);
            data.put("box64Version", box64Version);
            data.put("fexcorePreset", fexcorePreset);
            data.put("fexcoreVersion", fexcoreVersion);
            data.put("box64Preset", box64Preset);
            data.put("desktopTheme", desktopTheme);
            data.put("extraData", snapshotExtraData());
            data.put("midiSoundFont", midiSoundFont);
            data.put("lc_all", lc_all);
            data.put("launchBionicSteam", launchBionicSteam);
            data.put("useColdClient", useColdClient);
            data.put("coldClientMigrated", true);
            data.put("allowSteamUpdates", allowSteamUpdates);
            data.put("needsUnpacking", needsUnpacking);
            data.put("steamOfflineMode", steamOfflineMode);
            data.put("unpackFiles", unpackFiles);
            data.put("runtimePatcher", runtimePatcher);

            if (!WineInfo.isMainWineVersion(wineVersion)) data.put("wineVersion", wineVersion);
            return data.toString();
        }
        catch (Exception e) {
            android.util.Log.e("Container", "Failed to serialize container " + id, e);
            return null;
        }
    }

    private JSONObject snapshotExtraData() {
        JSONObject copy = new JSONObject();
        if (extraData == null) return copy;
        for (Iterator<String> it = extraData.keys(); it.hasNext(); ) {
            String key = it.next();
            try {
                copy.put(key, extraData.get(key));
            }
            catch (JSONException e) {}
        }
        return copy;
    }


    public void loadData(JSONObject data) throws JSONException {
        wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
        dxwrapperConfig = "";
        checkObsoleteOrMissingProperties(data);

        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String key = it.next();
            switch (key) {
                case "name" :
                    setName(data.getString(key));
                    break;
                case "screenSize" :
                    setScreenSize(data.getString(key));
                    break;
                case "envVars" :
                    setEnvVars(data.getString(key));
                    break;
                case "cpuList" :
                    setCPUList(data.getString(key));
                    break;
                case "cpuListWoW64" :
                    setCPUListWoW64(data.getString(key));
                    break;
                case "graphicsDriver" :
                    setGraphicsDriver(data.getString(key));
                    break;
                case "graphicsDriverConfig" :
                    setGraphicsDriverConfig(data.getString(key));
                    break;
                
                case "executablePath":
                    setExecutablePath(data.getString(key));
                    break;
                case "execArgs":
                    setExecArgs(data.getString(key));
                    break;
                case "emulator":
                    setEmulator(data.getString(key));
                    break;
                case "emulator64":
                    setEmulator64(data.getString(key));
                    break;
                case "wincomponents" :
                    setWinComponents(data.getString(key));
                    break;
                case "dxwrapper" :
                    setDXWrapper(data.getString(key));
                    break;
                case "dxwrapperConfig" :
                    setDXWrapperConfig(data.getString(key));
                    break;
                case "drives" :
                    setDrives(data.getString(key));
                    break;
                case "fullscreenStretched" :
                    setFullscreenStretched(data.getBoolean(key));
                    break;
                case "useUnixLibs" :
                    setUseUnixLibs(data.getBoolean(key));
                    break;
                case "inputType" :
                    setInputType(data.getInt(key));
                    break;
                case "exclusiveXInput" :
                    setExclusiveXInput(data.getBoolean(key));
                    break;
                case "startupSelection" :
                    setStartupSelection((byte)data.getInt(key));
                    break;
                case "extraData" : {
                    JSONObject extraData = data.getJSONObject(key);
                    checkObsoleteOrMissingProperties(extraData);
                    setExtraData(extraData);
                    break;
                }
                case "wineVersion" :
                    setWineVersion(data.getString(key));
                    break;
                case "box64Version":
                    setBox64Version(data.getString(key));
                    break;
                case "fexcoreVersion":
                    setFEXCoreVersion(data.getString(key));
                    break;
                case "fexcorePreset":
                    setFEXCorePreset(data.getString(key));
                    break;
                case "box64Preset" :
                    setBox64Preset(data.getString(key));
                    break;
                case "audioDriver" :
                    setAudioDriver(data.getString(key));
                    break;
                case "desktopTheme" :
                    setDesktopTheme(data.getString(key));
                    break;
                case "midiSoundFont" :
                    setMidiSoundFont(data.getString(key));
                    break;
                case "lc_all" :
                    setLC_ALL(data.getString(key));
                    break;
                case "launchBionicSteam" :
                    setLaunchBionicSteam(data.getBoolean(key));
                    break;
                case "useColdClient" :
                    // Only respect explicit user choice if coldClientMigrated flag is set
                    if (data.has("coldClientMigrated")) {
                        setUseColdClient(data.getBoolean(key));
                    }
                    // Otherwise keep default true (migrating from old data)
                    break;
                case "useLegacyDRM" :
                    // Old field — always default to ColdClient on
                    break;
                case "allowSteamUpdates" :
                    setAllowSteamUpdates(data.getBoolean(key));
                    break;
                case "needsUnpacking" :
                    setNeedsUnpacking(data.getBoolean(key));
                    break;
                case "steamOfflineMode":
                    setSteamOfflineMode(data.getBoolean(key));
                    break;
                case "unpackFiles":
                    setUnpackFiles(data.getBoolean(key));
                    break;
                case "runtimePatcher":
                    setRuntimePatcher(data.getBoolean(key));
                    break;
                case "moveSteamExe":
                    break;
            }
        }

        normalizeEmulatorFieldsForArch();
    }

    // Coerce emulator/emulator64 to values that are valid for the prefix's arch.
    // ARM64EC: 64-bit JIT is always FEXCore today; 32-bit JIT is FEXCore or wowbox64.
    // x86_64: both are box64. Anything else is legacy/invalid (e.g. literal "Box64"
    // string from pre-split builds, "FEXCore" hand-edited into x86_64 JSON, empty,
    // null, or mixed case). Normalizing here means every read path — launcher,
    // settings dialogs, shortcut overrides — sees a clean value without us having
    // to add defensive parsing scattered around the code. The next user-initiated
    // saveData() persists the cleaned value to disk; we deliberately don't write
    // here.
    private void normalizeEmulatorFieldsForArch() {
        boolean arm64ec = "arm64ec".equalsIgnoreCase(getExtra("wineprefixArch"));
        emulator = arm64ec
                ? sanitizeArm64ecEmulator32(emulator)
                : sanitizeX86Emulator(emulator);
        emulator64 = arm64ec
                ? "fexcore"
                : sanitizeX86Emulator(emulator64);
    }

    static String sanitizeArm64ecEmulator32(String value) {
        if (value == null) return "fexcore";
        String low = value.trim().toLowerCase(Locale.ROOT);
        return (low.equals("fexcore") || low.equals("wowbox64")) ? low : "fexcore";
    }

    static String sanitizeX86Emulator(String value) {
        // x86_64 containers always run via the box64 binary regardless of this
        // field, so the only sane saved value is "box64" — coerce everything else.
        return "box64";
    }

    public static void checkObsoleteOrMissingProperties(JSONObject data) {
        try {
            if (data.has("dxcomponents")) {
                data.put("wincomponents", data.getString("dxcomponents"));
                data.remove("dxcomponents");
            }

            if (data.has("dxwrapper")) {
                String dxwrapper = data.getString("dxwrapper");
                if (dxwrapper.equals("original-wined3d")) {
                    data.put("dxwrapper", DEFAULT_DXWRAPPER);
                }
                else if (dxwrapper.startsWith("d8vk-") || dxwrapper.startsWith("dxvk-")) {
                    data.put("dxwrapper", dxwrapper);
                }
            }

            if (data.has("cpuListWoW64")) {
                String savedWoW64 = data.getString("cpuListWoW64");
                if (savedWoW64.equals(legacyUpperHalfCPUList()) && !savedWoW64.equals(getFallbackCPUListWoW64())) {
                    data.remove("cpuListWoW64");
                }
            }

            if (data.has("graphicsDriver")) {
                String graphicsDriver = data.getString("graphicsDriver");
                if (graphicsDriver.equals("turnip-zink") || graphicsDriver.equals("turnip")) {
                    data.put("graphicsDriver", "wrapper");
                }
                else if (graphicsDriver.equals("llvmpipe")) {
                    data.put("graphicsDriver", "wrapper");
                }
            }

            if (data.has("envVars") && data.has("extraData")) {
                JSONObject extraData = data.getJSONObject("extraData");
                int appVersion = Integer.parseInt(extraData.optString("appVersion", "0"));
                if (appVersion < 16) {
                    EnvVars defaultEnvVars = new EnvVars(DEFAULT_ENV_VARS);
                    EnvVars envVars = new EnvVars(data.getString("envVars"));
                    for (String name : defaultEnvVars) if (!name.equals("VKD3D_SHADER_MODEL") && !name.equals(FAST_YIELD_ENV_VAR) && !envVars.has(name)) envVars.put(name, defaultEnvVars.get(name));
                    data.put("envVars", envVars.toString());
                }
            }

            KeyValueSet wincomponents1 = new KeyValueSet(DEFAULT_WINCOMPONENTS);
            KeyValueSet wincomponents2 = new KeyValueSet(data.getString("wincomponents"));
            String result = "";

            for (String[] wincomponent1 : wincomponents1) {
                String value = wincomponent1[1];

                for (String[] wincomponent2 : wincomponents2) {
                    if (wincomponent1[0].equals(wincomponent2[0])) {
                        value = wincomponent2[1];
                        break;
                    }
                }

                result += (!result.isEmpty() ? "," : "")+wincomponent1[0]+"="+value;
            }

            data.put("wincomponents", result);
        }
        catch (JSONException e) {}
    }

    public static String getFallbackCPUList() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < numProcessors; i++) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        return cpuList;
    }

    private static String legacyUpperHalfCPUList() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = numProcessors / 2; i < numProcessors; i++) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        return cpuList;
    }

    public static String getFallbackCPUListWoW64() {
        String cpuList = ProcessHelper.getPerformanceCPUList();
        return !cpuList.isEmpty() ? cpuList : getFallbackCPUList();
    }

    // Check if a specific environment variable exists
    public boolean hasEnvVar(String keyValue) {
        if (envVars == null || envVars.isEmpty()) return false;
        String[] vars = envVars.split(",");
        for (String var : vars) {
            if (var.trim().equalsIgnoreCase(keyValue.trim())) {
                return true; // Found the variable
            }
        }
        return false;
    }

    /** Bionic Steam mode: wine launches steam.exe + game.exe and our embedded
     *  libsteamclient.so via wn-steam-bootstrap handles the SteamWorks IPC. */
    public boolean isLaunchBionicSteam() {
        return launchBionicSteam;
    }

    public void setLaunchBionicSteam(boolean launchBionicSteam) {
        this.launchBionicSteam = launchBionicSteam;
    }

    public boolean isUseColdClient() {
        return useColdClient;
    }

    public void setUseColdClient(boolean useColdClient) {
        this.useColdClient = useColdClient;
    }

    public boolean isAllowSteamUpdates() {
        return allowSteamUpdates;
    }

    public void setAllowSteamUpdates(boolean allowSteamUpdates) {
        this.allowSteamUpdates = allowSteamUpdates;
    }

    public boolean isNeedsUnpacking() {
        return needsUnpacking;
    }

    public void setNeedsUnpacking(boolean needsUnpacking) {
        this.needsUnpacking = needsUnpacking;
    }

    public boolean isSteamOfflineMode() {
        return steamOfflineMode;
    }

    public void setSteamOfflineMode(boolean steamOfflineMode) {
        this.steamOfflineMode = steamOfflineMode;
    }

    public boolean isUnpackFiles() {
        return unpackFiles;
    }

    public void setUnpackFiles(boolean unpackFiles) {
        this.unpackFiles = unpackFiles;
    }

    public boolean isRuntimePatcher() {
        return runtimePatcher;
    }

    public void setRuntimePatcher(boolean runtimePatcher) {
        this.runtimePatcher = runtimePatcher;
    }

}
