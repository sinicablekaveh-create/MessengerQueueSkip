package com.sinicable.messengerqueue;
import java.io.*;import java.nio.charset.StandardCharsets;
public final class RubikaBotImportParserTest{
 public static void main(String[]a)throws Exception{
  String csv="name,chat_id,opt_in\\nAli,u0ABC123,true\\nBlocked,u0NOPE,false\\nDup,u0ABC123,true\\nSara,u0XYZ789,yes\\n";
  RubikaBotImportParser.Result r=RubikaBotImportParser.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),"x.csv");
  if(r.rows.size()!=2)throw new AssertionError("rows="+r.rows.size());
  if(r.blocked!=1)throw new AssertionError("blocked="+r.blocked);
  if(r.duplicate!=1)throw new AssertionError("duplicate="+r.duplicate);
  if(!"u0ABC123".equals(r.rows.get(0).chatId))throw new AssertionError("chat id");
  System.out.println("Rubika Official import test OK");
 }
}
