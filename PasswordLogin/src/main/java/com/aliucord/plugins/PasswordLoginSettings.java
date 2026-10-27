package com.aliucord.plugins;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.aliucord.Utils;
import com.aliucord.api.SettingsAPI;
import com.aliucord.widgets.BottomSheet;
import com.discord.utilities.color.ColorCompat;
import com.discord.views.CheckedSetting;
import com.discord.views.RadioManager;
import com.lytefast.flexinput.R;

import java.util.Arrays;
import java.util.List;

public class PasswordLoginSettings extends BottomSheet {

    private final SettingsAPI settings;
    private final PasswordLogin plugin;

    public PasswordLoginSettings(SettingsAPI settings, PasswordLogin plugin) {
        this.settings = settings;
        this.plugin = plugin;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context ctx = requireContext();
        setPadding(20);

        CheckedSetting enabled = Utils.createCheckedSetting(
                ctx, CheckedSetting.ViewType.SWITCH, "Enable password lock", "");
        enabled.setChecked(settings.getBool(PasswordLogin.ENABLED_KEY, true));
        enabled.setOnCheckedListener(v -> settings.setBool(PasswordLogin.ENABLED_KEY, v));
        addView(enabled);

        addHeader(ctx, "Lock delay");
        addLockDelayRadios(ctx);

        addHeader(ctx, "PIN");

        TextView setPin = actionRow(ctx, plugin.hasPin() ? "Change PIN" : "Set PIN");
        setPin.setOnClickListener(v -> plugin.showSetPinDialog(Utils.getAppActivity()));
        addView(setPin);

        if (plugin.hasPin()) {
            TextView clearPin = actionRow(ctx, "Clear PIN");
            clearPin.setOnClickListener(v -> plugin.clearPin());
            addView(clearPin);
        }

        TextView lockNow = actionRow(ctx, "Lock now");
        lockNow.setOnClickListener(v -> plugin.lockNow());
        addView(lockNow);
    }

    private void addHeader(Context ctx, String text) {
        TextView header = new TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header);
        header.setText(text);
        addView(header);
    }

    private TextView actionRow(Context ctx, String text) {
        TextView row = new TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon);
        row.setText(text);
        try {
            row.setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorTextNormal));
        } catch (Throwable t) {
            row.setTextColor(0xFFFFFFFF);
        }
        return row;
    }

    private void addLockDelayRadios(Context ctx) {
        List<CheckedSetting> radios = Arrays.asList(
                Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, "Immediately", ""),
                Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, "After 5 seconds", ""),
                Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, "After 10 seconds", ""),
                Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, "After 30 seconds", ""),
                Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, "After 1 minute", ""),
                Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, "After 5 minutes", "")
        );
        List<Integer> values = Arrays.asList(0, 5_000, 10_000, 30_000, 60_000, 300_000);
        RadioManager mgr = new RadioManager(radios);

        int idx = values.indexOf(settings.getInt(PasswordLogin.LOCK_DELAY_KEY, 0));
        if (idx < 0) idx = 0;
        CheckedSetting selected = radios.get(idx);
        selected.setChecked(true);
        mgr.a(selected);

        for (int i = 0; i < radios.size(); i++) {
            final int index = i;
            CheckedSetting radio = radios.get(i);
            radio.e(v -> {
                settings.setInt(PasswordLogin.LOCK_DELAY_KEY, values.get(index));
                mgr.a(radio);
            });
            addView(radio);
        }
    }
}
