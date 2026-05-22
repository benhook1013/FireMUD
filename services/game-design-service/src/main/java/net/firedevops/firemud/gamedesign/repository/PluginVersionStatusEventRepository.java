package net.firedevops.firemud.gamedesign.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.gamedesign.entity.PluginVersionStatusEvent;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PluginVersionStatusEventRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("plugin_version_status_events"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> EVENT_ID = DSL.field(DSL.name("event_id"), String.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> PLUGIN_ID = DSL.field(DSL.name("plugin_id"), String.class);
  private static final Field<String> PLUGIN_VERSION_ID =
      DSL.field(DSL.name("plugin_version_id"), String.class);
  private static final Field<String> PREVIOUS_PUBLICATION_STATE =
      DSL.field(DSL.name("previous_publication_state"), String.class);
  private static final Field<String> NEW_PUBLICATION_STATE =
      DSL.field(DSL.name("new_publication_state"), String.class);
  private static final Field<String> STATUS_REASON =
      DSL.field(DSL.name("status_reason"), String.class);
  private static final Field<Instant> OBSERVED_AT =
      DSL.field(DSL.name("observed_at"), Instant.class);
  private static final Field<Integer> ROW_VERSION =
      DSL.field(DSL.name("row_version"), Integer.class);

  private final DSLContext dsl;

  public PluginVersionStatusEventRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<PluginVersionStatusEvent> findEvents(
      String tenantId,
      String pluginId,
      String pluginVersionId,
      VersionLifecycleState newPublicationState,
      Instant changedAfter,
      Instant changedBefore,
      Pageable pageable) {
    Condition condition = TENANT_ID.eq(tenantId);
    if (pluginId != null && !pluginId.isBlank()) {
      condition = condition.and(PLUGIN_ID.eq(pluginId));
    }
    if (pluginVersionId != null && !pluginVersionId.isBlank()) {
      condition = condition.and(PLUGIN_VERSION_ID.eq(pluginVersionId));
    }
    if (newPublicationState != null) {
      condition = condition.and(NEW_PUBLICATION_STATE.eq(newPublicationState.name()));
    }
    if (changedAfter != null) {
      condition = condition.and(OBSERVED_AT.gt(changedAfter));
    }
    if (changedBefore != null) {
      condition = condition.and(OBSERVED_AT.lt(changedBefore));
    }
    return dsl.selectFrom(TABLE_REF)
        .where(condition)
        .orderBy(OBSERVED_AT.desc(), EVENT_ID.desc())
        .limit(limitOrDefault(pageable, Integer.MAX_VALUE))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  public PluginVersionStatusEvent save(PluginVersionStatusEvent event) {
    if (event.getId() == null) {
      Record record =
          dsl.insertInto(TABLE_REF)
              .set(EVENT_ID, event.getEventId())
              .set(TENANT_ID, event.getTenantId())
              .set(PLUGIN_ID, event.getPluginId())
              .set(PLUGIN_VERSION_ID, event.getPluginVersionId())
              .set(PREVIOUS_PUBLICATION_STATE, event.getPreviousPublicationState().name())
              .set(NEW_PUBLICATION_STATE, event.getNewPublicationState().name())
              .set(STATUS_REASON, event.getStatusReason())
              .set(OBSERVED_AT, event.getObservedAt())
              .set(ROW_VERSION, event.getRowVersion())
              .returning()
              .fetchOne();
      return toEntity(record);
    }
    dsl.update(TABLE_REF)
        .set(EVENT_ID, event.getEventId())
        .set(TENANT_ID, event.getTenantId())
        .set(PLUGIN_ID, event.getPluginId())
        .set(PLUGIN_VERSION_ID, event.getPluginVersionId())
        .set(PREVIOUS_PUBLICATION_STATE, event.getPreviousPublicationState().name())
        .set(NEW_PUBLICATION_STATE, event.getNewPublicationState().name())
        .set(STATUS_REASON, event.getStatusReason())
        .set(OBSERVED_AT, event.getObservedAt())
        .set(ROW_VERSION, event.getRowVersion())
        .where(ID.eq(event.getId()))
        .execute();
    return dsl.selectFrom(TABLE_REF).where(ID.eq(event.getId())).fetchOne(this::toEntity);
  }

  private PluginVersionStatusEvent toEntity(Record record) {
    if (record == null) {
      return null;
    }
    PluginVersionStatusEvent event = new PluginVersionStatusEvent();
    event.setId(record.get(ID));
    event.setEventId(record.get(EVENT_ID));
    event.setTenantId(record.get(TENANT_ID));
    event.setPluginId(record.get(PLUGIN_ID));
    event.setPluginVersionId(record.get(PLUGIN_VERSION_ID));
    event.setPreviousPublicationState(
        VersionLifecycleState.valueOf(record.get(PREVIOUS_PUBLICATION_STATE)));
    event.setNewPublicationState(VersionLifecycleState.valueOf(record.get(NEW_PUBLICATION_STATE)));
    event.setStatusReason(record.get(STATUS_REASON));
    event.setObservedAt(record.get(OBSERVED_AT));
    Integer rowVersion = record.get(ROW_VERSION);
    event.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return event;
  }
}
