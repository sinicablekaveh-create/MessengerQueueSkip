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
 * The network runner is Activity-scoped: reopening requires pressing START again.
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
    private boolean running;
    private int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int reviewCount = QueueDb.whatsappRecoverInterrupted(this);
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
        note.setText("Official WhatsApp Business Cloud API only. Requires an authorized Meta Business account, HTTPS backend and server token. Only rows with opt_in=true can be queued. Free-form text may be used only within the permitted customer-service window; approved templates are not implemented. The queue is saved, but sending stops when this screen closes. Never retry REVIEW rows without checking server/Meta logs.");
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
        button(root, "REFRESH QUEUE STATUS", () -> refreshStatus("Queue status"));
        confirmConsent = new CheckBox(this);
        confirmConsent.setText("I confirm that each selected recipient consented to this WhatsApp message.");
        root.addView(confirmConsent);
        status = new TextView(this);
        root.addView(status);
        button(root, "START AUTOMATIC • WHATSAPP", this::startQueue);
        button(root, "PAUSE / STOP WHATSAPP", () -> {
            running = false;
            generation++;
            refreshStatus("Paused. Unsent rows remain pending; any active request can still complete.");
        });
        button(root, "CLEAR PENDING WHATSAPP (keep shared contacts / audit)", () -> {
            running = false;
            generation++;
            QueueDb.whatsappClear(this);
            QueueDb.due(this, "WhatsApp", 0L);
            refreshStatus("Unsent WhatsApp rows cleared. REVIEW and SUBMITTED retained.");
        });
        refreshStatus(reviewCount == 0 ? "Ready" : reviewCount + " interrupted WhatsApp attempt(s) require REVIEW.");
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
                + "\nSUBMITTED means accepted by the backend, not delivered.");
    }

    private void pickRecipients() {
        if (running) {
            toast("Pause sending before importing recipients.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_RECIPIENTS);
    }

    private void startQueue() {
        if (running) return;
        String url = endpoint.getText().toString().trim();
        String token = serviceToken.getText().toString().trim();
        String text = message.getText().toString().trim();
        long minutes;
        try { minutes = Long.parseLong(delayMinutes.getText().toString().trim()); }
        catch (Exception ignored) { minutes = 0; }
        if (!url.startsWith("https://") || !url.endsWith("/v1/whatsapp/messages")
                || token.isEmpty() || text.isEmpty() || minutes < 1 || minutes > 1440
                || !confirmConsent.isChecked()) {
            toast("Enter the HTTPS endpoint, token, text, 1–1440 minutes and confirm consent.");
            return;
        }
        if (QueueDb.whatsappCount(this, "in_flight") != 0) {
            refreshStatus("An earlier request is still in flight. Wait for its outcome.");
            return;
        }
        if (QueueDb.whatsappCount(this, "pending") == 0) {
            refreshStatus("No opted-in WhatsApp recipients are pending.");
            return;
        }
        QueueDb.put(this, "delay_whatsapp", String.valueOf(minutes));
        running = true;
        generation++;
        dispatch(url, token, text, minutes * 60_000L, generation);
    }

    private static String toE164(String phone) {
        if (phone.matches("^\\+[1-9][0-9]{7,14}$")) return phone;
        if (phone.matches("^09[0-9]{9}$")) return "+98" + phone.substring(1);
        return "";
    }

    private void dispatch(String url, String token, String text, long delayMillis, int run) {
        if (!running || run != generation) return;
        int rowIndex = QueueDb.next(this, "WhatsApp");
        if (rowIndex < 0) {
            running = false;
            refreshStatus("No pending WhatsApp rows remain.");
            return;
        }
        long wait = QueueDb.due(this, "WhatsApp") - System.currentTimeMillis();
        if (wait > 0) {
            refreshStatus("Waiting until the next permitted send interval.");
            handler.postDelayed(() -> dispatch(url, token, text, delayMillis, run), wait);
            return;
        }
        String recipient = toE164(QueueDb.phone(this, rowIndex));
        if (recipient.isEmpty()) {
            QueueDb.status(this, rowIndex, "WhatsApp", "failed");
            refreshStatus("Invalid international phone format; row marked FAILED.");
            handler.post(() -> dispatch(url, token, text, delayMillis, run));
            return;
        }
        // Do not send unless the durable IN_FLIGHT write has succeeded.
        if (!QueueDb.whatsappMarkInFlight(this, rowIndex)) {
            running = false;
            refreshStatus("Cannot persist IN_FLIGHT. No request was sent.");
            return;
        }
        refreshStatus("Submitting one opted-in recipient...");
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String outcome = "review";
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(20_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                JSONObject body = new JSONObject();
                body.put("recipient", recipient);
                body.put("text", text);
                body.put("optIn", true);
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = connection.getResponseCode();
                String serverState = "";
                InputStream stream = code < 400 ? connection.getInputStream() : connection.getErrorStream();
                if (stream != null) {
                    try (InputStream input = stream;
                         BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                        String line = reader.readLine();
                        if (line != null && line.length() <= 4096)
                            serverState = new JSONObject(line).optString("status", "");
                    }
                }
                if (code == 202 && "SUBMITTED".equals(serverState)) outcome = "submitted";
                else if ("FAILED".equals(serverState) || (code >= 400 && code < 500)) outcome = "failed";
                // Any timeout, 5xx, malformed response, or unknown outcome stays REVIEW.
            } catch (Exception ignored) {
                outcome = "review";
            } finally {
                if (connection != null) connection.disconnect();
            }

            QueueDb.status(app, rowIndex, "WhatsApp", outcome);
            final String finalOutcome = outcome;
            handler.post(() -> {
                if (run != generation || !running) {
                    refreshStatus("Last attempt: " + finalOutcome + ". Queue paused.");
                    return;
                }
                if ("submitted".equals(finalOutcome)) {
                    QueueDb.due(this, "WhatsApp", System.currentTimeMillis() + delayMillis);
                    refreshStatus("Accepted by backend; waiting before the next row.");
                    dispatch(url, token, text, delayMillis, run);
                } else {
                    running = false;
                    refreshStatus("Stopped at " + finalOutcome.toUpperCase()
                            + ". Check backend logs before any manual requeue.");
                }
            });
        }, "whatsapp-cloud-queue").start();
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
                handler.post(() -> toast("Import failed. Use Excel/CSV with phone,opt_in."));
            }
        }, "whatsapp-import").start();
    }

    @Override protected void onDestroy() {
        running = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
