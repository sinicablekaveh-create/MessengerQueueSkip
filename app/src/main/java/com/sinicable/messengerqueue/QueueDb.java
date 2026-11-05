package com.sinicable.messengerqueue;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Local-only durable three-messenger queue. No uploaded contacts, analytics or network communication. */
final class QueueDb {
    static final String[] APPS={"Bale","Rubika","Eitaa"};

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("queue_v1", Context.MODE_PRIVATE);
    }
    static synchronized String get(Context c, String k, String def) {
        return prefs(c).getString(k, def);
    }
    static synchronized void put(Context c, String k, String val) {
        prefs(c).edit().putString(k, val).apply();
    }
    private static String key(String app) {
        return app==null?"":app.toLowerCase(java.util.Locale.US);
    }
    static synchronized JSONArray items(Context c) {
        try {
            JSONArray a=new JSONArray(get(c, "items", "[]"));
            boolean changed=false;
            for(int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i);
                if(o==null)continue;
                String legacy=o.optString("status","pending");
                for(String app:APPS) {
                    String k=key(app);
                    if(!o.has(k)) {
                        try {o.put(k,legacy);changed=true;} catch(Exception ignored) {}
                    }
                }
            }
            if(changed)save(c,a);
            return a;
        } catch (Exception e) { return new JSONArray(); }
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
                try {
                    v.put("phone",n);
                    v.put("status","pending");
                    for(String app:APPS)v.put(key(app),"pending");
                } catch (Exception ignored) {}
                a.put(v);added++;
            }
        }
        save(c,a);
        log(c,"Imported " + added + " unique contacts into Bale/Rubika/Eitaa queues");
        return added;
    }
    /** Interrupted attempts are not retried because Send may already have happened. */
    static synchronized int recoverInterrupted(Context c) {
        JSONArray a=items(c);
        int count=0;
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v==null)continue;
            for(String app:APPS) {
                String k=key(app);
                if("in_flight".equals(v.optString(k))) {
                    try {v.put(k,"review");count++;} catch(Exception ignored) {}
                }
            }
        }
        if(count>0) {
            save(c,a);
            log(c,"Recovered "+count+" interrupted messenger attempt(s) as REVIEW; duplicate sending blocked");
        }
        return count;
    }
    static synchronized int next(Context c,String app) {
        JSONArray a = items(c);
        String k=key(app);
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            String s=v==null?"":v.optString(k,"pending");
            if("pending".equals(s)||"image_pending".equals(s)) return i;
        }
        return -1;
    }
    static synchronized boolean hasPending(Context c,String app) {
        return next(c,app)>=0;
    }
    static synchronized boolean hasAnyPending(Context c) {
        for(String app:APPS) {
            if(enabled(c,app)&&hasPending(c,app))return true;
        }
        return false;
    }
    static synchronized String phone(Context c, int idx) {
        JSONObject v = items(c).optJSONObject(idx);
        return v == null ? "" : v.optString("phone");
    }
    static synchronized String state(Context c, int index,String app) {
        JSONObject v=items(c).optJSONObject(index);
        return v==null?"":v.optString(key(app),"pending");
    }
    static synchronized void status(Context c, int index,String app,String state) {
        JSONArray a=items(c);
        JSONObject v=a.optJSONObject(index);
        if(v!=null) {
            try {v.put(key(app),state);}
            catch (Exception ignored) {}
            save(c,a);
        }
    }
    static synchronized boolean enabled(Context c,String app) {
        return "1".equals(get(c,"enabled_"+key(app),"1"));
    }
    static synchronized long due(Context c,String app) {
        try {return Long.parseLong(get(c,"due_"+key(app),"0"));}
        catch(Exception e){return 0L;}
    }
    static synchronized void due(Context c,String app,long when) {
        put(c,"due_"+key(app),String.valueOf(Math.max(0L,when)));
    }
    static synchronized int delayMinutes(Context c,String app) {
        int d=3;
        try {d=Integer.parseInt(get(c,"delay_"+key(app),"3"));}
        catch(Exception ignored) {}
        return Math.max(1,Math.min(1440,d));
    }
    static synchronized String summary(Context c) {
        JSONArray a=items(c);
        StringBuilder out=new StringBuilder("Contacts: ").append(a.length());
        for(String app:APPS) {
            String k=key(app);
            int pending=0,submitted=0,skipped=0,review=0,active=0,imagePending=0;
            for(int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i);
                String s=o==null?"":o.optString(k,"pending");
                if("pending".equals(s))pending++;
                else if("image_pending".equals(s))imagePending++;
                else if("submitted".equals(s))submitted++;
                else if("review".equals(s))review++;
                else if("in_flight".equals(s))active++;
                else skipped++;
            }
            out.append("\n").append(app).append(": ")
               .append(enabled(c,app)?"ON":"OFF")
               .append(" | P ").append(pending)
               .append(" | Img ").append(imagePending)
               .append(" | OK ").append(submitted)
               .append(" | Skip ").append(skipped)
               .append(" | Review ").append(review)
               .append(" | Active ").append(active);
        }
        return out.toString();
    }
    static synchronized void clear(Context c) {
        save(c,new JSONArray());
        SharedPreferences.Editor e=prefs(c).edit().putString("logs","");
        for(String app:APPS)e.putString("due_"+key(app),"0");
        e.apply();
        log(c,"All three queues cleared");
    }
    static synchronized void log(Context c, String s) {
        String old=get(c,"logs","");
        String line=new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US)
                .format(new java.util.Date())+"  "+s+"\n";
        String all=old+line;
        if(all.length()>12000)all=all.substring(all.length()-12000);
        prefs(c).edit().putString("logs",all).apply();
    }
}

