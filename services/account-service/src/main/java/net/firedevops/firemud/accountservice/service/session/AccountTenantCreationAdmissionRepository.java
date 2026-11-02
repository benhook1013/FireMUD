package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.AccountAuthenticatedActor;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRedisClient.QualifiedReceipt;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account-local immutable request freezing and original admission; never dispatches Game Design.
 */
public final class AccountTenantCreationAdmissionRepository {
  private static final String REQUESTS = "account_tenant_creation_admission_requests";
  private static final String ADMISSIONS = "account_tenant_creation_admissions";
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The injected DSLContext is the repository's internal persistence collaborator.")
  public AccountTenantCreationAdmissionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Freeze data only. The caller commits this transaction before contacting Redis. */
  public PreparedAdmission prepareLocked(
      AccountTenantCreationIntentRepository.OriginalIntent intent,
      AccountAuthenticatedActor actor,
      AccountTenantCreationAdmissionBinding binding) {
    writable();
    Objects.requireNonNull(intent);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(binding);
    if (!actor.accountId().equals(binding.actorAccountId())
        || !actor.issuanceOperationId().equals(binding.issuanceOperationId())
        || !actor.tokenHash().equals(binding.tokenHash())
        || !intent.requestId().equals(binding.intentRequestId())
        || !intent.creationOperationId().equals(binding.creationOperationId())
        || !intent.initiatingAccountId().equals(binding.actorAccountId())
        || !intent.targetNamespace().equals(binding.targetNamespace())
        || !intent.gameDesignCreationRequestId().equals(binding.gameDesignCreationRequestId())
        || !intent.gameDesignRequestDigest().equals(binding.gameDesignRequestDigest())
        || !intent.requestDigest().equals(binding.intentDigest())
        || !intent
            .recentReauthenticationEvidenceId()
            .equals(binding.reauthenticationEvidenceId())) {
      throw conflict();
    }
    lock(binding.creationOperationId());
    var existing = readPrepared(binding.creationOperationId());
    if (existing.isPresent()) {
      same(existing.orElseThrow(), binding);
      return existing.orElseThrow();
    }
    byte[] payload = binding.canonicalReceipt();
    dsl.execute(
        "INSERT INTO "
            + REQUESTS
            + " (creation_operation_id, intent_request_id, issuance_operation_id, actor_account_id,"
            + " target_namespace, game_design_creation_request_id, game_design_request_digest,"
            + " intent_digest, active_registry_digest, reauthentication_evidence_id, token_hash,"
            + " token_cleanup_deadline_epoch_millis, token_expires_at_epoch_millis,"
            + " prepared_at_epoch_millis, operation_deadline_epoch_millis,"
            + " receipt_cleanup_deadline_epoch_millis, binding_payload, binding_digest)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.creationOperationId(),
        binding.intentRequestId(),
        binding.issuanceOperationId(),
        binding.actorAccountId(),
        binding.targetNamespace(),
        binding.gameDesignCreationRequestId(),
        binding.gameDesignRequestDigest(),
        binding.intentDigest(),
        binding.activeRegistryDigest(),
        binding.reauthenticationEvidenceId(),
        binding.tokenHash(),
        binding.tokenCleanupDeadlineEpochMillis(),
        binding.tokenExpiresAtEpochMillis(),
        binding.preparedAtEpochMillis(),
        binding.operationDeadlineEpochMillis(),
        binding.receiptCleanupDeadlineEpochMillis(),
        payload,
        digest(payload));
    var stored = readPrepared(binding.creationOperationId()).orElseThrow();
    same(stored, binding);
    return stored;
  }

  /**
   * Insert only under a still-held physical Redis qualification. The SQL trigger share-locks the
   * actual issuance and checks its current COMMITTED state and the fresh database clock. Caller
   * must commit and independently read back before dispatch or acknowledgement.
   */
  public Admitted admitLocked(PreparedAdmission prepared, QualifiedReceipt receipt) {
    writable();
    Objects.requireNonNull(prepared);
    Objects.requireNonNull(receipt).requireCurrent();
    same(prepared, receipt.binding());
    if (!Arrays.equals(prepared.binding.canonicalReceipt(), receipt.canonicalReceipt())
        || !digest(receipt.canonicalReceipt()).equals(receipt.recordDigest())) throw conflict();
    lock(prepared.binding.creationOperationId());
    var stored = readPrepared(prepared.binding.creationOperationId()).orElseThrow();
    same(stored, prepared.binding);
    receipt.requireCurrent();
    var existing = readAdmitted(prepared.binding.creationOperationId());
    if (existing.isPresent()) {
      receipt.requireCurrent();
      return existing.orElseThrow();
    }
    dsl.execute(
        "INSERT INTO "
            + ADMISSIONS
            + " (creation_operation_id, receipt_payload, receipt_digest, local_aof_count,"
            + " replica_aof_count, protected_binding_id, qualification_fence)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        prepared.binding.creationOperationId(),
        receipt.canonicalReceipt(),
        receipt.recordDigest(),
        receipt.localAofCount(),
        receipt.replicaAofCount(),
        receipt.protectedBindingId(),
        receipt.qualificationFence());
    receipt.requireCurrent();
    var admitted = readAdmitted(prepared.binding.creationOperationId()).orElseThrow();
    receipt.requireCurrent();
    return admitted;
  }

  /** Data-only recovery lookup. Absence never authenticates or authorizes creation. */
  public Optional<PreparedAdmission> readPrepared(UUID creationOperationId) {
    transaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + REQUESTS + " WHERE creation_operation_id = ?",
            Objects.requireNonNull(creationOperationId));
    if (row == null) return Optional.empty();
    var binding =
        new AccountTenantCreationAdmissionBinding(
            row.get("intent_request_id", UUID.class), row.get("creation_operation_id", UUID.class),
            row.get("issuance_operation_id", UUID.class), row.get("actor_account_id", UUID.class),
            row.get("target_namespace", String.class),
                row.get("game_design_creation_request_id", UUID.class),
            row.get("game_design_request_digest", String.class),
                row.get("intent_digest", String.class),
            row.get("active_registry_digest", String.class),
                row.get("reauthentication_evidence_id", UUID.class),
            row.get("token_hash", String.class),
                row.get("token_cleanup_deadline_epoch_millis", Long.class),
            row.get("token_expires_at_epoch_millis", Long.class),
                row.get("prepared_at_epoch_millis", Long.class),
            row.get("operation_deadline_epoch_millis", Long.class),
                row.get("receipt_cleanup_deadline_epoch_millis", Long.class));
    byte[] payload = row.get("binding_payload", byte[].class);
    if (!Arrays.equals(binding.canonicalReceipt(), payload)
        || !digest(payload).equals(row.get("binding_digest", String.class))) throw conflict();
    return Optional.of(new PreparedAdmission(binding));
  }

  /**
   * Existing original only: does not contact Redis, require an active old JWT, re-admit, or cancel
   * history after later revocation. A returned row is still subject to independent mutation gates.
   */
  public Optional<Admitted> readAdmitted(UUID creationOperationId) {
    transaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + ADMISSIONS + " WHERE creation_operation_id = ?",
            Objects.requireNonNull(creationOperationId));
    if (row == null) return Optional.empty();
    var prepared = readPrepared(creationOperationId).orElseThrow();
    byte[] payload = row.get("receipt_payload", byte[].class);
    if (!Arrays.equals(prepared.binding.canonicalReceipt(), payload)
        || !digest(payload).equals(row.get("receipt_digest", String.class))) throw conflict();
    return Optional.of(
        new Admitted(
            prepared.binding,
            payload,
            row.get("receipt_digest", String.class),
            row.get("local_aof_count", Long.class),
            row.get("replica_aof_count", Long.class),
            row.get("protected_binding_id", String.class),
            row.get("qualification_fence", Long.class),
            row.get("admitted_at", OffsetDateTime.class).toInstant()));
  }

  private void lock(UUID operationId) {
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text, ?))",
        operationId,
        "tenant-creation-admission".hashCode());
  }

  private static String digest(byte[] bytes) {
    return AccountControlUiCoordination.digest("SHA-256", bytes);
  }

  private static void same(
      PreparedAdmission prepared, AccountTenantCreationAdmissionBinding binding) {
    if (!Arrays.equals(prepared.binding.canonicalReceipt(), binding.canonicalReceipt()))
      throw conflict();
  }

  private static void transaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException(
          "Original creation admission requires an Account transaction");
  }

  private static void writable() {
    transaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException(
          "Original creation admission requires a writable Account transaction");
  }

  private static IllegalStateException conflict() {
    return new IllegalStateException("Original creation admission identity or evidence differs");
  }

  /** Privately constructed frozen data receipt; confers no admission authority. */
  public static final class PreparedAdmission {
    private final AccountTenantCreationAdmissionBinding binding;

    private PreparedAdmission(AccountTenantCreationAdmissionBinding binding) {
      this.binding = binding;
    }

    public AccountTenantCreationAdmissionBinding binding() {
      return binding;
    }
  }

  /**
   * Immutable original admission evidence. A local return precedes caller-owned transaction commit.
   */
  public static final class Admitted {
    private final AccountTenantCreationAdmissionBinding binding;
    private final byte[] receiptPayload;
    private final String receiptDigest;
    private final long localAofCount;
    private final long replicaAofCount;
    private final String protectedBindingId;
    private final long qualificationFence;
    private final Instant admittedAt;

    private Admitted(
        AccountTenantCreationAdmissionBinding binding,
        byte[] receiptPayload,
        String receiptDigest,
        long localAofCount,
        long replicaAofCount,
        String protectedBindingId,
        long qualificationFence,
        Instant admittedAt) {
      this.binding = binding;
      this.receiptPayload = receiptPayload.clone();
      this.receiptDigest = receiptDigest;
      this.localAofCount = localAofCount;
      this.replicaAofCount = replicaAofCount;
      this.protectedBindingId = protectedBindingId;
      this.qualificationFence = qualificationFence;
      this.admittedAt = admittedAt;
    }

    public AccountTenantCreationAdmissionBinding binding() {
      return binding;
    }

    public byte[] receiptPayload() {
      return receiptPayload.clone();
    }

    public String receiptDigest() {
      return receiptDigest;
    }

    public long localAofCount() {
      return localAofCount;
    }

    public long replicaAofCount() {
      return replicaAofCount;
    }

    public String protectedBindingId() {
      return protectedBindingId;
    }

    public long qualificationFence() {
      return qualificationFence;
    }

    public Instant admittedAt() {
      return admittedAt;
    }
  }
}
