package net.firedevops.firemud.accountservice.security;

/** Fail-closed result for an unavailable key ring or an invalid envelope operation. */
public final class AccountEnvelopeCryptoException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Failure {
    KEY_RING_UNAVAILABLE,
    KEY_RING_MALFORMED,
    KEY_UNAVAILABLE,
    INVALID_ENVELOPE,
    PURPOSE_MISMATCH,
    AUTHENTICATION_FAILED,
    CRYPTO_OPERATION_FAILED
  }

  private final Failure failure;

  AccountEnvelopeCryptoException(Failure failure) {
    super("Account response-envelope operation failed: " + failure.name());
    this.failure = failure;
  }

  public Failure failure() {
    return failure;
  }
}
