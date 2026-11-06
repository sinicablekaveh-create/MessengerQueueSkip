package com.sinicable.messengerqueue;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local-only durable queue. Recipient rows are blocked unless row-level opt_in is true. */
final class QueueDb {
    static final String[] APPS={"Bale","Rubika","Eitaa"};
    private static final Pattern PRIVATE_PHONE=Pattern.compile(
            "(?<![0-9])(?:\\+98|0098|98|0)?9[0-9]{9}(?![0-9])");

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("queue_v1", Context.MODE_PRIVATE);
    }

    static synchronized String get(Context c,String k,String def) {
        String value=prefs(c).getString(k,def);
        return "logs".equals(k)?redactPhoneNumbers(value):value;
    }

    static synchronized void put(Context c,String k,String val) {
        prefs(c).edit().putString(k,val).apply();
    }

    private static String key(String app) {
        return app==null?"":app.toLowerCase(java.util.Locale.US);
    }

    static synchronized JSONArray items(Context c) {
        try {
            JSONArray a=new JSONArray(get(c,"items","[]"));
            boolean changed=false;
            for(int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i);
                if(o==null)continue;
                if(!o.has("id")) {o.put("id",UUID.randomUUID().toString());changed=true;}
                if(!o.has("opt_in")) {o.put("opt_in",false);changed=true;}
                if(!o.has("opt_in_source")) {o.put("opt_in_source","legacy-unverified");changed=true;}
                if(!o.has("send_sms")) {o.put("send_sms",false);changed=true;}
                if(!o.has("message")) {o.put("message","");changed=true;}
                String legacy=o.optString("status","pending");
                boolean allowed=o.optBoolean("opt_in",false);
                for(String app:APPS) {
                    String k=key(app);
                    if(!o.has(k)) {o.put(k,allowed?legacy:"blocked");changed=true;}
                }
                if(!o.has("sms")) {
                    o.put("sms",allowed&&o.optBoolean("send_sms",false)?"pending":"blocked");
                    changed=true;
                }
            }
            if(changed)save(c,a);
            return a;
        } catch(Exception e) {
            return new JSONArray();
        }
    }

    private static void save(Context c,JSONArray arr) {
        prefs(c).edit().putString("items",arr.toString()).commit();
    }

    static synchronized int append(Context c,RecipientImportParser.Result parsed) {
        JSONArray a=items(c);
        Set<String> known=new HashSet<>();
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v!=null)known.add(v.optString("phone"));
        }
        int added=0,blocked=0;
        for(RecipientImportParser.Recipient r:parsed.recipients) {
            if(!known.add(r.phone))continue;
            JSONObject v=new JSONObject();
            try {
                v.put("id",UUID.randomUUID().toString());
                v.put("phone",r.phone);
                v.put("opt_in",r.optIn);
                v.put("opt_in_source",r.optInSource);
                v.put("send_sms",r.sendSms);
                v.put("message",r.message);
                v.put("status",r.optIn?"pending":"blocked");
                for(String app:APPS)v.put(key(app),r.optIn?"pending":"blocked");
                v.put("sms",r.optIn&&r.sendSms?"pending":"blocked");
                v.put("sms_total_parts",0);
                v.put("sms_sent_parts",0);
                v.put("sms_delivered_parts",0);
                v.put("sms_started_at",0L);
            } catch(Exception ignored){}
            a.put(v);
            added++;
            if(!r.optIn)blocked++;
        }
        save(c,a);
        log(c,"Imported "+added+" unique rows; "+blocked+" blocked without row opt-in; parser invalid="+
                parsed.invalidRows+", duplicates="+parsed.duplicateRows);
        return added;
    }

    /** Interrupted attempts are never blindly retried because send may already have happened. */
    static synchronized int recoverInterrupted(Context c) {
        JSONArray a=items(c);
        int count=0;
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v==null)continue;
            for(String app:APPS) {
                String k=key(app);
                if("in_flight".equals(v.optString(k))) {
                    try {v.put(k,"review");count++;} catch(Exception ignored){}
                }
            }
            if("in_flight".equals(v.optString("sms"))) {
                try {v.put("sms","review");count++;} catch(Exception ignored){}
            }
        }
        if(count>0) {
            save(c,a);
            log(c,"Recovered "+count+" interrupted attempt(s) as REVIEW; duplicate sending blocked");
        }
        return count;
    }

    static synchronized int next(Context c,String app) {
        JSONArray a=items(c);
        String k=key(app);
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v==null||!v.optBoolean("opt_in",false))continue;
            String s=v.optString(k,"pending");
            if("pending".equals(s)||"image_pending".equals(s))return i;
        }
        return -1;
    }

    static synchronized boolean hasPending(Context c,String app){return next(c,app)>=0;}

    static synchronized boolean hasAnyPending(Context c) {
        for(String app:APPS)if(enabled(c,app)&&hasPending(c,app))return true;
        return smsEnabled(c)&&!smsNextId(c).isEmpty();
    }

    static synchronized String phone(Context c,int idx) {
        JSONObject v=items(c).optJSONObject(idx);
        return v==null?"":v.optString("phone");
    }

    static synchronized String state(Context c,int index,String app) {
        JSONObject v=items(c).optJSONObject(index);
        return v==null?"":v.optString(key(app),"pending");
    }

    static synchronized void status(Context c,int index,String app,String state) {
        JSONArray a=items(c);
        JSONObject v=a.optJSONObject(index);
        if(v!=null) {
            try {v.put(key(app),state);} catch(Exception ignored){}
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
        try {d=Integer.parseInt(get(c,"delay_"+key(app),"3"));} catch(Exception ignored){}
        return Math.max(1,Math.min(1440,d));
    }

    static synchronized boolean smsEnabled(Context c) {
        return "1".equals(get(c,"enabled_sms","0"));
    }

    static synchronized int optedInCount(Context c) {
        JSONArray a=items(c);int count=0;
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v!=null&&v.optBoolean("opt_in",false))count++;
        }
        return count;
    }

    static synchronized int smsEligibleCount(Context c) {
        JSONArray a=items(c);int count=0;
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v!=null&&v.optBoolean("opt_in",false)&&v.optBoolean("send_sms",false))count++;
        }
        return count;
    }

    static synchronized long smsDue(Context c){return due(c,"SMS");}
    static synchronized void smsDue(Context c,long when){due(c,"SMS",when);}
    static synchronized int smsDelayMinutes(Context c){return delayMinutes(c,"SMS");}

    static synchronized String smsNextId(Context c) {
        JSONArray a=items(c);
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v==null)continue;
            if(!v.optBoolean("opt_in",false)||!v.optBoolean("send_sms",false))continue;
            if("pending".equals(v.optString("sms","blocked")))return v.optString("id");
        }
        return "";
    }

    static synchronized boolean smsHasInFlight(Context c) {
        JSONArray a=items(c);
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v!=null&&"in_flight".equals(v.optString("sms")))return true;
        }
        return false;
    }

    static synchronized void smsRecoverStale(Context c) {
        JSONArray a=items(c);
        long cutoff=System.currentTimeMillis()-180_000L;
        boolean changed=false;
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v==null)continue;
            if("in_flight".equals(v.optString("sms"))&&v.optLong("sms_started_at",0L)>0&&
                    v.optLong("sms_started_at",0L)<cutoff) {
                try {v.put("sms","review");changed=true;} catch(Exception ignored){}
            }
        }
        if(changed) {
            save(c,a);
            log(c,"SMS callback timeout moved uncertain attempt(s) to REVIEW");
        }
    }

    static synchronized String smsPhoneById(Context c,String rowId) {
        JSONObject v=find(items(c),rowId);
        return v==null?"":v.optString("phone");
    }

    static synchronized String smsMessageById(Context c,String rowId) {
        JSONObject v=find(items(c),rowId);
        return v==null?"":v.optString("message","");
    }

    static synchronized boolean smsBegin(Context c,String rowId,int parts) {
        JSONArray a=items(c);
        JSONObject v=find(a,rowId);
        if(v==null||!v.optBoolean("opt_in",false)||!v.optBoolean("send_sms",false)||
                !"pending".equals(v.optString("sms")))return false;
        try {
            v.put("sms","in_flight");
            v.put("sms_total_parts",Math.max(1,parts));
            v.put("sms_sent_parts",0);
            v.put("sms_delivered_parts",0);
            v.put("sms_started_at",System.currentTimeMillis());
        } catch(Exception e){return false;}
        save(c,a);
        return true;
    }

    static synchronized void smsSentPart(Context c,String rowId,boolean ok,int resultCode) {
        JSONArray a=items(c);
        JSONObject v=find(a,rowId);
        if(v==null)return;
        String state=v.optString("sms");
        if(!"in_flight".equals(state))return;
        try {
            if(!ok) {
                v.put("sms","failed");
                v.put("sms_error","SENT callback result="+resultCode);
                smsDue(c,System.currentTimeMillis()+smsDelayMinutes(c)*60_000L);
            } else {
                int sent=v.optInt("sms_sent_parts",0)+1;
                v.put("sms_sent_parts",sent);
                if(sent>=Math.max(1,v.optInt("sms_total_parts",1))) {
                    v.put("sms","submitted");
                    v.put("sms_submitted_at",System.currentTimeMillis());
                    smsDue(c,System.currentTimeMillis()+smsDelayMinutes(c)*60_000L);
                    log(c,"SMS accepted by radio stack for "+privatePhone(v.optString("phone"))+"; awaiting delivery receipt");
                }
            }
        } catch(Exception ignored){}
        save(c,a);
    }

    static synchronized void smsDeliveredPart(Context c,String rowId,boolean ok,int resultCode) {
        JSONArray a=items(c);
        JSONObject v=find(a,rowId);
        if(v==null)return;
        String state=v.optString("sms");
        if(!"submitted".equals(state)&&!"in_flight".equals(state))return;
        try {
            if(!ok) {
                v.put("sms","review");
                v.put("sms_error","DELIVERED callback result="+resultCode);
            } else {
                int delivered=v.optInt("sms_delivered_parts",0)+1;
                v.put("sms_delivered_parts",delivered);
                if(delivered>=Math.max(1,v.optInt("sms_total_parts",1))) {
                    v.put("sms","delivered");
                    v.put("sms_delivered_at",System.currentTimeMillis());
                    log(c,"SMS delivery confirmed for "+privatePhone(v.optString("phone")));
                }
            }
        } catch(Exception ignored){}
        save(c,a);
    }

    static synchronized void smsTerminal(Context c,String rowId,String state,String detail) {
        JSONArray a=items(c);
        JSONObject v=find(a,rowId);
        if(v==null)return;
        try {
            v.put("sms",state);
            v.put("sms_error",detail==null?"":detail);
        } catch(Exception ignored){}
        save(c,a);
        smsDue(c,System.currentTimeMillis()+smsDelayMinutes(c)*60_000L);
        log(c,"SMS "+state.toUpperCase(java.util.Locale.US)+": "+(detail==null?"":detail));
    }

    private static JSONObject find(JSONArray a,String rowId) {
        for(int i=0;i<a.length();i++) {
            JSONObject v=a.optJSONObject(i);
            if(v!=null&&rowId.equals(v.optString("id")))return v;
        }
        return null;
    }

    static synchronized String summary(Context c) {
        JSONArray a=items(c);
        int opted=0;
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.optJSONObject(i);
            if(o!=null&&o.optBoolean("opt_in",false))opted++;
        }
        StringBuilder out=new StringBuilder("Contacts: ").append(a.length())
                .append(" | opted-in: ").append(opted);
        for(String app:APPS) {
            String k=key(app);
            int pending=0,submitted=0,skipped=0,review=0,active=0,imagePending=0,blocked=0;
            for(int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i);
                String s=o==null?"":o.optString(k,"pending");
                if("pending".equals(s))pending++;
                else if("image_pending".equals(s))imagePending++;
                else if("submitted".equals(s))submitted++;
                else if("review".equals(s))review++;
                else if("in_flight".equals(s))active++;
                else if("blocked".equals(s))blocked++;
                else skipped++;
            }
            out.append("\n").append(app).append(": ")
               .append(enabled(c,app)?"ON":"OFF")
               .append(" | P ").append(pending)
               .append(" | Img ").append(imagePending)
               .append(" | OK ").append(submitted)
               .append(" | Skip ").append(skipped)
               .append(" | Review ").append(review)
               .append(" | Active ").append(active)
               .append(" | Block ").append(blocked);
        }

        int p=0,sub=0,del=0,fail=0,review=0,active=0,block=0;
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.optJSONObject(i);
            String s=o==null?"":o.optString("sms","blocked");
            if("pending".equals(s))p++;
            else if("submitted".equals(s))sub++;
            else if("delivered".equals(s))del++;
            else if("failed".equals(s))fail++;
            else if("review".equals(s))review++;
            else if("in_flight".equals(s))active++;
            else block++;
        }
        out.append("\nSMS: ").append(smsEnabled(c)?"ON":"OFF")
           .append(" | P ").append(p)
           .append(" | Submitted ").append(sub)
           .append(" | Delivered ").append(del)
           .append(" | Failed ").append(fail)
           .append(" | Review ").append(review)
           .append(" | Active ").append(active)
           .append(" | Block ").append(block);
        return out.toString();
    }

    static synchronized void clear(Context c) {
        save(c,new JSONArray());
        SharedPreferences.Editor e=prefs(c).edit().putString("logs","");
        for(String app:APPS)e.putString("due_"+key(app),"0");
        e.putString("due_sms","0");
        e.apply();
        log(c,"All queues cleared");
    }

    static synchronized void log(Context c,String s) {
        String old=get(c,"logs","");
        String line=new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US)
                .format(new java.util.Date())+"  "+redactPhoneNumbers(s)+"\n";
        String all=old+line;
        if(all.length()>12000)all=all.substring(all.length()-12000);
        prefs(c).edit().putString("logs",all).apply();
    }

    static synchronized void sanitizeStoredLogs(Context c) {
        String raw=prefs(c).getString("logs","");
        String safe=redactPhoneNumbers(raw);
        if(!safe.equals(raw))prefs(c).edit().putString("logs",safe).commit();
    }

    static String privatePhone(String value) {
        if(value==null)return "recipient";
        String digits=value.replaceAll("[^0-9]","");
        if(digits.length()<4)return "recipient";
        return "recipient ••••"+digits.substring(digits.length()-4);
    }

    private static String redactPhoneNumbers(String value) {
        if(value==null||value.isEmpty())return value==null?"":value;
        Matcher matcher=PRIVATE_PHONE.matcher(value);
        StringBuffer safe=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(safe,Matcher.quoteReplacement(privatePhone(matcher.group())));
        matcher.appendTail(safe);
        return safe.toString();
    }
}
