package io.scent.order;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderJobs {
    private static final Logger LOG = LoggerFactory.getLogger(OrderJobs.class);
    private final OrderMapper mapper;
    private final OrderService orders;
    private final boolean enabled;
    public OrderJobs(OrderMapper mapper, OrderService orders, @Value("${scent.order.auto-close:true}") boolean enabled) {
        this.mapper = mapper; this.orders = orders; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${scent.order.scan-ms:60000}")
    public void expire() {
        if (!enabled) { return; }
        for (long id : mapper.expired()) {
            MDC.put("traceId", UUID.randomUUID().toString());
            try { orders.closeExpired(id); }
            catch (RuntimeException ex) { LOG.warn("Order expiry retry required id={}, type={}", id, ex.getClass().getSimpleName()); }
            finally { MDC.remove("traceId"); }
        }
    }
}
