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
import android.view.accessibility.AccessibilityWindowInfo;
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
    private int rowIndex=-1,attempt=0,stage=0,generation=0,safeRetries=0;
    private long launchedAt=0,lastActionAt=0;
    private String phone="",targetName="",targetPackage="",message="",contactAlias="";
    private boolean triedAlias=false,newChatTapped=false,sendInProgress=false;
    private boolean waitingForNext=false,stepScheduled=false,awaitingResult=false,testOne=false;
    private int processed=0,bubblesBefore=0;
    private long sendActionAt=0,nextDueAt=0;
    private long searchStartedAt=0;
    private long pausedAt=0;
    private volatile boolean diagnosticMode=false;
    private Runnable scheduled;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE=this;
        QueueDb.log(this,"Accessibility engine connected");
        final boolean wantsRun="1".equals(QueueDb.get(this,"desired_running","0"));
        final boolean wantsPause="1".equals(QueueDb.get(this,"desired_paused","0"));
        if(wantsRun) {
            handler.postDelayed(()->{
                synchronized(QueueAccessibilityService.this) {
                    if(running)return;
                    QueueDb.recoverInterrupted(this);
                    if(QueueDb.next(this)<0) {
                        QueueDb.put(this,"desired_running","0");
                        QueueDb.put(this,"desired_paused","0");
                        QueueDb.log(this,"Persistent START cleared: no pending contacts");
                        return;
                    }
                    if(wantsPause) {
                        running=true;
                        paused=true;
                        processed=0;
                        diagnosticMode=false;
                        testOne=false;
                        message=QueueDb.get(this,"message","");
                        QueueDb.log(this,"Persistent PAUSE restored after service reconnect");
                    } else {
                        QueueDb.log(this,"Persistent START restored after service reconnect");
                        resumePersistentQueue();
                    }
                }
            },1200);
        }
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
        if(running && paused) {togglePause();return;}
        if(running)return;
        running=true;
        paused=false;
        processed=0;
        diagnosticMode=false;
        QueueDb.recoverInterrupted(this);
        testOne="1".equals(QueueDb.get(this,"testOne","0"));
        message=QueueDb.get(this,"message","");
        QueueDb.put(this,"desired_running",testOne?"0":"1");
        QueueDb.put(this,"desired_paused","0");
        QueueDb.put(this,"next_due_wall","0");
        QueueDb.log(this,testOne?"TEST ONE START":"START AUTOMATIC (persistent)");
        next(1000);
    }

    private synchronized void resumePersistentQueue() {
        if(running)return;
        running=true;
        paused=false;
        processed=0;
        diagnosticMode=false;
        QueueDb.recoverInterrupted(this);
        testOne=false;
        message=QueueDb.get(this,"message","");
        long due=readDueWall();
        long remaining=due>0 ? Math.max(1000L,due-System.currentTimeMillis()) : 1000L;
        QueueDb.log(this,"Persistent queue resume in "+Math.max(1L,remaining/1000L)+"s");
        next(remaining);
    }

    private long readDueWall() {
        try {return Long.parseLong(QueueDb.get(this,"next_due_wall","0"));}
        catch(Exception ignored) {return 0L;}
    }
    synchronized void togglePause() {
        if(!running) {startQueue();return;}
        paused=!paused;
        long clock=System.currentTimeMillis();
        if(paused) {
            pausedAt=clock;
        } else if(pausedAt>0) {
            long duration=Math.max(0,clock-pausedAt);
            launchedAt+=duration;
            if(searchStartedAt>0)searchStartedAt+=duration;
            if(sendActionAt>0)sendActionAt+=duration;
            nextDueAt+=duration;
            pausedAt=0;
        }
        QueueDb.put(this,"desired_paused",paused?"1":"0");
        QueueDb.log(this,paused?"Queue paused (persistent)":"Queue resumed");
        if(!paused) {
            if(waitingForNext) {
                long remaining=Math.max(0,nextDueAt-android.os.SystemClock.elapsedRealtime());
                next(remaining);
            } else if(rowIndex<0) {
                long due=readDueWall();
                long remaining=due>0 ? Math.max(1000L,due-System.currentTimeMillis()) : 1000L;
                next(remaining);
            } else schedule(300);
        }
    }
    synchronized void stopQueue() {
        boolean wasRunning=running;
        running=false;
        paused=false;
        QueueDb.put(this,"desired_running","0");
        QueueDb.put(this,"desired_paused","0");
        QueueDb.put(this,"next_due_wall","0");
        generation++;
        if(scheduled!=null)handler.removeCallbacks(scheduled);
        if(wasRunning && rowIndex>=0 && !waitingForNext &&
                "in_flight".equals(QueueDb.state(this,rowIndex))) {
            if(stage<4 && !sendInProgress && !awaitingResult) {
                QueueDb.status(this,rowIndex,"pending");
                QueueDb.log(this,"Current attempt safely returned to Pending");
            } else {
                QueueDb.status(this,rowIndex,"review");
                QueueDb.log(this,"Current attempt marked REVIEW to prevent duplicate send");
            }
        }
        waitingForNext=false;
        QueueDb.log(this,"Queue stopped by user");
    }
    String state() {
        return !running?"STOPPED":paused?"PAUSED":"RUNNING: " + targetName + " / " + phone;
    }
    private void next(long delay) {
        if(!running||paused)return;
        if(testOne && processed>=1) {
            running=false;
            waitingForNext=false;
            QueueDb.put(this,"desired_running","0");
            QueueDb.put(this,"desired_paused","0");
            QueueDb.put(this,"next_due_wall","0");
            QueueDb.log(this,"TEST ONE FINISHED: check results before starting the full queue");
            return;
        }
        waitingForNext=true;
        long safeDelay=Math.max(0,delay);
        nextDueAt=android.os.SystemClock.elapsedRealtime()+safeDelay;
        QueueDb.put(this,"next_due_wall",String.valueOf(System.currentTimeMillis()+safeDelay));
        generation++;
        if(scheduled!=null)handler.removeCallbacks(scheduled);
        int token=generation;
        scheduled=()->{
            if(token!=generation||!running||paused)return;
            waitingForNext=false;
            QueueDb.put(this,"next_due_wall","0");
            rowIndex=QueueDb.next(this);
            if(rowIndex<0){
                running=false;
                QueueDb.put(this,"desired_running","0");
                QueueDb.put(this,"desired_paused","0");
                QueueDb.put(this,"next_due_wall","0");
                QueueDb.log(this,"Queue complete");
                return;
            }
            phone=QueueDb.phone(this,rowIndex);
            QueueDb.status(this,rowIndex,"in_flight"); // durable crash-safe reservation
            attempt=0;
            safeRetries=0;
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
        generation++; // invalidate callbacks from the previous messenger attempt
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
        awaitingResult=false;
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
        if(sendInProgress || awaitingResult) {
            markReview("Could not confirm Send on screen: " + reason);
            return;
        }
        QueueDb.log(this,"Attempt failed: " + reason);
        if(safeRetries<2) {
            safeRetries++;
            QueueDb.log(this,"SAFE RETRY "+safeRetries+"/2 before any Send action");
            generation++;
            int token=generation;
            handler.postDelayed(()->{
                if(running && !paused && token==generation) launchCurrent();
            },1200);
            return;
        }
        String fallback=QueueDb.get(this,"fallback","None");
        String primary=QueueDb.get(this,"primary","Bale");
        if(attempt==0 && !"None".equals(fallback) && !fallback.equals(primary)) {
            attempt=1;
            safeRetries=0;
            generation++;
            int token=generation;
            handler.postDelayed(()->{if(running && !paused && token==generation) launchCurrent();},900);
        } else skip("No compatible chat / Skip");
    }
    private synchronized void skip(String why) {
        if(rowIndex<0)return;
        QueueDb.status(this,rowIndex,"skipped");
        QueueDb.log(this,"SKIP " + phone + ": " + why);
        processed++;
        next(delayMs());
    }
    private long delayMs() {
        int d=3;
        try {d=Integer.parseInt(QueueDb.get(this,"delay","3"));}
        catch(Exception ignored) {}
        return Math.max(1,Math.min(1440,d))*60000L;
    }
    private void schedule(long ms) {
        if(stepScheduled || waitingForNext || !running || paused)return;
        stepScheduled=true;
        int token=generation;
        handler.postDelayed(()->{
            stepScheduled=false;
            if(token==generation&&running&&!paused&&!waitingForNext)step();
        },ms);
    }
    private void step() {
        if(!running||paused||waitingForNext)return;
        long now=System.currentTimeMillis();
        if(now-launchedAt>ATTEMPT_TIMEOUT_MS) {failAttempt("Timed out waiting for verified chat");return;}
        if(now-lastActionAt<STEP_WAIT_MS) {schedule(STEP_WAIT_MS);return;}
        AccessibilityNodeInfo root=targetWindowRoot();
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
                        bubblesBefore=countVisibleMessage(root,message);
                        sendInProgress=true;
                        clickSend(send);
                        return;
                    }
                }
            } else if(stage==5) {
                if(now-sendActionAt>=1500) {
                    AccessibilityNodeInfo editor=findChatComposer(root);
                    String draft=editor==null||editor.getText()==null?"":editor.getText().toString();
                    if(draft.trim().isEmpty() && countVisibleMessage(root,message)>bubblesBefore) {
                        finishSubmitted("UI composer cleared and message bubble visible");
                        return;
                    }
                    if(now-sendActionAt>9000) {
                        markReview("Send action occurred but message bubble could not be verified");
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
        final int token=generation;
        final int sendingRow=rowIndex;
        if(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            beginVerification();
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
                            if(token==generation && sendingRow==rowIndex && running)
                                beginVerification();
                        }
                        @Override public void onCancelled(GestureDescription g) {
                            if(token==generation && sendingRow==rowIndex) {
                                sendInProgress=false;
                                QueueDb.log(QueueAccessibilityService.this,"Send gesture cancelled");
                            }
                        }
                    },handler);
            if(!initiated)sendInProgress=false;
        } catch(Exception ex) {
            sendInProgress=false;
            QueueDb.log(this,"Send gesture failed: "+ex.getClass().getSimpleName());
        }
    }
    private void beginVerification() {
        stage=5;
        sendActionAt=System.currentTimeMillis();
        awaitingResult=true;
        QueueDb.log(this,"Send action invoked; verifying chat UI...");
        schedule(1100);
    }
    private int countVisibleMessage(AccessibilityNodeInfo root,String target) {
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        collect(root,nodes,0,40);
        int count=0;
        for(AccessibilityNodeInfo node:nodes) {
            if(node.isEditable()||!node.isVisibleToUser())continue;
            String txt=node.getText()==null?"":node.getText().toString();
            if(target.equals(txt))count++;
        }
        return count;
    }
    private synchronized void markReview(String reason) {
        if(!running || rowIndex<0)return;
        QueueDb.status(this,rowIndex,"review");
        QueueDb.log(this,"REVIEW REQUIRED "+phone+" via "+targetName+": "+reason);
        sendInProgress=false;
        awaitingResult=false;
        running=false;
        paused=false;
        QueueDb.put(this,"desired_running","0");
        QueueDb.put(this,"desired_paused","0");
        QueueDb.put(this,"next_due_wall","0");
        generation++;
    }
    private synchronized void finishSubmitted(String detail) {
        if(!running || rowIndex<0)return;
        QueueDb.status(this,rowIndex,"submitted");
        QueueDb.log(this,"SUBMITTED "+phone+" via "+targetName+" ("+detail+")");
        sendInProgress=false;
        awaitingResult=false;
        processed++;
        next(delayMs());
    }

    /** Retrieve the foreground messenger window even if the on-screen keyboard has focus. */
    private AccessibilityNodeInfo targetWindowRoot() {
        AccessibilityNodeInfo active=getRootInActiveWindow();
        if(active!=null) {
            if(targetPackage.equals(String.valueOf(active.getPackageName())))return active;
            active.recycle();
        }
        try {
            List<AccessibilityWindowInfo> windows=getWindows();
            if(windows!=null) {
                for(AccessibilityWindowInfo window:windows) {
                    if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
                    AccessibilityNodeInfo root=window.getRoot();
                    if(root==null)continue;
                    if(targetPackage.equals(String.valueOf(root.getPackageName())))
                        return root;
                    root.recycle();
                }
            }
        } catch(Exception ignored) {}
        return null;
    }

    synchronized void inspectMessenger(String messenger) {
        if(running) {
            QueueDb.log(this,"Stop the queue before inspecting a messenger");
            return;
        }
        targetName=messenger;
        targetPackage=packageFor(messenger);
        if(targetPackage.isEmpty()) {
            QueueDb.log(this,"Unknown messenger for diagnostic");
            return;
        }
        diagnosticMode=true;
        QueueDb.log(this,"DIAG started: "+messenger+" (no sending)");
        try {
            Intent intent=getPackageManager().getLaunchIntentForPackage(targetPackage);
            if(intent==null) {
                QueueDb.log(this,"DIAG: app missing or launcher unavailable");
                diagnosticMode=false;
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            handler.postDelayed(this::recordDiagnostic,3200);
        } catch(Exception ex) {
            QueueDb.log(this,"DIAG cannot launch app: "+ex.getClass().getSimpleName());
            diagnosticMode=false;
        }
    }
    private void recordDiagnostic() {
        if(!diagnosticMode || running)return;
        diagnosticMode=false;
        AccessibilityNodeInfo root=targetWindowRoot();
        if(root==null) {
            QueueDb.log(this,"DIAG: no accessible messenger UI. Check unlock and Accessibility.");
            return;
        }
        try {
            List<AccessibilityNodeInfo> nodes=new ArrayList<>();
            collect(root,nodes,0,40);
            int editable=0,visible=0,search=0,send=0;
            StringBuilder controls=new StringBuilder();
            for(AccessibilityNodeInfo n:nodes) {
                if(!n.isVisibleToUser())continue;
                visible++;
                if(n.isEditable())editable++;
                String id=str(n.getViewIdResourceName());
                String meta=str(n.getContentDescription());
                if(id.contains("search") || meta.contains("search") || meta.contains("جست"))
                    search++;
                if(id.contains("send") || meta.equals("send") || meta.equals("ارسال"))
                    send++;
                // Only resource identifiers; never record contact names or message text.
                if(n.isClickable() && controls.length()<600 && !id.isEmpty()) {
                    controls.append(id).append(" ");
                }
            }
            QueueDb.log(this,"DIAG "+targetName+": visible="+visible+
                    " editable="+editable+" search="+search+" send="+send);
            QueueDb.log(this,"DIAG control IDs: "+controls.toString().trim());
            QueueDb.log(this,"DIAG complete; no messages sent");
        } finally {root.recycle();}
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
        String digits=onlyDigits(expectedPhone);
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
            String normalized=onlyDigits(label);
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
            if(r.isEmpty() || r.centerY()<screenHeight*0.35)continue;
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
                    id.endsWith("/chat_send") ||
                    (id.contains("send") && !id.contains("resend") && !id.contains("send_as"));
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
    private static String onlyDigits(String s) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(c>='۰' && c<='۹')c=(char)('0'+c-'۰');
            else if(c>='٠' && c<='٩')c=(char)('0'+c-'٠');
            if(c>='0' && c<='9')out.append(c);
        }
        return out.toString();
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
