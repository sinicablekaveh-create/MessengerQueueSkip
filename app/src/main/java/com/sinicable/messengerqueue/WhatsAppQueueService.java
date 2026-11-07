package com.sinicable.messengerqueue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * User-started, consent-gated WhatsApp Business backend queue.
 *
 * Unlike an Activity-scoped handler this survives closing the screen while Android
 * permits the foreground service to run. Credentials live ONLY in process memory.
 * On process death the service does not auto-restart without credentials; an
 * interrupted attempt is reconciled to REVIEW on next app open, never retried.
 */
public final class WhatsAppQueueService extends Service {
    public static final String ACTION_START = "com.sinicable.messengerqueue.whatsapp.START";
    public static final String ACTION_PAUSE = "com.sinicable.messengerqueue.whatsapp.PAUSE";
    public static final String EXTRA_ENDPOINT = "endpoint";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_TEXT = "message";
    public static final String EXTRA_INTERVAL = "interval";
    private static final String CHANNEL = "threeib_whatsapp_queue_v5";

    private static volatile boolean active;
    private volatile boolean running;
    private Thread worker;
    private String endpoint;
    private String token;
    private String message;
    private long interval;

    public static boolean isActive() { return active; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "WhatsApp Business queue",
                        NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            running = false;
            QueueDb.put(this, "whatsapp_service_status", "Paused by user");
            QueueDb.put(this, "whatsapp_running", "0");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || running) return START_NOT_STICKY;

        String url = intent.getStringExtra(EXTRA_ENDPOINT);
        String authorization = intent.getStringExtra(EXTRA_TOKEN);
        String body = intent.getStringExtra(EXTRA_TEXT);
        long minutes = intent.getLongExtra(EXTRA_INTERVAL, 0);
        if (url == null || !url.startsWith("https://") ||
                !url.endsWith("/v1/whatsapp/messages") ||
                authorization == null || authorization.isEmpty() ||
                body == null || body.trim().isEmpty() ||
                minutes < 1 || minutes > 1440 ||
                QueueDb.whatsappCount(this, "pending") == 0 ||
                QueueDb.whatsappCount(this, "in_flight") != 0) {
            QueueDb.put(this, "whatsapp_service_status",
                    "Cannot start: backend, consented queue or previous attempt requires review");
            stopSelf();
            return START_NOT_STICKY;
        }
        endpoint = url;
        token = authorization;
        message = body;
        interval = minutes * 60_000L;
        try {
            startForeground(5004, notification());
        } catch (RuntimeException failure) {
            token = null;
            QueueDb.put(this, "whatsapp_service_status",
                    "Android denied foreground queue start: " + failure.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        active = true;
        QueueDb.put(this, "whatsapp_running", "1");
        QueueDb.put(this, "whatsapp_service_status", "Running in foreground");
        worker = new Thread(this::dispatchLoop, "threeib-whatsapp-v5");
        worker.start();
        // Never restart a dead process with a missing/expired in-memory token.
        return START_NOT_STICKY;
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent view = PendingIntent.getActivity(this, 20,
                new Intent(this, WhatsAppCloudActivity.class), flags);
        PendingIntent pause = PendingIntent.getService(this, 21,
                new Intent(this, WhatsAppQueueService.class).setAction(ACTION_PAUSE), flags);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("THREE IB • WhatsApp Business")
                .setContentText("Consent-only outgoing queue; tap to view status")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(view)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "PAUSE", pause)
                .build();
    }

    private void dispatchLoop() {
        try {
            while (running) {
                int index = QueueDb.next(this, "WhatsApp");
                if (index < 0) {
                    QueueDb.put(this, "whatsapp_service_status", "Queue finished");
                    break;
                }
                long wait = QueueDb.due(this, "WhatsApp") - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(Math.min(wait, 1000L));
                    continue;
                }
                String phone = toE164(QueueDb.phone(this, index));
                if (phone.isEmpty()) {
                    QueueDb.status(this, index, "WhatsApp", "failed");
                    QueueDb.put(this, "whatsapp_service_status", "Invalid phone; row failed");
                    continue;
                }
                if (!running) break;
                if (!QueueDb.whatsappMarkInFlight(this, index)) {
                    QueueDb.put(this, "whatsapp_service_status", "Cannot persist send attempt");
                    break;
                }

                QueueDb.put(this, "whatsapp_service_status", "Submitting one authorized recipient");
                String outcome = submit(endpoint, token, phone, message);
                // If the user cleared the queue while an HTTP call was in progress,
                // keep REVIEW rather than overwriting audit state with the late result.
                if (!QueueDb.whatsappFinishIfInFlight(this, index, outcome)) {
                    QueueDb.put(this, "whatsapp_service_status", "Attempt needs manual reconciliation");
                    break;
                }
                if (!running) break;
                if (!"submitted".equals(outcome)) {
                    QueueDb.put(this, "whatsapp_service_status",
                            "Stopped at " + outcome + "; check backend before any retry");
                    break;
                }
                QueueDb.due(this, "WhatsApp", System.currentTimeMillis() + interval);
                QueueDb.put(this, "whatsapp_service_status",
                        "Backend accepted; waiting for the next consented recipient");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            QueueDb.put(this, "whatsapp_service_status",
                    "Queue stopped: " + failure.getClass().getSimpleName());
        } finally {
            running = false;
            active = false;
            token = null;
            QueueDb.put(this, "whatsapp_running", "0");
            stopSelf();
        }
    }

    private static String toE164(String phone) {
        if (phone.matches("^\\+[1-9][0-9]{7,14}$")) return phone;
        if (phone.matches("^09[0-9]{9}$")) return "+98" + phone.substring(1);
        return "";
    }

    private static String submit(String url, String authorization, String phone, String body) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + authorization);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject payload = new JSONObject();
            payload.put("recipient", phone);
            payload.put("text", body);
            payload.put("optIn", true);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = connection.getResponseCode();
            String status = "";
            InputStream reply = code < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (reply != null) {
                try (InputStream input = reply;
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null && line.length() <= 4096)
                        status = new JSONObject(line).optString("status", "");
                }
            }
            if (code == 202 && "SUBMITTED".equals(status)) return "submitted";
            if ("FAILED".equals(status) ||
                    (code >= 400 && code < 500 && code != 409 && code != 429))
                return "failed";
            // Timeouts, throttling, 5xx or ambiguous response: never auto-retry.
            return "review";
        } catch (Exception ignored) {
            return "review";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override public void onDestroy() {
        running = false;
        active = false;
        token = null;
        QueueDb.put(this, "whatsapp_running", "0");
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
