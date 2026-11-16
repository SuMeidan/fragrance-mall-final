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

@Service
public class AfterSaleService {
    private final OrderService orders;
    private final OrderMapper orderMapper;
    private final SettlementMapper mapper;
    private final PaymentGateway gateway;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public AfterSaleService(OrderService orders, OrderMapper orderMapper, SettlementMapper mapper,
                            PaymentGateway gateway, AuditService audit, PlatformTransactionManager manager) {
        this.orders = orders; this.orderMapper = orderMapper; this.mapper = mapper; this.gateway = gateway; this.audit = audit;
        tx = new TransactionTemplate(manager); tx.setTimeout(10);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public AfterSale apply(long owner, long orderId, String reason) {
        return tx.execute(status -> {
            Order row = orders.locked(orderId, owner);
            AfterSale existing = mapper.saleForOrder(orderId);
            if (existing != null) { return existing; }
            Payment paid = mapper.payment(orderId);
            if (row.getStatus() < 1 || row.getStatus() > 4 || paid == null || !"SUCCESS".equals(paid.status())) { conflict("只有确认付款的订单可以申请整单售后"); }
            var sale = new AfterSale(); sale.setOrderId(orderId); sale.setState("REQUESTED"); sale.setReason(reason.strip()); mapper.apply(sale);
            audit.write(owner, "AFTER_SALE_APPLY", "ORDER", orderId, "Whole-order after-sale requested");
            return require(sale.getId());
        });
    }

    public AfterSale review(long actor, long id, Decision input) {
        AfterSale snapshot = require(id);
        return tx.execute(status -> {
            Order row = orders.locked(snapshot.getOrderId(), null); AfterSale sale = require(id);
            if (!"REQUESTED".equals(sale.getState())) {
                if (input.approve() == !"REJECTED".equals(sale.getState())) { return sale; }
                conflict("售后已处理，不能改写原审核结果");
            }
            String target = input.approve() ? (row.getStatus() >= 3 ? "RETURN_REQUIRED" : "REFUND_PENDING") : "REJECTED";
            if (mapper.decision(id, target, input.note().strip(), actor) != 1) { conflict("售后状态已变化"); }
            audit.write(actor, "AFTER_SALE_REVIEW", "ORDER", row.getId(), "state=" + target);
            return require(id);
        });
    }

    public AfterSale returning(long owner, long id, OrderModels.Ship input) {
        AfterSale snapshot = require(id);
        return tx.execute(status -> {
            orders.locked(snapshot.getOrderId(), owner); AfterSale sale = require(id);
            if (sale.getReceivedAt() != null && input.carrier().strip().equals(sale.getReturnCarrier()) && input.trackingNo().equals(sale.getReturnTrackingNo())) { return sale; }
            if (mapper.returning(id, input.carrier().strip(), input.trackingNo()) != 1) { conflict("当前售后不在退回阶段"); }
            audit.write(owner, "AFTER_SALE_RETURN", "ORDER", sale.getOrderId(), "Return shipment recorded");
            return require(id);
        });
    }

    public AfterSale received(long actor, long id) {
        AfterSale snapshot = require(id);
        return tx.execute(status -> {
            orders.locked(snapshot.getOrderId(), null); AfterSale sale = require(id);
            if (sale.getReceivedAt() != null) { return sale; }
            if (mapper.received(id) != 1) { conflict("需要先同意退货并填写退回物流，再确认收到实物"); }
            audit.write(actor, "AFTER_SALE_RECEIVE", "ORDER", sale.getOrderId(), "Returned goods received; not yet restocked");
            return require(id);
        });
    }

    public Refund refund(Long actor, long id) {
        AfterSale snapshot = require(id); Refund existing = mapper.refund(id);
        if (existing != null && "SUCCESS".equals(existing.status())) { return existing; }
        Identity identity = gateway.identity();
        RefundWork work = tx.execute(status -> {
            Order row = orders.locked(snapshot.getOrderId(), null); AfterSale sale = require(id);
            Refund intent = mapper.refund(id);
            Payment paid = mapper.payment(row.getId());
            // A concurrent request may have completed after the unlocked fast path above.
            if (intent != null && "SUCCESS".equals(intent.status())) { return new RefundWork(row.getId(), row.getOrderNo(), intent, paid); }
            if (!"REFUND_PENDING".equals(sale.getState())) { conflict("请先完成售后审核，已发货的订单还须确认退回实物"); }
            if (paid == null || !"SUCCESS".equals(paid.status()) || !Objects.equals(paid.amountCents(), row.getTotalCents())
                    || !identity.appId().equals(paid.appId()) || !identity.sellerId().equals(paid.sellerId())) { conflict("原成功付款或商户配置无法核对，不能发起退款"); }
            if (intent == null) {
                mapper.refundIntent(id, "R" + UUID.randomUUID().toString().replace("-", ""), paid.amountCents());
                audit.write(actor, "REFUND_REQUEST", "ORDER", row.getId(), "Whole-order refund requested; stable request number");
                intent = mapper.refund(id);
            }
            if (!Objects.equals(paid.amountCents(), intent.amountCents())) { conflict("退款金额与原实付不一致，不能调用平台"); }
            return new RefundWork(row.getId(), row.getOrderNo(), intent, paid);
        });
        Objects.requireNonNull(work);
        if ("SUCCESS".equals(work.refund().status())) { return work.refund(); }
        try {
            RefundReceipt result = gateway.queryRefund(work.orderNo(), work.refund().requestNo());
            if (result.state() == RefundState.NOT_FOUND || result.state() == RefundState.FAILED) {
                result = gateway.refund(work.orderNo(), work.refund().requestNo(), work.refund().amountCents());
            }
            record(actor, id, work, result);
        } catch (RuntimeException ex) {
            // Unknown does not mean failed. Keep the same number for background query/retry.
            mapper.refundPending(id, "PENDING", "RESULT_UNKNOWN");
            throw ex;
        }
        return mapper.refund(id);
    }

    private void record(Long actor, long id, RefundWork work, RefundReceipt result) {
        tx.executeWithoutResult(status -> {
            orders.locked(work.orderId(), null); AfterSale sale = require(id); Refund current = mapper.refund(id);
            if ("SUCCESS".equals(current.status())) { return; }
            if (!"REFUND_PENDING".equals(sale.getState()) || !current.requestNo().equals(work.refund().requestNo())) { conflict("退款状态已变化"); }
            if (result.state() == RefundState.SUCCESS) {
                if (!current.requestNo().equals(result.requestNo()) || !Objects.equals(work.payment().tradeNo(), result.tradeNo())
                        || !Objects.equals(current.amountCents(), result.amountCents()) || result.successAt() == null
                        || result.successAt().isBefore(work.payment().successAt())
                        || result.successAt().isAfter(Instant.now().plusSeconds(300))) {
                    throw BusinessException.conflict("REFUND_MISMATCH", "退款核对不一致，请保留追踪号并由管理员核对");
                }
                if (mapper.refundSuccess(id, result.successAt()) != 1 || mapper.refunded(id) != 1) { throw new IllegalStateException("Refund transition incomplete"); }
                mapper.resolved(work.orderId());
                audit.write(actor, "REFUND_SUCCESS", "ORDER", work.orderId(), "cents=" + current.amountCents() + "; stock unchanged");
            } else {
                mapper.refundPending(id, result.state() == RefundState.FAILED ? "FAILED" : "PENDING", result.errorCode() == null ? "RESULT_PENDING" : AlipayGateway.safeCode(result.errorCode()));
            }
        });
    }

    public AfterSale restock(long actor, long id, String reason) {
        AfterSale snapshot = require(id);
        return tx.execute(status -> {
            Order row = orders.locked(snapshot.getOrderId(), null); AfterSale sale = require(id);
            if (Boolean.TRUE.equals(sale.getRestocked())) { return sale; }
            Refund refund = mapper.refund(id);
            if (row.getStatus() < 1 || !"REFUNDED".equals(sale.getState()) || refund == null || !"SUCCESS".equals(refund.status())
                    || (row.getStatus() >= 3 && sale.getReceivedAt() == null)) { conflict("请先核实退款与可售实物；关闭时已恢复的订单不再入库"); }
            if (mapper.restocked(id) != 1) { conflict("入库状态已变化"); }
            for (OrderModels.Item item : orderMapper.items(row.getId())) {
                if (orderMapper.restore(item.productId(), item.quantity()) != 1) { throw new IllegalStateException("After-sale stock restore incomplete"); }
            }
            audit.write(actor, "AFTER_SALE_RESTOCK", "ORDER", row.getId(), "Restocked once; reason=" + reason.strip());
            return require(id);
        });
    }

    public Page<AfterSale> list(String state, int page, int size) {
        if (state != null && state.isBlank()) { state = null; }
        if (state != null && !List.of("REQUESTED", "REJECTED", "RETURN_REQUIRED", "REFUND_PENDING", "REFUNDED").contains(state)) { throw BusinessException.invalid("无效售后状态"); }
        int offset = Page.offset(page, size);
        return new Page<>(mapper.sales(state, offset, size), mapper.saleCount(state), page, size);
    }
    private AfterSale require(long id) { AfterSale sale = mapper.sale(id); if (sale == null) { throw BusinessException.missing(); } return sale; }
    private static void conflict(String message) { throw BusinessException.conflict("STATE_CONFLICT", message); }
    private record RefundWork(long orderId, String orderNo, Refund refund, Payment payment) { }
}
