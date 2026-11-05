package com.sinicable.messengerqueue.pro;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

/** Builds a documented WhatsApp click-to-chat URL. It does not press Send or claim delivery. */
public final class WhatsAppChatLink {
    private WhatsAppChatLink() {}
    public static String build(String e164Phone, String text) {
        if (e164Phone == null || !e164Phone.matches("\\+[1-9][0-9]{7,14}"))
            throw new IllegalArgumentException("full international E.164 number required");
        String phone = e164Phone.substring(1);
        String base = "https://wa.me/" + phone;
        if (text == null || text.isEmpty()) return base;
        try { return base + "?text=" + URLEncoder.encode(text, "UTF-8"); }
        catch (UnsupportedEncodingException impossibleOnAndroid) { throw new AssertionError(impossibleOnAndroid); }
    }
}
