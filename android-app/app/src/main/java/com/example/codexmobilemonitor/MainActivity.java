package com.example.codexmobilemonitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String PREFS = "codex_monitor";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_AGENT_TOKEN = "agent_token";
    private static final String KEY_DEVICES = "devices";
    private static final String KEY_DEVICE_STATUS = "device_status";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final String KEY_ENGLISH = "english";
    private static final String KEY_POLL_INTERVAL_SECONDS = "poll_interval_seconds";
    private static final String KEY_POLL_INTERVAL_MILLIS = "poll_interval_millis";
    private static final String DEFAULT_SERVER_URL = "";
    private static final String CHANNEL_ID = "codex_monitor_alerts_v3";
    private static final int AGENT_PORT = 8787;
    private static final int DEFAULT_POLL_INTERVAL_MILLIS = 500;
    private static final int ACTIVE_TO_IDLE_CONFIRM_READS = 3;
    private static final int[] POLL_INTERVAL_OPTIONS_MILLIS = {500, 1000, 2000, 5000, 10000, 30000};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<DeviceState> devices = new ArrayList<>();
    private final ArrayList<String> scanResults = new ArrayList<>();

    private EditText serverInput;
    private EditText tokenInput;
    private TextView scanText;
    private LinearLayout deviceGrid;
    private LinearLayout configArea;
    private LinearLayout scanResultsList;
    private LinearLayout summaryStrip;
    private Button pollIntervalButton;
    private TextView stateText;
    private TextView detailText;
    private TextView commandText;
    private TextView outboundText;
    private TextView fiveHourText;
    private TextView weeklyText;
    private TextView fiveResetText;
    private TextView weeklyResetText;
    private TextView apiCostText;
    private TextView apiCostDetailText;
    private ProgressBar fiveHourBar;
    private ProgressBar weeklyBar;
    private LinearLayout apiCard;
    private Button apiToggleButton;
    private boolean showApiCost = false;
    private boolean isDarkMode = true;
    private boolean isEnglish = false;
    private boolean showConfig = false;
    private final Set<String> expandedBaseUrls = new HashSet<>();
    private int pollIntervalMillis = DEFAULT_POLL_INTERVAL_MILLIS;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            refreshSelectedDevices();
            handler.postDelayed(this, pollIntervalMillis);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        isDarkMode = prefs.getBoolean(KEY_DARK_MODE, true);
        isEnglish = prefs.getBoolean(KEY_ENGLISH, false);
        pollIntervalMillis = readPollIntervalMillis(prefs);

        setContentView(buildView());
        requestNotificationPermission();

        serverInput.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        tokenInput.setText(prefs.getString(KEY_AGENT_TOKEN, ""));
        loadDevices(prefs);
        renderDeviceList();
        startMonitorService();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        startMonitorService();
        refreshSelectedDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startMonitorService();
        handler.post(poller);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poller);
        saveDevices();
        super.onPause();
    }

    private View buildView() {
        int colorBg = isDarkMode ? Color.parseColor("#000000") : Color.parseColor("#f2f2f7");
        int colorTitle = isDarkMode ? Color.parseColor("#f5f5f7") : Color.parseColor("#1d1d1f");
        int colorTextSec = isDarkMode ? Color.parseColor("#8e8e93") : Color.parseColor("#6e6e73");

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(colorBg);
            getWindow().setNavigationBarColor(colorBg);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            View decorView = getWindow().getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (isDarkMode) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                if (isDarkMode) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decorView.setSystemUiVisibility(flags);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(colorBg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(24), dp(14), dp(20));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int statusInset = insets.getSystemWindowInsetTop();
            int navigationInset = insets.getSystemWindowInsetBottom();
            view.setPadding(
                    dp(14),
                    Math.max(dp(24), statusInset + dp(10)),
                    dp(14),
                    Math.max(dp(20), navigationInset + dp(8))
            );
            return insets;
        });
        scrollView.addView(root);
        root.requestApplyInsets();

        TextView title = text("Codex Monitor", 26, colorTitle, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        root.addView(title);

        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlRowParams = new LinearLayout.LayoutParams(-1, -2);
        controlRowParams.setMargins(0, dp(8), 0, dp(14));

        summaryStrip = new LinearLayout(this);
        summaryStrip.setOrientation(LinearLayout.HORIZONTAL);
        summaryStrip.setGravity(Gravity.CENTER_VERTICAL);
        controlRow.addView(summaryStrip, new LinearLayout.LayoutParams(0, -2, 1));

        String configText = t("设置", "Config");
        Button configBtn = miniButton(configText);
        configBtn.setOnClickListener(v -> {
            showConfig = !showConfig;
            configArea.setVisibility(showConfig ? View.VISIBLE : View.GONE);
            renderDeviceList();
        });
        controlRow.addView(configBtn, new LinearLayout.LayoutParams(buttonWidth(configText, 58), dp(34)));

        String langText = isEnglish ? "ZH" : "EN";
        Button langBtn = miniButton(langText);
        langBtn.setOnClickListener(v -> toggleLanguage());
        LinearLayout.LayoutParams langParams = new LinearLayout.LayoutParams(buttonWidth(langText, 46), dp(34));
        langParams.setMargins(dp(8), 0, 0, 0);
        controlRow.addView(langBtn, langParams);

        String themeText = isDarkMode ? t("浅", "Light") : t("深", "Dark");
        Button themeBtn = miniButton(themeText);
        themeBtn.setOnClickListener(v -> toggleTheme());
        LinearLayout.LayoutParams themeParams = new LinearLayout.LayoutParams(buttonWidth(themeText, 46), dp(34));
        themeParams.setMargins(dp(8), 0, 0, 0);
        controlRow.addView(themeBtn, themeParams);

        root.addView(controlRow, controlRowParams);

        // Collapsible Config
        configArea = card();
        configArea.setVisibility(showConfig ? View.VISIBLE : View.GONE);
        configArea.addView(label(t("状态检查周期", "Status Check Interval")));
        pollIntervalButton = button(
                pollIntervalLabel(),
                isDarkMode ? Color.parseColor("#21262d") : Color.parseColor("#f3f4f6")
        );
        if (!isDarkMode) pollIntervalButton.setTextColor(Color.BLACK);
        pollIntervalButton.setOnClickListener(v -> showPollIntervalDialog());
        LinearLayout.LayoutParams intervalParams = new LinearLayout.LayoutParams(-1, dp(38));
        intervalParams.setMargins(0, dp(5), 0, dp(10));
        configArea.addView(pollIntervalButton, intervalParams);

        LinearLayout notificationRow = new LinearLayout(this);
        notificationRow.setOrientation(LinearLayout.HORIZONTAL);
        Button notificationSettings = button(
                t("通知设置", "Notification Settings"),
                isDarkMode ? Color.parseColor("#21262d") : Color.parseColor("#f3f4f6")
        );
        if (!isDarkMode) notificationSettings.setTextColor(Color.BLACK);
        notificationSettings.setOnClickListener(v -> openAlertNotificationSettings());
        notificationRow.addView(notificationSettings, new LinearLayout.LayoutParams(0, dp(38), 1));

        Button testNotification = button(t("测试提醒", "Test Alert"), Color.parseColor("#007aff"));
        testNotification.setOnClickListener(v -> sendNotification(
                t("Codex Monitor 测试提醒", "Codex Monitor Test Alert"),
                t("如果手机震动并显示顶部横幅，设备状态提醒已正常开启。", "Vibration and heads-up alerts are enabled."),
                19001
        ));
        LinearLayout.LayoutParams testNotificationParams = new LinearLayout.LayoutParams(0, dp(38), 1);
        testNotificationParams.setMargins(dp(8), 0, 0, 0);
        notificationRow.addView(testNotification, testNotificationParams);
        LinearLayout.LayoutParams notificationRowParams = new LinearLayout.LayoutParams(-1, -2);
        notificationRowParams.setMargins(0, 0, 0, dp(10));
        configArea.addView(notificationRow, notificationRowParams);

        configArea.addView(label(t("手动连接", "Manual Connection")));
        configArea.addView(label(t("电脑端地址", "Server URL")));
        serverInput = edit();
        serverInput.setHint("http://192.168.x.x:8787");
        configArea.addView(serverInput, new LinearLayout.LayoutParams(-1, dp(48)));
        tokenInput = edit();
        tokenInput.setHint(t("访问令牌 (可选)", "Token (Optional)"));
        configArea.addView(tokenInput, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button addButton = button(t("添加", "Add"), isDarkMode ? Color.parseColor("#21262d") : Color.parseColor("#f3f4f6"));
        if (!isDarkMode) addButton.setTextColor(Color.BLACK);
        addButton.setOnClickListener(v -> {
            addOrSelectDevice(cleanBaseUrl());
            saveDevices();
            startMonitorService(true);
            renderDeviceList();
            refreshSelectedDevicesSoon();
        });
        btnRow.addView(addButton, new LinearLayout.LayoutParams(0, dp(36), 1));
        
        Button scanButton = button(t("扫描", "Scan"), Color.parseColor("#238636"));
        scanButton.setOnClickListener(v -> scanLanDevices());
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(0, dp(36), 1);
        scanParams.setMargins(dp(8), 0, 0, 0);
        btnRow.addView(scanButton, scanParams);
        configArea.addView(btnRow);

        scanText = small("");
        configArea.addView(scanText);
        scanResultsList = new LinearLayout(this);
        scanResultsList.setOrientation(LinearLayout.VERTICAL);
        configArea.addView(scanResultsList);
        root.addView(configArea);

        // Device Matrix
        LinearLayout matrixHeader = new LinearLayout(this);
        matrixHeader.setOrientation(LinearLayout.HORIZONTAL);
        matrixHeader.setGravity(Gravity.CENTER_VERTICAL);
        matrixHeader.setPadding(0, dp(8), 0, dp(4));
        matrixHeader.addView(label(t("设备状态", "Devices")));
        root.addView(matrixHeader);
        deviceGrid = new LinearLayout(this);
        deviceGrid.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceGrid);

        return scrollView;
    }

    private void toggleLanguage() {
        saveDevices();
        isEnglish = !isEnglish;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENGLISH, isEnglish).apply();
        rebuildViewFromPrefs();
    }

    private void toggleTheme() {
        saveDevices();
        isDarkMode = !isDarkMode;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DARK_MODE, isDarkMode).apply();
        rebuildViewFromPrefs();
    }

    private void rebuildViewFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        isDarkMode = prefs.getBoolean(KEY_DARK_MODE, true);
        isEnglish = prefs.getBoolean(KEY_ENGLISH, false);
        pollIntervalMillis = readPollIntervalMillis(prefs);
        setContentView(buildView());
        serverInput.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        tokenInput.setText(prefs.getString(KEY_AGENT_TOKEN, ""));
        loadDevices(prefs);
        renderDeviceList();
        startMonitorService();
    }

    private void showPollIntervalDialog() {
        String[] labels = new String[POLL_INTERVAL_OPTIONS_MILLIS.length];
        int checked = 0;
        for (int i = 0; i < POLL_INTERVAL_OPTIONS_MILLIS.length; i++) {
            labels[i] = intervalOptionLabel(POLL_INTERVAL_OPTIONS_MILLIS[i]);
            if (POLL_INTERVAL_OPTIONS_MILLIS[i] == pollIntervalMillis) checked = i;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("状态检查周期", "Status Check Interval"))
                .setSingleChoiceItems(labels, checked, null)
                .setNegativeButton(t("取消", "Cancel"), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            applyPollInterval(POLL_INTERVAL_OPTIONS_MILLIS[position]);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void applyPollInterval(int millis) {
        pollIntervalMillis = sanitizePollIntervalMillis(millis);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_POLL_INTERVAL_MILLIS, pollIntervalMillis)
                .remove(KEY_POLL_INTERVAL_SECONDS)
                .apply();
        if (pollIntervalButton != null) pollIntervalButton.setText(pollIntervalLabel());
        handler.removeCallbacks(poller);
        handler.post(poller);
        startMonitorService();
    }

    private int readPollIntervalMillis(SharedPreferences prefs) {
        if (prefs.contains(KEY_POLL_INTERVAL_MILLIS)) {
            return sanitizePollIntervalMillis(prefs.getInt(KEY_POLL_INTERVAL_MILLIS, DEFAULT_POLL_INTERVAL_MILLIS));
        }
        if (prefs.contains(KEY_POLL_INTERVAL_SECONDS)) {
            return sanitizePollIntervalMillis(prefs.getInt(KEY_POLL_INTERVAL_SECONDS, 10) * 1000);
        }
        return DEFAULT_POLL_INTERVAL_MILLIS;
    }

    private int sanitizePollIntervalMillis(int millis) {
        for (int option : POLL_INTERVAL_OPTIONS_MILLIS) {
            if (option == millis) return millis;
        }
        return DEFAULT_POLL_INTERVAL_MILLIS;
    }

    private String pollIntervalLabel() {
        return t("每 ", "Every ") + intervalOptionLabel(pollIntervalMillis);
    }

    private String intervalOptionLabel(int millis) {
        if (millis < 1000) return "0.5" + t(" 秒", " seconds");
        int seconds = millis / 1000;
        return seconds + t(" 秒", seconds == 1 ? " second" : " seconds");
    }

    private String t(String zh, String en) {
        return isEnglish ? en : zh;
    }

    private void refreshSelectedDevices() {
        loadDevices(getSharedPreferences(PREFS, MODE_PRIVATE));
        renderDeviceList();
    }

    private void refreshSelectedDevicesSoon() {
        handler.postDelayed(this::refreshSelectedDevices, 400);
        handler.postDelayed(this::refreshSelectedDevices, 1200);
        handler.postDelayed(this::refreshSelectedDevices, 2500);
    }

    private void scanLanDevices() {
        scanText.setText(t("扫描中...", "Scanning..."));
        scanResults.clear();
        renderScanResults();
        new Thread(() -> {
            Set<String> prefixes = localSubnetPrefixes();
            if (prefixes.isEmpty()) {
                runOnUiThread(() -> scanText.setText(t("无局域网。", "No LAN.")));
                return;
            }

            ExecutorService pool = Executors.newFixedThreadPool(32);
            CountDownLatch latch = new CountDownLatch(prefixes.size() * 254);
            Set<String> found = new HashSet<>();

            for (String prefix : prefixes) {
                for (int i = 1; i <= 254; i++) {
                    final String baseUrl = "http://" + prefix + "." + i + ":" + AGENT_PORT;
                    pool.execute(() -> {
                        try {
                            if (isCodexAgent(baseUrl)) {
                                synchronized (found) {
                                    found.add(baseUrl);
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            try {
                latch.await(18, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            pool.shutdownNow();

            runOnUiThread(() -> {
                scanResults.clear();
                scanResults.addAll(found);
                Collections.sort(scanResults);
                renderScanResults();
                scanText.setText(t("扫描完成，发现 ", "Scan complete, found ") + found.size());
            });
        }).start();
    }

    private void renderScanResults() {
        if (scanResultsList == null) return;
        scanResultsList.removeAllViews();
        if (scanResults.isEmpty()) return;

        TextView title = label(t("局域网扫描结果", "LAN Scan Results"));
        title.setPadding(0, dp(8), 0, dp(2));
        scanResultsList.addView(title);
        for (String baseUrl : scanResults) {
            scanResultsList.addView(scanResultRow(baseUrl));
        }
    }

    private View scanResultRow(String baseUrl) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(isDarkMode ? Color.parseColor("#2c2c2e") : Color.parseColor("#f2f2f7"));
        bg.setCornerRadius(dp(8));
        row.setBackground(bg);

        TextView url = text(baseUrl.replace("http://", "").replace("https://", ""), 12,
                isDarkMode ? Color.parseColor("#f5f5f7") : Color.parseColor("#1d1d1f"), Typeface.NORMAL);
        url.setSingleLine(true);
        row.addView(url, new LinearLayout.LayoutParams(0, -2, 1));

        boolean exists = hasDevice(baseUrl);
        String connectText = exists ? t("已连接", "Added") : t("连接", "Connect");
        Button connect = miniButton(connectText);
        connect.setEnabled(!exists);
        connect.setOnClickListener(v -> {
            addDevice(baseUrl, true);
            serverInput.setText(baseUrl);
            saveDevices();
            startMonitorService(true);
            renderDeviceList();
            renderScanResults();
            refreshSelectedDevicesSoon();
        });
        row.addView(connect, new LinearLayout.LayoutParams(buttonWidth(connectText, 68), dp(32)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(params);
        return row;
    }

    private boolean isCodexAgent(String baseUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/api/health").openConnection();
            connection.setConnectTimeout(450);
            connection.setReadTimeout(700);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject getJson(String target) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod("GET");
        String token = tokenInput.getText().toString().trim();
        if (!token.isEmpty()) {
            connection.setRequestProperty("X-Agent-Token", token);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return new JSONObject(builder.toString());
        } finally {
            connection.disconnect();
        }
    }

    private void updateDeviceFromPayload(DeviceState device, JSONObject payload) {
        device.online = true;
        device.stale = false;
        device.payload = payload;

        JSONObject server = payload.optJSONObject("server");
        if (server != null) {
            String host = server.optString("hostname", "");
            if (!host.isEmpty()) {
                device.name = host;
            }
        }

        JSONObject codex = payload.optJSONObject("codex");
        JSONObject activity = codex != null ? codex.optJSONObject("activity") : null;
        JSONObject limits = codex != null ? codex.optJSONObject("rateLimits") : null;
        JSONObject primary = limits != null ? limits.optJSONObject("primary") : null;
        JSONObject secondary = limits != null ? limits.optJSONObject("secondary") : null;

        String previousCommand = device.command;
        device.statusLabel = activity != null ? activity.optString("label", "--") : "--";
        device.detail = activity != null ? activity.optString("detail", "--") : "--";
        String incomingCommand = commandFromPayload(payload);
        device.command = stabilizeCommand(device, incomingCommand);
        device.fivePercent = readPercent(primary);
        device.weeklyPercent = readPercent(secondary);
        device.fiveResetAt = primary != null ? primary.optString("resetsAt", "") : "";
        device.weeklyResetAt = secondary != null ? secondary.optString("resetsAt", "") : "";
        device.fiveResetInSeconds = primary != null ? primary.optInt("resetInSeconds", -1) : -1;
        device.weeklyResetInSeconds = secondary != null ? secondary.optInt("resetInSeconds", -1) : -1;

        if (isEnglish) {
            if ("未连接".equals(device.statusLabel)) device.statusLabel = "Not Connected";
            if ("连接失败".equals(device.statusLabel)) device.statusLabel = "Conn Failed";
            if ("完成".equals(device.statusLabel)) device.statusLabel = "Done";
            if ("未知".equals(device.statusLabel)) device.statusLabel = "Unknown";
            if ("空闲".equals(device.statusLabel)) device.statusLabel = "IDLE";
            if ("运行中".equals(device.statusLabel)) device.statusLabel = "Running";
            if ("思考中".equals(device.statusLabel)) device.statusLabel = "Thinking";
            if ("编写中".equals(device.statusLabel)) device.statusLabel = "Writing";
            if ("待确认".equals(device.statusLabel)) device.statusLabel = "Need Confirm";
        }

        if (isEnglish) {
            if ("尚未识别到明确状态。".equals(device.detail)) device.detail = "No clear status recognized yet.";
            if ("上一轮任务已经完成。".equals(device.detail)) device.detail = "Previous task completed.";
            if ("Codex 正在运行。".equals(device.detail)) device.detail = "Codex is running.";
        }

        maybeNotify(device, previousCommand);
    }

    private String commandFromPayload(JSONObject payload) {
        if (payloadIndicatesPermission(payload)) return "NEED_CONFIRM";

        String activityCommand = commandFromActivity(payload);
        JSONObject agentCore = payload.optJSONObject("agentCore");
        String command = normalizeCommand(agentCore != null ? agentCore.optString("command", "") : "");

        if (isActiveCommand(activityCommand) && !isActiveCommand(command)) {
            return activityCommand;
        }
        if (!command.isEmpty()) {
            return command;
        }
        if (!activityCommand.isEmpty()) {
            return activityCommand;
        }
        return "UNKNOWN";
    }

    private String commandFromActivity(JSONObject payload) {
        JSONObject codex = payload.optJSONObject("codex");
        JSONObject activity = codex != null ? codex.optJSONObject("activity") : null;
        if (activity == null) return "";

        String status = activity.optString("status", "");
        JSONObject lastEvent = activity.optJSONObject("lastEvent");
        String toolName = lastEvent != null ? lastEvent.optString("toolName", "") : "";

        if ("needs_permission".equals(status)) return "NEED_CONFIRM";
        if ("running_tool".equals(status)) return "apply_patch".equals(toolName) ? "WRITING" : "RUNNING";
        if ("thinking".equals(status) || "starting".equals(status) || "replying".equals(status)) return "THINKING";
        if ("completed".equals(status)) return "DONE";
        if ("error".equals(status)) return "ERROR";
        if ("idle".equals(status)) return "IDLE";
        return "";
    }

    private boolean payloadIndicatesPermission(JSONObject payload) {
        JSONObject codex = payload.optJSONObject("codex");
        JSONObject activity = codex != null ? codex.optJSONObject("activity") : null;
        if (activity != null) {
            if ("needs_permission".equals(activity.optString("status", ""))) return true;
            if (looksLikePermissionRequest(activity.optString("label", ""))) return true;
            if (looksLikePermissionRequest(activity.optString("detail", ""))) return true;
        }

        JSONObject agentCore = payload.optJSONObject("agentCore");
        if (agentCore != null) {
            if ("NEED_CONFIRM".equals(normalizeCommand(agentCore.optString("command", "")))) return true;
            if (looksLikePermissionRequest(agentCore.optString("statusLabel", ""))) return true;
            if (looksLikePermissionRequest(agentCore.optString("statusDetail", ""))) return true;
        }

        JSONArray notifications = payload.optJSONArray("notifications");
        if (notifications != null) {
            for (int i = 0; i < notifications.length(); i++) {
                JSONObject item = notifications.optJSONObject(i);
                if (item == null) continue;
                if (looksLikePermissionRequest(item.optString("title", ""))) return true;
                if (looksLikePermissionRequest(item.optString("message", ""))) return true;
            }
        }
        return false;
    }

    private boolean looksLikePermissionRequest(String value) {
        if (value == null) return false;
        String text = value.trim().toLowerCase();
        if (text.isEmpty()) return false;
        return text.contains("needs_permission")
                || text.contains("need_confirm")
                || text.contains("needs permission")
                || text.contains("need permission")
                || text.contains("permission request")
                || text.contains("approval request")
                || text.contains("requires approval")
                || text.contains("waiting for approval")
                || text.contains("confirm permission")
                || text.contains("需要权限")
                || text.contains("等待权限")
                || text.contains("确认权限")
                || text.contains("权限请求");
    }

    private String normalizeCommand(String value) {
        if (value == null) return "";
        String command = value.trim().toUpperCase();
        if (command.isEmpty() || "--".equals(command) || "UNKNOWN".equals(command)) return "";
        if ("CONFIRM".equals(command) || "PERMISSION".equals(command)) return "NEED_CONFIRM";
        return command;
    }

    private String stabilizeCommand(DeviceState device, String incomingCommand) {
        if ("IDLE".equals(incomingCommand) && isActiveCommand(device.command)) {
            device.consecutiveIdleReads++;
            if (device.consecutiveIdleReads < ACTIVE_TO_IDLE_CONFIRM_READS) {
                return device.command;
            }
        } else {
            device.consecutiveIdleReads = 0;
        }
        return incomingCommand;
    }

    private boolean isActiveCommand(String command) {
        return "THINKING".equals(command)
                || "RUNNING".equals(command)
                || "WRITING".equals(command)
                || "NEED_CONFIRM".equals(command);
    }

    private void renderDeviceList() {
        deviceGrid.removeAllViews();
        renderSummary();
        if (devices.isEmpty()) {
            deviceGrid.addView(small(t("还没有设备。", "No devices.")));
            return;
        }

        for (DeviceState device : devices) {
            deviceGrid.addView(deviceDashboardItem(device));
        }
    }

    private View deviceDashboardItem(DeviceState device) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(12), dp(11), dp(12), dp(11));
        item.setMinimumHeight(dp(104));
        
        int colorCard = isDarkMode ? Color.parseColor("#1c1c1e") : Color.WHITE;
        boolean selectedDetail = expandedBaseUrls.contains(device.baseUrl);
        int colorBorder = selectedDetail ? Color.parseColor("#007aff") : (isDarkMode ? Color.parseColor("#2c2c2e") : Color.parseColor("#d1d1d6"));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorCard);
        bg.setStroke(selectedDetail ? dp(2) : dp(1), colorBorder);
        bg.setCornerRadius(dp(8));
        item.setBackground(bg);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        String signalState = signalState(device);
        int signalColor = signalColor(signalState);
        titleRow.addView(signalLights(signalState), new LinearLayout.LayoutParams(-2, dp(18)));

        TextView name = text(device.name, 14, isDarkMode ? Color.parseColor("#f5f5f7") : Color.parseColor("#1d1d1f"), Typeface.BOLD);
        name.setPadding(dp(8), 0, 0, 0);
        name.setSingleLine(true);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        String status = signalDisplay(signalState);
        TextView cmd = text(status, 10, Color.WHITE, Typeface.BOLD);
        cmd.setSingleLine(true);
        cmd.setPadding(dp(8), dp(3), dp(8), dp(3));
        styleChip(cmd, signalColor, false);
        LinearLayout.LayoutParams cmdParams = new LinearLayout.LayoutParams(-2, -2);
        cmdParams.setMargins(dp(8), 0, 0, 0);
        titleRow.addView(cmd, cmdParams);

        if (showConfig) {
            String deleteText = t("删", "Del");
            Button delete = miniButton(deleteText);
            delete.setTextColor(Color.parseColor("#ff3b30"));
            delete.setOnClickListener(v -> removeDevice(device));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(buttonWidth(deleteText, 42), dp(32));
            deleteParams.setMargins(dp(4), 0, 0, 0);
            titleRow.addView(delete, deleteParams);
        }
        item.addView(titleRow);

        LinearLayout quotaRow = new LinearLayout(this);
        quotaRow.setOrientation(LinearLayout.HORIZONTAL);
        quotaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams quotaRowParams = new LinearLayout.LayoutParams(-1, -2);
        quotaRowParams.setMargins(0, dp(10), 0, 0);
        quotaRow.addView(quotaBlock("5h", device.fivePercent, windowResetText(device.fiveResetInSeconds, device.fiveResetAt)), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams weeklyQuotaParams = new LinearLayout.LayoutParams(0, -2, 1);
        weeklyQuotaParams.setMargins(dp(12), 0, 0, 0);
        quotaRow.addView(quotaBlock("W", device.weeklyPercent, windowResetText(device.weeklyResetInSeconds, device.weeklyResetAt)), weeklyQuotaParams);
        item.addView(quotaRow, quotaRowParams);

        if (selectedDetail) {
            item.addView(expandedTokenDetail(device));
        }

        item.setOnClickListener(v -> {
            if (expandedBaseUrls.contains(device.baseUrl)) {
                expandedBaseUrls.remove(device.baseUrl);
            } else {
                expandedBaseUrls.add(device.baseUrl);
            }
            renderDeviceList();
        });

        LinearLayout.LayoutParams outerParams = new LinearLayout.LayoutParams(-1, -2);
        outerParams.setMargins(0, 0, 0, dp(10));
        item.setLayoutParams(outerParams);

        return item;
    }

    private View expandedTokenDetail(DeviceState device) {
        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);

        View divider = new View(this);
        divider.setBackgroundColor(isDarkMode ? Color.parseColor("#3a3a3c") : Color.parseColor("#d1d1d6"));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(0, dp(10), 0, dp(9));
        detail.addView(divider, dividerParams);

        JSONObject codex = device.payload != null ? device.payload.optJSONObject("codex") : null;
        JSONObject tokenUsage = codex != null ? codex.optJSONObject("tokenUsage") : null;
        JSONObject total = tokenUsage != null ? tokenUsage.optJSONObject("total") : null;
        JSONObject last = tokenUsage != null ? tokenUsage.optJSONObject("last") : null;

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(label(t("Token 消耗", "Token Usage")), new LinearLayout.LayoutParams(0, -2, 1));
        TextView totalText = text(
                total == null ? "--" : formatCompactNumber(total.optInt("totalTokens", 0)),
                20,
                isDarkMode ? Color.parseColor("#f5f5f7") : Color.parseColor("#1d1d1f"),
                Typeface.BOLD
        );
        top.addView(totalText);
        detail.addView(top);

        TextView breakdown = small(tokenUsageDetail(total, last));
        breakdown.setPadding(0, dp(5), 0, 0);
        detail.addView(breakdown);
        return detail;
    }

    private String tokenUsageDetail(JSONObject total, JSONObject last) {
        if (total == null) return t("未读取到 token_count", "token_count not read");
        String value = t("输入 ", "In ") + formatCompactNumber(total.optInt("inputTokens", 0))
                + t(" · 缓存 ", " · Cache ") + formatCompactNumber(total.optInt("cachedInputTokens", 0))
                + t(" · 输出 ", " · Out ") + formatCompactNumber(total.optInt("outputTokens", 0))
                + t(" · 推理 ", " · Reason ") + formatCompactNumber(total.optInt("reasoningOutputTokens", 0));
        if (last != null) value += t(" · 最近 ", " · Last ") + formatCompactNumber(last.optInt("totalTokens", 0));
        return value;
    }

    private void renderSummary() {
        if (summaryStrip == null) return;
        summaryStrip.removeAllViews();

        int online = 0;
        int pending = 0;
        int lowQuota = 0;
        for (DeviceState device : devices) {
            if (device.online) online++;
            if ("NEED_CONFIRM".equals(device.command)) pending++;
            if ((device.fivePercent >= 0 && device.fivePercent <= 10) || (device.weeklyPercent >= 0 && device.weeklyPercent <= 10)) {
                lowQuota++;
            }
        }

        addSummaryChip(t("设备 ", "Devices ") + devices.size(), Color.parseColor("#007aff"));
        addSummaryChip(t("在线 ", "Online ") + online, online > 0 ? Color.parseColor("#34c759") : Color.parseColor("#8e8e93"));
        if (pending > 0) addSummaryChip(t("待确认 ", "Need ") + pending, Color.parseColor("#ff9500"));
        if (lowQuota > 0) addSummaryChip(t("低余额 ", "Low ") + lowQuota, Color.parseColor("#ff3b30"));
    }

    private void addSummaryChip(String value, int color) {
        TextView chip = text(value, 11, color, Typeface.BOLD);
        chip.setPadding(dp(8), dp(4), dp(8), dp(4));
        styleChip(chip, color, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, 0, dp(6), 0);
        summaryStrip.addView(chip, params);
    }

    private LinearLayout quotaBlock(String label, int percent, String resetText) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        LinearLayout metric = new LinearLayout(this);
        metric.setOrientation(LinearLayout.HORIZONTAL);
        metric.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 11, isDarkMode ? Color.parseColor("#8e8e93") : Color.parseColor("#6e6e73"), Typeface.BOLD);
        metric.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(percentText(percent), 14, quotaColor(percent), Typeface.BOLD);
        value.setSingleLine(true);
        metric.addView(value);
        block.addView(metric);

        ProgressBar progress = thinBar(percent);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5));
        progressParams.setMargins(0, dp(6), 0, 0);
        block.addView(progress, progressParams);

        TextView reset = small(resetText);
        reset.setTextSize(10);
        reset.setSingleLine(true);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(-1, -2);
        resetParams.setMargins(0, dp(4), 0, 0);
        block.addView(reset, resetParams);
        return block;
    }

    private TextView compactMetric(String label, int percent) {
        TextView view = text(label + " " + percentText(percent), 12, quotaColor(percent), Typeface.BOLD);
        view.setSingleLine(true);
        return view;
    }

    private ProgressBar thinBar(int percent) {
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        setProgress(progressBar, percent);
        return progressBar;
    }

    private void setProgress(ProgressBar progressBar, int percent) {
        progressBar.setProgressDrawable(progressDrawable(quotaColor(percent)));
        progressBar.setProgress(Math.max(0, percent));
    }

    private LayerDrawable progressDrawable(int fillColor) {
        GradientDrawable track = new GradientDrawable();
        track.setColor(isDarkMode ? Color.parseColor("#3a3a3c") : Color.parseColor("#d1d1d6"));
        track.setCornerRadius(dp(999));

        GradientDrawable fill = new GradientDrawable();
        fill.setColor(fillColor);
        fill.setCornerRadius(dp(999));

        ClipDrawable clippedFill = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable drawable = new LayerDrawable(new android.graphics.drawable.Drawable[]{track, clippedFill});
        drawable.setId(0, android.R.id.background);
        drawable.setId(1, android.R.id.progress);
        return drawable;
    }

    private String percentText(int percent) {
        return percent >= 0 ? percent + "%" : "--";
    }

    private String windowResetText(int resetInSeconds, String resetsAt) {
        if (resetInSeconds >= 0) {
            return t("重置 ", "reset ") + formatDuration(resetInSeconds);
        }
        if (resetsAt != null && !resetsAt.isEmpty()) {
            return t("重置 ", "reset ") + shortTime(resetsAt);
        }
        return t("未读取", "no reset");
    }

    private String deviceAddressText(DeviceState device) {
        return t("地址 ", "Address ") + device.baseUrl.replace("http://", "").replace("https://", "");
    }

    private int quotaColor(int percent) {
        if (percent < 0) return Color.parseColor("#8e8e93");
        if (percent <= 10) return Color.parseColor("#ff3b30");
        if (percent <= 30) return Color.parseColor("#ff9500");
        return Color.parseColor("#34c759");
    }

    private void styleChip(TextView view, int color, boolean filled) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        bg.setColor(filled ? color : transparentTint(color));
        bg.setStroke(dp(1), color);
        view.setBackground(bg);
        view.setTextColor(filled ? Color.WHITE : color);
    }

    private int transparentTint(int color) {
        return Color.argb(isDarkMode ? 28 : 18, Color.red(color), Color.green(color), Color.blue(color));
    }

    private LinearLayout signalLights(String state) {
        LinearLayout lights = new LinearLayout(this);
        lights.setOrientation(LinearLayout.HORIZONTAL);
        lights.setGravity(Gravity.CENTER_VERTICAL);

        String active = activeSignalLight(state);
        long pulseDuration = signalPulseDuration(state);
        lights.addView(signalLamp(Color.parseColor("#ff5f57"), "red".equals(active), pulseDuration));
        lights.addView(signalLamp(Color.parseColor("#ffbd2e"), "yellow".equals(active), pulseDuration));
        lights.addView(signalLamp(Color.parseColor("#28c840"), "green".equals(active), pulseDuration));
        return lights;
    }

    private View signalLamp(int color, boolean active, long pulseDuration) {
        FrameLayout slot = new FrameLayout(this);
        LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        slotParams.setMargins(0, 0, dp(3), 0);
        slot.setLayoutParams(slotParams);

        if (active) {
            View glow = new View(this);
            GradientDrawable glowBg = new GradientDrawable();
            glowBg.setShape(GradientDrawable.OVAL);
            glowBg.setColor(Color.argb(isDarkMode ? 54 : 38, Color.red(color), Color.green(color), Color.blue(color)));
            glow.setBackground(glowBg);
            slot.addView(glow, new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER));
        }

        View lamp = new View(this);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        active ? appleLampHighlight(color) : inactiveLampHighlight(color),
                        active ? color : inactiveLampFill(color),
                        active ? appleLampShade(color) : inactiveLampShade(color)
                }
        );
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(1), active ? activeLampStroke(color) : inactiveLampStroke(color));
        lamp.setBackground(bg);
        lamp.setAlpha(1.0f);
        lamp.setElevation(active ? dp(3) : 0);

        int size = active ? dp(9) : dp(8);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
        lamp.setLayoutParams(params);
        slot.addView(lamp);

        if (active) {
            View shine = new View(this);
            GradientDrawable shineBg = new GradientDrawable();
            shineBg.setShape(GradientDrawable.OVAL);
            shineBg.setColor(Color.argb(isDarkMode ? 96 : 130, 255, 255, 255));
            shine.setBackground(shineBg);
            FrameLayout.LayoutParams shineParams = new FrameLayout.LayoutParams(dp(3), dp(3), Gravity.TOP | Gravity.LEFT);
            shineParams.setMargins(dp(3), dp(2), 0, 0);
            slot.addView(shine, shineParams);
        }

        if (active && pulseDuration > 0) {
            AlphaAnimation animation = new AlphaAnimation(1.0f, 0.64f);
            animation.setDuration(pulseDuration);
            animation.setRepeatMode(Animation.REVERSE);
            animation.setRepeatCount(Animation.INFINITE);
            slot.startAnimation(animation);
        }
        return slot;
    }

    private int appleLampHighlight(int color) {
        return blendColor(color, Color.WHITE, isDarkMode ? 0.22f : 0.30f);
    }

    private int appleLampShade(int color) {
        return blendColor(color, Color.BLACK, isDarkMode ? 0.18f : 0.10f);
    }

    private int inactiveLampHighlight(int color) {
        return blendColor(inactiveLampFill(color), Color.WHITE, isDarkMode ? 0.08f : 0.22f);
    }

    private int inactiveLampFill(int color) {
        int alpha = isDarkMode ? 66 : 52;
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int inactiveLampShade(int color) {
        return blendColor(inactiveLampFill(color), Color.BLACK, isDarkMode ? 0.16f : 0.06f);
    }

    private int inactiveLampStroke(int color) {
        int alpha = isDarkMode ? 98 : 82;
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int activeLampStroke(int color) {
        return blendColor(color, Color.BLACK, isDarkMode ? 0.16f : 0.08f);
    }

    private int blendColor(int base, int overlay, float amount) {
        float keep = 1.0f - amount;
        return Color.argb(
                Color.alpha(base),
                Math.round(Color.red(base) * keep + Color.red(overlay) * amount),
                Math.round(Color.green(base) * keep + Color.green(overlay) * amount),
                Math.round(Color.blue(base) * keep + Color.blue(overlay) * amount)
        );
    }

    private String signalState(DeviceState device) {
        if (device.stale) return "stale";
        if (!device.online) return "off";
        String command = device.command == null ? "" : device.command;
        switch (command) {
            case "NEED_CONFIRM":
                return "permission";
            case "ERROR":
                return "blocked";
            case "THINKING":
                return "thinking";
            case "RUNNING":
            case "WRITING":
                return "working";
            case "DONE":
                return "done";
            case "IDLE":
                return "idle";
            default:
                return command.isEmpty() ? "idle" : "stale";
        }
    }

    private String activeSignalLight(String state) {
        if ("permission".equals(state) || "blocked".equals(state)) return "red";
        if ("stale".equals(state)) return "yellow";
        if ("off".equals(state)) return "";
        return "green";
    }

    private long signalPulseDuration(String state) {
        if ("thinking".equals(state)) return 280L;
        if ("working".equals(state)) return 900L;
        if ("permission".equals(state)) return 360L;
        if ("blocked".equals(state)) return 220L;
        if ("stale".equals(state)) return 1100L;
        return 0L;
    }

    private int signalColor(String state) {
        if ("permission".equals(state) || "blocked".equals(state)) return Color.parseColor("#ff3b30");
        if ("stale".equals(state)) return Color.parseColor("#ff9500");
        if ("off".equals(state)) return Color.parseColor("#8e8e93");
        return Color.parseColor("#34c759");
    }

    private String signalDisplay(String state) {
        switch (state) {
            case "thinking":
                return isEnglish ? "thinking" : "\u601d\u8003";
            case "working":
                return isEnglish ? "working" : "\u5de5\u4f5c";
            case "done":
                return isEnglish ? "done" : "\u5b8c\u6210";
            case "permission":
                return isEnglish ? "permission" : "\u6743\u9650";
            case "blocked":
                return isEnglish ? "blocked" : "\u5f02\u5e38";
            case "stale":
                return isEnglish ? "stale" : "\u8fc7\u671f";
            case "off":
                return isEnglish ? "off" : "\u79bb\u7ebf";
            case "idle":
            default:
                return isEnglish ? "idle" : "\u7a7a\u95f2";
        }
    }

    private int statusColor(DeviceState device) {
        return signalColor(signalState(device));
    }

    private String commandDisplay(String command) {
        if (command == null) return "--";
        switch (command) {
            case "THINKING":
                return t("思考中", "Thinking");
            case "WRITING":
                return t("编写中", "Writing");
            case "RUNNING":
                return t("执行中", "Running");
            case "DONE":
                return t("完成", "Done");
            case "NEED_CONFIRM":
                return t("待确认", "Confirm");
            case "ERROR":
                return t("异常", "Error");
            case "IDLE":
                return t("空闲", "Idle");
            default:
                return command.isEmpty() ? "--" : command;
        }
    }

    private String displayStatusLabel(JSONObject activity, String fallback) {
        String value = activity != null ? activity.optString(isEnglish ? "labelEn" : "label", fallback) : fallback;
        if (!isEnglish) return value;
        return translateStatusLabel(value);
    }

    private String displayStatusDetail(JSONObject activity, String fallback) {
        String value = activity != null ? activity.optString(isEnglish ? "detailEn" : "detail", fallback) : fallback;
        if (!isEnglish) return value;
        return translateStatusDetail(value);
    }

    private String translateStatusLabel(String value) {
        if ("未连接".equals(value)) return "Not Connected";
        if ("连接失败".equals(value)) return "Connection Failed";
        if ("IDLE".equals(value)) return "Idle";
        if ("THINKING".equals(value)) return "Thinking";
        if ("RUNNING".equals(value)) return "Running";
        if ("WRITING".equals(value)) return "Writing";
        if ("DONE".equals(value)) return "Done";
        if ("NEED_CONFIRM".equals(value)) return "Needs Confirmation";
        if ("ERROR".equals(value)) return "Error";
        if ("完成".equals(value)) return "Done";
        if ("未知".equals(value)) return "Unknown";
        if ("空闲".equals(value)) return "Idle";
        if ("运行中".equals(value)) return "Running";
        if ("思考中".equals(value)) return "Thinking";
        if ("编写中".equals(value)) return "Writing";
        if ("待确认".equals(value) || "需要权限".equals(value)) return "Needs Confirmation";
        if ("执行工具".equals(value)) return "Running Tool";
        if ("处理结果".equals(value)) return "Processing Result";
        if ("输出结果".equals(value)) return "Writing Reply";
        if ("回复中".equals(value)) return "Replying";
        if ("已收到任务".equals(value)) return "Task Received";
        if ("启动中".equals(value)) return "Starting";
        if ("准备中".equals(value)) return "Preparing";
        if ("读取失败".equals(value)) return "Read Failed";
        return value;
    }

    private String translateStatusDetail(String value) {
        if ("未发现当前任务。".equals(value)) return "No active task was found.";
        if ("尚未识别到明确状态。".equals(value)) return "No clear status recognized yet.";
        if ("上一轮任务已经完成。".equals(value)) return "Previous task completed.";
        if ("Codex 正在运行。".equals(value)) return "Codex is running.";
        if ("Codex 正在推理下一步。".equals(value)) return "Codex is reasoning about the next step.";
        if ("工具结果已返回，Codex 正在继续处理。".equals(value)) return "Tool result returned; Codex is continuing.";
        if ("Codex 正在输出最终回复。".equals(value)) return "Codex is writing the final reply.";
        if ("Codex 正在发送进度或说明。".equals(value)) return "Codex is sending progress or notes.";
        if ("Codex 正在输出消息。".equals(value)) return "Codex is writing a message.";
        if ("Codex 已收到最新消息。".equals(value)) return "Codex received the latest message.";
        if ("Codex 正在开始新一轮任务。".equals(value)) return "Codex is starting a new task.";
        if ("Codex 正在加载上下文和权限模式。".equals(value)) return "Codex is loading context and permission mode.";
        if ("Codex 正在处理上一步结果。".equals(value)) return "Codex is processing the previous result.";
        if ("Codex 正在等待权限确认。".equals(value)) return "Codex is waiting for permission confirmation.";
        if (value != null && value.startsWith("正在调用 ") && value.endsWith("。")) {
            return "Calling " + value.substring(5, value.length() - 1) + ".";
        }
        if (value != null && value.startsWith("正在调用")) {
            return value.replace("正在调用", "Calling");
        }
        return value;
    }

    private String displayOpenAiMessage(String value) {
        if (!isEnglish) return value;
        if ("未配置 OPENAI_ADMIN_KEY 或 OPENAI_API_KEY。".equals(value)) {
            return "OPENAI_ADMIN_KEY or OPENAI_API_KEY is not configured.";
        }
        return value;
    }

    private String statusMetaLine(JSONObject activity, JSONObject agentCore, JSONArray outbound) {
        String source = agentCore != null ? agentCore.optString("source", "--") : "--";
        String updatedAt = agentCore != null ? agentCore.optString("updatedAt", "--") : "--";
        JSONObject mode = activity != null ? activity.optJSONObject("mode") : null;
        String model = mode != null ? mode.optString("model", "") : "";
        String sandbox = mode != null ? mode.optString(isEnglish ? "sandboxPolicy" : "sandboxLabel", "") : "";
        String approval = mode != null ? mode.optString(isEnglish ? "approvalPolicy" : "approvalLabel", "") : "";

        StringBuilder builder = new StringBuilder();
        if (!model.isEmpty()) builder.append(model);
        if (!sandbox.isEmpty()) appendMeta(builder, sandbox);
        if (!approval.isEmpty()) appendMeta(builder, approval);
        appendMeta(builder, t("来源 ", "Source ") + source);
        if (!"--".equals(updatedAt)) appendMeta(builder, shortTime(updatedAt));
        if (outbound != null && outbound.length() > 0) appendMeta(builder, t("命令 ", "Cmd ") + outbound.optString(0, "--"));
        return builder.length() == 0 ? "--" : builder.toString();
    }

    private void appendMeta(StringBuilder builder, String value) {
        if (builder.length() > 0) builder.append(" · ");
        builder.append(value);
    }

    private String shortTime(String value) {
        if (value == null || value.length() < 16) return value == null ? "--" : value;
        int tIndex = value.indexOf('T');
        if (tIndex >= 0 && value.length() >= tIndex + 6) {
            return value.substring(tIndex + 1, tIndex + 6);
        }
        return value;
    }

    private String resetText(JSONObject window) {
        if (window == null) return "--";
        int seconds = window.optInt("resetInSeconds", -1);
        if (seconds >= 0) {
            return isEnglish
                    ? "resets in " + formatDuration(seconds)
                    : formatDuration(seconds) + "后重置";
        }
        String raw = window.optString("resetsAt", "--");
        return shortTime(raw);
    }

    private String formatDuration(int seconds) {
        int minutes = Math.max(0, seconds / 60);
        int hours = minutes / 60;
        int days = hours / 24;
        if (isEnglish) {
            if (days > 0) return days + "d " + (hours % 24) + "h";
            if (hours > 0) return hours + "h " + (minutes % 60) + "m";
            return Math.max(1, minutes) + "m";
        }
        if (days > 0) return days + "天" + (hours % 24) + "小时";
        if (hours > 0) return hours + "小时" + (minutes % 60) + "分";
        return Math.max(1, minutes) + "分钟";
    }

    private ArrayList<DeviceState> selectedDevices() {
        return new ArrayList<>(devices);
    }

    private void addOrSelectDevice(String baseUrl) {
        addDevice(baseUrl, true);
    }

    private boolean hasDevice(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        for (DeviceState device : devices) {
            if (device.baseUrl.equals(normalized)) return true;
        }
        return false;
    }

    private void addDevice(String baseUrl, boolean selected) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (normalized.isEmpty()) return;
        for (DeviceState device : devices) {
            if (device.baseUrl.equals(normalized)) {
                device.selected = true;
                return;
            }
        }
        DeviceState device = new DeviceState(normalized);
        device.selected = true;
        devices.add(device);
    }

    private void removeDevice(DeviceState target) {
        devices.remove(target);
        expandedBaseUrls.remove(target.baseUrl);
        saveDevices();
        startMonitorService();
        renderDeviceList();
        renderScanResults();
        refreshSelectedDevices();
    }

    private void loadDevices(SharedPreferences prefs) {
        devices.clear();
        String raw = prefs.getString(KEY_DEVICES, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    DeviceState device = new DeviceState(item.optString("baseUrl", ""));
                    device.name = item.optString("name", device.baseUrl);
                    device.selected = true;
                    if (!device.baseUrl.isEmpty()) devices.add(device);
                }
            } catch (Exception ignored) {}
        }
        applySavedStatuses(prefs);
    }

    private void applySavedStatuses(SharedPreferences prefs) {
        String raw = prefs.getString(KEY_DEVICE_STATUS, "[]");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                DeviceState device = findDevice(item.optString("baseUrl", ""));
                if (device == null) continue;
                device.name = item.optString("name", device.name);
                device.online = item.optBoolean("online", device.online);
                device.stale = item.optBoolean("stale", device.stale);
                device.statusLabel = item.optString("statusLabel", device.statusLabel);
                device.detail = item.optString("detail", device.detail);
                device.command = item.optString("command", device.command);
                device.fivePercent = item.optInt("fivePercent", device.fivePercent);
                device.weeklyPercent = item.optInt("weeklyPercent", device.weeklyPercent);
                device.fiveResetAt = item.optString("fiveResetAt", device.fiveResetAt);
                device.weeklyResetAt = item.optString("weeklyResetAt", device.weeklyResetAt);
                device.fiveResetInSeconds = item.optInt("fiveResetInSeconds", device.fiveResetInSeconds);
                device.weeklyResetInSeconds = item.optInt("weeklyResetInSeconds", device.weeklyResetInSeconds);
                JSONObject payload = item.optJSONObject("payload");
                if (payload != null) device.payload = payload;
            }
        } catch (Exception ignored) {}
    }

    private DeviceState findDevice(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        for (DeviceState device : devices) {
            if (device.baseUrl.equals(normalized)) return device;
        }
        return null;
    }

    private void saveDevices() {
        JSONArray array = new JSONArray();
        for (DeviceState device : devices) {
            JSONObject item = new JSONObject();
            try {
                item.put("baseUrl", device.baseUrl);
                item.put("name", device.name);
                item.put("selected", true);
                array.put(item);
            } catch (Exception ignored) {}
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String serverUrl = serverInput == null
                ? prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
                : serverInput.getText().toString().trim();
        String token = tokenInput == null
                ? prefs.getString(KEY_AGENT_TOKEN, "")
                : tokenInput.getText().toString().trim();
        prefs.edit()
                .putString(KEY_DEVICES, array.toString())
                .putString(KEY_SERVER_URL, serverUrl)
                .putString(KEY_AGENT_TOKEN, token)
                .apply();
    }

    private Set<String> localSubnetPrefixes() {
        Set<String> prefixes = new HashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    if (!(address.getAddress() instanceof Inet4Address)) continue;
                    String host = address.getAddress().getHostAddress();
                    if (host.startsWith("127.") || host.startsWith("169.254.")) continue;
                    String[] parts = host.split("\\.");
                    if (parts.length == 4) prefixes.add(parts[0] + "." + parts[1] + "." + parts[2]);
                }
            }
        } catch (Exception ignored) {}
        if (prefixes.isEmpty()) {
            String host = cleanBaseUrl().replace("http://", "").replace("https://", "");
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
            String[] parts = host.split("\\.");
            if (parts.length == 4) prefixes.add(parts[0] + "." + parts[1] + "." + parts[2]);
        }
        return prefixes;
    }

    private void maybeNotify(DeviceState device, String previousCommand) {
        String command = device.command;

        if ("NEED_CONFIRM".equals(command) && !"NEED_CONFIRM".equals(previousCommand)) {
            String title = device.name + t(" 需要权限", " needs confirm");
            String message = t("请回到这台电脑确认权限请求。", "Please confirm the permission request on this PC.");
            sendNotification(title, message, eventNotificationId(device.baseUrl, "permission"));
            showPopupNotification(title, message);
            return;
        } else if ("DONE".equals(command) && previousCommand != null && !previousCommand.isEmpty() && !"DONE".equals(previousCommand)) {
            String title = device.name + t(" 任务完成", " task done");
            String message = t("当前任务已完成。", "The current task has finished.");
            sendNotification(title, message, eventNotificationId(device.baseUrl, "done"));
            showPopupNotification(title, message);
            return;
        } else if ("ERROR".equals(command) && previousCommand != null && !previousCommand.isEmpty() && !"ERROR".equals(previousCommand)) {
            String title = device.name + t(" 任务失败", " task failed");
            String message = t("处理当前任务时发生错误。", "The current task failed while processing.");
            sendNotification(title, message, eventNotificationId(device.baseUrl, "error"));
            showPopupNotification(title, message);
        }
    }

    private void sendNotification(String title, String message, int id) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_codex_notify_small_v2)
                .setContentTitle(title).setContentText(message).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_ALL)
                .setPriority(Notification.PRIORITY_MAX);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(id, builder.build());
    }

    private int eventNotificationId(String baseUrl, String event) {
        return Math.abs((baseUrl + ":" + event).hashCode());
    }

    private void openAlertNotificationSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 26) {
            intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID);
        } else {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        }
        startActivity(intent);
    }

    private void showPopupNotification(String title, String message) {
        handler.post(() -> {
            if (isFinishing()) return;
            new AlertDialog.Builder(this)
                    .setIcon(R.mipmap.ic_codex_notify_app_v2)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(t("知道了", "OK"), null)
                    .show();
        });
    }

    private void applyApiVisibility() {
        if (apiCard != null) apiCard.setVisibility(showApiCost ? View.VISIBLE : View.GONE);
        if (apiToggleButton != null) apiToggleButton.setText(showApiCost ? t("隐藏 API 成本", "Hide API Cost") : t("显示 API 成本", "Show API Cost"));
    }

    private int readPercent(JSONObject value) {
        if (value == null || !value.has("remainingPercent")) return -1;
        return (int) Math.round(value.optDouble("remainingPercent", -1));
    }

    private String cleanBaseUrl() {
        return normalizeBaseUrl(serverInput.getText().toString());
    }

    private String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.equals("http://") || value.equals("https://") ? "" : value;
    }

    private String formatInteger(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private String formatCompactNumber(int value) {
        double abs = Math.abs((double) value);
        if (abs >= 1_000_000) return String.format(Locale.US, "%.1fM", value / 1_000_000.0);
        if (abs >= 1_000) return String.format(Locale.US, "%.1fK", value / 1_000.0);
        return String.format(Locale.US, "%d", value);
    }

    private String formatCosts(JSONObject costs) {
        if (costs == null || costs.length() == 0) return "--";
        StringBuilder builder = new StringBuilder();
        Iterator<String> keys = costs.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (builder.length() > 0) builder.append(" / ");
            builder.append(key).append(" ").append(String.format(Locale.US, "%.4f", costs.optDouble(key, 0)));
        }
        return builder.toString();
    }

    private LinearLayout card() {
        int colorCard = isDarkMode ? Color.parseColor("#1c1c1e") : Color.WHITE;
        int colorBorder = isDarkMode ? Color.parseColor("#2c2c2e") : Color.parseColor("#d1d1d6");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorCard);
        bg.setStroke(dp(1), colorBorder);
        bg.setCornerRadius(dp(8));
        layout.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, 0);
        layout.setLayoutParams(params);
        return layout;
    }

    private Button button(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), isDarkMode ? Color.parseColor("#2c2c2e") : Color.parseColor("#d1d1d6"));
        btn.setBackground(bg);
        return btn;
    }

    private Button miniButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(isDarkMode ? Color.parseColor("#f5f5f7") : Color.parseColor("#1d1d1f"));
        btn.setAllCaps(false);
        btn.setTextSize(12);
        btn.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(8), 0, dp(8), 0);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumWidth(0);
        btn.setMinimumHeight(0);
        btn.setIncludeFontPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(isDarkMode ? Color.parseColor("#1c1c1e") : Color.WHITE);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), isDarkMode ? Color.parseColor("#2c2c2e") : Color.parseColor("#d1d1d6"));
        btn.setBackground(bg);
        return btn;
    }

    private int buttonWidth(String text, int minDp) {
        int units = 24;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                units += text.charAt(i) < 128 ? 7 : 14;
            }
        }
        return Math.max(dp(minDp), dp(units));
    }

    private EditText edit() {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(14);
        edit.setTextColor(isDarkMode ? Color.parseColor("#f5f5f7") : Color.parseColor("#1d1d1f"));
        edit.setHintTextColor(isDarkMode ? Color.parseColor("#636366") : Color.parseColor("#8e8e93"));
        edit.setPadding(dp(8), 0, dp(8), 0);
        return edit;
    }

    private ProgressBar bar() {
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        setProgress(progressBar, -1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(6));
        params.setMargins(0, dp(6), 0, 0);
        progressBar.setLayoutParams(params);
        return progressBar;
    }

    private TextView label(String value) {
        return text(value, 12, isDarkMode ? Color.parseColor("#aeaeb2") : Color.parseColor("#6e6e73"), Typeface.BOLD);
    }

    private TextView small(String value) {
        TextView view = text(value, 11, isDarkMode ? Color.parseColor("#8e8e93") : Color.parseColor("#6e6e73"), Typeface.NORMAL);
        view.setPadding(0, dp(3), 0, 0);
        return view;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Codex Monitor 设备提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.enableLights(true);
        channel.setLightColor(Color.BLUE);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 250, 150, 250});
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        channel.setSound(sound, audioAttributes);
        channel.setDescription("Codex Monitor 在任务完成、失败或需要权限时弹出提醒");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        handler.postDelayed(() -> {
            if (isFinishing() || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(t("开启通知", "Enable Notifications"))
                    .setMessage(t("用于在任务完成或需要权限时提醒你。", "Receive alerts when tasks finish or need permission."))
                    .setPositiveButton(t("允许", "Allow"), (dialog, which) ->
                            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100))
                    .setNegativeButton(t("稍后", "Later"), null)
                    .show();
        }, 350);
    }

    private void startMonitorService() {
        startMonitorService(false);
    }

    private void startMonitorService(boolean pollNow) {
        Intent intent = new Intent(this, CodexMonitorService.class);
        if (pollNow) intent.setAction(CodexMonitorService.ACTION_POLL_NOW);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private static class DeviceState {
        final String baseUrl;
        String name;
        boolean selected = true;
        boolean online = false;
        boolean stale = false;
        String statusLabel = "未连接";
        String detail = "";
        String command = "";
        String fiveResetAt = "";
        String weeklyResetAt = "";
        int fiveResetInSeconds = -1;
        int weeklyResetInSeconds = -1;
        int fivePercent = -1;
        int weeklyPercent = -1;
        int consecutiveIdleReads = 0;
        JSONObject payload;

        DeviceState(String baseUrl) {
            this.baseUrl = baseUrl;
            this.name = baseUrl.replace("http://", "").replace("https://", "");
        }
    }
}
