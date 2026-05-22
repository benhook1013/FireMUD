package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.PreparedVersionUpgrade.PREPARED_VERSION_UPGRADE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.PreparedVersionUpgrade;
import net.firedevops.firemud.gamesession.jooq.tables.records.PreparedVersionUpgradeRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PreparedVersionUpgradeRepository {
  private final DSLContext dsl;

  public PreparedVersionUpgradeRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<PreparedVersionUpgrade> findByPreparationId(String preparationId) {
    return dsl.selectFrom(PREPARED_VERSION_UPGRADE)
        .where(PREPARED_VERSION_UPGRADE.PREPARATION_ID.eq(preparationId))
        .fetchOptional(this::toEntity);
  }

  public Optional<PreparedVersionUpgrade> findByTenantIdAndControlPlaneRequestId(
      Long tenantId, String controlPlaneRequestId) {
    return dsl.selectFrom(PREPARED_VERSION_UPGRADE)
        .where(
            PREPARED_VERSION_UPGRADE
                .TENANT_ID
                .eq(tenantId)
                .and(PREPARED_VERSION_UPGRADE.CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId)))
        .fetchOptional(this::toEntity);
  }

  public PreparedVersionUpgrade save(PreparedVersionUpgrade entity) {
    if (entity.getId() == null) {
      PreparedVersionUpgradeRecord record = dsl.newRecord(PREPARED_VERSION_UPGRADE);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(PREPARED_VERSION_UPGRADE)
            .set(PREPARED_VERSION_UPGRADE.PREPARATION_ID, entity.getPreparationId())
            .set(
                PREPARED_VERSION_UPGRADE.CONTROL_PLANE_REQUEST_ID,
                entity.getControlPlaneRequestId())
            .set(PREPARED_VERSION_UPGRADE.TENANT_ID, entity.getTenantId())
            .set(PREPARED_VERSION_UPGRADE.SOURCE_GAME_INSTANCE_ID, entity.getSourceGameInstanceId())
            .set(PREPARED_VERSION_UPGRADE.SOURCE_VERSION_ID, entity.getSourceVersionId())
            .set(PREPARED_VERSION_UPGRADE.TARGET_VERSION_ID, entity.getTargetVersionId())
            .set(
                PREPARED_VERSION_UPGRADE.TARGET_LAUNCH_DESCRIPTOR_ID,
                entity.getTargetLaunchDescriptorId())
            .set(PREPARED_VERSION_UPGRADE.REMAP_SET_ID, entity.getRemapSetId())
            .set(PREPARED_VERSION_UPGRADE.RESULT, entity.getResult())
            .set(PREPARED_VERSION_UPGRADE.REASONS_JSON, entity.getReasonsJson())
            .set(
                PREPARED_VERSION_UPGRADE.CHECKED_PARTICIPANTS_JSON,
                entity.getCheckedParticipantsJson())
            .set(
                PREPARED_VERSION_UPGRADE.PARTICIPANT_RESULTS_JSON,
                entity.getParticipantResultsJson())
            .set(PREPARED_VERSION_UPGRADE.CHECKED_AT, toLocalDateTime(entity.getCheckedAt()))
            .set(
                PREPARED_VERSION_UPGRADE.EXECUTED_TARGET_GAME_INSTANCE_ID,
                entity.getExecutedTargetGameInstanceId())
            .set(
                PREPARED_VERSION_UPGRADE.EXECUTED_POINTER_VERSION,
                entity.getExecutedPointerVersion())
            .set(PREPARED_VERSION_UPGRADE.EXECUTED_AT, toLocalDateTime(entity.getExecutedAt()))
            .set(
                PREPARED_VERSION_UPGRADE.EXECUTION_CONTROL_PLANE_REQUEST_ID,
                entity.getExecutionControlPlaneRequestId())
            .where(PREPARED_VERSION_UPGRADE.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException(
          "Failed to update prepared_version_upgrade id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<PreparedVersionUpgrade> findById(Long id) {
    return dsl.selectFrom(PREPARED_VERSION_UPGRADE)
        .where(PREPARED_VERSION_UPGRADE.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(PreparedVersionUpgradeRecord record, PreparedVersionUpgrade entity) {
    record.setPreparationId(entity.getPreparationId());
    record.setControlPlaneRequestId(entity.getControlPlaneRequestId());
    record.setTenantId(entity.getTenantId());
    record.setSourceGameInstanceId(entity.getSourceGameInstanceId());
    record.setSourceVersionId(entity.getSourceVersionId());
    record.setTargetVersionId(entity.getTargetVersionId());
    record.setTargetLaunchDescriptorId(entity.getTargetLaunchDescriptorId());
    record.setRemapSetId(entity.getRemapSetId());
    record.setResult(entity.getResult());
    record.setReasonsJson(entity.getReasonsJson());
    record.setCheckedParticipantsJson(entity.getCheckedParticipantsJson());
    record.setParticipantResultsJson(entity.getParticipantResultsJson());
    record.setCheckedAt(toLocalDateTime(entity.getCheckedAt()));
    record.setExecutedTargetGameInstanceId(entity.getExecutedTargetGameInstanceId());
    record.setExecutedPointerVersion(entity.getExecutedPointerVersion());
    record.setExecutedAt(toLocalDateTime(entity.getExecutedAt()));
    record.setExecutionControlPlaneRequestId(entity.getExecutionControlPlaneRequestId());
  }

  private PreparedVersionUpgrade toEntity(Record record) {
    PreparedVersionUpgrade entity = new PreparedVersionUpgrade();
    entity.setId(record.get(PREPARED_VERSION_UPGRADE.ID));
    entity.setPreparationId(record.get(PREPARED_VERSION_UPGRADE.PREPARATION_ID));
    entity.setControlPlaneRequestId(record.get(PREPARED_VERSION_UPGRADE.CONTROL_PLANE_REQUEST_ID));
    entity.setTenantId(record.get(PREPARED_VERSION_UPGRADE.TENANT_ID));
    entity.setSourceGameInstanceId(record.get(PREPARED_VERSION_UPGRADE.SOURCE_GAME_INSTANCE_ID));
    entity.setSourceVersionId(record.get(PREPARED_VERSION_UPGRADE.SOURCE_VERSION_ID));
    entity.setTargetVersionId(record.get(PREPARED_VERSION_UPGRADE.TARGET_VERSION_ID));
    entity.setTargetLaunchDescriptorId(
        record.get(PREPARED_VERSION_UPGRADE.TARGET_LAUNCH_DESCRIPTOR_ID));
    entity.setRemapSetId(record.get(PREPARED_VERSION_UPGRADE.REMAP_SET_ID));
    entity.setResult(record.get(PREPARED_VERSION_UPGRADE.RESULT));
    entity.setReasonsJson(record.get(PREPARED_VERSION_UPGRADE.REASONS_JSON));
    entity.setCheckedParticipantsJson(
        record.get(PREPARED_VERSION_UPGRADE.CHECKED_PARTICIPANTS_JSON));
    entity.setParticipantResultsJson(record.get(PREPARED_VERSION_UPGRADE.PARTICIPANT_RESULTS_JSON));
    entity.setCheckedAt(toInstant(record.get(PREPARED_VERSION_UPGRADE.CHECKED_AT)));
    entity.setExecutedTargetGameInstanceId(
        record.get(PREPARED_VERSION_UPGRADE.EXECUTED_TARGET_GAME_INSTANCE_ID));
    entity.setExecutedPointerVersion(record.get(PREPARED_VERSION_UPGRADE.EXECUTED_POINTER_VERSION));
    entity.setExecutedAt(toInstant(record.get(PREPARED_VERSION_UPGRADE.EXECUTED_AT)));
    entity.setExecutionControlPlaneRequestId(
        record.get(PREPARED_VERSION_UPGRADE.EXECUTION_CONTROL_PLANE_REQUEST_ID));
    return entity;
  }
}
