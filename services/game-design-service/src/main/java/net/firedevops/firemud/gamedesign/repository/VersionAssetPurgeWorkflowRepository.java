package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.VersionAssetPurgeWorkflow;
import net.firedevops.firemud.gamedesign.model.VersionAssetPurgeWorkflowStatus;
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
public class VersionAssetPurgeWorkflowRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("version_asset_purge_workflow"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<String> PURGE_WORKFLOW_ID =
      DSL.field(DSL.name("purge_workflow_id"), String.class);
  private static final Field<String> WORKFLOW_STATUS =
      DSL.field(DSL.name("workflow_status"), String.class);
  private static final Field<Long> STARTED_FROM_STATE_EPOCH =
      DSL.field(DSL.name("started_from_state_epoch"), Long.class);
  private static final Field<String> LAST_ERROR_CODE =
      DSL.field(DSL.name("last_error_code"), String.class);
  private static final Field<String> LAST_ERROR_MESSAGE =
      DSL.field(DSL.name("last_error_message"), String.class);
  private static final Field<LocalDateTime> REQUESTED_AT =
      DSL.field(DSL.name("requested_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> UPDATED_AT =
      DSL.field(DSL.name("updated_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> COMPLETED_AT =
      DSL.field(DSL.name("completed_at"), LocalDateTime.class);

  private final DSLContext dsl;

  public VersionAssetPurgeWorkflowRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<VersionAssetPurgeWorkflow> findByTenantIdAndVersionIdAndPurgeWorkflowId(
      String tenantId, Long versionId, String purgeWorkflowId) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(
                TENANT_ID
                    .eq(tenantId)
                    .and(VERSION_ID.eq(versionId))
                    .and(PURGE_WORKFLOW_ID.eq(purgeWorkflowId)))
            .fetchOne(this::toEntity));
  }

  public VersionAssetPurgeWorkflow save(VersionAssetPurgeWorkflow workflow) {
    if (workflow.getId() == null) {
      Record record =
          dsl.insertInto(TABLE_REF)
              .set(TENANT_ID, workflow.getTenantId())
              .set(VERSION_ID, workflow.getVersionId())
              .set(PURGE_WORKFLOW_ID, workflow.getPurgeWorkflowId())
              .set(WORKFLOW_STATUS, workflow.getWorkflowStatus().name())
              .set(STARTED_FROM_STATE_EPOCH, workflow.getStartedFromStateEpoch())
              .set(LAST_ERROR_CODE, workflow.getLastErrorCode())
              .set(LAST_ERROR_MESSAGE, workflow.getLastErrorMessage())
              .set(REQUESTED_AT, workflow.getRequestedAt())
              .set(UPDATED_AT, workflow.getUpdatedAt())
              .set(COMPLETED_AT, workflow.getCompletedAt())
              .returning()
              .fetchOne();
      return toEntity(record);
    }
    dsl.update(TABLE_REF)
        .set(TENANT_ID, workflow.getTenantId())
        .set(VERSION_ID, workflow.getVersionId())
        .set(PURGE_WORKFLOW_ID, workflow.getPurgeWorkflowId())
        .set(WORKFLOW_STATUS, workflow.getWorkflowStatus().name())
        .set(STARTED_FROM_STATE_EPOCH, workflow.getStartedFromStateEpoch())
        .set(LAST_ERROR_CODE, workflow.getLastErrorCode())
        .set(LAST_ERROR_MESSAGE, workflow.getLastErrorMessage())
        .set(REQUESTED_AT, workflow.getRequestedAt())
        .set(UPDATED_AT, workflow.getUpdatedAt())
        .set(COMPLETED_AT, workflow.getCompletedAt())
        .where(ID.eq(workflow.getId()))
        .execute();
    return findByTenantIdAndVersionIdAndPurgeWorkflowId(
            workflow.getTenantId(), workflow.getVersionId(), workflow.getPurgeWorkflowId())
        .orElseThrow();
  }

  private VersionAssetPurgeWorkflow toEntity(Record record) {
    if (record == null) {
      return null;
    }
    VersionAssetPurgeWorkflow workflow = new VersionAssetPurgeWorkflow();
    workflow.setId(record.get(ID));
    workflow.setTenantId(record.get(TENANT_ID));
    workflow.setVersionId(record.get(VERSION_ID));
    workflow.setPurgeWorkflowId(record.get(PURGE_WORKFLOW_ID));
    workflow.setWorkflowStatus(
        VersionAssetPurgeWorkflowStatus.valueOf(record.get(WORKFLOW_STATUS)));
    Long startedEpoch = record.get(STARTED_FROM_STATE_EPOCH);
    workflow.setStartedFromStateEpoch(startedEpoch == null ? 0L : startedEpoch);
    workflow.setLastErrorCode(record.get(LAST_ERROR_CODE));
    workflow.setLastErrorMessage(record.get(LAST_ERROR_MESSAGE));
    workflow.setRequestedAt(record.get(REQUESTED_AT));
    workflow.setUpdatedAt(record.get(UPDATED_AT));
    workflow.setCompletedAt(record.get(COMPLETED_AT));
    return workflow;
  }
}
