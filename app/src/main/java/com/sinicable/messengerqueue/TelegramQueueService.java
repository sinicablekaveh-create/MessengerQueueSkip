package com.sinicable.messengerqueue;
import android.app.*;import android.content.*;import android.os.*;
public class TelegramQueueService extends Service {
 PowerManager.WakeLock lock;Handler h=new Handler(Looper.getMainLooper());
 final Runnable tick=new Runnable(){public void run(){TelegramEngine e=TelegramEngine.get(TelegramQueueService.this);if(!e.running&&!e.busy){stopSelf();return;}h.postDelayed(this,5000);}};
 public void onCreate(){super.onCreate();NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("telegram_queue","Telegram queue",NotificationManager.IMPORTANCE_LOW));
 PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,TelegramActivity.class),PendingIntent.FLAG_IMMUTABLE);
 startForeground(730,new Notification.Builder(this,"telegram_queue").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Telegram automatic queue").setContentText("Tap to view or pause").setContentIntent(open).setOngoing(true).build());
 lock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"threeib:telegram");lock.acquire();h.post(tick);}
 public int onStartCommand(Intent i,int f,int id){return START_NOT_STICKY;}
 public void onDestroy(){h.removeCallbacks(tick);if(lock!=null&&lock.isHeld())lock.release();TelegramEngine.get(this).pause();super.onDestroy();}
 public IBinder onBind(Intent i){return null;}
}
