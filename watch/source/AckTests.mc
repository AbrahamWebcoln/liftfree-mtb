using Toybox.Test;

class LFTestPhoneMessage {
    var data;
    function initialize(value) { data=value; }
}
class LFTestAckApp extends LiftFreeApp {
    var snapshots=0;
    function initialize() { LiftFreeApp.initialize(); }
    function now() { return 123456; }
    function snapshot() { snapshots++; }
    function buzz() { }
}
(:test) function testActualAcknowledgementHandler(logger) {
    var app=new LFTestAckApp();
    app.onPhone(new LFTestPhoneMessage({"v"=>3,"id"=>"READY".toLower(),"next"=>0,"saved"=>false}));
    Test.assert(app.phoneAt==123456);
    app.phase=1; app.sid=(1767225600).toString();
    app.queue=[[1],[2],[3]]; app.base=0; app.serial=3; app.lastSentEnd=2;
    app.onPhone(new LFTestPhoneMessage({"v"=>3,"id"=>(1767225600).toString(),"next"=>2,"saved"=>false}));
    Test.assert(app.base==2); Test.assert(app.queue.size()==1); Test.assert(app.queue[0][0]==3);
    Test.assert(app.snapshots==1);
    // Acknowledgements outside the sent window must not discard samples.
    app.onPhone(new LFTestPhoneMessage({"v"=>3,"id"=>app.sid,"next"=>4,"saved"=>false}));
    Test.assert(app.base==2 && app.queue.size()==1);
    app.onPhone(new LFTestPhoneMessage({"v"=>3,"id"=>app.sid,"next"=>0,"saved"=>false}));
    Test.assert(app.base==2 && app.queue.size()==1);
    app.onPhone(new LFTestPhoneMessage({"v"=>3,"id"=>"other-recording","next"=>3,"saved"=>true}));
    Test.assert(app.base==2 && app.phase==1);
    // Native recording was already saved; acknowledge the final sample.
    app.phase=3; app.lastSentEnd=3;
    app.onPhone(new LFTestPhoneMessage({"v"=>3,"id"=>(1767225600).toString(),"next"=>3,"saved"=>true}));
    Test.assert(app.base==3 && app.queue.size()==0 && app.phase==4);
    Test.assert(app.snapshots==2);
    return true;
}
