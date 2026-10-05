package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.Checkpoint;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.FrozenAttempt;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal owner-local persistence for the World publication fence prerequisite.
 *
 * <p>This repository is not wired to an RPC or authoring route. A supplied checkpoint is retained
 * as owner evidence only; it is not an authenticated Game Design freeze acknowledgement.
 */
@Repository
public class WorldDesignPublicationFenceRepository {
  private static final String OPEN = "OPEN";
  private static final String FROZEN = "FROZEN";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private static final Table<?> OWNER = DSL.table(DSL.name("world_design_publication_fence_owner"));
  private static final Table<?> ATTEMPT =
      DSL.table(DSL.name("world_design_publication_fence_attempt"));
  private static final Table<?> VERSION_IDENTITY =
      DSL.table(DSL.name("world_authored_version_identity"));
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<Long> LOCAL_TENANT_KEY =
      DSL.field(DSL.name("local_tenant_key"), Long.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<UUID> VERSION_OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<UUID> VERSION_CANONICAL_VERSION_ID =
      DSL.field(DSL.name("canonical_version_id"), UUID.class);
  private static final Field<Long> VERSION_GAME_DESIGN_VERSION_ID =
      DSL.field(DSL.name("game_design_version_id"), Long.class);
  private static final Field<Long> VERSION_LOCAL_VERSION_KEY =
      DSL.field(DSL.name("local_version_key"), Long.class);
  private static final Field<String> VERSION_WORLD_SLUG =
      DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<UUID> VERSION_INTAKE_OPERATION_ID =
      DSL.field(DSL.name("intake_operation_id"), UUID.class);
  private static final Field<UUID> VERSION_INTAKE_REQUEST_ID =
      DSL.field(DSL.name("intake_request_id"), UUID.class);
  private static final Field<Long> VERSION_LOCAL_TENANT_KEY =
      DSL.field(DSL.name("local_tenant_key"), Long.class);
  private static final Field<String> VERSION_INTAKE_REQUEST_DIGEST =
      DSL.field(DSL.name("intake_request_digest"), String.class);
  private static final Field<UUID> VERSION_SOURCE_OPERATION_ID =
      DSL.field(DSL.name("source_operation_id"), UUID.class);
  private static final Field<String> VERSION_SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("source_evidence_digest"), String.class);
  private static final Field<String> VERSION_INTAKE_RECEIPT_DIGEST =
      DSL.field(DSL.name("intake_receipt_digest"), String.class);
  private static final Field<UUID> INTAKE_OPERATION_ID =
      DSL.field(DSL.name("intake_operation_id"), UUID.class);
  private static final Field<UUID> INTAKE_REQUEST_ID =
      DSL.field(DSL.name("intake_request_id"), UUID.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("source_operation_id"), UUID.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("source_evidence_digest"), String.class);
  private static final Field<String> INTAKE_RECEIPT_DIGEST =
      DSL.field(DSL.name("intake_receipt_digest"), String.class);
  private static final Field<String> OWNER_FREEZE_PHASE =
      DSL.field(DSL.name("owner_freeze_phase"), String.class);
  private static final Field<UUID> CURRENT_PUBLICATION_FENCE =
      DSL.field(DSL.name("current_publication_fence"), UUID.class);

  private static final Field<UUID> PUBLICATION_FENCE =
      DSL.field(DSL.name("publication_fence"), UUID.class);
  private static final Field<String> PUBLICATION_REQUEST_ID =
      DSL.field(DSL.name("publication_request_id"), String.class);
  private static final Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<Long> VERSION_STATE_EPOCH =
      DSL.field(DSL.name("version_state_epoch"), Long.class);
  private static final Field<String> PUBLISH_WORKFLOW_ID =
      DSL.field(DSL.name("publish_workflow_id"), String.class);
  private static final Field<String> APPLIED_COMMIT_ID =
      DSL.field(DSL.name("applied_commit_id"), String.class);
  private static final Field<String> CONTENT_DIGEST =
      DSL.field(DSL.name("content_digest"), String.class);
  private static final Field<Integer> DIGEST_SCHEMA_VERSION =
      DSL.field(DSL.name("digest_schema_version"), Integer.class);
  private static final Field<Short> OWNER_BINDING_SCHEMA_VERSION =
      DSL.field(DSL.name("owner_binding_schema_version"), Short.class);
  private static final Field<UUID> CANONICAL_VERSION_ID =
      DSL.field(DSL.name("canonical_version_id"), UUID.class);
  private static final Field<UUID> VERSION_IDENTITY_OPERATION_ID =
      DSL.field(DSL.name("version_identity_operation_id"), UUID.class);
  private static final Field<Long> GAME_DESIGN_VERSION_ID =
      DSL.field(DSL.name("game_design_version_id"), Long.class);
  private static final Field<String> INTAKE_REQUEST_DIGEST =
      DSL.field(DSL.name("intake_request_digest"), String.class);

  private final DSLContext dsl;
  private final WorldAuthoredSourceIntakeRepository intakeRepository;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Injected repositories are internal Spring collaborators; construction acquires no resources and this repository has no finalizer.")
  public WorldDesignPublicationFenceRepository(
      DSLContext dsl, WorldAuthoredSourceIntakeRepository intakeRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    this.intakeRepository = Objects.requireNonNull(intakeRepository, "intakeRepository");
  }

  /**
   * Acquires the shared writable version row for an ordinary mutation and holds its row lock until
   * the caller's owner transaction ends. The exact committed intake is resolved before row create
   * or lock; the private local tenant key is always derived from that immutable intake.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockOpen(OwnerBinding binding) {
    lockOpenAndResolve(binding);
  }

  /** Exact retained source and private keys, available only after the OPEN owner lock is held. */
  record OpenOwner(
      OwnerBinding binding, WorldAuthoredSourceIntakeReceipt receipt, long localVersionKey) {
    long localTenantKey() {
      return receipt.localTenantKey();
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  OpenOwner lockOpenAndResolve(OwnerBinding binding) {
    requireWritableReadCommittedOwnerTransaction();
    ResolvedVersion resolvedVersion = resolveVersion(binding);
    Record owner = createAndLockOwner(resolvedVersion);
    requireMatchingOwnerBinding(owner, resolvedVersion);
    if (!OPEN.equals(owner.get(OWNER_FREEZE_PHASE, String.class))) {
      throw new ConflictException("World version is not open for an ordinary Draft mutation");
    }
    if (owner.get(CURRENT_PUBLICATION_FENCE, UUID.class) != null) {
      throw new ConflictException("Open World version unexpectedly retains a publication fence");
    }
    return new OpenOwner(binding, resolvedVersion.receipt(), resolvedVersion.localVersionKey());
  }

  /**
   * Claims the owner-local freeze after taking the same version row lock as ordinary writers.
   *
   * <p>The checkpoint supplier runs only for the first OPEN-to-FROZEN transition, after this
   * transaction holds the row lock. An exact retry returns the stored fence and checkpoint without
   * recapturing server-derived output. No producer, caller authentication, or public freeze route
   * is established by this internal persistence method.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public FrozenAttempt claimFreeze(
      WorldDesignPublicationFenceEvidence evidence, Supplier<Checkpoint> checkpointSupplier) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(checkpointSupplier, "checkpointSupplier");
    OwnerBinding binding = evidence.ownerBinding();
    ResolvedVersion resolvedVersion = resolveVersion(binding);
    rejectLegacyAttempt(evidence, resolvedVersion.localVersionKey());
    Record owner = createAndLockOwner(resolvedVersion);
    requireMatchingOwnerBinding(owner, resolvedVersion);

    String phase = owner.get(OWNER_FREEZE_PHASE, String.class);
    UUID currentFence = owner.get(CURRENT_PUBLICATION_FENCE, UUID.class);
    if (FROZEN.equals(phase)) {
      if (currentFence == null) {
        throw new ConflictException("Frozen World owner row has no publication fence");
      }
      FrozenAttempt stored = findAttemptByFence(currentFence);
      if (stored == null) {
        throw new ConflictException("Frozen World owner row has no immutable attempt record");
      }
      requireExactRequest(stored, evidence);
      return stored;
    }
    if (!OPEN.equals(phase) || currentFence != null) {
      throw new ConflictException(
          "World version cannot start a publication freeze in its current phase");
    }
    if (findAttemptByRequest(evidence, resolvedVersion.localVersionKey()) != null) {
      throw new ConflictException("World publication request already has an immutable attempt");
    }

    Checkpoint checkpoint =
        Objects.requireNonNull(
            checkpointSupplier.get(), "checkpointSupplier returned no complete World checkpoint");
    UUID publicationFence = newNonNilUuid();
    int inserted = insertAttempt(evidence, resolvedVersion, publicationFence, checkpoint);
    if (inserted != 1) {
      throw new ConflictException("World publication attempt was not inserted");
    }

    int updated =
        dsl.update(OWNER)
            .set(OWNER_FREEZE_PHASE, FROZEN)
            .set(CURRENT_PUBLICATION_FENCE, publicationFence)
            .where(
                TARGET_NAMESPACE
                    .eq(evidence.targetNamespace())
                    .and(CANONICAL_TENANT_ID.eq(evidence.canonicalTenantId()))
                    .and(VERSION_ID.eq(resolvedVersion.localVersionKey()))
                    .and(OWNER_FREEZE_PHASE.eq(OPEN))
                    .and(CURRENT_PUBLICATION_FENCE.isNull()))
            .execute();
    if (updated != 1) {
      throw new ConflictException("World publication freeze lost its locked OPEN owner row");
    }
    return new FrozenAttempt(evidence, publicationFence, checkpoint);
  }

  /**
   * Reads one exact immutable attempt by its full request binding without creating or changing
   * rows.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<FrozenAttempt> readAttempt(WorldDesignPublicationFenceEvidence evidence) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("World publication attempt read requires a committed read");
    }
    ResolvedVersion resolvedVersion = resolveVersion(evidence.ownerBinding());
    rejectLegacyAttempt(evidence, resolvedVersion.localVersionKey());
    FrozenAttempt stored = findAttemptByRequest(evidence, resolvedVersion.localVersionKey());
    if (stored == null) {
      return Optional.empty();
    }
    requireExactRequest(stored, evidence);
    return Optional.of(stored);
  }

  private ResolvedVersion resolveVersion(OwnerBinding binding) {
    Objects.requireNonNull(binding, "binding");
    WorldAuthoredSourceIntakeReceipt receipt =
        intakeRepository
            .read(binding.targetNamespace(), binding.intakeRequestId())
            .orElseThrow(
                () ->
                    new MissingIntakeException(
                        "World publication fence requires an already committed authored-source intake"));
    requireExactIntake(binding, receipt);

    Record identity =
        dsl.selectFrom(VERSION_IDENTITY)
            .where(VERSION_OPERATION_ID.eq(binding.versionIdentityOperationId()))
            .forShare()
            .fetchOne();
    if (identity == null) {
      throw new MissingVersionIdentityException(
          "World publication fence requires an exact retained V27 authored-Version identity");
    }
    requireExactVersionIdentity(identity, binding, receipt);
    Long localVersionKey = identity.get(VERSION_LOCAL_VERSION_KEY, Long.class);
    if (localVersionKey == null || localVersionKey <= 0L) {
      throw new ConflictException("World Version identity has no valid private local Version key");
    }
    return new ResolvedVersion(binding, receipt, localVersionKey);
  }

  private void requireExactIntake(OwnerBinding binding, WorldAuthoredSourceIntakeReceipt receipt) {
    if (!binding.targetNamespace().equals(receipt.targetNamespace())
        || !binding.canonicalTenantId().equals(receipt.canonicalTenantId())
        || !binding.intakeRequestId().equals(receipt.intakeRequestId())
        || !binding.intakeOperationId().equals(receipt.operationId())
        || !binding.intakeRequestDigest().equals(receipt.requestDigest())
        || !binding.sourceOperationId().equals(receipt.sourceOperationId())
        || !binding.sourceEvidenceDigest().equals(receipt.sourceEvidenceDigest())
        || !binding.intakeReceiptDigest().equals(receipt.receiptDigest())) {
      throw new ConflictException(
          "World publication request differs from the complete committed authored-source intake");
    }
  }

  private void requireExactVersionIdentity(
      Record identity, OwnerBinding binding, WorldAuthoredSourceIntakeReceipt receipt) {
    if (!Objects.equals(
            identity.get(VERSION_OPERATION_ID, UUID.class), binding.versionIdentityOperationId())
        || !Objects.equals(identity.get(TARGET_NAMESPACE, String.class), binding.targetNamespace())
        || !Objects.equals(
            identity.get(CANONICAL_TENANT_ID, UUID.class), binding.canonicalTenantId())
        || !Objects.equals(
            identity.get(VERSION_CANONICAL_VERSION_ID, UUID.class), binding.canonicalVersionId())
        || !Objects.equals(
            identity.get(VERSION_GAME_DESIGN_VERSION_ID, Long.class), binding.gameDesignVersionId())
        || !Objects.equals(identity.get(VERSION_WORLD_SLUG, String.class), receipt.worldSlug())
        || !Objects.equals(
            identity.get(VERSION_INTAKE_OPERATION_ID, UUID.class), binding.intakeOperationId())
        || !Objects.equals(
            identity.get(VERSION_INTAKE_REQUEST_ID, UUID.class), binding.intakeRequestId())
        || !Objects.equals(
            identity.get(VERSION_LOCAL_TENANT_KEY, Long.class), receipt.localTenantKey())
        || !Objects.equals(
            identity.get(VERSION_INTAKE_REQUEST_DIGEST, String.class),
            binding.intakeRequestDigest())
        || !Objects.equals(
            identity.get(VERSION_SOURCE_OPERATION_ID, UUID.class), binding.sourceOperationId())
        || !Objects.equals(
            identity.get(VERSION_SOURCE_EVIDENCE_DIGEST, String.class),
            binding.sourceEvidenceDigest())
        || !Objects.equals(
            identity.get(VERSION_INTAKE_RECEIPT_DIGEST, String.class),
            binding.intakeReceiptDigest())) {
      throw new ConflictException(
          "World publication request differs from the exact V27 Version and intake binding");
    }
  }

  private Record createAndLockOwner(ResolvedVersion resolvedVersion) {
    OwnerBinding binding = resolvedVersion.binding();
    WorldAuthoredSourceIntakeReceipt receipt = resolvedVersion.receipt();
    dsl.insertInto(OWNER)
        .set(TARGET_NAMESPACE, binding.targetNamespace())
        .set(CANONICAL_TENANT_ID, binding.canonicalTenantId())
        .set(LOCAL_TENANT_KEY, receipt.localTenantKey())
        .set(VERSION_ID, resolvedVersion.localVersionKey())
        .set(OWNER_FREEZE_PHASE, OPEN)
        .onConflictDoNothing()
        .execute();

    Record owner =
        dsl.selectFrom(OWNER)
            .where(
                TARGET_NAMESPACE
                    .eq(binding.targetNamespace())
                    .and(CANONICAL_TENANT_ID.eq(binding.canonicalTenantId()))
                    .and(VERSION_ID.eq(resolvedVersion.localVersionKey())))
            .forUpdate()
            .fetchOne();
    if (owner == null) {
      throw new ConflictException("World version owner row could not be created or locked");
    }
    return owner;
  }

  private void requireMatchingOwnerBinding(Record owner, ResolvedVersion resolvedVersion) {
    OwnerBinding binding = resolvedVersion.binding();
    WorldAuthoredSourceIntakeReceipt receipt = resolvedVersion.receipt();
    if (!Objects.equals(owner.get(TARGET_NAMESPACE, String.class), binding.targetNamespace())
        || !Objects.equals(owner.get(CANONICAL_TENANT_ID, UUID.class), binding.canonicalTenantId())
        || !Objects.equals(owner.get(LOCAL_TENANT_KEY, Long.class), receipt.localTenantKey())
        || !Objects.equals(owner.get(VERSION_ID, Long.class), resolvedVersion.localVersionKey())) {
      throw new ConflictException(
          "World version owner row is bound to a different canonical tenant association");
    }
  }

  private int insertAttempt(
      WorldDesignPublicationFenceEvidence evidence,
      ResolvedVersion resolvedVersion,
      UUID publicationFence,
      Checkpoint checkpoint) {
    OwnerBinding binding = resolvedVersion.binding();
    WorldAuthoredSourceIntakeReceipt receipt = resolvedVersion.receipt();
    return dsl.insertInto(ATTEMPT)
        .set(PUBLICATION_FENCE, publicationFence)
        .set(TARGET_NAMESPACE, binding.targetNamespace())
        .set(CANONICAL_TENANT_ID, binding.canonicalTenantId())
        .set(LOCAL_TENANT_KEY, receipt.localTenantKey())
        .set(VERSION_ID, resolvedVersion.localVersionKey())
        .set(OWNER_BINDING_SCHEMA_VERSION, (short) 1)
        .set(CANONICAL_VERSION_ID, binding.canonicalVersionId())
        .set(VERSION_IDENTITY_OPERATION_ID, binding.versionIdentityOperationId())
        .set(GAME_DESIGN_VERSION_ID, binding.gameDesignVersionId())
        .set(INTAKE_OPERATION_ID, receipt.operationId())
        .set(INTAKE_REQUEST_ID, receipt.intakeRequestId())
        .set(INTAKE_REQUEST_DIGEST, receipt.requestDigest())
        .set(SOURCE_OPERATION_ID, receipt.sourceOperationId())
        .set(SOURCE_EVIDENCE_DIGEST, receipt.sourceEvidenceDigest())
        .set(INTAKE_RECEIPT_DIGEST, receipt.receiptDigest())
        .set(PUBLICATION_REQUEST_ID, evidence.publicationRequestId())
        .set(REQUEST_DIGEST, evidence.requestDigest())
        .set(VERSION_STATE_EPOCH, evidence.versionStateEpoch())
        .set(PUBLISH_WORKFLOW_ID, evidence.publishWorkflowId())
        .set(APPLIED_COMMIT_ID, checkpoint.appliedCommitId())
        .set(CONTENT_DIGEST, checkpoint.contentDigest())
        .set(DIGEST_SCHEMA_VERSION, checkpoint.digestSchemaVersion())
        .execute();
  }

  private FrozenAttempt findAttemptByFence(UUID publicationFence) {
    Record record =
        dsl.selectFrom(ATTEMPT).where(PUBLICATION_FENCE.eq(publicationFence)).fetchOne();
    return record == null ? null : toFrozenAttempt(record);
  }

  private FrozenAttempt findAttemptByRequest(
      WorldDesignPublicationFenceEvidence evidence, long localVersionKey) {
    Record record =
        dsl.selectFrom(ATTEMPT)
            .where(
                TARGET_NAMESPACE
                    .eq(evidence.targetNamespace())
                    .and(CANONICAL_TENANT_ID.eq(evidence.canonicalTenantId()))
                    .and(VERSION_ID.eq(localVersionKey))
                    .and(PUBLICATION_REQUEST_ID.eq(evidence.publicationRequestId())))
            .fetchOne();
    return record == null ? null : toFrozenAttempt(record);
  }

  private void rejectLegacyAttempt(
      WorldDesignPublicationFenceEvidence evidence, long localVersionKey) {
    Record legacyCandidate =
        dsl.selectFrom(ATTEMPT)
            .where(
                TARGET_NAMESPACE
                    .eq(evidence.targetNamespace())
                    .and(CANONICAL_TENANT_ID.eq(evidence.canonicalTenantId()))
                    .and(VERSION_ID.eq(localVersionKey))
                    .and(PUBLICATION_REQUEST_ID.eq(evidence.publicationRequestId())))
            .fetchOne();
    if (legacyCandidate != null
        && !Short.valueOf((short) 1)
            .equals(legacyCandidate.get(OWNER_BINDING_SCHEMA_VERSION, Short.class))) {
      throw new ConflictException(
          "Legacy schema-0 World publication attempts are unqualified and cannot be promoted");
    }
  }

  private FrozenAttempt toFrozenAttempt(Record record) {
    Short ownerBindingSchemaVersion = record.get(OWNER_BINDING_SCHEMA_VERSION, Short.class);
    if (!Short.valueOf((short) 1).equals(ownerBindingSchemaVersion)) {
      throw new ConflictException(
          "Legacy schema-0 World publication attempts are unqualified and cannot be promoted");
    }
    WorldDesignPublicationFenceEvidence request =
        new WorldDesignPublicationFenceEvidence(
            record.get(TARGET_NAMESPACE),
            record.get(CANONICAL_TENANT_ID),
            record.get(CANONICAL_VERSION_ID),
            record.get(VERSION_IDENTITY_OPERATION_ID),
            record.get(GAME_DESIGN_VERSION_ID),
            record.get(INTAKE_REQUEST_ID),
            record.get(INTAKE_OPERATION_ID),
            record.get(INTAKE_REQUEST_DIGEST),
            record.get(SOURCE_OPERATION_ID),
            record.get(SOURCE_EVIDENCE_DIGEST),
            record.get(INTAKE_RECEIPT_DIGEST),
            record.get(PUBLICATION_REQUEST_ID),
            record.get(REQUEST_DIGEST),
            record.get(VERSION_STATE_EPOCH),
            record.get(PUBLISH_WORKFLOW_ID));
    Checkpoint checkpoint =
        new Checkpoint(
            record.get(APPLIED_COMMIT_ID),
            record.get(CONTENT_DIGEST),
            record.get(DIGEST_SCHEMA_VERSION));
    return new FrozenAttempt(request, record.get(PUBLICATION_FENCE), checkpoint);
  }

  private void requireExactRequest(
      FrozenAttempt stored, WorldDesignPublicationFenceEvidence supplied) {
    if (!stored.request().equals(supplied)) {
      throw new ConflictException(
          "World publication request identity was reused with changed source or request binding");
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World publication fence requires an active owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "World publication fence requires a writable owner transaction");
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (declaredIsolation != null && declaredIsolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException("World publication fence requires READ COMMITTED isolation");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equalsIgnoreCase(settings.get("transaction_isolation", String.class))
        || !"off".equalsIgnoreCase(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "World publication fence requires a writable READ COMMITTED owner transaction");
    }
  }

  private UUID newNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (NIL_UUID.equals(value));
    return value;
  }

  private record ResolvedVersion(
      OwnerBinding binding, WorldAuthoredSourceIntakeReceipt receipt, long localVersionKey) {}

  /** Exact input conflicts fail closed without changing either owner or attempt history. */
  public static class ConflictException extends IllegalStateException {
    public ConflictException(String message) {
      super(message);
    }
  }

  /** No owner state may be created for a missing or not-yet-committed authored-source intake. */
  public static final class MissingIntakeException extends IllegalStateException {
    public MissingIntakeException(String message) {
      super(message);
    }
  }

  /** No owner state may be created for a missing V27 source-qualified Version identity. */
  public static final class MissingVersionIdentityException extends ConflictException {
    public MissingVersionIdentityException(String message) {
      super(message);
    }
  }
}
