package com.agentgongdan.adapters.persistence.mapper;

import com.agentgongdan.adapters.persistence.entity.IdempotencyRecordEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface IdempotencyRecordMapper {

    @Insert("""
            INSERT IGNORE INTO idempotency_record
                (id, tenant_id, idem_key, scope, request_hash, status, expires_at, created_at, updated_at)
            VALUES
                (#{id}, 0, #{idemKey}, #{scope}, #{requestHash}, 'PROCESSING', #{expiresAt}, NOW(3), NOW(3))
            """)
    int tryInsert(@Param("id") long id,
                  @Param("idemKey") String idemKey,
                  @Param("scope") String scope,
                  @Param("requestHash") String requestHash,
                  @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
            SELECT id, tenant_id, idem_key, scope, request_hash, status, response_code,
                   CAST(response_json AS CHAR) AS response_json, expires_at, created_at, updated_at
            FROM idempotency_record
            WHERE tenant_id = 0 AND scope = #{scope} AND idem_key = #{idemKey}
            LIMIT 1
            """)
    IdempotencyRecordEntity find(@Param("scope") String scope, @Param("idemKey") String idemKey);

    @Update("""
            UPDATE idempotency_record
            SET status = 'SUCCESS',
                response_code = 0,
                response_json = CAST(#{responseJson} AS JSON),
                updated_at = NOW(3)
            WHERE id = #{id}
            """)
    int markSuccess(@Param("id") long id, @Param("responseJson") String responseJson);
}
