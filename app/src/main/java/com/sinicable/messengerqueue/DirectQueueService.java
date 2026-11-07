package com.sinicable.messengerqueue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground queue for opted-in contacts through the local direct account gateway.
 * Stays active after Activity closes. Interrupted/ambiguous attempts become REVIEW.
 */
public final class DirectQueueService extends Service {
    public static final String ACTION_START="com.sinicable.messengerqueue.direct.START";
    public static final String ACTION_PAUSE="com.sinicable.messengerqueue.direct.PAUSE";
    private static final String CHANNEL="threeib_direct_queue";
    private static final String[] PROVIDERS={"bale","rubika","eitaa"};
    private final AtomicBoolean running=new AtomicBoolean(false);
    private volatile Thread worker;

    @Override public void onCreate(){
        super.onCreate();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
            .createNotificationChannel(new NotificationChannel(CHANNEL,
                    "THREE IB direct message queue", NotificationManager.IMPORTANCE_LOW));
        QueueDb.recoverInterrupted(this);
    }

    private void notifyForeground(){
        Intent pause=new Intent(this,DirectQueueService.class);
        pause.setAction(ACTION_PAUSE);
        PendingIntent stop=PendingIntent.getService(this,1,pause,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("THREE IB | صف ارسال مستقل")
            .setContentText("فقط برای مخاطبان دارای رضایت • در حال اجرا")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause,"PAUSE",stop)
            .build();
        if(Build.VERSION.SDK_INT>=29)startForeground(3002,notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(3002,notification);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(ACTION_PAUSE.equals(action)){
            QueueDb.put(this,"direct_running","0");
            running.set(false);
            QueueDb.put(this,"direct_service_state","Paused by user");
            if(worker==null||!worker.isAlive())stopSelf();
            return START_NOT_STICKY;
        }
        if(ACTION_START.equals(action)||("1".equals(QueueDb.get(this,"direct_running","0")))){
            notifyForeground();
            if(running.compareAndSet(false,true)){
                QueueDb.put(this,"direct_running","1");
                Thread thread=new Thread(this::dispatch,"threeib-direct-queue");
                worker=thread;thread.start();
            }
            return START_STICKY;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    private void dispatch(){
        try{
            EmbeddedGatewayService.ensureStarted(getApplicationContext());
            // Gateway is also a foreground service; avoid sending until it is ready.
            for(int n=0;n<20&&running.get();n++){
                if(!EmbeddedGatewayService.credential(this).isEmpty()){
                    try{
                        GatewayClient.Reply reply=GatewayClient.call(this,"GET",
                                "/app/providers",null,null,null,null);
                        if(reply.success())break;
                    }catch(Exception ignored){}
                }
                Thread.sleep(1000);
            }
            while(running.get()){
                boolean selectedPending=false;
                boolean submitted=false;
                long nextWait=30_000;
                for(String provider:PROVIDERS){
                    if(!running.get())break;
                    if(!"1".equals(QueueDb.get(this,"direct_selected_"+provider,"0")))continue;
                    int index=QueueDb.next(this,provider);
                    if(index<0)continue;
                    selectedPending=true;
                    long wait=QueueDb.due(this,provider)-System.currentTimeMillis();
                    if(wait>0){
                        nextWait=Math.min(nextWait,wait);
                        continue;
                    }
                    String instance=QueueDb.get(this,"direct_instance_"+provider,"");
                    String alias=GatewayClient.alias(provider);
                    if(instance.isEmpty()){
                        pauseProvider(provider,"Account not linked");
                        continue;
                    }
                    try{
                        GatewayClient.Reply status=GatewayClient.call(this,"GET",
                            "/devices/"+alias+"/status",null,alias,instance,null);
                        JSONObject state=status.result();
                        if(!status.success()||!"authenticated".equals(state.optString("auth")) ||
                                !"connected".equals(state.optString("transport"))){
                            pauseProvider(provider,"Not authenticated/connected");
                            continue;
                        }
                    }catch(Exception error){
                        pauseProvider(provider,"Account status unavailable");
                        continue;
                    }
                    String recipient=GatewayClient.normalizedPhone(QueueDb.phone(this,index));
                    if(recipient.isEmpty()){
                        QueueDb.status(this,index,provider,"failed");
                        continue;
                    }
                    String content=QueueDb.get(this,"direct_text","");
                    JSONObject row=QueueDb.items(this).optJSONObject(index);
                    if(row!=null&&!row.optString("message","").isEmpty())
                        content=row.optString("message","");
                    boolean picture="1".equals(QueueDb.get(this,"direct_image_enabled","0"));
                    File image=new File(getFilesDir(),"direct-image");
                    if(content.trim().isEmpty()&&(!picture||!image.isFile())){
                        pauseProvider(provider,"No message content");
                        continue;
                    }
                    String key=QueueDb.directBegin(this,index,provider);
                    if(key.isEmpty()){
                        pauseProvider(provider,"Could not persist send attempt");
                        continue;
                    }
                    submitted=true;
                    QueueDb.put(this,"direct_service_state",
                            "Submitting one "+provider+" recipient (private)");
                    String state="review";
                    String sendId="";
                    PowerManager.WakeLock lock=((PowerManager)getSystemService(POWER_SERVICE))
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"threeib:direct-send");
                    lock.acquire(75_000L);
                    try{
                        GatewayClient.Reply reply;
                        if(picture){
                            String mime=QueueDb.get(this,"direct_image_mime","image/jpeg");
                            reply=GatewayClient.sendImage(this,alias,instance,key,recipient,content,image,mime);
                        }else{
                            JSONObject payload=new JSONObject();
                            payload.put("phone",recipient);
                            payload.put("message",content);
                            reply=GatewayClient.call(this,"POST","/send/message",
                                payload,alias,instance,key);
                        }
                        sendId=reply.result().optString("send_id","");
                        if(reply.http==200 && reply.success()){
                            state="submitted";
                        }else if(reply.http==202 && "ACCEPTED".equals(reply.code())){
                            state="submitted"; // durable gateway acceptance, not recipient delivery
                        }else if(reply.http>=400&&reply.http<500 && !"SEND_UNKNOWN".equals(reply.code())){
                            state="failed";
                        }
                        // 5xx, timeout, malformed body, and unknown stay REVIEW.
                    }catch(Exception error){
                        state="review";
                    }finally{
                        if(lock.isHeld())lock.release();
                    }
                    QueueDb.directFinish(this,index,provider,state,sendId);
                    if(!"submitted".equals(state)){
                        pauseProvider(provider,"Send "+state+"; manual review required");
                    }else{
                        QueueDb.due(this,provider,System.currentTimeMillis()
                            +QueueDb.delayMinutes(this,provider)*60_000L);
                        QueueDb.put(this,"direct_service_state","Gateway accepted "+provider+
                            " operation; this is not a delivery receipt.");
                    }
                    // Respect all selected app-specific intervals.
                    break;
                }
                if(!selectedPending){
                    QueueDb.put(this,"direct_service_state","All selected queues completed");
                    break;
                }
                if(running.get()&&!submitted)Thread.sleep(Math.min(30_000,Math.max(1500,nextWait)));
            }
        }catch(InterruptedException ignored){
            Thread.currentThread().interrupt();
        }catch(Exception error){
            QueueDb.put(this,"direct_service_state","Queue error: "+error.getClass().getSimpleName());
        }finally{
            running.set(false);
            QueueDb.put(this,"direct_running","0");
            worker=null;
            stopSelf();
        }
    }

    private void pauseProvider(String provider,String message){
        QueueDb.put(this,"direct_selected_"+provider,"0");
        QueueDb.put(this,"direct_service_state",provider+": "+message);
    }
    @Override public void onDestroy(){
        running.set(false);
        Thread thread=worker;
        if(thread!=null)thread.interrupt();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
