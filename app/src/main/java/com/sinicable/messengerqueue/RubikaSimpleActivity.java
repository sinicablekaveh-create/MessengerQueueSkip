package com.sinicable.messengerqueue;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Rubika Direct: personal-account login + Excel/CSV + text + delay + START. */
public final class RubikaSimpleActivity extends Activity {
    private static final int PICK_CONTACTS=760;
    private static final String PROVIDER="rubika";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());

    private EditText phone,code,password,message,minutes,testPhone;
    private CheckBox confirmation,testOwned;
    private TextView accountState,imported,queueState,preflightState,footer;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        EmbeddedGatewayService.ensureStarted(this);

        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28,24,28,44);
        scroll.addView(root);
        setContentView(scroll);

        TextView title=label(root,"THREE IB • RUBIKA DIRECT "+BuildConfig.VERSION_NAME,23);
        title.setTextColor(0xFF166346);
        label(root,"ورود حساب شخصی داخل خود APK، سپس Excel/CSV، متن، فاصله و START RUBIKA.",13);

        label(root,"1) ورود حساب روبیکا",18);
        phone=field(root,"شماره حساب، مانند 0912...",InputType.TYPE_CLASS_PHONE);
        phone.setText(QueueDb.get(this,"direct_phone_rubika",""));
        code=field(root,"کد تأیید",InputType.TYPE_CLASS_NUMBER);
        code.setSaveEnabled(false);
        password=field(root,"رمز دومرحله‌ای، در صورت درخواست",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        button(root,"دریافت کد ورود",this::requestCode);
        button(root,"ارسال مجدد کد",this::requestCode);
        button(root,"باز کردن Rubika Web رسمی",this::openRubikaWeb);
        button(root,"تأیید کد و ورود",this::verifyCode);
        button(root,"ثبت رمز دومرحله‌ای",this::verifyPassword);
        button(root,"بررسی وضعیت حساب",this::refreshAccount);
        accountState=label(root,"",13);
        button(root,"CHECK READY • بررسی کامل اتصال",this::checkReady);
        preflightState=label(root,"آمادگی: بررسی نشده",13);

        label(root,"2) فایل مخاطبان",18);
        button(root,"انتخاب Excel / CSV",this::pickContacts);
        imported=label(root,"",13);

        label(root,"3) پیام و فاصله",18);
        message=field(root,"متن پیام روبیکا",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setMinLines(4);
        message.setText(QueueDb.get(this,"direct_text",""));
        minutes=field(root,"فاصله بین ارسال‌ها، ۱ تا ۱۴۴۰ دقیقه",InputType.TYPE_CLASS_NUMBER);
        minutes.setText(String.valueOf(QueueDb.delayMinutes(this,PROVIDER)));

        confirmation=new CheckBox(this);
        confirmation.setText("تأیید می‌کنم همه شماره‌های فایل انتخاب‌شده برای دریافت این پیام رضایت دارند.");
        root.addView(confirmation);

        label(root,"4) تست یک پیام قبل از صف",18);
        testPhone=field(root,"شماره تستی خودم / مجاز، مثل 0912...",InputType.TYPE_CLASS_PHONE);
        testOwned=new CheckBox(this);
        testOwned.setText("تأیید می‌کنم شماره تستی متعلق به خودم یا گیرندهٔ مجاز است.");
        root.addView(testOwned);
        button(root,"TEST ONE • ارسال یک پیام تستی",this::testOne);

        Button start=new Button(this);
        start.setAllCaps(false);
        start.setText("START RUBIKA");
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF07865B));
        start.setTextColor(0xFFFFFFFF);
        start.setOnClickListener(v->startQueue());
        root.addView(start);

        button(root,"STOP",this::stopQueue);
        queueState=label(root,"",13);
        footer=label(root,"Rubika Direct از درگاه محلی بازسازی‌شده استفاده می‌کند. Submitted به معنی تحویل/خوانده‌شدن قطعی نیست.",12);

        refreshImported();
        refreshQueue("آماده");
        AppStyle.decorate(this,getWindow().getDecorView());
    }

    private TextView label(LinearLayout root,String value,int size){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setPadding(0,8,0,8);root.addView(t);return t;
    }
    private EditText field(LinearLayout root,String hint,int type){
        EditText e=new EditText(this);e.setHint(hint);e.setInputType(type);
        root.addView(e,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));return e;
    }
    private void button(LinearLayout root,String title,Runnable action){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setOnClickListener(v->action.run());root.addView(b);
    }
    private interface Task {String run() throws Exception;}
    private void network(String label,Task task){
        footer.setText("در حال "+label+" ...");
        worker.execute(()->{
            String out;
            try{out=task.run();}catch(Exception e){out="خطا: "+safeError(e);}
            final String result=out;
            ui.post(()->{
                footer.setText(result);
                if(result.startsWith("خطا:")) {
                    if(accountState!=null)accountState.setText(result);
                    toast(result);
                }
            });
        });
    }
    private static String safeError(Exception e){
        String m=e.getMessage()==null?"":e.getMessage();
        return e.getClass().getSimpleName()+": "+(m.length()>140?m.substring(0,140):m);
    }
    private void waitForGateway() throws Exception {
        EmbeddedGatewayService.ensureStarted(this);
        String last="";
        for(int i=0;i<22;i++){
            if(!EmbeddedGatewayService.credential(this).isEmpty()){
                try{
                    GatewayClient.Reply r=GatewayClient.call(this,"GET","/app/providers",null,null,null,null);
                    if(r.success())return;
                    last=r.summary();
                }catch(Exception e){last=safeError(e);}
            }
            String serviceError=getSharedPreferences("gateway",MODE_PRIVATE).getString("error","");
            if(serviceError!=null&&!serviceError.isEmpty())last=serviceError;
            Thread.sleep(550L);
        }
        throw new IllegalStateException("درگاه روبیکا آماده نشد"+(last.isEmpty()?"":" | "+last));
    }
    private String instance(){return QueueDb.get(this,"direct_instance_rubika","");}
    private JSONObject account() throws Exception {
        String inst=instance();
        GatewayClient.Reply r=GatewayClient.call(this,"GET","/devices/"+GatewayClient.alias(PROVIDER)+"/status",
                null,GatewayClient.alias(PROVIDER),inst.isEmpty()?null:inst,null);
        if(!r.success())throw new IllegalStateException(r.summary());
        return r.result();
    }

    /** GoOmni login contract: state is top-level; challenge carries delivery/timing metadata. */
    private JSONObject loginState() throws Exception {
        String inst=instance();
        if(inst.isEmpty())throw new IllegalStateException("حساب روبیکا هنوز provision نشده");
        GatewayClient.Reply r=GatewayClient.call(this,"GET",
                "/devices/"+GatewayClient.alias(PROVIDER)+"/login",
                null,GatewayClient.alias(PROVIDER),inst,null);
        if(!r.success())throw new IllegalStateException(r.summary());
        return r.result();
    }
    private String provision() throws Exception {
        String saved=instance();
        if(!saved.isEmpty())return saved;
        JSONObject body=new JSONObject();
        body.put("device_id",GatewayClient.alias(PROVIDER));
        body.put("provider",PROVIDER);
        String key=QueueDb.get(this,"direct_provision_key_rubika","");
        if(key.isEmpty()){
            key="threeib-rubika-"+UUID.randomUUID();
            QueueDb.put(this,"direct_provision_key_rubika",key);
        }
        GatewayClient.Reply r=GatewayClient.call(this,"POST","/devices",body,null,null,key);
        if(!r.success())throw new IllegalStateException(r.summary());
        String inst=r.result().optString("instance_id","");
        if(inst.isEmpty())throw new IllegalStateException("شناسه حساب ساخته نشد");
        QueueDb.put(this,"direct_instance_rubika",inst);
        return inst;
    }
    private void openRubikaWeb(){
        try {
            Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse("https://web.rubika.ir/"));
            startActivity(i);
        } catch(Exception e) {
            toast("باز کردن Rubika Web ممکن نشد");
        }
    }

    private void requestCode(){
        final String number=GatewayClient.normalizedPhone(phone.getText().toString());
        if(number.isEmpty()){toast("شماره معتبر وارد کن");return;}
        accountState.setText("در حال راه‌اندازی درگاه و درخواست کد...");
        toast("درخواست کد شروع شد");
        network("دریافت کد",()->{
            waitForGateway();
            String inst=provision();
            JSONObject body=new JSONObject();body.put("phone",number);
            GatewayClient.Reply r=GatewayClient.call(this,"POST","/devices/"+GatewayClient.alias(PROVIDER)+"/login",
                    body,GatewayClient.alias(PROVIDER),inst,null);
            if(!r.success())throw new IllegalStateException(r.summary());
            QueueDb.put(this,"direct_phone_rubika",number);

            JSONObject login=loginState();
            JSONObject challenge=login.optJSONObject("challenge");
            final String loginStateValue=login.optString("state","unknown");

            JSONObject status=account();
            ui.post(()->showAccount(status));

            if(challenge==null){
                final String detail="state="+loginStateValue+" | challenge ندارد";
                ui.post(()->{
                    accountState.setText(detail);
                    toast("وضعیت ورود: "+loginStateValue);
                });
                return detail;
            }

            final String delivery=challenge.optString("delivery","unknown");
            final String nextDelivery=challenge.optString("next_delivery","");
            final String available=challenge.optJSONArray("available_deliveries")==null
                    ? "" : challenge.optJSONArray("available_deliveries").toString();
            final String expiresAt=challenge.optString("expires_at","");
            final String resendAt=challenge.optString("resend_available_at","");
            final String masked=challenge.optString("masked_phone","");

            final String humanDelivery;
            if("sms".equalsIgnoreCase(delivery)) humanDelivery="SMS";
            else if("app".equalsIgnoreCase(delivery)) humanDelivery="داخل خود اپ روبیکا";
            else if("call".equalsIgnoreCase(delivery)) humanDelivery="تماس تلفنی";
            else humanDelivery=delivery.isEmpty()?"نامشخص":delivery;

            final String detail="STATE="+loginStateValue+
                    " | دریافت="+humanDelivery+
                    (masked.isEmpty()?"":" | شماره="+masked)+
                    (nextDelivery.isEmpty()?"":" | بعدی="+nextDelivery)+
                    (available.isEmpty()?"":" | روش‌های موجود="+available)+
                    (resendAt.isEmpty()?"":" | ارسال مجدد از "+resendAt)+
                    (expiresAt.isEmpty()?"":" | انقضا "+expiresAt);

            ui.post(()->{
                accountState.setText(detail);
                if("app".equalsIgnoreCase(delivery))
                    toast("کد را داخل خود برنامه روبیکا بررسی کن");
                else if("sms".equalsIgnoreCase(delivery))
                    toast("روبیکا: روش دریافت SMS");
                else if("call".equalsIgnoreCase(delivery))
                    toast("روبیکا: روش دریافت تماس تلفنی");
                else toast("STATE="+loginStateValue+" | delivery="+delivery);
            });
            return detail;
        });
    }
    private void verifyCode(){
        final String otp=code.getText().toString().trim();
        if(!otp.matches("[0-9]{4,9}")){toast("کد تأیید معتبر وارد کن");return;}
        code.setText("");
        network("تأیید کد",()->{
            waitForGateway();
            JSONObject login=loginState();
            JSONObject challenge=login.optJSONObject("challenge");
            if(challenge==null)throw new IllegalStateException("چالش ورود فعال نیست • state="+login.optString("state","unknown"));
            JSONObject body=new JSONObject();
            body.put("challenge_id",challenge.optString("challenge_id",""));
            body.put("code",otp);
            GatewayClient.Reply r=GatewayClient.call(this,"POST","/devices/"+GatewayClient.alias(PROVIDER)+"/login/code",
                    body,GatewayClient.alias(PROVIDER),instance(),null);
            if(!r.success())throw new IllegalStateException(r.summary());
            JSONObject after=account();
            ui.post(()->showAccount(after));
            return "وضعیت ورود: "+after.optString("auth","?");
        });
    }
    private void verifyPassword(){
        final String secret=password.getText().toString();
        if(secret.isEmpty()){toast("رمز دومرحله‌ای را وارد کن");return;}
        password.setText("");
        network("تأیید رمز",()->{
            JSONObject login=loginState();
            JSONObject challenge=login.optJSONObject("challenge");
            if(challenge==null)throw new IllegalStateException("چالش رمز فعال نیست • state="+login.optString("state","unknown"));
            JSONObject body=new JSONObject();
            body.put("challenge_id",challenge.optString("challenge_id",""));
            body.put("password",secret);
            GatewayClient.Reply r=GatewayClient.call(this,"POST","/devices/"+GatewayClient.alias(PROVIDER)+"/login/password",
                    body,GatewayClient.alias(PROVIDER),instance(),null);
            if(!r.success())throw new IllegalStateException(r.summary());
            JSONObject after=account();ui.post(()->showAccount(after));
            return "وضعیت ورود: "+after.optString("auth","?");
        });
    }
    private void refreshAccount(){
        network("بررسی حساب",()->{
            waitForGateway();
            JSONObject state=account();
            JSONObject login=loginState();
            ui.post(()->{
                showAccount(state);
                accountState.setText("LOGIN STATE="+login.optString("state","unknown")+
                        " | auth="+state.optString("auth","?")+
                        " | transport="+state.optString("transport","?"));
            });
            return "Rubika login="+login.optString("state","unknown")+
                    " | auth="+state.optString("auth","?")+
                    " | transport="+state.optString("transport","?");
        });
    }
    private void showAccount(JSONObject state){
        if(accountState!=null)accountState.setText("حساب: auth="+state.optString("auth","?")+
                " | transport="+state.optString("transport","?"));
    }

    private String preflight() throws Exception {
        waitForGateway();

        GatewayClient.Reply ready=GatewayClient.call(this,"GET","/ready",null,null,null,null);
        if(!ready.success())throw new IllegalStateException("Gateway readiness failed: "+ready.summary());

        String inst=instance();
        if(inst.isEmpty())throw new IllegalStateException("ابتدا با شماره و کد وارد روبیکا شو");

        JSONObject state=account();
        if(!"authenticated".equals(state.optString("auth"))||
                !"connected".equals(state.optString("transport")))
            throw new IllegalStateException("حساب روبیکا آماده نیست: auth="+
                    state.optString("auth","?")+" transport="+state.optString("transport","?"));

        GatewayClient.Reply caps=GatewayClient.call(this,"GET",
                "/devices/"+GatewayClient.alias(PROVIDER)+"/capabilities",
                null,GatewayClient.alias(PROVIDER),inst,null);
        if(!caps.success())throw new IllegalStateException("Rubika capabilities unavailable: "+caps.summary());

        return "READY ✅ | gateway=ready | auth=authenticated | transport=connected | capabilities=ok";
    }

    private void checkReady(){
        network("بررسی کامل آمادگی",()->{
            String result=preflight();
            ui.post(()->preflightState.setText("آمادگی: "+result));
            return result;
        });
    }

    private void testOne(){
        if(testOwned==null||!testOwned.isChecked()){
            toast("مالکیت/مجوز شماره تستی را تأیید کن");return;
        }
        final String recipient=GatewayClient.normalizedPhone(testPhone.getText().toString());
        if(recipient.isEmpty()){toast("شماره تستی معتبر وارد کن");return;}
        final String body=message.getText().toString().trim();
        if(body.isEmpty()){toast("متن پیام را وارد کن");return;}

        network("ارسال TEST ONE",()->{
            String ready=preflight();
            JSONObject payload=new JSONObject();
            payload.put("phone",recipient);
            payload.put("message",body);
            String idem="rubika-test-"+UUID.randomUUID();
            GatewayClient.Reply reply=GatewayClient.call(this,"POST","/send/message",
                    payload,GatewayClient.alias(PROVIDER),instance(),idem);
            if(!reply.success())throw new IllegalStateException(reply.summary());

            String state=reply.result().optString("state","");
            String sendId=reply.result().optString("send_id","");
            if(("queued".equals(state)||"sending".equals(state))&&!sendId.isEmpty()){
                for(int i=0;i<8;i++){
                    Thread.sleep(1500L);
                    GatewayClient.Reply check=GatewayClient.call(this,"GET",
                            "/send/operations/"+sendId,null,
                            GatewayClient.alias(PROVIDER),instance(),null);
                    if(!check.success())break;
                    state=check.result().optString("state",state);
                    if("succeeded".equals(state)||"failed".equals(state)||"unknown".equals(state))break;
                }
            }
            final String finalState=state.isEmpty()?"accepted":state;
            QueueDb.put(this,"rubika_last_test_state",finalState);
            ui.post(()->preflightState.setText("TEST ONE: "+finalState+" | "+ready));
            return "TEST ONE نتیجه: "+finalState+
                    ("succeeded".equals(finalState)?" ✅":"");
        });
    }

    private void pickContacts(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/csv","text/plain"});
        startActivityForResult(i,PICK_CONTACTS);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_CONTACTS||result!=RESULT_OK||data==null||data.getData()==null)return;
        final Uri uri=data.getData();
        network("خواندن مخاطبان",()->{
            String name=fileName(uri);
            String lower=name.toLowerCase(Locale.ROOT);
            if(!lower.endsWith(".xlsx")&&!lower.endsWith(".csv"))
                throw new IllegalArgumentException("فقط XLSX یا CSV");
            RecipientImportParser.Result parsed;
            try(InputStream input=getContentResolver().openInputStream(uri)){
                parsed=RecipientImportParser.readAttested(input,name);
            }
            if(parsed.recipients.isEmpty())throw new IllegalArgumentException("شماره معتبر پیدا نشد");
            stopQueue();
            QueueDb.clear(getApplicationContext());
            int added=QueueDb.append(getApplicationContext(),parsed);
            QueueDb.put(this,"rubika_file_name",name);
            QueueDb.put(this,"rubika_file_invalid",String.valueOf(parsed.invalidRows));
            QueueDb.put(this,"rubika_file_duplicates",String.valueOf(parsed.duplicateRows));
            ui.post(()->{confirmation.setChecked(false);refreshImported();refreshQueue("فایل جدید جایگزین صف قبلی شد");});
            return added+" شماره معتبر روبیکا آماده شد";
        });
    }
    private String fileName(Uri uri){
        String name="contacts.xlsx";
        try(Cursor c=getContentResolver().query(uri,null,null,null,null)){
            if(c!=null&&c.moveToFirst()){
                int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(i>=0&&!c.isNull(i))name=c.getString(i);
            }
        }catch(Exception ignored){}
        return name==null?"contacts.xlsx":name;
    }
    private void refreshImported(){
        if(imported==null)return;
        imported.setText("فایل: "+QueueDb.get(this,"rubika_file_name","انتخاب نشده")+
                "\nشماره معتبر: "+QueueDb.items(this).length()+
                " | نامعتبر: "+QueueDb.get(this,"rubika_file_invalid","0")+
                " | تکراری: "+QueueDb.get(this,"rubika_file_duplicates","0"));
    }

    private void startQueue(){
        if(!confirmation.isChecked()){toast("تأیید رضایت گیرندگان لازم است");return;}
        if(QueueDb.items(this).length()==0){toast("ابتدا Excel / CSV را انتخاب کن");return;}
        final String text=message.getText().toString().trim();
        if(text.isEmpty()){toast("متن پیام را وارد کن");return;}
        int delay;
        try{delay=Integer.parseInt(minutes.getText().toString().trim());}catch(Exception e){delay=0;}
        if(delay<1||delay>1440){toast("فاصله باید ۱ تا ۱۴۴۰ دقیقه باشد");return;}
        final int selectedDelay=delay;
        network("بررسی کامل و START",()->{
            String ready=preflight();
            ui.post(()->preflightState.setText("آمادگی: "+ready));

            QueueDb.put(this,"direct_text",text);
            QueueDb.put(this,"direct_image_enabled","0");
            QueueDb.put(this,"delay_rubika",String.valueOf(selectedDelay));
            QueueDb.put(this,"direct_selected_bale","0");
            QueueDb.put(this,"direct_selected_rubika","1");
            QueueDb.put(this,"direct_selected_eitaa","0");
            QueueDb.put(this,"direct_running","1");
            Intent service=new Intent(this,DirectQueueService.class);
            service.setAction(DirectQueueService.ACTION_START);
            startForegroundService(service);
            ui.post(()->refreshQueue("START RUBIKA ثبت شد"));
            return "صف روبیکا شروع شد";
        });
    }
    private void stopQueue(){
        Intent i=new Intent(this,DirectQueueService.class);
        i.setAction(DirectQueueService.ACTION_PAUSE);
        try{startService(i);}catch(Exception ignored){}
        QueueDb.put(this,"direct_selected_rubika","0");
        QueueDb.put(this,"direct_running","0");
        if(Looper.myLooper()==Looper.getMainLooper())refreshQueue("STOP ثبت شد");
        else ui.post(()->refreshQueue("STOP ثبت شد"));
    }
    private void refreshQueue(String note){
        if(queueState!=null)queueState.setText(note+"\nدر انتظار: "+QueueDb.directCount(this,PROVIDER,"pending")+
                " | Submitted: "+QueueDb.directCount(this,PROVIDER,"submitted")+
                " | Review: "+QueueDb.directCount(this,PROVIDER,"review")+
                " | Failed: "+QueueDb.directCount(this,PROVIDER,"failed"));
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
