package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import net.firedevops.firemud.entitymanagement.entity.ItemStackCompatibilityMode;

public record ItemDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull @Size(max = 100) String name,
    String description,
    String equipmentSlot,
    String equipmentSlotGroupKey,
    boolean container,
    boolean stackable,
    ItemStackCompatibilityMode stackCompatibilityMode,
    String stackVariantKey) {}
