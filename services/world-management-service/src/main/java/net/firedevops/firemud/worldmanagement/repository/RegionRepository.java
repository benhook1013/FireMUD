package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.Region.REGION;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.RegionRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RegionRepository {
  private final DSLContext dsl;

  public RegionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void deleteByTenantId(Long tenantId) {
    dsl.deleteFrom(REGION).where(REGION.TENANT_ID.eq(tenantId)).execute();
  }

  public List<Region> findByTenantId(Long tenantId) {
    return dsl.selectFrom(REGION).where(REGION.TENANT_ID.eq(tenantId)).fetch(this::toEntity);
  }

  public List<Region> findByTenantIdOrderByIdAsc(Long tenantId) {
    return dsl.selectFrom(REGION)
        .where(REGION.TENANT_ID.eq(tenantId))
        .orderBy(REGION.ID.asc())
        .fetch(this::toEntity);
  }

  public List<Region> findByTenantIdAndVersionIdOrderByIdAsc(Long tenantId, Long versionId) {
    return dsl.selectFrom(REGION)
        .where(REGION.TENANT_ID.eq(tenantId).and(REGION.VERSION_ID.eq(versionId)))
        .orderBy(REGION.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<Region> findByTenantIdAndVersionIdAndId(Long tenantId, Long versionId, Long id) {
    return dsl.selectFrom(REGION)
        .where(
            REGION
                .TENANT_ID
                .eq(tenantId)
                .and(REGION.VERSION_ID.eq(versionId))
                .and(REGION.ID.eq(id)))
        .fetchOptional(this::toEntity);
  }

  public Optional<Region> findFirstByTenantIdAndVersionIdAndShardIdAndName(
      Long tenantId, Long versionId, Integer shardId, String name) {
    return dsl.selectFrom(REGION)
        .where(
            REGION
                .TENANT_ID
                .eq(tenantId)
                .and(REGION.VERSION_ID.eq(versionId))
                .and(REGION.SHARD_ID.eq(shardId))
                .and(REGION.NAME.eq(name)))
        .orderBy(REGION.ID.asc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public List<Region> findByTenantIdAndShardId(Long tenantId, Integer shardId) {
    return dsl.selectFrom(REGION)
        .where(REGION.TENANT_ID.eq(tenantId).and(REGION.SHARD_ID.eq(shardId)))
        .fetch(this::toEntity);
  }

  public Optional<Region> findById(Long id) {
    return dsl.selectFrom(REGION).where(REGION.ID.eq(id)).fetchOptional(this::toEntity);
  }

  public long count() {
    return dsl.fetchCount(REGION);
  }

  public Region save(Region entity) {
    if (entity.getId() == null) {
      RegionRecord record = dsl.newRecord(REGION);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(REGION)
            .set(REGION.NAME, entity.getName())
            .set(REGION.TENANT_ID, entity.getTenantId())
            .set(REGION.WEATHER, entity.getWeather())
            .set(REGION.SHARD_ID, entity.getShardId())
            .set(REGION.GENERATION_SEED, entity.getGenerationSeed())
            .set(REGION.GENERATOR_TYPE, entity.getGeneratorType())
            .set(REGION.GENERATOR_PARAMS, entity.getGeneratorParams())
            .set(REGION.SPACING_MULTIPLIER, entity.getSpacingMultiplier())
            .set(REGION.VERSION_ID, entity.getVersionId())
            .set(REGION.VERSION, entity.getVersion() + 1)
            .where(REGION.ID.eq(entity.getId()).and(REGION.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("region", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public void delete(Region entity) {
    dsl.deleteFrom(REGION).where(REGION.ID.eq(entity.getId())).execute();
  }

  public void deleteAll(List<Region> entities) {
    if (entities.isEmpty()) {
      return;
    }
    dsl.deleteFrom(REGION)
        .where(REGION.ID.in(entities.stream().map(Region::getId).toList()))
        .execute();
  }

  private void populate(RegionRecord record, Region entity) {
    record.setName(entity.getName());
    record.setTenantId(entity.getTenantId());
    record.setWeather(entity.getWeather());
    record.setShardId(entity.getShardId());
    record.setVersion(entity.getVersion());
    record.setGenerationSeed(entity.getGenerationSeed());
    record.setGeneratorType(entity.getGeneratorType());
    record.setGeneratorParams(entity.getGeneratorParams());
    record.setSpacingMultiplier(entity.getSpacingMultiplier());
    record.setVersionId(entity.getVersionId());
  }

  private Region toEntity(Record record) {
    Region entity = new Region();
    entity.setId(record.get(REGION.ID));
    entity.setName(record.get(REGION.NAME));
    entity.setTenantId(record.get(REGION.TENANT_ID));
    entity.setWeather(record.get(REGION.WEATHER));
    entity.setShardId(record.get(REGION.SHARD_ID));
    Integer version = record.get(REGION.VERSION);
    entity.setVersion(version == null ? 0 : version);
    entity.setGenerationSeed(record.get(REGION.GENERATION_SEED));
    entity.setGeneratorType(record.get(REGION.GENERATOR_TYPE));
    entity.setGeneratorParams(record.get(REGION.GENERATOR_PARAMS));
    entity.setSpacingMultiplier(record.get(REGION.SPACING_MULTIPLIER));
    entity.setVersionId(record.get(REGION.VERSION_ID));
    return entity;
  }
}
