package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Owner-local immutable launch descriptor history. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class LaunchDescriptorRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("launch_descriptor"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> LAUNCH_DESCRIPTOR_ID =
      DSL.field(DSL.name("launch_descriptor_id"), String.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> GAME_TEMPLATE_ID =
      DSL.field(DSL.name("game_template_id"), Long.class);
  private static final Field<String> CONTROL_PLANE_REQUEST_ID =
      DSL.field(DSL.name("control_plane_request_id"), String.class);
  private static final Field<String> REQUEST_HASH =
      DSL.field(DSL.name("request_hash"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<String> SCRIPT_PATCH_VERSION =
      DSL.field(DSL.name("script_patch_version"), String.class);
  private static final Field<String> RUNTIME_FLAGS_JSON =
      DSL.field(DSL.name("runtime_flags_json"), String.class);
  private static final Field<String> GENERATION_CONFIG_REVISION =
      DSL.field(DSL.name("generation_config_revision"), String.class);
  private static final Field<Long> VERSION_STATE_EPOCH =
      DSL.field(DSL.name("version_state_epoch"), Long.class);
  private static final Field<Long> RELEASE_BUNDLE_ID =
      DSL.field(DSL.name("release_bundle_id"), Long.class);
  private static final Field<String> PUBLISHED_RELEASE_BUNDLE_REF =
      DSL.field(DSL.name("published_release_bundle_ref"), String.class);
  private static final Field<String> REMAP_SET_ID =
      DSL.field(DSL.name("remap_set_id"), String.class);
  private static final Field<Integer> DESCRIPTOR_SCHEMA_VERSION =
      DSL.field(DSL.name("descriptor_schema_version"), Integer.class);
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> WORLD_SLUG = DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("authored_world_source_operation_id"), UUID.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("authored_world_source_evidence_digest"), String.class);
  private static final Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<String> RESULT_DIGEST =
      DSL.field(DSL.name("result_digest"), String.class);
  private static final Field<String> ORIGINAL_REQUEST_JSON =
      DSL.field(DSL.name("original_request_json"), String.class);
  private static final Field<String> SOURCE_EVIDENCE_JSON =
      DSL.field(DSL.name("source_evidence_json"), String.class);
  private static final Field<String> OUTCOME_STATUS =
      DSL.field(DSL.name("outcome_status"), String.class);
  private static final Field<String> FAILURE_CODE =
      DSL.field(DSL.name("failure_code"), String.class);
  private static final Field<String> FAILURE_MESSAGE =
      DSL.field(DSL.name("failure_message"), String.class);
  private static final Field<Timestamp> CREATED_AT =
      DSL.field(DSL.name("created_at"), Timestamp.class);

  private final DSLContext dsl;

  public LaunchDescriptorRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Serializes one launch request identity before source, template, or version resolution. */
  public void lockBoundRequest(String targetNamespace, UUID canonicalTenantId, String requestId) {
    String lockIdentity = canonicalTenantId + ":" + requestId;
    dsl.fetch(
        "select pg_advisory_xact_lock(hashtext(?), hashtext(?))", targetNamespace, lockIdentity);
  }

  public Optional<LaunchDescriptor> findBoundByRequest(
      String targetNamespace, UUID canonicalTenantId, String controlPlaneRequestId) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(
                TARGET_NAMESPACE
                    .eq(targetNamespace)
                    .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                    .and(CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  /** Reads the legacy private owner/request tuple only to deny unbound retained history. */
  public Optional<LaunchDescriptor> findByPrivateRequest(
      String tenantId, String controlPlaneRequestId) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(TENANT_ID.eq(tenantId).and(CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public boolean existsByTenantIdAndVersionId(String tenantId, Long versionId) {
    return dsl.fetchExists(TABLE_REF, TENANT_ID.eq(tenantId).and(VERSION_ID.eq(versionId)));
  }

  /** Inserts one immutable descriptor; conflicting identities can only return their stored row. */
  public LaunchDescriptor insertImmutable(LaunchDescriptor descriptor) {
    if (descriptor.getId() != null || descriptor.getDescriptorSchemaVersion() == null) {
      throw new IllegalArgumentException("Only a new bound launch descriptor can be inserted");
    }
    if (descriptor.getOutcomeStatus() == null
        || !(LaunchDescriptor.OUTCOME_SUCCESS.equals(descriptor.getOutcomeStatus())
            || LaunchDescriptor.OUTCOME_FAILED.equals(descriptor.getOutcomeStatus()))) {
      throw new IllegalArgumentException("A known launch descriptor outcome is required");
    }
    LocalDateTime createdAt =
        descriptor.getCreatedAt() == null ? LocalDateTime.now() : descriptor.getCreatedAt();
    int inserted =
        dsl.insertInto(TABLE_REF)
            .set(LAUNCH_DESCRIPTOR_ID, descriptor.getLaunchDescriptorId())
            .set(TENANT_ID, descriptor.getTenantId())
            .set(GAME_TEMPLATE_ID, descriptor.getGameTemplateId())
            .set(CONTROL_PLANE_REQUEST_ID, descriptor.getControlPlaneRequestId())
            .set(REQUEST_HASH, descriptor.getRequestDigest())
            .set(VERSION_ID, descriptor.getVersionId())
            .set(SCRIPT_PATCH_VERSION, descriptor.getScriptPatchVersion())
            .set(RUNTIME_FLAGS_JSON, descriptor.getRuntimeFlagsJson())
            .set(GENERATION_CONFIG_REVISION, descriptor.getGenerationConfigRevision())
            .set(VERSION_STATE_EPOCH, descriptor.getVersionStateEpoch())
            .set(RELEASE_BUNDLE_ID, descriptor.getReleaseBundleId())
            .set(PUBLISHED_RELEASE_BUNDLE_REF, descriptor.getPublishedReleaseBundleRef())
            .set(REMAP_SET_ID, descriptor.getRemapSetId())
            .set(DESCRIPTOR_SCHEMA_VERSION, descriptor.getDescriptorSchemaVersion())
            .set(TARGET_NAMESPACE, descriptor.getTargetNamespace())
            .set(CANONICAL_TENANT_ID, UUID.fromString(descriptor.getCanonicalTenantId()))
            .set(WORLD_SLUG, descriptor.getWorldSlug())
            .set(
                SOURCE_OPERATION_ID,
                UUID.fromString(descriptor.getAuthoredWorldSourceOperationId()))
            .set(SOURCE_EVIDENCE_DIGEST, descriptor.getAuthoredWorldSourceEvidenceDigest())
            .set(REQUEST_DIGEST, descriptor.getRequestDigest())
            .set(RESULT_DIGEST, descriptor.getResultDigest())
            .set(ORIGINAL_REQUEST_JSON, descriptor.getOriginalRequestJson())
            .set(SOURCE_EVIDENCE_JSON, descriptor.getSourceEvidenceJson())
            .set(OUTCOME_STATUS, descriptor.getOutcomeStatus())
            .set(FAILURE_CODE, descriptor.getFailureCode())
            .set(FAILURE_MESSAGE, descriptor.getFailureMessage())
            .set(CREATED_AT, Timestamp.valueOf(createdAt))
            .onConflictDoNothing()
            .execute();
    Optional<LaunchDescriptor> stored =
        findBoundByRequest(
            descriptor.getTargetNamespace(),
            UUID.fromString(descriptor.getCanonicalTenantId()),
            descriptor.getControlPlaneRequestId());
    if (stored.isEmpty()) {
      throw new IllegalStateException(
          "Launch descriptor identity conflicts with existing immutable history");
    }
    if (inserted == 0 && stored.orElseThrow().getDescriptorSchemaVersion() == null) {
      throw new IllegalStateException("An unbound launch descriptor cannot be reused canonically");
    }
    return stored.orElseThrow();
  }

  private LaunchDescriptor toEntity(Record record) {
    if (record == null) {
      return null;
    }
    LaunchDescriptor descriptor = new LaunchDescriptor();
    descriptor.setId(record.get(ID));
    descriptor.setLaunchDescriptorId(record.get(LAUNCH_DESCRIPTOR_ID));
    descriptor.setTenantId(record.get(TENANT_ID));
    descriptor.setGameTemplateId(record.get(GAME_TEMPLATE_ID));
    descriptor.setControlPlaneRequestId(record.get(CONTROL_PLANE_REQUEST_ID));
    descriptor.setRequestHash(record.get(REQUEST_HASH));
    descriptor.setVersionId(record.get(VERSION_ID));
    descriptor.setScriptPatchVersion(record.get(SCRIPT_PATCH_VERSION));
    descriptor.setRuntimeFlagsJson(record.get(RUNTIME_FLAGS_JSON));
    descriptor.setGenerationConfigRevision(record.get(GENERATION_CONFIG_REVISION));
    descriptor.setVersionStateEpoch(record.get(VERSION_STATE_EPOCH));
    descriptor.setReleaseBundleId(record.get(RELEASE_BUNDLE_ID));
    descriptor.setPublishedReleaseBundleRef(record.get(PUBLISHED_RELEASE_BUNDLE_REF));
    descriptor.setRemapSetId(record.get(REMAP_SET_ID));
    descriptor.setDescriptorSchemaVersion(record.get(DESCRIPTOR_SCHEMA_VERSION, Integer.class));
    descriptor.setTargetNamespace(record.get(TARGET_NAMESPACE));
    UUID canonicalTenantId = record.get(CANONICAL_TENANT_ID, UUID.class);
    descriptor.setCanonicalTenantId(
        canonicalTenantId == null ? null : canonicalTenantId.toString());
    descriptor.setWorldSlug(record.get(WORLD_SLUG));
    UUID sourceOperationId = record.get(SOURCE_OPERATION_ID, UUID.class);
    descriptor.setAuthoredWorldSourceOperationId(
        sourceOperationId == null ? null : sourceOperationId.toString());
    descriptor.setAuthoredWorldSourceEvidenceDigest(record.get(SOURCE_EVIDENCE_DIGEST));
    descriptor.setRequestDigest(record.get(REQUEST_DIGEST));
    descriptor.setResultDigest(record.get(RESULT_DIGEST));
    descriptor.setOriginalRequestJson(record.get(ORIGINAL_REQUEST_JSON));
    descriptor.setSourceEvidenceJson(record.get(SOURCE_EVIDENCE_JSON));
    descriptor.setOutcomeStatus(record.get(OUTCOME_STATUS));
    descriptor.setFailureCode(record.get(FAILURE_CODE));
    descriptor.setFailureMessage(record.get(FAILURE_MESSAGE));
    Timestamp createdAt = record.get(CREATED_AT, Timestamp.class);
    descriptor.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
    return descriptor;
  }
}
