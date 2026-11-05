package com.sinicable.messengerqueue;
import android.app.*;import android.os.*;import android.content.*;import android.widget.*;import android.net.Uri;import android.provider.OpenableColumns;import android.database.Cursor;import java.io.*;import java.util.*;
public class TelegramActivity extends Activity {
 TelegramEngine e;TextView state;EditText login,message,interval;CheckBox consent;String image="";Button submit;
 public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(128);e=TelegramEngine.get(this);
  LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(24,24,24,24);ScrollView s=new ScrollView(this);s.addView(l);setContentView(s);
  TextView title=new TextView(this);title.setText("THREE IB • TELEGRAM DIRECT");title.setTextSize(23);l.addView(title);
  state=new TextView(this);state.setTextIsSelectable(true);l.addView(state);
  login=edit(l,"Phone (+98...), login code, or 2-step password"); login.setInputType(129);login.setSaveEnabled(false);
  submit=button(l,"CONTINUE LOGIN",()->{String v=login.getText().toString().trim();login.setText("");e.login(v);});
  button(l,"IMPORT EXCEL / CSV",()->{if(e.running||e.busy){toast("Pause and wait for current send first");return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,71);});
  message=edit(l,"Message");message.setMinLines(3);message.setText(e.text);image=e.photo;
  button(l,"SELECT PHOTO",()->{if(e.running||e.busy)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,72);});
  button(l,"REMOVE PHOTO",()->{if(!e.running&&!e.busy){image="";toast("Photo removed");}});
  interval=edit(l,"Delay in minutes (1–1440)");interval.setInputType(2);interval.setText(String.valueOf(e.delay/60000L));
  consent=new CheckBox(this);consent.setText("Recipients consent to these messages. Their numbers will be imported into my Telegram contacts.");l.addView(consent);
  Button start=button(l,"START AUTOMATIC • TELEGRAM",()->{if(!consent.isChecked()){toast("Confirm recipient consent");return;}try{long n=Long.parseLong(interval.getText().toString());if(n<1||n>1440)throw new Exception();e.start(message.getText().toString(),image,n*60000);}catch(Exception ex){toast("Delay must be 1–1440 minutes");}});start.setBackgroundColor(0xff14865e);start.setTextColor(-1);
  button(l,"PAUSE",()->e.pause());
  button(l,"SKIP CURRENT / RESOLVE REVIEW",()->{if(e.running||e.busy){toast("Pause and wait. If outcome remains uncertain, reopen the app and check Telegram first.");return;}new AlertDialog.Builder(this).setTitle("Check the last recipient in Telegram").setMessage("Skip advances past this recipient. Retry can duplicate a message if it was already sent.").setPositiveButton("Skip / already sent",(d,w)->e.resolve(true)).setNeutralButton("Confirmed not sent",(d,w)->e.resolve(false)).setNegativeButton("Cancel",null).show();});
  button(l,"LOG OUT",()->{e.pause();e.request(new org.drinkless.tdlib.TdApi.LogOut(),e::error);});
  TextView note=new TextView(this);note.setText("Telegram uses the configured API ID. First login requires your own account. Server accepted means Telegram accepted the message, not that the recipient read it. A stopped app resumes only when you press START. Other messengers retain their existing accessibility mode.");l.addView(note);
  e.observer=this::refresh;refresh();
 }
 EditText edit(LinearLayout l,String hint){EditText v=new EditText(this);v.setHint(hint);l.addView(v);return v;}
 Button button(LinearLayout l,String text,Runnable r){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->r.run());l.addView(b);return b;}
 void toast(String t){Toast.makeText(this,t,Toast.LENGTH_LONG).show();}
 void refresh(){state.setText("Account: "+e.auth+"\nQueue: "+e.index+" / "+e.phones.size()+"\n"+e.info);submit.setEnabled(!e.ready);login.setVisibility(e.ready?8:0);}
 protected void onDestroy(){super.onDestroy();e.observer=null;}
 protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null)return;Uri uri=data.getData();
  new Thread(()->{try{
   if(req==71){String name="contacts.csv";try(Cursor c=getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));}List<String> p=ContactImporter.read(getContentResolver(),uri,name);runOnUiThread(()->{e.replace(p);toast(p.size()+" contacts imported");});}
   else if(req==72){File f=new File(getFilesDir(),"telegram-photo-"+System.currentTimeMillis());try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(f)){byte[] buf=new byte[8192];int n;long total=0;while((n=in.read(buf))!=-1){total+=n;if(total>10*1024*1024)throw new IOException("Photo must be under 10 MB");out.write(buf,0,n);}}runOnUiThread(()->{image=f.getPath();toast("Photo selected");});}
  }catch(Exception ex){runOnUiThread(()->toast("Import failed: "+ex.getMessage()));}}).start();
 }
}
