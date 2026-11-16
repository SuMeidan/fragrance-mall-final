package io.scent.advisor;

import com.sun.net.httpserver.*;
import io.scent.advisor.AdvisorModels.*;
import io.scent.common.BusinessException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real chunked upstream, with no model calls or database. */
class AdvisorStreamTest {
    final ObjectMapper json=new ObjectMapper();
    HttpServer server; ExecutorService executor;
    final AtomicInteger posts=new AtomicInteger(),subscriptions=new AtomicInteger(),archives=new AtomicInteger();
    final Map<String,CountDownLatch> accepted=new ConcurrentHashMap<>();
    final List<String> paths=new CopyOnWriteArrayList<>();
    volatile String mode="normal";
    volatile CountDownLatch hold;
    final String answer="**清爽香氛**\n\n- 29元\n\n[查看商品](/products/1022)";
    Map<String,Object> message(String id,String role,String value) {
        return Map.of("id",id,"type","message","role",role,"status","completed","content",List.of(Map.of("type","text","text",value)));
    }
    Map<String,Object> status(String state,String reason) {
        return Map.of("id","sevt_status","type","session_status","content",List.of(Map.of("type","data","data",Map.of("session_status",state,"stop_reason",Map.of("type",reason)))));
    }
    void frame(HttpExchange exchange,Object value) throws IOException {
        byte[] bytes=("event: message\r\ndata: "+(value instanceof String s?s:json.writeValueAsString(value))+"\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        // Deliberately split UTF-8, CRLF and JSON boundaries across writes.
        for(int offset=0;offset<bytes.length;offset+=7) exchange.getResponseBody().write(bytes,offset,Math.min(7,bytes.length-offset));
        exchange.getResponseBody().flush();
    }
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();paths.add(method+" "+exchange.getRequestURI());
            try {
                Object result=Map.of();
                if(path.equals("/sessions")) {
                    String id="sesn_stream_"+UUID.randomUUID();accepted.put(id,new CountDownLatch(1)); result=Map.of("id",id);
                } else {
                    String id=path.split("/")[2];
                    if(path.endsWith("/archive")) {archives.incrementAndGet();}
                    else if(path.endsWith("/stream")) {
                        subscriptions.incrementAndGet();
                        if(mode.equals("bad-type")) {exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("{}".getBytes());return;}
                        assertEquals("text/event-stream",exchange.getRequestHeaders().getFirst("Accept"));
                        exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
                        exchange.getResponseBody().write(": connected\n\n".getBytes());exchange.getResponseBody().flush();
                        assertTrue(accepted.get(id).await(3,TimeUnit.SECONDS));
                        // Stale idle/old text before the new turn boundary must not complete this request.
                        frame(exchange,message("out_old","assistant","上一轮错误答案"));frame(exchange,status("idle","end_turn"));
                        frame(exchange,message("sevt_input","user","当前问题"));frame(exchange,status("running",""));
                        frame(exchange,Map.of("type","event_start","event",Map.of("id","thought","type","reasoning")));
                        frame(exchange,Map.of("type","event_delta","event_id","thought","delta",Map.of("index",0,"content",Map.of("type","text","text","fixture-secret-not-production"))));
                        frame(exchange,Map.of("type","mcp_call","content",List.of(Map.of("type","data","data",Map.of("output","internal tool payload")))));
                        frame(exchange,Map.of("type","event_start","event",Map.of("id","out_new","type","message")));
                        frame(exchange,Map.of("type","event_delta","event_id","out_new","delta",Map.of("index",0,"content",Map.of("type","text","text","**清爽香氛**"))));
                        if(hold!=null) hold.await(4,TimeUnit.SECONDS);
                        switch(mode) {
                            case "eof" -> {return;}
                            case "error" -> {frame(exchange,Map.of("type","error","error",Map.of("message","fixture-secret-not-production")));return;}
                            case "approval" -> {frame(exchange,status("idle","requires_action"));return;}
                            case "malformed" -> {frame(exchange,"not-json");return;}
                            case "oversized" -> {frame(exchange,message("out_new","assistant","字".repeat(6001)));return;}
                            case "frame-limit" -> {frame(exchange,"x".repeat(262145));return;}
                            default -> { }
                        }
                        frame(exchange,Map.of("type","event_delta","event_id","out_new","delta",Map.of("index",0,"content",Map.of("type","text","text",answer.substring("**清爽香氛**".length())))));
                        frame(exchange,message("out_new","assistant",answer));frame(exchange,status("idle","end_turn"));return;
                    } else if(method.equals("POST")) {
                        posts.incrementAndGet();result=Map.of("data",List.of(message("sevt_input","user","当前问题")));
                        accepted.get(id).countDown();
                    } else {
                        result=Map.of("data",List.of(status("idle","end_turn"),message("out_new","assistant",answer),message("sevt_input","user","当前问题")));
                    }
                }
                byte[] bytes=json.writeValueAsBytes(result);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(InterruptedException ex) {Thread.currentThread().interrupt();}
            catch(IOException ignored) { /* Expected when the tested consumer clears or disconnects. */ }
            finally {exchange.close();}
        });server.start();
    }
    @AfterEach void stop() {if(hold!=null)hold.countDown();server.stop(0);executor.shutdownNow();}
    AdvisorService service() {return new AdvisorService(json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"agent_test","env_test",7,"mcp-test","fixture-secret-not-production",Duration.ofSeconds(3),Duration.ofMillis(5));}
    Input input() {return new Input(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,"清爽，不甜，50元以内");}
    @Test void firstTextArrivesBeforeCompletionAndFinalMessageDoesNotDuplicateDeltas() throws Exception {
        hold=new CountDownLatch(1);var first=new CountDownLatch(1);var updates=new CopyOnWriteArrayList<String>();var s=service();var request=input();
        var future=executor.submit(()->s.stream(request,text->{updates.add(text);first.countDown();}));
        assertTrue(first.await(2,TimeUnit.SECONDS));assertFalse(future.isDone());assertEquals("**清爽香氛**",updates.getFirst());
        hold.countDown();assertEquals(answer,future.get(2,TimeUnit.SECONDS).message());assertEquals(answer,updates.getLast());
        assertTrue(updates.stream().noneMatch(t->t.contains("fixture-secret")||t.contains("internal tool")||t.contains("上一轮")));
        assertEquals(answer,s.stream(request,updates::add).message());assertEquals(1,posts.get());assertEquals(1,subscriptions.get());
        assertTrue(paths.stream().anyMatch(p->p.contains("event_deltas%5B%5D=message")));
    }
    @Test void consumerDisconnectCanResumeAcceptedRequestWithoutResubmission() {
        var s=service();var request=input();
        assertThrows(UncheckedIOException.class,()->s.stream(request,text->{throw new UncheckedIOException(new IOException("client gone"));}));
        assertEquals(answer,s.stream(request,text->fail("Resume must read the original completed result")).message());
        assertEquals(1,posts.get());assertEquals(1,subscriptions.get());
    }
    @Test void eofWithoutEndTurnIsNotSuccessAndRetryDoesNotRepeatUserInput() {
        mode="eof";var s=service();var request=input();
        assertEquals("ADVISOR_UPSTREAM",assertThrows(BusinessException.class,()->s.stream(request,text->{})).code());
        assertEquals(answer,s.chat(request).message());assertEquals(1,posts.get());
    }
    @Test void clearCancelsBlockedSubscriptionAndArchivesOnlyItsOwnConversation() throws Exception {
        hold=new CountDownLatch(1);var first=new CountDownLatch(1);var s=service();var request=input();
        var future=executor.submit(()->s.stream(request,text->first.countDown()));assertTrue(first.await(2,TimeUnit.SECONDS));
        s.clear(request.conversationId());assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS));
        assertEquals(1,archives.get());hold.countDown();assertEquals(answer,s.stream(input(),text->{}).message());
        assertEquals("ADVISOR_EXPIRED",assertThrows(BusinessException.class,()->s.stream(new Input(request.conversationId(),UUID.randomUUID().toString(),2,"上一款"),text->{})).code());
    }
    @Test void providerErrorAndApprovalCannotBecomeSuccessfulRecommendations() {
        for(String kind:List.of("error","approval")) {
            mode=kind;var s=service();var request=input();
            var ex=assertThrows(BusinessException.class,()->s.stream(request,text->assertFalse(text.contains("fixture-secret"))));
            assertEquals(kind.equals("error")?"ADVISOR_RESET_REQUIRED":"ADVISOR_APPROVAL_REQUIRED",ex.code());
            assertEquals("ADVISOR_RESET_REQUIRED",assertThrows(BusinessException.class,()->s.chat(request)).code());
        }
        assertEquals(2,posts.get());
    }
    @Test void malformedAndOversizedTextNeverBecomeCompletedAnswers() {
        for(String kind:List.of("malformed","oversized")) {
            mode=kind;assertEquals("ADVISOR_BAD_RESPONSE",assertThrows(BusinessException.class,()->service().stream(input(),text->{})).code());
        }
    }
    @Test void invalidStreamContentTypeDoesNotPostAnyQuestion() {
        mode="bad-type";assertEquals("ADVISOR_UPSTREAM",assertThrows(BusinessException.class,()->service().stream(input(),text->{})).code());
        assertEquals(0,posts.get());assertEquals(1,subscriptions.get());
    }
    @Test void oversizedFrameIsBoundedAndDoesNotCauseAnAutomaticSecondPost() {
        mode="frame-limit";var s=service();var request=input();assertThrows(BusinessException.class,()->s.stream(request,text->{}));
        assertEquals(1,posts.get());
    }
}
