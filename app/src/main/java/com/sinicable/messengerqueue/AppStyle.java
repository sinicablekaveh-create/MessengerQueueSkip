package com.sinicable.messengerqueue;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One consistent, lightweight, entirely offline UI for all six channel flavors. */
final class AppStyle {
    private static final int TEXT=Color.rgb(28,42,60);
    private static final int MUTED=Color.rgb(87,105,124);
    private static final int SURFACE=Color.WHITE;
    private static final int PAGE=Color.rgb(245,248,252);

    static int accent() {
        switch(BuildConfig.TARGET_CHANNEL) {
            case "bale": return Color.rgb(11,116,129);
            case "rubika": return Color.rgb(107,69,174);
            case "eitaa": return Color.rgb(188,93,40);
            case "telegram": return Color.rgb(25,136,205);
            case "sms": return Color.rgb(36,124,99);
            default: return Color.rgb(18,139,99);
        }
    }

    static int dp(Activity a,int value) {
        return Math.round(value*a.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable rounded(int color,int border,int radius,Activity a) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(a,radius));
        shape.setStroke(dp(a,1),border);
        return shape;
    }

    static void decorate(Activity a,View root) {
        if(root instanceof LinearLayout) ((LinearLayout)root).setBackgroundColor(PAGE);
        decorateChildren(a,root);
    }

    private static void decorateChildren(Activity a,View v) {
        if(v instanceof Button) {
            Button b=(Button)v;
            String title=b.getText().toString();
            boolean start=title.toUpperCase(java.util.Locale.ROOT).contains("START AUTOMATIC");
            boolean danger=title.contains("CLEAR")||title.contains("DELETE")||
                    title.contains("حذف")||title.contains("پاک");
            int bg=start?accent():SURFACE;
            int edge=start?accent():(danger?0xffefd0cc:0xffdae4ee);
            b.setBackgroundTintList(null);
            b.setBackground(rounded(bg,edge,14,a));
            b.setTextColor(start?Color.WHITE:(danger?0xff963c35:TEXT));
            b.setAllCaps(false);
            b.setTextSize(14);
            b.setTypeface(null,start?Typeface.BOLD:Typeface.NORMAL);
            b.setMinimumHeight(dp(a,48));
            b.setPadding(dp(a,12),dp(a,10),dp(a,12),dp(a,10));
            ViewGroup.LayoutParams lp=b.getLayoutParams();
            if(lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)lp;
                p.setMargins(dp(a,4),dp(a,5),dp(a,4),dp(a,5));
                b.setLayoutParams(p);
            }
        } else if(v instanceof EditText) {
            EditText e=(EditText)v;
            e.setTextColor(TEXT);
            e.setHintTextColor(MUTED);
            e.setTextSize(15);
            e.setSingleLine(false);
            e.setPadding(dp(a,15),dp(a,13),dp(a,15),dp(a,13));
            e.setBackground(rounded(SURFACE,0xffd5e0eb,13,a));
            ViewGroup.LayoutParams lp=e.getLayoutParams();
            if(lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)lp;
                p.setMargins(dp(a,3),dp(a,5),dp(a,3),dp(a,7));
                e.setLayoutParams(p);
            }
        } else if(v instanceof CheckBox) {
            CheckBox check=(CheckBox)v;
            check.setTextColor(TEXT);
            check.setButtonTintList(ColorStateList.valueOf(accent()));
            check.setPadding(dp(a,5),dp(a,6),dp(a,5),dp(a,6));
        } else if(v instanceof TextView) {
            TextView t=(TextView)v;
            String title=t.getText().toString();
            if(title.startsWith("THREE IB")) {
                t.setTextColor(accent());
                t.setTypeface(null,Typeface.BOLD);
            } else {
                t.setTextColor(t.getTextSize()>dp(a,18)?TEXT:MUTED);
                if(t.getTextSize()>dp(a,18))t.setTypeface(null,Typeface.BOLD);
            }
            t.setPadding(dp(a,4),dp(a,8),dp(a,4),dp(a,8));
        }
        if(v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++)decorateChildren(a,g.getChildAt(i));
        }
    }
}
