package io.scent.settlement;

import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.sun.net.httpserver.HttpServer;
import io.scent.common.BusinessException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + official SDK signing/response verification against a loopback-only bank fixture. Not Alipay sandbox. */
class AlipayGatewayTest {
    @Test
    void configuredKeyFilesAreValidatedAndPartialSetupFailsClosed(@TempDir Path directory) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var keys = generator.generateKeyPair();
        Path privateFile = directory.resolve("app.key"), publicFile = directory.resolve("bank.key");
        Files.writeString(privateFile, Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
        Files.writeString(publicFile, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        ObjectMapper json = JsonMapper.builder().build();
        assertTrue(new AlipayGateway(json, "20261015000001", "208800000001", privateFile.toString(), publicFile.toString(), "http://127.0.0.1:15183", "").configured());
        assertFalse(new AlipayGateway(json, "", "", "", "", "http://127.0.0.1:15183", "").configured());
        assertThrows(IllegalArgumentException.class, () -> new AlipayGateway(json, "20261015000001", "", "", "", "http://127.0.0.1:15183", ""));
        Files.writeString(privateFile, "not-a-key");
        assertThrows(IllegalArgumentException.class, () -> new AlipayGateway(json, "20261015000001", "208800000001", privateFile.toString(), publicFile.toString(), "http://127.0.0.1:15183", ""));
    }

    @Test
    void officialTransportQueriesClosesAndRefundsWithVerifiedResponses() throws Exception {
        ObjectMapper json = JsonMapper.builder().build();
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var app = generator.generateKeyPair(); var bank = generator.generateKeyPair();
        String appPrivate = Base64.getEncoder().encodeToString(app.getPrivate().getEncoded());
        String appPublic = Base64.getEncoder().encodeToString(app.getPublic().getEncoded());
        String bankPrivate = Base64.getEncoder().encodeToString(bank.getPrivate().getEncoded());
        String bankPublic = Base64.getEncoder().encodeToString(bank.getPublic().getEncoded());
        var failure = new AtomicReference<Throwable>(); var calls = new AtomicInteger();
        var badSignature = new AtomicBoolean(); var foundRefund = new AtomicBoolean(); var refundShape = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gateway.do", exchange -> {
            try {
                String encoded = Objects.toString(exchange.getRequestURI().getRawQuery(), "") + "&"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String,String> params = decode(encoded);
                String signature = params.remove("sign");
                assertTrue(AlipaySignature.rsaCheck(AlipaySignature.getSignContent(params), signature, appPublic, "UTF-8", "RSA2"));
                assertEquals("20261015000001", params.get("app_id"));
                var input = json.readTree(params.get("biz_content"));
                assertEquals("scent-http-fixture", input.path("out_trade_no").asString());
                String method = params.get("method");
                Map<String,Object> result = new LinkedHashMap<>();
                result.put("code", "10000"); result.put("msg", "Success");
                result.put("out_trade_no", "scent-http-fixture"); result.put("trade_no", "BANK202610150000001");
                switch (method) {
                    case "alipay.trade.query" -> {
                        result.put("trade_status", "TRADE_SUCCESS"); result.put("total_amount", "129.00");
                        result.put("send_pay_date", "2026-10-15 00:01:00");
                    }
                    case "alipay.trade.close" -> { }
                    case "alipay.trade.fastpay.refund.query" -> {
                        result.put("out_request_no", input.path("out_request_no").asString());
                        if (!foundRefund.get()) { result.put("code", "40004"); result.put("sub_code", "ACQ.REFUND_NOT_EXIST"); }
                        else { result.put("refund_status", "REFUND_SUCCESS"); result.put("refund_amount", "129.00"); result.put("gmt_refund_pay", "2026-10-16 00:02:00"); }
                        if (refundShape.get() == 1) { result.clear(); result.put("code", "10000"); result.put("msg", "Success"); }
                        if (refundShape.get() == 2) { result.remove("refund_status"); }
                        if (refundShape.get() == 3) { result.put("out_request_no", "R-wrong"); }
                        if (refundShape.get() == 4) { result.remove("gmt_refund_pay"); }
                    }
                    case "alipay.trade.refund" -> {
                        assertEquals("129.00", input.path("refund_amount").asString());
                        result.put("fund_change", "Y"); result.put("refund_fee", "129.00");
                        result.put("gmt_refund_pay", "2026-10-16 00:02:00"); foundRefund.set(true);
                    }
                    default -> throw new AssertionError("Unexpected bank method");
                }
                calls.incrementAndGet();
                String payload = json.writeValueAsString(result);
                String signed = badSignature.get() ? Base64.getEncoder().encodeToString(new byte[256]) : AlipaySignature.rsaSign(payload, bankPrivate, "UTF-8", "RSA2");
                byte[] bytes = ("{\"" + method.replace('.', '_') + "_response\":" + payload + ",\"sign\":\"" + signed + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable ex) { failure.set(ex); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var sdk = new DefaultAlipayClient("http://127.0.0.1:" + server.getAddress().getPort() + "/gateway.do", "20261015000001", appPrivate, "json", "UTF-8", bankPublic, "RSA2");
            var gateway = new AlipayGateway(json, sdk, new PaymentGateway.Identity("20261015000001", "208800000001"), bankPublic);
            var paid = gateway.query("scent-http-fixture");
            assertEquals(PaymentGateway.State.PAID, paid.state()); assertEquals(12900, paid.amountCents());
            assertEquals("2026-10-14T16:01:00Z", paid.successAt().toString());
            assertEquals(PaymentGateway.State.CLOSED, gateway.close("scent-http-fixture"));
            assertEquals(PaymentGateway.RefundState.NOT_FOUND, gateway.queryRefund("scent-http-fixture", "R-fixed").state());
            var refunded = gateway.refund("scent-http-fixture", "R-fixed", 12900);
            assertEquals(PaymentGateway.RefundState.SUCCESS, refunded.state()); assertEquals(12900, refunded.amountCents());
            assertEquals("2026-10-15T16:02:00Z", refunded.successAt().toString());
            assertEquals(PaymentGateway.RefundState.SUCCESS, gateway.queryRefund("scent-http-fixture", "R-fixed").state());
            refundShape.set(1);
            var empty = gateway.queryRefund("scent-http-fixture", "R-fixed");
            assertEquals(PaymentGateway.RefundState.FAILED, empty.state());
            assertEquals("REFUND_NOT_CONFIRMED", empty.errorCode()); assertNull(empty.amountCents());
            refundShape.set(2);
            assertEquals(PaymentGateway.RefundState.FAILED, gateway.queryRefund("scent-http-fixture", "R-fixed").state());
            refundShape.set(3);
            assertEquals("REFUND_CHECK_PENDING", assertThrows(BusinessException.class, () -> gateway.queryRefund("scent-http-fixture", "R-fixed")).code());
            refundShape.set(4);
            assertEquals("REFUND_CHECK_PENDING", assertThrows(BusinessException.class, () -> gateway.queryRefund("scent-http-fixture", "R-fixed")).code());
            refundShape.set(0);
            badSignature.set(true);
            assertEquals(503, assertThrows(BusinessException.class, () -> gateway.query("scent-http-fixture")).status());
            var error = assertThrows(BusinessException.class, () -> gateway.queryRefund("scent-http-fixture", "R-fixed"));
            assertEquals("REFUND_CHECK_PENDING", error.code()); assertFalse(error.getMessage().contains("付款"));
            assertNull(failure.get(), "Loopback bank assertions failed"); assertEquals(11, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void moneyAndProviderDatesRejectLossyInputAndPreserveShanghaiBoundary() {
        assertEquals(1, AlipayGateway.cents("0.01")); assertEquals(12900, AlipayGateway.cents("129.00"));
        for (String value : List.of("1.001", "0", "-1.00", "1e2", "NaN", "999999999.99")) { assertThrows(BusinessException.class, () -> AlipayGateway.cents(value)); }
        assertEquals("2026-10-14T16:00:00Z", AlipayGateway.date("2026-10-15 00:00:00").toString());
        assertThrows(BusinessException.class, () -> AlipayGateway.date("2026-02-30 12:00:00"));
    }

    private static Map<String,String> decode(String encoded) {
        Map<String,String> result = new HashMap<>();
        for (String pair : encoded.split("&")) {
            if (pair.isBlank()) { continue; }
            String[] p = pair.split("=", 2);
            result.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }
}
