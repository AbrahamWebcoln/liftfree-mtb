package org.liftfree.mtb;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.garmin.android.connectiq.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class BridgeService extends Service {
    static final String APP_ID="6eacb382-8846-4b09-a6cd-268018d8f007";
    public static volatile BridgeService instance;
    public static volatile String status="Companion stopped";
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService disk=Executors.newSingleThreadExecutor(),net=Executors.newSingleThreadExecutor();
    final Set<String> uploading=ConcurrentHashMap.newKeySet();
    final Map<String,RideStore> stores=new HashMap<>();
    volatile List<IQDevice> devices=Collections.emptyList();
    ConnectIQ iq;IQDevice selected;IQApp app=new IQApp(APP_ID);boolean sdkReady=false;
    PowerManager.WakeLock wake;File root;
    @Override public void onCreate() {
        super.onCreate();instance=this;root=new File(getFilesDir(),"rides");root.mkdirs();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("recording","LiftFree recording",NotificationManager.IMPORTANCE_LOW));
        if(Build.VERSION.SDK_INT>=29)startForeground(7,notification("Starting Garmin connection"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(7,notification("Starting Garmin connection"));
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LiftFree:watch-transfer");wake.acquire(12*60*60*1000L);
        iq=ConnectIQ.getInstance(this,ConnectIQ.IQConnectType.WIRELESS);
        iq.initialize(this,false,new ConnectIQ.ConnectIQListener(){
            public void onSdkReady(){sdkReady=true;discover();}
            public void onInitializeError(ConnectIQ.IQSdkErrorStatus error){setStatus("Garmin connection error: "+error+". Open Garmin Connect.");}
            public void onSdkShutDown(){sdkReady=false;setStatus("Garmin service disconnected; reopen companion");}
        });
        main.postDelayed(autoTick,10000);
    }
    Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"recording").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("LiftFree MTB").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build();
    }
    void setStatus(String s){status=s;main.post(()->{NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(7,notification(s));});}
    void discover(){
        try {
            devices=new ArrayList<>(iq.getKnownDevices());
            long id=getSharedPreferences("settings",MODE_PRIVATE).getLong("device",-1);
            IQDevice found=null;for(IQDevice d:devices)if(d.getDeviceIdentifier()==id)found=d;
            if(found==null&&devices.size()==1)found=devices.get(0);
            if(found!=null)subscribe(found);else setStatus(devices.isEmpty()?"Pair the watch in Garmin Connect first":"Choose your watch in LiftFree");
        }catch(Exception e){setStatus("Open Garmin Connect, then restart companion");}
    }
    void choose(long id){getSharedPreferences("settings",MODE_PRIVATE).edit().putLong("device",id).apply();if(sdkReady)discover();}
    void subscribe(IQDevice device)throws Exception {
        if(selected!=null){iq.unregisterForApplicationEvents(selected,app);iq.unregisterForDeviceEvents(selected);}
        selected=device;
        iq.registerForDeviceEvents(selected,(d,s)->setStatus(d.getFriendlyName()+": "+s));
        iq.registerForAppEvents(selected,app,(d,a,messages,messageStatus)->{
            if(messages==null || selected==null || d.getDeviceIdentifier()!=selected.getDeviceIdentifier())return;
            for(Object message:messages)if(message instanceof Map){Map<?,?> copy=new HashMap<>((Map<?,?>)message);disk.execute(()->receive(d,copy));}
        });
        setStatus("Ready for LiftFree on "+selected.getFriendlyName());
    }
    static long number(Map<?,?> m,String key)throws IOException {Object v=m.get(key);if(!(v instanceof Number))throw new IOException("Invalid "+key);return ((Number)v).longValue();}
    void receive(IQDevice device,Map<?,?> m){
        try {
            if(number(m,"v")!=3)return;String kind=String.valueOf(m.get("kind")),id=String.valueOf(m.get("id"));
            if("hello".equals(kind)){reply(device,"ready",0,false);return;}
            RideStore ride=stores.get(id);if(ride==null){ride=new RideStore(root,id);stores.put(id,ride);}
            if("batch".equals(kind)) {
                int next=ride.append((int)number(m,"base"),String.valueOf(m.get("rows")),(int)number(m,"model"),number(m,"start"));
                reply(device,id,next,false);setStatus("Recording: "+next+" samples safely stored");
            } else if("finish".equals(kind)) {
                int count=(int)number(m,"count");
                if(count!=ride.rows.size()){reply(device,id,ride.rows.size(),false);return;}
                ride.finish(count,number(m,"end"),number(m,"timer"),(int)number(m,"model"),number(m,"start"));
                reply(device,id,count,true);setStatus("Ride saved: "+ride.status().getLong("expected_ascent")+" m riding ascent");
                if(autoEnabled())queueUpload(ride,false);
            }
        }catch(Exception e){setStatus("Transfer not acknowledged: "+e.getMessage());}
    }
    void reply(IQDevice d,String id,int next,boolean saved){
        Map<String,Object> message=new HashMap<>();message.put("v",3);message.put("id",id);message.put("next",next);message.put("saved",saved);
        main.post(()->{try{iq.sendMessage(d,app,message,(device,application,result)->{});}catch(Exception e){setStatus("Phone saved data; acknowledgement will retry");}});
    }
    boolean autoEnabled(){SharedPreferences p=getSharedPreferences("settings",MODE_PRIVATE);return p.getBoolean("auto_upload",false)&&p.getBoolean("backup_sync_ack",false)&&new StravaApi(this).authorized();}
    void queueUpload(RideStore ride,boolean explicit){
        if(!uploading.add(ride.id))return;
        net.execute(()->{try{setStatus("Uploading corrected ride");new StravaApi(this).upload(ride,explicit);JSONObject s=ride.status();setStatus("Strava: "+s.optString("status"));}catch(Exception e){setStatus("Strava: "+e.getMessage());try{JSONObject s=ride.status();s.put("error",e.getMessage());ride.status(s);}catch(Exception ignored){}}finally{uploading.remove(ride.id);}});
    }
    void upload(String id){disk.execute(()->{try{queueUpload(new RideStore(root,id),true);}catch(Exception e){setStatus(e.getMessage());}});}
    final Runnable autoTick=new Runnable(){public void run(){
        if(autoEnabled())disk.execute(()->{File[] dirs=root.listFiles();if(dirs!=null)for(File d:dirs)try{
            File ready=new File(d,"ready.json");if(!ready.isFile())continue;String s=RideStore.readJson(ready).optString("status");
            if(s.equals("ready")||s.equals("processing")||s.equals("uploaded"))queueUpload(new RideStore(root,d.getName()),false);
        }catch(Exception e){setStatus("Pending ride needs attention");}});
        main.postDelayed(this,60000);
    }};
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"SELECT".equals(intent.getAction()))choose(intent.getLongExtra("device",-1));
        if(intent!=null&&"UPLOAD".equals(intent.getAction()))upload(intent.getStringExtra("id"));
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        main.removeCallbacks(autoTick);instance=null;status="Companion stopped";
        try{if(iq!=null)iq.shutdown(this);}catch(Exception ignored){}
        if(wake!=null&&wake.isHeld())wake.release();disk.shutdown();net.shutdown();super.onDestroy();
    }
}
