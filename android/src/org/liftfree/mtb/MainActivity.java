package org.liftfree.mtb;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import com.garmin.android.connectiq.IQDevice;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    final Handler handler=new Handler(Looper.getMainLooper());
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    TextView live;LinearLayout rides;SharedPreferences prefs;StravaApi api;
    String listing="",exportId=null;Intent requestedService=null;
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView text(LinearLayout parent,String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(0,dp(7),0,dp(7));parent.addView(t);return t;}
    Button button(LinearLayout parent,String label,View.OnClickListener click){Button b=new Button(this);b.setText(label);b.setAllCaps(false);parent.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(click);return b;}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);prefs=getSharedPreferences("settings",MODE_PRIVATE);api=new StravaApi(this);
        if(saved!=null)exportId=saved.getString("exportId");
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(42),dp(20),dp(32));scroll.addView(body);setContentView(scroll);
        text(body,"LiftFree MTB",28);text(body,"Watch + phone recorder · test build 0.3",14);
        live=text(body,BridgeService.status,18);
        button(body,"Start phone companion",v->startBridge(new Intent(this,BridgeService.class)));
        button(body,"Choose Garmin watch",v->chooseWatch());
        button(body,"Stop phone companion",v->new AlertDialog.Builder(this).setTitle("Stop the companion?").setMessage("Finish and transfer your watch recording first. The watch only buffers about three minutes without the phone.").setNegativeButton("Keep running",null).setPositiveButton("Stop",(d,w)->stopService(new Intent(this,BridgeService.class))).show());
        text(body,"One-time setup",22);
        text(body,"1. Install LiftFree on the watch and keep Garmin Connect installed and paired.\n2. Turn OFF Garmin-to-Strava automatic syncing. This avoids uploading the uncorrected Garmin backup as well.\n3. Connect your Strava account below. Set activity visibility to Only You in Strava for the first test.",15);
        CheckBox backup=new CheckBox(this);backup.setText("Garmin → Strava auto-sync is off");backup.setChecked(prefs.getBoolean("backup_sync_ack",false));body.addView(backup);backup.setOnCheckedChangeListener((b,c)->prefs.edit().putBoolean("backup_sync_ack",c).apply());
        button(body,"Connect Strava / account setup",v->account());
        CheckBox auto=new CheckBox(this);auto.setText("Automatically upload completed corrected rides");auto.setChecked(prefs.getBoolean("auto_upload",false));body.addView(auto);
        auto.setOnCheckedChangeListener((b,checked)->{
            if(checked && (!prefs.getBoolean("backup_sync_ack",false)||!api.authorized())){auto.setChecked(false);alert("Finish setup first","Confirm that Garmin auto-sync is off and connect Strava before enabling automatic upload.");return;}
            prefs.edit().putBoolean("auto_upload",checked).apply();
        });
        text(body,"Uploads use your Strava account's default activity privacy. Nothing is uploaded until you explicitly upload a saved ride or enable automatic uploading.",14);
        button(body,"Phone battery settings",v->{try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(Exception e){alert("Battery settings","Open Android Settings → Apps → LiftFree MTB → Battery. Allow background use during rides.");}});
        text(body,"At the bike park",22);
        text(body,"Start the phone companion, then open LiftFree MTB on the watch. Wait for GPS and PHONE CONNECTED; press START.\n\nOn the first trip up a new lift, press BACK when boarding and BACK at the top. Later trips on that learned route can be automatic. DOWN forces riding if a climb is mistaken for a lift.\n\nFinish with START → Finish and send. Keep the phone nearby until SAVED ON PHONE appears. Your entire ride remains one activity.",15);
        text(body,"Ride files stay on this phone. The native Garmin recording remains a backup. Lift time, distance, and the actual altitude graph are kept; only ascent is corrected. If the phone buffer overflows, the app keeps the backup and refuses an incomplete corrected upload.",14);
        text(body,"Saved rides",22);rides=new LinearLayout(this);rides.setOrientation(LinearLayout.VERTICAL);body.addView(rides);
        button(body,"Refresh saved rides",v->{listing="";refreshRides();});
        text(body,"Compiled and simulator-tested is not field-tested. After an upload, LiftFree compares Strava's elevation with the corrected file and shows VERIFIED or ELEVATION MISMATCH. A real watch/phone/Strava test is still required.",14);
        if(saved==null)handleCallback(getIntent());
    }
    void alert(String title,String message){if(!isFinishing())new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();}
    void startBridge(Intent intent){
        List<String> need=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!need.isEmpty()){requestedService=intent;requestPermissions(need.toArray(new String[0]),100);return;}
        launchBridge(intent);
    }
    void launchBridge(Intent intent){try{startForegroundService(intent);}catch(Exception e){alert("Companion could not start",e.getMessage());}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==100){Intent intent=requestedService;requestedService=null;
            if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){alert("Nearby devices permission needed","Allow Nearby devices for the Garmin connection. No location permission is requested by this phone companion; GPS comes from the watch.");return;}
            if(intent!=null)launchBridge(intent);
        }
    }
    void chooseWatch(){
        BridgeService bridge=BridgeService.instance;
        if(bridge==null || bridge.devices.isEmpty()){alert("Start the companion first","Open Garmin Connect to make sure the watch is paired, then start the phone companion and try again.");return;}
        List<IQDevice> devices=new ArrayList<>(bridge.devices);String[] names=new String[devices.size()];for(int i=0;i<names.length;i++)names[i]=devices.get(i).getFriendlyName();
        new AlertDialog.Builder(this).setTitle("Your watch").setItems(names,(d,n)->startBridge(new Intent(this,BridgeService.class).setAction("SELECT").putExtra("device",devices.get(n).getDeviceIdentifier()))).show();
    }
    void account(){
        if(api.configured()){
            new AlertDialog.Builder(this).setTitle(api.authorized()?"Strava is connected":"Strava authorization").setItems(new String[]{"Authorize / reconnect Strava","Change API client settings"},(d,i)->{if(i==0)authorize();else credentials();}).show();
        }else credentials();
    }
    void credentials(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(8),dp(20),dp(8));
        text(form,"This personal build needs your own Strava API client. At strava.com/settings/api, create an application and use localhost as the Authorization Callback Domain. Enter the client ID and secret here on your phone, not in chat or GitHub. They are encrypted in Android's Keystore-backed storage.",14);
        Button site=button(form,"Open Strava API settings",v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.strava.com/settings/api"))));
        EditText id=new EditText(this);id.setHint("Client ID");id.setInputType(InputType.TYPE_CLASS_NUMBER);form.addView(id);
        EditText secret=new EditText(this);secret.setHint("Client secret");secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);form.addView(secret);
        try{String old=api.secrets.get("config");if(!old.isEmpty())id.setText(new JSONObject(old).getString("client"));}catch(Exception ignored){}
        new AlertDialog.Builder(this).setTitle("Personal Strava client").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save and authorize",(d,w)->{
            try{api.config(id.getText().toString().trim(),secret.getText().toString().trim());authorize();}catch(Exception e){alert("Client settings",e.getMessage());}
        }).show();
    }
    void authorize(){try{startActivity(new Intent(Intent.ACTION_VIEW,api.authorization()));}catch(Exception e){alert("Strava authorization",e.getMessage());}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleCallback(intent);}
    void handleCallback(Intent intent){
        Uri uri=intent==null?null:intent.getData();if(uri==null||!"liftfreemtb".equals(uri.getScheme()))return;
        live.setText("Completing Strava authorization…");
        worker.execute(()->{try{api.callback(uri);handler.post(()->alert("Strava connected","You can now upload a saved ride or enable automatic uploads. Check Strava's default activity privacy before your first test."));}catch(Exception e){handler.post(()->alert("Strava authorization failed",e.getMessage()));}});
    }
    void refreshRides(){
        File root=new File(getFilesDir(),"rides");File[] dirs=root.listFiles();if(dirs==null)dirs=new File[0];Arrays.sort(dirs,(a,b)->b.getName().compareTo(a.getName()));
        List<JSONObject> entries=new ArrayList<>();StringBuilder signature=new StringBuilder();
        for(File dir:dirs){if(entries.size()>=20)break;try{JSONObject j=RideStore.readJson(new File(dir,"ready.json"));entries.add(j);signature.append(j.toString());}catch(Exception ignored){}}
        if(signature.toString().equals(listing)&&rides.getChildCount()>0)return;listing=signature.toString();rides.removeAllViews();
        if(entries.isEmpty()){text(rides,"No completed rides yet.",16);return;}
        for(JSONObject j:entries){
            String id=j.optString("id"),state=j.optString("status");long expected=j.optLong("expected_ascent");
            text(rides,new java.text.SimpleDateFormat("MMM d, yyyy h:mm a",Locale.getDefault()).format(new Date(Long.parseLong(id)*1000)),18);
            text(rides,"Riding ascent: "+expected+" m / "+Math.round(expected*3.28084)+" ft\nLift trips: "+j.optInt("lift_trips")+" · Samples: "+j.optInt("samples")+"\nStatus: "+state+(j.has("strava_ascent")?"\nStrava ascent: "+j.optDouble("strava_ascent")+" m":"")+(j.optString("error").isEmpty()?"":"\n"+j.optString("error")),15);
            button(rides,j.optLong("activity_id")>0?"Recheck Strava elevation":"Upload / check upload",v->upload(id,state));
            button(rides,"Export corrected FIT",v->export(id));
            if(j.optLong("activity_id")>0){long aid=j.optLong("activity_id");button(rides,"Open activity in Strava",v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.strava.com/activities/"+aid))));}
        }
    }
    void upload(String id,String state){
        if(!api.authorized()){account();return;}
        if(!prefs.getBoolean("backup_sync_ack",false)){alert("Avoid duplicate recordings","Turn off Garmin → Strava auto-sync and confirm it above before uploading the corrected ride.");return;}
        String message="Upload this corrected activity to your Strava account? Your default Strava privacy applies.";
        if(state.equals("uncertain")||state.equals("uploading"))message="The previous upload result is uncertain. Check Strava for an existing activity before retrying. Retry now?";
        new AlertDialog.Builder(this).setTitle("Strava upload").setMessage(message).setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->startBridge(new Intent(this,BridgeService.class).setAction("UPLOAD").putExtra("id",id))).show();
    }
    void export(String id){exportId=id;Intent out=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"LiftFree-"+id+".fit");startActivityForResult(out,200);}
    @Override public void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==200&&result==RESULT_OK&&data!=null&&data.getData()!=null&&exportId!=null){
            String id=exportId;Uri destination=data.getData();exportId=null;
            worker.execute(()->{try{if(!id.matches("[0-9]{9,10}"))throw new IOException("Invalid ride ID");try(OutputStream out=getContentResolver().openOutputStream(destination,"w")){if(out==null)throw new IOException("Could not open destination");Files.copy(new File(getFilesDir(),"rides/"+id+"/activity.fit").toPath(),out);}handler.post(()->alert("FIT exported","Your corrected activity file was saved."));}catch(Exception e){handler.post(()->alert("Export failed",e.getMessage()));}});
        }
    }
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("exportId",exportId);}
    final Runnable refresh=new Runnable(){public void run(){live.setText(BridgeService.status);refreshRides();handler.postDelayed(this,2000);}};
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);worker.shutdown();super.onDestroy();}
}
