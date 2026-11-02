package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.requireDigest;
import static net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.requireProviderCustomer;
import static net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.requireUuid;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.Observation;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.State;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manually composed Account customer persistence, not an authenticated producer or provider worker.
 * Caller supplies already-verified provider observation digests in a short writable Account
 * transaction. No remote validation or mutation belongs inside that transaction.
 */
public final class AccountBillingCustomerRepository {
  private static final String HISTORY_SELECT =
      "SELECT c.account_uuid, c.customer_provisioning_request_id, c.provider_idempotency_id, "
          + "h.row_version, h.state, h.provider_customer_id, h.request_id AS change_request_id, "
          + "h.observation, h.evidence_digest, c.created_at, h.recorded_at AS updated_at "
          + "FROM account_billing_customer_observations h JOIN billing_customer c USING (account_uuid) ";
  private final DSLContext dsl;

  public AccountBillingCustomerRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Competing first-subscription candidates reuse the one Account-keyed customer intent. Reusing an
   * existing operation/key with changed binding is a conflict, never a second customer intent.
   */
  public IntentResult retainIntent(UUID accountUuid, UUID operationId, UUID providerIdempotencyId) {
    requireWritableTransaction();
    requireUuid(accountUuid);
    requireUuid(operationId);
    requireUuid(providerIdempotencyId);
    if (operationId.equals(providerIdempotencyId)) {
      throw new IllegalArgumentException(
          "Customer operation and provider idempotency IDs must differ");
    }
    lockAccount(accountUuid);
    List<Record> identityMatches =
        dsl.fetch(
            "SELECT * FROM billing_customer WHERE customer_provisioning_request_id = ? OR provider_idempotency_id = ?",
            operationId,
            providerIdempotencyId);
    if (!identityMatches.isEmpty()) {
      if (identityMatches.size() != 1) {
        throw new ConflictException("Customer request/key belongs to conflicting intents");
      }
      AccountBillingCustomerRecord retained = decode(identityMatches.getFirst());
      if (!retained.accountUuid().equals(accountUuid)
          || !retained.customerProvisioningRequestId().equals(operationId)
          || !retained.providerIdempotencyId().equals(providerIdempotencyId)) {
        throw new ConflictException("Customer request/key binding changed");
      }
      return new IntentResult(IntentDisposition.REPLAYED, retained);
    }
    Optional<AccountBillingCustomerRecord> existing = findByAccountForUpdate(accountUuid);
    if (existing.isPresent()) {
      return new IntentResult(IntentDisposition.REUSED, existing.orElseThrow());
    }
    try {
      int inserted =
          dsl.execute(
              "INSERT INTO billing_customer (account_uuid, customer_provisioning_request_id, provider_idempotency_id, "
                  + "row_version, state, change_request_id, observation) VALUES (?, ?, ?, 1, 'UNCONFIRMED', ?, 'NONE') "
                  + "ON CONFLICT DO NOTHING",
              accountUuid,
              operationId,
              providerIdempotencyId,
              operationId);
      if (inserted != 1) {
        throw new ConflictException(
            "Customer request/key was concurrently retained for another owner");
      }
    } catch (IntegrityConstraintViolationException conflict) {
      throw new ConflictException(
          "Customer intent conflicts with retained request evidence", conflict);
    }
    return new IntentResult(IntentDisposition.CREATED, findByAccount(accountUuid).orElseThrow());
  }

  public Optional<AccountBillingCustomerRecord> findByAccount(UUID accountUuid) {
    requireUuid(accountUuid);
    Record row = dsl.fetchOne("SELECT * FROM billing_customer WHERE account_uuid = ?", accountUuid);
    return row == null ? Optional.empty() : Optional.of(decode(row));
  }

  /**
   * Account-first lock order; Root must retain this transaction through its local intent/claim CAS.
   */
  public Optional<AccountBillingCustomerRecord> findByAccountForUpdate(UUID accountUuid) {
    requireWritableTransaction();
    requireUuid(accountUuid);
    lockAccount(accountUuid);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM billing_customer WHERE account_uuid = ? FOR UPDATE", accountUuid);
    return row == null ? Optional.empty() : Optional.of(decode(row));
  }

  /** Exact retry returns the original retained observation even after later observations. */
  public AccountBillingCustomerRecord recordObservation(
      UUID accountUuid,
      UUID operationId,
      long expectedVersion,
      UUID requestId,
      Observation observation,
      String providerCustomerId,
      String evidenceDigest) {
    requireWritableTransaction();
    requireUuid(accountUuid);
    requireUuid(operationId);
    requireUuid(requestId);
    requireDigest(evidenceDigest);
    if (expectedVersion < 1
        || expectedVersion == Long.MAX_VALUE
        || observation == null
        || observation == Observation.NONE) {
      throw new IllegalArgumentException("Customer observation and expected version are required");
    }
    if (observation == Observation.CONFIRMED) {
      requireProviderCustomer(providerCustomerId);
    } else if (providerCustomerId != null) {
      throw new IllegalArgumentException(
          "Ambiguous/absent customer evidence must remain unconfirmed");
    }
    AccountBillingCustomerRecord current =
        findByAccountForUpdate(accountUuid)
            .orElseThrow(() -> new ConflictException("Customer intent is absent"));
    if (!current.customerProvisioningRequestId().equals(operationId)
        || requestId.equals(current.providerIdempotencyId())) {
      throw new ConflictException("Customer observation does not bind the retained operation");
    }
    Record receipt = dsl.fetchOne(HISTORY_SELECT + "WHERE h.request_id = ?", requestId);
    if (receipt != null) {
      AccountBillingCustomerRecord original = decode(receipt);
      if (!original.accountUuid().equals(accountUuid)
          || !original.customerProvisioningRequestId().equals(operationId)
          || original.rowVersion() != expectedVersion + 1
          || original.observation() != observation
          || !Objects.equals(original.providerCustomerId(), providerCustomerId)
          || !Objects.equals(original.evidenceDigest(), evidenceDigest)) {
        throw new ConflictException("Customer observation request was reused with changed input");
      }
      return original;
    }
    if (current.rowVersion() != expectedVersion) {
      throw new StaleVersionException();
    }
    if (current.state() == State.CONFIRMED) {
      throw new ConflictException("Confirmed customer cannot be rebound or regressed");
    }
    try {
      int changed =
          dsl.execute(
              "UPDATE billing_customer SET row_version = row_version + 1, state = ?, provider_customer_id = ?, "
                  + "change_request_id = ?, observation = ?, evidence_digest = ? "
                  + "WHERE account_uuid = ? AND customer_provisioning_request_id = ? AND row_version = ?",
              observation == Observation.CONFIRMED ? "CONFIRMED" : "RECONCILING",
              providerCustomerId,
              requestId,
              observation.name(),
              evidenceDigest,
              accountUuid,
              operationId,
              expectedVersion);
      if (changed != 1) {
        throw new StaleVersionException();
      }
    } catch (IntegrityConstraintViolationException conflict) {
      // The caller must roll back this owner transaction; a rejected SQL statement is not success.
      throw new ConflictException(
          "Customer request/provider binding conflicts with retained evidence", conflict);
    }
    return findByAccount(accountUuid).orElseThrow();
  }

  /**
   * Reads one immutable original customer-observation receipt by its globally unique request ID.
   * The caller must hold the Account and customer owner locks in a writable transaction.
   */
  public Optional<AccountBillingCustomerRecord> findObservationByRequestId(UUID requestId) {
    requireWritableTransaction();
    requireUuid(requestId);
    Record row = dsl.fetchOne(HISTORY_SELECT + "WHERE h.request_id = ?", requestId);
    return row == null ? Optional.empty() : Optional.of(decode(row));
  }

  /**
   * Commits a retained first-dispatch boundary under the existing Account/customer locks. Only this
   * invocation's successful INSERT on a pristine intent can receive initial-call permission;
   * replaying the same claim ID cannot receive it. A claim is not provider evidence and does not
   * confirm the customer.
   */
  public DispatchClaimResult claimFirstProviderDispatch(
      AccountBillingCustomerRecord expected, UUID claimId) {
    requireWritableTransaction();
    Objects.requireNonNull(expected, "durable customer intent is required");
    requireUuid(claimId);
    if (claimId.equals(expected.customerProvisioningRequestId())
        || claimId.equals(expected.providerIdempotencyId())) {
      throw new IllegalArgumentException("Dispatch claim identity must be separate");
    }
    AccountBillingCustomerRecord current =
        findByAccountForUpdate(expected.accountUuid())
            .orElseThrow(() -> new ConflictException("Customer intent is absent"));
    if (!current.equals(expected) || current.state() == State.CONFIRMED) {
      throw new ConflictException("Customer intent changed before first-dispatch claim");
    }
    int inserted =
        dsl.execute(
            "INSERT INTO account_billing_customer_dispatch_claim "
                + "(account_uuid, customer_provisioning_request_id, provider_idempotency_id, claim_id) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (account_uuid) DO NOTHING",
            current.accountUuid(),
            current.customerProvisioningRequestId(),
            current.providerIdempotencyId(),
            claimId);
    DispatchClaim retained =
        findDispatchClaim(current.accountUuid())
            .orElseThrow(() -> new ConflictException("Dispatch claim readback is absent"));
    if (!retained.customerProvisioningRequestId().equals(current.customerProvisioningRequestId())
        || !retained.providerIdempotencyId().equals(current.providerIdempotencyId())
        || (inserted == 1 && !retained.claimId().equals(claimId))) {
      throw new ConflictException("Dispatch claim readback changed immutable owner binding");
    }
    return new DispatchClaimResult(retained, inserted == 1);
  }

  /** Exact immutable dispatch receipt, independently read after the claim transaction commits. */
  public Optional<DispatchClaim> findDispatchClaim(UUID accountUuid) {
    requireWritableTransaction();
    requireUuid(accountUuid);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_billing_customer_dispatch_claim WHERE account_uuid = ?",
            accountUuid);
    return row == null
        ? Optional.empty()
        : Optional.of(
            new DispatchClaim(
                row.get("account_uuid", UUID.class),
                row.get("customer_provisioning_request_id", UUID.class),
                row.get("provider_idempotency_id", UUID.class),
                row.get("claim_id", UUID.class),
                row.get("claimed_at", OffsetDateTime.class).toInstant()));
  }

  public record DispatchClaim(
      UUID accountUuid,
      UUID customerProvisioningRequestId,
      UUID providerIdempotencyId,
      UUID claimId,
      java.time.Instant claimedAt) {}

  public record DispatchClaimResult(DispatchClaim claim, boolean inserted) {}

  public List<AccountBillingCustomerRecord> readHistory(UUID accountUuid) {
    requireUuid(accountUuid);
    return dsl.fetch(
            HISTORY_SELECT + "WHERE h.account_uuid = ? ORDER BY h.row_version", accountUuid)
        .map(AccountBillingCustomerRepository::decode);
  }

  private void lockAccount(UUID accountUuid) {
    if (dsl.fetchOne(
            "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", accountUuid)
        == null) {
      throw new ConflictException("Existing Account UUID owner is required");
    }
  }

  private static AccountBillingCustomerRecord decode(Record row) {
    return new AccountBillingCustomerRecord(
        row.get("account_uuid", UUID.class),
        row.get("customer_provisioning_request_id", UUID.class),
        row.get("provider_idempotency_id", UUID.class),
        row.get("row_version", Long.class),
        State.valueOf(row.get("state", String.class)),
        row.get("provider_customer_id", String.class),
        row.get("change_request_id", UUID.class),
        Observation.valueOf(row.get("observation", String.class)),
        row.get("evidence_digest", String.class),
        row.get("created_at", OffsetDateTime.class).toInstant(),
        row.get("updated_at", OffsetDateTime.class).toInstant());
  }

  private static void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Customer storage requires an active writable Account transaction");
    }
  }

  public enum IntentDisposition {
    CREATED,
    REPLAYED,
    REUSED
  }

  public record IntentResult(
      IntentDisposition disposition, AccountBillingCustomerRecord customer) {}

  public static final class ConflictException extends IllegalStateException {
    public ConflictException(String message) {
      super(message);
    }

    public ConflictException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class StaleVersionException extends IllegalStateException {
    public StaleVersionException() {
      super("Customer row-version fence rejected");
    }
  }
}
