package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.TickEffect.TICK_EFFECT;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.jooq.tables.records.TickEffectRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class TickEffectRepository {
  private final DSLContext dsl;

  public TickEffectRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<TickEffect> findByTickBatchId(String tickBatchId) {
    return dsl.selectFrom(TICK_EFFECT)
        .where(TICK_EFFECT.TICK_BATCH_ID.eq(tickBatchId))
        .orderBy(TICK_EFFECT.ID.asc())
        .fetch(this::toEntity);
  }

  public List<TickEffect> findByTickBatchIdAndStatusOrderByIdAsc(
      String tickBatchId, String status) {
    return dsl.selectFrom(TICK_EFFECT)
        .where(TICK_EFFECT.TICK_BATCH_ID.eq(tickBatchId).and(TICK_EFFECT.STATUS.eq(status)))
        .orderBy(TICK_EFFECT.ID.asc())
        .fetch(this::toEntity);
  }

  public List<TickEffect> saveAll(Iterable<TickEffect> entities) {
    List<TickEffect> saved = new ArrayList<>();
    for (TickEffect entity : entities) {
      saved.add(save(entity));
    }
    return List.copyOf(saved);
  }

  public TickEffect save(TickEffect entity) {
    if (entity.getId() == null) {
      TickEffectRecord record = dsl.newRecord(TICK_EFFECT);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(TICK_EFFECT)
            .set(TICK_EFFECT.EFFECT_ID, entity.getEffectId())
            .set(TICK_EFFECT.TICK_BATCH_ID, entity.getTickBatchId())
            .set(TICK_EFFECT.COMMAND_ID, entity.getCommandId())
            .set(TICK_EFFECT.EFFECT_KEY, entity.getEffectKey())
            .set(TICK_EFFECT.EFFECT_TYPE, entity.getEffectType())
            .set(TICK_EFFECT.TARGET_AGGREGATE, entity.getTargetAggregate())
            .set(TICK_EFFECT.STATUS, entity.getStatus())
            .set(TICK_EFFECT.STAGED_AT, toLocalDateTime(entity.getStagedAt()))
            .set(TICK_EFFECT.COMPLETED_AT, toLocalDateTime(entity.getCompletedAt()))
            .set(TICK_EFFECT.FAILURE_CODE, entity.getFailureCode())
            .set(TICK_EFFECT.FAILURE_MESSAGE, entity.getFailureMessage())
            .where(TICK_EFFECT.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update tick_effect id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private java.util.Optional<TickEffect> findById(Long id) {
    return dsl.selectFrom(TICK_EFFECT).where(TICK_EFFECT.ID.eq(id)).fetchOptional(this::toEntity);
  }

  private void populate(TickEffectRecord record, TickEffect entity) {
    record.setEffectId(entity.getEffectId());
    record.setTickBatchId(entity.getTickBatchId());
    record.setCommandId(entity.getCommandId());
    record.setEffectKey(entity.getEffectKey());
    record.setEffectType(entity.getEffectType());
    record.setTargetAggregate(entity.getTargetAggregate());
    record.setStatus(entity.getStatus());
    record.setStagedAt(toLocalDateTime(entity.getStagedAt()));
    record.setCompletedAt(toLocalDateTime(entity.getCompletedAt()));
    record.setFailureCode(entity.getFailureCode());
    record.setFailureMessage(entity.getFailureMessage());
  }

  private TickEffect toEntity(Record record) {
    TickEffect entity = new TickEffect();
    entity.setId(record.get(TICK_EFFECT.ID));
    entity.setEffectId(record.get(TICK_EFFECT.EFFECT_ID));
    entity.setTickBatchId(record.get(TICK_EFFECT.TICK_BATCH_ID));
    entity.setCommandId(record.get(TICK_EFFECT.COMMAND_ID));
    entity.setEffectKey(record.get(TICK_EFFECT.EFFECT_KEY));
    entity.setEffectType(record.get(TICK_EFFECT.EFFECT_TYPE));
    entity.setTargetAggregate(record.get(TICK_EFFECT.TARGET_AGGREGATE));
    entity.setStatus(record.get(TICK_EFFECT.STATUS));
    entity.setStagedAt(toInstant(record.get(TICK_EFFECT.STAGED_AT)));
    entity.setCompletedAt(toInstant(record.get(TICK_EFFECT.COMPLETED_AT)));
    entity.setFailureCode(record.get(TICK_EFFECT.FAILURE_CODE));
    entity.setFailureMessage(record.get(TICK_EFFECT.FAILURE_MESSAGE));
    return entity;
  }
}
