package com.threeib.queue;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPrefs {
    private static final String NAME = "three_ib";
    private AppPrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean desiredRunning(Context c) { return p(c).getBoolean("desired_running", false); }
    public static void setDesiredRunning(Context c, boolean v) { p(c).edit().putBoolean("desired_running", v).apply(); }

    public static String engineStatus(Context c) { return p(c).getString("engine_status", "STOPPED"); }
    public static void setEngineStatus(Context c, String v) { p(c).edit().putString("engine_status", v).apply(); }

    public static String defaultMessage(Context c) { return p(c).getString("default_message", ""); }
    public static void setDefaultMessage(Context c, String v) { p(c).edit().putString("default_message", v == null ? "" : v).apply(); }

    public static String imageUri(Context c) { return p(c).getString("image_uri", ""); }
    public static void setImageUri(Context c, String v) { p(c).edit().putString("image_uri", v == null ? "" : v).apply(); }

    public static int interval(Context c, String messenger) {
        return clamp(p(c).getInt("interval_" + messenger, 5));
    }
    public static void setInterval(Context c, String messenger, int minutes) {
        p(c).edit().putInt("interval_" + messenger, clamp(minutes)).apply();
    }
    private static int clamp(int v) { return Math.max(1, Math.min(1440, v)); }

    public static long lastSend(Context c, String messenger) { return p(c).getLong("last_send_" + messenger, 0L); }
    public static void setLastSend(Context c, String messenger, long t) { p(c).edit().putLong("last_send_" + messenger, t).apply(); }

    public static void setCurrentSession(Context c, long id, String messenger, String phone, String contact, String message, String imageUri) {
        p(c).edit()
            .putLong("session_id", id)
            .putString("session_messenger", messenger)
            .putString("session_phone", phone == null ? "" : phone)
            .putString("session_contact", contact == null ? "" : contact)
            .putString("session_message", message == null ? "" : message)
            .putString("session_image", imageUri == null ? "" : imageUri)
            .putString("session_state", "LAUNCH")
            .putLong("session_started", System.currentTimeMillis())
            .apply();
    }
    public static long sessionId(Context c) { return p(c).getLong("session_id", -1L); }
    public static String sessionMessenger(Context c) { return p(c).getString("session_messenger", ""); }
    public static String sessionPhone(Context c) { return p(c).getString("session_phone", ""); }
    public static String sessionContact(Context c) { return p(c).getString("session_contact", ""); }
    public static String sessionMessage(Context c) { return p(c).getString("session_message", ""); }
    public static String sessionImage(Context c) { return p(c).getString("session_image", ""); }
    public static String sessionState(Context c) { return p(c).getString("session_state", "IDLE"); }
    public static void setSessionState(Context c, String state) { p(c).edit().putString("session_state", state).apply(); }
    public static long sessionStarted(Context c) { return p(c).getLong("session_started", 0L); }
    public static void clearSession(Context c) {
        p(c).edit().remove("session_id").remove("session_messenger").remove("session_phone")
            .remove("session_contact").remove("session_message").remove("session_image")
            .remove("session_state").remove("session_started").apply();
    }

    public static boolean recording(Context c) { return p(c).getBoolean("recording", false); }
    public static String recordingMessenger(Context c) { return p(c).getString("recording_messenger", ""); }
    public static void setRecording(Context c, boolean v, String messenger) {
        p(c).edit().putBoolean("recording", v).putString("recording_messenger", messenger == null ? "" : messenger)
            .putInt("record_stage", 0).apply();
    }
    public static int recordStage(Context c) { return p(c).getInt("record_stage", 0); }
    public static void setRecordStage(Context c, int stage) { p(c).edit().putInt("record_stage", stage).apply(); }

    public static void putSelector(Context c, String messenger, String key, String value) {
        if (value != null && !value.isEmpty()) p(c).edit().putString("sel_" + messenger + "_" + key, value).apply();
    }
    public static String selector(Context c, String messenger, String key) {
        return p(c).getString("sel_" + messenger + "_" + key, "");
    }

    public static boolean messengerEnabled(Context c, String messenger) { return p(c).getBoolean("enabled_" + messenger, true); }
    public static void setMessengerEnabled(Context c, String messenger, boolean v) { p(c).edit().putBoolean("enabled_" + messenger, v).apply(); }

}
