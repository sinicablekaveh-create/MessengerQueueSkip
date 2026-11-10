package com.sinicable.messengerqueue;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Telegram has its own TDLib queue; never edit processed or uncertain recipients. */
public final class TelegramRecipientsActivity extends Activity {
    private static final int PAGE_SIZE=18;
    private TelegramEngine engine;
    private LinearLayout list;
    private TextView counter,status;
    private EditText phone;
    private CheckBox consent;
    private Button save;
    private int editing=-1,page=0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        engine=TelegramEngine.get(this);
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setPadding(24,24,24,36);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        setContentView(scroll);
        text(root,"THREE IB • TELEGRAM • مخاطبان",23);
        text(root,"تنها شماره‌های باقی‌مانده را می‌توان ایجاد، ویرایش یا حذف کرد. " +
                "ردیف‌های ارسال‌شده و نتیجه نامشخص برای جلوگیری از تکرار محفوظ می‌مانند.",13);
        status=text(root,"",14);
        phone=new EditText(this);
        phone.setHint("شماره جدید یا شماره اصلاح‌شده");
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        root.addView(phone);
        consent=new CheckBox(this);
        consent.setText("رضایت این گیرنده برای دریافت پیام تأیید شده است.");
        root.addView(consent);
        save=button(root,"+ ایجاد شماره",this::submit);
        button(root,"انصراف از ویرایش",this::reset);
        button(root,"پاک‌کردن همه شماره‌های باقی‌مانده",()->new AlertDialog.Builder(this)
                .setTitle("پاک‌کردن صف ارسال‌نشده تلگرام")
                .setMessage("فقط شماره‌های ارسال‌نشده حذف شوند؟ سوابق اقدام‌های قبلی باقی می‌مانند.")
                .setNegativeButton("انصراف",null)
                .setPositiveButton("پاک کن",(d,w)->{
                    toast(engine.editorClear());
                    reset();
                    refresh();
                }).show());
        counter=text(root,"",15);
        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(controls);
        button(controls,"قبل",()->{page=Math.max(0,page-1);refresh();});
        button(controls,"بعد",()->{page++;refresh();});
        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        refresh();
    }

    private TextView text(LinearLayout root,String value,int size) {
        TextView t=new TextView(this);
        t.setText(value);t.setTextSize(size);
        root.addView(t);return t;
    }

    private Button button(LinearLayout root,String label,Runnable action) {
        Button b=new Button(this);
        b.setText(label);b.setAllCaps(false);
        b.setOnClickListener(v->action.run());
        root.addView(b);return b;
    }

    private void submit(){
        if(!consent.isChecked()){toast("رضایت گیرنده را تأیید کنید.");return;}
        String result=editing<0?engine.editorAdd(phone.getText().toString()):
                engine.editorEdit(editing,phone.getText().toString());
        toast(result);
        if(result.equals("Recipient added.")||result.equals("Recipient updated.")){
            reset();refresh();
        } else status.setText(result);
    }

    private void reset(){
        editing=-1;
        phone.setText("");
        consent.setChecked(false);
        save.setText("+ ایجاد شماره");
    }

    private void refresh(){
        boolean idle=engine.editorIdle();
        status.setText(idle?"صف متوقف است؛ ویرایش مجاز است.":"صف فعال یا نتیجه‌ای نیازمند بررسی است؛ ویرایش قفل است.");
        save.setEnabled(idle);
        int remaining=Math.max(0,engine.phones.size()-engine.index);
        int pages=Math.max(1,(remaining+PAGE_SIZE-1)/PAGE_SIZE);
        page=Math.max(0,Math.min(page,pages-1));
        counter.setText("کل: "+engine.phones.size()+" • پردازش‌شده: "+engine.index+
                " • باقی‌مانده: "+remaining+" • صفحه "+(page+1)+"/"+pages);
        list.removeAllViews();
        for(int i=engine.index+page*PAGE_SIZE;i<Math.min(engine.phones.size(),
                engine.index+(page+1)*PAGE_SIZE);i++){
            final int index=i;
            final String number=engine.phones.get(i);
            LinearLayout section=new LinearLayout(this);
            section.setOrientation(LinearLayout.VERTICAL);
            section.setPadding(8,6,8,6);
            list.addView(section);
            text(section,(i+1)+". "+number,15);
            if(idle){
                LinearLayout actions=new LinearLayout(this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                section.addView(actions);
                Button edit=button(actions,"ویرایش",()->{
                    editing=index;phone.setText(number);consent.setChecked(false);
                    save.setText("ذخیره تغییرات");
                });
                edit.setLayoutParams(new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
                Button delete=button(actions,"حذف",()->new AlertDialog.Builder(this)
                        .setTitle("حذف شماره ارسال‌نشده")
                        .setMessage("این شماره از صف تلگرام حذف شود؟")
                        .setNegativeButton("انصراف",null)
                        .setPositiveButton("حذف کن",(d,w)->{
                            toast(engine.editorRemove(index));
                            reset();refresh();
                        }).show());
                delete.setLayoutParams(new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
            }
        }
        AppStyle.decorate(this,getWindow().getDecorView());
    }

    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override public void onResume(){super.onResume();if(list!=null)refresh();}
}
