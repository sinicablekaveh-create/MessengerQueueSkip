package com.sinicable.messengerqueue;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;

public class TelegramActivity extends Activity {
    private TelegramEngine engine;
    private TextView state, configState;
    private EditText apiId, apiHash, login, message, interval;
    private CheckBox consent;
    private String image = "";
    private Button submit;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        engine = TelegramEngine.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("THREE IB • TELEGRAM TDLib");
        title.setTextSize(23);
        root.addView(title);

        configState = new TextView(this);
        root.addView(configState);
        apiId = edit(root, "Telegram API ID (my.telegram.org)");
        apiId.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        apiHash = edit(root, "Telegram API HASH (32 hexadecimal characters)");
        apiHash.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiHash.setSaveEnabled(false);
        button(root, "SAVE API CREDENTIALS ENCRYPTED ON THIS DEVICE", () -> {
            if (engine.client != null) {
                toast("TDLib is already initialized. Log out and reopen the app before changing credentials.");
                return;
            }
            if (!TelegramConfigStore.save(this, apiId.getText().toString(), apiHash.getText().toString())) {
                toast("Invalid API ID or API HASH. Check the values from my.telegram.org.");
                return;
            }
            apiHash.setText("");
            engine.connect();
            refresh();
            toast("Telegram credentials encrypted and saved on this device.");
        });

        state = new TextView(this);
        state.setTextIsSelectable(true);
        root.addView(state);
        login = edit(root, "Phone number, Telegram login code, or 2-step password");
        login.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        login.setSaveEnabled(false);
        submit = button(root, "CONTINUE TELEGRAM LOGIN", () -> {
            String value = login.getText().toString().trim();
            login.setText("");
            engine.login(value);
        });

        button(root, "IMPORT EXCEL / CSV (phone,opt_in)", () -> {
            if (engine.running || engine.busy) {
                toast("Pause the Telegram queue before changing its recipients.");
                return;
            }
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("*/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, 71);
        });

        message = edit(root, "Telegram message");
        message.setMinLines(3);
        message.setText(engine.text);
        image = engine.photo;
        button(root, "SELECT PHOTO", () -> {
            if (engine.running || engine.busy) return;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("image/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, 72);
        });
        button(root, "REMOVE PHOTO", () -> {
            if (!engine.running && !engine.busy) {
                image = "";
                toast("Photo removed.");
            }
        });

        interval = edit(root, "Delay in minutes (1–1440)");
        interval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        interval.setText(String.valueOf(engine.delay / 60000L));

        consent = new CheckBox(this);
        consent.setText("I confirm the imported opt_in=true recipients agreed to receive this Telegram message.");
        root.addView(consent);

        Button start = button(root, "START AUTOMATIC • TELEGRAM", () -> {
            if (!consent.isChecked()) {
                toast("Confirm recipient consent before starting.");
                return;
            }
            try {
                long minutes = Long.parseLong(interval.getText().toString().trim());
                if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException();
                engine.start(message.getText().toString(), image, minutes * 60000L);
            } catch (Exception e) {
                toast("Delay must be between 1 and 1440 minutes.");
            }
        });
        start.setBackgroundColor(0xff14865e);
        start.setTextColor(0xffffffff);

        button(root, "PAUSE TELEGRAM QUEUE", () -> engine.pause());
        button(root, "SKIP / RESOLVE REVIEW", () -> {
            if (engine.running || engine.busy) {
                toast("Pause and wait. If the result is uncertain, check Telegram before resolving.");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Review the last Telegram recipient")
                    .setMessage("Skip advances to the next recipient. Retry can duplicate a message if Telegram already accepted it.")
                    .setPositiveButton("Skip / already sent", (d, w) -> engine.resolve(true))
                    .setNeutralButton("Confirmed not sent", (d, w) -> engine.resolve(false))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        button(root, "LOG OUT OF TELEGRAM", () -> {
            engine.pause();
            engine.request(new org.drinkless.tdlib.TdApi.LogOut(), engine::error);
        });

        TextView note = new TextView(this);
        note.setText("Telegram uses the official TDLib client library. API credentials are encrypted with Android Keystore. Login may require your phone number, OTP, email confirmation, or 2-step password. Import requires phone and opt_in headers; only rows with opt_in=true enter this queue. Telegram may not resolve every phone number. Server accepted is not a read receipt. The Telegram queue has separate START / PAUSE controls from the Bale, Rubika, Eitaa and SMS queues.");
        root.addView(note);
        engine.observer = this::refresh;
        refresh();
    }

    private EditText edit(LinearLayout root, String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        root.addView(field);
        return field;
    }

    private Button button(LinearLayout root, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        root.addView(b);
        return b;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void refresh() {
        if (state == null || engine == null) return;
        state.setText("Account state: " + engine.auth +
                "\nQueue: " + engine.index + " / " + engine.phones.size() +
                "\n" + engine.info);
        configState.setText(TelegramConfigStore.hasCredentials(this)
                ? "API credentials: saved encrypted on this device"
                : "API credentials: not configured");
        submit.setEnabled(engine.client != null && !engine.ready);
        login.setVisibility(engine.ready ? View.GONE : View.VISIBLE);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.observer = null;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                if (requestCode == 71) {
                    String name = "contacts.csv";
                    try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (col >= 0) name = cursor.getString(col);
                        }
                    }
                    RecipientImportParser.Result parsed;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        parsed = RecipientImportParser.read(in, name);
                    }
                    List<String> optedIn = new ArrayList<>();
                    for (RecipientImportParser.Recipient row : parsed.recipients) {
                        if (row.optIn) optedIn.add(row.phone);
                    }
                    final int blocked = parsed.blockedRows;
                    runOnUiThread(() -> {
                        engine.replace(optedIn);
                        toast(optedIn.size() + " opted-in contacts imported; " + blocked + " rows blocked without opt_in=true.");
                    });
                } else if (requestCode == 72) {
                    File file = new File(getFilesDir(), "telegram-photo-" + System.currentTimeMillis());
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(file)) {
                        if (in == null) throw new IOException("Photo is not readable");
                        byte[] buffer = new byte[8192];
                        int n;
                        long total = 0;
                        while ((n = in.read(buffer)) != -1) {
                            total += n;
                            if (total > 10 * 1024 * 1024) throw new IOException("Photo must be under 10 MB");
                            out.write(buffer, 0, n);
                        }
                    }
                    runOnUiThread(() -> {
                        image = file.getAbsolutePath();
                        toast("Telegram photo selected.");
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> toast("Import failed: " + e.getClass().getSimpleName()));
            }
        }).start();
    }
}
