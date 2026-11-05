package com.sinicable.messengerqueue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.util.Locale;

/** Invalidates learned UI profiles when a target messenger is updated/reinstalled/removed. */
public class MessengerPackageReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        if(intent==null)return;
        Uri data=intent.getData();
        String pkg=data==null?"":data.getSchemeSpecificPart();
        String app=appFor(pkg);
        if(app.isEmpty())return;

        String key=app.toLowerCase(Locale.US);
        QueueDb.put(c,"reliability_gate_last","STALE");
        QueueDb.put(c,"calib_"+key,"STALE");
        QueueDb.put(c,"calib_version_"+key,"");
        QueueDb.put(c,"selector_id_"+key+"_search_editor","");
        QueueDb.put(c,"selector_id_"+key+"_composer","");
        QueueDb.put(c,"selector_id_"+key+"_text_send","");
        QueueDb.put(c,"selector_id_"+key+"_image_send","");
        QueueDb.put(c,"selector_version_"+key+"_search_editor","");
        QueueDb.put(c,"selector_version_"+key+"_composer","");
        QueueDb.put(c,"selector_version_"+key+"_text_send","");
        QueueDb.put(c,"selector_version_"+key+"_image_send","");
        QueueDb.log(c,app+" package changed; learned UI profile invalidated");

        QueueAccessibilityService a=QueueAccessibilityService.INSTANCE;
        if(a!=null)a.messengerPackageChanged(app);
    }

    private String appFor(String pkg){
        if("ir.nasim".equals(pkg))return "Bale";
        if("app.rbmain.a".equals(pkg))return "Rubika";
        if("ir.eitaa.messenger".equals(pkg))return "Eitaa";
        return "";
    }
}

