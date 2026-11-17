package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_sequence")
public class BizSequenceEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String seqName;
    private Long currentValue;
    private Integer step;
    private LocalDateTime updatedAt;
}
