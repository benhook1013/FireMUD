package net.firedevops.firemud.gamesession.service;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;

/**
 * One credential-bearing text LOGIN presentation transferred into the original-attempt owner. The
 * owner wipes this object's mutable secret buffer at the end of its invocation; transport and
 * protobuf/library copies are outside that erasure guarantee.
 */
public final class InitialGameplayLoginCredentialPresentation implements AutoCloseable {
  private final String normalizedEmail;
  private final String canonicalClientAddress;
  private final char[] ownedSecret;
  private final Object custodyMutex = new Object();
  private boolean current = true;
  private boolean requestCreated;

  /** Ownership of {@code ownedSecret} transfers to this presentation. */
  InitialGameplayLoginCredentialPresentation(
      String normalizedEmail, String canonicalClientAddress, char[] ownedSecret) {
    this.ownedSecret = Objects.requireNonNull(ownedSecret, "Owned credential buffer required");
    try {
      this.normalizedEmail = requireEmail(normalizedEmail);
      this.canonicalClientAddress = requireAddress(canonicalClientAddress);
      if (ownedSecret.length == 0 || ownedSecret.length > 1024) {
        throw new IllegalArgumentException("Credential presentation is invalid");
      }
    } catch (RuntimeException invalid) {
      Arrays.fill(this.ownedSecret, '\0');
      current = false;
      throw new IllegalArgumentException("Credential presentation is invalid");
    }
  }

  /**
   * Builds the only Authenticate envelope for these credentials and binds it to server coordinates.
   * The returned protobuf is transient and must not be retained after the Account invocation.
   */
  AuthenticateRequest createOriginalRequest(InitialGameplayLoginAttemptCoordinates coordinates) {
    synchronized (custodyMutex) {
      Objects.requireNonNull(coordinates, "Original coordinates required");
      if (!current || requestCreated) throw new CustodyLostException();
      requestCreated = true;
      var request =
          AuthenticateRequest.newBuilder()
              .setEmail(normalizedEmail)
              .setPassword(new String(ownedSecret))
              .setRequestId(coordinates.requestId().toString())
              .setSourceContext(
                  GameplayLoginSourceContext.newBuilder()
                      .setCallerContextId(coordinates.callerContextId().toString())
                      .setCanonicalClientAddress(canonicalClientAddress)
                      .setTransportClass(
                          GameplayLoginTransportClass
                              .GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY))
              .build();
      CanonicalGameplayLoginResult.requireOriginalRequest(request);
      return request;
    }
  }

  public boolean isCurrent() {
    synchronized (custodyMutex) {
      return current;
    }
  }

  @Override
  public void close() {
    synchronized (custodyMutex) {
      if (current) {
        current = false;
        Arrays.fill(ownedSecret, '\0');
      }
    }
  }

  @Override
  public String toString() {
    return "InitialGameplayLoginCredentialPresentation[redacted]";
  }

  private static String requireEmail(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 320
        || !value.equals(value.trim())
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Credential presentation is invalid");
    }
    return value;
  }

  private static String requireAddress(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 256
        || !value.equals(value.trim())
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Credential presentation is invalid");
    }
    return value;
  }

  static final class CustodyLostException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    CustodyLostException() {
      super("Original LOGIN private custody is unavailable");
    }
  }
}
