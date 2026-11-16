package io.scent.catalog;

import io.scent.catalog.CatalogModels.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CatalogMapper {
    List<Product> list(Query query);
    List<Product> homeProducts();
    long count(Query query);
    List<Product> advisorList(AdvisorQuery query);
    long advisorCount(AdvisorQuery query);
    Product find(@Param("id") long id, @Param("admin") boolean admin);
    List<Product> related(@Param("id") long id, @Param("admin") boolean admin);
    void create(Product row);
    int update(Product row);

    @Select("SELECT id,parent_id,code,name,input_view,sort,enabled FROM category ORDER BY sort,id")
    List<Category> categories();

    @Select("SELECT id,parent_id,code,name,input_view,sort,enabled FROM category WHERE id=#{id}")
    Category category(long id);

    @Select("SELECT id,code,name,description,sort FROM fragrance_family ORDER BY sort,id")
    List<Family> families();

    @Update("UPDATE category SET name=#{body.name},sort=#{body.sort},enabled=#{body.enabled} WHERE id=#{id}")
    int updateCategory(@Param("id") long id, @Param("body") CategoryInput body);

    @Update("UPDATE product SET stock=stock+#{delta},version=version+1 WHERE id=#{id} AND version=#{version} AND stock+#{delta} BETWEEN 0 AND 2147483647")
    int stock(@Param("id") long id, @Param("version") long version, @Param("delta") int delta);

    @Select("SELECT CASE WHEN product_id=#{id} THEN related_product_id ELSE product_id END FROM product_compatibility WHERE product_id=#{id} OR related_product_id=#{id}")
    List<Long> relatedIds(long id);

    @Delete("DELETE FROM product_compatibility WHERE product_id=#{id} OR related_product_id=#{id}")
    void clearRelated(long id);

    @Insert("INSERT INTO product_compatibility(product_id,related_product_id) VALUES(#{a},#{b})")
    void relate(@Param("a") long a, @Param("b") long b);

    @Update("UPDATE product SET version=version+1 WHERE id=#{id}")
    void touch(long id);
}
