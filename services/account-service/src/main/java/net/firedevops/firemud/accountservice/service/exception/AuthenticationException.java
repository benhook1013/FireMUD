package net.firedevops.firemud.accountservice.service.exception;

/** Signals a login failure that should be surfaced to callers via explicit error codes. */
public final class AuthenticationException extends RuntimeException {
  private final String code;
  private final int retryAfterSeconds;

  public AuthenticationException(String code, String message) {
    this(code, message, null, 0);
  }

  public AuthenticationException(String code, String message, Throwable cause) {
    this(code, message, cause, 0);
  }

  public AuthenticationException(String code, String message, int retryAfterSeconds) {
    this(code, message, null, retryAfterSeconds);
  }

  private AuthenticationException(
      String code, String message, Throwable cause, int retryAfterSeconds) {
    super(message, cause);
    this.code = code;
    this.retryAfterSeconds = Math.min(3600, Math.max(0, retryAfterSeconds));
  }

  public String getCode() {
    return code;
  }

  /** Optional bounded whole-second retry guidance for neutral temporary throttles. */
  public int getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
