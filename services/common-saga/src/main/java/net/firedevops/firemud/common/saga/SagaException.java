package net.firedevops.firemud.common.saga;

/** Exception thrown when a saga fails to complete successfully. */
public class SagaException extends Exception {
  public SagaException(String message, Throwable cause) {
    super(message, cause);
  }
}
