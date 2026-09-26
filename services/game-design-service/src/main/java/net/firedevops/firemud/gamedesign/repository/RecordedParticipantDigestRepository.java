package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.RecordedParticipantDigest;
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
public class RecordedParticipantDigestRepository {
  private static final int MAX_ENTITY_BASELINE_MIGRATION_BATCH_SIZE = 500;
  private static final int ENTITY_V1_DIGEST_SCHEMA_VERSION = 1;
  private static final int ENTITY_V2_DIGEST_SCHEMA_VERSION = 2;
  private static final Table<?> TABLE_REF =
      DSL.table(DSL.name("publish_recorded_participant_digest"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> PUBLISH_TYPE =
      DSL.field(DSL.name("publish_type"), String.class);
  private static final Field<String> PARTICIPANT_KEY =
      DSL.field(DSL.name("participant_key"), String.class);
  private static final Field<String> SCOPE_VALUE = DSL.field(DSL.name("scope_value"), String.class);
  private static final Field<Long> BASE_VERSION_ID =
      DSL.field(DSL.name("base_version_id"), Long.class);
  private static final Field<String> APPLIED_COMMIT_ID =
      DSL.field(DSL.name("applied_commit_id"), String.class);
  private static final Field<String> CONTENT_DIGEST =
      DSL.field(DSL.name("content_digest"), String.class);
  private static final Field<Integer> DIGEST_SCHEMA_VERSION =
      DSL.field(DSL.name("digest_schema_version"), Integer.class);
  private static final Field<String> RECORDED_FROM_PUBLISH_WORKFLOW_ID =
      DSL.field(DSL.name("recorded_from_publish_workflow_id"), String.class);
  private static final Field<LocalDateTime> RECORDED_AT =
      DSL.field(DSL.name("recorded_at"), LocalDateTime.class);
  private static final Field<String> LAST_VERIFIED_PUBLISH_WORKFLOW_ID =
      DSL.field(DSL.name("last_verified_publish_workflow_id"), String.class);
  private static final Field<LocalDateTime> LAST_VERIFIED_AT =
      DSL.field(DSL.name("last_verified_at"), LocalDateTime.class);

  private final DSLContext dsl;

  public RecordedParticipantDigestRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<RecordedParticipantDigest>
      findByTenantIdAndPublishTypeAndParticipantKeyAndAppliedCommitId(
          String tenantId,
          PublishType publishType,
          PublishParticipantKey participantKey,
          Long baseVersionId,
          String scopeValue,
          String appliedCommitId) {
    var baseCondition =
        baseVersionId == null ? BASE_VERSION_ID.isNull() : BASE_VERSION_ID.eq(baseVersionId);
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(
                TENANT_ID
                    .eq(tenantId)
                    .and(PUBLISH_TYPE.eq(publishType.name()))
                    .and(PARTICIPANT_KEY.eq(participantKey.name()))
                    .and(baseCondition)
                    .and(SCOPE_VALUE.eq(scopeValue))
                    .and(APPLIED_COMMIT_ID.eq(appliedCommitId)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  /** Returns one ID-keyset page of retained full-version Entity v1 baselines. */
  public List<RecordedParticipantDigest> findEntityV1FullVersionBatchAfterId(
      long afterId, int limit) {
    if (afterId < 0) {
      throw new IllegalArgumentException("afterId must be non-negative");
    }
    if (limit < 1 || limit > MAX_ENTITY_BASELINE_MIGRATION_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + MAX_ENTITY_BASELINE_MIGRATION_BATCH_SIZE);
    }
    return dsl.selectFrom(TABLE_REF)
        .where(
            ID.gt(afterId)
                .and(PUBLISH_TYPE.eq(PublishType.FULL_VERSION.name()))
                .and(PARTICIPANT_KEY.eq(PublishParticipantKey.ENTITY_MANAGEMENT.name()))
                .and(BASE_VERSION_ID.isNull())
                .and(DIGEST_SCHEMA_VERSION.eq(ENTITY_V1_DIGEST_SCHEMA_VERSION)))
        .orderBy(ID.asc())
        .limit(limit)
        .fetch(this::toEntity);
  }

  /**
   * Reads at most two Entity full-version baselines for an exact tenant/version scope. Returning
   * two rows is enough for callers to reject an ambiguous scope without loading unbounded data.
   */
  public List<RecordedParticipantDigest> findEntityFullVersionBaselinesByScope(
      String tenantId, String versionId) {
    requireNonBlank(tenantId, "tenantId");
    requireNonBlank(versionId, "versionId");
    return dsl.selectFrom(TABLE_REF)
        .where(
            TENANT_ID
                .eq(tenantId)
                .and(PUBLISH_TYPE.eq(PublishType.FULL_VERSION.name()))
                .and(PARTICIPANT_KEY.eq(PublishParticipantKey.ENTITY_MANAGEMENT.name()))
                .and(BASE_VERSION_ID.isNull())
                .and(SCOPE_VALUE.eq(versionId)))
        .orderBy(ID.asc())
        .limit(2)
        .fetch(this::toEntity);
  }

  /** Reads a recorded baseline by its stable row identity for exact migration readback. */
  public Optional<RecordedParticipantDigest> findById(long id) {
    if (id < 1) {
      throw new IllegalArgumentException("id must be positive");
    }
    return Optional.ofNullable(dsl.selectFrom(TABLE_REF).where(ID.eq(id)).fetchOne(this::toEntity));
  }

  /**
   * Replaces only the digest, schema, commit, and provenance when the complete expected v1 row is
   * still present. The caller owns the transaction that also inserts the migration audit row.
   *
   * @return the number of rows changed; callers must require exactly one
   */
  public int migrateEntityFullVersionBaselineIfUnchanged(
      RecordedParticipantDigest expectedOld, RecordedParticipantDigest replacement) {
    validateEntityBaselineMigrationPair(expectedOld, replacement);
    return dsl.update(TABLE_REF)
        .set(APPLIED_COMMIT_ID, replacement.getAppliedCommitId())
        .set(CONTENT_DIGEST, replacement.getContentDigest())
        .set(DIGEST_SCHEMA_VERSION, replacement.getDigestSchemaVersion())
        .where(
            ID.eq(expectedOld.getId())
                .and(TENANT_ID.eq(expectedOld.getTenantId()))
                .and(PUBLISH_TYPE.eq(PublishType.FULL_VERSION.name()))
                .and(PARTICIPANT_KEY.eq(PublishParticipantKey.ENTITY_MANAGEMENT.name()))
                .and(BASE_VERSION_ID.isNull())
                .and(SCOPE_VALUE.eq(expectedOld.getScopeValue()))
                .and(APPLIED_COMMIT_ID.eq(expectedOld.getAppliedCommitId()))
                .and(CONTENT_DIGEST.eq(expectedOld.getContentDigest()))
                .and(DIGEST_SCHEMA_VERSION.eq(ENTITY_V1_DIGEST_SCHEMA_VERSION))
                .and(
                    RECORDED_FROM_PUBLISH_WORKFLOW_ID.eq(
                        expectedOld.getRecordedFromPublishWorkflowId()))
                .and(RECORDED_AT.eq(expectedOld.getRecordedAt()))
                .and(
                    LAST_VERIFIED_PUBLISH_WORKFLOW_ID.eq(
                        expectedOld.getLastVerifiedPublishWorkflowId()))
                .and(LAST_VERIFIED_AT.eq(expectedOld.getLastVerifiedAt())))
        .execute();
  }

  private void validateEntityBaselineMigrationPair(
      RecordedParticipantDigest expectedOld, RecordedParticipantDigest replacement) {
    if (expectedOld == null || replacement == null) {
      throw new IllegalArgumentException("expected and replacement baselines are required");
    }
    requireNonBlank(expectedOld.getTenantId(), "expected tenantId");
    requireNonBlank(expectedOld.getScopeValue(), "expected scopeValue");
    requireNonBlank(expectedOld.getAppliedCommitId(), "expected appliedCommitId");
    requireNonBlank(expectedOld.getContentDigest(), "expected contentDigest");
    requireNonBlank(
        expectedOld.getRecordedFromPublishWorkflowId(), "expected recordedFromPublishWorkflowId");
    requireNonBlank(
        expectedOld.getLastVerifiedPublishWorkflowId(), "expected lastVerifiedPublishWorkflowId");
    if (expectedOld.getId() == null
        || expectedOld.getId() < 1
        || expectedOld.getPublishType() != PublishType.FULL_VERSION
        || expectedOld.getParticipantKey() != PublishParticipantKey.ENTITY_MANAGEMENT
        || expectedOld.getBaseVersionId() != null
        || expectedOld.getDigestSchemaVersion() != ENTITY_V1_DIGEST_SCHEMA_VERSION
        || expectedOld.getRecordedAt() == null
        || expectedOld.getLastVerifiedAt() == null) {
      throw new IllegalArgumentException("expected baseline is not a complete Entity v1 row");
    }

    requireNonBlank(replacement.getTenantId(), "replacement tenantId");
    requireNonBlank(replacement.getScopeValue(), "replacement scopeValue");
    requireNonBlank(replacement.getAppliedCommitId(), "replacement appliedCommitId");
    requireNonBlank(replacement.getContentDigest(), "replacement contentDigest");
    requireNonBlank(
        replacement.getRecordedFromPublishWorkflowId(),
        "replacement recordedFromPublishWorkflowId");
    requireNonBlank(
        replacement.getLastVerifiedPublishWorkflowId(),
        "replacement lastVerifiedPublishWorkflowId");
    if (!expectedOld.getTenantId().equals(replacement.getTenantId())
        || !expectedOld.getScopeValue().equals(replacement.getScopeValue())
        || (replacement.getId() != null && !expectedOld.getId().equals(replacement.getId()))
        || replacement.getPublishType() != PublishType.FULL_VERSION
        || replacement.getParticipantKey() != PublishParticipantKey.ENTITY_MANAGEMENT
        || replacement.getBaseVersionId() != null
        || replacement.getDigestSchemaVersion() != ENTITY_V2_DIGEST_SCHEMA_VERSION
        || replacement.getRecordedAt() == null
        || replacement.getLastVerifiedAt() == null) {
      throw new IllegalArgumentException("replacement is not the matching Entity v2 baseline");
    }
  }

  private void requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }

  public RecordedParticipantDigest save(RecordedParticipantDigest digest) {
    LocalDateTime recordedAt =
        digest.getRecordedAt() == null ? LocalDateTime.now() : digest.getRecordedAt();
    LocalDateTime lastVerifiedAt =
        digest.getLastVerifiedAt() == null ? LocalDateTime.now() : digest.getLastVerifiedAt();
    if (digest.getId() == null) {
      Record record =
          dsl.insertInto(TABLE_REF)
              .set(TENANT_ID, digest.getTenantId())
              .set(PUBLISH_TYPE, digest.getPublishType().name())
              .set(PARTICIPANT_KEY, digest.getParticipantKey().name())
              .set(SCOPE_VALUE, digest.getScopeValue())
              .set(BASE_VERSION_ID, digest.getBaseVersionId())
              .set(APPLIED_COMMIT_ID, digest.getAppliedCommitId())
              .set(CONTENT_DIGEST, digest.getContentDigest())
              .set(DIGEST_SCHEMA_VERSION, digest.getDigestSchemaVersion())
              .set(RECORDED_FROM_PUBLISH_WORKFLOW_ID, digest.getRecordedFromPublishWorkflowId())
              .set(RECORDED_AT, recordedAt)
              .set(LAST_VERIFIED_PUBLISH_WORKFLOW_ID, digest.getLastVerifiedPublishWorkflowId())
              .set(LAST_VERIFIED_AT, lastVerifiedAt)
              .returning()
              .fetchOne();
      return toEntity(record);
    }
    dsl.update(TABLE_REF)
        .set(TENANT_ID, digest.getTenantId())
        .set(PUBLISH_TYPE, digest.getPublishType().name())
        .set(PARTICIPANT_KEY, digest.getParticipantKey().name())
        .set(SCOPE_VALUE, digest.getScopeValue())
        .set(BASE_VERSION_ID, digest.getBaseVersionId())
        .set(APPLIED_COMMIT_ID, digest.getAppliedCommitId())
        .set(CONTENT_DIGEST, digest.getContentDigest())
        .set(DIGEST_SCHEMA_VERSION, digest.getDigestSchemaVersion())
        .set(RECORDED_FROM_PUBLISH_WORKFLOW_ID, digest.getRecordedFromPublishWorkflowId())
        .set(RECORDED_AT, recordedAt)
        .set(LAST_VERIFIED_PUBLISH_WORKFLOW_ID, digest.getLastVerifiedPublishWorkflowId())
        .set(LAST_VERIFIED_AT, lastVerifiedAt)
        .where(ID.eq(digest.getId()))
        .execute();
    return findByTenantIdAndPublishTypeAndParticipantKeyAndAppliedCommitId(
            digest.getTenantId(),
            digest.getPublishType(),
            digest.getParticipantKey(),
            digest.getBaseVersionId(),
            digest.getScopeValue(),
            digest.getAppliedCommitId())
        .orElseThrow();
  }

  private RecordedParticipantDigest toEntity(Record record) {
    if (record == null) {
      return null;
    }
    RecordedParticipantDigest digest = new RecordedParticipantDigest();
    digest.setId(record.get(ID));
    digest.setTenantId(record.get(TENANT_ID));
    String publishType = record.get(PUBLISH_TYPE);
    digest.setPublishType(publishType == null ? null : PublishType.valueOf(publishType));
    String participantKey = record.get(PARTICIPANT_KEY);
    digest.setParticipantKey(
        participantKey == null ? null : PublishParticipantKey.valueOf(participantKey));
    digest.setScopeValue(record.get(SCOPE_VALUE));
    digest.setBaseVersionId(record.get(BASE_VERSION_ID));
    digest.setAppliedCommitId(record.get(APPLIED_COMMIT_ID));
    digest.setContentDigest(record.get(CONTENT_DIGEST));
    digest.setDigestSchemaVersion(record.get(DIGEST_SCHEMA_VERSION));
    digest.setRecordedFromPublishWorkflowId(record.get(RECORDED_FROM_PUBLISH_WORKFLOW_ID));
    digest.setRecordedAt(record.get(RECORDED_AT));
    digest.setLastVerifiedPublishWorkflowId(record.get(LAST_VERIFIED_PUBLISH_WORKFLOW_ID));
    digest.setLastVerifiedAt(record.get(LAST_VERIFIED_AT));
    return digest;
  }
}
