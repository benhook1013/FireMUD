package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnerCommitProofStatus;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-private persistence for immutable World authored graph captures. */
@Repository
public class WorldAuthoredGraphSnapshotRepository {
  private static final String FROZEN = "FROZEN";
  private static final Set<String> RETAINED_FENCE_PHASES =
      Set.of(FROZEN, "PUBLISHED", "ABORTED", "RECONCILIATION_REQUIRED");
  private static final String CAPTURED_UNVERIFIED = "CAPTURED_UNVERIFIED";

  private static final Table<?> SNAPSHOT = DSL.table(DSL.name("world_authored_graph_snapshot"));
  private static final Table<?> VERSION_IDENTITY =
      DSL.table(DSL.name("world_authored_version_identity"));
  private static final Table<?> SOURCE_INTAKE = DSL.table(DSL.name("world_authored_source_intake"));
  private static final Table<?> FENCE_OWNER =
      DSL.table(DSL.name("world_design_publication_fence_owner"));
  private static final Table<?> FENCE_ATTEMPT =
      DSL.table(DSL.name("world_design_publication_fence_attempt"));

  private static final Field<UUID> SNAPSHOT_ID = field("snapshot_id", UUID.class);
  private static final Field<String> CAPTURE_REQUEST_DIGEST =
      field("capture_request_digest", String.class);
  private static final Field<UUID> PUBLICATION_FENCE = field("publication_fence", UUID.class);
  private static final Field<String> TARGET_NAMESPACE = field("target_namespace", String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID = field("canonical_tenant_id", UUID.class);
  private static final Field<UUID> CANONICAL_VERSION_ID = field("canonical_version_id", UUID.class);
  private static final Field<UUID> VERSION_IDENTITY_OPERATION_ID =
      field("version_identity_operation_id", UUID.class);
  private static final Field<String> WORLD_SLUG = field("world_slug", String.class);
  private static final Field<Long> GAME_DESIGN_VERSION_ID =
      field("game_design_version_id", Long.class);
  private static final Field<Long> LOCAL_VERSION_KEY = field("local_version_key", Long.class);
  private static final Field<Long> LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<Short> OWNER_BINDING_SCHEMA_VERSION =
      field("owner_binding_schema_version", Short.class);
  private static final Field<UUID> INTAKE_OPERATION_ID = field("intake_operation_id", UUID.class);
  private static final Field<UUID> INTAKE_REQUEST_ID = field("intake_request_id", UUID.class);
  private static final Field<String> INTAKE_REQUEST_DIGEST =
      field("intake_request_digest", String.class);
  private static final Field<UUID> SOURCE_OPERATION_ID = field("source_operation_id", UUID.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> INTAKE_RECEIPT_DIGEST =
      field("intake_receipt_digest", String.class);
  private static final Field<String> PUBLICATION_REQUEST_ID =
      field("publication_request_id", String.class);
  private static final Field<String> REQUEST_DIGEST = field("request_digest", String.class);
  private static final Field<Long> VERSION_STATE_EPOCH = field("version_state_epoch", Long.class);
  private static final Field<String> PUBLISH_WORKFLOW_ID =
      field("publish_workflow_id", String.class);
  private static final Field<String> APPLIED_COMMIT_ID = field("applied_commit_id", String.class);
  private static final Field<String> CONTENT_DIGEST = field("content_digest", String.class);
  private static final Field<Integer> DIGEST_SCHEMA_VERSION =
      field("digest_schema_version", Integer.class);
  private static final Field<String> SUPPLIED_TUPLES_JSON =
      field("supplied_owned_affected_tuples_json", String.class);
  private static final Field<String> OWNER_REVISION_EVIDENCE_JSON =
      field("owner_revision_evidence_json", String.class);
  private static final Field<String> OWNER_COMMIT_PROOF_STATUS =
      field("owner_commit_proof_status", String.class);
  private static final Field<byte[]> GRAPH_BYTES = field("graph_bytes", byte[].class);
  private static final Field<String> GRAPH_SHA256 = field("graph_sha256", String.class);

  private static final Field<UUID> VERSION_OPERATION_ID = field("operation_id", UUID.class);
  private static final Field<String> VERSION_TARGET_NAMESPACE =
      field("target_namespace", String.class);
  private static final Field<UUID> VERSION_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<UUID> VERSION_CANONICAL_VERSION_ID =
      field("canonical_version_id", UUID.class);
  private static final Field<String> VERSION_WORLD_SLUG = field("world_slug", String.class);
  private static final Field<Long> VERSION_GAME_DESIGN_VERSION_ID =
      field("game_design_version_id", Long.class);
  private static final Field<Long> VERSION_LOCAL_VERSION_KEY =
      field("local_version_key", Long.class);
  private static final Field<Long> VERSION_LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<UUID> VERSION_INTAKE_OPERATION_ID =
      field("intake_operation_id", UUID.class);
  private static final Field<UUID> VERSION_INTAKE_REQUEST_ID =
      field("intake_request_id", UUID.class);
  private static final Field<String> VERSION_INTAKE_REQUEST_DIGEST =
      field("intake_request_digest", String.class);
  private static final Field<UUID> VERSION_SOURCE_OPERATION_ID =
      field("source_operation_id", UUID.class);
  private static final Field<String> VERSION_SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> VERSION_INTAKE_RECEIPT_DIGEST =
      field("intake_receipt_digest", String.class);

  private static final Field<UUID> INTAKE_OPERATION = field("operation_id", UUID.class);
  private static final Field<String> INTAKE_TARGET_NAMESPACE =
      field("target_namespace", String.class);
  private static final Field<UUID> INTAKE_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<Long> INTAKE_LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<UUID> INTAKE_REQUEST = field("intake_request_id", UUID.class);
  private static final Field<UUID> INTAKE_SOURCE_OPERATION_ID =
      field("source_operation_id", UUID.class);
  private static final Field<String> INTAKE_SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> INTAKE_RECEIPT = field("receipt_digest", String.class);
  private static final Field<String> INTAKE_WORLD_SLUG = field("world_slug", String.class);

  private static final Field<String> OWNER_TARGET_NAMESPACE =
      field("target_namespace", String.class);
  private static final Field<UUID> OWNER_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<Long> OWNER_LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<Long> OWNER_VERSION_ID = field("version_id", Long.class);
  private static final Field<String> OWNER_FREEZE_PHASE = field("owner_freeze_phase", String.class);
  private static final Field<UUID> OWNER_CURRENT_FENCE =
      field("current_publication_fence", UUID.class);

  private static final Field<UUID> ATTEMPT_FENCE = field("publication_fence", UUID.class);
  private static final Field<String> ATTEMPT_TARGET_NAMESPACE =
      field("target_namespace", String.class);
  private static final Field<UUID> ATTEMPT_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<Long> ATTEMPT_LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<Long> ATTEMPT_VERSION_ID = field("version_id", Long.class);
  private static final Field<Short> ATTEMPT_OWNER_BINDING_SCHEMA_VERSION =
      field("owner_binding_schema_version", Short.class);
  private static final Field<UUID> ATTEMPT_CANONICAL_VERSION_ID =
      field("canonical_version_id", UUID.class);
  private static final Field<UUID> ATTEMPT_VERSION_IDENTITY_OPERATION_ID =
      field("version_identity_operation_id", UUID.class);
  private static final Field<Long> ATTEMPT_GAME_DESIGN_VERSION_ID =
      field("game_design_version_id", Long.class);
  private static final Field<UUID> ATTEMPT_INTAKE_OPERATION_ID =
      field("intake_operation_id", UUID.class);
  private static final Field<UUID> ATTEMPT_INTAKE_REQUEST_ID =
      field("intake_request_id", UUID.class);
  private static final Field<String> ATTEMPT_INTAKE_REQUEST_DIGEST =
      field("intake_request_digest", String.class);
  private static final Field<UUID> ATTEMPT_SOURCE_OPERATION_ID =
      field("source_operation_id", UUID.class);
  private static final Field<String> ATTEMPT_SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> ATTEMPT_INTAKE_RECEIPT_DIGEST =
      field("intake_receipt_digest", String.class);
  private static final Field<String> ATTEMPT_PUBLICATION_REQUEST_ID =
      field("publication_request_id", String.class);
  private static final Field<String> ATTEMPT_REQUEST_DIGEST = field("request_digest", String.class);
  private static final Field<Long> ATTEMPT_VERSION_STATE_EPOCH =
      field("version_state_epoch", Long.class);
  private static final Field<String> ATTEMPT_PUBLISH_WORKFLOW_ID =
      field("publish_workflow_id", String.class);
  private static final Field<String> ATTEMPT_APPLIED_COMMIT_ID =
      field("applied_commit_id", String.class);
  private static final Field<String> ATTEMPT_CONTENT_DIGEST = field("content_digest", String.class);
  private static final Field<Integer> ATTEMPT_DIGEST_SCHEMA_VERSION =
      field("digest_schema_version", Integer.class);

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Injected DSLContext is an internal Spring collaborator; construction acquires no resources and this repository has no finalizer.")
  public WorldAuthoredGraphSnapshotRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
  }

  /** Resolves all private keys and locks the existing V25 FROZEN owner row. */
  @Transactional(propagation = Propagation.MANDATORY)
  public OwnerProvenance lockAndResolve(CaptureRequest request) {
    requireWritableReadCommittedOwnerTransaction();
    Record version =
        dsl.selectFrom(VERSION_IDENTITY)
            .where(
                VERSION_TARGET_NAMESPACE
                    .eq(request.targetNamespace())
                    .and(VERSION_CANONICAL_TENANT_ID.eq(request.canonicalTenantId()))
                    .and(VERSION_CANONICAL_VERSION_ID.eq(request.canonicalVersionId()))
                    .and(VERSION_INTAKE_REQUEST_ID.eq(request.intakeRequestId())))
            .forShare()
            .fetchOne();
    if (version == null) {
      throw new MissingOwnerHistoryException(
          "World graph capture requires an exact retained V27 authored-Version identity");
    }
    Record intake =
        dsl.selectFrom(SOURCE_INTAKE)
            .where(
                INTAKE_TARGET_NAMESPACE
                    .eq(request.targetNamespace())
                    .and(INTAKE_REQUEST.eq(request.intakeRequestId())))
            .forShare()
            .fetchOne();
    if (intake == null) {
      throw new MissingOwnerHistoryException(
          "World graph capture requires the exact retained authored-source intake");
    }
    Record owner =
        dsl.selectFrom(FENCE_OWNER)
            .where(
                OWNER_TARGET_NAMESPACE
                    .eq(request.targetNamespace())
                    .and(OWNER_CANONICAL_TENANT_ID.eq(request.canonicalTenantId()))
                    .and(OWNER_VERSION_ID.eq(required(version, VERSION_LOCAL_VERSION_KEY))))
            .forUpdate()
            .fetchOne();
    if (owner == null) {
      throw new MissingOwnerHistoryException(
          "World graph capture requires an existing V25 shared publication-fence owner row");
    }
    String ownerFreezePhase = owner.get(OWNER_FREEZE_PHASE, String.class);
    OwnerProvenance provenance = toProvenance(version, ownerFreezePhase);
    requireExactIntake(intake, request, provenance);
    if (!Objects.equals(owner.get(OWNER_LOCAL_TENANT_KEY), provenance.localTenantKey())
        || !RETAINED_FENCE_PHASES.contains(ownerFreezePhase)
        || !request.publicationFence().equals(owner.get(OWNER_CURRENT_FENCE, UUID.class))) {
      throw new SnapshotConflictException(
          "World graph capture requires the exact retained V25 owner binding");
    }

    Record attempt =
        dsl.selectFrom(FENCE_ATTEMPT)
            .where(
                ATTEMPT_FENCE
                    .eq(request.publicationFence())
                    .and(ATTEMPT_TARGET_NAMESPACE.eq(request.targetNamespace()))
                    .and(ATTEMPT_CANONICAL_TENANT_ID.eq(request.canonicalTenantId()))
                    .and(ATTEMPT_LOCAL_TENANT_KEY.eq(provenance.localTenantKey()))
                    .and(ATTEMPT_VERSION_ID.eq(provenance.localVersionKey())))
            .forShare()
            .fetchOne();
    if (attempt == null) {
      throw new MissingOwnerHistoryException(
          "World graph capture requires the immutable V25 publication attempt");
    }
    requireExactAttempt(attempt, request, provenance);
    return provenance;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public WorldAuthoredGraphSnapshot findByFence(UUID publicationFence) {
    Record row =
        dsl.selectFrom(SNAPSHOT)
            .where(PUBLICATION_FENCE.eq(Objects.requireNonNull(publicationFence)))
            .fetchOne();
    return row == null ? null : toSnapshot(row);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public WorldAuthoredGraphSnapshot insertAndReadback(WorldAuthoredGraphSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    int inserted =
        dsl.insertInto(SNAPSHOT)
            .set(SNAPSHOT_ID, snapshot.snapshotId())
            .set(CAPTURE_REQUEST_DIGEST, snapshot.captureRequestDigest())
            .set(PUBLICATION_FENCE, snapshot.publicationFence())
            .set(TARGET_NAMESPACE, snapshot.targetNamespace())
            .set(CANONICAL_TENANT_ID, snapshot.canonicalTenantId())
            .set(CANONICAL_VERSION_ID, snapshot.canonicalVersionId())
            .set(VERSION_IDENTITY_OPERATION_ID, snapshot.versionIdentityOperationId())
            .set(WORLD_SLUG, snapshot.worldSlug())
            .set(GAME_DESIGN_VERSION_ID, snapshot.gameDesignVersionId())
            .set(LOCAL_VERSION_KEY, snapshot.localVersionKey())
            .set(LOCAL_TENANT_KEY, snapshot.localTenantKey())
            .set(OWNER_BINDING_SCHEMA_VERSION, snapshot.ownerBindingSchemaVersion())
            .set(INTAKE_OPERATION_ID, snapshot.intakeOperationId())
            .set(INTAKE_REQUEST_ID, snapshot.intakeRequestId())
            .set(INTAKE_REQUEST_DIGEST, snapshot.intakeRequestDigest())
            .set(SOURCE_OPERATION_ID, snapshot.sourceOperationId())
            .set(SOURCE_EVIDENCE_DIGEST, snapshot.sourceEvidenceDigest())
            .set(INTAKE_RECEIPT_DIGEST, snapshot.intakeReceiptDigest())
            .set(PUBLICATION_REQUEST_ID, snapshot.publicationRequestId())
            .set(REQUEST_DIGEST, snapshot.requestDigest())
            .set(VERSION_STATE_EPOCH, snapshot.versionStateEpoch())
            .set(PUBLISH_WORKFLOW_ID, snapshot.publishWorkflowId())
            .set(APPLIED_COMMIT_ID, snapshot.appliedCommitId())
            .set(CONTENT_DIGEST, snapshot.contentDigest())
            .set(DIGEST_SCHEMA_VERSION, snapshot.digestSchemaVersion())
            .set(SUPPLIED_TUPLES_JSON, snapshot.suppliedOwnedAffectedTuplesJson())
            .set(OWNER_REVISION_EVIDENCE_JSON, snapshot.ownerRevisionEvidenceJson())
            .set(OWNER_COMMIT_PROOF_STATUS, CAPTURED_UNVERIFIED)
            .set(GRAPH_BYTES, snapshot.graphBytes())
            .set(GRAPH_SHA256, snapshot.graphSha256())
            .execute();
    if (inserted != 1) {
      throw new SnapshotConflictException("World authored graph snapshot was not inserted");
    }
    Record row = dsl.selectFrom(SNAPSHOT).where(SNAPSHOT_ID.eq(snapshot.snapshotId())).fetchOne();
    if (row == null) {
      throw new SnapshotConflictException(
          "World authored graph snapshot disappeared before immutable readback");
    }
    WorldAuthoredGraphSnapshot stored = toSnapshot(row);
    requireSameSnapshot(snapshot, stored);
    return stored;
  }

  public void requireSameRequest(
      WorldAuthoredGraphSnapshot snapshot,
      CaptureRequest request,
      OwnerProvenance provenance,
      String captureRequestDigest,
      String tuplesJson) {
    if (!snapshot.targetNamespace().equals(request.targetNamespace())
        || !snapshot.canonicalTenantId().equals(request.canonicalTenantId())
        || !snapshot.canonicalVersionId().equals(request.canonicalVersionId())
        || !snapshot.versionIdentityOperationId().equals(provenance.versionIdentityOperationId())
        || !snapshot.worldSlug().equals(provenance.worldSlug())
        || snapshot.gameDesignVersionId() != provenance.gameDesignVersionId()
        || snapshot.localVersionKey() != provenance.localVersionKey()
        || snapshot.localTenantKey() != provenance.localTenantKey()
        || snapshot.ownerBindingSchemaVersion() != 1
        || !snapshot.intakeOperationId().equals(provenance.intakeOperationId())
        || !snapshot.intakeRequestId().equals(request.intakeRequestId())
        || !snapshot.intakeRequestDigest().equals(provenance.intakeRequestDigest())
        || !snapshot.sourceOperationId().equals(provenance.sourceOperationId())
        || !snapshot.sourceEvidenceDigest().equals(provenance.sourceEvidenceDigest())
        || !snapshot.intakeReceiptDigest().equals(provenance.intakeReceiptDigest())
        || !snapshot.publicationFence().equals(request.publicationFence())
        || !snapshot.publicationRequestId().equals(request.publicationRequestId())
        || !snapshot.requestDigest().equals(request.requestDigest())
        || snapshot.versionStateEpoch() != request.versionStateEpoch()
        || !snapshot.publishWorkflowId().equals(request.publishWorkflowId())
        || !snapshot.appliedCommitId().equals(request.appliedCommitId())
        || !snapshot.contentDigest().equals(request.contentDigest())
        || snapshot.digestSchemaVersion() != request.digestSchemaVersion()
        || !snapshot.captureRequestDigest().equals(captureRequestDigest)
        || !snapshot.suppliedOwnedAffectedTuplesJson().equals(tuplesJson)) {
      throw new SnapshotConflictException(
          "World graph capture identity was reused with changed source or request binding");
    }
    verifyGraphHash(snapshot);
  }

  private OwnerProvenance toProvenance(Record version, String ownerFreezePhase) {
    return new OwnerProvenance(
        required(version, VERSION_OPERATION_ID),
        required(version, VERSION_TARGET_NAMESPACE),
        required(version, VERSION_CANONICAL_TENANT_ID),
        required(version, VERSION_CANONICAL_VERSION_ID),
        required(version, VERSION_WORLD_SLUG),
        required(version, VERSION_GAME_DESIGN_VERSION_ID),
        required(version, VERSION_LOCAL_VERSION_KEY),
        required(version, VERSION_LOCAL_TENANT_KEY),
        required(version, VERSION_INTAKE_OPERATION_ID),
        required(version, VERSION_INTAKE_REQUEST_ID),
        required(version, VERSION_INTAKE_REQUEST_DIGEST),
        required(version, VERSION_SOURCE_OPERATION_ID),
        required(version, VERSION_SOURCE_EVIDENCE_DIGEST),
        required(version, VERSION_INTAKE_RECEIPT_DIGEST),
        ownerFreezePhase);
  }

  private void requireExactIntake(
      Record intake, CaptureRequest request, OwnerProvenance provenance) {
    if (!request.targetNamespace().equals(intake.get(INTAKE_TARGET_NAMESPACE, String.class))
        || !request.canonicalTenantId().equals(intake.get(INTAKE_CANONICAL_TENANT_ID, UUID.class))
        || !request.intakeRequestId().equals(intake.get(INTAKE_REQUEST, UUID.class))
        || !provenance.intakeOperationId().equals(intake.get(INTAKE_OPERATION, UUID.class))
        || !provenance.intakeRequestDigest().equals(intake.get("request_digest", String.class))
        || !Objects.equals(
            provenance.localTenantKey(), intake.get(INTAKE_LOCAL_TENANT_KEY, Long.class))
        || !provenance
            .sourceOperationId()
            .equals(intake.get(INTAKE_SOURCE_OPERATION_ID, UUID.class))
        || !provenance
            .sourceEvidenceDigest()
            .equals(intake.get(INTAKE_SOURCE_EVIDENCE_DIGEST, String.class))
        || !provenance.intakeReceiptDigest().equals(intake.get(INTAKE_RECEIPT, String.class))
        || !provenance.worldSlug().equals(intake.get(INTAKE_WORLD_SLUG, String.class))) {
      throw new SnapshotConflictException(
          "World Version provenance differs from its exact authored-source intake");
    }
  }

  private void requireExactAttempt(
      Record attempt, CaptureRequest request, OwnerProvenance provenance) {
    if (!request.publicationFence().equals(attempt.get(ATTEMPT_FENCE, UUID.class))
        || !request.targetNamespace().equals(attempt.get(ATTEMPT_TARGET_NAMESPACE, String.class))
        || !request.canonicalTenantId().equals(attempt.get(ATTEMPT_CANONICAL_TENANT_ID, UUID.class))
        || !Objects.equals(
            provenance.localTenantKey(), attempt.get(ATTEMPT_LOCAL_TENANT_KEY, Long.class))
        || !Objects.equals(
            provenance.localVersionKey(), attempt.get(ATTEMPT_VERSION_ID, Long.class))
        || !Short.valueOf((short) 1)
            .equals(attempt.get(ATTEMPT_OWNER_BINDING_SCHEMA_VERSION, Short.class))
        || !request
            .canonicalVersionId()
            .equals(attempt.get(ATTEMPT_CANONICAL_VERSION_ID, UUID.class))
        || !provenance
            .versionIdentityOperationId()
            .equals(attempt.get(ATTEMPT_VERSION_IDENTITY_OPERATION_ID, UUID.class))
        || !Objects.equals(
            provenance.gameDesignVersionId(),
            attempt.get(ATTEMPT_GAME_DESIGN_VERSION_ID, Long.class))
        || !provenance
            .intakeOperationId()
            .equals(attempt.get(ATTEMPT_INTAKE_OPERATION_ID, UUID.class))
        || !request.intakeRequestId().equals(attempt.get(ATTEMPT_INTAKE_REQUEST_ID, UUID.class))
        || !provenance
            .intakeRequestDigest()
            .equals(attempt.get(ATTEMPT_INTAKE_REQUEST_DIGEST, String.class))
        || !provenance
            .sourceOperationId()
            .equals(attempt.get(ATTEMPT_SOURCE_OPERATION_ID, UUID.class))
        || !provenance
            .sourceEvidenceDigest()
            .equals(attempt.get(ATTEMPT_SOURCE_EVIDENCE_DIGEST, String.class))
        || !provenance
            .intakeReceiptDigest()
            .equals(attempt.get(ATTEMPT_INTAKE_RECEIPT_DIGEST, String.class))
        || !request
            .publicationRequestId()
            .equals(attempt.get(ATTEMPT_PUBLICATION_REQUEST_ID, String.class))
        || !request.requestDigest().equals(attempt.get(ATTEMPT_REQUEST_DIGEST, String.class))
        || request.versionStateEpoch() != attempt.get(ATTEMPT_VERSION_STATE_EPOCH, Long.class)
        || !request
            .publishWorkflowId()
            .equals(attempt.get(ATTEMPT_PUBLISH_WORKFLOW_ID, String.class))
        || !request.appliedCommitId().equals(attempt.get(ATTEMPT_APPLIED_COMMIT_ID, String.class))
        || !request.contentDigest().equals(attempt.get(ATTEMPT_CONTENT_DIGEST, String.class))
        || request.digestSchemaVersion()
            != attempt.get(ATTEMPT_DIGEST_SCHEMA_VERSION, Integer.class)) {
      throw new SnapshotConflictException(
          "World graph capture differs from the complete immutable V25 freeze attempt");
    }
  }

  private WorldAuthoredGraphSnapshot toSnapshot(Record row) {
    WorldAuthoredGraphSnapshot snapshot =
        new WorldAuthoredGraphSnapshot(
            required(row, SNAPSHOT_ID),
            required(row, TARGET_NAMESPACE),
            required(row, CANONICAL_TENANT_ID),
            required(row, CANONICAL_VERSION_ID),
            required(row, VERSION_IDENTITY_OPERATION_ID),
            required(row, WORLD_SLUG),
            required(row, GAME_DESIGN_VERSION_ID),
            required(row, LOCAL_VERSION_KEY),
            required(row, LOCAL_TENANT_KEY),
            required(row, OWNER_BINDING_SCHEMA_VERSION),
            required(row, INTAKE_OPERATION_ID),
            required(row, INTAKE_REQUEST_ID),
            required(row, INTAKE_REQUEST_DIGEST),
            required(row, SOURCE_OPERATION_ID),
            required(row, SOURCE_EVIDENCE_DIGEST),
            required(row, INTAKE_RECEIPT_DIGEST),
            required(row, PUBLICATION_FENCE),
            required(row, PUBLICATION_REQUEST_ID),
            required(row, REQUEST_DIGEST),
            required(row, VERSION_STATE_EPOCH),
            required(row, PUBLISH_WORKFLOW_ID),
            required(row, APPLIED_COMMIT_ID),
            required(row, CONTENT_DIGEST),
            required(row, DIGEST_SCHEMA_VERSION),
            required(row, CAPTURE_REQUEST_DIGEST),
            required(row, SUPPLIED_TUPLES_JSON),
            required(row, OWNER_REVISION_EVIDENCE_JSON),
            required(row, GRAPH_BYTES),
            required(row, GRAPH_SHA256),
            parseProofStatus(required(row, OWNER_COMMIT_PROOF_STATUS)));
    verifyGraphHash(snapshot);
    return snapshot;
  }

  private OwnerCommitProofStatus parseProofStatus(String value) {
    if (!CAPTURED_UNVERIFIED.equals(value)) {
      throw new SnapshotConflictException("Unsupported World owner commit proof status");
    }
    return OwnerCommitProofStatus.CAPTURED_UNVERIFIED;
  }

  private void requireSameSnapshot(
      WorldAuthoredGraphSnapshot expected, WorldAuthoredGraphSnapshot stored) {
    if (!expected.snapshotId().equals(stored.snapshotId())
        || !expected.targetNamespace().equals(stored.targetNamespace())
        || !expected.canonicalTenantId().equals(stored.canonicalTenantId())
        || !expected.canonicalVersionId().equals(stored.canonicalVersionId())
        || !expected.versionIdentityOperationId().equals(stored.versionIdentityOperationId())
        || !expected.worldSlug().equals(stored.worldSlug())
        || expected.gameDesignVersionId() != stored.gameDesignVersionId()
        || expected.localVersionKey() != stored.localVersionKey()
        || expected.localTenantKey() != stored.localTenantKey()
        || expected.ownerBindingSchemaVersion() != stored.ownerBindingSchemaVersion()
        || !expected.intakeOperationId().equals(stored.intakeOperationId())
        || !expected.intakeRequestId().equals(stored.intakeRequestId())
        || !expected.intakeRequestDigest().equals(stored.intakeRequestDigest())
        || !expected.sourceOperationId().equals(stored.sourceOperationId())
        || !expected.sourceEvidenceDigest().equals(stored.sourceEvidenceDigest())
        || !expected.intakeReceiptDigest().equals(stored.intakeReceiptDigest())
        || !expected.publicationFence().equals(stored.publicationFence())
        || !expected.publicationRequestId().equals(stored.publicationRequestId())
        || !expected.requestDigest().equals(stored.requestDigest())
        || expected.versionStateEpoch() != stored.versionStateEpoch()
        || !expected.publishWorkflowId().equals(stored.publishWorkflowId())
        || !expected.appliedCommitId().equals(stored.appliedCommitId())
        || !expected.contentDigest().equals(stored.contentDigest())
        || expected.digestSchemaVersion() != stored.digestSchemaVersion()
        || !expected.captureRequestDigest().equals(stored.captureRequestDigest())
        || !expected
            .suppliedOwnedAffectedTuplesJson()
            .equals(stored.suppliedOwnedAffectedTuplesJson())
        || !expected.ownerRevisionEvidenceJson().equals(stored.ownerRevisionEvidenceJson())
        || !Arrays.equals(expected.graphBytes(), stored.graphBytes())
        || !expected.graphSha256().equals(stored.graphSha256())
        || expected.ownerCommitProofStatus() != stored.ownerCommitProofStatus()) {
      throw new SnapshotConflictException(
          "World graph snapshot differs from its immutable committed readback");
    }
  }

  private void verifyGraphHash(WorldAuthoredGraphSnapshot snapshot) {
    if (!WorldAuthoredGraphSnapshotCapture.sha256(snapshot.graphBytes())
        .equals(snapshot.graphSha256())) {
      throw new SnapshotConflictException(
          "World authored graph snapshot bytes failed their stored SHA-256 readback");
    }
  }

  private <T> T required(Record row, Field<T> field) {
    T value = row.get(field);
    if (value == null) {
      throw new SnapshotConflictException(
          "World graph snapshot is missing required " + field.getName());
    }
    return value;
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("World graph capture requires a caller-owned transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("World graph capture requires a writable owner transaction");
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (declaredIsolation != null && declaredIsolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException("World graph capture requires READ COMMITTED isolation");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equalsIgnoreCase(settings.get("transaction_isolation", String.class))
        || !"off".equalsIgnoreCase(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "World graph capture requires a writable READ COMMITTED owner transaction");
    }
  }

  private static <T> Field<T> field(String name, Class<T> type) {
    return DSL.field(DSL.name(name), type);
  }

  public record OwnerProvenance(
      UUID versionIdentityOperationId,
      String targetNamespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String worldSlug,
      long gameDesignVersionId,
      long localVersionKey,
      long localTenantKey,
      UUID intakeOperationId,
      UUID intakeRequestId,
      String intakeRequestDigest,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String intakeReceiptDigest,
      String ownerFreezePhase) {}

  public static class SnapshotConflictException extends IllegalStateException {
    public SnapshotConflictException(String message) {
      super(message);
    }
  }

  public static class MissingOwnerHistoryException extends IllegalStateException {
    public MissingOwnerHistoryException(String message) {
      super(message);
    }
  }
}
