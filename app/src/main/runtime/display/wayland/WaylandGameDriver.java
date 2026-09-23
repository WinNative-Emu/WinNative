package com.winlator.cmod.runtime.display.wayland;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.runtime.system.GPUInformation;
import com.winlator.cmod.runtime.wine.EnvVars;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the bundled Wayland Turnip variant the game renders on. A Wayland-capable Proton ships
 * several builds and reads BANNER_WAYLAND_VK_VARIANT to choose one; the variant follows the GPU
 * generation (Adreno 7xx, Adreno 8xx, or the plain build for everything else). A value the user
 * put in the container or shortcut environment is kept.
 */
public final class WaylandGameDriver {
    private WaylandGameDriver() {}

    private static final String TAG = "WaylandGameDriver";

    public static final String ENV_VARIANT = "BANNER_WAYLAND_VK_VARIANT";
    public static final String ENV_ICD = "BANNER_WAYLAND_VK_ICD";

    public static final String VARIANT_PLAIN = "";
    public static final String VARIANT_A7XX = "a7xx";
    public static final String VARIANT_A8XX = "a8xx";

    private static final Pattern ADRENO_MODEL = Pattern.compile("(?i)Adreno\\s*\\(?(?:TM\\)?\\s*)?(\\d+)");

    private static volatile String cachedAutoVariant;

    public static String variantForRenderer(String renderer) {
        if (renderer == null) return VARIANT_PLAIN;
        Matcher m = ADRENO_MODEL.matcher(renderer);
        if (!m.find()) return VARIANT_PLAIN;
        String n = m.group(1);
        switch (n) {
            case "710":
            case "720":
            case "722":
                return VARIANT_A7XX;
            default:
                return n.length() == 3 && n.charAt(0) == '8' ? VARIANT_A8XX : VARIANT_PLAIN;
        }
    }

    /** Probes the GPU once per process; call off the main thread. */
    public static String autoVariant(Context context) {
        String known = cachedAutoVariant;
        if (known == null) {
            String renderer;
            try {
                renderer = GPUInformation.getRenderer(null, context.getApplicationContext());
            } catch (Throwable t) {
                renderer = "";
            }
            cachedAutoVariant = known = variantForRenderer(renderer);
        }
        return known;
    }

    /** Exports the variant for a Wayland launch unless the user set one of the two variables. */
    public static void applyToLaunchEnv(Context context, EnvVars envVars, java.io.File wineRoot) {
        if (envVars.has(ENV_ICD) || envVars.has(ENV_VARIANT)) {
            Log.i(TAG, "wayland game driver: keeping the user's " + ENV_VARIANT + "/" + ENV_ICD);
            return;
        }
        String variant = autoVariant(context);
        if (!variant.isEmpty()) envVars.put(ENV_VARIANT, variant);
        envVars.put(ENV_ICD, WineWaylandSupport.manifestFor(wineRoot, variant).getPath());
        Log.i(TAG, "wayland game driver: auto -> " + (variant.isEmpty() ? "plain" : variant));
    }
}
