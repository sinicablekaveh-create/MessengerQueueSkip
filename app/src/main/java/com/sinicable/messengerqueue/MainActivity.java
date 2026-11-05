package com.sinicable.messengerqueue;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import java.util.List;

public class MainActivity extends Activity {
    static final String[] NAMES={"Bale","Rubika","Eitaa"};
    private static final String[] FALLBACK={"None","Bale","Rubika","Eitaa"};
    private static final int FILE_PICKER = 512;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private EditText message, delay;
    private Spinner primary, fallback;
    private CheckBox approved, addContacts;
    private TextView status, logs, access, reliability;
    private final Runnable ticker=new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this,1500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10,16,29));
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(23),dp(20),dp(24));
        scroll.addView(root);
        setContentView(scroll);

        TextView title=label("MESSENGER QUEUE SKIP",26,Color.WHITE,true);root.addView(title);
        root.addView(label("Real queue • Excel import • Accessibility screen automation",13,0xFFBAC8DB,false));
        space(root,14);
        access=label("",13,0xFFFFCE75,true);root.addView(access);
        addButton(root,"1  ENABLE ACCESSIBILITY",0xFF365FCE,()-> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        reliability=label("",12,0xFFBFD1E6,false);root.addView(reliability);
        addButton(root,"RELIABILITY / BATTERY SETTINGS",0xFF405F80,()-> {
            try {startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
            catch(Exception e) {startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));}
        });

        space(root,12);
        root.addView(label("2  IMPORT CONTACTS",17,Color.WHITE,true));
        addButton(root,"IMPORT .XLSX / .CSV",0xFF294861,()->{
            Intent in=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            in.setType("*/*");
            in.addCategory(Intent.CATEGORY_OPENABLE);
            in.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                 "text/csv","application/csv","application/octet-stream"});
            startActivityForResult(in,FILE_PICKER);
        });
        status=label("",14,0xFFC7D4E4,true);root.addView(status);

        space(root,14);
        root.addView(label("3  PRIMARY MESSENGER (BALE / RUBIKA / EITAA)",16,Color.WHITE,true));
        primary=spinner(NAMES);root.addView(primary);
        root.addView(label("FALLBACK IF CHAT COULD NOT BE OPENED",14,0xFFBECEDF,true));
        fallback=spinner(FALLBACK);root.addView(fallback);

        space(root,14);
        root.addView(label("4  MESSAGE TEXT",16,Color.WHITE,true));
        message=new EditText(this);
        message.setTextColor(Color.WHITE);message.setHintTextColor(0xFF8097AE);
        message.setTextSize(16);message.setMinLines(4);
        message.setGravity(android.view.Gravity.TOP);
        message.setHint("Write message to opted-in recipients...");
        message.setBackground(bg(0xFF1B2A3F,10));
        message.setPadding(dp(12),dp(12),dp(12),dp(12));
        root.addView(message,lp(-1,dp(130)));
        message.setText(QueueDb.get(this,"message",""));
        root.addView(label("DELAY BETWEEN CONTACTS (1–1440 MINUTES)",14,0xFFBECEDF,true));
        delay=new EditText(this);
        delay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        delay.setTextColor(Color.WHITE);
        delay.setText(QueueDb.get(this,"delay","3"));
        root.addView(delay,lp(-1,dp(52)));
        primary.setSelection(index(NAMES,QueueDb.get(this,"primary","Bale")));
        fallback.setSelection(index(FALLBACK,QueueDb.get(this,"fallback","None")));

        approved=new CheckBox(this);
        approved.setText("I confirm these recipients agreed to receive this message.");
        approved.setTextColor(0xFFD1DFE9);
        root.addView(approved);
        addContacts=new CheckBox(this);
        addContacts.setText("Optional: create uniquely named phone contacts for Bale, Rubika and Eitaa search (requires Contacts permission).");
        addContacts.setTextColor(0xFFD1DFE9);
        addContacts.setChecked("1".equals(QueueDb.get(this,"addContacts","0")));
        root.addView(addContacts);

        space(root,6);
        addButton(root,"INSPECT MESSENGER UI (NO SEND)",0xFF455C88,()->{
            QueueAccessibilityService s=QueueAccessibilityService.INSTANCE;
            if(s==null){toast("Enable Accessibility first");return;}
            s.inspectMessenger(NAMES[primary.getSelectedItemPosition()]);
            toast("Messenger UI inspection started; return to the app to see the log");
        });
        addButton(root,"▶  TEST ONE CONTACT",0xFF285C96,()->start(true));
        addButton(root,"▶  START AUTOMATIC",0xFF00A768,()->start(false));
        addButton(root,"Ⅱ  PAUSE / RESUME",0xFFF1B950,()-> {
            QueueAccessibilityService s=QueueAccessibilityService.INSTANCE;
            if(s==null){toast("Enable the accessibility service first");return;}
            s.togglePause();refresh();
        });
        addButton(root,"■  STOP QUEUE",0xFFE06150,()-> {
            QueueAccessibilityService s=QueueAccessibilityService.INSTANCE;
            if(s!=null)s.stopQueue();
            toast("Automation stopped. Pending contacts retained.");
            refresh();
        });
        addButton(root,"🗑  CLEAR QUEUE",0xFF3E5067,()-> new android.app.AlertDialog.Builder(this)
                .setTitle("Clear queue?")
                .setMessage("Erase every queue item and all logs? This cannot be undone.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Clear",(d,w)->{
                    QueueAccessibilityService s=QueueAccessibilityService.INSTANCE;
                    if(s!=null)s.stopQueue();QueueDb.clear(this);refresh();
                }).show());

        space(root,8);
        root.addView(label("LIVE LOG",17,Color.WHITE,true));
        logs=label("",12,0xFFD9E3F0,false);
        logs.setTypeface(Typeface.MONOSPACE);
        logs.setPadding(dp(12),dp(12),dp(12),dp(12));
        logs.setBackground(bg(0xFF19283D,10));
        root.addView(logs);
        addButton(root,"COPY LOG FOR DIAGNOSIS",0xFF405F80,()->{
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MQS log",
                    QueueDb.get(this,"logs","")));
            toast("Queue log copied. It may contain phone numbers.");
        });
        root.addView(label("UI confirmed means message appeared in chat and editor cleared. It is NOT server delivery. REVIEW requires manual inspection. Stop during an uncertain send also marks REVIEW. Inspect Messenger collects only UI control IDs.",12,0xFFAEBCD0,false));
        refresh();
    }
    private void start(boolean testOne) {
        if(!approved.isChecked()) {toast("Confirm recipients' consent first");return;}
        String selected=NAMES[primary.getSelectedItemPosition()];
        if(!isInstalled(packageFor(selected))) {
            toast(selected+" is not installed. Preflight stopped.");
            return;
        }
        if(isBackgroundRestricted()) {
            toast("Android has this app in Restricted background mode. Remove restriction before START.");
            try {startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
            catch(Exception ignored) {}
            return;
        }
        if(QueueDb.next(this)<0) {toast("Import contacts with pending status first");return;}
        if(message.getText().toString().trim().isEmpty()){toast("Enter the message text");return;}
        int d;
        try {d=Integer.parseInt(delay.getText().toString().trim());}
        catch(Exception e){toast("Delay must be 1 to 1440 minutes");return;}
        if(d<1||d>1440) {toast("Delay must be 1 to 1440 minutes");return;}
        if(addContacts.isChecked() && !ContactBridge.permission(this)) {
            requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.WRITE_CONTACTS}, 532);
            toast("Allow Contacts access and press START again");
            return;
        }
        QueueDb.put(this,"addContacts",addContacts.isChecked()?"1":"0");
        QueueAccessibilityService s=QueueAccessibilityService.INSTANCE;
        if(s==null) {
            toast("Enable Messenger Queue Skip in Accessibility settings");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        QueueDb.put(this,"message",message.getText().toString());
        QueueDb.put(this,"delay",String.valueOf(d));
        QueueDb.put(this,"primary",NAMES[primary.getSelectedItemPosition()]);
        QueueDb.put(this,"fallback",FALLBACK[fallback.getSelectedItemPosition()]);
        QueueDb.put(this,"testOne",testOne?"1":"0");
        QueueDb.log(this,"PREFLIGHT OK: "+selected+" "+versionOf(packageFor(selected))+
                ", backgroundRestricted="+isBackgroundRestricted()+
                ", batteryExempt="+isBatteryExempt());
        s.startQueue();
        toast(testOne?"Single-contact test started":"Automatic queue started");refresh();
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=FILE_PICKER || resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        String name="contacts.xlsx";
        try(Cursor cursor=getContentResolver().query(uri,null,null,null,null)) {
            if(cursor!=null&&cursor.moveToFirst()) {
                int idx=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(idx>=0)name=cursor.getString(idx);
            }
        } catch(Exception ignored) {}
        final String filename=name;
        new Thread(()->{
            try {
                List<String> contacts=ContactImporter.read(getContentResolver(),uri,filename);
                int imported=QueueDb.append(this,contacts);
                runOnUiThread(()->{toast("Imported " + imported + " new contacts");refresh();});
            } catch(Exception e) {
                runOnUiThread(()->toast("Import failed: " + e.getMessage()));
            }
        }).start();
    }
    private void refresh() {
        if(status==null)return;
        boolean ready=QueueAccessibilityService.INSTANCE!=null;
        access.setText(ready ? "● Accessibility enabled" : "● Accessibility disabled: enable once before START");
        access.setTextColor(ready?0xFF57E6AC:0xFFFFC27E);
        String mode=ready?QueueAccessibilityService.INSTANCE.state():"OFF";
        status.setText(QueueDb.summary(this)+"\nEngine: "+mode);
        String selected=primary==null?"Bale":NAMES[primary.getSelectedItemPosition()];
        String pkg=packageFor(selected);
        reliability.setText("Preflight: "+selected+" "+versionOf(pkg)+
                " | installed="+isInstalled(pkg)+
                " | Android Restricted="+isBackgroundRestricted()+
                " | battery exemption="+isBatteryExempt()+
                "\nSamsung: add Messenger Queue Skip to Never sleeping apps for maximum reliability.");
        String log=QueueDb.get(this,"logs","No events yet.");
        if(!log.equals(logs.getText().toString()))logs.setText(log);
    }
    private static String packageFor(String app) {
        switch(app) {
            case "Bale": return "ir.nasim";
            case "Rubika": return "app.rbmain.a";
            case "Eitaa": return "ir.eitaa.messenger";
            default: return "";
        }
    }
    private boolean isInstalled(String pkg) {
        if(pkg==null||pkg.isEmpty())return false;
        try {getPackageManager().getPackageInfo(pkg,0);return true;}
        catch(Exception e){return false;}
    }
    private String versionOf(String pkg) {
        if(!isInstalled(pkg))return "NOT INSTALLED";
        try {
            PackageInfo p=getPackageManager().getPackageInfo(pkg,0);
            return p.versionName==null?"installed":("v"+p.versionName);
        } catch(Exception e){return "installed";}
    }
    private boolean isBackgroundRestricted() {
        if(Build.VERSION.SDK_INT<28)return false;
        ActivityManager am=(ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        return am!=null && am.isBackgroundRestricted();
    }
    private boolean isBatteryExempt() {
        PowerManager pm=(PowerManager)getSystemService(Context.POWER_SERVICE);
        return pm!=null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }
    @Override protected void onResume(){super.onResume();handler.post(ticker);}
    @Override protected void onPause(){handler.removeCallbacks(ticker);super.onPause();}
    private Spinner spinner(String[] names) {
        Spinner v=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        v.setAdapter(adapter);
        v.setBackground(bg(0xFFE4EDF8,8));
        return v;
    }
    private static int index(String[] options,String value) {
        for(int i=0;i<options.length;i++)if(options[i].equals(value))return i;
        return 0;
    }
    private TextView label(String s,int sp,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        t.setPadding(0,dp(6),0,dp(6));return t;
    }
    private void addButton(LinearLayout root,String name,int color,Runnable click) {
        Button b=new Button(this);b.setText(name);b.setAllCaps(false);
        b.setTextColor(Color.WHITE);b.setTextSize(15);
        b.setBackground(bg(color,12));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));
        p.topMargin=dp(8);root.addView(b,p);b.setOnClickListener(v->click.run());
    }
    private GradientDrawable bg(int color,int radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private int dp(float f){return (int)(f*getResources().getDisplayMetrics().density+.5f);}
    private void space(LinearLayout root,int size){View v=new View(this);root.addView(v,lp(1,dp(size)));}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
