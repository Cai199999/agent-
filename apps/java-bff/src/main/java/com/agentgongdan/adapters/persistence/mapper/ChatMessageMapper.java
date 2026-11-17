package com.agentgongdan.adapters.persistence.mapper;

import com.agentgongdan.adapters.persistence.entity.ChatMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
}
