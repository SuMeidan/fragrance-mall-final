package io.scent.advisor;

import com.sun.net.httpserver.HttpServer;
import io.scent.advisor.AdvisorModels.*;
import io.scent.common.BusinessException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class AdvisorServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger creations=new AtomicInteger(),posts=new AtomicInteger(),reads=new AtomicInteger(),archives=new AtomicInteger();
    private final Map<String,State> sessions=new ConcurrentHashMap<>();
    private final List<String> paths=new CopyOnWriteArrayList<>(),inputs=new CopyOnWriteArrayList<>();
    private final AtomicReference<String> createBody=new AtomicReference<>(),authorization=new AtomicReference<>();
    private volatile boolean complete=true,pagination;
    private volatile int createStatus=200,postStatus=200;
    private volatile String rawEvents,rawAck,stopReason="end_turn",answer="查询到本店商品，价格49元。";
    private volatile CountDownLatch entered,release;
    private static final class State {
        final List<Map<String,Object>> events=new ArrayList<>();
        int round;
        boolean finished;
    }
    private Map<String,Object> message(String id,String role,String text) {
        return Map.of("id",id,"type","message","role",role,"status","completed","content",List.of(Map.of("type","text","text",text)));
    }
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            paths.add(method+" "+exchange.getRequestURI());authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            int status=200; Object response=Map.of();
            try {
                if (path.equals("/sessions")) {
                    createBody.set(body);status=createStatus;
                    String id="sesn_fixture_"+creations.incrementAndGet();sessions.put(id,new State());
                    response=Map.of("id",id,"status","idle");
                } else {
                    String id=path.split("/")[2];State state=sessions.get(id);
                    if (path.endsWith("/archive")) { archives.incrementAndGet();response=Map.of("status","terminated"); }
                    else if (method.equals("POST")) {
                        posts.incrementAndGet();inputs.add(body);status=postStatus;
                        synchronized (state) {
                            state.round++;state.finished=false;
                            var user=message("sevt_"+state.round,"user",json.readTree(body).path("input").get(0).path("content").get(0).path("text").asString());
                            state.events.add(0,user);response=Map.of("data",List.of(user));
                        }
                        if (rawAck!=null) { response=rawAck; }
                    } else {
                        reads.incrementAndGet();
                        if (entered!=null) { entered.countDown(); }
                        if (release!=null) { release.await(3,TimeUnit.SECONDS); }
                        synchronized (state) {
                            if (complete && !state.finished) {
                                state.events.add(0,message("out_"+state.round,"assistant",answer));
                                state.events.add(0,Map.of("id","out_status_"+state.round,"type","session_status","status","completed",
                                    "content",List.of(Map.of("type","data","data",Map.of("session_status","idle","stop_reason",Map.of("type",stopReason))))));
                                state.finished=true;
                            }
                            List<Map<String,Object>> events=new ArrayList<>(state.events);
                            if (pagination && events.size()>=3) {
                                if (exchange.getRequestURI().getRawQuery().contains("page=")) { response=Map.of("data",events.subList(2,events.size())); }
                                else { response=Map.of("data",events.subList(0,2),"next_page","cursor/with+symbols?"); }
                            } else { response=Map.of("data",events); }
                        }
                        if (rawEvents!=null) { response=rawEvents; }
                    }
                }
                byte[] bytes=(response instanceof String value ? value : json.writeValueAsString(response)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/sessions");
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });server.start();
    }
    @AfterEach void cleanup() { if(release!=null)release.countDown();server.stop(0);executor.shutdownNow(); }
    private AdvisorService service(Duration timeout) {
        return new AdvisorService(json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"agent_fixture","env_fixture",3,"mcp-fixture",
            "fixture-secret-not-production",timeout,Duration.ofMillis(5));
    }
    private AdvisorService service() { return service(Duration.ofSeconds(2)); }
    private Input input() { return new Input(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,"100元以内，不喜欢甜香"); }
    private Input next(Input previous,String text) { return new Input(previous.conversationId(),UUID.randomUUID().toString(),previous.turn()+1,text); }
    private static BusinessException error(int status,String code,Runnable operation) {
        BusinessException ex=assertThrows(BusinessException.class,operation::run);assertEquals(status,ex.status());assertEquals(code,ex.code());return ex;
    }

    @Test void sendsPinnedAgentWithOnlyReadOnlyMcpAndReturnsPlainText() {
        var s=service();Input request=input();
        assertEquals(answer,s.chat(request).message());
        var created=json.readTree(createBody.get());
        var agent=created.path("agent");
        assertEquals("agent_with_overrides",agent.path("type").asString());assertEquals("agent_fixture",agent.path("id").asString());
        assertEquals(3,agent.path("version").asInt());assertEquals("env_fixture",created.path("environment_id").asString());
        assertEquals(1,agent.path("tools").size());var toolkit=agent.path("tools").get(0);
        assertEquals("mcp_toolkit",toolkit.path("type").asString());assertEquals("mcp-fixture",toolkit.path("mcp_server_name").asString());
        assertFalse(toolkit.path("default_config").path("enabled").asBoolean());
        assertEquals("always_allow",toolkit.path("default_config").path("permission_policy").path("type").asString());
        var names=new ArrayList<String>();
        for(var tool:toolkit.path("configs"))names.add(tool.path("name").asString());
        assertEquals(List.of("search_products","get_product_detail"),names);
        assertFalse(createBody.get().contains("builtin_toolkit"));assertFalse(createBody.get().contains("fixture-secret"));
        assertEquals("Bearer fixture-secret-not-production",authorization.get());
        assertEquals(request.message(),json.readTree(inputs.getFirst()).path("input").get(0).path("content").get(0).path("text").asString());
        assertEquals(1,creations.get());assertEquals(1,posts.get());
    }
    @Test void reusesCloudSessionAcrossBudgetReferenceAndPreferenceChanges() {
        var s=service();Input first=input(),second=next(first,"预算改成50元"),third=next(second,"上一款怎么用"),fourth=next(third,"改成偏甜果香");
        for(Input request:List.of(first,second,third,fourth))assertEquals(answer,s.chat(request).message());
        assertEquals(1,creations.get());assertEquals(4,posts.get());
        assertTrue(paths.stream().filter(p->p.startsWith("POST /sessions/")&&p.contains("/events")).allMatch(p->p.startsWith("POST /sessions/sesn_fixture_1/")));
        assertTrue(inputs.getLast().contains("偏甜果香"));assertFalse(inputs.getLast().contains("system"));
    }
    @Test void twoConversationsNeverShareCloudSession() {
        var s=service();s.chat(input());s.chat(input());
        assertEquals(2,creations.get());assertTrue(paths.contains("POST /sessions/sesn_fixture_1/events"));
        assertTrue(paths.contains("POST /sessions/sesn_fixture_2/events"));
    }
    @Test void duplicateRequestReturnsCachedAnswerAndRejectsChangedPayload() {
        var s=service();Input request=input();
        assertEquals(s.chat(request),s.chat(request));assertEquals(1,posts.get());assertEquals(1,reads.get());
        error(400,"VALIDATION",()->s.chat(new Input(request.conversationId(),request.requestId(),1,"另一个问题")));
    }
    @Test void timedOutPollingResumesSameAcceptedQuestionWithoutPostingAgain() {
        complete=false;var s=service(Duration.ofSeconds(2));Input request=input();
        error(504,"ADVISOR_TIMEOUT",()->s.chat(request));assertEquals(1,posts.get());
        complete=true;assertEquals(answer,s.chat(request).message());assertEquals(1,posts.get());assertEquals(1,creations.get());
    }
    @Test void oldCompletedTurnCannotBeMistakenForCurrentAnswer() {
        var s=service(Duration.ofSeconds(1));Input first=input();s.chat(first);
        complete=false;Input second=next(first,"预算改变后重新查询");
        error(504,"ADVISOR_TIMEOUT",()->s.chat(second));assertEquals(2,posts.get());
        complete=true;answer="新预算的新回答";assertEquals(answer,s.chat(second).message());assertEquals(2,posts.get());
    }
    @Test void uncertainPostIsNeverResentAndRequiresNewConversation() {
        rawAck="not-json";var s=service();Input request=input();
        error(409,"ADVISOR_RESET_REQUIRED",()->s.chat(request));
        rawAck=null;error(409,"ADVISOR_RESET_REQUIRED",()->s.chat(request));assertEquals(1,posts.get());
    }
    @Test void paginationUsesEncodedOpaqueCursorAndFindsOwnTurnBoundary() {
        pagination=true;var s=service();s.chat(input());
        assertTrue(paths.stream().anyMatch(p->p.contains("page=cursor%2Fwith%2Bsymbols%3F")));assertEquals(2,reads.get());
    }
    @Test void approvalAndRuntimeFailureAreNotReturnedAsSuccessfulAnswers() {
        var s=service();stopReason="requires_action";
        error(503,"ADVISOR_APPROVAL_REQUIRED",()->s.chat(input()));
        stopReason="retries_exhausted";error(409,"ADVISOR_RESET_REQUIRED",()->s.chat(input()));
        stopReason="end_turn";answer="字".repeat(6001);error(502,"ADVISOR_BAD_RESPONSE",()->s.chat(input()));
    }
    @Test void malformedAndOversizedProviderBodiesAreBounded() {
        var s=service();
        for(String raw:List.of("not-json","[]","{\"data\":null}","x".repeat(262145))) {
            rawEvents=raw;error(502,"ADVISOR_BAD_RESPONSE",()->s.chat(input()));
        }
    }
    @Test void transportDoesNotRedirectRetryOrExposeProviderErrors() {
        var s=service();createStatus=302;
        BusinessException ex=error(503,"ADVISOR_UPSTREAM",()->s.chat(input()));assertFalse(ex.getMessage().contains("fixture-secret"));
        assertEquals(1,creations.get());assertEquals(0,posts.get());
        createStatus=401;error(503,"ADVISOR_UPSTREAM",()->s.chat(input()));assertEquals(2,creations.get());
    }
    @Test void clearArchivesAndLaterTurnsCannotRecreateMissingHistory() {
        var s=service();Input first=input();s.chat(first);s.clear(first.conversationId());
        assertEquals(1,archives.get());error(409,"ADVISOR_EXPIRED",()->s.chat(next(first,"上一款")));
        s.chat(input());assertEquals(2,creations.get());
    }
    @Test void sameConversationCannotRunTwoQuestionsAtOnce() throws Exception {
        entered=new CountDownLatch(1);release=new CountDownLatch(1);var s=service();Input request=input();
        var caller=Executors.newSingleThreadExecutor();
        try {
            var future=caller.submit(()->s.chat(request));assertTrue(entered.await(1,TimeUnit.SECONDS));
            error(409,"ADVISOR_BUSY",()->s.chat(request));assertEquals(1,posts.get());
            release.countDown();assertEquals(answer,future.get(2,TimeUnit.SECONDS).message());
        } finally { release.countDown();caller.shutdownNow(); }
    }
    @Test void onlyTwoCloudRoundsMayBeInFlight() throws Exception {
        entered=new CountDownLatch(2);release=new CountDownLatch(1);var s=service();
        var callers=Executors.newFixedThreadPool(2);
        try {
            var first=callers.submit(()->s.chat(input()));var second=callers.submit(()->s.chat(input()));
            assertTrue(entered.await(1,TimeUnit.SECONDS));error(429,"ADVISOR_BUSY",()->s.chat(input()));assertEquals(2,posts.get());
            release.countDown();first.get(2,TimeUnit.SECONDS);second.get(2,TimeUnit.SECONDS);
        } finally {release.countDown();callers.shutdownNow();}
    }
    @Test void invalidIdentifiersSequenceAndTextNeverReachCloud() {
        var s=service();Input request=input();
        for(Input invalid:Arrays.asList(null,new Input(null,request.requestId(),1,"x"),new Input("sesn_foreign",request.requestId(),1,"x"),
                new Input(request.conversationId(),request.requestId(),0,"x"),new Input(request.conversationId(),request.requestId(),31,"x"),
                new Input(request.conversationId(),request.requestId(),1,"x".repeat(601)),new Input(request.conversationId(),request.requestId(),1,"bad\u0000text"))) {
            error(400,"VALIDATION",()->s.chat(invalid));
        }
        error(409,"ADVISOR_EXPIRED",()->s.chat(next(request,"lost history")));
        assertDoesNotThrow(()->AdvisorService.validate(new Input(request.conversationId(),request.requestId(),1,"🌿".repeat(600))));
        assertEquals(0,creations.get());
    }
    @Test void missingPartialAndUnsafeConfigurationFailClosed(@TempDir Path directory) throws Exception {
        var env=new MockEnvironment();var unconfigured=new AdvisorService(json,env);assertFalse(unconfigured.configured());
        error(503,"ADVISOR_UNAVAILABLE",()->unconfigured.chat(input()));
        env.setProperty("scent.bailian.base-url","http://127.0.0.1/api/v1/agentstudio");
        assertThrows(IllegalStateException.class,()->new AdvisorService(json,env));
        Path key=directory.resolve("fixture.key");Files.writeString(key,"fixture-secret-not-production");
        env.setProperty("scent.bailian.base-url","https://ws-fixture.cn-beijing.maas.aliyuncs.com/api/v1/agentstudio");
        env.setProperty("scent.bailian.agent-id","agent_fixture");env.setProperty("scent.bailian.environment-id","env_fixture");
        env.setProperty("scent.bailian.agent-version","3");env.setProperty("scent.bailian.mcp-server-id","mcp-fixture");
        env.setProperty("scent.bailian.key-file",key.toString());assertTrue(new AdvisorService(json,env).configured());
        for(String base:List.of("https://ws-fixture.cn-beijing.maas.aliyuncs.com.evil.invalid/api/v1/agentstudio","https://example.com/api/v1/agentstudio",
                "https://ws-fixture.cn-beijing.maas.aliyuncs.com/api/v1/agentstudio?key=x")) {
            env.setProperty("scent.bailian.base-url",base);assertThrows(IllegalStateException.class,()->new AdvisorService(json,env));
        }
        assertEquals(0,creations.get());
    }
}
