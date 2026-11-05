package com.threeib.queue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.view.accessibility.AccessibilityManager;
import java.util.List;

public final class AccessibilityUtils {
    private AccessibilityUtils() {}
    public static boolean enabled(Context c) {
        AccessibilityManager am = (AccessibilityManager)c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) return false;
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String id = c.getPackageName() + "/" + AutoAccessibilityService.class.getName();
        String shortId = c.getPackageName() + "/." + AutoAccessibilityService.class.getSimpleName();
        for (AccessibilityServiceInfo info : list) {
            if (info.getId().equals(id) || info.getId().equals(shortId)) return true;
        }
        return false;
    }
}
