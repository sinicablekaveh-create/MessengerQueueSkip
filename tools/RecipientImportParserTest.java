package com.sinicable.messengerqueue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class RecipientImportParserTest {
    private static void check(boolean ok,String message){
        if(!ok)throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        String csv="phone,opt_in,opt_in_source,send_sms,message\n"+
                "۰۹۱۲۱۲۳۴۵۶۷,بله,web-form,yes,hello\n"+
                "09121234567,true,duplicate,true,duplicate\n"+
                "09125550000,no,unknown,no,blocked\n"+
                "not-a-phone,true,source,true,bad\n";
        RecipientImportParser.Result r=RecipientImportParser.read(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),"recipients.csv");
        check(r.recipients.size()==2,"expected two unique valid recipients");
        check(r.duplicateRows==1,"duplicate count");
        check(r.invalidRows==1,"invalid count");
        check(r.blockedRows==1,"blocked count");
        RecipientImportParser.Recipient first=r.recipients.get(0);
        check("09121234567".equals(first.phone),"Persian digits normalize");
        check(first.optIn,"first recipient opted in");
        check(first.sendSms,"first recipient explicitly enabled SMS");
        check("web-form".equals(first.optInSource),"consent source preserved");
        RecipientImportParser.Recipient second=r.recipients.get(1);
        check(!second.optIn,"opt-out row must remain blocked");
        check(!second.sendSms,"SMS is not implicitly enabled");

        String simple="نام,شماره تماس\n,09121110000\nشرکت الف,09123330000\n";
        RecipientImportParser.Result two=RecipientImportParser.read(
                new ByteArrayInputStream(simple.getBytes(StandardCharsets.UTF_8)),"names.csv");
        check(two.recipients.size()==2,"two-column CSV must import");
        check("بدون نام".equals(two.recipients.get(0).name),"empty names must become بدون نام");
        check("شرکت الف".equals(two.recipients.get(1).name),"Persian name must be preserved");
        check(two.recipients.get(0).optIn,"two-column import needs subsequent START attestation");
        check(!two.recipients.get(0).sendSms,"non-SMS apps must not enable SIM SMS");
        RecipientImportParser.Result sms=RecipientImportParser.read(
                new ByteArrayInputStream(simple.getBytes(StandardCharsets.UTF_8)),"names.csv",true);
        check(sms.recipients.get(0).sendSms,"standalone SMS enables selection");

        String priorReview="name,phone,opt_in,send_sms\n"+
                "مخاطب تست,09124440000,false,false\n";
        RecipientImportParser.Result smsReview=RecipientImportParser.read(
                new ByteArrayInputStream(priorReview.getBytes(StandardCharsets.UTF_8)),"review.csv",true);
        check(smsReview.recipients.size()==1,"SMS Simple must read review-style files");
        check(smsReview.recipients.get(0).optIn,"SMS Simple uses START attestation instead of file opt_in");
        check(smsReview.recipients.get(0).sendSms,"SMS Simple enables selected rows after START attestation");

        String phoneOnly="phone\n09125550123\n";
        RecipientImportParser.Result smsPhoneOnly=RecipientImportParser.read(
                new ByteArrayInputStream(phoneOnly.getBytes(StandardCharsets.UTF_8)),"phones.csv",true);
        check(smsPhoneOnly.recipients.size()==1,"SMS Simple accepts phone-only files");

        RecipientImportParser.Result rubikaSimple=RecipientImportParser.readAttested(
                new ByteArrayInputStream(priorReview.getBytes(StandardCharsets.UTF_8)),"rubika.csv");
        check(rubikaSimple.recipients.size()==1,"Rubika Simple must read review-style files");
        check(rubikaSimple.recipients.get(0).optIn,"Rubika Simple uses START attestation");
        check(!rubikaSimple.recipients.get(0).sendSms,"Rubika Simple must not enable SIM SMS");
        boolean threw=false;
        try {
            RecipientImportParser.read(new ByteArrayInputStream(
                    "phone,message\n09121234567,test\n".getBytes(StandardCharsets.UTF_8)),"bad.csv");
        } catch(IllegalArgumentException expected){threw=true;}
        check(threw,"unsupported header without name or opt_in must fail");

        check("09121234567".equals(RecipientImportParser.phone("+989121234567")),"+98 normalize");
        check(RecipientImportParser.truthy("بلی"),"Persian truthy");
        System.out.println("RecipientImportParserTest OK");
    }
}
