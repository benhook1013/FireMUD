package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.ZoneInstance.ZONE_INSTANCE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.ZoneInstance;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.ZoneInstanceRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ZoneInstanceRepository {
  private final DSLContext dsl;

  public ZoneInstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ZoneInstance> findByTenantIdAndGameInstanceIdAndZoneInstanceId(
      Long tenantId, Long gameInstanceId, Long zoneInstanceId) {
    return dsl.selectFrom(ZONE_INSTANCE)
        .where(
            ZONE_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(ZONE_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(ZONE_INSTANCE.ZONE_INSTANCE_ID.eq(zoneInstanceId)))
        .fetchOptional(this::toEntity);
  }

  public long countByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    return dsl.fetchCount(
        ZONE_INSTANCE,
        ZONE_INSTANCE
            .TENANT_ID
            .eq(tenantId)
            .and(ZONE_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)));
  }

  public void deleteByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    dsl.deleteFrom(ZONE_INSTANCE)
        .where(
            ZONE_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(ZONE_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  public Optional<ZoneInstance> findById(Long id) {
    return dsl.selectFrom(ZONE_INSTANCE)
        .where(ZONE_INSTANCE.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public ZoneInstance save(ZoneInstance entity) {
    if (entity.getId() == null) {
      ZoneInstanceRecord record = dsl.newRecord(ZONE_INSTANCE);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(ZONE_INSTANCE)
            .set(ZONE_INSTANCE.TENANT_ID, entity.getTenantId())
            .set(ZONE_INSTANCE.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(ZONE_INSTANCE.ZONE_INSTANCE_ID, entity.getZoneInstanceId())
            .set(ZONE_INSTANCE.TEMPLATE_ZONE_ID, entity.getTemplateZoneId())
            .set(ZONE_INSTANCE.REGION_INSTANCE_ID, entity.getRegionInstance().getId())
            .set(ZONE_INSTANCE.NAME, entity.getName())
            .set(ZONE_INSTANCE.VERSION, entity.getVersion() + 1)
            .where(
                ZONE_INSTANCE
                    .ID
                    .eq(entity.getId())
                    .and(ZONE_INSTANCE.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("zone_instance", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private void populate(ZoneInstanceRecord record, ZoneInstance entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setZoneInstanceId(entity.getZoneInstanceId());
    record.setTemplateZoneId(entity.getTemplateZoneId());
    record.setRegionInstanceId(entity.getRegionInstance().getId());
    record.setName(entity.getName());
    record.setVersion(entity.getVersion());
  }

  private ZoneInstance toEntity(Record record) {
    ZoneInstance entity = new ZoneInstance();
    entity.setId(record.get(ZONE_INSTANCE.ID));
    entity.setTenantId(record.get(ZONE_INSTANCE.TENANT_ID));
    entity.setGameInstanceId(record.get(ZONE_INSTANCE.GAME_INSTANCE_ID));
    entity.setZoneInstanceId(record.get(ZONE_INSTANCE.ZONE_INSTANCE_ID));
    entity.setTemplateZoneId(record.get(ZONE_INSTANCE.TEMPLATE_ZONE_ID));
    entity.setRegionInstance(
        JooqWorldManagementRepositorySupport.partialRegionInstance(
            record.get(ZONE_INSTANCE.REGION_INSTANCE_ID)));
    entity.setName(record.get(ZONE_INSTANCE.NAME));
    Integer version = record.get(ZONE_INSTANCE.VERSION);
    entity.setVersion(version == null ? 0 : version);
    return entity;
  }
}
