package com.sinicable.messengerqueue;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

final class RubikaBotQueueDb extends SQLiteOpenHelper {
    private static final String NAME="rubika_official_queue.db";
    private static final int VERSION=1;
    private static RubikaBotQueueDb db(Context c){return new RubikaBotQueueDb(c.getApplicationContext());}
    private RubikaBotQueueDb(Context c){super(c,NAME,null,VERSION);}
    @Override public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE recipients(id INTEGER PRIMARY KEY AUTOINCREMENT,chat_id TEXT NOT NULL UNIQUE,name TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',attempts INTEGER NOT NULL DEFAULT 0,last_error TEXT NOT NULL DEFAULT '')");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int a,int b){}

    static synchronized int replace(Context c,List<RubikaBotImportParser.Row> rows){
        try(RubikaBotQueueDb h=db(c)){SQLiteDatabase d=h.getWritableDatabase();d.beginTransaction();int n=0;try{
            d.delete("recipients",null,null);
            for(RubikaBotImportParser.Row r:rows){ContentValues v=new ContentValues();v.put("chat_id",r.chatId);v.put("name",r.name);v.put("status","pending");if(d.insertWithOnConflict("recipients",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1)n++;}
            d.setTransactionSuccessful();return n;
        }finally{d.endTransaction();}}
    }
    static synchronized boolean addOptedIn(Context c,String chatId,String name){
        if(chatId==null||chatId.trim().isEmpty())return false;
        try(RubikaBotQueueDb h=db(c)){ContentValues v=new ContentValues();v.put("chat_id",chatId.trim());v.put("name",name==null||name.trim().isEmpty()?"Rubika user":name.trim());v.put("status","pending");
            return h.getWritableDatabase().insertWithOnConflict("recipients",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;}
    }
    static final class Item{final long id;final String chatId,name;final int attempts;Item(long i,String c,String n,int a){id=i;chatId=c;name=n;attempts=a;}}
    static synchronized Item next(Context c){
        try(RubikaBotQueueDb h=db(c);Cursor x=h.getReadableDatabase().rawQuery("SELECT id,chat_id,name,attempts FROM recipients WHERE status='pending' ORDER BY id LIMIT 1",null)){
            if(!x.moveToFirst())return null;return new Item(x.getLong(0),x.getString(1),x.getString(2),x.getInt(3));}
    }
    static synchronized void sent(Context c,long id){update(c,id,"sent","",0);}
    static synchronized void fail(Context c,long id,String error,int attempts,boolean terminal){
        try(RubikaBotQueueDb h=db(c)){ContentValues v=new ContentValues();v.put("attempts",attempts);v.put("last_error",error==null?"":error);v.put("status",terminal?"failed":"pending");h.getWritableDatabase().update("recipients",v,"id=?",new String[]{String.valueOf(id)});}
    }
    private static void update(Context c,long id,String status,String error,int attempts){
        try(RubikaBotQueueDb h=db(c)){ContentValues v=new ContentValues();v.put("status",status);v.put("last_error",error);v.put("attempts",attempts);h.getWritableDatabase().update("recipients",v,"id=?",new String[]{String.valueOf(id)});}
    }
    static synchronized int count(Context c,String status){
        try(RubikaBotQueueDb h=db(c);Cursor x=h.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM recipients WHERE status=?",new String[]{status})){x.moveToFirst();return x.getInt(0);}
    }
    static synchronized int total(Context c){
        try(RubikaBotQueueDb h=db(c);Cursor x=h.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM recipients",null)){x.moveToFirst();return x.getInt(0);}
    }
    static synchronized void retryFailed(Context c){
        try(RubikaBotQueueDb h=db(c)){h.getWritableDatabase().execSQL("UPDATE recipients SET status='pending',attempts=0,last_error='' WHERE status='failed'");}
    }
    static synchronized void clear(Context c){try(RubikaBotQueueDb h=db(c)){h.getWritableDatabase().delete("recipients",null,null);}}

    private static SharedPreferences p(Context c){return c.getApplicationContext().getSharedPreferences("rubika_official_state",Context.MODE_PRIVATE);}
    static void put(Context c,String k,String v){p(c).edit().putString(k,v).apply();}
    static String get(Context c,String k,String d){return p(c).getString(k,d);}
    static long getLong(Context c,String k,long d){try{return Long.parseLong(get(c,k,String.valueOf(d)));}catch(Exception e){return d;}}
}
