package com.aliucord.plugins;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.utils.DimenUtils;
import com.discord.utilities.color.ColorCompat;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PIN lock for Aliucord.
 *
 * Rewritten from scratch. Highlights vs. the old implementation:
 *   - State for the lock dialog and the set-PIN dialog is fully separated.
 *   - PIN entry lives in {@link PinEntryView} (reused by both dialogs).
 *   - PBKDF2 hashing / verification runs off the main thread.
 *   - Lock lifecycle is derived from a small, explicit state machine
 *     (`locked`, `lockRequested`, `lastStoppedAt`) instead of ad-hoc flags.
 *   - Set-PIN uses a confirm step so a typo can't lock you out.
 *   - Theme colours are read defensively; boxes scale down for 6-digit PINs.
 */
@AliucordPlugin
@SuppressWarnings("unused")
public class PasswordLogin extends Plugin {

    public static final String ENABLED_KEY    = "enabled";
    public static final String LOCK_DELAY_KEY = "lockDelayMs";

    private static final String PIN_HASH   = "pinHash";
    private static final String PIN_SALT   = "pinSalt";
    private static final String PIN_LENGTH = "pinLength";
    private static final String PIN_SCHEME = "pinScheme";
    private static final String PIN_SCHEME_VALUE = "pbkdf2-sha256-v1";
    private static final int    PBKDF2_ITERATIONS = 150_000;

    private Application app;
    private Application.ActivityLifecycleCallbacks lifecycle;
    private Dialog lockDialog;
    private Activity lockDialogHost;

    private final Set<Activity> liveActivities =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private volatile boolean alive = false;

    /* ---- lock state ---- */
    private boolean locked = true;          // must be unlocked before next visible frame
    private boolean lockRequested = false;  // explicit "Lock now" – overrides enable toggle
    private long lastStoppedAt = 0L;        // when the last activity stopped

    // ------------------------------------------------------------------------------------
    // Plugin lifecycle
    // ------------------------------------------------------------------------------------

    @Override
    public void start(Context context) throws Throwable {
        app = (Application) context.getApplicationContext();
        alive = true;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PasswordLogin-Worker");
            t.setDaemon(true);
            return t;
        });

        settingsTab = new SettingsTab(PasswordLoginSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings, this);

        lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}

            @Override
            public void onActivityStarted(Activity a) {
                liveActivities.add(a);
            }

            @Override
            public void onActivityResumed(Activity a) {
                if (shouldShowLock()) showLockDialog(a);
            }

            @Override public void onActivityPaused(Activity a) {}

            @Override
            public void onActivityStopped(Activity a) {
                liveActivities.remove(a);
                if (liveActivities.isEmpty() && !a.isChangingConfigurations()) {
                    locked = true;
                    lastStoppedAt = System.currentTimeMillis();
                }
            }

            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}

            @Override
            public void onActivityDestroyed(Activity a) {
                if (lockDialog != null && lockDialogHost == a) dismissLockDialog();
            }
        };
        app.registerActivityLifecycleCallbacks(lifecycle);

        Activity current = Utils.getAppActivity();
        if (current != null) {
            liveActivities.add(current);
            if (shouldShowLock()) showLockDialog(current);
        }
    }

    @Override
    public void stop(Context context) throws Throwable {
        alive = false;
        try { patcher.unpatchAll(); } catch (Throwable ignored) {}
        try { commands.unregisterAll(); } catch (Throwable ignored) {}

        if (app != null && lifecycle != null) {
            try { app.unregisterActivityLifecycleCallbacks(lifecycle); } catch (Throwable ignored) {}
        }
        dismissLockDialog();

        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }

        liveActivities.clear();
        lifecycle = null;
        app = null;
        locked = true;
        lockRequested = false;
        lastStoppedAt = 0L;
    }

    // ------------------------------------------------------------------------------------
    // Public API used by the settings tab
    // ------------------------------------------------------------------------------------

    public boolean hasPin() {
        return settings.exists(PIN_HASH)
                && settings.exists(PIN_SALT)
                && settings.exists(PIN_LENGTH)
                && PIN_SCHEME_VALUE.equals(settings.getString(PIN_SCHEME, null));
    }

    public void clearPin() {
        settings.remove(PIN_HASH);
        settings.remove(PIN_SALT);
        settings.remove(PIN_LENGTH);
        settings.remove(PIN_SCHEME);
        settings.setBool(ENABLED_KEY, false);
        toast(Utils.getAppContext(), "PIN cleared");
    }

    /** Explicit "lock now" – ignores the enable toggle but still requires a PIN. */
    public void lockNow() {
        if (!hasPin()) {
            toast(Utils.getAppContext(), "Set a PIN first");
            return;
        }
        locked = true;
        lockRequested = true;
        lastStoppedAt = System.currentTimeMillis();

        Activity a = Utils.getAppActivity();
        if (a == null && !liveActivities.isEmpty()) a = liveActivities.iterator().next();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) showLockDialog(a);
    }

    // ------------------------------------------------------------------------------------
    // Lock dialog
    // ------------------------------------------------------------------------------------

    private boolean shouldShowLock() {
        if (!hasPin()) return false;
        if (lockRequested) return true;
        if (!settings.getBool(ENABLED_KEY, true)) return false;
        if (!locked) return false;

        int delay = settings.getInt(LOCK_DELAY_KEY, 0);
        if (delay <= 0) return true;
        return System.currentTimeMillis() - lastStoppedAt >= delay;
    }

    private int getPinLength() {
        int n = settings.getInt(PIN_LENGTH, 4);
        return Math.max(4, Math.min(6, n));
    }

    private void showLockDialog(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (lockDialog != null && lockDialog.isShowing()) {
            if (lockDialogHost == activity) return;
            dismissLockDialog();
        }
        lockDialog = buildLockDialog(activity);
        lockDialogHost = activity;
        lockDialog.show();
    }

    private void dismissLockDialog() {
        if (lockDialog != null) {
            try { lockDialog.dismiss(); } catch (Throwable ignored) {}
            lockDialog = null;
            lockDialogHost = null;
        }
    }

    private Dialog buildLockDialog(Activity activity) {
        int pad     = DimenUtils.dpToPx(16);
        int cardPad = DimenUtils.dpToPx(20);
        int text    = safeText(activity);
        int muted   = safeMuted(activity);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(safeBg(activity));
        root.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Dialog_Container);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Settings_Item_Header);
        title.setText("Unlock Discord");
        title.setTextColor(text);
        card.addView(title);

        TextView subtitle = new TextView(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Settings_Item_Label);
        subtitle.setText("Enter your PIN to continue");
        subtitle.setTextColor(muted);
        card.addView(subtitle);

        PinEntryView pin = new PinEntryView(activity, getPinLength(), true);
        card.addView(pin);

        ProgressBar progress = new ProgressBar(activity);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                DimenUtils.dpToPx(28), DimenUtils.dpToPx(28));
        pLp.topMargin = DimenUtils.dpToPx(12);
        card.addView(progress, pLp);

        pin.setListener(new PinEntryView.Listener() {
            @Override public void onPinChanged(String current) { /* not used */ }

            @Override
            public void onPinComplete(String value) {
                pin.setEnabledState(false);
                progress.setVisibility(View.VISIBLE);
                verifyAsync(value, ok -> {
                    if (ok) {
                        locked = false;
                        lockRequested = false;
                        lastStoppedAt = 0L;
                        dismissLockDialog();
                    } else {
                        progress.setVisibility(View.GONE);
                        pin.setEnabledState(true);
                        pin.clear();
                        toast(activity, "Wrong PIN");
                    }
                });
            }
        });

        root.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        d.setContentView(root);

        Window w = d.getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.setOnShowListener(x -> {
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                w.setBackgroundDrawable(new ColorDrawable(safeBg(activity)));
            }
            pin.focusFirst();
        });
        return d;
    }

    // ------------------------------------------------------------------------------------
    // Set PIN dialog (enter → confirm → save)
    // ------------------------------------------------------------------------------------

    public void showSetPinDialog(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        int pad     = DimenUtils.dpToPx(16);
        int cardPad = DimenUtils.dpToPx(20);
        int text    = safeText(activity);
        int muted   = safeMuted(activity);

        FrameLayout root = new FrameLayout(activity);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Dialog_Container);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Settings_Item_Header);
        title.setTextColor(text);
        card.addView(title);

        TextView subtitle = new TextView(activity, null, 0,
                com.lytefast.flexinput.R.i.UiKit_Settings_Item_Label);
        subtitle.setTextColor(muted);
        card.addView(subtitle);

        PinEntryView pin = new PinEntryView(activity, 6, false);
        card.addView(pin);

        Button action = new Button(activity);
        action.setTextColor(text);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = DimenUtils.dpToPx(12);
        card.addView(action, aLp);

        Button cancel = new Button(activity);
        cancel.setText("Cancel");
        cancel.setTextColor(muted);
        card.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(true);
        d.setContentView(root);

        final String[]  first = {null};
        final boolean[] busy  = {false};

        Runnable refresh = () -> {
            String cur = pin.getPin();
            if (first[0] == null) {
                title.setText("Set PIN");
                subtitle.setText("Enter 4–6 digits");
                action.setText("Next");
                action.setEnabled(!busy[0] && cur.length() >= 4 && cur.length() <= 6);
            } else {
                title.setText("Confirm PIN");
                subtitle.setText("Re-enter your PIN");
                action.setText("Save");
                action.setEnabled(!busy[0] && cur.length() == first[0].length());
            }
        };

        pin.setListener(new PinEntryView.Listener() {
            @Override public void onPinChanged(String current) { refresh.run(); }
            @Override public void onPinComplete(String value) { /* autoSubmit is off */ }
        });

        action.setOnClickListener(v -> {
            if (busy[0]) return;
            String cur = pin.getPin();

            if (first[0] == null) {
                if (cur.length() < 4 || cur.length() > 6) {
                    toast(activity, "PIN must be 4–6 digits");
                    return;
                }
                first[0] = cur;
                pin.clear();
                refresh.run();
            } else {
                if (!cur.equals(first[0])) {
                    toast(activity, "PINs don't match");
                    first[0] = null;
                    pin.clear();
                    refresh.run();
                    return;
                }
                busy[0] = true;
                refresh.run();
                savePinAsync(cur,
                        () -> { toast(activity, "PIN saved"); d.dismiss(); },
                        () -> { busy[0] = false; refresh.run(); toast(activity, "Failed to save PIN"); });
            }
        });

        cancel.setOnClickListener(v -> d.dismiss());

        refresh.run();

        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(safeBg(activity)));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        pin.focusFirst();
    }

    // ------------------------------------------------------------------------------------
    // Crypto (always off the main thread)
    // ------------------------------------------------------------------------------------

    private interface BoolCallback { void onResult(boolean ok); }

    private void verifyAsync(String pin, BoolCallback cb) {
        if (worker == null) return;
        worker.submit(() -> {
            boolean ok = verifyPin(pin);
            mainHandler.post(() -> {
                if (alive) cb.onResult(ok);
            });
        });
    }

    private void savePinAsync(String pin, Runnable onSuccess, Runnable onFailure) {
        if (worker == null) { onFailure.run(); return; }
        worker.submit(() -> {
            try {
                byte[] saltBytes = new byte[16];
                new SecureRandom().nextBytes(saltBytes);
                String salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP);
                String hash = computeHash(pin, salt);
                if (hash == null) throw new IllegalStateException("hash failed");

                mainHandler.post(() -> {
                    if (!alive) return;
                    settings.setString(PIN_SALT,   salt);
                    settings.setString(PIN_HASH,   hash);
                    settings.setString(PIN_SCHEME, PIN_SCHEME_VALUE);
                    settings.setInt   (PIN_LENGTH, pin.length());
                    settings.setBool  (ENABLED_KEY, true);
                    onSuccess.run();
                });
            } catch (Throwable t) {
                logger.error("savePin failed", t);
                mainHandler.post(() -> {
                    if (alive) onFailure.run();
                });
            }
        });
    }

    private boolean verifyPin(String pin) {
        String salt     = settings.getString(PIN_SALT,   null);
        String expected = settings.getString(PIN_HASH,   null);
        String scheme   = settings.getString(PIN_SCHEME, null);
        if (salt == null || expected == null || !PIN_SCHEME_VALUE.equals(scheme)) return false;

        String actual = computeHash(pin, salt);
        if (actual == null) return false;

        try {
            return MessageDigest.isEqual(
                    Base64.decode(expected, Base64.NO_WRAP),
                    Base64.decode(actual,   Base64.NO_WRAP));
        } catch (Throwable t) {
            return false;
        }
    }

    private String computeHash(String pin, String saltB64) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    pin.toCharArray(),
                    Base64.decode(saltB64, Base64.NO_WRAP),
                    PBKDF2_ITERATIONS,
                    256);
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Throwable t) {
            logger.error("hash failed", t);
            return null;
        }
    }

    // ------------------------------------------------------------------------------------
    // Theme helpers
    // ------------------------------------------------------------------------------------

    private static void toast(Context c, String msg) {
        try { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    private static int safeText(Context c) {
        try { return ColorCompat.getThemedColor(c, com.lytefast.flexinput.R.b.colorTextNormal); }
        catch (Throwable t) { return Color.WHITE; }
    }

    private static int safeMuted(Context c) {
        try { return ColorCompat.getThemedColor(c, com.lytefast.flexinput.R.b.colorTextMuted); }
        catch (Throwable t) { return Color.GRAY; }
    }

    private static int safeBg(Context c) {
        try { return ColorCompat.getThemedColor(c, com.lytefast.flexinput.R.b.colorBackgroundPrimary); }
        catch (Throwable t) { return Color.BLACK; }
    }
}
