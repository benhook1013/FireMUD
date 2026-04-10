package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;

public record InventoryEntryDto(
    @NotNull Long tenantId,
    @NotNull Long characterId,
    @NotNull Long itemId,
    @NotNull String itemName,
    String itemDescription,
    int quantity,
    Long itemInstanceId,
    Long containerInstanceId,
    String visibleRef) {}
