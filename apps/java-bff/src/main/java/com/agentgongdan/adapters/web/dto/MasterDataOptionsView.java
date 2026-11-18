package com.agentgongdan.adapters.web.dto;

import java.util.List;

public record MasterDataOptionsView(
        List<MasterDataOption> projects,
        List<MasterDataOption> buildings,
        List<MasterDataOption> rooms,
        List<MasterDataOption> customers,
        List<MasterDataOption> equipment,
        List<MasterDataOption> serviceItems
) {
}
