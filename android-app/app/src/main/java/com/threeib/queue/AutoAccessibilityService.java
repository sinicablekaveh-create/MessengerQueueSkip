package com.threeib.queue;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AutoAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable driveRunnable = this::drive;
    private long stateChangedAt = 0L;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (AppPrefs.recording(this)) record(event, pkg);
        long id = AppPrefs.sessionId(this);
        if (id <= 0) return;
        String targetPkg = MessengerUtil.pkg(AppPrefs.sessionMessenger(this));
        if (!targetPkg.equals(pkg)) return;
        handler.removeCallbacks(driveRunnable);
        handler.postDelayed(driveRunnable, 250L);
    }

    private void drive() {
        long id = AppPrefs.sessionId(this);
        if (id <= 0) return;
        String messenger = AppPrefs.sessionMessenger(this);
        String pkg = MessengerUtil.pkg(messenger);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) return;

        String state = AppPrefs.sessionState(this);
        if (stateChangedAt == 0L) stateChangedAt = System.currentTimeMillis();
        long age = System.currentTimeMillis() - stateChangedAt;

        try {
            switch (state) {
                case "LAUNCH":
                    if (clickTrained(root, messenger, "search_btn") || clickMatching(root, new String[]{"search", "جستجو", "جست و جو"})) {
                        move("SEARCH_FIELD");
                    } else if (findEditable(root, false) != null) {
                        move("SEARCH_FIELD");
                    } else if (age > 10_000L) {
                        fail(id, "Could not find search control in " + MessengerUtil.label(messenger));
                    }
                    break;

                case "SEARCH_FIELD": {
                    AccessibilityNodeInfo edit = findTrained(root, messenger, "search_field");
                    if (edit == null) edit = findEditable(root, false);
                    if (edit != null) {
                        String query = AppPrefs.sessionContact(this);
                        if (query == null || query.isEmpty()) query = AppPrefs.sessionPhone(this);
                        if (setText(edit, query)) move("SEARCH_RESULT");
                    } else if (age > 10_000L) fail(id, "Search input not found");
                    break;
                }

                case "SEARCH_RESULT": {
                    String contact = AppPrefs.sessionContact(this);
                    String phone = AppPrefs.sessionPhone(this);
                    AccessibilityNodeInfo result = findContaining(root, contact);
                    if (result == null) result = findContaining(root, lastDigits(phone, 7));
                    if (result == null) result = findContaining(root, lastDigits(phone, 4));
                    if (result != null && clickNodeOrParent(result)) move("CHAT");
                    else if (age > 14_000L) fail(id, "Contact result not found. Open messenger once to refresh contacts or use Record/Test.");
                    break;
                }

                case "CHAT": {
                    String image = AppPrefs.sessionImage(this);
                    if (image != null && !image.isEmpty()) {
                        if (clickTrained(root, messenger, "send_btn") || clickMatching(root, new String[]{"send", "ارسال", "فرستادن"})) {
                            AppPrefs.setSessionState(this, "SEND_CLICKED");
                            handler.postDelayed(() -> success(id), 1200L);
                        } else if (age > 12_000L) fail(id, "Attachment preview send button not found");
                    } else {
                        AccessibilityNodeInfo edit = findTrained(root, messenger, "message_field");
                        if (edit == null) edit = findEditable(root, true);
                        if (edit != null && setText(edit, AppPrefs.sessionMessage(this))) move("MESSAGE_READY");
                        else if (age > 12_000L) fail(id, "Message input not found");
                    }
                    break;
                }

                case "MESSAGE_READY":
                    if (clickTrained(root, messenger, "send_btn") || clickMatching(root, new String[]{"send", "ارسال", "فرستادن"})) {
                        AppPrefs.setSessionState(this, "SEND_CLICKED");
                        handler.postDelayed(() -> success(id), 1000L);
                    } else if (age > 10_000L) {
                        fail(id, "Send button not found. Use Record/Test once for this messenger.");
                    }
                    break;

                case "SEND_CLICKED":
                    break;
            }
        } finally {
            try { root.recycle(); } catch (Exception ignored) {}
        }
    }

    private void move(String state) {
        AppPrefs.setSessionState(this, state);
        stateChangedAt = System.currentTimeMillis();
        handler.removeCallbacks(driveRunnable);
        handler.postDelayed(driveRunnable, 600L);
    }

    private void success(long id) {
        if (AppPrefs.sessionId(this) != id) return;
        QueueEngineService.complete(this, id, "SENT", "Send control clicked");
        stateChangedAt = 0L;
        try { performGlobalAction(GLOBAL_ACTION_HOME); } catch (Exception ignored) {}
    }

    private void fail(long id, String why) {
        if (AppPrefs.sessionId(this) != id) return;
        QueueEngineService.complete(this, id, "FAILED", why);
        stateChangedAt = 0L;
        try { performGlobalAction(GLOBAL_ACTION_HOME); } catch (Exception ignored) {}
    }

    private boolean setText(AccessibilityNodeInfo node, String value) {
        if (node == null || value == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) || ok;
        return ok;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root, boolean preferLast) {
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collectEditable(root, found);
        if (found.isEmpty()) return null;
        return found.get(preferLast ? found.size()-1 : 0);
    }

    private void collectEditable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null) return;
        if (n.isEditable() || "android.widget.EditText".contentEquals(n.getClassName())) out.add(n);
        for (int i=0;i<n.getChildCount();i++) collectEditable(n.getChild(i), out);
    }

    private boolean clickMatching(AccessibilityNodeInfo root, String[] tokens) {
        AccessibilityNodeInfo n = findTokenNode(root, tokens);
        return n != null && clickNodeOrParent(n);
    }

    private AccessibilityNodeInfo findTokenNode(AccessibilityNodeInfo n, String[] tokens) {
        if (n == null) return null;
        String hay = norm(text(n) + " " + desc(n) + " " + id(n));
        for (String token : tokens) if (!token.isEmpty() && hay.contains(norm(token))) return n;
        for (int i=0;i<n.getChildCount();i++) {
            AccessibilityNodeInfo r = findTokenNode(n.getChild(i), tokens);
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findContaining(AccessibilityNodeInfo n, String needle) {
        if (n == null || needle == null || needle.trim().isEmpty()) return null;
        String nn = norm(needle);
        String hay = norm(text(n) + " " + desc(n));
        if (hay.contains(nn)) return n;
        for (int i=0;i<n.getChildCount();i++) {
            AccessibilityNodeInfo r = findContaining(n.getChild(i), needle);
            if (r != null) return r;
        }
        return null;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i=0;i<6 && cur!=null;i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            cur = cur.getParent();
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean clickTrained(AccessibilityNodeInfo root, String messenger, String key) {
        AccessibilityNodeInfo n = findTrained(root, messenger, key);
        return n != null && clickNodeOrParent(n);
    }

    private AccessibilityNodeInfo findTrained(AccessibilityNodeInfo root, String messenger, String key) {
        String sel = AppPrefs.selector(this, messenger, key);
        if (sel.isEmpty()) return null;
        if (sel.startsWith("id:")) {
            try {
                List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(sel.substring(3));
                if (list != null && !list.isEmpty()) return list.get(0);
            } catch (Exception ignored) {}
        } else if (sel.startsWith("text:")) {
            return findContaining(root, sel.substring(5));
        }
        return null;
    }

    private void record(AccessibilityEvent event, String pkg) {
        String messenger = AppPrefs.recordingMessenger(this);
        if (!MessengerUtil.pkg(messenger).equals(pkg)) return;
        AccessibilityNodeInfo src = event.getSource();
        if (src == null) return;
        int stage = AppPrefs.recordStage(this);
        try {
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                String hay = norm(text(src) + " " + desc(src) + " " + id(src));
                if (stage == 0 && (hay.contains("search") || hay.contains("جستجو") || hay.contains("جست و جو"))) {
                    saveSelector(messenger, "search_btn", src);
                    AppPrefs.setRecordStage(this, 1);
                } else if (stage == 2) {
                    AppPrefs.setRecordStage(this, 3);
                } else if (stage >= 4) {
                    saveSelector(messenger, "send_btn", src);
                    AppPrefs.setRecordStage(this, 5);
                    AppPrefs.setRecording(this, false, messenger);
                    AppPrefs.setEngineStatus(this, "TRAINING SAVED for " + MessengerUtil.label(messenger));
                }
            } else if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                if (src.isEditable() || "android.widget.EditText".contentEquals(src.getClassName())) {
                    if (stage <= 1) {
                        saveSelector(messenger, "search_field", src);
                        AppPrefs.setRecordStage(this, 2);
                    } else if (stage >= 3 && stage < 4) {
                        saveSelector(messenger, "message_field", src);
                        AppPrefs.setRecordStage(this, 4);
                    }
                }
            }
        } finally {
            try { src.recycle(); } catch (Exception ignored) {}
        }
    }

    private void saveSelector(String messenger, String key, AccessibilityNodeInfo n) {
        String viewId = id(n);
        if (!viewId.isEmpty()) AppPrefs.putSelector(this, messenger, key, "id:" + viewId);
        else {
            String label = !desc(n).isEmpty() ? desc(n) : text(n);
            if (!label.isEmpty()) AppPrefs.putSelector(this, messenger, key, "text:" + label);
        }
    }

    private String id(AccessibilityNodeInfo n) { try { return n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName(); } catch (Exception e) { return ""; } }
    private String text(AccessibilityNodeInfo n) { return n.getText() == null ? "" : n.getText().toString(); }
    private String desc(AccessibilityNodeInfo n) { return n.getContentDescription() == null ? "" : n.getContentDescription().toString(); }
    private String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ي','ی').replace('ك','ک').trim(); }
    private String lastDigits(String s, int n) {
        if (s == null) return "";
        String d = s.replaceAll("\\D", "");
        return d.substring(Math.max(0, d.length()-n));
    }

    @Override public void onInterrupt() {}
    @Override public void onServiceConnected() {
        super.onServiceConnected();
        AppPrefs.setEngineStatus(this, AppPrefs.desiredRunning(this) ? "ACCESSIBILITY READY" : "STOPPED • Accessibility ready");
    }
}
