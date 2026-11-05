package com.sinicable.messengerqueue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Build;

/**
 * User-visible foreground watchdog for a user-started long-running queue.
 * It does not send messages itself; it keeps the queue process important,
 * mirrors durable desired_running state, and exposes pause/stop controls.
 */
public class QueueKeeperService extends Service {
    static final String ACTION_START="com.sinicable.messengerqueue.KEEPER_START";
    static final String ACTION_TOGGLE="com.sinicable.messengerqueue.KEEPER_TOGGLE";
    static final String ACTION_STOP="com.sinicable.messengerqueue.KEEPER_STOP";
    private static final String CHANNEL="queue_keeper";
    private static final int NOTIFICATION_ID=2401;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable health=new Runnable(){
        @Override public void run(){
            if(!"1".equals(QueueDb.get(QueueKeeperService.this,"desired_running","0"))) {
                stopForeground(true);
                stopSelf();
                return;
            }
            refreshNotification();
            QueueAccessibilityService a=QueueAccessibilityService.INSTANCE;
            String state=a==null?"Accessibility reconnect pending":a.state();
            QueueDb.put(QueueKeeperService.this,"keeper_health",state);
            QueueDb.put(QueueKeeperService.this,"keeper_heartbeat",
                    String.valueOf(System.currentTimeMillis()));
            handler.postDelayed(this,10000);
        }
    };

    public static void start(Context c) {
        try {
            Intent i=new Intent(c,QueueKeeperService.class).setAction(ACTION_START);
            if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i); else c.startService(i);
        } catch(Exception e) {
            QueueDb.log(c,"QueueKeeper start failed: "+e.getClass().getSimpleName());
        }
    }

    public static void stop(Context c) {
        try {c.stopService(new Intent(c,QueueKeeperService.class));} catch(Exception ignored){}
    }

    @Override public void onCreate(){
        super.onCreate();
        ensureChannel();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?ACTION_START:intent.getAction();
        if(ACTION_STOP.equals(action)){
            QueueAccessibilityService a=QueueAccessibilityService.INSTANCE;
            if(a!=null)a.stopQueue();
            QueueDb.put(this,"desired_running","0");
            QueueDb.put(this,"desired_paused","0");
            stopForeground(true);stopSelf();
            return START_NOT_STICKY;
        }
        if(ACTION_TOGGLE.equals(action)){
            QueueAccessibilityService a=QueueAccessibilityService.INSTANCE;
            if(a!=null)a.togglePause();
            else {
                boolean paused="1".equals(QueueDb.get(this,"desired_paused","0"));
                QueueDb.put(this,"desired_paused",paused?"0":"1");
            }
        }
        if(!"1".equals(QueueDb.get(this,"desired_running","0"))){
            stopSelf();return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID,buildNotification());
        handler.removeCallbacks(health);
        handler.post(health);
        QueueDb.log(this,"Foreground queue keeper active");
        return START_STICKY;
    }

    private void ensureChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(
                    CHANNEL,"Messenger queue",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps user-started automatic queues visible and resilient");
            ch.setShowBadge(false);
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null)nm.createNotificationChannel(ch);
        }
    }

    private PendingIntent serviceAction(String action,int request){
        Intent i=new Intent(this,QueueKeeperService.class).setAction(action);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT;
        if(Build.VERSION.SDK_INT>=23)flags|=PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this,request,i,flags);
    }

    private Notification buildNotification(){
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_NEW_TASK);
        int pflags=PendingIntent.FLAG_UPDATE_CURRENT;
        if(Build.VERSION.SDK_INT>=23)pflags|=PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content=PendingIntent.getActivity(this,1,open,pflags);

        String state=QueueAccessibilityService.INSTANCE==null
                ?"Waiting for Accessibility service"
                :QueueAccessibilityService.INSTANCE.state();
        boolean paused="1".equals(QueueDb.get(this,"desired_paused","0"));

        Notification.Builder b=Build.VERSION.SDK_INT>=26
                ?new Notification.Builder(this,CHANNEL)
                :new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
         .setContentTitle("Messenger Queue Skip")
         .setContentText(state)
         .setStyle(new Notification.BigTextStyle().bigText(
                 state+"\n"+QueueDb.summary(this)))
         .setOngoing(true)
         .setOnlyAlertOnce(true)
         .setCategory(Notification.CATEGORY_SERVICE)
         .setContentIntent(content)
         .addAction(new Notification.Action.Builder(
                 android.R.drawable.ic_media_pause,
                 paused?"RESUME":"PAUSE",
                 serviceAction(ACTION_TOGGLE,2)).build())
         .addAction(new Notification.Action.Builder(
                 android.R.drawable.ic_menu_close_clear_cancel,
                 "STOP",
                 serviceAction(ACTION_STOP,3)).build());
        return b.build();
    }

    private void refreshNotification(){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(nm!=null)nm.notify(NOTIFICATION_ID,buildNotification());
    }

    @Override public void onDestroy(){
        handler.removeCallbacks(health);
        QueueDb.put(this,"keeper_health","STOPPED");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
