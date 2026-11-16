package io.scent.settlement;

import com.alipay.api.*;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.internal.util.AlipayLogger;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import io.scent.common.BusinessException;
import io.scent.order.Order;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AlipayGateway implements PaymentGateway {
    static final String GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final AlipayClient client;
    private final Identity identity;
    private final String publicKey;
    private final String publicOrigin;
    private final String notifyOrigin;
    private final ObjectMapper json;

    @Autowired
    public AlipayGateway(ObjectMapper json, @Value("${scent.payment.app-id:}") String appId,
            @Value("${scent.payment.seller-id:}") String sellerId,
            @Value("${scent.payment.private-key-file:}") String privateFile,
            @Value("${scent.payment.public-key-file:}") String publicFile,
            @Value("${scent.payment.public-origin:http://127.0.0.1:15183}") String publicOrigin,
            @Value("${scent.payment.notify-origin:}") String notifyOrigin) {
        AlipayLogger.setNeedEnableLogger(false);
        this.json = json;
        this.publicOrigin = origin(publicOrigin, true);
        this.notifyOrigin = notifyOrigin.isBlank() ? "" : origin(notifyOrigin, false);
        if (List.of(appId, sellerId, privateFile, publicFile).stream().allMatch(String::isBlank)) {
            client = null; identity = null; publicKey = null; return;
        }
        if (!appId.matches("[0-9]{8,32}") || !sellerId.matches("[0-9]{8,32}") || privateFile.isBlank() || publicFile.isBlank()) {
            throw new IllegalArgumentException("Complete the four SCENT_ALIPAY_* settings, or leave all unset.");
        }
        try {
            var config = new AlipayConfig();
            config.setServerUrl(GATEWAY); config.setAppId(appId); config.setFormat("json");
            config.setCharset("UTF-8"); config.setSignType("RSA2");
            String privateKey = key(privateFile); publicKey = key(publicFile);
            var factory = KeyFactory.getInstance("RSA");
            RSAKey signing = (RSAKey) factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
            RSAKey verifying = (RSAKey) factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            if (signing.getModulus().bitLength() < 2048 || verifying.getModulus().bitLength() < 2048) { throw new IllegalArgumentException(); }
            config.setPrivateKey(privateKey); config.setAlipayPublicKey(publicKey);
            config.setConnectTimeout(3000); config.setReadTimeout(4000);
            client = new DefaultAlipayClient(config); identity = new Identity(appId, sellerId);
        } catch (Exception ex) { throw new IllegalArgumentException("Payment key files or SDK configuration are invalid; secrets were not logged."); }
    }

    // Package-scoped transport injection for protocol tests; Spring always uses the configured constructor above.
    AlipayGateway(ObjectMapper json, AlipayClient client, Identity identity, String publicKey) {
        AlipayLogger.setNeedEnableLogger(false);
        this.json = json; this.client = client; this.identity = identity; this.publicKey = publicKey;
        this.publicOrigin = "http://127.0.0.1:15183"; this.notifyOrigin = "";
    }

    @Override public boolean configured() { return client != null; }
    @Override public Identity identity() { requireConfigured(); return identity; }

    @Override public String checkout(Order order) {
        requireConfigured();
        var model = new AlipayTradePagePayModel();
        model.setOutTradeNo(order.getOrderNo()); model.setTotalAmount(yuan(order.getTotalCents()));
        model.setSubject("SCENT / " + order.getOrderNo()); model.setProductCode("FAST_INSTANT_TRADE_PAY");
        model.setTimeExpire(DATE.format(order.getExpiresAt().atZone(ZONE)));
        var request = new AlipayTradePagePayRequest(); request.setBizModel(model);
        request.setReturnUrl(publicOrigin + "/payment/" + order.getId() + "?returned=1");
        if (!notifyOrigin.isEmpty()) { request.setNotifyUrl(notifyOrigin + "/api/v1/payments/alipay/notify"); }
        try {
            String url = client.pageExecute(request, "GET").getBody();
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"openapi-sandbox.dl.alipaydev.com".equals(uri.getHost()) || !"/gateway.do".equals(uri.getPath())) { throw unavailable(); }
            return url;
        } catch (AlipayApiException | IllegalArgumentException ex) { throw unavailable(); }
    }

    @Override public Receipt query(String orderNo) {
        requireConfigured(); var request = new AlipayTradeQueryRequest(); request.setBizContent(json.writeValueAsString(Map.of("out_trade_no", orderNo)));
        try {
            var response = client.execute(request);
            if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) { return absent(orderNo); }
            success(response);
            JsonNode body = body(response, "alipay_trade_query_response");
            return receipt(body.path("trade_status").asString(), body.path("out_trade_no").asString(), body.path("trade_no").asString(),
                identity.appId(), identity.sellerId(), body.path("total_amount").asString(), body.path("send_pay_date").asString());
        } catch (AlipayApiException ex) { throw unavailable(); }
    }

    @Override public State close(String orderNo) {
        requireConfigured(); var request = new AlipayTradeCloseRequest(); request.setBizContent(json.writeValueAsString(Map.of("out_trade_no", orderNo)));
        try {
            var response = client.execute(request);
            if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) { return State.NOT_FOUND; }
            if ("ACQ.TRADE_HAS_SUCCESS".equals(response.getSubCode())) { return State.PAID; }
            success(response);
            if (!orderNo.equals(body(response, "alipay_trade_close_response").path("out_trade_no").asString())) { throw unavailable(); }
            return State.CLOSED;
        } catch (AlipayApiException ex) { throw unavailable(); }
    }

    @Override public Receipt notification(Map<String,String> parameters) {
        requireConfigured();
        try {
            if (!"RSA2".equals(parameters.get("sign_type")) || !"UTF-8".equalsIgnoreCase(parameters.get("charset"))
                    || !AlipaySignature.rsaCheckV1(new HashMap<>(parameters), publicKey, "UTF-8", "RSA2")) {
                throw BusinessException.invalid("支付通知无法验证");
            }
        } catch (AlipayApiException | IllegalArgumentException ex) { throw BusinessException.invalid("支付通知无法验证"); }
        return receipt(parameters.get("trade_status"), parameters.get("out_trade_no"), parameters.get("trade_no"),
            parameters.get("app_id"), parameters.get("seller_id"), parameters.get("total_amount"), parameters.get("gmt_payment"));
    }

    @Override public RefundReceipt refund(String orderNo, String requestNo, long cents) {
        requireConfigured(); var request = new AlipayTradeRefundRequest();
        request.setBizContent(json.writeValueAsString(Map.of("out_trade_no", orderNo, "out_request_no", requestNo, "refund_amount", yuan(cents), "refund_reason", "SCENT整单售后")));
        try {
            var response = client.execute(request);
            if (!response.isSuccess()) { return new RefundReceipt(RefundState.FAILED, requestNo, null, null, null, safeCode(response.getSubCode())); }
            JsonNode body = body(response, "alipay_trade_refund_response");
            if (!orderNo.equals(body.path("out_trade_no").asString())) { throw refundUnavailable(); }
            if (!"Y".equals(body.path("fund_change").asString()) || body.path("gmt_refund_pay").asString().isBlank()) { return queryRefund(orderNo, requestNo); }
            return new RefundReceipt(RefundState.SUCCESS, requestNo, body.path("trade_no").asString(),
                cents(body.path("refund_fee").asString()), date(body.path("gmt_refund_pay").asString()), null);
        } catch (AlipayApiException | BusinessException ex) { throw refundUnavailable(); }
    }

    @Override public RefundReceipt queryRefund(String orderNo, String requestNo) {
        requireConfigured(); var request = new AlipayTradeFastpayRefundQueryRequest();
        request.setBizContent(json.writeValueAsString(Map.of("out_trade_no", orderNo, "out_request_no", requestNo, "query_options", List.of("gmt_refund_pay"))));
        try {
            var response = client.execute(request);
            if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode()) || "ACQ.REFUND_NOT_EXIST".equals(response.getSubCode())) { return new RefundReceipt(RefundState.NOT_FOUND, requestNo, null, null, null, null); }
            success(response); JsonNode body = body(response, "alipay_trade_fastpay_refund_query_response");
            // A verified successful query may contain only code/msg when no refund
            // is confirmed. Retry the same request number/amount, never mark it paid.
            if ((body.hasNonNull("out_trade_no") && !orderNo.equals(body.path("out_trade_no").asString()))
                    || (body.hasNonNull("out_request_no") && !requestNo.equals(body.path("out_request_no").asString()))) { throw refundUnavailable(); }
            if (!"REFUND_SUCCESS".equals(body.path("refund_status").asString())) {
                return new RefundReceipt(RefundState.FAILED, requestNo, null, null, null, "REFUND_NOT_CONFIRMED");
            }
            if (!orderNo.equals(body.path("out_trade_no").asString()) || !requestNo.equals(body.path("out_request_no").asString())) { throw refundUnavailable(); }
            return new RefundReceipt(RefundState.SUCCESS, requestNo, body.path("trade_no").asString(),
                cents(body.path("refund_amount").asString()), date(body.path("gmt_refund_pay").asString()), null);
        } catch (AlipayApiException | BusinessException ex) { throw refundUnavailable(); }
    }

    private Receipt receipt(String state, String orderNo, String tradeNo, String appId, String sellerId, String amount, String paidAt) {
        State mapped = switch (state == null ? "" : state) { case "TRADE_SUCCESS", "TRADE_FINISHED" -> State.PAID; case "TRADE_CLOSED" -> State.CLOSED; case "WAIT_BUYER_PAY" -> State.PENDING; default -> throw unavailable(); };
        return new Receipt(mapped, orderNo, tradeNo, appId, sellerId, mapped == State.PAID ? cents(amount) : null, mapped == State.PAID ? date(paidAt) : null);
    }
    private Receipt absent(String orderNo) { return new Receipt(State.NOT_FOUND, orderNo, null, identity.appId(), identity.sellerId(), null, null); }
    private JsonNode body(AlipayResponse response, String name) { return json.readTree(response.getBody()).path(name); }
    private static void success(AlipayResponse response) { if (!response.isSuccess()) { throw unavailable(); } }
    private void requireConfigured() { if (!configured()) { throw new BusinessException(503, "PAYMENT_UNAVAILABLE", "支付暂未开放，订单已保留"); } }
    static BusinessException unavailable() { return new BusinessException(503, "PAYMENT_CHECK_PENDING", "支付平台暂时无法核对，请稍后重试，不必重复付款"); }
    static BusinessException refundUnavailable() { return new BusinessException(503, "REFUND_CHECK_PENDING", "退款结果暂时无法确认，原退款请求已保留，请稍后刷新核对"); }
    static String safeCode(String value) { return value != null && value.matches("[A-Z0-9._]{1,64}") ? value : "GATEWAY_REJECTED"; }
    static long cents(String value) {
        if (value == null || !value.matches("[0-9]{1,9}(\\.[0-9]{1,2})?")) { throw unavailable(); }
        try { long cents = new BigDecimal(value).movePointRight(2).longValueExact(); if (cents < 1 || cents > 10000000000L) { throw unavailable(); } return cents; }
        catch (ArithmeticException ex) { throw unavailable(); }
    }
    static String yuan(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }
    static Instant date(String value) {
        try { return LocalDateTime.parse(value, DATE).atZone(ZONE).toInstant(); }
        catch (RuntimeException ex) { throw unavailable(); }
    }
    private static String key(String file) throws Exception {
        Path path = Path.of(file); if (!path.isAbsolute() || Files.size(path) > 16384) { throw new IllegalArgumentException(); }
        return Files.readString(path).replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
    }
    private static String origin(String value, boolean localAllowed) {
        URI uri = URI.create(value);
        boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
        if (!("https".equals(uri.getScheme()) || (localAllowed && loopback && "http".equals(uri.getScheme()))) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) { throw new IllegalArgumentException("Payment origins must be trusted HTTPS origins; local return may use loopback HTTP."); }
        return value.replaceAll("/$", "");
    }
}
