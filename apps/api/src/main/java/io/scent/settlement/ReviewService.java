package io.scent.settlement;

import io.scent.catalog.CatalogService;
import io.scent.common.*;
import io.scent.order.*;
import io.scent.settlement.SettlementModels.*;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class ReviewService {
    private final OrderService orders;
    private final OrderMapper orderMapper;
    private final CatalogService catalog;
    private final SettlementMapper mapper;
    private final AuditService audit;
    public ReviewService(OrderService orders, OrderMapper orderMapper, CatalogService catalog, SettlementMapper mapper, AuditService audit) {
        this.orders = orders; this.orderMapper = orderMapper; this.catalog = catalog; this.mapper = mapper; this.audit = audit;
    }

    @Transactional(timeout = 10, isolation = Isolation.READ_COMMITTED)
    public Review create(long owner, long id, ReviewInput input) {
        Order order = orders.locked(id, owner);
        Payment payment = mapper.payment(id);
        if (order.getStatus() != 4 || payment == null || !"SUCCESS".equals(payment.status()) || mapper.blocksFulfillment(id) > 0) { throw BusinessException.conflict("STATE_CONFLICT", "请在交易完成、没有进行中售后或整单退款时评价"); }
        if (orderMapper.items(id).stream().noneMatch(i -> i.id() == input.orderItemId())) { throw BusinessException.missing(); }
        var existing = mapper.orderReviews(id).stream().filter(r -> r.orderItemId() == input.orderItemId()).findFirst();
        if (existing.isPresent()) {
            if (existing.get().stars() == input.stars() && Objects.equals(existing.get().content(), input.content().strip())) { return existing.get(); }
            throw BusinessException.conflict("REVIEW_EXISTS", "这个订单项已经评价");
        }
        mapper.review(input.orderItemId(), input.stars(), input.content().strip());
        audit.write(owner, "REVIEW_CREATE", "ORDER", id, "Order item reviewed");
        return mapper.orderReviews(id).stream().filter(r -> r.orderItemId() == input.orderItemId()).findFirst().orElseThrow();
    }

    public Page<Review> list(long productId, int page, int size) {
        catalog.detail(productId, false);
        int offset = Page.offset(page, size);
        return new Page<>(mapper.productReviews(productId, offset, size), mapper.reviewCount(productId), page, size);
    }
}
