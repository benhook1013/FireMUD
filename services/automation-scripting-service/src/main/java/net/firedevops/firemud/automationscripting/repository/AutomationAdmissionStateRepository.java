package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.AutomationAdmissionStates.AUTOMATION_ADMISSION_STATES;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionState;
import net.firedevops.firemud.automationscripting.jooq.tables.records.AutomationAdmissionStatesRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AutomationAdmissionStateRepository {
  private final DSLContext dsl;

  public AutomationAdmissionStateRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<AutomationAdmissionState> findByTenantIdAndGameInstanceIdAndRegionId(
      String tenantId, String gameInstanceId, String regionId) {
    return dsl.selectFrom(AUTOMATION_ADMISSION_STATES)
        .where(
            AUTOMATION_ADMISSION_STATES
                .TENANT_ID
                .eq(tenantId)
                .and(AUTOMATION_ADMISSION_STATES.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(AUTOMATION_ADMISSION_STATES.REGION_ID.eq(regionId)))
        .fetchOptional(this::toEntity);
  }

  public AutomationAdmissionState save(AutomationAdmissionState entity) {
    if (entity.getId() == null) {
      AutomationAdmissionStatesRecord record = dsl.newRecord(AUTOMATION_ADMISSION_STATES);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(AUTOMATION_ADMISSION_STATES)
            .set(AUTOMATION_ADMISSION_STATES.TENANT_ID, entity.getTenantId())
            .set(AUTOMATION_ADMISSION_STATES.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(AUTOMATION_ADMISSION_STATES.REGION_ID, entity.getRegionId())
            .set(AUTOMATION_ADMISSION_STATES.MODE, entity.getMode())
            .set(AUTOMATION_ADMISSION_STATES.ADMISSION_EPOCH, entity.getAdmissionEpoch())
            .set(
                AUTOMATION_ADMISSION_STATES.CONTROL_PLANE_REQUEST_ID,
                entity.getControlPlaneRequestId())
            .set(AUTOMATION_ADMISSION_STATES.ACTOR_PRINCIPAL, entity.getActorPrincipal())
            .set(AUTOMATION_ADMISSION_STATES.REASON, entity.getReason())
            .set(AUTOMATION_ADMISSION_STATES.CREATED_AT, toLocalDateTime(entity.getCreatedAt()))
            .set(AUTOMATION_ADMISSION_STATES.UPDATED_AT, toLocalDateTime(entity.getUpdatedAt()))
            .set(AUTOMATION_ADMISSION_STATES.ROW_VERSION, nextRowVersion)
            .where(
                AUTOMATION_ADMISSION_STATES
                    .ID
                    .eq(entity.getId())
                    .and(AUTOMATION_ADMISSION_STATES.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "automation_admission_states", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<AutomationAdmissionState> findById(Long id) {
    return dsl.selectFrom(AUTOMATION_ADMISSION_STATES)
        .where(AUTOMATION_ADMISSION_STATES.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(AutomationAdmissionStatesRecord record, AutomationAdmissionState entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setRegionId(entity.getRegionId());
    record.setMode(entity.getMode());
    record.setAdmissionEpoch(entity.getAdmissionEpoch());
    record.setControlPlaneRequestId(entity.getControlPlaneRequestId());
    record.setActorPrincipal(entity.getActorPrincipal());
    record.setReason(entity.getReason());
    record.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
    record.setUpdatedAt(toLocalDateTime(entity.getUpdatedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private AutomationAdmissionState toEntity(Record record) {
    AutomationAdmissionState entity = new AutomationAdmissionState();
    entity.setId(record.get(AUTOMATION_ADMISSION_STATES.ID));
    entity.setTenantId(record.get(AUTOMATION_ADMISSION_STATES.TENANT_ID));
    entity.setGameInstanceId(record.get(AUTOMATION_ADMISSION_STATES.GAME_INSTANCE_ID));
    entity.setRegionId(record.get(AUTOMATION_ADMISSION_STATES.REGION_ID));
    entity.setMode(record.get(AUTOMATION_ADMISSION_STATES.MODE));
    Long admissionEpoch = record.get(AUTOMATION_ADMISSION_STATES.ADMISSION_EPOCH);
    entity.setAdmissionEpoch(admissionEpoch == null ? 0L : admissionEpoch);
    entity.setControlPlaneRequestId(
        record.get(AUTOMATION_ADMISSION_STATES.CONTROL_PLANE_REQUEST_ID));
    entity.setActorPrincipal(record.get(AUTOMATION_ADMISSION_STATES.ACTOR_PRINCIPAL));
    entity.setReason(record.get(AUTOMATION_ADMISSION_STATES.REASON));
    entity.setCreatedAt(toInstant(record.get(AUTOMATION_ADMISSION_STATES.CREATED_AT)));
    entity.setUpdatedAt(toInstant(record.get(AUTOMATION_ADMISSION_STATES.UPDATED_AT)));
    Integer rowVersion = record.get(AUTOMATION_ADMISSION_STATES.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
