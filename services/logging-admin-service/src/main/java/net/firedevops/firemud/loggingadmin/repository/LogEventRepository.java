package net.firedevops.firemud.loggingadmin.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.loggingadmin.jooq.tables.LogEvents.LOG_EVENTS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.loggingadmin.entity.LogEvent;
import net.firedevops.firemud.loggingadmin.jooq.tables.records.LogEventsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class LogEventRepository {
  private final DSLContext dsl;

  public LogEventRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public long count() {
    return dsl.fetchCount(LOG_EVENTS);
  }

  public List<LogEvent> findByTenantIdAndMessageContainingIgnoreCase(
      Long tenantId, String message) {
    String filter = message == null ? "" : message;
    return dsl.selectFrom(LOG_EVENTS)
        .where(LOG_EVENTS.TENANT_ID.eq(tenantId).and(LOG_EVENTS.MESSAGE.containsIgnoreCase(filter)))
        .fetch(this::toEntity);
  }

  public Optional<LogEvent> findFirstByTenantIdAndTypeAndMessage(
      Long tenantId, String type, String message) {
    return dsl.selectFrom(LOG_EVENTS)
        .where(
            LOG_EVENTS
                .TENANT_ID
                .eq(tenantId)
                .and(LOG_EVENTS.TYPE.eq(type))
                .and(LOG_EVENTS.MESSAGE.eq(message)))
        .orderBy(LOG_EVENTS.ID.asc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public LogEvent save(LogEvent entity) {
    if (entity.getId() == null) {
      LogEventsRecord record = dsl.newRecord(LOG_EVENTS);
      populate(record, entity);
      record.store();
      entity.setId(record.getId());
      return entity;
    }
    int updated =
        dsl.update(LOG_EVENTS)
            .set(LOG_EVENTS.TENANT_ID, entity.getTenantId())
            .set(LOG_EVENTS.TYPE, entity.getType())
            .set(LOG_EVENTS.MESSAGE, entity.getMessage())
            .set(LOG_EVENTS.TIMESTAMP, toLocalDateTime(entity.getTimestamp()))
            .set(LOG_EVENTS.ACCOUNT_ID, entity.getAccountId())
            .where(LOG_EVENTS.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update log_events id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<LogEvent> findById(Long id) {
    return dsl.selectFrom(LOG_EVENTS).where(LOG_EVENTS.ID.eq(id)).fetchOptional(this::toEntity);
  }

  private void populate(LogEventsRecord record, LogEvent entity) {
    record.setTenantId(entity.getTenantId());
    record.setType(entity.getType());
    record.setMessage(entity.getMessage());
    record.setTimestamp(toLocalDateTime(entity.getTimestamp()));
    record.setAccountId(entity.getAccountId());
  }

  private LogEvent toEntity(Record record) {
    LogEvent entity = new LogEvent();
    entity.setId(record.get(LOG_EVENTS.ID));
    entity.setTenantId(record.get(LOG_EVENTS.TENANT_ID));
    entity.setType(record.get(LOG_EVENTS.TYPE));
    entity.setMessage(record.get(LOG_EVENTS.MESSAGE));
    entity.setTimestamp(toInstant(record.get(LOG_EVENTS.TIMESTAMP)));
    entity.setAccountId(record.get(LOG_EVENTS.ACCOUNT_ID));
    return entity;
  }
}
