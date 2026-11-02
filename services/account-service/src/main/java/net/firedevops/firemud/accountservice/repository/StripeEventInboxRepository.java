package net.firedevops.firemud.accountservice.repository;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier.VerifiedEvent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Insert-once Account storage for verified Stripe webhook evidence. It stores no correlation and
 * has no processing, provider mutation, cleanup, or deletion operation.
 */
public final class StripeEventInboxRepository {
  private static final String TABLE = "stripe_event_inbox";
  private static final Logger logger = LoggerFactory.getLogger(StripeEventInboxRepository.class);

  private final DSLContext dsl;

  public StripeEventInboxRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /** Retains a verified event once or returns its original receipt for a stable duplicate event. */
  public StripeEventInboxRecord retainOrReplay(VerifiedEvent event) {
    requireWritableTransaction();
    Objects.requireNonNull(event, "verified Stripe event is required");
    byte[] rawPayload = event.rawPayload().getBytes(StandardCharsets.UTF_8);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (provider_event_id, raw_payload, payload_sha256, event_type, "
                + "provider_created_at_epoch_seconds, provider_api_version, livemode, received_at, "
                + "processing_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'PENDING') "
                + "ON CONFLICT (provider_event_id) DO NOTHING",
            event.providerEventId(),
            rawPayload,
            event.payloadSha256(),
            event.eventType(),
            event.eventCreatedAtEpochSeconds(),
            event.providerApiVersion(),
            event.livemode(),
            OffsetDateTime.ofInstant(event.receivedAt(), ZoneOffset.UTC));
    if (inserted != 0 && inserted != 1) {
      throw new IllegalStateException("Stripe event inbox insert returned an ambiguous row count");
    }

    Optional<StripeEventInboxRecord> retained = findByProviderEventId(event.providerEventId());
    if (retained.isEmpty()) {
      throw new IllegalStateException(
          "Stripe event inbox row is absent after conflict-safe insert");
    }
    StripeEventInboxRecord original = retained.orElseThrow();
    if (!sameStableEventIdentity(original, event)) {
      throw new StripeEventInboxConflictException(event.providerEventId());
    }
    if (inserted == 1) {
      if (!sameInitialReceiptPayload(original, event, rawPayload)) {
        throw new IllegalStateException("Stripe event inbox first receipt changed on readback");
      }
      if (!original.receivedAt().equals(event.receivedAt())) {
        throw new IllegalStateException("Stripe event inbox receipt timestamp changed on readback");
      }
    } else if (!sameRawPayload(original, rawPayload)) {
      logger.debug(
          "Verified duplicate Stripe event had different delivery serialization; "
              + "preserving first receipt: eventId={}",
          event.providerEventId());
    }
    return original;
  }

  private Optional<StripeEventInboxRecord> findByProviderEventId(String providerEventId) {
    Record row =
        dsl.fetchOne(
            "SELECT provider_event_id, raw_payload, payload_sha256, event_type, "
                + "provider_created_at_epoch_seconds, provider_api_version, livemode, received_at, "
                + "processing_status FROM "
                + TABLE
                + " WHERE provider_event_id = ?",
            providerEventId);
    return row == null ? Optional.empty() : Optional.of(decode(row));
  }

  private static StripeEventInboxRecord decode(Record row) {
    byte[] rawPayload = required(row.get("raw_payload", byte[].class), "raw payload");
    String exactPayload;
    try {
      exactPayload =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(rawPayload))
              .toString();
    } catch (CharacterCodingException malformedPayload) {
      throw new IllegalStateException(
          "Stored Stripe event payload is not exact UTF-8", malformedPayload);
    }
    OffsetDateTime receivedAt =
        required(row.get("received_at", OffsetDateTime.class), "received timestamp");
    return new StripeEventInboxRecord(
        required(row.get("provider_event_id", String.class), "provider event ID"),
        exactPayload,
        required(row.get("payload_sha256", String.class), "payload digest"),
        required(row.get("event_type", String.class), "event type"),
        required(row.get("provider_created_at_epoch_seconds", Long.class), "provider event time"),
        row.get("provider_api_version", String.class),
        required(row.get("livemode", Boolean.class), "livemode"),
        receivedAt.toInstant(),
        required(row.get("processing_status", String.class), "processing status"));
  }

  private static boolean sameStableEventIdentity(
      StripeEventInboxRecord retained, VerifiedEvent event) {
    return retained.providerEventId().equals(event.providerEventId())
        && retained.eventType().equals(event.eventType())
        && retained.eventCreatedAtEpochSeconds() == event.eventCreatedAtEpochSeconds()
        && Objects.equals(retained.providerApiVersion(), event.providerApiVersion())
        && retained.livemode() == event.livemode()
        && StripeEventInboxRecord.PENDING.equals(retained.processingStatus());
  }

  private static boolean sameInitialReceiptPayload(
      StripeEventInboxRecord retained, VerifiedEvent event, byte[] incomingPayload) {
    return sameRawPayload(retained, incomingPayload)
        && retained.payloadSha256().equals(event.payloadSha256());
  }

  private static boolean sameRawPayload(StripeEventInboxRecord retained, byte[] incomingPayload) {
    return Arrays.equals(retained.rawPayload().getBytes(StandardCharsets.UTF_8), incomingPayload);
  }

  private static void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Stripe event inbox writes require an active writable Account transaction");
    }
  }

  private static <T> T required(T value, String label) {
    if (value == null) {
      throw new IllegalStateException("Stored Stripe event " + label + " is absent");
    }
    return value;
  }
}
