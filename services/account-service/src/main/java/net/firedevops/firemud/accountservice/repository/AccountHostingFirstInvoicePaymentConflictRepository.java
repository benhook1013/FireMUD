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
   * Returns the original first-invoice confirmation request for an exact verified-event replay. The
   * cancellation record remains addressable after its subscription row is superseded.
   */
  UUID retainedConflictRequestId(VerifiedEvent verified) {
    requireTransaction();
    Objects.requireNonNull(verified, "verified Stripe event is required");
    Record row =
        dsl.fetchOne(
            "SELECT conflict.request_id, COALESCE(replay.event_payload_digest,"
                + " conflict.event_payload_digest) AS event_payload_digest, inbox.raw_payload,"
                + " inbox.payload_sha256, inbox.event_type,"
                + " inbox.provider_created_at_epoch_seconds, inbox.provider_api_version,"
                + " inbox.livemode, replay.provider_event_id IS NOT NULL AS linked_replay,"
                + " confirmation.consented_at FROM stripe_event_inbox inbox LEFT JOIN"
                + " subscription_first_invoice_late_paid_event_replays replay USING"
                + " (provider_event_id) JOIN "
                + TABLE
                + " conflict ON conflict.provider_event_id = inbox.provider_event_id "
                + "OR conflict.subscription_id = replay.subscription_id "
                + "JOIN subscription_first_invoice_confirmations confirmation "
                + "ON confirmation.request_id = conflict.request_id "
                + "AND confirmation.subscription_id = conflict.subscription_id "
                + "WHERE inbox.provider_event_id = ?",
            verified.providerEventId());
    if (row == null) return null;
    requireExactEvent(verified, row);
    if (Boolean.TRUE.equals(row.get("linked_replay", Boolean.class))
        && verified.eventCreatedAtEpochSeconds()
            < Objects.requireNonNull(row.get("consented_at", OffsetDateTime.class))
                .toInstant()
                .getEpochSecond()) {
      // A historical link is not consent. Let the signed classifier retain this as read-only
      // reconciliation without provider I/O or fulfillment.
      return null;
    }
    return row.get("request_id", UUID.class);
  }

  /**
   * Retains a paid conflict only when the same Account subscription is already canceled by its
   * exact committed unpaid-first-invoice receipt. The payment owner has already verified the signed
   * tuple and provider readback. False leaves its existing fulfillment path in charge.
   */
  boolean retainIfCanceled(
      VerifiedEvent verified, ConfirmationDispatch candidate, Observation observed) {
    requireWritableTransaction();
    Objects.requireNonNull(verified, "verified Stripe event is required");

    UUID replayRequestId = retainedConflictRequestId(verified);
    if (replayRequestId != null) {
      if (!candidate.requestId().equals(replayRequestId)) throw refused();
      return true;
    }
    AccountHostingFirstInvoicePayment invoice = candidate.invoice();
    Record current =
        dsl.fetchOne(
            "SELECT status, provider_subscription_id, current_receipt_request_id, "
                + "lifecycle_inflight_request_id "
                + "FROM subscription WHERE subscription_id = ? FOR UPDATE",
            invoice.subscriptionId());
    if (current == null) throw refused();
    if (!"canceled".equals(current.get("status", String.class))) return false;
    if (current.get("current_receipt_request_id", UUID.class) == null
        || current.get("lifecycle_inflight_request_id", UUID.class) != null
        || !invoice
            .providerSubscriptionId()
            .equals(current.get("provider_subscription_id", String.class))) throw refused();

    Record cancellation = exactCommittedCancellation(invoice);
    if (cancellation == null) return false;

    // The signed event has already been retained by webhook intake in the usual path. Retaining
    // here as well keeps this owner safe when called directly, while an altered duplicate payload
    // cannot be rebound to the first immutable inbox receipt.
    StripeEventInboxRecord intake = new StripeEventInboxRepository(dsl).retainOrReplay(verified);
    if (!intake.payloadSha256().equals(StripeEventInboxRecord.sha256(intake.rawPayload()))
        || !sameSignedSuccessTuple(intake.rawPayload(), verified.rawPayload())) throw refused();

    int inserted =
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
            candidate.requestId(),
            candidate.lifecycleRequestId(),
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
    if (inserted == 0) {
      requireMatchingSubscriptionConflict(invoice, candidate, cancellation, verified);
      dsl.execute(
          "INSERT INTO subscription_first_invoice_late_paid_event_replays "
              + "(provider_event_id, subscription_id, event_payload_digest) VALUES (?, ?, ?) "
              + "ON CONFLICT (provider_event_id) DO NOTHING",
          intake.providerEventId(),
          invoice.subscriptionId(),
          intake.payloadSha256());
    } else if (inserted != 1) {
      throw refused();
    }
    if (!candidate.requestId().equals(retainedConflictRequestId(verified))) throw refused();
    return true;
  }

  private void requireMatchingSubscriptionConflict(
      AccountHostingFirstInvoicePayment invoice,
      ConfirmationDispatch candidate,
      Record cancellation,
      VerifiedEvent verified) {
    Record winner =
        dsl.fetchOne(
            "SELECT conflict.request_id, conflict.lifecycle_request_id, conflict.creation_request_id, "
                + "conflict.tuple_digest, conflict.cancellation_request_id, "
                + "conflict.cancellation_receipt_digest, conflict.cancellation_row_version, "
                + "conflict.cancellation_committed_transaction_id, conflict.event_payload_digest, "
                + "inbox.raw_payload, inbox.payload_sha256 FROM "
                + TABLE
                + " conflict JOIN stripe_event_inbox inbox USING (provider_event_id) "
                + "WHERE conflict.subscription_id = ?",
            invoice.subscriptionId());
    if (winner == null
        || !candidate.requestId().equals(winner.get("request_id", UUID.class))
        || !candidate.lifecycleRequestId().equals(winner.get("lifecycle_request_id", UUID.class))
        || !invoice.creationRequestId().equals(winner.get("creation_request_id", UUID.class))
        || !invoice.digest().equals(winner.get("tuple_digest", String.class))
        || !Objects.equals(
            cancellation.get("cancellation_request_id", UUID.class),
            winner.get("cancellation_request_id", UUID.class))
        || !Objects.equals(
            cancellation.get("cancellation_receipt_digest", String.class),
            winner.get("cancellation_receipt_digest", String.class))
        || !Objects.equals(
            cancellation.get("cancellation_row_version", Long.class),
            winner.get("cancellation_row_version", Long.class))
        || !Objects.equals(
            cancellation.get("cancellation_committed_transaction_id", Long.class),
            winner.get("cancellation_committed_transaction_id", Long.class))) throw refused();
    byte[] retainedPayload = winner.get("raw_payload", byte[].class);
    String digest = winner.get("payload_sha256", String.class);
    if (retainedPayload == null
        || !sha256(retainedPayload).equals(digest)
        || !digest.equals(winner.get("event_payload_digest", String.class))
        || !signedSuccessTuple(decodeUtf8(retainedPayload))
            .payment()
            .equals(signedSuccessTuple(verified.rawPayload()).payment())) throw refused();
  }

  private Record exactCommittedCancellation(AccountHostingFirstInvoicePayment invoice) {
    return dsl.fetchOne(
        "SELECT operation.request_id AS cancellation_request_id, receipt.receipt_digest AS cancellation_receipt_digest, "
            + "receipt.subscription_row_version AS cancellation_row_version, "
            + "receipt.committed_transaction_id AS cancellation_committed_transaction_id "
            + "FROM subscription s "
            + "JOIN subscription_provisioning_work work ON work.request_id = ? AND work.subscription_id = s.subscription_id "
            + "JOIN subscription_first_invoices first_invoice ON first_invoice.subscription_id = s.subscription_id "
            + "JOIN subscription_lifecycle_operations operation ON operation.request_id = s.current_receipt_request_id "
            + "AND operation.subscription_id = s.subscription_id "
            + "JOIN subscription_lifecycle_receipts receipt ON receipt.request_id = operation.request_id "
            + "AND receipt.subscription_id = operation.subscription_id "
            + "WHERE s.subscription_id = ? AND s.status = 'canceled' "
            + "AND s.current_receipt_request_id = operation.request_id "
            + "AND operation.state = 'COMMITTED' AND operation.provisioning_attempt_id IS NULL "
            + "AND operation.creation_request_id = first_invoice.creation_request_id "
            + "AND operation.provider_subscription_id = first_invoice.provider_subscription_id "
            + "AND receipt.status = 'canceled' AND receipt.provider_subscription_id = first_invoice.provider_subscription_id "
            + "AND work.phase = 'CANCELED' AND work.last_attempt_id IS NOT NULL "
            + "AND convert_from(operation.request_payload, 'UTF8')::jsonb->>'schemaVersion' = ? "
            + "AND receipt.committed_transaction_id <> txid_current()",
        invoice.creationRequestId(),
        invoice.subscriptionId(),
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
          new SignedPaymentTuple(
              eventLivemode.asBoolean(),
              id.asText(),
              customer.asText(),
              paymentMethod.asText(),
              amount.asLong(),
              amountReceived.asLong(),
              currency.asText(),
              livemode.asBoolean()));
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
      SignedPaymentTuple payment) {}

  private record SignedPaymentTuple(
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
