package com.aliucord.plugins;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.utils.DimenUtils;
import com.discord.utilities.color.ColorCompat;

import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@AliucordPlugin
@SuppressWarnings("unused")
public class PasswordLogin extends Plugin {
    public static final String ENABLED_KEY = "enabled";
    public static final String LOCK_DELAY_KEY = "lockDelayMs";

    private static final String AUTH_PREFS = "PasswordLoginAuth";
    private static final String PASSWORD_HASH_KEY = "passwordHash";
    private static final String PASSWORD_SALT_KEY = "passwordSalt";
    private static final String PIN_LENGTH_KEY = "pinLength";
    private static final String PASSWORD_SCHEME_KEY = "passwordScheme";
    private static final String LAST_STOPPED_AT_KEY = "lastStoppedAt";
    private static final String SCHEME_PBKDF2 = "pbkdf2-sha256-v1";

    private Application application;
    private SharedPreferences authPrefs;
    private Application.ActivityLifecycleCallbacks lifecycleCallbacks;
    private Dialog lockDialog;
    private boolean locked = true;
    private boolean forceLock;
    private long lastStoppedAt = 0;
    private int startedActivities = 0;
    private boolean isClearingPin = false;

    @Override
    public void start(Context context) throws Throwable {
        application = (Application) context.getApplicationContext();
        authPrefs = application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        settingsTab = new SettingsTab(PasswordLoginSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings, this);

        lastStoppedAt = authPrefs.getLong(LAST_STOPPED_AT_KEY, 0);

        lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}

            @Override
            public void onActivityStarted(Activity activity) {
                startedActivities++;
                if (shouldLock()) {
                    showLockDialog(activity);
                }
            }

            @Override
            public void onActivityResumed(Activity activity) {
                if (shouldLock()) {
                    showLockDialog(activity);
                }
            }

            @Override public void onActivityPaused(Activity a) {}

            @Override
            public void onActivityStopped(Activity activity) {
                startedActivities--;
                if (startedActivities <= 0) {
                    startedActivities = 0;
                    if(!activity.isChangingConfigurations()) {
                        locked = true;
                        lastStoppedAt = System.currentTimeMillis();
                        if (authPrefs != null) {
                            authPrefs.edit().putLong(LAST_STOPPED_AT_KEY, lastStoppedAt).apply();
                        }
                    }
                }
            }

            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}

            @Override
            public void onActivityDestroyed(Activity a) {
                if (lockDialog != null && lockDialog.getOwnerActivity() == a) {
                    lockDialog.dismiss();
                    lockDialog = null;
                }
            }
        };
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks);

        Activity currentActivity = com.aliucord.Utils.getAppActivity();
        if (currentActivity != null && shouldLock()) {
            showLockDialog(currentActivity);
        }
    }

    @Override
    public void stop(Context context) throws Throwable {
        patcher.unpatchAll();
        commands.unregisterAll();

        if (application != null && lifecycleCallbacks != null) {
            application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
        }

        if (lockDialog != null) {
            lockDialog.dismiss();
            lockDialog = null;
        }

        lifecycleCallbacks = null;
        authPrefs = null;
        application = null;
    }

    public void lockNow() {
        locked = true;
        forceLock = true;
        Activity activity = com.aliucord.Utils.getAppActivity();
        if (activity != null && shouldLock()) {
            showLockDialog(activity);
        }
    }

    private boolean shouldLock() {
        if (!settings.getBool(ENABLED_KEY, true) || !hasPassword()) {
            return false;
        }

        if (forceLock) {
            return true;
        }

        if (!locked) {
            return false;
        }

        if (lastStoppedAt == 0) {
            return true;
        }

        int lockDelay = settings.getInt(LOCK_DELAY_KEY, 0);
        return lockDelay <= 0
                || (lastStoppedAt > 0 && System.currentTimeMillis() - lastStoppedAt >= lockDelay);
    }

    private void showLockDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        if (lockDialog != null) {
            if (lockDialog.isShowing() && lockDialog.getOwnerActivity() == activity) {
                return;
            }
            lockDialog.dismiss();
            lockDialog = null;
        }

        int padding = DimenUtils.dpToPx(24);
        int cardPadding = DimenUtils.dpToPx(20);
        int textNormal = themedText(activity);
        int textMuted = ColorCompat.getThemedColor(activity, com.lytefast.flexinput.R.b.colorTextMuted);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(ColorCompat.getThemedColor(activity,
                com.lytefast.flexinput.R.b.colorBackgroundPrimary));
        root.setPadding(padding, padding, padding, padding);

        LinearLayout card = new LinearLayout(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Dialog_Container);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);

        TextView title = new TextView(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Settings_Item_Header);
        title.setText("Unlock Discord");
        title.setTextColor(textNormal);
        card.addView(title);

        TextView description = new TextView(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Settings_Item_Label);
        description.setText("Enter your PIN to continue");
        description.setTextColor(textMuted);
        card.addView(description);

        final EditText[][] pinBoxesRef = new EditText[1][];
        pinBoxesRef[0] = addPinBoxes(activity, card, getPinLength(), pin -> {
            if (checkPassword(pin)) {
                locked = false;
                forceLock = false;
                lastStoppedAt = 0;
                
                if (authPrefs != null) {
                    authPrefs.edit().remove(LAST_STOPPED_AT_KEY).apply();
                }

                if (lockDialog != null) {
                    lockDialog.dismiss();
                    lockDialog = null;
                }
            } else {
                Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show();
                clearPinBoxes(pinBoxesRef[0]);
            }
        });

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        root.addView(card, cardParams);

        lockDialog = new Dialog(activity);
        lockDialog.setOwnerActivity(activity);
        if (lockDialog.getWindow() != null) {
            lockDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            lockDialog.getWindow().getAttributes().screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        lockDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        lockDialog.setCancelable(false);
        lockDialog.setContentView(root);
        lockDialog.setOnShowListener(dialog -> {
            if (lockDialog != null && lockDialog.getWindow() != null) {
                lockDialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            if (pinBoxesRef[0] != null && pinBoxesRef[0].length > 0) {
                pinBoxesRef[0][0].requestFocus();
            }
        });
        
        lockDialog.show();

        if (lockDialog.getWindow() != null) {
            lockDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lockDialog.getWindow().setBackgroundDrawable(new ColorDrawable(
                    ColorCompat.getThemedColor(activity,
                            com.lytefast.flexinput.R.b.colorBackgroundPrimary)));
        }
    }

    public boolean hasPassword() {
        return authPrefs != null
                && authPrefs.contains(PASSWORD_HASH_KEY)
                && authPrefs.contains(PASSWORD_SALT_KEY)
                && authPrefs.contains(PIN_LENGTH_KEY)
                && SCHEME_PBKDF2.equals(authPrefs.getString(PASSWORD_SCHEME_KEY, null));
    }

    public void setPassword(String password) {
        if (authPrefs == null) return;

        if (password == null || password.isEmpty()) {
            authPrefs.edit()
                    .remove(PASSWORD_HASH_KEY)
                    .remove(PASSWORD_SALT_KEY)
                    .remove(PIN_LENGTH_KEY)
                    .remove(PASSWORD_SCHEME_KEY)
                    .remove(LAST_STOPPED_AT_KEY)
                    .apply();
            settings.setBool(ENABLED_KEY, false);
            return;
        }

        if (password.length() < 4 || password.length() > 6 || !password.matches("\\d+")) {
            Toast.makeText(com.aliucord.Utils.getAppContext(),
                    "PIN must be 4 to 6 digits", Toast.LENGTH_SHORT).show();
            return;
        }

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String encodedSalt = Base64.encodeToString(salt, Base64.NO_WRAP);
        String encodedHash = hashPassword(password, encodedSalt);

        authPrefs.edit()
                .putString(PASSWORD_SALT_KEY, encodedSalt)
                .putString(PASSWORD_HASH_KEY, encodedHash)
                .putString(PASSWORD_SCHEME_KEY, SCHEME_PBKDF2)
                .putInt(PIN_LENGTH_KEY, password.length())
                .apply();
        settings.setBool(ENABLED_KEY, true);
    }

    public void showSetPinDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        int padding = DimenUtils.dpToPx(24);
        int cardPadding = DimenUtils.dpToPx(20);
        int textNormal = themedText(activity);
        int textMuted = ColorCompat.getThemedColor(activity, com.lytefast.flexinput.R.b.colorTextMuted);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(ColorCompat.getThemedColor(activity,
                com.lytefast.flexinput.R.b.colorBackgroundPrimary));
        root.setPadding(padding, padding, padding, padding);

        LinearLayout card = new LinearLayout(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Dialog_Container);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);

        TextView title = new TextView(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Settings_Item_Header);
        title.setText("Set PIN");
        title.setTextColor(textNormal);
        card.addView(title);

        TextView description = new TextView(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Settings_Item_Label);
        description.setText("Enter 4 to 6 digits.");
        description.setTextColor(textMuted);
        card.addView(description);

        EditText[] pinBoxes = addPinBoxes(activity, card, 6, null);

        Dialog dialog = new Dialog(activity);
        Button save = new Button(activity);
        save.setText("Save PIN");
        save.setTextColor(textNormal);
        save.setOnClickListener(view -> {
            String pin = collectPin(pinBoxes);
            if (pin.length() < 4 || pin.length() > 6) {
                Toast.makeText(activity, "PIN must be 4 to 6 digits", Toast.LENGTH_SHORT).show();
                return;
            }
            setPassword(pin);
            dialog.dismiss();
        });
        card.addView(save);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        root.addView(card, cardParams);

        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(
                    ColorCompat.getThemedColor(activity,
                            com.lytefast.flexinput.R.b.colorBackgroundPrimary)));
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private int getPinLength() {
        return authPrefs == null ? 4 : authPrefs.getInt(PIN_LENGTH_KEY, 4);
    }

    private EditText[] addPinBoxes(Context context, LinearLayout parent, int count,
                                   PinCompleteListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, DimenUtils.dpToPx(16), 0, 0);
        int textNormal = themedText(context);
        int textMuted = ColorCompat.getThemedColor(context,
                com.lytefast.flexinput.R.b.colorTextMuted);

        EditText[] boxes = new EditText[count];
        int size = DimenUtils.dpToPx(44);
        int margin = DimenUtils.dpToPx(4);

        for (int i = 0; i < count; i++) {
            int index = i;
            EditText box = new EditText(context);
            box.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            box.setGravity(Gravity.CENTER);
            box.setSingleLine(true);
            box.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1)});
            box.setTextSize(18f);
            box.setTextColor(textNormal);
            box.setHintTextColor(textMuted);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, 0, margin, 0);
            row.addView(box, params);
            boxes[i] = box;

            box.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable editable) {
                    if (isClearingPin) return;

                    if (editable.length() == 1) {
                        if (index + 1 < boxes.length) {
                            boxes[index + 1].requestFocus();
                        }

                        String pin = collectPin(boxes);
                        if (listener != null && pin.length() == boxes.length) {
                            listener.onComplete(pin);
                        }
                    }
                }
            });

            box.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (box.getText().length() == 0 && index > 0) {
                        EditText prevBox = boxes[index - 1];
                        prevBox.requestFocus();
                        
                        isClearingPin = true;
                        prevBox.setText("");
                        isClearingPin = false;
                        return true;
                    }
                }
                return false;
            });
        }

        parent.addView(row);
        boxes[0].post(() -> boxes[0].requestFocus());
        return boxes;
    }

    private void clearPinBoxes(EditText[] boxes) {
        if (boxes == null || boxes.length == 0) return;
        boxes[0].post(() -> {
            isClearingPin = true;
            for (EditText box : boxes) {
                if (box != null) box.setText("");
            }
            isClearingPin = false;
            if (boxes[0] != null) boxes[0].requestFocus();
        });
    }

    private String collectPin(EditText[] boxes) {
        StringBuilder pin = new StringBuilder();
        for (EditText box : boxes) {
            if (box == null || box.getText().length() == 0) break;
            pin.append(box.getText());
        }
        return pin.toString();
    }

    private interface PinCompleteListener {
        void onComplete(String pin);
    }

    private boolean checkPassword(String password) {
        if (authPrefs == null || password == null) return false;

        String salt = authPrefs.getString(PASSWORD_SALT_KEY, null);
        String expectedHash = authPrefs.getString(PASSWORD_HASH_KEY, null);
        if (salt == null || expectedHash == null) return false;

        String actualHash = hashPassword(password, salt);
        if (actualHash.length() != expectedHash.length()) return false;

        int diff = 0;
        for (int i = 0; i < expectedHash.length(); i++) {
            diff |= expectedHash.charAt(i) ^ actualHash.charAt(i);
        }
        return diff == 0;
    }

    private String hashPassword(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(),
                    Base64.decode(salt, Base64.NO_WRAP),
                    120_000,
                    256);
            byte[] hash = SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Throwable e) {
            logger.error("Failed to hash password", e);
            return "";
        }
    }

    private int themedText(Context context) {
        try {
            return ColorCompat.getThemedColor(context,
                    com.lytefast.flexinput.R.b.colorTextNormal);
        } catch (Throwable t) {
            return Color.WHITE;
        }
    }
}