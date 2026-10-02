using Toybox.Test;
using Toybox.System;
using Toybox.Application.Storage;

(:test) function testFullBufferCapacity(logger) {
    var rows=[];
    for(var i=0;i<180;i++) {
        rows.add([1767225600+i,400000000+i*100,-1050000000,1000+i,130,0,3000,i*300,0,1767225600]);
    }
    var route=[];
    for(var j=0;j<64;j++) { route.add([40.0+j*0.0001,-105.0+j*0.0001]); }
    var d=new LiftDetector(); d.routes=[route];
    for(var t=0;t<12;t++) { d.update(1767225600+t,[40.0+t*0.00001,-105.0+t*0.00001],100.0+t,3.0,70,0); }
    var before=System.getSystemStats();
    logger.debug("Full buffer used="+before.usedMemory.toString()+" free="+before.freeMemory.toString()+" total="+before.totalMemory.toString());
    Test.assert(before.freeMemory>16384);
    Storage.setValue("capacity-test",{"queue"=>rows,"route"=>route});
    var saved=Storage.getValue("capacity-test");
    Test.assert(saved["queue"].size()==180);
    Test.assert(saved["route"].size()==64);
    Storage.deleteValue("capacity-test");
    var packet="";
    for(var r=0;r<12;r++) {
        for(var k=0;k<10;k++) { if(k>0) { packet+=","; } packet+=rows[r][k].toString(); }
        packet+="\n";
    }
    Test.assert(packet.length()<1600);
    logger.debug("Twelve-row payload bytes="+packet.length().toString());
    return true;
}
(:test) function testMissingValueSerialization(logger) {
    var missing=-2147483648;
    Test.assert(missing.toString()=="-2147483648");
    var position=(40.0*10000000.0).toNumber();
    Test.assert(position==400000000);
    return true;
}
