package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.TicketMaterialEntity;
import com.agentgongdan.adapters.persistence.mapper.TicketMaterialMapper;
import com.agentgongdan.adapters.web.dto.AddTicketMaterialRequest;
import com.agentgongdan.adapters.web.dto.TicketMaterialView;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class TicketMaterialService {

    private static final long TENANT = 0L;

    private final TicketMaterialMapper materialMapper;

    public TicketMaterialService(TicketMaterialMapper materialMapper) {
        this.materialMapper = materialMapper;
    }

    public List<TicketMaterialView> list(long ticketId) {
        return materialMapper.selectList(Wrappers.<TicketMaterialEntity>lambdaQuery()
                        .eq(TicketMaterialEntity::getTenantId, TENANT)
                        .eq(TicketMaterialEntity::getTicketId, ticketId)
                        .isNull(TicketMaterialEntity::getDeletedAt)
                        .orderByAsc(TicketMaterialEntity::getId))
                .stream().map(this::toView).toList();
    }

    @Transactional
    public TicketMaterialView add(long ticketId, AddTicketMaterialRequest request, long userId) {
        TicketMaterialEntity entity = new TicketMaterialEntity();
        entity.setId(IdWorker.getId());
        entity.setTenantId(TENANT);
        entity.setTicketId(ticketId);
        entity.setMaterialName(request.materialName().trim());
        entity.setSpecification(trim(request.specification()));
        entity.setQuantity(request.quantity() == null ? BigDecimal.ZERO : request.quantity());
        entity.setUnit(trim(request.unit()));
        entity.setAmount(request.amount());
        entity.setRemark(trim(request.remark()));
        entity.setCreatedBy(userId);
        entity.setUpdatedBy(userId);
        materialMapper.insert(entity);
        return toView(entity);
    }

    private TicketMaterialView toView(TicketMaterialEntity entity) {
        return new TicketMaterialView(
                String.valueOf(entity.getId()),
                entity.getMaterialName(),
                entity.getSpecification(),
                entity.getQuantity(),
                entity.getUnit(),
                entity.getAmount(),
                entity.getRemark(),
                iso(entity.getCreatedAt())
        );
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String iso(LocalDateTime value) {
        return value == null ? null
                : value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
