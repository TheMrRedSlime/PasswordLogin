package com.aliucord.plugins;

import android.content.Context;
import android.graphics.Color;
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
        Context context = requireContext();
        setPadding(20);

        CheckedSetting enabled = Utils.createCheckedSetting(
                context, CheckedSetting.ViewType.SWITCH, "Enable password login", "");
        enabled.setChecked(settings.getBool(PasswordLogin.ENABLED_KEY, true));
        enabled.setOnCheckedListener(value -> settings.setBool(PasswordLogin.ENABLED_KEY, value));
        addView(enabled);

        addLockDelayRadios(context);

        TextView setPassword = createActionRow(context, "Set PIN");
        setPassword.setOnClickListener(v -> plugin.showSetPinDialog(Utils.getAppActivity()));
        addView(setPassword);

        TextView lockNow = createActionRow(context, "Lock now");
        lockNow.setOnClickListener(v -> plugin.lockNow());
        addView(lockNow);
    }

    // FIX: theme-aware text colour instead of hardcoded white
    private TextView createActionRow(Context context, String text) {
        TextView row = new TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon);
        row.setText(text);
        int colour;
        try {
            colour = ColorCompat.getThemedColor(context, R.b.colorTextNormal);
        } catch (Throwable t) {
            colour = Color.WHITE;
        }
        row.setTextColor(colour);
        return row;
    }

    private void addLockDelayRadios(Context context) {
        List<CheckedSetting> radios = Arrays.asList(
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO,
                        "Show immediately", ""),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO,
                        "After 5 seconds inactive", ""),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO,
                        "After 10 seconds inactive", ""),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO,
                        "After 30 seconds inactive", ""),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO,
                        "After 1 minute inactive", ""),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO,
                        "After 5 minutes inactive", "")
        );
        List<Integer> values = Arrays.asList(0,5_000, 10_000, 30_000, 60_000, 300_000);
        RadioManager radioManager = new RadioManager(radios);

        int selectedIndex = values.indexOf(settings.getInt(PasswordLogin.LOCK_DELAY_KEY, 0));
        if (selectedIndex == -1)
            selectedIndex = 0;

        CheckedSetting selected = radios.get(selectedIndex);
        selected.setChecked(true);
        radioManager.a(selected);

        for (int i = 0; i < radios.size(); i++) {
            int index = i;
            CheckedSetting radio = radios.get(i);
            radio.e(view -> {
                settings.setInt(PasswordLogin.LOCK_DELAY_KEY, values.get(index));
                radioManager.a(radio);
            });
            addView(radio);
        }
    }
}