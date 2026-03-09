package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;

/** Request body for adding an item to a character's inventory. */
public record AddInventoryItemRequest(@NotNull Long itemId, int quantity) {
  public AddInventoryItemRequest {
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
  }
}
