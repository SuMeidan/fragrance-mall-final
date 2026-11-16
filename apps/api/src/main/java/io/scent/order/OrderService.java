package io.scent.order;

import io.scent.account.Address;
import io.scent.account.AddressMapper;
import io.scent.account.AuthMapper;
import io.scent.cart.CartMapper;
import io.scent.cart.CartModels;
import io.scent.catalog.Product;
import io.scent.common.*;
import io.scent.order.OrderModels.*;
import io.scent.settlement.SettlementMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;

@Service
public class OrderService {
    private final OrderMapper mapper;
    private final CartMapper cart;
    private final AddressMapper addresses;
    private final AuthMapper accounts;
    private final AuditService audit;
    private final SettlementMapper funds;
    private final int timeoutMinutes;
    public OrderService(OrderMapper mapper, CartMapper cart, AddressMapper addresses, AuthMapper accounts,
                        AuditService audit, SettlementMapper funds, @Value("${scent.order.timeout-minutes:20}") int timeoutMinutes) {
        if (timeoutMinutes < 1 || timeoutMinutes > 1440) { throw new IllegalArgumentException("order timeout must be 1..1440 minutes"); }
        this.mapper = mapper; this.cart = cart; this.addresses = addresses; this.accounts = accounts;
        this.audit = audit; this.timeoutMinutes = timeoutMinutes;
        this.funds = funds;
    }

    @Transactional(timeout = 10, isolation = Isolation.READ_COMMITTED)
    public Detail checkout(long owner, long addressId, String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,64}")) { throw BusinessException.invalid("Idempotency-Key应为16..64位字母、数字、下划线或短横线"); }
        accounts.lock(owner);
        Order existing = mapper.byCheckout(owner, key);
        if (existing != null) {
            if (existing.getCheckoutAddressId() != addressId) { throw BusinessException.conflict("IDEMPOTENCY_CONFLICT", "此请求号已用于另一个结算地址，请开始新结算"); }
            return detail(existing.getId(), owner);
        }
        Address address = addresses.find(addressId, owner);
        if (address == null) { throw BusinessException.missing(); }
        List<CartModels.Line> lines = cart.lines(owner);
        if (lines.isEmpty()) { throw BusinessException.conflict("CART_EMPTY", "购物车为空，请先选购商品"); }
        if (lines.size() > 20) { throw BusinessException.conflict("CART_LIMIT", "购物车超过20项"); }
        List<Item> snapshots = new ArrayList<>();
        long total = 0;
        Set<Long> checkedCategories = new HashSet<>();
        // lines are sorted by product ID. A later failure rolls back earlier deductions too.
        for (CartModels.Line line : lines) {
            Product p = mapper.lockProduct(line.productId());
            if (p == null || !p.getActive()) { throw BusinessException.conflict("PRODUCT_UNAVAILABLE", "部分商品已下架，请返回购物车检查"); }
            if (checkedCategories.add(p.getCategoryId()) && mapper.lockActiveCategory(p.getCategoryId()) == null) {
                throw BusinessException.conflict("PRODUCT_UNAVAILABLE", "部分商品分类已停用");
            }
            if (mapper.deduct(p.getId(), line.quantity()) != 1) { throw BusinessException.conflict("STOCK_INSUFFICIENT", "部分商品库存不足，请返回购物车检查"); }
            total = Math.addExact(total, Math.multiplyExact(p.getPriceCents(), line.quantity()));
            snapshots.add(new Item(0, 0, p.getId(), p.getName(), p.getSpec(), p.getUnit(), p.getImagePath(), p.getPriceCents(), line.quantity()));
        }
        Order row = new Order();
        row.setOrderNo(UUID.randomUUID().toString().replace("-", "")); row.setAccountId(owner);
        row.setCheckoutKey(key); row.setCheckoutAddressId(addressId); row.setTotalCents(total);
        row.setReceiver(address.getReceiver()); row.setPhone(address.getPhone()); row.setRegion(address.getRegion());
        row.setAddressDetail(address.getDetail()); row.setExpiresAt(Instant.now().plus(timeoutMinutes, ChronoUnit.MINUTES));
        mapper.create(row);
        for (Item item : snapshots) {
            int inserted = mapper.item(new Item(0, row.getId(), item.productId(), item.name(), item.spec(), item.unit(), item.imagePath(), item.priceCents(), item.quantity()));
            if (inserted != 1) { throw new IllegalStateException("Order item insert was not complete"); }
        }
        cart.clear(owner);
        audit.write(owner, "ORDER_CREATE", "ORDER", row.getId(), "items=" + lines.size() + "; cents=" + total);
        return detail(row.getId(), owner);
    }

    public Detail detail(long id, Long owner) {
        Order row = mapper.find(id, owner);
        if (row == null) { throw BusinessException.missing(); }
        List<Item> items = mapper.items(id);
        row.setItemCount(items.stream().mapToInt(Item::quantity).sum());
        return new Detail(row, items);
    }

    public Page<Summary> list(Long owner, Integer status, int page, int size) {
        if (status != null && !OrderState.valid(status)) { throw BusinessException.invalid("无效订单状态"); }
        Query query = new Query(owner, status, Page.offset(page, size), size);
        List<Summary> result = mapper.list(query).stream().map(o -> new Summary(o.getId(), o.getOrderNo(), o.getStatus(),
            o.getStatusName(), o.getTotalCents(), o.getItemCount(), o.getCreatedAt(), o.getExpiresAt())).toList();
        return new Page<>(result, mapper.count(query), page, size);
    }

    @Transactional(timeout = 10, isolation = Isolation.READ_COMMITTED)
    public Detail cancel(long id, Long owner, Long actor, int target, String reason) {
        Order row = locked(id, owner);
        if (row.getStatus() < 0) { return detail(id, owner); }
        if (row.getStatus() != 0) { stateConflict("已付款订单请申请售后"); }
        requirePaymentClosed(id);
        close(row, actor, target, reason);
        return detail(id, owner);
    }

    @Transactional(timeout = 10, isolation = Isolation.READ_COMMITTED)
    public void closeExpired(long id) {
        Order row = locked(id, null);
        if (row.getStatus() == 0 && !row.getExpiresAt().isAfter(Instant.now())) {
            requirePaymentClosed(id);
            close(row, null, OrderState.EXPIRED.code(), "待付款超时");
        }
    }

    @Transactional(timeout = 10, isolation = Isolation.READ_COMMITTED)
    public Detail pack(long actor, long id) {
        Order row = locked(id, null);
        if (row.getStatus() == 2) { return detail(id, null); }
        requireNoAfterSale(id);
        if (mapper.pack(id) != 1) { stateConflict("只有已付款订单可以配货"); }
        audit.write(actor, "ORDER_PACK", "ORDER", id, "Order packed");
        return detail(id, null);
    }

    @Transactional(timeout = 10, isolation = Isolation.READ_COMMITTED)
    public Detail ship(long actor, long id, Ship input) {
        Order row = locked(id, null);
        String carrier = input.carrier().strip();
        if (row.getStatus() == 3 && carrier.equals(row.getCarrier()) && input.trackingNo().equals(row.getTrackingNo())) { return detail(id, null); }
        requireNoAfterSale(id);
        if (mapper.ship(id, carrier, input.trackingNo()) != 1) { stateConflict("只有已付款或配货订单可以发货"); }
        audit.write(actor, "ORDER_SHIP", "ORDER", id, "Shipment recorded");
        return detail(id, null);
    }

    @Transactional(timeout = 10, isolation = Isolation.READ_COMMITTED)
    public Detail receive(long owner, long id) {
        Order row = locked(id, owner);
        if (row.getStatus() == 4) { return detail(id, owner); }
        requireNoAfterSale(id);
        if (mapper.receive(id) != 1) { stateConflict("只有已发货订单可以确认收货"); }
        audit.write(owner, "ORDER_RECEIVE", "ORDER", id, "Receipt confirmed");
        return detail(id, owner);
    }

    /** Shared lock order for money/after-sale changes; callers must already be in a short transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Order locked(long id, Long owner) {
        Order current = mapper.find(id, owner);
        if (current == null) { throw BusinessException.missing(); }
        accounts.lock(current.getAccountId());
        Order row = mapper.lock(id, owner);
        if (row == null) { throw BusinessException.missing(); }
        return row;
    }

    private void requirePaymentClosed(long id) {
        if (funds.paymentBlocksClose(id) > 0) { throw BusinessException.conflict("PAYMENT_CHECK_PENDING", "此订单已发起支付，请先核对平台结果"); }
    }

    private void requireNoAfterSale(long id) {
        if (funds.blocksFulfillment(id) > 0) { throw BusinessException.conflict("AFTER_SALE_ACTIVE", "订单正在售后或已退款，无法继续履约"); }
    }

    private void close(Order row, Long actor, int target, String reason) {
        if (!List.of(-1,-2,-3).contains(target)) { throw new IllegalArgumentException("Invalid close transition"); }
        if (mapper.close(row.getId(), target, reason) != 1) { stateConflict("订单状态已变化"); }
        for (Item item : mapper.items(row.getId())) {
            if (mapper.restore(item.productId(), item.quantity()) != 1) { throw new IllegalStateException("Stock restore was not complete"); }
        }
        audit.write(actor, "ORDER_CLOSE", "ORDER", row.getId(), "status=" + target + "; restored once");
    }

    private static void stateConflict(String message) { throw BusinessException.conflict("STATE_CONFLICT", message); }
}
