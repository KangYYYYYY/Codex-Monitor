package com.example.codexmobilemonitor;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CodexMonitorService extends Service {
    public static final String ACTION_POLL_NOW = "com.example.codexmobilemonitor.POLL_NOW";
    private static final String PREFS = "codex_monitor";
    private static final String KEY_AGENT_TOKEN = "agent_token";
    private static final String KEY_DEVICES = "devices";
    private static final String KEY_DEVICE_STATUS = "device_status";
    private static final String KEY_NOTIFIED_EVENT_KEYS = "notified_event_keys";
    private static final String KEY_NOTIFIED_ALERT_IDS = "notified_alert_ids";
    private static final String KEY_ENGLISH = "english";
    private static final String KEY_POLL_INTERVAL_SECONDS = "poll_interval_seconds";
    private static final String KEY_POLL_INTERVAL_MILLIS = "poll_interval_millis";
    private static final String STATUS_CHANNEL_ID = "codex_monitor_alerts_v3";
    private static final String MONITOR_CHANNEL_ID = "codex_monitor_service_silent_v4";
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;
    private static final int DEFAULT_POLL_INTERVAL_MILLIS = 500;
    private static final int ACTIVE_TO_IDLE_CONFIRM_READS = 3;
    private static final int OFFLINE_CONFIRM_READS = 3;

    private final Map<String, String> previousCommands = new HashMap<>();
    private final Map<String, String> stableCommands = new HashMap<>();
    private final Map<String, Integer> idleReadCounts = new HashMap<>();
    private final Map<String, Integer> failureReadCounts = new HashMap<>();
    private final Map<String, JSONObject> latestSnapshots = new HashMap<>();
    private final Map<String, String> notifiedEventKeys = new HashMap<>();
    private final Set<String> notifiedAlertIds = new HashSet<>();
    private final AtomicBoolean pollInProgress = new AtomicBoolean(false);
    private final Object schedulerLock = new Object();
    private ScheduledExecutorService scheduler;
    private int scheduledIntervalMillis = -1;
    private boolean stateHydrated = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.cancelAll();
        startForeground(FOREGROUND_NOTIFICATION_ID, monitoringNotification(0, 0, 0, 0, null, null, false, ""));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        restartSchedulerIfNeeded();
        if (intent != null && ACTION_POLL_NOW.equals(intent.getAction())) {
            triggerImmediatePoll();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        synchronized (schedulerLock) {
            if (scheduler != null) scheduler.shutdownNow();
            scheduler = null;
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        scheduleServiceRestart();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pollDevices() {
        if (!pollInProgress.compareAndSet(false, true)) return;
        try {
            doPollDevices();
        } finally {
            pollInProgress.set(false);
        }
    }

    private void doPollDevices() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hydrateStateFromPrefs(prefs);
        String token = prefs.getString(KEY_AGENT_TOKEN, "");
        String rawDevices = prefs.getString(KEY_DEVICES, "[]");
        boolean english = prefs.getBoolean(KEY_ENGLISH, false);
        JSONArray statusArray = new JSONArray();
        Set<String> seenDevices = new HashSet<>();
        int reachable = 0;
        int total = 0;
        int thinking = 0;
        int done = 0;
        String highestCommand = null;
        String highestName = null;
        int highestPriority = -1;
        StringBuilder statusLines = new StringBuilder();

        try {
            JSONArray devices = new JSONArray(rawDevices);
            total = devices.length();
            for (int i = 0; i < devices.length(); i++) {
                JSONObject item = devices.optJSONObject(i);
                if (item == null) continue;
                String baseUrl = normalizeBaseUrl(item.optString("baseUrl", ""));
                if (baseUrl.isEmpty()) continue;
                String savedName = item.optString("name", baseUrl);
                seenDevices.add(baseUrl);

                JSONObject snapshot;
                try {
                    JSONObject payload = getJson(baseUrl + "/api/mobile-status?fresh=1", token);
                    JSONObject server = payload.optJSONObject("server");
                    String name = server != null
                            ? server.optString("hostname", savedName)
                            : savedName;
                    String incomingCommand = commandFromPayload(payload);
                    String command = stabilizeCommand(baseUrl, incomingCommand);
                    handleAgentCoreEvents(baseUrl, name, payload, english);
                    handleCommandChange(baseUrl, name, command, payload, english);
                    failureReadCounts.remove(baseUrl);
                    snapshot = snapshotFromPayload(baseUrl, name, payload, command);
                    latestSnapshots.put(baseUrl, snapshot);
                } catch (Exception ignored) {
                    int failures = failureReadCounts.getOrDefault(baseUrl, 0) + 1;
                    failureReadCounts.put(baseUrl, failures);
                    JSONObject cached = latestSnapshots.get(baseUrl);
                    if (cached != null && failures < OFFLINE_CONFIRM_READS) {
                        snapshot = copySnapshot(cached);
                        putQuiet(snapshot, "stale", true);
                    } else {
                        String command = stabilizeCommand(baseUrl, "OFFLINE");
                        snapshot = offlineSnapshot(baseUrl, savedName, command, english);
                        latestSnapshots.put(baseUrl, snapshot);
                    }
                }

                statusArray.put(snapshot);
                boolean online = snapshot.optBoolean("online", false);
                String command = snapshot.optString("command", "");
                String name = snapshot.optString("name", savedName);
                if (online) reachable++;
                if (online && ("THINKING".equals(command) || "RUNNING".equals(command) || "WRITING".equals(command))) {
                    thinking++;
                } else if (online && "DONE".equals(command)) {
                    done++;
                }
                if (statusLines.length() > 0) statusLines.append('\n');
                statusLines.append(name).append(": ")
                        .append(online ? commandLabel(command, english) : (english ? "Offline" : "离线"));
                int priority = online ? commandPriority(command) : 5;
                if (priority > highestPriority) {
                    highestPriority = priority;
                    highestCommand = command;
                    highestName = name;
                }
            }
        } catch (Exception ignored) {
            // Invalid saved data is ignored; the Activity rewrites it on the next change.
        }

        latestSnapshots.keySet().removeIf(baseUrl -> !seenDevices.contains(baseUrl));
        failureReadCounts.keySet().removeIf(baseUrl -> !seenDevices.contains(baseUrl));
        idleReadCounts.keySet().removeIf(baseUrl -> !seenDevices.contains(baseUrl));
        stableCommands.keySet().removeIf(baseUrl -> !seenDevices.contains(baseUrl));
        previousCommands.keySet().removeIf(baseUrl -> !seenDevices.contains(baseUrl));
        notifiedEventKeys.keySet().removeIf(baseUrl -> !seenDevices.contains(baseUrl));
        notifiedAlertIds.removeIf(id -> {
            int split = id.indexOf('|');
            return split > 0 && !seenDevices.contains(id.substring(0, split));
        });

        prefs.edit()
                .putString(KEY_DEVICE_STATUS, statusArray.toString())
                .putString(KEY_NOTIFIED_EVENT_KEYS, eventKeysToJson())
                .putString(KEY_NOTIFIED_ALERT_IDS, alertIdsToJson())
                .apply();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(
                FOREGROUND_NOTIFICATION_ID,
                monitoringNotification(
                        reachable,
                        total,
                        thinking,
                        done,
                        highestName,
                        highestCommand,
                        english,
                        statusLines.toString()
                )
        );
    }

    private void triggerImmediatePoll() {
        synchronized (schedulerLock) {
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.execute(this::pollDevices);
                return;
            }
        }
        new Thread(this::pollDevices).start();
    }

    private void scheduleServiceRestart() {
        if (!hasSavedDevices()) return;
        Intent restartIntent = new Intent(this, CodexMonitorService.class);
        restartIntent.setAction(ACTION_POLL_NOW);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(this, 1002, restartIntent, flags)
                : PendingIntent.getService(this, 1002, restartIntent, flags);
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000L,
                    pendingIntent
            );
        }
    }

    private boolean hasSavedDevices() {
        String rawDevices = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DEVICES, "[]");
        if (rawDevices == null || rawDevices.trim().isEmpty()) return false;
        try {
            return new JSONArray(rawDevices).length() > 0;
        } catch (Exception ignored) {
            return rawDevices.contains("baseUrl");
        }
    }

    private JSONObject snapshotFromPayload(String baseUrl, String name, JSONObject payload, String command) {
        JSONObject snapshot = new JSONObject();
        JSONObject codex = payload.optJSONObject("codex");
        JSONObject activity = codex != null ? codex.optJSONObject("activity") : null;
        JSONObject limits = codex != null ? codex.optJSONObject("rateLimits") : null;
        JSONObject primary = limits != null ? limits.optJSONObject("primary") : null;
        JSONObject secondary = limits != null ? limits.optJSONObject("secondary") : null;

        putQuiet(snapshot, "baseUrl", baseUrl);
        putQuiet(snapshot, "name", name);
        putQuiet(snapshot, "online", true);
        putQuiet(snapshot, "command", command == null || command.isEmpty() ? "UNKNOWN" : command);
        putQuiet(snapshot, "statusLabel", activity != null ? activity.optString("label", "--") : "--");
        putQuiet(snapshot, "detail", activity != null ? activity.optString("detail", "--") : "--");
        putQuiet(snapshot, "fivePercent", readPercent(primary));
        putQuiet(snapshot, "weeklyPercent", readPercent(secondary));
        putQuiet(snapshot, "fiveResetAt", primary != null ? primary.optString("resetsAt", "") : "");
        putQuiet(snapshot, "weeklyResetAt", secondary != null ? secondary.optString("resetsAt", "") : "");
        putQuiet(snapshot, "fiveResetInSeconds", primary != null ? primary.optInt("resetInSeconds", -1) : -1);
        putQuiet(snapshot, "weeklyResetInSeconds", secondary != null ? secondary.optInt("resetInSeconds", -1) : -1);
        putQuiet(snapshot, "payload", payload);
        return snapshot;
    }

    private JSONObject offlineSnapshot(String baseUrl, String name, String command, boolean english) {
        JSONObject cached = latestSnapshots.get(baseUrl);
        JSONObject snapshot = cached == null ? new JSONObject() : copySnapshot(cached);
        putQuiet(snapshot, "baseUrl", baseUrl);
        putQuiet(snapshot, "name", name);
        putQuiet(snapshot, "online", false);
        putQuiet(snapshot, "command", command == null || command.isEmpty() ? "OFFLINE" : command);
        putQuiet(snapshot, "statusLabel", english ? "Offline" : "离线");
        putQuiet(snapshot, "detail", english ? "Connection lost." : "连接已断开。");
        return snapshot;
    }

    private JSONObject copySnapshot(JSONObject source) {
        if (source == null) return new JSONObject();
        try {
            return new JSONObject(source.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void putQuiet(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception ignored) {}
    }

    private int readPercent(JSONObject value) {
        if (value == null || !value.has("remainingPercent")) return -1;
        return (int) Math.round(value.optDouble("remainingPercent", -1));
    }

    private void restartSchedulerIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int intervalMillis = readPollIntervalMillis(prefs);
        synchronized (schedulerLock) {
            if (scheduler != null && !scheduler.isShutdown() && scheduledIntervalMillis == intervalMillis) return;
            if (scheduler != null) scheduler.shutdownNow();
            scheduledIntervalMillis = intervalMillis;
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleWithFixedDelay(this::pollDevices, 0, intervalMillis, TimeUnit.MILLISECONDS);
        }
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
        return millis == 500 || millis == 1000 || millis == 2000 || millis == 5000 || millis == 10000 || millis == 30000
                ? millis
                : DEFAULT_POLL_INTERVAL_MILLIS;
    }

    private void handleCommandChange(String baseUrl, String name, String command, JSONObject payload, boolean english) {
        String previous = previousCommands.put(baseUrl, command);
        String eventKey = eventKeyForCommand(command, payload);
        boolean newEvent = isNewEvent(baseUrl, eventKey);
        boolean hadPrevious = previous != null && !previous.isEmpty();

        if ("NEED_CONFIRM".equals(command) && (!"NEED_CONFIRM".equals(previous) || newEvent)) {
            sendStatusNotification(
                    name + (english ? " needs permission" : " 需要权限"),
                    english
                            ? "Confirm the permission request on this computer."
                            : "请回到这台电脑确认权限请求。",
                    Math.abs((baseUrl + ":permission").hashCode())
            );
        } else if ("DONE".equals(command) && hadPrevious && (!"DONE".equals(previous) || newEvent)) {
            sendStatusNotification(
                    name + (english ? " task completed" : " 任务完成"),
                    english ? "The current task has completed." : "当前任务已完成。",
                    Math.abs((baseUrl + ":done").hashCode())
            );
        } else if ("ERROR".equals(command) && hadPrevious && (!"ERROR".equals(previous) || newEvent)) {
            sendStatusNotification(
                    name + (english ? " task failed" : " 任务失败"),
                    english ? "The current task failed while processing." : "处理当前任务时发生错误。",
                    Math.abs((baseUrl + ":error").hashCode())
            );
        }
        rememberEventKey(baseUrl, eventKey);
    }

    private void handleAgentCoreEvents(String baseUrl, String name, JSONObject payload, boolean english) {
        JSONObject agentCore = payload.optJSONObject("agentCore");
        JSONArray events = agentCore != null ? agentCore.optJSONArray("events") : null;
        if (events == null) return;

        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            String command = normalizeCommand(event.optString("command", ""));
            if (!isNotifyCommand(command)) continue;

            String eventId = event.optString("id", "");
            if (eventId.isEmpty()) {
                eventId = command + ":" + event.optString("createdAt", "");
            }
            if (eventId.endsWith(":")) continue;

            String fullId = baseUrl + "|" + eventId;
            if (notifiedAlertIds.contains(fullId)) continue;
            notifiedAlertIds.add(fullId);

            String title = event.optString("title", "");
            String message = event.optString("message", "");
            if (title.isEmpty()) title = alertTitle(name, command, english);
            else title = name + " " + title;
            if (message.isEmpty()) message = alertMessage(command, english);

            sendStatusNotification(title, message, Math.abs(fullId.hashCode()));
        }
    }

    private String alertTitle(String name, String command, boolean english) {
        if ("NEED_CONFIRM".equals(command)) return name + (english ? " needs permission" : " 需要权限");
        if ("DONE".equals(command)) return name + (english ? " task completed" : " 任务完成");
        if ("ERROR".equals(command)) return name + (english ? " task failed" : " 任务失败");
        return name;
    }

    private String alertMessage(String command, boolean english) {
        if ("NEED_CONFIRM".equals(command)) {
            return english ? "Confirm the permission request on this computer." : "请回到这台电脑确认权限请求。";
        }
        if ("DONE".equals(command)) {
            return english ? "The current task has completed." : "当前任务已完成。";
        }
        if ("ERROR".equals(command)) {
            return english ? "The current task failed while processing." : "处理当前任务时发生错误。";
        }
        return "";
    }

    private void hydrateStateFromPrefs(SharedPreferences prefs) {
        if (stateHydrated) return;
        stateHydrated = true;

        String rawStatus = prefs.getString(KEY_DEVICE_STATUS, "[]");
        if (rawStatus != null && !rawStatus.isEmpty()) {
            try {
                JSONArray array = new JSONArray(rawStatus);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject snapshot = array.optJSONObject(i);
                    if (snapshot == null) continue;
                    String baseUrl = normalizeBaseUrl(snapshot.optString("baseUrl", ""));
                    String command = snapshot.optString("command", "");
                    if (baseUrl.isEmpty() || command.isEmpty()) continue;

                    latestSnapshots.put(baseUrl, copySnapshot(snapshot));
                    previousCommands.put(baseUrl, command);
                    stableCommands.put(baseUrl, command);
                    String eventKey = eventKeyForSnapshot(snapshot);
                    if (!eventKey.isEmpty()) {
                        notifiedEventKeys.put(baseUrl, eventKey);
                    }
                }
            } catch (Exception ignored) {}
        }

        String rawKeys = prefs.getString(KEY_NOTIFIED_EVENT_KEYS, "{}");
        if (rawKeys != null && !rawKeys.isEmpty()) {
            try {
                JSONObject keys = new JSONObject(rawKeys);
                JSONArray names = keys.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String baseUrl = normalizeBaseUrl(names.optString(i, ""));
                        String eventKey = keys.optString(names.optString(i, ""), "");
                        if (!baseUrl.isEmpty() && !eventKey.isEmpty()) {
                            notifiedEventKeys.put(baseUrl, eventKey);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        String rawAlertIds = prefs.getString(KEY_NOTIFIED_ALERT_IDS, "[]");
        if (rawAlertIds != null && !rawAlertIds.isEmpty()) {
            try {
                JSONArray array = new JSONArray(rawAlertIds);
                for (int i = 0; i < array.length(); i++) {
                    String id = array.optString(i, "");
                    if (!id.isEmpty()) notifiedAlertIds.add(id);
                }
            } catch (Exception ignored) {}
        }
    }

    private String eventKeysToJson() {
        JSONObject keys = new JSONObject();
        for (Map.Entry<String, String> entry : notifiedEventKeys.entrySet()) {
            putQuiet(keys, entry.getKey(), entry.getValue());
        }
        return keys.toString();
    }

    private String alertIdsToJson() {
        JSONArray ids = new JSONArray();
        for (String id : notifiedAlertIds) {
            ids.put(id);
        }
        return ids.toString();
    }

    private String eventKeyForSnapshot(JSONObject snapshot) {
        String command = snapshot.optString("command", "");
        JSONObject payload = snapshot.optJSONObject("payload");
        return eventKeyForCommand(command, payload);
    }

    private String eventKeyForCommand(String command, JSONObject payload) {
        if (!isNotifyCommand(command)) return "";
        String timestamp = eventTimestampForCommand(command, payload);
        if (timestamp.isEmpty()) return command;
        return command + ":" + timestamp;
    }

    private String eventTimestampForCommand(String command, JSONObject payload) {
        if (payload == null) return "";
        JSONObject agentCore = payload.optJSONObject("agentCore");
        JSONObject codex = payload.optJSONObject("codex");
        JSONObject activity = codex != null ? codex.optJSONObject("activity") : null;
        JSONObject lastEvent = activity != null ? activity.optJSONObject("lastEvent") : null;

        if ("DONE".equals(command)) {
            String completedAt = activity != null ? activity.optString("taskCompletedAt", "") : "";
            if (!completedAt.isEmpty()) return completedAt;
        }

        String eventAt = lastEvent != null ? lastEvent.optString("timestamp", "") : "";
        if (!eventAt.isEmpty()) return eventAt;

        String activityUpdatedAt = activity != null ? activity.optString("updatedAt", "") : "";
        if (!activityUpdatedAt.isEmpty()) return activityUpdatedAt;

        String agentUpdatedAt = agentCore != null ? agentCore.optString("updatedAt", "") : "";
        return agentUpdatedAt == null ? "" : agentUpdatedAt;
    }

    private boolean isNewEvent(String baseUrl, String eventKey) {
        if (eventKey == null || eventKey.isEmpty()) return false;
        String previousEventKey = notifiedEventKeys.get(baseUrl);
        return previousEventKey != null && !previousEventKey.equals(eventKey);
    }

    private void rememberEventKey(String baseUrl, String eventKey) {
        if (eventKey != null && !eventKey.isEmpty()) {
            notifiedEventKeys.put(baseUrl, eventKey);
        }
    }

    private boolean isNotifyCommand(String command) {
        return "NEED_CONFIRM".equals(command) || "DONE".equals(command) || "ERROR".equals(command);
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

    private String stabilizeCommand(String baseUrl, String incomingCommand) {
        if (incomingCommand == null || incomingCommand.trim().isEmpty()) {
            incomingCommand = "UNKNOWN";
        }
        String currentCommand = stableCommands.get(baseUrl);
        if ("IDLE".equals(incomingCommand) && isActiveCommand(currentCommand)) {
            int idleReads = idleReadCounts.getOrDefault(baseUrl, 0) + 1;
            idleReadCounts.put(baseUrl, idleReads);
            if (idleReads < ACTIVE_TO_IDLE_CONFIRM_READS) {
                return currentCommand;
            }
        } else {
            idleReadCounts.remove(baseUrl);
        }
        stableCommands.put(baseUrl, incomingCommand);
        return incomingCommand;
    }

    private boolean isActiveCommand(String command) {
        return "THINKING".equals(command)
                || "RUNNING".equals(command)
                || "WRITING".equals(command)
                || "NEED_CONFIRM".equals(command);
    }

    private JSONObject getJson(String target, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod("GET");
        if (token != null && !token.trim().isEmpty()) {
            connection.setRequestProperty("X-Agent-Token", token.trim());
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
            return new JSONObject(builder.toString());
        } finally {
            connection.disconnect();
        }
    }

    private Notification monitoringNotification(
            int reachable,
            int total,
            int thinking,
            int done,
            String deviceName,
            String command,
            boolean english,
            String statusLines
    ) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        String title;
        String content;
        if (total == 0) {
            title = "Codex Monitor";
            content = english ? "No devices added" : "尚未添加设备";
        } else if (reachable == 0) {
            title = english ? "Codex Monitor · Offline" : "Codex Monitor · 设备离线";
            content = english
                    ? "Online 0/" + total + " · Thinking 0 · Done 0"
                    : "在线 0/" + total + " · 思考中 0 · 完成 0";
        } else {
            String state = commandLabel(command, english);
            title = (total == 1 && deviceName != null ? deviceName : "Codex Monitor") + " · " + state;
            content = english
                    ? "Online " + reachable + "/" + total + " · Thinking " + thinking + " · Done " + done
                    : "在线 " + reachable + "/" + total + " · 思考中 " + thinking + " · 完成 " + done;
        }

        Notification.Builder builder = new Notification.Builder(this, MONITOR_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_codex_notify_small_v2)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setPriority(Notification.PRIORITY_MIN);
        if (statusLines != null && !statusLines.isEmpty()) {
            builder.setStyle(new Notification.BigTextStyle().bigText(statusLines));
        }
        return builder.build();
    }

    private int commandPriority(String command) {
        if ("NEED_CONFIRM".equals(command)) return 100;
        if ("ERROR".equals(command)) return 90;
        if ("RUNNING".equals(command) || "WRITING".equals(command) || "THINKING".equals(command)) return 80;
        if ("DONE".equals(command)) return 40;
        if ("IDLE".equals(command)) return 20;
        return 10;
    }

    private String commandLabel(String command, boolean english) {
        if ("NEED_CONFIRM".equals(command)) return english ? "Needs Permission" : "需要权限";
        if ("RUNNING".equals(command)) return english ? "Running" : "运行中";
        if ("WRITING".equals(command)) return english ? "Writing" : "编写中";
        if ("THINKING".equals(command)) return english ? "Thinking" : "思考中";
        if ("DONE".equals(command)) return english ? "Done" : "完成";
        if ("ERROR".equals(command)) return english ? "Error" : "错误";
        if ("IDLE".equals(command)) return english ? "Idle" : "空闲";
        return english ? "Unknown" : "未知";
    }

    private void sendStatusNotification(String title, String message, int id) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                id,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new Notification.Builder(this, STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_codex_notify_small_v2)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_ALL)
                .setPriority(Notification.PRIORITY_MAX)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(id, notification);
    }

    private void createChannels() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel statusChannel = new NotificationChannel(
                STATUS_CHANNEL_ID,
                "Codex Monitor 设备提醒",
                NotificationManager.IMPORTANCE_HIGH
        );
        statusChannel.enableLights(true);
        statusChannel.setLightColor(Color.BLUE);
        statusChannel.enableVibration(true);
        statusChannel.setVibrationPattern(new long[]{0, 250, 150, 250});
        statusChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        statusChannel.setSound(sound, audioAttributes);
        statusChannel.setDescription("Codex Monitor 在任务完成、失败或需要权限时弹出提醒");
        manager.createNotificationChannel(statusChannel);

        NotificationChannel monitorChannel = new NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Codex Monitor background",
                NotificationManager.IMPORTANCE_MIN
        );
        monitorChannel.setDescription("Runs Codex Monitor background checks silently.");
        monitorChannel.enableLights(false);
        monitorChannel.enableVibration(false);
        monitorChannel.setSound(null, null);
        monitorChannel.setShowBadge(false);
        monitorChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        manager.createNotificationChannel(monitorChannel);
    }

    private String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
