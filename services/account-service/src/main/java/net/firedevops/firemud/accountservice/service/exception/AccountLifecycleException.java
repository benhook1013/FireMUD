package net.firedevops.firemud.accountservice.service.exception;

public class AccountLifecycleException extends RuntimeException {
  private final String code;

  public AccountLifecycleException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
