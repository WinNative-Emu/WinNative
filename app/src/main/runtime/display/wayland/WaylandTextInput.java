package com.winlator.cmod.runtime.display.wayland;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * Soft-keyboard text for the Wayland guest (zwp_text_input_v3). Keys still travel the wl_keyboard
 * path; this adds an {@link InputConnection} on the compositor's SurfaceView while a program accepts
 * IME text, so committed and composing text reaches the guest as text. The keyboard is shown only
 * when the program also reports a caret rectangle and no hardware keyboard is attached, and hidden
 * again when text input ends unless the user opened it with the app's own toggle.
 */
public final class WaylandTextInput implements WaylandCompositor.TextInputListener {
    private static final String TAG = "WaylandTextInput";

    /** The compositor's SurfaceView, able to host the soft keyboard's InputConnection. */
    public static final class SurfaceInputView extends SurfaceView {
        private WaylandTextInput owner;

        public SurfaceInputView(Context context) { super(context); }

        @Override public boolean onCheckIsTextEditor() { return owner != null && owner.textMode(); }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (owner == null || !owner.textMode()) return null; // key-event mode, as on X11
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            outAttrs.initialSelStart = 0;
            outAttrs.initialSelEnd = 0;
            return owner.new GuestInputConnection(this);
        }
    }

    private final Activity activity;
    private final SurfaceInputView view;
    private final InputMethodManager imm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean textActive;        // a program accepts IME text
    private boolean caretKnown;        // it reported a caret rectangle
    private boolean autoShown;         // we opened the keyboard ourselves
    private boolean userForced;        // the app's keyboard toggle opened it
    private View focusBefore;          // who had focus before we took it for the IME

    public WaylandTextInput(Activity activity, SurfaceInputView view) {
        this.activity = activity;
        this.view = view;
        this.imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        view.owner = this;
        // Not focusable until we need the IME: a focused SurfaceView would take joystick/D-pad
        // motion away from InputControlsView (the framework focus-routes motion events).
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
    }

    /** IME text mode: only once a program has both enabled text input AND placed a caret. Without
     *  the caret (winewayland enables for every focused window) the keyboard stays in key mode, so
     *  a game gets key events, not IME strings. */
    boolean textMode() { return textActive && caretKnown; }

    public void start() { WaylandCompositor.setTextInputListener(this); }

    public void stop() { WaylandCompositor.setTextInputListener(null); }

    /** The user pressed the app's keyboard toggle: mirror its state and stop auto-hiding. */
    public void onUserToggledKeyboard() {
        userForced = !userForced;
        autoShown = false;
        if (userForced && textMode() && !view.hasFocus()) takeFocus();
        else if (!userForced && !autoShown) giveFocusBack();
    }

    private boolean hardwareKeyboardPresent() {
        Configuration c = activity.getResources().getConfiguration();
        return c.keyboard == Configuration.KEYBOARD_QWERTY
                && c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    private void takeFocus() {
        View cur = activity.getCurrentFocus();
        if (cur != view) focusBefore = cur;
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.requestFocus();
    }

    private void giveFocusBack() {
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        if (focusBefore != null && focusBefore.isAttachedToWindow()) focusBefore.requestFocus();
        focusBefore = null;
    }

    @Override
    public void onTextInput(boolean enabled, String program, int x, int y, int w, int h) {
        main.post(() -> {
            boolean wasTextMode = textMode();
            textActive = enabled;
            caretKnown = enabled && w > 0 && h > 0;
            if (textMode() && !wasTextMode) {
                if (!autoShown && !userForced && !hardwareKeyboardPresent()) {
                    takeFocus();
                    if (imm != null && imm.showSoftInput(view, 0)) autoShown = true;
                    Log.i(TAG, "keyboard shown for " + program);
                } else if (userForced) {
                    takeFocus();                       /* the user's keyboard switches to text mode */
                }
                if (imm != null) imm.restartInput(view);
            } else if (!textMode() && wasTextMode) {
                if (autoShown) {
                    if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
                    autoShown = false;
                    Log.i(TAG, "keyboard hidden");
                }
                giveFocusBack();                       /* back to key mode for whoever had focus */
                if (imm != null) imm.restartInput(view);
            }
        });
    }

    /** What the IME types goes to the guest as text; keys it sends go the wl_keyboard way. */
    final class GuestInputConnection extends BaseInputConnection {
        private String composing;

        GuestInputConnection(View target) { super(target, true); }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            composing = null;
            WaylandCompositor.textInputCommit(text == null ? "" : text.toString());
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            composing = text == null ? "" : text.toString();
            WaylandCompositor.textInputPreedit(composing, -1);
            return true;
        }

        @Override
        public boolean setComposingRegion(int start, int end) { return true; }

        @Override
        public boolean finishComposingText() {
            if (composing != null && !composing.isEmpty()) WaylandCompositor.textInputCommit(composing);
            else if (composing != null) WaylandCompositor.textInputPreedit("", 0);
            composing = null;
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            composing = null;
            WaylandCompositor.textInputDelete(beforeLength, afterLength);
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            return deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            return activity.dispatchKeyEvent(event);
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            long t = System.currentTimeMillis();
            activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0));
            activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0));
            return true;
        }

        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) { return ""; }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) { return ""; }

        @Override
        public CharSequence getSelectedText(int flags) { return null; }
    }
}
