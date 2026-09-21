package com.winlator.cmod.runtime.display.reshade;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.reshade.ReshadeConfigWriter;
import com.winlator.cmod.runtime.reshade.ReshadeManager;
import com.winlator.cmod.runtime.wine.EnvVars;

/**
 * ReShade loadout resolve, env injection, and debounced live config writes.
 * Extracted from XServerDisplayActivity.
 */
public final class ReshadeSessionController {
    private static final String TAG = "ReshadeSessionController";

    public interface Host {
        Context context();
        Container container();
        Shortcut shortcut();
        ImageFs imageFs();
        String dxwrapper();
        boolean usesContainerDefaults();
    }

    private final Host host;

    public ReshadeSessionController(Host host) {
        this.host = host;
    }

    public boolean isSessionAvailable() { return reshadeSessionAvailable; }
    public boolean isMasterEnabled() { return reshadeMasterEnabled; }
    public void setMasterEnabled(boolean v) { reshadeMasterEnabled = v; }
    public String getMode() { return reshadeMode; }
    public void setMode(String mode) { this.reshadeMode = mode; }
    public java.util.ArrayList<ReshadeLiveEffect> getLiveEffects() { return reshadeLive; }


    private static final long RESHADE_LIVE_DEBOUNCE_MS = 120;
    private android.os.HandlerThread reshadeLiveThread;
    private android.os.Handler reshadeLiveHandler;
    private volatile ReshadeLiveSnapshot pendingReshadeWrite;

    private boolean reshadeSessionAvailable = false;
    private boolean reshadeMasterEnabled = true;
    private String reshadeMode = com.winlator.cmod.runtime.reshade.ReshadeLoadout.MODE_SOLO;
    private final java.util.ArrayList<ReshadeLiveEffect> reshadeLive = new java.util.ArrayList<>();

    public static final class ReshadeLiveSnapshot {
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
    public static class ReshadeLiveEffect {
        final String name;
        boolean enabled;
        final java.util.List<ReshadeManager.ReshadeParam> defs;
        final java.util.LinkedHashMap<String, Float> values;
        ReshadeLiveEffect(String name, boolean enabled, java.util.List<ReshadeManager.ReshadeParam> defs,
                          java.util.LinkedHashMap<String, Float> values) {
            this.name = name; this.enabled = enabled; this.defs = defs; this.values = values;
        }
    }
    public boolean gyroscopeCardExpanded = false;
    private XServerDrawerStateHolder drawerStateHolder;
    private XServerDrawerActionListener drawerActionListener;
    private ExternalDisplayController externalDisplayController;

    public static class ResolvedReshade {
        java.util.List<com.winlator.cmod.runtime.reshade.ReshadeLoadout.Entry> loadout;
        String mode;
        String paramsJson;
        boolean nested;
        String legacyEffect;
        boolean masterEnabled = true;
    }

    // container config stays authoritative until the shortcut has both own-settings and a reshade extra,
    // so a loadout is never mixed half-shortcut half-container

    public boolean reshadeShortcutOwns() {
        if (host.shortcut() == null || host.shortcut().usesContainerDefaults()) return false;
        return host.shortcut().getExtra(ReshadeConfigWriter.EXTRA_LOADOUT, null) != null
                || host.shortcut().getExtra(ReshadeConfigWriter.EXTRA_EFFECT, null) != null;
    }

    // read as one unit from a single source; ReshadeLoadout.parse migrates legacy single-effect saves
    private ResolvedReshade resolveReshade() {
        ResolvedReshade r = new ResolvedReshade();
        r.mode = com.winlator.cmod.runtime.reshade.ReshadeLoadout.MODE_SOLO;
        r.legacyEffect = "None";
        if (host.container() == null) {
            r.loadout = new java.util.ArrayList<>();
            r.nested = false;
            return r;
        }
        String loadoutJson, mode, paramsJson, legacyEffect;
        if (reshadeShortcutOwns()) {
            Shortcut shortcut = host.shortcut();
            loadoutJson  = emptyToNull(shortcut.getExtra(ReshadeConfigWriter.EXTRA_LOADOUT, null));
            mode         = shortcut.getExtra(ReshadeConfigWriter.EXTRA_MODE, "solo");
            paramsJson   = emptyToNull(shortcut.getExtra(ReshadeConfigWriter.EXTRA_PARAMS, null));
            legacyEffect = shortcut.getExtra(ReshadeConfigWriter.EXTRA_EFFECT, "None");
            r.masterEnabled = !"0".equals(shortcut.getExtra(ReshadeConfigWriter.EXTRA_MASTER, "1"));
        } else {
            Container container = host.container();
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
    public void applyReshadeEnv(EnvVars envVars) {
        try {
            if (host.container() == null || host.imageFs() == null) return;
            ResolvedReshade rr = resolveReshade();
            boolean vulkanWrapper = ReshadeConfigWriter.supportedFor(host.dxwrapper());
            boolean applied = ReshadeConfigWriter.applyLoadout(host.context(), host.imageFs(), rr.loadout, rr.paramsJson,
                    rr.nested, rr.legacyEffect, rr.masterEnabled, vulkanWrapper, envVars);
            reshadeSessionAvailable = applied;
            reshadeMasterEnabled = rr.masterEnabled;
            reshadeMode = rr.mode;
            if (applied) seedReshadeLive(rr);
        } catch (Exception e) {
            Log.e(TAG, "ReShade env injection failed (ignored)", e);
        }
    }

    // only effects present in the drop-in folder become tunable
    public void seedReshadeLive(ResolvedReshade rr) {
        reshadeLive.clear();
        for (com.winlator.cmod.runtime.reshade.ReshadeLoadout.Entry entry : rr.loadout) {
            ReshadeManager.ReshadeEffect effect = ReshadeManager.findEffect(host.context(), entry.name);
            if (effect == null) continue;
            org.json.JSONObject saved = com.winlator.cmod.runtime.reshade.ReshadeLoadout.paramsForEffect(
                    rr.paramsJson, effect.name, rr.nested, rr.legacyEffect);
            java.util.LinkedHashMap<String, Float> values = new java.util.LinkedHashMap<>();
            for (ReshadeManager.ReshadeParam p : effect.params) ReshadeManager.seedValues(p, saved, values);
            reshadeLive.add(new ReshadeLiveEffect(effect.name, entry.enabled, effect.params, values));
        }
    }

    public java.util.ArrayList<ReshadeLoadoutItem> buildReshadeItems() {
        java.util.ArrayList<ReshadeLoadoutItem> items = new java.util.ArrayList<>();
        for (ReshadeLiveEffect e : reshadeLive) {
            items.add(new ReshadeLoadoutItem(e.name, e.enabled, e.defs, new java.util.LinkedHashMap<>(e.values)));
        }
        return items;
    }

    // conf rewrite bumps mtime -> live reload without restage; a defaults-following shortcut must persist
    // to the container so use_container_defaults is not flipped mid-session
    public void applyReshadeLive() {
        try {
            if (host.imageFs() == null) return;
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
            Log.e(TAG, "applyReshadeLive failed (ignored)", e);
        }
    }

    private final Runnable reshadeLiveWriteTask = () -> {
        ReshadeLiveSnapshot s = pendingReshadeWrite;
        if (s == null || host.imageFs() == null) return;
        try {
            Shortcut shortcut = host.shortcut();
            Container container = host.container();
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
            ReshadeConfigWriter.writeMergedConfig(host.context(), host.imageFs(), s.entries, s.paramsJson, s.paramsJson != null,
                    s.firstEffect, s.masterEnabled, false);
        } catch (Exception e) {
            Log.e(TAG, "applyReshadeLive failed (ignored)", e);
        }
    };



    public void onDestroy() {
        if (reshadeLiveHandler != null) {
            reshadeLiveHandler.removeCallbacks(reshadeLiveWriteTask);
            reshadeLiveHandler = null;
        }
        if (reshadeLiveThread != null) {
            reshadeLiveThread.quitSafely();
            reshadeLiveThread = null;
        }
    }
}
