package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
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
public class PublishAttemptRepository {
  private static final Table<?> PUBLISH_ATTEMPT_TABLE = DSL.table(DSL.name("publish_attempt"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> PUBLISH_WORKFLOW_ID =
      DSL.field(DSL.name("publish_workflow_id"), String.class);
  private static final Field<String> PUBLISH_TYPE =
      DSL.field(DSL.name("publish_type"), String.class);
  private static final Field<String> STATUS = DSL.field(DSL.name("status"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<Integer> VERSION_NUMBER =
      DSL.field(DSL.name("version_number"), Integer.class);
  private static final Field<String> SCRIPT_PATCH_VERSION =
      DSL.field(DSL.name("script_patch_version"), String.class);
  private static final Field<String> FAILURE_CODE =
      DSL.field(DSL.name("failure_code"), String.class);
  private static final Field<String> FAILURE_MESSAGE =
      DSL.field(DSL.name("failure_message"), String.class);
  private static final Field<LocalDateTime> CREATED_AT =
      DSL.field(DSL.name("created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> COMPLETED_AT =
      DSL.field(DSL.name("completed_at"), LocalDateTime.class);

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
              .set(FAILURE_CODE, attempt.getFailureCode())
              .set(FAILURE_MESSAGE, attempt.getFailureMessage())
              .set(CREATED_AT, createdAt)
              .set(COMPLETED_AT, attempt.getCompletedAt())
              .returning()
              .fetchOne();
      return toEntity(record);
    }
    dsl.update(PUBLISH_ATTEMPT_TABLE)
        .set(TENANT_ID, attempt.getTenantId())
        .set(PUBLISH_WORKFLOW_ID, attempt.getPublishWorkflowId())
        .set(PUBLISH_TYPE, attempt.getPublishType().name())
        .set(STATUS, attempt.getStatus().name())
        .set(VERSION_ID, attempt.getVersionId())
        .set(VERSION_NUMBER, attempt.getVersionNumber())
        .set(SCRIPT_PATCH_VERSION, attempt.getScriptPatchVersion())
        .set(FAILURE_CODE, attempt.getFailureCode())
        .set(FAILURE_MESSAGE, attempt.getFailureMessage())
        .set(CREATED_AT, createdAt)
        .set(COMPLETED_AT, attempt.getCompletedAt())
        .where(ID.eq(attempt.getId()))
        .execute();
    return findByPublishWorkflowId(attempt.getPublishWorkflowId()).orElseThrow();
  }

  private PublishAttempt toEntity(Record record) {
    if (record == null) {
      return null;
    }
    PublishAttempt attempt = new PublishAttempt();
    attempt.setId(record.get(ID));
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
    attempt.setFailureCode(record.get(FAILURE_CODE));
    attempt.setFailureMessage(record.get(FAILURE_MESSAGE));
    attempt.setCreatedAt(record.get(CREATED_AT));
    attempt.setCompletedAt(record.get(COMPLETED_AT));
    return attempt;
  }
}
