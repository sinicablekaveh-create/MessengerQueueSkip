package com.sinicable.messengerqueue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Runs the pinned GoOmni local account gateway as an isolated child process.
 * No external messaging app, Accessibility service, root or screen control is used.
 * All account secrets live in Android's private application storage.
 */
public final class EmbeddedGatewayService extends Service {
    private static final int NOTIFY = 3001;
    private static final String CHANNEL = "threeib_account_gateway";
    private volatile Process child;
    private volatile boolean stopping;

    static File work(Context context) {
        File dir = new File(context.getFilesDir(), "private-gateway");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
            throw new IllegalStateException("Unable to create private backend storage");
        return dir;
    }

    static String credential(Context context) {
        File env = new File(work(context), ".env");
        if (!env.isFile()) return "";
        try (InputStream input = new FileInputStream(env)) {
            byte[] bytes = new byte[(int) Math.min(env.length(), 16_384)];
            int n = input.read(bytes);
            if (n <= 0) return "";
            String source = new String(bytes, 0, n, StandardCharsets.UTF_8);
            for (String line : source.split("\\r?\\n")) {
                if (!line.startsWith("APP_BASIC_AUTH=")) continue;
                String value = line.substring("APP_BASIC_AUTH=".length()).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))))
                    value = value.substring(1, value.length() - 1);
                return value;
            }
        } catch (Exception ignored) { }
        return "";
    }

    // Each independently installed flavor owns a distinct loopback port.
    // Android app sandboxes isolate files, but TCP ports share the device-wide network namespace.
    static int port() {
        switch (BuildConfig.TARGET_CHANNEL) {
            case "bale": return 38741;
            case "rubika": return 40742;
            case "eitaa": return 38743;
            default: throw new IllegalStateException("This flavor has no embedded personal gateway");
        }
    }

    static String address() { return "http://127.0.0.1:" + port(); }

    static void ensureStarted(Context context) {
        Intent intent = new Intent(context, EmbeddedGatewayService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        getSharedPreferences("gateway", MODE_PRIVATE).edit().remove("error").apply();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "THREE IB personal accounts",
                NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this,
                "rubika".equals(BuildConfig.TARGET_CHANNEL) ? RubikaSimpleActivity.class : DirectAccountActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("THREE IB | حساب شخصی")
                .setContentText("درگاه ورود بله، روبیکا و ایتا در حال اجراست")
                .setOngoing(true).setContentIntent(pending).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFY, notification);
        Thread thread = new Thread(this::runGateway, "threeib-private-gateway");
        thread.setDaemon(true);
        thread.start();
    }

    private void runGateway() {
        final File dir = work(this);
        final File binary = new File(getApplicationInfo().nativeLibraryDir, "libgoomni.so");
        if (!binary.exists()) {
            getSharedPreferences("gateway", MODE_PRIVATE).edit()
                    .putString("error", "Gateway native library is missing").apply();
            return;
        }
        try {
            // The init command never overwrites a saved master key or credentials.
            if (!new File(dir, ".env").exists()) {
                Process init = process(binary, dir, "init", "--dir", dir.getAbsolutePath(),
                        "--bale-web-client").start();
                drain(init.getInputStream());
                drain(init.getErrorStream());
                if (init.waitFor() != 0 || !new File(dir, ".env").exists())
                    throw new IllegalStateException("Local gateway initialization failed");
            }
            if (credential(this).isEmpty()) throw new IllegalStateException("Missing gateway credentials");
            while (!stopping) {
                ProcessBuilder builder = process(binary, dir, "rest");
                child = builder.start();
                int code = child.waitFor();
                child = null;
                if (stopping) return;
                getSharedPreferences("gateway", MODE_PRIVATE).edit()
                        .putString("error", "Gateway exited (" + code + "); retrying").apply();
                Thread.sleep(6000);
            }
        } catch (Exception e) {
            getSharedPreferences("gateway", MODE_PRIVATE).edit()
                    .putString("error", e.getClass().getSimpleName() + ": " + e.getMessage()).apply();
        }
    }

    private ProcessBuilder process(File executable, File dir, String... args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(executable.getAbsolutePath());
        java.util.Collections.addAll(command, args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir);
        java.util.Map<String,String> env = builder.environment();
        env.put("HOME", dir.getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        env.put("APP_UI_ENABLED", "false");
        env.put("APP_HOST", "127.0.0.1");
        env.put("APP_PORT", String.valueOf(port()));
        env.put("EITAA_ENABLED", "true");
        env.put("RUBIKA_ENABLED", "true");
        env.put("APP_DATABASE", new File(dir,"goomni.db").getAbsolutePath());
        env.put("APP_MEDIA_ROOT", new File(dir,"media").getAbsolutePath());
        // Discard child logs so account tokens, OTP and messages never appear in logcat.
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File("/dev/null"));
        return builder;
    }

    private static void drain(InputStream input) throws java.io.IOException {
        byte[] buf = new byte[2048];
        while (input.read(buf) != -1) { /* disregard potentially sensitive output */ }
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        return START_STICKY;
    }
    @Override public void onDestroy() {
        stopping = true;
        Process value = child;
        if (value != null) value.destroy();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
