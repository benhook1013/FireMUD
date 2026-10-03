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
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<Long> LOCAL_TENANT_KEY =
      DSL.field(DSL.name("local_tenant_key"), Long.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
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
    requireWritableReadCommittedOwnerTransaction();
    ResolvedIntake intake = resolveCommittedIntake(binding);
    Record owner = createAndLockOwner(binding, intake);
    requireMatchingOwnerBinding(owner, binding, intake);
    if (!OPEN.equals(owner.get(OWNER_FREEZE_PHASE, String.class))) {
      throw new ConflictException("World version is not open for an ordinary Draft mutation");
    }
    if (owner.get(CURRENT_PUBLICATION_FENCE, UUID.class) != null) {
      throw new ConflictException("Open World version unexpectedly retains a publication fence");
    }
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
    ResolvedIntake intake = resolveCommittedIntake(binding);
    Record owner = createAndLockOwner(binding, intake);
    requireMatchingOwnerBinding(owner, binding, intake);

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
    if (findAttemptByRequest(evidence) != null) {
      throw new ConflictException("World publication request already has an immutable attempt");
    }

    Checkpoint checkpoint =
        Objects.requireNonNull(
            checkpointSupplier.get(), "checkpointSupplier returned no complete World checkpoint");
    UUID publicationFence = newNonNilUuid();
    int inserted = insertAttempt(evidence, intake, publicationFence, checkpoint);
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
                    .and(VERSION_ID.eq(evidence.versionId()))
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
    resolveCommittedIntake(evidence.ownerBinding());
    FrozenAttempt stored = findAttemptByRequest(evidence);
    if (stored == null) {
      return Optional.empty();
    }
    requireExactRequest(stored, evidence);
    return Optional.of(stored);
  }

  private ResolvedIntake resolveCommittedIntake(OwnerBinding binding) {
    Objects.requireNonNull(binding, "binding");
    WorldAuthoredSourceIntakeReceipt receipt =
        intakeRepository
            .read(binding.targetNamespace(), binding.intakeRequestId())
            .orElseThrow(
                () ->
                    new MissingIntakeException(
                        "World publication fence requires an already committed authored-source intake"));
    if (!binding.targetNamespace().equals(receipt.targetNamespace())
        || !binding.canonicalTenantId().equals(receipt.canonicalTenantId())
        || !binding.intakeRequestId().equals(receipt.intakeRequestId())
        || !binding.intakeOperationId().equals(receipt.operationId())
        || !binding.sourceOperationId().equals(receipt.sourceOperationId())
        || !binding.sourceEvidenceDigest().equals(receipt.sourceEvidenceDigest())) {
      throw new ConflictException(
          "World publication request differs from the complete committed authored-source intake");
    }
    return new ResolvedIntake(receipt);
  }

  private Record createAndLockOwner(OwnerBinding binding, ResolvedIntake intake) {
    WorldAuthoredSourceIntakeReceipt receipt = intake.receipt();
    dsl.insertInto(OWNER)
        .set(TARGET_NAMESPACE, binding.targetNamespace())
        .set(CANONICAL_TENANT_ID, binding.canonicalTenantId())
        .set(LOCAL_TENANT_KEY, receipt.localTenantKey())
        .set(VERSION_ID, binding.versionId())
        .set(OWNER_FREEZE_PHASE, OPEN)
        .onConflictDoNothing()
        .execute();

    Record owner =
        dsl.selectFrom(OWNER)
            .where(
                TARGET_NAMESPACE
                    .eq(binding.targetNamespace())
                    .and(CANONICAL_TENANT_ID.eq(binding.canonicalTenantId()))
                    .and(VERSION_ID.eq(binding.versionId())))
            .forUpdate()
            .fetchOne();
    if (owner == null) {
      throw new ConflictException("World version owner row could not be created or locked");
    }
    return owner;
  }

  private void requireMatchingOwnerBinding(
      Record owner, OwnerBinding binding, ResolvedIntake intake) {
    WorldAuthoredSourceIntakeReceipt receipt = intake.receipt();
    if (!Objects.equals(owner.get(TARGET_NAMESPACE, String.class), binding.targetNamespace())
        || !Objects.equals(owner.get(CANONICAL_TENANT_ID, UUID.class), binding.canonicalTenantId())
        || !Objects.equals(owner.get(LOCAL_TENANT_KEY, Long.class), receipt.localTenantKey())
        || !Objects.equals(owner.get(VERSION_ID, Long.class), binding.versionId())) {
      throw new ConflictException(
          "World version owner row is bound to a different canonical tenant association");
    }
  }

  private int insertAttempt(
      WorldDesignPublicationFenceEvidence evidence,
      ResolvedIntake intake,
      UUID publicationFence,
      Checkpoint checkpoint) {
    WorldAuthoredSourceIntakeReceipt receipt = intake.receipt();
    return dsl.insertInto(ATTEMPT)
        .set(PUBLICATION_FENCE, publicationFence)
        .set(TARGET_NAMESPACE, evidence.targetNamespace())
        .set(CANONICAL_TENANT_ID, evidence.canonicalTenantId())
        .set(LOCAL_TENANT_KEY, receipt.localTenantKey())
        .set(VERSION_ID, evidence.versionId())
        .set(INTAKE_OPERATION_ID, receipt.operationId())
        .set(INTAKE_REQUEST_ID, receipt.intakeRequestId())
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

  private FrozenAttempt findAttemptByRequest(WorldDesignPublicationFenceEvidence evidence) {
    Record record =
        dsl.selectFrom(ATTEMPT)
            .where(
                TARGET_NAMESPACE
                    .eq(evidence.targetNamespace())
                    .and(CANONICAL_TENANT_ID.eq(evidence.canonicalTenantId()))
                    .and(VERSION_ID.eq(evidence.versionId()))
                    .and(PUBLICATION_REQUEST_ID.eq(evidence.publicationRequestId())))
            .fetchOne();
    return record == null ? null : toFrozenAttempt(record);
  }

  private FrozenAttempt toFrozenAttempt(Record record) {
    WorldDesignPublicationFenceEvidence request =
        new WorldDesignPublicationFenceEvidence(
            record.get(TARGET_NAMESPACE),
            record.get(CANONICAL_TENANT_ID),
            record.get(VERSION_ID),
            record.get(INTAKE_REQUEST_ID),
            record.get(INTAKE_OPERATION_ID),
            record.get(SOURCE_OPERATION_ID),
            record.get(SOURCE_EVIDENCE_DIGEST),
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

  private record ResolvedIntake(WorldAuthoredSourceIntakeReceipt receipt) {}

  /** Exact input conflicts fail closed without changing either owner or attempt history. */
  public static final class ConflictException extends IllegalStateException {
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
}
