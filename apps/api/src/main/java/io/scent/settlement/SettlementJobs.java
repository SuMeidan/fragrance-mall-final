package io.scent.settlement;

import java.util.UUID;
import java.util.function.LongConsumer;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SettlementJobs {
    private static final Logger LOG = LoggerFactory.getLogger(SettlementJobs.class);
    private final PaymentService payments;
    private final AfterSaleService sales;
    private final SettlementMapper mapper;
    private final boolean enabled;
    public SettlementJobs(PaymentService payments, AfterSaleService sales, SettlementMapper mapper,
                          @Value("${scent.payment.auto-reconcile:${scent.order.auto-close:true}}") boolean enabled) {
        this.payments = payments; this.sales = sales; this.mapper = mapper; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${scent.payment.scan-ms:60000}")
    public void reconcile() {
        if (!enabled || !payments.configured()) { return; }
        // Separate bounded scans: unknown bank trades cannot starve ordinary order expiry.
        for (long id : mapper.pendingPayments()) { run("payment", id, payments::reconcile); }
        for (long id : mapper.pendingRefunds()) { run("refund", id, key -> sales.refund(null, key)); }
    }
    private void run(String kind, long id, LongConsumer action) {
        MDC.put("traceId", UUID.randomUUID().toString());
        try { action.accept(id); }
        catch (RuntimeException ex) { LOG.warn("Settlement retry required kind={}, id={}, type={}", kind, id, ex.getClass().getSimpleName()); }
        finally { MDC.remove("traceId"); }
    }
}
