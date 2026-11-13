package net.firedevops.firemud.accountservice.repository;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient.Observation;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier.VerifiedEvent;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account-owned immutable record of a verified payment succeeding after retained cancellation. */
final class AccountHostingFirstInvoicePaymentConflictRepository {
  private static final String TABLE = "subscription_first_invoice_late_paid_conflicts";

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
            "SELECT conflict.request_id, conflict.event_payload_digest, inbox.raw_payload, "
                + "inbox.payload_sha256, inbox.event_type, inbox.provider_created_at_epoch_seconds, "
                + "inbox.provider_api_version, inbox.livemode "
                + "FROM "
                + TABLE
                + " conflict JOIN stripe_event_inbox inbox USING (provider_event_id) "
                + "WHERE conflict.provider_event_id = ?",
            verified.providerEventId());
    if (row == null) return null;
    requireExactEvent(verified, row);
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
    if (!intake.payloadSha256().equals(verified.payloadSha256())
        || !intake.rawPayload().equals(verified.rawPayload())) throw refused();

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
            utcMicros(observed.expiresAt()),
            cancellation.get("cancellation_request_id", UUID.class),
            cancellation.get("cancellation_receipt_digest", String.class),
            cancellation.get("cancellation_row_version", Long.class),
            cancellation.get("cancellation_committed_transaction_id", Long.class));
    if ((inserted != 0 && inserted != 1)
        || !candidate.requestId().equals(retainedConflictRequestId(verified))) throw refused();
    return true;
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
    byte[] incomingPayload = verified.rawPayload().getBytes(StandardCharsets.UTF_8);
    if (!"payment_intent.succeeded".equals(verified.eventType())
        || !verified.payloadSha256().equals(row.get("event_payload_digest", String.class))
        || !verified.payloadSha256().equals(row.get("payload_sha256", String.class))
        || !Arrays.equals(retainedPayload, incomingPayload)
        || !verified.eventType().equals(row.get("event_type", String.class))
        || verified.eventCreatedAtEpochSeconds()
            != row.get("provider_created_at_epoch_seconds", Long.class)
        || !Objects.equals(
            verified.providerApiVersion(), row.get("provider_api_version", String.class))
        || verified.livemode() != row.get("livemode", Boolean.class)) throw refused();
  }

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
