-- V13: 房间唯一键加入单元号，允许同一楼宇不同单元存在相同房号。
ALTER TABLE biz_room
    DROP INDEX uk_biz_room_no,
    ADD UNIQUE KEY uk_biz_room_no (tenant_id, building_id, unit_no, room_no);
