package net.firedevops.firemud.entitymanagement.service;

/** Entity-owned numeric runtime identity; qualified by tenant and game instance in a reference. */
public record RuntimeEntityId(long value) {
  public RuntimeEntityId {
    if (value <= 0) {
      throw new IllegalArgumentException("runtimeEntityId must be positive");
    }
  }
}
