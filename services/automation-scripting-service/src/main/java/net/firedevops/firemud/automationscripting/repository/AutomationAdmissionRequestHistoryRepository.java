package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.AutomationAdmissionRequestHistory.AUTOMATION_ADMISSION_REQUEST_HISTORY;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionRequestHistory;
import net.firedevops.firemud.automationscripting.jooq.tables.records.AutomationAdmissionRequestHistoryRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AutomationAdmissionRequestHistoryRepository {
  private final DSLContext dsl;

  public AutomationAdmissionRequestHistoryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<AutomationAdmissionRequestHistory> find(
      String tenantId,
      String gameInstanceId,
      String regionId,
      String mode,
      String controlPlaneRequestId) {
    return dsl.selectFrom(AUTOMATION_ADMISSION_REQUEST_HISTORY)
        .where(
            AUTOMATION_ADMISSION_REQUEST_HISTORY
                .TENANT_ID
                .eq(tenantId)
                .and(AUTOMATION_ADMISSION_REQUEST_HISTORY.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(AUTOMATION_ADMISSION_REQUEST_HISTORY.REGION_ID.eq(regionId))
                .and(AUTOMATION_ADMISSION_REQUEST_HISTORY.MODE.eq(mode))
                .and(
                    AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID.eq(
                        controlPlaneRequestId)))
        .fetchOptional(this::toEntity);
  }

  public AutomationAdmissionRequestHistory insertOrGet(AutomationAdmissionRequestHistory entity) {
    AutomationAdmissionRequestHistoryRecord record =
        dsl.newRecord(AUTOMATION_ADMISSION_REQUEST_HISTORY);
    populate(record, entity);
    return dsl.insertInto(AUTOMATION_ADMISSION_REQUEST_HISTORY)
        .set(record)
        .onConflict(
            AUTOMATION_ADMISSION_REQUEST_HISTORY.TENANT_ID,
            AUTOMATION_ADMISSION_REQUEST_HISTORY.GAME_INSTANCE_ID,
            AUTOMATION_ADMISSION_REQUEST_HISTORY.REGION_ID,
            AUTOMATION_ADMISSION_REQUEST_HISTORY.MODE,
            AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID)
        .doUpdate()
        .set(
            AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID,
            AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID)
        .returningResult(AUTOMATION_ADMISSION_REQUEST_HISTORY.fields())
        .fetchOne(this::toEntity);
  }

  private void populate(
      AutomationAdmissionRequestHistoryRecord record, AutomationAdmissionRequestHistory entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setRegionId(entity.getRegionId());
    record.setMode(entity.getMode());
    record.setControlPlaneRequestId(entity.getControlPlaneRequestId());
    record.setRequestFingerprint(entity.getRequestFingerprint());
    record.setAdmissionEpoch(entity.getAdmissionEpoch());
    record.setOutcome(entity.getOutcome());
    record.setActorPrincipal(entity.getActorPrincipal());
    record.setReason(entity.getReason());
    record.setCreatedAt(toOffsetDateTime(entity.getCreatedAt()));
  }

  private AutomationAdmissionRequestHistory toEntity(Record record) {
    AutomationAdmissionRequestHistory entity = new AutomationAdmissionRequestHistory();
    entity.setId(record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.ID));
    entity.setTenantId(record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.TENANT_ID));
    entity.setGameInstanceId(record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.GAME_INSTANCE_ID));
    entity.setRegionId(record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.REGION_ID));
    entity.setMode(record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.MODE));
    entity.setControlPlaneRequestId(
        record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID));
    entity.setRequestFingerprint(
        record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.REQUEST_FINGERPRINT));
    Long admissionEpoch = record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.ADMISSION_EPOCH);
    entity.setAdmissionEpoch(admissionEpoch == null ? 0L : admissionEpoch);
    entity.setOutcome(record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.OUTCOME));
    entity.setActorPrincipal(record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.ACTOR_PRINCIPAL));
    entity.setReason(record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.REASON));
    entity.setCreatedAt(toInstant(record.get(AUTOMATION_ADMISSION_REQUEST_HISTORY.CREATED_AT)));
    return entity;
  }
}
