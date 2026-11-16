package io.scent.settlement;

import io.scent.order.Order;
import java.time.Instant;
import java.util.Map;

/** The only external-money boundary. Production has one sandbox implementation; test doubles stay in tests. */
public interface PaymentGateway {
    enum State { NOT_FOUND, PENDING, PAID, CLOSED }
    enum RefundState { NOT_FOUND, PENDING, FAILED, SUCCESS }
    record Identity(String appId, String sellerId) { }
    record Receipt(State state, String orderNo, String tradeNo, String appId, String sellerId,
                   Long amountCents, Instant successAt) { }
    record RefundReceipt(RefundState state, String requestNo, String tradeNo, Long amountCents,
                         Instant successAt, String errorCode) { }
    boolean configured();
    Identity identity();
    String checkout(Order order);
    Receipt query(String orderNo);
    State close(String orderNo);
    Receipt notification(Map<String,String> parameters);
    RefundReceipt refund(String orderNo, String requestNo, long cents);
    RefundReceipt queryRefund(String orderNo, String requestNo);
}
