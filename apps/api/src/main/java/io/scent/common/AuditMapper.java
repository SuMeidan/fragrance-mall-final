package io.scent.common;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditMapper {
    @Insert("""
        INSERT INTO audit_log(actor_id,action,resource_type,resource_id,trace_id,summary)
        VALUES(#{actor},#{action},#{type},#{resource},#{trace},#{summary})
        """)
    void insert(@Param("actor") Long actor, @Param("action") String action, @Param("type") String type,
                @Param("resource") Long resource, @Param("trace") String trace, @Param("summary") String summary);

    @Select("SELECT id,actor_id,action,resource_type,resource_id,trace_id,summary,created_at FROM audit_log ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<Entry> list(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM audit_log")
    long count();

    record Entry(long id, Long actorId, String action, String resourceType, Long resourceId,
                 String traceId, String summary, Instant createdAt) { }
}
