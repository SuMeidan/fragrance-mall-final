package io.scent;

import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.internal.util.AlipayLogger;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Compatibility probe only. Generated keys and local signing are not a sandbox transaction. */
class AlipaySdkTest {
    @Test
    void signedPageAndNotificationWorkWithModernTransitiveDependencies() throws Exception {
        AlipayLogger.setNeedEnableLogger(false);
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var keys = generator.generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        var sdk = new DefaultAlipayClient("https://openapi-sandbox.dl.alipaydev.com/gateway.do",
            "20261015000001", privateKey, "json", "UTF-8", publicKey, "RSA2");
        var model = new AlipayTradePagePayModel();
        model.setOutTradeNo("scent-compatibility-probe"); model.setTotalAmount("129.00");
        model.setSubject("SCENT test"); model.setProductCode("FAST_INSTANT_TRADE_PAY");
        model.setTimeExpire("2026-10-15 23:59:00");
        var request = new AlipayTradePagePayRequest(); request.setBizModel(model);
        var url = URI.create(sdk.pageExecute(request, "GET").getBody());
        assertEquals("openapi-sandbox.dl.alipaydev.com", url.getHost());
        var params = new HashMap<String,String>();
        for (String pair : url.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        assertTrue(params.get("biz_content").contains("129.00"));
        assertTrue(params.get("biz_content").contains("2026-10-15 23:59:00"));
        // Query signatures include sign_type; async notify verification deliberately excludes it.
        var unsigned = new HashMap<>(params); unsigned.remove("sign");
        assertTrue(AlipaySignature.rsaCheck(AlipaySignature.getSignContent(unsigned), params.get("sign"), publicKey, "UTF-8", "RSA2"));
        var notification = new HashMap<String,String>(Map.of("app_id", "20261015000001", "out_trade_no", "scent-test",
            "trade_status", "TRADE_SUCCESS", "total_amount", "129.00", "charset", "UTF-8"));
        notification.put("sign", AlipaySignature.rsaSign(AlipaySignature.getSignContent(notification), privateKey, "UTF-8", "RSA2"));
        notification.put("sign_type", "RSA2");
        assertTrue(AlipaySignature.rsaCheckV1(new HashMap<>(notification), publicKey, "UTF-8", "RSA2"));
        notification.put("total_amount", "0.01");
        assertFalse(AlipaySignature.rsaCheckV1(new HashMap<>(notification), publicKey, "UTF-8", "RSA2"));
    }
}
