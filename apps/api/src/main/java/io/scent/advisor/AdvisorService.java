package io.scent.advisor;

import io.scent.advisor.AdvisorModels.*;
import io.scent.common.BusinessException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One cloud session per opaque browser conversation; no database or transaction access. */
@Service
public class AdvisorService {
    private static final Logger LOG = LoggerFactory.getLogger(AdvisorService.class);
    private static final long IDLE_NANOS = Duration.ofMinutes(30).toNanos();
    private static final String EVENTS = "/events?order=desc&limit=100&types=message&types=session_status&types=error&types=tool_approval_request";
    private final ObjectMapper json;
    private final Settings settings;
    private final OkHttpClient http;
    private final Duration timeout, pause;
    private final Semaphore slots = new Semaphore(2);
    private final ConcurrentHashMap<String, Conversation> conversations = new ConcurrentHashMap<>();
    private record Settings(URI base, String agent, String environment, Integer version, String mcp, String key) { }
    private static final class Conversation {
        final ReentrantLock lock = new ReentrantLock();
        volatile long touched = System.nanoTime();
        volatile boolean closed;
        volatile Call streamCall;
        String session, request, message, event;
        int turn, completed;
        boolean submitted, reset;
        Reply answer;
    }

    @Autowired
    public AdvisorService(ObjectMapper json, Environment environment) {
        this(json, production(environment), Duration.ofSeconds(60), Duration.ofMillis(600));
    }
    // Package-only local HTTP fixture. Production only accepts the Bailian HTTPS host pattern.
    AdvisorService(ObjectMapper json, URI base, String agent, String environment, Integer version,
            String mcp, String key, Duration timeout, Duration pause) {
        this(json, new Settings(base, agent, environment, version, mcp, key), timeout, pause);
    }
    private AdvisorService(ObjectMapper json, Settings settings, Duration timeout, Duration pause) {
        this.json=json; this.settings=settings; this.timeout=timeout; this.pause=pause;
        http=new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(12))
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }
    public boolean configured() { return settings.base()!=null; }

    public Reply chat(Input input) {
        return chat(input,null);
    }
    /** A disconnected consumer may resume the accepted request; it must never send it again. */
    public Reply stream(Input input,Consumer<String> update) {
        return chat(input,Objects.requireNonNull(update));
    }
    private Reply chat(Input input,Consumer<String> update) {
        validate(input);
        if (!configured()) { throw new BusinessException(503,"ADVISOR_UNAVAILABLE","香氛顾问暂未开放，请先浏览商品"); }
        if (!slots.tryAcquire()) { throw new BusinessException(429,"ADVISOR_BUSY","顾问正在回复其他咨询，请稍后重试"); }
        long started=System.nanoTime(), sessionMs=0, subscribeMs=0, submitMs=0;
        long[] first={-1,-1}; String outcome="failed";
        Consumer<String> timedUpdate=update==null ? null : text->{
            if (!text.isEmpty() && first[0]<0) { first[0]=(System.nanoTime()-started)/1000000; }
            if (first[1]<0 && text.codePoints().anyMatch(Character::isLetterOrDigit)) {
                first[1]=(System.nanoTime()-started)/1000000;
            }
            update.accept(text);
        };
        Conversation c=null; boolean locked=false; Response stream=null;
        try {
            c=conversation(input);
            locked=c.lock.tryLock();
            if (!locked) { throw new BusinessException(409,"ADVISOR_BUSY","这次咨询仍在处理中，请稍后重试"); }
            if (c.closed) { throw expired(); }
            if (c.reset) { throw reset(); }
            c.touched=System.nanoTime();
            if (input.requestId().equals(c.request)) {
                if (input.turn()!=c.turn || !input.message().equals(c.message)) { throw BusinessException.invalid("重试必须保持原来的问题与请求标识"); }
                if (c.answer!=null) { outcome="cached"; return c.answer; }
            } else {
                if (c.request!=null && c.answer==null) { throw new BusinessException(409,"ADVISOR_BUSY","上一条问题尚未结束，请重试原问题或清空咨询"); }
                if (input.turn()!=c.completed+1) { throw BusinessException.invalid("咨询顺序不一致，请清空后重新开始"); }
                c.request=input.requestId(); c.message=input.message(); c.turn=input.turn();
                c.event=null; c.answer=null; c.submitted=false;
            }
            long deadline=System.nanoTime()+timeout.toNanos();
            if (c.session==null) {
                long phase=System.nanoTime();
                var agent=new LinkedHashMap<String,Object>();
                agent.put("type","agent_with_overrides"); agent.put("id",settings.agent());
                if (settings.version()!=null) { agent.put("version",settings.version()); }
                // Replace the complete toolkit list, preserving only the two read-only product tools.
                agent.put("tools",List.of(Map.of("type","mcp_toolkit","mcp_server_name",settings.mcp(),
                    "default_config",Map.of("enabled",false,"permission_policy",Map.of("type","always_allow")),
                    "configs",List.of(Map.of("name","search_products","enabled",true),Map.of("name","get_product_detail","enabled",true)))));
                var created=exchange("POST","/sessions",Map.of("agent",agent,"environment_id",settings.environment(),"title","SCENT 香氛咨询"),remaining(deadline));
                String id=created.path("id").asString();
                if (!id.matches("sesn_[A-Za-z0-9_-]{1,120}")) { throw badResponse(); }
                c.session=id;
                sessionMs=(System.nanoTime()-phase)/1000000;
            }
            if (c.closed) { throw expired(); }
            if (c.event==null) {
                if (c.submitted) { throw reset(); }
                // Subscribe before posting so the first generated fragments cannot be missed.
                if (update!=null) {
                    long phase=System.nanoTime();
                    stream=subscribe(c,deadline);
                    subscribeMs=(System.nanoTime()-phase)/1000000;
                }
                if (c.closed) { throw expired(); }
                c.submitted=true; // An uncertain POST must never resend a possibly accepted question.
                long phase=System.nanoTime();
                var accepted=exchange("POST","/sessions/"+c.session+"/events",
                    Map.of("input",List.of(Map.of("role","user","type","message",
                        "content",List.of(Map.of("type","text","text",c.message))))),remaining(deadline));
                JsonNode data=accepted.path("data");
                if (!data.isArray() || data.size()!=1 || !"user".equals(data.get(0).path("role").asString())
                        || !"message".equals(data.get(0).path("type").asString())) { throw badResponse(); }
                String event=data.get(0).path("id").asString();
                if (!event.matches("(msg|sevt)_[A-Za-z0-9_-]{1,120}")) { throw badResponse(); }
                c.event=event;
                submitMs=(System.nanoTime()-phase)/1000000;
            }
            Conversation active=c;
            c.answer=stream==null ? awaitAnswer(c,deadline)
                : AdvisorEventStream.read(stream.body().source(),json,c.event,()->active.closed,timedUpdate);
            c.completed=c.turn;
            outcome="completed";
            return c.answer;
        } catch (BusinessException ex) {
            if (c!=null && locked && (c.submitted && c.event==null
                    || Set.of("ADVISOR_BAD_RESPONSE","ADVISOR_RESET_REQUIRED","ADVISOR_APPROVAL_REQUIRED").contains(ex.code()))) {
                c.reset=true;
                if (c.submitted && c.event==null) { throw reset(); }
            }
            throw ex;
        } catch (InterruptedIOException ex) { throw timedOut(); }
        catch (IOException ex) { throw new BusinessException(503,"ADVISOR_UPSTREAM","顾问连接中断，请继续等待原问题的结果"); }
        finally {
            if (stream!=null) { stream.close(); }
            if (c!=null && locked) {
                if (c.streamCall!=null) { c.streamCall.cancel(); c.streamCall=null; }
                c.touched=System.nanoTime();
                if (c.closed) { archive(c); }
                c.lock.unlock();
            }
            slots.release();
            // Scalar timings only: no question, response, credential, cloud ID or tool arguments.
            LOG.info("ADVISOR_TIMING mode={} outcome={} sessionMs={} subscribeMs={} submitMs={} firstTextMs={} firstReadableMs={} totalMs={}",
                update==null?"json":"stream", outcome, sessionMs, subscribeMs, submitMs,
                first[0], first[1], (System.nanoTime()-started)/1000000);
        }
    }

    private Response subscribe(Conversation c,long deadline) throws IOException {
        var request=new Request.Builder().url(settings.base()+"/sessions/"+c.session+"/events/stream?event_deltas%5B%5D=message")
            .header("Authorization","Bearer "+settings.key()).header("Accept","text/event-stream").build();
        Call call=http.newBuilder().readTimeout(Duration.ZERO).build().newCall(request);
        c.streamCall=call;
        call.timeout().timeout(Math.max(1,remaining(deadline).toMillis()),TimeUnit.MILLISECONDS);
        Response response=call.execute();
        if (!response.isSuccessful() || response.body()==null
                || !response.header("Content-Type","").toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
            response.close(); throw new BusinessException(503,"ADVISOR_UPSTREAM","暂时无法建立顾问连接，请稍后重试");
        }
        return response;
    }

    private synchronized Conversation conversation(Input input) {
        Conversation c=conversations.get(input.conversationId());
        if (c!=null) {
            if (System.nanoTime()-c.touched>IDLE_NANOS) { throw expired(); }
            return c;
        }
        if (input.turn()!=1) { throw expired(); }
        if (conversations.size()>=64) { throw new BusinessException(429,"ADVISOR_BUSY","当前咨询较多，请稍后再来"); }
        c=new Conversation(); conversations.put(input.conversationId(),c);
        return c;
    }

    private Reply awaitAnswer(Conversation c,long deadline) {
        while (true) {
            if (c.closed) { throw expired(); }
            remaining(deadline);
            String page=null,state=null,reason=null;
            boolean boundary=false,failed=false;
            var messages=new ArrayList<String>();
            for (int pages=0;pages<4;pages++) {
                String suffix=page==null ? "" : "&page="+URLEncoder.encode(page,StandardCharsets.UTF_8);
                var root=exchange("GET","/sessions/"+c.session+EVENTS+suffix,null,remaining(deadline));
                JsonNode data=root.path("data");
                if (!data.isArray()) { throw badResponse(); }
                for (JsonNode event:data) {
                    if (c.event.equals(event.path("id").asString())) { boundary=true; break; }
                    String type=event.path("type").asString();
                    if ("error".equals(type)) { failed=true; }
                    if ("session_status".equals(type) && state==null) {
                        for (JsonNode block:event.path("content")) {
                            if ("data".equals(block.path("type").asString())) {
                                state=block.path("data").path("session_status").asString();
                                reason=block.path("data").path("stop_reason").path("type").asString(); break;
                            }
                        }
                    }
                    if ("message".equals(type) && "assistant".equals(event.path("role").asString())
                            && "completed".equals(event.path("status").asString())) {
                        var text=new StringBuilder();
                        for (JsonNode block:event.path("content")) {
                            if ("text".equals(block.path("type").asString()) && block.path("text").isString()) { text.append(block.path("text").asString()); }
                        }
                        if (!text.isEmpty()) { messages.add(text.toString()); }
                    }
                }
                if (boundary) { break; }
                JsonNode next=root.path("next_page");
                if (!next.isString() || next.asString().isBlank()) { break; }
                if (next.asString().length()>2048 || next.asString().equals(page) || pages==3) { throw badResponse(); }
                page=next.asString();
            }
            if (boundary) {
                if (failed || "terminated".equals(state) || "retries_exhausted".equals(reason)) { throw reset(); }
                if ("idle".equals(state) && "requires_action".equals(reason)) {
                    throw new BusinessException(503,"ADVISOR_APPROVAL_REQUIRED","商品工具正在等待百炼审批，请联系管理员检查工具权限后新建咨询");
                }
                if ("idle".equals(state) && "end_turn".equals(reason) && !messages.isEmpty()) {
                    Collections.reverse(messages);
                    String text=String.join("\n\n",messages).strip();
                    return checkedReply(text);
                }
            }
            try { Thread.sleep(Math.min(pause.toMillis(),Math.max(1,remaining(deadline).toMillis()))); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw timedOut(); }
        }
    }

    public void clear(String id) {
        uuid(id);
        Conversation c=conversations.remove(id);
        if (c==null) { return; }
        c.closed=true;
        if (c.streamCall!=null) { c.streamCall.cancel(); }
        if (c.lock.tryLock()) { try { archive(c); } finally { c.lock.unlock(); } }
    }
    // Reuse the existing scheduler; provider cleanup holds it for at most four seconds.
    @Scheduled(fixedDelay=60000)
    void expire() {
        long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        for (var entry:conversations.entrySet()) {
            if (System.nanoTime()>deadline) { break; }
            var c=entry.getValue();
            if (System.nanoTime()-c.touched>IDLE_NANOS && c.lock.tryLock()) {
                try { if (conversations.remove(entry.getKey(),c)) { c.closed=true; archive(c); } }
                finally { c.lock.unlock(); }
            }
        }
    }
    private void archive(Conversation c) {
        if (c.session==null) { return; }
        try { exchange("POST","/sessions/"+c.session+"/archive",Map.of(),Duration.ofSeconds(2)); }
        catch (BusinessException ignored) { /* Best effort; never delete or retry user messages. */ }
    }

    private JsonNode exchange(String method,String path,Object payload,Duration limit) {
        try {
            var request=new Request.Builder().url(settings.base().toString()+path)
                .header("Authorization","Bearer "+settings.key()).header("Accept","application/json");
            if ("POST".equals(method)) { request.post(RequestBody.create(json.writeValueAsString(payload),MediaType.get("application/json; charset=utf-8"))); }
            Call call=http.newCall(request.build());
            call.timeout().timeout(Math.max(1,Math.min(12000,limit.toMillis())),TimeUnit.MILLISECONDS);
            try (Response response=call.execute()) {
                if (!response.isSuccessful()) { throw new BusinessException(503,"ADVISOR_UPSTREAM","顾问暂时无法连接，请稍后重试"); }
                if (response.body()==null) { throw badResponse(); }
                byte[] bytes=response.body().byteStream().readNBytes(262145);
                if (bytes.length>262144) { throw badResponse(); }
                if (bytes.length==0) { return json.createObjectNode(); }
                JsonNode node=json.readTree(bytes);
                if (!node.isObject()) { throw badResponse(); }
                return node;
            }
        } catch (BusinessException ex) { throw ex; }
        catch (InterruptedIOException ex) { throw timedOut(); }
        catch (IOException ex) { throw new BusinessException(503,"ADVISOR_UPSTREAM","顾问连接中断，请稍后重试"); }
        catch (tools.jackson.core.JacksonException ex) { throw badResponse(); }
    }
    static void validate(Input input) {
        if (input==null) { throw BusinessException.invalid("请提交咨询内容"); }
        uuid(input.conversationId()); uuid(input.requestId());
        if (input.turn()<1 || input.turn()>30 || input.message()==null || input.message().isBlank()
                || input.message().codePointCount(0,input.message().length())>600
                || input.message().codePoints().anyMatch(v->Character.isISOControl(v) && v!='\n' && v!='\t')) {
            throw BusinessException.invalid("每次提问最多600字，每段咨询最多30轮");
        }
    }
    private static void uuid(String value) {
        try { if (value==null || !UUID.fromString(value).toString().equals(value)) { throw new IllegalArgumentException(); } }
        catch (IllegalArgumentException ex) { throw BusinessException.invalid("咨询标识不正确"); }
    }
    private static Duration remaining(long deadline) {
        long left=deadline-System.nanoTime();
        if (left<=0) { throw timedOut(); }
        return Duration.ofNanos(left);
    }
    private static Settings production(Environment env) {
        String base=env.getProperty("scent.bailian.base-url","").strip(),agent=env.getProperty("scent.bailian.agent-id","").strip();
        String environment=env.getProperty("scent.bailian.environment-id","").strip(),version=env.getProperty("scent.bailian.agent-version","").strip();
        String mcp=env.getProperty("scent.bailian.mcp-server-id","").strip(),file=env.getProperty("scent.bailian.key-file","").strip();
        if (List.of(base,agent,environment,version,mcp,file).stream().allMatch(String::isBlank)) { return new Settings(null,"","",null,"",""); }
        try {
            URI uri=URI.create(base);
            if (!"https".equals(uri.getScheme()) || uri.getHost()==null || !uri.getHost().matches("[a-zA-Z0-9-]{1,100}\\.cn-beijing\\.maas\\.aliyuncs\\.com")
                    || uri.getPort()!=-1 && uri.getPort()!=443 || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !"/api/v1/agentstudio".equals(uri.getPath()) || !agent.matches("agent_[A-Za-z0-9_-]{1,120}")
                    || !environment.matches("env_[A-Za-z0-9_-]{1,120}") || !mcp.matches("mcp[-_][A-Za-z0-9_-]{1,120}")
                    || !version.matches("[1-9][0-9]{0,8}")) { throw new IllegalArgumentException(); }
            Path path=Path.of(file);
            if (!path.isAbsolute() || !Files.isRegularFile(path) || Files.size(path)>1024) { throw new IOException(); }
            String key=Files.readString(path,StandardCharsets.UTF_8).replace("\uFEFF","").strip();
            if (!key.matches("[A-Za-z0-9._-]{16,512}")) { throw new IOException(); }
            return new Settings(uri,agent,environment,Integer.valueOf(version),mcp,key);
        } catch (IllegalArgumentException | IOException ex) { throw new IllegalStateException("Invalid or incomplete Bailian advisor configuration; check private files without printing credentials."); }
    }
    private static BusinessException expired() { return new BusinessException(409,"ADVISOR_EXPIRED","本次咨询已结束或过期，请清空后新建咨询"); }
    static BusinessException reset() { return new BusinessException(409,"ADVISOR_RESET_REQUIRED","这次咨询未能确认处理结果，请清空后新建咨询，避免重复发送问题"); }
    private static BusinessException timedOut() { return new BusinessException(504,"ADVISOR_TIMEOUT","顾问仍在处理中，请重试等待结果；不会重复发送问题"); }
    static BusinessException badResponse() { return new BusinessException(502,"ADVISOR_BAD_RESPONSE","顾问返回内容异常，请新建咨询后重试"); }
    static Reply checkedReply(String text) {
        if (text.isBlank() || text.codePointCount(0,text.length())>6000
                || text.codePoints().anyMatch(v->Character.isISOControl(v) && v!='\n' && v!='\t' && v!='\r')) { throw badResponse(); }
        return new Reply(text.strip());
    }
}
