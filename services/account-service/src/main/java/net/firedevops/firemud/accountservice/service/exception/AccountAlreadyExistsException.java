package net.firedevops.firemud.accountservice.service.exception;

/** Signals that account creation collided with an existing username or canonical email. */
public final class AccountAlreadyExistsException extends RuntimeException {
  public AccountAlreadyExistsException(Throwable cause) {
    super("Account already exists", cause);
  }
}
