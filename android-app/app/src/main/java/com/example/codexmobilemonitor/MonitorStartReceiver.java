package com.example.codexmobilemonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;

public class MonitorStartReceiver extends BroadcastReceiver {
    private static final String PREFS = "codex_monitor";
    private static final String KEY_DEVICES = "devices";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!hasDevices(context)) return;

        Intent serviceIntent = new Intent(context, CodexMonitorService.class);
        serviceIntent.setAction(CodexMonitorService.ACTION_POLL_NOW);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    private boolean hasDevices(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String rawDevices = prefs.getString(KEY_DEVICES, "[]");
        if (rawDevices == null || rawDevices.trim().isEmpty()) return false;
        try {
            return new JSONArray(rawDevices).length() > 0;
        } catch (Exception ignored) {
            return rawDevices.contains("baseUrl");
        }
    }
}
