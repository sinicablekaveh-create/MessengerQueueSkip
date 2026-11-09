package com.sinicable.messengerqueue;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

final class RubikaBotImportParser {
    static final class Row {
        final String name, chatId;
        Row(String n,String id){name=n==null||n.trim().isEmpty()?"بدون نام":n.trim();chatId=id.trim();}
    }
    static final class Result {
        final List<Row> rows=new ArrayList<>();
        int invalid, duplicate, blocked;
    }

    static Result read(InputStream stream,String filename)throws Exception{
        if(stream==null)throw new IllegalArgumentException("File is not readable");
        String n=filename==null?"":filename.toLowerCase(Locale.ROOT);
        List<Map<String,String>> rows=n.endsWith(".xlsx")?xlsx(stream):csv(stream);
        Result out=new Result(); Set<String> seen=new HashSet<>();
        for(Map<String,String> row:rows){
            String id=safe(row.get("chat_id")).trim();
            if(id.isEmpty()||id.length()>160||!id.matches("[A-Za-z0-9_.:-]+")){out.invalid++;continue;}
            if(!truthy(row.get("opt_in"))){out.blocked++;continue;}
            if(!seen.add(id)){out.duplicate++;continue;}
            out.rows.add(new Row(row.get("name"),id));
        }
        return out;
    }

    private static List<Map<String,String>> csv(InputStream in)throws Exception{
        List<Map<String,String>> out=new ArrayList<>();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            String h=br.readLine(); if(h==null)throw new IllegalArgumentException("Empty CSV");
            if(h.startsWith("\uFEFF"))h=h.substring(1);
            char d=h.indexOf(';')>=0&&h.indexOf(',')<0?';':',';
            List<String> hs=parse(h,d); Map<Integer,String> names=new HashMap<>();
            boolean chat=false,opt=false;
            for(int i=0;i<hs.size();i++){String x=header(hs.get(i));names.put(i,x);if("chat_id".equals(x))chat=true;if("opt_in".equals(x))opt=true;}
            if(!chat||!opt)throw new IllegalArgumentException("Required headers: chat_id,opt_in (name optional)");
            String line; int count=0;
            while((line=br.readLine())!=null){
                if(++count>100000)throw new IllegalArgumentException("Too many rows");
                if(line.trim().isEmpty())continue;
                List<String> vals=parse(line,d); Map<String,String> row=new HashMap<>();
                for(int i=0;i<vals.size();i++){String k=names.get(i);if(k!=null&&!k.isEmpty())row.put(k,vals.get(i));}
                out.add(row);
            }
        }
        return out;
    }

    private static List<String> parse(String line,char d){
        List<String> out=new ArrayList<>(); StringBuilder b=new StringBuilder(); boolean q=false;
        for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"'){if(q&&i+1<line.length()&&line.charAt(i+1)=='"'){b.append('"');i++;}else q=!q;}else if(c==d&&!q){out.add(b.toString().trim());b.setLength(0);}else b.append(c);}
        out.add(b.toString().trim()); return out;
    }

    private static List<Map<String,String>> xlsx(InputStream in)throws Exception{
        byte[] shared=null,sheet=null;
        try(ZipInputStream z=new ZipInputStream(in)){ZipEntry e;while((e=z.getNextEntry())!=null){String n=e.getName();if("xl/sharedStrings.xml".equals(n))shared=read(z,16_000_000);else if("xl/worksheets/sheet1.xml".equals(n))sheet=read(z,32_000_000);z.closeEntry();}}
        if(sheet==null)throw new IllegalArgumentException("First worksheet not found");
        List<String> ss=new ArrayList<>();
        if(shared!=null){Document d=xml(shared);NodeList sis=d.getElementsByTagNameNS("*","si");for(int i=0;i<sis.getLength();i++){NodeList ts=((Element)sis.item(i)).getElementsByTagNameNS("*","t");StringBuilder b=new StringBuilder();for(int j=0;j<ts.getLength();j++)b.append(ts.item(j).getTextContent());ss.add(b.toString());}}
        Document d=xml(sheet); NodeList rows=d.getElementsByTagNameNS("*","row"); if(rows.getLength()==0)throw new IllegalArgumentException("Empty worksheet");
        Map<Integer,String> headers=new HashMap<>(); List<Map<String,String>> out=new ArrayList<>();
        for(int ri=0;ri<rows.getLength();ri++){
            if(ri>100000)throw new IllegalArgumentException("Too many rows");
            Map<Integer,String> vals=row((Element)rows.item(ri),ss);
            if(ri==0){boolean chat=false,opt=false;for(Map.Entry<Integer,String> e:vals.entrySet()){String h=header(e.getValue());headers.put(e.getKey(),h);if("chat_id".equals(h))chat=true;if("opt_in".equals(h))opt=true;}if(!chat||!opt)throw new IllegalArgumentException("Required headers: chat_id,opt_in (name optional)");continue;}
            Map<String,String> r=new HashMap<>();for(Map.Entry<Integer,String> e:vals.entrySet()){String h=headers.get(e.getKey());if(h!=null&&!h.isEmpty())r.put(h,e.getValue());}if(!r.isEmpty())out.add(r);
        }
        return out;
    }

    private static Map<Integer,String> row(Element row,List<String> shared){
        Map<Integer,String> out=new HashMap<>();NodeList cs=row.getElementsByTagNameNS("*","c");
        for(int i=0;i<cs.getLength();i++){Element c=(Element)cs.item(i);int col=col(c.getAttribute("r"));String t=c.getAttribute("t"),v="";
            if("inlineStr".equals(t)){NodeList ts=c.getElementsByTagNameNS("*","t");StringBuilder b=new StringBuilder();for(int j=0;j<ts.getLength();j++)b.append(ts.item(j).getTextContent());v=b.toString();}
            else{NodeList vs=c.getElementsByTagNameNS("*","v");if(vs.getLength()>0)v=vs.item(0).getTextContent();if("s".equals(t)){try{int x=Integer.parseInt(v);v=x>=0&&x<shared.size()?shared.get(x):"";}catch(Exception ignored){v="";}}else if("b".equals(t))v="1".equals(v)?"true":"false";}
            out.put(col,v==null?"":v.trim());
        }return out;
    }
    private static int col(String r){int n=0;for(int i=0;i<r.length();i++){char c=Character.toUpperCase(r.charAt(i));if(c<'A'||c>'Z')break;n=n*26+c-'A'+1;}return Math.max(0,n-1);}
    private static byte[] read(ZipInputStream z,int limit)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=z.read(b))!=-1){if(o.size()+n>limit)throw new IllegalArgumentException("Excel too large");o.write(b,0,n);}return o.toByteArray();}
    private static Document xml(byte[] b)throws Exception{
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        try{f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true);}catch(Exception ignored){}
        try{f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);}catch(Exception ignored){}
        try{f.setFeature("http://xml.org/sax/features/external-general-entities",false);}catch(Exception ignored){}
        try{f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);}catch(Exception ignored){}
        try{f.setXIncludeAware(false);}catch(Exception ignored){}f.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder db=f.newDocumentBuilder();db.setEntityResolver((a,s)->new org.xml.sax.InputSource(new java.io.StringReader("")));
        return db.parse(new ByteArrayInputStream(b));
    }
    private static boolean truthy(String s){String v=safe(s).trim().toLowerCase(Locale.ROOT);return v.equals("1")||v.equals("true")||v.equals("yes")||v.equals("y")||v.equals("ok")||v.equals("بله")||v.equals("بلی");}
    private static String header(String s){String h=safe(s).replace("\uFEFF","").trim().toLowerCase(Locale.ROOT).replace(' ','_').replace('-','_');if(h.equals("نام")||h.equals("اسم"))return "name";if(h.equals("شناسه_چت")||h.equals("chatid"))return "chat_id";if(h.equals("رضایت")||h.equals("مجاز"))return "opt_in";return h;}
    private static String safe(String s){return s==null?"":s;}
}
