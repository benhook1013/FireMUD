package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.*;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ACTOR_RESOURCE_STATES;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.entitymanagement.entity.ActorResourceState;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
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
                .and(ACTOR_RESOURCE_STATES.CHARACTER_ID.eq(characterId))
                .and(CharacterRepository.legacyCharacterExists(ACTOR_RESOURCE_STATES.CHARACTER_ID)))
        .orderBy(ACTOR_RESOURCE_STATES.STAT_KEY.asc())
        .fetch(this::toEntity);
  }

  public ActorResourceState save(ActorResourceState entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(
                  ACTOR_RESOURCE_STATES,
                  ACTOR_RESOURCE_STATES.TENANT_ID,
                  ACTOR_RESOURCE_STATES.PLAYABLE_STATE_KEY,
                  ACTOR_RESOURCE_STATES.CHARACTER_ID,
                  ACTOR_RESOURCE_STATES.STAT_KEY,
                  ACTOR_RESOURCE_STATES.CURRENT_VALUE,
                  ACTOR_RESOURCE_STATES.MAX_VALUE,
                  ACTOR_RESOURCE_STATES.BASE_VALUE,
                  ACTOR_RESOURCE_STATES.SOURCE_TYPE,
                  ACTOR_RESOURCE_STATES.SOURCE_ID,
                  ACTOR_RESOURCE_STATES.CREATED_AT,
                  ACTOR_RESOURCE_STATES.UPDATED_AT)
              .select(
                  dsl.select(
                          DSL.val(entity.getTenantId(), ACTOR_RESOURCE_STATES.TENANT_ID),
                          DSL.val(
                              entity.getPlayableStateKey(),
                              ACTOR_RESOURCE_STATES.PLAYABLE_STATE_KEY),
                          DSL.val(entity.getCharacterId(), ACTOR_RESOURCE_STATES.CHARACTER_ID),
                          DSL.val(entity.getStatKey(), ACTOR_RESOURCE_STATES.STAT_KEY),
                          DSL.val(entity.getCurrentValue(), ACTOR_RESOURCE_STATES.CURRENT_VALUE),
                          DSL.val(entity.getMaxValue(), ACTOR_RESOURCE_STATES.MAX_VALUE),
                          DSL.val(entity.getBaseValue(), ACTOR_RESOURCE_STATES.BASE_VALUE),
                          DSL.val(entity.getSourceType(), ACTOR_RESOURCE_STATES.SOURCE_TYPE),
                          DSL.val(entity.getSourceId(), ACTOR_RESOURCE_STATES.SOURCE_ID),
                          DSL.val(
                              toOffsetDateTime(entity.getCreatedAt()),
                              ACTOR_RESOURCE_STATES.CREATED_AT),
                          DSL.val(
                              toOffsetDateTime(entity.getUpdatedAt()),
                              ACTOR_RESOURCE_STATES.UPDATED_AT))
                      .where(
                          CharacterRepository.legacyCharacterExists(
                              DSL.val(
                                  entity.getCharacterId(), ACTOR_RESOURCE_STATES.CHARACTER_ID))))
              .returningResult(ACTOR_RESOURCE_STATES.ID)
              .fetchOne(ACTOR_RESOURCE_STATES.ID);
      if (id == null) {
        throw new IllegalStateException("Legacy resource character is unavailable");
      }
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(ACTOR_RESOURCE_STATES)
            .set(ACTOR_RESOURCE_STATES.CURRENT_VALUE, entity.getCurrentValue())
            .set(ACTOR_RESOURCE_STATES.MAX_VALUE, entity.getMaxValue())
            .set(ACTOR_RESOURCE_STATES.BASE_VALUE, entity.getBaseValue())
            .set(ACTOR_RESOURCE_STATES.SOURCE_TYPE, entity.getSourceType())
            .set(ACTOR_RESOURCE_STATES.SOURCE_ID, entity.getSourceId())
            .set(ACTOR_RESOURCE_STATES.UPDATED_AT, toOffsetDateTime(entity.getUpdatedAt()))
            .set(ACTOR_RESOURCE_STATES.VERSION, entity.getVersion() + 1)
            .where(
                ACTOR_RESOURCE_STATES
                    .ID
                    .eq(entity.getId())
                    .and(
                        CharacterRepository.legacyCharacterExists(
                            ACTOR_RESOURCE_STATES.CHARACTER_ID)))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Legacy resource row is unavailable");
    }
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
