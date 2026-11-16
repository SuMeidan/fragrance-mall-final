package io.scent.account;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AddressMapper {
    @Select("SELECT id,account_id,receiver,phone,region,detail,is_default FROM user_address WHERE account_id=#{owner} ORDER BY is_default DESC,id")
    List<Address> list(long owner);

    @Select("SELECT id,account_id,receiver,phone,region,detail,is_default FROM user_address WHERE id=#{id} AND account_id=#{owner}")
    Address find(@Param("id") long id, @Param("owner") long owner);

    @Insert("INSERT INTO user_address(account_id,receiver,phone,region,detail,is_default) VALUES(#{accountId},#{receiver},#{phone},#{region},#{detail},#{isDefault})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void create(Address row);

    @Update("UPDATE user_address SET receiver=#{receiver},phone=#{phone},region=#{region},detail=#{detail},is_default=#{isDefault} WHERE id=#{id} AND account_id=#{accountId}")
    int update(Address row);

    @Update("UPDATE user_address SET is_default=FALSE WHERE account_id=#{owner}")
    void clearDefault(long owner);

    @Update("UPDATE user_address SET is_default=TRUE WHERE id=#{id} AND account_id=#{owner}")
    void setDefault(@Param("id") long id, @Param("owner") long owner);

    @Delete("DELETE FROM user_address WHERE id=#{id} AND account_id=#{owner}")
    int delete(@Param("id") long id, @Param("owner") long owner);
}
