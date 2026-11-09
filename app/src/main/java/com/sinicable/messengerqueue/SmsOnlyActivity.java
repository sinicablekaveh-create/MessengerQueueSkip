package com.sinicable.messengerqueue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SMS Simple: import one Excel/CSV file, choose SIM, enter text and press START.
 * Selecting a new file replaces the previous local queue.
 */
public final class SmsOnlyActivity extends Activity {
    private static final int PICK_RECIPIENTS = 940;
    private static final int PERMISSIONS = 941;

    private EditText message;
    private EditText minutes;
    private TextView imported;
    private TextView sim;
    private TextView status;
    private CheckBox consent;
    private boolean pendingStart;

    private final Handler statusHandler=new Handler(Looper.getMainLooper());
    private final Runnable liveStatus=new Runnable(){
        @Override public void run(){
            refreshStatus();
            statusHandler.postDelayed(this,2000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28,24,28,36);
        scroll.addView(root);
        setContentView(scroll);

        text(root,"THREE IB • SMS SIMPLE "+BuildConfig.VERSION_NAME,23);
        text(root,"فقط Excel/CSV، سیم‌کارت، متن و START. فایل جدید صف قبلی را جایگزین می‌کند.",13);

        button(root,"1) انتخاب Excel / CSV",this::pickRecipients);
        imported=text(root,"",14);

        button(root,"2) انتخاب سیم‌کارت",this::chooseSim);
        sim=text(root,"",14);

        message=field(root,"3) متن پیامک",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setMinLines(4);
        message.setText(QueueDb.get(this,"message",""));

        minutes=field(root,"4) فاصله بین ارسال‌ها، ۱ تا ۱۴۴۰ دقیقه",InputType.TYPE_CLASS_NUMBER);
        minutes.setText(String.valueOf(QueueDb.delayMinutes(this,"SMS")));

        consent=new CheckBox(this);
        consent.setText("تأیید می‌کنم همه شماره‌های فایل انتخاب‌شده برای دریافت این پیام رضایت دارند.");
        root.addView(consent);

        button(root,"START SMS",this::startSms);
        button(root,"STOP",()->{
            QueueKeeperService.stop(this);
            refreshStatus();
            toast("صف SMS متوقف شد");
        });

        status=text(root,"",13);
        refreshImported();
        refreshStatus();
        AppStyle.decorate(this,getWindow().getDecorView());
    }

    private TextView text(LinearLayout root,String value,int size) {
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setPadding(0,8,0,8);
        root.addView(t);
        return t;
    }

    private EditText field(LinearLayout root,String hint,int type) {
        EditText e=new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        root.addView(e,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void button(LinearLayout root,String title,Runnable action) {
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(title);
        b.setOnClickListener(v->action.run());
        root.addView(b);
    }

    private void pickRecipients() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv",
                "text/comma-separated-values",
                "text/plain"
        });
        startActivityForResult(i,PICK_RECIPIENTS);
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_RECIPIENTS||result!=RESULT_OK||data==null||data.getData()==null)return;

        Uri uri=data.getData();
        new Thread(()->{
            try {
                String fileName=fileName(uri);
                String lower=fileName.toLowerCase(java.util.Locale.ROOT);
                if(!lower.endsWith(".xlsx")&&!lower.endsWith(".csv")) {
                    runOnUiThread(()->toast("فقط فایل .xlsx یا .csv انتخاب کن"));
                    return;
                }

                RecipientImportParser.Result parsed;
                try(InputStream input=getContentResolver().openInputStream(uri)) {
                    parsed=RecipientImportParser.read(input,fileName,true);
                }
                if(parsed.recipients.isEmpty()) {
                    runOnUiThread(()->toast("هیچ شماره موبایل معتبر در فایل پیدا نشد"));
                    return;
                }

                QueueKeeperService.stop(getApplicationContext());
                QueueDb.clear(getApplicationContext());
                int added=QueueDb.append(getApplicationContext(),parsed);
                QueueDb.put(getApplicationContext(),"sms_file_name",fileName);
                QueueDb.put(getApplicationContext(),"sms_file_invalid",String.valueOf(parsed.invalidRows));
                QueueDb.put(getApplicationContext(),"sms_file_duplicates",String.valueOf(parsed.duplicateRows));

                runOnUiThread(()->{
                    consent.setChecked(false);
                    refreshImported();
                    refreshStatus();
                    toast(added+" شماره معتبر آماده شد");
                });
            } catch(Exception error) {
                runOnUiThread(()->toast("خطا در خواندن Excel: "+error.getClass().getSimpleName()));
            }
        }).start();
    }

    private String fileName(Uri uri) {
        String name="contacts.xlsx";
        try(Cursor cursor=getContentResolver().query(uri,null,null,null,null)) {
            if(cursor!=null&&cursor.moveToFirst()) {
                int c=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(c>=0&&!cursor.isNull(c))name=cursor.getString(c);
            }
        } catch(Exception ignored){}
        return name==null||name.trim().isEmpty()?"contacts.xlsx":name.trim();
    }

    private void refreshImported() {
        if(imported==null)return;
        String file=QueueDb.get(this,"sms_file_name","انتخاب نشده");
        int count=QueueDb.items(this).length();
        String invalid=QueueDb.get(this,"sms_file_invalid","0");
        String duplicates=QueueDb.get(this,"sms_file_duplicates","0");
        imported.setText("Excel: "+file+"\nشماره معتبر: "+count+
                " | نامعتبر: "+invalid+" | تکراری: "+duplicates);
    }

    private void chooseSim() {
        if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            toast("این دستگاه قابلیت SMS سیم‌کارتی ندارد");
            return;
        }
        if(checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_PHONE_STATE},PERMISSIONS);
            return;
        }
        try {
            SubscriptionManager manager=(SubscriptionManager)getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> list=manager==null?null:manager.getActiveSubscriptionInfoList();
            if(list==null||list.isEmpty()) {
                toast("سیم‌کارت فعال پیدا نشد");
                return;
            }
            ArrayList<String> labels=new ArrayList<>();
            for(SubscriptionInfo info:list) {
                String carrier=info.getCarrierName()==null?"SIM":info.getCarrierName().toString();
                labels.add("SIM "+(info.getSimSlotIndex()+1)+" • "+carrier);
            }
            new AlertDialog.Builder(this)
                    .setTitle("انتخاب سیم‌کارت ارسال")
                    .setItems(labels.toArray(new String[0]),(d,which)->{
                        SubscriptionInfo info=list.get(which);
                        QueueDb.put(this,"sms_subscription_id",String.valueOf(info.getSubscriptionId()));
                        QueueDb.put(this,"sms_subscription_label",labels.get(which));
                        refreshStatus();
                    }).show();
        } catch(Exception error) {
            toast("انتخاب SIM در دسترس نیست: "+error.getClass().getSimpleName());
        }
    }

    private void startSms() {
        if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            toast("قابلیت SMS روی دستگاه موجود نیست");
            return;
        }
        if(QueueDb.items(this).length()==0) {
            toast("اول فایل Excel / CSV را انتخاب کن");
            return;
        }
        if(!consent.isChecked()) {
            toast("تأیید رضایت گیرندگان لازم است");
            return;
        }
        if(QueueDb.get(this,"sms_subscription_id","").isEmpty()) {
            toast("اول سیم‌کارت را انتخاب کن");
            return;
        }

        String body=message.getText().toString().trim();
        if(body.isEmpty()) {
            toast("متن پیامک را وارد کن");
            return;
        }

        int delay;
        try {delay=Integer.parseInt(minutes.getText().toString().trim());}
        catch(Exception e){delay=0;}
        if(delay<1||delay>1440) {
            toast("فاصله باید ۱ تا ۱۴۴۰ دقیقه باشد");
            return;
        }

        boolean notificationMissing=Build.VERSION.SDK_INT>=33&&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED;
        boolean smsMissing=
                checkSelfPermission(android.Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED||
                checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED;

        if(notificationMissing||smsMissing) {
            pendingStart=true;
            ArrayList<String> asks=new ArrayList<>();
            if(notificationMissing)asks.add(android.Manifest.permission.POST_NOTIFICATIONS);
            if(checkSelfPermission(android.Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED)
                asks.add(android.Manifest.permission.SEND_SMS);
            if(checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)
                asks.add(android.Manifest.permission.READ_PHONE_STATE);
            requestPermissions(asks.toArray(new String[0]),PERMISSIONS);
            return;
        }

        QueueDb.put(this,"message",body);
        QueueDb.put(this,"delay_sms",String.valueOf(delay));
        QueueDb.put(this,"enabled_bale","0");
        QueueDb.put(this,"enabled_rubika","0");
        QueueDb.put(this,"enabled_eitaa","0");
        QueueDb.put(this,"enabled_sms","1");
        QueueDb.put(this,"desired_paused","0");
        QueueDb.put(this,"desired_running","1");
        QueueDb.smsRecoverStale(this);
        if(QueueDb.smsDue(this)<=0)QueueDb.smsDue(this,System.currentTimeMillis());
        QueueKeeperService.start(this);
        refreshStatus();
        toast("صف SMS شروع شد");
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode!=PERMISSIONS)return;
        boolean granted=results.length>0;
        for(int value:results)if(value!=PackageManager.PERMISSION_GRANTED)granted=false;
        if(!granted) {
            pendingStart=false;
            toast("مجوز لازم داده نشد");
            return;
        }
        if(pendingStart) {
            pendingStart=false;
            startSms();
        } else chooseSim();
    }

    private void refreshStatus() {
        if(sim!=null)sim.setText("SIM: "+QueueDb.get(this,"sms_subscription_label","انتخاب نشده"));
        if(status!=null)status.setText(QueueDb.smsSummary(this));
    }

    @Override protected void onStart() {
        super.onStart();
        statusHandler.removeCallbacks(liveStatus);
        statusHandler.post(liveStatus);
    }

    @Override protected void onStop() {
        statusHandler.removeCallbacks(liveStatus);
        super.onStop();
    }

    private void toast(String value) {
        Toast.makeText(this,value,Toast.LENGTH_LONG).show();
    }
}
