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
import org.json.JSONArray;
import org.json.JSONObject;

/** Editable only while queues are stopped. Attempted/uncertain sends are immutable. */
public final class RecipientEditorActivity extends Activity {
    private static final int PAGE_SIZE=18;
    private final String channel=BuildConfig.TARGET_CHANNEL;
    private LinearLayout list;
    private EditText name,phone;
    private CheckBox consent,sendSms;
    private TextView notice,counter;
    private Button submit;
    private String editing="";
    private int page=0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(26,24,26,38);
        scroll.addView(content);
        setContentView(scroll);

        TextView title=text(content,"THREE IB • مدیریت مخاطبان",23);
        text(content,"ایجاد، اصلاح و حذف فقط برای شماره‌های ارسال‌نشده ممکن است. " +
                "برای حفاظت از گزارش ارسال، ردیف‌های SUBMITTED / REVIEW / FAILED تغییر نمی‌کنند.",13);
        notice=text(content,"",14);
        name=field(content,"نام مخاطب",InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        phone=field(content,"شماره تماس (مثال: 0912...)",InputType.TYPE_CLASS_PHONE);
        sendSms=new CheckBox(this);
        sendSms.setText("اجازه ارسال پیامک برای این شماره");
        sendSms.setChecked("sms".equals(channel));
        sendSms.setVisibility("sms".equals(channel)?android.view.View.VISIBLE:android.view.View.GONE);
        content.addView(sendSms);
        consent=new CheckBox(this);
        consent.setText("تأیید می‌کنم این مخاطب به دریافت پیام رضایت داده است.");
        content.addView(consent);
        submit=button(content,"+ ایجاد مخاطب",this::saveEditor);
        button(content,"انصراف از ویرایش و پاک‌کردن فرم",this::resetForm);
        counter=text(content,"",16);
        LinearLayout paging=new LinearLayout(this);
        paging.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(paging);
        button(paging,"صفحه قبل",()->{page=Math.max(0,page-1);refresh();});
        button(paging,"صفحه بعد",()->{page++;refresh();});
        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        refresh();
    }

    private TextView text(LinearLayout parent,String text,int size) {
        TextView item=new TextView(this);
        item.setText(text);
        item.setTextSize(size);
        parent.addView(item);
        return item;
    }

    private EditText field(LinearLayout parent,String hint,int type) {
        EditText item=new EditText(this);
        item.setHint(hint);
        item.setInputType(type);
        parent.addView(item,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        return item;
    }

    private Button button(LinearLayout parent,String title,Runnable action) {
        Button item=new Button(this);
        item.setText(title);
        item.setAllCaps(false);
        item.setOnClickListener(v->action.run());
        parent.addView(item);
        return item;
    }

    private void refresh() {
        boolean idle=QueueDb.editorIdle(this);
        notice.setText(idle?"صف‌ها متوقف‌اند؛ مدیریت مخاطبان فعال است.":
                "برای ویرایش، ابتدا همهٔ صف‌ها را متوقف کنید. ارسال فعال را دستکاری نکنید.");
        submit.setEnabled(idle);
        JSONArray rows=QueueDb.items(this);
        int pages=Math.max(1,(rows.length()+PAGE_SIZE-1)/PAGE_SIZE);
        page=Math.max(0,Math.min(page,pages-1));
        counter.setText("مخاطبان: "+rows.length()+" • صفحه "+(page+1)+" از "+pages);
        list.removeAllViews();
        for(int index=page*PAGE_SIZE;index<Math.min(rows.length(),(page+1)*PAGE_SIZE);index++) {
            JSONObject row=rows.optJSONObject(index);
            if(row==null)continue;
            String id=row.optString("id","");
            String number=row.optString("phone","");
            String contactName=row.optString("name","بدون نام");
            String status=row.optString(channel,"pending");
            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(10,8,10,8);
            list.addView(card);
            text(card,(index+1)+". "+contactName+" • "+number,15);
            text(card,"وضعیت: "+status,12);
            if(QueueDb.editorEditable(this,id)) {
                LinearLayout actions=new LinearLayout(this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                card.addView(actions);
                Button edit=button(actions,"ویرایش",()->{
                    editing=id;
                    name.setText(contactName);
                    phone.setText(number);
                    sendSms.setChecked(row.optBoolean("send_sms",false));
                    consent.setChecked(false);
                    submit.setText("ذخیره تغییرات");
                    notice.setText("برای اصلاح مخاطب، رضایت دریافت پیام را دوباره تأیید کنید.");
                });
                edit.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,1f));
                Button delete=button(actions,"حذف",()->new AlertDialog.Builder(this)
                        .setTitle("حذف مخاطب ارسال‌نشده")
                        .setMessage("این مخاطب و صف‌های ارسال‌نشدهٔ مربوط به آن از همین برنامه حذف شود؟")
                        .setNegativeButton("انصراف",null)
                        .setPositiveButton("حذف کن",(d,w)->{
                            String result=QueueDb.editorDelete(this,id);
                            Toast.makeText(this,result,Toast.LENGTH_LONG).show();
                            if(id.equals(editing))resetForm();
                            refresh();
                        }).show());
                delete.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,1f));
            } else {
                text(card,"قفل ویرایش: ارسال شروع شده یا صف در حال اجراست.",12);
            }
        }
        AppStyle.decorate(this,getWindow().getDecorView());
    }

    private void saveEditor() {
        if(!consent.isChecked()) {
            Toast.makeText(this,"ابتدا رضایت واقعی مخاطب را تأیید کنید.",Toast.LENGTH_LONG).show();
            return;
        }
        String result=editing.isEmpty()
                ?QueueDb.editorAdd(this,name.getText().toString(),phone.getText().toString(),sendSms.isChecked())
                :QueueDb.editorEdit(this,editing,name.getText().toString(),phone.getText().toString(),sendSms.isChecked());
        Toast.makeText(this,result,Toast.LENGTH_LONG).show();
        if(result.startsWith("مخاطب با رضایت")||result.startsWith("تغییرات ذخیره")) {
            resetForm();
            refresh();
        } else notice.setText(result);
    }

    private void resetForm() {
        editing="";
        name.setText("");
        phone.setText("");
        consent.setChecked(false);
        sendSms.setChecked("sms".equals(channel));
        submit.setText("+ ایجاد مخاطب");
    }

    @Override protected void onResume() {
        super.onResume();
        if(list!=null)refresh();
    }
}
