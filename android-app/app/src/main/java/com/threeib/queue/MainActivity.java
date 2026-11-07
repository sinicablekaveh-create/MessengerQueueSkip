package com.threeib.queue;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_XLSX = 1001;
    private static final int REQ_IMAGE = 1002;
    private static final int REQ_PERMS = 1003;

    private TextView statusText, queueText, imageText, detailText;
    private CheckBox baleCheck, eitaaCheck, rubikaCheck;
    private EditText messageEdit, baleInterval, eitaaInterval, rubikaInterval;
    private DbHelper db;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable refresher = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1000L); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new DbHelper(this);
        bind();
        loadSettings();
        requestCorePermissions();
        wire();
        ui.post(refresher);
    }

    private void bind() {
        statusText = findViewById(R.id.statusText);
        queueText = findViewById(R.id.queueText);
        imageText = findViewById(R.id.imageText);
        detailText = findViewById(R.id.detailText);
        baleCheck = findViewById(R.id.baleCheck);
        eitaaCheck = findViewById(R.id.eitaaCheck);
        rubikaCheck = findViewById(R.id.rubikaCheck);
        messageEdit = findViewById(R.id.messageEdit);
        baleInterval = findViewById(R.id.baleInterval);
        eitaaInterval = findViewById(R.id.eitaaInterval);
        rubikaInterval = findViewById(R.id.rubikaInterval);
    }

    private void loadSettings() {
        baleCheck.setChecked(AppPrefs.messengerEnabled(this, "bale"));
        eitaaCheck.setChecked(AppPrefs.messengerEnabled(this, "eitaa"));
        rubikaCheck.setChecked(AppPrefs.messengerEnabled(this, "rubika"));
        messageEdit.setText(AppPrefs.defaultMessage(this));
        baleInterval.setText(String.valueOf(AppPrefs.interval(this, "bale")));
        eitaaInterval.setText(String.valueOf(AppPrefs.interval(this, "eitaa")));
        rubikaInterval.setText(String.valueOf(AppPrefs.interval(this, "rubika")));
        String img = AppPrefs.imageUri(this);
        imageText.setText(img.isEmpty() ? "Image: none" : "Image: selected");
    }

    private void wire() {
        ((Button)findViewById(R.id.importButton)).setOnClickListener(v -> pickExcel());
        ((Button)findViewById(R.id.imageButton)).setOnClickListener(v -> pickImage());
        ((Button)findViewById(R.id.saveButton)).setOnClickListener(v -> { saveSettings(); toast("Settings saved"); });
        ((Button)findViewById(R.id.startButton)).setOnClickListener(v -> startAutomatic());
        ((Button)findViewById(R.id.stopButton)).setOnClickListener(v -> stopAutomatic());
        ((Button)findViewById(R.id.recordButton)).setOnClickListener(v -> startRecording());
        ((Button)findViewById(R.id.accessibilityButton)).setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        ((Button)findViewById(R.id.reliabilityButton)).setOnClickListener(v -> openReliabilitySettings());
        ((Button)findViewById(R.id.clearButton)).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear queue")
                .setMessage("Delete all pending/sent/failed queue records?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d,w) -> {
                    stopAutomatic();
                    io.execute(() -> { db.clearAll(); ui.post(() -> toast("Queue cleared")); });
                }).show());
    }

    private void pickExcel() {
        saveSettings();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        startActivityForResult(i, REQ_XLSX);
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMAGE) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            AppPrefs.setImageUri(this, uri.toString());
            imageText.setText("Image: selected");
            toast("Image saved");
        } else if (requestCode == REQ_XLSX) {
            boolean b = baleCheck.isChecked(), e = eitaaCheck.isChecked(), r = rubikaCheck.isChecked();
            io.execute(() -> {
                try {
                    XlsxImporter.ImportResult res = XlsxImporter.importInto(this, uri, db, b, e, r);
                    ui.post(() -> new AlertDialog.Builder(this)
                            .setTitle("Excel imported")
                            .setMessage("Added queue items: " + res.added + "\nSkipped rows/items: " + res.skipped + "\n\nOnly rows with opt_in=yes/true/1 are queued.")
                            .setPositiveButton("OK", null).show());
                } catch (Exception ex) {
                    ui.post(() -> new AlertDialog.Builder(this).setTitle("Import failed")
                            .setMessage(ex.getClass().getSimpleName() + ": " + ex.getMessage()).setPositiveButton("OK", null).show());
                }
            });
        }
    }

    private void saveSettings() {
        AppPrefs.setMessengerEnabled(this, "bale", baleCheck.isChecked());
        AppPrefs.setMessengerEnabled(this, "eitaa", eitaaCheck.isChecked());
        AppPrefs.setMessengerEnabled(this, "rubika", rubikaCheck.isChecked());
        AppPrefs.setDefaultMessage(this, messageEdit.getText().toString());
        AppPrefs.setInterval(this, "bale", interval(baleInterval));
        AppPrefs.setInterval(this, "eitaa", interval(eitaaInterval));
        AppPrefs.setInterval(this, "rubika", interval(rubikaInterval));
    }

    private int interval(EditText e) {
        try { return Math.max(1, Math.min(1440, Integer.parseInt(e.getText().toString().trim()))); }
        catch (Exception ignored) { return 5; }
    }

    private void startAutomatic() {
        saveSettings();
        requestCorePermissions();
        AppPrefs.setDesiredRunning(this, true);
        try {
            Intent i = new Intent(this, QueueEngineService.class).setAction(QueueEngineService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i); else startService(i);
            WatchdogReceiver.scheduleNext(this, 15 * 60_000L);
            toast("Automatic queue started");
        } catch (Exception e) {
            toast("Start error: " + e.getMessage());
        }
        if (!AccessibilityUtils.enabled(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Enable THREE IB Automation")
                    .setMessage("The queue is armed, but Android Accessibility must be enabled once so the app can control Bale, Eitaa and Rubika.")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Open settings", (d,w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();
        }
    }

    private void stopAutomatic() {
        AppPrefs.setDesiredRunning(this, false);
        WatchdogReceiver.cancel(this);
        try { startService(new Intent(this, QueueEngineService.class).setAction(QueueEngineService.ACTION_STOP)); } catch (Exception ignored) {}
        toast("Automatic queue stopped");
    }

    private void startRecording() {
        stopAutomatic();
        String messenger = baleCheck.isChecked() ? "bale" : (eitaaCheck.isChecked() ? "eitaa" : (rubikaCheck.isChecked() ? "rubika" : "bale"));
        if (!MessengerUtil.installed(this, messenger)) { toast(MessengerUtil.label(messenger) + " is not installed"); return; }
        AppPrefs.setRecording(this, true, messenger);
        new AlertDialog.Builder(this)
                .setTitle("Record " + MessengerUtil.label(messenger))
                .setMessage("Perform one normal manual send: tap Search, type/select a contact, type a short message, then tap Send. THREE IB records view selectors, not your screen video or password. Recording ends after the Send tap.")
                .setNegativeButton("Cancel", (d,w) -> AppPrefs.setRecording(this, false, messenger))
                .setPositiveButton("Start", (d,w) -> MessengerUtil.launch(this, messenger, "", "")).show();
    }

    private void openReliabilitySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager)getSystemService(ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()));
                    startActivity(i); return;
                } catch (Exception ignored) {}
            }
        }
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception ignored) {}
        }
        try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void requestCorePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.READ_CONTACTS);
            if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.WRITE_CONTACTS);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.POST_NOTIFICATIONS);
            if (!list.isEmpty()) requestPermissions(list.toArray(new String[0]), REQ_PERMS);
        }
    }

    private void refresh() {
        if (isFinishing()) return;
        statusText.setText("Engine: " + AppPrefs.engineStatus(this));
        io.execute(() -> {
            int pending = db.count(DbHelper.PENDING), sending = db.count(DbHelper.SENDING), sent = db.count(DbHelper.SENT), failed = db.count(DbHelper.FAILED), unknown = db.count(DbHelper.UNKNOWN);
            ui.post(() -> queueText.setText(String.format(Locale.ROOT, "Queue: %d pending • %d sending • %d sent • %d failed • %d unknown", pending, sending, sent, failed, unknown)));
        });
        StringBuilder d = new StringBuilder();
        d.append("Accessibility: ").append(AccessibilityUtils.enabled(this) ? "ON" : "OFF").append('\n');
        d.append("Bale installed: ").append(MessengerUtil.installed(this,"bale") ? "YES" : "NO").append('\n');
        d.append("Eitaa installed: ").append(MessengerUtil.installed(this,"eitaa") ? "YES" : "NO").append('\n');
        d.append("Rubika installed: ").append(MessengerUtil.installed(this,"rubika") ? "YES" : "NO").append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager)getSystemService(ALARM_SERVICE);
            d.append("Exact alarm: ").append(am != null && am.canScheduleExactAlarms() ? "ALLOWED" : "NOT ALLOWED").append('\n');
        }
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        d.append("Battery optimization: ").append(pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()) ? "UNRESTRICTED" : "OPTIMIZED/UNKNOWN").append('\n');
        if (AppPrefs.recording(this)) d.append("Recording UI: ").append(MessengerUtil.label(AppPrefs.recordingMessenger(this))).append(" stage ").append(AppPrefs.recordStage(this)).append('\n');
        detailText.setText(d.toString());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        ui.removeCallbacks(refresher);
        io.shutdownNow();
        if (db != null) db.close();
        super.onDestroy();
    }
}
