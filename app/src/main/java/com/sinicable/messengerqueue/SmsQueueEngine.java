package com.sinicable.messengerqueue;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import java.util.ArrayList;

/** Independent, consent-gated SMS queue. It never reads incoming SMS. */
final class SmsQueueEngine {
    private SmsQueueEngine(){}

    static void tick(Context c) {
        if(!QueueDb.smsEnabled(c))return;
        if(!"1".equals(QueueDb.get(c,"desired_running","0")))return;
        if("1".equals(QueueDb.get(c,"desired_paused","0")))return;
        if(c.checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED)return;
        if(System.currentTimeMillis()<QueueDb.smsDue(c))return;
        if(QueueDb.smsHasInFlight(c))return;

        String rowId=QueueDb.smsNextId(c);
        if(rowId.isEmpty())return;

        int subId;
        try {subId=Integer.parseInt(QueueDb.get(c,"sms_subscription_id",""));}
        catch(Exception e){QueueDb.log(c,"SMS waiting: choose a SIM subscription");return;}

        String phone=QueueDb.smsPhoneById(c,rowId);
        String text=QueueDb.smsMessageById(c,rowId);
        if(text.trim().isEmpty())text=QueueDb.get(c,"message","");
        if(phone.isEmpty()||text.trim().isEmpty()) {
            QueueDb.smsTerminal(c,rowId,"failed","Missing SMS destination or message");
            return;
        }

        SmsManager manager=c.getSystemService(SmsManager.class);
        if(manager==null) {
            QueueDb.smsTerminal(c,rowId,"failed","SmsManager unavailable");
            return;
        }
        if(Build.VERSION.SDK_INT>=31)manager=manager.createForSubscriptionId(subId);
        else manager=SmsManager.getSmsManagerForSubscriptionId(subId);

        ArrayList<String> parts=manager.divideMessage(text);
        if(parts==null||parts.isEmpty())parts=new ArrayList<>();
        if(parts.isEmpty())parts.add(text);

        if(!QueueDb.smsBegin(c,rowId,parts.size()))return;
        try {
            ArrayList<PendingIntent> sent=new ArrayList<>();
            ArrayList<PendingIntent> delivered=new ArrayList<>();
            int base=Math.abs(rowId.hashCode()%100000)*20;
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;
            if(Build.VERSION.SDK_INT>=23)flags|=PendingIntent.FLAG_IMMUTABLE;
            for(int i=0;i<parts.size();i++) {
                Intent si=new Intent(c,SmsStatusReceiver.class)
                        .setAction(SmsStatusReceiver.ACTION_SENT)
                        .putExtra("row_id",rowId).putExtra("part",i);
                Intent di=new Intent(c,SmsStatusReceiver.class)
                        .setAction(SmsStatusReceiver.ACTION_DELIVERED)
                        .putExtra("row_id",rowId).putExtra("part",i);
                sent.add(PendingIntent.getBroadcast(c,base+i,si,flags));
                delivered.add(PendingIntent.getBroadcast(c,base+100000+i,di,flags));
            }
            manager.sendMultipartTextMessage(phone,null,parts,sent,delivered);
            QueueDb.log(c,"SMS dispatched to "+QueueDb.privatePhone(phone)+" via selected SIM; awaiting SENT callback");
        } catch(Exception e) {
            QueueDb.smsTerminal(c,rowId,"review","SMS dispatch threw after IN_FLIGHT; duplicate retry blocked");
            QueueDb.log(c,"SMS dispatch uncertain: "+e.getClass().getSimpleName());
        }
    }
}