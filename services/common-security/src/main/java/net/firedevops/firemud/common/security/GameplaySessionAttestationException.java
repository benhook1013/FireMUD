package net.firedevops.firemud.common.security;

public class GameplaySessionAttestationException extends RuntimeException {
  private final String code;

  public GameplaySessionAttestationException(String code, String message) {
    super(message);
    this.code = code;
  }

  public GameplaySessionAttestationException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
