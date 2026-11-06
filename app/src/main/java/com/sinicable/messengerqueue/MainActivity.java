package com.sinicable.messengerqueue;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int FILE_PICKER=512;
    private static final int IMAGE_PICKER=513;
    private final Handler handler=new Handler(Looper.getMainLooper());

    private EditText message,accountPhone,baleDelay,rubikaDelay,eitaaDelay,smsDelay;
    private CheckBox approved,addContacts,keepAwake,baleEnabled,rubikaEnabled,eitaaEnabled,smsEnabled;
    private TextView status,logs,access,reliability,imageLabel,readiness,smsSimLabel;
    private String imageUri="";

    private final Runnable ticker=new Runnable(){
        @Override public void run(){refresh();handler.postDelayed(this,1500);}
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        QueueDb.sanitizeStoredLogs(this);

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10,16,29));
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(23),dp(20),dp(24));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(label("MESSENGER QUEUE SKIP",26,Color.WHITE,true));
        root.addView(label("Bale • Rubika • Eitaa • SMS SIM • row-level consent • persistent START",13,0xFFBAC8DB,false));

        space(root,12);
        access=label("",13,0xFFFFCE75,true);root.addView(access);
        addButton(root,"1  ENABLE ACCESSIBILITY",0xFF365FCE,()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        reliability=label("",12,0xFFBFD1E6,false);root.addView(reliability);
        readiness=label("",14,0xFFFFD58A,true);root.addView(readiness);
        addButton(root,"MAX POWER: BATTERY EXEMPTION",0xFF405F80,()->requestBatteryExemption());
        addButton(root,"RELIABILITY / BATTERY SETTINGS",0xFF405F80,()->{
            try {startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
            catch(Exception e){startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));}
        });
        addButton(root,"SAMSUNG: NEVER SLEEPING APPS",0xFF405F80,()->{
            try {
                Intent i=new Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY");
                i.setPackage("com.samsung.android.lool");
                i.putExtra("activity_type",2);
                startActivity(i);
            } catch(Exception e) {
                try {startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
                catch(Exception ignored){}
            }
        });

        space(root,12);
        root.addView(label("2  YOUR ACCOUNT PHONE",16,Color.WHITE,true));
        root.addView(label("Reference only. Sign in once inside Bale, Rubika and Eitaa themselves.",12,0xFF9DB0C7,false));
        accountPhone=textBox(1,"09xxxxxxxxx");
        accountPhone.setText(QueueDb.get(this,"account_phone",""));
        root.addView(accountPhone,lp(-1,dp(52)));
        LinearLayout opens=new LinearLayout(this);opens.setOrientation(LinearLayout.HORIZONTAL);
        addSmallButton(opens,"OPEN BALE",0xFF425A85,()->openApp("Bale"));
        addSmallButton(opens,"RUBIKA",0xFF425A85,()->openApp("Rubika"));
        addSmallButton(opens,"EITAA",0xFF425A85,()->openApp("Eitaa"));
        root.addView(opens);

        space(root,12);
        root.addView(label("3  IMPORT RECIPIENTS",17,Color.WHITE,true));
        addButton(root,"IMPORT .XLSX / .CSV",0xFF294861,()->{
            Intent in=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            in.setType("*/*");in.addCategory(Intent.CATEGORY_OPENABLE);
            in.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/csv","application/csv","application/octet-stream"});
            startActivityForResult(in,FILE_PICKER);
        });
        status=label("",13,0xFFC7D4E4,true);root.addView(status);

        space(root,14);
        root.addView(label("4  CHANNEL QUEUES",17,Color.WHITE,true));
        baleEnabled=queueToggle(root,"BALE", "bale",0xFF2D8FCE);
        baleDelay=delayBox(root,"Bale delay (minutes)","bale");
        rubikaEnabled=queueToggle(root,"RUBIKA","rubika",0xFF9255D6);
        rubikaDelay=delayBox(root,"Rubika delay (minutes)","rubika");
        eitaaEnabled=queueToggle(root,"EITAA","eitaa",0xFF1B9A74);
        eitaaDelay=delayBox(root,"Eitaa delay (minutes)","eitaa");
        smsEnabled=queueToggle(root,"SMS SIM","sms",0xFF40A6A6);
        smsDelay=delayBox(root,"SMS delay (minutes)","sms");
        smsSimLabel=label("",12,0xFFC7D4E4,true);root.addView(smsSimLabel);
        addButton(root,"SELECT SMS SIM",0xFF376C78,()->chooseSmsSim());
        root.addView(label("SMS is text-only. Rows must contain opt_in=true and send_sms=true. Carrier charges may apply.",12,0xFFFFD58A,true));
        refreshSmsSimLabel();

        space(root,14);
        root.addView(label("5  MESSAGE",17,Color.WHITE,true));
        message=textBox(4,"Write message to opted-in recipients...");
        message.setText(QueueDb.get(this,"message",""));
        root.addView(message,lp(-1,dp(130)));

        root.addView(label("OPTIONAL IMAGE",14,0xFFBECEDF,true));
        imageLabel=label("",12,0xFFC7D4E4,false);root.addView(imageLabel);
        addButton(root,"SELECT IMAGE",0xFF455C88,()->{
            Intent in=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            in.setType("image/*");in.addCategory(Intent.CATEGORY_OPENABLE);
            in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(in,IMAGE_PICKER);
        });
        addButton(root,"REMOVE IMAGE",0xFF3E5067,()->{
            imageUri="";
            QueueDb.put(this,"image_uri","");
            QueueDb.put(this,"image_name","");
            QueueDb.put(this,"image_mime","image/*");
            QueueDb.put(this,"reliability_gate_last","STALE");
            refreshImageLabel();
        });
        imageUri=QueueDb.get(this,"image_uri","");
        refreshImageLabel();

        approved=new CheckBox(this);
        approved.setText("I confirm these recipients agreed to receive this message.");
        approved.setTextColor(0xFFD1DFE9);root.addView(approved);
        addContacts=new CheckBox(this);
        addContacts.setText("Create uniquely named local contacts when needed for messenger search.");
        addContacts.setTextColor(0xFFD1DFE9);
        addContacts.setChecked("1".equals(QueueDb.get(this,"addContacts","0")));
        root.addView(addContacts);

        keepAwake=new CheckBox(this);
        keepAwake.setText("Keep CPU/screen awake while automatic queues are running.");
        keepAwake.setTextColor(0xFFD1DFE9);
        keepAwake.setChecked("1".equals(QueueDb.get(this,"keep_awake","1")));
        applyKeepScreenOn(keepAwake.isChecked());
        keepAwake.setOnCheckedChangeListener((button,checked)->{
            QueueDb.put(this,"keep_awake",checked?"1":"0");
            applyKeepScreenOn(checked);
        });
        root.addView(keepAwake);

        root.addView(label("HIGH RELIABILITY GATE: START first performs a no-send dry-run in every enabled messenger. Search, recipient match, composer, Send control, and optional image-share route must all pass before any real queue is released.",12,0xFFFFD58A,true));

        addButton(root,"▶  START AUTOMATIC",0xFF00A768,()->start(false));
        addButton(root,"▶  TEST ONE QUEUE TASK",0xFF285C96,()->start(true));
        addButton(root,"Ⅱ  PAUSE / RESUME ALL",0xFFF1B950,()->{
            QueueAccessibilityService s=QueueAccessibilityService.INSTANCE;
            if(s!=null)s.togglePause();
            else {
                boolean paused="1".equals(QueueDb.get(this,"desired_paused","0"));
                QueueDb.put(this,"desired_paused",paused?"0":"1");
                QueueDb.log(this,paused?"SMS queue resumed":"SMS queue paused");
            }
            refresh();
        });
        addButton(root,"■  STOP ALL QUEUES",0xFFE06150,()->{
            QueueAccessibilityService s=QueueAccessibilityService.INSTANCE;
            if(s!=null)s.stopQueue();
            QueueKeeperService.stop(this);
            toast("Automation stopped. Pending items retained.");refresh();
        });
        addButton(root,"🗑  CLEAR ALL QUEUES",0xFF3E5067,()->new android.app.AlertDialog.Builder(this)
                .setTitle("Clear all queues?")
                .setMessage("Erase all recipient queue states and logs?")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Clear",(d,w)->{
                    QueueAccessibilityService s=QueueAccessibilityService.INSTANCE;
                    if(s!=null)s.stopQueue();
                    QueueKeeperService.stop(this);
                    QueueDb.clear(this);refresh();
                }).show());

        space(root,8);
        root.addView(label("LIVE LOG",17,Color.WHITE,true));
        logs=label("",12,0xFFD9E3F0,false);
        logs.setTypeface(Typeface.MONOSPACE);
        logs.setPadding(dp(12),dp(12),dp(12),dp(12));
        logs.setBackground(bg(0xFF19283D,10));root.addView(logs);
        addButton(root,"COPY REDACTED LOG",0xFF405F80,()->{
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MQS log",QueueDb.get(this,"logs","")));
            toast("Queue log copied.");
        });
        root.addView(label("Text is verified from the visible chat UI. Optional image is sent through Android's standard share intent and the messenger's Send UI. REVIEW means the app avoided a blind duplicate.",12,0xFFAEBCD0,false));
        refresh();
    }

    private CheckBox queueToggle(LinearLayout root,String title,String key,int color){
        CheckBox c=new CheckBox(this);
        c.setText(title+" queue enabled");
        c.setTextColor(color);
        c.setTextSize(16);
        c.setChecked("1".equals(QueueDb.get(this,"enabled_"+key,"sms".equals(key)?"0":"1")));
        root.addView(c);
        return c;
    }
    private EditText delayBox(LinearLayout root,String hint,String key){
        EditText e=textBox(1,hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(QueueDb.get(this,"delay_"+key,"3"));
        root.addView(e,lp(-1,dp(50)));
        return e;
    }
    private EditText textBox(int lines,String hint){
        EditText e=new EditText(this);
        e.setTextColor(Color.WHITE);e.setHintTextColor(0xFF8097AE);
        e.setTextSize(16);e.setMinLines(lines);
        if(lines>1)e.setGravity(android.view.Gravity.TOP);
        e.setHint(hint);e.setBackground(bg(0xFF1B2A3F,10));
        e.setPadding(dp(12),dp(10),dp(12),dp(10));
        return e;
    }

    private void start(boolean testOne){
        if(!approved.isChecked()){toast("Confirm this send session first; row-level opt_in is also required");return;}
        if(message.getText().toString().trim().isEmpty()){toast("Enter message text");return;}
        boolean messengerOn=baleEnabled.isChecked()||rubikaEnabled.isChecked()||eitaaEnabled.isChecked();
        boolean smsOn=smsEnabled.isChecked();
        if(!messengerOn&&!smsOn){toast("Enable at least one queue");return;}
        if(testOne&&smsOn){toast("TEST ONE currently covers messenger UI queues only. Disable SMS for this test.");return;}
        if(QueueDb.items(this).length()==0){toast("Import a headered phone,opt_in Excel/CSV first");return;}
        if(smsOn&&QueueDb.get(this,"sms_subscription_id","").isEmpty()){toast("Select the SMS SIM first");return;}

        int bd=parseDelay(baleDelay),rd=parseDelay(rubikaDelay),ed=parseDelay(eitaaDelay),sd=parseDelay(smsDelay);
        if(bd<1||rd<1||ed<1||sd<1){toast("Each delay must be 1 to 1440 minutes");return;}

        StringBuilder missing=new StringBuilder();
        if(baleEnabled.isChecked()&&!isInstalled(packageFor("Bale")))missing.append("Bale ");
        if(rubikaEnabled.isChecked()&&!isInstalled(packageFor("Rubika")))missing.append("Rubika ");
        if(eitaaEnabled.isChecked()&&!isInstalled(packageFor("Eitaa")))missing.append("Eitaa ");
        if(missing.length()>0){toast("Install and sign in first: "+missing.toString().trim());return;}

        if(!imageUri.isEmpty()){
            try {
                java.io.InputStream in=getContentResolver().openInputStream(Uri.parse(imageUri));
                if(in!=null)in.close();
                String mime=QueueDb.get(this,"image_mime","image/*");
                StringBuilder noShare=new StringBuilder();
                if(baleEnabled.isChecked()&&!canShareImage(packageFor("Bale"),mime))noShare.append("Bale ");
                if(rubikaEnabled.isChecked()&&!canShareImage(packageFor("Rubika"),mime))noShare.append("Rubika ");
                if(eitaaEnabled.isChecked()&&!canShareImage(packageFor("Eitaa"),mime))noShare.append("Eitaa ");
                if(noShare.length()>0){toast("Image share route unavailable: "+noShare.toString().trim());return;}
            } catch(Exception e){toast("Selected image is no longer readable. Select it again.");return;}
        }

        QueueDb.put(this,"account_phone",accountPhone.getText().toString().trim());
        QueueDb.put(this,"message",message.getText().toString());
        QueueDb.put(this,"image_uri",imageUri);
        QueueDb.put(this,"addContacts",addContacts.isChecked()?"1":"0");
        QueueDb.put(this,"keep_awake",keepAwake.isChecked()?"1":"0");
        QueueDb.put(this,"enabled_bale",baleEnabled.isChecked()?"1":"0");
        QueueDb.put(this,"enabled_rubika",rubikaEnabled.isChecked()?"1":"0");
        QueueDb.put(this,"enabled_eitaa",eitaaEnabled.isChecked()?"1":"0");
        QueueDb.put(this,"enabled_sms",smsEnabled.isChecked()?"1":"0");
        QueueDb.put(this,"delay_bale",String.valueOf(bd));
        QueueDb.put(this,"delay_rubika",String.valueOf(rd));
        QueueDb.put(this,"delay_eitaa",String.valueOf(ed));
        QueueDb.put(this,"delay_sms",String.valueOf(sd));
        QueueDb.put(this,"testOne",testOne?"1":"0");
        QueueDb.put(this,"pending_auto_start","1");
        QueueDb.put(this,"pending_test_one",testOne?"1":"0");
        QueueDb.put(this,"pending_prompt","");

        continueAutomaticStart();
    }

    private void continueAutomaticStart(){
        if(!"1".equals(QueueDb.get(this,"pending_auto_start","0")))return;
        boolean testOne="1".equals(QueueDb.get(this,"pending_test_one","0"));

        if(Build.VERSION.SDK_INT>=33 &&
           checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            if(!"notification".equals(QueueDb.get(this,"pending_prompt",""))){
                QueueDb.put(this,"pending_prompt","notification");
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},533);
            }
            return;
        }

        if(smsEnabled.isChecked()&&
           (checkSelfPermission(android.Manifest.permission.SEND_SMS)!=android.content.pm.PackageManager.PERMISSION_GRANTED||
            checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)){
            if(!"sms_permissions".equals(QueueDb.get(this,"pending_prompt",""))){
                QueueDb.put(this,"pending_prompt","sms_permissions");
                requestPermissions(new String[]{android.Manifest.permission.SEND_SMS,android.Manifest.permission.READ_PHONE_STATE},534);
            }
            return;
        }

        if(addContacts.isChecked()&&!ContactBridge.permission(this)){
            if(!"contacts".equals(QueueDb.get(this,"pending_prompt",""))){
                QueueDb.put(this,"pending_prompt","contacts");
                requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS,android.Manifest.permission.WRITE_CONTACTS},532);
            }
            return;
        }

        boolean messengerOn=baleEnabled.isChecked()||rubikaEnabled.isChecked()||eitaaEnabled.isChecked();
        if(messengerOn&&QueueAccessibilityService.INSTANCE==null){
            if(!"accessibility".equals(QueueDb.get(this,"pending_prompt",""))){
                QueueDb.put(this,"pending_prompt","accessibility");
                toast("Enable Messenger Queue Skip Accessibility once; messenger START will continue automatically.");
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
            return;
        }

        if(isBackgroundRestricted()){
            if(!"background".equals(QueueDb.get(this,"pending_prompt",""))){
                QueueDb.put(this,"pending_prompt","background");
                toast("Remove Restricted background mode; START will continue automatically.");
                try {startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
                catch(Exception ignored){}
            }
            return;
        }

        if(!testOne&&!isBatteryExempt()){
            if(!"battery".equals(QueueDb.get(this,"pending_prompt",""))){
                QueueDb.put(this,"pending_prompt","battery");
                toast("Allow MAX POWER battery exemption once; START will continue automatically.");
                requestBatteryExemption();
            }
            return;
        }

        if(messengerOn&&!"1".equals(QueueDb.get(this,"network_available","1"))){
            QueueDb.put(this,"pending_prompt","network");
            toast("Waiting for internet/VPN required by messenger queues. START is saved.");
            return;
        }

        QueueDb.put(this,"pending_auto_start","0");
        QueueDb.put(this,"pending_prompt","");
        if(baleEnabled.isChecked()&&QueueDb.due(this,"Bale")<=0)QueueDb.due(this,"Bale",System.currentTimeMillis());
        if(rubikaEnabled.isChecked()&&QueueDb.due(this,"Rubika")<=0)QueueDb.due(this,"Rubika",System.currentTimeMillis());
        if(eitaaEnabled.isChecked()&&QueueDb.due(this,"Eitaa")<=0)QueueDb.due(this,"Eitaa",System.currentTimeMillis());
        if(smsEnabled.isChecked()&&QueueDb.smsDue(this)<=0)QueueDb.smsDue(this,System.currentTimeMillis());

        QueueDb.recoverInterrupted(this);
        QueueAccessibilityService s=QueueAccessibilityService.INSTANCE;
        if(messengerOn) {
            if(s==null)return;
            QueueDb.log(this,"AUTO-SETUP COMPLETE: launching messenger Reliability Gate");
            s.startQueue();
        } else if(!testOne) {
            QueueDb.put(this,"desired_running","1");
            QueueDb.put(this,"desired_paused","0");
            QueueKeeperService.start(this);
            QueueDb.log(this,"AUTO-SETUP COMPLETE: SMS-only queue keeper started");
        }
        if(!testOne&&smsEnabled.isChecked())QueueKeeperService.start(this);
        toast(testOne?"Single messenger queue task started":"START accepted: enabled queues are running.");
        refresh();
    }

    private static class ReadinessResult {
        final boolean ready;
        final String reason;
        final int passed;
        final int total;
        ReadinessResult(boolean ready,String reason,int passed,int total){
            this.ready=ready;this.reason=reason;this.passed=passed;this.total=total;
        }
    }

    private ReadinessResult readinessCheck(boolean logIt){
        int passed=0,total=0;
        String fail="";
        boolean messengerOn=baleEnabled!=null&&(baleEnabled.isChecked()||rubikaEnabled.isChecked()||eitaaEnabled.isChecked());
        boolean smsOn=smsEnabled!=null&&smsEnabled.isChecked();

        if(messengerOn){
            total++;
            if(QueueAccessibilityService.INSTANCE!=null)passed++;
            else if(fail.isEmpty())fail="Enable Accessibility";

            total++;
            if("1".equals(QueueDb.get(this,"network_available","1")))passed++;
            else if(fail.isEmpty())fail="Connect to the internet/VPN required by your messengers";
        }

        total++;
        if(!isBackgroundRestricted())passed++;
        else if(fail.isEmpty())fail="Remove Android background restriction";

        total++;
        if(isBatteryExempt())passed++;
        else if(fail.isEmpty())fail="Allow MAX POWER battery exemption";

        total++;
        if(QueueDb.items(this).length()>0)passed++;
        else if(fail.isEmpty())fail="Import Excel/CSV recipients";

        total++;
        if(message!=null&&!message.getText().toString().trim().isEmpty())passed++;
        else if(fail.isEmpty())fail="Enter message text";

        total++;
        boolean enabled=messengerOn||smsOn;
        if(enabled)passed++; else if(fail.isEmpty())fail="Enable at least one queue";

        total++;
        boolean installed=true;
        if(baleEnabled!=null&&baleEnabled.isChecked()&&!isInstalled(packageFor("Bale")))installed=false;
        if(rubikaEnabled!=null&&rubikaEnabled.isChecked()&&!isInstalled(packageFor("Rubika")))installed=false;
        if(eitaaEnabled!=null&&eitaaEnabled.isChecked()&&!isInstalled(packageFor("Eitaa")))installed=false;
        if(installed)passed++; else if(fail.isEmpty())fail="Install/sign in to every enabled messenger";

        total++;
        boolean delays=parseDelay(baleDelay)>=1&&parseDelay(rubikaDelay)>=1&&parseDelay(eitaaDelay)>=1&&
                parseDelay(smsDelay)>=1;
        if(delays)passed++; else if(fail.isEmpty())fail="Set all delays to 1..1440 minutes";

        if(smsOn){
            total++;
            boolean smsPerm=checkSelfPermission(android.Manifest.permission.SEND_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED&&
                    checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)==android.content.pm.PackageManager.PERMISSION_GRANTED;
            if(smsPerm)passed++; else if(fail.isEmpty())fail="Allow SMS and phone-state permissions";

            total++;
            if(!QueueDb.get(this,"sms_subscription_id","").isEmpty())passed++;
            else if(fail.isEmpty())fail="Select the SMS SIM";
        }

        total++;
        boolean contactsOk=!addContacts.isChecked()||ContactBridge.permission(this);
        if(contactsOk)passed++; else if(fail.isEmpty())fail="Allow Contacts permission";

        total++;
        boolean imageOk=true;
        if(imageUri!=null&&!imageUri.isEmpty()){
            try{
                java.io.InputStream in=getContentResolver().openInputStream(Uri.parse(imageUri));
                if(in!=null)in.close();
                String mime=QueueDb.get(this,"image_mime","image/*");
                if(baleEnabled.isChecked()&&!canShareImage(packageFor("Bale"),mime))imageOk=false;
                if(rubikaEnabled.isChecked()&&!canShareImage(packageFor("Rubika"),mime))imageOk=false;
                if(eitaaEnabled.isChecked()&&!canShareImage(packageFor("Eitaa"),mime))imageOk=false;
            }catch(Exception e){imageOk=false;}
        }
        if(imageOk)passed++; else if(fail.isEmpty())fail="Selected image cannot be read/shared by an enabled messenger";

        if(Build.VERSION.SDK_INT>=33){
            total++;
            if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)passed++;
            else if(fail.isEmpty())fail="Allow Notifications for watchdog controls";
        }

        boolean ready=passed==total;
        String text=(ready?"READY 100%":"NOT READY")+" ("+passed+"/"+total+")";
        if(readiness!=null)readiness.setText(text+(ready?" • START can proceed":" • "+fail));
        QueueDb.put(this,"readiness_last",ready?"PASS":"FAIL");
        QueueDb.put(this,"readiness_score",passed+"/"+total);
        if(logIt)QueueDb.log(this,"READINESS "+text+(ready?"":" : "+fail));
        return new ReadinessResult(ready,fail,passed,total);
    }

    private int parseDelay(EditText e){
        try {
            int d=Integer.parseInt(e.getText().toString().trim());
            return d>=1&&d<=1440?d:-1;
        } catch(Exception ex){return -1;}
    }

    private void openApp(String app){
        try {
            Intent i=getPackageManager().getLaunchIntentForPackage(packageFor(app));
            if(i==null){toast(app+" is not installed");return;}
            startActivity(i);
        } catch(Exception e){toast("Cannot open "+app);}
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();

        if(requestCode==IMAGE_PICKER){
            try {
                final int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri,flags);
            } catch(Exception ignored){}
            imageUri=uri.toString();
            String mime=getContentResolver().getType(uri);
            if(mime==null||mime.trim().isEmpty())mime="image/*";
            QueueDb.put(this,"image_uri",imageUri);
            QueueDb.put(this,"image_mime",mime);
            QueueDb.put(this,"image_name",displayName(uri,"Selected image"));
            QueueDb.put(this,"reliability_gate_last","STALE");
            refreshImageLabel();
            toast("Image selected for all enabled queues");
            return;
        }
        if(requestCode!=FILE_PICKER)return;

        String name=displayName(uri,"contacts.xlsx");
        final String filename=name;
        new Thread(()->{
            try {
                RecipientImportParser.Result contacts=ContactImporter.read(getContentResolver(),uri,filename);
                int imported=QueueDb.append(this,contacts);
                runOnUiThread(()->{toast("Imported "+imported+" rows; blocked without opt-in: "+contacts.blockedRows);refresh();});
            } catch(Exception e){
                runOnUiThread(()->toast("Import failed: "+e.getMessage()));
            }
        }).start();
    }

    private String displayName(Uri uri,String def){
        try(Cursor cursor=getContentResolver().query(uri,null,null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){
                int idx=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(idx>=0){String n=cursor.getString(idx);if(n!=null&&!n.isEmpty())return n;}
            }
        } catch(Exception ignored){}
        return def;
    }

    private void refreshImageLabel(){
        if(imageLabel==null)return;
        String name=QueueDb.get(this,"image_name","");
        imageLabel.setText(imageUri==null||imageUri.isEmpty()?"No image selected":"Selected: "+(name.isEmpty()?"image":name));
    }

    private void refresh(){
        if(status==null)return;
        boolean ready=QueueAccessibilityService.INSTANCE!=null;
        boolean messengerOn=baleEnabled!=null&&(baleEnabled.isChecked()||rubikaEnabled.isChecked()||eitaaEnabled.isChecked());
        access.setText(ready?"● Accessibility enabled":(messengerOn?"● Accessibility disabled: enable for messenger queues":"● Accessibility not required for SMS-only"));
        access.setTextColor(ready||!messengerOn?0xFF57E6AC:0xFFFFC27E);
        String mode=ready?QueueAccessibilityService.INSTANCE.state():("1".equals(QueueDb.get(this,"desired_running","0"))?"SMS RUNNING":"OFF");
        status.setText(QueueDb.summary(this)+"\nEngine: "+mode);
        ReadinessResult readyNow=readinessCheck(false);
        reliability.setText("Bale "+versionOf(packageFor("Bale"))+" ["+QueueDb.get(this,"calib_bale","NOT CHECKED")+"]"+
                " | Rubika "+versionOf(packageFor("Rubika"))+" ["+QueueDb.get(this,"calib_rubika","NOT CHECKED")+"]"+
                " | Eitaa "+versionOf(packageFor("Eitaa"))+" ["+QueueDb.get(this,"calib_eitaa","NOT CHECKED")+"]"+
                "\nSMS SIM="+QueueDb.get(this,"sms_subscription_label","NOT SELECTED")+
                " | SMS permission="+(checkSelfPermission(android.Manifest.permission.SEND_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED?"OK":"NO")+
                "\nReliability gate="+QueueDb.get(this,"reliability_gate_last","NOT RUN")+
                " | Keeper="+QueueDb.get(this,"keeper_health","STOPPED")+
                " | Android Restricted="+isBackgroundRestricted()+
                " | battery exemption="+isBatteryExempt()+
                "\nSamsung: put Messenger Queue Skip in Never sleeping apps; keep Power Saving off while queues run."+
                "\nReadiness="+(readyNow.ready?"100% READY":(readyNow.passed+"/"+readyNow.total+" • "+readyNow.reason)));
        String log=QueueDb.get(this,"logs","No events yet.");
        if(!log.equals(logs.getText().toString()))logs.setText(log);
    }

    private static String packageFor(String app){
        switch(app){
            case "Bale":return "ir.nasim";
            case "Rubika":return "app.rbmain.a";
            case "Eitaa":return "ir.eitaa.messenger";
            default:return "";
        }
    }
    private boolean canShareImage(String pkg,String mime){
        try {
            Intent i=new Intent(Intent.ACTION_SEND);
            i.setPackage(pkg);
            i.setType(mime==null||mime.isEmpty()?"image/*":mime);
            return i.resolveActivity(getPackageManager())!=null;
        } catch(Exception e){return false;}
    }
    private boolean isInstalled(String pkg){
        try {getPackageManager().getPackageInfo(pkg,0);return true;}catch(Exception e){return false;}
    }
    private String versionOf(String pkg){
        if(!isInstalled(pkg))return "NOT INSTALLED";
        try {
            PackageInfo p=getPackageManager().getPackageInfo(pkg,0);
            return p.versionName==null?"installed":("v"+p.versionName);
        } catch(Exception e){return "installed";}
    }
    private void requestBatteryExemption(){
        try {
            Intent i=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:"+getPackageName()));
            startActivity(i);
        } catch(Exception e) {
            try {startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
            catch(Exception ignored){}
        }
    }

    private boolean isBackgroundRestricted(){
        if(Build.VERSION.SDK_INT<28)return false;
        ActivityManager am=(ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        return am!=null&&am.isBackgroundRestricted();
    }
    private boolean isBatteryExempt(){
        PowerManager pm=(PowerManager)getSystemService(Context.POWER_SERVICE);
        return pm!=null&&pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @Override protected void onResume(){
        super.onResume();
        applyKeepScreenOn(keepAwake==null?
                "1".equals(QueueDb.get(this,"keep_awake","1")):keepAwake.isChecked());
        handler.post(ticker);
        handler.postDelayed(()->{
            if("1".equals(QueueDb.get(this,"pending_auto_start","0"))) {
                String prompt=QueueDb.get(this,"pending_prompt","");
                boolean satisfied=
                        ("accessibility".equals(prompt)&&QueueAccessibilityService.INSTANCE!=null) ||
                        ("battery".equals(prompt)&&isBatteryExempt()) ||
                        ("background".equals(prompt)&&!isBackgroundRestricted()) ||
                        ("network".equals(prompt)&&"1".equals(QueueDb.get(this,"network_available","1"))) ||
                        ("sms_permissions".equals(prompt)&&
                            checkSelfPermission(android.Manifest.permission.SEND_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED&&
                            checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)==android.content.pm.PackageManager.PERMISSION_GRANTED) ||
                        prompt.isEmpty();
                if(satisfied) {
                    QueueDb.put(this,"pending_prompt","");
                    continueAutomaticStart();
                } else {
                    toast("START is saved. Complete the requested system setting, then return here.");
                }
            }
        },700);
    }
    @Override protected void onPause(){handler.removeCallbacks(ticker);super.onPause();}

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        QueueDb.put(this,"pending_prompt","");
        boolean granted=grantResults.length>0;
        for(int result:grantResults) {
            if(result!=android.content.pm.PackageManager.PERMISSION_GRANTED){granted=false;break;}
        }
        if(requestCode==532&&!granted) {
            addContacts.setChecked(false);
            QueueDb.put(this,"addContacts","0");
            toast("Contacts permission denied. Contact aliases are off; phone search will still be used.");
            handler.postDelayed(this::continueAutomaticStart,300);
            return;
        }
        if(requestCode==533&&!granted) {
            QueueDb.put(this,"pending_auto_start","0");
            QueueDb.put(this,"pending_test_one","0");
            toast("Notification permission is required for the visible queue service. START cancelled.");
            return;
        }
        if(requestCode==534) {
            if(!granted) {
                QueueDb.put(this,"pending_auto_start","0");
                QueueDb.put(this,"pending_test_one","0");
                toast("SMS permissions denied. SMS START cancelled; messenger queues remain available.");
                return;
            }
            if(!"1".equals(QueueDb.get(this,"pending_auto_start","0")))chooseSmsSim();
        }
        handler.postDelayed(this::continueAutomaticStart,300);
    }

    private void chooseSmsSim(){
        if(checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.READ_PHONE_STATE},534);
            return;
        }
        try {
            SubscriptionManager sm=(SubscriptionManager)getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> list=sm==null?null:sm.getActiveSubscriptionInfoList();
            if(list==null||list.isEmpty()){toast("No active SIM subscription found");return;}
            ArrayList<String> labels=new ArrayList<>();
            for(SubscriptionInfo info:list){
                String carrier=info.getCarrierName()==null?"SIM":info.getCarrierName().toString();
                labels.add("SIM "+(info.getSimSlotIndex()+1)+" • "+carrier+" • subscription "+info.getSubscriptionId());
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Select SMS SIM")
                    .setItems(labels.toArray(new String[0]),(d,which)->{
                        SubscriptionInfo info=list.get(which);
                        String label=labels.get(which);
                        QueueDb.put(this,"sms_subscription_id",String.valueOf(info.getSubscriptionId()));
                        QueueDb.put(this,"sms_subscription_label",label);
                        refreshSmsSimLabel();
                    }).show();
        } catch(Exception e){toast("SIM selection unavailable: "+e.getClass().getSimpleName());}
    }

    private void refreshSmsSimLabel(){
        if(smsSimLabel!=null)smsSimLabel.setText("Selected: "+QueueDb.get(this,"sms_subscription_label","none"));
    }

    private void applyKeepScreenOn(boolean enabled){
        if(enabled)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private TextView label(String s,int sp,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(6),0,dp(6));return t;
    }
    private void addButton(LinearLayout root,String name,int color,Runnable click){
        Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(15);
        b.setBackground(bg(color,12));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.topMargin=dp(8);
        root.addView(b,p);b.setOnClickListener(v->click.run());
    }
    private void addSmallButton(LinearLayout root,String name,int color,Runnable click){
        Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(11);b.setBackground(bg(color,8));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1f);p.setMargins(dp(2),dp(4),dp(2),0);
        root.addView(b,p);b.setOnClickListener(v->click.run());
    }
    private GradientDrawable bg(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private int dp(float f){return (int)(f*getResources().getDisplayMetrics().density+.5f);}
    private void space(LinearLayout root,int size){View v=new View(this);root.addView(v,lp(1,dp(size)));}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
