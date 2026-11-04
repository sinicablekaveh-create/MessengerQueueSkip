package com.sinicable.messengerqueue;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rule-based on-screen automation. It does NOT use APIs that send messages silently.
 * Only messages to explicitly imported, opt-in numbers can be attempted.
 * 'submitted' means the Send UI action succeeded, not confirmed server delivery.
 */
public class QueueAccessibilityService extends AccessibilityService {
    static volatile QueueAccessibilityService INSTANCE;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private static final long STEP_WAIT_MS=850;
    private static final long ATTEMPT_TIMEOUT_MS=60000;
    private volatile boolean running=false;
    private volatile boolean paused=false;
    private int rowIndex=-1,attempt=0,stage=0,generation=0;
    private long launchedAt=0,lastActionAt=0;
    private String phone="",targetName="",targetPackage="",message="",contactAlias="";
    private boolean triedAlias=false,newChatTapped=false,sendInProgress=false;
    private long searchStartedAt=0;
    private Runnable scheduled;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE=this;
        QueueDb.log(this,"Accessibility engine connected");
    }
    @Override public void onDestroy() {
        running=false;
        generation++;
        INSTANCE=null;
        super.onDestroy();
    }
    @Override public void onInterrupt() { /* User can pause/stop from the app. */ }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if(!running||paused)return;
        if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
           e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            schedule(450);
        }
    }
    synchronized void startQueue() {
        if(running && paused) {paused=false;schedule(200);return;}
        if(running)return;
        running=true;
        paused=false;
        message=QueueDb.get(this,"message","");
        QueueDb.log(this,"START AUTOMATIC");
        next(1000);
    }
    synchronized void togglePause() {
        if(!running) {startQueue();return;}
        paused=!paused;
        QueueDb.log(this,paused?"Queue paused":"Queue resumed");
        if(!paused)schedule(300);
    }
    synchronized void stopQueue() {
        running=false;
        paused=false;
        generation++;
        if(scheduled!=null)handler.removeCallbacks(scheduled);
        QueueDb.log(this,"Queue stopped by user");
    }
    String state() {
        return !running?"STOPPED":paused?"PAUSED":"RUNNING: " + targetName + " / " + phone;
    }
    private void next(long delay) {
        if(!running||paused)return;
        generation++;
        if(scheduled!=null)handler.removeCallbacks(scheduled);
        int token=generation;
        scheduled=()->{
            if(token!=generation||!running||paused)return;
            rowIndex=QueueDb.next(this);
            if(rowIndex<0){running=false;QueueDb.log(this,"Queue complete");return;}
            phone=QueueDb.phone(this,rowIndex);
            attempt=0;
            launchCurrent();
        };
        handler.postDelayed(scheduled,delay);
    }
    private String packageFor(String app) {
        switch(app) {
            case "Bale":return "ir.nasim";
            case "Rubika":return "app.rbmain.a";
            case "Eitaa":return "ir.eitaa.messenger";
            case "Telegram":return "org.telegram.messenger";
            case "WhatsApp":return "com.whatsapp";
            default:return "";
        }
    }
    private void launchCurrent() {
        if(!running||paused)return;
        targetName=attempt==0?QueueDb.get(this,"primary","Bale"):
                QueueDb.get(this,"fallback","None");
        targetPackage=packageFor(targetName);
        if(targetPackage.isEmpty()) {skip("No fallback app configured");return;}
        stage=0;
        lastActionAt=0;
        searchStartedAt=0;
        triedAlias=false;
        newChatTapped=false;
        sendInProgress=false;
        contactAlias="1".equals(QueueDb.get(this,"addContacts","0"))
                ? ContactBridge.ensure(this,phone):"";
        launchedAt=System.currentTimeMillis();
        QueueDb.log(this,"Attempt " + (attempt+1) + " " + targetName + " -> " + phone);
        try {
            Intent open;
            if("WhatsApp".equals(targetName)) {
                Uri u=new Uri.Builder().scheme("https").authority("wa.me")
                        .appendPath(ContactImporter.international(phone))
                        .appendQueryParameter("text",message).build();
                open=new Intent(Intent.ACTION_VIEW,u);
                open.setPackage("com.whatsapp");
                stage=4; // Phone deep-link should pre-fill the text in the chosen conversation.
            } else {
                open=getPackageManager().getLaunchIntentForPackage(targetPackage);
                if(open==null) {failAttempt("App not installed or launcher missing");return;}
            }
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
            schedule(1700);
        } catch(Exception ex) {failAttempt("Cannot open app: " + ex.getClass().getSimpleName());}
    }
    private synchronized void failAttempt(String reason) {
        if(!running)return;
        if(sendInProgress) {
            QueueDb.log(this,"Send may have been pressed; no automatic retry to prevent duplicates");
            finishSubmitted("UI confirmation unavailable");
            return;
        }
        QueueDb.log(this,"Attempt failed: " + reason);
        String fallback=QueueDb.get(this,"fallback","None");
        String primary=QueueDb.get(this,"primary","Bale");
        if(attempt==0 && !"None".equals(fallback) && !fallback.equals(primary)) {
            attempt=1;
            handler.postDelayed(this::launchCurrent,900);
        } else skip("No compatible chat / Skip");
    }
    private synchronized void skip(String why) {
        if(rowIndex<0)return;
        QueueDb.status(this,rowIndex,"skipped");
        QueueDb.log(this,"SKIP " + phone + ": " + why);
        next(delayMs());
    }
    private long delayMs() {
        int d=3;
        try {d=Integer.parseInt(QueueDb.get(this,"delay","3"));}
        catch(Exception ignored) {}
        return Math.max(1,Math.min(1440,d))*60000L;
    }
    private void schedule(long ms) {
        int token=generation;
        handler.postDelayed(()->{
            if(token==generation&&running&&!paused)step();
        },ms);
    }
    private void step() {
        if(!running||paused)return;
        long now=System.currentTimeMillis();
        if(now-launchedAt>ATTEMPT_TIMEOUT_MS) {failAttempt("Timed out waiting for verified chat");return;}
        if(now-lastActionAt<STEP_WAIT_MS) {schedule(STEP_WAIT_MS);return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){schedule(1000);return;}
        try {
            if(!targetPackage.equals(String.valueOf(root.getPackageName()))) {
                schedule(900);return;
            }
            if(stage==0) {
                AccessibilityNodeInfo search=findByWords(root,
                        new String[]{"search","جستجو","جست و جو","search_icon","search_button","ic_search"},true);
                if(search!=null && click(search)) {
                    stage=1;lastActionAt=now;schedule(800);return;
                }
                if(findSearchEditor(root)!=null) {
                    stage=1;schedule(250);return;
                }
                // Some versions hide search inside the 'new chat' or pencil action.
                if(!newChatTapped) {
                    AccessibilityNodeInfo newChat=findByWords(root,
                            new String[]{"new chat","new message","پیام جدید","گفتگوی جدید",
                                    "گفت و گوی جدید","compose","ic_edit","fab"},true);
                    if(newChat!=null && click(newChat)) {
                        newChatTapped=true;lastActionAt=now;schedule(900);return;
                    }
                }
            } else if(stage==1) {
                AccessibilityNodeInfo input=findSearchEditor(root);
                String query=triedAlias?contactAlias:phone;
                if(input!=null && !query.isEmpty() && setText(input,query)) {
                    stage=2;searchStartedAt=now;lastActionAt=now;schedule(1500);return;
                }
            } else if(stage==2) {
                AccessibilityNodeInfo match=findVerifiedResult(root,phone,
                        triedAlias?contactAlias:"");
                if(match!=null && click(match)) {
                    stage=3;lastActionAt=now;
                    QueueDb.log(this,"Matched verified contact in "+targetName);
                    schedule(1100);return;
                }
                if(!triedAlias && !contactAlias.isEmpty()
                        && now-searchStartedAt>9000) {
                    triedAlias=true;stage=1;
                    QueueDb.log(this,"Searching synced contact alias in "+targetName);
                    schedule(300);return;
                }
            } else if(stage==3) {
                AccessibilityNodeInfo composer=findChatComposer(root);
                if(composer!=null && setText(composer,message)) {
                    stage=4;lastActionAt=now;schedule(900);return;
                }
            } else if(stage==4) {
                if(sendInProgress)return;
                AccessibilityNodeInfo composer=findChatComposer(root);
                if(composer!=null && message.contentEquals(composer.getText()==null
                        ? "" : composer.getText())) {
                    AccessibilityNodeInfo send=findSendNearComposer(root,composer);
                    if(send!=null) {
                        sendInProgress=true;
                        clickSend(send);
                        return;
                    }
                }
            }
        } catch(Exception e) {
            QueueDb.log(this,"UI engine error at step "+stage+": "+e.getClass().getSimpleName());
        } finally {root.recycle();}
        schedule(900);
    }

    private void clickSend(AccessibilityNodeInfo target) {
        if(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            finishSubmitted("Send button tapped via Accessibility action");
            return;
        }
        Rect r=new Rect();target.getBoundsInScreen(r);
        if(r.isEmpty()) {sendInProgress=false;return;}
        Path p=new Path();p.moveTo(r.centerX(),r.centerY());
        GestureDescription.StrokeDescription stroke=
                new GestureDescription.StrokeDescription(p,0,100);
        try {
            boolean initiated=dispatchGesture(
                    new GestureDescription.Builder().addStroke(stroke).build(),
                    new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription g) {
                            finishSubmitted("Send touch gesture completed; delivery not confirmed");
                        }
                        @Override public void onCancelled(GestureDescription g) {
                            sendInProgress=false;
                            QueueDb.log(QueueAccessibilityService.this,"Send gesture cancelled");
                        }
                    },handler);
            if(!initiated)sendInProgress=false;
        } catch(Exception ex) {
            sendInProgress=false;
            QueueDb.log(this,"Send gesture failed: "+ex.getClass().getSimpleName());
        }
    }
    private synchronized void finishSubmitted(String detail) {
        if(!running || rowIndex<0)return;
        QueueDb.status(this,rowIndex,"submitted");
        QueueDb.log(this,"SUBMITTED "+phone+" via "+targetName+" ("+detail+")");
        sendInProgress=false;
        next(delayMs());
    }

    private AccessibilityNodeInfo findSearchEditor(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        collect(root,nodes,0,40);
        AccessibilityNodeInfo best=null;
        int minTop=Integer.MAX_VALUE;
        int screenHeight=getResources().getDisplayMetrics().heightPixels;
        for(AccessibilityNodeInfo n:nodes) {
            if(!n.isEditable() || !n.isEnabled())continue;
            String info=str(n.getHintText())+" "+str(n.getContentDescription())+
                    " "+str(n.getViewIdResourceName());
            Rect r=new Rect();n.getBoundsInScreen(r);
            if(r.isEmpty())continue;
            boolean search=info.contains("search")||info.contains("جست")||
                    info.contains("filter");
            if(search || r.centerY()<screenHeight*0.38) {
                if(r.top<minTop){best=n;minTop=r.top;}
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findVerifiedResult(AccessibilityNodeInfo root,
            String expectedPhone,String expectedAlias) {
        String digits=expectedPhone.replaceAll("[^0-9]","");
        if(digits.length()<10)return null;
        String last10=digits.substring(digits.length()-10);
        AccessibilityNodeInfo search=findSearchEditor(root);
        int minY=0;
        if(search!=null) {
            Rect b=new Rect();search.getBoundsInScreen(b);
            minY=b.bottom+6;
        }
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        collect(root,nodes,0,40);
        for(AccessibilityNodeInfo n:nodes) {
            if(n.isEditable() || !n.isVisibleToUser())continue;
            Rect b=new Rect();n.getBoundsInScreen(b);
            if(b.isEmpty() || b.top<minY)continue;
            String label=str(n.getText())+" "+str(n.getContentDescription());
            String normalized=label.replaceAll("[^0-9]","");
            // Never choose a look-alike name: require the actual phone suffix
            // or the tagged MQS phone contact we created with permission.
            if(normalized.contains(last10) ||
               (!expectedAlias.isEmpty() && label.contains(expectedAlias.toLowerCase(Locale.ROOT)))) {
                AccessibilityNodeInfo action=clickable(n);
                if(action!=null && action.isVisibleToUser())return action;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findChatComposer(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        collect(root,nodes,0,40);
        int screenHeight=getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best=null;
        int maxY=-1;
        for(AccessibilityNodeInfo n:nodes) {
            if(!n.isEditable() || !n.isEnabled() || !n.isVisibleToUser())continue;
            String info=str(n.getHintText())+" "+str(n.getContentDescription())+
                    " "+str(n.getViewIdResourceName());
            if(info.contains("search") || info.contains("جست"))continue;
            Rect r=new Rect();n.getBoundsInScreen(r);
            if(r.isEmpty() || r.centerY()<screenHeight*0.48)continue;
            if(r.centerY()>maxY) {best=n;maxY=r.centerY();}
        }
        return best;
    }

    private AccessibilityNodeInfo findSendNearComposer(AccessibilityNodeInfo root,
            AccessibilityNodeInfo composer) {
        Rect editor=new Rect();composer.getBoundsInScreen(editor);
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        collect(root,nodes,0,40);
        AccessibilityNodeInfo best=null;
        long bestDistance=Long.MAX_VALUE;
        for(AccessibilityNodeInfo n:nodes) {
            if(n.isEditable() || !n.isEnabled() || !n.isVisibleToUser())continue;
            String title=str(n.getText()).trim();
            String desc=str(n.getContentDescription()).trim();
            String id=str(n.getViewIdResourceName());
            boolean label=title.equals("send")||title.equals("ارسال")||
                    desc.equals("send")||desc.equals("send message")||
                    desc.equals("ارسال")||desc.equals("ارسال پیام");
            boolean resource=id.endsWith("/send")||id.endsWith("/send_button")||
                    id.endsWith("/btn_send")||id.endsWith("/ic_send")||
                    id.endsWith("/chat_send");
            if(!label && !resource)continue;
            Rect r=new Rect();n.getBoundsInScreen(r);
            if(r.isEmpty() || Math.abs(r.centerY()-editor.centerY())>250)continue;
            // Never use a generic 'send' control far above the chat composer.
            AccessibilityNodeInfo action=clickable(n);
            if(action==null)continue;
            long distance=Math.abs((long)r.centerY()-editor.centerY())+
                    Math.abs((long)r.centerX()-editor.right)/3;
            if(distance<bestDistance){best=action;bestDistance=distance;}
        }
        return best;
    }
    private static String str(CharSequence c){return c==null?"":c.toString().toLowerCase(Locale.ROOT);}
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo parent,boolean searchOnly) {
        List<AccessibilityNodeInfo> found=new ArrayList<>();
        collect(parent,found,0,40);
        for(AccessibilityNodeInfo n:found) {
            if(!n.isEditable())continue;
            if(searchOnly) {
                String combined=str(n.getHintText())+" "+str(n.getContentDescription());
                if(!(combined.contains("search")||combined.contains("جست")))continue;
            }
            return n;
        }
        return null;
    }
    private AccessibilityNodeInfo findByWords(AccessibilityNodeInfo root,String[] words,boolean clickable) {
        List<AccessibilityNodeInfo> all=new ArrayList<>();collect(root,all,0,40);
        for(AccessibilityNodeInfo node:all) {
            String s=str(node.getText())+" "+str(node.getContentDescription())+
                     " "+str(node.getViewIdResourceName());
            for(String word:words) {
                if(s.contains(word.toLowerCase(Locale.ROOT))) {
                    AccessibilityNodeInfo action=clickable?clickable(node):node;
                    if(action!=null)return action;
                }
            }
        }
        return null;
    }
    private AccessibilityNodeInfo findNumberResult(AccessibilityNodeInfo root,String expected) {
        String digits=expected.replaceAll("[^0-9]","");
        if(digits.length()<10)return null;
        String significant=digits.substring(digits.length()-10);
        List<AccessibilityNodeInfo> all=new ArrayList<>();collect(root,all,0,40);
        for(AccessibilityNodeInfo node:all) {
            if(node.isEditable())continue;
            String visible=(str(node.getText())+" "+str(node.getContentDescription())).replaceAll("[^0-9]","");
            if(visible.contains(significant)) {
                AccessibilityNodeInfo clickable=clickable(node);
                if(clickable!=null)return clickable;
            }
        }
        return null;
    }
    private void collect(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> out,int depth,int maxDepth) {
        if(root==null||depth>maxDepth||out.size()>500)return;
        out.add(root);
        for(int i=0;i<root.getChildCount();i++) {
            AccessibilityNodeInfo ch=root.getChild(i);
            if(ch!=null)collect(ch,out,depth+1,maxDepth);
        }
    }
    private AccessibilityNodeInfo clickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n=node;
        for(int i=0;n!=null&&i<7;i++,n=n.getParent()) {
            if(n.isClickable()&&n.isEnabled())return n;
        }
        return null;
    }
    private boolean setText(AccessibilityNodeInfo node,String text) {
        if(!node.isEditable()||!node.isEnabled())return false;
        Bundle args=new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
    }
    private boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target=clickable(node);
        if(target==null)return false;
        if(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
        Rect r=new Rect();target.getBoundsInScreen(r);
        if(r.isEmpty())return false;
        Path p=new Path();p.moveTo(r.centerX(),r.centerY());
        GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(p,0,80);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),null,null);
    }
}
