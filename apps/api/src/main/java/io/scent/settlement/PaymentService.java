package io.scent.settlement;

import io.scent.common.*;
import io.scent.order.*;
import io.scent.settlement.PaymentGateway.*;
import io.scent.settlement.SettlementModels.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Network requests deliberately sit outside the short database transactions below. */
@Service
public class PaymentService {
    private final PaymentGateway gateway;
    private final OrderService orders;
    private final SettlementMapper mapper;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public PaymentService(PaymentGateway gateway, OrderService orders, SettlementMapper mapper,
                          AuditService audit, PlatformTransactionManager manager) {
        this.gateway = gateway; this.orders = orders; this.mapper = mapper; this.audit = audit;
        tx = new TransactionTemplate(manager); tx.setTimeout(10);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public boolean configured() { return gateway.configured(); }

    public PaymentLink begin(long owner, long id) {
        orders.detail(id, owner);
        Identity identity = gateway.identity(); // Missing configuration must not create an intent.
        Order row = tx.execute(status -> {
            Order locked = orders.locked(id, owner);
            if (locked.getStatus() != 0 || !locked.getExpiresAt().isAfter(Instant.now())) {
                throw BusinessException.conflict("STATE_CONFLICT", "订单当前无法付款，请查看最新状态");
            }
            Payment current = mapper.payment(id);
            if (current == null) {
                mapper.issue(id, identity.appId(), identity.sellerId());
                audit.write(owner, "PAYMENT_ISSUE", "ORDER", id, "Sandbox payment link requested");
            } else if (!"PENDING".equals(current.status())) {
                throw BusinessException.conflict("STATE_CONFLICT", "此付款已关闭或完成，请查看订单，必要时重新下单");
            } else if (!identity.appId().equals(current.appId()) || !identity.sellerId().equals(current.sellerId())) {
                throw BusinessException.conflict("PAYMENT_IDENTITY_CHANGED", "商户配置已变化，请由管理员核对原付款");
            }
            return locked;
        });
        return new PaymentLink(gateway.checkout(Objects.requireNonNull(row)));
    }

    public SettlementModels.Detail detail(long id, Long owner) {
        orders.detail(id, owner);
        AfterSale sale = mapper.saleForOrder(id);
        return new SettlementModels.Detail(mapper.payment(id), sale, sale == null ? null : mapper.refund(sale.getId()), mapper.orderReviews(id));
    }

    public SettlementModels.Detail synchronize(long id, Long owner) {
        Order order = orders.detail(id, owner).order();
        if (mapper.payment(id) == null) { return detail(id, owner); }
        try { accept(gateway.query(order.getOrderNo())); }
        finally { mapper.checked(id); }
        return detail(id, owner);
    }

    public void notification(Map<String,String> parameters) { accept(gateway.notification(parameters)); }

    public OrderModels.Detail cancel(long id, Long owner, Long actor, int target, String reason) {
        Order row = orders.detail(id, owner).order();
        Payment current = mapper.payment(id);
        if (row.getStatus() != 0 || current == null || "CLOSED".equals(current.status())) {
            return orders.cancel(id, owner, actor, target, reason);
        }
        Receipt result;
        try {
            result = gateway.query(row.getOrderNo()); accept(result);
            if (result.state() == State.PAID) { throw BusinessException.conflict("ALREADY_PAID", "平台已确认收款，请按整单售后处理"); }
            if (result.state() != State.CLOSED) {
                State closed = gateway.close(row.getOrderNo());
                if (closed == State.PAID) {
                    accept(gateway.query(row.getOrderNo()));
                    throw BusinessException.conflict("ALREADY_PAID", "支付状态已变化，请刷新订单，已付款请申请售后");
                }
                if (closed != State.CLOSED) {
                    // A signed URL may not have created a bank trade yet. Absence is not closure proof.
                    throw BusinessException.conflict("PAYMENT_CHECK_PENDING", "付款结果暂不能确认，库存继续保留，请稍后核对");
                }
                Identity identity = gateway.identity();
                accept(new Receipt(State.CLOSED, row.getOrderNo(), null, identity.appId(), identity.sellerId(), null, null));
            }
        } finally { mapper.checked(id); }
        return orders.cancel(id, owner, actor, target, reason);
    }

    public void reconcile(long id) {
        Order row = orders.detail(id, null).order();
        if (row.getStatus() != 0) { return; }
        if (row.getExpiresAt().isAfter(Instant.now())) { synchronize(id, null); }
        else { cancel(id, null, null, OrderState.EXPIRED.code(), "待付款超时，经平台核对关闭"); }
    }

    private void accept(Receipt receipt) {
        Long id = mapper.orderId(receipt.orderNo());
        if (id == null) { throw BusinessException.missing(); }
        tx.executeWithoutResult(status -> {
            Order row = orders.locked(id, null);
            Payment current = mapper.payment(id);
            if (current == null || !row.getOrderNo().equals(receipt.orderNo())
                    || !current.appId().equals(receipt.appId()) || !current.sellerId().equals(receipt.sellerId())) { mismatch(); }
            if (receipt.state() == State.PAID) {
                if (!Objects.equals(row.getTotalCents(), receipt.amountCents()) || receipt.tradeNo() == null
                        || !receipt.tradeNo().matches("[A-Za-z0-9_-]{6,64}") || receipt.successAt() == null
                        || receipt.successAt().isAfter(Instant.now().plusSeconds(300))
                        || receipt.successAt().isBefore(row.getCreatedAt().minusSeconds(300))) { mismatch(); }
                if ("SUCCESS".equals(current.status())) {
                    if (!Objects.equals(current.tradeNo(), receipt.tradeNo()) || !Objects.equals(current.amountCents(), receipt.amountCents())) { mismatch(); }
                    return; // Keep the original success time and audit entry on retries.
                }
                boolean late = row.getStatus() < 0;
                if (mapper.paid(id, receipt.tradeNo(), receipt.amountCents(), receipt.successAt(), late) != 1) { throw new IllegalStateException("Payment transition incomplete"); }
                if (row.getStatus() == 0 && mapper.markOrderPaid(id, receipt.successAt()) != 1) { throw new IllegalStateException("Order payment transition incomplete"); }
                if (late && mapper.saleForOrder(id) == null) {
                    var sale = new AfterSale(); sale.setOrderId(id); sale.setState("REFUND_PENDING");
                    sale.setReason("订单关闭后平台确认收款，待整单退款核对"); mapper.apply(sale);
                }
                audit.write(null, late ? "PAYMENT_LATE" : "PAYMENT_SUCCESS", "ORDER", id, "cents=" + receipt.amountCents());
            } else if (receipt.state() == State.CLOSED && "PENDING".equals(current.status())) {
                mapper.closePayment(id);
                audit.write(null, "PAYMENT_CLOSED", "ORDER", id, "Platform confirmed closed");
            }
        });
    }

    private static void mismatch() { throw BusinessException.conflict("PAYMENT_MISMATCH", "付款核对不一致，请联系管理员并保留追踪号"); }
}
