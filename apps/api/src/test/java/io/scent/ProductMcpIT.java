package io.scent;

import io.scent.account.AttemptLimiter;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + MySQL: the model has two bounded public reads, never arbitrary SQL or account access. */
class ProductMcpIT extends ApiIntegrationSupport {
    private static final String KEY = "mcp-integration-only-" + "a".repeat(43);
    private static final Map<String, String> HEADERS = Map.of("Accept", "application/json, text/event-stream", "MCP-Protocol-Version", "2025-11-25");
    private static final Path KEY_FILE = keyFile();
    @Autowired AttemptLimiter limiter;
    @DynamicPropertySource static void mcpConfiguration(DynamicPropertyRegistry properties) {
        properties.add("scent.mcp.token-file", () -> KEY_FILE.toString());
    }
    private static Path keyFile() {
        try { Path file = Files.createTempFile("scent-mcp-it-", ".key"); Files.writeString(file, KEY); file.toFile().deleteOnExit(); return file; }
        catch (Exception ex) { throw new IllegalStateException("Cannot create isolated test credential", ex); }
    }
    @BeforeEach void clearRate() { limiter.clear("mcp:127.0.0.1"); }
    private JsonNode rpc(String method, Map<String, ?> params) throws Exception {
        return raw(Map.of("jsonrpc", "2.0", "id", "test-1", "method", method, "params", params));
    }
    private JsonNode raw(Object body) throws Exception {
        var response = send("POST", "/mcp/products", KEY, body, HEADERS);
        assertEquals(200, response.statusCode(), "MCP HTTP contract");
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return json.readTree(response.body());
    }
    private JsonNode tool(String name, Map<String, ?> arguments) throws Exception {
        JsonNode reply = rpc("tools/call", Map.of("name", name, "arguments", arguments));
        assertFalse(reply.has("error"), reply.toString());
        return reply.path("result");
    }
    private JsonNode toolData(String name, Map<String, ?> arguments) throws Exception {
        JsonNode result = tool(name, arguments);
        assertFalse(result.path("isError").asBoolean());
        assertFalse(result.has("structuredContent"), "Do not duplicate catalog facts in model context");
        assertEquals(1, result.path("content").size());
        JsonNode block = result.path("content").get(0);
        assertEquals("text", block.path("type").asString());
        return json.readTree(block.path("text").asString());
    }
    private Map<String, Object> product(String tag, int price, int stock) {
        var body = input(103, 4L, stock);
        body.put("name", "MCP-" + tag); body.put("priceCents", price);
        body.put("description", "气味：清酸柑橘。甜度低，浓淡中等。适合少甜偏好；不适合讨厌柑橘的人。" + tag);
        return body;
    }

    @Test void negotiatesVersionsAndAcceptsNotificationWithoutInventingSessions() throws Exception {
        var params = new HashMap<String, Object>(Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "integration", "version", "1")));
        assertEquals("2025-11-25", rpc("initialize", params).path("result").path("protocolVersion").asString());
        params.put("protocolVersion", "2025-06-18");
        assertEquals("2025-06-18", rpc("initialize", params).path("result").path("protocolVersion").asString());
        params.put("protocolVersion", "2099-01-01");
        assertEquals("2025-11-25", rpc("initialize", params).path("result").path("protocolVersion").asString());
        var response = send("POST", "/mcp/products", KEY, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), HEADERS);
        assertEquals(202, response.statusCode()); assertTrue(response.body().isEmpty());
        assertTrue(response.headers().firstValue("MCP-Session-Id").isEmpty());
        assertTrue(rpc("ping", Map.of()).path("result").isObject());
    }

    @Test void advertisesExactlyTwoReadonlyToolsAndCurrentFragranceVocabulary() throws Exception {
        JsonNode tools = rpc("tools/list", Map.of()).path("result").path("tools");
        assertEquals(List.of("search_products", "get_product_detail"), iterable(tools).stream().map(t -> t.path("name").asString()).toList());
        for (JsonNode entry : tools) {
            assertFalse(entry.has("outputSchema"), "Text-only MCP results must not advertise a structured output schema");
            assertTrue(entry.path("annotations").path("readOnlyHint").asBoolean());
            assertFalse(entry.path("annotations").path("destructiveHint").asBoolean());
            assertFalse(entry.path("inputSchema").path("additionalProperties").asBoolean());
        }
        assertEquals(6, tools.get(0).path("inputSchema").path("properties").path("family").path("enum").size());
    }

    @Test void searchesDescriptionWithInclusiveCentsBudgetAndReturnsOnlyPublicFields() throws Exception {
        String tag = UUID.randomUUID().toString();
        var input = product(tag, 8950, 9); input.put("name", "描述检索-" + UUID.randomUUID()); long id = create(input);
        JsonNode data = toolData("search_products", Map.of("keyword", tag, "family", "CITRUS", "minPriceCents", 8950, "maxPriceCents", 8950));
        assertEquals(1, data.path("total").asInt());
        JsonNode item = data.path("items").get(0);
        assertEquals(id, item.path("id").asLong()); assertEquals(8950, item.path("priceCents").asInt());
        assertEquals("CNY", item.path("currency").asString()); assertTrue(item.path("inStock").asBoolean());
        assertFalse(item.has("stock")); assertFalse(item.has("version")); assertFalse(item.has("active"));
        assertEquals("/products/" + id, item.path("productPath").asString());
        assertEquals(0, toolData("search_products", Map.of("keyword", tag, "maxPriceCents", 8949)).path("total").asInt());
        assertEquals(0, toolData("search_products", Map.of("keyword", tag, "family", "WOODY")).path("total").asInt());
    }

    @Test void filtersHiddenSoldOutAndDisabledCategoriesWithoutLeakingTheirDetails() throws Exception {
        String tag = UUID.randomUUID().toString();
        long visible = create(product(tag, 1000, 1));
        long soldOut = create(product(tag, 2000, 0));
        var hiddenBody = product(tag, 3000, 1); hiddenBody.put("active", false); long hidden = create(hiddenBody);
        assertEquals(1, toolData("search_products", Map.of("keyword", tag)).path("total").asInt());
        assertFalse(toolData("get_product_detail", Map.of("productId", soldOut)).path("product").path("inStock").asBoolean());
        assertTrue(tool("get_product_detail", Map.of("productId", hidden)).path("isError").asBoolean());
        try {
            db.update("UPDATE category SET enabled=FALSE WHERE id=103");
            assertEquals(0, toolData("search_products", Map.of("keyword", tag)).path("total").asInt());
            assertTrue(tool("get_product_detail", Map.of("productId", visible)).path("isError").asBoolean());
        } finally { db.update("UPDATE category SET enabled=TRUE WHERE id=103"); }
        try {
            db.update("UPDATE category SET enabled=FALSE WHERE id=1");
            assertEquals(0, toolData("search_products", Map.of("keyword", tag)).path("total").asInt());
        } finally { db.update("UPDATE category SET enabled=TRUE WHERE id=1"); }
    }

    @Test void pagesBoundedCandidatesAndKeepsPriceOrderingStable() throws Exception {
        String tag = UUID.randomUUID().toString();
        long first = create(product(tag, 1000, 2)); long second = create(product(tag, 2000, 2)); create(product(tag, 3000, 2));
        JsonNode a = toolData("search_products", Map.of("keyword", tag, "page", 1, "limit", 1));
        JsonNode b = toolData("search_products", Map.of("keyword", tag, "page", 2, "limit", 1));
        assertEquals(3, a.path("total").asInt()); assertEquals(first, a.path("items").get(0).path("id").asLong());
        assertTrue(a.path("hasMore").asBoolean()); assertEquals(second, b.path("items").get(0).path("id").asLong());
        assertFalse(toolData("search_products", Map.of("keyword", tag, "page", 3, "limit", 1)).path("hasMore").asBoolean());
    }

    @Test void adminChangesAreVisibleOnNextReadAndMcpDoesNotChangeStockOrVersion() throws Exception {
        String tag = UUID.randomUUID().toString(); var body = product(tag, 10000, 2); long id = create(body);
        JsonNode before = toolData("get_product_detail", Map.of("productId", id)).path("product");
        assertEquals(10000, before.path("priceCents").asInt());
        var update = edit(body, 0); update.put("priceCents", 8800); update.put("description", "编辑后的低甜柑橘气味说明。" + tag);
        call("PUT", "/admin/products/" + id, admin, update, 200);
        long version = db.queryForObject("SELECT version FROM product WHERE id=?", Long.class, id);
        JsonNode after = toolData("get_product_detail", Map.of("productId", id)).path("product");
        assertEquals(8800, after.path("priceCents").asInt()); assertTrue(after.path("description").asString().startsWith("编辑后"));
        tool("search_products", Map.of("keyword", tag));
        assertEquals(version, db.queryForObject("SELECT version FROM product WHERE id=?", Long.class, id));
        assertEquals(2, db.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, id));
    }

    @Test void exposesOnlyExplicitVisibleCompatibilityAndSupportsUnscentedTools() throws Exception {
        var scent = product(UUID.randomUUID().toString(), 1000, 2); long scentId = create(scent);
        long toolId = create(input(401, null, 1));
        var update = edit(scent, 0); update.put("compatibleIds", List.of(toolId));
        call("PUT", "/admin/products/" + scentId, admin, update, 200);
        JsonNode detail = toolData("get_product_detail", Map.of("productId", scentId));
        assertEquals(toolId, detail.path("compatibleProducts").get(0).path("id").asLong());
        assertEquals("无香", toolData("get_product_detail", Map.of("productId", toolId)).path("product").path("family").asString());
        db.update("UPDATE product SET active=FALSE WHERE id=?", toolId);
        assertTrue(toolData("get_product_detail", Map.of("productId", scentId)).path("compatibleProducts").isEmpty());
    }

    @Test void truncatesOnlySearchSummariesAtUnicodeBoundariesAndKeepsFullDetail() throws Exception {
        String tag = UUID.randomUUID().toString(); var body = product(tag, 1000, 1);
        String description = tag + "🌿".repeat(700) + "。不适合甜香偏好。";
        body.put("description", description); long id = create(body);
        JsonNode summary = toolData("search_products", Map.of("keyword", tag)).path("items").get(0);
        assertTrue(summary.path("descriptionTruncated").asBoolean());
        String shortText = summary.path("description").asString();
        assertEquals(600, shortText.codePointCount(0, shortText.length()));
        assertEquals(description, toolData("get_product_detail", Map.of("productId", id)).path("product").path("description").asString());
    }

    @Test void rejectsArbitrarySqlUnknownToolsAndInvalidArgumentTypes() throws Exception {
        for (Map<String, ?> args : List.<Map<String, ?>>of(Map.of("sql", "SELECT * FROM account"), Map.of("admin", true),
                Map.of("limit", 11), Map.of("page", 101), Map.of("categoryId", 0), Map.of("family", "UNKNOWN"),
                Map.of("maxPriceCents", 10.5), Map.of("minPriceCents", 100, "maxPriceCents", 99),
                Map.of("keyword", "x".repeat(101)), Map.of("maxPriceCents", -1))) {
            assertEquals(-32602, rpc("tools/call", Map.of("name", "search_products", "arguments", args)).path("error").path("code").asInt());
        }
        assertEquals(-32602, rpc("tools/call", Map.of("name", "execute_sql", "arguments", Map.of())).path("error").path("code").asInt());
        assertEquals(-32602, rpc("tools/call", Map.of("name", "get_product_detail", "arguments", Map.of())).path("error").path("code").asInt());
        assertEquals(-32601, rpc("orders/list", Map.of()).path("error").path("code").asInt());
    }

    @Test void escapesSqlLikeWildcardsAndTreatsInjectedTextAsData() throws Exception {
        String tag = UUID.randomUUID().toString(); var body = product(tag, 1000, 1);
        body.put("description", tag + "-100%_!清爽"); create(body);
        assertEquals(1, toolData("search_products", Map.of("keyword", tag + "-100%_!")).path("total").asInt());
        assertEquals(0, toolData("search_products", Map.of("keyword", "%' OR 1=1 --")).path("total").asInt());
    }

    @Test void isolatesMachineCredentialFromBuyerAndAdministratorTokens() throws Exception {
        Object request = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");
        for (String credential : Arrays.asList(null, "wrong", admin, buyer().token())) {
            var response = send("POST", "/mcp/products", credential, request, HEADERS);
            assertEquals(401, response.statusCode()); assertFalse(response.body().contains(KEY));
        }
        call("GET", "/admin/products", KEY, null, 401);
        call("GET", "/orders", KEY, null, 401);
    }

    @Test void enforcesTransportOriginProtocolHeadersSizeAndUnsupportedGet() throws Exception {
        Object request = Map.of("jsonrpc", "2.0", "id", 1, "method", "ping");
        assertEquals(405, send("GET", "/mcp/products", KEY, null, HEADERS).statusCode());
        assertEquals(406, send("POST", "/mcp/products", KEY, request).statusCode());
        assertEquals(400, send("POST", "/mcp/products", KEY, request, Map.of("Accept", "application/json, text/event-stream", "MCP-Protocol-Version", "bad")).statusCode());
        var headers = new HashMap<>(HEADERS); headers.put("Origin", "https://untrusted.example");
        assertEquals(403, send("POST", "/mcp/products", KEY, request, headers).statusCode());
        assertEquals(413, send("POST", "/mcp/products", KEY, Map.of("padding", "x".repeat(16385)), HEADERS).statusCode());
        var malformed = HttpRequest.newBuilder(uri("/mcp/products")).header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer " + KEY)
            .POST(HttpRequest.BodyPublishers.ofString("{", StandardCharsets.UTF_8)).build();
        var response = client.send(malformed, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode()); assertEquals(-32700, json.readTree(response.body()).path("error").path("code").asInt());
        assertEquals(400, send("POST", "/mcp/products", KEY, List.of(request), HEADERS).statusCode());
    }

    @Test void boundsQueryRateWithoutTrustingForwardedClientAddress() throws Exception {
        for (int i = 0; i < 120; i++) { limiter.check("mcp:127.0.0.1", 120, 60); }
        var headers = new HashMap<>(HEADERS); headers.put("X-Forwarded-For", "192.0.2.99");
        assertEquals(429, send("POST", "/mcp/products", KEY, Map.of("jsonrpc", "2.0", "id", 1, "method", "ping"), headers).statusCode());
    }
}
