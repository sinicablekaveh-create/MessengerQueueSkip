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
        // Reuse an existing tagged contact, but also migrate it to include
        // both local and E.164-style phone forms when older app versions
        // created only one number.
        try {
            long rawId=-1L;
            java.util.HashSet<String> existingNumbers=new java.util.HashSet<>();
            try (android.database.Cursor cur = c.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + "=?",
                    new String[]{name}, null)) {
                while (cur != null && cur.moveToNext()) {
                    if(rawId<0) rawId=cur.getLong(0);
                    String n=ContactImporter.phone(cur.getString(1));
                    if(!n.isEmpty())existingNumbers.add(n);
                    String intlExisting=ContactImporter.international(n);
                    if(intlExisting!=null&&!intlExisting.isEmpty())existingNumbers.add(intlExisting.replace("+",""));
                }
            }
            if(rawId>=0) {
                String local=ContactImporter.phone(phone);
                String intl=ContactImporter.international(local).replace("+","");
                ArrayList<ContentProviderOperation> upgrade=new ArrayList<>();
                if(!local.isEmpty()&&!existingNumbers.contains(local)) {
                    upgrade.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID,rawId)
                            .withValue(ContactsContract.Data.MIMETYPE,CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(CommonDataKinds.Phone.NUMBER,local)
                            .withValue(CommonDataKinds.Phone.TYPE,CommonDataKinds.Phone.TYPE_MOBILE).build());
                }
                if(!intl.isEmpty()&&!existingNumbers.contains(intl)) {
                    upgrade.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID,rawId)
                            .withValue(ContactsContract.Data.MIMETYPE,CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(CommonDataKinds.Phone.NUMBER,"+"+intl)
                            .withValue(CommonDataKinds.Phone.TYPE,CommonDataKinds.Phone.TYPE_MOBILE).build());
                }
                if(!upgrade.isEmpty()) {
                    c.getContentResolver().applyBatch(ContactsContract.AUTHORITY,upgrade);
                    QueueDb.log(c,"Upgraded existing "+name+" contact with missing phone format");
                }
                QueueDb.put(c,key,"ok");
                return name;
            }
        } catch (Exception ex) {
            QueueDb.log(c, "Contact lookup/migration error: " + ex.getClass().getSimpleName());
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
            String local=ContactImporter.phone(phone);
            String intl=ContactImporter.international(local);
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Phone.NUMBER, local.isEmpty()?phone:local)
                    .withValue(CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.TYPE_MOBILE).build());
            if(intl!=null&&!intl.isEmpty()&&!intl.equals(local)) {
                String e164=intl.startsWith("+")?intl:("+"+intl);
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(CommonDataKinds.Phone.NUMBER, e164)
                        .withValue(CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.TYPE_MOBILE).build());
            }
            c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            try {c.getContentResolver().notifyChange(ContactsContract.Contacts.CONTENT_URI,null);} catch(Exception ignored) {}
            QueueDb.put(c, key, "ok");
            QueueDb.log(c, "Created optional phone contact " + name + " with local/international number forms");
            return name;
        } catch (Exception ex) {
            QueueDb.log(c, "Cannot create phone contact: " + ex.getClass().getSimpleName());
            return "";
        }
    }
}

