package io.scent.advisor;

import io.scent.advisor.AdvisorModels.Reply;
import io.scent.common.BusinessException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import okio.BufferedSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Decode provider events locally; only assistant text can cross the storefront boundary. */
final class AdvisorEventStream {
    private AdvisorEventStream() { }

    static Reply read(BufferedSource source,ObjectMapper json,String boundary,BooleanSupplier closed,
            Consumer<String> update) throws IOException {
        var messages=new LinkedHashMap<String,TreeMap<Integer,String>>();
        var data=new StringBuilder();
        long bytes=0,lastAt=0; int frames=0,updates=0; String sent=""; boolean started=false;
        while (!source.exhausted()) {
            if (closed.getAsBoolean()) { throw AdvisorService.reset(); }
            String line=source.readUtf8LineStrict(262144);
            bytes+=line.getBytes(StandardCharsets.UTF_8).length+1;
            if (bytes>2097152 || data.length()+line.length()>262144) { throw AdvisorService.badResponse(); }
            if (!line.isEmpty()) {
                if (line.startsWith("data:")) { if (!data.isEmpty()) data.append('\n'); data.append(line.substring(5).stripLeading()); }
                continue;
            }
            if (data.isEmpty()) { continue; }
            if (++frames>4096) { throw AdvisorService.badResponse(); }
            JsonNode event;
            try { event=json.readTree(data.toString()); }
            catch (tools.jackson.core.JacksonException ex) { throw AdvisorService.badResponse(); }
            data.setLength(0);
            if (!event.isObject()) { throw AdvisorService.badResponse(); }
            String type=event.path("type").asString(),id=event.path("id").asString();
            if (boundary.equals(id)) { started=true; continue; }
            if ("session_status".equals(type)) {
                for (JsonNode block:event.path("content")) {
                    if ("running".equals(block.path("data").path("session_status").asString())) { started=true; }
                }
            }
            if (!started) { continue; }
            boolean changed=false,complete=false;
            if ("error".equals(type)) { throw AdvisorService.reset(); }
            if ("event_start".equals(type)) {
                JsonNode start=event.path("event"); id=start.path("id").asString();
                if ("message".equals(start.path("type").asString()) && !boundary.equals(id)
                        && !"user".equals(start.path("role").asString())) {
                    if (id.isBlank() || id.length()>160 || messages.size()>=32 && !messages.containsKey(id)) { throw AdvisorService.badResponse(); }
                    messages.putIfAbsent(id,new TreeMap<>());
                }
            } else if ("event_delta".equals(type)) {
                var parts=messages.get(event.path("event_id").asString());
                JsonNode delta=event.path("delta"),content=delta.path("content");
                if (parts!=null && "text".equals(content.path("type").asString()) && content.path("text").isString()) {
                    int index=delta.path("index").asInt(-1);
                    if (index<0 || index>31) { throw AdvisorService.badResponse(); }
                    String text=parts.getOrDefault(index,"")+content.path("text").asString();
                    if (text.length()>12000) { throw AdvisorService.badResponse(); }
                    parts.put(index,text); changed=true;
                }
            } else if ("message".equals(type) && "assistant".equals(event.path("role").asString())
                    && "completed".equals(event.path("status").asString()) && !boundary.equals(id)) {
                if (id.isBlank() || id.length()>160 || messages.size()>=32 && !messages.containsKey(id)) { throw AdvisorService.badResponse(); }
                var parts=new TreeMap<Integer,String>(); int index=0;
                for (JsonNode block:event.path("content")) {
                    if ("text".equals(block.path("type").asString()) && block.path("text").isString()) {
                        if (index>=32) { throw AdvisorService.badResponse(); }
                        parts.put(index++,block.path("text").asString());
                    }
                }
                messages.put(id,parts); changed=true; complete=true;
            } else if ("session_status".equals(type)) {
                for (JsonNode block:event.path("content")) {
                    JsonNode status=block.path("data");
                    String state=status.path("session_status").asString(),reason=status.path("stop_reason").path("type").asString();
                    if ("terminated".equals(state) || "retries_exhausted".equals(reason)) { throw AdvisorService.reset(); }
                    if ("idle".equals(state) && "requires_action".equals(reason)) {
                        throw new BusinessException(503,"ADVISOR_APPROVAL_REQUIRED","商品工具等待审批，请联系管理员检查后新建咨询");
                    }
                    if ("idle".equals(state) && "end_turn".equals(reason)) { return AdvisorService.checkedReply(text(messages)); }
                }
            }
            if (changed) {
                String snapshot=text(messages);
                if (!snapshot.isBlank()) {
                    AdvisorService.checkedReply(snapshot);
                    long now=System.nanoTime();
                    // Send the first real text immediately; coalesce subsequent tiny fragments, never simulate typing.
                    if (!sent.equals(snapshot) && updates<256 && (sent.isEmpty() || complete
                            || now-lastAt>=80000000 || Math.abs(snapshot.length()-sent.length())>=64)) {
                        update.accept(snapshot); sent=snapshot; lastAt=now; updates++;
                    }
                }
            }
        }
        throw new BusinessException(503,"ADVISOR_UPSTREAM","回复尚未完成，请继续等待原问题的结果");
    }
    private static String text(LinkedHashMap<String,TreeMap<Integer,String>> messages) {
        var texts=new ArrayList<String>();
        for (var parts:messages.values()) { String value=String.join("",parts.values()); if (!value.isBlank()) texts.add(value); }
        return String.join("\n\n",texts);
    }
}
