package net.firedevops.firemud.gamesession.service;

/** Indicates retained session context could not be read safely from its backing store. */
public final class SessionContextUnavailableException extends IllegalStateException {
  public SessionContextUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
