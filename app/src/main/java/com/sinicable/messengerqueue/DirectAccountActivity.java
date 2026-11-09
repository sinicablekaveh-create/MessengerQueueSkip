package com.sinicable.messengerqueue;

import android.app.Activity;
import android.content.Context;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Standalone personal-account UX. No browser/app switching or Accessibility. */
public final class DirectAccountActivity extends Activity {
    private static final int PICK_CONTACTS=720, PICK_IMAGE=721;
    private static final String[] PROVIDERS={"bale","rubika","eitaa"};
    private static final String[] LABELS={"بله","روبیکا","ایتا"};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private EditText phone,code,password,message,minutes;
    private CheckBox confirmation,useImage;
    private CheckBox[] channels;
    private TextView current,summary,footer;
    private String selected="bale";
    private String fixedProvider="";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String target=BuildConfig.TARGET_CHANNEL;
        if("bale".equals(target)||"rubika".equals(target)||"eitaa".equals(target)){fixedProvider=target;selected=target;}
        // Recover uncertain submissions but NEVER send them twice automatically.
        EmbeddedGatewayService.ensureStarted(this);
        ScrollView scroll=new ScrollView(this);
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(28,24,28,44);
        scroll.addView(column);
        setContentView(scroll);

        TextView header=label(column,"THREE IB "+BuildConfig.VERSION_NAME+" • حساب شخصی",22);
        header.setTextColor(0xFF166346);
        label(column,"ورود با شماره و کد در همین برنامه؛ بدون بازکردن بله، روبیکا یا ایتای نصب‌شده. سرویس ارتباطی غیررسمی، محلی و قابل‌وقفه است.",13);

        label(column,"۱. ورود به حساب شخصی",18);
        LinearLayout apps=new LinearLayout(this);
        apps.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(apps);
        if(!fixedProvider.isEmpty())apps.setVisibility(android.view.View.GONE);
        for(int i=0;i<PROVIDERS.length;i++) {
            final String provider=PROVIDERS[i];
            Button b=new Button(this);
            b.setAllCaps(false);
            b.setText(LABELS[i]);
            b.setOnClickListener(v->select(provider));
            apps.addView(b,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        }

        current=label(column,"",14);
        phone=field(column,"شماره حساب شخصی، مانند 0912...",InputType.TYPE_CLASS_PHONE);
        code=field(column,"کد تأیید دریافت‌شده",InputType.TYPE_CLASS_NUMBER);
        code.setSaveEnabled(false);
        password=field(column,"رمز دومرحله‌ای (فقط در صورت درخواست)",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        button(column,"شروع ورود / دریافت کد",this::requestCode);
        button(column,"تأیید کد و ورود",this::verifyCode);
        button(column,"ثبت رمز دومرحله‌ای",this::verifyPassword);
        button(column,"بررسی وضعیت حساب",this::refreshAccount);

        label(column,"۲. فهرست گیرندگان و محتوا",18);
        button(column,"انتخاب فایل Excel / CSV مخاطبان",this::pickContacts);
        button(column,"مدیریت مخاطبان • ایجاد، ویرایش، حذف",()->startActivity(new Intent(this,RecipientEditorActivity.class)));
        label(column,"اکسل فقط با نام و شماره تماس پذیرفته می‌شود. نام خالی: بدون نام. برای START رضایت گیرندگان را تأیید کنید.",12);
        message=field(column,"متن پیام",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setMinLines(3);
        message.setText(QueueDb.get(this,"direct_text",""));
        button(column,"انتخاب تصویر اختیاری",this::pickImage);
        useImage=new CheckBox(this);
        useImage.setText("ارسال تصویر انتخاب‌شده همراه با متن");
        useImage.setChecked("1".equals(QueueDb.get(this,"direct_image_enabled","0")));
        column.addView(useImage);
        minutes=field(column,"فاصلهٔ ارسال برای پیام‌رسان انتخاب‌شده (۱ تا ۱۴۴۰ دقیقه)",InputType.TYPE_CLASS_NUMBER);
        minutes.setText(String.valueOf(QueueDb.delayMinutes(this,selected)));
        button(column,"ذخیرهٔ فاصله برای پیام‌رسان انتخاب‌شده",this::saveSelectedDelay);

        label(column,"۳. صف مستقل ارسال",18);
        channels=new CheckBox[3];
        for(int i=0;i<PROVIDERS.length;i++){
            channels[i]=new CheckBox(this);
            channels[i].setText("فعال‌کردن صف "+LABELS[i]);
            channels[i].setChecked("1".equals(QueueDb.get(this,"direct_selected_"+PROVIDERS[i],"0")));
            if(!fixedProvider.isEmpty()){
                boolean isFixed=fixedProvider.equals(PROVIDERS[i]);
                channels[i].setChecked(isFixed);
                channels[i].setEnabled(false);
                if(!isFixed)channels[i].setVisibility(android.view.View.GONE);
                QueueDb.put(this,"direct_selected_"+PROVIDERS[i],isFixed?"1":"0");
            }
            final int index=i;
            channels[i].setOnCheckedChangeListener((view,checked)->QueueDb.put(this,
                    "direct_selected_"+PROVIDERS[index],checked?"1":"0"));
            column.addView(channels[i]);
        }
        confirmation=new CheckBox(this);
        confirmation.setText("تأیید می‌کنم گیرندگان ردیف‌به‌ردیف به دریافت این پیام رضایت داده‌اند.");
        column.addView(confirmation);
        Button start=new Button(this);
        start.setAllCaps(false);
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF07865B));
        start.setTextColor(0xFFFFFFFF);
        start.setText("START AUTOMATIC • آغاز صف مستقل");
        start.setOnClickListener(v->startQueue());
        column.addView(start);
        button(column,"PAUSE / توقف صف",this::pauseQueue);
        button(column,"پاک‌کردن پیام‌های ارسال‌نشدهٔ صف‌های انتخاب‌شده",this::clearPending);
        button(column,"تازه‌سازی آمار صف",()->updateSummary("وضعیت صف"));

        summary=label(column,"",13);
        footer=label(column,"درگاه حساب محلی و غیررسمی است. ارسال موفق سرور لزوماً تحویل یا خوانده‌شدن توسط گیرنده نیست. در صورت پاسخ نامشخص، مورد به REVIEW می‌رود.",12);
        select(fixedProvider.isEmpty() ? "bale" : fixedProvider);
        updateSummary("برنامه آماده است؛ وضعیت سرور هنگام ورود بررسی می‌شود.");
        AppStyle.decorate(this,getWindow().getDecorView());
    }

    private TextView label(LinearLayout root,String value,int size){
        TextView text=new TextView(this);
        text.setText(value);text.setTextSize(size);text.setPadding(2,9,2,9);
        root.addView(text);
        return text;
    }
    private EditText field(LinearLayout root,String hint,int type){
        EditText item=new EditText(this);item.setHint(hint);item.setInputType(type);
        root.addView(item);
        return item;
    }
    private void button(LinearLayout root,String title,Runnable task){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);
        b.setOnClickListener(v->task.run());root.addView(b);
    }
    private void select(String provider){
        if(!fixedProvider.isEmpty()&&!fixedProvider.equals(provider))return;
        if(minutes!=null && !selected.equals(provider)) saveSelectedDelayQuiet();
        selected=provider;
        int index=0;
        for(int i=0;i<PROVIDERS.length;i++)if(PROVIDERS[i].equals(provider))index=i;
        current.setText("حساب انتخاب‌شده: "+LABELS[index]+" | ورود با شماره و OTP");
        String previous=QueueDb.get(this,"direct_phone_"+provider,"");
        phone.setText(previous);
        code.setText("");password.setText("");
        minutes.setText(String.valueOf(QueueDb.delayMinutes(this,provider)));
    }
    private void saveSelectedDelayQuiet(){
        try{
            int value=Integer.parseInt(minutes.getText().toString().trim());
            if(value>=1&&value<=1440)QueueDb.put(this,"delay_"+selected,String.valueOf(value));
        }catch(Exception ignored){}
    }
    private void saveSelectedDelay(){
        try{
            int value=Integer.parseInt(minutes.getText().toString().trim());
            if(value<1||value>1440)throw new NumberFormatException();
            QueueDb.put(this,"delay_"+selected,String.valueOf(value));
            toast("فاصلهٔ "+selected+" ثبت شد");
        }catch(Exception e){toast("فاصله باید ۱ تا ۱۴۴۰ دقیقه باشد");}
    }

    private interface Task {String run() throws Exception;}
    private void network(String verb,Task task){
        footer.setText("در حال "+verb+" ...");
        worker.execute(()->{
            String result;
            try{result=task.run();}
            catch(Exception error){result="خطا: "+safeError(error);}
            final String label=result;
            ui.post(()->footer.setText(label));
        });
    }
    private static String safeError(Exception error){
        // Return class and terse message only; never print OTPs or account credentials.
        String message=error.getMessage();
        if(message==null)message="";
        return error.getClass().getSimpleName()+": "+(message.length()>130?message.substring(0,130):message);
    }

    private void waitForGateway()throws Exception{
        for(int i=0;i<16;i++){
            if(!EmbeddedGatewayService.credential(this).isEmpty()){
                try{
                    GatewayClient.Reply response=GatewayClient.call(this,"GET","/app/providers",null,null,null,null);
                    if(response.success())return;
                }catch(Exception ignored){}
            }
            Thread.sleep(650);
        }
        throw new IllegalStateException("درگاه محلی هنوز آماده نیست؛ وضعیت سرویس را بررسی کنید");
    }

    private String instance(String provider){
        return QueueDb.get(this,"direct_instance_"+provider,"");
    }
    private JSONObject account(String provider)throws Exception{
        String alias=GatewayClient.alias(provider);
        String inst=instance(provider);
        GatewayClient.Reply response=GatewayClient.call(this,"GET",
                "/devices/"+alias+"/status",null,alias,inst.isEmpty()?null:inst,null);
        if(!response.success())throw new IllegalStateException(response.summary());
        return response.result();
    }

    private JSONObject loginState(String provider)throws Exception{
        String alias=GatewayClient.alias(provider);
        String inst=instance(provider);
        if(inst.isEmpty())throw new IllegalStateException("حساب هنوز ساخته نشده است");
        GatewayClient.Reply response=GatewayClient.call(this,"GET",
                "/devices/"+alias+"/login",null,alias,inst,null);
        if(!response.success())throw new IllegalStateException(response.summary());
        return response.result();
    }

    private String loginStepText(JSONObject state){
        String phase=state.optString("state","");
        JSONObject challenge=state.optJSONObject("challenge");
        if(challenge==null)challenge=state;
        String delivery=challenge.optString("delivery","نامشخص");
        if("authenticated".equals(phase))return "ورود حساب کامل شد.";
        if("awaiting_code".equals(phase))
            return "کد تأیید لازم است. روش تحویل: "+delivery+" | کد را وارد و «تأیید کد و ورود» را بزن.";
        if("awaiting_password".equals(phase))
            return "رمز دومرحله‌ای لازم است. رمز را وارد و «ثبت رمز دومرحله‌ای» را بزن.";
        return "مرحلهٔ ورود: "+(phase.isEmpty()?"نامشخص":phase);
    }

    private JSONObject currentChallenge(String provider,String expectedState)throws Exception{
        JSONObject state=loginState(provider);
        String phase=state.optString("state","");
        if("authenticated".equals(phase))
            throw new IllegalStateException("حساب قبلاً وارد شده است");
        if(!expectedState.equals(phase))
            throw new IllegalStateException(loginStepText(state));
        JSONObject challenge=state.optJSONObject("challenge");
        if(challenge==null||challenge.optString("challenge_id","").isEmpty())
            throw new IllegalStateException("شناسهٔ چالش ورود در دسترس نیست");
        return challenge;
    }

    private static boolean contains(JSONArray values,String wanted){
        if(values==null)return false;
        for(int i=0;i<values.length();i++)if(wanted.equals(values.optString(i)))return true;
        return false;
    }

    private static boolean hasLiveEvidence(JSONObject evidence){
        if(evidence==null)return false;
        String level=evidence.optString("level","");
        return "live_scoped".equals(level)||"live_response".equals(level);
    }

    private void assertProviderReady(String provider,String text,boolean image)throws Exception{
        GatewayClient.Reply reply=GatewayClient.call(this,"GET","/app/providers",null,null,null,null);
        if(!reply.success())throw new IllegalStateException(reply.summary());
        JSONArray providers=reply.resultArray();
        if(providers==null)throw new IllegalStateException("فهرست قابلیت‌های پیام‌رسان در دسترس نیست");
        JSONObject descriptor=null;
        for(int i=0;i<providers.length();i++){
            JSONObject row=providers.optJSONObject(i);
            if(row!=null&&provider.equals(row.optString("id",""))){descriptor=row;break;}
        }
        if(descriptor==null||!descriptor.optBoolean("enabled",false))
            throw new IllegalStateException("درگاه "+provider+" در این ساخت فعال نیست");
        if(!hasLiveEvidence(descriptor.optJSONObject("evidence")))
            throw new IllegalStateException(provider+" در این ساخت شاهد زندهٔ کافی برای provider ندارد");
        JSONObject send=descriptor.optJSONObject("send");
        JSONArray kinds=send==null?null:send.optJSONArray("kinds");
        JSONObject sendEvidence=send==null?null:send.optJSONObject("evidence");
        if(!text.trim().isEmpty()&&!contains(kinds,"text"))
            throw new IllegalStateException(provider+" در این ساخت ارسال متن را اعلام نکرده است");
        if(!text.trim().isEmpty()&&(sendEvidence==null||!hasLiveEvidence(sendEvidence.optJSONObject("text"))))
            throw new IllegalStateException(provider+" شاهد زندهٔ کافی برای ارسال متن ندارد");
        if(image&&!contains(kinds,"image"))
            throw new IllegalStateException(provider+" در این ساخت ارسال تصویر را اعلام نکرده است");
        if(image&&(sendEvidence==null||!hasLiveEvidence(sendEvidence.optJSONObject("image"))))
            throw new IllegalStateException(provider+" شاهد زندهٔ کافی برای ارسال تصویر ندارد");
        if(send!=null){
            int maxChars=send.optInt("max_text_characters",0);
            if(maxChars>0&&text.codePointCount(0,text.length())>maxChars)
                throw new IllegalStateException("متن از سقف "+maxChars+" کاراکتر "+provider+" بیشتر است");
            int maxBytes=send.optInt("max_text_bytes",0);
            if(maxBytes>0&&text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>maxBytes)
                throw new IllegalStateException("متن از سقف بایتی "+provider+" بیشتر است");
        }
        if("rubika".equals(provider)){
            String alias=GatewayClient.alias(provider);
            GatewayClient.Reply caps=GatewayClient.call(this,"GET",
                    "/devices/"+alias+"/capabilities",null,alias,instance(provider),null);
            if(!caps.success())throw new IllegalStateException(caps.summary());
            JSONArray operations=caps.resultArray();
            boolean contactResolve=false;
            boolean contactResolveLive=false;
            if(operations!=null)for(int i=0;i<operations.length();i++){
                JSONObject op=operations.optJSONObject(i);
                if(op!=null&&"contacts.add".equals(op.optString("operation",""))
                        &&"mutation".equals(op.optString("mode",""))){
                    contactResolve=true;
                    contactResolveLive=hasLiveEvidence(op.optJSONObject("evidence"));
                    break;
                }
            }
            if(!contactResolve)
                throw new IllegalStateException("Rubika contacts.add برای تبدیل شماره به شناسهٔ کاربر در دسترس نیست");
            if(!contactResolveLive)
                throw new IllegalStateException("Rubika contacts.add شاهد زندهٔ ثبت‌شدهٔ کافی ندارد");
            JSONObject accountBody=new JSONObject();
            GatewayClient.Reply accountInfo=GatewayClient.call(this,"POST",
                    "/operations/account.info",accountBody,alias,instance(provider),null);
            if(!accountInfo.success())
                throw new IllegalStateException("Rubika account.info ناموفق بود: "+accountInfo.summary());
            String liveGuid=accountInfo.result().optString("user_guid","");
            JSONObject status=account(provider);
            String boundGuid=status.optString("account_id","");
            if(!liveGuid.matches("^u0[A-Za-z0-9]{1,126}$"))
                throw new IllegalStateException("Rubika account.info شناسهٔ معتبر برنگرداند");
            if(!boundGuid.isEmpty()&&!liveGuid.equals(boundGuid))
                throw new IllegalStateException("شناسهٔ حساب زنده با اتصال ذخیره‌شده همخوان نیست");
        }
    }
    private String provision(String provider)throws Exception{
        String saved=instance(provider);
        if(!saved.isEmpty())return saved;
        JSONObject payload=new JSONObject();
        payload.put("device_id",GatewayClient.alias(provider));
        payload.put("provider",provider);
        String key=QueueDb.get(this,"direct_provision_key_"+provider,"");
        if(key.isEmpty()){
            key="threeib-provision-"+provider+"-"+UUID.randomUUID();
            QueueDb.put(this,"direct_provision_key_"+provider,key);
        }
        GatewayClient.Reply response=GatewayClient.call(this,"POST","/devices",payload,null,null,key);
        if(!response.success())throw new IllegalStateException(response.summary());
        String inst=response.result().optString("instance_id","");
        if(inst.isEmpty())throw new IllegalStateException("Backend did not provide an account instance");
        QueueDb.put(this,"direct_instance_"+provider,inst);
        return inst;
    }

    private void requestCode(){
        final String provider=selected;
        final String number=GatewayClient.normalizedPhone(phone.getText().toString());
        if(number.isEmpty()){toast("شماره معتبر وارد کن");return;}
        network("شروع ورود "+provider,()->{
            waitForGateway();
            String inst=provision(provider);
            JSONObject body=new JSONObject();body.put("phone",number);
            GatewayClient.Reply reply=GatewayClient.call(this,"POST",
                "/devices/"+GatewayClient.alias(provider)+"/login",body,
                GatewayClient.alias(provider),inst,null);
            if(!reply.success())throw new IllegalStateException(reply.summary());
            QueueDb.put(this,"direct_phone_"+provider,number);
            JSONObject challenge=reply.result();
            String id=challenge.optString("challenge_id","");
            String phase=challenge.optString("state","");
            if(id.isEmpty()&&!"authenticated".equals(phase))
                throw new IllegalStateException("پاسخ ورود شناسهٔ چالش نداشت");
            return loginStepText(challenge);
        });
    }

    private void verifyCode(){
        final String provider=selected;
        final String otp=code.getText().toString().trim();
        if(!otp.matches("[0-9]{4,9}")){toast("کد تأیید معتبر وارد کن");return;}
        code.setText("");
        network("تأیید کد",()->{
            waitForGateway();
            JSONObject challenge=currentChallenge(provider,"awaiting_code");
            JSONObject body=new JSONObject();
            body.put("challenge_id",challenge.optString("challenge_id",""));
            body.put("code",otp);
            GatewayClient.Reply reply=GatewayClient.call(this,"POST",
                "/devices/"+GatewayClient.alias(provider)+"/login/code",body,
                GatewayClient.alias(provider),instance(provider),null);
            if(!reply.success()&&!"PASSWORD_REQUIRED".equals(reply.code()))
                throw new IllegalStateException(reply.summary());
            if(reply.success()&&"authenticated".equals(reply.result().optString("auth","")))
                return "ورود حساب کامل شد؛ حساب آمادهٔ START است.";
            return loginStepText(loginState(provider));
        });
    }

    private void verifyPassword(){
        final String provider=selected;
        final String secret=password.getText().toString();
        if(secret.isEmpty()){toast("رمز دومرحله‌ای را وارد کن");return;}
        password.setText("");
        network("تأیید رمز دومرحله‌ای",()->{
            waitForGateway();
            JSONObject challenge=currentChallenge(provider,"awaiting_password");
            JSONObject payload=new JSONObject();
            payload.put("challenge_id",challenge.optString("challenge_id",""));
            payload.put("password",secret);
            GatewayClient.Reply reply=GatewayClient.call(this,"POST",
                    "/devices/"+GatewayClient.alias(provider)+"/login/password",
                    payload,GatewayClient.alias(provider),instance(provider),null);
            if(!reply.success())throw new IllegalStateException(reply.summary());
            if("authenticated".equals(reply.result().optString("auth","")))
                return "ورود حساب کامل شد؛ حساب آمادهٔ START است.";
            return loginStepText(loginState(provider));
        });
    }

    private void refreshAccount(){
        final String provider=selected;
        network("خواندن وضعیت حساب",()->{
            waitForGateway();
            JSONObject state=account(provider);
            return provider+" | auth="+state.optString("auth","?")+
                " | transport="+state.optString("transport","?")+
                " | recovery="+state.optString("recovery","?");
        });
    }

    private void pickContacts(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");
        startActivityForResult(intent,PICK_CONTACTS);
    }
    private void pickImage(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/*");
        startActivityForResult(intent,PICK_IMAGE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        final Uri uri=data.getData();
        if(request==PICK_CONTACTS){
            network("واردکردن مخاطبان",()->{
                String name="recipients.csv";
                try(Cursor cursor=getContentResolver().query(uri,null,null,null,null)){
                    if(cursor!=null&&cursor.moveToFirst()){
                        int column=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if(column>=0&&!cursor.isNull(column))name=cursor.getString(column);
                    }
                }
                try(InputStream input=getContentResolver().openInputStream(uri)){
                    RecipientImportParser.Result parsed=RecipientImportParser.read(input,name);
                    int added=QueueDb.append(getApplicationContext(),parsed);
                    ui.post(()->updateSummary("تعداد شماره‌های غیرتکراری واردشده: "+added));
                    return added+" مخاطب وارد شد. بدون opt_in=true ارسال ممنوع است.";
                }
            });
        } else if(request==PICK_IMAGE){
            final String mime=getContentResolver().getType(uri);
            if(!"image/jpeg".equals(mime)&&!"image/png".equals(mime)){
                toast("فعلاً فقط JPG و PNG پشتیبانی می‌شوند");return;
            }
            network("ذخیرهٔ امن تصویر",()->{
                File dest=new File(getFilesDir(),"direct-image");
                long size=0;
                try(InputStream input=getContentResolver().openInputStream(uri);
                    FileOutputStream output=new FileOutputStream(dest)){
                    if(input==null)throw new IllegalStateException("Image unavailable");
                    byte[] buf=new byte[32768];int n;
                    while((n=input.read(buf))!=-1){
                        size+=n;
                        if(size>6_000_000L)throw new IllegalArgumentException("تصویر بیشتر از ۶ مگابایت است");
                        output.write(buf,0,n);
                    }
                }catch(Exception error){dest.delete();throw error;}
                QueueDb.put(this,"direct_image_mime",mime);
                QueueDb.put(this,"direct_image_enabled","1");
                ui.post(()->useImage.setChecked(true));
                return "تصویر ذخیره شد: "+size+" بایت";
            });
        }
    }

    private void startQueue(){
        if(!confirmation.isChecked()){toast("تأیید رضایت گیرندگان ضروری است");return;}
        String text=message.getText().toString().trim();
        boolean image=useImage.isChecked();
        if(text.isEmpty()&&!image){toast("متن یا تصویر وارد کن");return;}
        if(image&&!new File(getFilesDir(),"direct-image").isFile()){
            toast("ابتدا تصویر را انتخاب کن");return;
        }
        QueueDb.put(this,"direct_text",text);
        QueueDb.put(this,"direct_image_enabled",image?"1":"0");
        saveSelectedDelayQuiet();
        // Capture UI choices before the asynchronous account check.
        final boolean[] selectedSnapshot=new boolean[3];
        StringBuilder selectedNames=new StringBuilder();
        for(int i=0;i<3;i++){
            String provider=PROVIDERS[i];
            selectedSnapshot[i]=channels[i].isChecked();
            QueueDb.put(this,"direct_selected_"+provider,selectedSnapshot[i]?"1":"0");
            if(selectedSnapshot[i])selectedNames.append(provider).append(" ");
        }
        if(selectedNames.length()==0){toast("حداقل یک پیام‌رسان را انتخاب کن");return;}
        boolean anyPending=false;
        for(int i=0;i<3;i++)if(selectedSnapshot[i] &&
                QueueDb.directCount(this,PROVIDERS[i],"pending")>0)anyPending=true;
        if(!anyPending){toast("مخاطب رضایت‌دادهٔ در انتظار ارسال وجود ندارد");return;}
        network("بررسی حساب‌ها و شروع صف",()->{
            waitForGateway();
            for(int i=0;i<3;i++)if(selectedSnapshot[i]){
                String p=PROVIDERS[i];
                if(instance(p).isEmpty())throw new IllegalStateException("برای "+LABELS[i]+" ابتدا با شماره و کد وارد شو");
                JSONObject state=account(p);
                if(!"authenticated".equals(state.optString("auth")) ||
                   !"connected".equals(state.optString("transport"))){
                    throw new IllegalStateException("حساب "+LABELS[i]+" وارد نشده: "+state.optString("auth"));
                }
                assertProviderReady(p,text,image);
            }
            Intent service=new Intent(this,DirectQueueService.class);
            service.setAction(DirectQueueService.ACTION_START);
            startForegroundService(service);
            ui.post(()->updateSummary("صف مستقل شروع شد. جزئیات در اعلان دستگاه."));
            return "START ثبت شد؛ صف در سرویس پیش‌زمینه اجرا می‌شود.";
        });
    }
    private void pauseQueue(){
        Intent intent=new Intent(this,DirectQueueService.class);
        intent.setAction(DirectQueueService.ACTION_PAUSE);
        startService(intent);
        updateSummary("درخواست توقف صف ثبت شد");
    }
    private void clearPending(){
        pauseQueue();
        for(int i=0;i<3;i++)if(channels[i].isChecked())
            QueueDb.directClearPending(this,PROVIDERS[i]);
        updateSummary("فقط موارد ارسال‌نشدهٔ کانال‌های انتخاب‌شده حذف شدند؛ REVIEW حفظ شد.");
    }
    private void updateSummary(String note){
        StringBuilder out=new StringBuilder(note).append("\n");
        for(int i=0;i<3;i++){
            String p=PROVIDERS[i];
            out.append(LABELS[i]).append(": انتظار ")
                    .append(QueueDb.directCount(this,p,"pending"))
                    .append(" | پذیرفته‌شده ")
                    .append(QueueDb.directCount(this,p,"submitted"))
                    .append(" | بررسی ")
                    .append(QueueDb.directCount(this,p,"review")).append("\n");
        }
        if(summary!=null)summary.setText(out.toString());
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
