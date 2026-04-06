package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;

public record RoomGroundInventoryEntryDto(
    @NotNull Long tenantId,
    @NotNull String gameInstanceId,
    @NotNull String roomInstanceId,
    @NotNull Long itemId,
    @NotNull String itemName,
    String itemDescription,
    int quantity,
    Long containerInstanceId) {}
