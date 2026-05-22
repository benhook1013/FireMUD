package net.firedevops.firemud.common.saga.persistence;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class SagaStepRepository {
  private static final org.jooq.Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<Long> INSTANCE_ID =
      DSL.field(DSL.name("instance_id"), Long.class);
  private static final org.jooq.Field<String> NAME = DSL.field(DSL.name("name"), String.class);
  private static final org.jooq.Field<String> STATUS = DSL.field(DSL.name("status"), String.class);
  private static final org.jooq.Field<Integer> ATTEMPT =
      DSL.field(DSL.name("attempt"), Integer.class);
  private static final org.jooq.Field<java.time.LocalDateTime> CREATED_AT =
      DSL.field(DSL.name("created_at"), java.time.LocalDateTime.class);
  private static final org.jooq.Field<java.time.LocalDateTime> UPDATED_AT =
      DSL.field(DSL.name("updated_at"), java.time.LocalDateTime.class);

  private final org.jooq.Table<?> sagaStep;
  private final DSLContext dsl;

  public SagaStepRepository(DSLContext dsl, String serviceSchema) {
    this.dsl = dsl;
    this.sagaStep = DSL.table(DSL.name(serviceSchema, "saga_step"));
  }

  public SagaStep save(SagaStep entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(sagaStep)
              .set(INSTANCE_ID, entity.getInstanceId())
              .set(NAME, entity.getName())
              .set(STATUS, entity.getStatus())
              .set(ATTEMPT, entity.getAttempt())
              .set(CREATED_AT, JooqPersistenceSupport.toLocalDateTime(entity.getCreatedAt()))
              .set(UPDATED_AT, JooqPersistenceSupport.toLocalDateTime(entity.getUpdatedAt()))
              .returningResult(ID)
              .fetchOne(ID);
      entity.setId(id);
      return entity;
    }
    dsl.update(sagaStep)
        .set(INSTANCE_ID, entity.getInstanceId())
        .set(NAME, entity.getName())
        .set(STATUS, entity.getStatus())
        .set(ATTEMPT, entity.getAttempt())
        .set(CREATED_AT, JooqPersistenceSupport.toLocalDateTime(entity.getCreatedAt()))
        .set(UPDATED_AT, JooqPersistenceSupport.toLocalDateTime(entity.getUpdatedAt()))
        .where(ID.eq(entity.getId()))
        .execute();
    return entity;
  }

  public List<SagaStep> findByInstanceId(Long instanceId) {
    return dsl.select(ID, INSTANCE_ID, NAME, STATUS, ATTEMPT, CREATED_AT, UPDATED_AT)
        .from(sagaStep)
        .where(INSTANCE_ID.eq(instanceId))
        .orderBy(ID.asc())
        .fetch(
            record -> {
              SagaStep entity = new SagaStep();
              entity.setId(record.get(ID));
              entity.setInstanceId(record.get(INSTANCE_ID));
              entity.setName(record.get(NAME));
              entity.setStatus(record.get(STATUS));
              entity.setAttempt(record.get(ATTEMPT) == null ? 0 : record.get(ATTEMPT));
              entity.setCreatedAt(JooqPersistenceSupport.toInstant(record.get(CREATED_AT)));
              entity.setUpdatedAt(JooqPersistenceSupport.toInstant(record.get(UPDATED_AT)));
              return entity;
            });
  }
}
