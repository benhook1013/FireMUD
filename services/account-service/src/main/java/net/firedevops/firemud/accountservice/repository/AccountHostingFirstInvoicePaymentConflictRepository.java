package net.firedevops.firemud.accountservice.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient.Observation;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier.VerifiedEvent;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Account-owned immutable record of a verified payment succeeding after retained cancellation. */
final class AccountHostingFirstInvoicePaymentConflictRepository {
  private static final String TABLE = "subscription_first_invoice_late_paid_conflicts";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final DSLContext dsl;

  AccountHostingFirstInvoicePaymentConflictRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Returns the retained operator request for an exact event replay or an event linked to that
   * conflict. The cancellation record remains addressable after its subscription row is superseded.
   */
  UUID retainedConflictRequestId(VerifiedEvent verified) {
    requireTransaction();
    Objects.requireNonNull(verified, "verified Stripe event is required");
    Record row =
        dsl.fetchOne(
            "SELECT COALESCE(conflict.request_id, conflict.cancellation_request_id) AS request_id, "
                + "conflict.event_payload_digest, inbox.raw_payload, "
                + "inbox.payload_sha256, inbox.event_type, inbox.provider_created_at_epoch_seconds, "
                + "inbox.provider_api_version, inbox.livemode "
                + "FROM "
                + TABLE
                + " conflict JOIN stripe_event_inbox inbox USING (provider_event_id) "
                + "WHERE conflict.provider_event_id = ?",
            verified.providerEventId());
    if (row != null) {
      requireExactEvent(verified, row);
      return row.get("request_id", UUID.class);
    }
    Record related =
        dsl.fetchOne(
            "SELECT COALESCE(conflict.request_id, conflict.cancellation_request_id) AS request_id, "
                + "related.event_payload_digest, inbox.raw_payload, inbox.payload_sha256, inbox.event_type, "
                + "inbox.provider_created_at_epoch_seconds, inbox.provider_api_version, inbox.livemode, "
                + "original_inbox.raw_payload AS original_raw_payload "
                + "FROM subscription_first_invoice_late_paid_conflict_events related "
                + "JOIN "
                + TABLE
                + " conflict ON conflict.provider_event_id = related.conflict_provider_event_id "
                + "JOIN stripe_event_inbox inbox ON inbox.provider_event_id = related.provider_event_id "
                + "JOIN stripe_event_inbox original_inbox ON original_inbox.provider_event_id = conflict.provider_event_id "
                + "WHERE related.provider_event_id = ?",
            verified.providerEventId());
    if (related == null) return null;
    requireExactEvent(verified, related);
    if (!samePaidIntentTuple(
        decodeUtf8(related.get("original_raw_payload", byte[].class)), verified.rawPayload()))
      throw refused();
    return related.get("request_id", UUID.class);
  }

  /**
   * Retains a paid conflict only when the same Account subscription is already canceled by its
   * exact committed unpaid-first-invoice receipt. The payment owner has already verified the signed
   * tuple and provider readback. Null leaves its existing fulfillment path in charge.
   */
  UUID retainIfCanceled(
      VerifiedEvent verified,
      AccountHostingFirstInvoicePayment invoice,
      ConfirmationDispatch candidate,
      Observation observed) {
    requireWritableTransaction();
    Objects.requireNonNull(verified, "verified Stripe event is required");
    Objects.requireNonNull(invoice, "retained first invoice is required");

    UUID replayRequestId = retainedConflictRequestId(verified);
    if (replayRequestId != null) {
      if (!retainedEventBelongsToInvoice(verified.providerEventId(), invoice.subscriptionId())
          || (candidate != null
              && (!candidate.requestId().equals(replayRequestId)
                  || !candidate.invoice().subscriptionId().equals(invoice.subscriptionId())
                  || !candidate.invoice().digest().equals(invoice.digest())))) throw refused();
      return replayRequestId;
    }
    Record current =
        dsl.fetchOne(
            "SELECT status, provider_subscription_id, current_receipt_request_id, "
                + "lifecycle_inflight_request_id "
                + "FROM subscription WHERE subscription_id = ? FOR UPDATE",
            invoice.subscriptionId());
    if (current == null) throw refused();
    if (!"canceled".equals(current.get("status", String.class))) return null;
    if (current.get("current_receipt_request_id", UUID.class) == null
        || current.get("lifecycle_inflight_request_id", UUID.class) != null
        || !invoice
            .providerSubscriptionId()
            .equals(current.get("provider_subscription_id", String.class))) throw refused();

    Record cancellation = exactCommittedCancellation(invoice);
    if (cancellation == null) return null;

    // The signed event has already been retained by webhook intake in the usual path. Retaining
    // here as well keeps this owner safe when called directly, while an altered duplicate payload
    // cannot be rebound to the first immutable inbox receipt.
    StripeEventInboxRecord intake = new StripeEventInboxRepository(dsl).retainOrReplay(verified);
    if (!intake.payloadSha256().equals(StripeEventInboxRecord.sha256(intake.rawPayload()))
        || !sameSignedSuccessTuple(intake.rawPayload(), verified.rawPayload())) throw refused();

    String originalConflictEventId = existingConflictEventId(invoice.subscriptionId());
    int inserted;
    if (originalConflictEventId != null) {
      inserted =
          dsl.execute(
              "INSERT INTO subscription_first_invoice_late_paid_conflict_events "
                  + "(provider_event_id, conflict_provider_event_id, event_payload_digest, "
                  + "provider_subscription_id, provider_customer_id, provider_payment_method_id, "
                  + "provider_price_id, provider_quantity, amount, currency, livemode, observed_status, "
                  + "observation_read_started_at, observation_observed_at, observation_expires_at) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, "
                  + "?::timestamptz) ON CONFLICT DO NOTHING",
              intake.providerEventId(),
              originalConflictEventId,
              intake.payloadSha256(),
              observed.providerSubscriptionId(),
              observed.providerCustomerId(),
              observed.providerPaymentMethodId(),
              observed.providerPriceId(),
              observed.providerQuantity(),
              observed.amount(),
              observed.currency(),
              observed.livemode(),
              observed.status(),
              utcMicros(observed.readStartedAt()),
              utcMicros(observed.observedAt()),
              utcMicros(observed.readStartedAt().plusSeconds(20)));
    } else {
      inserted =
          dsl.execute(
              "INSERT INTO "
                  + TABLE
                  + " (subscription_id, request_id, lifecycle_request_id, creation_request_id, tuple_digest, "
                  + "invoice_id, payment_intent_id, provider_event_id, event_payload_digest, provider_subscription_id, "
                  + "provider_customer_id, provider_payment_method_id, provider_price_id, provider_quantity, amount, "
                  + "currency, livemode, observed_status, observation_read_started_at, observation_observed_at, "
                  + "observation_expires_at, cancellation_request_id, cancellation_receipt_digest, "
                  + "cancellation_row_version, cancellation_committed_transaction_id) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, "
                  + "?::timestamptz, ?::timestamptz, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
              invoice.subscriptionId(),
              candidate == null ? null : candidate.requestId(),
              candidate == null ? null : candidate.lifecycleRequestId(),
              invoice.creationRequestId(),
              invoice.digest(),
              invoice.invoiceId(),
              invoice.paymentIntentId(),
              intake.providerEventId(),
              intake.payloadSha256(),
              observed.providerSubscriptionId(),
              observed.providerCustomerId(),
              observed.providerPaymentMethodId(),
              observed.providerPriceId(),
              observed.providerQuantity(),
              observed.amount(),
              observed.currency(),
              observed.livemode(),
              observed.status(),
              utcMicros(observed.readStartedAt()),
              utcMicros(observed.observedAt()),
              utcMicros(observed.readStartedAt().plusSeconds(20)),
              cancellation.get("cancellation_request_id", UUID.class),
              cancellation.get("cancellation_receipt_digest", String.class),
              cancellation.get("cancellation_row_version", Long.class),
              cancellation.get("cancellation_committed_transaction_id", Long.class));
    }
    UUID retainedRequestId = retainedConflictRequestId(verified);
    UUID expectedRequestId =
        candidate != null
            ? candidate.requestId()
            : originalConflictEventId == null
                ? cancellation.get("cancellation_request_id", UUID.class)
                : retainedRequestId;
    if ((inserted != 0 && inserted != 1)
        || retainedRequestId == null
        || !expectedRequestId.equals(retainedRequestId)) throw refused();
    return retainedRequestId;
  }

  private String existingConflictEventId(UUID subscriptionId) {
    Record row =
        dsl.fetchOne(
            "SELECT provider_event_id FROM " + TABLE + " WHERE subscription_id = ?",
            subscriptionId);
    return row == null ? null : row.get("provider_event_id", String.class);
  }

  private boolean retainedEventBelongsToInvoice(String providerEventId, UUID subscriptionId) {
    return dsl.fetchOne(
            "SELECT retained.subscription_id FROM ("
                + "SELECT subscription_id FROM "
                + TABLE
                + " WHERE provider_event_id = ? UNION ALL "
                + "SELECT conflict.subscription_id FROM subscription_first_invoice_late_paid_conflict_events related "
                + "JOIN "
                + TABLE
                + " conflict ON conflict.provider_event_id = related.conflict_provider_event_id "
                + "WHERE related.provider_event_id = ?) retained WHERE retained.subscription_id = ?",
            providerEventId,
            providerEventId,
            subscriptionId)
        != null;
  }

  private Record exactCommittedCancellation(AccountHostingFirstInvoicePayment invoice) {
    return dsl.fetchOne(
        "SELECT operation.request_id AS cancellation_request_id, receipt.receipt_digest AS cancellation_receipt_digest, "
            + "receipt.subscription_row_version AS cancellation_row_version, "
            + "receipt.committed_transaction_id AS cancellation_committed_transaction_id "
            + "FROM subscription s "
            + "JOIN subscription_provisioning_work work ON work.request_id = ? AND work.subscription_id = s.subscription_id "
            + "JOIN subscription_first_invoices first_invoice ON first_invoice.subscription_id = s.subscription_id "
            + "JOIN subscription_provider_terminal_resolutions terminal ON terminal.resolution_id = s.terminal_resolution_id "
            + "AND terminal.subscription_id = s.subscription_id "
            + "JOIN subscription_lifecycle_observations observation ON observation.evidence_digest = terminal.evidence_digest "
            + "JOIN subscription_lifecycle_receipts receipt ON receipt.request_id = observation.request_id "
            + "AND receipt.attempt_id = observation.attempt_id AND receipt.observation_digest = observation.evidence_digest "
            + "JOIN subscription_lifecycle_operations operation ON operation.request_id = receipt.request_id "
            + "AND operation.subscription_id = receipt.subscription_id "
            + "WHERE s.subscription_id = ? AND s.status = 'canceled' "
            + "AND (first_invoice.creation_request_id, first_invoice.tenant_id, first_invoice.tuple_digest, "
            + "first_invoice.invoice_id, first_invoice.payment_intent_id, first_invoice.provider_subscription_id, "
            + "first_invoice.provider_customer_id, first_invoice.provider_payment_method_id, first_invoice.provider_price_id, "
            + "first_invoice.provider_quantity, first_invoice.amount, first_invoice.currency, first_invoice.quote_digest, first_invoice.livemode) "
            + "= (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "AND operation.state = 'COMMITTED' AND operation.provisioning_attempt_id IS NULL "
            + "AND operation.creation_request_id = first_invoice.creation_request_id "
            + "AND operation.tenant_id = first_invoice.tenant_id "
            + "AND operation.provider_subscription_id = first_invoice.provider_subscription_id "
            + "AND receipt.status = 'canceled' AND receipt.provider_subscription_id = first_invoice.provider_subscription_id "
            + "AND receipt.subscription_row_version <= s.row_version "
            + "AND EXISTS (SELECT 1 FROM subscription_lifecycle_operations current_operation "
            + "JOIN subscription_lifecycle_receipts current_receipt ON current_receipt.request_id = current_operation.request_id "
            + "AND current_receipt.subscription_id = current_operation.subscription_id "
            + "WHERE current_operation.request_id = s.current_receipt_request_id "
            + "AND current_operation.subscription_id = s.subscription_id AND current_operation.state = 'COMMITTED' "
            + "AND current_operation.provider_subscription_id = first_invoice.provider_subscription_id "
            + "AND current_receipt.status = 'canceled' AND current_receipt.provider_subscription_id = first_invoice.provider_subscription_id "
            + "AND current_receipt.subscription_row_version >= receipt.subscription_row_version "
            + "AND current_receipt.subscription_row_version <= s.row_version "
            + "AND (s.superseded_at IS NOT NULL OR current_receipt.subscription_row_version = s.row_version) "
            + "AND current_receipt.committed_transaction_id <> txid_current()) "
            + "AND work.phase = 'CANCELED' AND work.last_attempt_id IS NOT NULL "
            + "AND terminal.request_id = first_invoice.creation_request_id AND terminal.outcome = 'CANCELED' "
            + "AND terminal.provider_subscription_id = first_invoice.provider_subscription_id "
            + "AND observation.raw_status IN ('canceled', 'incomplete_expired') "
            + "AND EXISTS (SELECT 1 FROM subscription_instrument_reservations reservation "
            + "WHERE reservation.subscription_id = s.subscription_id AND reservation.request_id = first_invoice.creation_request_id "
            + "AND reservation.terminal_resolution_id = terminal.resolution_id AND reservation.released_at IS NOT NULL) "
            + "AND convert_from(operation.request_payload, 'UTF8')::jsonb->>'schemaVersion' = ? "
            + "AND receipt.committed_transaction_id <> txid_current()",
        invoice.creationRequestId(),
        invoice.subscriptionId(),
        invoice.creationRequestId(),
        invoice.tenantId(),
        invoice.digest(),
        invoice.invoiceId(),
        invoice.paymentIntentId(),
        invoice.providerSubscriptionId(),
        invoice.providerCustomerId(),
        invoice.providerPaymentMethodId(),
        invoice.providerPriceId(),
        invoice.providerQuantity(),
        invoice.amount(),
        invoice.currency(),
        invoice.quoteDigest(),
        invoice.livemode(),
        "account-hosting-unpaid-first-invoice-cancellation/v1");
  }

  private static void requireExactEvent(VerifiedEvent verified, Record row) {
    byte[] retainedPayload = row.get("raw_payload", byte[].class);
    if (!"payment_intent.succeeded".equals(verified.eventType())
        || !row.get("payload_sha256", String.class)
            .equals(row.get("event_payload_digest", String.class))
        || !sha256(retainedPayload).equals(row.get("payload_sha256", String.class))
        || !verified.eventType().equals(row.get("event_type", String.class))
        || verified.eventCreatedAtEpochSeconds()
            != row.get("provider_created_at_epoch_seconds", Long.class)
        || !Objects.equals(
            verified.providerApiVersion(), row.get("provider_api_version", String.class))
        || verified.livemode() != row.get("livemode", Boolean.class)
        || !sameSignedSuccessTuple(decodeUtf8(retainedPayload), verified.rawPayload()))
      throw refused();
  }

  private static boolean sameSignedSuccessTuple(String retainedPayload, String incomingPayload) {
    return signedSuccessTuple(retainedPayload).equals(signedSuccessTuple(incomingPayload));
  }

  private static boolean samePaidIntentTuple(String retainedPayload, String incomingPayload) {
    SignedSuccessTuple retained = signedSuccessTuple(retainedPayload);
    SignedSuccessTuple incoming = signedSuccessTuple(incomingPayload);
    return retained.paymentIntentId().equals(incoming.paymentIntentId())
        && retained.customerId().equals(incoming.customerId())
        && retained.paymentMethodId().equals(incoming.paymentMethodId())
        && retained.amount() == incoming.amount()
        && retained.amountReceived() == incoming.amountReceived()
        && retained.currency().equals(incoming.currency())
        && retained.livemode() == incoming.livemode();
  }

  private static SignedSuccessTuple signedSuccessTuple(String payload) {
    try {
      JsonNode event = JSON.readTree(payload);
      if (event == null || !event.isObject() || !event.path("data").isObject()) throw refused();
      JsonNode object = event.path("data").path("object");
      if (!object.isObject()) throw refused();
      JsonNode eventId = event.path("id");
      JsonNode eventType = event.path("type");
      JsonNode created = event.path("created");
      JsonNode apiVersion = event.path("api_version");
      JsonNode eventLivemode = event.path("livemode");
      JsonNode objectType = object.path("object");
      JsonNode status = object.path("status");
      JsonNode id = object.path("id");
      JsonNode customer = object.path("customer");
      JsonNode paymentMethod = object.path("payment_method");
      JsonNode amount = object.path("amount");
      JsonNode amountReceived = object.path("amount_received");
      JsonNode currency = object.path("currency");
      JsonNode livemode = object.path("livemode");
      if (!text(eventId)
          || !"payment_intent.succeeded".equals(eventType.asText())
          || !created.isIntegralNumber()
          || !created.canConvertToLong()
          || !(apiVersion.isMissingNode() || apiVersion.isNull() || apiVersion.isTextual())
          || !eventLivemode.isBoolean()
          || !"payment_intent".equals(objectType.asText())
          || !"succeeded".equals(status.asText())
          || !text(id)
          || !text(customer)
          || !text(paymentMethod)
          || !amount.isIntegralNumber()
          || !amount.canConvertToLong()
          || !amountReceived.isIntegralNumber()
          || !amountReceived.canConvertToLong()
          || !currency.isTextual()
          || currency.asText().isBlank()
          || !livemode.isBoolean()) throw refused();
      return new SignedSuccessTuple(
          eventId.asText(),
          created.asLong(),
          apiVersion.isTextual() ? apiVersion.asText() : null,
          eventLivemode.asBoolean(),
          id.asText(),
          customer.asText(),
          paymentMethod.asText(),
          amount.asLong(),
          amountReceived.asLong(),
          currency.asText(),
          livemode.asBoolean());
    } catch (JacksonException malformed) {
      throw refused();
    }
  }

  private static boolean text(JsonNode value) {
    return value.isTextual() && !value.asText().isBlank();
  }

  private static String decodeUtf8(byte[] payload) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(payload))
          .toString();
    } catch (java.nio.charset.CharacterCodingException malformed) {
      throw refused();
    }
  }

  private static String sha256(byte[] payload) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record SignedSuccessTuple(
      String eventId,
      long eventCreatedAtEpochSeconds,
      String providerApiVersion,
      boolean eventLivemode,
      String paymentIntentId,
      String customerId,
      String paymentMethodId,
      long amount,
      long amountReceived,
      String currency,
      boolean livemode) {}

  private static OffsetDateTime utcMicros(java.time.Instant instant) {
    return OffsetDateTime.ofInstant(
        Objects.requireNonNull(instant).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        ZoneOffset.UTC);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Late first-invoice conflict lookup requires an Account transaction");
    }
  }

  private static void requireWritableTransaction() {
    requireTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Late first-invoice conflict retention requires a writable Account transaction");
    }
  }

  private static IllegalStateException refused() {
    return new IllegalStateException("Exact late first-invoice payment conflict refused");
  }
}
