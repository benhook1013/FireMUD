package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationRepository;
import org.jooq.Condition;
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
public class PublishAttemptRepository {
  private static final Table<?> PUBLISH_ATTEMPT_TABLE = DSL.table(DSL.name("publish_attempt"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> PUBLISH_WORKFLOW_ID =
      DSL.field(DSL.name("publish_workflow_id"), String.class);
  private static final Field<String> PUBLISH_TYPE =
      DSL.field(DSL.name("publish_type"), String.class);
  private static final Field<String> STATUS = DSL.field(DSL.name("status"), String.class);
  private static final Field<Long> REVISION = DSL.field(DSL.name("revision"), Long.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<Integer> VERSION_NUMBER =
      DSL.field(DSL.name("version_number"), Integer.class);
  private static final Field<String> SCRIPT_PATCH_VERSION =
      DSL.field(DSL.name("script_patch_version"), String.class);
  private static final Field<Long> BASE_VERSION_ID =
      DSL.field(DSL.name("base_version_id"), Long.class);
  private static final Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<String> FAILURE_CODE =
      DSL.field(DSL.name("failure_code"), String.class);
  private static final Field<String> FAILURE_MESSAGE =
      DSL.field(DSL.name("failure_message"), String.class);
  private static final Field<Timestamp> CREATED_AT =
      DSL.field(DSL.name("created_at"), Timestamp.class);
  private static final Field<Timestamp> COMPLETED_AT =
      DSL.field(DSL.name("completed_at"), Timestamp.class);

  private final DSLContext dsl;

  public PublishAttemptRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<PublishAttempt> findByPublishWorkflowId(String publishWorkflowId) {
    return Optional.ofNullable(
        dsl.selectFrom(PUBLISH_ATTEMPT_TABLE)
            .where(PUBLISH_WORKFLOW_ID.eq(publishWorkflowId))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  /** The owning Game lock always precedes the exact attempt lock. */
  public Optional<PublishAttempt> findByPublishWorkflowIdForUpdate(String workflow) {
    Optional<PublishAttempt> initial = findByPublishWorkflowId(workflow);
    if (initial.isEmpty()) return initial;
    dsl.fetchOne("SELECT id FROM game WHERE tenant_id = ? FOR UPDATE", initial.get().getTenantId());
    return Optional.ofNullable(
        dsl.selectFrom(PUBLISH_ATTEMPT_TABLE)
            .where(PUBLISH_WORKFLOW_ID.eq(workflow))
            .forUpdate()
            .fetchOne(this::toEntity));
  }

  public net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence
      requirePublicationPending(PublishAttempt attempt) {
    return new GameDesignPublicationOperationRepository(dsl)
        .requirePending(
            attempt.getTenantId(),
            attempt.getPublishWorkflowId(),
            attempt.getVersionId(),
            attempt.getRequestDigest())
        .world();
  }

  public void sealPublication(PublishAttempt attempt, boolean published) {
    new GameDesignPublicationOperationRepository(dsl)
        .seal(
            attempt.getTenantId(),
            attempt.getPublishWorkflowId(),
            attempt.getVersionId(),
            attempt.getRequestDigest(),
            published);
    if (published) {
      new RealmPolicyPublicationRepository(dsl)
          .retainSealedPublished(attempt.getPublishWorkflowId());
    }
  }

  public void requirePublishedOperation(PublishAttempt attempt) {
    requireTerminalOperation(attempt, "PUBLISHED");
  }

  public void requireNoPublicationOperation(PublishAttempt attempt) {
    requireTerminalOperation(attempt, "NO_PUBLICATION");
  }

  private void requireTerminalOperation(PublishAttempt attempt, String outcome) {
    var result =
        new GameDesignPublicationOperationRepository(dsl)
            .read(attempt.getPublishWorkflowId())
            .orElseThrow(() -> new IllegalStateException("PUBLICATION_OPERATION_UNAVAILABLE"));
    if (!outcome.equals(result.outcome())
        || !result.operation().selectionDigest().equals(attempt.getRequestDigest())
        || result.operation().versionId() != attempt.getVersionId()
        || !result.operation().tenantKey().equals(attempt.getTenantId())) {
      throw new IllegalStateException("PUBLICATION_OPERATION_SEALED_OR_CHANGED");
    }
  }

  /**
   * Installs a compatibility digest only once, and only on the exact legacy full-version row whose
   * identity was validated by the caller.
   *
   * <p>The conditional update makes concurrent retries idempotent: the first writer installs the
   * digest and later writers can only read the already-installed value. It deliberately does not
   * derive or replace a digest for any other attempt shape.
   */
  public Optional<PublishAttempt> backfillFullVersionRequestDigestIfAbsent(
      Long attemptId,
      String tenantId,
      String publishWorkflowId,
      Long versionId,
      int versionNumber,
      String requestDigest) {
    if (attemptId == null || requestDigest == null || requestDigest.isBlank()) {
      return Optional.empty();
    }
    Condition fullVersionAttemptIdentity =
        ID.eq(attemptId)
            .and(TENANT_ID.eq(tenantId))
            .and(PUBLISH_WORKFLOW_ID.eq(publishWorkflowId))
            .and(PUBLISH_TYPE.eq(PublishType.FULL_VERSION.name()))
            .and(VERSION_ID.eq(versionId))
            .and(VERSION_NUMBER.eq(versionNumber));
    dsl.update(PUBLISH_ATTEMPT_TABLE)
        .set(REQUEST_DIGEST, requestDigest)
        .set(REVISION, REVISION.plus(1L))
        .where(fullVersionAttemptIdentity.and(REQUEST_DIGEST.isNull()))
        .execute();
    return Optional.ofNullable(
        dsl.selectFrom(PUBLISH_ATTEMPT_TABLE)
            .where(fullVersionAttemptIdentity)
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public PublishAttempt save(PublishAttempt attempt) {
    LocalDateTime createdAt =
        attempt.getCreatedAt() == null ? LocalDateTime.now() : attempt.getCreatedAt();
    if (attempt.getId() == null) {
      Record record =
          dsl.insertInto(PUBLISH_ATTEMPT_TABLE)
              .set(TENANT_ID, attempt.getTenantId())
              .set(PUBLISH_WORKFLOW_ID, attempt.getPublishWorkflowId())
              .set(PUBLISH_TYPE, attempt.getPublishType().name())
              .set(STATUS, attempt.getStatus().name())
              .set(VERSION_ID, attempt.getVersionId())
              .set(VERSION_NUMBER, attempt.getVersionNumber())
              .set(SCRIPT_PATCH_VERSION, attempt.getScriptPatchVersion())
              .set(BASE_VERSION_ID, attempt.getBaseVersionId())
              .set(REQUEST_DIGEST, attempt.getRequestDigest())
              .set(FAILURE_CODE, attempt.getFailureCode())
              .set(FAILURE_MESSAGE, attempt.getFailureMessage())
              .set(CREATED_AT, JooqPersistenceSupport.toTimestamp(createdAt))
              .set(COMPLETED_AT, JooqPersistenceSupport.toTimestamp(attempt.getCompletedAt()))
              .returning(
                  ID,
                  TENANT_ID,
                  PUBLISH_WORKFLOW_ID,
                  PUBLISH_TYPE,
                  STATUS,
                  REVISION,
                  VERSION_ID,
                  VERSION_NUMBER,
                  SCRIPT_PATCH_VERSION,
                  BASE_VERSION_ID,
                  REQUEST_DIGEST,
                  FAILURE_CODE,
                  FAILURE_MESSAGE,
                  CREATED_AT,
                  COMPLETED_AT)
              .fetchOne();
      if (record == null) {
        throw new IllegalStateException("Publish attempt insert did not return its persisted row");
      }
      return toEntity(record);
    }
    int changed =
        dsl.update(PUBLISH_ATTEMPT_TABLE)
            .set(TENANT_ID, attempt.getTenantId())
            .set(PUBLISH_WORKFLOW_ID, attempt.getPublishWorkflowId())
            .set(PUBLISH_TYPE, attempt.getPublishType().name())
            .set(STATUS, attempt.getStatus().name())
            .set(REVISION, Math.addExact(attempt.getRevision(), 1L))
            .set(VERSION_ID, attempt.getVersionId())
            .set(VERSION_NUMBER, attempt.getVersionNumber())
            .set(SCRIPT_PATCH_VERSION, attempt.getScriptPatchVersion())
            .set(BASE_VERSION_ID, attempt.getBaseVersionId())
            .set(REQUEST_DIGEST, attempt.getRequestDigest())
            .set(FAILURE_CODE, attempt.getFailureCode())
            .set(FAILURE_MESSAGE, attempt.getFailureMessage())
            .set(CREATED_AT, JooqPersistenceSupport.toTimestamp(createdAt))
            .set(COMPLETED_AT, JooqPersistenceSupport.toTimestamp(attempt.getCompletedAt()))
            .where(
                ID.eq(attempt.getId())
                    .and(REVISION.eq(attempt.getRevision()))
                    .and(STATUS.eq(PublishAttemptStatus.PENDING.name())))
            .execute();
    if (changed != 1) throw new IllegalStateException("PUBLISH_ATTEMPT_CAS_CONFLICT");
    return findByPublishWorkflowId(attempt.getPublishWorkflowId()).orElseThrow();
  }

  private PublishAttempt toEntity(Record record) {
    if (record == null) {
      return null;
    }
    PublishAttempt attempt = new PublishAttempt();
    attempt.setId(record.get(ID));
    attempt.setRevision(record.get(REVISION));
    attempt.setTenantId(record.get(TENANT_ID));
    attempt.setPublishWorkflowId(record.get(PUBLISH_WORKFLOW_ID));
    String publishType = record.get(PUBLISH_TYPE);
    attempt.setPublishType(publishType == null ? null : PublishType.valueOf(publishType));
    String status = record.get(STATUS);
    attempt.setStatus(
        status == null ? PublishAttemptStatus.PENDING : PublishAttemptStatus.valueOf(status));
    attempt.setVersionId(record.get(VERSION_ID));
    attempt.setVersionNumber(record.get(VERSION_NUMBER));
    attempt.setScriptPatchVersion(record.get(SCRIPT_PATCH_VERSION));
    attempt.setBaseVersionId(record.get(BASE_VERSION_ID));
    attempt.setRequestDigest(record.get(REQUEST_DIGEST));
    attempt.setFailureCode(record.get(FAILURE_CODE));
    attempt.setFailureMessage(record.get(FAILURE_MESSAGE));
    attempt.setCreatedAt(JooqPersistenceSupport.toLocalDateTime(record.get(CREATED_AT)));
    attempt.setCompletedAt(JooqPersistenceSupport.toLocalDateTime(record.get(COMPLETED_AT)));
    return attempt;
  }
}
