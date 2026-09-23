package com.winlator.cmod.shared.ui.dialog;

/* Shared components dialog shell used across content install, warning, and info flows. */

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.util.SparseBooleanArray;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.R;
import com.winlator.cmod.shared.android.AppUtils;
import com.winlator.cmod.shared.util.Callback;
import java.util.ArrayList;

public class ContentDialog extends Dialog {
  public Runnable onConfirmCallback;
  private Runnable onCancelCallback;
  private final View contentView;

  public interface OnControllerInputListener {
    void onControllerInput(InputDevice device);
  }

  private OnControllerInputListener onControllerInputListener;

  public void setOnControllerInputListener(OnControllerInputListener listener) {
    this.onControllerInputListener = listener;
  }

  private boolean isDarkMode;

  public ContentDialog(@NonNull Context context) {
    this(context, 0);
  }

  private View inflatedLayout;

  public ContentDialog(@NonNull Context context, int layoutResId) {
    super(context, R.style.ContentDialog);
    contentView = LayoutInflater.from(context).inflate(R.layout.content_dialog, null);

    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    isDarkMode = sharedPreferences.getBoolean("dark_mode", false);

    if (isDarkMode) {
      this.getContext().setTheme(R.style.ContentDialog_Dark);
    }

    if (layoutResId > 0) {
      FrameLayout frameLayout = contentView.findViewById(R.id.FrameLayout);
      frameLayout.setVisibility(View.VISIBLE);
      View view = LayoutInflater.from(getContext()).inflate(layoutResId, frameLayout, false);
      frameLayout.addView(view);

      // Cap any fixed-height ScrollView/NestedScrollView to fit available screen space
      View scrollView = findScrollView(view);
      if (scrollView != null) {
        ViewGroup.LayoutParams lp = scrollView.getLayoutParams();
        if (lp.height > 0) { // Only adjust explicit fixed heights
          DisplayMetrics dm = context.getResources().getDisplayMetrics();
          // Reserve ~150dp for dialog chrome (title bar, buttons, padding)
          int chromeReserve = (int) (150 * dm.density);
          int maxScrollHeight = dm.heightPixels - chromeReserve;
          if (maxScrollHeight > 0 && lp.height > maxScrollHeight) {
            lp.height = maxScrollHeight;
            scrollView.setLayoutParams(lp);
          }
        }
      }
    }

    View confirmButton = contentView.findViewById(R.id.BTConfirm);
    confirmButton.setOnClickListener(
        (v) -> {
          AppUtils.hideKeyboard(v);
          if (onConfirmCallback != null) onConfirmCallback.run();
          dismiss();
        });

    View cancelButton = contentView.findViewById(R.id.BTCancel);
    cancelButton.setOnClickListener(
        (v) -> {
          if (onCancelCallback != null) onCancelCallback.run();
          dismiss();
        });

    if (getWindow() != null) {
      getWindow()
          .setSoftInputMode(
              android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                  | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
      getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    // Prevent keyboard glitch by ensuring EditText doesn't get focus by default
    View editText = contentView.findViewById(R.id.EditText);
    if (editText != null) {
      editText.setVisibility(View.GONE);
      editText.setFocusable(false);
      editText.setFocusableInTouchMode(false);
    }

    setContentView(contentView);
  }

  @Override
  public void dismiss() {
    AppUtils.hideKeyboard(contentView);
    super.dismiss();
  }

  public View getInflatedLayout() {
    return inflatedLayout;
  }

  public View getContentView() {
    return contentView;
  }

  public void setOnConfirmCallback(Runnable onConfirmCallback) {
    this.onConfirmCallback = onConfirmCallback;
  }

  public void setOnCancelCallback(Runnable onCancelCallback) {
    this.onCancelCallback = onCancelCallback;
  }

  @Override
  public void setTitle(int titleResId) {
    setTitle(getContext().getString(titleResId));
  }

  public void setIcon(int iconResId) {
    ImageView imageView = findViewById(R.id.IVIcon);
    imageView.setImageResource(iconResId);
    imageView.setVisibility(View.VISIBLE);
  }

  public void setTitle(String title) {
    LinearLayout titleBar = findViewById(R.id.LLTitleBar);
    TextView tvTitle = findViewById(R.id.TVTitle);

    if (title != null && !title.isEmpty()) {
      tvTitle.setText(title);
      titleBar.setVisibility(View.VISIBLE);
    } else {
      tvTitle.setText("");
      titleBar.setVisibility(View.GONE);
    }
  }

  public void setBottomBarText(String bottomBarText) {
    TextView tvBottomBarText = findViewById(R.id.TVBottomBarText);

    if (bottomBarText != null && !bottomBarText.isEmpty()) {
      tvBottomBarText.setText(bottomBarText);
      tvBottomBarText.setVisibility(View.VISIBLE);
    } else {
      tvBottomBarText.setText("");
      tvBottomBarText.setVisibility(View.GONE);
    }
  }

  public void setMessage(int msgResId) {
    setMessage(getContext().getString(msgResId));
  }

  public void setMessage(CharSequence message) {
    TextView tvMessage = findViewById(R.id.TVMessage);

    if (message != null && message.length() > 0) {
      tvMessage.setText(message);
      tvMessage.setVisibility(View.VISIBLE);
    } else {
      tvMessage.setText("");
      tvMessage.setVisibility(View.GONE);
    }
  }

  public void setMessage(String message) {
    setMessage((CharSequence) message);
  }

  public static void alert(Context context, int msgResId, Runnable callback) {
    if (WinNativeComposeDialogs.showAlert(context, context.getString(msgResId), callback)) return;
    ContentDialog dialog = new ContentDialog(context);
    dialog.setMessage(msgResId);
    dialog.setOnConfirmCallback(callback);
    dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
    if (callback != null) dialog.setOnCancelListener(d -> callback.run());
    dialog.show();
  }

  public static void alert(Context context, String msg, Runnable callback) {
    if (WinNativeComposeDialogs.showAlert(context, msg, callback)) return;
    ContentDialog dialog = new ContentDialog(context);
    dialog.setMessage(msg);
    dialog.setOnConfirmCallback(callback);
    dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
    if (callback != null) dialog.setOnCancelListener(d -> callback.run());
    dialog.show();
  }

  public static void confirm(Context context, int msgResId, Runnable callback) {
    if (WinNativeComposeDialogs.showConfirm(context, context.getString(msgResId), callback)) return;
    ContentDialog dialog = new ContentDialog(context);
    dialog.setMessage(msgResId);
    dialog.setOnConfirmCallback(callback);
    dialog.show();
  }

  public static void confirm(Context context, String msg, Runnable callback) {
    if (WinNativeComposeDialogs.showConfirm(context, msg, callback)) return;
    ContentDialog dialog = new ContentDialog(context);
    dialog.setMessage(msg);
    dialog.setOnConfirmCallback(callback);
    dialog.show();
  }

  public static void prompt(
      Context context, int titleResId, String defaultText, Callback<String> callback) {
    if (WinNativeComposeDialogs.showPrompt(
        context, context.getString(titleResId), defaultText, callback)) return;
    ContentDialog dialog = new ContentDialog(context);

    if (dialog.getWindow() != null) {
      dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    final EditText editText = dialog.findViewById(R.id.EditText);
    editText.setHint(R.string.common_ui_untitled);
    editText.setFocusable(true);
    editText.setFocusableInTouchMode(true);
    editText.setClickable(true);
    editText.setCursorVisible(true);
    editText.setImeOptions(
        editText.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    if (defaultText != null) editText.setText(defaultText);
    editText.setVisibility(View.VISIBLE);

    dialog.setTitle(titleResId);
    dialog.setOnConfirmCallback(
        () -> {
          String text = editText.getText().toString().trim();
          if (!text.isEmpty()) callback.call(text);
        });

    dialog
        .getWindow()
        .setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    dialog.show();
    editText.requestFocus();
    editText.post(
        () -> {
          android.view.inputmethod.InputMethodManager imm =
              (android.view.inputmethod.InputMethodManager)
                  context.getSystemService(Context.INPUT_METHOD_SERVICE);
          if (imm != null)
            imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
  }

  public static void showMultipleChoiceList(
      Context context,
      int titleResId,
      final String[] items,
      Callback<ArrayList<Integer>> callback) {
    ContentDialog dialog = new ContentDialog(context);

    final ListView listView = dialog.findViewById(R.id.ListView);
    listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
    listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
    listView.setAdapter(
        new ArrayAdapter<>(context, android.R.layout.simple_list_item_multiple_choice, items));
    listView.setVisibility(View.VISIBLE);

    dialog.setTitle(titleResId);
    dialog.setOnConfirmCallback(
        () -> {
          ArrayList<Integer> result = new ArrayList<>();
          SparseBooleanArray checkedItemPositions = listView.getCheckedItemPositions();
          for (int i = 0; i < checkedItemPositions.size(); i++) {
            if (checkedItemPositions.valueAt(i)) result.add(checkedItemPositions.keyAt(i));
          }
          callback.call(result);
        });

    dialog.show();
  }

  public static void showSingleChoiceList(
      Context context, int titleResId, final String[] items, Callback<Integer> callback) {
    ContentDialog dialog = new ContentDialog(context);
    dialog.getContentView().findViewById(R.id.BTConfirm).setVisibility(View.GONE);

    final ListView listView = dialog.findViewById(R.id.ListView);
    listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
    listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
    listView.setAdapter(
        new ArrayAdapter<>(context, android.R.layout.simple_list_item_single_choice, items));
    listView.setVisibility(View.VISIBLE);
    listView.setOnItemClickListener(
        (parent, view, position, id) -> {
          callback.call(position);
          dialog.dismiss();
        });

    dialog.setTitle(titleResId);
    dialog.show();
  }

  @Override
  public boolean dispatchKeyEvent(@NonNull android.view.KeyEvent event) {
    if (onControllerInputListener != null
        && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
      android.view.InputDevice device = event.getDevice();
      if (device != null
          && !device.isVirtual()
          && com.winlator.cmod.runtime.input.controls.ControllerManager.isGameController(device)) {
        onControllerInputListener.onControllerInput(device);
        return true;
      }
    }
    return super.dispatchKeyEvent(event);
  }

  private static View findScrollView(View root) {
    if (root instanceof NestedScrollView || root instanceof ScrollView) return root;
    if (root instanceof ViewGroup) {
      ViewGroup vg = (ViewGroup) root;
      for (int i = 0; i < vg.getChildCount(); i++) {
        View found = findScrollView(vg.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }
}
