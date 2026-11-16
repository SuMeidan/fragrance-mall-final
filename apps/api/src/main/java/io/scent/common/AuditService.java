package io.scent.common;

import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditMapper mapper;
    public AuditService(AuditMapper mapper) { this.mapper = mapper; }

    public void write(Long actor, String action, String type, Long resource, String summary) {
        mapper.insert(actor, action, type, resource, Objects.toString(MDC.get("traceId"), "background"), summary);
    }

    public Page<AuditMapper.Entry> list(int page, int size) {
        return new Page<>(mapper.list(Page.offset(page, size), size), mapper.count(), page, size);
    }
}
