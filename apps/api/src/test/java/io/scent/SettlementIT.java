package io.scent;

import io.scent.common.BusinessException;
import io.scent.order.*;
import io.scent.settlement.*;
import io.scent.settlement.PaymentGateway.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real API/MySQL; the bank boundary is a test double. Real RSA/SDK HTTP is covered separately, not a sandbox claim. */
class SettlementIT extends ApiIntegrationSupport {
    @MockitoBean PaymentGateway bank;
    @Autowired OrderService orders;
    @Autowired OrderMapper orderMapper;
    @Autowired PaymentService payments;
    private final Map<String,Receipt> receipts = new ConcurrentHashMap<>();
    private final Map<String,RefundReceipt> refunds = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> refundAt = new AtomicReference<>();
    private static final Identity IDENTITY = new Identity("20261015000001", "208800000001");

    @BeforeEach
    void bankBoundary() {
        reset(bank); receipts.clear(); refunds.clear(); refundAt.set(null);
        when(bank.configured()).thenReturn(true); when(bank.identity()).thenReturn(IDENTITY);
        when(bank.checkout(any())).thenReturn("https://openapi-sandbox.dl.alipaydev.com/gateway.do?test_fixture=1");
        when(bank.query(anyString())).thenAnswer(i -> receipts.getOrDefault(i.getArgument(0), absent(i.getArgument(0))));
        when(bank.close(anyString())).thenAnswer(i -> receipts.containsKey(i.getArgument(0)) ? State.CLOSED : State.NOT_FOUND);
        when(bank.notification(anyMap())).thenAnswer(i -> {
            Map<String,String> p = i.getArgument(0);
            if (!"fixture-valid".equals(p.get("sign"))) { throw BusinessException.invalid("Invalid test signature"); }
            Receipt result = receipts.get(p.get("out_trade_no"));
            if (result == null) { throw BusinessException.invalid("Unknown fixture order"); } return result;
        });
        when(bank.queryRefund(anyString(), anyString())).thenAnswer(i -> refunds.getOrDefault(i.getArgument(1), new RefundReceipt(RefundState.NOT_FOUND, i.getArgument(1), null, null, null, null)));
        when(bank.refund(anyString(), anyString(), anyLong())).thenAnswer(i -> refunds.computeIfAbsent(i.getArgument(1), key ->
            new RefundReceipt(RefundState.SUCCESS, key, receipts.get(i.getArgument(0)).tradeNo(), i.getArgument(2),
                Optional.ofNullable(refundAt.get()).orElseGet(() -> Instant.now().truncatedTo(ChronoUnit.SECONDS)), null)));
    }

    @Test
    void missingConfigurationDoesNotCreatePaymentAndMoneyInputsCannotBeFractional() throws Exception {
        Fixture f = order(); when(bank.configured()).thenReturn(false);
        when(bank.identity()).thenThrow(new BusinessException(503, "PAYMENT_UNAVAILABLE", "支付暂未开放"));
        assertFalse(call("GET", "/capabilities", null, null, 200).path("data").path("paymentConfigured").asBoolean());
        call("POST", path(f, "/payment"), f.user().token(), null, 503);
        assertEquals(0L, count("payment", "order_id", f.id()));
        var input = input(101, 1L, 1); input.put("priceCents", 12900.5);
        call("POST", "/admin/products", admin, input, 400);
        call("POST", "/cart", f.user().token(), Map.of("productId", f.product()), 200);
        call("PATCH", "/cart/" + f.product(), f.user().token(), Map.of("quantity", 1.5), 400);
    }

    @Test
    void paymentIntentUsesServerAmountAndIsOnePerOrderWithOwnerAndRoleChecks() throws Exception {
        Fixture f = order(); User other = buyer();
        begin(f); begin(f);
        assertEquals(1L, count("payment", "order_id", f.id()));
        verify(bank, times(2)).checkout(argThat(o -> o.getTotalCents() == 12900 && o.getOrderNo().equals(f.number())));
        for (String endpoint : List.of("/payment", "/payment/sync")) { call("POST", path(f, endpoint), other.token(), null, 404); }
        call("GET", path(f, "/settlement"), other.token(), null, 404);
        call("POST", path(f, "/payment"), admin, null, 403);
        call("POST", path(f, "/payment"), null, null, 401);
        call("POST", "/admin/orders/" + f.id() + "/payment/sync", f.user().token(), null, 403);
        call("POST", path(f, "/paySuccess"), f.user().token(), null, 404);
        assertEquals(0, status(f)); assertEquals(1, stock(f));
    }

    @Test
    void tenDuplicateNotificationsCommitOneReceiptWithoutStockOrTimeChanges() throws Exception {
        Fixture f = order(); begin(f); Receipt paid = paid(f, Instant.now().truncatedTo(ChronoUnit.SECONDS)); receipts.put(f.number(), paid);
        var pool = Executors.newFixedThreadPool(10);
        try {
            var tasks = new ArrayList<Future<Integer>>();
            for (int i=0; i<10; i++) { tasks.add(pool.submit(() -> notifyOrder(f).statusCode())); }
            for (var task : tasks) { assertEquals(200, task.get(20, TimeUnit.SECONDS)); }
        } finally { pool.shutdownNow(); }
        assertEquals(1, status(f)); assertEquals(1, stock(f));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='PAYMENT_SUCCESS' AND resource_id=?", Long.class, f.id()));
        String at = call("GET", path(f, "/settlement"), f.user().token(), null, 200).path("data").path("payment").path("successAt").asString();
        receipts.put(f.number(), new Receipt(State.CLOSED, f.number(), paid.tradeNo(), IDENTITY.appId(), IDENTITY.sellerId(), null, null));
        assertEquals(200, notifyOrder(f).statusCode());
        var current = call("GET", path(f, "/settlement"), f.user().token(), null, 200).path("data").path("payment");
        assertEquals("SUCCESS", current.path("status").asString()); assertEquals(at, current.path("successAt").asString());
    }

    @Test
    void badSignatureMerchantAmountAndDuplicateParametersCannotMarkPaid() throws Exception {
        Fixture f = order(); begin(f);
        assertEquals(400, form("out_trade_no=" + f.number() + "&sign=invalid").statusCode());
        Receipt good = paid(f, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        receipts.put(f.number(), new Receipt(State.PAID, f.number(), good.tradeNo(), "wrong-app", good.sellerId(), 12900L, good.successAt()));
        assertEquals(409, notifyOrder(f).statusCode());
        receipts.put(f.number(), new Receipt(State.PAID, f.number(), good.tradeNo(), good.appId(), "wrong-seller", 12900L, good.successAt()));
        assertEquals(409, notifyOrder(f).statusCode());
        receipts.put(f.number(), new Receipt(State.PAID, f.number(), good.tradeNo(), good.appId(), good.sellerId(), 1L, good.successAt()));
        assertEquals(409, notifyOrder(f).statusCode());
        clearInvocations(bank);
        assertEquals(400, form("out_trade_no=" + f.number() + "&sign=one&sign=two").statusCode());
        verify(bank, never()).notification(anyMap());
        assertEquals(0, status(f)); assertEquals("PENDING", db.queryForObject("SELECT status FROM payment WHERE order_id=?", String.class, f.id()));
    }

    @Test
    void cancellationRequiresBankClosureAndStockRestoresOnlyOnce() throws Exception {
        Fixture f = order(); begin(f);
        receipts.put(f.number(), new Receipt(State.PENDING, f.number(), null, IDENTITY.appId(), IDENTITY.sellerId(), null, null));
        call("POST", path(f, "/cancel"), f.user().token(), null, 200);
        call("POST", path(f, "/cancel"), f.user().token(), null, 200);
        assertEquals(-1, status(f)); assertEquals(2, stock(f)); verify(bank, times(1)).close(f.number());
        assertEquals("CLOSED", db.queryForObject("SELECT status FROM payment WHERE order_id=?", String.class, f.id()));
    }

    @Test
    void unknownOrAbsentBankResultKeepsStockAndCannotBypassServiceGuard() throws Exception {
        Fixture f = order(); begin(f);
        call("POST", path(f, "/cancel"), f.user().token(), null, 409);
        assertEquals(0, status(f)); assertEquals(1, stock(f));
        assertThrows(BusinessException.class, () -> orders.cancel(f.id(), f.user().id(), f.user().id(), -1, "bypass"));
        when(bank.query(f.number())).thenThrow(new BusinessException(503, "PAYMENT_CHECK_PENDING", "结果未知"));
        call("POST", path(f, "/cancel"), f.user().token(), null, 503); assertEquals(1, stock(f));
        db.update("UPDATE mall_order SET expires_at='2000-01-01' WHERE id=?", f.id());
        Fixture plain = order(); db.update("UPDATE mall_order SET expires_at='2001-01-01' WHERE id=?", plain.id());
        assertFalse(orderMapper.expired().contains(f.id())); assertTrue(orderMapper.expired().contains(plain.id()));
        orders.closeExpired(plain.id()); assertEquals(2, stock(plain));
    }

    @Test
    void paidQueryWinsAgainstCancellationAndLateReceiptUsesRefundWithoutRestockingAgain() throws Exception {
        Fixture paid = order(); begin(paid); receipts.put(paid.number(), paid(paid, Instant.now().truncatedTo(ChronoUnit.SECONDS)));
        call("POST", path(paid, "/cancel"), paid.user().token(), null, 409); assertEquals(1, status(paid)); assertEquals(1, stock(paid));
        Fixture late = order(); begin(late);
        receipts.put(late.number(), new Receipt(State.CLOSED, late.number(), null, IDENTITY.appId(), IDENTITY.sellerId(), null, null));
        call("POST", path(late, "/cancel"), late.user().token(), null, 200); assertEquals(2, stock(late));
        receipts.put(late.number(), paid(late, Instant.now().truncatedTo(ChronoUnit.SECONDS))); assertEquals(200, notifyOrder(late).statusCode());
        var state = settlement(late); long sale = state.path("afterSale").path("id").asLong();
        assertTrue(state.path("payment").path("needsReview").asBoolean()); assertEquals(-1, status(late));
        call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 200);
        call("POST", "/admin/after-sales/" + sale + "/restock", admin, Map.of("reason", "不应再次入库"), 409);
        assertFalse(settlement(late).path("payment").path("needsReview").asBoolean()); assertEquals(2, stock(late));
    }

    @Test
    void unshippedWholeRefundAndSeparateRestockAreIdempotentAndBlockFulfillment() throws Exception {
        Fixture f = order(); confirmPaid(f); long sale = apply(f);
        assertEquals(sale, apply(f));
        call("POST", "/admin/orders/" + f.id() + "/pack", admin, null, 409);
        call("POST", "/admin/orders/" + f.id() + "/ship", admin, shipment(), 409);
        call("POST", "/admin/after-sales/" + sale + "/review", f.user().token(), Map.of("approve", true, "note", "同意"), 403);
        approve(sale); call("POST", "/admin/after-sales/" + sale + "/restock", admin, Map.of("reason", "太早"), 409);
        var refund = call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 200).path("data");
        assertEquals("SUCCESS", refund.path("status").asString()); assertEquals(12900, refund.path("amountCents").asLong()); assertEquals(1, stock(f));
        call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 200); assertEquals(1, refunds.size());
        for (int i=0;i<2;i++) { call("POST", "/admin/after-sales/" + sale + "/restock", admin, Map.of("reason", "实物核验可售"), 200); }
        assertEquals(2, stock(f)); assertEquals("SUCCESS", settlement(f).path("payment").path("status").asString());
        call("POST", "/admin/orders/" + f.id() + "/ship", admin, shipment(), 409);
    }

    @Test
    void shippedReturnRequiresReceiptAndReviewsBelongToCompletedOrderItemOnly() throws Exception {
        Fixture f = order(); User other = buyer(); confirmPaid(f);
        long item = call("GET", path(f, ""), f.user().token(), null, 200).path("data").path("items").get(0).path("id").asLong();
        var review = Map.of("orderItemId", item, "stars", 5, "content", "<script>这是纯文本</script>");
        call("POST", path(f, "/reviews"), f.user().token(), review, 409);
        call("POST", "/admin/orders/" + f.id() + "/ship", admin, shipment(), 200);
        call("POST", path(f, "/receive"), f.user().token(), null, 200);
        call("POST", path(f, "/reviews"), other.token(), review, 404);
        call("POST", path(f, "/reviews"), f.user().token(), review, 200); call("POST", path(f, "/reviews"), f.user().token(), review, 200);
        call("POST", path(f, "/reviews"), f.user().token(), Map.of("orderItemId", item, "stars", 4, "content", "第二条"), 409);
        var publicReview = call("GET", "/catalog/products/" + f.product() + "/reviews", null, null, 200).path("data").path("items").get(0);
        assertEquals(review.get("content"), publicReview.path("content").asString()); assertFalse(publicReview.has("phone"));
        long sale = apply(f); approve(sale);
        call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 409);
        call("POST", "/admin/after-sales/" + sale + "/receive", admin, null, 409);
        call("POST", "/after-sales/" + sale + "/return", other.token(), shipment(), 404);
        call("POST", "/after-sales/" + sale + "/return", f.user().token(), shipment(), 200);
        call("POST", "/admin/after-sales/" + sale + "/receive", admin, null, 200);
        assertEquals(1, stock(f)); call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 200);
        call("POST", path(f, "/reviews"), f.user().token(), review, 409);
        assertEquals(1, settlement(f).path("reviews").size());
        call("POST", "/admin/after-sales/" + sale + "/restock", admin, Map.of("reason", "验收后可售"), 200); assertEquals(2, stock(f));
    }

    @Test
    void rejectionRestoresOriginalFulfillmentPermissionAndDoesNotRefund() throws Exception {
        Fixture f = order(); confirmPaid(f); long sale = apply(f);
        call("POST", "/admin/after-sales/" + sale + "/review", admin, Map.of("approve", false, "note", "未满足申请条件"), 200);
        call("POST", "/admin/orders/" + f.id() + "/pack", admin, null, 200);
        call("POST", "/admin/orders/" + f.id() + "/ship", admin, shipment(), 200);
        call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 409);
        assertEquals(0L, count("refund", "after_sale_id", sale));
    }

    @Test
    void tenRefundAndRestockRequestsUseOneBankNumberOneLedgerAndOneStockRestore() throws Exception {
        Fixture f = order(); confirmPaid(f); long sale = apply(f); approve(sale);
        var pool = Executors.newFixedThreadPool(10);
        try {
            var work = new ArrayList<Future<HttpResponse<String>>>();
            for (int i=0;i<10;i++) { work.add(pool.submit(() -> send("POST", "/admin/after-sales/" + sale + "/refund", admin, null))); }
            for (var result : work) { var response = result.get(20, TimeUnit.SECONDS); assertEquals(200, response.statusCode(), response.body()); }
            assertEquals(1, refunds.size()); assertEquals(1L, count("refund", "after_sale_id", sale)); assertEquals(1, stock(f));
            work.clear();
            for (int i=0;i<10;i++) { work.add(pool.submit(() -> send("POST", "/admin/after-sales/" + sale + "/restock", admin, Map.of("reason", "并发只恢复一次")))); }
            for (var result : work) { var response = result.get(20, TimeUnit.SECONDS); assertEquals(200, response.statusCode(), response.body()); }
        } finally { pool.shutdownNow(); }
        assertEquals(2, stock(f));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='REFUND_SUCCESS' AND resource_id=?", Long.class, f.id()));
    }

    @Test
    void refundSuccessBeforeDatabaseFailureIsRecoveredBySameRequestQuery() throws Exception {
        Fixture f = order(); confirmPaid(f); long sale = apply(f); approve(sale);
        db.execute("ALTER TABLE after_sale ADD CONSTRAINT it_refund_failure CHECK (order_id<>" + f.id() + " OR state<>'REFUNDED')");
        try {
            call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 500);
            assertEquals("PENDING", settlement(f).path("refund").path("status").asString()); assertEquals(1, refunds.size());
            assertEquals("REFUND_PENDING", settlement(f).path("afterSale").path("state").asString());
        } finally { db.execute("ALTER TABLE after_sale DROP CHECK it_refund_failure"); }
        call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 200);
        assertEquals(1, refunds.size()); verify(bank, times(1)).refund(eq(f.number()), anyString(), eq(12900L));
        assertEquals("SUCCESS", settlement(f).path("refund").path("status").asString()); assertEquals(1, stock(f));
    }

    @Test
    void corruptedRefundAmountCannotReachBank() throws Exception {
        Fixture f = order(); confirmPaid(f); long sale = apply(f); approve(sale);
        db.update("INSERT INTO refund(after_sale_id,request_no,amount_cents) VALUES(?,?,12901)", sale, "R"+UUID.randomUUID());
        call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 409);
        verify(bank, never()).refund(anyString(), anyString(), anyLong());
    }

    @Test
    void secondProductRestoreFailureRollsBackFirstProductAndRestockedMarker() throws Exception {
        User user = buyer(); long first = create(input(101,1L,2));
        var body = input(101,1L,2); String unique = "restore-"+UUID.randomUUID(); body.put("name",unique); long second = create(body);
        long address = call("POST","/addresses",user.token(),address("入库回滚",true),201).path("data").path("id").asLong();
        for (long product : List.of(first,second)) { call("POST","/cart",user.token(),Map.of("productId",product),200); }
        var row = call("POST","/orders",user.token(),Map.of("addressId",address),201,Map.of("Idempotency-Key",UUID.randomUUID().toString())).path("data").path("order");
        Fixture f = new Fixture(user,first,row.path("id").asLong(),row.path("orderNo").asString());
        assertEquals(25800,row.path("totalCents").asLong()); confirmPaid(f); long sale = apply(f); approve(sale);
        call("POST","/admin/after-sales/"+sale+"/refund",admin,null,200);
        db.execute("ALTER TABLE product ADD CONSTRAINT it_restore_failure CHECK (name<>'"+unique+"' OR stock<>2)");
        try {
            call("POST","/admin/after-sales/"+sale+"/restock",admin,Map.of("reason","故障回滚"),500);
            assertEquals(1,stock(f)); assertEquals(1,db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,second));
            assertFalse(settlement(f).path("afterSale").path("restocked").asBoolean());
        } finally { db.execute("ALTER TABLE product DROP CHECK it_restore_failure"); }
        call("POST","/admin/after-sales/"+sale+"/restock",admin,Map.of("reason","恢复后重试"),200);
        assertEquals(2,stock(f)); assertEquals(2,db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,second));
        assertEquals(1,refunds.size());
    }

    @Test
    void financeUsesIndependentShanghaiSuccessDaysIncludingNegativeNetAndExcludesPending() throws Exception {
        var before = call("GET", "/admin/finance?from=2001-02-03&to=2001-02-05", admin, null, 200).path("data");
        Fixture a = order(); historicalPaid(a, Instant.parse("2001-02-03T15:59:59Z"));
        Fixture b = order(); historicalPaid(b, Instant.parse("2001-02-03T16:00:00Z"));
        Fixture pending = order(); begin(pending);
        long sale = apply(a); approve(sale); refundAt.set(Instant.parse("2001-02-04T16:00:00Z"));
        call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 200);
        var after = call("GET", "/admin/finance?from=2001-02-03&to=2001-02-05", admin, null, 200).path("data");
        assertEquals(25800, after.path("paidCents").asLong()-before.path("paidCents").asLong());
        assertEquals(12900, after.path("refundedCents").asLong()-before.path("refundedCents").asLong());
        assertEquals(12900, after.path("days").get(0).path("paidCents").asLong()-before.path("days").get(0).path("paidCents").asLong());
        assertEquals(12900, after.path("days").get(1).path("paidCents").asLong()-before.path("days").get(1).path("paidCents").asLong());
        assertEquals(-12900, after.path("days").get(2).path("netCents").asLong()-before.path("days").get(2).path("netCents").asLong());
        call("GET", "/admin/finance?from=2001-01-01&to=2002-01-01", admin, null, 400);
        call("GET", "/admin/finance?from=2001-01-01&to=2001-01-02", a.user().token(), null, 403);
    }

    @Test
    void expiredIssuedPaymentWaitsForClosureThenRestoresStockExactlyOnce() throws Exception {
        Fixture f = order(); begin(f);
        db.update("UPDATE mall_order SET expires_at='2000-01-01' WHERE id=?", f.id());
        call("POST", path(f, "/payment"), f.user().token(), null, 409);
        assertThrows(BusinessException.class, () -> payments.reconcile(f.id()));
        assertEquals(0, status(f)); assertEquals(1, stock(f));
        assertEquals("PENDING", settlement(f).path("payment").path("status").asString());
        receipts.put(f.number(), new Receipt(State.CLOSED, f.number(), null, IDENTITY.appId(), IDENTITY.sellerId(), null, null));
        payments.reconcile(f.id()); payments.reconcile(f.id());
        assertEquals(-2, status(f)); assertEquals(2, stock(f));
        assertEquals("CLOSED", settlement(f).path("payment").path("status").asString());
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='PAYMENT_CLOSED' AND resource_id=?", Long.class, f.id()));
    }

    @Test
    void busyPaymentAndUnknownRefundRecoverWithoutInventingMoneyOrNewRequest() throws Exception {
        Fixture f = order(); begin(f);
        when(bank.query(f.number())).thenThrow(new BusinessException(503, "PAYMENT_CHECK_PENDING", "平台繁忙"));
        call("POST", path(f, "/payment/sync"), f.user().token(), null, 503);
        assertEquals(0, status(f)); assertEquals(1, stock(f));
        assertTrue(settlement(f).path("payment").path("amountCents").isNull());
        doReturn(paid(f, Instant.now().truncatedTo(ChronoUnit.SECONDS))).when(bank).query(f.number());
        call("POST", path(f, "/payment/sync"), f.user().token(), null, 200);
        long sale = apply(f); approve(sale);
        when(bank.refund(eq(f.number()), anyString(), eq(12900L))).thenThrow(new BusinessException(503, "PAYMENT_CHECK_PENDING", "退款结果未知"));
        call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 503);
        var pending = settlement(f).path("refund");
        assertEquals("PENDING", pending.path("status").asString()); assertEquals(1, stock(f));
        String number = pending.path("requestNo").asString();
        when(bank.queryRefund(f.number(), number)).thenReturn(new RefundReceipt(RefundState.SUCCESS, number, paid(f, Instant.now()).tradeNo(), 12900L, Instant.now().truncatedTo(ChronoUnit.SECONDS), null));
        call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 200);
        call("POST", "/admin/after-sales/" + sale + "/refund", admin, null, 200);
        assertEquals(number, settlement(f).path("refund").path("requestNo").asString());
        assertEquals("SUCCESS", settlement(f).path("refund").path("status").asString());
        verify(bank, times(1)).refund(f.number(), number, 12900L);
        assertEquals(1, stock(f));
    }

    private Fixture order() throws Exception {
        User user=buyer(); long product=create(input(101,1L,2));
        long address=call("POST","/addresses",user.token(),address("资金验证",true),201).path("data").path("id").asLong();
        call("POST","/cart",user.token(),Map.of("productId",product),200);
        var row=call("POST","/orders",user.token(),Map.of("addressId",address),201,Map.of("Idempotency-Key",UUID.randomUUID().toString())).path("data").path("order");
        return new Fixture(user,product,row.path("id").asLong(),row.path("orderNo").asString());
    }
    private void begin(Fixture f) throws Exception { call("POST",path(f,"/payment"),f.user().token(),null,200); }
    private void confirmPaid(Fixture f) throws Exception { begin(f);receipts.put(f.number(),paid(f,Instant.now().truncatedTo(ChronoUnit.SECONDS)));assertEquals(200,notifyOrder(f).statusCode()); }
    private void historicalPaid(Fixture f,Instant at) throws Exception { db.update("UPDATE mall_order SET created_at=? WHERE id=?",java.sql.Timestamp.from(at.minusSeconds(60)),f.id());begin(f);receipts.put(f.number(),paid(f,at));assertEquals(200,notifyOrder(f).statusCode()); }
    private Receipt paid(Fixture f,Instant at) { return new Receipt(State.PAID,f.number(),"BANK"+f.number(),IDENTITY.appId(),IDENTITY.sellerId(),db.queryForObject("SELECT total_cents FROM mall_order WHERE id=?",Long.class,f.id()),at); }
    private static Receipt absent(String number) { return new Receipt(State.NOT_FOUND,number,null,IDENTITY.appId(),IDENTITY.sellerId(),null,null); }
    private long apply(Fixture f) throws Exception { return call("POST",path(f,"/after-sale"),f.user().token(),Map.of("reason","虚构整单售后验证"),200).path("data").path("id").asLong(); }
    private void approve(long sale) throws Exception { call("POST","/admin/after-sales/"+sale+"/review",admin,Map.of("approve",true,"note","同意整单处理"),200); }
    private JsonNode settlement(Fixture f) throws Exception { return call("GET",path(f,"/settlement"),f.user().token(),null,200).path("data"); }
    private HttpResponse<String> notifyOrder(Fixture f) throws Exception { return form("out_trade_no="+f.number()+"&sign=fixture-valid"); }
    private HttpResponse<String> form(String body) throws Exception { return client.send(HttpRequest.newBuilder(uri("/payments/alipay/notify")).header("Content-Type","application/x-www-form-urlencoded;charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); }
    private int status(Fixture f) { return db.queryForObject("SELECT status FROM mall_order WHERE id=?",Integer.class,f.id()); }
    private int stock(Fixture f) { return db.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,f.product()); }
    private long count(String table,String key,long id) { return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+key+"=?",Long.class,id); }
    private static Map<String,String> shipment() { return Map.of("carrier","虚构快递","trackingNo","RETURN000001"); }
    private static String path(Fixture f,String suffix) { return "/orders/"+f.id()+suffix; }
    private record Fixture(User user,long product,long id,String number) { }
}
