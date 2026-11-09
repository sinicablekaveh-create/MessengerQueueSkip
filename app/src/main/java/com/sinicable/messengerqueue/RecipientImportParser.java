package com.sinicable.messengerqueue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Pure-Java recipient parser for legacy consent and two-column name/phone lists. */
final class RecipientImportParser {
    static final class Recipient {
        final String name;
        final String phone;
        final boolean optIn;
        final String optInSource;
        final boolean sendSms;
        final String message;

        Recipient(String name, String phone, boolean optIn, String optInSource, boolean sendSms, String message) {
            this.name=name==null||name.trim().isEmpty()?"بدون نام":name.trim();
            this.phone=phone;
            this.optIn=optIn;
            this.optInSource=optInSource==null?"":optInSource.trim();
            this.sendSms=sendSms;
            this.message=message==null?"":message;
        }
    }

    static final class Result {
        final List<Recipient> recipients=new ArrayList<>();
        int invalidRows;
        int duplicateRows;
        int blockedRows;
    }

    static Result read(InputStream stream, String filename) throws Exception {
        return read(stream,filename,false);
    }

    /** smsDedicated is true only for the standalone SMS app. */
    static Result read(InputStream stream, String filename, boolean smsDedicated) throws Exception {
        if(stream==null)throw new IllegalArgumentException("File is not readable");
        String name=filename==null?"":filename.toLowerCase(Locale.ROOT);
        List<Map<String,String>> rows=name.endsWith(".xlsx")?xlsx(stream,smsDedicated):csv(stream,smsDedicated);
        return normalize(rows,smsDedicated,smsDedicated);
    }

    /** Simple standalone channel: file provides recipients; START provides the explicit attestation. */
    static Result readAttested(InputStream stream,String filename) throws Exception {
        if(stream==null)throw new IllegalArgumentException("File is not readable");
        String name=filename==null?"":filename.toLowerCase(Locale.ROOT);
        List<Map<String,String>> rows=name.endsWith(".xlsx")?xlsx(stream,true):csv(stream,true);
        return normalize(rows,true,false);
    }

    private static Result normalize(List<Map<String,String>> rows,boolean attestAtStart,boolean enableSms) {
        Result result=new Result();
        Set<String> seen=new HashSet<>();
        for(Map<String,String> row:rows) {
            String phone=phone(row.get("phone"));
            if(phone.isEmpty()) {result.invalidRows++;continue;}
            if(!seen.add(phone)) {result.duplicateRows++;continue;}
            boolean twoColumn=row.containsKey("name")&&!row.containsKey("opt_in");
            String contactName=safe(row.get("name")).trim();
            if(contactName.isEmpty())contactName="بدون نام";
            // Standalone SMS-simple mode accepts the selected file as the user-managed recipient list.
            // START still requires an explicit on-screen attestation before any send begins.
            // Non-SMS imports preserve the previous two-column behavior.
            boolean optIn=attestAtStart||twoColumn||truthy(row.get("opt_in"));
            boolean sendSms=enableSms||truthy(row.get("send_sms"));
            String source=attestAtStart
                    ?"user-attestation-required-at-start"
                    :(twoColumn?"user-attestation-required-at-start":safe(row.get("opt_in_source")).trim());
            if(source.isEmpty())source="imported-row";
            if(!optIn)result.blockedRows++;
            result.recipients.add(new Recipient(contactName,phone,optIn,source,sendSms,safe(row.get("message"))));
        }
        return result;
    }

    private static List<Map<String,String>> csv(InputStream stream,boolean smsDedicated) throws Exception {
        List<Map<String,String>> out=new ArrayList<>();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            String headerLine=br.readLine();
            if(headerLine==null)throw new IllegalArgumentException("Empty CSV");
            if(headerLine.startsWith("\uFEFF"))headerLine=headerLine.substring(1);
            char delimiter=delimiter(headerLine);
            List<String> headers=parseCsvLine(headerLine,delimiter);
            Map<Integer,String> normalizedHeaders=new HashMap<>();
            boolean phone=false,optIn=false,contactName=false;
            for(int i=0;i<headers.size();i++) {
                String h=header(headers.get(i));
                normalizedHeaders.put(i,h);
                if("phone".equals(h))phone=true;
                if("opt_in".equals(h))optIn=true;
                if("name".equals(h))contactName=true;
            }
            if(!phone||(!smsDedicated&&!optIn&&!contactName))throw new IllegalArgumentException("Required header: phone; non-SMS imports also require name or opt_in");

            String line;
            int rowCount=0;
            while((line=br.readLine())!=null) {
                if(++rowCount>100000)throw new IllegalArgumentException("Too many recipient rows");
                if(line.trim().isEmpty())continue;
                List<String> values=parseCsvLine(line,delimiter);
                Map<String,String> row=new HashMap<>();
                for(int i=0;i<values.size();i++) {
                    String h=normalizedHeaders.get(i);
                    if(h!=null&&!h.isEmpty())row.put(h,values.get(i));
                }
                out.add(row);
            }
        }
        return out;
    }

    private static char delimiter(String header) {
        int comma=count(header,',');
        int semi=count(header,';');
        int tab=count(header,'\t');
        if(tab>=comma&&tab>=semi&&tab>0)return '\t';
        if(semi>comma)return ';';
        return ',';
    }

    private static int count(String s,char c) {
        int n=0; boolean quoted=false;
        for(int i=0;i<s.length();i++) {
            char ch=s.charAt(i);
            if(ch=='"') {
                if(quoted&&i+1<s.length()&&s.charAt(i+1)=='"'){i++;continue;}
                quoted=!quoted;
            } else if(!quoted&&ch==c)n++;
        }
        return n;
    }

    private static List<String> parseCsvLine(String line,char delimiter) {
        List<String> out=new ArrayList<>();
        StringBuilder b=new StringBuilder();
        boolean quoted=false;
        for(int i=0;i<line.length();i++) {
            char ch=line.charAt(i);
            if(ch=='"') {
                if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){b.append('"');i++;}
                else quoted=!quoted;
            } else if(ch==delimiter&&!quoted) {
                out.add(b.toString().trim());b.setLength(0);
            } else b.append(ch);
        }
        out.add(b.toString().trim());
        return out;
    }

    private static List<Map<String,String>> xlsx(InputStream stream,boolean smsDedicated) throws Exception {
        byte[] shared=null,sheet=null;
        try(ZipInputStream zip=new ZipInputStream(stream)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String n=entry.getName();
                if("xl/sharedStrings.xml".equals(n))shared=readEntry(zip,16_000_000);
                else if("xl/worksheets/sheet1.xml".equals(n))sheet=readEntry(zip,32_000_000);
                zip.closeEntry();
            }
        }
        if(sheet==null)throw new IllegalArgumentException("First worksheet not found");
        List<String> strings=new ArrayList<>();
        if(shared!=null) {
            Document d=parseXml(shared);
            NodeList sis=d.getElementsByTagNameNS("*","si");
            for(int i=0;i<sis.getLength();i++) {
                Element si=(Element)sis.item(i);
                NodeList ts=si.getElementsByTagNameNS("*","t");
                StringBuilder b=new StringBuilder();
                for(int j=0;j<ts.getLength();j++)b.append(ts.item(j).getTextContent());
                strings.add(b.toString());
            }
        }

        Document d=parseXml(sheet);
        NodeList rows=d.getElementsByTagNameNS("*","row");
        if(rows.getLength()==0)throw new IllegalArgumentException("Empty worksheet");
        Map<Integer,String> headers=new HashMap<>();
        List<Map<String,String>> out=new ArrayList<>();
        for(int ri=0;ri<rows.getLength();ri++) {
            if(ri>100000)throw new IllegalArgumentException("Too many recipient rows");
            Map<Integer,String> values=parseXlsxRow((Element)rows.item(ri),strings);
            if(ri==0) {
                boolean phone=false,optIn=false,contactName=false;
                for(Map.Entry<Integer,String> e:values.entrySet()) {
                    String h=header(e.getValue());
                    headers.put(e.getKey(),h);
                    if("phone".equals(h))phone=true;
                    if("opt_in".equals(h))optIn=true;
                if("name".equals(h))contactName=true;
                }
                if(!phone||(!smsDedicated&&!optIn&&!contactName))throw new IllegalArgumentException("Required header: phone; non-SMS imports also require name or opt_in");
                continue;
            }
            Map<String,String> row=new HashMap<>();
            for(Map.Entry<Integer,String> e:values.entrySet()) {
                String h=headers.get(e.getKey());
                if(h!=null&&!h.isEmpty())row.put(h,e.getValue());
            }
            if(!row.isEmpty())out.add(row);
        }
        return out;
    }

    private static Map<Integer,String> parseXlsxRow(Element row,List<String> shared) {
        Map<Integer,String> out=new HashMap<>();
        NodeList cells=row.getElementsByTagNameNS("*","c");
        for(int i=0;i<cells.getLength();i++) {
            Element c=(Element)cells.item(i);
            int col=columnIndex(c.getAttribute("r"));
            String type=c.getAttribute("t");
            String value="";
            if("inlineStr".equals(type)) {
                NodeList ts=c.getElementsByTagNameNS("*","t");
                StringBuilder b=new StringBuilder();
                for(int j=0;j<ts.getLength();j++)b.append(ts.item(j).getTextContent());
                value=b.toString();
            } else {
                NodeList vs=c.getElementsByTagNameNS("*","v");
                if(vs.getLength()>0)value=vs.item(0).getTextContent();
                if("s".equals(type)) {
                    try {int idx=Integer.parseInt(value);value=idx>=0&&idx<shared.size()?shared.get(idx):"";}
                    catch(Exception ignored){value="";}
                } else if("b".equals(type)) value="1".equals(value)?"true":"false";
            }
            out.put(col,value==null?"":value.trim());
        }
        return out;
    }

    private static int columnIndex(String ref) {
        int n=0;
        for(int i=0;i<ref.length();i++) {
            char ch=Character.toUpperCase(ref.charAt(i));
            if(ch<'A'||ch>'Z')break;
            n=n*26+(ch-'A'+1);
        }
        return Math.max(0,n-1);
    }

    private static byte[] readEntry(ZipInputStream zip,int limit) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];int n;
        while((n=zip.read(buf))!=-1) {
            if(out.size()+n>limit)throw new IllegalArgumentException("Excel data too large");
            out.write(buf,0,n);
        }
        return out.toByteArray();
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);

        // Android XML parser implementations differ by OS/vendor. Unsupported
        // hardening features must not make an otherwise valid XLSX unreadable.
        try {f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true);}
        catch(Exception ignored){}
        try {f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);}
        catch(Exception ignored){}
        try {f.setFeature("http://xml.org/sax/features/external-general-entities",false);}
        catch(Exception ignored){}
        try {f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);}
        catch(Exception ignored){}
        try {f.setXIncludeAware(false);}
        catch(UnsupportedOperationException ignored){}
        f.setExpandEntityReferences(false);

        javax.xml.parsers.DocumentBuilder builder=f.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->
                new org.xml.sax.InputSource(new java.io.StringReader("")));
        return builder.parse(new ByteArrayInputStream(bytes));
    }

    static String phone(String raw) {
        if(raw==null)return "";
        String v=raw.trim();
        StringBuilder b=new StringBuilder();
        for(int i=0;i<v.length();i++) {
            char ch=v.charAt(i);
            if(ch>='۰'&&ch<='۹')ch=(char)('0'+ch-'۰');
            else if(ch>='٠'&&ch<='٩')ch=(char)('0'+ch-'٠');
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

    static boolean truthy(String s) {
        String v=safe(s).trim().toLowerCase(Locale.ROOT);
        return v.equals("1")||v.equals("true")||v.equals("yes")||v.equals("y")||
                v.equals("ok")||v.equals("بله")||v.equals("بلی")||v.equals("yes, i agree");
    }

    private static String header(String s) {
        String h=safe(s).replace("\uFEFF","").trim().toLowerCase(Locale.ROOT)
                .replace(' ','_').replace('-','_');
        if(h.equals("نام")||h.equals("اسم")||h.equals("نام_مخاطب")||h.equals("contact_name"))return "name";
        if(h.equals("شماره")||h.equals("شماره_تماس")||h.equals("شماره_تلفن")||h.equals("شماره_موبایل")||h.equals("موبایل")||h.equals("mobile")||h.equals("phone_number"))return "phone";
        return h;
    }

    private static String safe(String s){return s==null?"":s;}
}