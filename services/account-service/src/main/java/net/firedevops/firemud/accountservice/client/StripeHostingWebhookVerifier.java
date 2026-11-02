package net.firedevops.firemud.accountservice.client;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Authenticates and parses Stripe hosting webhook payloads without applying billing policy.
 *
 * <p>Signature verification establishes only that the exact payload was signed with the configured
 * webhook secret and passes Stripe's signature-freshness check. Account's owner coordinator remains
 * responsible for business authorization, durable event deduplication, provider-ID mapping, and all
 * billing and entitlement authority. This verifier does not select supported event types or make
 * any event a billing or entitlement transition.
 */
public final class StripeHostingWebhookVerifier {
  private final String webhookSecret;
  private final Clock clock;

  public StripeHostingWebhookVerifier(String webhookSecret, Clock clock) {
    if (webhookSecret == null || webhookSecret.isBlank()) {
      throw new IllegalArgumentException("Webhook secret must not be blank");
    }
    this.webhookSecret = webhookSecret;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Verifies the signature and parses the Stripe event from the exact supplied payload and header.
   *
   * <p>The SDK's default 300-second tolerance is Stripe signature freshness, not FireMUD
   * entitlement policy. Signature failures are propagated; successful parsing does not authorize
   * the event for Account business processing.
   *
   * @param rawPayload exact unmodified request payload
   * @param signatureHeader exact Stripe signature header
   * @return an immutable verified envelope after successful signature verification
   * @throws IllegalArgumentException if either input is null or blank
   * @throws SignatureVerificationException if Stripe signature verification fails
   */
  public VerifiedEvent verify(String rawPayload, String signatureHeader)
      throws SignatureVerificationException {
    if (rawPayload == null || rawPayload.isBlank()) {
      throw new IllegalArgumentException("Webhook payload must not be blank");
    }
    if (signatureHeader == null || signatureHeader.isBlank()) {
      throw new IllegalArgumentException("Webhook signature header must not be blank");
    }

    if (!StandardCharsets.UTF_8.newEncoder().canEncode(rawPayload)) {
      throw new IllegalArgumentException("Webhook payload must be representable as exact UTF-8");
    }
    Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    // constructEvent deserializes before checking the signature. Authenticate the exact bytes
    // first so forged malformed JSON never reaches that parser, and malformed timestamps have
    // the same typed rejection as other invalid signature headers.
    try {
      Webhook.Signature.verifyHeader(
          rawPayload, signatureHeader, webhookSecret, Webhook.DEFAULT_TOLERANCE, clock);
    } catch (RuntimeException malformedHeader) {
      throw new SignatureVerificationException(
          "Malformed Stripe signature header", signatureHeader);
    }
    Event event =
        Webhook.constructEvent(
            rawPayload, signatureHeader, webhookSecret, Webhook.DEFAULT_TOLERANCE, clock);
    return new VerifiedEvent(
        rawPayload,
        requiredEventValue(event.getId(), "event ID"),
        requiredEventValue(event.getType(), "event type"),
        Objects.requireNonNull(event.getCreated(), "Stripe event creation time is required"),
        event.getApiVersion(),
        Objects.requireNonNull(event.getLivemode(), "Stripe event livemode is required"),
        receivedAt);
  }

  private static String requiredEventValue(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Verified Stripe " + label + " must not be blank");
    }
    return value;
  }

  private static String sha256(String value) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /**
   * Verified Stripe fields are immutable evidence only. The SDK Event is deliberately not retained:
   * parsing a provider object does not authorize any Account lifecycle transition.
   */
  public static final class VerifiedEvent {
    private final String rawPayload;
    private final String payloadSha256;
    private final String providerEventId;
    private final String eventType;
    private final long eventCreatedAtEpochSeconds;
    private final String providerApiVersion;
    private final boolean livemode;
    private final Instant receivedAt;

    private VerifiedEvent(
        String rawPayload,
        String providerEventId,
        String eventType,
        long eventCreatedAtEpochSeconds,
        String providerApiVersion,
        boolean livemode,
        Instant receivedAt) {
      this.rawPayload = Objects.requireNonNull(rawPayload, "raw payload is required");
      this.payloadSha256 = sha256(rawPayload);
      this.providerEventId = Objects.requireNonNull(providerEventId, "event ID is required");
      this.eventType = Objects.requireNonNull(eventType, "event type is required");
      this.eventCreatedAtEpochSeconds = eventCreatedAtEpochSeconds;
      this.providerApiVersion = providerApiVersion;
      this.livemode = livemode;
      this.receivedAt = Objects.requireNonNull(receivedAt, "received timestamp is required");
    }

    public String rawPayload() {
      return rawPayload;
    }

    public String payloadSha256() {
      return payloadSha256;
    }

    public String providerEventId() {
      return providerEventId;
    }

    public String eventType() {
      return eventType;
    }

    public long eventCreatedAtEpochSeconds() {
      return eventCreatedAtEpochSeconds;
    }

    public String providerApiVersion() {
      return providerApiVersion;
    }

    public boolean livemode() {
      return livemode;
    }

    public Instant receivedAt() {
      return receivedAt;
    }
  }
}
