package com.threeib.queue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public final class MessengerUtil {
    private MessengerUtil() {}
    public static String pkg(String messenger) {
        switch (messenger) {
            case "bale": return "ir.nasim";
            case "eitaa": return "ir.eitaa.messenger";
            case "rubika": return "app.rbmain.a";
            default: return "";
        }
    }
    public static String label(String messenger) {
        switch (messenger) {
            case "bale": return "Bale";
            case "eitaa": return "Eitaa";
            case "rubika": return "Rubika";
            default: return messenger;
        }
    }
    public static boolean installed(Context c, String messenger) {
        return c.getPackageManager().getLaunchIntentForPackage(pkg(messenger)) != null;
    }
    public static boolean launch(Context c, String messenger, String text, String imageUri) {
        String pkg = pkg(messenger);
        try {
            Intent i;
            if (imageUri != null && !imageUri.isEmpty()) {
                i = new Intent(Intent.ACTION_SEND);
                i.setType("image/*");
                i.putExtra(Intent.EXTRA_STREAM, Uri.parse(imageUri));
                if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.setPackage(pkg);
            } else {
                i = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
