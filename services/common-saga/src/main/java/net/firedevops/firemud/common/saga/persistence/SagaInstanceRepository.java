package net.firedevops.firemud.common.saga.persistence;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class SagaInstanceRepository {
  private static final org.jooq.Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> SAGA_NAME =
      DSL.field(DSL.name("saga_name"), String.class);
  private static final org.jooq.Field<String> STATE = DSL.field(DSL.name("state"), String.class);
  private static final org.jooq.Field<java.time.LocalDateTime> CREATED_AT =
      DSL.field(DSL.name("created_at"), java.time.LocalDateTime.class);
  private static final org.jooq.Field<java.time.LocalDateTime> UPDATED_AT =
      DSL.field(DSL.name("updated_at"), java.time.LocalDateTime.class);

  private final org.jooq.Table<?> sagaInstance;
  private final DSLContext dsl;

  public SagaInstanceRepository(DSLContext dsl, String serviceSchema) {
    this.dsl = dsl;
    this.sagaInstance = DSL.table(DSL.name(serviceSchema, "saga_instance"));
  }

  public SagaInstance save(SagaInstance entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(sagaInstance)
              .set(SAGA_NAME, entity.getSagaName())
              .set(STATE, entity.getState())
              .set(CREATED_AT, JooqPersistenceSupport.toLocalDateTime(entity.getCreatedAt()))
              .set(UPDATED_AT, JooqPersistenceSupport.toLocalDateTime(entity.getUpdatedAt()))
              .returningResult(ID)
              .fetchOne(ID);
      entity.setId(id);
      return entity;
    }
    dsl.update(sagaInstance)
        .set(SAGA_NAME, entity.getSagaName())
        .set(STATE, entity.getState())
        .set(CREATED_AT, JooqPersistenceSupport.toLocalDateTime(entity.getCreatedAt()))
        .set(UPDATED_AT, JooqPersistenceSupport.toLocalDateTime(entity.getUpdatedAt()))
        .where(ID.eq(entity.getId()))
        .execute();
    return entity;
  }

  public List<SagaInstance> findAll() {
    return dsl.select(ID, SAGA_NAME, STATE, CREATED_AT, UPDATED_AT)
        .from(sagaInstance)
        .orderBy(ID.desc())
        .fetch(
            record -> {
              SagaInstance entity = new SagaInstance();
              entity.setId(record.get(ID));
              entity.setSagaName(record.get(SAGA_NAME));
              entity.setState(record.get(STATE));
              entity.setCreatedAt(JooqPersistenceSupport.toInstant(record.get(CREATED_AT)));
              entity.setUpdatedAt(JooqPersistenceSupport.toInstant(record.get(UPDATED_AT)));
              return entity;
            });
  }
}
