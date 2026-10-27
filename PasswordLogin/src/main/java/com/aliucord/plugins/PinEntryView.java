package com.aliucord.plugins;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.aliucord.utils.DimenUtils;
import com.discord.utilities.color.ColorCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable PIN input. A row of N single-character masked boxes with:
 *   - auto-advance on entry,
 *   - backspace on an empty box jumps to / clears the previous box,
 *   - paste distribution ("1234" pasted into box 0 fills boxes 0..3),
 *   - optional auto-submit when full (used by the lock screen),
 *   - autofill hint on the first box (OTP / password managers).
 */
public class PinEntryView extends LinearLayout {

    public interface Listener {
        /** Fires on every change (typing, clear, paste distribution). */
        void onPinChanged(String current);
        /** Fires only when {@code autoSubmit} is true and all boxes are filled. */
        void onPinComplete(String pin);
    }

    private final List<PinBox> boxes = new ArrayList<>();
    private final int length;
    private final boolean autoSubmit;
    private Listener listener;
    private boolean suppress;

    public PinEntryView(Context context, int length, boolean autoSubmit) {
        super(context);
        this.length = Math.max(4, length);
        this.autoSubmit = autoSubmit;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(0, DimenUtils.dpToPx(16), 0, 0);
        build(context);
    }

    public void setListener(Listener l) { this.listener = l; }

    public void setEnabledState(boolean enabled) {
        for (EditText b : boxes) b.setEnabled(enabled);
    }

    /** Concatenation of the filled boxes; stops at the first empty box. */
    public String getPin() {
        StringBuilder sb = new StringBuilder(length);
        for (EditText b : boxes) {
            if (b.length() == 0) break;
            sb.append(b.getText());
        }
        return sb.toString();
    }

    public void clear() {
        suppress = true;
        try {
            for (EditText b : boxes) b.setText("");
        } finally {
            suppress = false;
        }
        if (!boxes.isEmpty()) boxes.get(0).requestFocus();
        if (listener != null) listener.onPinChanged(getPin());
    }

    public void focusFirst() {
        if (!boxes.isEmpty()) boxes.get(0).requestFocus();
    }

    // --------------------------------------------------------------------------------

    private void build(Context ctx) {
        int size   = DimenUtils.dpToPx(length >= 6 ? 36 : 42);
        int margin = DimenUtils.dpToPx(4);
        int text   = safeText(ctx);
        int muted  = safeMuted(ctx);

        for (int i = 0; i < length; i++) {
            final int index = i;
            PinBox box = new PinBox(ctx);
            box.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            box.setGravity(Gravity.CENTER);
            box.setSingleLine(true);
            box.setTextSize(18f);
            box.setTextColor(text);
            box.setHintTextColor(muted);

            box.setOnBackspace(() -> {
                if (index == 0) return false;
                PinBox prev = boxes.get(index - 1);
                if (prev.length() > 0) {
                    suppress = true;
                    try { prev.setText(""); } finally { suppress = false; }
                }
                prev.requestFocus();
                if (listener != null) listener.onPinChanged(getPin());
                return true;
            });

            box.addTextChangedListener(new Watcher(index));

            if (i == 0) {
                box.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    box.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
                }
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            addView(box, lp);
            boxes.add(box);
        }
    }

    private class Watcher implements TextWatcher {
        final int index;
        Watcher(int i) { this.index = i; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void afterTextChanged(Editable s) {
            if (suppress) return;

            String text = s.toString();

            // Multi-char input (paste / IME commit): distribute on the next frame.
            if (text.length() > 1) {
                String toDistribute = text;
                post(() -> distribute(index, toDistribute));
                return;
            }

            if (text.length() == 1 && index < boxes.size() - 1) {
                boxes.get(index + 1).requestFocus();
            }

            if (listener != null) listener.onPinChanged(getPin());
            if (autoSubmit && isFull()) maybeComplete();
        }
    }

    private void distribute(int start, String text) {
        suppress = true;
        try {
            for (int i = 0; i < text.length() && start + i < boxes.size(); i++) {
                boxes.get(start + i).setText(String.valueOf(text.charAt(i)));
            }
        } finally {
            suppress = false;
        }
        int last = Math.min(start + text.length() - 1, boxes.size() - 1);
        if (last >= 0 && last < boxes.size() - 1) boxes.get(last + 1).requestFocus();
        if (listener != null) listener.onPinChanged(getPin());
        if (autoSubmit && isFull()) maybeComplete();
    }

    private void maybeComplete() {
        post(() -> {
            if (listener != null && isFull()) listener.onPinComplete(getPin());
        });
    }

    private boolean isFull() {
        for (EditText b : boxes) if (b.length() == 0) return false;
        return true;
    }

    // --------------------------------------------------------------------------------

    private static int safeText(Context c) {
        try { return ColorCompat.getThemedColor(c, com.lytefast.flexinput.R.b.colorTextNormal); }
        catch (Throwable t) { return android.graphics.Color.WHITE; }
    }

    private static int safeMuted(Context c) {
        try { return ColorCompat.getThemedColor(c, com.lytefast.flexinput.R.b.colorTextMuted); }
        catch (Throwable t) { return android.graphics.Color.GRAY; }
    }

    @SuppressLint("AppCompatCustomView")
    private static class PinBox extends EditText {
        interface Backspace { boolean onBackspace(); }
        private Backspace handler;

        PinBox(Context c) { super(c); }

        void setOnBackspace(Backspace b) { this.handler = b; }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection base = super.onCreateInputConnection(outAttrs);
            if (base == null) return null;
            return new InputConnectionWrapper(base, true) {
                @Override
                public boolean deleteSurroundingText(int before, int after) {
                    if (before == 1 && after == 0 && length() == 0 && handler != null) {
                        if (handler.onBackspace()) return true;
                    }
                    return super.deleteSurroundingText(before, after);
                }

                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getKeyCode() == KeyEvent.KEYCODE_DEL
                            && length() == 0 && handler != null) {
                        if (handler.onBackspace()) return true;
                    }
                    return super.sendKeyEvent(event);
                }
            };
        }
    }
}
