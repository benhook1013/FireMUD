package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;

public record ContainerContentEntryDto(
    @NotNull Long tenantId,
    @NotNull Long characterId,
    @NotNull Long containerInstanceId,
    @NotNull Long itemId,
    @NotNull String itemName,
    String itemDescription,
    int quantity,
    Long itemInstanceId,
    String visibleRef) {}
