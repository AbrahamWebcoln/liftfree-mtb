package org.liftfree.mtb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real HTTP serialization against a loopback-only mock. All credentials/data are synthetic. */
public class StravaClientTest {
    static final long NOW=1800000000L, UPLOAD=9007199254740993L, ACTIVITY=9007199254740995L;
    static final String SCOPES="activity:write,activity:read_all";
    final Memory settings=new Memory();
    final List<Long> waits=new ArrayList<>();
    long now=NOW;
    Path root;
    MockHttp http;
    StravaClient api;

    static final class Memory implements StravaClient.Settings {
        final Map<String,String> values=new ConcurrentHashMap<>();
        public String get(String key){return values.getOrDefault(key,"");}
        public void put(String key,String value){values.put(key,value);}
    }
    static final class Request {
        final String method,path,contentType,authorization,accept;
        final byte[] bytes;
        Request(HttpExchange e)throws IOException {
            method=e.getRequestMethod();path=e.getRequestURI().toString();
            contentType=e.getRequestHeaders().getFirst("Content-Type");
            authorization=e.getRequestHeaders().getFirst("Authorization");
            accept=e.getRequestHeaders().getFirst("Accept");bytes=e.getRequestBody().readAllBytes();
        }
        String body(){return new String(bytes,StandardCharsets.ISO_8859_1);}
    }
    static final class Reply {
        final String method,path,body;final int code;
        Runnable beforeReply=()->{};
        Reply(String method,String path,int code,String body){this.method=method;this.path=path;this.code=code;this.body=body;}
    }
    static final class MockHttp implements AutoCloseable,StravaClient.Connections {
        final Queue<Reply> replies=new ConcurrentLinkedQueue<>();
        final List<Request> requests=Collections.synchronizedList(new ArrayList<>());
        final List<String> failures=Collections.synchronizedList(new ArrayList<>());
        final HttpServer server;
        final ExecutorService executor=Executors.newCachedThreadPool();
        MockHttp()throws IOException {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(executor);server.createContext("/",this::handle);server.start();
        }
        Reply reply(String method,String path,int code,String body){Reply r=new Reply(method,path,code,body);replies.add(r);return r;}
        void handle(HttpExchange exchange)throws IOException {
            try {
                Request r=new Request(exchange);requests.add(r);Reply next=replies.poll();
                if(next==null || !next.method.equals(r.method) || !next.path.equals(r.path)){
                    failures.add("Unexpected request: "+r.method+" "+r.path);
                    exchange.sendResponseHeaders(599,-1);return;
                }
                next.beforeReply.run();
                if(next.body==null)return; // accepted bytes, response lost
                byte[] bytes=next.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                if(next.code==302)exchange.getResponseHeaders().set("Location","https://must-not-be-contacted.invalid/");
                exchange.sendResponseHeaders(next.code,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}
        }
        public HttpURLConnection open(String url)throws Exception {
            URI uri=new URI(url);
            // Never permit a test request to reach Strava (or any non-loopback endpoint).
            if(!"https".equals(uri.getScheme()) || !"www.strava.com".equals(uri.getHost()) || uri.getPort()!=-1)
                throw new AssertionError("Unexpected production URL: "+url);
            if(!uri.getPath().equals("/oauth/token") && !uri.getPath().startsWith("/api/v3/"))
                throw new AssertionError("Unexpected API path: "+url);
            return (HttpURLConnection)new URL("http://127.0.0.1:"+server.getAddress().getPort()+uri.getRawPath()).openConnection(Proxy.NO_PROXY);
        }
        public void close(){server.stop(0);executor.shutdownNow();}
    }
    @Before public void setup()throws Exception {
        root=Files.createTempDirectory("liftfree-synthetic-");http=new MockHttp();api=client();
        api.config("12345","SYNTHETIC secret&+%");
        settings.put("tokens",tokens("fake-access-A","fake-refresh-A",NOW+3600));
    }
    @After public void cleanup()throws Exception {
        try {
            assertEquals("HTTP request order",Collections.emptyList(),http.failures);
            assertTrue("All expected mock responses consumed",http.replies.isEmpty());
        }finally{
            http.close();try(java.util.stream.Stream<Path> files=Files.walk(root)){
                for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);
            }
        }
    }
    StravaClient client(){return new StravaClient(settings,http,()->now,millis->waits.add(millis));}
    static String tokens(String access,String refresh,long expires)throws Exception {
        return new JSONObject().put("access_token",access).put("refresh_token",refresh).put("expires_at",expires).toString();
    }
    RideStore ride()throws Exception {
        RideStore r=new RideStore(root.toFile(),Long.toString(CoreTest.T));
        List<Core.Row> rows=CoreTest.example();
        for(int base=0;base<rows.size();base+=22){
            StringBuilder csv=new StringBuilder();for(int i=base;i<Math.min(base+22,rows.size());i++)csv.append(rows.get(i).csv()).append('\n');
            r.append(base,csv.toString(),3289,CoreTest.T);
        }
        r.finish(rows.size(),CoreTest.T+43,43000,3289,CoreTest.T);return r;
    }
    RideStore reopen(RideStore r)throws Exception{return new RideStore(root.toFile(),r.id);}
    static Map<String,String> form(String text)throws Exception{return StravaClient.query(new URI("https://fake.invalid/?"+text));}
    String callback(String state,String code,String scopes)throws Exception {
        return StravaClient.REDIRECT+"?state="+StravaClient.encode(state)+(code==null?"":"&code="+StravaClient.encode(code))+(scopes==null?"":"&scope="+StravaClient.encode(scopes));
    }
    String startAuth()throws Exception{settings.put("tokens","");api.authorizationUrl();return settings.get("oauth_state");}
    interface Work {void run()throws Exception;}
    static Exception fails(Work work)throws Exception {
        try{work.run();}catch(Exception e){return e;}throw new AssertionError("Expected failure");
    }
    void uploadReply(){http.reply("POST","/api/v3/uploads",201,"{\"id\":"+UPLOAD+",\"error\":null,\"activity_id\":null}");}
    void processed(){http.reply("GET","/api/v3/uploads/"+UPLOAD,200,"{\"error\":null,\"activity_id\":"+ACTIVITY+"}");}
    void elevation(double value){http.reply("GET","/api/v3/activities/"+ACTIVITY,200,"{\"total_elevation_gain\":"+value+"}");}
    void knownUpload(RideStore r,String status)throws Exception {r.status(r.status().put("upload_id",UPLOAD).put("status",status));}
    void knownActivity(RideStore r)throws Exception {r.status(r.status().put("activity_id",ACTIVITY).put("status","uploaded"));}

    @Test public void authorizationRequestsRequiredScopesAndFreshState()throws Exception {
        URI url=new URI(api.authorizationUrl());Map<String,String> q=StravaClient.query(url);String first=q.get("state");
        assertEquals("https",url.getScheme());assertEquals("www.strava.com",url.getHost());
        assertEquals("/oauth/mobile/authorize",url.getPath());assertEquals("12345",q.get("client_id"));
        assertEquals(StravaClient.REDIRECT,q.get("redirect_uri"));assertEquals(SCOPES,q.get("scope"));
        assertEquals("code",q.get("response_type"));assertEquals(first,settings.get("oauth_state"));
        api.authorizationUrl();assertNotEquals(first,settings.get("oauth_state"));assertFalse(url.toString().contains("secret"));
    }
    @Test public void callbackExchangesEncodedFormAndCannotBeReplayed()throws Exception {
        String state=startAuth(),code="fake code/&+=%";
        http.reply("POST","/oauth/token",200,tokens("fake-access-B","fake-refresh-B",NOW+20000));
        String uri=callback(state,code,SCOPES);api.callback(uri);
        Request r=http.requests.get(0);Map<String,String> body=form(new String(r.bytes,StandardCharsets.UTF_8));
        assertEquals("application/x-www-form-urlencoded",r.contentType);assertNull(r.authorization);
        assertEquals("application/json",r.accept);assertEquals(code,body.get("code"));
        assertEquals("SYNTHETIC secret&+%",body.get("client_secret"));assertEquals("12345",body.get("client_id"));
        assertEquals("authorization_code",body.get("grant_type"));assertEquals("",settings.get("oauth_state"));
        assertEquals("fake-access-B",api.access());fails(()->api.callback(uri));assertEquals(1,http.requests.size());
    }
    @Test public void wrongCallbackAddressOrStateCannotConsumePendingAuthorization()throws Exception {
        String state=startAuth();
        for(String base:new String[]{"wrong://localhost/exchange_token","liftfreemtb://other/exchange_token","liftfreemtb://localhost/wrong","liftfreemtb://localhost:99/exchange_token","liftfreemtb://user@localhost/exchange_token"}){
            fails(()->api.callback(base+"?state="+state+"&code=fake&scope=activity:write,activity:read_all"));
            assertEquals(state,settings.get("oauth_state"));
        }
        fails(()->api.callback(callback("wrong-state","fake",SCOPES)));fails(()->api.callback(StravaClient.REDIRECT+"?code=fake"));
        assertEquals(state,settings.get("oauth_state"));assertTrue(http.requests.isEmpty());
    }
    @Test public void callbackRejectsFragmentsAndRepeatedParameters()throws Exception {
        String state=startAuth();fails(()->api.callback(callback(state,"fake",SCOPES)+"#fragment"));
        String next=startAuth();final String uri=callback(next,"fake",SCOPES)+"&state="+next;
        fails(()->api.callback(uri));assertTrue(http.requests.isEmpty());
    }
    @Test public void deniedMissingAndBlankCodesNeverExchangeTokens()throws Exception {
        String state=startAuth();apiErrorCallback(state);
        for(String code:Arrays.asList(null,""," ")){
            String s=startAuth();fails(()->api.callback(callback(s,code,SCOPES)));assertEquals("",settings.get("oauth_state"));
        }
        assertTrue(http.requests.isEmpty());assertEquals("",settings.get("tokens"));
    }
    void apiErrorCallback(String state)throws Exception {
        Exception error=fails(()->api.callback(callback(state,null,null)+"&error=access_denied"));
        assertTrue(error.getMessage().contains("declined"));assertEquals("",settings.get("oauth_state"));
    }
    @Test public void missingPermissionsAreRejectedBeforeTokenExchange()throws Exception {
        for(String scope:Arrays.asList(null,"","read","activity:write","activity:read_all","read,activity:write")){
            String state=startAuth();fails(()->api.callback(callback(state,"fake",scope)));assertEquals("",settings.get("tokens"));
        }
        assertTrue(http.requests.isEmpty());
    }
    @Test public void incompleteAuthorizationTokensAreNeverSaved()throws Exception {
        for(String body:Arrays.asList("{}","{\"access_token\":null,\"refresh_token\":null}",tokens("","fake",NOW+1000),tokens("fake","",NOW+1000),"{\"access_token\":\"fake\",\"refresh_token\":\"fake\"}",tokens("fake","fake",NOW-1))){
            String state=startAuth();http.reply("POST","/oauth/token",200,body);
            fails(()->api.callback(callback(state,"fake",SCOPES)));assertEquals("",settings.get("tokens"));
        }
    }
    @Test public void failedAuthorizationResponseCannotReplayCode()throws Exception {
        for(String body:Arrays.asList("not-json",null)){
            String state=startAuth(),uri=callback(state,"fake",SCOPES);http.reply("POST","/oauth/token",200,body);
            fails(()->api.callback(uri));fails(()->api.callback(uri));assertEquals("",settings.get("tokens"));
        }
    }
    @Test public void changingClientInvalidatesTokensAndPendingCallback()throws Exception {
        api.authorizationUrl();api.config("67890","different synthetic secret");
        assertFalse(api.authorized());assertEquals("",settings.get("oauth_state"));
    }
    @Test public void invalidOrUnchangedConfigurationPreservesCurrentAuthorization()throws Exception {
        String original=settings.get("tokens");api.config("12345","SYNTHETIC secret&+%");assertEquals(original,settings.get("tokens"));
        for(String id:Arrays.asList(null,"","abc","12&3"))fails(()->api.config(id,"fake"));
        fails(()->api.config("12345"," "));assertEquals(original,settings.get("tokens"));
    }
    @Test public void tokenExchangeHttpErrorsDoNotAuthorizeOrLeakResponseBody()throws Exception {
        for(int code:new int[]{400,401,403,429,500}){
            String state=startAuth();http.reply("POST","/oauth/token",code,"{\"secret\":\"never-display\"}");
            Exception e=fails(()->api.callback(callback(state,"fake",SCOPES)));
            assertTrue(e.getMessage().contains("HTTP "+code));assertFalse(e.getMessage().contains("never-display"));assertFalse(api.authorized());
        }
    }
    @Test public void validAccessTokenNeedsNoHttp()throws Exception {assertEquals("fake-access-A",api.access());assertTrue(http.requests.isEmpty());}
    @Test public void refreshWindowDoesNotRefreshEarlierThanNecessary()throws Exception {
        settings.put("tokens",tokens("fake-access-A","fake-refresh-A",NOW+121));assertEquals("fake-access-A",api.access());assertTrue(http.requests.isEmpty());
    }
    @Test public void expiredAndAboutToExpireTokensRefresh()throws Exception {
        for(long expiry:new long[]{NOW-1,NOW,NOW+119,NOW+120}){
            settings.put("tokens",tokens("old-access","old-refresh",expiry));
            http.reply("POST","/oauth/token",200,tokens("new-access","new-refresh",NOW+3600));
            assertEquals("new-access",api.access());Request r=http.requests.get(http.requests.size()-1);
            Map<String,String> body=form(new String(r.bytes,StandardCharsets.UTF_8));
            assertEquals("refresh_token",body.get("grant_type"));assertEquals("old-refresh",body.get("refresh_token"));
            assertEquals("new-refresh",new JSONObject(settings.get("tokens")).getString("refresh_token"));
        }
    }
    @Test public void rotatedRefreshTokenIsUsedOnNextExpiration()throws Exception {
        now=NOW+4000;http.reply("POST","/oauth/token",200,tokens("fake-access-B","fake-refresh-B",now+3600));api.access();
        now+=4000;http.reply("POST","/oauth/token",200,tokens("fake-access-C","fake-refresh-C",now+3600));api.access();
        assertEquals("fake-refresh-B",form(new String(http.requests.get(1).bytes,StandardCharsets.UTF_8)).get("refresh_token"));
    }
    @Test public void failedRefreshPreservesStoredTokensAndDoesNotUpload()throws Exception {
        RideStore r=ride();now=NOW+4000;String previous=settings.get("tokens");
        for(int code:new int[]{400,401,429,503}){
            http.reply("POST","/oauth/token",code,"{}");fails(()->api.upload(r));
            assertEquals(previous,settings.get("tokens"));assertEquals("ready",r.status().getString("status"));
        }
        assertEquals(4,http.requests.size());
    }
    @Test public void invalidRefreshResponsePreservesPreviousTokens()throws Exception {
        now=NOW+4000;String previous=settings.get("tokens");
        for(String response:Arrays.asList("{}",tokens("","fake",now+3600),tokens("fake","",now+3600),tokens("fake","fake",now-1))){
            http.reply("POST","/oauth/token",200,response);fails(()->api.access());assertEquals(previous,settings.get("tokens"));
        }
    }
    @Test public void multipartSubmissionPreservesExactCorrectedFitAndVerifies()throws Exception {
        RideStore r=ride();byte[] fit=Files.readAllBytes(new File(r.dir,"activity.fit").toPath()),csv=Files.readAllBytes(new File(r.dir,"samples.csv").toPath());
        uploadReply();http.reply("GET","/api/v3/uploads/"+UPLOAD,200,"{\"activity_id\":null,\"error\":null}");processed();elevation(20);
        api.upload(r);Request request=http.requests.get(0);
        assertEquals("Bearer fake-access-A",request.authorization);assertEquals("application/json",request.accept);
        assertTrue(request.contentType.startsWith("multipart/form-data; boundary="));String boundary=request.contentType.split("boundary=")[1];
        String text=request.body();assertTrue(text.endsWith("\r\n--"+boundary+"--\r\n"));
        Map<String,String> fields=new HashMap<>();String filePrefix=null;
        for(String key:new String[]{"data_type","sport_type","name","description","external_id"}){
            String prefix="--"+boundary+"\r\nContent-Disposition: form-data; name=\""+key+"\"\r\n\r\n";
            int at=text.indexOf(prefix);assertTrue("Multipart field "+key,at>=0);int end=text.indexOf("\r\n",at+prefix.length());fields.put(key,text.substring(at+prefix.length(),end));
        }
        assertEquals("fit",fields.get("data_type"));assertEquals("MountainBikeRide",fields.get("sport_type"));
        assertEquals("liftfree-"+r.id+".fit",fields.get("external_id"));assertTrue(fields.get("description").contains("1 marked lift trips"));
        filePrefix="Content-Disposition: form-data; name=\"file\"; filename=\"liftfree.fit\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        int offset=text.indexOf(filePrefix)+filePrefix.length();assertTrue(offset>=filePrefix.length());
        assertArrayEquals(fit,Arrays.copyOfRange(request.bytes,offset,offset+fit.length));
        assertArrayEquals(fit,Files.readAllBytes(new File(r.dir,"activity.fit").toPath()));assertArrayEquals(csv,Files.readAllBytes(new File(r.dir,"samples.csv").toPath()));
        JSONObject state=r.status();assertEquals("verified",state.getString("status"));assertEquals(UPLOAD,state.getLong("upload_id"));
        assertEquals(ACTIVITY,state.getLong("activity_id"));assertEquals(20,state.getDouble("strava_ascent"),0);assertEquals(Arrays.asList(2000L),waits);
    }
    @Test public void elevationToleranceAndMismatchAreReportedWithoutChangingFit()throws Exception {
        RideStore r=ride();knownActivity(r);byte[] fit=Files.readAllBytes(new File(r.dir,"activity.fit").toPath());
        for(double value:new double[]{19,20,20.8,21,18.99,21.01,120}){
            elevation(value);client().upload(reopen(r));JSONObject state=r.status();boolean match=Math.abs(value-20)<=1;
            assertEquals(match?"verified":"ELEVATION MISMATCH",state.getString("status"));assertEquals(value,state.getDouble("strava_ascent"),0);
            assertEquals(match,state.getString("error").isEmpty());assertArrayEquals(fit,Files.readAllBytes(new File(r.dir,"activity.fit").toPath()));
        }
    }
    @Test public void pendingProcessingIsBoundedAndResumesAfterRestartWithoutPost()throws Exception {
        RideStore r=ride();uploadReply();for(int i=0;i<25;i++)http.reply("GET","/api/v3/uploads/"+UPLOAD,200,"{\"error\":null,\"activity_id\":null}");
        api.upload(r);assertEquals("processing",r.status().getString("status"));assertEquals(24,waits.size());
        processed();elevation(20);client().upload(reopen(r));assertEquals("verified",r.status().getString("status"));assertEquals(1,posts());
    }
    @Test public void alreadyProcessedSubmissionVerifiesWithoutPolling()throws Exception {
        RideStore r=ride();http.reply("POST","/api/v3/uploads",201,"{\"id\":"+UPLOAD+",\"activity_id\":"+ACTIVITY+",\"error\":null}");
        elevation(20);api.upload(r);assertEquals(2,http.requests.size());assertEquals("verified",r.status().getString("status"));
    }
    @Test public void interruptedPollingPreservesUploadAndThreadInterruption()throws Exception {
        RideStore r=ride();knownUpload(r,"processing");http.reply("GET","/api/v3/uploads/"+UPLOAD,200,"{\"activity_id\":null,\"error\":null}");
        StravaClient interrupted=new StravaClient(settings,http,()->now,millis->{throw new InterruptedException("synthetic interrupt");});
        try {
            assertTrue(fails(()->interrupted.upload(r)) instanceof InterruptedException);assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(UPLOAD,r.status().getLong("upload_id"));assertEquals("processing",r.status().getString("status"));
        }finally{Thread.interrupted();}
        processed();elevation(20);client().upload(reopen(r));assertEquals(0,posts());
    }
    int posts(){return (int)http.requests.stream().filter(r->r.path.equals("/api/v3/uploads")&&r.method.equals("POST")).count();}
    @Test public void pollApiFailuresRetainUploadIdForSafeRecovery()throws Exception {
        RideStore r=ride();knownUpload(r,"processing");
        for(int code:new int[]{401,403,404,429,500,503}){
            http.reply("GET","/api/v3/uploads/"+UPLOAD,code,"{\"secret\":\"do-not-display\"}");Exception e=fails(()->client().upload(reopen(r)));
            assertTrue(e.getMessage().contains("HTTP "+code));assertFalse(e.getMessage().contains("do-not-display"));assertEquals(UPLOAD,r.status().getLong("upload_id"));
        }
        processed();elevation(20);client().upload(reopen(r));assertEquals(0,posts());assertEquals("verified",r.status().getString("status"));
    }
    @Test public void processingRejectionIsPersistedAndNeverPostsAgain()throws Exception {
        RideStore r=ride();uploadReply();http.reply("GET","/api/v3/uploads/"+UPLOAD,200,"{\"error\":\"duplicate activity\",\"activity_id\":null}");
        fails(()->api.upload(r));assertEquals("rejected",r.status().getString("status"));assertEquals("duplicate activity",r.status().getString("error"));
        http.reply("GET","/api/v3/uploads/"+UPLOAD,200,"{\"error\":\"duplicate activity\"}");fails(()->client().upload(reopen(r)));assertEquals(1,posts());
    }
    @Test public void initialProcessingErrorIsHandledWithoutAnotherRequest()throws Exception {
        RideStore r=ride();http.reply("POST","/api/v3/uploads",201,"{\"id\":"+UPLOAD+",\"error\":\"invalid FIT\"}");
        fails(()->api.upload(r));assertEquals("rejected",r.status().getString("status"));assertEquals(UPLOAD,r.status().getLong("upload_id"));
    }
    @Test public void verificationFailureKeepsActivityIdAndCanBeRechecked()throws Exception {
        RideStore r=ride();uploadReply();processed();http.reply("GET","/api/v3/activities/"+ACTIVITY,503,"{}");
        fails(()->api.upload(r));assertEquals(ACTIVITY,r.status().getLong("activity_id"));
        elevation(20);client().upload(reopen(r));assertEquals("verified",r.status().getString("status"));assertEquals(1,posts());
    }
    @Test public void malformedActivityNeverBecomesVerified()throws Exception {
        RideStore r=ride();knownActivity(r);
        for(String response:Arrays.asList("not-json","{}","{\"total_elevation_gain\":null}","{\"total_elevation_gain\":-1}","{\"total_elevation_gain\":\"NaN\"}")){
            http.reply("GET","/api/v3/activities/"+ACTIVITY,200,response);fails(()->api.upload(r));
            assertEquals(ACTIVITY,r.status().getLong("activity_id"));assertNotEquals("verified",r.status().getString("status"));
        }
    }
    @Test public void failedRecheckClearsStaleVerifiedStatus()throws Exception {
        RideStore r=ride();knownActivity(r);elevation(20);api.upload(r);
        http.reply("GET","/api/v3/activities/"+ACTIVITY,401,"{}");fails(()->api.upload(r));assertEquals("uploaded",r.status().getString("status"));
    }
    @Test public void lostSubmissionResponseCannotCauseResubmissionEvenOnExplicitRetry()throws Exception {
        RideStore r=ride();http.reply("POST","/api/v3/uploads",200,null);fails(()->api.upload(r));
        assertEquals("uncertain",r.status().getString("status"));fails(()->client().upload(reopen(r)));fails(()->client().upload(reopen(r)));assertEquals(1,posts());
    }
    @Test public void invalidSubmissionIdentifiersAndHttpFailuresRemainUncertain()throws Exception {
        int index=0;
        for(String body:Arrays.asList("not-json","{}","{\"id\":null}","{\"id\":0}","{\"id\":-1}")){
            RideStore r=ride();r.status(r.status().put("status","ready"));http.reply("POST","/api/v3/uploads",201,body);
            fails(()->api.upload(r));assertEquals("uncertain",r.status().getString("status"));
            fails(()->client().upload(reopen(r)));fails(()->client().upload(reopen(r)));assertEquals(++index,posts());
        }
        for(int code:new int[]{302,400,401,403,409,429,500,503}){
            RideStore r=ride();r.status(r.status().put("status","ready"));http.reply("POST","/api/v3/uploads",code,"{}");
            Exception e=fails(()->api.upload(r));assertTrue(e.getMessage().contains("HTTP "+code));
            assertEquals("uncertain",r.status().getString("status"));fails(()->client().upload(reopen(r)));assertEquals(++index,posts());
        }
    }
    @Test public void interruptedUploadingStateCannotResubmit()throws Exception {
        RideStore r=ride();r.status(r.status().put("status","uploading"));
        fails(()->client().upload(reopen(r)));fails(()->client().upload(reopen(r)));assertTrue(http.requests.isEmpty());
    }
    @Test public void knownIdsRecoverEvenWhenPreviousStateWasUncertain()throws Exception {
        RideStore r=ride();knownUpload(r,"uncertain");processed();elevation(20);client().upload(reopen(r));
        elevation(20);client().upload(reopen(r));assertEquals(0,posts());assertEquals("verified",r.status().getString("status"));
    }
    @Test public void incompleteRidesAndInvalidTotalsNeverReachHttp()throws Exception {
        RideStore missing=new RideStore(root.toFile(),Long.toString(CoreTest.T+1));fails(()->api.upload(missing));
        RideStore r=ride();Files.delete(new File(r.dir,"activity.fit").toPath());fails(()->api.upload(r));assertTrue(http.requests.isEmpty());
        // finish refuses to replace an existing completed ride; restore this synthetic fixture only
        Files.write(new File(r.dir,"activity.fit").toPath(),Core.encode(CoreTest.example(),3289,CoreTest.T,CoreTest.T+43,43000).fit);
        for(Object invalid:new Object[]{JSONObject.NULL,-1,"NaN"}){
            r.status(r.status().put("expected_ascent",invalid));RideStore current=r;fails(()->api.upload(current));
        }
        assertTrue(http.requests.isEmpty());
    }
    @Test public void statusMustBeDurableBeforeSendingFitBytes()throws Exception {
        RideStore r=ride();Files.createDirectory(new File(r.dir,"ready.json.tmp").toPath());fails(()->api.upload(r));assertTrue(http.requests.isEmpty());
    }
    @Test public void failureSavingAcceptedUploadIdCannotCauseSecondSubmission()throws Exception {
        RideStore r=ride();Path blocked=new File(r.dir,"ready.json.tmp").toPath();
        http.reply("POST","/api/v3/uploads",201,"{\"id\":"+UPLOAD+"}").beforeReply=()->{
            try{Files.createDirectory(blocked);}catch(IOException e){throw new UncheckedIOException(e);}
        };
        fails(()->api.upload(r));assertEquals("uploading",r.status().getString("status"));Files.delete(blocked);
        fails(()->client().upload(reopen(r)));assertEquals(1,posts());
    }
    @Test public void uncertainUploadPreservesAllRecordingData()throws Exception {
        RideStore r=ride();Map<String,byte[]> before=new HashMap<>();
        for(String name:new String[]{"samples.csv","activity.fit","meta.json"})before.put(name,Files.readAllBytes(new File(r.dir,name).toPath()));
        http.reply("POST","/api/v3/uploads",503,"{}");fails(()->api.upload(r));
        for(Map.Entry<String,byte[]> e:before.entrySet())assertArrayEquals(e.getValue(),Files.readAllBytes(new File(r.dir,e.getKey()).toPath()));
        assertTrue(r.ready());assertEquals(20,r.status().getInt("expected_ascent"));assertEquals(44,r.status().getInt("samples"));
    }
    @Test public void oversizedResponseIsRejectedWithoutLosingKnownId()throws Exception {
        RideStore r=ride();knownUpload(r,"processing");http.reply("GET","/api/v3/uploads/"+UPLOAD,200,"x".repeat(2000001));
        Exception e=fails(()->api.upload(r));assertTrue(e.getMessage().contains("large"));assertEquals(UPLOAD,r.status().getLong("upload_id"));
    }
    @Test public void concurrentClientsShareOneSubmission()throws Exception {
        RideStore r=ride();CountDownLatch accepted=new CountDownLatch(1),release=new CountDownLatch(1);
        Reply post=http.reply("POST","/api/v3/uploads",201,"{\"id\":"+UPLOAD+"}");
        post.beforeReply=()->{accepted.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Test release timed out");}catch(InterruptedException e){throw new AssertionError(e);}};
        processed();elevation(20);elevation(20);ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<?> one=workers.submit(()->{try{client().upload(reopen(r));}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(accepted.await(5,TimeUnit.SECONDS));CountDownLatch started=new CountDownLatch(1);
            Future<?> two=workers.submit(()->{started.countDown();try{client().upload(reopen(r));}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(started.await(5,TimeUnit.SECONDS));release.countDown();one.get(10,TimeUnit.SECONDS);two.get(10,TimeUnit.SECONDS);
            assertEquals(1,posts());assertEquals("verified",r.status().getString("status"));
        }finally{release.countDown();workers.shutdownNow();}
    }
}
