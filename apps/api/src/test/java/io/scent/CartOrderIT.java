package io.scent;

import io.scent.order.OrderService;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

class CartOrderIT extends ApiIntegrationSupport {
    @Autowired OrderService orders;

    @Test
    void cartPreservesDuplicateRuleQuantityLimitAndBuyerIsolation() throws Exception {
        User a = buyer(); User b = buyer(); long product = create(input(101, 1L, 10));
        call("POST", "/cart", null, Map.of("productId", product), 401);
        call("POST", "/cart", admin, Map.of("productId", product), 403);
        add(a, product);
        call("POST", "/cart", a.token(), Map.of("productId", product), 409);
        call("PATCH", "/cart/" + product, a.token(), Map.of("quantity", 5), 200);
        call("PATCH", "/cart/" + product, a.token(), Map.of("quantity", 6), 400);
        call("PATCH", "/cart/" + product, b.token(), Map.of("quantity", 1), 404);
        call("DELETE", "/cart/" + product, b.token(), null, 200);
        JsonNode cart = call("GET", "/cart", a.token(), null, 200).path("data");
        assertEquals(5, cart.path("items").get(0).path("quantity").asInt());
        assertEquals(64500, cart.path("totalCents").asLong());
        assertTrue(cart.path("canCheckout").asBoolean());
        call("POST", "/cart", a.token(), Map.of("productId", product, "accountId", b.id()), 400);
    }

    @Test
    void twoConcurrentAddsAtTheTwentyItemBoundaryCannotOverfillCart() throws Exception {
        User user = buyer(); List<Long> products = new ArrayList<>();
        for (int i = 0; i < 21; i++) { products.add(create(input(101, 1L, 10))); }
        for (int i = 0; i < 19; i++) { add(user, products.get(i)); }
        var one = CompletableFuture.supplyAsync(() -> rawStatus("POST", "/cart", user.token(), Map.of("productId", products.get(19))));
        var two = CompletableFuture.supplyAsync(() -> rawStatus("POST", "/cart", user.token(), Map.of("productId", products.get(20))));
        List<Integer> statuses = new ArrayList<>(List.of(one.join(), two.join())); Collections.sort(statuses);
        assertEquals(List.of(200,409), statuses);
        assertEquals(20, db.queryForObject("SELECT COUNT(*) FROM cart_item WHERE account_id=?", Integer.class, user.id()));
        call("DELETE", "/cart/" + products.getFirst(), user.token(), null, 200);
        assertEquals(19, call("GET", "/cart", user.token(), null, 200).path("data").path("items").size());
    }

    @Test
    void serverPricesAndAddressSnapshotsSurviveCatalogAndAddressChanges() throws Exception {
        User user = buyer(); long address = address(user); var original = input(101, 1L, 10); long product = create(original);
        add(user, product); call("PATCH", "/cart/" + product, user.token(), Map.of("quantity", 2), 200);
        String key = UUID.randomUUID().toString(); JsonNode detail = checkout(user, address, key, 201).path("data");
        long id = detail.path("order").path("id").asLong();
        assertEquals(25800, detail.path("order").path("totalCents").asLong());
        assertEquals(12900, detail.path("items").get(0).path("priceCents").asLong());
        assertEquals(8, stock(product));
        assertEquals(0, call("GET", "/cart", user.token(), null, 200).path("data").path("items").size());
        Instant created = Instant.parse(detail.path("order").path("createdAt").asString());
        assertTrue(Duration.between(created, Instant.now()).abs().toSeconds() < 15, "API timestamps must have a correct UTC offset");
        long expiryMinutes = Duration.between(Instant.now(), Instant.parse(detail.path("order").path("expiresAt").asString())).toMinutes();
        assertTrue(expiryMinutes >= 19 && expiryMinutes <= 20);
        var edited = edit(original, 1); edited.put("name", "已经改名"); edited.put("priceCents", 9900);
        call("PUT", "/admin/products/" + product, admin, edited, 200);
        call("PUT", "/addresses/" + address, user.token(), address("新收件人", true), 200);
        call("DELETE", "/addresses/" + address, user.token(), null, 200);
        JsonNode replay = checkout(user, address, key, 201).path("data");
        assertEquals(id, replay.path("order").path("id").asLong());
        assertEquals(25800, replay.path("order").path("totalCents").asLong());
        assertEquals(original.get("name"), replay.path("items").get(0).path("name").asString());
        assertEquals("测试收件人", replay.path("order").path("receiver").asString());
        assertFalse(replay.path("order").has("checkoutKey"));
    }

    @Test
    void malformedOrForgedCheckoutCannotSetOwnerPriceOrStatus() throws Exception {
        User a = buyer(); User b = buyer(); long address = address(a); long otherAddress = address(b);
        long product = create(input(101, 1L, 3)); add(a, product);
        Map<String,String> key = Map.of("Idempotency-Key", UUID.randomUUID().toString());
        call("POST", "/orders", a.token(), Map.of("addressId", address), 400);
        call("POST", "/orders", a.token(), Map.of("addressId", address, "totalCents", 1), 400, key);
        call("POST", "/orders", a.token(), Map.of("addressId", address, "status", 1), 400, key);
        checkout(a, otherAddress, UUID.randomUUID().toString(), 404);
        assertEquals(3, stock(product));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM mall_order WHERE account_id=?", Integer.class, a.id()));
        checkout(b, otherAddress, UUID.randomUUID().toString(), 409);
    }

    @Test
    void tenReplaysWithOneKeyCreateOneOrderAndDeductOnlyOnce() throws Exception {
        User user = buyer(); long address = address(user); long product = create(input(101, 1L, 5)); add(user, product);
        String key = UUID.randomUUID().toString(); List<CompletableFuture<JsonNode>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> checkoutUnchecked(user, address, key, 201)));
        }
        Set<Long> ids = new HashSet<>();
        for (var response : calls) { ids.add(response.join().path("data").path("order").path("id").asLong()); }
        assertEquals(1, ids.size()); assertEquals(4, stock(product));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM mall_order WHERE account_id=?", Integer.class, user.id()));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cart_item WHERE account_id=?", Integer.class, user.id()));
        long nextAddress = address(user);
        checkout(user, nextAddress, key, 409);
    }

    @Test
    void tenIndependentBuyersCompetingForOneUnitProduceExactlyOneOrder() throws Exception {
        long product = create(input(101, 1L, 1)); List<User> users = new ArrayList<>(); List<Long> addresses = new ArrayList<>();
        for (int i = 0; i < 10; i++) { User user = buyer(); users.add(user); addresses.add(address(user)); add(user, product); }
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i); long address = addresses.get(i);
            calls.add(CompletableFuture.supplyAsync(() -> {
                try { return send("POST", "/orders", user.token(), Map.of("addressId", address), Map.of("Idempotency-Key", UUID.randomUUID().toString())).statusCode(); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }));
        }
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();
        assertEquals(1, statuses.stream().filter(s -> s == 201).count());
        assertEquals(9, statuses.stream().filter(s -> s == 409).count());
        assertEquals(0, stock(product));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM order_item WHERE product_id=?", Integer.class, product));
        assertEquals(1, db.queryForObject("SELECT SUM(quantity) FROM order_item WHERE product_id=?", Integer.class, product));
    }

    @Test
    void secondItemFailureRollsBackFirstDeductionAndKeepsTheCart() throws Exception {
        User user = buyer(); long address = address(user); long first = create(input(101, 1L, 1)); long second = create(input(101, 1L, 1));
        add(user, first); add(user, second);
        call("POST", "/admin/products/" + second + "/stock", admin, Map.of("version", 0, "delta", -1, "reason", "库存变化验证"), 200);
        checkout(user, address, UUID.randomUUID().toString(), 409);
        assertEquals(1, stock(first)); assertEquals(0, stock(second));
        assertEquals(0L, db.queryForObject("SELECT version FROM product WHERE id=?", Long.class, first));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM cart_item WHERE account_id=?", Integer.class, user.id()));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM mall_order WHERE account_id=?", Integer.class, user.id()));
    }

    @Test
    void forcedItemInsertFailureRollsBackHeaderStockAndCartAsOneTransaction() throws Exception {
        User user = buyer(); long address = address(user); long product = create(input(101, 1L, 2)); add(user, product);
        // Ordinary schema ALTER privilege works with binlogging; no SUPER/TRIGGER privilege needed.
        // The numeric ID is created by this test, never taken from an untrusted SQL string.
        db.execute("ALTER TABLE order_item ADD CONSTRAINT it_reject_test_item CHECK (product_id <> " + product + ")");
        try {
            JsonNode failure = checkout(user, address, UUID.randomUUID().toString(), 500);
            assertEquals("INTERNAL_ERROR", failure.path("errorCode").asString());
            assertFalse(failure.toString().contains("it_reject_test_item"));
        }
        finally { db.execute("ALTER TABLE order_item DROP CHECK it_reject_test_item"); }
        assertEquals(2, stock(product));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM cart_item WHERE account_id=?", Integer.class, user.id()));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM mall_order WHERE account_id=?", Integer.class, user.id()));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM order_item WHERE product_id=?", Integer.class, product));
    }

    @Test
    void soldOutAndOffShelfOrderCanBeCancelledOnceWithoutDuplicateRestore() throws Exception {
        User user = buyer(); long address = address(user); var input = input(101, 1L, 1); long product = create(input); add(user, product);
        long id = checkout(user, address, UUID.randomUUID().toString(), 201).path("data").path("order").path("id").asLong();
        assertEquals(0, stock(product));
        var offShelf = edit(input, 1); offShelf.put("active", false); call("PUT", "/admin/products/" + product, admin, offShelf, 200);
        for (int i = 0; i < 2; i++) {
            assertEquals(-1, call("POST", "/orders/" + id + "/cancel", user.token(), null, 200).path("data").path("order").path("status").asInt());
        }
        assertEquals(1, stock(product));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_CLOSE' AND resource_id=?", Integer.class, id));
        assertFalse(db.queryForObject("SELECT active FROM product WHERE id=?", Boolean.class, product));
        call("GET", "/catalog/products/" + product, null, null, 404);
    }

    @Test
    void cancellationStillRestoresAfterWarehouseTopUpReachesTheOldArtificialCap() throws Exception {
        User user = buyer(); long address = address(user); long product = create(input(101, 1L, 1)); add(user, product);
        long id = checkout(user, address, UUID.randomUUID().toString(), 201).path("data").path("order").path("id").asLong();
        call("POST", "/admin/products/" + product + "/stock", admin, Map.of("version", 1, "delta", 1000000, "reason", "恢复边界验证"), 200);
        call("POST", "/orders/" + id + "/cancel", user.token(), null, 200);
        assertEquals(1000001, stock(product));
    }

    @Test
    void expiredOrdersCloseOnceAndFutureOrdersRemainPending() throws Exception {
        User user = buyer(); long address = address(user); long product = create(input(101, 1L, 1)); add(user, product);
        long id = checkout(user, address, UUID.randomUUID().toString(), 201).path("data").path("order").path("id").asLong();
        orders.closeExpired(id); assertEquals(0, stock(product));
        db.update("UPDATE mall_order SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?", id);
        orders.closeExpired(id); orders.closeExpired(id);
        assertEquals(-2, db.queryForObject("SELECT status FROM mall_order WHERE id=?", Integer.class, id));
        assertEquals(1, stock(product));
    }

    @Test
    void orderOwnershipAndStateGuardsCoverPackingShippingAndReceipt() throws Exception {
        User user = buyer(); User other = buyer(); long address = address(user); long product = create(input(101, 1L, 1)); add(user, product);
        long id = checkout(user, address, UUID.randomUUID().toString(), 201).path("data").path("order").path("id").asLong();
        call("GET", "/orders/" + id, other.token(), null, 404);
        call("POST", "/orders/" + id + "/cancel", other.token(), null, 404);
        call("POST", "/orders/" + id + "/receive", other.token(), null, 404);
        call("POST", "/orders/" + id + "/receive", user.token(), null, 409);
        call("POST", "/admin/orders/" + id + "/pack", user.token(), null, 403);
        call("POST", "/admin/orders/" + id + "/pack", admin, null, 409);
        // Controlled fixture only: no HTTP endpoint can mark this paid, and this is not a sandbox payment test.
        db.update("UPDATE mall_order SET status=1,paid_at=UTC_TIMESTAMP(6) WHERE id=?", id);
        call("POST", "/orders/" + id + "/cancel", user.token(), null, 409);
        call("POST", "/admin/orders/" + id + "/pack", admin, null, 200);
        call("POST", "/admin/orders/" + id + "/pack", admin, null, 200);
        var shipment = Map.of("carrier", "虚构快递", "trackingNo", "TEST000001");
        call("POST", "/admin/orders/" + id + "/ship", admin, shipment, 200);
        call("POST", "/admin/orders/" + id + "/ship", admin, shipment, 200);
        call("POST", "/admin/orders/" + id + "/ship", admin, Map.of("carrier", "虚构快递", "trackingNo", "DIFFERENT1"), 409);
        String completed = call("POST", "/orders/" + id + "/receive", user.token(), null, 200).path("data").path("order").path("completedAt").asString();
        assertEquals(completed, call("POST", "/orders/" + id + "/receive", user.token(), null, 200).path("data").path("order").path("completedAt").asString());
        call("POST", "/admin/orders/" + id + "/cancel", admin, null, 409);
        call("GET", "/orders?status=999", user.token(), null, 400);
        assertEquals(0, stock(product));
    }

    private long address(User user) throws Exception {
        return call("POST", "/addresses", user.token(), address("测试收件人", true), 201).path("data").path("id").asLong();
    }
    private void add(User user, long product) throws Exception { call("POST", "/cart", user.token(), Map.of("productId", product), 200); }
    private int stock(long product) { return db.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, product); }
    private JsonNode checkout(User user, long address, String key, int expected) throws Exception {
        return call("POST", "/orders", user.token(), Map.of("addressId", address), expected, Map.of("Idempotency-Key", key));
    }
    private JsonNode checkoutUnchecked(User user, long address, String key, int expected) {
        try { return checkout(user, address, key, expected); } catch (Exception ex) { throw new RuntimeException(ex); }
    }
}
