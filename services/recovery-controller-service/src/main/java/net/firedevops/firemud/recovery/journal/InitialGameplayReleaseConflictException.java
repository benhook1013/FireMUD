package net.firedevops.firemud.recovery.journal;

public class InitialGameplayReleaseConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public InitialGameplayReleaseConflictException(String message) {
    super(message);
  }
}
