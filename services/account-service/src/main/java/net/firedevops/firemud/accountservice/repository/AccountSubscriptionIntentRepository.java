package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionProviderHistoryRecord.TerminalResolution;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, local-only pending intent/current/history persistence. No provider business call,
 * lifecycle producer, grant, transfer or generic update API. Producer evidence is input, not auth.
 * The caller owns one short transaction and must roll it back after any rejected SQL statement.
 * Lock order: Account -> customer -> instrument -> tenant owner -> current subscription ->
 * operation and reservation. Root must compose future transfer/update paths in that same global
 * order.
 */
public final class AccountSubscriptionIntentRepository {
  private static final String CURRENT_SELECT =
      "SELECT s.*, o.plan_quote_payload, o.provider_price_id, o.provider_quantity, "
          + "o.max_active_sessions, o.max_concurrent_game_instances, o.max_storage_bytes "
          + "FROM subscription s JOIN subscription_create_operations o ON o.request_id = s.creation_request_id ";
  private final DSLContext dsl;
  private final AccountPaymentInstrumentRepository instruments;
  private final AccountHostingPlanCatalog plans;

  public AccountSubscriptionIntentRepository(DSLContext dsl, AccountHostingPlanCatalog plans) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.plans = Objects.requireNonNull(plans, "Explicit server hosting-plan catalog is required");
    instruments = new AccountPaymentInstrumentRepository(dsl);
  }

  /** Replays the original immutable pending result, even after the current row has advanced. */
  public AccountSubscriptionCreationRecord retainPending(AccountSubscriptionCreateRequest request) {
    requireWritableTransaction();
    Objects.requireNonNull(request, "subscription create request");
    var encoded =
        AccountSubscriptionCreateDigest.encode(
            request.tenantId(),
            request.owner().accountUuid(),
            request.planCode(),
            request.paymentInstrumentId());
    Optional<AccountSubscriptionCreationRecord> replay = replay(request.requestId(), encoded);
    if (replay.isPresent()) {
      return replay.orElseThrow();
    }
    var instrument =
        instruments
            .findByOwnerForUpdate(request.owner().accountUuid(), request.paymentInstrumentId())
            .orElseThrow(() -> new FenceRejectedException("Selected Account instrument is absent"));
    if (instrument.rowVersion() != request.instrumentVersion()
        || instrument.state() != AccountPaymentInstrumentRecord.State.ATTACHABLE) {
      throw new FenceRejectedException(
          "Exact saved instrument version/attachability fence rejected");
    }
    lockOwner(request.tenantId(), request.owner());
    // Account/tenant serialization may have waited for an exact same-request commit.
    replay = replay(request.requestId(), encoded);
    if (replay.isPresent()) {
      return replay.orElseThrow();
    }
    // A replay never consults today's catalog. Only a new operation freezes this server quote,
    // before any supersession/intent/reservation/outbox write in the local transaction.
    AccountHostingPlanQuote quote = plans.resolve(request.planCode());
    var current = currentForUpdate(request.tenantId());
    if (request.replacement() == null) {
      if (current.isPresent()) {
        throw new ConflictException("Tenant already has a current subscription");
      }
    } else {
      var predecessor =
          current.orElseThrow(
              () -> new FenceRejectedException("Canceled current subscription is absent"));
      if (!predecessor.subscriptionId().equals(request.replacement().subscriptionId())
          || predecessor.rowVersion() != request.replacement().expectedRowVersion()) {
        throw new FenceRejectedException("Canceled replacement expected-current CAS rejected");
      }
      if (!predecessor.status().equals("canceled") || predecessor.terminalResolutionId() == null) {
        throw new ConflictException("Current subscription is not terminal canceled");
      }
      // Deferred reciprocal FKs require the exact successor intent before this can commit.
      int changed =
          dsl.execute(
              "UPDATE subscription SET superseded_at = clock_timestamp(), "
                  + "superseded_by_request_id = ?, row_version = row_version + 1 "
                  + "WHERE subscription_id = ? AND tenant_id = ? AND status = 'canceled' AND row_version = ? AND superseded_at IS NULL",
              request.requestId(),
              predecessor.subscriptionId(),
              request.tenantId(),
              request.replacement().expectedRowVersion());
      if (changed != 1) {
        throw new FenceRejectedException("Canceled replacement CAS lost");
      }
    }
    UUID subscriptionId = UUID.randomUUID();
    UUID providerCreate = UUID.randomUUID();
    UUID providerCleanup = UUID.randomUUID();
    try {
      int inserted =
          dsl.execute(
              "INSERT INTO subscription_create_operations (request_id, subscription_id, tenant_id, "
                  + "account_uuid, owner_version, owner_generation, actor_account_uuid, authorization_operation_id, authorization_digest, "
                  + "plan_code, payment_instrument_id, reserved_instrument_version, customer_provisioning_request_id, provider_customer_id, "
                  + "subscription_provisioning_request_id, cleanup_provider_identity, request_payload, request_digest, replaced_subscription_id, replaced_row_version, "
                  + "plan_quote_payload, plan_quote_digest, provider_price_id, provider_quantity, max_active_sessions, max_concurrent_game_instances, max_storage_bytes) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING",
              request.requestId(),
              subscriptionId,
              request.tenantId(),
              request.owner().accountUuid(),
              request.owner().version(),
              request.owner().generation(),
              request.producer().actorAccountUuid(),
              request.producer().authorizationOperationId(),
              request.producer().authorizationDigest(),
              request.planCode(),
              request.paymentInstrumentId(),
              request.instrumentVersion(),
              instrument.customerProvisioningRequestId(),
              instrument.providerCustomerId(),
              providerCreate,
              providerCleanup,
              encoded.payload(),
              encoded.digest(),
              request.replacement() == null ? null : request.replacement().subscriptionId(),
              request.replacement() == null ? null : request.replacement().expectedRowVersion(),
              quote.payload(),
              quote.digest(),
              quote.providerPriceId(),
              quote.providerQuantity(),
              quote.maxActiveSessions(),
              quote.maxConcurrentGameInstances(),
              quote.maxStorageBytes());
      if (inserted != 1) {
        // A cross-owner request collision cannot be replayed after our supersession write. Reject
        // and roll back rather than retaining a half-applied replacement or guessing an outcome.
        throw new ConflictException(
            "Caller create request was concurrently retained; retry after rollback");
      }
      dsl.execute(
          "INSERT INTO subscription (subscription_id, tenant_id, account_uuid, owner_version, owner_generation, "
              + "plan_code, payment_instrument_id, reserved_instrument_version, customer_provisioning_request_id, provider_customer_id, "
              + "creation_request_id, row_version, status, plan_quote_digest) SELECT subscription_id, tenant_id, account_uuid, owner_version, owner_generation, "
              + "plan_code, payment_instrument_id, reserved_instrument_version, customer_provisioning_request_id, provider_customer_id, "
              + "request_id, 1, 'pending', plan_quote_digest FROM subscription_create_operations WHERE request_id = ?",
          request.requestId());
      dsl.execute(
          "INSERT INTO subscription_instrument_reservations (request_id, subscription_id, payment_instrument_id, instrument_version) "
              + "VALUES (?, ?, ?, ?)",
          request.requestId(),
          subscriptionId,
          request.paymentInstrumentId(),
          request.instrumentVersion());
      dsl.execute(
          "INSERT INTO subscription_create_outbox (request_id, subscription_id, work_kind) "
              + "VALUES (?, ?, 'SUBSCRIPTION_PROVISIONING_INTENT')",
          request.requestId(),
          subscriptionId);
    } catch (IntegrityConstraintViolationException conflict) {
      throw new ConflictException(
          "Subscription identity/current mapping conflicts with retained intent", conflict);
    }
    return replay(request.requestId(), encoded).orElseThrow();
  }

  /**
   * Read-only original receipt. A real caller must separately authorize disclosure of this record.
   */
  public Optional<AccountSubscriptionCreationRecord> findCreation(UUID requestId) {
    AccountBillingCustomerRecord.requireUuid(requestId);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM subscription_create_operations WHERE request_id = ?", requestId);
    return row == null ? Optional.empty() : Optional.of(creation(row));
  }

  public Optional<AccountSubscriptionCurrentRecord> findCurrentByTenant(UUID tenantId) {
    AccountBillingCustomerRecord.requireUuid(tenantId);
    Record row =
        dsl.fetchOne(
            CURRENT_SELECT + "WHERE s.tenant_id = ? AND s.superseded_at IS NULL", tenantId);
    return row == null ? Optional.empty() : Optional.of(current(row));
  }

  /**
   * Locked, version-fenced read for a future genuine worker's local claim transaction. This method
   * does NOT claim/dispatch work, move to provisioning, validate Stripe, or confer provider
   * authority. Root must add the actual durable claim/recovery/outbox state machine before any
   * remote work.
   */
  public AccountSubscriptionCurrentRecord lockPendingForClaim(
      UUID requestId, long expectedRowVersion) {
    return lockProvisioningBinding(requestId, expectedRowVersion, false);
  }

  /** Exact original-binding fence for the durable provisioning owner's recovery transaction. */
  public AccountSubscriptionCurrentRecord lockForProvisioningRecovery(
      UUID requestId, long expectedRowVersion) {
    return lockProvisioningBinding(requestId, expectedRowVersion, true);
  }

  /**
   * Original-binding lock for provider reconciliation and the consistent internal reader. Later
   * display/availability observations do not invalidate the immutable reserved instrument binding.
   * This read does not authorize a new provider dispatch.
   */
  public AccountSubscriptionCurrentRecord lockForLifecycle(
      UUID requestId, long expectedRowVersion) {
    return lockBinding(requestId, expectedRowVersion, true, true, InstrumentRequirement.RETAINED);
  }

  /**
   * First-invoice dispatch requires the current observation of the retained method to be usable.
   */
  AccountSubscriptionCurrentRecord lockForFirstInvoiceCommand(
      UUID requestId, long expectedRowVersion) {
    return lockBinding(
        requestId, expectedRowVersion, true, false, InstrumentRequirement.ATTACHABLE);
  }

  /**
   * Local settlement only for retained provisioning work. Later wallet observations cannot strand
   * its original reservation, but this lock never authorizes a claim or provider dispatch.
   */
  AccountSubscriptionCurrentRecord lockForProvisioningSettlement(
      UUID requestId, long expectedRowVersion) {
    return lockBinding(requestId, expectedRowVersion, true, false, InstrumentRequirement.RETAINED);
  }

  private AccountSubscriptionCurrentRecord lockProvisioningBinding(
      UUID requestId, long expectedRowVersion, boolean allowProvisioning) {
    return lockBinding(
        requestId, expectedRowVersion, allowProvisioning, false, InstrumentRequirement.ORIGINAL);
  }

  private enum InstrumentRequirement {
    ORIGINAL,
    ATTACHABLE,
    RETAINED
  }

  private AccountSubscriptionCurrentRecord lockBinding(
      UUID requestId,
      long expectedRowVersion,
      boolean allowProvisioning,
      boolean lifecycle,
      InstrumentRequirement instrumentRequirement) {
    requireWritableTransaction();
    AccountBillingCustomerRecord.requireUuid(requestId);
    if (expectedRowVersion < 1) {
      throw new IllegalArgumentException("Positive expected subscription version is required");
    }
    var original =
        findCreation(requestId)
            .orElseThrow(() -> new ConflictException("Subscription create is absent"));
    var instrument =
        instruments
            .findByOwnerForUpdate(original.owner().accountUuid(), original.paymentInstrumentId())
            .orElseThrow(() -> new FenceRejectedException("Reserved instrument owner is absent"));
    lockOwner(original.tenantId(), original.owner());
    var current =
        currentForUpdate(original.tenantId())
            .orElseThrow(() -> new FenceRejectedException("Current subscription is absent"));
    boolean instrumentVersionAndStateMatch =
        instrument.rowVersion() >= original.reservedInstrumentVersion()
            && (instrumentRequirement != InstrumentRequirement.ORIGINAL
                || instrument.rowVersion() == original.reservedInstrumentVersion())
            && (instrument.state() == AccountPaymentInstrumentRecord.State.ATTACHABLE
                || (instrumentRequirement == InstrumentRequirement.RETAINED
                    && instrument.state() == AccountPaymentInstrumentRecord.State.UNAVAILABLE));
    if (!current.subscriptionId().equals(original.subscriptionId())
        || !current.tenantId().equals(original.tenantId())
        || !current.owner().equals(original.owner())
        || !current.creationRequestId().equals(original.requestId())
        || !current.paymentInstrumentId().equals(original.paymentInstrumentId())
        || current.reservedInstrumentVersion() != original.reservedInstrumentVersion()
        || !current.customerProvisioningRequestId().equals(original.customerProvisioningRequestId())
        || !current.providerCustomerId().equals(original.providerCustomerId())
        || current.rowVersion() != expectedRowVersion
        || !(lifecycle
            || current.status().equals("pending")
            || (allowProvisioning && current.status().equals("provisioning")))
        || current.supersededAt() != null
        || !instrument.instrumentUuid().equals(original.paymentInstrumentId())
        || !instrument.accountUuid().equals(original.owner().accountUuid())
        || !instrumentVersionAndStateMatch
        || !instrument
            .customerProvisioningRequestId()
            .equals(original.customerProvisioningRequestId())
        || !instrument.providerCustomerId().equals(original.providerCustomerId())) {
      throw new FenceRejectedException(
          "Pending claim owner/customer/instrument/subscription fence rejected");
    }
    Record hold =
        dsl.fetchOne(
            "SELECT r.* FROM subscription_instrument_reservations r "
                + "JOIN subscription_create_outbox o USING (request_id, subscription_id) WHERE r.request_id = ? FOR UPDATE OF r",
            requestId);
    if (hold == null
        || (hold.get("released_at") != null
            && !(lifecycle
                && current.status().equals("canceled")
                && Objects.equals(
                    current.terminalResolutionId(),
                    hold.get("terminal_resolution_id", UUID.class))))
        || !original.subscriptionId().equals(hold.get("subscription_id", UUID.class))
        || !original.paymentInstrumentId().equals(hold.get("payment_instrument_id", UUID.class))
        || original.reservedInstrumentVersion() != hold.get("instrument_version", Long.class)) {
      throw new FenceRejectedException(
          "Exact durable operation/version reservation or work intent is absent");
    }
    return current;
  }

  /** Unknown/predecessor/conflicting IDs never repair, rebind, or choose billing authority. */
  public ProviderLookup lookupProviderId(String providerSubscriptionId) {
    if (providerSubscriptionId == null
        || providerSubscriptionId.length() > 255
        || !providerSubscriptionId.matches("sub_[A-Za-z0-9]+")) {
      throw new IllegalArgumentException("Exact Stripe subscription reference is required");
    }
    Record row =
        dsl.fetchOne(
            "SELECT h.*, s.provider_subscription_id AS current_provider_id, s.superseded_at, "
                + "s.status AS current_status, s.terminal_resolution_id FROM subscription_provider_history h "
                + "JOIN subscription s USING (subscription_id) WHERE h.provider_subscription_id = ?",
            providerSubscriptionId);
    if (row == null) {
      return new ProviderLookup(MappingDisposition.UNRESOLVED, Optional.empty(), Optional.empty());
    }
    var history =
        new AccountSubscriptionProviderHistoryRecord(
            row.get("provider_subscription_id", String.class),
            row.get("subscription_id", UUID.class),
            row.get("operation_kind", String.class),
            row.get("request_id", UUID.class),
            row.get("provider_operation_id", UUID.class),
            row.get("role", String.class),
            instant(row, "effective_at"),
            row.get("evidence_digest", String.class),
            instant(row, "recorded_at"));
    Record terminal =
        dsl.fetchOne(
            "SELECT * FROM subscription_provider_terminal_resolutions "
                + "WHERE subscription_id = ? AND provider_subscription_id = ?",
            history.subscriptionId(),
            providerSubscriptionId);
    if (terminal != null) {
      var evidence =
          new TerminalResolution(
              terminal.get("resolution_id", UUID.class),
              terminal.get("request_id", UUID.class),
              terminal.get("subscription_id", UUID.class),
              terminal.get("cleanup_provider_identity", UUID.class),
              terminal.get("provider_subscription_id", String.class),
              terminal.get("outcome", String.class),
              instant(terminal, "effective_at"),
              terminal.get("evidence_digest", String.class),
              instant(terminal, "recorded_at"));
      return new ProviderLookup(
          MappingDisposition.TERMINAL_HISTORY, Optional.of(history), Optional.of(evidence));
    }
    // A mapped resource with no current match stays evidence-only, regardless of timestamps.
    MappingDisposition disposition =
        row.get("superseded_at") == null
                && providerSubscriptionId.equals(row.get("current_provider_id", String.class))
                && !"canceled".equals(row.get("current_status", String.class))
            ? MappingDisposition.CURRENT_MAPPING
            : MappingDisposition.HISTORY_ONLY;
    return new ProviderLookup(disposition, Optional.of(history), Optional.empty());
  }

  private Optional<AccountSubscriptionCreationRecord> replay(
      UUID requestId, AccountSubscriptionCreateDigest.EncodedRequest encoded) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM subscription_create_operations WHERE request_id = ?", requestId);
    if (row == null) {
      return Optional.empty();
    }
    if (!encoded.digest().equals(row.get("request_digest", String.class))
        || !Arrays.equals(encoded.payload(), row.get("request_payload", byte[].class))) {
      throw new ConflictException(
          "IDEMPOTENCY_CONFLICT: caller create request has changed normalized fields");
    }
    return Optional.of(creation(row));
  }

  private void lockOwner(UUID tenantId, OwnerFence expected) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_tenant_billing_owners WHERE tenant_uuid = ? FOR UPDATE",
            tenantId);
    if (row == null
        || !expected.accountUuid().equals(row.get("owner_account_uuid", UUID.class))
        || expected.version() != row.get("owner_version", Long.class)
        || !expected.generation().equals(row.get("owner_generation", UUID.class))) {
      throw new FenceRejectedException(
          "Exact current tenant billing-owner version/generation fence rejected");
    }
  }

  private Optional<AccountSubscriptionCurrentRecord> currentForUpdate(UUID tenantId) {
    Record row =
        dsl.fetchOne(
            CURRENT_SELECT + "WHERE s.tenant_id = ? AND s.superseded_at IS NULL FOR UPDATE OF s",
            tenantId);
    return row == null ? Optional.empty() : Optional.of(current(row));
  }

  private static AccountSubscriptionCreationRecord creation(Record row) {
    return new AccountSubscriptionCreationRecord(
        row.get("request_id", UUID.class),
        row.get("request_digest", String.class),
        row.get("subscription_id", UUID.class),
        row.get("tenant_id", UUID.class),
        owner(row),
        row.get("plan_code", String.class),
        quote(row),
        row.get("payment_instrument_id", UUID.class),
        row.get("reserved_instrument_version", Long.class),
        row.get("customer_provisioning_request_id", UUID.class),
        row.get("provider_customer_id", String.class),
        row.get("subscription_provisioning_request_id", UUID.class),
        row.get("cleanup_provider_identity", UUID.class),
        row.get("result_status", String.class),
        row.get("result_row_version", Long.class),
        instant(row, "created_at"));
  }

  private static AccountSubscriptionCurrentRecord current(Record row) {
    return new AccountSubscriptionCurrentRecord(
        row.get("subscription_id", UUID.class),
        row.get("tenant_id", UUID.class),
        owner(row),
        row.get("plan_code", String.class),
        quote(row),
        row.get("payment_instrument_id", UUID.class),
        row.get("reserved_instrument_version", Long.class),
        row.get("customer_provisioning_request_id", UUID.class),
        row.get("provider_customer_id", String.class),
        row.get("creation_request_id", UUID.class),
        row.get("row_version", Long.class),
        row.get("status", String.class),
        row.get("provider_subscription_id", String.class),
        row.get("terminal_resolution_id", UUID.class),
        instant(row, "superseded_at"),
        row.get("superseded_by_request_id", UUID.class));
  }

  private static AccountHostingPlanQuote quote(Record row) {
    var quote =
        new AccountHostingPlanQuote(
            row.get("plan_code", String.class),
            row.get("provider_price_id", String.class),
            row.get("provider_quantity", Long.class),
            row.get("max_active_sessions", Long.class),
            row.get("max_concurrent_game_instances", Long.class),
            row.get("max_storage_bytes", Long.class));
    quote.requireRetained(
        row.get("plan_quote_payload", byte[].class), row.get("plan_quote_digest", String.class));
    return quote;
  }

  private static OwnerFence owner(Record row) {
    return new OwnerFence(
        row.get("account_uuid", UUID.class),
        row.get("owner_version", Long.class),
        row.get("owner_generation", UUID.class));
  }

  private static Instant instant(Record row, String column) {
    OffsetDateTime value = row.get(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Subscription intent requires an active writable Account transaction");
    }
  }

  public enum MappingDisposition {
    UNRESOLVED,
    CURRENT_MAPPING,
    HISTORY_ONLY,
    TERMINAL_HISTORY
  }

  public record ProviderLookup(
      MappingDisposition disposition,
      Optional<AccountSubscriptionProviderHistoryRecord> history,
      Optional<TerminalResolution> terminalResolution) {}

  public static final class ConflictException extends IllegalStateException {
    public ConflictException(String message) {
      super(message);
    }

    public ConflictException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class FenceRejectedException extends IllegalStateException {
    public FenceRejectedException(String message) {
      super(message);
    }
  }
}
