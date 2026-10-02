package org.liftfree.mtb;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class StravaApi {
    static final String REDIRECT="liftfreemtb://localhost/exchange_token";
    final Context context;final Secrets secrets;
    StravaApi(Context c){context=c.getApplicationContext();secrets=new Secrets(context);}
    void config(String client,String secret)throws Exception {
        if(!client.matches("[0-9]+") || secret.trim().isEmpty())throw new IOException("Enter your Strava API client ID and secret");
        secrets.put("config",new JSONObject().put("client",client).put("secret",secret.trim()).toString());
    }
    boolean configured(){try{return !secrets.get("config").isEmpty();}catch(Exception e){return false;}}
    boolean authorized(){try{return !secrets.get("tokens").isEmpty();}catch(Exception e){return false;}}
    Uri authorization()throws Exception {
        JSONObject cfg=new JSONObject(secrets.get("config"));String state=UUID.randomUUID().toString();secrets.put("oauth_state",state);
        return Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon().appendQueryParameter("client_id",cfg.getString("client")).appendQueryParameter("redirect_uri",REDIRECT).appendQueryParameter("response_type","code").appendQueryParameter("approval_prompt","auto").appendQueryParameter("scope","activity:write,activity:read_all").appendQueryParameter("state",state).build();
    }
    void callback(Uri uri)throws Exception {
        if(!"liftfreemtb".equals(uri.getScheme()) || !"localhost".equals(uri.getHost()) || !"/exchange_token".equals(uri.getPath()))throw new IOException("Wrong OAuth callback");
        String expected=secrets.get("oauth_state"),actual=uri.getQueryParameter("state");
        if(expected.isEmpty() || !expected.equals(actual))throw new IOException("OAuth state mismatch; authorize again");
        secrets.put("oauth_state","");
        if(uri.getQueryParameter("error")!=null)throw new IOException("Strava authorization was declined");
        String code=uri.getQueryParameter("code");if(code==null)throw new IOException("No authorization code");
        JSONObject cfg=new JSONObject(secrets.get("config"));
        JSONObject token=form("https://www.strava.com/oauth/token",Map.of("client_id",cfg.getString("client"),"client_secret",cfg.getString("secret"),"code",code,"grant_type","authorization_code"));
        if(!token.has("access_token") || !token.has("refresh_token"))throw new IOException("Incomplete authorization response");
        secrets.put("tokens",token.toString());
    }
    synchronized String access()throws Exception {
        JSONObject t=new JSONObject(secrets.get("tokens"));
        if(t.optLong("expires_at")<System.currentTimeMillis()/1000+120) {
            JSONObject cfg=new JSONObject(secrets.get("config"));
            t=form("https://www.strava.com/oauth/token",Map.of("client_id",cfg.getString("client"),"client_secret",cfg.getString("secret"),"refresh_token",t.getString("refresh_token"),"grant_type","refresh_token"));
            if(!t.has("access_token") || !t.has("refresh_token"))throw new IOException("Reconnect Strava");secrets.put("tokens",t.toString());
        }
        return t.getString("access_token");
    }
    JSONObject form(String url,Map<String,String> values)throws Exception {
        StringBuilder b=new StringBuilder();for(Map.Entry<String,String> e:values.entrySet()){if(b.length()>0)b.append('&');b.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));}
        HttpURLConnection c=open(url,"POST",null);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        try(OutputStream out=c.getOutputStream()){out.write(b.toString().getBytes(StandardCharsets.UTF_8));}
        return response(c);
    }
    HttpURLConnection open(String url,String method,String token)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","LiftFreeMTB/0.3");if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);return c;
    }
    JSONObject response(HttpURLConnection c)throws Exception {
        try {
            int code=c.getResponseCode();if(code<200 || code>=300)throw new IOException("Strava HTTP "+code+(code==401?"; reconnect Strava":code==429?"; rate limit, retry later":""));
            try(InputStream in=c.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){b.write(buf,0,n);if(b.size()>2000000)throw new IOException("Response too large");}return new JSONObject(b.toString("UTF-8"));}
        } finally {c.disconnect();}
    }
    JSONObject get(String path)throws Exception{return response(open("https://www.strava.com/api/v3"+path,"GET",access()));}
    void part(OutputStream out,String boundary,String key,String value)throws IOException {
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+key+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));
    }
    void upload(RideStore ride,boolean explicitRetry)throws Exception {
        JSONObject state=ride.status();
        if(state.optLong("activity_id")>0){verify(ride,state);return;}
        long uploadId=state.optLong("upload_id");
        if(uploadId==0) {
            if("uncertain".equals(state.optString("status"))&&!explicitRetry)throw new IOException("Check Strava before retrying an uncertain upload");
            String token=access();String boundary="LiftFree"+UUID.randomUUID().toString().replace("-","");
            HttpURLConnection c=open("https://www.strava.com/api/v3/uploads","POST",token);c.setDoOutput(true);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);c.setChunkedStreamingMode(16384);
            state.put("status","uploading");ride.status(state);
            try {
                try(OutputStream out=c.getOutputStream()) {
                    part(out,boundary,"data_type","fit");part(out,boundary,"sport_type","MountainBikeRide");part(out,boundary,"name","LiftFree Mountain Bike Ride");
                    part(out,boundary,"description","Garmin watch recording processed by LiftFree. Excludes "+state.optInt("lift_trips")+" marked lift trips from ascent. Recorded altitude, lift time and lift distance remain included.");
                    part(out,boundary,"external_id","liftfree-"+ride.id+".fit");
                    out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"liftfree.fit\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    Files.copy(new File(ride.dir,"activity.fit").toPath(),out);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
                }
                JSONObject result=response(c);uploadId=result.getLong("id");state.put("upload_id",uploadId).put("status","processing");ride.status(state);
            }catch(Exception e){state.put("status","uncertain").put("error","Upload result uncertain. Check Strava before retrying. "+e.getMessage());ride.status(state);throw e;}
        }
        for(int i=0;i<25;i++) {
            JSONObject p=get("/uploads/"+uploadId);
            if(!p.isNull("error")&&!p.optString("error").isEmpty()){state.put("status","rejected").put("error",p.optString("error"));ride.status(state);throw new IOException("Strava rejected this upload; see ride status");}
            if(!p.isNull("activity_id")&&p.optLong("activity_id")>0){state.put("activity_id",p.getLong("activity_id")).put("status","uploaded");ride.status(state);verify(ride,state);return;}
            Thread.sleep(2000);
        }
        state.put("status","processing");ride.status(state);
    }
    void verify(RideStore ride,JSONObject state)throws Exception {
        JSONObject activity=get("/activities/"+state.getLong("activity_id"));
        double actual=activity.getDouble("total_elevation_gain"),expected=state.getDouble("expected_ascent");
        state.put("strava_ascent",actual).put("status",Math.abs(actual-expected)<=1.0?"verified":"ELEVATION MISMATCH");
        state.put("error",Math.abs(actual-expected)<=1.0?"":"Strava shows "+actual+" m; corrected file contains "+expected+" m.");ride.status(state);
    }
    /** Android Keystore-backed AES-GCM; no token or client secret is committed. */
    static final class Secrets {
        final SharedPreferences prefs;
        Secrets(Context c){prefs=c.getSharedPreferences("encrypted-secrets",Context.MODE_PRIVATE);}
        javax.crypto.SecretKey key()throws Exception {
            KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);
            if(!k.containsAlias("LiftFreeSecrets")){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder("LiftFreeSecrets",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
            return (javax.crypto.SecretKey)k.getKey("LiftFreeSecrets",null);
        }
        synchronized String get(String name)throws Exception {
            String value=prefs.getString(name,"");if(value.isEmpty())return "";byte[] blob=Base64.decode(value,Base64.NO_WRAP);if(blob.length<29)throw new IOException("Encrypted setting is damaged");
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(blob,12)));return new String(c.doFinal(Arrays.copyOfRange(blob,12,blob.length)),StandardCharsets.UTF_8);
        }
        synchronized void put(String name,String value)throws Exception {
            if(value.isEmpty()){if(!prefs.edit().remove(name).commit())throw new IOException("Cannot save setting");return;}
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(c.getIV());b.write(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
            if(!prefs.edit().putString(name,Base64.encodeToString(b.toByteArray(),Base64.NO_WRAP)).commit())throw new IOException("Cannot save encrypted setting");
        }
    }
}
