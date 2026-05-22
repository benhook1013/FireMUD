package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.RegionInstance.REGION_INSTANCE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.RegionInstanceRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RegionInstanceRepository {
  private final DSLContext dsl;

  public RegionInstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<RegionInstance> findByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    return dsl.selectFrom(REGION_INSTANCE)
        .where(
            REGION_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(REGION_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .fetch(this::toEntity);
  }

  public long countByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    return dsl.fetchCount(
        REGION_INSTANCE,
        REGION_INSTANCE
            .TENANT_ID
            .eq(tenantId)
            .and(REGION_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)));
  }

  public void deleteByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    dsl.deleteFrom(REGION_INSTANCE)
        .where(
            REGION_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(REGION_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  public Optional<RegionInstance> findById(Long id) {
    return dsl.selectFrom(REGION_INSTANCE)
        .where(REGION_INSTANCE.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public RegionInstance save(RegionInstance entity) {
    if (entity.getId() == null) {
      RegionInstanceRecord record = dsl.newRecord(REGION_INSTANCE);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(REGION_INSTANCE)
            .set(REGION_INSTANCE.TENANT_ID, entity.getTenantId())
            .set(REGION_INSTANCE.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(REGION_INSTANCE.WORLD_INSTANCE_ID, entity.getWorldInstance().getId())
            .set(REGION_INSTANCE.SHARD_ID, entity.getShardId())
            .set(REGION_INSTANCE.NAME, entity.getName())
            .set(REGION_INSTANCE.WEATHER, entity.getWeather())
            .set(REGION_INSTANCE.GENERATION_SEED, entity.getGenerationSeed())
            .set(REGION_INSTANCE.GENERATOR_TYPE, entity.getGeneratorType())
            .set(REGION_INSTANCE.GENERATOR_PARAMS, entity.getGeneratorParams())
            .set(REGION_INSTANCE.SPACING_MULTIPLIER, entity.getSpacingMultiplier())
            .set(REGION_INSTANCE.VERSION, entity.getVersion() + 1)
            .where(
                REGION_INSTANCE
                    .ID
                    .eq(entity.getId())
                    .and(REGION_INSTANCE.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("region_instance", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private void populate(RegionInstanceRecord record, RegionInstance entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setWorldInstanceId(entity.getWorldInstance().getId());
    record.setShardId(entity.getShardId());
    record.setName(entity.getName());
    record.setWeather(entity.getWeather());
    record.setGenerationSeed(entity.getGenerationSeed());
    record.setGeneratorType(entity.getGeneratorType());
    record.setGeneratorParams(entity.getGeneratorParams());
    record.setSpacingMultiplier(entity.getSpacingMultiplier());
    record.setVersion(entity.getVersion());
  }

  private RegionInstance toEntity(Record record) {
    RegionInstance entity = new RegionInstance();
    entity.setId(record.get(REGION_INSTANCE.ID));
    entity.setTenantId(record.get(REGION_INSTANCE.TENANT_ID));
    entity.setGameInstanceId(record.get(REGION_INSTANCE.GAME_INSTANCE_ID));
    entity.setWorldInstance(
        JooqWorldManagementRepositorySupport.partialWorldInstance(
            record.get(REGION_INSTANCE.WORLD_INSTANCE_ID)));
    entity.setShardId(record.get(REGION_INSTANCE.SHARD_ID));
    entity.setName(record.get(REGION_INSTANCE.NAME));
    entity.setWeather(record.get(REGION_INSTANCE.WEATHER));
    entity.setGenerationSeed(record.get(REGION_INSTANCE.GENERATION_SEED));
    entity.setGeneratorType(record.get(REGION_INSTANCE.GENERATOR_TYPE));
    entity.setGeneratorParams(record.get(REGION_INSTANCE.GENERATOR_PARAMS));
    entity.setSpacingMultiplier(record.get(REGION_INSTANCE.SPACING_MULTIPLIER));
    Integer version = record.get(REGION_INSTANCE.VERSION);
    entity.setVersion(version == null ? 0 : version);
    return entity;
  }
}
