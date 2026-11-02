package net.firedevops.firemud.accountservice.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable retained webhook receipt; pending means no provider or billing correlation was made.
 */
public record StripeEventInboxRecord(
    String providerEventId,
    String rawPayload,
    String payloadSha256,
    String eventType,
    long eventCreatedAtEpochSeconds,
    String providerApiVersion,
    boolean livemode,
    Instant receivedAt,
    String processingStatus) {
  public static final String PENDING = "PENDING";

  public StripeEventInboxRecord {
    requireNonblank(providerEventId, "Stripe event ID");
    Objects.requireNonNull(rawPayload, "raw Stripe payload is required");
    requireNonblank(payloadSha256, "Stripe payload digest");
    requireNonblank(eventType, "Stripe event type");
    Objects.requireNonNull(receivedAt, "local received timestamp is required");
    if (!PENDING.equals(processingStatus)) {
      throw new IllegalArgumentException("Stripe intake receipts must remain pending");
    }
    if (!payloadSha256.equals(sha256(rawPayload))) {
      throw new IllegalArgumentException(
          "Stripe payload digest does not match exact payload bytes");
    }
  }

  static String sha256(String payload) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void requireNonblank(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
  }
}
