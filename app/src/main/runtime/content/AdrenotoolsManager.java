package com.winlator.cmod.runtime.content;

import android.content.res.AssetManager;
import android.net.Uri;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.app.config.SettingsConfig;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.container.ContainerManager;
import com.winlator.cmod.runtime.wine.EnvVars;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.feature.settings.GraphicsDriverConfigUtils;
import com.winlator.cmod.shared.io.TarCompressorUtils;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class AdrenotoolsManager {
    
    private File adrenotoolsContentDir;
    private Context mContext;
    
    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }
        
    public String getLibraryName(String adrenoToolsDriverId) {
        String libraryName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            String jsonStr = FileUtils.readString(metaProfile);
            JSONObject jsonObject = new JSONObject(jsonStr != null ? jsonStr : "{}");
            libraryName = jsonObject.getString("libraryName");
        }
        catch (JSONException e) {
        }
        return libraryName;
    }
    
    public String getDriverName(String adrenoToolsDriverId) {
        String driverName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            String jsonStr = FileUtils.readString(metaProfile);
            JSONObject jsonObject = new JSONObject(jsonStr != null ? jsonStr : "{}");
            driverName = jsonObject.getString("name");
        }
        catch (JSONException e) {
        }
        return driverName;
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        String driverVersion = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            String jsonStr = FileUtils.readString(metaProfile);
            JSONObject jsonObject = new JSONObject(jsonStr != null ? jsonStr : "{}");
            driverVersion = jsonObject.getString("driverVersion");
        }
        catch (JSONException e) {
        }
        return driverVersion;
    }

    /**
     * Returns the original GitHub asset filename a driver was installed from, or an empty
     * string if the driver was installed before source-asset tracking existed (or from a
     * local file picker, where no asset name is available). Used to detect duplicate
     * downloads in the Drivers screen.
     */
    public String getSourceAsset(String adrenoToolsDriverId) {
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            String jsonStr = FileUtils.readString(metaProfile);
            if (jsonStr == null) return "";
            JSONObject jsonObject = new JSONObject(jsonStr);
            return jsonObject.optString("sourceAsset", "");
        }
        catch (JSONException e) {
            return "";
        }
    }

    public String getDriverPath(String adrenotoolsDriverId) {
        return adrenotoolsContentDir.getAbsolutePath() + "/" + adrenotoolsDriverId + "/";
    }

    private void reloadContainers(String adrenoToolsDriverId) {
        ContainerManager containerManager = new ContainerManager(mContext);
        String driverName = getDriverName(adrenoToolsDriverId);
        for (Container container : containerManager.getContainers()) {
            HashMap<String, String> config = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());
            String version = config.get("version");
            Log.d("AdrenotoolsManager", "Checking if container driver version " + version + " matches " + driverName);
            if (version != null && driverName != null && version.contains(driverName)) {
                Log.d("AdrenotoolsManager", "Found a match for container " + container.getName());
                config.put("version", "System");
                container.setGraphicsDriverConfig(GraphicsDriverConfigUtils.toGraphicsDriverConfig(config));
                container.saveData();
            }     
        }
        for (Shortcut shortcut : containerManager.loadShortcuts()) {
            HashMap<String, String> config = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
            String version = config.get("version");
            Log.d("AdrenotoolsManager", "Checking if shortcut driver version " + version + " matches " + driverName);
            if (version != null && driverName != null && version.contains(driverName)) {
                Log.d("AdrenotoolsManager", "Found a match for shortcut " + shortcut.name);
                config.put("version", "System");
                shortcut.putExtra("graphicsDriverConfig", GraphicsDriverConfigUtils.toGraphicsDriverConfig(config));
                shortcut.saveData();
            }
        }
    }
    
    public void removeDriver(String adrenoToolsDriverId) {
        com.winlator.cmod.runtime.system.GraphicsDriverCatalog.invalidate();
        Log.d("AdrenotoolsManager", "Removing driver " + adrenoToolsDriverId);
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        reloadContainers(adrenoToolsDriverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();
        File[] files = adrenotoolsContentDir.listFiles();
        if (files == null) return driversList;
        for (File f : files) {
            boolean fromResources = isFromResources(f.getName());
            if (!fromResources && new File(f, "meta.json").exists())
                driversList.add(f.getName());
        }
        return driversList;
    }
    
    public boolean isFromResources(String adrenotoolsDriverId) {
        String driver = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        AssetManager am = mContext.getResources().getAssets();
        InputStream is = null;
        boolean isFromResources = true;
        
        try {
            is = am.open(driver);
            is.close();
        }
        catch (IOException e) {
            isFromResources = false;
        }
        
        return isFromResources;
    }
        
    public boolean extractDriverFromResources(String adrenotoolsDriverId) {
        if ("System".equalsIgnoreCase(adrenotoolsDriverId)) return true;

        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }
    
    public String installDriver(Uri driverUri) {
        return installDriver(driverUri, null);
    }

    /**
     * Installs a driver ZIP and, if {@code sourceAssetName} is non-null, stamps it into the
     * driver's meta.json as the {@code sourceAsset} field so the Drivers screen can later
     * detect that a remote GitHub asset with that filename is already installed.
     */
    public String installDriver(Uri driverUri, String sourceAssetName) {
        com.winlator.cmod.runtime.system.GraphicsDriverCatalog.invalidate();
        File tmpDir = new File(adrenotoolsContentDir, "tmp");
        if (tmpDir.exists()) tmpDir.delete();
        tmpDir.mkdirs();
        ZipInputStream zis;
        InputStream is;
        String name = "";

        try {
            is = mContext.getContentResolver().openInputStream(driverUri);
            zis = new ZipInputStream(is);
            ZipEntry entry = zis.getNextEntry();
            while (entry != null) {
                File dstFile = new File(tmpDir, entry.getName());
                Files.copy(zis, dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                entry = zis.getNextEntry();
            }
            zis.close();
            if (new File(tmpDir, "meta.json").exists()) {
                name = getDriverName(tmpDir.getName());
                File dst = new File(adrenotoolsContentDir, name);
                if (!dst.exists() && !name.equals("")) {
                    tmpDir.renameTo(dst);
                    if (sourceAssetName != null && !sourceAssetName.isEmpty()) {
                        stampSourceAsset(dst, sourceAssetName);
                    }
                }
                else {
                    name = "";
                    FileUtils.delete(tmpDir);
                }
            }
            else {
                Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
                tmpDir.delete();
            }
        }
        catch (IOException e) {
            Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
            tmpDir.delete();
        }

        return name;
    }

    private void stampSourceAsset(File driverDir, String sourceAssetName) {
        File metaFile = new File(driverDir, "meta.json");
        try {
            String jsonStr = FileUtils.readString(metaFile);
            JSONObject jsonObject = new JSONObject(jsonStr != null ? jsonStr : "{}");
            jsonObject.put("sourceAsset", sourceAssetName);
            FileUtils.writeString(metaFile, jsonObject.toString(2));
        }
        catch (JSONException e) {
            Log.w("AdrenotoolsManager", "Failed to stamp sourceAsset into meta.json: " + e.getMessage());
        }
    }
    
    public void setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        if (adrenotoolsDriverId == null || adrenotoolsDriverId.isEmpty()) {
            Log.w("AdrenotoolsManager", "setDriverById called with empty driver id - system driver will be used");
            return;
        }

        boolean isFromResources = isFromResources(adrenotoolsDriverId);
        boolean isInstalled = enumarateInstalledDrivers().contains(adrenotoolsDriverId);

        if (!isFromResources && !isInstalled) {
            Log.w("AdrenotoolsManager", "Driver '" + adrenotoolsDriverId
                    + "' not installed and not bundled - system driver will be used");
            return;
        }

        String libraryName = getLibraryName(adrenotoolsDriverId);
        if (libraryName.equals("")) {
            Log.w("AdrenotoolsManager", "Driver '" + adrenotoolsDriverId
                    + "' has no libraryName in meta.json - system driver will be used");
            return;
        }

        String driverPath = getDriverPath(adrenotoolsDriverId);
        envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
        // adrenotools requires the hooks path to be the APK's nativeLibraryDir
        // so that android_dlopen_ext can load the hook libraries within the
        // app's linker namespace.  Using imagefs/usr/lib causes a silent
        // fallback to the system GPU driver.
        String nativeLibDir = mContext.getApplicationInfo().nativeLibraryDir;
        envVars.put("ADRENOTOOLS_HOOKS_PATH", nativeLibDir);
        envVars.put("ADRENOTOOLS_DRIVER_NAME", libraryName);
    }
 }
