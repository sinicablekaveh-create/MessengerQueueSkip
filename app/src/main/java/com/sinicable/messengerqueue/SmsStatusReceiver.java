package com.sinicable.messengerqueue;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;

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
            if(result!=Activity.RESULT_OK) {
                int radio=intent.getIntExtra("errorCode",Integer.MIN_VALUE);
                String detail=sentResult(result);
                if(radio!=Integer.MIN_VALUE)detail+=" | radio="+radio;
                QueueDb.log(context,"SMS send failed: "+detail);
            }
            QueueDb.smsSentPart(context,rowId,result==Activity.RESULT_OK,result);
        } else if(ACTION_DELIVERED.equals(intent.getAction())) {
            if(result!=Activity.RESULT_OK)QueueDb.log(context,"SMS delivery receipt failed: result="+result);
            QueueDb.smsDeliveredPart(context,rowId,result==Activity.RESULT_OK,result);
        }
        if("1".equals(QueueDb.get(context,"desired_running","0")))QueueKeeperService.start(context);
    }

    private static String sentResult(int code) {
        switch(code) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return "generic failure";
            case SmsManager.RESULT_ERROR_RADIO_OFF: return "radio off";
            case SmsManager.RESULT_ERROR_NULL_PDU: return "null PDU";
            case SmsManager.RESULT_ERROR_NO_SERVICE: return "no cellular service";
            case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED: return "platform send limit exceeded";
            case SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE: return "FDN check failure";
            case SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED: return "short code not allowed";
            case SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED: return "short code blocked";
            case SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE: return "radio unavailable/resetting";
            case SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY: return "modem requested retry";
            case SmsManager.RESULT_RIL_NETWORK_REJECT: return "network rejected SMS";
            case SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED: return "radio rate limited";
            case SmsManager.RESULT_RIL_NETWORK_NOT_READY: return "network not ready";
            case SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED: return "operation not allowed";
            case SmsManager.RESULT_RIL_NO_RESOURCES: return "radio resources unavailable";
            case SmsManager.RESULT_RIL_SIM_ABSENT: return "SIM absent";
            case SmsManager.RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED: return "SMS blocked during call";
            case SmsManager.RESULT_RIL_ACCESS_BARRED: return "network access barred";
            case SmsManager.RESULT_RIL_BLOCKED_DUE_TO_CALL: return "blocked due to call";
            case SmsManager.RESULT_RIL_SIM_BUSY: return "SIM busy";
            case SmsManager.RESULT_RIL_SIM_ERROR: return "SIM error";
            default: return "result="+code;
        }
    }
}
