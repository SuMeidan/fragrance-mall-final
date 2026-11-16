package io.scent.cart;

import io.scent.cart.CartModels.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CartMapper {
    @Select("""
        SELECT p.id AS product_id,p.name,p.spec,p.unit,p.image_path,p.price_cents,p.stock,ci.quantity,
          (p.active AND c.enabled AND parent.enabled AND p.stock>=ci.quantity) AS available
        FROM cart_item ci JOIN product p ON p.id=ci.product_id
        JOIN category c ON c.id=p.category_id JOIN category parent ON parent.id=c.parent_id
        WHERE ci.account_id=#{owner} ORDER BY ci.id
        """)
    List<Item> list(long owner);

    @Select("SELECT product_id,quantity FROM cart_item WHERE account_id=#{owner} ORDER BY product_id")
    List<Line> lines(long owner);

    @Insert("INSERT INTO cart_item(account_id,product_id,quantity) VALUES(#{owner},#{product},1)")
    void add(@Param("owner") long owner, @Param("product") long product);

    @Update("UPDATE cart_item SET quantity=#{quantity} WHERE account_id=#{owner} AND product_id=#{product}")
    int quantity(@Param("owner") long owner, @Param("product") long product, @Param("quantity") int quantity);

    @Delete("DELETE FROM cart_item WHERE account_id=#{owner} AND product_id=#{product}")
    void remove(@Param("owner") long owner, @Param("product") long product);

    @Delete("DELETE FROM cart_item WHERE account_id=#{owner}")
    void clear(long owner);
}
