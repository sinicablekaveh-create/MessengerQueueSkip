package com.sinicable.messengerqueue;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives explicit PendingIntent callbacks for SMS sent and delivery state. */
public class SmsStatusReceiver extends BroadcastReceiver {
    static final String ACTION_SENT="com.sinicable.messengerqueue.SMS_SENT";
    static final String ACTION_DELIVERED="com.sinicable.messengerqueue.SMS_DELIVERED";

    @Override public void onReceive(Context context, Intent intent) {
        if(intent==null)return;
        String rowId=intent.getStringExtra("row_id");
        if(rowId==null||rowId.isEmpty())return;
        int result=getResultCode();
        if(ACTION_SENT.equals(intent.getAction())) {
            QueueDb.smsSentPart(context,rowId,result==Activity.RESULT_OK,result);
        } else if(ACTION_DELIVERED.equals(intent.getAction())) {
            QueueDb.smsDeliveredPart(context,rowId,result==Activity.RESULT_OK,result);
        }
        if("1".equals(QueueDb.get(context,"desired_running","0")))QueueKeeperService.start(context);
    }
}