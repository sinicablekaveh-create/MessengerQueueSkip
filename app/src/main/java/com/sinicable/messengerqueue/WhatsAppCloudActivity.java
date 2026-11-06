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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Independent WhatsApp Business Cloud API queue using a user-managed HTTPS backend. */
public final class WhatsAppCloudActivity extends Activity {
    private static final int PICK_RECIPIENTS = 801;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> recipients = new ArrayList<>();
    private EditText endpoint;
    private EditText serviceToken;
    private EditText message;
    private EditText delayMinutes;
    private TextView status;
    private boolean running;
    private int index;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        note.setText("Requires your own HTTPS backend and an authorized WhatsApp Business Cloud API account. Enter the backend URL and service token supplied by its administrator. Meta access tokens belong on the server, never in this app. Only opted-in rows are queued. Text messages only.");
        root.addView(note);

        endpoint = field(root, "Backend URL ending in /v1/whatsapp/messages", InputType.TYPE_TEXT_VARIATION_URI);
        serviceToken = field(root, "Backend service token", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        serviceToken.setSaveEnabled(false);
        message = field(root, "WhatsApp message", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setMinLines(3);
        delayMinutes = field(root, "Delay in minutes (1–1440)", InputType.TYPE_CLASS_NUMBER);
        delayMinutes.setText("1");

        button(root, "IMPORT EXCEL / CSV (phone,opt_in)", () -> pickRecipients());
        status = new TextView(this);
        root.addView(status);
        button(root, "START AUTOMATIC • WHATSAPP", () -> startQueue());
        button(root, "PAUSE WHATSAPP QUEUE", () -> {
            running = false;
            status.setText("Paused at " + index + " / " + recipients.size());
        });
        button(root, "CLEAR WHATSAPP QUEUE", () -> {
            running = false;
            recipients.clear();
            index = 0;
            status.setText("Queue cleared");
        });
    }

    private EditText field(LinearLayout root, String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setInputType(inputType);
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

    private void pickRecipients() {
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
        try {
            minutes = Long.parseLong(delayMinutes.getText().toString().trim());
        } catch (Exception error) {
            minutes = 0;
        }
        if (!url.startsWith("https://") || token.isEmpty() || text.isEmpty()
                || minutes < 1 || minutes > 1440 || recipients.isEmpty()) {
            toast("Enter an HTTPS backend, service token, message, valid delay, and opted-in recipients.");
            return;
        }
        if (index >= recipients.size()) index = 0;
        running = true;
        sendNext(url, token, text, minutes * 60_000L);
    }

    private void sendNext(String url, String token, String text, long delayMillis) {
        if (!running || index >= recipients.size()) {
            running = false;
            status.setText("Finished " + index + " / " + recipients.size()
                    + ". Submitted means accepted by the backend, not delivered.");
            return;
        }
        String recipient = recipients.get(index);
        status.setText("Submitting " + (index + 1) + " / " + recipients.size());
        new Thread(() -> {
            String result;
            int responseCode = 0;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
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
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
                responseCode = connection.getResponseCode();
                result = responseCode >= 200 && responseCode < 300 ? "SUBMITTED"
                        : "FAILED (" + responseCode + ")";
            } catch (Exception error) {
                result = "REVIEW: network result uncertain. Check backend logs before retrying.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            String finalResult = result;
            handler.post(() -> {
                status.setText((index + 1) + " / " + recipients.size() + " • " + finalResult);
                if (finalResult.startsWith("SUBMITTED")) {
                    index++;
                    if (running && index < recipients.size()) {
                        handler.postDelayed(() -> sendNext(url, token, text, delayMillis), delayMillis);
                    } else {
                        running = false;
                    }
                } else {
                    running = false;
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
                        if (column >= 0) name = cursor.getString(column);
                    }
                }
                RecipientImportParser.Result parsed;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    parsed = RecipientImportParser.read(input, name);
                }
                List<String> optedIn = new ArrayList<>();
                for (RecipientImportParser.Recipient row : parsed.recipients) {
                    if (row.optIn) optedIn.add(row.phone);
                }
                int blocked = parsed.blockedRows;
                handler.post(() -> {
                    running = false;
                    recipients.clear();
                    recipients.addAll(optedIn);
                    index = 0;
                    status.setText(recipients.size() + " opted-in rows loaded. "
                            + blocked + " rows without opt_in=true were excluded.");
                });
            } catch (Exception error) {
                handler.post(() -> toast("Import failed. Use Excel or CSV with phone,opt_in headers."));
            }
        }, "whatsapp-import").start();
    }

    @Override protected void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
