package com.winlator.cmod.runtime.display.wayland;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Keeps Android's clipboard and the Wayland guest's selection in sync (text only, both ways).
 * Android hides clipboard changes from background apps, so {@link #refresh()} re-reads it on
 * resume and focus gain. Text that came from the guest is not pushed back.
 */
public final class WaylandClipboardSync implements WaylandCompositor.ClipboardListener,
        ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "WaylandClipboard";

    private final Context context;
    private final ClipboardManager clipboard;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String lastFromGuest;   // echo guard: the last text the guest gave us
    private String lastToGuest;     // the last text we pushed down
    private boolean started;

    public WaylandClipboardSync(Activity activity) {
        context = activity.getApplicationContext();
        clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /** Register both directions. Call once the compositor is up (main thread). */
    public void start() {
        if (started || clipboard == null) return;
        started = true;
        WaylandCompositor.setClipboardListener(this);
        clipboard.addPrimaryClipChangedListener(this);
        refresh();
    }

    public void stop() {
        if (!started) return;
        started = false;
        WaylandCompositor.setClipboardListener(null);
        if (clipboard != null) clipboard.removePrimaryClipChangedListener(this);
    }

    /** Re-read Android's clipboard and hand any new text to the guest (resume / window focus). */
    public void refresh() {
        if (!started || clipboard == null) return;
        String text = currentText();
        if (text == null || text.isEmpty()) return;
        if (text.equals(lastFromGuest) || text.equals(lastToGuest)) return;
        lastToGuest = text;
        WaylandCompositor.setClipboardText(text);
    }

    private String currentText() {
        try {
            if (!clipboard.hasPrimaryClip()) return null;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence cs = clip.getItemAt(0).coerceToText(context);
            return cs == null ? null : cs.toString();
        } catch (Exception e) {
            Log.w(TAG, "clipboard read failed", e);
            return null;
        }
    }

    @Override
    public void onPrimaryClipChanged() {
        refresh();
    }

    @Override
    public void onGuestClipboardText(String text) {
        if (text == null || clipboard == null) return;
        main.post(() -> {
            lastFromGuest = text;
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("Wayland guest", text));
            } catch (Exception e) {
                Log.w(TAG, "clipboard write failed", e);
            }
        });
    }
}
