package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.BizBuildingEntity;
import com.agentgongdan.adapters.persistence.entity.BizCustomerEntity;
import com.agentgongdan.adapters.persistence.entity.BizEquipmentEntity;
import com.agentgongdan.adapters.persistence.entity.BizProjectEntity;
import com.agentgongdan.adapters.persistence.entity.BizRoomEntity;
import com.agentgongdan.adapters.persistence.entity.BizServiceItemEntity;
import com.agentgongdan.adapters.persistence.mapper.BizBuildingMapper;
import com.agentgongdan.adapters.persistence.mapper.BizCustomerMapper;
import com.agentgongdan.adapters.persistence.mapper.BizEquipmentMapper;
import com.agentgongdan.adapters.persistence.mapper.BizProjectMapper;
import com.agentgongdan.adapters.persistence.mapper.BizRoomMapper;
import com.agentgongdan.adapters.persistence.mapper.BizServiceItemMapper;
import com.agentgongdan.adapters.web.dto.MasterDataOption;
import com.agentgongdan.adapters.web.dto.MasterDataOptionsView;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 主数据只读选项：项目/楼宇/房间/客户/设备/服务项目。
 * <p>不做 JOIN；各表分别查询，由前端按 parentId 级联过滤。</p>
 */
@Service
public class MasterDataService {

    private static final int LIMIT = 200;

    private final BizProjectMapper projectMapper;
    private final BizBuildingMapper buildingMapper;
    private final BizRoomMapper roomMapper;
    private final BizCustomerMapper customerMapper;
    private final BizEquipmentMapper equipmentMapper;
    private final BizServiceItemMapper serviceItemMapper;

    public MasterDataService(BizProjectMapper projectMapper,
                             BizBuildingMapper buildingMapper,
                             BizRoomMapper roomMapper,
                             BizCustomerMapper customerMapper,
                             BizEquipmentMapper equipmentMapper,
                             BizServiceItemMapper serviceItemMapper) {
        this.projectMapper = projectMapper;
        this.buildingMapper = buildingMapper;
        this.roomMapper = roomMapper;
        this.customerMapper = customerMapper;
        this.equipmentMapper = equipmentMapper;
        this.serviceItemMapper = serviceItemMapper;
    }

    public MasterDataOptionsView options() {
        List<MasterDataOption> projects = projectMapper.selectList(
                        Wrappers.<BizProjectEntity>lambdaQuery()
                                .eq(BizProjectEntity::getTenantId, 0L)
                                .eq(BizProjectEntity::getStatus, "ACTIVE")
                                .isNull(BizProjectEntity::getDeletedAt)
                                .orderByAsc(BizProjectEntity::getId)
                                .last("LIMIT " + LIMIT))
                .stream()
                .map(item -> option(item.getId(), item.getProjectCode(), item.getProjectName(), null,
                        item.getAddress()))
                .toList();

        List<MasterDataOption> buildings = buildingMapper.selectList(
                        Wrappers.<BizBuildingEntity>lambdaQuery()
                                .eq(BizBuildingEntity::getTenantId, 0L)
                                .eq(BizBuildingEntity::getStatus, "ACTIVE")
                                .isNull(BizBuildingEntity::getDeletedAt)
                                .orderByAsc(BizBuildingEntity::getId)
                                .last("LIMIT " + LIMIT))
                .stream()
                .map(item -> option(item.getId(), item.getBuildingCode(), item.getBuildingName(),
                        item.getProjectId(), item.getFloorCount() == null ? null : item.getFloorCount() + " 层"))
                .toList();

        List<MasterDataOption> rooms = roomMapper.selectList(
                        Wrappers.<BizRoomEntity>lambdaQuery()
                                .eq(BizRoomEntity::getTenantId, 0L)
                                .eq(BizRoomEntity::getStatus, "ACTIVE")
                                .isNull(BizRoomEntity::getDeletedAt)
                                .orderByAsc(BizRoomEntity::getId)
                                .last("LIMIT " + LIMIT))
                .stream()
                .map(item -> option(item.getId(), item.getRoomNo(),
                        roomName(item), item.getBuildingId(), item.getOccupancyStatus()))
                .toList();

        List<MasterDataOption> customers = customerMapper.selectList(
                        Wrappers.<BizCustomerEntity>lambdaQuery()
                                .eq(BizCustomerEntity::getTenantId, 0L)
                                .eq(BizCustomerEntity::getStatus, "ACTIVE")
                                .isNull(BizCustomerEntity::getDeletedAt)
                                .orderByAsc(BizCustomerEntity::getId)
                                .last("LIMIT " + LIMIT))
                .stream()
                .map(item -> option(item.getId(), item.getCustomerNo(), item.getCustomerName(),
                        item.getProjectId(), item.getContactName()))
                .toList();

        List<MasterDataOption> equipment = equipmentMapper.selectList(
                        Wrappers.<BizEquipmentEntity>lambdaQuery()
                                .eq(BizEquipmentEntity::getTenantId, 0L)
                                .eq(BizEquipmentEntity::getStatus, "NORMAL")
                                .isNull(BizEquipmentEntity::getDeletedAt)
                                .orderByAsc(BizEquipmentEntity::getId)
                                .last("LIMIT " + LIMIT))
                .stream()
                .map(item -> option(item.getId(), item.getEquipmentNo(), item.getEquipmentName(),
                        item.getBuildingId(), item.getLocationText()))
                .toList();

        List<MasterDataOption> serviceItems = serviceItemMapper.selectList(
                        Wrappers.<BizServiceItemEntity>lambdaQuery()
                                .eq(BizServiceItemEntity::getTenantId, 0L)
                                .eq(BizServiceItemEntity::getStatus, "ACTIVE")
                                .isNull(BizServiceItemEntity::getDeletedAt)
                                .orderByAsc(BizServiceItemEntity::getId)
                                .last("LIMIT " + LIMIT))
                .stream()
                .map(item -> option(item.getId(), item.getItemCode(), item.getItemName(),
                        null, item.getDefaultPriority()))
                .toList();

        return new MasterDataOptionsView(projects, buildings, rooms, customers, equipment, serviceItems);
    }

    private MasterDataOption option(Long id, String code, String name, Long parentId, String extra) {
        return new MasterDataOption(
                id == null ? null : String.valueOf(id),
                code == null ? "" : code,
                name == null ? "" : name,
                parentId == null ? null : String.valueOf(parentId),
                StringUtils.hasText(extra) ? extra : null
        );
    }

    private String roomName(BizRoomEntity room) {
        String unit = StringUtils.hasText(room.getUnitNo()) ? room.getUnitNo() + "单元 " : "";
        return unit + (room.getRoomNo() == null ? "" : room.getRoomNo());
    }
}
