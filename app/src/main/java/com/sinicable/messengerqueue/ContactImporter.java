package com.sinicable.messengerqueue;

import android.content.ContentResolver;
import android.net.Uri;
import java.io.InputStream;

/** Android URI adapter around the pure-Java header-aware recipient parser. */
final class ContactImporter {
    static RecipientImportParser.Result read(ContentResolver resolver, Uri uri, String filename) throws Exception {
        try(InputStream is=resolver.openInputStream(uri)) {
            return RecipientImportParser.read(is,filename);
        }
    }

    static String phone(String raw){return RecipientImportParser.phone(raw);}

    static String international(String local) {
        if(local!=null&&local.startsWith("09")&&local.length()==11)return "98"+local.substring(1);
        return local==null?"":local.replace("+","");
    }
}