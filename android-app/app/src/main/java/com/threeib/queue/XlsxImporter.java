package com.threeib.queue;

import android.content.Context;
import android.net.Uri;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;

public final class XlsxImporter {
    private XlsxImporter() {}

    public static ImportResult importInto(Context ctx, Uri uri, DbHelper db, boolean allowBale, boolean allowEitaa, boolean allowRubika) throws Exception {
        File tmp = File.createTempFile("three_ib_", ".xlsx", ctx.getCacheDir());
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IllegalArgumentException("Cannot open selected file");
            byte[] buf = new byte[32 * 1024]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        try { return parse(tmp, db, allowBale, allowEitaa, allowRubika); }
        finally { tmp.delete(); }
    }

    private static ImportResult parse(File file, DbHelper db, boolean allowBale, boolean allowEitaa, boolean allowRubika) throws Exception {
        ImportResult result = new ImportResult();
        try (ZipFile zip = new ZipFile(file)) {
            List<String> shared = loadSharedStrings(zip);
            ZipEntry sheet = zip.getEntry("xl/worksheets/sheet1.xml");
            if (sheet == null) throw new IllegalArgumentException("Excel sheet1 not found");
            Document doc;
            try (InputStream in = zip.getInputStream(sheet)) { doc = factory().newDocumentBuilder().parse(in); }
            NodeList rows = doc.getElementsByTagNameNS("*", "row");
            Map<Integer, String> headers = new HashMap<>();
            for (int ri = 0; ri < rows.getLength(); ri++) {
                Element row = (Element) rows.item(ri);
                Map<Integer, String> values = parseRow(row, shared);
                if (ri == 0) {
                    for (Map.Entry<Integer,String> e : values.entrySet()) headers.put(e.getKey(), cleanHeader(e.getValue()));
                    continue;
                }
                Map<String,String> data = new HashMap<>();
                for (Map.Entry<Integer,String> e : values.entrySet()) {
                    String h = headers.get(e.getKey());
                    if (h != null) data.put(h, e.getValue());
                }
                String phone = normalizePhone(data.get("phone"));
                if (phone.isEmpty()) { result.skipped++; continue; }
                if (!truthy(data.get("opt_in"))) { result.skipped++; continue; }
                String name = safe(data.get("name")).trim();
                if (name.isEmpty()) name = "TIB-" + phone.substring(Math.max(0, phone.length() - 8));
                String message = safe(data.get("message"));

                boolean rowBale = !data.containsKey("send_bale") || truthy(data.get("send_bale"));
                boolean rowEitaa = !data.containsKey("send_eitaa") || truthy(data.get("send_eitaa"));
                boolean rowRubika = !data.containsKey("send_rubika") || truthy(data.get("send_rubika"));
                int before = result.added;
                if (allowBale && rowBale && db.enqueue(phone, name, "bale", message) > 0) result.added++;
                if (allowEitaa && rowEitaa && db.enqueue(phone, name, "eitaa", message) > 0) result.added++;
                if (allowRubika && rowRubika && db.enqueue(phone, name, "rubika", message) > 0) result.added++;
                if (result.added == before) result.skipped++;
            }
        }
        return result;
    }

    private static Map<Integer,String> parseRow(Element row, List<String> shared) {
        Map<Integer,String> out = new HashMap<>();
        NodeList cells = row.getElementsByTagNameNS("*", "c");
        for (int i = 0; i < cells.getLength(); i++) {
            Element c = (Element) cells.item(i);
            int col = columnIndex(c.getAttribute("r"));
            String t = c.getAttribute("t");
            String value = "";
            if ("inlineStr".equals(t)) {
                NodeList ts = c.getElementsByTagNameNS("*", "t");
                StringBuilder sb = new StringBuilder();
                for (int j=0;j<ts.getLength();j++) sb.append(ts.item(j).getTextContent());
                value = sb.toString();
            } else {
                NodeList vs = c.getElementsByTagNameNS("*", "v");
                if (vs.getLength() > 0) value = vs.item(0).getTextContent();
                if ("s".equals(t)) {
                    try { int idx = Integer.parseInt(value); if (idx >= 0 && idx < shared.size()) value = shared.get(idx); }
                    catch (NumberFormatException ignored) {}
                } else if ("b".equals(t)) {
                    value = "1".equals(value) ? "true" : "false";
                }
            }
            out.put(col, value == null ? "" : value.trim());
        }
        return out;
    }

    private static List<String> loadSharedStrings(ZipFile zip) throws Exception {
        List<String> out = new ArrayList<>();
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) return out;
        Document doc;
        try (InputStream in = zip.getInputStream(entry)) { doc = factory().newDocumentBuilder().parse(in); }
        NodeList sis = doc.getElementsByTagNameNS("*", "si");
        for (int i=0;i<sis.getLength();i++) {
            Element si = (Element) sis.item(i);
            NodeList ts = si.getElementsByTagNameNS("*", "t");
            StringBuilder sb = new StringBuilder();
            for (int j=0;j<ts.getLength();j++) sb.append(ts.item(j).getTextContent());
            out.add(sb.toString());
        }
        return out;
    }

    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
        f.setExpandEntityReferences(false);
        return f;
    }

    private static int columnIndex(String ref) {
        int n = 0;
        for (int i=0; i<ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch < 'A' || ch > 'Z') break;
            n = n * 26 + (ch - 'A' + 1);
        }
        return Math.max(0, n - 1);
    }

    private static String cleanHeader(String s) {
        return safe(s).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    public static String normalizePhone(String in) {
        String s = safe(in).trim();
        StringBuilder b = new StringBuilder();
        for (int i=0;i<s.length();i++) {
            char c = s.charAt(i);
            if (c >= '۰' && c <= '۹') c = (char)('0' + (c - '۰'));
            if (c >= '٠' && c <= '٩') c = (char)('0' + (c - '٠'));
            if (Character.isDigit(c) || (c=='+' && b.length()==0)) b.append(c);
        }
        s = b.toString();
        if (s.startsWith("+98")) s = "0" + s.substring(3);
        else if (s.startsWith("98") && s.length() >= 12) s = "0" + s.substring(2);
        else if (s.startsWith("9") && s.length() == 10) s = "0" + s;
        return s;
    }

    private static boolean truthy(String s) {
        s = safe(s).trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("بله") || s.equals("بلی") || s.equals("ok");
    }
    private static String safe(String s) { return s == null ? "" : s; }

    public static class ImportResult {
        public int added;
        public int skipped;
    }
}
