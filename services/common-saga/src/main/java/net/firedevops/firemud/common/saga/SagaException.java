package net.firedevops.firemud.common.saga;

/** Exception thrown when a short synchronous saga fails to complete successfully. */
public class SagaException extends Exception {
  public SagaException(String message, Throwable cause) {
    super(message, cause);
  }
}
