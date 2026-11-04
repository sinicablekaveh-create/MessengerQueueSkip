package com.sinicable.messengerqueue;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Local-only durable queue. No uploaded contacts, analytics or network communication. */
final class QueueDb {
    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("queue_v1", Context.MODE_PRIVATE);
    }
    static synchronized String get(Context c, String k, String def) {
        return prefs(c).getString(k, def);
    }
    static synchronized void put(Context c, String k, String val) {
        prefs(c).edit().putString(k, val).apply();
    }
    static synchronized JSONArray items(Context c) {
        try { return new JSONArray(get(c, "items", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private static void save(Context c, JSONArray arr) {
        prefs(c).edit().putString("items", arr.toString()).commit();
    }
    static synchronized int append(Context c, List<String> numbers) {
        JSONArray a = items(c);
        Set<String> known = new HashSet<>();
        for (int i=0; i<a.length(); i++) {
            JSONObject v = a.optJSONObject(i);
            if (v != null) known.add(v.optString("phone"));
        }
        int added = 0;
        for (String n: numbers) {
            if (known.add(n)) {
                JSONObject v = new JSONObject();
                try {v.put("phone",n);v.put("status","pending");}
                catch (Exception ignored) {}
                a.put(v);added++;
            }
        }
        save(c,a);
        log(c,"Imported " + added + " unique contacts");
        return added;
    }
    /** Interrupted attempts are not automatically retried: the send may have occurred. */
    static synchronized int recoverInterrupted(Context c) {
        JSONArray a=items(c);
        int count=0;
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v!=null && "in_flight".equals(v.optString("status"))) {
                try {v.put("status","review");count++;}
                catch(Exception ignored) {}
            }
        }
        if(count>0) {
            save(c,a);
            log(c,"Recovered "+count+" interrupted attempt(s) as REVIEW, no duplicate sending");
        }
        return count;
    }
    static synchronized int next(Context c) {
        JSONArray a = items(c);
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v!=null && "pending".equals(v.optString("status"))) return i;
        }
        return -1;
    }
    static synchronized String phone(Context c, int idx) {
        JSONObject v = items(c).optJSONObject(idx);
        return v == null ? "" : v.optString("phone");
    }
    static synchronized String state(Context c, int index) {
        JSONObject v=items(c).optJSONObject(index);
        return v==null?"":v.optString("status");
    }
    static synchronized void status(Context c, int index, String state) {
        JSONArray a=items(c);
        JSONObject v=a.optJSONObject(index);
        if(v!=null) {
            try {v.put("status",state);}
            catch (Exception ignored) {}
            save(c,a);
        }
    }
    static synchronized String summary(Context c) {
        JSONArray a=items(c);
        int pending=0,submitted=0,skipped=0,review=0,inFlight=0;
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.optJSONObject(i);
            String s=o==null?"":o.optString("status");
            if("pending".equals(s))pending++;
            else if("submitted".equals(s))submitted++;
            else if("review".equals(s))review++;
            else if("in_flight".equals(s))inFlight++;
            else skipped++;
        }
        return "Total: " + a.length() + "  |  Pending: " + pending +
                "  |  UI confirmed: " + submitted + "  |  Skipped: " + skipped +
                "  |  REVIEW: " + review + "  |  Active: " + inFlight;
    }
    static synchronized void clear(Context c) {
        save(c,new JSONArray());
        prefs(c).edit().putString("logs", "").apply();
        log(c,"Queue cleared");
    }
    static synchronized void log(Context c, String s) {
        String old=get(c,"logs","");
        String line=new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US)
                .format(new java.util.Date())+"  "+s+"\n";
        String all=old+line;
        if(all.length()>8000)all=all.substring(all.length()-8000);
        prefs(c).edit().putString("logs",all).apply();
    }
}
