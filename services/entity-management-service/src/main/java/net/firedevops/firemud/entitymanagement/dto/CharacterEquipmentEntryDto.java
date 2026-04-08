package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;

public record CharacterEquipmentEntryDto(
    @NotNull Long tenantId,
    @NotNull Long characterId,
    @NotNull String slot,
    @NotNull Long itemId,
    @NotNull String itemName,
    String itemDescription,
    Long itemInstanceId,
    Long containerInstanceId) {}
