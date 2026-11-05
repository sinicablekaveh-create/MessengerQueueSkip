package com.sinicable.messengerqueue;

import android.content.ContentResolver;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Imports column A of .xlsx files or the first CSV column, preserving phone numbers as strings. */
final class ContactImporter {
    static List<String> read(ContentResolver resolver, Uri uri, String filename) throws Exception {
        try(InputStream is = resolver.openInputStream(uri)) {
            if(is==null)throw new IllegalArgumentException("File is not readable");
            List<String> raw;
            if(filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) raw = xlsx(is);
            else raw = csv(is);
            Set<String> unique = new HashSet<>();
            List<String> out = new ArrayList<>();
            for(String r:raw) {
                String n=phone(r);
                if(!n.isEmpty() && unique.add(n))out.add(n);
            }
            return out;
        }
    }
    private static List<String> csv(InputStream stream) throws Exception {
        List<String> out = new ArrayList<>();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            String line;
            while((line=br.readLine())!=null) {
                if(out.size()>100000)throw new IllegalArgumentException("Too many contacts");
                String v;
                if(line.startsWith("\"")) {
                    StringBuilder sb=new StringBuilder();
                    for(int i=1;i<line.length();i++) {
                        char ch=line.charAt(i);
                        if(ch=='\"') {
                            if(i+1<line.length() && line.charAt(i+1)=='\"') {sb.append('"');i++;}
                            else break;
                        } else sb.append(ch);
                    }
                    v=sb.toString();
                } else {
                    int comma=line.indexOf(',');
                    int semi=line.indexOf(';');
                    int tab=line.indexOf('\t');
                    int end=line.length();
                    if(comma>=0)end=Math.min(end,comma);
                    if(semi>=0)end=Math.min(end,semi);
                    if(tab>=0)end=Math.min(end,tab);
                    v=line.substring(0,end);
                }
                out.add(v);
            }
        }
        return out;
    }
    private static byte[] readZipEntry(ZipInputStream zip) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];int n;
        while((n=zip.read(buf))!=-1) {
            if(out.size()+n > 16_000_000)throw new IllegalArgumentException("Excel sheet too large");
            out.write(buf,0,n);
        }
        return out.toByteArray();
    }
    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
    }
    private static List<String> xlsx(InputStream stream) throws Exception {
        byte[] shared=null,sheet=null;
        try(ZipInputStream z=new ZipInputStream(stream)) {
            ZipEntry entry;
            while((entry=z.getNextEntry())!=null) {
                if("xl/sharedStrings.xml".equals(entry.getName()))shared=readZipEntry(z);
                if("xl/worksheets/sheet1.xml".equals(entry.getName()))sheet=readZipEntry(z);
                z.closeEntry();
            }
        }
        if(sheet==null)throw new IllegalArgumentException("First worksheet not found");
        List<String> strings=new ArrayList<>();
        if(shared!=null) {
            Document d=parse(shared);
            NodeList si=d.getElementsByTagName("si");
            for(int i=0;i<si.getLength();i++) {
                Element el=(Element)si.item(i);
                NodeList ts=el.getElementsByTagName("t");
                StringBuilder b=new StringBuilder();
                for(int j=0;j<ts.getLength();j++) b.append(ts.item(j).getTextContent());
                strings.add(b.toString());
            }
        }
        List<String> out=new ArrayList<>();
        Document d=parse(sheet);
        NodeList rows=d.getElementsByTagName("row");
        for(int i=0;i<rows.getLength();i++) {
            Element row=(Element)rows.item(i);
            NodeList cells=row.getElementsByTagName("c");
            for(int j=0;j<cells.getLength();j++) {
                Element cell=(Element)cells.item(j);
                String r=cell.getAttribute("r");
                if(!r.matches("(?i)A[0-9]+"))continue;
                String typ=cell.getAttribute("t");
                String v="";
                if("inlineStr".equals(typ)) {
                    NodeList t=cell.getElementsByTagName("t");
                    if(t.getLength()>0)v=t.item(0).getTextContent();
                } else {
                    NodeList vs=cell.getElementsByTagName("v");
                    if(vs.getLength()>0)v=vs.item(0).getTextContent();
                    if("s".equals(typ)) {
                        int idx=Integer.parseInt(v);
                        v=idx>=0&&idx<strings.size()?strings.get(idx):"";
                    }
                }
                out.add(v);
                break;
            }
        }
        return out;
    }
    static String phone(String raw) {
        if(raw==null)return "";
        String v=raw.trim();
        StringBuilder b=new StringBuilder();
        for(int i=0;i<v.length();i++) {
            char ch=v.charAt(i);
            if(ch>='۰'&&ch<='۹')ch=(char)('0'+ch-'۰');
            if(ch>='٠'&&ch<='٩')ch=(char)('0'+ch-'٠');
            if(ch>='0'&&ch<='9')b.append(ch);
            else if(ch=='+'&&b.length()==0)b.append(ch);
        }
        String s=b.toString();
        if(s.startsWith("+98")&&s.length()==13)s="0"+s.substring(3);
        else if(s.startsWith("0098")&&s.length()==14)s="0"+s.substring(4);
        else if(s.startsWith("98")&&s.length()==12)s="0"+s.substring(2);
        else if(s.startsWith("9")&&s.length()==10)s="0"+s;
        if(s.startsWith("09")&&s.length()==11)return s;
        if(s.startsWith("+")&&s.length()>=11&&s.length()<=16)return s;
        return "";
    }
    static String international(String local) {
        if(local.startsWith("09")&&local.length()==11)return "98"+local.substring(1);
        return local.replace("+","");
    }
}

