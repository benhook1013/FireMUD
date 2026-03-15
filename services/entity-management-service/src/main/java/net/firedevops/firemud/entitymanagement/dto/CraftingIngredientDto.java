package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;

public record CraftingIngredientDto(@NotNull Long itemId, int quantity) {}
