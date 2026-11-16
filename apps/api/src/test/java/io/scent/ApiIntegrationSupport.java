package io.scent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "scent.bootstrap.admin-login=integration-admin", "scent.bootstrap.admin-password=V1TestPass123!",
    "scent.security.auth-per-minute=1000", "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true",
    "scent.order.auto-close=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ApiIntegrationSupport {
    protected static final String PASSWORD = "V1TestPass123!";
    @Value("${local.server.port}") protected int port;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate db;
    protected final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    protected String admin;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        String url = IsolatedDatabase.require(System.getenv("SCENT_DB_URL"));
        properties.add("spring.datasource.url", () -> url);
    }

    @BeforeAll
    protected void setup() throws Exception {
        String name = db.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(name);
        assertTrue(name.endsWith("_it"), "Integration tests require a dedicated *_it database");
        admin = call("POST", "/auth/admin-login", null,
            Map.of("loginName", "integration-admin", "password", PASSWORD), 200).path("data").path("token").asString();
    }

    protected User buyer() throws Exception {
        String phone = phone();
        long id = call("POST", "/auth/register", null, Map.of("phone", phone, "password", PASSWORD, "displayName", "验证买家"), 201).path("data").path("id").asLong();
        String token = call("POST", "/auth/login", null, Map.of("loginName", phone, "password", PASSWORD), 200).path("data").path("token").asString();
        return new User(id, phone, token);
    }
    protected static String phone() { return "188" + String.format("%08d", ThreadLocalRandom.current().nextInt(100000000)); }
    protected static Map<String,Object> address(String name, boolean isDefault) {
        return Map.of("receiver", name, "phone", "18800000001", "region", "虚构省 虚构市", "detail", "测试地址一号", "isDefault", isDefault);
    }
    protected static Map<String,Object> input(long category, Long family, int stock) {
        Map<String,Object> body = new HashMap<>();
        body.put("categoryId", category); body.put("familyId", family); body.put("name", "验证-" + UUID.randomUUID());
        body.put("spec", "180g"); body.put("unit", "件"); body.put("priceCents", 12900); body.put("initialStock", stock);
        body.put("active", true); body.put("imagePath", "/assets/candle.png"); body.put("description", "虚构验证数据");
        body.put("usageNotes", "仅按说明使用"); body.put("material", "陶瓷 / 10cm");
        return body;
    }
    protected static Map<String,Object> edit(Map<String,Object> source, long version) {
        var result = new HashMap<>(source); result.remove("initialStock"); result.put("version", version); return result;
    }
    protected long create(Map<String,Object> body) throws Exception {
        return call("POST", "/admin/products", admin, body, 201).path("data").path("product").path("id").asLong();
    }
    protected URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/v1" + path); }
    protected HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        return send(method, path, token, body, Map.of());
    }
    protected HttpResponse<String> send(String method, String path, String token, Object body, Map<String,String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        headers.forEach(builder::header);
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    protected JsonNode call(String method, String path, String token, Object body, int expected) throws Exception {
        return call(method, path, token, body, expected, Map.of());
    }
    protected JsonNode call(String method, String path, String token, Object body, int expected, Map<String,String> headers) throws Exception {
        var response = send(method, path, token, body, headers);
        JsonNode result = json.readTree(response.body());
        assertEquals(expected, response.statusCode(), method + " " + path + " error=" + result.path("errorCode").asString());
        assertEquals(expected, result.path("resultCode").asInt());
        assertFalse(result.path("traceId").asString().isBlank());
        return result;
    }
    protected JsonNode unchecked(String method, String path, String token, Object body, int expected) {
        try { return call(method, path, token, body, expected); } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    protected int rawStatus(String method, String path, String token, Object body) {
        try { return send(method, path, token, body).statusCode(); } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    protected static List<JsonNode> iterable(JsonNode nodes) { var list = new ArrayList<JsonNode>(); nodes.forEach(list::add); return list; }
    protected record User(long id, String phone, String token) { }
}
