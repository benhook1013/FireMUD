package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.*;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ACTOR_RESOURCE_STATES;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.entitymanagement.entity.ActorResourceState;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ActorResourceStateRepository {
  private final DSLContext dsl;

  public ActorResourceStateRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<ActorResourceState> findByTenantIdAndPlayableStateKeyAndCharacterIdOrderByStatKeyAsc(
      Long tenantId, String playableStateKey, Long characterId) {
    return dsl.selectFrom(ACTOR_RESOURCE_STATES)
        .where(
            ACTOR_RESOURCE_STATES
                .TENANT_ID
                .eq(tenantId)
                .and(ACTOR_RESOURCE_STATES.PLAYABLE_STATE_KEY.eq(playableStateKey))
                .and(ACTOR_RESOURCE_STATES.CHARACTER_ID.eq(characterId)))
        .orderBy(ACTOR_RESOURCE_STATES.STAT_KEY.asc())
        .fetch(this::toEntity);
  }

  public ActorResourceState save(ActorResourceState entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ACTOR_RESOURCE_STATES)
              .set(ACTOR_RESOURCE_STATES.TENANT_ID, entity.getTenantId())
              .set(ACTOR_RESOURCE_STATES.PLAYABLE_STATE_KEY, entity.getPlayableStateKey())
              .set(ACTOR_RESOURCE_STATES.CHARACTER_ID, entity.getCharacterId())
              .set(ACTOR_RESOURCE_STATES.STAT_KEY, entity.getStatKey())
              .set(ACTOR_RESOURCE_STATES.CURRENT_VALUE, entity.getCurrentValue())
              .set(ACTOR_RESOURCE_STATES.MAX_VALUE, entity.getMaxValue())
              .set(ACTOR_RESOURCE_STATES.BASE_VALUE, entity.getBaseValue())
              .set(ACTOR_RESOURCE_STATES.SOURCE_TYPE, entity.getSourceType())
              .set(ACTOR_RESOURCE_STATES.SOURCE_ID, entity.getSourceId())
              .set(ACTOR_RESOURCE_STATES.CREATED_AT, toOffsetDateTime(entity.getCreatedAt()))
              .set(ACTOR_RESOURCE_STATES.UPDATED_AT, toOffsetDateTime(entity.getUpdatedAt()))
              .returningResult(ACTOR_RESOURCE_STATES.ID)
              .fetchOne(ACTOR_RESOURCE_STATES.ID);
      entity.setId(id);
      return entity;
    }
    dsl.update(ACTOR_RESOURCE_STATES)
        .set(ACTOR_RESOURCE_STATES.CURRENT_VALUE, entity.getCurrentValue())
        .set(ACTOR_RESOURCE_STATES.MAX_VALUE, entity.getMaxValue())
        .set(ACTOR_RESOURCE_STATES.BASE_VALUE, entity.getBaseValue())
        .set(ACTOR_RESOURCE_STATES.SOURCE_TYPE, entity.getSourceType())
        .set(ACTOR_RESOURCE_STATES.SOURCE_ID, entity.getSourceId())
        .set(ACTOR_RESOURCE_STATES.UPDATED_AT, toOffsetDateTime(entity.getUpdatedAt()))
        .set(ACTOR_RESOURCE_STATES.VERSION, entity.getVersion() + 1)
        .where(ACTOR_RESOURCE_STATES.ID.eq(entity.getId()))
        .execute();
    entity.setVersion(entity.getVersion() + 1);
    return entity;
  }

  private ActorResourceState toEntity(Record record) {
    if (record == null) {
      return null;
    }
    ActorResourceState entity = new ActorResourceState();
    entity.setId(record.get(ACTOR_RESOURCE_STATES.ID));
    entity.setTenantId(record.get(ACTOR_RESOURCE_STATES.TENANT_ID));
    entity.setPlayableStateKey(record.get(ACTOR_RESOURCE_STATES.PLAYABLE_STATE_KEY));
    entity.setCharacterId(record.get(ACTOR_RESOURCE_STATES.CHARACTER_ID));
    entity.setStatKey(record.get(ACTOR_RESOURCE_STATES.STAT_KEY));
    entity.setCurrentValue(record.get(ACTOR_RESOURCE_STATES.CURRENT_VALUE));
    entity.setMaxValue(record.get(ACTOR_RESOURCE_STATES.MAX_VALUE));
    entity.setBaseValue(record.get(ACTOR_RESOURCE_STATES.BASE_VALUE));
    entity.setSourceType(record.get(ACTOR_RESOURCE_STATES.SOURCE_TYPE));
    entity.setSourceId(record.get(ACTOR_RESOURCE_STATES.SOURCE_ID));
    entity.setCreatedAt(toInstant(record.get(ACTOR_RESOURCE_STATES.CREATED_AT)));
    entity.setUpdatedAt(toInstant(record.get(ACTOR_RESOURCE_STATES.UPDATED_AT)));
    entity.setVersion(record.get(ACTOR_RESOURCE_STATES.VERSION));
    return entity;
  }
}
