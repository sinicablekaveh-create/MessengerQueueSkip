package com.sinicable.messengerqueue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

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
    private static final String ALERT_CHANNEL="queue_keeper_alerts";
    private static final int NOTIFICATION_ID=2401;
    private static final int ALERT_ID=2402;
    private long accessibilityMissingSince=0;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable health=new Runnable(){
        @Override public void run(){
            if(!"1".equals(QueueDb.get(QueueKeeperService.this,"desired_running","0"))) {
                stopForeground(true);
                stopSelf();
                return;
            }
            QueueDb.smsRecoverStale(QueueKeeperService.this);
            SmsQueueEngine.tick(QueueKeeperService.this);
            refreshNotification();
            boolean messengerOn=messengerQueuesEnabled();
            QueueAccessibilityService a=QueueAccessibilityService.INSTANCE;
            String state=messengerOn?(a==null?"Accessibility reconnect pending":a.state()):"SMS queue active";
            if(messengerOn&&a==null) {
                if(accessibilityMissingSince<=0)accessibilityMissingSince=System.currentTimeMillis();
                if(System.currentTimeMillis()-accessibilityMissingSince>=30000)postAccessibilityAlert();
            } else {
                accessibilityMissingSince=0;
                NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                if(nm!=null)nm.cancel(ALERT_ID);
            }
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
        QueueDb.put(c,"desired_running","0");
        QueueDb.put(c,"desired_paused","0");
        try {c.stopService(new Intent(c,QueueKeeperService.class));} catch(Exception ignored){}
    }

    @Override public void onCreate(){
        super.onCreate();
        ensureChannel();
        registerNetworkMonitor();
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
            if(messengerQueuesEnabled()&&a!=null)a.togglePause();
            else {
                boolean paused="1".equals(QueueDb.get(this,"desired_paused","0"));
                QueueDb.put(this,"desired_paused",paused?"0":"1");
                QueueDb.log(this,paused?"Queue resumed":"Queue paused");
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
            if(nm!=null){
                nm.createNotificationChannel(ch);
                NotificationChannel alert=new NotificationChannel(
                        ALERT_CHANNEL,"Queue reliability alerts",NotificationManager.IMPORTANCE_HIGH);
                alert.setDescription("Alerts when Accessibility automation is unavailable");
                nm.createNotificationChannel(alert);
            }
        }
    }

    private void postAccessibilityAlert(){
        try {
            Intent settings=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;
            if(Build.VERSION.SDK_INT>=23)flags|=PendingIntent.FLAG_IMMUTABLE;
            PendingIntent open=PendingIntent.getActivity(this,41,settings,flags);
            Notification.Builder b=Build.VERSION.SDK_INT>=26
                    ?new Notification.Builder(this,ALERT_CHANNEL)
                    :new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.stat_notify_error)
             .setContentTitle("Queue needs Accessibility")
             .setContentText("Automatic queue is paused until Messenger Queue Skip Accessibility is enabled.")
             .setStyle(new Notification.BigTextStyle().bigText(
                     "Automatic queue is preserved, but Accessibility has been unavailable for 30 seconds. Tap to reopen Accessibility settings."))
             .setAutoCancel(false)
             .setContentIntent(open);
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null)nm.notify(ALERT_ID,b.build());
        } catch(Exception ignored){}
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

        boolean messengerOn=messengerQueuesEnabled();
        String state=!messengerOn
                ?"SMS queue active"
                :(QueueAccessibilityService.INSTANCE==null
                    ?"Waiting for Accessibility service"
                    :QueueAccessibilityService.INSTANCE.state());
        String network="1".equals(QueueDb.get(this,"network_available","1"))?"ONLINE":"OFFLINE";
        boolean paused="1".equals(QueueDb.get(this,"desired_paused","0"));

        Notification.Builder b=Build.VERSION.SDK_INT>=26
                ?new Notification.Builder(this,CHANNEL)
                :new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
         .setContentTitle("Messenger Queue Skip")
         .setContentText(state+" | "+network)
         .setStyle(new Notification.BigTextStyle().bigText(
                 state+" | Network: "+network+"\n"+QueueDb.summary(this)))
         .setOngoing(true)
         .setOnlyAlertOnce(true)
         .setVisibility(Notification.VISIBILITY_PRIVATE)
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

    private boolean messengerQueuesEnabled(){
        return QueueDb.enabled(this,"Bale")||QueueDb.enabled(this,"Rubika")||QueueDb.enabled(this,"Eitaa");
    }

    private void registerNetworkMonitor(){
        try {
            connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if(connectivity==null)return;
            networkCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network network){updateNetwork(true);}
                @Override public void onLost(Network network){
                    Network active=connectivity.getActiveNetwork();
                    updateNetwork(active!=null);
                }
                @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){
                    updateNetwork(caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                }
            };
            connectivity.registerDefaultNetworkCallback(networkCallback);
            Network active=connectivity.getActiveNetwork();
            NetworkCapabilities caps=active==null?null:connectivity.getNetworkCapabilities(active);
            updateNetwork(caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        } catch(Exception e){
            QueueDb.log(this,"Network monitor unavailable: "+e.getClass().getSimpleName());
        }
    }

    private void updateNetwork(boolean available){
        String now=available?"1":"0";
        String old=QueueDb.get(this,"network_available","1");
        QueueDb.put(this,"network_available",now);
        if(!now.equals(old))QueueDb.log(this,available?"Network restored":"Network lost; queue timers frozen");
        QueueAccessibilityService a=QueueAccessibilityService.INSTANCE;
        if(a!=null)a.networkChanged(available);
        refreshNotification();
    }

    @Override public void onDestroy(){
        handler.removeCallbacks(health);
        try {
            if(connectivity!=null&&networkCallback!=null)connectivity.unregisterNetworkCallback(networkCallback);
        } catch(Exception ignored){}
        QueueDb.put(this,"keeper_health","STOPPED");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
