package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;

public record InventoryEntryDto(@NotNull Long characterId, @NotNull Long itemId, int quantity) {}
