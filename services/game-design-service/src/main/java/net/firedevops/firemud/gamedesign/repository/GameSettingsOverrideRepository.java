package net.firedevops.firemud.gamedesign.repository;

import static net.firedevops.firemud.gamedesign.repository.JooqGameDesignRepositorySupport.jsonbParam;
import static net.firedevops.firemud.gamedesign.repository.JooqGameDesignRepositorySupport.nullableString;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.GameSettingsOverride;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameSettingsOverrideRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("game_settings_override"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> GAME_INSTANCE_ID =
      DSL.field(DSL.name("game_instance_id"), Long.class);
  private static final Field<String> DOMAIN = DSL.field(DSL.name("domain"), String.class);
  private static final Field<JSONB> PAYLOAD = DSL.field(DSL.name("payload"), JSONB.class);
  private static final Field<Timestamp> UPDATED_AT =
      DSL.field(DSL.name("updated_at"), Timestamp.class);

  private final DSLContext dsl;

  public GameSettingsOverrideRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<GameSettingsOverride> findByTenantIdAndGameInstanceIdIsNull(String tenantId) {
    return dsl.selectFrom(TABLE_REF)
        .where(TENANT_ID.eq(tenantId).and(GAME_INSTANCE_ID.isNull()))
        .orderBy(DOMAIN.asc())
        .fetch(this::toEntity);
  }

  public List<GameSettingsOverride> findByTenantIdAndGameInstanceId(
      String tenantId, Long gameInstanceId) {
    return dsl.selectFrom(TABLE_REF)
        .where(TENANT_ID.eq(tenantId).and(GAME_INSTANCE_ID.eq(gameInstanceId)))
        .orderBy(DOMAIN.asc())
        .fetch(this::toEntity);
  }

  public List<GameSettingsOverride> findByTenantIdAndGameInstanceIdIsNotNullAndDomain(
      String tenantId, String domain) {
    return dsl.selectFrom(TABLE_REF)
        .where(TENANT_ID.eq(tenantId).and(GAME_INSTANCE_ID.isNotNull()).and(DOMAIN.eq(domain)))
        .orderBy(GAME_INSTANCE_ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<GameSettingsOverride> findByTenantIdAndGameInstanceIdIsNullAndDomain(
      String tenantId, String domain) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(TENANT_ID.eq(tenantId).and(GAME_INSTANCE_ID.isNull()).and(DOMAIN.eq(domain)))
            .fetchOne(this::toEntity));
  }

  public Optional<GameSettingsOverride> findByTenantIdAndGameInstanceIdAndDomain(
      String tenantId, Long gameInstanceId, String domain) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(
                TENANT_ID
                    .eq(tenantId)
                    .and(GAME_INSTANCE_ID.eq(gameInstanceId))
                    .and(DOMAIN.eq(domain)))
            .fetchOne(this::toEntity));
  }

  public GameSettingsOverride save(GameSettingsOverride entity) {
    Instant updatedAt = entity.getUpdatedAt() == null ? Instant.now() : entity.getUpdatedAt();
    if (entity.getId() == null) {
      Long generatedId =
          dsl.insertInto(TABLE_REF)
              .set(TENANT_ID, entity.getTenantId())
              .set(GAME_INSTANCE_ID, entity.getGameInstanceId())
              .set(DOMAIN, entity.getDomain())
              .set(PAYLOAD, jsonbParam(entity.getPayload()))
              .set(UPDATED_AT, Timestamp.from(updatedAt))
              .returningResult(ID)
              .fetchOne(ID);
      return dsl.selectFrom(TABLE_REF).where(ID.eq(generatedId)).fetchOne(this::toEntity);
    }
    dsl.update(TABLE_REF)
        .set(TENANT_ID, entity.getTenantId())
        .set(GAME_INSTANCE_ID, entity.getGameInstanceId())
        .set(DOMAIN, entity.getDomain())
        .set(PAYLOAD, jsonbParam(entity.getPayload()))
        .set(UPDATED_AT, Timestamp.from(updatedAt))
        .where(ID.eq(entity.getId()))
        .execute();
    return findOneByScope(entity.getTenantId(), entity.getGameInstanceId(), entity.getDomain())
        .orElseThrow();
  }

  public void delete(GameSettingsOverride entity) {
    if (entity == null || entity.getId() == null) {
      return;
    }
    dsl.deleteFrom(TABLE_REF).where(ID.eq(entity.getId())).execute();
  }

  private Optional<GameSettingsOverride> findOneByScope(
      String tenantId, Long gameInstanceId, String domain) {
    Condition scope =
        gameInstanceId == null ? GAME_INSTANCE_ID.isNull() : GAME_INSTANCE_ID.eq(gameInstanceId);
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(TENANT_ID.eq(tenantId).and(scope).and(DOMAIN.eq(domain)))
            .fetchOne(this::toEntity));
  }

  private GameSettingsOverride toEntity(Record record) {
    if (record == null) {
      return null;
    }
    GameSettingsOverride entity = new GameSettingsOverride();
    entity.setId(record.get(ID));
    entity.setTenantId(record.get(TENANT_ID));
    entity.setGameInstanceId(record.get(GAME_INSTANCE_ID));
    entity.setDomain(record.get(DOMAIN));
    entity.setPayload(nullableString(record.get(PAYLOAD)));
    Timestamp updatedAt = record.get(UPDATED_AT);
    entity.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
    return entity;
  }
}
