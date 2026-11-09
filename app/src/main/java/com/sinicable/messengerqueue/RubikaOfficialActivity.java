package com.sinicable.messengerqueue;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;

public final class RubikaOfficialActivity extends Activity {
    private static final int PICK=770;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private EditText token,message,delay;
    private CheckBox consent;
    private TextView status,recipients,api;
    private final Runnable refresh=new Runnable(){@Override public void run(){showStatus();ui.postDelayed(this,2000);}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(28,24,28,44);scroll.addView(root);setContentView(scroll);
        label(root,"THREE IB • RUBIKA OFFICIAL "+BuildConfig.VERSION_NAME,23);
        label(root,"نسخه رسمی مبتنی بر Rubika Bot API. مقصد ارسال chat_id است، نه شماره تلفن.",13);

        label(root,"1) توکن رسمی بات",18);
        token=field(root,"Token از @BotFather",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setSaveEnabled(false);
        button(root,"SAVE TOKEN + TEST getMe",this::saveAndTest);
        button(root,"باز کردن BotFather روبیکا",()->open("https://rubika.ir/BotFather"));
        api=label(root,"اتصال API: بررسی نشده",13);

        label(root,"2) گیرندگان مجاز",18);
        button(root,"SYNC کاربرانی که بات را Start کرده‌اند",this::syncOptedIn);
        button(root,"IMPORT Excel / CSV (chat_id,opt_in)",this::pick);
        recipients=label(root,"",13);

        label(root,"3) متن و فاصله",18);
        message=field(root,"متن پیام",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);message.setMinLines(4);message.setText(RubikaBotQueueDb.get(this,"message",""));
        delay=field(root,"فاصله ارسال، ۱ تا ۱۴۴۰ دقیقه",InputType.TYPE_CLASS_NUMBER);delay.setText(RubikaBotQueueDb.get(this,"delay","5"));
        consent=new CheckBox(this);consent.setText("تأیید می‌کنم گیرندگان صف برای دریافت پیام از این بات رضایت داده‌اند.");root.addView(consent);

        Button start=new Button(this);start.setText("START RUBIKA OFFICIAL");start.setAllCaps(false);start.setOnClickListener(v->startQueue());root.addView(start);
        button(root,"STOP",this::stopQueue);
        button(root,"RETRY FAILED",()->{RubikaBotQueueDb.retryFailed(this);showStatus();});
        button(root,"CLEAR RECIPIENTS",()->{stopQueue();RubikaBotQueueDb.clear(this);showStatus();});
        status=label(root,"",13);
        label(root,"برای دریافت chat_id، کاربر باید ابتدا بات را Start کند. API رسمی روبیکا ارسال مستقیم به شماره تلفن را تعریف نکرده است.",12);
        showStatus();AppStyle.decorate(this,getWindow().getDecorView());
    }

    private TextView label(LinearLayout r,String s,int z){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setPadding(0,8,0,8);r.addView(t);return t;}
    private EditText field(LinearLayout r,String h,int type){EditText e=new EditText(this);e.setHint(h);e.setInputType(type);r.addView(e,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));return e;}
    private void button(LinearLayout r,String s,Runnable a){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setOnClickListener(v->a.run());r.addView(b);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void open(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("باز کردن لینک ممکن نشد");}}

    private void saveAndTest(){
        String t=token.getText().toString().trim();token.setText("");
        if(!RubikaBotConfigStore.saveToken(this,t)){toast("توکن معتبر ذخیره نشد");return;}
        async(()->{
            RubikaBotClient.Reply r=RubikaBotClient.getMe(this);if(!r.ok())throw new IllegalStateException(r.error());
            JSONObject bot=r.data().optJSONObject("bot");String name=bot==null?"":bot.optString("username",bot.optString("first_name",""));
            final String msg="API رسمی متصل شد"+(name.isEmpty()?"":" • "+name);ui.post(()->api.setText(msg));return msg;
        });
    }

    private void syncOptedIn(){
        async(()->{
            String offset=RubikaBotQueueDb.get(this,"offset","");int added=0;
            for(int page=0;page<20;page++){
                RubikaBotClient.Reply r=RubikaBotClient.getUpdates(this,offset,100);if(!r.ok())throw new IllegalStateException(r.error());
                JSONObject d=r.data();JSONArray updates=d.optJSONArray("updates");if(updates==null||updates.length()==0)break;
                for(int i=0;i<updates.length();i++){
                    JSONObject u=updates.optJSONObject(i);if(u==null)continue;
                    String type=u.optString("type",""),chat=u.optString("chat_id","");
                    JSONObject nm=u.optJSONObject("new_message");String text=nm==null?"":nm.optString("text","");
                    boolean opted="StartedBot".equalsIgnoreCase(type)||"/start".equalsIgnoreCase(text.trim());
                    if(opted&&!chat.isEmpty()&&RubikaBotQueueDb.addOptedIn(this,chat,"Rubika opted-in user"))added++;
                }
                String next=d.optString("next_offset_id","");if(next.isEmpty()||next.equals(offset))break;offset=next;RubikaBotQueueDb.put(this,"offset",offset);
            }
            final int n=added;ui.post(()->{toast(n+" کاربر Start‌شده اضافه شد");showStatus();});return "Sync completed";
        });
    }

    private void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/csv","text/plain"});startActivityForResult(i,PICK);}
    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);if(req!=PICK||res!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        async(()->{
            String name=fileName(uri);String lower=name.toLowerCase(Locale.ROOT);if(!lower.endsWith(".xlsx")&&!lower.endsWith(".csv"))throw new IllegalArgumentException("فقط XLSX یا CSV");
            RubikaBotImportParser.Result parsed;try(InputStream in=getContentResolver().openInputStream(uri)){parsed=RubikaBotImportParser.read(in,name);}
            int added=RubikaBotQueueDb.replace(this,parsed.rows);
            RubikaBotQueueDb.put(this,"file",name);RubikaBotQueueDb.put(this,"invalid",String.valueOf(parsed.invalid));RubikaBotQueueDb.put(this,"blocked",String.valueOf(parsed.blocked));RubikaBotQueueDb.put(this,"duplicate",String.valueOf(parsed.duplicate));
            ui.post(()->{consent.setChecked(false);toast(added+" chat_id مجاز وارد شد");showStatus();});return "Import completed";
        });
    }
    private String fileName(Uri u){String n="rubika.xlsx";try(Cursor c=getContentResolver().query(u,null,null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0&&!c.isNull(i))n=c.getString(i);}}catch(Exception ignored){}return n;}

    private void startQueue(){
        if(!consent.isChecked()){toast("تأیید رضایت گیرندگان لازم است");return;}
        if(RubikaBotConfigStore.token(this).isEmpty()){toast("اول توکن بات را ذخیره و تست کن");return;}
        if(RubikaBotQueueDb.total(this)==0){toast("گیرنده مجاز وجود ندارد");return;}
        String m=message.getText().toString().trim();if(m.isEmpty()){toast("متن پیام را وارد کن");return;}
        int d;try{d=Integer.parseInt(delay.getText().toString().trim());}catch(Exception e){d=0;}if(d<1||d>1440){toast("فاصله باید ۱ تا ۱۴۴۰ دقیقه باشد");return;}
        RubikaBotQueueDb.put(this,"message",m);RubikaBotQueueDb.put(this,"delay",String.valueOf(d));RubikaBotQueueDb.put(this,"next_due","0");RubikaBotQueueDb.put(this,"running","1");
        Intent i=new Intent(this,RubikaBotQueueService.class);i.setAction(RubikaBotQueueService.ACTION_START);startForegroundService(i);toast("صف رسمی روبیکا شروع شد");showStatus();
    }
    private void stopQueue(){RubikaBotQueueDb.put(this,"running","0");Intent i=new Intent(this,RubikaBotQueueService.class);i.setAction(RubikaBotQueueService.ACTION_STOP);try{startService(i);}catch(Exception ignored){}showStatus();}
    private void showStatus(){if(recipients!=null)recipients.setText("گیرندگان: "+RubikaBotQueueDb.total(this)+" | pending="+RubikaBotQueueDb.count(this,"pending")+" | sent="+RubikaBotQueueDb.count(this,"sent")+" | failed="+RubikaBotQueueDb.count(this,"failed"));if(status!=null)status.setText("Running: "+RubikaBotQueueDb.get(this,"running","0")+"\nآخرین نتیجه: "+RubikaBotQueueDb.get(this,"last_result","-")+"\nفایل: "+RubikaBotQueueDb.get(this,"file","Sync/API"));}
    private interface Work{String run()throws Exception;}
    private void async(Work w){worker.execute(()->{try{String s=w.run();ui.post(()->toast(s));}catch(Exception e){String m="خطا: "+e.getClass().getSimpleName()+": "+(e.getMessage()==null?"":e.getMessage());ui.post(()->{if(api!=null)api.setText(m);toast(m);});}});}
    @Override protected void onStart(){super.onStart();ui.removeCallbacks(refresh);ui.post(refresh);}
    @Override protected void onStop(){ui.removeCallbacks(refresh);super.onStop();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
