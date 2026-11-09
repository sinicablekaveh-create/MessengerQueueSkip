package com.sinicable.messengerqueue;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Routes each product flavor to one dedicated channel UI. */
public final class ChannelLauncherActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String channel = BuildConfig.TARGET_CHANNEL;
        Intent next;
        switch (channel) {
            case "rubika":
                next = new Intent(this, RubikaSimpleActivity.class);
                break;
            case "bale":
            case "eitaa":
                next = new Intent(this, DirectAccountActivity.class);
                next.putExtra("fixed_provider", channel);
                break;
            case "telegram":
                next = new Intent(this, TelegramActivity.class);
                break;
            case "sms":
                next = new Intent(this, SmsOnlyActivity.class);
                break;
            case "whatsapp":
                next = new Intent(this, WhatsAppCloudActivity.class);
                break;
            default:
                throw new IllegalStateException("Unsupported channel flavor: " + channel);
        }
        startActivity(next);
        finish();
    }
}
