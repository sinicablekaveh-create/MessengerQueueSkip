package com.sinicable.messengerqueue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores only a queue the user previously left in desired_running state. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String a=intent==null?"":intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(a)||
           Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)||
           Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)){
            if("1".equals(QueueDb.get(context,"desired_running","0"))){
                QueueDb.log(context,"Boot/package recovery: restarting foreground queue keeper");
                QueueKeeperService.start(context);
            }
        }
    }
}

