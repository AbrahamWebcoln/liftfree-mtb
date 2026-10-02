package org.liftfree.mtb;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class CoreTest {
    static final long T=1767225600L;static int checks=0;
    static void ok(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static void near(double actual,double expected,String message){ok(Math.abs(actual-expected)<0.001,message+": "+actual+" != "+expected);}
    interface Work{void run()throws Exception;}
    static void rejects(Work action,String message)throws Exception {boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}ok(rejected,message);}
    static Core.Row row(int seconds,double altitude,int state,int since){return new Core.Row((T+seconds)+","+(400000000+seconds*100)+",-1050000000,"+(Double.isFinite(altitude)?Math.round(altitude*10):Core.MISSING)+",130,0,3000,"+(seconds*300)+","+state+","+(T+since));}
    static List<Core.Row> example(){
        List<Core.Row> rows=new ArrayList<>();
        for(int i=0;i<=10;i++)rows.add(row(i,100+i,0,0));
        for(int i=11;i<=21;i++)rows.add(row(i,110+(i-11)*10,2,11));
        for(int i=22;i<=32;i++)rows.add(row(i,210-(i-22)*10,3,22));
        for(int i=33;i<=43;i++)rows.add(row(i,110+i-33,3,22));
        return rows;
    }
    static long uint(byte[] b,int pos,int n){long v=0;for(int i=0;i<n;i++)v|=(b[pos+i]&255L)<<(8*i);return v;}
    static List<Map<Integer,Long>> records(byte[] bytes,int want){
        int header=bytes[0]&255,pos=header,end=header+(int)uint(bytes,4,4);int[][][] defs=new int[16][][];int[] global=new int[16];List<Map<Integer,Long>> result=new ArrayList<>();
        while(pos<end){int h=bytes[pos++]&255,local=h&15;
            if((h&64)!=0){pos++;int architecture=bytes[pos++]&255;ok(architecture==0,"little endian writer");global[local]=(int)uint(bytes,pos,2);pos+=2;int n=bytes[pos++]&255;defs[local]=new int[n][3];for(int i=0;i<n;i++){for(int k=0;k<3;k++)defs[local][i][k]=bytes[pos++]&255;}}
            else {Map<Integer,Long> fields=new HashMap<>();for(int[] f:defs[local]){fields.put(f[0],uint(bytes,pos,f[1]));pos+=f[1];}if(global[local]==want)result.add(fields);}
        }
        ok(pos==end,"FIT body ends on record boundary");return result;
    }
    public static void main(String[] args)throws Exception {
        Core.Gain g=new Core.Gain();for(double z:new double[]{100,101,102,103,104,105,103,101,102,104,106})g.add(z);near(g.total(),10,"real ascent across reversals");
        g=new Core.Gain();for(double z:new double[]{100,100.5,100.2,100.8,100.1})g.add(z);near(g.total(),0,"subthreshold jitter ignored");
        g=new Core.Gain();g.add(100);g.add(110);g.reset();g.add(200);g.add(205);near(g.total(),15,"reset never counts elevation jump");
        g=new Core.Gain();g.add(100);g.add(110);g.add(Double.NaN);g.add(1000);g.add(1005);near(g.total(),15,"missing samples cannot create ascent jump");
        List<Core.Row> example=example();
        Core.Result result=Core.encode(example,3289,T,T+43,43000);
        near(result.rideAscent,20,"lift excluded but both riding climbs retained");near(result.liftAscent,100,"lift ascent audited");ok(result.liftTrips==1,"one lift trip");near(result.distance,129,"full native distance retained");
        ok(Core.crc(result.fit)==0,"file CRC");ok(Core.crc(Arrays.copyOf(result.fit,14))==0,"header CRC");
        List<Map<Integer,Long>> session=records(result.fit,18);ok(session.size()==1,"single activity session");ok(session.get(0).get(22)==20,"standard session ascent is corrected");ok(session.get(0).get(6)==8,"mountain bike sub-sport");
        List<Map<Integer,Long>> lap=records(result.fit,19);ok(lap.get(0).get(21)==20,"standard lap ascent is corrected");
        List<Map<Integer,Long>> rec=records(result.fit,20);ok(rec.size()==example.size(),"no GPS samples deleted");
        for(int i=0;i<rec.size();i++){Core.Row r=example.get(i);ok(rec.get(i).get(253)==r.v[0]-Core.FIT_EPOCH,"timestamp preserved");near(rec.get(i).get(78)/5.0-500,r.altitude(),"altitude profile unchanged");ok(rec.get(i).get(3)==130,"heart rate retained");}
        List<Map<Integer,Long>> device=records(result.fit,0);ok(device.get(0).get(2)==3289,"standard fenix 6 identity");
        Core.Result pro=Core.encode(example,3290,T,T+43,43000);ok(records(pro.fit,0).get(0).get(2)==3290,"fenix 6 Pro identity");
        List<Core.Row> climb=new ArrayList<>();for(int i=0;i<=30;i++)climb.add(row(i,100+i,0,0));near(Core.encode(climb,3289,T,T+30,30000).rideAscent,30,"unmarked genuine climbing retained");
        List<Core.Row> delayed=new ArrayList<>();for(int i=0;i<=30;i++)delayed.add(row(i,100+i,i<20?0:1,i<20?0:10));
        boolean[] mask=Core.liftMask(delayed);ok(!mask[9]&&mask[10]&&mask[19],"automatic confirmation backdates lift start");near(Core.encode(delayed,3289,T,T+30,30000).rideAscent,9,"backdated lift gain removed");
        List<Core.Row> forced=new ArrayList<>();for(int i=0;i<=30;i++)forced.add(row(i,100+i,3,0));near(Core.encode(forced,3289,T,T+30,30000).rideAscent,30,"explicit riding override retained");
        List<Core.Row> gap=Arrays.asList(row(0,100,0,0),row(1,105,0,0),row(20,500,0,0),row(21,506,0,0));near(Core.encode(gap,3289,T,T+21,21000).rideAscent,11,"time gap does not fabricate climb");
        rejects(()->new Core.Row("bad,csv"),"malformed sample rejected");
        rejects(()->Core.encode(example,123,T,T+43,43000),"unknown device rejected");
        rejects(()->Core.encode(Arrays.asList(row(0,100,0,0),row(0,110,0,0)),3289,T,T+1,1000),"duplicate timestamps rejected");
        rejects(()->Core.encode(example,3289,T,T+10,10000),"truncated finish rejected");
        rejects(()->Core.liftMask(Arrays.asList(row(10,100,0,10),row(20,110,2,15),row(30,120,3,12))),"out of order state changes rejected");
        if(args.length>0)Files.write(Path.of(args[0]),result.fit);
        System.out.println("PASSED: "+checks+" assertions. Synthetic activity: 20 m riding ascent, 100 m lift ascent, 44 unchanged samples.");
    }
}
