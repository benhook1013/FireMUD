package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;
import java.util.UUID;

/**
 * Unregistered composition input, not a route grant. A genuine producer must authenticate/authorize
 * the actor, resolve the plan and owner, and verify current authority before this local SQL
 * boundary. Neither these values nor their digest authenticate anyone. Only exact saved instruments
 * exist here.
 */
public record AccountSubscriptionCreateRequest(
    UUID requestId,
    UUID tenantId,
    OwnerFence owner,
    String planCode,
    UUID paymentInstrumentId,
    long instrumentVersion,
    ProducerEvidence producer,
    ReplacementFence replacement) {
  public AccountSubscriptionCreateRequest {
    AccountBillingCustomerRecord.requireUuid(requestId);
    Objects.requireNonNull(owner, "resolved owner fence");
    Objects.requireNonNull(producer, "authenticated producer evidence input");
    AccountSubscriptionCreateDigest.encode(
        tenantId, owner.accountUuid(), planCode, paymentInstrumentId);
    if (planCode.length() > 255 || instrumentVersion < 1 || instrumentVersion == Long.MAX_VALUE) {
      throw new IllegalArgumentException(
          "Bounded normalized plan and advanceable instrument version are required");
    }
  }

  public record OwnerFence(UUID accountUuid, long version, UUID generation) {
    public OwnerFence {
      AccountBillingCustomerRecord.requireUuid(accountUuid);
      AccountBillingCustomerRecord.requireUuid(generation);
      if (version < 1) {
        throw new IllegalArgumentException("Positive captured owner version is required");
      }
    }
  }

  public record ProducerEvidence(
      UUID actorAccountUuid, UUID authorizationOperationId, String authorizationDigest) {
    public ProducerEvidence {
      AccountBillingCustomerRecord.requireUuid(actorAccountUuid);
      AccountBillingCustomerRecord.requireUuid(authorizationOperationId);
      AccountBillingCustomerRecord.requireDigest(authorizationDigest);
    }
  }

  /**
   * Internal expected-current replacement CAS, not an update-only public CreateSubscription field.
   */
  public record ReplacementFence(UUID subscriptionId, long expectedRowVersion) {
    public ReplacementFence {
      AccountBillingCustomerRecord.requireUuid(subscriptionId);
      if (expectedRowVersion < 1 || expectedRowVersion == Long.MAX_VALUE) {
        throw new IllegalArgumentException("Advanceable canceled-current row version is required");
      }
    }
  }
}
