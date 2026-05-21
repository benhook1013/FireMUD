package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ENTITY_MUTATION_EFFECTS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.EntityMutationEffect;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class EntityMutationEffectRepository {
  private final DSLContext dsl;

  public EntityMutationEffectRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<EntityMutationEffect> findByTenantIdAndEffectId(Long tenantId, String effectId) {
    return Optional.ofNullable(
        dsl.selectFrom(ENTITY_MUTATION_EFFECTS)
            .where(
                ENTITY_MUTATION_EFFECTS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(ENTITY_MUTATION_EFFECTS.EFFECT_ID.eq(effectId)))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public EntityMutationEffect save(EntityMutationEffect entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ENTITY_MUTATION_EFFECTS)
              .set(ENTITY_MUTATION_EFFECTS.TENANT_ID, entity.getTenantId())
              .set(ENTITY_MUTATION_EFFECTS.EFFECT_ID, entity.getEffectId())
              .set(ENTITY_MUTATION_EFFECTS.OPERATION_NAME, entity.getOperationName())
              .set(ENTITY_MUTATION_EFFECTS.RESPONSE_TYPE, entity.getResponseType())
              .set(ENTITY_MUTATION_EFFECTS.RESPONSE_PAYLOAD, entity.getResponsePayload())
              .set(ENTITY_MUTATION_EFFECTS.STATUS, entity.getStatus())
              .set(ENTITY_MUTATION_EFFECTS.CREATED_AT, toOffsetDateTime(entity.getCreatedAt()))
              .set(ENTITY_MUTATION_EFFECTS.COMPLETED_AT, toOffsetDateTime(entity.getCompletedAt()))
              .returningResult(ENTITY_MUTATION_EFFECTS.ID)
              .fetchOne(ENTITY_MUTATION_EFFECTS.ID);
      return findById(id).orElseThrow();
    }
    dsl.update(ENTITY_MUTATION_EFFECTS)
        .set(ENTITY_MUTATION_EFFECTS.TENANT_ID, entity.getTenantId())
        .set(ENTITY_MUTATION_EFFECTS.EFFECT_ID, entity.getEffectId())
        .set(ENTITY_MUTATION_EFFECTS.OPERATION_NAME, entity.getOperationName())
        .set(ENTITY_MUTATION_EFFECTS.RESPONSE_TYPE, entity.getResponseType())
        .set(ENTITY_MUTATION_EFFECTS.RESPONSE_PAYLOAD, entity.getResponsePayload())
        .set(ENTITY_MUTATION_EFFECTS.STATUS, entity.getStatus())
        .set(ENTITY_MUTATION_EFFECTS.CREATED_AT, toOffsetDateTime(entity.getCreatedAt()))
        .set(ENTITY_MUTATION_EFFECTS.COMPLETED_AT, toOffsetDateTime(entity.getCompletedAt()))
        .where(ENTITY_MUTATION_EFFECTS.ID.eq(entity.getId()))
        .execute();
    return findById(entity.getId()).orElseThrow();
  }

  public EntityMutationEffect saveAndFlush(EntityMutationEffect entity) {
    return save(entity);
  }

  private Optional<EntityMutationEffect> findById(Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(ENTITY_MUTATION_EFFECTS)
            .where(ENTITY_MUTATION_EFFECTS.ID.eq(id))
            .fetchOne(this::toEntity));
  }

  private EntityMutationEffect toEntity(Record record) {
    if (record == null) {
      return null;
    }
    EntityMutationEffect effect = new EntityMutationEffect();
    effect.setId(record.get(ENTITY_MUTATION_EFFECTS.ID));
    effect.setTenantId(record.get(ENTITY_MUTATION_EFFECTS.TENANT_ID));
    effect.setEffectId(record.get(ENTITY_MUTATION_EFFECTS.EFFECT_ID));
    effect.setOperationName(record.get(ENTITY_MUTATION_EFFECTS.OPERATION_NAME));
    effect.setResponseType(record.get(ENTITY_MUTATION_EFFECTS.RESPONSE_TYPE));
    effect.setResponsePayload(record.get(ENTITY_MUTATION_EFFECTS.RESPONSE_PAYLOAD));
    effect.setStatus(record.get(ENTITY_MUTATION_EFFECTS.STATUS));
    effect.setCreatedAt(toInstant(record.get(ENTITY_MUTATION_EFFECTS.CREATED_AT)));
    effect.setCompletedAt(toInstant(record.get(ENTITY_MUTATION_EFFECTS.COMPLETED_AT)));
    return effect;
  }
}
