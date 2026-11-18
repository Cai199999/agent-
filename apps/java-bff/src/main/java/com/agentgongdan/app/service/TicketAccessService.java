package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.TicketEntity;
import com.agentgongdan.adapters.persistence.mapper.TicketMapper;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 工单级访问控制：防止仅凭 ticketId 越权访问附件/材料/回访/详情。
 * 特权角色可访问全部；其他用户仅能访问本人报障或本人处理的工单。
 */
@Service
public class TicketAccessService {

    private final TicketMapper ticketMapper;

    public TicketAccessService(TicketMapper ticketMapper) {
        this.ticketMapper = ticketMapper;
    }

    public TicketEntity requireReadable(long ticketId, long userId, List<String> roles) {
        TicketEntity ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeletedAt() != null) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND);
        }
        if (!isPrivileged(roles)
                && !Objects.equals(ticket.getReporterId(), userId)
                && !Objects.equals(ticket.getAssigneeId(), userId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "无权访问该工单");
        }
        return ticket;
    }

    public TicketEntity requireHandler(long ticketId, long userId, List<String> roles) {
        TicketEntity ticket = requireReadable(ticketId, userId, roles);
        if (!isManager(roles) && !Objects.equals(ticket.getAssigneeId(), userId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "仅当前处理人可执行该操作");
        }
        return ticket;
    }

    public TicketEntity requireHandlerOrUnassigned(long ticketId, long userId, List<String> roles) {
        TicketEntity ticket = requireReadable(ticketId, userId, roles);
        if (!isManager(roles) && ticket.getAssigneeId() != null
                && !Objects.equals(ticket.getAssigneeId(), userId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "该工单已派给其他处理人");
        }
        return ticket;
    }

    public TicketEntity requireReporterOrPrivileged(long ticketId, long userId, List<String> roles) {
        TicketEntity ticket = requireReadable(ticketId, userId, roles);
        if (!isManager(roles) && !Objects.equals(ticket.getReporterId(), userId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "仅报障人可执行该操作");
        }
        return ticket;
    }

    private boolean isManager(List<String> roles) {
        return roles != null && (roles.contains("ADMIN") || roles.contains("DISPATCHER"));
    }

    public boolean isPrivileged(List<String> roles) {
        if (roles == null) {
            return false;
        }
        return roles.contains("ADMIN") || roles.contains("DISPATCHER")
                || roles.contains("ENGINEER") || roles.contains("INSPECTOR");
    }
}
