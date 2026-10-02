package org.liftfree.mtb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure Java processing; no network, account access, or Android dependencies. */
public final class Core {
    public static final long MISSING = Integer.MIN_VALUE;
    public static final long FIT_EPOCH = 631065600L;
    public static final class Row {
        public final long[] v;
        public Row(String line) {
            String[] p=line.trim().split(",",-1);
            if(p.length!=10) throw new IllegalArgumentException("Expected 10 sample fields");
            v=new long[10]; for(int i=0;i<10;i++) v[i]=Long.parseLong(p[i]);
            if(v[0]<=FIT_EPOCH || v[0]>2147483647L || v[8]<0 || v[8]>3 || v[9]>v[0]) throw new IllegalArgumentException("Invalid time or lift marker");
            if((v[1]==MISSING)!=(v[2]==MISSING)) throw new IllegalArgumentException("Incomplete location");
            if(v[1]!=MISSING && (Math.abs(v[1])>900000000L || Math.abs(v[2])>1800000000L)) throw new IllegalArgumentException("Location out of range");
            if(v[3]!=MISSING && (v[3]<-5000 || v[3]>120000)) throw new IllegalArgumentException("Altitude out of range");
            if(v[4]<0 || v[4]>255 || v[5]<0 || v[5]>255 || v[6]<-1 || v[7]<-1) throw new IllegalArgumentException("Invalid sensor value");
        }
        public double altitude() { return v[3]==MISSING?Double.NaN:v[3]/10.0; }
        public String csv() { StringBuilder b=new StringBuilder();for(long x:v){if(b.length()>0)b.append(',');b.append(x);}return b.toString(); }
    }
    /** Two-metre reversal hysteresis. Keeps genuine ascent within each riding span. */
    public static final class Gain {
        double committed=0,low=Double.NaN,high=Double.NaN;int direction=0;
        public void reset() { if(direction==1)committed+=high-low;low=high=Double.NaN;direction=0; }
        public void add(double z) {
            if(!Double.isFinite(z)){reset();return;}
            if(Double.isNaN(low)){low=high=z;return;}
            if(direction==0){low=Math.min(low,z);high=Math.max(high,z);if(z-low>=2){direction=1;high=z;}else if(high-z>=2){direction=-1;low=z;}}
            else if(direction==1){high=Math.max(high,z);if(high-z>=2){committed+=high-low;direction=-1;low=z;}}
            else {low=Math.min(low,z);if(z-low>=2){direction=1;high=z;}}
        }
        public double total(){return committed+(direction==1?high-low:0);}
    }
    public static final class Result {
        public final byte[] fit; public final double rideAscent,liftAscent,distance;public final int liftTrips; public final int samples;
        Result(byte[] f,double r,double l,double d,int trips,int n){fit=f;rideAscent=r;liftAscent=l;distance=d;liftTrips=trips;samples=n;}
    }
    public static boolean[] liftMask(List<Row> rows) {
        boolean[] mask=new boolean[rows.size()];
        List<long[]> transitions=new ArrayList<>(); long oldState=-1,oldSince=-1,lastEffective=Long.MIN_VALUE;
        for(Row r:rows) {
            if(r.v[8]!=oldState || r.v[9]!=oldSince) {
                long effective=Math.max(rows.get(0).v[0],r.v[9]);
                if(effective<lastEffective) throw new IllegalArgumentException("Out-of-order lift transition");
                transitions.add(new long[]{effective,r.v[8]});lastEffective=effective;oldState=r.v[8];oldSince=r.v[9];
            }
        }
        int p=0;long state=0;
        for(int i=0;i<rows.size();i++) {
            while(p<transitions.size() && transitions.get(p)[0]<=rows.get(i).v[0]) state=transitions.get(p++)[1];
            mask[i]=state==1 || state==2;
        }
        return mask;
    }
    public static Result encode(List<Row> rows,int product,long start,long end,long timerMs) throws IOException {
        if(rows.size()<2 || rows.size()>200000)throw new IllegalArgumentException("Incomplete or oversized recording");
        if(product!=3288 && product!=3289)throw new IllegalArgumentException("Unsupported watch identity");
        if(start>rows.get(0).v[0] || end<rows.get(rows.size()-1).v[0] || end<=start || end-start>172800)throw new IllegalArgumentException("Invalid recording duration");
        long last=0;int gps=0;
        for(Row r:rows){if(r.v[0]<=last)throw new IllegalArgumentException("Non-increasing sample time");last=r.v[0];if(r.v[1]!=MISSING)gps++;}
        if(gps<2)throw new IllegalArgumentException("Not enough GPS samples");
        boolean[] lift=liftMask(rows); Gain ride=new Gain(),transport=new Gain(),descent=new Gain();int trips=0;
        double distance=0;long priorDistance=-1;boolean nativeDistance=true;
        for(int i=0;i<rows.size();i++) {
            Row r=rows.get(i);
            if(i==0 || lift[i]!=lift[i-1] || r.v[0]-rows.get(i-1).v[0]>5){ride.reset();transport.reset();}
            if(i>0 && r.v[0]-rows.get(i-1).v[0]>5)descent.reset();
            if(lift[i])transport.add(r.altitude());else ride.add(r.altitude());
            descent.add(-r.altitude());
            if(lift[i] && (i==0 || !lift[i-1]))trips++;
            if(r.v[7]<0 || (priorDistance>=0 && r.v[7]<priorDistance))nativeDistance=false;
            priorDistance=r.v[7];
        }
        if(nativeDistance)distance=rows.get(rows.size()-1).v[7]/100.0;
        else for(int i=1;i<rows.size();i++){Row a=rows.get(i-1),b=rows.get(i);if(a.v[1]!=MISSING && b.v[1]!=MISSING && b.v[0]-a.v[0]<=5)distance+=haversine(a,b);}
        long elapsed=(end-start)*1000L;
        if(timerMs<=0 || timerMs>elapsed+2000)timerMs=elapsed;
        long ascent=Math.round(ride.total()),down=Math.round(descent.total());
        if(ascent>65534 || down>65534 || distance*100>4294967294L)throw new IllegalArgumentException("Summary exceeds FIT range");
        Writer w=new Writer();
        w.def(0,0,new int[][]{{0,1,0},{1,2,132},{2,2,132},{4,4,134}});
        w.data(0,4,1,product,start-FIT_EPOCH);
        w.def(5,23,new int[][]{{253,4,134},{0,1,2},{2,2,132},{4,2,132}});
        w.data(5,start-FIT_EPOCH,0,1,product);
        w.def(2,21,new int[][]{{253,4,134},{0,1,0},{1,1,0}});
        w.data(2,start-FIT_EPOCH,0,0);
        w.def(1,20,new int[][]{{253,4,134},{0,4,133},{1,4,133},{2,2,132},{78,4,134},{3,1,2},{4,1,2},{5,4,134},{6,2,132},{73,4,134}});
        double fallbackDistance=0;
        for(int i=0;i<rows.size();i++) {
            Row r=rows.get(i);
            if(!nativeDistance && i>0){Row p=rows.get(i-1);if(p.v[1]!=MISSING && r.v[1]!=MISSING && r.v[0]-p.v[0]<=5)fallbackDistance+=haversine(p,r);}
            long lat=r.v[1]==MISSING?2147483647L:Math.round((r.v[1]/10000000.0)*2147483648.0/180.0);
            long lon=r.v[2]==MISSING?2147483647L:Math.round((r.v[2]/10000000.0)*2147483648.0/180.0);
            long z=r.v[3]==MISSING?4294967295L:Math.round((r.altitude()+500)*5);
            long speed=r.v[6]<0?4294967295L:r.v[6];
            w.data(1,r.v[0]-FIT_EPOCH,lat,lon,z>65534?65535:z,z,r.v[4],r.v[5],nativeDistance?r.v[7]:Math.round(fallbackDistance*100),speed>65534?65535:speed,speed);
        }
        w.data(2,end-FIT_EPOCH,0,4);
        w.def(3,19,new int[][]{{254,2,132},{253,4,134},{2,4,134},{7,4,134},{8,4,134},{9,4,134},{21,2,132},{22,2,132}});
        w.data(3,0,end-FIT_EPOCH,start-FIT_EPOCH,elapsed,timerMs,Math.round(distance*100),ascent,down);
        w.def(3,18,new int[][]{{254,2,132},{253,4,134},{2,4,134},{5,1,0},{6,1,0},{7,4,134},{8,4,134},{9,4,134},{22,2,132},{23,2,132},{25,2,132},{26,2,132}});
        w.data(3,0,end-FIT_EPOCH,start-FIT_EPOCH,2,8,elapsed,timerMs,Math.round(distance*100),ascent,down,0,1);
        w.def(4,34,new int[][]{{253,4,134},{0,4,134},{1,2,132},{2,1,0},{3,1,0},{4,1,0}});
        w.data(4,end-FIT_EPOCH,timerMs,1,0,26,1);
        return new Result(w.finish(),ride.total(),transport.total(),distance,trips,rows.size());
    }
    static double haversine(Row a,Row b) {
        double p1=Math.toRadians(a.v[1]/1e7),p2=Math.toRadians(b.v[1]/1e7),dp=p2-p1,dl=Math.toRadians((b.v[2]-a.v[2])/1e7);
        double q=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
        return 6371000*2*Math.atan2(Math.sqrt(q),Math.sqrt(Math.max(0,1-q)));
    }
    public static int crc(byte[] bytes){int c=0;for(byte b:bytes){c^=b&255;for(int k=0;k<8;k++)c=(c&1)!=0?(c>>>1)^0xa001:c>>>1;}return c;}
    static final class Writer {
        final ByteArrayOutputStream b=new ByteArrayOutputStream();final Map<Integer,int[][]> defs=new HashMap<>();
        void le(OutputStream out,long n,int size)throws IOException{for(int i=0;i<size;i++)out.write((int)(n>>>(8*i))&255);}
        void def(int local,int global,int[][] fields)throws IOException{defs.put(local,fields);b.write(64|local);b.write(0);b.write(0);le(b,global,2);b.write(fields.length);for(int[] f:fields){b.write(f[0]);b.write(f[1]);b.write(f[2]);}}
        void data(int local,long...v)throws IOException{int[][]f=defs.get(local);if(f==null || f.length!=v.length)throw new IOException("FIT field mismatch");b.write(local);for(int i=0;i<v.length;i++)le(b,v[i],f[i][1]);}
        byte[] finish()throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(14);out.write(32);le(out,21000,2);le(out,b.size(),4);out.write(".FIT".getBytes(StandardCharsets.US_ASCII));le(out,crc(out.toByteArray()),2);out.write(b.toByteArray());le(out,crc(out.toByteArray()),2);byte[] result=out.toByteArray();if(crc(result)!=0)throw new IOException("FIT checksum failure");return result;}
    }
}
