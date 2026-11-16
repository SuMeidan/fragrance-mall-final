package io.scent.advisor;

import io.scent.account.AttemptLimiter;
import io.scent.advisor.AdvisorModels.*;
import io.scent.common.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real MVC negotiation with SSE Accept, without a database or any model calls. */
class AdvisorStreamBoundaryTest {
    final ObjectMapper json=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    String input() {return json.writeValueAsString(new Input(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,"清爽不甜"));}
    MockMvc mvc(AdvisorService service) {
        return MockMvcBuilders.standaloneSetup(new AdvisorController(service,new AttemptLimiter(),json)).setControllerAdvice(new ApiErrors()).build();
    }
    MockMvc unavailable() {return mvc(new AdvisorService(json,new MockEnvironment()));}
    @Test void unavailableBeforeStreamIsJson503DespiteSseOnlyAccept() throws Exception {
        var response=unavailable().perform(post("/api/v1/advisor/chat/stream").contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM).content(input())).andReturn().getResponse();
        assertEquals(503,response.getStatus());assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("ADVISOR_UNAVAILABLE",json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("errorCode").asString());
        assertFalse(response.getContentAsString().contains("event: done"));
    }
    @Test void malformedUnknownAndOversizedRequestsRemainJsonErrors() throws Exception {
        for(String body:List.of("{",input().replace("\"turn\":1","\"turn\":1,\"userId\":123"),"x".repeat(32769))) {
            var response=unavailable().perform(post("/api/v1/advisor/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content(body)).andReturn().getResponse();
            assertEquals(body.length()>32768?413:400,response.getStatus());
            assertTrue(response.getContentType().startsWith("application/json"));
            assertFalse(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("errorCode").asString().isBlank());
        }
    }
    @Test void preStreamRateLimitRetainsJson429() throws Exception {
        var mvc=unavailable();
        for(int index=0;index<11;index++) {
            var response=mvc.perform(post("/api/v1/advisor/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content(input())).andReturn().getResponse();
            assertEquals(index<10?503:429,response.getStatus());
            assertTrue(response.getContentType().startsWith("application/json"));
        }
    }
    @Test void failureAfterFirstTextRemainsSseErrorWithoutDoneOrJsonFallback() throws Exception {
        var service=mock(AdvisorService.class);when(service.configured()).thenReturn(true);
        when(service.stream(any(Input.class),any())).thenAnswer(call->{
            Consumer<String> update=call.getArgument(1);update.accept("**首段文字**");
            throw new BusinessException(503,"ADVISOR_UPSTREAM","回复暂时中断");
        });
        var response=mvc(service).perform(post("/api/v1/advisor/chat/stream").contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM).content(input())).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertTrue(response.getContentType().startsWith("text/event-stream"));
        String body=response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("event: text\n"));assertTrue(body.contains("event: error\n"));assertTrue(body.contains("ADVISOR_UPSTREAM"));
        assertFalse(body.contains("event: done"));assertFalse(body.contains("resultCode"));
    }
}
