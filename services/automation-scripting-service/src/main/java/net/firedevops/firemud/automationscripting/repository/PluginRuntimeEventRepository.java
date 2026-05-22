package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.PluginRuntimeEvents.PLUGIN_RUNTIME_EVENTS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeEvent;
import net.firedevops.firemud.automationscripting.jooq.tables.records.PluginRuntimeEventsRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PluginRuntimeEventRepository {
  private final DSLContext dsl;

  public PluginRuntimeEventRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<PluginRuntimeEvent> findEvents(
      String tenantId,
      String gameInstanceId,
      String pluginId,
      String pluginState,
      String activePluginVersionId,
      Instant changedAfter,
      Instant changedBefore,
      Pageable pageable) {
    Condition condition = PLUGIN_RUNTIME_EVENTS.TENANT_ID.eq(tenantId);
    if (!gameInstanceId.isBlank()) {
      condition = condition.and(PLUGIN_RUNTIME_EVENTS.GAME_INSTANCE_ID.eq(gameInstanceId));
    }
    if (!pluginId.isBlank()) {
      condition = condition.and(PLUGIN_RUNTIME_EVENTS.PLUGIN_ID.eq(pluginId));
    }
    if (!pluginState.isBlank()) {
      condition = condition.and(PLUGIN_RUNTIME_EVENTS.PLUGIN_STATE.eq(pluginState));
    }
    if (!activePluginVersionId.isBlank()) {
      condition =
          condition.and(PLUGIN_RUNTIME_EVENTS.ACTIVE_PLUGIN_VERSION_ID.eq(activePluginVersionId));
    }
    if (changedAfter != null) {
      condition =
          condition.and(PLUGIN_RUNTIME_EVENTS.OBSERVED_AT.gt(toLocalDateTime(changedAfter)));
    }
    if (changedBefore != null) {
      condition =
          condition.and(PLUGIN_RUNTIME_EVENTS.OBSERVED_AT.lt(toLocalDateTime(changedBefore)));
    }
    return dsl.selectFrom(PLUGIN_RUNTIME_EVENTS)
        .where(condition)
        .orderBy(PLUGIN_RUNTIME_EVENTS.OBSERVED_AT.desc(), PLUGIN_RUNTIME_EVENTS.EVENT_ID.desc())
        .limit(limitOrDefault(pageable, 100))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  public PluginRuntimeEvent save(PluginRuntimeEvent entity) {
    if (entity.getId() == null) {
      PluginRuntimeEventsRecord record = dsl.newRecord(PLUGIN_RUNTIME_EVENTS);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(PLUGIN_RUNTIME_EVENTS)
            .set(PLUGIN_RUNTIME_EVENTS.EVENT_ID, entity.getEventId())
            .set(PLUGIN_RUNTIME_EVENTS.TENANT_ID, entity.getTenantId())
            .set(PLUGIN_RUNTIME_EVENTS.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(PLUGIN_RUNTIME_EVENTS.RUNTIME_REGION_ID, entity.getRuntimeRegionId())
            .set(PLUGIN_RUNTIME_EVENTS.RUNTIME_REGION_EPOCH, entity.getRuntimeRegionEpoch())
            .set(PLUGIN_RUNTIME_EVENTS.PLUGIN_ID, entity.getPluginId())
            .set(
                PLUGIN_RUNTIME_EVENTS.PREVIOUS_PLUGIN_VERSION_ID,
                entity.getPreviousPluginVersionId())
            .set(PLUGIN_RUNTIME_EVENTS.ACTIVE_PLUGIN_VERSION_ID, entity.getActivePluginVersionId())
            .set(PLUGIN_RUNTIME_EVENTS.PLUGIN_STATE, entity.getPluginState())
            .set(PLUGIN_RUNTIME_EVENTS.STATUS_REASON, entity.getStatusReason())
            .set(PLUGIN_RUNTIME_EVENTS.CONTROL_PLANE_REQUEST_ID, entity.getControlPlaneRequestId())
            .set(PLUGIN_RUNTIME_EVENTS.ACTOR_PRINCIPAL, entity.getActorPrincipal())
            .set(PLUGIN_RUNTIME_EVENTS.OBSERVED_AT, toLocalDateTime(entity.getObservedAt()))
            .set(PLUGIN_RUNTIME_EVENTS.ROW_VERSION, nextRowVersion)
            .where(
                PLUGIN_RUNTIME_EVENTS
                    .ID
                    .eq(entity.getId())
                    .and(PLUGIN_RUNTIME_EVENTS.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "plugin_runtime_events", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<PluginRuntimeEvent> findById(Long id) {
    return dsl.selectFrom(PLUGIN_RUNTIME_EVENTS)
        .where(PLUGIN_RUNTIME_EVENTS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(PluginRuntimeEventsRecord record, PluginRuntimeEvent entity) {
    record.setEventId(entity.getEventId());
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setRuntimeRegionId(entity.getRuntimeRegionId());
    record.setRuntimeRegionEpoch(entity.getRuntimeRegionEpoch());
    record.setPluginId(entity.getPluginId());
    record.setPreviousPluginVersionId(entity.getPreviousPluginVersionId());
    record.setActivePluginVersionId(entity.getActivePluginVersionId());
    record.setPluginState(entity.getPluginState());
    record.setStatusReason(entity.getStatusReason());
    record.setControlPlaneRequestId(entity.getControlPlaneRequestId());
    record.setActorPrincipal(entity.getActorPrincipal());
    record.setObservedAt(toLocalDateTime(entity.getObservedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private PluginRuntimeEvent toEntity(Record record) {
    PluginRuntimeEvent entity = new PluginRuntimeEvent();
    entity.setId(record.get(PLUGIN_RUNTIME_EVENTS.ID));
    entity.setEventId(record.get(PLUGIN_RUNTIME_EVENTS.EVENT_ID));
    entity.setTenantId(record.get(PLUGIN_RUNTIME_EVENTS.TENANT_ID));
    entity.setGameInstanceId(record.get(PLUGIN_RUNTIME_EVENTS.GAME_INSTANCE_ID));
    entity.setRuntimeRegionId(record.get(PLUGIN_RUNTIME_EVENTS.RUNTIME_REGION_ID));
    entity.setRuntimeRegionEpoch(record.get(PLUGIN_RUNTIME_EVENTS.RUNTIME_REGION_EPOCH));
    entity.setPluginId(record.get(PLUGIN_RUNTIME_EVENTS.PLUGIN_ID));
    entity.setPreviousPluginVersionId(record.get(PLUGIN_RUNTIME_EVENTS.PREVIOUS_PLUGIN_VERSION_ID));
    entity.setActivePluginVersionId(record.get(PLUGIN_RUNTIME_EVENTS.ACTIVE_PLUGIN_VERSION_ID));
    entity.setPluginState(record.get(PLUGIN_RUNTIME_EVENTS.PLUGIN_STATE));
    entity.setStatusReason(record.get(PLUGIN_RUNTIME_EVENTS.STATUS_REASON));
    entity.setControlPlaneRequestId(record.get(PLUGIN_RUNTIME_EVENTS.CONTROL_PLANE_REQUEST_ID));
    entity.setActorPrincipal(record.get(PLUGIN_RUNTIME_EVENTS.ACTOR_PRINCIPAL));
    entity.setObservedAt(toInstant(record.get(PLUGIN_RUNTIME_EVENTS.OBSERVED_AT)));
    Integer rowVersion = record.get(PLUGIN_RUNTIME_EVENTS.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
