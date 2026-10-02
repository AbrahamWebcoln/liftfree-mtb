package org.liftfree.mtb;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;

/** The phone acknowledges only data already fsynced to private app storage. */
final class RideStore {
    final File dir; final String id; final List<Core.Row> rows=new ArrayList<>();
    JSONObject meta;
    RideStore(File root,String id)throws Exception {
        if(!id.matches("[0-9]{9,10}"))throw new IOException("Invalid recording ID");
        this.id=id;dir=new File(root,id);if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create recording directory");
        File m=new File(dir,"meta.json");meta=m.exists()?readJson(m):new JSONObject();
        File journal=new File(dir,"samples.csv");
        if(journal.exists()) {
            // An interrupted last write is never acknowledged. Drop only its
            // unterminated tail, then let the watch resend that sequence.
            try(RandomAccessFile f=new RandomAccessFile(journal,"rw")) {
                long n=f.length(); if(n>40_000_000)throw new IOException("Recording too large");
                while(n>0){f.seek(n-1);if(f.read()==10)break;n--;}
                if(n!=f.length()){f.setLength(n);f.getFD().sync();}
            }
            try(BufferedReader br=new BufferedReader(new InputStreamReader(new FileInputStream(journal),StandardCharsets.US_ASCII))) {
                String line;while((line=br.readLine())!=null){Core.Row r=new Core.Row(line);if(!rows.isEmpty()&&r.v[0]<=rows.get(rows.size()-1).v[0])throw new IOException("Stored timestamps are invalid");rows.add(r);}
            }
        }
    }
    synchronized int append(int base,String csv,int model,long start)throws Exception {
        if(base<0 || base>200000)throw new IOException("Invalid sequence");
        if(base>rows.size())return rows.size();
        if(meta.has("model")&&(meta.getInt("model")!=model || meta.getLong("start")!=start))throw new IOException("Recording metadata changed");
        if(!meta.has("model")){meta.put("model",model).put("start",start);saveMeta();}
        if(csv.length()>8000)throw new IOException("Packet too large");
        List<Core.Row> incoming=new ArrayList<>();
        for(String line:csv.split("\n")){if(!line.isEmpty())incoming.add(new Core.Row(line));}
        if(incoming.isEmpty() || incoming.size()>32 || rows.size()+incoming.size()>200000)throw new IOException("Invalid packet length");
        int overlap=Math.min(incoming.size(),rows.size()-base);
        for(int i=0;i<overlap;i++)if(!incoming.get(i).csv().equals(rows.get(base+i).csv()))throw new IOException("Conflicting replayed sample");
        long last=rows.isEmpty()?0:rows.get(rows.size()-1).v[0];
        StringBuilder tail=new StringBuilder();
        for(int i=overlap;i<incoming.size();i++){Core.Row r=incoming.get(i);if(r.v[0]<=last)throw new IOException("Sample time moved backwards");last=r.v[0];tail.append(r.csv()).append('\n');}
        if(tail.length()>0) {
            try(FileOutputStream f=new FileOutputStream(new File(dir,"samples.csv"),true)){f.write(tail.toString().getBytes(StandardCharsets.US_ASCII));f.getFD().sync();}
            for(int i=overlap;i<incoming.size();i++)rows.add(incoming.get(i));
        }
        return rows.size();
    }
    synchronized void finish(int expected,long end,long timer,int model,long start)throws Exception {
        if(expected!=rows.size())throw new IOException("Waiting for missing watch samples");
        if(meta.getInt("model")!=model || meta.getLong("start")!=start)throw new IOException("Finish metadata mismatch");
        if(new File(dir,"ready.json").exists())return;
        Core.Result result=Core.encode(rows,model,start,end,timer);
        atomic(new File(dir,"activity.fit"),result.fit);
        JSONObject ready=new JSONObject().put("id",id).put("samples",rows.size()).put("expected_ascent",Math.round(result.rideAscent)).put("lift_ascent",result.liftAscent).put("lift_trips",result.liftTrips).put("distance",result.distance).put("end",end).put("timer",timer).put("status","ready");
        atomic(new File(dir,"ready.json"),ready.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    synchronized boolean ready(){return new File(dir,"ready.json").isFile()&&new File(dir,"activity.fit").isFile();}
    synchronized JSONObject status()throws Exception{return readJson(new File(dir,"ready.json"));}
    synchronized void status(JSONObject s)throws Exception{atomic(new File(dir,"ready.json"),s.toString(2).getBytes(StandardCharsets.UTF_8));}
    void saveMeta()throws Exception{atomic(new File(dir,"meta.json"),meta.toString().getBytes(StandardCharsets.UTF_8));}
    static JSONObject readJson(File f)throws Exception{return new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}
    static void atomic(File dst,byte[] bytes)throws Exception {
        File temp=new File(dst.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
        java.nio.file.Files.move(temp.toPath(),dst.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
