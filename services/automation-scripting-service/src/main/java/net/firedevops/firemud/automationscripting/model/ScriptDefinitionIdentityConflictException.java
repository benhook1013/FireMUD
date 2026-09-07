package net.firedevops.firemud.automationscripting.model;

/** Signals an attempt to change a script definition's immutable stable identity. */
public final class ScriptDefinitionIdentityConflictException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public ScriptDefinitionIdentityConflictException(String message) {
    super(message);
  }
}
