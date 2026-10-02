using Toybox.Math;
using Toybox.Application.Storage;
function lfMin(a,b) { return a<b?a:b; }
function lfMax(a,b) { return a>b?a:b; }
class LiftDetector {
    var routes=[]; var autoOn=true; var history=[]; var candidates=[];
    var active=-1; var outside=0; var pedaling=0; var holdRide=false;
    var manualTrack=[]; var manualStartAlt=null; var learnedSaved=false;
    var sampleSpacing=20.0;
    function initialize() {
        var stored=Storage.getValue("learnedLiftV3");
        if(stored!=null && stored.size()>=2 && stored.size()<=64) { routes.add(stored); }
        var setting=Storage.getValue("autoLift"); if(setting!=null) { autoOn=setting; }
    }
    function clearWindow() { history=[]; candidates=[]; outside=0; pedaling=0; }
    function distance(a,b) {
        var x=(a[1]-b[1])*111195.0*Math.cos(a[0]*Math.PI/180.0);
        var y=(a[0]-b[0])*111195.0; return Math.sqrt(x*x+y*y);
    }
    function project(point,route) {
        var ref=route[0]; var scale=111195.0*Math.cos(ref[0]*Math.PI/180.0);
        var px=(point[1]-ref[1])*scale; var py=(point[0]-ref[0])*111195.0;
        var best=100000000.0; var progress=0.0; var cumulative=0.0;
        for(var i=1;i<route.size();i++) {
            var ax=(route[i-1][1]-ref[1])*scale; var ay=(route[i-1][0]-ref[0])*111195.0;
            var bx=(route[i][1]-ref[1])*scale; var by=(route[i][0]-ref[0])*111195.0;
            var dx=bx-ax; var dy=by-ay; var len2=dx*dx+dy*dy;
            if(len2<0.01) { continue; }
            var length=Math.sqrt(len2);
            var f=lfMax(0.0,lfMin(1.0,((px-ax)*dx+(py-ay)*dy)/len2));
            var ex=px-ax-f*dx; var ey=py-ay-f*dy; var d=Math.sqrt(ex*ex+ey*ey);
            if(d<best) { best=d; progress=cumulative+f*length; }
            cumulative+=length;
        }
        return [best,progress,cumulative];
    }
    function forceRide() { holdRide=true; active=-1; clearWindow(); }
    function toggleAuto() { autoOn=!autoOn; Storage.setValue("autoLift",autoOn); holdRide=false; active=-1; clearWindow(); }
    function startManual(point,alt) {
        manualTrack=[]; sampleSpacing=20.0; manualStartAlt=alt; learnedSaved=false;
        active=-1; clearWindow(); if(point!=null) { manualTrack.add(point); }
    }
    function sampleManual(point) {
        if(point==null) { return; }
        if(manualTrack.size()>=62) {
            var reduced=[]; for(var k=0;k<manualTrack.size();k+=2) { reduced.add(manualTrack[k]); }
            manualTrack=reduced; sampleSpacing*=2.0;
        }
        if(manualTrack.size()==0 || distance(manualTrack[manualTrack.size()-1],point)>=sampleSpacing) { manualTrack.add(point); }
    }
    function endManual(point,alt) {
        if(point!=null && manualTrack.size()<64) { manualTrack.add(point); }
        if(manualTrack.size()>=4 && manualStartAlt!=null && alt!=null && alt-manualStartAlt>=8.0) {
            var len=0.0; for(var i=1;i<manualTrack.size();i++) { len+=distance(manualTrack[i-1],manualTrack[i]); }
            if(len>=100.0) {
                Storage.setValue("learnedLiftV3",manualTrack);
                routes=[manualTrack]; learnedSaved=true;
            }
        }
        forceRide();
    }
    // 0 ride, 1 automatic lift, 2 manual lift, 3 explicit riding override.
    // A known corridor is required: climbing alone never triggers lift mode.
    function update(t,point,alt,speed,cadence,state) {
        if(state==2) { sampleManual(point); return null; }
        if(point==null || alt==null) { clearWindow(); return null; }
        var projections=[]; var close=false;
        for(var k=0;k<routes.size();k++) {
            var pr=project(point,routes[k]); projections.add(pr); if(pr[0]<=32.0) { close=true; }
        }
        if(holdRide) { if(!close) { holdRide=false; clearWindow(); return [0,t]; } return null; }
        if(!autoOn) { return null; }
        if(history.size()>0 && t-history[history.size()-1][0]>5) { clearWindow(); }
        if(candidates.size()!=routes.size()) { candidates=[]; for(var c=0;c<routes.size();c++) { candidates.add(null); } }
        if(state!=1) {
            for(var c=0;c<routes.size();c++) {
                var pp=projections[c]; var candidate=candidates[c];
                if(pp[0]>18.0 || pp[1]>=pp[2]-25.0 || (cadence!=null && cadence>5)) { candidates[c]=null; }
                else if(candidate==null || t-candidate[0]>120 || pp[1]<candidate[1]-8.0) { candidates[c]=[t,pp[1]]; }
            }
        }
        history.add([t,alt,projections]);
        while(history.size()>1 && t-history[0][0]>12) { history=history.slice(1,history.size()); }
        if(state==1) {
            if(active<0 || active>=projections.size()) { active=-1; clearWindow(); return [0,t]; }
            var p=projections[active]; outside=p[0]>22.0?outside+1:0;
            pedaling=(cadence!=null && cadence>15)?pedaling+1:0;
            var reversing=false; if(history.size()>=6) { reversing=history[0][2][active][1]-p[1]>=10.0; }
            if(p[1]>=p[2]-5.0 || outside>=3 || reversing || pedaling>=8) { active=-1; clearWindow(); holdRide=true; return [0,t]; }
            return null;
        }
        if(history.size()<10 || t-history[0][0]<9) { return null; }
        if(cadence!=null && cadence>5) { return null; }
        if(speed!=null && (speed<0.4 || speed>9.0)) { return null; }
        var first=history[0];
        for(var r=0;r<routes.size();r++) {
            var a=first[2][r]; var b=projections[r];
            if(a[0]>18.0 || b[0]>18.0 || b[1]>=b[2]-25.0) { continue; }
            if(b[1]-a[1]>=15.0 && alt-first[1]>=1.0) {
                active=r; outside=0; pedaling=0; var onset=first[0];
                if(candidates[r]!=null && candidates[r][0]<onset && candidates[r][1]<=a[1]+5.0) { onset=candidates[r][0]; }
                return [1,onset];
            }
        }
        return null;
    }
}
