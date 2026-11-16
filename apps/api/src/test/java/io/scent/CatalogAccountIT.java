package io.scent;

import io.scent.account.AuthService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + real MySQL catalog/account contract tests. */
class CatalogAccountIT extends ApiIntegrationSupport {
    @Test
    void unscentedQueryUsesNullFamilyAndKeepsLegacyFamilyFiltering() throws Exception {
        long tool=create(input(401,null,2));
        var result=call("GET","/catalog/products?unscented=true&pageSize=40",null,null,200).path("data");
        assertTrue(iterable(result.path("items")).stream().allMatch(p->p.path("familyId").isNull()));
        assertTrue(iterable(result.path("items")).stream().anyMatch(p->p.path("id").asLong()==tool));
        var adminPage=call("GET","/admin/products?unscented=true&pageSize=40",admin,null,200).path("data");
        assertTrue(iterable(adminPage.path("items")).stream().allMatch(p->p.path("familyId").isNull()));
        call("GET","/catalog/products?unscented=true&familyId=1",null,null,400);
        call("GET","/admin/products?unscented=true&familyId=1",admin,null,400);
        call("GET","/catalog/products?familyId=0",null,null,400);
        var old=call("GET","/catalog/products?unscented=false&familyId=1",null,null,200).path("data").path("items");
        assertTrue(iterable(old).stream().allMatch(p->p.path("familyId").asLong()==1));
    }

    @Test
    void migrationsAndTaxonomyAreRealAndComplete() throws Exception {
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IN ('1','2')", Integer.class));
        assertEquals(5, db.queryForObject("SELECT COUNT(*) FROM category WHERE parent_id IS NULL", Integer.class));
        assertEquals(18, db.queryForObject("SELECT COUNT(*) FROM category WHERE parent_id IS NOT NULL", Integer.class));
        JsonNode roots = call("GET", "/catalog/categories", null, null, 200).path("data");
        assertEquals(5, roots.size());
        assertEquals(List.of(4,3,3,4,4), iterable(roots).stream().map(n -> n.path("children").size()).toList());
        assertEquals(6, call("GET", "/catalog/families", null, null, 200).path("data").size());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM account WHERE role='ADMIN'", Integer.class));
    }

    @Test
    void signupStoresBcryptAndSessionDigestAndRejectsDuplicateAndExtraRole() throws Exception {
        User buyer = buyer();
        String hash = db.queryForObject("SELECT password_hash FROM account WHERE id=?", String.class, buyer.id());
        assertTrue(hash.startsWith("$2a$12$")); assertNotEquals(PASSWORD, hash);
        String tokenHash = db.queryForObject("SELECT token_hash FROM auth_session WHERE account_id=?", String.class, buyer.id());
        assertEquals(AuthService.hash(buyer.token()), tokenHash); assertNotEquals(buyer.token(), tokenHash);
        call("POST", "/auth/register", null, Map.of("phone", buyer.phone(), "password", PASSWORD, "displayName", "重复"), 409);
        call("POST", "/auth/register", null, Map.of("phone", phone(), "password", PASSWORD, "displayName", "越权", "role", "ADMIN"), 400);
        String view = call("GET", "/auth/me", buyer.token(), null, 200).toString();
        assertFalse(view.contains("password")); assertFalse(view.contains("token_hash"));
    }

    @Test
    void backendEnforcesGuestBuyerAndAdminRolesAndRejectsOldPaymentHole() throws Exception {
        User buyer = buyer();
        call("GET", "/admin/products", null, null, 401);
        call("GET", "/admin/products", buyer.token(), null, 403);
        call("POST", "/admin/products", buyer.token(), input(101, 1L, 0), 403);
        call("GET", "/addresses", admin, null, 403);
        call("GET", "/auth/me", "fake", null, 401);
        call("POST", "/auth/admin-login", null, Map.of("loginName", buyer.phone(), "password", PASSWORD), 401);
        call("POST", "/auth/login", null, Map.of("loginName", "integration-admin", "password", PASSWORD), 401);
        call("GET", "/paySuccess?orderNo=anything", null, null, 401);
        HttpRequest cookie = HttpRequest.newBuilder(uri("/auth/me?token=" + buyer.token()))
            .header("Cookie", "token=" + buyer.token()).build();
        assertEquals(401, client.send(cookie, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void buyerAddressesAreIsolatedAndDefaultIsAtomic() throws Exception {
        User a = buyer(); User b = buyer();
        long id = call("POST", "/addresses", a.token(), address("A", false), 201).path("data").path("id").asLong();
        assertEquals(0, call("GET", "/addresses", b.token(), null, 200).path("data").size());
        call("PUT", "/addresses/" + id, b.token(), address("篡改", true), 404);
        call("DELETE", "/addresses/" + id, b.token(), null, 404);
        var first = CompletableFuture.supplyAsync(() -> unchecked("POST", "/addresses", a.token(), address("B", true), 201));
        var second = CompletableFuture.supplyAsync(() -> unchecked("POST", "/addresses", a.token(), address("C", true), 201));
        CompletableFuture.allOf(first, second).join();
        List<JsonNode> rows = iterable(call("GET", "/addresses", a.token(), null, 200).path("data"));
        assertEquals(3, rows.size()); assertEquals(1, rows.stream().filter(n -> n.path("isDefault").asBoolean()).count());
        long current = rows.stream().filter(n -> n.path("isDefault").asBoolean()).findFirst().orElseThrow().path("id").asLong();
        call("DELETE", "/addresses/" + current, a.token(), null, 200);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM user_address WHERE account_id=? AND is_default=TRUE", Integer.class, a.id()));
        assertEquals("A", db.queryForObject("SELECT receiver FROM user_address WHERE id=?", String.class, id));
    }

    @Test
    void logoutExpiryAndAccountDisableInvalidateSessionsWithoutRevival() throws Exception {
        User a = buyer();
        call("POST", "/auth/logout", a.token(), null, 200);
        call("GET", "/auth/me", a.token(), null, 401);
        User b = buyer();
        db.update("UPDATE auth_session SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE account_id=?", b.id());
        call("GET", "/auth/me", b.token(), null, 401);
        User c = buyer();
        call("PATCH", "/admin/accounts/" + c.id(), admin, Map.of("enabled", false), 200);
        call("GET", "/auth/me", c.token(), null, 401);
        call("PATCH", "/admin/accounts/" + c.id(), admin, Map.of("enabled", true), 200);
        call("GET", "/auth/me", c.token(), null, 401);
        long adminId = call("GET", "/auth/me", admin, null, 200).path("data").path("id").asLong();
        call("PATCH", "/admin/accounts/" + adminId, admin, Map.of("enabled", false), 404);
    }

    @Test
    void scentToolRefillAndGiftFieldsAreValidatedAndCentsAreExact() throws Exception {
        var scent = input(101, 1L, 10);
        long id = create(scent);
        JsonNode product = call("GET", "/catalog/products/" + id, null, null, 200).path("data").path("product");
        assertEquals(12900, product.path("priceCents").asLong()); assertEquals("SCENT", product.path("inputView").asString());
        var bad = input(101, null, 0); call("POST", "/admin/products", admin, bad, 400);
        var tool = input(401, null, 2); long toolId = create(tool);
        assertTrue(call("GET", "/catalog/products/" + toolId, null, null, 200).path("data").path("product").path("familyId").isNull());
        var refill = input(501, 2L, 3); refill.put("compatibleIds", List.of(toolId)); long refillId = create(refill);
        assertEquals(1, call("GET", "/catalog/products/" + refillId, null, null, 200).path("data").path("compatibleProducts").size());
        assertEquals(1, call("GET", "/catalog/products/" + toolId, null, null, 200).path("data").path("compatibleProducts").size());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM product_compatibility WHERE product_id=? AND related_product_id=?", Integer.class, Math.min(toolId, refillId), Math.max(toolId, refillId)));
        var gift = input(504, null, 5); gift.put("contents", "固定预装蜡烛与藤条各一件，独立整盒库存"); create(gift);
        gift.put("contents", ""); call("POST", "/admin/products", admin, gift, 400);
        var root = input(1, 1L, 0); call("POST", "/admin/products", admin, root, 400);
    }

    @Test
    void loginRacingWithDisableCannotReviveARevokedSession() throws Exception {
        User user = buyer();
        var login = CompletableFuture.supplyAsync(() -> {
            try { return send("POST", "/auth/login", null, Map.of("loginName", user.phone(), "password", PASSWORD)); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        });
        var disable = CompletableFuture.supplyAsync(() -> unchecked("PATCH", "/admin/accounts/" + user.id(), admin, Map.of("enabled", false), 200));
        var response = login.join(); disable.join();
        assertTrue(response.statusCode() == 200 || response.statusCode() == 401);
        call("PATCH", "/admin/accounts/" + user.id(), admin, Map.of("enabled", true), 200);
        call("GET", "/auth/me", user.token(), null, 401);
        if (response.statusCode() == 200) {
            String lateToken = json.readTree(response.body()).path("data").path("token").asString();
            call("GET", "/auth/me", lateToken, null, 401);
        }
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id=?", Integer.class, user.id()));
    }

    @Test
    void hiddenSoldOutAndDisabledCategoriesHaveCorrectVisibility() throws Exception {
        var input = input(202, 1L, 0); long id = create(input);
        assertEquals(0, call("GET", "/catalog/products/" + id, null, null, 200).path("data").path("product").path("stock").asInt());
        var update = edit(input, 0); update.put("active", false);
        call("PUT", "/admin/products/" + id, admin, update, 200);
        call("GET", "/catalog/products/" + id, null, null, 404);
        call("GET", "/admin/products/" + id, admin, null, 200);
        // Use a leaf not shared with other tests, restore in finally.
        var another = input(203, 2L, 1); long anotherId = create(another);
        try {
            call("PATCH", "/admin/categories/203", admin, Map.of("name", "固体香氛", "sort", 3, "enabled", false), 200);
            call("GET", "/catalog/products/" + anotherId, null, null, 404);
        } finally {
            call("PATCH", "/admin/categories/203", admin, Map.of("name", "固体香氛", "sort", 3, "enabled", true), 200);
        }
    }

    @Test
    void staleProductOrStockFormsCannotOverwriteConcurrentChanges() throws Exception {
        var input = input(101, 1L, 10); long id = create(input);
        var first = CompletableFuture.supplyAsync(() -> rawStatus("POST", "/admin/products/" + id + "/stock", admin, Map.of("version", 0, "delta", -1, "reason", "并发验证")));
        var second = CompletableFuture.supplyAsync(() -> rawStatus("POST", "/admin/products/" + id + "/stock", admin, Map.of("version", 0, "delta", -1, "reason", "并发验证")));
        List<Integer> statuses = new ArrayList<>(List.of(first.join(), second.join())); Collections.sort(statuses);
        assertEquals(List.of(200,409), statuses);
        var current = call("GET", "/admin/products/" + id, admin, null, 200).path("data").path("product");
        assertEquals(9, current.path("stock").asInt()); assertEquals(1, current.path("version").asLong());
        call("PUT", "/admin/products/" + id, admin, edit(input, 0), 409);
        var correct = edit(input, 1); correct.put("priceCents", 9900);
        call("PUT", "/admin/products/" + id, admin, correct, 200);
        assertEquals(9, db.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, id));
        correct.put("initialStock", 999);
        call("PUT", "/admin/products/" + id, admin, correct, 400);
        call("POST", "/admin/products/" + id + "/stock", admin, Map.of("version", 2, "delta", -10, "reason", "不得负库存"), 400);
    }

    @Test
    void compatibilityFailureRollsBackProductCreationAndLeavesNoHalfAudit() throws Exception {
        var input = input(501, 1L, 2); input.put("compatibleIds", List.of(Long.MAX_VALUE));
        long before = db.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class);
        call("POST", "/admin/products", admin, input, 400);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM product WHERE name=?", Integer.class, input.get("name")));
        assertEquals(before, db.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class));
    }

    @Test
    void publicQueryBindsSqlEscapesWildcardsAndBoundsResources() throws Exception {
        var input = input(301, 1L, 3); String name = "SQL_50%" + UUID.randomUUID(); input.put("name", name); create(input);
        var exact = call("GET", "/catalog/products?keyword=" + URLEncoder.encode(name, StandardCharsets.UTF_8), null, null, 200).path("data");
        assertEquals(1, exact.path("total").asInt());
        assertEquals(0, call("GET", "/catalog/products?keyword=%27%20OR%201%3D1--", null, null, 200).path("data").path("total").asInt());
        call("GET", "/catalog/products?pageSize=100000", null, null, 400);
        call("GET", "/catalog/products?page=0", null, null, 400);
        call("GET", "/catalog/products?sort=price_cents;drop", null, null, 400);
        call("GET", "/catalog/products/not-a-number", null, null, 400);
    }

    @Test
    void failedLoginIsLimitedAndAuditsExcludeSecrets() throws Exception {
        String name = "missing-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 10; i++) { call("POST", "/auth/login", null, Map.of("loginName", name, "password", PASSWORD), 401); }
        call("POST", "/auth/login", null, Map.of("loginName", name, "password", PASSWORD), 429);
        String audits = call("GET", "/admin/audits", admin, null, 200).toString();
        assertFalse(audits.contains(PASSWORD)); assertFalse(audits.contains(admin)); assertFalse(audits.contains("password_hash"));
    }

    @Test
    void openApiHealthAndTraceAreUsableWithoutSecretDetails() throws Exception {
        var health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode()); assertEquals("UP", json.readTree(health.body()).path("status").asString());
        assertFalse(health.body().contains("components"));
        var spec = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, spec.statusCode()); assertTrue(json.readTree(spec.body()).path("paths").has("/api/v1/admin/products"));
        var response = send("GET", "/catalog/categories", null, null);
        String trace = json.readTree(response.body()).path("traceId").asString();
        assertEquals(trace, response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertDoesNotThrow(() -> UUID.fromString(trace));
    }

}
