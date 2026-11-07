package com.threeib.queue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!AppPrefs.desiredRunning(context)) return;
        WatchdogReceiver.scheduleNext(context, 10_000L);
        try {
            Intent s = new Intent(context, QueueEngineService.class).setAction(QueueEngineService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(s); else context.startService(s);
        } catch (Exception ignored) {}
    }
}
