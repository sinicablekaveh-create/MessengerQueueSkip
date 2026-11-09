package com.sinicable.messengerqueue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;

/**
 * Resolves a Rubika phone number to the provider's required user GUID.
 *
 * Rubika does not accept a phone destination for ordinary sends. The number is
 * first submitted through the reviewed contacts.add/addAddressBook operation,
 * whose public result contains the matching user_guid. That GUID is then used
 * as peer {type:"user", id:"u0..."} for /send/message.
 */
final class RubikaPeerResolver {
    static final class ResolveException extends Exception {
        final boolean terminal;
        ResolveException(String message, boolean terminal) {
            super(message);
            this.terminal = terminal;
        }
    }

    private RubikaPeerResolver() {}

    static JSONObject resolve(Context context, String alias, String instance,
                              String rawPhone, String displayName) throws Exception {
        String phone = GatewayClient.normalizedPhone(rawPhone);
        if (phone.isEmpty()) throw new ResolveException("شماره گیرنده معتبر نیست", true);

        String firstName = safeName(displayName);
        JSONObject body = new JSONObject();
        body.put("phone", phone);
        body.put("first_name", firstName);
        body.put("last_name", "");

        String idem = "threeib-rubika-contact-" + sha256(phone + "\n" + firstName).substring(0, 32);
        GatewayClient.Reply reply;
        try {
            reply = GatewayClient.call(context, "POST", "/operations/contacts.add",
                    body, alias, instance, idem);
        } catch (Exception e) {
            throw new ResolveException("ارتباط با Rubika برای تبدیل شماره به GUID برقرار نشد", false);
        }

        if (!reply.success()) {
            boolean terminal = reply.http >= 400 && reply.http < 500
                    && reply.http != 409 && reply.http != 429;
            throw new ResolveException("تبدیل شماره به GUID ناموفق بود: " + reply.summary(), terminal);
        }

        String guid = findUserGuid(reply.data);
        String operationId = reply.result().optString("send_id", "");
        String state = reply.result().optString("state", "");

        if (guid.isEmpty() && !operationId.isEmpty()
                && ("queued".equals(state) || "sending".equals(state))) {
            for (int i = 0; i < 10; i++) {
                Thread.sleep(1200L);
                GatewayClient.Reply check;
                try {
                    check = GatewayClient.call(context, "GET",
                            "/send/operations/" + operationId,
                            null, alias, instance, null);
                } catch (Exception e) {
                    throw new ResolveException("نتیجه تبدیل شماره نامشخص ماند؛ دوباره START نزن تا وضعیت بررسی شود", false);
                }
                guid = findUserGuid(check.data);
                state = check.result().optString("state", state);
                if (!guid.isEmpty() || "succeeded".equals(state) || "failed".equals(state)
                        || "unknown".equals(state)) break;
            }
        }

        if (guid.isEmpty()) {
            if ("failed".equals(state)) {
                String error = reply.result().optString("error_message", "");
                throw new ResolveException(
                        error.isEmpty() ? "این شماره به کاربر روبیکا resolve نشد" : error, true);
            }
            throw new ResolveException("Rubika برای این شماره user_guid معتبر برنگرداند", false);
        }

        if (!guid.matches("^u0[A-Za-z0-9]{1,126}$")) {
            throw new ResolveException("GUID برگشتی روبیکا از نوع user نیست", false);
        }

        JSONObject peer = new JSONObject();
        peer.put("type", "user");
        peer.put("id", guid);
        return peer;
    }

    private static String safeName(String value) {
        String name = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (name.isEmpty()) name = "THREE IB";
        if (name.length() > 120) name = name.substring(0, 120);
        return name;
    }

    private static String findUserGuid(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String direct = object.optString("user_guid", "");
            if (direct.matches("^u0[A-Za-z0-9]{1,126}$")) return direct;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String found = findUserGuid(object.opt(keys.next()));
                if (!found.isEmpty()) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = findUserGuid(array.opt(i));
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
