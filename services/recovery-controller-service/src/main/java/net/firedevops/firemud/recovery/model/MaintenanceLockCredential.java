package net.firedevops.firemud.recovery.model;

import java.util.Objects;
import java.util.UUID;

/** The controller-issued lock credential; string rendering is intentionally redacted. */
public final class MaintenanceLockCredential {
  private final UUID identity;

  private MaintenanceLockCredential(UUID identity) {
    this.identity = InitialGameplayReleaseModel.requiredUuid(identity, "identity");
  }

  public static MaintenanceLockCredential issue(UUID identity) {
    return new MaintenanceLockCredential(identity);
  }

  public static MaintenanceLockCredential fromProtectedTransport(String token) {
    Objects.requireNonNull(token, "token must not be null");
    try {
      UUID identity = UUID.fromString(token);
      if (!identity.toString().equals(token)) {
        throw new IllegalArgumentException("maintenance-lock credential is not canonical");
      }
      return new MaintenanceLockCredential(identity);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("maintenance-lock credential is invalid");
    }
  }

  public UUID identity() {
    return identity;
  }

  public String protectedTransportValue() {
    return identity.toString();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof MaintenanceLockCredential credential
        && identity.equals(credential.identity);
  }

  @Override
  public int hashCode() {
    return identity.hashCode();
  }

  @Override
  public String toString() {
    return "MaintenanceLockCredential[redacted]";
  }
}
