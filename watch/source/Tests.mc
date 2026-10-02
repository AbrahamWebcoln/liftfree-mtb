using Toybox.Test;
(:test) function testProjection(logger) {
    var d=new LiftDetector();
    var p=d.project([40.0005,-105.0],[[40.0,-105.0],[40.001,-105.0]]);
    Test.assert(p[0]<0.1); Test.assert(p[1]>50 && p[1]<60); return true;
}
(:test) function testUnknownClimbIsNotLift(logger) {
    var d=new LiftDetector(); d.routes=[];
    for(var i=0;i<30;i++) { Test.assert(d.update(100+i,[40.0+i*0.00003,-105.0],100.0+i,3.0,null,0)==null); }
    return true;
}
(:test) function testLearnedRouteDetectsLift(logger) {
    var d=new LiftDetector(); d.routes=[[[40.0,-105.0],[40.01,-105.0]]]; d.autoOn=true;
    var hit=null;
    for(var i=0;i<15 && hit==null;i++) { hit=d.update(100+i,[40.0+i*0.00003,-105.0],100.0+i,3.0,null,0); }
    Test.assert(hit!=null); Test.assert(hit[0]==1); Test.assert(hit[1]<=101); return true;
}
(:test) function testPedalingProtectsClimb(logger) {
    var d=new LiftDetector(); d.routes=[[[40.0,-105.0],[40.01,-105.0]]];
    for(var i=0;i<30;i++) { Test.assert(d.update(100+i,[40.0+i*0.00003,-105.0],100.0+i,3.0,75,0)==null); }
    return true;
}
(:test) function testManualOverrideWins(logger) {
    var d=new LiftDetector(); d.routes=[[[40.0,-105.0],[40.01,-105.0]]]; d.forceRide();
    for(var i=0;i<30;i++) { Test.assert(d.update(100+i,[40.0+i*0.00003,-105.0],100.0+i,3.0,null,3)==null); }
    return true;
}
(:test) function testReverseTravelNotLift(logger) {
    var d=new LiftDetector(); d.routes=[[[40.0,-105.0],[40.01,-105.0]]];
    for(var i=0;i<30;i++) { Test.assert(d.update(100+i,[40.006-i*0.00003,-105.0],150.0-i,3.0,null,0)==null); }
    return true;
}
