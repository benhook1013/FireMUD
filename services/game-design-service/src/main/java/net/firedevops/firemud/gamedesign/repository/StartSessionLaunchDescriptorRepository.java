package net.firedevops.firemud.gamedesign.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

/** Immutable binding from an owner-produced descriptor to its original StartSession evidence. */
public final class StartSessionLaunchDescriptorRepository {
  private static final Table<?> TABLE =
      DSL.table(DSL.name("game_design_start_session_launch_descriptor_binding"));
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> CONTROL_PLANE_REQUEST_ID =
      DSL.field(DSL.name("control_plane_request_id"), String.class);
  private static final Field<Long> DESCRIPTOR_ROW_ID =
      DSL.field(DSL.name("launch_descriptor_row_id"), Long.class);
  private static final Field<Long> RELEASE_BUNDLE_ID =
      DSL.field(DSL.name("release_bundle_id"), Long.class);
  private static final Field<Long> VERSION_STATE_EPOCH =
      DSL.field(DSL.name("version_state_epoch"), Long.class);
  private static final Field<byte[]> CANONICAL_TUPLE =
      DSL.field(DSL.name("canonical_post_authorization_tuple"), byte[].class);
  private static final Field<UUID> OWNER_ATTEMPT_ID =
      DSL.field(DSL.name("owner_attempt_id"), UUID.class);
  private static final Field<Long> OWNER_FENCE = DSL.field(DSL.name("owner_fence"), Long.class);
  private static final Field<Long> GAME_TEMPLATE_ID =
      DSL.field(DSL.name("game_template_id"), Long.class);
  private static final Field<UUID> CANONICAL_VERSION_ID =
      DSL.field(DSL.name("canonical_version_id"), UUID.class);
  private static final Field<UUID> SELECTED_COMMIT_ID =
      DSL.field(DSL.name("selected_commit_id"), UUID.class);
  private static final Field<String> PUBLISH_WORKFLOW_ID =
      DSL.field(DSL.name("publish_workflow_id"), String.class);
  private static final Field<String> PUBLICATION_SELECTION_DIGEST =
      DSL.field(DSL.name("publication_selection_digest"), String.class);
  private static final Field<String> ASSOCIATION_DIGEST =
      DSL.field(DSL.name("association_digest"), String.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("source_operation_id"), UUID.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("source_evidence_digest"), String.class);
  private static final Field<String> WORLD_SLUG = DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<Long> REFERENCE_PHASE_EPOCH =
      DSL.field(DSL.name("reference_phase_epoch"), Long.class);
  private static final Field<String> RUNTIME_SURFACE =
      DSL.field(DSL.name("runtime_surface"), String.class);
  private static final Field<String> GAME_LOGIC_PUBLISH_REQUEST_ID =
      DSL.field(DSL.name("game_logic_publish_request_id"), String.class);
  private static final Field<String> GAME_LOGIC_SELECTION_DIGEST =
      DSL.field(DSL.name("game_logic_selection_digest"), String.class);
  private static final Field<String> GAME_LOGIC_AUTHORIZATION_DIGEST =
      DSL.field(DSL.name("game_logic_authorization_digest"), String.class);
  private static final Field<String> GAME_LOGIC_RECEIPT_DIGEST =
      DSL.field(DSL.name("game_logic_receipt_digest"), String.class);
  private static final Field<byte[]> ASSOCIATION_READ_RESPONSE =
      DSL.field(DSL.name("association_read_response_bytes"), byte[].class);
  private static final Field<Timestamp> CREATED_AT =
      DSL.field(DSL.name("created_at"), Timestamp.class);

  private final DSLContext dsl;

  public StartSessionLaunchDescriptorRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  public Optional<Binding> find(String targetNamespace, UUID canonicalTenantId, String requestId) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE)
            .where(
                TARGET_NAMESPACE
                    .eq(targetNamespace)
                    .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                    .and(CONTROL_PLANE_REQUEST_ID.eq(requestId)))
            .fetchOne(this::toBinding));
  }

  /** Insert-only; the caller must compare the complete readback before returning it. */
  public Binding insertImmutable(Binding binding) {
    Objects.requireNonNull(binding, "binding");
    dsl.insertInto(TABLE)
        .set(TARGET_NAMESPACE, binding.targetNamespace())
        .set(CANONICAL_TENANT_ID, binding.canonicalTenantId())
        .set(CONTROL_PLANE_REQUEST_ID, binding.controlPlaneRequestId())
        .set(DESCRIPTOR_ROW_ID, binding.descriptorRowId())
        .set(RELEASE_BUNDLE_ID, binding.releaseBundleId())
        .set(VERSION_STATE_EPOCH, binding.versionStateEpoch())
        .set(CANONICAL_TUPLE, binding.canonicalPostAuthorizationTuple())
        .set(OWNER_ATTEMPT_ID, binding.ownerAttemptId())
        .set(OWNER_FENCE, binding.ownerFence())
        .set(GAME_TEMPLATE_ID, binding.gameTemplateId())
        .set(CANONICAL_VERSION_ID, binding.canonicalVersionId())
        .set(SELECTED_COMMIT_ID, binding.selectedCommitId())
        .set(PUBLISH_WORKFLOW_ID, binding.publishWorkflowId())
        .set(PUBLICATION_SELECTION_DIGEST, binding.publicationSelectionDigest())
        .set(ASSOCIATION_DIGEST, binding.associationDigest())
        .set(SOURCE_OPERATION_ID, binding.sourceOperationId())
        .set(SOURCE_EVIDENCE_DIGEST, binding.sourceEvidenceDigest())
        .set(WORLD_SLUG, binding.worldSlug())
        .set(REFERENCE_PHASE_EPOCH, binding.referencePhaseEpoch())
        .set(RUNTIME_SURFACE, binding.runtimeSurface())
        .set(GAME_LOGIC_PUBLISH_REQUEST_ID, binding.gameLogicPublishRequestId())
        .set(GAME_LOGIC_SELECTION_DIGEST, binding.gameLogicSelectionDigest())
        .set(GAME_LOGIC_AUTHORIZATION_DIGEST, binding.gameLogicAuthorizationDigest())
        .set(GAME_LOGIC_RECEIPT_DIGEST, binding.gameLogicReceiptDigest())
        .set(ASSOCIATION_READ_RESPONSE, binding.associationReadResponseBytes())
        .set(CREATED_AT, Timestamp.valueOf(binding.createdAt()))
        .onConflictDoNothing()
        .execute();
    return find(
            binding.targetNamespace(), binding.canonicalTenantId(), binding.controlPlaneRequestId())
        .orElseThrow(
            () -> new IllegalStateException("START_SESSION_DESCRIPTOR_BINDING_READBACK_MISSING"));
  }

  private Binding toBinding(Record row) {
    Timestamp createdAt = row.get(CREATED_AT, Timestamp.class);
    return new Binding(
        row.get(TARGET_NAMESPACE),
        row.get(CANONICAL_TENANT_ID),
        row.get(CONTROL_PLANE_REQUEST_ID),
        row.get(DESCRIPTOR_ROW_ID),
        row.get(RELEASE_BUNDLE_ID),
        row.get(VERSION_STATE_EPOCH),
        row.get(CANONICAL_TUPLE),
        row.get(OWNER_ATTEMPT_ID),
        row.get(OWNER_FENCE),
        row.get(GAME_TEMPLATE_ID),
        row.get(CANONICAL_VERSION_ID),
        row.get(SELECTED_COMMIT_ID),
        row.get(PUBLISH_WORKFLOW_ID),
        row.get(PUBLICATION_SELECTION_DIGEST),
        row.get(ASSOCIATION_DIGEST),
        row.get(SOURCE_OPERATION_ID),
        row.get(SOURCE_EVIDENCE_DIGEST),
        row.get(WORLD_SLUG),
        row.get(REFERENCE_PHASE_EPOCH),
        row.get(RUNTIME_SURFACE),
        row.get(GAME_LOGIC_PUBLISH_REQUEST_ID),
        row.get(GAME_LOGIC_SELECTION_DIGEST),
        row.get(GAME_LOGIC_AUTHORIZATION_DIGEST),
        row.get(GAME_LOGIC_RECEIPT_DIGEST),
        row.get(ASSOCIATION_READ_RESPONSE),
        createdAt == null ? null : createdAt.toLocalDateTime());
  }

  public record Binding(
      String targetNamespace,
      UUID canonicalTenantId,
      String controlPlaneRequestId,
      Long descriptorRowId,
      Long releaseBundleId,
      Long versionStateEpoch,
      byte[] canonicalPostAuthorizationTuple,
      UUID ownerAttemptId,
      Long ownerFence,
      Long gameTemplateId,
      UUID canonicalVersionId,
      UUID selectedCommitId,
      String publishWorkflowId,
      String publicationSelectionDigest,
      String associationDigest,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String worldSlug,
      Long referencePhaseEpoch,
      String runtimeSurface,
      String gameLogicPublishRequestId,
      String gameLogicSelectionDigest,
      String gameLogicAuthorizationDigest,
      String gameLogicReceiptDigest,
      byte[] associationReadResponseBytes,
      LocalDateTime createdAt) {
    public Binding {
      canonicalPostAuthorizationTuple = cloneBytes(canonicalPostAuthorizationTuple);
      associationReadResponseBytes = cloneBytes(associationReadResponseBytes);
    }

    @Override
    public byte[] canonicalPostAuthorizationTuple() {
      return cloneBytes(canonicalPostAuthorizationTuple);
    }

    @Override
    public byte[] associationReadResponseBytes() {
      return cloneBytes(associationReadResponseBytes);
    }

    private static byte[] cloneBytes(byte[] value) {
      return value == null ? null : value.clone();
    }
  }
}
