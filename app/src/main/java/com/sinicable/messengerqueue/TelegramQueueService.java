package com.sinicable.messengerqueue;

import android.app.*;
import android.content.*;
import android.os.*;

public class TelegramQueueService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            TelegramEngine engine = TelegramEngine.get(TelegramQueueService.this);
            if (!engine.running && !engine.busy) {
                stopSelf();
                return;
            }
            handler.postDelayed(this, 5000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                "telegram_queue", "Telegram queue", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, TelegramActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "telegram_queue")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Telegram automatic queue")
                .setContentText("Tap to view or pause")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(730, notification);
        handler.post(tick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        TelegramEngine.get(this).pause();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
