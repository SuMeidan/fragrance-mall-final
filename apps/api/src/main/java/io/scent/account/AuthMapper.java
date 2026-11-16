package io.scent.account;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AuthMapper {
    @Select("SELECT id,login_name,password_hash,display_name,role,enabled FROM account WHERE login_name=#{login}")
    Account find(String login);

    @Select("SELECT id,login_name,password_hash,display_name,role,enabled FROM account WHERE login_name=#{login} FOR UPDATE")
    Account forLogin(String login);

    @Insert("INSERT INTO account(login_name,password_hash,display_name,role) VALUES(#{login},#{hash},#{name},#{role})")
    void create(@Param("login") String login, @Param("hash") String hash, @Param("name") String name, @Param("role") String role);

    @Select("SELECT id FROM account WHERE id=#{id} FOR UPDATE")
    Long lock(long id);

    @Insert("INSERT INTO auth_session(token_hash,account_id,expires_at) VALUES(#{hash},#{account},#{expiry})")
    void session(@Param("hash") String hash, @Param("account") long account, @Param("expiry") LocalDateTime expiry);

    @Select("""
        SELECT a.id,a.login_name,a.display_name,a.role FROM auth_session s JOIN account a ON a.id=s.account_id
        WHERE s.token_hash=#{hash} AND s.expires_at>UTC_TIMESTAMP(6) AND a.enabled=TRUE
        """)
    Actor authenticate(String hash);

    @Delete("DELETE FROM auth_session WHERE token_hash=#{hash}")
    void revoke(String hash);

    @Delete("DELETE FROM auth_session WHERE expires_at<=UTC_TIMESTAMP(6)")
    void deleteExpired();

    @Delete("DELETE FROM auth_session WHERE account_id=#{id}")
    void revokeAccount(long id);

    @Select("SELECT COUNT(*) FROM account WHERE role='ADMIN'")
    long adminCount();

    @Select("SELECT id,login_name,display_name,role,enabled FROM account WHERE role='BUYER' AND (#{keyword} IS NULL OR login_name LIKE CONCAT('%',#{keyword},'%') ESCAPE '!') ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<View> list(@Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM account WHERE role='BUYER' AND (#{keyword} IS NULL OR login_name LIKE CONCAT('%',#{keyword},'%') ESCAPE '!')")
    long count(String keyword);

    @Update("UPDATE account SET enabled=#{enabled} WHERE id=#{id} AND role='BUYER'")
    int setEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    record Account(long id, String loginName, String passwordHash, String displayName, String role, boolean enabled) {
        public View view() { return new View(id, loginName, displayName, role, enabled); }
    }
    record View(long id, String loginName, String displayName, String role, boolean enabled) { }
}
