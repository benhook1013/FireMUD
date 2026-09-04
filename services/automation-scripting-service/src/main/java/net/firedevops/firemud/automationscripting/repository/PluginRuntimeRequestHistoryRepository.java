package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.PluginRuntimeRequestHistory.PLUGIN_RUNTIME_REQUEST_HISTORY;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeRequestHistory;
import net.firedevops.firemud.automationscripting.jooq.tables.records.PluginRuntimeRequestHistoryRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PluginRuntimeRequestHistoryRepository {
  private final DSLContext dsl;

  public PluginRuntimeRequestHistoryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<PluginRuntimeRequestHistory> find(
      String tenantId, String gameInstanceId, String pluginId, String operation, String requestId) {
    return dsl.selectFrom(PLUGIN_RUNTIME_REQUEST_HISTORY)
        .where(
            PLUGIN_RUNTIME_REQUEST_HISTORY
                .TENANT_ID
                .eq(tenantId)
                .and(PLUGIN_RUNTIME_REQUEST_HISTORY.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(PLUGIN_RUNTIME_REQUEST_HISTORY.PLUGIN_ID.eq(pluginId))
                .and(PLUGIN_RUNTIME_REQUEST_HISTORY.OPERATION.eq(operation))
                .and(PLUGIN_RUNTIME_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID.eq(requestId)))
        .fetchOptional(this::toEntity);
  }

  public PluginRuntimeRequestHistory insertOrGet(PluginRuntimeRequestHistory entity) {
    PluginRuntimeRequestHistoryRecord record = dsl.newRecord(PLUGIN_RUNTIME_REQUEST_HISTORY);
    populate(record, entity);
    return dsl.insertInto(PLUGIN_RUNTIME_REQUEST_HISTORY)
        .set(record)
        .onConflict(
            PLUGIN_RUNTIME_REQUEST_HISTORY.TENANT_ID,
            PLUGIN_RUNTIME_REQUEST_HISTORY.GAME_INSTANCE_ID,
            PLUGIN_RUNTIME_REQUEST_HISTORY.PLUGIN_ID,
            PLUGIN_RUNTIME_REQUEST_HISTORY.OPERATION,
            PLUGIN_RUNTIME_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID)
        .doUpdate()
        .set(
            PLUGIN_RUNTIME_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID,
            PLUGIN_RUNTIME_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID)
        .returningResult(PLUGIN_RUNTIME_REQUEST_HISTORY.fields())
        .fetchOne(this::toEntity);
  }

  private void populate(
      PluginRuntimeRequestHistoryRecord record, PluginRuntimeRequestHistory entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setPluginId(entity.getPluginId());
    record.setOperation(entity.getOperation());
    record.setControlPlaneRequestId(entity.getControlPlaneRequestId());
    record.setRequestFingerprint(entity.getRequestFingerprint());
    record.setPreviousPluginVersionId(entity.getPreviousPluginVersionId());
    record.setActivePluginVersionId(entity.getActivePluginVersionId());
    record.setPluginActivationEpoch(entity.getPluginActivationEpoch());
    record.setLifecycleRevision(entity.getLifecycleRevision());
    record.setPluginState(entity.getPluginState());
    record.setCreatedAt(toOffsetDateTime(entity.getCreatedAt()));
  }

  private PluginRuntimeRequestHistory toEntity(Record record) {
    PluginRuntimeRequestHistory entity = new PluginRuntimeRequestHistory();
    entity.setId(record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.ID));
    entity.setTenantId(record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.TENANT_ID));
    entity.setGameInstanceId(record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.GAME_INSTANCE_ID));
    entity.setPluginId(record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.PLUGIN_ID));
    entity.setOperation(record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.OPERATION));
    entity.setControlPlaneRequestId(
        record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.CONTROL_PLANE_REQUEST_ID));
    entity.setRequestFingerprint(record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.REQUEST_FINGERPRINT));
    entity.setPreviousPluginVersionId(
        record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.PREVIOUS_PLUGIN_VERSION_ID));
    entity.setActivePluginVersionId(
        record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.ACTIVE_PLUGIN_VERSION_ID));
    entity.setPluginActivationEpoch(
        record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.PLUGIN_ACTIVATION_EPOCH));
    entity.setLifecycleRevision(record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.LIFECYCLE_REVISION));
    entity.setPluginState(record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.PLUGIN_STATE));
    entity.setCreatedAt(toInstant(record.get(PLUGIN_RUNTIME_REQUEST_HISTORY.CREATED_AT)));
    return entity;
  }
}
