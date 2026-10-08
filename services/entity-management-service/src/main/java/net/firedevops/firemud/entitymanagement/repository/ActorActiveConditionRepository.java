package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.*;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ACTOR_ACTIVE_CONDITIONS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ActorActiveCondition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ActorActiveConditionRepository {
  private final DSLContext dsl;

  public ActorActiveConditionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<ActorActiveCondition> findActiveForCharacter(
      Long tenantId, String playableStateKey, Long characterId, Instant now) {
    return dsl.selectFrom(ACTOR_ACTIVE_CONDITIONS)
        .where(
            ACTOR_ACTIVE_CONDITIONS
                .TENANT_ID
                .eq(tenantId)
                .and(ACTOR_ACTIVE_CONDITIONS.PLAYABLE_STATE_KEY.eq(playableStateKey))
                .and(ACTOR_ACTIVE_CONDITIONS.CHARACTER_ID.eq(characterId))
                .and(
                    ACTOR_ACTIVE_CONDITIONS
                        .EXPIRES_AT
                        .isNull()
                        .or(ACTOR_ACTIVE_CONDITIONS.EXPIRES_AT.gt(toOffsetDateTime(now)))))
        .orderBy(
            ACTOR_ACTIVE_CONDITIONS.CONDITION_KEY.asc(),
            ACTOR_ACTIVE_CONDITIONS.STARTED_AT.asc(),
            ACTOR_ACTIVE_CONDITIONS.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<ActorActiveCondition>
      findFirstByTenantIdAndPlayableStateKeyAndCharacterIdAndSourceTypeAndSourceIdOrderByIdAsc(
          Long tenantId,
          String playableStateKey,
          Long characterId,
          String sourceType,
          String sourceId) {
    return Optional.ofNullable(
        dsl.selectFrom(ACTOR_ACTIVE_CONDITIONS)
            .where(
                ACTOR_ACTIVE_CONDITIONS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(ACTOR_ACTIVE_CONDITIONS.PLAYABLE_STATE_KEY.eq(playableStateKey))
                    .and(ACTOR_ACTIVE_CONDITIONS.CHARACTER_ID.eq(characterId))
                    .and(ACTOR_ACTIVE_CONDITIONS.SOURCE_TYPE.eq(sourceType))
                    .and(ACTOR_ACTIVE_CONDITIONS.SOURCE_ID.eq(sourceId)))
            .orderBy(ACTOR_ACTIVE_CONDITIONS.ID.asc())
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public ActorActiveCondition save(ActorActiveCondition entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ACTOR_ACTIVE_CONDITIONS)
              .set(ACTOR_ACTIVE_CONDITIONS.TENANT_ID, entity.getTenantId())
              .set(ACTOR_ACTIVE_CONDITIONS.PLAYABLE_STATE_KEY, entity.getPlayableStateKey())
              .set(ACTOR_ACTIVE_CONDITIONS.CHARACTER_ID, entity.getCharacterId())
              .set(ACTOR_ACTIVE_CONDITIONS.CONDITION_KEY, entity.getConditionKey())
              .set(ACTOR_ACTIVE_CONDITIONS.STACK_COUNT, entity.getStackCount())
              .set(ACTOR_ACTIVE_CONDITIONS.SOURCE_TYPE, entity.getSourceType())
              .set(ACTOR_ACTIVE_CONDITIONS.SOURCE_ID, entity.getSourceId())
              .set(ACTOR_ACTIVE_CONDITIONS.STARTED_AT, toOffsetDateTime(entity.getStartedAt()))
              .set(ACTOR_ACTIVE_CONDITIONS.EXPIRES_AT, toOffsetDateTime(entity.getExpiresAt()))
              .set(ACTOR_ACTIVE_CONDITIONS.EFFECT_PAYLOAD_JSON, entity.getEffectPayloadJson())
              .set(ACTOR_ACTIVE_CONDITIONS.CREATED_AT, toOffsetDateTime(entity.getCreatedAt()))
              .set(ACTOR_ACTIVE_CONDITIONS.UPDATED_AT, toOffsetDateTime(entity.getUpdatedAt()))
              .returningResult(ACTOR_ACTIVE_CONDITIONS.ID)
              .fetchOne(ACTOR_ACTIVE_CONDITIONS.ID);
      entity.setId(id);
      return entity;
    }
    dsl.update(ACTOR_ACTIVE_CONDITIONS)
        .set(ACTOR_ACTIVE_CONDITIONS.CONDITION_KEY, entity.getConditionKey())
        .set(ACTOR_ACTIVE_CONDITIONS.STACK_COUNT, entity.getStackCount())
        .set(ACTOR_ACTIVE_CONDITIONS.SOURCE_TYPE, entity.getSourceType())
        .set(ACTOR_ACTIVE_CONDITIONS.SOURCE_ID, entity.getSourceId())
        .set(ACTOR_ACTIVE_CONDITIONS.STARTED_AT, toOffsetDateTime(entity.getStartedAt()))
        .set(ACTOR_ACTIVE_CONDITIONS.EXPIRES_AT, toOffsetDateTime(entity.getExpiresAt()))
        .set(ACTOR_ACTIVE_CONDITIONS.EFFECT_PAYLOAD_JSON, entity.getEffectPayloadJson())
        .set(ACTOR_ACTIVE_CONDITIONS.UPDATED_AT, toOffsetDateTime(entity.getUpdatedAt()))
        .set(ACTOR_ACTIVE_CONDITIONS.VERSION, entity.getVersion() + 1)
        .where(ACTOR_ACTIVE_CONDITIONS.ID.eq(entity.getId()))
        .execute();
    entity.setVersion(entity.getVersion() + 1);
    return entity;
  }

  public int deleteExpired(Instant now) {
    return dsl.deleteFrom(ACTOR_ACTIVE_CONDITIONS)
        .where(
            ACTOR_ACTIVE_CONDITIONS
                .EXPIRES_AT
                .isNotNull()
                .and(ACTOR_ACTIVE_CONDITIONS.EXPIRES_AT.le(toOffsetDateTime(now))))
        .execute();
  }

  private ActorActiveCondition toEntity(Record record) {
    if (record == null) {
      return null;
    }
    ActorActiveCondition entity = new ActorActiveCondition();
    entity.setId(record.get(ACTOR_ACTIVE_CONDITIONS.ID));
    entity.setTenantId(record.get(ACTOR_ACTIVE_CONDITIONS.TENANT_ID));
    entity.setPlayableStateKey(record.get(ACTOR_ACTIVE_CONDITIONS.PLAYABLE_STATE_KEY));
    entity.setCharacterId(record.get(ACTOR_ACTIVE_CONDITIONS.CHARACTER_ID));
    entity.setConditionKey(record.get(ACTOR_ACTIVE_CONDITIONS.CONDITION_KEY));
    entity.setStackCount(record.get(ACTOR_ACTIVE_CONDITIONS.STACK_COUNT));
    entity.setSourceType(record.get(ACTOR_ACTIVE_CONDITIONS.SOURCE_TYPE));
    entity.setSourceId(record.get(ACTOR_ACTIVE_CONDITIONS.SOURCE_ID));
    entity.setStartedAt(toInstant(record.get(ACTOR_ACTIVE_CONDITIONS.STARTED_AT)));
    entity.setExpiresAt(toInstant(record.get(ACTOR_ACTIVE_CONDITIONS.EXPIRES_AT)));
    entity.setEffectPayloadJson(record.get(ACTOR_ACTIVE_CONDITIONS.EFFECT_PAYLOAD_JSON));
    entity.setCreatedAt(toInstant(record.get(ACTOR_ACTIVE_CONDITIONS.CREATED_AT)));
    entity.setUpdatedAt(toInstant(record.get(ACTOR_ACTIVE_CONDITIONS.UPDATED_AT)));
    entity.setVersion(record.get(ACTOR_ACTIVE_CONDITIONS.VERSION));
    return entity;
  }
}
