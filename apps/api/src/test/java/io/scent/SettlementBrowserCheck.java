package io.scent;

import io.scent.order.Order;
import io.scent.settlement.PaymentGateway;
import io.scent.settlement.PaymentGateway.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit post-build check: real browser -> HTTP -> MySQL, only the bank is a test double.
 * Not named *IT: run after Node dependencies and both frontend builds are ready.
 * No fixture endpoint, production switch or test class is packaged in the application Jar. */
class SettlementBrowserCheck extends ApiIntegrationSupport {
    @MockitoBean PaymentGateway bank;
    @TempDir Path controlDirectory;

    @Test
    void threeBrowserOrdersKeepFulfillmentReviewsRefundsStockAndFinanceConsistent() throws Exception {
        reset(bank);
        Identity identity = new Identity("20261027000001", "208800000001");
        Path control = controlDirectory.resolve("bank.json"); Files.writeString(control, "{}");
        var refunds = new ConcurrentHashMap<String,RefundReceipt>();
        when(bank.configured()).thenReturn(true); when(bank.identity()).thenReturn(identity);
        when(bank.checkout(any(Order.class))).thenAnswer(i -> "https://openapi-sandbox.dl.alipaydev.com/gateway.do?internal_order=" + ((Order)i.getArgument(0)).getOrderNo());
        when(bank.query(anyString())).thenAnswer(i -> {
            String number = i.getArgument(0); var row = json.readTree(Files.readString(control)).path(number);
            if (row.isMissingNode()) return new Receipt(State.NOT_FOUND, number, null, identity.appId(), identity.sellerId(), null, null);
            return new Receipt(State.PAID, number, "INTERNAL" + number, identity.appId(), identity.sellerId(), row.path("cents").asLong(), Instant.parse(row.path("paidAt").asString()));
        });
        when(bank.queryRefund(anyString(), anyString())).thenAnswer(i -> refunds.getOrDefault(i.getArgument(1), new RefundReceipt(RefundState.NOT_FOUND, i.getArgument(1), null, null, null, null)));
        when(bank.refund(anyString(), anyString(), anyLong())).thenAnswer(i -> refunds.computeIfAbsent(i.getArgument(1), key -> new RefundReceipt(RefundState.SUCCESS, key, "INTERNAL" + i.getArgument(0), i.getArgument(2), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), null)));
        User buyer = buyer(); long product = create(input(101, 1L, 8));
        call("POST", "/addresses", buyer.token(), address("隔离端到端验收", true), 201);
        Path root = Path.of("../..").toRealPath();
        Path evidence = Optional.ofNullable(System.getenv("SCENT_INTERNAL_BROWSER_EVIDENCE")).map(Path::of).orElseGet(() -> controlDirectory.resolve("evidence"));
        Files.createDirectories(evidence);
        var builder = new ProcessBuilder(System.getenv().getOrDefault("SCENT_TEST_NODE", "node"), root.resolve("tests/settlement-internal-browser.cjs").toString());
        builder.directory(root.toFile()); builder.redirectErrorStream(true); builder.redirectOutput(evidence.resolve("browser.log").toFile());
        builder.environment().putAll(Map.of("SCENT_INTERNAL_API", "http://127.0.0.1:" + port, "SCENT_INTERNAL_CONTROL", control.toString(), "SCENT_INTERNAL_PRODUCT", Long.toString(product), "SCENT_INTERNAL_BUYER", buyer.phone(), "SCENT_INTERNAL_BROWSER_EVIDENCE", evidence.toString()));
        Process process = builder.start();
        boolean finished = process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.toHandle().descendants().forEach(child -> child.destroyForcibly());
            process.destroyForcibly();
        }
        assertTrue(finished, "Internal browser timed out; see private browser evidence");
        assertEquals(0, process.exitValue(), () -> {
            try { return Files.readString(evidence.resolve("browser.log")); } catch (Exception ex) { return "Cannot read internal browser evidence"; }
        });
        assertEquals(3L, db.queryForObject("SELECT COUNT(*) FROM payment p JOIN mall_order o ON o.id=p.order_id WHERE o.account_id=? AND p.status='SUCCESS' AND p.amount_cents=12900", Long.class, buyer.id()));
        assertEquals(2, refunds.size());
        assertEquals(2L, db.queryForObject("SELECT COUNT(*) FROM refund r JOIN after_sale a ON a.id=r.after_sale_id JOIN mall_order o ON o.id=a.order_id WHERE o.account_id=? AND r.status='SUCCESS' AND r.amount_cents=12900", Long.class, buyer.id()));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM review r JOIN order_item i ON i.id=r.order_item_id JOIN mall_order o ON o.id=i.order_id WHERE o.account_id=?", Long.class, buyer.id()));
        assertEquals(7, db.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, product));
        verify(bank, times(2)).refund(anyString(), anyString(), eq(12900L));
        System.out.println("INTERNAL_BROWSER_EVIDENCE=" + evidence);
    }
}
