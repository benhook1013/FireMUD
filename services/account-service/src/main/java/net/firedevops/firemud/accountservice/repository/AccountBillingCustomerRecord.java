package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Account-local customer intent/observation, not provider authentication or billing authority. */
public record AccountBillingCustomerRecord(
    UUID accountUuid,
    UUID customerProvisioningRequestId,
    UUID providerIdempotencyId,
    long rowVersion,
    State state,
    String providerCustomerId,
    UUID changeRequestId,
    Observation observation,
    String evidenceDigest,
    Instant createdAt,
    Instant updatedAt) {
  public AccountBillingCustomerRecord {
    requireUuid(accountUuid);
    requireUuid(customerProvisioningRequestId);
    requireUuid(providerIdempotencyId);
    requireUuid(changeRequestId);
    Objects.requireNonNull(state, "customer state");
    Objects.requireNonNull(observation, "customer observation");
    Objects.requireNonNull(createdAt, "creation time");
    Objects.requireNonNull(updatedAt, "observation time");
    if (rowVersion < 1 || customerProvisioningRequestId.equals(providerIdempotencyId)) {
      throw new IllegalArgumentException("Customer version/operation identities are invalid");
    }
    if (state == State.UNCONFIRMED) {
      if (observation != Observation.NONE || providerCustomerId != null || evidenceDigest != null) {
        throw new IllegalArgumentException("Unconfirmed customer must not contain confirmation");
      }
    } else {
      requireDigest(evidenceDigest);
      if (state == State.CONFIRMED) {
        requireProviderCustomer(providerCustomerId);
        if (observation != Observation.CONFIRMED) {
          throw new IllegalArgumentException("Customer confirmation is absent");
        }
      } else if (providerCustomerId != null
          || (observation != Observation.AMBIGUOUS && observation != Observation.PROVIDER_ABSENT)) {
        throw new IllegalArgumentException("Reconciliation must remain unconfirmed/nonterminal");
      }
    }
  }

  public enum State {
    UNCONFIRMED,
    RECONCILING,
    CONFIRMED
  }

  public enum Observation {
    NONE,
    AMBIGUOUS,
    PROVIDER_ABSENT,
    CONFIRMED
  }

  static void requireUuid(UUID value) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Non-nil UUID is required");
    }
  }

  static void requireDigest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Exact evidence SHA-256 digest is required");
    }
  }

  static void requireProviderCustomer(String value) {
    if (value == null || value.length() > 255 || !value.matches("cus_[A-Za-z0-9]+")) {
      throw new IllegalArgumentException("Stripe customer reference is required");
    }
  }
}
