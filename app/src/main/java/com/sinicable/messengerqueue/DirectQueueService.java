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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
                    JSONObject peer=null;
                    if("rubika".equals(provider)){
                        try{
                            peer=resolveRubikaPeer(recipient,alias,instance);
                        }catch(Exception error){
                            pauseProvider(provider,"Recipient resolution unavailable: "+safeIssue(error));
                            running.set(false);
                            break;
                        }
                        if(peer==null){
                            QueueDb.status(this,index,provider,"failed");
                            QueueDb.put(this,"direct_service_state",
                                    "Rubika recipient could not be resolved from the imported phone number");
                            continue;
                        }
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
                    String providerMessageId="";
                    PowerManager.WakeLock lock=((PowerManager)getSystemService(POWER_SERVICE))
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"threeib:direct-send");
                    lock.acquire(75_000L);
                    try{
                        GatewayClient.Reply reply;
                        if(picture){
                            String mime=QueueDb.get(this,"direct_image_mime","image/jpeg");
                            reply=GatewayClient.sendImage(this,alias,instance,key,peer,recipient,
                                    content,image,mime);
                        }else{
                            JSONObject payload=new JSONObject();
                            if(peer!=null) payload.put("peer",peer);
                            else payload.put("phone",recipient);
                            payload.put("message",content);
                            reply=GatewayClient.call(this,"POST","/send/message",
                                payload,alias,instance,key);
                        }
                        sendId=reply.result().optString("send_id","");
                        providerMessageId=reply.result().optString("message_id","");
                        // HTTP 200/202 does not establish send success: inspect operation state.
                        if(reply.success()){
                            String phase=reply.result().optString("state","");
                            if("succeeded".equals(phase)) {
                                state=confirmedSendIdentity(provider,providerMessageId)?"submitted":"review";
                            } else if("failed".equals(phase)) {
                                state="failed";
                            } else if(("queued".equals(phase) || "sending".equals(phase))
                                    && !sendId.isEmpty()) {
                                // Poll same operation by ID, without submitting a duplicate.
                                for(int check=0;check<8 && running.get();check++){
                                    Thread.sleep(2000L);
                                    GatewayClient.Reply checkReply=GatewayClient.call(this,"GET",
                                        "/send/operations/"+sendId,null,alias,instance,null);
                                    if(!checkReply.success()) break;
                                    JSONObject checked=checkReply.result();
                                    phase=checked.optString("state","");
                                    if("succeeded".equals(phase)){
                                        providerMessageId=checked.optString("message_id","");
                                        state=confirmedSendIdentity(provider,providerMessageId)?"submitted":"review";
                                        break;
                                    }
                                    if("failed".equals(phase)){state="failed";break;}
                                    if("unknown".equals(phase))break;
                                }
                            }
                        } else if(reply.http>=400 && reply.http<500 && reply.http!=409
                                && reply.http!=429 && !"SEND_UNKNOWN".equals(reply.code())){
                            state="failed";
                        }
                        // Unresolved queues, unknown, 409/429, 5xx and timeout remain REVIEW.
                    }catch(Exception error){
                        state="review";
                        QueueDb.put(this,"direct_service_state",
                                provider+" send error: "+safeIssue(error));
                    }finally{
                        if(lock.isHeld())lock.release();
                    }
                    QueueDb.directFinish(this,index,provider,state,sendId);
                    if(!"submitted".equals(state)){
                        pauseProvider(provider,"Send "+state+"; manual review required");
                    }else{
                        QueueDb.due(this,provider,System.currentTimeMillis()
                            +QueueDb.delayMinutes(this,provider)*60_000L);
                        QueueDb.put(this,"direct_service_state","Gateway verified "+provider+
                            " operation"+(providerMessageId.isEmpty()?"":" message_id="+providerMessageId)+
                            "; this is not a delivery receipt.");
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

    private JSONObject resolveRubikaPeer(String phone,String alias,String instance)throws Exception {
        String resolveKey="threeib-rubika-contact-"+stableRecipientKey(instance+"|"+phone);
        JSONObject contact=new JSONObject();
        contact.put("phone",phone);
        contact.put("first_name","THREE IB");
        GatewayClient.Reply reply=GatewayClient.call(this,"POST","/operations/contacts.add",
                contact,alias,instance,resolveKey);
        String phase=reply.result().optString("state","");
        String operationId=reply.result().optString("send_id","");
        if(reply.success() && ("queued".equals(phase)||"sending".equals(phase))
                && !operationId.isEmpty()){
            for(int check=0;check<8&&running.get();check++){
                Thread.sleep(1500L);
                GatewayClient.Reply current=GatewayClient.call(this,"GET",
                        "/send/operations/"+operationId,null,alias,instance,null);
                reply=current;
                phase=current.result().optString("state","");
                if("succeeded".equals(phase)||"failed".equals(phase)
                        ||"unknown".equals(phase))break;
            }
        }
        if(!reply.success() || !"succeeded".equals(reply.result().optString("state",""))){
            if(reply.http>=400&&reply.http<500&&reply.http!=409&&reply.http!=429)
                return null;
            throw new IllegalStateException(reply.summary());
        }
        JSONObject results=reply.result();
        JSONObject data=results.optJSONObject("data");
        if(data==null){
            Object raw=results.opt("data");
            if(raw instanceof String){
                try{data=new JSONObject((String)raw);}catch(Exception ignored){}
            }
        }
        if(data==null){
            JSONObject operationResult=results.optJSONObject("result");
            data=operationResult==null?null:operationResult.optJSONObject("data");
            if(data==null&&operationResult!=null){
                Object raw=operationResult.opt("data");
                if(raw instanceof String){
                    try{data=new JSONObject((String)raw);}catch(Exception ignored){}
                }
            }
        }
        String guid="";
        if(data!=null){
            JSONObject user=data.optJSONObject("user");
            if(user!=null)guid=user.optString("user_guid","");
            if(guid.isEmpty())guid=data.optString("user_guid","");
        }
        if(!guid.matches("^u0[A-Za-z0-9]{1,126}$"))return null;
        JSONObject peer=new JSONObject();
        peer.put("type","user");
        peer.put("id",guid);
        return peer;
    }

    private static boolean confirmedSendIdentity(String provider,String messageId){
        if(!"rubika".equals(provider))return true;
        if(messageId==null||messageId.isEmpty()||messageId.length()>19)return false;
        try{
            long parsed=Long.parseLong(messageId);
            return parsed>0 && Long.toString(parsed).equals(messageId);
        }catch(Exception ignored){
            return false;
        }
    }

    private static String stableRecipientKey(String value){
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();
            for(int i=0;i<12;i++)
                out.append(String.format(java.util.Locale.US,"%02x",digest[i]));
            return out.toString();
        }catch(Exception ignored){
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String safeIssue(Exception error){
        String message=error.getMessage();
        if(message==null||message.trim().isEmpty())return error.getClass().getSimpleName();
        message=message.replaceAll("[\r\n]+"," ").trim();
        if(message.length()>120)message=message.substring(0,120);
        return message;
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
