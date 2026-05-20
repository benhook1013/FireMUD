package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import net.firedevops.firemud.gamedesign.entity.PublishAttemptParticipantDigest;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
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
public class PublishAttemptParticipantDigestRepository {
  private static final Table<?> TABLE_REF =
      DSL.table(DSL.name("publish_attempt_participant_digest"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<Long> PUBLISH_ATTEMPT_ID =
      DSL.field(DSL.name("publish_attempt_id"), Long.class);
  private static final Field<String> PARTICIPANT_KEY =
      DSL.field(DSL.name("participant_key"), String.class);
  private static final Field<String> SCOPE_VALUE = DSL.field(DSL.name("scope_value"), String.class);
  private static final Field<String> APPLIED_COMMIT_ID =
      DSL.field(DSL.name("applied_commit_id"), String.class);
  private static final Field<String> CONTENT_DIGEST =
      DSL.field(DSL.name("content_digest"), String.class);
  private static final Field<Integer> DIGEST_SCHEMA_VERSION =
      DSL.field(DSL.name("digest_schema_version"), Integer.class);
  private static final Field<String> ERROR_CODE = DSL.field(DSL.name("error_code"), String.class);
  private static final Field<String> ERROR_MESSAGE =
      DSL.field(DSL.name("error_message"), String.class);
  private static final Field<LocalDateTime> OBSERVED_AT =
      DSL.field(DSL.name("observed_at"), LocalDateTime.class);

  private final DSLContext dsl;

  public PublishAttemptParticipantDigestRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public PublishAttemptParticipantDigest save(PublishAttemptParticipantDigest digest) {
    LocalDateTime observedAt =
        digest.getObservedAt() == null ? LocalDateTime.now() : digest.getObservedAt();
    if (digest.getId() == null) {
      Record record =
          dsl.insertInto(TABLE_REF)
              .set(PUBLISH_ATTEMPT_ID, digest.getPublishAttemptId())
              .set(PARTICIPANT_KEY, digest.getParticipantKey().name())
              .set(SCOPE_VALUE, digest.getScopeValue())
              .set(APPLIED_COMMIT_ID, digest.getAppliedCommitId())
              .set(CONTENT_DIGEST, digest.getContentDigest())
              .set(DIGEST_SCHEMA_VERSION, digest.getDigestSchemaVersion())
              .set(ERROR_CODE, digest.getErrorCode())
              .set(ERROR_MESSAGE, digest.getErrorMessage())
              .set(OBSERVED_AT, observedAt)
              .returning()
              .fetchOne();
      return toEntity(record);
    }
    dsl.update(TABLE_REF)
        .set(PUBLISH_ATTEMPT_ID, digest.getPublishAttemptId())
        .set(PARTICIPANT_KEY, digest.getParticipantKey().name())
        .set(SCOPE_VALUE, digest.getScopeValue())
        .set(APPLIED_COMMIT_ID, digest.getAppliedCommitId())
        .set(CONTENT_DIGEST, digest.getContentDigest())
        .set(DIGEST_SCHEMA_VERSION, digest.getDigestSchemaVersion())
        .set(ERROR_CODE, digest.getErrorCode())
        .set(ERROR_MESSAGE, digest.getErrorMessage())
        .set(OBSERVED_AT, observedAt)
        .where(ID.eq(digest.getId()))
        .execute();
    return digest;
  }

  public void deleteByPublishAttemptId(Long publishAttemptId) {
    dsl.deleteFrom(TABLE_REF).where(PUBLISH_ATTEMPT_ID.eq(publishAttemptId)).execute();
  }

  private PublishAttemptParticipantDigest toEntity(Record record) {
    if (record == null) {
      return null;
    }
    PublishAttemptParticipantDigest digest = new PublishAttemptParticipantDigest();
    digest.setId(record.get(ID));
    digest.setPublishAttemptId(record.get(PUBLISH_ATTEMPT_ID));
    String participantKey = record.get(PARTICIPANT_KEY);
    digest.setParticipantKey(
        participantKey == null ? null : PublishParticipantKey.valueOf(participantKey));
    digest.setScopeValue(record.get(SCOPE_VALUE));
    digest.setAppliedCommitId(record.get(APPLIED_COMMIT_ID));
    digest.setContentDigest(record.get(CONTENT_DIGEST));
    digest.setDigestSchemaVersion(record.get(DIGEST_SCHEMA_VERSION));
    digest.setErrorCode(record.get(ERROR_CODE));
    digest.setErrorMessage(record.get(ERROR_MESSAGE));
    digest.setObservedAt(record.get(OBSERVED_AT));
    return digest;
  }
}
