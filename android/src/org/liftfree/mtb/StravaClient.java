package org.liftfree.mtb;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.LongSupplier;

/** Strava protocol and durable upload state. Android only supplies encrypted settings. */
class StravaClient {
    static final String REDIRECT="liftfreemtb://localhost/exchange_token";
    // The Activity and Service create separate clients over the same encrypted settings.
    private static final Object AUTH_LOCK=new Object(), UPLOAD_LOCK=new Object();
    interface Settings {
        String get(String name)throws Exception;
        void put(String name,String value)throws Exception;
    }
    interface Connections {HttpURLConnection open(String url)throws Exception;}
    interface Sleeper {void sleep(long millis)throws InterruptedException;}
    final Settings secrets;
    final Connections connections;
    final LongSupplier clock;
    final Sleeper sleeper;

    StravaClient(Settings settings){this(settings,url->(HttpURLConnection)new URL(url).openConnection(),()->System.currentTimeMillis()/1000,Thread::sleep);}
    StravaClient(Settings settings,Connections connections,LongSupplier clock,Sleeper sleeper){
        this.secrets=settings;this.connections=connections;this.clock=clock;this.sleeper=sleeper;
    }
    static String encode(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    static Map<String,String> query(URI uri)throws Exception {
        Map<String,String> values=new HashMap<>();
        if(uri.getRawQuery()!=null)for(String part:uri.getRawQuery().split("&")){
            String[] pair=part.split("=",2);
            String key=URLDecoder.decode(pair[0],"UTF-8");
            if(values.containsKey(key))throw new IOException("Repeated OAuth parameter");
            values.put(key,pair.length==2?URLDecoder.decode(pair[1],"UTF-8"):"");
        }
        return values;
    }
    void config(String client,String secret)throws Exception {
        if(client==null || secret==null || !client.matches("[0-9]+") || secret.trim().isEmpty())throw new IOException("Enter your Strava API client ID and secret");
        synchronized(AUTH_LOCK) {
            String old=secrets.get("config");
            JSONObject previous=old.isEmpty()?new JSONObject():new JSONObject(old);
            if(!client.equals(previous.optString("client")) || !secret.trim().equals(previous.optString("secret"))){
                secrets.put("tokens","");secrets.put("oauth_state","");
            }
            secrets.put("config",new JSONObject().put("client",client).put("secret",secret.trim()).toString());
        }
    }
    boolean configured(){try{return !secrets.get("config").isEmpty();}catch(Exception e){return false;}}
    boolean authorized(){try{return !secrets.get("tokens").isEmpty();}catch(Exception e){return false;}}
    String authorizationUrl()throws Exception {
        synchronized(AUTH_LOCK) {
        JSONObject cfg=new JSONObject(secrets.get("config"));String state=UUID.randomUUID().toString();secrets.put("oauth_state",state);
        return "https://www.strava.com/oauth/mobile/authorize?client_id="+encode(cfg.getString("client"))+"&redirect_uri="+encode(REDIRECT)+"&response_type=code&approval_prompt=auto&scope="+encode("activity:write,activity:read_all")+"&state="+encode(state);
        }
    }
    void callback(String callback)throws Exception {
        synchronized(AUTH_LOCK) {
        URI uri=new URI(callback);Map<String,String> query=query(uri);
        if(!"liftfreemtb".equals(uri.getScheme()) || !"localhost".equals(uri.getRawAuthority()) || !"/exchange_token".equals(uri.getRawPath()) || uri.getRawFragment()!=null)throw new IOException("Wrong OAuth callback");
        String expected=secrets.get("oauth_state"),actual=query.get("state");
        if(expected.isEmpty() || !expected.equals(actual))throw new IOException("OAuth state mismatch; authorize again");
        secrets.put("oauth_state","");
        if(query.get("error")!=null)throw new IOException("Strava authorization was declined");
        String code=query.get("code");if(code==null || code.trim().isEmpty())throw new IOException("No authorization code");
        Set<String> scopes=new HashSet<>(Arrays.asList(query.getOrDefault("scope","").split("[,\\s]+")));
        if(!scopes.contains("activity:write") || !scopes.contains("activity:read_all"))throw new IOException("Allow both activity permissions to upload and verify private rides; authorize again");
        JSONObject cfg=new JSONObject(secrets.get("config"));
        JSONObject token=form("https://www.strava.com/oauth/token",Map.of("client_id",cfg.getString("client"),"client_secret",cfg.getString("secret"),"code",code,"grant_type","authorization_code"));
        validateToken(token);
        secrets.put("tokens",token.toString());
        }
    }
    void validateToken(JSONObject token)throws Exception {
        for(String field:new String[]{"access_token","refresh_token"})
            if(!(token.opt(field) instanceof String) || token.getString(field).trim().isEmpty())throw new IOException("Incomplete authorization response; reconnect Strava");
        if(token.optLong("expires_at")<=clock.getAsLong())throw new IOException("Invalid token expiration; reconnect Strava");
    }
    String access()throws Exception {
        synchronized(AUTH_LOCK) {
        JSONObject t=new JSONObject(secrets.get("tokens"));
        if(t.optLong("expires_at")<=clock.getAsLong()+120) {
            JSONObject cfg=new JSONObject(secrets.get("config"));
            t=form("https://www.strava.com/oauth/token",Map.of("client_id",cfg.getString("client"),"client_secret",cfg.getString("secret"),"refresh_token",t.getString("refresh_token"),"grant_type","refresh_token"));
            validateToken(t);secrets.put("tokens",t.toString());
        }
        validateToken(t);
        return t.getString("access_token");
        }
    }
    JSONObject form(String url,Map<String,String> values)throws Exception {
        StringBuilder b=new StringBuilder();for(Map.Entry<String,String> e:values.entrySet()){if(b.length()>0)b.append('&');b.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));}
        HttpURLConnection c=open(url,"POST",null);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        byte[] bytes=b.toString().getBytes(StandardCharsets.UTF_8);
        // Streaming mode prevents HttpURLConnection from silently replaying a token POST.
        c.setFixedLengthStreamingMode(bytes.length);
        try {
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            return response(c);
        }finally{c.disconnect();}
    }
    HttpURLConnection open(String url,String method,String token)throws Exception {
        HttpURLConnection c=connections.open(url);c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(false);
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
    void upload(RideStore ride)throws Exception {
        // Serialize across client/store instances before reading durable state, not just per object.
        synchronized(UPLOAD_LOCK){uploadLocked(ride);}
    }
    private void uploadLocked(RideStore ride)throws Exception {
        if(!ride.ready())throw new IOException("A complete corrected recording is required before upload");
        JSONObject state=ride.status();
        double expected=state.getDouble("expected_ascent");
        if(!Double.isFinite(expected) || expected<0)throw new IOException("Invalid corrected ascent");
        if(state.optLong("activity_id")>0){verify(ride,state);return;}
        long uploadId=state.optLong("upload_id");
        if(uploadId==0) {
            // external_id is stable metadata, not a documented idempotency guarantee.
            // A lost response (or process death while uploading) must never cause another POST.
            if(!"ready".equals(state.optString("status")))throw new IOException("Upload result is uncertain. Check Strava; LiftFree will not resubmit this recording. Export the FIT only if you confirm it is absent.");
            String token=access();String boundary="LiftFree"+UUID.randomUUID().toString().replace("-","");
            HttpURLConnection c=open("https://www.strava.com/api/v3/uploads","POST",token);c.setDoOutput(true);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);c.setChunkedStreamingMode(16384);
            JSONObject result;
            try {
                state.put("status","uploading").put("error","");ride.status(state);
                try(OutputStream out=c.getOutputStream()) {
                    part(out,boundary,"data_type","fit");part(out,boundary,"sport_type","MountainBikeRide");part(out,boundary,"name","LiftFree Mountain Bike Ride");
                    part(out,boundary,"description","Garmin watch recording processed by LiftFree. Excludes "+state.optInt("lift_trips")+" marked lift trips from ascent. Recorded altitude, lift time and lift distance remain included.");
                    part(out,boundary,"external_id","liftfree-"+ride.id+".fit");
                    out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"liftfree.fit\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    Files.copy(new File(ride.dir,"activity.fit").toPath(),out);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
                }
                result=response(c);uploadId=result.getLong("id");
                if(uploadId<=0)throw new IOException("Invalid Strava upload ID");
                state.put("upload_id",uploadId).put("status","processing");ride.status(state);
            }catch(Exception e){state.put("status","uncertain").put("error","Upload result uncertain. Check Strava; do not resubmit. "+e.getMessage());ride.status(state);throw e;}
            finally{c.disconnect();}
            if(processed(ride,state,result))return;
        }
        if(uploadId<0)throw new IOException("Invalid stored upload ID; check Strava before proceeding");
        state.put("status","processing").put("error","");ride.status(state);
        for(int i=0;i<25;i++) {
            JSONObject p=get("/uploads/"+uploadId);
            if(processed(ride,state,p))return;
            if(i<24)try{sleeper.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        }
        state.put("status","processing");ride.status(state);
    }
    private boolean processed(RideStore ride,JSONObject state,JSONObject result)throws Exception {
        if(!result.isNull("error")&&!result.optString("error").isEmpty()){
            state.put("status","rejected").put("error",result.optString("error"));ride.status(state);
            throw new IOException("Strava rejected this upload; see ride status");
        }
        if(!result.isNull("activity_id")&&result.optLong("activity_id")>0){
            state.put("activity_id",result.getLong("activity_id")).put("status","uploaded");ride.status(state);
            verify(ride,state);return true;
        }
        return false;
    }
    void verify(RideStore ride,JSONObject state)throws Exception {
        state.put("status","uploaded").put("error","");state.remove("strava_ascent");ride.status(state);
        JSONObject activity=get("/activities/"+state.getLong("activity_id"));
        double actual=activity.getDouble("total_elevation_gain"),expected=state.getDouble("expected_ascent");
        if(!Double.isFinite(actual) || actual<0)throw new IOException("Invalid Strava elevation response");
        state.put("strava_ascent",actual).put("status",Math.abs(actual-expected)<=1.0?"verified":"ELEVATION MISMATCH");
        state.put("error",Math.abs(actual-expected)<=1.0?"":"Strava shows "+actual+" m; corrected file contains "+expected+" m.");ride.status(state);
    }
}
