package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient.TerminalUnpaidObservation;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver.ExpectedBinding;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver.ObservedSubscription;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.Status;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.Authorization;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.LockedTarget;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.Request;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.FenceRejectedException;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manually composed hosting lifecycle owner. Mutation methods use short writable Account
 * transactions; the service performs actual SDK reads strictly between them. No provider mutation
 * or runtime route is registered. Stored status rows alone are never a positive internal read.
 */
public final class AccountHostingSubscriptionLifecycleRepository {
  private static final String UNPAID_FIRST_INVOICE_CANCELLATION_SCHEMA =
      "account-hosting-unpaid-first-invoice-cancellation/v1";

  private final DSLContext dsl;
  private final AccountSubscriptionIntentRepository intents;
  private final Clock clock;
  private final ReconciliationLimits limits;
  private final AccountHostingSubscriptionLifecyclePolicy policy;
  private final boolean livemode;
  private final AccountAuthorityGenerationRepository generations;
  private final FreshTenantIdentityAssociationRepository tenantIdentity;
  private final AccountTenantEntitlementOutboxRepository billing;
  private final AccountTenantAuthorityEventRepository tenantEvents;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected owner transaction collaborators are retained privately and never exposed.")
  public AccountHostingSubscriptionLifecycleRepository(
      DSLContext dsl,
      AccountSubscriptionIntentRepository intents,
      Clock clock,
      ReconciliationLimits limits,
      AccountHostingSubscriptionLifecyclePolicy policy,
      String namespace,
      boolean livemode) {
    this.dsl = Objects.requireNonNull(dsl);
    this.intents = Objects.requireNonNull(intents);
    this.clock = Objects.requireNonNull(clock);
    this.limits =
        Objects.requireNonNull(limits, "Explicit bounded reconciliation limits are required");
    this.policy = Objects.requireNonNull(policy);
    ReconciliationLimits.wholeMilliseconds(policy.maximumObservationAge());
    ReconciliationLimits.wholeMilliseconds(policy.recoveryTiming().pastDueWarning());
    ReconciliationLimits.wholeMilliseconds(policy.recoveryTiming().grace());
    this.livemode = livemode;
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("Explicit Account workload namespace is required");
    }
    generations = new AccountAuthorityGenerationRepository(dsl);
    tenantIdentity = new FreshTenantIdentityAssociationRepository(dsl, namespace);
    billing = new AccountTenantEntitlementOutboxRepository(dsl);
    tenantEvents =
        new AccountTenantAuthorityEventRepository(
            dsl, new AccountAuthorityOutboxRepository(dsl), billing, tenantIdentity, generations);
  }

  /**
   * Read-only activation prerequisite, not an activation grant or a retained-row repair.
   *
   * <p>The caller owns a read-only transaction and must keep registration held on any failure. A
   * clear snapshot does not fence concurrent writers or qualify the remaining activation gates. No
   * subscription identities or retained evidence leave this check.
   */
  public void requireNoRetainedPositiveCompletionGaps(int queryTimeoutSeconds) {
    if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 5) {
      throw new IllegalArgumentException(
          "Retained-positive inspection requires a 1..5 second budget");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Retained-positive inspection requires read-only Account SQL");
    }
    var query =
        dsl.resultQuery(
            "SELECT EXISTS (SELECT 1 FROM subscription s WHERE s.superseded_at IS NULL "
                + "AND s.status IN ('trialing', 'active', 'past_due', 'grace') "
                + "AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_completions c "
                + "WHERE c.subscription_id = s.subscription_id)) AS unresolved");
    query.queryTimeout(queryTimeoutSeconds);
    Record result = Objects.requireNonNull(query.fetchOne(), "Retained-positive inspection absent");
    if (!Boolean.FALSE.equals(result.get("unresolved", Boolean.class))) {
      throw new FenceRejectedException(
          "HOSTING_ACTIVATION_UNAVAILABLE: retained positive completion evidence unresolved");
    }
  }

  /** Locked read capture only. No journal, intent, audit or fence changes precede final auth. */
  public ResolutionPreparation prepareTerminalResolution(Request request) {
    requireTransaction();
    Objects.requireNonNull(request);
    var original = intents.findCreation(request.creationRequestId()).orElseThrow();
    if (!request.tenantId().equals(original.tenantId())
        || !request.subscriptionId().equals(original.subscriptionId())
        || !request.actorAccountId().equals(original.owner().accountUuid())) {
      throw new FenceRejectedException("Exact same-owner terminal lifecycle target rejected");
    }
    Record journal =
        dsl.fetchOne(
            "SELECT * FROM subscription_lifecycle_terminal_resolutions WHERE resolution_request_id = ? FOR UPDATE",
            request.resolutionRequestId());
    // Exact committed-result disclosure belongs to the original authenticated actor. It must not
    // select a successor subscription or require the canceled predecessor to remain current.
    // Only a new mutation takes today's current-row/owner/instrument fence, in its original order.
    long currentVersion;
    if (journal == null) {
      var observedCurrent = intents.findCurrentByTenant(original.tenantId()).orElseThrow();
      currentVersion =
          intents.lockForLifecycle(original.requestId(), observedCurrent.rowVersion()).rowVersion();
    } else {
      currentVersion = journal.get("result_row_version", Long.class);
    }
    Record failed = operationForUpdate(request.failedRequestId());
    if (!request.creationRequestId().equals(failed.get("creation_request_id", UUID.class))
        || !request.subscriptionId().equals(failed.get("subscription_id", UUID.class))
        || !"BILLING_RECONCILIATION_TERMINAL_FAILURE".equals(failed.get("state", String.class))
        || request.expectedFailedOperationVersion() != failed.get("row_version", Long.class)) {
      throw new FenceRejectedException("Exact same-owner terminal lifecycle target rejected");
    }
    ExpectedBinding binding =
        expected(original, failed.get("provider_subscription_id", String.class));
    if (journal != null) {
      if (!request.digest().equals(journal.get("request_digest", String.class))
          || !Arrays.equals(request.payload(), journal.get("request_payload", byte[].class))) {
        throw new FenceRejectedException("IDEMPOTENCY_CONFLICT: terminal resolution tuple changed");
      }
      Record receipt =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT * FROM subscription_lifecycle_receipts WHERE request_id = ?",
                  request.resolutionRequestId()));
      if (!"COMMITTED".equals(journal.get("state", String.class))
          || !"canceled".equals(receipt.get("status", String.class))
          || !journal
              .get("receipt_digest", String.class)
              .equals(receipt.get("receipt_digest", String.class))) {
        throw new FenceRejectedException("Terminal resolution receipt readback differs");
      }
      new AccountAuditOutboxRepository(dsl)
          .findCanonicalTenantEnvelopeForUpdate(request.auditEventId())
          .filter(
              audit ->
                  request.digest().equals(audit.payloadDigest())
                      && "HOSTING_SUBSCRIPTION_TERMINAL_RESOLVED".equals(audit.eventType())
                      && request.tenantId().equals(audit.tenantUuid()))
          .orElseThrow(
              () -> new FenceRejectedException("Terminal resolution audit readback differs"));
      return new ResolutionPreparation(
          request,
          binding,
          original.owner().accountUuid(),
          currentVersion,
          now(),
          now(),
          new TransitionResult(
              request.resolutionRequestId(),
              Outcome.COMMITTED,
              journal.get("result_row_version", Long.class),
              null));
    }
    if (request.expectedSubscriptionVersion() != currentVersion
        || !request
            .failedRequestId()
            .equals(
                links(request.subscriptionId()).get("lifecycle_inflight_request_id", UUID.class))) {
      throw new FenceRejectedException("Terminal resolution lost its exact failed current fence");
    }
    Instant captured = now();
    Instant deadline =
        earlier(
            captured.plus(limits.lease()),
            captured.plusMillis(failed.get("observation_age_millis", Long.class)));
    return new ResolutionPreparation(
        request, binding, original.owner().accountUuid(), currentVersion, captured, deadline, null);
  }

  /**
   * Final cancellation-only owner mutation. The mandatory callback runs under exact target locks
   * immediately before writes; callback inputs/outputs alone do not establish Account authority. If
   * the callback throws an authentication denial, the caller may catch it outside this method and
   * commit its own failed-login accounting because this method has made no repository writes before
   * invoking the callback. A repository write added before the callback removes this narrow
   * exception; all other failures require rollback. Commit failures remain the caller transaction
   * manager’s responsibility.
   */
  public TransitionResult applyTerminalResolution(
      ResolutionPreparation prepared,
      StripeHostingSubscriptionObserver.Result result,
      Authorization authorization) {
    requireTransaction();
    Objects.requireNonNull(prepared);
    Objects.requireNonNull(authorization, "Explicit final Account authorization callback required");
    Request request = prepared.request;
    ResolutionPreparation locked = prepareTerminalResolution(request);
    Instant evaluated = now();
    if (locked.replay == null) {
      if (prepared.replay != null
          || !prepared.binding.equals(locked.binding)
          || evaluated.isBefore(prepared.capturedAt)
          || !evaluated.isBefore(prepared.deadline)
          || !(result instanceof ObservedSubscription observed)
          || !observed.providerStatusIsCanceled()
          || observed.readStartedAt().isBefore(prepared.capturedAt)
          || observed.observedAt().isAfter(evaluated)) {
        throw new FenceRejectedException(
            "Fresh exact canceled SDK evidence required for resolution");
      }
      requireObservedBinding(prepared.binding, observed);
      Record failed = operationForUpdate(request.failedRequestId());
      var decision = retainedPolicy(failed).evaluate(observed, evaluated, null);
      if (!decision.resolved() || decision.status() != Status.CANCELED) {
        throw new FenceRejectedException("Terminal cancellation evidence expired or is unresolved");
      }
    }
    Instant authenticationStarted = now();
    var actor =
        Objects.requireNonNull(
            authorization.requireAuthorized(
                new LockedTarget(request, locked.billingOwnerAccountId, locked.currentVersion)));
    Instant authenticated = now();
    if (!request.actorAccountId().equals(actor.actorAccountId())
        || !request.controlUiIssuanceOperationId().equals(actor.controlUiIssuanceOperationId())
        || !request.controlUiTokenHash().equals(actor.controlUiTokenHash())
        || actor.authenticatedAt().isBefore(authenticationStarted)
        || actor.authenticatedAt().isAfter(authenticated)) {
      throw new FenceRejectedException(
          "Final primary-authentication source differs from exact actor");
    }
    if (locked.replay != null) return locked.replay;
    if (!authenticated.isBefore(prepared.deadline)) {
      throw new FenceRejectedException("Terminal resolution capture expired during authorization");
    }
    new AccountAuditOutboxRepository(dsl)
        .appendCanonicalTenant(
            request.auditEventId(),
            request.tenantId().toString(),
            "HOSTING_SUBSCRIPTION_TERMINAL_RESOLVED",
            new String(request.payload(), StandardCharsets.UTF_8));
    UUID attemptId = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO subscription_lifecycle_terminal_resolutions (resolution_request_id, failed_request_id, creation_request_id, "
            + "subscription_id, tenant_id, expected_failed_version, expected_subscription_version, actor_account_id, "
            + "control_ui_issuance_operation_id, control_ui_token_hash, audit_event_id, audit_reason, request_payload, request_digest, "
            + "attempt_id, captured_at, expires_at, authenticated_at, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, 'PREPARED')",
        request.resolutionRequestId(),
        request.failedRequestId(),
        request.creationRequestId(),
        request.subscriptionId(),
        request.tenantId(),
        request.expectedFailedOperationVersion(),
        request.expectedSubscriptionVersion(),
        request.actorAccountId(),
        request.controlUiIssuanceOperationId(),
        request.controlUiTokenHash(),
        request.auditEventId(),
        request.auditReason(),
        request.payload(),
        request.digest(),
        attemptId,
        utc(prepared.capturedAt),
        utc(prepared.deadline),
        utc(actor.authenticatedAt()));
    if (dsl.execute(
            "INSERT INTO subscription_lifecycle_operations (request_id, creation_request_id, subscription_id, tenant_id, expected_row_version, "
                + "reserved_row_version, provider_subscription_id, provisioning_attempt_id, request_payload, request_digest, state, row_version, "
                + "max_attempts, max_stalled_attempts, lease_millis, backoff_millis, observation_age_millis, warning_millis, grace_millis, deadline_at, retry_at, created_at) "
                + "SELECT ?, creation_request_id, subscription_id, tenant_id, ?, ?, provider_subscription_id, provisioning_attempt_id, ?, ?, 'PENDING', 1, "
                + "1, 1, lease_millis, backoff_millis, observation_age_millis, warning_millis, grace_millis, ?::timestamptz, ?::timestamptz, ?::timestamptz "
                + "FROM subscription_lifecycle_operations WHERE request_id = ? AND state = 'BILLING_RECONCILIATION_TERMINAL_FAILURE' AND row_version = ?",
            request.resolutionRequestId(),
            request.expectedSubscriptionVersion(),
            request.expectedSubscriptionVersion() + 1,
            request.payload(),
            request.digest(),
            utc(prepared.deadline),
            utc(prepared.capturedAt),
            utc(prepared.capturedAt),
            request.failedRequestId(),
            request.expectedFailedOperationVersion())
        != 1) {
      throw new FenceRejectedException("Terminal resolution exact failed-operation copy rejected");
    }
    if (dsl.execute(
            "UPDATE subscription SET lifecycle_inflight_request_id = ?, row_version = row_version + 1 "
                + "WHERE subscription_id = ? AND row_version = ? AND lifecycle_inflight_request_id = ? AND superseded_at IS NULL",
            request.resolutionRequestId(),
            request.subscriptionId(),
            request.expectedSubscriptionVersion(),
            request.failedRequestId())
        != 1) {
      throw new FenceRejectedException("Terminal resolution expected-current CAS rejected");
    }
    dsl.execute(
        "INSERT INTO subscription_lifecycle_attempts (attempt_id, request_id, attempt_number, started_at, expires_at) VALUES (?, ?, 1, ?::timestamptz, ?::timestamptz)",
        attemptId,
        request.resolutionRequestId(),
        utc(prepared.capturedAt),
        utc(prepared.deadline));
    dsl.execute(
        "UPDATE subscription_lifecycle_operations SET attempt_count = 1, last_attempt_id = ?, row_version = row_version + 1 WHERE request_id = ?",
        attemptId,
        request.resolutionRequestId());
    TransitionResult committed =
        apply(
            new Preparation(
                request.resolutionRequestId(),
                attemptId,
                prepared.binding,
                Outcome.READY,
                null,
                request.expectedSubscriptionVersion() + 1),
            result);
    if (committed.outcome() != Outcome.COMMITTED)
      throw new FenceRejectedException("Cancellation resolution did not commit");
    dsl.execute(
        "UPDATE subscription_lifecycle_terminal_resolutions SET state = 'COMMITTED', result_row_version = ?, "
            + "receipt_digest = (SELECT receipt_digest FROM subscription_lifecycle_receipts WHERE request_id = ?), committed_at = ?::timestamptz "
            + "WHERE resolution_request_id = ? AND state = 'PREPARED'",
        committed.committedRowVersion(),
        request.resolutionRequestId(),
        utc(now()),
        request.resolutionRequestId());
    return committed;
  }

  public static final class ResolutionPreparation {
    private final Request request;
    private final ExpectedBinding binding;
    private final UUID billingOwnerAccountId;
    private final long currentVersion;
    private final Instant capturedAt;
    private final Instant deadline;
    private final TransitionResult replay;

    private ResolutionPreparation(
        Request request,
        ExpectedBinding binding,
        UUID billingOwnerAccountId,
        long currentVersion,
        Instant capturedAt,
        Instant deadline,
        TransitionResult replay) {
      this.request = request;
      this.binding = binding;
      this.billingOwnerAccountId = billingOwnerAccountId;
      this.currentVersion = currentVersion;
      this.capturedAt = capturedAt;
      this.deadline = deadline;
      this.replay = replay;
    }

    public Request request() {
      return request;
    }

    public ExpectedBinding binding() {
      return binding;
    }

    public TransitionResult replayResult() {
      return replay;
    }
  }

  /**
   * Reserves currentness before an SDK read; stable request replay never issues a second
   * transition.
   */
  public Preparation prepare(
      UUID requestId,
      UUID creationId,
      long expectedVersion,
      String providerId,
      UUID provisioningAttemptId) {
    return prepare(requestId, creationId, expectedVersion, providerId, provisioningAttemptId, null);
  }

  /** Captures the exact unpaid checkout for a fresh terminal provider read without reserving it. */
  public UnpaidCancellationCapture captureUnpaidFirstInvoiceCancellation(
      UUID requestId, UUID creationId, long expectedVersion, String providerId) {
    requireTransaction();
    byte[] input =
        reconciliationInput(requestId, creationId, expectedVersion, providerId, null, true);
    var original = intents.findCreation(creationId).orElseThrow();
    lockReconciliationWallet(original);
    Record operation = matchingOperation(requestId, input);
    if (operation != null) {
      if (!"COMMITTED".equals(operation.get("state", String.class))) {
        throw new FenceRejectedException("Unpaid cancellation has no committed outcome to replay");
      }
      return new UnpaidCancellationCapture(
          requestId,
          creationId,
          expectedVersion,
          null,
          null,
          null,
          null,
          preparationOutcome(operation).result());
    }
    var current = intents.lockForLifecycle(creationId, expectedVersion);
    ExpectedBinding binding = expected(original, providerId);
    var invoice = requireUnpaidFirstInvoice(current, binding);
    UUID confirmationId = cancellationClaim(current.subscriptionId());
    if (links(current.subscriptionId()).get("lifecycle_inflight_request_id") != null) {
      throw new FenceRejectedException("Another lifecycle request owns the current fence");
    }
    return new UnpaidCancellationCapture(
        requestId, creationId, expectedVersion, binding, now(), invoice, confirmationId, null);
  }

  /** A nonterminal or ambiguous observation never takes the durable lifecycle fence. */
  public TransitionResult applyUnpaidFirstInvoiceCancellation(
      UnpaidCancellationCapture captured,
      StripeHostingSubscriptionObserver.Result result,
      TerminalUnpaidObservation unpaid) {
    requireTransaction();
    Record isolation = dsl.fetchOne("SHOW transaction_isolation");
    if (isolation == null || !"read committed".equals(isolation.get(0, String.class))) {
      throw new IllegalStateException(
          "Unpaid first-invoice cancellation requires READ COMMITTED isolation");
    }
    Objects.requireNonNull(captured);
    if (captured.replayResult() != null) return captured.replayResult();
    Instant evaluated = now();
    ObservedSubscription observed = result instanceof ObservedSubscription value ? value : null;
    boolean incompleteExpired = observed != null && observed.providerStatusIsIncompleteExpired();
    var cancellationDecision =
        incompleteExpired
            ? policy.evaluateIncompleteExpiredFirstInvoiceCancellation(observed, evaluated)
            : policy.evaluate(result, evaluated, null);
    boolean terminalInvoiceRequired = captured.confirmationId != null || incompleteExpired;
    if (observed == null
        || (!observed.providerStatusIsCanceled() && !incompleteExpired)
        || observed.readStartedAt().isBefore(captured.capturedAt)
        || !evaluated.isBefore(captured.capturedAt.plus(limits.lease()))
        || cancellationDecision.status() != Status.CANCELED
        || (terminalInvoiceRequired
            && !exactTerminalUnpaidObservation(captured, observed, unpaid, evaluated))) {
      return new TransitionResult(
          captured.requestId, Outcome.RECONCILING, 0, "UNPAID_CANCELLATION_UNCONFIRMED");
    }
    requireObservedBinding(captured.binding(), observed);
    var prepared =
        prepare(
            captured.requestId,
            captured.creationId,
            captured.expectedVersion,
            captured.binding().providerSubscriptionId(),
            null,
            captured);
    if (prepared.outcome() == Outcome.COMMITTED) return prepared.result();
    if (prepared.outcome() == Outcome.RECONCILING) return prepared.result();
    if (prepared.outcome() != Outcome.READY) {
      throw new FenceRejectedException(
          "Unpaid first-invoice cancellation did not reserve its receipt");
    }
    if (captured.confirmationId != null) {
      dsl.execute(
          "INSERT INTO subscription_first_invoice_no_send_cancellations "
              + "(cancellation_request_id, confirmation_request_id, invoice_id, payment_intent_id, "
              + "read_started_at, observed_at) VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
          captured.requestId,
          captured.confirmationId,
          unpaid.invoiceId(),
          unpaid.paymentIntentId(),
          utc(unpaid.readStartedAt().truncatedTo(ChronoUnit.MICROS)),
          utc(unpaid.observedAt().truncatedTo(ChronoUnit.MICROS)));
    }
    return apply(prepared, observed, true);
  }

  private static byte[] reconciliationInput(
      UUID requestId,
      UUID creationId,
      long expectedVersion,
      String providerId,
      UUID provisioningAttemptId,
      boolean unpaidCancellation) {
    AccountBillingCustomerRecord.requireUuid(requestId);
    AccountBillingCustomerRecord.requireUuid(creationId);
    if (expectedVersion < 1
        || expectedVersion >= Long.MAX_VALUE - 1
        || providerId == null
        || !providerId.matches("sub_[A-Za-z0-9]{1,251}")) {
      throw new IllegalArgumentException(
          "Exact provider identity and advanceable current version are required");
    }
    return AccountHostingSubscriptionLifecycleEvidence.encode(
        Map.of(
            "schemaVersion",
            unpaidCancellation
                ? UNPAID_FIRST_INVOICE_CANCELLATION_SCHEMA
                : "account-hosting-reconciliation-request/v1",
            "requestId",
            requestId.toString(),
            "creationRequestId",
            creationId.toString(),
            "expectedRowVersion",
            Long.toString(expectedVersion),
            "providerSubscriptionId",
            providerId,
            "provisioningAttemptId",
            provisioningAttemptId == null ? "" : provisioningAttemptId.toString()));
  }

  private Record matchingOperation(UUID requestId, byte[] input) {
    Record operation =
        dsl.fetchOne(
            "SELECT * FROM subscription_lifecycle_operations WHERE request_id = ?", requestId);
    if (operation != null
        && !Arrays.equals(input, operation.get("request_payload", byte[].class))) {
      throw new FenceRejectedException("IDEMPOTENCY_CONFLICT: lifecycle request changed");
    }
    return operation;
  }

  private Preparation prepare(
      UUID requestId,
      UUID creationId,
      long expectedVersion,
      String providerId,
      UUID provisioningAttemptId,
      UnpaidCancellationCapture cancellation) {
    requireTransaction();
    boolean unpaidCancellation = cancellation != null;
    byte[] input =
        reconciliationInput(
            requestId,
            creationId,
            expectedVersion,
            providerId,
            provisioningAttemptId,
            unpaidCancellation);
    var original = intents.findCreation(creationId).orElseThrow();
    // Serialize same-owner retries before reading the mutable operation head. A same-request
    // contender observes the winner's pending/committed outcome instead of a stale pre-lock image.
    lockReconciliationWallet(original);
    Record operation = matchingOperation(requestId, input);
    if (unpaidCancellation
        && operation != null
        && !"COMMITTED".equals(operation.get("state", String.class))) {
      throw new FenceRejectedException("Unpaid cancellation has no committed outcome to replay");
    }
    if (operation != null && !"PENDING".equals(operation.get("state", String.class))) {
      return preparationOutcome(operation);
    }
    var current =
        intents.lockForLifecycle(
            creationId,
            operation == null
                ? expectedVersion
                : operation.get("reserved_row_version", Long.class));
    Instant now = now();
    if (now.isBefore(original.createdAt())) {
      throw new FenceRejectedException("Lifecycle clock predates its durable creation operation");
    }
    if (operation == null) {
      Record currentLinks = links(current.subscriptionId());
      if (currentLinks.get("lifecycle_inflight_request_id") != null) {
        throw new FenceRejectedException("Another lifecycle request owns the current fence");
      }
      Record priorReceipt = receiptForCurrent(currentLinks);
      if (unpaidCancellation) {
        var invoice = requireUnpaidFirstInvoice(current, expected(original, providerId));
        if (!invoice.digest().equals(cancellation.invoice.digest())
            || !Objects.equals(
                cancellationClaim(current.subscriptionId()), cancellation.confirmationId)) {
          throw new FenceRejectedException("First-invoice cancellation evidence changed");
        }
        if (Boolean.TRUE.equals(
            dsl.fetchSingle(
                    "SELECT account_first_invoice_signed_success_retained(?, ?)",
                    invoice.paymentIntentId(),
                    invoice.livemode())
                .get(0, Boolean.class))) {
          return new Preparation(
              requestId, null, null, Outcome.RECONCILING, "UNPAID_CANCELLATION_UNCONFIRMED", 0);
        }
      }
      if (current.providerSubscriptionId() != null
          && !providerId.equals(current.providerSubscriptionId())) {
        throw new FenceRejectedException(
            "Reconciliation cannot rebind the canonical provider identity");
      }
      if (priorReceipt == null) {
        if (current.providerSubscriptionId() != null) {
          if (!unpaidCancellation
              && (firstPaymentProof(current.subscriptionId(), requestId) == null
                  || provisioningAttemptId != null
                  || !"provisioning".equals(current.status()))) {
            throw new FenceRejectedException(
                "Initial paid lifecycle requires exact signed first-invoice completion evidence");
          }
        } else {
          Record work = workForUpdate(creationId);
          Record claimedAttempt =
              provisioningAttemptId == null
                  ? null
                  : dsl.fetchOne(
                      "SELECT claimed_at FROM subscription_provisioning_attempts WHERE attempt_id = ?",
                      provisioningAttemptId);
          Instant provisioningNow = databaseNow();
          if (provisioningAttemptId == null
              || !"provisioning".equals(current.status())
              || claimedAttempt == null
              || provisioningNow.isBefore(instant(claimedAttempt, "claimed_at"))
              || !"CLAIMED".equals(work.get("phase", String.class))
              || !provisioningAttemptId.equals(work.get("last_attempt_id", UUID.class))
              || current.rowVersion() != work.get("subscription_row_version", Long.class)
              || !provisioningNow.isBefore(instant(work, "lease_expires_at"))
              || !provisioningNow.isBefore(instant(work, "deadline_at"))) {
            throw new FenceRejectedException(
                "Initial confirmation lacks its exact durable provisioning claim");
          }
        }
      } else if (current.providerSubscriptionId() == null || provisioningAttemptId != null) {
        throw new FenceRejectedException(
            "Reconciliation cannot rebind a provider or reuse provisioning work");
      }
      long warning =
          priorReceipt != null && priorReceipt.get("past_due_since") != null
              ? priorReceipt.get("warning_millis", Long.class)
              : policy.recoveryTiming().pastDueWarning().toMillis();
      long grace =
          priorReceipt != null && priorReceipt.get("past_due_since") != null
              ? priorReceipt.get("grace_millis", Long.class)
              : policy.recoveryTiming().grace().toMillis();
      dsl.execute(
          "INSERT INTO subscription_lifecycle_operations (request_id, creation_request_id, subscription_id, tenant_id, "
              + "expected_row_version, reserved_row_version, provider_subscription_id, provisioning_attempt_id, request_payload, request_digest, "
              + "state, row_version, max_attempts, max_stalled_attempts, lease_millis, backoff_millis, observation_age_millis, warning_millis, grace_millis, "
              + "deadline_at, retry_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 1, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)",
          requestId,
          creationId,
          current.subscriptionId(),
          current.tenantId(),
          expectedVersion,
          expectedVersion + 1,
          providerId,
          provisioningAttemptId,
          input,
          AccountHostingSubscriptionLifecycleEvidence.digest(input),
          limits.maximumAttempts(),
          limits.maximumStalledAttempts(),
          limits.lease().toMillis(),
          limits.backoff().toMillis(),
          policy.maximumObservationAge().toMillis(),
          warning,
          grace,
          utc(now.plus(limits.absoluteTimeout())),
          utc(now),
          utc(now));
      if (dsl.execute(
              "UPDATE subscription SET lifecycle_inflight_request_id = ?, row_version = row_version + 1 "
                  + "WHERE subscription_id = ? AND row_version = ? AND lifecycle_inflight_request_id IS NULL AND superseded_at IS NULL",
              requestId,
              current.subscriptionId(),
              expectedVersion)
          != 1) {
        throw new FenceRejectedException("Lifecycle prepare expected-current CAS rejected");
      }
    }
    operation = operationForUpdate(requestId);
    if (!now.isBefore(instant(operation, "deadline_at"))) {
      terminalLifecycle(operation, "ABSOLUTE_DEADLINE", now);
      return preparationOutcome(operationForUpdate(requestId));
    }
    if (operation.get("attempt_count", Integer.class)
            >= operation.get("max_attempts", Integer.class)
        || operation.get("stalled_count", Integer.class)
            >= operation.get("max_stalled_attempts", Integer.class)) {
      terminalLifecycle(operation, "ATTEMPT_OR_STALL_BUDGET", now);
      return preparationOutcome(operationForUpdate(requestId));
    }
    if (now.isBefore(instant(operation, "retry_at"))) {
      return new Preparation(requestId, null, null, Outcome.RECONCILING, "BACKOFF_OR_INFLIGHT", 0);
    }
    UUID attemptId = UUID.randomUUID();
    Instant attemptStarted = unpaidCancellation ? cancellation.capturedAt : now;
    Instant expires =
        earlier(
            attemptStarted.plusMillis(operation.get("lease_millis", Long.class)),
            instant(operation, "deadline_at"));
    int number = operation.get("attempt_count", Integer.class) + 1;
    dsl.execute(
        "INSERT INTO subscription_lifecycle_attempts (attempt_id, request_id, attempt_number, started_at, expires_at) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)",
        attemptId,
        requestId,
        number,
        utc(attemptStarted),
        utc(expires));
    dsl.execute(
        "UPDATE subscription_lifecycle_operations SET attempt_count = ?, last_attempt_id = ?, retry_at = ?::timestamptz, "
            + "row_version = row_version + 1 WHERE request_id = ?",
        number,
        attemptId,
        utc(expires.plusMillis(operation.get("backoff_millis", Long.class))),
        requestId);
    return new Preparation(
        requestId,
        attemptId,
        expected(original, providerId),
        Outcome.READY,
        null,
        operation.get("reserved_row_version", Long.class));
  }

  /**
   * Accepts only the observer's sealed SDK result, rechecking every binding under the original lock
   * order.
   */
  public TransitionResult apply(
      Preparation prepared, StripeHostingSubscriptionObserver.Result result) {
    return apply(prepared, result, false);
  }

  private TransitionResult apply(
      Preparation prepared,
      StripeHostingSubscriptionObserver.Result result,
      boolean unpaidCancellation) {
    requireTransaction();
    Objects.requireNonNull(prepared);
    Objects.requireNonNull(result);
    Record retained = operationForUpdateRead(prepared.requestId());
    var current =
        intents.lockForLifecycle(
            retained.get("creation_request_id", UUID.class),
            retained.get("reserved_row_version", Long.class));
    Record operation = operationForUpdate(prepared.requestId());
    Instant now = now();
    Record attempt =
        dsl.fetchOne(
            "SELECT * FROM subscription_lifecycle_attempts WHERE attempt_id = ?",
            prepared.attemptId());
    if (!"PENDING".equals(operation.get("state", String.class))
        || !prepared.attemptId().equals(operation.get("last_attempt_id", UUID.class))
        || attempt == null
        || now.isBefore(instant(attempt, "started_at"))
        || !now.isBefore(instant(attempt, "expires_at"))
        || !now.isBefore(instant(operation, "deadline_at"))
        || result.readStartedAt().isBefore(instant(attempt, "started_at"))
        || !result
            .providerSubscriptionId()
            .equals(operation.get("provider_subscription_id", String.class))) {
      throw new FenceRejectedException("Stale, expired or mismatched lifecycle SDK attempt");
    }
    var original = intents.findCreation(current.creationRequestId()).orElseThrow();
    ExpectedBinding binding =
        expected(original, operation.get("provider_subscription_id", String.class));
    if (result instanceof ObservedSubscription observed) {
      requireObservedBinding(binding, observed);
    }
    Record oldReceipt = receiptForCurrent(links(current.subscriptionId()));
    Instant origin = retainedPastDueOrigin(oldReceipt);
    // A provider read cannot establish when an uninterrupted payment-failure episode began.
    // Missing retained history must remain unresolved rather than restarting warning/grace.
    var lifecyclePolicy = retainedPolicy(operation);
    var decision =
        unpaidCancellation
                && result instanceof ObservedSubscription observed
                && observed.providerStatusIsIncompleteExpired()
            ? lifecyclePolicy.evaluateIncompleteExpiredFirstInvoiceCancellation(observed, now)
            : lifecyclePolicy.evaluate(result, now, origin);
    if (unpaidCancellation
        && (!(result instanceof ObservedSubscription observed)
            || (!observed.providerStatusIsCanceled()
                && !observed.providerStatusIsIncompleteExpired())
            || decision.status() != Status.CANCELED)) {
      throw new FenceRejectedException("Fresh exact terminal SDK evidence is required");
    }
    byte[] observation = AccountHostingSubscriptionLifecycleEvidence.observation(result);
    String observationDigest = AccountHostingSubscriptionLifecycleEvidence.digest(observation);
    String factsDigest =
        AccountHostingSubscriptionLifecycleEvidence.digest(
            AccountHostingSubscriptionLifecycleEvidence.encode(
                AccountHostingSubscriptionLifecycleEvidence.facts(result)));
    ObservedSubscription observed = result instanceof ObservedSubscription value ? value : null;
    dsl.execute(
        "INSERT INTO subscription_lifecycle_observations (attempt_id, request_id, payload, evidence_digest, facts_digest, "
            + "raw_status, period_start, period_end, read_started_at, observed_at, mapping_reason) VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?)",
        prepared.attemptId(),
        prepared.requestId(),
        observation,
        observationDigest,
        factsDigest,
        observed == null ? null : observed.providerStatus(),
        observed == null ? null : utcNullable(observed.currentPeriodStart().orElse(null)),
        observed == null ? null : utcNullable(observed.currentPeriodEnd().orElse(null)),
        utc(result.readStartedAt().truncatedTo(ChronoUnit.MICROS)),
        utc(result.observedAt().truncatedTo(ChronoUnit.MICROS)),
        decision.reason().name());
    if (!decision.resolved()) {
      int stalled =
          factsDigest.equals(operation.get("last_facts_digest", String.class))
              ? operation.get("stalled_count", Integer.class) + 1
              : 1;
      dsl.execute(
          "UPDATE subscription_lifecycle_operations SET last_facts_digest = ?, stalled_count = ?, failure_reason = ?, "
              + "retry_at = ?::timestamptz, row_version = row_version + 1 WHERE request_id = ?",
          factsDigest,
          stalled,
          decision.reason().name(),
          utc(
              earlier(
                  now.plusMillis(operation.get("backoff_millis", Long.class)),
                  instant(operation, "deadline_at"))),
          prepared.requestId());
      operation = operationForUpdate(prepared.requestId());
      if (stalled >= operation.get("max_stalled_attempts", Integer.class)
          || operation.get("attempt_count", Integer.class)
              >= operation.get("max_attempts", Integer.class)) {
        terminalLifecycle(operation, "ATTEMPT_OR_STALL_BUDGET", now);
        return new TransitionResult(
            prepared.requestId(), Outcome.TERMINAL_FAILURE, 0, decision.reason().name());
      }
      return new TransitionResult(
          prepared.requestId(), Outcome.RECONCILING, 0, decision.reason().name());
    }
    Objects.requireNonNull(observed);
    if (!unpaidCancellation
        && oldReceipt == null
        && current.status().equals("provisioning")
        && decision.status() == Status.CANCELED
        && firstPaymentProof(current.subscriptionId(), prepared.requestId()) != null) {
      String reason = "PAID_FIRST_INVOICE_PROVIDER_CANCELED";
      terminalLifecycle(operation, reason, now);
      return new TransitionResult(prepared.requestId(), Outcome.TERMINAL_FAILURE, 0, reason);
    }
    Record firstPayment = null;
    if (java.util.Set.of(Status.TRIALING, Status.ACTIVE, Status.PAST_DUE, Status.GRACE)
            .contains(decision.status())
        && dsl.fetchOne(
                "SELECT subscription_id FROM subscription_first_invoice_completions WHERE subscription_id = ?",
                current.subscriptionId())
            == null) {
      firstPayment = firstPaymentProof(current.subscriptionId(), prepared.requestId());
      if (firstPayment == null)
        throw new FenceRejectedException(
            "Authorizing hosting requires exact signed first-invoice payment completion");
    }
    if (current.status().equals("canceled") && decision.status() != Status.CANCELED) {
      throw new FenceRejectedException("A canceled subscription cannot be reactivated");
    }
    UUID inflight =
        links(current.subscriptionId()).get("lifecycle_inflight_request_id", UUID.class);
    if (!prepared.requestId().equals(inflight)) {
      throw new FenceRejectedException("Lifecycle operation lost its current fence");
    }
    if (current.providerSubscriptionId() == null) {
      dsl.execute(
          "INSERT INTO subscription_provider_history (provider_subscription_id, subscription_id, operation_kind, request_id, "
              + "provider_operation_id, role, effective_at, evidence_digest) VALUES (?, ?, 'SUBSCRIPTION_PROVISIONING', ?, ?, 'canonical', ?::timestamptz, ?)",
          observed.providerSubscriptionId(),
          current.subscriptionId(),
          original.requestId(),
          original.subscriptionProvisioningRequestId(),
          utc(observed.observedAt()),
          observationDigest);
    } else {
      var mapping = intents.lookupProviderId(observed.providerSubscriptionId());
      if (mapping.history().isEmpty()
          || !mapping.history().orElseThrow().subscriptionId().equals(current.subscriptionId())
          || !observed.providerSubscriptionId().equals(current.providerSubscriptionId())) {
        throw new FenceRejectedException("Provider reconciliation has no exact canonical mapping");
      }
    }
    var source = tenantIdentity.read(current.tenantId()).orElseThrow();
    var authority =
        generations.read(
            AccountAuthorityGenerationRepository.AuthorityScope.tenant(current.tenantId()));
    if (dsl.fetchOne(
            "SELECT tenant_uuid FROM account_tenant_authority_source_records WHERE tenant_uuid = ? FOR UPDATE",
            current.tenantId())
        == null) {
      tenantEvents.establishFirstEventSource(
          current.tenantId(), prepared.requestId(), source, authority);
    }
    var next = generations.advance(authority, null);
    long entitlementVersion =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT greatest("
                        + "coalesce((SELECT max(entitlement_version) FROM subscription_lifecycle_receipts WHERE tenant_id = ?), 0), "
                        + "coalesce((SELECT entitlement_version FROM account_demo_tenant_entitlements WHERE tenant_uuid = ?), 0)) + 1 AS next_version",
                    current.tenantId(),
                    current.tenantId()))
            .get("next_version", Long.class);
    if (!observed.providerStatus().equals("past_due")) {
      origin = null;
    }
    Map<String, Object> receipt =
        receiptFields(
            operation,
            original,
            current.rowVersion() + 1,
            entitlementVersion,
            observed,
            observationDigest,
            decision,
            origin,
            next.generation(),
            next.sourceVersion());
    byte[] receiptPayload = AccountHostingSubscriptionLifecycleEvidence.encode(receipt);
    UUID eventId = TenantAuthorityEventV1Codec.tenantBillingEventId(prepared.requestId());
    var event =
        billing.appendHosting(
            current.tenantId(),
            prepared.requestId(),
            sequence ->
                AccountHostingSubscriptionLifecycleEvidence.billing(
                    current.tenantId(),
                    prepared.requestId(),
                    retained.get("request_digest", String.class),
                    sequence,
                    eventId,
                    receipt));
    var authorityEvent =
        tenantEvents.appendHosting(
            prepared.requestId(),
            operation.get("request_digest", String.class),
            source,
            next,
            event.sequence,
            event.eventId,
            event.digest());
    dsl.execute(
        "INSERT INTO subscription_lifecycle_receipts (request_id, subscription_id, tenant_id, request_digest, subscription_row_version, "
            + "status, plan_quote_digest, provider_subscription_id, attempt_id, observation_digest, receipt_payload, receipt_digest, valid_until, cutoff_at, "
            + "past_due_since, warning_millis, grace_millis, entitlement_version, billing_sequence, billing_event_id, billing_event_digest, authority_generation, "
            + "authority_source_version, authority_sequence, authority_event_id, authority_event_digest, committed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
        prepared.requestId(),
        current.subscriptionId(),
        current.tenantId(),
        operation.get("request_digest", String.class),
        current.rowVersion() + 1,
        decision.status().storageValue(),
        original.planQuote().digest(),
        observed.providerSubscriptionId(),
        prepared.attemptId(),
        observationDigest,
        receiptPayload,
        AccountHostingSubscriptionLifecycleEvidence.digest(receiptPayload),
        utc(decision.receiptValidUntil()),
        utcNullable(decision.cutoffAt()),
        utcNullable(origin),
        operation.get("warning_millis", Long.class),
        operation.get("grace_millis", Long.class),
        entitlementVersion,
        event.sequence,
        event.eventId,
        event.digest(),
        next.generation(),
        next.sourceVersion(),
        authorityEvent.outboxSequence(),
        authorityEvent.eventId().toString(),
        authorityEvent.eventDigest(),
        utc(now));
    if (firstPayment != null) {
      var auditFields = new LinkedHashMap<String, Object>();
      auditFields.put("schemaVersion", "account-hosting-first-invoice-completed/v1");
      auditFields.put("checkoutRequestId", firstPayment.get("request_id", UUID.class).toString());
      auditFields.put("subscriptionId", current.subscriptionId().toString());
      auditFields.put("invoiceId", firstPayment.get("invoice_id", String.class));
      auditFields.put("paymentIntentId", firstPayment.get("payment_intent_id", String.class));
      auditFields.put("tupleDigest", firstPayment.get("tuple_digest", String.class));
      auditFields.put(
          "receiptDigest", AccountHostingSubscriptionLifecycleEvidence.digest(receiptPayload));
      new AccountAuditOutboxRepository(dsl)
          .appendCanonicalTenant(
              firstPayment.get("audit_event_id", UUID.class),
              current.tenantId().toString(),
              "HOSTING_FIRST_INVOICE_COMPLETED",
              new String(
                  AccountHostingSubscriptionLifecycleEvidence.encode(auditFields),
                  StandardCharsets.UTF_8));
      dsl.execute(
          "INSERT INTO subscription_first_invoice_completions (subscription_id, request_id, lifecycle_request_id, tuple_digest, receipt_digest, audit_event_id, committed_at) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz)",
          current.subscriptionId(),
          firstPayment.get("request_id"),
          prepared.requestId(),
          firstPayment.get("tuple_digest"),
          AccountHostingSubscriptionLifecycleEvidence.digest(receiptPayload),
          firstPayment.get("audit_event_id", UUID.class),
          utc(now));
    }
    UUID terminalResolution = current.terminalResolutionId();
    boolean terminalProviderCancellation =
        observed.providerStatusIsCanceled()
            || (unpaidCancellation && observed.providerStatusIsIncompleteExpired());
    if (terminalProviderCancellation && terminalResolution == null) {
      terminalResolution = UUID.randomUUID();
      dsl.execute(
          "INSERT INTO subscription_provider_terminal_resolutions (resolution_id, request_id, subscription_id, cleanup_provider_identity, "
              + "provider_subscription_id, outcome, effective_at, evidence_digest) VALUES (?, ?, ?, ?, ?, 'CANCELED', ?::timestamptz, ?)",
          terminalResolution,
          original.requestId(),
          current.subscriptionId(),
          original.cleanupProviderIdentity(),
          observed.providerSubscriptionId(),
          utc(decision.cutoffAt()),
          observationDigest);
      dsl.execute(
          "UPDATE subscription_instrument_reservations SET terminal_resolution_id = ?, released_at = ?::timestamptz WHERE request_id = ? AND released_at IS NULL",
          terminalResolution,
          utc(now),
          original.requestId());
    }
    if (dsl.execute(
            "UPDATE subscription SET status = ?, provider_subscription_id = ?, terminal_resolution_id = ?, "
                + "lifecycle_inflight_request_id = NULL, current_receipt_request_id = ?, row_version = row_version + 1 "
                + "WHERE subscription_id = ? AND row_version = ? AND lifecycle_inflight_request_id = ? AND superseded_at IS NULL",
            decision.status().storageValue(),
            observed.providerSubscriptionId(),
            terminalResolution,
            prepared.requestId(),
            current.subscriptionId(),
            current.rowVersion(),
            prepared.requestId())
        != 1) {
      throw new FenceRejectedException("Lifecycle expected-current commit CAS rejected");
    }
    dsl.execute(
        "UPDATE subscription_lifecycle_operations SET state = 'COMMITTED', last_facts_digest = ?, failure_reason = NULL, "
            + "row_version = row_version + 1 WHERE request_id = ?",
        factsDigest,
        prepared.requestId());
    if (unpaidCancellation) {
      if (dsl.execute(
              "UPDATE subscription_provisioning_work SET phase = 'CANCELED', row_version = row_version + 1 WHERE request_id = ? AND phase = 'AWAITING_PAYMENT'",
              original.requestId())
          != 1) {
        throw new FenceRejectedException(
            "Unpaid first-invoice cancellation lost its provisioning work");
      }
    } else if (operation.get("provisioning_attempt_id") != null || firstPayment != null) {
      dsl.execute(
          "UPDATE subscription_provisioning_work SET phase = 'CONFIRMED', row_version = row_version + 1 WHERE request_id = ? AND phase IN ('CLAIMED','AWAITING_PAYMENT')",
          original.requestId());
    }
    tenantEvents.readHostingCheckpoint(current.tenantId(), prepared.requestId());
    return new TransitionResult(
        prepared.requestId(), Outcome.COMMITTED, current.rowVersion() + 1, null);
  }

  private AccountHostingFirstInvoicePayment requireUnpaidFirstInvoice(
      AccountSubscriptionCurrentRecord current, ExpectedBinding binding) {
    var payments = new AccountHostingFirstInvoicePaymentRepository(dsl, intents, clock);
    var invoice = payments.find(current.subscriptionId());
    if (invoice == null
        || current.terminalResolutionId() != null
        || links(current.subscriptionId()).get("current_receipt_request_id") != null
        || !current.creationRequestId().equals(invoice.creationRequestId())
        || !current.tenantId().equals(invoice.tenantId())
        || !current.owner().accountUuid().equals(invoice.ownerAccountId())
        || !current.paymentInstrumentId().equals(invoice.paymentInstrumentId())
        || current.reservedInstrumentVersion() != invoice.instrumentVersion()
        || !current.planQuote().digest().equals(invoice.quoteDigest())
        || !binding.providerSubscriptionId().equals(invoice.providerSubscriptionId())
        || !binding.providerCustomerId().equals(invoice.providerCustomerId())
        || !binding.providerPaymentMethodId().equals(invoice.providerPaymentMethodId())
        || !binding.providerPriceId().equals(invoice.providerPriceId())
        || binding.expectedQuantity() != invoice.providerQuantity()
        || binding.livemode() != invoice.livemode()
        || dsl.fetchOne(
                "SELECT 1 FROM subscription_first_invoice_success_evidence WHERE subscription_id = ?",
                current.subscriptionId())
            != null) {
      throw new FenceRejectedException("Exact unpaid first invoice is required for cancellation");
    }
    payments.requireAwaitingPayment(invoice, current.rowVersion());
    return invoice;
  }

  private boolean exactTerminalUnpaidObservation(
      UnpaidCancellationCapture captured,
      ObservedSubscription observed,
      TerminalUnpaidObservation unpaid,
      Instant evaluatedAt) {
    if (captured.invoice == null || unpaid == null) return false;
    return captured.invoice.invoiceId().equals(unpaid.invoiceId())
        && captured.invoice.paymentIntentId().equals(unpaid.paymentIntentId())
        && captured.invoice.digest().equals(unpaid.tupleDigest())
        && !unpaid.readStartedAt().isBefore(observed.observedAt())
        && !unpaid.observedAt().isBefore(unpaid.readStartedAt())
        && !evaluatedAt.isBefore(unpaid.observedAt().truncatedTo(ChronoUnit.MICROS))
        && evaluatedAt.isBefore(
            unpaid
                .readStartedAt()
                .truncatedTo(ChronoUnit.MICROS)
                .plus(policy.maximumObservationAge()));
  }

  private boolean retainedIncompleteExpiredCancellation(
      AccountSubscriptionCurrentRecord current,
      AccountSubscriptionCreationRecord original,
      Record receipt,
      Record operation,
      Record observation,
      ObservedSubscription observed) {
    if (!Status.CANCELED.storageValue().equals(current.status())
        || !observed.providerStatusIsIncompleteExpired()
        || !"incomplete_expired".equals(observation.get("raw_status", String.class))
        || !"COMMITTED".equals(operation.get("state", String.class))
        || !operation.get("request_id", UUID.class).equals(receipt.get("request_id", UUID.class))
        || !Status.CANCELED.storageValue().equals(receipt.get("status", String.class))
        || operation.get("provisioning_attempt_id", UUID.class) != null
        || !original.requestId().equals(operation.get("creation_request_id", UUID.class))
        || !current
            .providerSubscriptionId()
            .equals(operation.get("provider_subscription_id", String.class))
        || !hasUnpaidFirstInvoiceCancellationSchema(operation)
        || current.terminalResolutionId() == null) {
      return false;
    }
    Record terminal =
        dsl.fetchOne(
            "SELECT request_id, subscription_id, provider_subscription_id, outcome, evidence_digest "
                + "FROM subscription_provider_terminal_resolutions WHERE resolution_id = ?",
            current.terminalResolutionId());
    return terminal != null
        && original.requestId().equals(terminal.get("request_id", UUID.class))
        && current.subscriptionId().equals(terminal.get("subscription_id", UUID.class))
        && current
            .providerSubscriptionId()
            .equals(terminal.get("provider_subscription_id", String.class))
        && "CANCELED".equals(terminal.get("outcome", String.class))
        && receipt
            .get("observation_digest", String.class)
            .equals(terminal.get("evidence_digest", String.class));
  }

  private boolean hasUnpaidFirstInvoiceCancellationSchema(Record operation) {
    Record schema =
        dsl.fetchOne(
            "SELECT convert_from(?::bytea, 'UTF8')::jsonb ->> 'schemaVersion' AS schema_version",
            operation.get("request_payload", byte[].class));
    return schema != null
        && UNPAID_FIRST_INVOICE_CANCELLATION_SCHEMA.equals(
            schema.get("schema_version", String.class));
  }

  private UUID cancellationClaim(UUID subscriptionId) {
    Record claim =
        dsl.fetchOne(
            "SELECT request_id FROM subscription_first_invoice_command_claims WHERE subscription_id = ?",
            subscriptionId);
    if (claim == null) return null;
    UUID requestId = claim.get("request_id", UUID.class);
    if (dsl.fetchOne(
            "SELECT request_id FROM subscription_first_invoice_expired_before_post WHERE request_id = ?",
            requestId)
        == null) {
      throw new FenceRejectedException(
          "Exact unclaimed unpaid first invoice or committed no-send evidence is required for cancellation");
    }
    return requestId;
  }

  private void lockReconciliationWallet(AccountSubscriptionCreationRecord original) {
    new AccountPaymentInstrumentRepository(dsl)
        .findByOwnerForUpdate(original.owner().accountUuid(), original.paymentInstrumentId())
        .orElseThrow(() -> new FenceRejectedException("Original provisioning wallet is absent"));
  }

  private Record firstPaymentProof(UUID subscriptionId, UUID lifecycleRequestId) {
    // Historical authorization was atomically consumed before the single provider dispatch.
    // Reading it after expiry cannot renew payment permission or disclose a client secret.
    return dsl.fetchOne(
        "SELECT c.*, i.invoice_id, i.payment_intent_id, e.tuple_digest AS evidence_tuple FROM subscription_first_invoice_confirmations c "
            + "JOIN subscription_first_invoice_success_evidence e USING (request_id, subscription_id) "
            + "JOIN subscription_first_invoice_dispatches d USING (request_id, subscription_id) "
            + "JOIN subscription_first_invoices i ON i.subscription_id = c.subscription_id AND i.tuple_digest = c.tuple_digest "
            + "WHERE c.subscription_id = ? AND c.lifecycle_request_id = ? AND c.tuple_digest = e.tuple_digest FOR SHARE OF c",
        subscriptionId,
        lifecycleRequestId);
  }

  private Map<String, Object> receiptFields(
      Record operation,
      AccountSubscriptionCreationRecord original,
      long rowVersion,
      long entitlementVersion,
      ObservedSubscription observed,
      String observationDigest,
      AccountHostingSubscriptionLifecyclePolicy.Decision decision,
      Instant origin,
      long generation,
      long sourceVersion) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schemaVersion", "account-hosting-current-receipt/v1");
    fields.put("requestId", operation.get("request_id", UUID.class).toString());
    fields.put("requestDigest", operation.get("request_digest", String.class));
    fields.put("subscriptionId", original.subscriptionId().toString());
    fields.put("tenantId", original.tenantId().toString());
    fields.put("creationRequestId", original.requestId().toString());
    fields.put("billingOwnerAccountId", original.owner().accountUuid().toString());
    fields.put("billingOwnerVersion", Long.toString(original.owner().version()));
    fields.put("billingOwnerGeneration", original.owner().generation().toString());
    fields.put("paymentInstrumentId", original.paymentInstrumentId().toString());
    fields.put("reservedInstrumentVersion", Long.toString(original.reservedInstrumentVersion()));
    fields.put(
        "customerProvisioningRequestId", original.customerProvisioningRequestId().toString());
    fields.put("providerCustomerId", original.providerCustomerId());
    fields.put("subscriptionRowVersion", Long.toString(rowVersion));
    fields.put("entitlementVersion", Long.toString(entitlementVersion));
    fields.put("subscriptionStatus", decision.status().storageValue());
    fields.put("planQuoteDigest", original.planQuote().digest());
    fields.put(
        "planQuote",
        new String(original.planQuote().payload(), java.nio.charset.StandardCharsets.UTF_8));
    fields.put("observationDigest", observationDigest);
    fields.put("providerSubscriptionId", observed.providerSubscriptionId());
    fields.put("providerFacts", AccountHostingSubscriptionLifecycleEvidence.facts(observed));
    fields.put(
        "hardCutoff",
        decision.status() == Status.SUSPENDED || decision.status() == Status.CANCELED);
    fields.put("validUntil", decision.receiptValidUntil().toString());
    fields.put("cutoffAt", decision.cutoffAt() == null ? null : decision.cutoffAt().toString());
    fields.put("pastDueSince", origin == null ? null : origin.toString());
    fields.put("authorityGeneration", Long.toString(generation));
    fields.put("authoritySourceVersion", Long.toString(sourceVersion));
    return fields;
  }

  private void terminalLifecycle(Record operation, String reason, Instant now) {
    dsl.execute(
        "UPDATE subscription_lifecycle_operations SET state = 'BILLING_RECONCILIATION_TERMINAL_FAILURE', "
            + "failure_reason = ?, row_version = row_version + 1 WHERE request_id = ?",
        reason,
        operation.get("request_id", UUID.class));
    dsl.execute(
        "INSERT INTO subscription_lifecycle_operator_outbox (request_id, reason, recorded_at) VALUES (?, ?, ?::timestamptz)",
        operation.get("request_id", UUID.class),
        reason,
        utc(now));
  }

  private Preparation preparationOutcome(Record operation) {
    boolean committed = "COMMITTED".equals(operation.get("state", String.class));
    return new Preparation(
        operation.get("request_id", UUID.class),
        null,
        null,
        committed ? Outcome.COMMITTED : Outcome.TERMINAL_FAILURE,
        operation.get("failure_reason", String.class),
        committed ? operation.get("reserved_row_version", Long.class) + 1 : 0);
  }

  private Record links(UUID subscriptionId) {
    return Objects.requireNonNull(
        dsl.fetchOne(
            "SELECT lifecycle_inflight_request_id, current_receipt_request_id "
                + "FROM subscription WHERE subscription_id = ?",
            subscriptionId));
  }

  private Record receiptForCurrent(Record links) {
    UUID id = links.get("current_receipt_request_id", UUID.class);
    return id == null
        ? null
        : dsl.fetchOne("SELECT * FROM subscription_lifecycle_receipts WHERE request_id = ?", id);
  }

  private Record operationForUpdateRead(UUID id) {
    return Objects.requireNonNull(
        dsl.fetchOne("SELECT * FROM subscription_lifecycle_operations WHERE request_id = ?", id));
  }

  private Record operationForUpdate(UUID id) {
    return Objects.requireNonNull(
        dsl.fetchOne(
            "SELECT * FROM subscription_lifecycle_operations WHERE request_id = ? FOR UPDATE", id));
  }

  private ExpectedBinding expected(AccountSubscriptionCreationRecord original, String providerId) {
    var wallet =
        new AccountPaymentInstrumentRepository(dsl)
            .findByOwner(original.owner().accountUuid(), original.paymentInstrumentId())
            .orElseThrow();
    return new ExpectedBinding(
        providerId,
        original.subscriptionId(),
        original.providerCustomerId(),
        wallet.providerPaymentMethodId(),
        original.planQuote().providerPriceId(),
        original.planQuote().providerQuantity(),
        StripeHostingSubscriptionObserver.OperationType.SUBSCRIPTION_PROVISIONING,
        original.subscriptionProvisioningRequestId(),
        livemode);
  }

  private static void requireObservedBinding(
      ExpectedBinding binding, ObservedSubscription observed) {
    if (!binding.providerSubscriptionId().equals(observed.providerSubscriptionId())
        || !binding.providerCustomerId().equals(observed.providerCustomerId())
        || !binding.providerPaymentMethodId().equals(observed.providerPaymentMethodId())
        || !binding.providerPriceId().equals(observed.providerPriceId())
        || binding.expectedQuantity() != observed.providerQuantity()
        || binding.livemode() != observed.livemode()
        || binding.operationType() != observed.correlation().operationType()
        || !binding.operationId().equals(observed.correlation().operationId())
        || !binding.subscriptionId().equals(observed.correlation().subscriptionId())) {
      throw new FenceRejectedException(
          "Actual SDK evidence differs from the exact retained binding");
    }
  }

  private AccountHostingSubscriptionLifecyclePolicy retainedPolicy(Record operation) {
    return new AccountHostingSubscriptionLifecyclePolicy(
        Duration.ofMillis(operation.get("observation_age_millis", Long.class)),
        new RecoveryTiming(
            Duration.ofMillis(operation.get("warning_millis", Long.class)),
            Duration.ofMillis(operation.get("grace_millis", Long.class))));
  }

  /** Provisioning leases and dispatch fences share PostgreSQL time, not the observation clock. */
  private Instant databaseNow() {
    return instant(Objects.requireNonNull(dsl.fetchOne("SELECT clock_timestamp() AS now")), "now");
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /** Captures the locked current fence; it contains no gameplay permission and is not a receipt. */
  public ReadFence prepareCurrentRead(UUID tenantId) {
    requireTransaction();
    var candidate = intents.findCurrentByTenant(tenantId).orElseThrow();
    var current = intents.lockForLifecycle(candidate.creationRequestId(), candidate.rowVersion());
    Record currentLinks = links(current.subscriptionId());
    if (java.util.Set.of("trialing", "active", "past_due", "grace").contains(current.status())
        && dsl.fetchOne(
                "SELECT subscription_id FROM subscription_first_invoice_completions WHERE subscription_id = ?",
                current.subscriptionId())
            == null) {
      throw new FenceRejectedException(
          "ENTITLEMENT_UNAVAILABLE: signed first-invoice fulfillment is absent");
    }
    if (currentLinks.get("lifecycle_inflight_request_id") != null
        || current.providerSubscriptionId() == null) {
      throw new FenceRejectedException(
          "ENTITLEMENT_UNAVAILABLE: lifecycle is unresolved or in flight");
    }
    Record receipt =
        Objects.requireNonNull(
            receiptForCurrent(currentLinks), "Committed current receipt is absent");
    // CANCELED is a terminal negative projection, never a time-bounded positive entitlement.
    // Its retained observation deadline does not prevent a fresh SDK confirmation below; the
    // original receipt/source bytes remain mandatory and the result still has its own deadline.
    boolean terminalCanceled = Status.CANCELED.storageValue().equals(current.status());
    if (current.supersededAt() != null
        || current.rowVersion() != receipt.get("subscription_row_version", Long.class)
        || !current.status().equals(receipt.get("status", String.class))
        || !current
            .providerSubscriptionId()
            .equals(receipt.get("provider_subscription_id", String.class))
        || now().isBefore(instant(receipt, "committed_at"))
        || (!terminalCanceled && !now().isBefore(instant(receipt, "valid_until")))) {
      throw new FenceRejectedException(
          "ENTITLEMENT_UNAVAILABLE: current receipt is expired or inconsistent");
    }
    tenantEvents.readHostingCheckpoint(tenantId, receipt.get("request_id", UUID.class));
    var original = intents.findCreation(current.creationRequestId()).orElseThrow();
    return new ReadFence(
        tenantId,
        current.creationRequestId(),
        current.rowVersion(),
        receipt.get("request_id", UUID.class),
        expected(original, current.providerSubscriptionId()));
  }

  /**
   * A positive internal result requires a fresh REAL SDK read AND the same committed receipt/source
   * under reacquired original-binding locks. Raw SQL-shaped status/quote rows alone never
   * authorize. The SDK call is outside SQL; a changed/in-flight row, period or provider fact
   * rejects the fence.
   */
  public AccountHostingSubscriptionCurrentReceipt readCurrent(
      ReadFence fence, StripeHostingSubscriptionObserver.Result result) {
    requireTransaction();
    Objects.requireNonNull(fence);
    if (!(result instanceof ObservedSubscription observed)) {
      throw new FenceRejectedException("ENTITLEMENT_UNAVAILABLE: provider authority is unresolved");
    }
    var current = intents.lockForLifecycle(fence.creationId, fence.version);
    ReadFence rechecked = prepareCurrentRead(fence.tenantId);
    if (!rechecked.receiptId.equals(fence.receiptId) || rechecked.version != fence.version) {
      throw new FenceRejectedException("ENTITLEMENT_UNAVAILABLE: current read fence changed");
    }
    var original = intents.findCreation(fence.creationId).orElseThrow();
    requireObservedBinding(expected(original, current.providerSubscriptionId()), observed);
    Record receipt = receiptForCurrent(links(current.subscriptionId()));
    Objects.requireNonNull(receipt);
    Record operation = operationForUpdateRead(fence.receiptId);
    Record observation =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT * FROM subscription_lifecycle_observations WHERE attempt_id = ?",
                receipt.get("attempt_id", UUID.class)));
    byte[] storedObservation = observation.get("payload", byte[].class);
    if (!AccountHostingSubscriptionLifecycleEvidence.digest(storedObservation)
            .equals(receipt.get("observation_digest", String.class))
        || !Arrays.equals(
            storedObservation,
            AccountHostingSubscriptionLifecycleEvidence.observation(
                AccountHostingSubscriptionLifecycleEvidence.facts(observed),
                instant(observation, "read_started_at"),
                instant(observation, "observed_at")))
        || !AccountHostingSubscriptionLifecycleEvidence.digest(
                AccountHostingSubscriptionLifecycleEvidence.encode(
                    AccountHostingSubscriptionLifecycleEvidence.facts(observed)))
            .equals(observation.get("facts_digest", String.class))) {
      throw new FenceRejectedException(
          "ENTITLEMENT_UNAVAILABLE: provider facts differ from the committed receipt");
    }
    Instant evaluatedAt = now();
    var lifecyclePolicy = retainedPolicy(operation);
    boolean retainedIncompleteExpiredCancellation =
        retainedIncompleteExpiredCancellation(
            current, original, receipt, operation, observation, observed);
    var decision =
        retainedIncompleteExpiredCancellation
            ? lifecyclePolicy.evaluateIncompleteExpiredFirstInvoiceCancellation(
                observed, evaluatedAt)
            : lifecyclePolicy.evaluate(
                observed, evaluatedAt, nullableInstant(receipt, "past_due_since"));
    if (!decision.resolved() || !decision.status().storageValue().equals(current.status())) {
      throw new FenceRejectedException(
          "ENTITLEMENT_UNAVAILABLE: lifecycle/period requires reconciliation");
    }
    var committedDecision =
        new AccountHostingSubscriptionLifecyclePolicy.Decision(
            decision.status(),
            decision.reason(),
            instant(receipt, "valid_until"),
            instant(receipt, "valid_until"),
            nullableInstant(receipt, "cutoff_at"));
    Map<String, Object> fields =
        receiptFields(
            operation,
            original,
            current.rowVersion(),
            receipt.get("entitlement_version", Long.class),
            observed,
            receipt.get("observation_digest", String.class),
            committedDecision,
            nullableInstant(receipt, "past_due_since"),
            receipt.get("authority_generation", Long.class),
            receipt.get("authority_source_version", Long.class));
    byte[] expectedPayload = AccountHostingSubscriptionLifecycleEvidence.encode(fields);
    if (!Arrays.equals(expectedPayload, receipt.get("receipt_payload", byte[].class))
        || !AccountHostingSubscriptionLifecycleEvidence.digest(expectedPayload)
            .equals(receipt.get("receipt_digest", String.class))) {
      throw new FenceRejectedException("ENTITLEMENT_UNAVAILABLE: receipt fields/bytes disagree");
    }
    var expectedEvent =
        AccountHostingSubscriptionLifecycleEvidence.billing(
            fence.tenantId,
            fence.receiptId,
            receipt.get("request_digest", String.class),
            receipt.get("billing_sequence", Long.class),
            receipt.get("billing_event_id", UUID.class),
            fields);
    Record storedEvent =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT e.*, s.last_sequence FROM account_tenant_entitlement_outbox_events e "
                    + "JOIN account_tenant_entitlement_outbox_streams s USING (tenant_uuid) WHERE e.tenant_uuid = ? AND e.tenant_billing_sequence = ? FOR SHARE OF s",
                fence.tenantId,
                expectedEvent.sequence));
    if (storedEvent.get("last_sequence", Long.class) != expectedEvent.sequence
        || !expectedEvent.matches(
            storedEvent.get("payload", byte[].class), storedEvent.get("event_digest", String.class))
        || !expectedEvent.digest().equals(receipt.get("billing_event_digest", String.class))) {
      throw new FenceRejectedException(
          "ENTITLEMENT_UNAVAILABLE: billing checkpoint does not cover the current receipt");
    }
    var authority = tenantEvents.readHostingCheckpoint(fence.tenantId, fence.receiptId);
    boolean available =
        decision.status() != Status.SUSPENDED && decision.status() != Status.CANCELED;
    boolean admit = available && decision.status() != Status.GRACE;
    return new AccountHostingSubscriptionCurrentReceipt(
        current,
        fence.receiptId,
        decision.status(),
        available,
        admit,
        admit,
        admit,
        receipt.get("entitlement_version", Long.class),
        expectedEvent.sequence,
        expectedEvent.eventId,
        expectedEvent.digest(),
        authority,
        observed.providerStatus(),
        observed.currentPeriodStart().orElse(null),
        observed.currentPeriodEnd().orElse(null),
        observed.cancelAtPeriodEnd(),
        observed.cancelAt().orElse(null),
        nullableInstant(receipt, "cutoff_at"),
        evaluatedAt,
        decision.status() == Status.CANCELED
            ? decision.validUntil()
            : earlier(instant(receipt, "valid_until"), decision.validUntil()));
  }

  public static final class ReadFence {
    private final UUID tenantId;
    private final UUID creationId;
    private final long version;
    private final UUID receiptId;
    private final ExpectedBinding binding;

    private ReadFence(
        UUID tenantId, UUID creationId, long version, UUID receiptId, ExpectedBinding binding) {
      this.tenantId = tenantId;
      this.creationId = creationId;
      this.version = version;
      this.receiptId = receiptId;
      this.binding = binding;
    }

    public ExpectedBinding binding() {
      return binding;
    }
  }

  static Instant retainedPastDueOrigin(Record receipt) {
    return receipt == null ? null : nullableInstant(receipt, "past_due_since");
  }

  private static Instant nullableInstant(Record row, String field) {
    OffsetDateTime value = row.get(field, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime utcNullable(Instant instant) {
    return instant == null ? null : utc(instant);
  }

  public enum Outcome {
    READY,
    COMMITTED,
    RECONCILING,
    TERMINAL_FAILURE
  }

  public record TransitionResult(
      UUID requestId, Outcome outcome, long committedRowVersion, String reason) {}

  public static final class Preparation {
    private final UUID requestId;
    private final UUID attemptId;
    private final ExpectedBinding binding;
    private final Outcome outcome;
    private final String reason;
    private final long version;

    private Preparation(
        UUID requestId,
        UUID attemptId,
        ExpectedBinding binding,
        Outcome outcome,
        String reason,
        long version) {
      this.requestId = requestId;
      this.attemptId = attemptId;
      this.binding = binding;
      this.outcome = outcome;
      this.reason = reason;
      this.version = version;
    }

    public UUID requestId() {
      return requestId;
    }

    public UUID attemptId() {
      return attemptId;
    }

    public ExpectedBinding binding() {
      return binding;
    }

    public Outcome outcome() {
      return outcome;
    }

    public TransitionResult result() {
      return new TransitionResult(requestId, outcome, version, reason);
    }
  }

  public static final class UnpaidCancellationCapture {
    private final UUID requestId;
    private final UUID creationId;
    private final long expectedVersion;
    private final ExpectedBinding binding;
    private final Instant capturedAt;
    private final AccountHostingFirstInvoicePayment invoice;
    private final UUID confirmationId;
    private final TransitionResult replay;

    private UnpaidCancellationCapture(
        UUID requestId,
        UUID creationId,
        long expectedVersion,
        ExpectedBinding binding,
        Instant capturedAt,
        AccountHostingFirstInvoicePayment invoice,
        UUID confirmationId,
        TransitionResult replay) {
      this.requestId = requestId;
      this.creationId = creationId;
      this.expectedVersion = expectedVersion;
      this.binding = binding;
      this.capturedAt = capturedAt;
      this.invoice = invoice;
      this.confirmationId = confirmationId;
      this.replay = replay;
    }

    public ExpectedBinding binding() {
      return binding;
    }

    public TransitionResult replayResult() {
      return replay;
    }

    public AccountHostingFirstInvoicePayment invoiceRequiringTerminalRead(
        boolean incompleteExpired) {
      return confirmationId == null && !incompleteExpired ? null : invoice;
    }
  }

  /**
   * Claims only the original durable create work. Recovery never allocates a new provider identity.
   * A recovered attempt requires provider reconciliation before any retry of the original create;
   * even the first claim is not remote instrument/customer validation or permission to charge.
   */
  public ClaimResult claim(UUID requestId, long expectedRowVersion, UUID attemptId) {
    requireTransaction();
    AccountBillingCustomerRecord.requireUuid(requestId);
    AccountBillingCustomerRecord.requireUuid(attemptId);
    if (expectedRowVersion < 1 || expectedRowVersion == Long.MAX_VALUE) {
      throw new IllegalArgumentException("Positive advanceable subscription version is required");
    }
    Record prior =
        dsl.fetchOne(
            "SELECT * FROM subscription_provisioning_attempts WHERE attempt_id = ?", attemptId);
    if (prior != null
        && (!requestId.equals(prior.get("request_id", UUID.class))
            || expectedRowVersion != prior.get("expected_row_version", Long.class))) {
      throw new FenceRejectedException(
          "Provisioning attempt identity was reused with different input");
    }
    long versionToLock =
        prior == null ? expectedRowVersion : prior.get("claimed_row_version", Long.class);
    AccountSubscriptionCurrentRecord current;
    try {
      current = intents.lockForProvisioningRecovery(requestId, versionToLock);
    } catch (FenceRejectedException refused) {
      // A later wallet observation may prevent even the first dispatch. The original intent
      // can use the local settlement fence, but this path never returns a provider-capable claim.
      return recoverUndispatchableWork(requestId, versionToLock, prior);
    }
    var original = intents.findCreation(requestId).orElseThrow();
    retainUnclaimedWork(original);
    Record work = workForUpdate(requestId);
    Instant now = databaseNow();
    if (now.isBefore(original.createdAt())) {
      throw new FenceRejectedException("Provisioning clock predates the durable intent");
    }
    if ("BILLING_RECONCILIATION_TERMINAL_FAILURE".equals(work.get("phase", String.class))) {
      return new ClaimResult(Disposition.TERMINAL_FAILURE, null);
    }
    if (prior != null) {
      if (!attemptId.equals(work.get("last_attempt_id", UUID.class))
          || !"CLAIMED".equals(work.get("phase", String.class))
          || now.isBefore(instant(prior, "claimed_at"))
          || !now.isBefore(instant(prior, "expires_at"))
          || !now.isBefore(instant(work, "deadline_at"))) {
        return new ClaimResult(Disposition.ATTEMPT_EXPIRED, null);
      }
      return new ClaimResult(Disposition.CLAIMED, claim(original, prior));
    }
    if (!now.isBefore(instant(work, "deadline_at"))) {
      return fail(work, "ABSOLUTE_DEADLINE", now);
    }
    if ("CLAIMED".equals(work.get("phase", String.class))
        && now.isBefore(instant(work, "lease_expires_at"))) {
      return new ClaimResult(Disposition.WAITING, null);
    }
    if (work.get("attempt_count", Integer.class) >= work.get("max_attempts", Integer.class)) {
      return fail(work, "ATTEMPT_BUDGET", now);
    }
    // No completed provider/current commit has established progress in this pre-confirmation owner.
    // Timeouts and lost responses do not reset a stalled counter or authorize another resource.
    if (work.get("attempt_count", Integer.class)
        >= work.get("max_stalled_attempts", Integer.class)) {
      return fail(work, "STALLED_WITHOUT_COMMITTED_PROGRESS", now);
    }
    if (now.isBefore(instant(work, "retry_at"))) {
      return new ClaimResult(Disposition.WAITING, null);
    }
    if (current.providerSubscriptionId() != null) {
      throw new FenceRejectedException(
          "Mapped provider work requires lifecycle reconciliation, not a create claim");
    }
    Instant expiresAt =
        earlier(now.plusMillis(work.get("lease_millis", Long.class)), instant(work, "deadline_at"));
    int number = Math.addExact(work.get("attempt_count", Integer.class), 1);
    long claimedVersion = Math.addExact(current.rowVersion(), 1L);
    int updated =
        dsl.execute(
            "UPDATE subscription SET status = 'provisioning', row_version = row_version + 1 "
                + "WHERE subscription_id = ? AND row_version = ? AND superseded_at IS NULL "
                + "AND status IN ('pending','provisioning') AND provider_subscription_id IS NULL",
            original.subscriptionId(),
            current.rowVersion());
    if (updated != 1) {
      throw new FenceRejectedException("Provisioning expected-current CAS rejected");
    }
    dsl.execute(
        "INSERT INTO subscription_provisioning_attempts (attempt_id, request_id, subscription_id, "
            + "attempt_number, expected_row_version, claimed_row_version, claimed_at, expires_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
        attemptId,
        requestId,
        original.subscriptionId(),
        number,
        current.rowVersion(),
        claimedVersion,
        utc(now),
        utc(expiresAt));
    int claimed =
        dsl.execute(
            "UPDATE subscription_provisioning_work SET phase = 'CLAIMED', row_version = row_version + 1, "
                + "attempt_count = ?, last_attempt_id = ?, subscription_row_version = ?, lease_expires_at = ?::timestamptz, "
                + "retry_at = ?::timestamptz, failure_reason = NULL WHERE request_id = ? AND row_version = ?",
            number,
            attemptId,
            claimedVersion,
            utc(expiresAt),
            utc(expiresAt.plusMillis(work.get("backoff_millis", Long.class))),
            requestId,
            work.get("row_version", Long.class));
    if (claimed != 1) {
      throw new FenceRejectedException("Provisioning work claim CAS rejected");
    }
    Record attempt =
        dsl.fetchOne(
            "SELECT * FROM subscription_provisioning_attempts WHERE attempt_id = ?", attemptId);
    return new ClaimResult(Disposition.CLAIMED, claim(original, Objects.requireNonNull(attempt)));
  }

  private void retainUnclaimedWork(AccountSubscriptionCreationRecord original) {
    dsl.execute(
        "INSERT INTO subscription_provisioning_work (request_id, subscription_id, phase, row_version, "
            + "attempt_count, max_attempts, max_stalled_attempts, lease_millis, backoff_millis, deadline_at, retry_at) "
            + "VALUES (?, ?, 'RECONCILING', 1, 0, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz) ON CONFLICT (request_id) DO NOTHING",
        original.requestId(),
        original.subscriptionId(),
        limits.maximumAttempts(),
        limits.maximumStalledAttempts(),
        limits.lease().toMillis(),
        limits.backoff().toMillis(),
        utc(original.createdAt().plus(limits.absoluteTimeout())),
        utc(original.createdAt()));
  }

  private ClaimResult recoverUndispatchableWork(UUID requestId, long version, Record prior) {
    var current = intents.lockForProvisioningSettlement(requestId, version);
    var original = intents.findCreation(requestId).orElseThrow();
    retainUnclaimedWork(original);
    Record work = workForUpdate(requestId);
    Instant now = databaseNow();
    if (now.isBefore(original.createdAt())) {
      throw new FenceRejectedException("Provisioning clock predates the durable intent");
    }
    if (work.get("last_attempt_id") == null) {
      if (prior != null
          || work.get("attempt_count", Integer.class) != 0
          || !current.subscriptionId().equals(work.get("subscription_id", UUID.class))
          || !"pending".equals(current.status())
          || current.rowVersion() != 1L
          || current.providerSubscriptionId() != null
          || links(current.subscriptionId()).get("lifecycle_inflight_request_id") != null) {
        throw new FenceRejectedException("Exact unclaimed provisioning settlement fence rejected");
      }
      if ("BILLING_RECONCILIATION_TERMINAL_FAILURE".equals(work.get("phase", String.class))) {
        return new ClaimResult(Disposition.TERMINAL_FAILURE, null);
      }
      if (!"RECONCILING".equals(work.get("phase", String.class))) {
        throw new FenceRejectedException("Unclaimed provisioning work is not reconciling");
      }
      // No claim is issued and no provider absence is inferred. Keep the reservation held and
      // retain one operator item even when the wallet changed before work was first claimed.
      return fail(work, "RESERVED_INSTRUMENT_CHANGED", now);
    }
    requireUnmappedProvisioningWork(current, work);
    if (prior != null
        && !prior.get("attempt_id", UUID.class).equals(work.get("last_attempt_id", UUID.class))) {
      return new ClaimResult(Disposition.ATTEMPT_EXPIRED, null);
    }
    Record retained =
        dsl.fetchOne(
            "SELECT * FROM subscription_provisioning_attempts WHERE attempt_id = ?",
            work.get("last_attempt_id", UUID.class));
    if (retained == null || now.isBefore(instant(retained, "claimed_at"))) {
      throw new FenceRejectedException("Exact retained provisioning attempt is absent or future");
    }
    if ("BILLING_RECONCILIATION_TERMINAL_FAILURE".equals(work.get("phase", String.class))) {
      return new ClaimResult(Disposition.TERMINAL_FAILURE, null);
    }
    if (!now.isBefore(instant(work, "deadline_at"))) {
      return fail(work, "ABSOLUTE_DEADLINE", now);
    }
    if ("CLAIMED".equals(work.get("phase", String.class))
        && now.isBefore(instant(work, "lease_expires_at"))) {
      return new ClaimResult(Disposition.WAITING, null);
    }
    if (work.get("attempt_count", Integer.class) >= work.get("max_attempts", Integer.class)) {
      return fail(work, "ATTEMPT_BUDGET", now);
    }
    if (work.get("attempt_count", Integer.class)
        >= work.get("max_stalled_attempts", Integer.class)) {
      return fail(work, "STALLED_WITHOUT_COMMITTED_PROGRESS", now);
    }
    return new ClaimResult(Disposition.WAITING, null);
  }

  private void requireUnmappedProvisioningWork(
      AccountSubscriptionCurrentRecord current, Record work) {
    if (!current.subscriptionId().equals(work.get("subscription_id", UUID.class))
        || !Objects.equals(current.rowVersion(), work.get("subscription_row_version", Long.class))
        || work.get("last_attempt_id") == null
        || current.providerSubscriptionId() != null
        || links(current.subscriptionId()).get("lifecycle_inflight_request_id") != null) {
      throw new FenceRejectedException("Exact unmapped retained provisioning work fence rejected");
    }
  }

  /**
   * Inserts a separate immutable provider-dispatch boundary after revalidating the exact live
   * private worker claim. Only a newly inserted first-dispatch receipt may carry initial-call
   * permission, even if an earlier worker attempt expired without dispatching. Preexisting unknown
   * migration guards and all replayed dispatches reconcile only.
   */
  public ProviderDispatch prepareProviderDispatch(ProvisioningClaim supplied, UUID dispatchId) {
    requireTransaction();
    AccountBillingCustomerRecord.requireUuid(dispatchId);
    ProvisioningClaim retained = requireLiveProvisioningClaim(supplied);
    var original = retained.creation();
    int inserted =
        dsl.execute(
            "INSERT INTO subscription_provider_dispatch_claim "
                + "(creation_request_id, subscription_id, provider_operation_id, dispatch_id, classification, "
                + "first_attempt_id, first_claimed_row_version, recorded_at) "
                + "VALUES (?, ?, ?, ?, 'FIRST_DISPATCH', ?, ?, clock_timestamp()) "
                + "ON CONFLICT (creation_request_id) DO NOTHING",
            original.requestId(),
            original.subscriptionId(),
            original.subscriptionProvisioningRequestId(),
            dispatchId,
            retained.attemptId(),
            retained.subscriptionRowVersion());
    DispatchReceipt receipt = dispatchReceipt(original.requestId());
    requireDispatchBinding(retained, receipt);
    boolean initial =
        inserted == 1
            && "FIRST_DISPATCH".equals(receipt.classification())
            && retained.attemptId().equals(receipt.firstAttemptId());
    return providerDispatch(retained, receipt, initial, false, transactionId());
  }

  /**
   * Independent committed readback. Carries only the inserting invocation's existing private
   * permission; the persisted row, a replayed claim or a new invocation cannot mint permission.
   */
  public ProviderDispatch readProviderDispatch(ProviderDispatch supplied) {
    requireTransaction();
    Objects.requireNonNull(supplied);
    if (transactionId() == supplied.preparationTransactionId) {
      throw new FenceRejectedException(
          "Independent provider dispatch readback requires a new transaction");
    }
    ProvisioningClaim retained = requireLiveProvisioningClaim(supplied.claim());
    DispatchReceipt receipt = dispatchReceipt(retained.creation().requestId());
    requireDispatchBinding(retained, receipt);
    if (!receipt.equals(supplied.receipt)) {
      throw new FenceRejectedException("Committed provider dispatch receipt changed");
    }
    return providerDispatch(
        retained, receipt, supplied.initialPermission, true, supplied.preparationTransactionId);
  }

  private ProvisioningClaim requireLiveProvisioningClaim(ProvisioningClaim supplied) {
    Objects.requireNonNull(supplied, "Genuine retained provisioning claim is required");
    ClaimResult replay =
        claim(
            supplied.creation().requestId(),
            supplied.subscriptionRowVersion() - 1,
            supplied.attemptId());
    ProvisioningClaim retained = replay.claim();
    if (replay.disposition() != Disposition.CLAIMED
        || retained == null
        || !retained.creation().equals(supplied.creation())
        || retained.attemptNumber() != supplied.attemptNumber()
        || retained.subscriptionRowVersion() != supplied.subscriptionRowVersion()
        || !retained.claimedAt().equals(supplied.claimedAt())
        || !retained.expiresAt().equals(supplied.expiresAt())) {
      throw new FenceRejectedException("Exact live provisioning claim readback rejected");
    }
    Record current = links(retained.creation().subscriptionId());
    if (current.get("lifecycle_inflight_request_id") != null) {
      throw new FenceRejectedException("Lifecycle reconciliation already owns provider work");
    }
    return retained;
  }

  private DispatchReceipt dispatchReceipt(UUID creationId) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM subscription_provider_dispatch_claim WHERE creation_request_id = ?",
            creationId);
    if (row == null) {
      throw new FenceRejectedException("Provider dispatch receipt is absent");
    }
    return new DispatchReceipt(
        row.get("creation_request_id", UUID.class),
        row.get("subscription_id", UUID.class),
        row.get("provider_operation_id", UUID.class),
        row.get("dispatch_id", UUID.class),
        row.get("classification", String.class),
        row.get("first_attempt_id", UUID.class),
        row.get("first_claimed_row_version", Long.class),
        instant(row, "recorded_at"));
  }

  private static void requireDispatchBinding(ProvisioningClaim claim, DispatchReceipt receipt) {
    var original = claim.creation();
    if (!original.requestId().equals(receipt.creationId())
        || !original.subscriptionId().equals(receipt.subscriptionId())
        || !original.subscriptionProvisioningRequestId().equals(receipt.providerOperationId())) {
      throw new FenceRejectedException("Provider dispatch changed original immutable operation");
    }
  }

  private ProviderDispatch providerDispatch(
      ProvisioningClaim claim,
      DispatchReceipt receipt,
      boolean initialPermission,
      boolean independentlyRead,
      long preparationTransactionId) {
    var original = claim.creation();
    var customer =
        new AccountBillingCustomerRepository(dsl)
            .findByAccountForUpdate(original.owner().accountUuid())
            .orElseThrow();
    var wallet =
        new AccountPaymentInstrumentRepository(dsl)
            .findByOwner(original.owner().accountUuid(), original.paymentInstrumentId())
            .orElseThrow();
    if (customer.state() != AccountBillingCustomerRecord.State.CONFIRMED
        || !original
            .customerProvisioningRequestId()
            .equals(customer.customerProvisioningRequestId())
        || !original.providerCustomerId().equals(customer.providerCustomerId())
        || !original.providerCustomerId().equals(wallet.providerCustomerId())
        || !original.customerProvisioningRequestId().equals(wallet.customerProvisioningRequestId())
        || wallet.rowVersion() != original.reservedInstrumentVersion()
        || wallet.state() != AccountPaymentInstrumentRecord.State.ATTACHABLE) {
      throw new FenceRejectedException(
          "Provider dispatch lost confirmed customer/instrument binding");
    }
    return new ProviderDispatch(
        claim,
        receipt,
        wallet.providerPaymentMethodId(),
        customer.providerIdempotencyId(),
        livemode,
        initialPermission,
        independentlyRead,
        preparationTransactionId);
  }

  private long transactionId() {
    return Objects.requireNonNull(dsl.fetchOne("SELECT txid_current() AS xid"))
        .get("xid", Long.class);
  }

  private record DispatchReceipt(
      UUID creationId,
      UUID subscriptionId,
      UUID providerOperationId,
      UUID dispatchId,
      String classification,
      UUID firstAttemptId,
      Long firstClaimedRowVersion,
      Instant recordedAt) {}

  /** Private durable producer result; prepared values are unusable until committed readback. */
  public static final class ProviderDispatch {
    private final ProvisioningClaim claim;
    private final DispatchReceipt receipt;
    private final String paymentMethodId;
    private final UUID customerProviderIdempotencyId;
    private final boolean livemode;
    private final boolean initialPermission;
    private final boolean independentlyRead;
    private final long preparationTransactionId;

    private ProviderDispatch(
        ProvisioningClaim claim,
        DispatchReceipt receipt,
        String paymentMethodId,
        UUID customerProviderIdempotencyId,
        boolean livemode,
        boolean initialPermission,
        boolean independentlyRead,
        long preparationTransactionId) {
      this.claim = claim;
      this.receipt = receipt;
      this.paymentMethodId = paymentMethodId;
      this.customerProviderIdempotencyId = customerProviderIdempotencyId;
      this.livemode = livemode;
      this.initialPermission = initialPermission;
      this.independentlyRead = independentlyRead;
      this.preparationTransactionId = preparationTransactionId;
    }

    public ProvisioningClaim claim() {
      return claim;
    }

    public String providerPaymentMethodId() {
      return paymentMethodId;
    }

    public UUID customerProviderIdempotencyId() {
      return customerProviderIdempotencyId;
    }

    public boolean readyForProviderIo() {
      return independentlyRead;
    }

    public boolean initialCallAllowed() {
      return independentlyRead && initialPermission;
    }

    public ExpectedBinding expectedBinding(String providerId) {
      var original = claim.creation();
      return new ExpectedBinding(
          providerId,
          original.subscriptionId(),
          original.providerCustomerId(),
          paymentMethodId,
          original.planQuote().providerPriceId(),
          original.planQuote().providerQuantity(),
          StripeHostingSubscriptionObserver.OperationType.SUBSCRIPTION_PROVISIONING,
          original.subscriptionProvisioningRequestId(),
          livemode);
    }
  }

  /**
   * Ends one worker attempt without asserting provider absence or cancellation. Failure keeps the
   * subscription provisioning and its reservation held; even terminal reconciliation failure is
   * unresolved billing work. The caller must commit this result rather than throw it away.
   *
   * <p>An exact retained attempt that expires before the absolute deadline returns {@link
   * Disposition#ATTEMPT_EXPIRED} without changing its work or reservation, leaving recovery to the
   * next claimant.
   */
  public Disposition deferUnresolved(ProvisioningClaim claim) {
    requireTransaction();
    Objects.requireNonNull(claim);
    var current =
        intents.lockForProvisioningSettlement(
            claim.creation().requestId(), claim.subscriptionRowVersion());
    Record work = workForUpdate(claim.creation().requestId());
    requireUnmappedProvisioningWork(current, work);
    Record retained =
        dsl.fetchOne(
            "SELECT * FROM subscription_provisioning_attempts WHERE attempt_id = ?",
            claim.attemptId());
    if (retained == null
        || !claim
            .creation()
            .equals(intents.findCreation(claim.creation().requestId()).orElseThrow())
        || !claim.creation().requestId().equals(retained.get("request_id", UUID.class))
        || !claim.creation().subscriptionId().equals(retained.get("subscription_id", UUID.class))
        || claim.attemptNumber() != retained.get("attempt_number", Integer.class)
        || claim.subscriptionRowVersion() != retained.get("claimed_row_version", Long.class)
        || !claim.claimedAt().equals(instant(retained, "claimed_at"))
        || !claim.expiresAt().equals(instant(retained, "expires_at"))) {
      throw new FenceRejectedException("Exact retained provisioning settlement claim rejected");
    }
    Instant now = databaseNow();
    if (now.isBefore(claim.claimedAt())) {
      throw new FenceRejectedException("Provisioning clock predates its retained claim");
    }
    if (!claim.attemptId().equals(work.get("last_attempt_id", UUID.class))) {
      throw new FenceRejectedException("A stale worker cannot settle a replacement attempt");
    }
    String phase = work.get("phase", String.class);
    if ("BILLING_RECONCILIATION_TERMINAL_FAILURE".equals(phase)) {
      return Disposition.TERMINAL_FAILURE;
    }
    if (!now.isBefore(instant(work, "deadline_at"))) {
      return fail(work, "ABSOLUTE_DEADLINE", now).disposition();
    }
    if ("RECONCILING".equals(phase)) {
      return Disposition.WAITING;
    }
    if (!now.isBefore(claim.expiresAt())) {
      // The exact retained attempt expired without a provider completion. Leave its work and
      // reservation untouched for the next claimant rather than treating normal expiry as a fault.
      return Disposition.ATTEMPT_EXPIRED;
    }
    if (work.get("attempt_count", Integer.class) >= work.get("max_attempts", Integer.class)) {
      return fail(work, "ATTEMPT_BUDGET", now).disposition();
    }
    if (work.get("attempt_count", Integer.class)
        >= work.get("max_stalled_attempts", Integer.class)) {
      return fail(work, "STALLED_WITHOUT_COMMITTED_PROGRESS", now).disposition();
    }
    int deferred =
        dsl.execute(
            "UPDATE subscription_provisioning_work SET phase = 'RECONCILING', row_version = row_version + 1, "
                + "retry_at = ?::timestamptz, failure_reason = 'UNRESOLVED_PROVIDER_WORK' WHERE request_id = ? AND row_version = ?",
            utc(
                earlier(
                    now.plusMillis(work.get("backoff_millis", Long.class)),
                    instant(work, "deadline_at"))),
            claim.creation().requestId(),
            work.get("row_version", Long.class));
    if (deferred != 1) {
      throw new FenceRejectedException("Provisioning deferred-attempt CAS rejected");
    }
    return Disposition.WAITING;
  }

  private ClaimResult fail(Record work, String reason, Instant now) {
    int failed =
        dsl.execute(
            "UPDATE subscription_provisioning_work SET phase = 'BILLING_RECONCILIATION_TERMINAL_FAILURE', "
                + "row_version = row_version + 1, failure_reason = ?, terminal_at = ?::timestamptz "
                + "WHERE request_id = ? AND row_version = ?",
            reason,
            utc(now),
            work.get("request_id", UUID.class),
            work.get("row_version", Long.class));
    if (failed != 1) {
      throw new FenceRejectedException("Provisioning failure CAS rejected");
    }
    dsl.execute(
        "INSERT INTO subscription_provisioning_operator_outbox (request_id, subscription_id, reason, recorded_at) "
            + "VALUES (?, ?, ?, ?::timestamptz)",
        work.get("request_id", UUID.class),
        work.get("subscription_id", UUID.class),
        reason,
        utc(now));
    return new ClaimResult(Disposition.TERMINAL_FAILURE, null);
  }

  private Record workForUpdate(UUID requestId) {
    Record work =
        dsl.fetchOne(
            "SELECT * FROM subscription_provisioning_work WHERE request_id = ? FOR UPDATE",
            requestId);
    if (work == null) {
      throw new FenceRejectedException("Original durable provisioning work is absent");
    }
    return work;
  }

  private static ProvisioningClaim claim(AccountSubscriptionCreationRecord original, Record row) {
    return new ProvisioningClaim(
        original,
        row.get("attempt_id", UUID.class),
        row.get("attempt_number", Integer.class),
        row.get("claimed_row_version", Long.class),
        instant(row, "claimed_at"),
        instant(row, "expires_at"));
  }

  private static Instant earlier(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(Record row, String field) {
    return Objects.requireNonNull(row.get(field, OffsetDateTime.class)).toInstant();
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Hosting provisioning requires a writable Account transaction");
    }
  }

  public enum Disposition {
    CLAIMED,
    WAITING,
    ATTEMPT_EXPIRED,
    TERMINAL_FAILURE
  }

  public record ClaimResult(Disposition disposition, ProvisioningClaim claim) {}

  /**
   * Private construction prevents mistaking caller-supplied status/version booleans for a claim. A
   * claim's attempt number never grants or denies provider creation. Only an independently read
   * ProviderDispatch carries initial-call permission from the retained first-dispatch receipt.
   */
  public static final class ProvisioningClaim {
    private final AccountSubscriptionCreationRecord creation;
    private final UUID attemptId;
    private final int attemptNumber;
    private final long subscriptionRowVersion;
    private final Instant claimedAt;
    private final Instant expiresAt;

    private ProvisioningClaim(
        AccountSubscriptionCreationRecord creation,
        UUID attemptId,
        int attemptNumber,
        long subscriptionRowVersion,
        Instant claimedAt,
        Instant expiresAt) {
      this.creation = creation;
      this.attemptId = attemptId;
      this.attemptNumber = attemptNumber;
      this.subscriptionRowVersion = subscriptionRowVersion;
      this.claimedAt = claimedAt;
      this.expiresAt = expiresAt;
    }

    public AccountSubscriptionCreationRecord creation() {
      return creation;
    }

    public UUID attemptId() {
      return attemptId;
    }

    public int attemptNumber() {
      return attemptNumber;
    }

    public long subscriptionRowVersion() {
      return subscriptionRowVersion;
    }

    public Instant claimedAt() {
      return claimedAt;
    }

    public Instant expiresAt() {
      return expiresAt;
    }
  }

  /** Frozen into durable work on its first claim; later configuration cannot widen its budget. */
  public record ReconciliationLimits(
      int maximumAttempts,
      int maximumStalledAttempts,
      Duration lease,
      Duration backoff,
      Duration absoluteTimeout) {
    public ReconciliationLimits {
      if (maximumAttempts < 1 || maximumStalledAttempts < 1) {
        throw new IllegalArgumentException(
            "Explicit positive attempt and stall budgets are required");
      }
      wholeMilliseconds(lease);
      wholeMilliseconds(backoff);
      wholeMilliseconds(absoluteTimeout);
      if (lease.compareTo(absoluteTimeout) > 0) {
        throw new IllegalArgumentException(
            "Worker lease cannot exceed the absolute reconciliation timeout");
      }
    }

    private static void wholeMilliseconds(Duration duration) {
      if (duration == null || duration.isNegative() || duration.isZero()) {
        throw new IllegalArgumentException(
            "Explicit positive reconciliation durations are required");
      }
      try {
        if (!Duration.ofMillis(duration.toMillis()).equals(duration)) {
          throw new IllegalArgumentException("Reconciliation durations must be whole milliseconds");
        }
        Instant.EPOCH.plus(duration);
      } catch (ArithmeticException | java.time.DateTimeException overflow) {
        throw new IllegalArgumentException(
            "Reconciliation duration exceeds the supported time domain", overflow);
      }
    }
  }
}
