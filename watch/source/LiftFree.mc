using Toybox.Application;
using Toybox.Application.Storage;
using Toybox.Activity;
using Toybox.ActivityRecording;
using Toybox.Attention;
using Toybox.Communications;
using Toybox.FitContributor;
using Toybox.Graphics;
using Toybox.Math;
using Toybox.Position;
using Toybox.Sensor;
using Toybox.System;
using Toybox.Time;
using Toybox.Timer;
using Toybox.WatchUi;

// No bundled GPS locations. Routes are learned on the owner's watch only.
class LiftFreeApp extends Application.AppBase {
    var session=null; var detector=null; var timer=null;
    var phase=0; var state=0; var since=0; var sid="";
    var position=null; var positionAt=0; var altitude=null;
    var queue=[]; var base=0; var serial=0; var lastSentEnd=0;
    var busy=false; var lastSend=0; var phoneAt=0; var lastSampleAt=0;
    var startAt=0; var endAt=0; var timerMs=0; var metres=0.0;
    var overflow=false; var nativeSaved=false; var saveError=false;
    var stateField=null; var sinceField=null;
    var hint="Open phone companion"; var product=3289;
    function initialize() { AppBase.initialize(); }
    function onStart(options) {
        product=WatchUi.loadResource(Rez.Strings.Product).toNumber();
        detector=new LiftDetector();
        Communications.registerForPhoneAppMessages(method(:onPhone));
        Position.enableLocationEvents(Position.LOCATION_CONTINUOUS,method(:onPosition));
        Sensor.setEnabledSensors([Sensor.SENSOR_HEARTRATE]);
        var pending=Storage.getValue("transfer");
        if(pending!=null && pending["finished"]==true) {
            sid=pending["id"]; queue=pending["queue"]; base=pending["base"];
            serial=pending["count"]; startAt=pending["start"]; endAt=pending["end"];
            timerMs=pending["timer"]; nativeSaved=true; phase=3;
            hint="Resuming phone transfer";
        }
        timer=new Timer.Timer(); timer.start(method(:tick),1000,true);
    }
    function getInitialView() { return [new LFView(self),new LFKeys(self)]; }
    function now() { return Time.now().value(); }
    function onPosition(info) {
        if(info.position!=null && (info.accuracy==Position.QUALITY_GOOD || info.accuracy==Position.QUALITY_USABLE)) {
            position=info.position.toDegrees(); positionAt=now();
        }
    }
    function gpsGood() { return position!=null && now()-positionAt<=5; }
    function buzz() { if(Attention has :vibrate) { Attention.vibrate([new Attention.VibeProfile(60,160)]); } }
    function tick() {
        if(phase==1) { sample(); }
        pump(); WatchUi.requestUpdate();
    }
    function startRide() {
        if(!gpsGood()) { hint="Waiting for GPS"; return; }
        if(now()-phoneAt>20) { hint="Connect phone first"; return; }
        try {
            session=ActivityRecording.createSession({:name=>"LiftFree MTB",:sport=>Activity.SPORT_CYCLING,:subSport=>Activity.SUB_SPORT_MOUNTAIN});
            stateField=session.createField("LiftFreeState",0,FitContributor.DATA_TYPE_UINT8,{:mesgType=>FitContributor.MESG_TYPE_RECORD});
            sinceField=session.createField("LiftFreeSince",1,FitContributor.DATA_TYPE_UINT32,{:mesgType=>FitContributor.MESG_TYPE_RECORD,:units=>"unix_s"});
            startAt=now(); since=startAt; sid=startAt.toString(); state=0;
            queue=[]; base=0; serial=0; overflow=false; nativeSaved=false;
            endAt=0; lastSentEnd=0; lastSampleAt=0; saveError=false;
            stateField.setData(0); sinceField.setData(since);
            if(!session.start()) { hint="Recorder did not start"; session=null; return; }
            phase=1; hint="BACK: mark lift"; sample(); buzz();
        } catch(e) { hint="Recorder start failed"; }
    }
    function setState(s,t) {
        if(t<since) { t=since; }
        state=s; since=t;
        if(stateField!=null) { stateField.setData(s); sinceField.setData(t); }
        buzz();
    }
    function number(v,scale,missing) { return v==null?missing:(v*scale).toNumber(); }
    function sample() {
        if(overflow || session==null) { return; }
        var t=now();
        // Do not rely on queue contents: it may have just been acknowledged.
        if(t<=lastSampleAt) { return; }
        var a=Activity.getActivityInfo(); altitude=a.altitude;
        if(a.elapsedDistance!=null) { metres=a.elapsedDistance; }
        if(a.timerTime!=null) { timerMs=a.timerTime.toNumber(); }
        var p=gpsGood()?position:null;
        var transition=detector.update(t,p,altitude,a.currentSpeed,a.currentCadence,state);
        if(transition!=null) { setState(transition[0],transition[1]); }
        if(stateField!=null) { stateField.setData(state); sinceField.setData(since); }
        if(queue.size()>=180) { overflow=true; hint="PHONE LOST - backup only"; buzz(); return; }
        // UNIX seconds, deg*1e7, altitude decimetres, bpm, rpm, mm/s,
        // native distance cm, lift state, effective state transition UNIX time.
        var row=[t,p==null?-2147483648:number(p[0],10000000.0,-2147483648),p==null?-2147483648:number(p[1],10000000.0,-2147483648),number(altitude,10.0,-2147483648),number(a.currentHeartRate,1,255),number(a.currentCadence,1,255),number(a.currentSpeed,1000.0,-1),number(a.elapsedDistance,100.0,-1),state,since];
        queue.add(row); serial++; lastSampleAt=t;
        if(serial%10==0) { snapshot(); }
    }
    function snapshot() {
        try {
            Storage.setValue("transfer",{"id"=>sid,"base"=>base,"count"=>serial,"queue"=>queue,"start"=>startAt,"end"=>endAt,"timer"=>timerMs,"finished"=>phase==3 && !overflow});
        } catch(e) { hint="Storage error - keep phone near"; }
    }
    function send(payload) {
        busy=true; lastSend=now();
        try { Communications.transmit(payload,null,new LFTransmit(self)); }
        catch(e) { busy=false; }
    }
    function pump() {
        var t=now();
        if(busy && t-lastSend<15) { return; }
        if(t-lastSend<3) { return; }
        busy=false;
        if(phase==0 || phase==4) {
            if(t-lastSend>=5) { send({"v"=>3,"kind"=>"hello","id"=>"ready"}); }
            return;
        }
        if(overflow) { return; }
        if(queue.size()>0 && (queue.size()>=8 || phase==3 || t-lastSend>=10)) {
            var n=queue.size()<12?queue.size():12;
            var rows="";
            for(var i=0;i<n;i++) {
                var r=queue[i];
                for(var k=0;k<r.size();k++) { if(k>0) { rows+=","; } rows+=lfWireInteger(r[k]); }
                rows+="\n";
            }
            lastSentEnd=base+n;
            send({"v"=>3,"kind"=>"batch","id"=>sid,"base"=>base,"rows"=>rows,"model"=>product,"start"=>startAt});
        } else if(phase==3 && queue.size()==0) {
            send({"v"=>3,"kind"=>"finish","id"=>sid,"count"=>serial,"end"=>endAt,"timer"=>timerMs,"model"=>product,"start"=>startAt});
        }
    }
    function onPhone(message) {
        var d=message.data;
        if(!(d instanceof Dictionary) || d["v"]!=3) { return; }
        if(d["id"]=="ready") { phoneAt=now(); return; }
        if(d["id"]!=sid) { return; }
        phoneAt=now();
        var next=d["next"];
        if(next!=null && next>=base && next<=lastSentEnd && next<=serial) {
            var n=next-base;
            if(n<=queue.size()) { queue=queue.slice(n,queue.size()); base=next; snapshot(); }
        }
        if(phase==3 && d["saved"]==true && d["next"]==serial) {
            phase=4; hint="Corrected FIT saved on phone"; Storage.deleteValue("transfer"); buzz();
        }
    }
    function back() {
        if(phase==0 || phase==4) { System.exit(); return; }
        if(phase!=1) { return; }
        if(state==1 || state==2) {
            if(state==2) { detector.endManual(gpsGood()?position:null,altitude); }
            else { detector.forceRide(); }
            setState(3,now()); hint="RIDING forced";
        } else {
            detector.startManual(gpsGood()?position:null,altitude);
            setState(2,now()); hint="BACK at top";
        }
    }
    function forceRide() { if(phase==1) { detector.forceRide(); setState(3,now()); hint="RIDING forced"; } }
    function select() { if(phase==0) { startRide(); } else if(phase==4) { System.exit(); } else { menu(); } }
    function menu() {
        var m=new WatchUi.Menu2({:title=>"LiftFree MTB"});
        if(phase==1) {
            m.addItem(new WatchUi.MenuItem("Keep recording",null,:keep,null));
            m.addItem(new WatchUi.MenuItem("Finish and send","Garmin backup also saved",:finish,null));
            m.addItem(new WatchUi.MenuItem("Force riding",null,:ride,null));
        }
        if(phase==3) {
            m.addItem(new WatchUi.MenuItem("Keep transferring",null,:keep,null));
            m.addItem(new WatchUi.MenuItem("Close; transfer later","Reopen to resume",:later,null));
        }
        if(phase==0) { m.addItem(new WatchUi.MenuItem(detector.autoOn?"Auto lift ON":"Auto lift OFF","Learned route only",:auto,null)); }
        WatchUi.pushView(m,new LFMenu(self),WatchUi.SLIDE_UP);
    }
    function finish() {
        if(phase!=1) { return; }
        if(now()-startAt<2) { hint="Record at least 2 seconds"; return; }
        sample(); endAt=now();
        if(session!=null) {
            if(session.isRecording() && !session.stop()) { hint="Stop failed - try again"; return; }
            if(!session.save()) { hint="Garmin save failed - retry"; saveError=true; return; }
            nativeSaved=true; session=null; stateField=null; sinceField=null;
        }
        if(overflow) { phase=4; hint="BACKUP ONLY: phone was lost"; Storage.deleteValue("transfer"); }
        else { phase=3; hint="Keep phone nearby"; snapshot(); }
        WatchUi.requestUpdate();
    }
    function onStop(options) {
        if(timer!=null) { timer.stop(); }
        Position.enableLocationEvents(Position.LOCATION_DISABLE,method(:onPosition));
        if(phase==3) { snapshot(); }
        if(session!=null) {
            if(session.isRecording()) { session.stop(); }
            session.save();
            // An interrupted ride is preserved natively but never auto-uploaded
            // by the companion without a complete, explicit finish message.
        }
    }
}
class LFTransmit extends Communications.ConnectionListener {
    var app;
    function initialize(a) { ConnectionListener.initialize(); app=a; }
    function onComplete() { app.busy=false; }
    function onError() { app.busy=false; }
}
class LFView extends WatchUi.View {
    var app;
    function initialize(a) { View.initialize(); app=a; }
    function line(dc,y,font,text) { dc.drawText(dc.getWidth()/2,y,font,text,Graphics.TEXT_JUSTIFY_CENTER); }
    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_WHITE,Graphics.COLOR_BLACK); dc.clear();
        var h=dc.getHeight(); var title="READY";
        if(app.phase==1) { title=(app.state==1 || app.state==2)?"LIFT":"RIDING"; }
        if(app.phase==3) { title="TRANSFERRING"; }
        if(app.phase==4) { title=app.overflow?"BACKUP ONLY":"SAVED ON PHONE"; }
        line(dc,(h*0.10).toNumber(),Graphics.FONT_SMALL,"LIFTFREE MTB");
        line(dc,(h*0.27).toNumber(),Graphics.FONT_MEDIUM,title);
        var secs=(app.timerMs/1000).toNumber();
        line(dc,(h*0.43).toNumber(),Graphics.FONT_LARGE,(secs/60).toNumber().toString()+":"+(secs%60).format("%02d"));
        line(dc,(h*0.62).toNumber(),Graphics.FONT_SMALL,(app.metres/1609.344).format("%.1f")+" mi");
        var link=(app.now()-app.phoneAt<=20)?"PHONE CONNECTED":"CHECK PHONE";
        line(dc,(h*0.73).toNumber(),Graphics.FONT_XTINY,link+" | "+app.queue.size().toString()+" queued");
        var hint=app.hint;
        if(app.phase==0 && !app.gpsGood()) { hint="Waiting for GPS"; }
        if(app.phase==0 && app.gpsGood() && app.now()-app.phoneAt<=20) { hint="START: begin one ride"; }
        line(dc,(h*0.84).toNumber(),Graphics.FONT_XTINY,hint);
    }
}
class LFKeys extends WatchUi.BehaviorDelegate {
    var app;
    function initialize(a) { BehaviorDelegate.initialize(); app=a; }
    function onSelect() { app.select(); return true; }
    function onBack() { app.back(); return true; }
    function onMenu() { app.menu(); return true; }
    function onNextPage() { app.forceRide(); return true; }
}
class LFMenu extends WatchUi.Menu2InputDelegate {
    var app;
    function initialize(a) { Menu2InputDelegate.initialize(); app=a; }
    function onSelect(item) {
        var id=item.getId(); WatchUi.popView(WatchUi.SLIDE_DOWN);
        if(id==:finish) { app.finish(); }
        else if(id==:ride) { app.forceRide(); }
        else if(id==:auto) { app.detector.toggleAuto(); }
        else if(id==:later) { System.exit(); }
    }
}
