package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.PluginRuntimeStates.PLUGIN_RUNTIME_STATES;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.jooq.tables.records.PluginRuntimeStatesRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PluginRuntimeStateRepository {
  private final DSLContext dsl;

  public PluginRuntimeStateRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<PluginRuntimeState> findByTenantIdAndGameInstanceIdAndPluginId(
      String tenantId, String gameInstanceId, String pluginId) {
    return dsl.selectFrom(PLUGIN_RUNTIME_STATES)
        .where(
            PLUGIN_RUNTIME_STATES
                .TENANT_ID
                .eq(tenantId)
                .and(PLUGIN_RUNTIME_STATES.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(PLUGIN_RUNTIME_STATES.PLUGIN_ID.eq(pluginId)))
        .fetchOptional(this::toEntity);
  }

  public List<PluginRuntimeState> findByTenantIdAndGameInstanceId(
      String tenantId, String gameInstanceId) {
    return dsl.selectFrom(PLUGIN_RUNTIME_STATES)
        .where(
            PLUGIN_RUNTIME_STATES
                .TENANT_ID
                .eq(tenantId)
                .and(PLUGIN_RUNTIME_STATES.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .fetch(this::toEntity);
  }

  public List<PluginRuntimeState>
      findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
          String pluginState, String activePluginVersionId, Pageable pageable) {
    return findByScopeAndPluginStateNot("", "", pluginState, activePluginVersionId, pageable);
  }

  public List<PluginRuntimeState>
      findByTenantIdAndPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
          String tenantId, String pluginState, String activePluginVersionId, Pageable pageable) {
    return findByScopeAndPluginStateNot(tenantId, "", pluginState, activePluginVersionId, pageable);
  }

  public List<PluginRuntimeState>
      findByTenantIdAndGameInstanceIdAndPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
          String tenantId,
          String gameInstanceId,
          String pluginState,
          String activePluginVersionId,
          Pageable pageable) {
    return findByScopeAndPluginStateNot(
        tenantId, gameInstanceId, pluginState, activePluginVersionId, pageable);
  }

  public PluginRuntimeState save(PluginRuntimeState entity) {
    if (entity.getId() == null) {
      PluginRuntimeStatesRecord record = dsl.newRecord(PLUGIN_RUNTIME_STATES);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(PLUGIN_RUNTIME_STATES)
            .set(PLUGIN_RUNTIME_STATES.TENANT_ID, entity.getTenantId())
            .set(PLUGIN_RUNTIME_STATES.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(PLUGIN_RUNTIME_STATES.RUNTIME_REGION_ID, entity.getRuntimeRegionId())
            .set(PLUGIN_RUNTIME_STATES.RUNTIME_REGION_EPOCH, entity.getRuntimeRegionEpoch())
            .set(PLUGIN_RUNTIME_STATES.PLUGIN_ID, entity.getPluginId())
            .set(PLUGIN_RUNTIME_STATES.ACTIVE_PLUGIN_VERSION_ID, entity.getActivePluginVersionId())
            .set(PLUGIN_RUNTIME_STATES.PLUGIN_ACTIVATION_EPOCH, entity.getPluginActivationEpoch())
            .set(PLUGIN_RUNTIME_STATES.LIFECYCLE_REVISION, entity.getLifecycleRevision())
            .set(
                PLUGIN_RUNTIME_STATES.PENDING_PLUGIN_VERSION_ID, entity.getPendingPluginVersionId())
            .set(PLUGIN_RUNTIME_STATES.PLUGIN_STATE, entity.getPluginState())
            .set(PLUGIN_RUNTIME_STATES.STATUS_REASON, entity.getStatusReason())
            .set(PLUGIN_RUNTIME_STATES.CONTROL_PLANE_REQUEST_ID, entity.getControlPlaneRequestId())
            .set(PLUGIN_RUNTIME_STATES.ACTOR_PRINCIPAL, entity.getActorPrincipal())
            .set(PLUGIN_RUNTIME_STATES.LAST_CHANGED_AT, toLocalDateTime(entity.getLastChangedAt()))
            .set(
                PLUGIN_RUNTIME_STATES.LAST_POLICY_CHECKED_AT,
                toLocalDateTime(entity.getLastPolicyCheckedAt()))
            .set(PLUGIN_RUNTIME_STATES.ROW_VERSION, nextRowVersion)
            .where(
                PLUGIN_RUNTIME_STATES
                    .ID
                    .eq(entity.getId())
                    .and(PLUGIN_RUNTIME_STATES.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "plugin_runtime_states", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private List<PluginRuntimeState> findByScopeAndPluginStateNot(
      String tenantId,
      String gameInstanceId,
      String pluginState,
      String activePluginVersionId,
      Pageable pageable) {
    var condition = PLUGIN_RUNTIME_STATES.PLUGIN_STATE.eq(pluginState);
    if (!tenantId.isBlank()) {
      condition = condition.and(PLUGIN_RUNTIME_STATES.TENANT_ID.eq(tenantId));
    }
    if (!gameInstanceId.isBlank()) {
      condition = condition.and(PLUGIN_RUNTIME_STATES.GAME_INSTANCE_ID.eq(gameInstanceId));
    }
    condition =
        condition.and(
            org.jooq
                .impl
                .DSL
                .coalesce(PLUGIN_RUNTIME_STATES.ACTIVE_PLUGIN_VERSION_ID, "")
                .ne(activePluginVersionId));
    return dsl.selectFrom(PLUGIN_RUNTIME_STATES)
        .where(condition)
        .orderBy(PLUGIN_RUNTIME_STATES.LAST_CHANGED_AT.asc())
        .limit(limitOrDefault(pageable, 100))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  private Optional<PluginRuntimeState> findById(Long id) {
    return dsl.selectFrom(PLUGIN_RUNTIME_STATES)
        .where(PLUGIN_RUNTIME_STATES.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(PluginRuntimeStatesRecord record, PluginRuntimeState entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setRuntimeRegionId(entity.getRuntimeRegionId());
    record.setRuntimeRegionEpoch(entity.getRuntimeRegionEpoch());
    record.setPluginId(entity.getPluginId());
    record.setActivePluginVersionId(entity.getActivePluginVersionId());
    record.setPluginActivationEpoch(entity.getPluginActivationEpoch());
    record.setLifecycleRevision(entity.getLifecycleRevision());
    record.setPendingPluginVersionId(entity.getPendingPluginVersionId());
    record.setPluginState(entity.getPluginState());
    record.setStatusReason(entity.getStatusReason());
    record.setControlPlaneRequestId(entity.getControlPlaneRequestId());
    record.setActorPrincipal(entity.getActorPrincipal());
    record.setLastChangedAt(toLocalDateTime(entity.getLastChangedAt()));
    record.setLastPolicyCheckedAt(toLocalDateTime(entity.getLastPolicyCheckedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private PluginRuntimeState toEntity(Record record) {
    PluginRuntimeState entity = new PluginRuntimeState();
    entity.setId(record.get(PLUGIN_RUNTIME_STATES.ID));
    entity.setTenantId(record.get(PLUGIN_RUNTIME_STATES.TENANT_ID));
    entity.setGameInstanceId(record.get(PLUGIN_RUNTIME_STATES.GAME_INSTANCE_ID));
    entity.setRuntimeRegionId(record.get(PLUGIN_RUNTIME_STATES.RUNTIME_REGION_ID));
    entity.setRuntimeRegionEpoch(record.get(PLUGIN_RUNTIME_STATES.RUNTIME_REGION_EPOCH));
    entity.setPluginId(record.get(PLUGIN_RUNTIME_STATES.PLUGIN_ID));
    entity.setActivePluginVersionId(record.get(PLUGIN_RUNTIME_STATES.ACTIVE_PLUGIN_VERSION_ID));
    Long pluginActivationEpoch = record.get(PLUGIN_RUNTIME_STATES.PLUGIN_ACTIVATION_EPOCH);
    entity.setPluginActivationEpoch(pluginActivationEpoch == null ? 0L : pluginActivationEpoch);
    Long lifecycleRevision = record.get(PLUGIN_RUNTIME_STATES.LIFECYCLE_REVISION);
    entity.setLifecycleRevision(lifecycleRevision == null ? 0L : lifecycleRevision);
    entity.setPendingPluginVersionId(record.get(PLUGIN_RUNTIME_STATES.PENDING_PLUGIN_VERSION_ID));
    entity.setPluginState(record.get(PLUGIN_RUNTIME_STATES.PLUGIN_STATE));
    entity.setStatusReason(record.get(PLUGIN_RUNTIME_STATES.STATUS_REASON));
    entity.setControlPlaneRequestId(record.get(PLUGIN_RUNTIME_STATES.CONTROL_PLANE_REQUEST_ID));
    entity.setActorPrincipal(record.get(PLUGIN_RUNTIME_STATES.ACTOR_PRINCIPAL));
    entity.setLastChangedAt(toInstant(record.get(PLUGIN_RUNTIME_STATES.LAST_CHANGED_AT)));
    entity.setLastPolicyCheckedAt(
        toInstant(record.get(PLUGIN_RUNTIME_STATES.LAST_POLICY_CHECKED_AT)));
    Integer rowVersion = record.get(PLUGIN_RUNTIME_STATES.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
