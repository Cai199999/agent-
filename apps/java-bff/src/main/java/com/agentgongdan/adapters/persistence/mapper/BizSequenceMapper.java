package com.agentgongdan.adapters.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BizSequenceMapper {

    @Insert("""
            INSERT IGNORE INTO biz_sequence (id, tenant_id, seq_name, current_value, step, updated_at)
            VALUES (#{id}, 0, #{seqName}, 0, 100, NOW(3))
            """)
    int ensure(@Param("id") long id, @Param("seqName") String seqName);

    @Update("""
            UPDATE biz_sequence
            SET current_value = LAST_INSERT_ID(current_value + 1), updated_at = NOW(3)
            WHERE tenant_id = 0 AND seq_name = #{seqName}
            """)
    int next(@Param("seqName") String seqName);

    @Select("SELECT LAST_INSERT_ID()")
    long lastValue();
}
