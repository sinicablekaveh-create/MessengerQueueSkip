package com.sinicable.messengerqueue;

import android.content.*;
import android.os.*;
import org.drinkless.tdlib.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Single TDLib client; all queue transitions serialized on the main looper. */
final class TelegramEngine {
 static TelegramEngine instance;
 static synchronized TelegramEngine get(Context c) { if(instance==null) instance=new TelegramEngine(c.getApplicationContext()); return instance; }
 final Context ctx; final Handler h=new Handler(Looper.getMainLooper());
 final android.content.SharedPreferences prefs;
 Client client; String auth="Connecting", info="", text="", photo=""; boolean ready=false,running=false,busy=false;
 List<String> phones=new ArrayList<>(); int index=0; long delay=60000,next=0,pendingId=0,pendingChat=0,earlySuccess=0,earlyFailure=0;
 Runnable observer;
 TelegramEngine(Context c) {
  ctx=c; prefs=c.getSharedPreferences("telegram_queue",0);
  try { JSONArray a=new JSONArray(prefs.getString("phones","[]")); for(int i=0;i<a.length();i++)phones.add(a.getString(i)); }catch(Exception ignored){}
  index=prefs.getInt("index",0); text=prefs.getString("text",""); photo=prefs.getString("photo",""); next=prefs.getLong("next",0); delay=Math.max(60000L,prefs.getLong("delay",60000L));
  info=prefs.getBoolean("inflight",false)?"REVIEW: last send was interrupted. Check Telegram before continuing.":"Queue paused";
  connect();
 }
 synchronized void connect() {
  if(client!=null)return;
  TelegramConfigStore.Credentials cfg=TelegramConfigStore.read(ctx);
  if(cfg==null){auth="ConfigurationRequired";info="Enter your Telegram API ID and API HASH, then save securely on this device.";notifyUi();return;}
  try {
   System.loadLibrary("tdjni");
   Client.execute(new TdApi.SetLogVerbosityLevel(0));
   client=Client.create(o->h.post(()->update(o)),null,null);
   auth="Connecting";
  } catch(Throwable e) {auth="TDLib initialization failed";info=e.getClass().getSimpleName();}
  notifyUi();
 }
 void notifyUi(){if(observer!=null)observer.run();}
 void request(TdApi.Function f, java.util.function.Consumer<TdApi.Object> cb){
  if(client==null){info="TDLib unavailable";notifyUi();return;}
  client.send(f,o->h.post(()->cb.accept(o)));
 }
 void error(TdApi.Object o){ if(o instanceof TdApi.Error){TdApi.Error e=(TdApi.Error)o; info="Error "+e.code+": "+e.message; running=false;busy=false;notifyUi();} }
 void update(TdApi.Object o){
  if(o instanceof TdApi.UpdateAuthorizationState){
   TdApi.AuthorizationState s=((TdApi.UpdateAuthorizationState)o).authorizationState;
   ready=s instanceof TdApi.AuthorizationStateReady; auth=s.getClass().getSimpleName().replace("AuthorizationState","");
   if(s instanceof TdApi.AuthorizationStateWaitTdlibParameters){
    try {
     TelegramConfigStore.Credentials cfg=TelegramConfigStore.read(ctx);
     if(cfg==null){info="Telegram API credentials are missing. Save them in this screen.";notifyUi();return;}
     TdApi.SetTdlibParameters p=new TdApi.SetTdlibParameters();
     p.apiId=cfg.apiId;p.apiHash=cfg.apiHash;
     p.databaseDirectory=new File(ctx.getFilesDir(),"telegram-db").getPath();
     p.filesDirectory=new File(ctx.getFilesDir(),"telegram-files").getPath();
     p.databaseEncryptionKey=TelegramConfigStore.databaseKey(ctx);
     p.useMessageDatabase=true;p.useChatInfoDatabase=true;p.useFileDatabase=true;p.useSecretChats=false;
     p.useTestDc=false;p.systemLanguageCode=java.util.Locale.getDefault().toLanguageTag();
     p.deviceModel=Build.MODEL;p.systemVersion=Build.VERSION.RELEASE;p.applicationVersion="3.2.0";
     request(p,this::error);
    } catch(Exception e){info="Telegram configuration or encrypted database key could not be loaded: "+e.getClass().getSimpleName();notifyUi();}
   }
   if(s instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation)info="Confirm this login in Telegram: "+((TdApi.AuthorizationStateWaitOtherDeviceConfirmation)s).link;
   if(s instanceof TdApi.AuthorizationStateClosed){info="Logged out. Close and reopen this screen to reconnect.";instance=null;}
   if(!ready && running)pause(); notifyUi();
  }else if(o instanceof TdApi.UpdateMessageSendSucceeded){
   TdApi.UpdateMessageSendSucceeded u=(TdApi.UpdateMessageSendSucceeded)o;
   if(busy&&u.message.chatId==pendingChat){if(pendingId==0)earlySuccess=u.oldMessageId;else if(u.oldMessageId==pendingId)complete();}
  }else if(o instanceof TdApi.UpdateMessageSendFailed){
   TdApi.UpdateMessageSendFailed u=(TdApi.UpdateMessageSendFailed)o;
   if(busy&&u.message.chatId==pendingChat){if(pendingId==0)earlyFailure=u.oldMessageId;else if(u.oldMessageId==pendingId){prefs.edit().putBoolean("inflight",false).apply();error(u.error);}}
  }
 }
 void login(String value){
  if(auth.equals("WaitPhoneNumber")){TdApi.SetAuthenticationPhoneNumber f=new TdApi.SetAuthenticationPhoneNumber();f.phoneNumber=value.startsWith("09")?"+98"+value.substring(1):value;request(f,this::error);}
  else if(auth.equals("WaitCode"))request(new TdApi.CheckAuthenticationCode(value),this::error);
  else if(auth.equals("WaitPassword"))request(new TdApi.CheckAuthenticationPassword(value),this::error);
  else if(auth.equals("WaitEmailAddress"))request(new TdApi.SetAuthenticationEmailAddress(value),this::error);
  else if(auth.equals("WaitEmailCode"))request(new TdApi.CheckAuthenticationEmailCode(new TdApi.EmailAddressAuthenticationCode(value)),this::error);
  else {info="Follow the current login state: "+auth;notifyUi();}
 }
 void save(){JSONArray a=new JSONArray(phones);prefs.edit().putString("phones",a.toString()).putInt("index",index).putString("text",text).putString("photo",photo).putLong("next",next).putLong("delay",delay).apply();}
 void replace(List<String> p){if(busy||running)return;phones=p;index=0;next=0;save();notifyUi();}
 void start(String t,String image,long interval){
  if(!ready){info="Sign in first";notifyUi();return;}
  if(busy||prefs.getBoolean("inflight",false)){info="REVIEW required: check last message before resolving it";notifyUi();return;}
  if(phones.isEmpty()||index>=phones.size()||(t.trim().isEmpty()&&image.isEmpty())){info="Import contacts and enter a message or image";notifyUi();return;}
  text=t;photo=image;delay=interval;save();running=true;
  try { ctx.startForegroundService(new Intent(ctx,TelegramQueueService.class)); }
  catch(RuntimeException ex) {running=false;info="Cannot start foreground queue: "+ex.getClass().getSimpleName();notifyUi();return;}
  h.postDelayed(this::step,500);notifyUi();
 }
 void pause(){running=false;info="Paused";notifyUi();}
 void resolve(boolean sent){if(busy||running)return;if(sent&&index<phones.size())index++;prefs.edit().putBoolean("inflight",false).apply();save();info="Review resolved; press START to continue";notifyUi();}
 String internationalPhone(String raw){
  String p=RecipientImportParser.phone(raw);
  if(p.startsWith("+"))return p;
  if(p.startsWith("0"))return "+98"+p.substring(1);
  return p.isEmpty()?"":"+"+p;
 }

 void step(){
  if(!running||busy)return;
  if(index>=phones.size()){running=false;info="Queue completed";notifyUi();return;}
  if(System.currentTimeMillis()<next){h.postDelayed(this::step,Math.min(next-System.currentTimeMillis(),10000));return;}
  busy=true; info="Finding recipient "+(index+1);notifyUi();
  TdApi.ImportContacts f=new TdApi.ImportContacts();TdApi.ImportedContact c=new TdApi.ImportedContact();c.phoneNumber=internationalPhone(phones.get(index));c.firstName="MQ "+c.phoneNumber;c.lastName="";c.note=new TdApi.FormattedText("",new TdApi.TextEntity[0]);f.contacts=new TdApi.ImportedContact[]{c};
  request(f,o->{if(o instanceof TdApi.Error){error(o);return;}long[] ids=((TdApi.ImportedContacts)o).userIds;
   if(ids.length==0||ids[0]==0){
    // A confirmed unavailable recipient is not a send attempt. Skip this row
    // rather than halting the entire opted-in queue; persist progress first.
    busy=false;index++;next=System.currentTimeMillis()+delay;save();
    info="Recipient unavailable on Telegram; skipped "+index+" / "+phones.size();notifyUi();
    if(running)h.postDelayed(this::step,delay);
    return;
   }
   request(new TdApi.CreatePrivateChat(ids[0],false),chat->{if(chat instanceof TdApi.Error){error(chat);return;}send(((TdApi.Chat)chat).id);});
  });
 }
 void send(long chatId){
  if(!running){busy=false;return;}
  TdApi.SendMessage f=new TdApi.SendMessage();f.chatId=chatId;
  TdApi.FormattedText ft=new TdApi.FormattedText(text,new TdApi.TextEntity[0]);
  if(photo.isEmpty()){TdApi.InputMessageText t=new TdApi.InputMessageText();t.text=ft;f.inputMessageContent=t;}
  else {TdApi.InputMessagePhoto p=new TdApi.InputMessagePhoto();p.photo=new TdApi.InputPhoto();p.photo.photo=new TdApi.InputFileLocal(photo);p.photo.addedStickerFileIds=new int[0];p.caption=ft;f.inputMessageContent=p;}
  prefs.edit().putBoolean("inflight",true).commit();pendingId=0;earlySuccess=0;earlyFailure=0;pendingChat=chatId;
  request(f,o->{if(o instanceof TdApi.Error){prefs.edit().putBoolean("inflight",false).apply();error(o);return;}
   TdApi.Message m=(TdApi.Message)o;pendingId=m.id;
   if(earlyFailure==m.id){prefs.edit().putBoolean("inflight",false).apply();busy=false;running=false;info="Telegram reported a failed send. Inspect account before retrying.";notifyUi();return;}
   if(earlySuccess==m.id||m.sendingState==null){complete();return;}info="Waiting for Telegram server confirmation";notifyUi();
   h.postDelayed(()->{if(busy&&pendingId==m.id){running=false;busy=false;info="REVIEW: send outcome uncertain. Do not resend before checking Telegram.";notifyUi();}},120000);
  });
 }
 void complete(){busy=false;pendingId=0;earlySuccess=0;earlyFailure=0;index++;next=System.currentTimeMillis()+delay;prefs.edit().putBoolean("inflight",false).apply();save();info="Server accepted "+index+" / "+phones.size();notifyUi();if(running)h.postDelayed(this::step,delay);}
}
