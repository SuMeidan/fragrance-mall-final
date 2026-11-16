package io.scent.order;

import io.scent.catalog.Product;
import io.scent.order.OrderModels.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OrderMapper {
    Order find(@Param("id") long id, @Param("owner") Long owner);
    Order lock(@Param("id") long id, @Param("owner") Long owner);
    List<Order> list(Query query);
    long count(Query query);

    @Select("SELECT * FROM mall_order WHERE account_id=#{owner} AND checkout_key=#{key}")
    Order byCheckout(@Param("owner") long owner, @Param("key") String key);

    @Select("SELECT * FROM product WHERE id=#{id} FOR UPDATE")
    Product lockProduct(long id);

    @Select("SELECT c.id FROM category c JOIN category p ON p.id=c.parent_id WHERE c.id=#{id} AND c.enabled=TRUE AND p.enabled=TRUE FOR SHARE")
    Long lockActiveCategory(long id);

    @Update("UPDATE product SET stock=stock-#{quantity},version=version+1 WHERE id=#{id} AND active=TRUE AND stock>=#{quantity}")
    int deduct(@Param("id") long id, @Param("quantity") int quantity);

    @Update("UPDATE product SET stock=stock+#{quantity},version=version+1 WHERE id=#{id}")
    int restore(@Param("id") long id, @Param("quantity") int quantity);

    @Insert("""
        INSERT INTO mall_order(order_no,account_id,checkout_key,checkout_address_id,status,total_cents,
          receiver,phone,region,address_detail,expires_at)
        VALUES(#{orderNo},#{accountId},#{checkoutKey},#{checkoutAddressId},0,#{totalCents},
          #{receiver},#{phone},#{region},#{addressDetail},#{expiresAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void create(Order order);

    @Insert("""
        INSERT INTO order_item(order_id,product_id,name,spec,unit,image_path,price_cents,quantity)
        VALUES(#{orderId},#{productId},#{name},#{spec},#{unit},#{imagePath},#{priceCents},#{quantity})
        """)
    int item(Item item);

    @Select("SELECT id,order_id,product_id,name,spec,unit,image_path,price_cents,quantity FROM order_item WHERE order_id=#{id} ORDER BY product_id")
    List<Item> items(long id);

    @Update("UPDATE mall_order SET status=#{status},closed_at=UTC_TIMESTAMP(6),close_reason=#{reason} WHERE id=#{id} AND status=0")
    int close(@Param("id") long id, @Param("status") int status, @Param("reason") String reason);

    @Update("UPDATE mall_order SET status=2 WHERE id=#{id} AND status=1")
    int pack(long id);

    @Update("UPDATE mall_order SET status=3,carrier=#{carrier},tracking_no=#{tracking},shipped_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND status IN (1,2)")
    int ship(@Param("id") long id, @Param("carrier") String carrier, @Param("tracking") String tracking);

    @Update("UPDATE mall_order SET status=4,completed_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND status=3")
    int receive(long id);

    @Select("SELECT o.id FROM mall_order o WHERE o.status=0 AND o.expires_at<=UTC_TIMESTAMP(6) AND NOT EXISTS(SELECT 1 FROM payment p WHERE p.order_id=o.id AND p.status='PENDING') ORDER BY o.expires_at,o.id LIMIT 50")
    List<Long> expired();
}
