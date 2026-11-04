package com.sinicable.messengerqueue;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import java.util.ArrayList;

/**
 * Optional, explicitly enabled contact bridge. The app never modifies contacts
 * without WRITE_CONTACTS permission and the user's in-app opt-in.
 */
final class ContactBridge {
    private static final String PREFIX = "MQS_";
    private ContactBridge() {}

    static boolean permission(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    static String alias(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 10) return "";
        return PREFIX + digits.substring(digits.length() - 10);
    }

    static String ensure(Context c, String phone) {
        if (!permission(c)) return "";
        String name = alias(phone);
        if (name.isEmpty()) return "";
        String key = "contact_" + name;
        if ("ok".equals(QueueDb.get(c, key, ""))) return name;
        // Avoid importing twice when the app's preferences have been reset.
        try (android.database.Cursor cur = c.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + "=?",
                new String[]{name}, null)) {
            if (cur != null && cur.moveToFirst()) {
                QueueDb.put(c, key, "ok");
                return name;
            }
        } catch (Exception ex) {
            QueueDb.log(c, "Contact lookup error: " + ex.getClass().getSimpleName());
        }
        try {
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, name).build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.TYPE_MOBILE).build());
            c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            QueueDb.put(c, key, "ok");
            QueueDb.log(c, "Created optional phone contact " + name);
            return name;
        } catch (Exception ex) {
            QueueDb.log(c, "Cannot create phone contact: " + ex.getClass().getSimpleName());
            return "";
        }
    }
}
