package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Request body for adding an item to a character's inventory. */
public record AddInventoryItemRequest(@NotNull @Positive Long itemId, int quantity) {
  public AddInventoryItemRequest {
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
  }
}
