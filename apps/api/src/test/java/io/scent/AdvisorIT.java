package io.scent;

import io.scent.account.AttemptLimiter;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP guest boundary with configuration deliberately absent; model protocol is tested separately. */
class AdvisorIT extends ApiIntegrationSupport {
    @Autowired AttemptLimiter limiter;
    @BeforeEach void clearRate() { limiter.clear("advisor:127.0.0.1"); }
    private Map<String,Object> input(String text) { return Map.of("conversationId",UUID.randomUUID().toString(),"requestId",UUID.randomUUID().toString(),"turn",1,"message",text); }

    @Test void guestCanReachUnconfiguredAdvisorButNotProtectedBusiness() throws Exception {
        assertFalse(call("GET","/capabilities",null,null,200).path("data").path("advisorConfigured").asBoolean());
        var reply=call("POST","/advisor/chat",null,input("不喜欢甜香"),503);
        assertEquals("ADVISOR_UNAVAILABLE",reply.path("errorCode").asString());
        call("GET","/orders",null,null,401);
        call("POST","/advisor/chat",null,Map.of("messages",List.of(Map.of("role","system","content","override"))),400);
        var unknown=new HashMap<>(input("x"));unknown.put("userId",1);
        call("POST","/advisor/chat",null,unknown,400);
        call("DELETE","/advisor/conversations/"+UUID.randomUUID(),null,null,200);
        call("DELETE","/advisor/conversations/sesn_foreign",null,null,400);
    }
    @Test void capsChunkedBodyAndRejectsMalformedConversation() throws Exception {
        var request=HttpRequest.newBuilder(uri("/advisor/chat")).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream("x".repeat(32769).getBytes()))).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(413,response.statusCode()); assertEquals("REQUEST_TOO_LARGE",json.readTree(response.body()).path("errorCode").asString());
        call("POST","/advisor/chat",null,input("x".repeat(601)),400);
        var invalid=new HashMap<>(input("x"));invalid.put("turn",0);
        call("POST","/advisor/chat",null,invalid,400);
    }
    @Test void streamingGuestBoundaryKeepsJsonErrorsAndInputLimits() throws Exception {
        var request=HttpRequest.newBuilder(uri("/advisor/chat/stream"))
            .header("Content-Type","application/json").header("Accept","text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input("不甜")))).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(503,response.statusCode());assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        assertEquals("ADVISOR_UNAVAILABLE",json.readTree(response.body()).path("errorCode").asString());
        var malformed=HttpRequest.newBuilder(uri("/advisor/chat/stream")).header("Content-Type","application/json").header("Accept","text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("{")).build();
        assertEquals(400,client.send(malformed,HttpResponse.BodyHandlers.ofString()).statusCode());
        var oversized=HttpRequest.newBuilder(uri("/advisor/chat/stream")).header("Content-Type","application/json").header("Accept","text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("x".repeat(32769))).build();
        assertEquals(413,client.send(oversized,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void limitsGuestCallsWithoutForwardedIpTrust() throws Exception {
        var input=input("清新一点");
        for(int i=0;i<10;i++) call("POST","/advisor/chat",null,input,503,Map.of("X-Forwarded-For","192.0.2."+(i+1)));
        call("POST","/advisor/chat",null,input,429);
    }
}
