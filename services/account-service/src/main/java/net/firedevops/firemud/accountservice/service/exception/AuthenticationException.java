package net.firedevops.firemud.accountservice.service.exception;

/** Signals a login failure that should be surfaced to callers via explicit error codes. */
public final class AuthenticationException extends RuntimeException {
  private final String code;

  public AuthenticationException(String code, String message) {
    super(message);
    this.code = code;
  }

  public AuthenticationException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
