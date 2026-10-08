package net.firedevops.firemud.gamesession.repository;

/** Exact transition identity or compare-tuple conflicts never fall back to a new operation. */
public final class CanonicalGameplayBindingInventoryConflictException extends RuntimeException {
  public CanonicalGameplayBindingInventoryConflictException(String message) {
    super(message);
  }
}
