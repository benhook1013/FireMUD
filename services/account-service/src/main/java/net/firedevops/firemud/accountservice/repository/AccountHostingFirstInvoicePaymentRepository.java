package net.firedevops.firemud.accountservice.repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient.ExpiredBeforePost;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient.Observation;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier.VerifiedEvent;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProviderDispatch;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationEvidence;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationEvidence.BillingAuthorizationPurpose;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicy;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Held Account checkout journal, separate from immutable webhook intake and hosting authority. */
public final class AccountHostingFirstInvoicePaymentRepository {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  static final String AWAITING_PAYMENT_SQL =
      "SELECT s.status, s.row_version, s.provider_subscription_id, w.phase "
          + "FROM subscription s JOIN subscription_provisioning_work w ON w.subscription_id = s.subscription_id "
          + "WHERE s.subscription_id = ? AND w.request_id = ? AND s.superseded_at IS NULL";
  private final DSLContext dsl;
  private final AccountSubscriptionIntentRepository intents;
  private final AccountHostingBillingPolicyRepository policies;
  private final Clock clock;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Private injected transaction collaborators must preserve the Account owner transaction boundary and are never exposed.")
  public AccountHostingFirstInvoicePaymentRepository(
      DSLContext dsl, AccountSubscriptionIntentRepository intents, Clock clock) {
    this.dsl = Objects.requireNonNull(dsl);
    this.intents = Objects.requireNonNull(intents);
    this.clock = Objects.requireNonNull(clock);
    policies = new AccountHostingBillingPolicyRepository(dsl);
  }

  /** Retains linkage while status stays non-authorizing provisioning. No confirmation is sent. */
  public AccountHostingFirstInvoicePayment retainAwaitingPayment(
      ProviderDispatch dispatch, String providerId, Observation observation) {
    transaction();
    var creation = dispatch.claim().creation();
    AccountHostingFirstInvoicePayment retained = find(creation.subscriptionId());
    if (retained != null) {
      if (!retained.providerSubscriptionId().equals(providerId)
          || !retained.invoiceId().equals(observation.invoiceId())
          || !retained.paymentIntentId().equals(observation.paymentIntentId())) throw refused();
      return retained;
    }
    var current =
        intents.lockForLifecycle(creation.requestId(), dispatch.claim().subscriptionRowVersion());
    var policy = policies.requireCurrent(creation.planQuote());
    Instant now = now();
    if (!dispatch.readyForProviderIo()
        || !"provisioning".equals(current.status())
        || current.providerSubscriptionId() != null
        || !now.isBefore(dispatch.claim().expiresAt())
        || !now.isBefore(observation.expiresAt())
        || now.isBefore(observation.observedAt())
        || observation.readStartedAt().isBefore(dispatch.claim().claimedAt())
        || Duration.between(observation.readStartedAt(), now).compareTo(Duration.ofSeconds(20)) >= 0
        || !providerId.equals(observation.providerSubscriptionId())
        || !creation.providerCustomerId().equals(observation.providerCustomerId())
        || !dispatch.providerPaymentMethodId().equals(observation.providerPaymentMethodId())
        || dispatch.expectedBinding(providerId).livemode() != observation.livemode()
        || !creation.planQuote().providerPriceId().equals(observation.providerPriceId())
        || creation.planQuote().providerQuantity() != observation.providerQuantity()
        || policy.amount() != observation.amount()
        || !policy.currency().equals(observation.currency())) throw refused();
    var invoice =
        new AccountHostingFirstInvoicePayment(
            creation.subscriptionId(),
            creation.requestId(),
            creation.tenantId(),
            creation.owner().accountUuid(),
            creation.paymentInstrumentId(),
            creation.reservedInstrumentVersion(),
            providerId,
            creation.providerCustomerId(),
            dispatch.providerPaymentMethodId(),
            observation.invoiceId(),
            observation.paymentIntentId(),
            observation.amount(),
            observation.currency(),
            creation.planQuote().digest(),
            observation.providerPriceId(),
            observation.providerQuantity(),
            policy.version(),
            policy.digest(),
            dispatch.expectedBinding(providerId).livemode(),
            now,
            observation.expiresAt());
    dsl.execute(
        "INSERT INTO subscription_provider_history (provider_subscription_id, subscription_id, operation_kind, request_id, "
            + "provider_operation_id, role, effective_at, evidence_digest) VALUES (?, ?, 'SUBSCRIPTION_PROVISIONING', ?, ?, 'canonical', ?::timestamptz, ?)",
        providerId,
        creation.subscriptionId(),
        creation.requestId(),
        creation.subscriptionProvisioningRequestId(),
        utc(now),
        invoice.digest());
    dsl.execute(
        "INSERT INTO subscription_first_invoices (subscription_id, creation_request_id, tenant_id, account_uuid, payment_instrument_id, "
            + "instrument_version, provider_subscription_id, provider_customer_id, provider_payment_method_id, invoice_id, payment_intent_id, amount, currency, "
            + "quote_digest, provider_price_id, provider_quantity, billing_policy_version, billing_policy_digest, livemode, captured_at, expires_at, tuple_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?)",
        invoice.subscriptionId(),
        invoice.creationRequestId(),
        invoice.tenantId(),
        invoice.ownerAccountId(),
        invoice.paymentInstrumentId(),
        invoice.instrumentVersion(),
        invoice.providerSubscriptionId(),
        invoice.providerCustomerId(),
        invoice.providerPaymentMethodId(),
        invoice.invoiceId(),
        invoice.paymentIntentId(),
        invoice.amount(),
        invoice.currency(),
        invoice.quoteDigest(),
        invoice.providerPriceId(),
        invoice.providerQuantity(),
        invoice.billingPolicyVersion(),
        invoice.billingPolicyDigest(),
        invoice.livemode(),
        utc(invoice.capturedAt()),
        utc(invoice.expiresAt()),
        invoice.digest());
    if (dsl.execute(
            "UPDATE subscription SET provider_subscription_id = ?, row_version = row_version + 1 WHERE subscription_id = ? "
                + "AND row_version = ? AND provider_subscription_id IS NULL AND status = 'provisioning' AND superseded_at IS NULL",
            providerId,
            creation.subscriptionId(),
            current.rowVersion())
        != 1) throw refused();
    if (dsl.execute(
            "UPDATE subscription_provisioning_work SET phase = 'AWAITING_PAYMENT', row_version = row_version + 1 "
                + "WHERE request_id = ? AND phase = 'CLAIMED' AND last_attempt_id = ?",
            creation.requestId(),
            dispatch.claim().attemptId())
        != 1) throw refused();
    return Objects.requireNonNull(find(invoice.subscriptionId()));
  }

  public AccountHostingFirstInvoicePayment requireOwner(UUID subscriptionId, UUID actor) {
    transaction();
    var invoice = find(subscriptionId);
    if (invoice == null) throw refused();
    var row =
        dsl.fetchOne(
            "SELECT row_version FROM subscription WHERE subscription_id = ? AND account_uuid = ? AND superseded_at IS NULL",
            subscriptionId,
            actor);
    if (!invoice.ownerAccountId().equals(actor) || row == null) throw refused();
    intents.lockForLifecycle(invoice.creationRequestId(), row.get("row_version", Long.class));
    return invoice;
  }

  public long ownerRowVersion(UUID subscriptionId, UUID actor) {
    requireOwner(subscriptionId, actor);
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT row_version FROM subscription WHERE subscription_id = ? AND superseded_at IS NULL",
                subscriptionId))
        .get("row_version", Long.class);
  }

  public boolean hasCanceledCheckout(AccountHostingFirstInvoicePayment invoice) {
    transaction();
    return dsl.fetchOne(
            "SELECT 1 FROM subscription WHERE subscription_id = ?"
                + " AND account_uuid = ? AND status = 'canceled' AND superseded_at IS NULL",
            invoice.subscriptionId(),
            invoice.ownerAccountId())
        != null;
  }

  public UUID confirmationRequestId(UUID subscriptionId) {
    transaction();
    Record row =
        dsl.fetchOne(
            "SELECT request_id FROM subscription_first_invoice_confirmations WHERE subscription_id = ?",
            subscriptionId);
    return row == null ? null : row.get("request_id", UUID.class);
  }

  /** Observational recovery identity only; never restores consent, proof or command permission. */
  public UUID preRowOriginalRequestId(UUID subscriptionId, UUID actor) {
    transaction();
    var invoice = requireOwner(subscriptionId, actor);
    // Existing confirmations own their scoped recovery, including historical rows without a fence.
    if (confirmationRequestId(subscriptionId) != null) return null;
    Record fence = operationFence(subscriptionId, true);
    if (authorizationVersion(subscriptionId) != 0L
        || dsl.fetchOne(
                "SELECT 1 FROM subscription_first_invoice_dispatches WHERE subscription_id = ? LIMIT 1",
                subscriptionId)
            != null
        || dsl.fetchOne(
                "SELECT 1 FROM subscription_first_invoice_command_claims WHERE subscription_id = ? LIMIT 1",
                subscriptionId)
            != null) throw refused();
    if (fence == null) return null;
    long rowVersion = ownerRowVersion(subscriptionId, actor);
    UUID originalRequestId = fence.get("request_id", UUID.class);
    OffsetDateTime fenceExpiry = fence.get("invoice_expires_at", OffsetDateTime.class);
    AccountBillingCustomerRecord.requireUuid(originalRequestId);
    if (!subscriptionId.equals(fence.get("subscription_id", UUID.class))
        || !actor.equals(fence.get("account_uuid", UUID.class))
        || !invoice.digest().equals(fence.get("tuple_digest", String.class))
        || !Long.valueOf(rowVersion).equals(fence.get("expected_row_version", Long.class))
        || fenceExpiry == null
        || !invoice.expiresAt().equals(fenceExpiry.toInstant())) throw refused();
    requireAwaitingPayment(invoice, rowVersion);
    // Expired invoices may still be inspected; mutation owners independently enforce expiry.
    return originalRequestId;
  }

  public long authorizationVersion(UUID subscriptionId) {
    transaction();
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COALESCE(MAX(authorization_version), 0) AS version FROM subscription_first_invoice_authorizations WHERE subscription_id = ?",
                subscriptionId))
        .get("version", Long.class);
  }

  /**
   * Stores the original reauthentication as a durable, exact operation fence, or validates a
   * separately bound renewal attempt against that fence or an already retained confirmation. The
   * caller holds the current authenticated Account and exact reauthentication evidence locks.
   */
  public void bindReauthenticationContext(
      UUID actor,
      UUID subscriptionId,
      AccountRecentReauthenticationEvidence.BillingContext context,
      AccountRecentReauthenticationEvidence evidence) {
    transaction();
    Objects.requireNonNull(context);
    Objects.requireNonNull(evidence);
    var invoice = requireOwner(subscriptionId, actor);
    requireCheckoutCurrent(invoice);
    requireAwaitingPayment(invoice, context.expectedRowVersion());
    requireContext(invoice, actor, context, evidence);
    long currentAuthorizationVersion = authorizationVersion(subscriptionId);
    Record original =
        dsl.fetchOne(
            "SELECT * FROM subscription_first_invoice_confirmations WHERE subscription_id = ? FOR UPDATE",
            subscriptionId);
    Record fence = operationFence(subscriptionId, true);
    if (context.authorizationPurpose() == BillingAuthorizationPurpose.INITIAL_CONFIRMATION) {
      if (context.expectedAuthorizationVersion() != 0L) throw refused();
      if (fence == null) {
        if (original != null || currentAuthorizationVersion != 0L) throw refused();
        dsl.execute(
            "INSERT INTO subscription_first_invoice_operation_fences (subscription_id, request_id, account_uuid, tuple_digest, "
                + "expected_row_version, invoice_expires_at, reauthentication_evidence_id, binding_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?, ?)",
            subscriptionId,
            context.originalRequestId(),
            actor,
            invoice.digest(),
            context.expectedRowVersion(),
            utc(context.invoiceExpiresAt()),
            evidence.evidenceId(),
            context.digest());
        fence = operationFence(subscriptionId, true);
      }
      requireFenceMatches(fence, invoice, actor, context, evidence.evidenceId());
      return;
    }

    if (context.authorizationPurpose() == BillingAuthorizationPurpose.PRE_ROW_RENEWAL) {
      requireFenceMatches(fence, invoice, actor, context, null);
      if (context.expectedAuthorizationVersion() != 0L) throw refused();
      if (original == null) {
        if (currentAuthorizationVersion != 0L
            || dsl.fetchOne(
                    "SELECT 1 FROM subscription_first_invoice_dispatches WHERE subscription_id = ? LIMIT 1",
                    subscriptionId)
                != null
            || dsl.fetchOne(
                    "SELECT 1 FROM subscription_first_invoice_command_claims WHERE subscription_id = ? LIMIT 1",
                    subscriptionId)
                != null) throw refused();
      } else {
        Record winner = authorization(context.authorizationRequestId());
        if (!context.originalRequestId().equals(original.get("request_id", UUID.class))
            || winner == null
            || winner.get("authorization_version", Long.class) != 1L
            || !evidence
                .evidenceId()
                .equals(winner.get("reauthentication_evidence_id", UUID.class))) throw refused();
      }
      return;
    }
    if (original == null
        || !context.originalRequestId().equals(original.get("request_id", UUID.class))
        || !context.tupleDigest().equals(original.get("tuple_digest", String.class))
        || context.expectedRowVersion() != original.get("expected_row_version", Long.class)
        || !actor.equals(original.get("account_uuid", UUID.class))) throw refused();
    if (fence != null) requireFenceMatches(fence, invoice, actor, context, null);
    Record priorAttempt = authorization(context.authorizationRequestId());
    if (priorAttempt != null) {
      if (priorAttempt.get("authorization_version", Long.class)
              != context.expectedAuthorizationVersion() + 1L
          || !evidence
              .evidenceId()
              .equals(priorAttempt.get("reauthentication_evidence_id", UUID.class)))
        throw refused();
    } else if (currentAuthorizationVersion != context.expectedAuthorizationVersion()) {
      throw refused();
    }
  }

  public boolean hasCompleted(UUID subscriptionId) {
    transaction();
    Record row =
        dsl.fetchOne(
            "SELECT lifecycle_request_id FROM subscription_first_invoice_completions WHERE subscription_id = ?",
            subscriptionId);
    return row != null && completed(subscriptionId, row.get("lifecycle_request_id", UUID.class));
  }

  /**
   * First dispatch permission is durable consent plus real purpose-bound consumed reauthentication.
   */
  public ConfirmationDispatch prepareConfirmation(
      UUID actor,
      UUID requestId,
      UUID subscriptionId,
      long expectedVersion,
      String acceptedTupleDigest,
      AccountRecentReauthenticationEvidence reauthentication) {
    transaction();
    AccountBillingCustomerRecord.requireUuid(requestId);
    var invoice = requireOwner(subscriptionId, actor);
    requireCheckoutCurrent(invoice);
    requireAwaitingPayment(invoice, expectedVersion);
    if (!invoice.digest().equals(acceptedTupleDigest)
        || !AccountRecentReauthenticationPolicy.BILLING_PURPOSE.equals(reauthentication.purpose())
        || !requestId.equals(reauthentication.creationOperationId())
        || !actor.equals(reauthentication.accountId())) throw refused();
    Record fence = operationFence(subscriptionId, true);
    Record existing =
        dsl.fetchOne(
            "SELECT * FROM subscription_first_invoice_confirmations WHERE subscription_id = ? FOR UPDATE",
            subscriptionId);
    if (existing != null) {
      if (!requestId.equals(existing.get("request_id", UUID.class))
          || !acceptedTupleDigest.equals(existing.get("tuple_digest", String.class))
          || !reauthentication
              .evidenceId()
              .equals(existing.get("reauthentication_evidence_id", UUID.class))
          || expectedVersion != existing.get("expected_row_version", Long.class)) throw refused();
      return authorizedDispatch(existing, authorization(requestId), invoice, false, false);
    }
    var context = reauthentication.billingContext();
    if (context == null
        || context.authorizationPurpose() != BillingAuthorizationPurpose.INITIAL_CONFIRMATION
        || !requestId.equals(context.originalRequestId())
        || context.expectedAuthorizationVersion() != 0L
        || context.expectedRowVersion() != expectedVersion
        || !context.tupleDigest().equals(acceptedTupleDigest)) throw refused();
    requireContext(invoice, actor, context, reauthentication);
    requireFenceMatches(fence, invoice, actor, context, reauthentication.evidenceId());
    if (authorizationVersion(subscriptionId) != 0L) throw refused();
    intents.lockForLifecycle(invoice.creationRequestId(), expectedVersion);
    Instant validUntil =
        earlier(
            invoice.expiresAt(),
            reauthentication
                .issuedAt()
                .plusSeconds(
                    reauthentication.recentAgeSeconds() - reauthentication.maxClockSkewSeconds()));
    if (!now().isBefore(validUntil)) throw refused();
    dsl.execute(
        "INSERT INTO subscription_first_invoice_confirmations (request_id, subscription_id, account_uuid, tuple_digest, expected_row_version, "
            + "reauthentication_evidence_id, provider_idempotency_id, lifecycle_request_id, audit_event_id, consented_at, valid_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
        requestId,
        subscriptionId,
        actor,
        invoice.digest(),
        expectedVersion,
        reauthentication.evidenceId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        utc(now()),
        utc(validUntil));
    dsl.execute(
        "INSERT INTO subscription_first_invoice_dispatches (request_id, subscription_id, authorized_at) VALUES (?, ?, ?::timestamptz)",
        requestId,
        subscriptionId,
        utc(now()));
    Record original =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT * FROM subscription_first_invoice_confirmations WHERE request_id = ?",
                requestId));
    retainAuthorization(original, invoice, requestId, 1, reauthentication, validUntil);
    return authorizedDispatch(original, authorization(requestId), invoice, false, true);
  }

  /** Fresh consent renews only authorization. It never replaces the immutable payment operation. */
  public ConfirmationDispatch renewAuthorization(
      UUID actor,
      UUID requestId,
      UUID authorizationRequestId,
      UUID subscriptionId,
      long expectedVersion,
      long expectedAuthorizationVersion,
      String acceptedTupleDigest,
      AccountRecentReauthenticationEvidence reauthentication) {
    transaction();
    AccountBillingCustomerRecord.requireUuid(requestId);
    AccountBillingCustomerRecord.requireUuid(authorizationRequestId);
    var invoice = requireOwner(subscriptionId, actor);
    requireCheckoutCurrent(invoice);
    requireAwaitingPayment(invoice, expectedVersion);
    Record fence = operationFence(subscriptionId, true);
    Record original =
        dsl.fetchOne(
            "SELECT * FROM subscription_first_invoice_confirmations WHERE subscription_id = ? FOR UPDATE",
            subscriptionId);
    if (requestId.equals(authorizationRequestId)
        || !invoice.digest().equals(acceptedTupleDigest)
        || !actor.equals(reauthentication.accountId())
        || !AccountRecentReauthenticationPolicy.BILLING_PURPOSE.equals(reauthentication.purpose())
        || !authorizationRequestId.equals(reauthentication.creationOperationId())) throw refused();
    Record existing = authorization(authorizationRequestId);
    if (existing != null) {
      if (!requestId.equals(existing.get("request_id", UUID.class))
          || !subscriptionId.equals(existing.get("subscription_id", UUID.class))
          || !reauthentication
              .evidenceId()
              .equals(existing.get("reauthentication_evidence_id", UUID.class))
          || existing.get("authorization_version", Long.class) != expectedAuthorizationVersion + 1)
        throw refused();
      if (original == null
          || !requestId.equals(original.get("request_id", UUID.class))
          || !acceptedTupleDigest.equals(original.get("tuple_digest", String.class))
          || expectedVersion != original.get("expected_row_version", Long.class)) throw refused();
      var context = reauthentication.billingContext();
      if (context != null) {
        requireContext(invoice, actor, context, reauthentication);
        if (!requestId.equals(context.originalRequestId())
            || !authorizationRequestId.equals(context.authorizationRequestId())
            || context.expectedAuthorizationVersion() != expectedAuthorizationVersion
            || context.expectedRowVersion() != expectedVersion
            || !context.tupleDigest().equals(acceptedTupleDigest)
            || (expectedAuthorizationVersion == 0L
                && context.authorizationPurpose() != BillingAuthorizationPurpose.PRE_ROW_RENEWAL)
            || (expectedAuthorizationVersion > 0L
                && context.authorizationPurpose() != BillingAuthorizationPurpose.FOLLOWUP_RENEWAL))
          throw refused();
        if (context.authorizationPurpose() == BillingAuthorizationPurpose.PRE_ROW_RENEWAL
            || fence != null) {
          requireFenceMatches(fence, invoice, actor, context, null);
        }
      } else if (existing.get("authorization_version", Long.class) <= 1L) {
        throw refused();
      }
      return authorizedDispatch(original, existing, invoice, false, false);
    }
    if (expectedAuthorizationVersion < 0L
        || authorizationVersion(subscriptionId) != expectedAuthorizationVersion) throw refused();
    var context = reauthentication.billingContext();
    if (context == null) throw refused();
    requireContext(invoice, actor, context, reauthentication);
    if (!requestId.equals(context.originalRequestId())
        || !authorizationRequestId.equals(context.authorizationRequestId())
        || context.expectedAuthorizationVersion() != expectedAuthorizationVersion
        || context.expectedRowVersion() != expectedVersion
        || !context.tupleDigest().equals(acceptedTupleDigest)) throw refused();
    Instant validUntil =
        earlier(
            invoice.expiresAt(),
            reauthentication
                .issuedAt()
                .plusSeconds(
                    reauthentication.recentAgeSeconds() - reauthentication.maxClockSkewSeconds()));
    if (!now().isBefore(validUntil)) throw refused();

    if (original == null) {
      if (expectedAuthorizationVersion != 0L
          || context.authorizationPurpose() != BillingAuthorizationPurpose.PRE_ROW_RENEWAL) {
        throw refused();
      }
      requireFenceMatches(fence, invoice, actor, context, null);
      dsl.execute(
          "INSERT INTO subscription_first_invoice_confirmations (request_id, subscription_id, account_uuid, tuple_digest, expected_row_version, "
              + "reauthentication_evidence_id, provider_idempotency_id, lifecycle_request_id, audit_event_id, consented_at, valid_until) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
          requestId,
          subscriptionId,
          actor,
          invoice.digest(),
          expectedVersion,
          reauthentication.evidenceId(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          utc(now()),
          utc(validUntil));
      dsl.execute(
          "INSERT INTO subscription_first_invoice_dispatches (request_id, subscription_id, authorized_at) VALUES (?, ?, ?::timestamptz)",
          requestId,
          subscriptionId,
          utc(now()));
      original =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT * FROM subscription_first_invoice_confirmations WHERE request_id = ?",
                  requestId));
    } else {
      if (!requestId.equals(original.get("request_id", UUID.class))
          || !acceptedTupleDigest.equals(original.get("tuple_digest", String.class))
          || expectedVersion != original.get("expected_row_version", Long.class)
          || !actor.equals(original.get("account_uuid", UUID.class))
          || expectedAuthorizationVersion < 1L
          || context.authorizationPurpose() != BillingAuthorizationPurpose.FOLLOWUP_RENEWAL)
        throw refused();
      if (fence != null) requireFenceMatches(fence, invoice, actor, context, null);
    }
    retainAuthorization(
        original,
        invoice,
        authorizationRequestId,
        Math.addExact(expectedAuthorizationVersion, 1),
        reauthentication,
        validUntil);
    return authorizedDispatch(
        original, authorization(authorizationRequestId), invoice, false, true);
  }

  private Record authorization(UUID authorizationRequestId) {
    return dsl.fetchOne(
        "SELECT * FROM subscription_first_invoice_authorizations WHERE authorization_request_id = ?",
        authorizationRequestId);
  }

  private Record operationFence(UUID subscriptionId, boolean lock) {
    return dsl.fetchOne(
        "SELECT * FROM subscription_first_invoice_operation_fences WHERE subscription_id = ?"
            + (lock ? " FOR UPDATE" : ""),
        subscriptionId);
  }

  private static void requireContext(
      AccountHostingFirstInvoicePayment invoice,
      UUID actor,
      AccountRecentReauthenticationEvidence.BillingContext context,
      AccountRecentReauthenticationEvidence evidence) {
    if (!actor.equals(context.accountId())
        || !invoice.subscriptionId().equals(context.subscriptionId())
        || !invoice.digest().equals(context.tupleDigest())
        || !invoice.expiresAt().equals(context.invoiceExpiresAt())
        || !AccountRecentReauthenticationPolicy.BILLING_PURPOSE.equals(evidence.purpose())
        || !context.authorizationRequestId().equals(evidence.creationOperationId())
        || !context.equals(evidence.billingContext())) throw refused();
  }

  private static void requireFenceMatches(
      Record fence,
      AccountHostingFirstInvoicePayment invoice,
      UUID actor,
      AccountRecentReauthenticationEvidence.BillingContext context,
      UUID expectedEvidenceId) {
    if (fence == null
        || !invoice.subscriptionId().equals(fence.get("subscription_id", UUID.class))
        || !context.originalRequestId().equals(fence.get("request_id", UUID.class))
        || !actor.equals(fence.get("account_uuid", UUID.class))
        || !invoice.digest().equals(fence.get("tuple_digest", String.class))
        || context.expectedRowVersion() != fence.get("expected_row_version", Long.class)
        || !context.invoiceExpiresAt().equals(instant(fence, "invoice_expires_at"))
        || (expectedEvidenceId != null
            && !context.digest().equals(fence.get("binding_digest", String.class)))
        || (expectedEvidenceId != null
            && !expectedEvidenceId.equals(fence.get("reauthentication_evidence_id", UUID.class))))
      throw refused();
  }

  private void retainAuthorization(
      Record original,
      AccountHostingFirstInvoicePayment invoice,
      UUID authorizationRequestId,
      long version,
      AccountRecentReauthenticationEvidence evidence,
      Instant validUntil) {
    dsl.execute(
        "INSERT INTO subscription_first_invoice_authorizations (authorization_request_id, request_id, subscription_id, authorization_version, reauthentication_evidence_id, consented_at, valid_until) VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
        authorizationRequestId,
        original.get("request_id"),
        invoice.subscriptionId(),
        version,
        evidence.evidenceId(),
        utc(now()),
        utc(validUntil));
  }

  /** A separate transaction and current owner/policy readback precede any provider confirmation. */
  public ConfirmationDispatch readDispatch(ConfirmationDispatch prepared, UUID actor) {
    var current = requireCurrentDispatch(prepared, actor);
    return authorizedDispatch(
        current.confirmation(),
        current.authorization(),
        current.invoice(),
        true,
        prepared.takeInitialCommandPermission());
  }

  /**
   * Rechecks a secret-bearing provider handoff under the current Account and owner locks. This
   * path is read-only: it neither consumes the one-shot command permission nor opens a claim.
   */
  public void validateHandoffAuthorization(
      ConfirmationDispatch dispatch,
      UUID actor,
      AccountRecentReauthenticationEvidence evidence) {
    Objects.requireNonNull(evidence);
    var current = requireCurrentDispatch(dispatch, actor);
    var context = evidence.billingContext();
    if (context == null) throw refused();
    requireContext(current.invoice(), actor, context, evidence);
    long expectedAuthorizationVersion = dispatch.authorizationVersion() - 1;
    BillingAuthorizationPurpose expectedPurpose =
        dispatch.authorizationVersion() == 1L
            ? dispatch.requestId().equals(dispatch.authorizationRequestId())
                ? BillingAuthorizationPurpose.INITIAL_CONFIRMATION
                : BillingAuthorizationPurpose.PRE_ROW_RENEWAL
            : BillingAuthorizationPurpose.FOLLOWUP_RENEWAL;
    if (!dispatch.independentlyRead()
        || !dispatch.invoice().equals(current.invoice())
        || dispatch.expectedRowVersion()
            != current.confirmation().get("expected_row_version", Long.class)
        || dispatch.authorizationVersion()
            != current.authorization().get("authorization_version", Long.class)
        || !dispatch.reauthenticationEvidenceId()
            .equals(current.authorization().get("reauthentication_evidence_id", UUID.class))
        || !dispatch.validUntil()
            .equals(instant(current.authorization(), "valid_until"))
        || !evidence.evidenceId().equals(dispatch.reauthenticationEvidenceId())
        || !dispatch.requestId().equals(context.originalRequestId())
        || dispatch.expectedRowVersion() != context.expectedRowVersion()
        || !dispatch.authorizationRequestId().equals(context.authorizationRequestId())
        || expectedAuthorizationVersion != context.expectedAuthorizationVersion()
        || context.authorizationPurpose() != expectedPurpose) {
      throw refused();
    }
  }

  private CurrentDispatch requireCurrentDispatch(ConfirmationDispatch prepared, UUID actor) {
    transaction();
    UUID requestId = prepared.requestId();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM subscription_first_invoice_confirmations WHERE request_id = ?",
            requestId);
    if (row == null) throw refused();
    var invoice = requireOwner(row.get("subscription_id", UUID.class), actor);
    requireCheckoutCurrent(invoice);
    requireAwaitingPayment(invoice, row.get("expected_row_version", Long.class));
    Record authorization = authorization(prepared.authorizationRequestId());
    if (authorization == null) throw refused();
    Instant validUntil = instant(authorization, "valid_until");
    if (!actor.equals(row.get("account_uuid", UUID.class))
        || !requestId.equals(authorization.get("request_id", UUID.class))
        || authorization.get("authorization_version", Long.class)
            != authorizationVersion(invoice.subscriptionId())
        || authorization.get("created_transaction_id", Long.class) == transactionId()
        || !invoice.digest().equals(row.get("tuple_digest", String.class))
        || !now().isBefore(validUntil)) throw refused();
    return new CurrentDispatch(row, authorization, invoice);
  }

  /**
   * Commits before provider POST. Any lost response, including a crash after this claim, fences all
   * later POSTs permanently; provider observation/signed completion must resolve that outcome.
   */
  public CommandClaim claimCommand(ConfirmationDispatch dispatch, UUID actor) {
    transaction();
    var invoice = requireOwner(dispatch.invoice().subscriptionId(), actor);
    requireCheckoutCurrent(invoice);
    requireAwaitingPayment(invoice, dispatch.expectedRowVersion());
    intents.lockForFirstInvoiceCommand(invoice.creationRequestId(), dispatch.expectedRowVersion());
    Record auth = Objects.requireNonNull(authorization(dispatch.authorizationRequestId()));
    if (!dispatch.independentlyRead()
        || !dispatch.requestId().equals(auth.get("request_id", UUID.class))
        || !dispatch
            .reauthenticationEvidenceId()
            .equals(auth.get("reauthentication_evidence_id", UUID.class))
        || dispatch.authorizationVersion() != auth.get("authorization_version", Long.class)
        || dispatch.authorizationVersion() != authorizationVersion(invoice.subscriptionId())
        || !now().isBefore(instant(auth, "valid_until"))) throw refused();
    int inserted =
        dsl.execute(
            "INSERT INTO subscription_first_invoice_command_claims (request_id, subscription_id, authorization_request_id, claimed_at) VALUES (?, ?, ?, ?::timestamptz) ON CONFLICT (request_id) DO NOTHING",
            dispatch.requestId(),
            invoice.subscriptionId(),
            dispatch.authorizationRequestId(),
            utc(now()));
    return inserted == 1 ? new CommandClaim(dispatch) : null;
  }

  /** Retains the exact no-send observation without altering the original command claim. */
  public void retainExpiredBeforePost(ExpiredBeforePost observation) {
    transaction();
    Instant observedAt = observation.observedAt().truncatedTo(ChronoUnit.MICROS);
    dsl.execute(
        "INSERT INTO subscription_first_invoice_expired_before_post (request_id, observed_at) "
            + "VALUES (?, ?::timestamptz) ON CONFLICT (request_id) DO NOTHING",
        observation.requestId(),
        utc(observedAt));
    Record retained =
        dsl.fetchOne(
            "SELECT observed_at FROM subscription_first_invoice_expired_before_post WHERE request_id = ?",
            observation.requestId());
    if (retained == null || !observedAt.equals(instant(retained, "observed_at"))) throw refused();
  }

  void requireAwaitingPayment(AccountHostingFirstInvoicePayment invoice, long expectedVersion) {
    transaction();
    Record row =
        dsl.fetchOne(AWAITING_PAYMENT_SQL, invoice.subscriptionId(), invoice.creationRequestId());
    if (row == null
        || !"provisioning".equals(row.get("status", String.class))
        || !"AWAITING_PAYMENT".equals(row.get("phase", String.class))
        || !Long.valueOf(expectedVersion).equals(row.get("row_version", Long.class))
        || !invoice
            .providerSubscriptionId()
            .equals(row.get("provider_subscription_id", String.class))) throw refused();
  }

  private void requireCheckoutCurrent(AccountHostingFirstInvoicePayment invoice) {
    policies.requireUnchanged(
        invoice, intents.findCreation(invoice.creationRequestId()).orElseThrow().planQuote());
    if (!now().isBefore(invoice.expiresAt())) throw refused();
  }

  public ConfirmationDispatch candidateForSignedEvent(VerifiedEvent verified) {
    transaction();
    if (!"payment_intent.succeeded".equals(verified.eventType())) return null;
    String intent =
        JSON.readTree(verified.rawPayload()).path("data").path("object").path("id").asText();
    Record row =
        dsl.fetchOne(
            "SELECT c.* FROM subscription_first_invoice_confirmations c "
                + "JOIN subscription_first_invoices i USING (subscription_id) WHERE i.payment_intent_id = ?",
            intent);
    if (row == null) return null;
    var invoice = Objects.requireNonNull(find(row.get("subscription_id", UUID.class)));
    requireSignedTuple(verified, row, invoice);
    return dispatch(row, invoice, false, false);
  }

  public UUID retainedSignedConflictRequestId(VerifiedEvent verified) {
    transaction();
    return new AccountHostingFirstInvoicePaymentConflictRepository(dsl)
        .retainedConflictRequestId(verified);
  }

  public boolean completed(UUID subscriptionId, UUID lifecycleRequestId) {
    transaction();
    Record row =
        dsl.fetchOne(
            "SELECT c.receipt_digest AS completed_digest, r.receipt_digest AS actual_digest, r.receipt_payload, "
                + "c.tuple_digest, a.payload AS audit_payload, a.payload_digest AS audit_digest "
                + "FROM subscription_first_invoice_completions c JOIN subscription_lifecycle_receipts r "
                + "ON r.request_id = c.lifecycle_request_id JOIN account_audit_outbox a ON a.audit_event_id = c.audit_event_id "
                + "WHERE c.subscription_id = ? AND c.lifecycle_request_id = ?",
            subscriptionId,
            lifecycleRequestId);
    if (row == null) return false;
    if (!row.get("completed_digest", String.class).equals(row.get("actual_digest", String.class))
        || !AccountHostingSubscriptionLifecycleEvidence.digest(
                row.get("receipt_payload", byte[].class))
            .equals(row.get("actual_digest", String.class))) throw refused();
    String auditPayload = row.get("audit_payload", String.class);
    JsonNode audit = JSON.readTree(auditPayload);
    if (!net.firedevops.firemud.accountservice.dto.AccountAuditDigest.ofPayload(auditPayload)
            .equals(row.get("audit_digest", String.class))
        || !row.get("tuple_digest", String.class).equals(audit.path("tupleDigest").asText())
        || !row.get("actual_digest", String.class).equals(audit.path("receiptDigest").asText()))
      throw refused();
    return true;
  }

  /** The verified payload, not a browser result or invoice.paid, establishes success evidence. */
  public ConfirmationDispatch retainSignedSuccess(VerifiedEvent verified, Observation observed) {
    var retained = retainSignedOutcome(verified, observed);
    if (retained.latePaidConflict()) throw refused();
    return retained.dispatch();
  }

  /** Separately retains a verified paid conflict without changing a committed cancellation. */
  public SignedOutcome retainSignedOutcome(VerifiedEvent verified, Observation observed) {
    transaction();
    if (!"payment_intent.succeeded".equals(verified.eventType())) throw refused();
    JsonNode object = JSON.readTree(verified.rawPayload()).path("data").path("object");
    Record subscription =
        dsl.fetchOne(
            "SELECT s.subscription_id FROM subscription s JOIN subscription_first_invoices i USING (subscription_id) "
                + "WHERE i.payment_intent_id = ? FOR UPDATE OF s",
            object.path("id").asText());
    if (subscription == null) throw refused();
    UUID subscriptionId = subscription.get("subscription_id", UUID.class);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM subscription_first_invoice_confirmations WHERE subscription_id = ? FOR UPDATE",
            subscriptionId);
    if (row == null) throw refused();
    var invoice = Objects.requireNonNull(find(subscriptionId));
    requireSignedTuple(verified, row, invoice);
    if (!"succeeded".equals(observed.status())
        || !invoice.invoiceId().equals(observed.invoiceId())
        || !invoice.paymentIntentId().equals(observed.paymentIntentId())
        || !invoice.providerSubscriptionId().equals(observed.providerSubscriptionId())
        || !invoice.providerCustomerId().equals(observed.providerCustomerId())
        || !invoice.providerPaymentMethodId().equals(observed.providerPaymentMethodId())
        || invoice.livemode() != observed.livemode()
        || !invoice.providerPriceId().equals(observed.providerPriceId())
        || invoice.providerQuantity() != observed.providerQuantity()
        || invoice.amount() != observed.amount()
        || !invoice.currency().equals(observed.currency())
        || observed.readStartedAt().isBefore(verified.receivedAt())
        || now().isBefore(observed.observedAt())
        || Duration.between(observed.readStartedAt(), now()).compareTo(Duration.ofSeconds(20)) >= 0)
      throw refused();
    var dispatch = dispatch(row, invoice, true, false);
    if (new AccountHostingFirstInvoicePaymentConflictRepository(dsl)
        .retainIfCanceled(verified, dispatch, observed)) {
      return new SignedOutcome(dispatch, true);
    }
    var intake = new StripeEventInboxRepository(dsl).retainOrReplay(verified);
    dsl.execute(
        "INSERT INTO subscription_first_invoice_success_evidence (request_id, subscription_id, provider_event_id, event_payload_digest, tuple_digest, observed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?::timestamptz) ON CONFLICT (request_id) DO NOTHING",
        row.get("request_id"),
        invoice.subscriptionId(),
        intake.providerEventId(),
        intake.payloadSha256(),
        invoice.digest(),
        utc(observed.observedAt()));
    return new SignedOutcome(dispatch, false);
  }

  private record CurrentDispatch(
      Record confirmation,
      Record authorization,
      AccountHostingFirstInvoicePayment invoice) {}

  public record SignedOutcome(ConfirmationDispatch dispatch, boolean latePaidConflict) {}

  private static void requireSignedTuple(
      VerifiedEvent verified, Record confirmation, AccountHostingFirstInvoicePayment invoice) {
    JsonNode object = JSON.readTree(verified.rawPayload()).path("data").path("object");
    if (!"payment_intent.succeeded".equals(verified.eventType())
        || !"payment_intent".equals(object.path("object").asText())
        || !"succeeded".equals(object.path("status").asText())
        || !invoice.paymentIntentId().equals(object.path("id").asText())
        || !invoice.providerCustomerId().equals(object.path("customer").asText())
        || !invoice.providerPaymentMethodId().equals(object.path("payment_method").asText())
        || !object.path("amount").isIntegralNumber()
        || !object.path("amount_received").isIntegralNumber()
        || !object.path("amount").canConvertToLong()
        || !object.path("amount_received").canConvertToLong()
        || invoice.amount() != object.path("amount").asLong()
        || invoice.amount() != object.path("amount_received").asLong()
        || !invoice.currency().equals(object.path("currency").asText())
        || !object.path("livemode").isBoolean()
        || invoice.livemode() != object.path("livemode").asBoolean()
        || invoice.livemode() != verified.livemode()
        || verified.eventCreatedAtEpochSeconds()
            < instant(confirmation, "consented_at").getEpochSecond()) throw refused();
  }

  public AccountHostingFirstInvoicePayment find(UUID subscriptionId) {
    transaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM subscription_first_invoices WHERE subscription_id = ?", subscriptionId);
    if (row == null) return null;
    var invoice =
        new AccountHostingFirstInvoicePayment(
            row.get("subscription_id", UUID.class),
            row.get("creation_request_id", UUID.class),
            row.get("tenant_id", UUID.class),
            row.get("account_uuid", UUID.class),
            row.get("payment_instrument_id", UUID.class),
            row.get("instrument_version", Long.class),
            row.get("provider_subscription_id", String.class),
            row.get("provider_customer_id", String.class),
            row.get("provider_payment_method_id", String.class),
            row.get("invoice_id", String.class),
            row.get("payment_intent_id", String.class),
            row.get("amount", Long.class),
            row.get("currency", String.class),
            row.get("quote_digest", String.class),
            row.get("provider_price_id", String.class),
            row.get("provider_quantity", Long.class),
            row.get("billing_policy_version", Long.class),
            row.get("billing_policy_digest", String.class),
            row.get("livemode", Boolean.class),
            instant(row, "captured_at"),
            instant(row, "expires_at"));
    if (!invoice.digest().equals(row.get("tuple_digest", String.class))) throw refused();
    return invoice;
  }

  private static ConfirmationDispatch dispatch(
      Record row, AccountHostingFirstInvoicePayment invoice, boolean read, boolean commandAllowed) {
    return new ConfirmationDispatch(
        invoice,
        row.get("request_id", UUID.class),
        row.get("provider_idempotency_id", UUID.class),
        row.get("lifecycle_request_id", UUID.class),
        row.get("reauthentication_evidence_id", UUID.class),
        row.get("request_id", UUID.class),
        1,
        row.get("expected_row_version", Long.class),
        instant(row, "valid_until"),
        read,
        commandAllowed);
  }

  private static ConfirmationDispatch authorizedDispatch(
      Record original,
      Record authorization,
      AccountHostingFirstInvoicePayment invoice,
      boolean read,
      boolean commandAllowed) {
    return new ConfirmationDispatch(
        invoice,
        original.get("request_id", UUID.class),
        original.get("provider_idempotency_id", UUID.class),
        original.get("lifecycle_request_id", UUID.class),
        authorization.get("reauthentication_evidence_id", UUID.class),
        authorization.get("authorization_request_id", UUID.class),
        authorization.get("authorization_version", Long.class),
        original.get("expected_row_version", Long.class),
        instant(authorization, "valid_until"),
        read,
        commandAllowed);
  }

  private long transactionId() {
    return Objects.requireNonNull(dsl.fetchOne("SELECT txid_current() AS id"))
        .get("id", Long.class);
  }

  private Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  private static Instant instant(Record row, String field) {
    return row.get(field, OffsetDateTime.class).toInstant();
  }

  private static OffsetDateTime utc(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static Instant earlier(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static IllegalStateException refused() {
    return new IllegalStateException("Exact current first-invoice owner/payment evidence refused");
  }

  private static void transaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account owner transaction required");
    }
  }

  /** Closed durable dispatch evidence, not constructible from request booleans or provider IDs. */
  public static final class CommandClaim {
    private final ConfirmationDispatch dispatch;
    private final java.util.concurrent.atomic.AtomicBoolean available =
        new java.util.concurrent.atomic.AtomicBoolean(true);

    private CommandClaim(ConfirmationDispatch dispatch) {
      this.dispatch = dispatch;
    }

    public boolean takePermission(ConfirmationDispatch expected) {
      return dispatch == expected && available.compareAndSet(true, false);
    }

    @Override
    public String toString() {
      return "HostingFirstInvoiceCommandClaim[redacted]";
    }
  }

  /** Closed durable dispatch evidence, not constructible from request booleans or provider IDs. */
  public static final class ConfirmationDispatch {
    private final AccountHostingFirstInvoicePayment invoice;
    private final UUID requestId,
        providerIdempotencyId,
        lifecycleRequestId,
        reauthenticationEvidenceId,
        authorizationRequestId;
    private final long expectedRowVersion, authorizationVersion;
    private final Instant validUntil;
    private final boolean independentlyRead;
    private final java.util.concurrent.atomic.AtomicBoolean initialCommandAllowed;

    private ConfirmationDispatch(
        AccountHostingFirstInvoicePayment invoice,
        UUID requestId,
        UUID providerIdempotencyId,
        UUID lifecycleRequestId,
        UUID reauthenticationEvidenceId,
        UUID authorizationRequestId,
        long authorizationVersion,
        long expectedRowVersion,
        Instant validUntil,
        boolean independentlyRead,
        boolean initialCommandAllowed) {
      this.invoice = invoice;
      this.requestId = requestId;
      this.providerIdempotencyId = providerIdempotencyId;
      this.lifecycleRequestId = lifecycleRequestId;
      this.reauthenticationEvidenceId = reauthenticationEvidenceId;
      this.authorizationRequestId = authorizationRequestId;
      this.authorizationVersion = authorizationVersion;
      this.expectedRowVersion = expectedRowVersion;
      this.validUntil = validUntil;
      this.independentlyRead = independentlyRead;
      this.initialCommandAllowed =
          new java.util.concurrent.atomic.AtomicBoolean(initialCommandAllowed);
    }

    public AccountHostingFirstInvoicePayment invoice() {
      return invoice;
    }

    public UUID requestId() {
      return requestId;
    }

    public UUID providerIdempotencyId() {
      return providerIdempotencyId;
    }

    public UUID lifecycleRequestId() {
      return lifecycleRequestId;
    }

    public UUID reauthenticationEvidenceId() {
      return reauthenticationEvidenceId;
    }

    public long expectedRowVersion() {
      return expectedRowVersion;
    }

    public UUID authorizationRequestId() {
      return authorizationRequestId;
    }

    public long authorizationVersion() {
      return authorizationVersion;
    }

    public Instant validUntil() {
      return validUntil;
    }

    public boolean independentlyRead() {
      return independentlyRead;
    }

    public boolean takeInitialCommandPermission() {
      return initialCommandAllowed.getAndSet(false);
    }

    @Override
    public String toString() {
      return "HostingFirstInvoiceDispatch[redacted]";
    }
  }
}
