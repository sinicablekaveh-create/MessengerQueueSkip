package com.threeib.queue;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;

public class QueueEngineService extends Service {
    public static final String ACTION_START = "com.threeib.queue.START";
    public static final String ACTION_STOP = "com.threeib.queue.STOP";
    public static final String ACTION_WAKE = "com.threeib.queue.WAKE";
    private static final int NOTIF_ID = 4401;
    private static final String CHANNEL = "three_ib_engine";

    private HandlerThread workerThread;
    private Handler worker;
    private DbHelper db;
    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock screenWakeLock;

    @Override public void onCreate() {
        super.onCreate();
        db = new DbHelper(this);
        db.recoverStaleSending();
        nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        workerThread = new HandlerThread("three-ib-engine");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_WAKE : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            userStop();
            return START_NOT_STICKY;
        }
        AppPrefs.setDesiredRunning(this, true);
        startAsForeground();
        WatchdogReceiver.scheduleNext(this, 15 * 60_000L);
        worker.removeCallbacksAndMessages(null);
        worker.post(this::tick);
        return START_STICKY;
    }

    private void tick() {
        if (!AppPrefs.desiredRunning(this)) { stopSelf(); return; }
        try {
            long sessionId = AppPrefs.sessionId(this);
            if (sessionId > 0) {
                if (System.currentTimeMillis() - AppPrefs.sessionStarted(this) > 100_000L) {
                    DbHelper.QueueItem q = db.get(sessionId);
                    if (q != null && DbHelper.SENDING.equals(q.status)) {
                        String state = AppPrefs.sessionState(this);
                        if ("SEND_CLICKED".equals(state)) db.markUnknown(sessionId, "Timed out after send click; duplicate-safe hold");
                        else if (q.attempts < 3) db.retry(sessionId, "UI automation timed out before send click");
                        else db.markFailed(sessionId, "UI automation timed out 3 times");
                    }
                    AppPrefs.clearSession(this);
                    releaseWakeLock();
                    setStatus("RECOVERED TIMEOUT");
                    schedule(3_000L);
                    return;
                }
                setStatus("SENDING #" + sessionId + " via " + MessengerUtil.label(AppPrefs.sessionMessenger(this)));
                schedule(2_000L);
                return;
            }

            if (!AccessibilityUtils.enabled(this)) {
                setStatus("WAITING: enable Accessibility");
                schedule(10_000L);
                return;
            }
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                setStatus("WAITING: contacts permission");
                schedule(10_000L);
                return;
            }

            List<DbHelper.QueueItem> pending = db.pending(200);
            if (pending.isEmpty()) {
                setStatus("RUNNING • queue empty");
                schedule(10_000L);
                return;
            }

            long now = System.currentTimeMillis();
            DbHelper.QueueItem chosen = null;
            long earliest = Long.MAX_VALUE;
            for (DbHelper.QueueItem q : pending) {
                long due = AppPrefs.lastSend(this, q.messenger) + AppPrefs.interval(this, q.messenger) * 60_000L;
                if (due <= now) { chosen = q; break; }
                if (due < earliest) earliest = due;
            }

            if (chosen == null) {
                long delay = Math.max(1_000L, earliest - now);
                setStatus("RUNNING • next in " + Math.max(1, delay / 60_000L) + " min");
                WatchdogReceiver.scheduleNext(this, Math.min(delay, 15 * 60_000L));
                schedule(Math.min(delay, 30_000L));
                return;
            }
            dispatch(chosen);
        } catch (Throwable t) {
            setStatus("ENGINE ERROR: " + shortText(t.getMessage()));
            schedule(10_000L);
        }
    }

    private void dispatch(DbHelper.QueueItem q) {
        if (!MessengerUtil.installed(this, q.messenger)) {
            db.markFailed(q.id, MessengerUtil.label(q.messenger) + " is not installed");
            schedule(1_000L);
            return;
        }
        String text = q.message == null || q.message.trim().isEmpty() ? AppPrefs.defaultMessage(this) : q.message;
        String image = AppPrefs.imageUri(this);
        if ((text == null || text.trim().isEmpty()) && (image == null || image.isEmpty())) {
            db.markFailed(q.id, "No message text or image configured");
            schedule(1_000L);
            return;
        }
        try {
            KeyguardManager km = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
            if (km != null && km.isDeviceLocked()) {
                setStatus("WAITING: unlock phone");
                schedule(15_000L);
                return;
            }
            String contact = ContactHelper.ensureContact(this, q.phone, q.contactName);
            if (!db.markSending(q.id)) { schedule(1_000L); return; }
            AppPrefs.setCurrentSession(this, q.id, q.messenger, q.phone, contact, text, image);
            acquireWakeLock();
            if (!MessengerUtil.launch(this, q.messenger, text, image)) {
                db.retry(q.id, "Could not launch messenger");
                AppPrefs.clearSession(this);
                releaseWakeLock();
                schedule(5_000L);
                return;
            }
            setStatus("OPENED " + MessengerUtil.label(q.messenger) + " • " + contact);
            schedule(2_000L);
        } catch (SecurityException se) {
            db.retry(q.id, "Contacts permission missing");
            AppPrefs.clearSession(this);
            releaseWakeLock();
            setStatus("WAITING: contacts permission");
            schedule(10_000L);
        } catch (Exception e) {
            if (q.attempts < 2) db.retry(q.id, "Pre-send error: " + shortText(e.getMessage()));
            else db.markFailed(q.id, "Pre-send error: " + shortText(e.getMessage()));
            AppPrefs.clearSession(this);
            releaseWakeLock();
            schedule(5_000L);
        }
    }

    public static void complete(Context c, long id, String result, String detail) {
        DbHelper db = new DbHelper(c);
        DbHelper.QueueItem q = db.get(id);
        if (q != null) {
            if ("SENT".equals(result)) {
                db.markSent(id);
                AppPrefs.setLastSend(c, q.messenger, System.currentTimeMillis());
            } else if ("UNKNOWN".equals(result)) {
                db.markUnknown(id, detail);
            } else {
                if (q.attempts < 3) db.retry(id, detail); else db.markFailed(id, detail);
            }
        }
        AppPrefs.clearSession(c);
        try {
            Intent s = new Intent(c, QueueEngineService.class).setAction(ACTION_WAKE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(s); else c.startService(s);
        } catch (Exception ignored) {}
    }

    private void schedule(long delay) {
        if (worker != null) worker.postDelayed(this::tick, Math.max(500L, delay));
    }

    private void acquireWakeLock() {
        releaseWakeLock();
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "threeib:send-cpu");
            wakeLock.setReferenceCounted(false);
            try { wakeLock.acquire(120_000L); } catch (Exception ignored) {}
            try {
                screenWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "threeib:send-screen");
                screenWakeLock.setReferenceCounted(false);
                screenWakeLock.acquire(120_000L);
            } catch (Exception ignored) {}
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
        }
        if (screenWakeLock != null) {
            try { if (screenWakeLock.isHeld()) screenWakeLock.release(); } catch (Exception ignored) {}
            screenWakeLock = null;
        }
    }

    private void startAsForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, QueueEngineService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 11, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("THREE IB automatic queue")
                .setContentText(AppPrefs.engineStatus(this))
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(R.drawable.ic_launcher, "STOP", stopPi).build())
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "THREE IB Queue", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Persistent automatic queue status");
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
    }

    private void setStatus(String s) {
        AppPrefs.setEngineStatus(this, s);
        if (nm != null) try { nm.notify(NOTIF_ID, buildNotification()); } catch (Exception ignored) {}
    }

    private void userStop() {
        AppPrefs.setDesiredRunning(this, false);
        long id = AppPrefs.sessionId(this);
        if (id > 0) {
            DbHelper.QueueItem q = db.get(id);
            if (q != null && DbHelper.SENDING.equals(q.status)) db.markUnknown(id, "Stopped by user during active send");
        }
        AppPrefs.clearSession(this);
        WatchdogReceiver.cancel(this);
        releaseWakeLock();
        AppPrefs.setEngineStatus(this, "STOPPED");
        if (worker != null) worker.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (AppPrefs.desiredRunning(this)) WatchdogReceiver.scheduleNext(this, 10_000L);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        releaseWakeLock();
        if (workerThread != null) workerThread.quitSafely();
        if (AppPrefs.desiredRunning(this)) WatchdogReceiver.scheduleNext(this, 10_000L);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static String shortText(String s) {
        if (s == null || s.trim().isEmpty()) return "unknown";
        s = s.replace('\n', ' ').trim();
        return s.length() > 90 ? s.substring(0, 90) : s;
    }
}
