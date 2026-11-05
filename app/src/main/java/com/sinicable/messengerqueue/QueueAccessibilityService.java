package com.sinicable.messengerqueue;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.Intent;
import android.app.KeyguardManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Build;
import android.content.pm.PackageInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Three independent durable messenger queues sharing one foreground Accessibility engine.
 * Text is verified on-screen. Optional image uses Android ACTION_SEND + EXTRA_STREAM.
 * "submitted" means the relevant Send UI action completed, not server delivery/read receipt.
 */
public class QueueAccessibilityService extends AccessibilityService {
    static volatile QueueAccessibilityService INSTANCE;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private static final long STEP_WAIT_MS=850;
    private static final long ATTEMPT_TIMEOUT_MS=70000;
    private volatile boolean running=false;
    private volatile boolean paused=false;

    private int rowIndex=-1,stage=0,generation=0,safeRetries=0,searchVariant=0;
    private int processed=0,bubblesBefore=0;
    private long launchedAt=0,lastActionAt=0,sendActionAt=0,searchStartedAt=0,pausedAt=0,lastUiEventAt=0;
    private long nextDueAt=0;
    private String currentMessenger="",phone="",targetName="",targetPackage="",message="",contactAlias="";
    private String imageUri="",imageMime="image/*";
    private boolean imageMode=false,triedAlias=false,newChatTapped=false,sendInProgress=false;
    private boolean waitingForNext=false,stepScheduled=false,awaitingResult=false,testOne=false;
    private volatile boolean diagnosticMode=false;
    private volatile boolean calibrationMode=false;
    private final ArrayList<String> calibrationApps=new ArrayList<>();
    private int calibrationIndex=0,calibrationRetries=0;
    private String calibrationProbe="MQS_PRECHECK";
    private PowerManager.WakeLock queueWakeLock;
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
                    if(!QueueDb.hasAnyPending(this)) {
                        QueueDb.put(this,"desired_running","0");
                        QueueDb.put(this,"desired_paused","0");
                        QueueDb.log(this,"Persistent START cleared: all enabled queues finished");
                        return;
                    }
                    message=QueueDb.get(this,"message","");
                    imageUri=QueueDb.get(this,"image_uri","");
                    imageMime=QueueDb.get(this,"image_mime","image/*");
                    testOne=false;
                    running=true;
                    acquireQueueWakeLock();
                    processed=0;
                    diagnosticMode=false;
                    if(wantsPause) {
                        paused=true;
                        QueueDb.log(this,"Persistent PAUSE restored after service reconnect");
                    } else {
                        paused=false;
                        if(calibrationStillValid()) {
                            QueueDb.log(this,"Persistent START restored with valid reliability fingerprint");
                            scheduleEarliest(1000);
                        } else {
                            QueueDb.log(this,"Persistent START requires fresh reliability calibration");
                            beginCalibration();
                        }
                    }
                }
            },1200);
        }
    }

    @Override public void onDestroy() {
        running=false;
        generation++;
        releaseQueueWakeLock();
        INSTANCE=null;
        super.onDestroy();
    }
    @Override public void onInterrupt() { }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        lastUiEventAt=android.os.SystemClock.uptimeMillis();
        if(!running||paused)return;
        if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
           e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) schedule(450);
    }

    synchronized void startQueue() {
        if(running && paused) {togglePause();return;}
        if(running)return;
        QueueDb.recoverInterrupted(this);
        if(!QueueDb.hasAnyPending(this)) {
            QueueDb.log(this,"START blocked: no pending item in enabled queues");
            return;
        }
        running=true;
        paused=false;
        processed=0;
        diagnosticMode=false;
        calibrationMode=false;
        testOne="1".equals(QueueDb.get(this,"testOne","0"));
        message=QueueDb.get(this,"message","");
        imageUri=QueueDb.get(this,"image_uri","");
        imageMime=QueueDb.get(this,"image_mime","image/*");
        QueueDb.put(this,"desired_running",testOne?"0":"1");
        QueueDb.put(this,"desired_paused","0");
        acquireQueueWakeLock();
        if(testOne) {
            QueueDb.log(this,"TEST ONE TASK START");
            scheduleEarliest(1000);
        } else {
            QueueDb.log(this,"HIGH RELIABILITY START: automatic dry-run calibration begins");
            beginCalibration();
        }
    }

    synchronized void togglePause() {
        if(!running) {startQueue();return;}
        paused=!paused;
        long now=System.currentTimeMillis();
        if(paused) {
            pausedAt=now;
        } else {
            if(pausedAt>0) {
                long duration=Math.max(0,now-pausedAt);
                for(String app:QueueDb.APPS) {
                    long due=QueueDb.due(this,app);
                    if(due>0)QueueDb.due(this,app,due+duration);
                }
                launchedAt+=duration;
                if(searchStartedAt>0)searchStartedAt+=duration;
                if(sendActionAt>0)sendActionAt+=duration;
                nextDueAt+=duration;
                pausedAt=0;
            }
        }
        QueueDb.put(this,"desired_paused",paused?"1":"0");
        QueueDb.log(this,paused?"All queues paused (persistent)":"All queues resumed");
        if(!paused) {
            if(waitingForNext && rowIndex<0 && !testOne && !calibrationStillValid()) beginCalibration();
            else if(waitingForNext) scheduleEarliest(300);
            else schedule(300);
        }
    }

    synchronized void stopQueue() {
        boolean wasRunning=running;
        running=false;
        paused=false;
        QueueDb.put(this,"desired_running","0");
        QueueDb.put(this,"desired_paused","0");
        generation++;
        if(scheduled!=null)handler.removeCallbacks(scheduled);
        if(wasRunning && rowIndex>=0 && !currentMessenger.isEmpty()) {
            String state=QueueDb.state(this,rowIndex,currentMessenger);
            if("in_flight".equals(state)) {
                if(!imageMode && stage<4 && !sendInProgress && !awaitingResult) {
                    QueueDb.status(this,rowIndex,currentMessenger,"pending");
                    QueueDb.log(this,currentMessenger+" current attempt safely returned to Pending");
                } else if(imageMode && stage<23 && !sendInProgress && !awaitingResult) {
                    QueueDb.status(this,rowIndex,currentMessenger,"image_pending");
                    QueueDb.log(this,currentMessenger+" image attempt safely returned to Image Pending");
                } else {
                    QueueDb.status(this,rowIndex,currentMessenger,"review");
                    QueueDb.log(this,currentMessenger+" current attempt marked REVIEW to prevent duplicate send");
                }
            }
        }
        waitingForNext=false;
        calibrationMode=false;
        releaseQueueWakeLock();
        QueueDb.log(this,"All automatic queues stopped by user");
    }

    String state() {
        if(!running)return "STOPPED";
        if(paused)return "PAUSED";
        if(calibrationMode)return "RELIABILITY GATE: "+currentMessenger+" / "+phone;
        if(waitingForNext)return "WAITING FOR NEXT DUE QUEUE";
        return "RUNNING: "+currentMessenger+" / "+phone+(imageMode?" / IMAGE":" / TEXT");
    }

    private String chooseNextMessenger() {
        long now=System.currentTimeMillis();
        String best="";
        long bestDue=Long.MAX_VALUE;
        for(String app:QueueDb.APPS) {
            if(!QueueDb.enabled(this,app)||!QueueDb.hasPending(this,app))continue;
            long due=QueueDb.due(this,app);
            if(due<=0)due=now;
            if(due<bestDue) {bestDue=due;best=app;}
        }
        return best;
    }

    private void scheduleEarliest(long minimumDelay) {
        if(!running||paused)return;
        if(!screenReady()) {
            waitingForNext=true;
            generation++;
            int screenToken=generation;
            QueueDb.log(this,"Queue waiting: screen locked/off; state preserved");
            handler.postDelayed(()->{
                if(screenToken==generation&&running&&!paused){
                    waitingForNext=false;
                    scheduleEarliest(0);
                }
            },5000);
            return;
        }
        if(testOne && processed>=1) {
            running=false;
            waitingForNext=false;
            QueueDb.put(this,"desired_running","0");
            QueueDb.put(this,"desired_paused","0");
            QueueDb.log(this,"TEST ONE TASK FINISHED");
            return;
        }
        String app=chooseNextMessenger();
        if(app.isEmpty()) {
            running=false;
            waitingForNext=false;
            QueueDb.put(this,"desired_running","0");
            QueueDb.put(this,"desired_paused","0");
            QueueDb.log(this,"All enabled queues complete");
            releaseQueueWakeLock();
            return;
        }
        long now=System.currentTimeMillis();
        long due=Math.max(now,QueueDb.due(this,app));
        long wait=Math.max(Math.max(0,minimumDelay),due-now);
        waitingForNext=true;
        nextDueAt=android.os.SystemClock.elapsedRealtime()+wait;
        generation++;
        if(scheduled!=null)handler.removeCallbacks(scheduled);
        int token=generation;
        scheduled=()->{
            if(token!=generation||!running||paused)return;
            waitingForNext=false;
            String selected=chooseNextMessenger();
            if(selected.isEmpty()){scheduleEarliest(0);return;}
            long selectedDue=QueueDb.due(this,selected);
            long remaining=Math.max(0,selectedDue-System.currentTimeMillis());
            if(remaining>250) {scheduleEarliest(remaining);return;}
            currentMessenger=selected;
            targetName=selected;
            targetPackage=packageFor(selected);
            rowIndex=QueueDb.next(this,selected);
            if(rowIndex<0){scheduleEarliest(0);return;}
            phone=QueueDb.phone(this,rowIndex);
            String state=QueueDb.state(this,rowIndex,selected);
            safeRetries=0;
            if("image_pending".equals(state)) {
                launchImageShare();
            } else {
                QueueDb.status(this,rowIndex,selected,"in_flight");
                launchText();
            }
        };
        handler.postDelayed(scheduled,wait);
    }

    private String packageFor(String app) {
        switch(app) {
            case "Bale":return "ir.nasim";
            case "Rubika":return "app.rbmain.a";
            case "Eitaa":return "ir.eitaa.messenger";
            default:return "";
        }
    }

    private void resetAttempt(boolean image) {
        imageMode=image;
        stage=image?20:0;
        lastActionAt=0;
        searchStartedAt=0;
        triedAlias=false;
        searchVariant=0;
        newChatTapped=false;
        sendInProgress=false;
        awaitingResult=false;
        contactAlias="1".equals(QueueDb.get(this,"addContacts","0"))
                ? ContactBridge.ensure(this,phone):"";
        launchedAt=System.currentTimeMillis();
    }

    private void launchText() {
        if(!running||paused)return;
        generation++;
        resetAttempt(false);
        QueueDb.log(this,"TEXT "+currentMessenger+" -> "+phone);
        try {
            Intent open=getPackageManager().getLaunchIntentForPackage(targetPackage);
            if(open==null){failAttempt("App not installed or launcher missing");return;}
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
            schedule(1700);
        } catch(Exception ex){failAttempt("Cannot open app: "+ex.getClass().getSimpleName());}
    }

    private void launchImageShare() {
        if(!running||paused)return;
        if(imageUri==null||imageUri.trim().isEmpty()) {
            completeTask("Text UI confirmed; no image selected");
            return;
        }
        generation++;
        resetAttempt(true);
        QueueDb.status(this,rowIndex,currentMessenger,"in_flight");
        QueueDb.log(this,"IMAGE "+currentMessenger+" -> "+phone);
        try {
            Uri image=Uri.parse(imageUri);
            grantUriPermission(targetPackage,image,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent share=new Intent(Intent.ACTION_SEND);
            share.setPackage(targetPackage);
            share.setType(imageMime==null||imageMime.isEmpty()?"image/*":imageMime);
            share.putExtra(Intent.EXTRA_STREAM,image);
            share.setClipData(ClipData.newRawUri("image",image));
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(share);
            schedule(1800);
        } catch(Exception ex){
            QueueDb.status(this,rowIndex,currentMessenger,"image_pending");
            failAttempt("Cannot share image: "+ex.getClass().getSimpleName());
        }
    }

    private synchronized void failAttempt(String reason) {
        if(!running||rowIndex<0)return;
        if(sendInProgress||awaitingResult) {
            markReview("Could not confirm Send on screen: "+reason);
            return;
        }
        QueueDb.log(this,currentMessenger+" attempt failed: "+reason);
        if(safeRetries<2) {
            safeRetries++;
            QueueDb.log(this,currentMessenger+" SAFE RETRY "+safeRetries+"/2 before Send");
            generation++;
            int token=generation;
            handler.postDelayed(()->{
                if(!running||paused||token!=generation)return;
                if(imageMode)launchImageShare(); else launchText();
            },1400);
            return;
        }
        if(imageMode) {
            QueueDb.status(this,rowIndex,currentMessenger,"review");
            QueueDb.log(this,"REVIEW "+phone+" via "+currentMessenger+": image could not be sent safely");
        } else {
            QueueDb.status(this,rowIndex,currentMessenger,"skipped");
            QueueDb.log(this,"SKIP "+phone+" via "+currentMessenger+": "+reason);
        }
        processed++;
        finishMessengerSlot();
    }

    private long delayMs(String app) {
        return QueueDb.delayMinutes(this,app)*60000L;
    }

    private void finishMessengerSlot() {
        QueueDb.due(this,currentMessenger,System.currentTimeMillis()+delayMs(currentMessenger));
        imageMode=false;
        sendInProgress=false;
        awaitingResult=false;
        rowIndex=-1;
        phone="";
        currentMessenger="";
        targetName="";
        targetPackage="";
        scheduleEarliest(500);
    }

    private void schedule(long ms) {
        if(stepScheduled||waitingForNext||!running||paused)return;
        stepScheduled=true;
        int token=generation;
        handler.postDelayed(()->{
            stepScheduled=false;
            if(token==generation&&running&&!paused&&!waitingForNext)step();
        },ms);
    }

    private void step() {
        if(!running||paused||waitingForNext)return;
        if(calibrationMode){calibrationStep();return;}
        if(!uiSettled()){schedule(220);return;}
        long now=System.currentTimeMillis();
        if(now-launchedAt>ATTEMPT_TIMEOUT_MS){failAttempt("Timed out waiting for verified UI");return;}
        if(now-lastActionAt<STEP_WAIT_MS){schedule(STEP_WAIT_MS);return;}
        AccessibilityNodeInfo root=targetWindowRoot();
        if(root==null){schedule(1000);return;}
        try {
            if(!targetPackage.equals(String.valueOf(root.getPackageName()))){schedule(900);return;}

            if(stage==0||stage==20) {
                AccessibilityNodeInfo search=findByWords(root,
                        new String[]{"search","جستجو","جست و جو","search_icon","search_button","ic_search"},true);
                if(search!=null&&click(search)){
                    stage=imageMode?21:1;lastActionAt=now;schedule(800);return;
                }
                if(findSearchEditor(root)!=null){stage=imageMode?21:1;schedule(250);return;}
                if(!newChatTapped) {
                    AccessibilityNodeInfo newChat=findByWords(root,
                            new String[]{"new chat","new message","پیام جدید","گفتگوی جدید","گفت و گوی جدید","compose","ic_edit","fab"},true);
                    if(newChat!=null&&click(newChat)){
                        newChatTapped=true;lastActionAt=now;schedule(900);return;
                    }
                }
            } else if(stage==1||stage==21) {
                AccessibilityNodeInfo input=findSearchEditor(root);
                String query=currentSearchQuery();
                if(input!=null&&!query.isEmpty()&&setText(input,query)){
                    stage=imageMode?22:2;searchStartedAt=now;lastActionAt=now;schedule(1500);return;
                }
            } else if(stage==2||stage==22) {
                AccessibilityNodeInfo currentSearch=findSearchEditor(root);
                String expectedQuery=currentSearchQuery();
                String actualQuery=currentSearch==null||currentSearch.getText()==null?"":currentSearch.getText().toString();
                if(currentSearch!=null&&!expectedQuery.isEmpty()&&!onlyDigits(actualQuery).contains(
                        onlyDigits(expectedQuery).length()>=10?
                        onlyDigits(expectedQuery).substring(onlyDigits(expectedQuery).length()-10):
                        onlyDigits(expectedQuery)) && searchVariant<2) {
                    stage=imageMode?21:1;
                    schedule(250);return;
                }
                AccessibilityNodeInfo match=findVerifiedResult(root,phone,searchVariant>=2?contactAlias:"");
                if(match!=null&&click(match)){
                    stage=imageMode?23:3;lastActionAt=now;
                    QueueDb.log(this,"Matched verified contact in "+currentMessenger);
                    schedule(1200);return;
                }
                if(now-searchStartedAt>7000){
                    if(advanceSearchVariant()) {
                        stage=imageMode?21:1;
                        searchStartedAt=now;
                        schedule(300);return;
                    }
                }
            } else if(stage==3) {
                AccessibilityNodeInfo composer=findChatComposer(root);
                if(composer!=null&&setText(composer,message)){
                    stage=4;lastActionAt=now;schedule(900);return;
                }
            } else if(stage==4) {
                if(sendInProgress)return;
                AccessibilityNodeInfo composer=findChatComposer(root);
                if(composer!=null&&message.contentEquals(composer.getText()==null?"":composer.getText())){
                    AccessibilityNodeInfo send=findSendNearComposer(root,composer);
                    if(send!=null){
                        bubblesBefore=countVisibleMessage(root,message);
                        sendInProgress=true;
                        clickSend(send,false);
                        return;
                    }
                }
            } else if(stage==5) {
                if(now-sendActionAt>=1500){
                    AccessibilityNodeInfo editor=findChatComposer(root);
                    String draft=editor==null||editor.getText()==null?"":editor.getText().toString();
                    if(draft.trim().isEmpty()&&countVisibleMessage(root,message)>bubblesBefore){
                        finishTextSubmitted("composer cleared and text bubble visible");
                        return;
                    }
                    if(now-sendActionAt>9000){markReview("Text Send occurred but message bubble was not verified");return;}
                }
            } else if(stage==23) {
                if(sendInProgress)return;
                AccessibilityNodeInfo send=findGenericSend(root);
                if(send!=null){
                    sendInProgress=true;
                    clickSend(send,true);
                    return;
                }
                if(now-lastActionAt>9000){
                    markReview("Image recipient selected but a trustworthy Send control was not found");
                    return;
                }
            } else if(stage==24) {
                if(now-sendActionAt>=1800){
                    AccessibilityNodeInfo composer=findChatComposer(root);
                    AccessibilityNodeInfo shareSend=findGenericSend(root);
                    if(composer!=null&&shareSend==null) {
                        completeTask("Text verified + image Send returned to chat UI");
                        return;
                    }
                    if(now-sendActionAt>10000) {
                        markReview("Image Send was invoked but return to chat UI was not verified");
                        return;
                    }
                }
            }
        } catch(Exception e){
            QueueDb.log(this,"UI engine error at stage "+stage+": "+e.getClass().getSimpleName());
        } finally {root.recycle();}
        schedule(900);
    }

    private void clickSend(AccessibilityNodeInfo target,boolean imageSend) {
        final int token=generation;
        final int sendingRow=rowIndex;
        if(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
            beginVerification(imageSend);return;
        }
        Rect r=new Rect();target.getBoundsInScreen(r);
        if(r.isEmpty()){sendInProgress=false;return;}
        Path p=new Path();p.moveTo(r.centerX(),r.centerY());
        GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(p,0,100);
        try {
            boolean initiated=dispatchGesture(
                    new GestureDescription.Builder().addStroke(stroke).build(),
                    new GestureResultCallback(){
                        @Override public void onCompleted(GestureDescription g){
                            if(token==generation&&sendingRow==rowIndex&&running)beginVerification(imageSend);
                        }
                        @Override public void onCancelled(GestureDescription g){
                            if(token==generation&&sendingRow==rowIndex){
                                sendInProgress=false;
                                QueueDb.log(QueueAccessibilityService.this,"Send gesture cancelled");
                            }
                        }
                    },handler);
            if(!initiated)sendInProgress=false;
        } catch(Exception ex){
            sendInProgress=false;
            QueueDb.log(this,"Send gesture failed: "+ex.getClass().getSimpleName());
        }
    }

    private void beginVerification(boolean imageSend) {
        stage=imageSend?24:5;
        sendActionAt=System.currentTimeMillis();
        awaitingResult=true;
        QueueDb.log(this,(imageSend?"Image":"Text")+" Send action invoked; verifying UI...");
        schedule(imageSend?1600:1100);
    }

    private int countVisibleMessage(AccessibilityNodeInfo root,String target) {
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        collect(root,nodes,0,40);
        int count=0;
        for(AccessibilityNodeInfo node:nodes){
            if(node.isEditable()||!node.isVisibleToUser())continue;
            String txt=node.getText()==null?"":node.getText().toString();
            if(target.equals(txt))count++;
        }
        return count;
    }

    private synchronized void markReview(String reason) {
        if(!running||rowIndex<0)return;
        QueueDb.status(this,rowIndex,currentMessenger,"review");
        QueueDb.log(this,"REVIEW REQUIRED "+phone+" via "+currentMessenger+": "+reason);
        processed++;
        finishMessengerSlot();
    }

    private synchronized void finishTextSubmitted(String detail) {
        if(!running||rowIndex<0)return;
        QueueDb.log(this,"TEXT UI CONFIRMED "+phone+" via "+currentMessenger+" ("+detail+")");
        sendInProgress=false;
        awaitingResult=false;
        if(imageUri!=null&&!imageUri.trim().isEmpty()){
            QueueDb.status(this,rowIndex,currentMessenger,"image_pending");
            handler.postDelayed(()->{
                if(running&&!paused&&rowIndex>=0)launchImageShare();
            },800);
        } else {
            completeTask("Text UI confirmed");
        }
    }

    private synchronized void completeTask(String detail) {
        if(!running||rowIndex<0)return;
        QueueDb.status(this,rowIndex,currentMessenger,"submitted");
        QueueDb.log(this,"SUBMITTED "+phone+" via "+currentMessenger+" ("+detail+")");
        processed++;
        finishMessengerSlot();
    }


    private boolean uiSettled() {
        long last=lastUiEventAt;
        if(last<=0)return true;
        return android.os.SystemClock.uptimeMillis()-last>=420L;
    }

    private String currentSearchQuery() {
        if(searchVariant<=0)return phone;
        if(searchVariant==1)return ContactImporter.international(phone);
        return contactAlias==null?"":contactAlias;
    }

    private boolean advanceSearchVariant() {
        if(searchVariant==0) {
            String intl=ContactImporter.international(phone);
            if(intl!=null&&!intl.isEmpty()&&!intl.equals(phone)) {
                searchVariant=1;
                QueueDb.log(this,"Search fallback: international number in "+currentMessenger);
                return true;
            }
        }
        if(searchVariant<=1 && contactAlias!=null && !contactAlias.isEmpty()) {
            searchVariant=2;
            triedAlias=true;
            QueueDb.log(this,"Search fallback: synced MQS alias in "+currentMessenger);
            return true;
        }
        return false;
    }

    private boolean screenReady() {
        try {
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            KeyguardManager km=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
            boolean interactive=pm==null||pm.isInteractive();
            boolean locked=km!=null&&km.isKeyguardLocked();
            return interactive&&!locked;
        } catch(Exception e){return true;}
    }

    private boolean calibrationStillValid() {
        try {
            long t=Long.parseLong(QueueDb.get(this,"reliability_gate_time","0"));
            if(!"PASS".equals(QueueDb.get(this,"reliability_gate_last","")))return false;
            if(t<=0||System.currentTimeMillis()-t>12L*60L*60L*1000L)return false;
            String imageFlag=(imageUri==null||imageUri.isEmpty())?"0":"1";
            for(String app:QueueDb.APPS) {
                if(!QueueDb.enabled(this,app)||!QueueDb.hasPending(this,app))continue;
                String key=app.toLowerCase(Locale.US);
                if(!"PASS".equals(QueueDb.get(this,"calib_"+key,"")))return false;
                if(!versionFingerprint(packageFor(app)).equals(QueueDb.get(this,"calib_version_"+key,"")))return false;
                if(!imageFlag.equals(QueueDb.get(this,"calib_image_"+key,"")))return false;
                if(!String.valueOf(imageUri).equals(QueueDb.get(this,"calib_image_uri_"+key,"")))return false;
                if(!String.valueOf(imageMime).equals(QueueDb.get(this,"calib_image_mime_"+key,"")))return false;
            }
            return true;
        } catch(Exception e){return false;}
    }

    private void acquireQueueWakeLock() {
        try {
            if(queueWakeLock!=null&&queueWakeLock.isHeld())return;
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if(pm==null)return;
            int flags=PowerManager.PARTIAL_WAKE_LOCK;
            if("1".equals(QueueDb.get(this,"keep_awake","1"))) {
                flags=PowerManager.SCREEN_BRIGHT_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP|PowerManager.ON_AFTER_RELEASE;
            }
            queueWakeLock=pm.newWakeLock(flags,"MessengerQueueSkip:Queue");
            queueWakeLock.setReferenceCounted(false);
            queueWakeLock.acquire();
            QueueDb.log(this,"Queue wake lock acquired");
        } catch(Exception e) {
            QueueDb.log(this,"Wake lock unavailable: "+e.getClass().getSimpleName());
        }
    }

    private void releaseQueueWakeLock() {
        try {
            if(queueWakeLock!=null&&queueWakeLock.isHeld())queueWakeLock.release();
        } catch(Exception ignored) {}
        queueWakeLock=null;
    }

    private void beginCalibration() {
        calibrationApps.clear();
        for(String app:QueueDb.APPS) {
            if(QueueDb.enabled(this,app)&&QueueDb.hasPending(this,app)) {
                calibrationApps.add(app);
                QueueDb.put(this,"calib_"+app.toLowerCase(Locale.US),"WAITING");
            }
        }
        calibrationIndex=0;
        calibrationRetries=0;
        calibrationMode=true;
        if(calibrationApps.isEmpty()) {
            calibrationFail("No enabled queue has a pending contact");
            return;
        }
        launchCalibrationCurrent();
    }

    private void launchCalibrationCurrent() {
        if(!running||paused)return;
        if(!screenReady()) {
            QueueDb.log(this,"Reliability gate waiting for unlocked interactive screen");
            int token=generation;
            handler.postDelayed(()->{
                if(token==generation&&running&&!paused&&calibrationMode)launchCalibrationCurrent();
            },5000);
            return;
        }
        if(calibrationIndex>=calibrationApps.size()) {
            calibrationSuccessAll();
            return;
        }
        currentMessenger=calibrationApps.get(calibrationIndex);
        targetName=currentMessenger;
        targetPackage=packageFor(currentMessenger);
        rowIndex=QueueDb.next(this,currentMessenger);
        if(rowIndex<0) {
            calibrationPassCurrent("No pending contact remained");
            return;
        }
        phone=QueueDb.phone(this,rowIndex);
        resetAttempt(false);
        stage=100;
        calibrationMode=true;
        QueueDb.put(this,"calib_"+currentMessenger.toLowerCase(Locale.US),"CHECKING");
        QueueDb.log(this,"RELIABILITY DRY-RUN "+currentMessenger+" -> "+phone+" (NO SEND)");
        try {
            Intent open=getPackageManager().getLaunchIntentForPackage(targetPackage);
            if(open==null){calibrationFail("Launcher missing for "+currentMessenger);return;}
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
            schedule(1700);
        } catch(Exception e) {
            calibrationFail("Cannot open "+currentMessenger+": "+e.getClass().getSimpleName());
        }
    }

    private void calibrationStep() {
        if(!calibrationMode||!running||paused)return;
        if(!uiSettled()){schedule(220);return;}
        long now=System.currentTimeMillis();
        if(now-launchedAt>60000L) {
            calibrationFail("Dry-run timeout at stage "+stage);
            return;
        }
        if(now-lastActionAt<STEP_WAIT_MS){schedule(STEP_WAIT_MS);return;}
        AccessibilityNodeInfo root=targetWindowRoot();
        if(root==null){schedule(850);return;}
        try {
            if(!targetPackage.equals(String.valueOf(root.getPackageName()))){schedule(750);return;}

            if(stage==100||stage==120) {
                AccessibilityNodeInfo search=findByWords(root,
                        new String[]{"search","جستجو","جست و جو","search_icon","search_button","ic_search"},true);
                if(search!=null&&click(search)) {
                    stage=stage==120?121:101;lastActionAt=now;schedule(700);return;
                }
                if(findSearchEditor(root)!=null) {
                    stage=stage==120?121:101;schedule(200);return;
                }
                if(!newChatTapped) {
                    AccessibilityNodeInfo newChat=findByWords(root,
                            new String[]{"new chat","new message","پیام جدید","گفتگوی جدید",
                                    "گفت و گوی جدید","compose","ic_edit","fab"},true);
                    if(newChat!=null&&click(newChat)) {
                        newChatTapped=true;lastActionAt=now;schedule(800);return;
                    }
                }
            } else if(stage==101||stage==121) {
                AccessibilityNodeInfo input=findSearchEditor(root);
                String query=currentSearchQuery();
                if(input!=null&&!query.isEmpty()&&setText(input,query)) {
                    stage=stage==121?122:102;searchStartedAt=now;lastActionAt=now;schedule(1200);return;
                }
            } else if(stage==102||stage==122) {
                AccessibilityNodeInfo currentSearch=findSearchEditor(root);
                String expectedQuery=currentSearchQuery();
                String actualQuery=currentSearch==null||currentSearch.getText()==null?"":currentSearch.getText().toString();
                if(currentSearch!=null&&searchVariant<2&&!expectedQuery.isEmpty()&&!onlyDigits(actualQuery).contains(
                        onlyDigits(expectedQuery).length()>=10?
                        onlyDigits(expectedQuery).substring(onlyDigits(expectedQuery).length()-10):
                        onlyDigits(expectedQuery))) {
                    stage=stage==122?121:101;
                    schedule(250);return;
                }
                AccessibilityNodeInfo match=findVerifiedResult(root,phone,searchVariant>=2?contactAlias:"");
                if(match!=null&&click(match)) {
                    stage=stage==122?123:103;
                    lastActionAt=now;
                    schedule(1000);
                    return;
                }
                if(now-searchStartedAt>6500) {
                    if(advanceSearchVariant()) {
                        stage=stage==122?121:101;
                        searchStartedAt=now;
                        schedule(250);return;
                    }
                }
            } else if(stage==103) {
                AccessibilityNodeInfo composer=findChatComposer(root);
                if(composer!=null&&setText(composer,calibrationProbe)) {
                    stage=104;lastActionAt=now;schedule(650);return;
                }
            } else if(stage==104) {
                AccessibilityNodeInfo composer=findChatComposer(root);
                if(composer!=null&&calibrationProbe.contentEquals(
                        composer.getText()==null?"":composer.getText())) {
                    AccessibilityNodeInfo send=findSendNearComposer(root,composer);
                    if(send!=null) {
                        setText(composer,"");
                        QueueDb.log(this,"DRY-RUN "+currentMessenger+": text route PASS");
                        if(imageUri!=null&&!imageUri.trim().isEmpty()) {
                            stage=119;
                            handler.postDelayed(this::launchCalibrationImage,500);
                        } else {
                            calibrationPassCurrent("text route");
                        }
                        return;
                    }
                }
            } else if(stage==123) {
                AccessibilityNodeInfo send=findGenericSend(root);
                if(send!=null) {
                    QueueDb.log(this,"DRY-RUN "+currentMessenger+": image route PASS");
                    stage=199;
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    handler.postDelayed(()->performGlobalAction(GLOBAL_ACTION_BACK),300);
                    calibrationPassCurrent("text + image routes");
                    return;
                }
            }
        } catch(Exception e) {
            QueueDb.log(this,"Dry-run UI error "+currentMessenger+" stage "+stage+": "+e.getClass().getSimpleName());
        } finally {root.recycle();}
        schedule(700);
    }

    private void launchCalibrationImage() {
        if(!calibrationMode||!running||paused)return;
        try {
            imageMode=true;
            triedAlias=false;
            searchVariant=0;
            newChatTapped=false;
            searchStartedAt=0;
            lastActionAt=0;
            launchedAt=System.currentTimeMillis();
            stage=120;
            Uri image=Uri.parse(imageUri);
            grantUriPermission(targetPackage,image,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent share=new Intent(Intent.ACTION_SEND);
            share.setPackage(targetPackage);
            share.setType("image/*");
            share.putExtra(Intent.EXTRA_STREAM,image);
            share.setClipData(ClipData.newRawUri("image",image));
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(share);
            schedule(1500);
        } catch(Exception e) {
            calibrationFail("Image dry-run failed: "+e.getClass().getSimpleName());
        }
    }

    private void calibrationPassCurrent(String detail) {
        if(!calibrationMode)return;
        String key=currentMessenger.toLowerCase(Locale.US);
        QueueDb.put(this,"calib_"+key,"PASS");
        QueueDb.put(this,"calib_version_"+key,versionFingerprint(targetPackage));
        QueueDb.put(this,"calib_image_"+key,(imageUri==null||imageUri.isEmpty())?"0":"1");
        QueueDb.put(this,"calib_image_uri_"+key,String.valueOf(imageUri));
        QueueDb.put(this,"calib_image_mime_"+key,String.valueOf(imageMime));
        QueueDb.put(this,"calib_time_"+key,String.valueOf(System.currentTimeMillis()));
        QueueDb.log(this,"RELIABILITY PASS "+currentMessenger+" ("+detail+")");
        imageMode=false;
        calibrationRetries=0;
        calibrationIndex++;
        generation++;
        int token=generation;
        handler.postDelayed(()->{
            if(token==generation&&running&&!paused)launchCalibrationCurrent();
        },650);
    }

    private void calibrationFail(String reason) {
        if(!running)return;
        if(calibrationMode&&calibrationIndex<calibrationApps.size()&&calibrationRetries<2) {
            calibrationRetries++;
            QueueDb.log(this,"DRY-RUN RETRY "+calibrationRetries+"/2 "+currentMessenger+": "+reason);
            performGlobalAction(GLOBAL_ACTION_BACK);
            generation++;
            int token=generation;
            handler.postDelayed(()->{
                if(token==generation&&running&&!paused)launchCalibrationCurrent();
            },900);
            return;
        }
        if(calibrationMode&&calibrationIndex<calibrationApps.size()) {
            String key=currentMessenger.toLowerCase(Locale.US);
            QueueDb.put(this,"calib_"+key,"FAIL");
            QueueDb.put(this,"reliability_gate_last","FAIL");
        }
        QueueDb.log(this,"RELIABILITY GATE FAILED: "+reason+"; no automatic messages started");
        calibrationMode=false;
        running=false;
        paused=false;
        waitingForNext=false;
        QueueDb.put(this,"desired_running","0");
        QueueDb.put(this,"desired_paused","0");
        releaseQueueWakeLock();
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private void calibrationSuccessAll() {
        calibrationMode=false;
        imageMode=false;
        rowIndex=-1;
        phone="";
        currentMessenger="";
        targetName="";
        targetPackage="";
        QueueDb.put(this,"reliability_gate_last","PASS");
        QueueDb.put(this,"reliability_gate_time",String.valueOf(System.currentTimeMillis()));
        long now=System.currentTimeMillis();
        for(String app:QueueDb.APPS) {
            if(QueueDb.enabled(this,app)&&QueueDb.hasPending(this,app))QueueDb.due(this,app,now);
        }
        QueueDb.log(this,"RELIABILITY GATE PASS: all enabled messenger dry-runs passed; automatic queues released");
        scheduleEarliest(700);
    }

    private String versionFingerprint(String pkg) {
        try {
            PackageInfo p=getPackageManager().getPackageInfo(pkg,0);
            long code=Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;
            return String.valueOf(p.versionName)+":"+code;
        } catch(Exception e){return "unknown";}
    }

    private AccessibilityNodeInfo findGenericSend(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo learned=findLearnedSelector(root,"image_send",false,true);
        if(learned!=null)return learned;
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        collect(root,nodes,0,40);
        int h=getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best=null;
        int bestY=-1;
        for(AccessibilityNodeInfo n:nodes){
            if(!n.isEnabled()||!n.isVisibleToUser()||n.isEditable())continue;
            String title=str(n.getText()).trim();
            String desc=str(n.getContentDescription()).trim();
            String id=str(n.getViewIdResourceName());
            boolean match=title.equals("send")||title.equals("ارسال")||
                    desc.equals("send")||desc.equals("send message")||
                    desc.equals("ارسال")||desc.equals("ارسال پیام")||
                    id.endsWith("/send")||id.endsWith("/send_button")||
                    id.endsWith("/btn_send")||id.endsWith("/ic_send")||
                    (id.contains("send")&&!id.contains("resend")&&!id.contains("send_as"));
            if(!match)continue;
            Rect r=new Rect();n.getBoundsInScreen(r);
            if(r.isEmpty()||r.centerY()<h*0.35)continue;
            AccessibilityNodeInfo action=clickable(n);
            if(action!=null&&r.centerY()>bestY){best=action;bestY=r.centerY();}
        }
        if(best!=null)rememberSelector("image_send",best);
        return best;
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
        AccessibilityNodeInfo learned=findLearnedSelector(root,"search_editor",true,false);
        if(learned!=null)return learned;
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
        if(best!=null)rememberSelector("search_editor",best);
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
        AccessibilityNodeInfo learned=findLearnedSelector(root,"composer",true,false);
        if(learned!=null)return learned;
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
        if(best!=null)rememberSelector("composer",best);
        return best;
    }

    private AccessibilityNodeInfo findSendNearComposer(AccessibilityNodeInfo root,
            AccessibilityNodeInfo composer) {
        AccessibilityNodeInfo learned=findLearnedSelector(root,"text_send",false,true);
        if(learned!=null) {
            Rect lr=new Rect();learned.getBoundsInScreen(lr);
            Rect er=new Rect();composer.getBoundsInScreen(er);
            if(!lr.isEmpty()&&!er.isEmpty()&&Math.abs(lr.centerY()-er.centerY())<=250)return learned;
        }
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
        if(best!=null)rememberSelector("text_send",best);
        return best;
    }

    private AccessibilityNodeInfo findLearnedSelector(AccessibilityNodeInfo root,String role,
            boolean mustEditable,boolean mustClickable) {
        if(root==null||currentMessenger==null||currentMessenger.isEmpty())return null;
        String app=currentMessenger.toLowerCase(Locale.US);
        String version=versionFingerprint(packageFor(currentMessenger));
        if(!version.equals(QueueDb.get(this,"selector_version_"+app+"_"+role,"")))return null;
        String id=QueueDb.get(this,"selector_id_"+app+"_"+role,"");
        if(id.isEmpty())return null;
        try {
            List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByViewId(id);
            if(nodes==null)return null;
            for(AccessibilityNodeInfo n:nodes) {
                if(n==null||!n.isVisibleToUser()||!n.isEnabled())continue;
                if(mustEditable&&!n.isEditable())continue;
                AccessibilityNodeInfo candidate=mustClickable?clickable(n):n;
                if(candidate!=null) {
                    QueueDb.put(this,"selector_last_hit_"+app+"_"+role,
                            String.valueOf(System.currentTimeMillis()));
                    return candidate;
                }
            }
        } catch(Exception ignored) {}
        return null;
    }

    private void rememberSelector(String role,AccessibilityNodeInfo node) {
        if(node==null||currentMessenger==null||currentMessenger.isEmpty())return;
        String id=strRaw(node.getViewIdResourceName());
        if(id.isEmpty())return;
        String app=currentMessenger.toLowerCase(Locale.US);
        QueueDb.put(this,"selector_id_"+app+"_"+role,id);
        QueueDb.put(this,"selector_class_"+app+"_"+role,strRaw(node.getClassName()));
        QueueDb.put(this,"selector_version_"+app+"_"+role,
                versionFingerprint(packageFor(currentMessenger)));
    }

    private static String strRaw(CharSequence c){return c==null?"":c.toString();}
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
