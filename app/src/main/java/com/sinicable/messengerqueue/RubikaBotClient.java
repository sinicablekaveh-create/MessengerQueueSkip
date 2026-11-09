package com.sinicable.messengerqueue;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class RubikaBotClient {
    static final class Reply {
        final int http; final JSONObject json;
        Reply(int h,JSONObject j){http=h;json=j;}
        boolean ok(){
            String s=json.optString("status","");
            return http>=200&&http<300&&(s.isEmpty()||"OK".equalsIgnoreCase(s));
        }
        JSONObject data(){JSONObject d=json.optJSONObject("data");return d==null?json:d;}
        String error(){
            String d=json.optString("status_det","");
            String m=json.optString("message","");
            if(m.isEmpty())m=d;
            if(m.isEmpty())m=json.toString();
            return "HTTP "+http+(m.isEmpty()?"":": "+m);
        }
    }

    static Reply getMe(Context c)throws Exception{return call(c,"getMe",new JSONObject());}
    static Reply getUpdates(Context c,String offset,int limit)throws Exception{
        JSONObject b=new JSONObject();b.put("limit",Math.max(1,Math.min(100,limit)));if(offset!=null&&!offset.isEmpty())b.put("offset_id",offset);
        return call(c,"getUpdates",b);
    }
    static Reply sendMessage(Context c,String chatId,String text)throws Exception{
        JSONObject b=new JSONObject();b.put("chat_id",chatId);b.put("text",text);
        return call(c,"sendMessage",b);
    }

    private static Reply call(Context c,String method,JSONObject body)throws Exception{
        String token=RubikaBotConfigStore.token(c);
        if(token.isEmpty())throw new IllegalStateException("Bot token is not saved");
        URL u=new URL("https://botapi.rubika.ir/v3/"+URLEncoder.encode(token,"UTF-8")+"/"+method);
        HttpsURLConnection conn=(HttpsURLConnection)u.openConnection();
        conn.setRequestMethod("POST");conn.setConnectTimeout(15000);conn.setReadTimeout(30000);
        conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");conn.setRequestProperty("Accept","application/json");
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        try(OutputStream o=conn.getOutputStream()){o.write(bytes);}
        int code=conn.getResponseCode();InputStream in=code<400?conn.getInputStream():conn.getErrorStream();
        String raw="{}";
        if(in!=null)try(InputStream s=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=s.read(b))!=-1){if(o.size()+n>512000)throw new IllegalArgumentException("Rubika response too large");o.write(b,0,n);}raw=o.toString(StandardCharsets.UTF_8.name());}
        conn.disconnect();
        JSONObject json;try{json=new JSONObject(raw);}catch(Exception e){json=new JSONObject().put("message",raw);}
        return new Reply(code,json);
    }
    private RubikaBotClient(){}
}
