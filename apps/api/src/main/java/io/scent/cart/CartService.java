package io.scent.cart;

import io.scent.account.AuthMapper;
import io.scent.catalog.CatalogMapper;
import io.scent.catalog.Product;
import io.scent.common.BusinessException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {
    private final CartMapper mapper;
    private final CatalogMapper products;
    private final AuthMapper accounts;
    public CartService(CartMapper mapper, CatalogMapper products, AuthMapper accounts) {
        this.mapper = mapper; this.products = products; this.accounts = accounts;
    }

    public CartModels.View view(long owner) {
        List<CartModels.Item> items = mapper.list(owner);
        long total = 0;
        for (CartModels.Item item : items) { total = Math.addExact(total, item.getSubtotalCents()); }
        return new CartModels.View(items, total, !items.isEmpty() && items.stream().allMatch(CartModels.Item::available));
    }

    @Transactional
    public CartModels.View add(long owner, long productId) {
        accounts.lock(owner);
        List<CartModels.Line> lines = mapper.lines(owner);
        if (lines.stream().anyMatch(line -> line.productId() == productId)) {
            throw BusinessException.conflict("CART_EXISTS", "商品已在购物车中，请在购物车调整数量");
        }
        if (lines.size() >= 20) { throw BusinessException.conflict("CART_LIMIT", "购物车最多20个商品项"); }
        available(productId, 1);
        mapper.add(owner, productId);
        return view(owner);
    }

    @Transactional
    public CartModels.View quantity(long owner, long productId, int quantity) {
        accounts.lock(owner);
        if (mapper.lines(owner).stream().noneMatch(line -> line.productId() == productId)) { throw BusinessException.missing(); }
        available(productId, quantity);
        mapper.quantity(owner, productId, quantity);
        return view(owner);
    }

    @Transactional
    public CartModels.View remove(long owner, long productId) {
        accounts.lock(owner); mapper.remove(owner, productId); return view(owner);
    }

    private void available(long id, int quantity) {
        Product row = products.find(id, false);
        if (row == null) { throw BusinessException.conflict("PRODUCT_UNAVAILABLE", "商品已下架或不存在"); }
        if (row.getStock() < quantity) { throw BusinessException.conflict("STOCK_INSUFFICIENT", "商品库存不足"); }
    }
}
