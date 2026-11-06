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

        boolean threw=false;
        try {
            RecipientImportParser.read(new ByteArrayInputStream(
                    "phone,name\n09121234567,test\n".getBytes(StandardCharsets.UTF_8)),"bad.csv");
        } catch(IllegalArgumentException expected){threw=true;}
        check(threw,"missing opt_in header must fail");

        check("09121234567".equals(RecipientImportParser.phone("+989121234567")),"+98 normalize");
        check(RecipientImportParser.truthy("بلی"),"Persian truthy");
        System.out.println("RecipientImportParserTest OK");
    }
}
