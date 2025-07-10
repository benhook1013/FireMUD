package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;

public record CraftingIngredientDto(@NotNull Long itemId, int quantity) {}
