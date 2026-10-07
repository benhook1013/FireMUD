package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, non-admitting Account-owned storage foundation. No production caller exists.
 *
 * <p>Owner-internal seams require a writable SERIALIZABLE Account transaction and lock Account
 * before allocation or operation rows. They compare storage identity, not current lifecycle,
 * billing, security, source checkpoints or authenticated workload. An eventual authenticated owner
 * must compose every current predicate before using these seams. COMMITTED here records a decision
 * identity only; it is never source authorization or a public finalization result.
 *
 * <p>SQL statement time guards expiry; this does not prove expiry at physical transaction COMMIT.
 * ABORTED atomically retains the original full lease and exact orphan decision as pending cleanup;
 * Account neither deletes Game Session state nor claims token retirement or cleanup delivery.
 */
public final class AccountGameplayAdmissionLeaseRepository {
  private static final String OPERATIONS = "account_gameplay_admission_lease_operations";
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Private injected persistence context; the repository never exposes it")
  public AccountGameplayAdmissionLeaseRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Fence domain is independent of issuance/token fences; retries retain the first allocation. */
  Allocation allocate(UUID accountId, UUID requestId, UUID leaseId) {
    lockAccount(accountId);
    requireUuidV4(requestId);
    requireUuidV4(leaseId);
    Record existing =
        dsl.fetchOne(
            "SELECT * FROM account_gameplay_admission_lease_allocations WHERE request_id = ?",
            requestId);
    if (existing != null) {
      Allocation prior = allocation(existing);
      if (!prior.accountId().equals(accountId)
          || !prior.requestId().equals(requestId)
          || !prior.leaseId().equals(leaseId)) throw conflict();
      return prior;
    }
    dsl.execute(
        "INSERT INTO account_gameplay_admission_lease_fences(account_uuid, last_fence) VALUES (?, 1) "
            + "ON CONFLICT (account_uuid) DO UPDATE SET last_fence = "
            + "account_gameplay_admission_lease_fences.last_fence + 1",
        accountId);
    Record counter =
        dsl.fetchOne(
            "SELECT last_fence FROM account_gameplay_admission_lease_fences WHERE account_uuid = ?",
            accountId);
    if (counter == null) {
      throw new IllegalStateException("Account admission lease counter readback unavailable");
    }
    Long fence = counter.get("last_fence", Long.class);
    if (fence == null || fence <= 0L) {
      throw new IllegalStateException("Account admission lease counter readback invalid");
    }
    int inserted =
        dsl.execute(
            "INSERT INTO account_gameplay_admission_lease_allocations "
                + "(account_uuid, request_id, lease_id, lease_fence) VALUES (?, ?, ?, ?)",
            accountId,
            requestId,
            leaseId,
            fence);
    if (inserted != 1) {
      throw new IllegalStateException("Account admission lease allocation insert unavailable");
    }
    Allocation expected = new Allocation(accountId, requestId, leaseId, fence);
    Record readback =
        dsl.fetchOne(
            "SELECT * FROM account_gameplay_admission_lease_allocations WHERE request_id = ?",
            requestId);
    if (readback == null) {
      throw new IllegalStateException("Account admission lease allocation readback unavailable");
    }
    Allocation stored = allocation(readback);
    if (!expected.equals(stored)) throw conflict();
    return stored;
  }

  /**
   * Recovers the original allocation after a lost response using only the original request. An
   * empty result is storage absence only, never authoritative absence or no-token proof.
   */
  Optional<Allocation> findAllocation(UUID accountId, UUID requestId) {
    lockAccount(accountId);
    requireUuidV4(requestId);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_gameplay_admission_lease_allocations WHERE request_id = ?",
            requestId);
    if (row == null) return Optional.empty();
    Allocation stored = allocation(row);
    if (!accountId.equals(stored.accountId()) || !requestId.equals(stored.requestId()))
      throw conflict();
    return Optional.of(stored);
  }

  /**
   * Recovers validated original operation storage after a lost response. Workload equality is
   * storage correlation only. An eventual facade must supply its concretely verified peer and
   * compare current authorization and immutable client inputs before any externally usable retry.
   * Empty storage is never admission, authoritative absence or no-token evidence.
   */
  Optional<AccountGameplayAdmissionLeaseOperation> findByRequest(
      UUID accountId, UUID requestId, String expectedCallerWorkload) {
    lockAccount(accountId);
    requireUuidV4(requestId);
    if (expectedCallerWorkload == null || expectedCallerWorkload.isBlank()) {
      throw new IllegalArgumentException("Exact stored caller workload correlation required");
    }
    Record row = select(requestId);
    if (row == null) return Optional.empty();
    AccountGameplayAdmissionLeaseEvidence evidence =
        AccountGameplayAdmissionLeaseEvidence.parseCanonical(
            row.get("evidence_json", String.class));
    AccountGameplayAdmissionLeaseOperation stored = exact(row, evidence);
    if (!accountId.equals(accountId(stored.evidence()))
        || !requestId.equals(id(stored.evidence(), "requestId"))
        || !expectedCallerWorkload.equals(stored.evidence().carrier().get("callerWorkload"))) {
      throw conflict();
    }
    return Optional.of(stored);
  }

  /** Stores only the exact carrier for an already retained Account-owned allocation. */
  AccountGameplayAdmissionLeaseOperation beginPending(
      AccountGameplayAdmissionLeaseEvidence evidence) {
    Objects.requireNonNull(evidence);
    UUID accountId = accountId(evidence);
    lockAccount(accountId);
    Allocation expected =
        new Allocation(
            accountId,
            id(evidence, "requestId"),
            id(evidence, "leaseId"),
            evidence.leaseFence().longValueExact());
    Record allocated =
        dsl.fetchOne(
            "SELECT * FROM account_gameplay_admission_lease_allocations WHERE request_id = ?",
            expected.requestId());
    if (allocated == null || !expected.equals(allocation(allocated))) throw conflict();
    Record prior = select(expected.requestId());
    if (prior != null) return exact(prior, evidence);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OPERATIONS
                + " (account_uuid, request_id, lease_id, lease_fence, caller_workload, evidence_json, "
                + "evidence_sha256, evaluated_at_ms, expires_at_ms, status) "
                + "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING' "
                + "WHERE ? <= floor(extract(epoch FROM clock_timestamp()) * 1000) "
                + "AND ? > floor(extract(epoch FROM clock_timestamp()) * 1000)",
            accountId,
            expected.requestId(),
            expected.leaseId(),
            expected.leaseFence(),
            evidence.carrier().get("callerWorkload"),
            evidence.canonicalJson(),
            evidence.sha256(),
            time(evidence, "evaluatedAt"),
            time(evidence, "expiresAt"),
            time(evidence, "evaluatedAt"),
            time(evidence, "expiresAt"));
    if (inserted != 1) throw new IllegalStateException("Admission lease storage deadline expired");
    return exact(select(expected.requestId()), evidence);
  }

  /** Exact retry/readback retains the original state and deadline, including expired evidence. */
  Optional<AccountGameplayAdmissionLeaseOperation> readExact(
      AccountGameplayAdmissionLeaseEvidence evidence) {
    Objects.requireNonNull(evidence);
    lockAccount(accountId(evidence));
    Record stored = select(id(evidence, "requestId"));
    return stored == null ? Optional.empty() : Optional.of(exact(stored, evidence));
  }

  AccountGameplayAdmissionLeaseOperation recordCommitted(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID bindingDecisionId) {
    return terminal(evidence, State.COMMITTED, bindingDecisionId, null);
  }

  /** A missing decision remains unknown; the original cleanup identity is always required. */
  AccountGameplayAdmissionLeaseOperation recordAborted(
      AccountGameplayAdmissionLeaseEvidence evidence,
      UUID bindingDecisionId,
      UUID orphanCleanupId) {
    requireUuidV4(orphanCleanupId);
    return terminal(evidence, State.ABORTED, bindingDecisionId, orphanCleanupId);
  }

  private AccountGameplayAdmissionLeaseOperation terminal(
      AccountGameplayAdmissionLeaseEvidence evidence, State state, UUID decision, UUID cleanup) {
    if (state == State.COMMITTED || decision != null) requireUuidV4(decision);
    AccountGameplayAdmissionLeaseOperation prior =
        readExact(evidence)
            .orElseThrow(
                () -> new IllegalStateException("Exact admission lease operation missing"));
    if (prior.state() != State.PENDING) {
      if (prior.state() != state
          || !Objects.equals(prior.bindingDecisionId(), decision)
          || !Objects.equals(prior.orphanCleanupId(), cleanup)) throw conflict();
      return prior;
    }
    int updated =
        dsl.execute(
            "UPDATE "
                + OPERATIONS
                + " SET status = ?, binding_decision_id = ?, orphan_cleanup_id = ? "
                + "WHERE request_id = ? AND status = 'PENDING' "
                + "AND (? = 'ABORTED' OR expires_at_ms > floor(extract(epoch FROM clock_timestamp()) * 1000))",
            state.name(),
            decision,
            cleanup,
            id(evidence, "requestId"),
            state.name());
    if (updated != 1) throw new IllegalStateException("Admission lease storage deadline expired");
    return exact(select(id(evidence, "requestId")), evidence);
  }

  private void lockAccount(UUID accountId) {
    requireUuidV4(accountId);
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_SERIALIZABLE)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException("Writable SERIALIZABLE Account transaction required");
    }
    if (dsl.fetchOne(
            "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", accountId)
        == null) throw new IllegalStateException("Account admission storage owner missing");
  }

  private Record select(UUID requestId) {
    return dsl.fetchOne(
        "SELECT * FROM " + OPERATIONS + " WHERE request_id = ? FOR UPDATE", requestId);
  }

  private static AccountGameplayAdmissionLeaseOperation exact(
      Record row, AccountGameplayAdmissionLeaseEvidence expected) {
    if (row == null) throw new IllegalStateException("Admission lease storage readback missing");
    AccountGameplayAdmissionLeaseEvidence stored =
        AccountGameplayAdmissionLeaseEvidence.parseCanonical(
            row.get("evidence_json", String.class));
    if (!stored.sha256().equals(row.get("evidence_sha256", String.class))
        || !stored.hasSameIdentity(expected)
        || !accountId(stored).equals(row.get("account_uuid", UUID.class))
        || !id(stored, "requestId").equals(row.get("request_id", UUID.class))
        || !id(stored, "leaseId").equals(row.get("lease_id", UUID.class))
        || !Long.valueOf(stored.leaseFence().longValueExact())
            .equals(row.get("lease_fence", Long.class))
        || !stored.carrier().get("callerWorkload").equals(row.get("caller_workload", String.class))
        || !Long.valueOf(time(stored, "evaluatedAt")).equals(row.get("evaluated_at_ms", Long.class))
        || !Long.valueOf(time(stored, "expiresAt")).equals(row.get("expires_at_ms", Long.class)))
      throw conflict();
    return new AccountGameplayAdmissionLeaseOperation(
        stored,
        State.valueOf(row.get("status", String.class)),
        row.get("binding_decision_id", UUID.class),
        row.get("orphan_cleanup_id", UUID.class));
  }

  private static Allocation allocation(Record row) {
    UUID account = row.get("account_uuid", UUID.class);
    UUID request = row.get("request_id", UUID.class);
    UUID lease = row.get("lease_id", UUID.class);
    Long fence = row.get("lease_fence", Long.class);
    requireUuidV4(account);
    requireUuidV4(request);
    requireUuidV4(lease);
    if (fence == null || fence <= 0L) throw conflict();
    return new Allocation(account, request, lease, fence);
  }

  private static UUID accountId(AccountGameplayAdmissionLeaseEvidence evidence) {
    return UUID.fromString(
        (String) ((java.util.Map<?, ?>) evidence.carrier().get("bindingScope")).get("accountId"));
  }

  private static UUID id(AccountGameplayAdmissionLeaseEvidence evidence, String key) {
    return UUID.fromString((String) evidence.carrier().get(key));
  }

  private static long time(AccountGameplayAdmissionLeaseEvidence evidence, String key) {
    return Long.parseLong((String) evidence.carrier().get(key));
  }

  private static void requireUuidV4(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2)
      throw new IllegalArgumentException("Canonical non-nil UUIDv4 identity required");
  }

  private static IdentityConflictException conflict() {
    return new IdentityConflictException();
  }

  static final class IdentityConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    IdentityConflictException() {
      super("Exact admission operation identity conflict");
    }
  }

  record Allocation(UUID accountId, UUID requestId, UUID leaseId, long leaseFence) {}
}
