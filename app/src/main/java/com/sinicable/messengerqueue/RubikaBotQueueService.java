package com.sinicable.messengerqueue;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

public final class RubikaBotQueueService extends Service {
    static final String ACTION_START="rubika.bot.START",ACTION_STOP="rubika.bot.STOP";
    private static final String CHANNEL="rubika_official_queue";
    private ScheduledExecutorService exec;
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Rubika Official queue",NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,RubikaOfficialActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("THREE IB • Rubika Official").setContentText("صف رسمی Rubika Bot API")
                .setOngoing(true).setContentIntent(pi).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(7101,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(7101,n);
        exec=Executors.newSingleThreadScheduledExecutor();exec.scheduleWithFixedDelay(this::tick,1,8,TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i!=null&&ACTION_STOP.equals(i.getAction())){RubikaBotQueueDb.put(this,"running","0");stopSelf();return START_NOT_STICKY;}
        if(i!=null&&ACTION_START.equals(i.getAction()))RubikaBotQueueDb.put(this,"running","1");
        return START_STICKY;
    }
    private void tick(){
        try{
            if(!"1".equals(RubikaBotQueueDb.get(this,"running","0")))return;
            long now=System.currentTimeMillis(),due=RubikaBotQueueDb.getLong(this,"next_due",0);
            if(now<due)return;
            RubikaBotQueueDb.Item item=RubikaBotQueueDb.next(this);
            if(item==null){RubikaBotQueueDb.put(this,"running","0");return;}
            String text=RubikaBotQueueDb.get(this,"message","");
            if(text.trim().isEmpty()){RubikaBotQueueDb.put(this,"running","0");return;}
            RubikaBotClient.Reply r=RubikaBotClient.sendMessage(this,item.chatId,text);
            if(r.ok()){
                RubikaBotQueueDb.sent(this,item.id);
                int delay=Math.max(1,Math.min(1440,(int)RubikaBotQueueDb.getLong(this,"delay",5)));
                RubikaBotQueueDb.put(this,"next_due",String.valueOf(System.currentTimeMillis()+delay*60_000L));
                RubikaBotQueueDb.put(this,"last_result","ارسال شد: "+item.name);
            }else{
                int a=item.attempts+1;boolean terminal=a>=3;
                RubikaBotQueueDb.fail(this,item.id,r.error(),a,terminal);
                RubikaBotQueueDb.put(this,"next_due",String.valueOf(System.currentTimeMillis()+60_000L));
                RubikaBotQueueDb.put(this,"last_result",(terminal?"ناموفق نهایی: ":"تلاش مجدد: ")+r.error());
            }
        }catch(Exception e){
            RubikaBotQueueDb.put(this,"last_result","خطا: "+e.getClass().getSimpleName()+": "+(e.getMessage()==null?"":e.getMessage()));
            RubikaBotQueueDb.put(this,"next_due",String.valueOf(System.currentTimeMillis()+60_000L));
        }
    }
    @Override public void onDestroy(){if(exec!=null)exec.shutdownNow();super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent i){return null;}
}
