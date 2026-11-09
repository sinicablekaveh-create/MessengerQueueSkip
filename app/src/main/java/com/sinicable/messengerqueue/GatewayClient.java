package com.sinicable.messengerqueue;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Narrow local-only HTTP client for GoOmni v2.4.1. */
final class GatewayClient {
    static final class Reply {
        final int http;
        final JSONObject data;
        Reply(int http, JSONObject data){this.http=http;this.data=data;}
        JSONObject result(){return data.optJSONObject("results") == null ? new JSONObject() : data.optJSONObject("results");}
        JSONArray resultArray(){return data.optJSONArray("results");}
        String state(){return result().optString("state","");}
        String code(){return data.optString("code","");}
        boolean success(){return http >= 200 && http < 300 && !"SEND_UNKNOWN".equals(code());}
        String summary(){
            return "HTTP " + http + ": " + data.optString("message", code());
        }
    }

    static Reply call(Context context, String method, String path, JSONObject body,
                      String alias, String instance, String idem) throws Exception {
        final String auth = EmbeddedGatewayService.credential(context);
        if (auth.isEmpty()) throw new IllegalStateException("Local backend not yet initialized");
        if (!path.startsWith("/") || path.contains("..") || path.contains("?"))
            throw new IllegalArgumentException("Invalid backend path");
        HttpURLConnection conn = open(context, method, path, auth, alias, instance, idem);
        try {
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 65536) throw new IllegalArgumentException("Request too large");
                try (OutputStream out = conn.getOutputStream()){out.write(bytes);}
            }
            return read(conn);
        } finally {conn.disconnect();}
    }

    private static HttpURLConnection open(Context context,String method,String path,String auth,
                                          String alias,String instance,String idem)throws Exception {
        URL url = new URL(EmbeddedGatewayService.address() + path);
        if (!"127.0.0.1".equals(url.getHost()))
            throw new SecurityException("Gateway must stay on loopback");
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(45000);
        conn.setRequestProperty("Accept","application/json");
        conn.setRequestProperty("Authorization", "Basic " +
                Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        if (alias!=null && !alias.isEmpty()) conn.setRequestProperty("X-Device-Id",alias);
        if (instance!=null && !instance.isEmpty()) conn.setRequestProperty("X-Device-Instance",instance);
        if (idem!=null && !idem.isEmpty()) conn.setRequestProperty("Idempotency-Key",idem);
        return conn;
    }

    static Reply sendImage(Context context,String alias,String instance,String idem,
                           JSONObject peer,String phone,String caption,File image,String mime)throws Exception {
        if (!image.isFile() || image.length()>6_000_000L)
            throw new IllegalArgumentException("Image missing or over 6 MB");
        final String auth = EmbeddedGatewayService.credential(context);
        if (auth.isEmpty()) throw new IllegalStateException("Local gateway unavailable");
        HttpURLConnection conn = open(context,"POST","/send/image",auth,alias,instance,idem);
        String boundary="THREEIB"+UUID.randomUUID().toString().replace("-","");
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(65536);
        conn.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        try {
            try(OutputStream out=conn.getOutputStream()){
                if(peer!=null) formValue(out,boundary,"peer",peer.toString());
                else formValue(out,boundary,"phone",phone);
                formValue(out,boundary,"caption",caption);
                String ext = "image/png".equals(mime) ? "png" : "jpg";
                write(out,"--"+boundary+"\r\n");
                write(out,"Content-Disposition: form-data; name=\"image\"; filename=\"threeib."+ext+"\"\r\n");
                write(out,"Content-Type: "+("image/png".equals(mime)?"image/png":"image/jpeg")+"\r\n\r\n");
                try(InputStream input=new FileInputStream(image)){
                    byte[] buf = new byte[32768];int count;
                    while((count=input.read(buf))!=-1)out.write(buf,0,count);
                }
                write(out,"\r\n--"+boundary+"--\r\n");
            }
            return read(conn);
        } finally {conn.disconnect();}
    }

    private static void formValue(OutputStream out,String boundary,String name,String value)throws Exception {
        write(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n");
        write(out,value);
        write(out,"\r\n");
    }
    private static void write(OutputStream out,String text)throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Reply read(HttpURLConnection conn)throws Exception {
        int status=conn.getResponseCode();
        if (status>=300 && status<400) throw new SecurityException("Unexpected redirect");
        InputStream input=status<400 ? conn.getInputStream():conn.getErrorStream();
        if(input == null)return new Reply(status,new JSONObject());
        try(InputStream stream=input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192]; int n;
            while((n=stream.read(buf))!=-1) {
                if(out.size()+n>128_000) throw new IllegalArgumentException("Gateway reply exceeds limit");
                out.write(buf,0,n);
            }
            return new Reply(status, new JSONObject(out.toString(StandardCharsets.UTF_8.name())));
        }
    }

    static String normalizedPhone(String source) {
        if(source==null)return "";
        String phone=source.trim().replace(" ","").replace("-","");
        if(phone.matches("^09[0-9]{9}$")) return "+98"+phone.substring(1);
        if(phone.matches("^989[0-9]{9}$")) return "+"+phone;
        if(phone.matches("^\\+[1-9][0-9]{7,14}$")) return phone;
        return "";
    }

    static String alias(String provider) {
        if(!provider.matches("bale|rubika|eitaa"))throw new IllegalArgumentException("Invalid provider");
        return "threeib-"+provider;
    }
}
