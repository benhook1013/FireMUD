package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
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
  private static final Table<?> TABLE_REF =
      DSL.table(DSL.name("publish_recorded_participant_digest"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> PUBLISH_TYPE =
      DSL.field(DSL.name("publish_type"), String.class);
  private static final Field<String> PARTICIPANT_KEY =
      DSL.field(DSL.name("participant_key"), String.class);
  private static final Field<String> SCOPE_VALUE = DSL.field(DSL.name("scope_value"), String.class);
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
          String appliedCommitId) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(
                TENANT_ID
                    .eq(tenantId)
                    .and(PUBLISH_TYPE.eq(publishType.name()))
                    .and(PARTICIPANT_KEY.eq(participantKey.name()))
                    .and(APPLIED_COMMIT_ID.eq(appliedCommitId)))
            .limit(1)
            .fetchOne(this::toEntity));
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
