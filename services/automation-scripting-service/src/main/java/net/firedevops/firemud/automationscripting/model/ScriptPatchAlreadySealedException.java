package net.firedevops.firemud.automationscripting.model;

/** A retained immutable patch snapshot cannot be replaced by a different seal. */
public final class ScriptPatchAlreadySealedException extends IllegalStateException {
  public ScriptPatchAlreadySealedException(String message) {
    super(message);
  }
}
