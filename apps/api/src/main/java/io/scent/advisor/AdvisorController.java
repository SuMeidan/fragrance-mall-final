package io.scent.advisor;

import io.scent.account.AttemptLimiter;
import io.scent.advisor.AdvisorModels.*;
import io.scent.common.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/advisor")
public class AdvisorController {
    private final AdvisorService service;
    private final AttemptLimiter limiter;
    private final ObjectMapper json;
    public AdvisorController(AdvisorService service, AttemptLimiter limiter, ObjectMapper json) { this.service = service; this.limiter = limiter; this.json = json; }

    @PostMapping(value = "/chat", consumes = "application/json")
    ApiResult<Reply> chat(HttpServletRequest request) throws IOException {
        return ApiResult.ok(service.chat(input(request)));
    }

    @PostMapping(value = "/chat/stream", consumes = "application/json", produces = "text/event-stream")
    void stream(HttpServletRequest request,HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        Input input=input(request);
        AdvisorService.validate(input);
        if (!service.configured()) { throw new BusinessException(503,"ADVISOR_UNAVAILABLE","香氛顾问暂未开放，请先浏览商品"); }
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Accel-Buffering","no");
        try {
            send(response,"status",new Reply("正在了解你的偏好…"));
            Reply result=service.stream(input,text->{
                try { send(response,"text",new Reply(text)); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            send(response,"done",result);
        } catch (BusinessException ex) {
            try { send(response,"error",Map.of("status",ex.status(),"errorCode",ex.code(),"message",ex.getMessage())); }
            catch (IOException ignored) { /* Client disconnected; the accepted request remains resumable. */ }
        } catch (UncheckedIOException ignored) { /* Service closes the upstream subscription and releases its slot. */ }
        catch (IOException ignored) { /* No JSON error can be written after an SSE client disconnects. */ }
    }
    private void send(HttpServletResponse response,String type,Object value) throws IOException {
        byte[] bytes=("event: "+type+"\ndata: "+json.writeValueAsString(value)+"\n\n").getBytes(StandardCharsets.UTF_8);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }
    private Input input(HttpServletRequest request) throws IOException {
        limiter.check("advisor:" + request.getRemoteAddr(), 10, 60);
        // Includes chunked input: never bind an unbounded JSON body into memory.
        byte[] bytes = request.getInputStream().readNBytes(32769);
        if (bytes.length > 32768) { throw new BusinessException(413, "REQUEST_TOO_LARGE", "咨询内容过长，请清空部分对话再试"); }
        try { return json.readValue(bytes, Input.class); }
        catch (tools.jackson.core.JacksonException ex) { throw BusinessException.invalid("请检查咨询内容和对话格式"); }
    }

    @DeleteMapping("/conversations/{conversationId}")
    ApiResult<Void> clear(@PathVariable String conversationId, HttpServletRequest request) {
        limiter.check("advisor-clear:" + request.getRemoteAddr(), 20, 60);
        service.clear(conversationId);
        return ApiResult.ok(null);
    }
}
