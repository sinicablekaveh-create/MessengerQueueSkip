package com.sinicable.messengerqueue;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * User-controlled WhatsApp Business Cloud API queue.
 * Shared Excel rows and per-row states survive Activity recreation/process death.
 * Attempts with uncertain outcomes require manual review, never automatic retry.
 * A foreground Service owns the queue, so leaving this screen does not stop it.
 * The backend token is never persisted; Android process death pauses safely.
 */
public final class WhatsAppCloudActivity extends Activity {
    private static final int PICK_RECIPIENTS = 801;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText endpoint;
    private EditText serviceToken;
    private EditText message;
    private EditText delayMinutes;
    private TextView status;
    private CheckBox confirmConsent;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int reviewCount = WhatsAppQueueService.isActive()
                ? 0 : QueueDb.whatsappRecoverInterrupted(this);
        if (!WhatsAppQueueService.isActive())
            QueueDb.put(this,"whatsapp_running","0");
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 24);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("THREE IB • WHATSAPP BUSINESS");
        title.setTextSize(22);
        root.addView(title);
        TextView note = new TextView(this);
        note.setText("Official WhatsApp Business Cloud API only. Requires an authorized Meta Business account, HTTPS backend and server token. Two-column name/phone files are supported; confirm recipient consent at START. Free-form text may be used only within the permitted customer-service window; approved templates are not implemented. The foreground queue continues when this screen closes, while Android allows its service to run. Process death pauses safely and requires START again; the token is never saved. Never retry REVIEW rows without checking server/Meta logs.");
        root.addView(note);

        endpoint = field(root, "HTTPS backend ending /v1/whatsapp/messages",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serviceToken = field(root, "Backend service token (never saved)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        serviceToken.setSaveEnabled(false);
        message = field(root, "WhatsApp text", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setMinLines(3);
        delayMinutes = field(root, "Minutes between messages (1–1440)", InputType.TYPE_CLASS_NUMBER);
        delayMinutes.setText(String.valueOf(QueueDb.delayMinutes(this, "WhatsApp")));

        button(root, "IMPORT EXCEL / CSV (shared contacts)", this::pickRecipients);
        button(root, "مدیریت مخاطبان • ایجاد، ویرایش، حذف", () -> startActivity(new Intent(this,RecipientEditorActivity.class)));
        button(root, "REFRESH QUEUE STATUS", () -> refreshStatus("Queue status"));
        confirmConsent = new CheckBox(this);
        confirmConsent.setText("I confirm that each selected recipient consented to this WhatsApp message.");
        root.addView(confirmConsent);
        status = new TextView(this);
        root.addView(status);
        button(root, "START AUTOMATIC • WHATSAPP", this::startQueue);
        button(root, "PAUSE / STOP WHATSAPP", () -> {
            startService(new Intent(this, WhatsAppQueueService.class)
                    .setAction(WhatsAppQueueService.ACTION_PAUSE));
            refreshStatus("Pause requested. Unsent rows stay pending; active requests can still complete.");
        });
        button(root, "CLEAR PENDING WHATSAPP (keep shared contacts / audit)", () -> {
            startService(new Intent(this, WhatsAppQueueService.class)
                    .setAction(WhatsAppQueueService.ACTION_PAUSE));
            QueueDb.whatsappClear(this);
            QueueDb.due(this, "WhatsApp", 0L);
            refreshStatus("Unsent rows cleared. In-flight or uncertain attempts require REVIEW.");
        });
        refreshStatus(reviewCount == 0 ? "Ready" : reviewCount + " interrupted WhatsApp attempt(s) require REVIEW.");
        AppStyle.decorate(this,getWindow().getDecorView());
    }

    private EditText field(LinearLayout root, String hint, int type) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setInputType(type);
        root.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return edit;
    }

    private void button(LinearLayout root, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(view -> action.run());
        root.addView(button);
    }

    private void refreshStatus(String note) {
        if (status == null) return;
        status.setText(note + "\nPending: " + QueueDb.whatsappCount(this, "pending")
                + " • Submitted: " + QueueDb.whatsappCount(this, "submitted")
                + " • Review: " + QueueDb.whatsappCount(this, "review")
                + " • Failed: " + QueueDb.whatsappCount(this, "failed")
                + " • Cleared: " + QueueDb.whatsappCount(this, "cleared")
                + "\nQueue: " + QueueDb.get(this, "whatsapp_service_status", "Idle")
                + "\nSUBMITTED means accepted by the backend, not delivered.");
    }

    private void pickRecipients() {
        if (WhatsAppQueueService.isActive()) {
            toast("Pause sending before importing recipients.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_RECIPIENTS);
    }

    private void startQueue() {
        if (WhatsAppQueueService.isActive()) {
            refreshStatus("Queue is already running in the foreground.");
            return;
        }
        String url = endpoint.getText().toString().trim();
        String token = serviceToken.getText().toString().trim();
        String text = message.getText().toString().trim();
        long minutes;
        try { minutes = Long.parseLong(delayMinutes.getText().toString().trim()); }
        catch (Exception ignored) { minutes = 0; }
        if (!url.startsWith("https://") || !url.endsWith("/v1/whatsapp/messages")
                || token.isEmpty() || text.isEmpty() || minutes < 1 || minutes > 1440
                || !confirmConsent.isChecked()) {
            toast("Enter an authorized HTTPS endpoint, token, text, delay, and confirm recipient consent.");
            return;
        }
        if (QueueDb.whatsappCount(this, "in_flight") != 0) {
            refreshStatus("Previous attempt still in flight; review it before starting.");
            return;
        }
        if (QueueDb.whatsappCount(this, "pending") == 0) {
            refreshStatus("No consent-confirmed recipients pending.");
            return;
        }
        QueueDb.put(this, "delay_whatsapp", String.valueOf(minutes));
        Intent service = new Intent(this, WhatsAppQueueService.class)
                .setAction(WhatsAppQueueService.ACTION_START)
                .putExtra(WhatsAppQueueService.EXTRA_ENDPOINT, url)
                .putExtra(WhatsAppQueueService.EXTRA_TOKEN, token)
                .putExtra(WhatsAppQueueService.EXTRA_TEXT, text)
                .putExtra(WhatsAppQueueService.EXTRA_INTERVAL, minutes);
        try {
            startForegroundService(service);
            refreshStatus("Starting foreground queue. See notification for PAUSE.");
        } catch (RuntimeException failure) {
            refreshStatus("Android denied service start: " + failure.getClass().getSimpleName());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_RECIPIENTS || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                String name = "recipients.csv";
                try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (column >= 0 && cursor.getString(column) != null)
                            name = cursor.getString(column);
                    }
                }
                RecipientImportParser.Result parsed;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    parsed = RecipientImportParser.read(input, name);
                }
                int added = QueueDb.append(getApplicationContext(), parsed);
                int blocked = parsed.blockedRows;
                handler.post(() -> refreshStatus("Shared contacts: " + added
                        + " new unique rows; " + blocked + " without opt-in blocked."));
            } catch (Exception error) {
                handler.post(() -> toast("Import failed. Use Excel/CSV with name,phone or phone,opt_in."));
            }
        }, "whatsapp-import").start();
    }

    @Override protected void onDestroy() {
        // Do not stop the user-started foreground queue when leaving the screen.
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
