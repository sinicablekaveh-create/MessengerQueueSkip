package com.threeib.queue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class DbHelper extends SQLiteOpenHelper {
    private static final String DB = "three_ib.db";
    private static final int VERSION = 1;

    public static final String PENDING = "PENDING";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";

    public DbHelper(Context c) { super(c, DB, null, VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
        db.execSQL("PRAGMA synchronous=FULL");
        db.execSQL("PRAGMA foreign_keys=ON");
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE queue (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "phone TEXT NOT NULL," +
                "contact_name TEXT NOT NULL," +
                "messenger TEXT NOT NULL," +
                "message TEXT," +
                "status TEXT NOT NULL DEFAULT 'PENDING'," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "last_error TEXT DEFAULT ''" +
                ")");
        db.execSQL("CREATE INDEX idx_queue_status ON queue(status, created_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public synchronized long enqueue(String phone, String contactName, String messenger, String message) {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT id FROM queue WHERE phone=? AND messenger=? AND IFNULL(message,'')=IFNULL(?,'') LIMIT 1",
                new String[]{phone, messenger, message == null ? "" : message})) {
            if (c.moveToFirst()) return -1L;
        }
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("phone", phone);
        v.put("contact_name", contactName);
        v.put("messenger", messenger);
        v.put("message", message == null ? "" : message);
        v.put("status", PENDING);
        v.put("created_at", now);
        v.put("updated_at", now);
        return db.insert("queue", null, v);
    }

    public synchronized List<QueueItem> pending(int limit) {
        List<QueueItem> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,phone,contact_name,messenger,message,status,attempts,created_at,updated_at,last_error FROM queue WHERE status=? ORDER BY created_at,id LIMIT " + Math.max(1, Math.min(limit, 500)),
                new String[]{PENDING})) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    public synchronized QueueItem get(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,phone,contact_name,messenger,message,status,attempts,created_at,updated_at,last_error FROM queue WHERE id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    private QueueItem fromCursor(Cursor c) {
        QueueItem q = new QueueItem();
        q.id = c.getLong(0); q.phone = c.getString(1); q.contactName = c.getString(2);
        q.messenger = c.getString(3); q.message = c.getString(4); q.status = c.getString(5);
        q.attempts = c.getInt(6); q.createdAt = c.getLong(7); q.updatedAt = c.getLong(8); q.lastError = c.getString(9);
        return q;
    }

    public synchronized boolean markSending(long id) {
        ContentValues v = new ContentValues();
        v.put("status", SENDING);
        v.put("updated_at", System.currentTimeMillis());
        v.put("attempts", attempts(id) + 1);
        return getWritableDatabase().update("queue", v, "id=? AND status=?", new String[]{String.valueOf(id), PENDING}) == 1;
    }

    private int attempts(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT attempts FROM queue WHERE id=?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized void markSent(long id) { setStatus(id, SENT, ""); }
    public synchronized void markUnknown(long id, String err) { setStatus(id, UNKNOWN, err); }
    public synchronized void markFailed(long id, String err) { setStatus(id, FAILED, err); }
    public synchronized void retry(long id, String err) { setStatus(id, PENDING, err); }

    private void setStatus(long id, String status, String err) {
        ContentValues v = new ContentValues();
        v.put("status", status); v.put("updated_at", System.currentTimeMillis()); v.put("last_error", err == null ? "" : err);
        getWritableDatabase().update("queue", v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized int recoverStaleSending() {
        long cutoff = System.currentTimeMillis() - 5 * 60_000L;
        ContentValues v = new ContentValues();
        v.put("status", UNKNOWN);
        v.put("last_error", "Process restarted after send attempt; not retried automatically to prevent duplicates");
        v.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().update("queue", v, "status=? AND updated_at<?", new String[]{SENDING, String.valueOf(cutoff)});
    }

    public synchronized int count(String status) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM queue WHERE status=?", new String[]{status})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized int total() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM queue", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized void clearAll() { getWritableDatabase().delete("queue", null, null); }

    public static class QueueItem {
        public long id, createdAt, updatedAt;
        public String phone, contactName, messenger, message, status, lastError;
        public int attempts;
    }
}
