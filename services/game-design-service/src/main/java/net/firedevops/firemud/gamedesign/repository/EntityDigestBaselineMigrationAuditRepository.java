package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.EntityDigestBaselineMigrationAudit;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class EntityDigestBaselineMigrationAuditRepository {
  private static final Table<?> TABLE_REF =
      DSL.table(DSL.name("entity_digest_baseline_migration_audit"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> OPERATION_ID =
      DSL.field(DSL.name("operation_id"), String.class);
  private static final Field<Long> BASELINE_ID =
      DSL.field(DSL.name("recorded_participant_digest_id"), Long.class);
  private static final Field<String> SOURCE_TENANT_ID =
      DSL.field(DSL.name("source_tenant_id"), String.class);
  private static final Field<String> SOURCE_PUBLISH_TYPE =
      DSL.field(DSL.name("source_publish_type"), String.class);
  private static final Field<String> SOURCE_PARTICIPANT_KEY =
      DSL.field(DSL.name("source_participant_key"), String.class);
  private static final Field<Long> SOURCE_BASE_VERSION_ID =
      DSL.field(DSL.name("source_base_version_id"), Long.class);
  private static final Field<String> SOURCE_SCOPE_VALUE =
      DSL.field(DSL.name("source_scope_value"), String.class);
  private static final Field<String> SOURCE_APPLIED_COMMIT_ID =
      DSL.field(DSL.name("source_applied_commit_id"), String.class);
  private static final Field<String> SOURCE_CONTENT_DIGEST =
      DSL.field(DSL.name("source_content_digest"), String.class);
  private static final Field<Integer> SOURCE_DIGEST_SCHEMA_VERSION =
      DSL.field(DSL.name("source_digest_schema_version"), Integer.class);
  private static final Field<String> SOURCE_RECORDED_FROM_WORKFLOW_ID =
      DSL.field(DSL.name("source_recorded_from_publish_workflow_id"), String.class);
  private static final Field<LocalDateTime> SOURCE_RECORDED_AT =
      DSL.field(DSL.name("source_recorded_at"), LocalDateTime.class);
  private static final Field<String> SOURCE_LAST_VERIFIED_WORKFLOW_ID =
      DSL.field(DSL.name("source_last_verified_publish_workflow_id"), String.class);
  private static final Field<LocalDateTime> SOURCE_LAST_VERIFIED_AT =
      DSL.field(DSL.name("source_last_verified_at"), LocalDateTime.class);
  private static final Field<String> OBSERVED_TENANT_ID =
      DSL.field(DSL.name("observed_tenant_id"), String.class);
  private static final Field<String> OBSERVED_PUBLISH_TYPE =
      DSL.field(DSL.name("observed_publish_type"), String.class);
  private static final Field<String> OBSERVED_PARTICIPANT_KEY =
      DSL.field(DSL.name("observed_participant_key"), String.class);
  private static final Field<Long> OBSERVED_BASE_VERSION_ID =
      DSL.field(DSL.name("observed_base_version_id"), Long.class);
  private static final Field<String> OBSERVED_SCOPE_VALUE =
      DSL.field(DSL.name("observed_scope_value"), String.class);
  private static final Field<String> OBSERVED_APPLIED_COMMIT_ID =
      DSL.field(DSL.name("observed_applied_commit_id"), String.class);
  private static final Field<String> OBSERVED_CONTENT_DIGEST =
      DSL.field(DSL.name("observed_content_digest"), String.class);
  private static final Field<Integer> OBSERVED_DIGEST_SCHEMA_VERSION =
      DSL.field(DSL.name("observed_digest_schema_version"), Integer.class);
  private static final Field<String> ACTOR_IDENTITY =
      DSL.field(DSL.name("actor_identity"), String.class);
  private static final Field<String> WORKLOAD_IDENTITY =
      DSL.field(DSL.name("workload_identity"), String.class);
  private static final Field<String> OUTCOME = DSL.field(DSL.name("outcome"), String.class);
  private static final Field<LocalDateTime> COMMITTED_AT =
      DSL.field(DSL.name("committed_at"), LocalDateTime.class);

  private final DSLContext dsl;

  public EntityDigestBaselineMigrationAuditRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Inserts one immutable audit record; operation and baseline uniqueness are enforced by SQL. */
  public EntityDigestBaselineMigrationAudit insert(EntityDigestBaselineMigrationAudit audit) {
    validateCommittedAudit(audit);
    Record record =
        dsl.insertInto(TABLE_REF)
            .set(OPERATION_ID, audit.operationId())
            .set(BASELINE_ID, audit.recordedParticipantDigestId())
            .set(SOURCE_TENANT_ID, audit.sourceTenantId())
            .set(SOURCE_PUBLISH_TYPE, audit.sourcePublishType().name())
            .set(SOURCE_PARTICIPANT_KEY, audit.sourceParticipantKey().name())
            .set(SOURCE_BASE_VERSION_ID, audit.sourceBaseVersionId())
            .set(SOURCE_SCOPE_VALUE, audit.sourceScopeValue())
            .set(SOURCE_APPLIED_COMMIT_ID, audit.sourceAppliedCommitId())
            .set(SOURCE_CONTENT_DIGEST, audit.sourceContentDigest())
            .set(SOURCE_DIGEST_SCHEMA_VERSION, audit.sourceDigestSchemaVersion())
            .set(SOURCE_RECORDED_FROM_WORKFLOW_ID, audit.sourceRecordedFromPublishWorkflowId())
            .set(SOURCE_RECORDED_AT, audit.sourceRecordedAt())
            .set(SOURCE_LAST_VERIFIED_WORKFLOW_ID, audit.sourceLastVerifiedPublishWorkflowId())
            .set(SOURCE_LAST_VERIFIED_AT, audit.sourceLastVerifiedAt())
            .set(OBSERVED_TENANT_ID, audit.observedTenantId())
            .set(OBSERVED_PUBLISH_TYPE, audit.observedPublishType().name())
            .set(OBSERVED_PARTICIPANT_KEY, audit.observedParticipantKey().name())
            .set(OBSERVED_BASE_VERSION_ID, audit.observedBaseVersionId())
            .set(OBSERVED_SCOPE_VALUE, audit.observedScopeValue())
            .set(OBSERVED_APPLIED_COMMIT_ID, audit.observedAppliedCommitId())
            .set(OBSERVED_CONTENT_DIGEST, audit.observedContentDigest())
            .set(OBSERVED_DIGEST_SCHEMA_VERSION, audit.observedDigestSchemaVersion())
            .set(ACTOR_IDENTITY, audit.actorIdentity())
            .set(WORKLOAD_IDENTITY, audit.workloadIdentity())
            .set(OUTCOME, audit.outcome())
            .set(COMMITTED_AT, audit.committedAt())
            .returning()
            .fetchOne();
    return toEntity(record);
  }

  /** Resolves a prior operation by its stable id for exact retry and readback. */
  public Optional<EntityDigestBaselineMigrationAudit> findByOperationId(String operationId) {
    if (operationId == null || operationId.isBlank()) {
      throw new IllegalArgumentException("operationId must not be blank");
    }
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF).where(OPERATION_ID.eq(operationId)).fetchOne(this::toEntity));
  }

  private void validateCommittedAudit(EntityDigestBaselineMigrationAudit audit) {
    if (audit == null) {
      throw new IllegalArgumentException("audit is required");
    }
    requireNonBlank(audit.operationId(), "operationId");
    requireNonBlank(audit.sourceTenantId(), "sourceTenantId");
    requireNonBlank(audit.sourceScopeValue(), "sourceScopeValue");
    requireNonBlank(audit.sourceAppliedCommitId(), "sourceAppliedCommitId");
    requireNonBlank(audit.sourceContentDigest(), "sourceContentDigest");
    requireNonBlank(audit.sourceRecordedFromPublishWorkflowId(), "sourceRecordedFrom workflow id");
    requireNonBlank(audit.sourceLastVerifiedPublishWorkflowId(), "sourceLastVerified workflow id");
    requireNonBlank(audit.observedTenantId(), "observedTenantId");
    requireNonBlank(audit.observedScopeValue(), "observedScopeValue");
    requireNonBlank(audit.observedAppliedCommitId(), "observedAppliedCommitId");
    requireNonBlank(audit.observedContentDigest(), "observedContentDigest");
    requireNonBlank(audit.actorIdentity(), "actorIdentity");
    requireNonBlank(audit.workloadIdentity(), "workloadIdentity");
    if (audit.recordedParticipantDigestId() == null
        || audit.recordedParticipantDigestId() < 1
        || audit.sourcePublishType() != PublishType.FULL_VERSION
        || audit.sourceParticipantKey() != PublishParticipantKey.ENTITY_MANAGEMENT
        || audit.sourceBaseVersionId() != null
        || audit.sourceDigestSchemaVersion() != 1
        || audit.sourceRecordedAt() == null
        || audit.sourceLastVerifiedAt() == null
        || audit.observedPublishType() != PublishType.FULL_VERSION
        || audit.observedParticipantKey() != PublishParticipantKey.ENTITY_MANAGEMENT
        || audit.observedBaseVersionId() != null
        || audit.observedDigestSchemaVersion() != 2
        || !audit.sourceTenantId().equals(audit.observedTenantId())
        || !audit.sourceScopeValue().equals(audit.observedScopeValue())
        || !EntityDigestBaselineMigrationAudit.COMMITTED_OUTCOME.equals(audit.outcome())
        || audit.committedAt() == null) {
      throw new IllegalArgumentException("audit is not a complete Entity v1-to-v2 commit record");
    }
  }

  private void requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }

  private EntityDigestBaselineMigrationAudit toEntity(Record record) {
    if (record == null) {
      return null;
    }
    return new EntityDigestBaselineMigrationAudit(
        record.get(ID),
        record.get(OPERATION_ID),
        record.get(BASELINE_ID),
        record.get(SOURCE_TENANT_ID),
        PublishType.valueOf(record.get(SOURCE_PUBLISH_TYPE)),
        PublishParticipantKey.valueOf(record.get(SOURCE_PARTICIPANT_KEY)),
        record.get(SOURCE_BASE_VERSION_ID),
        record.get(SOURCE_SCOPE_VALUE),
        record.get(SOURCE_APPLIED_COMMIT_ID),
        record.get(SOURCE_CONTENT_DIGEST),
        record.get(SOURCE_DIGEST_SCHEMA_VERSION),
        record.get(SOURCE_RECORDED_FROM_WORKFLOW_ID),
        record.get(SOURCE_RECORDED_AT),
        record.get(SOURCE_LAST_VERIFIED_WORKFLOW_ID),
        record.get(SOURCE_LAST_VERIFIED_AT),
        record.get(OBSERVED_TENANT_ID),
        PublishType.valueOf(record.get(OBSERVED_PUBLISH_TYPE)),
        PublishParticipantKey.valueOf(record.get(OBSERVED_PARTICIPANT_KEY)),
        record.get(OBSERVED_BASE_VERSION_ID),
        record.get(OBSERVED_SCOPE_VALUE),
        record.get(OBSERVED_APPLIED_COMMIT_ID),
        record.get(OBSERVED_CONTENT_DIGEST),
        record.get(OBSERVED_DIGEST_SCHEMA_VERSION),
        record.get(ACTOR_IDENTITY),
        record.get(WORKLOAD_IDENTITY),
        record.get(OUTCOME),
        record.get(COMMITTED_AT));
  }
}
