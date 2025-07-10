package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CraftingRecipeDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull String name,
    @NotNull Long resultItemId,
    int resultQuantity,
    List<CraftingIngredientDto> ingredients) {}
