package net.firedevops.firemud.accountservice.repository;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver.ObservedSubscription;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/**
 * Narrow canonical persisted provider facts and production billing envelope; never authority alone.
 */
final class AccountHostingSubscriptionLifecycleEvidence {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AccountHostingSubscriptionLifecycleEvidence() {}

  static Map<String, Object> facts(StripeHostingSubscriptionObserver.Result result) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("providerSubscriptionId", result.providerSubscriptionId());
    if (result instanceof ObservedSubscription observed) {
      fields.put("kind", "SDK_OBSERVED");
      fields.put("providerCustomerId", observed.providerCustomerId());
      fields.put("providerPaymentMethodId", observed.providerPaymentMethodId());
      fields.put("providerSubscriptionItemId", observed.providerSubscriptionItemId());
      fields.put("providerPriceId", observed.providerPriceId());
      fields.put("providerQuantity", Long.toString(observed.providerQuantity()));
      fields.put("providerStatus", observed.providerStatus());
      fields.put("periodStart", observed.currentPeriodStart().map(Object::toString).orElse(null));
      fields.put("periodEnd", observed.currentPeriodEnd().map(Object::toString).orElse(null));
      fields.put("cancelAtPeriodEnd", observed.cancelAtPeriodEnd());
      fields.put("cancelAt", observed.cancelAt().map(Object::toString).orElse(null));
      fields.put("canceledAt", observed.canceledAt().map(Object::toString).orElse(null));
      fields.put("endedAt", observed.endedAt().map(Object::toString).orElse(null));
      fields.put("metadata", observed.providerMetadata());
      fields.put("livemode", observed.livemode());
    } else if (result instanceof StripeHostingSubscriptionObserver.InvalidObservation invalid) {
      fields.put("kind", "SDK_INVALID");
      fields.put("reason", invalid.reason().name());
    } else {
      fields.put("kind", "SDK_UNRESOLVED");
      // Exception text can contain sensitive provider details; it is not absence or progress.
    }
    return fields;
  }

  static byte[] observation(StripeHostingSubscriptionObserver.Result result) {
    return observation(
        facts(result),
        result.readStartedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        result.observedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  }

  static byte[] observation(
      Map<String, Object> facts, java.time.Instant readStartedAt, java.time.Instant observedAt) {
    Map<String, Object> fields = new LinkedHashMap<>(facts);
    fields.put("schemaVersion", "account-hosting-provider-observation/v1");
    fields.put("readStartedAt", readStartedAt.toString());
    fields.put("observedAt", observedAt.toString());
    return encode(fields);
  }

  static byte[] encode(Map<String, ?> fields) {
    try {
      byte[] payload = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(fields));
      if (payload.length > 32768) {
        throw new IllegalArgumentException("Hosting evidence exceeds the retained payload bound");
      }
      return payload;
    } catch (IOException failure) {
      throw new IllegalStateException("Hosting evidence encoding failed", failure);
    }
  }

  static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  static BillingEvent billing(
      UUID tenantId,
      UUID requestId,
      String requestDigest,
      long sequence,
      UUID eventId,
      Map<String, Object> receipt) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schemaVersion", "account-hosting-subscription-event/v1");
    fields.put("eventType", "SubscriptionStatusChanged");
    fields.put("tenantBillingStateChanged", Boolean.TRUE.equals(receipt.get("hardCutoff")));
    // One atomic sequenced envelope carries both typed domain notifications for a hard cutoff.
    fields.put(
        "eventTypes",
        Boolean.TRUE.equals(receipt.get("hardCutoff"))
            ? java.util.List.of("SubscriptionStatusChanged", "TenantBillingStateChanged")
            : java.util.List.of("SubscriptionStatusChanged"));
    fields.put("tenantId", tenantId.toString());
    fields.put("requestId", requestId.toString());
    fields.put("requestDigest", requestDigest);
    fields.put("tenantBillingSequence", Long.toString(sequence));
    fields.put("eventId", eventId.toString());
    fields.put("receipt", receipt);
    return new BillingEvent(tenantId, requestId, requestDigest, sequence, eventId, encode(fields));
  }

  static final class BillingEvent {
    final UUID tenantId;
    final UUID requestId;
    final String requestDigest;
    final long sequence;
    final UUID eventId;
    private final byte[] payload;

    BillingEvent(
        UUID tenantId,
        UUID requestId,
        String requestDigest,
        long sequence,
        UUID eventId,
        byte[] payload) {
      this.tenantId = tenantId;
      this.requestId = requestId;
      this.requestDigest = requestDigest;
      this.sequence = sequence;
      this.eventId = eventId;
      this.payload = payload.clone();
    }

    byte[] payload() {
      return payload.clone();
    }

    String digest() {
      return AccountHostingSubscriptionLifecycleEvidence.digest(payload);
    }

    boolean matches(byte[] bytes, String digest) {
      return Arrays.equals(payload, bytes) && digest().equals(digest);
    }
  }
}
