package net.firedevops.firemud.common.security;

/** Raised when the current caller lacks the required admin-level role. */
public class AdminAuthorizationException extends RuntimeException {
  public AdminAuthorizationException(String message) {
    super(message);
  }
}
