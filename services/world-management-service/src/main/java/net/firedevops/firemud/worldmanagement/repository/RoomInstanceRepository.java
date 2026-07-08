package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.RoomInstance.ROOM_INSTANCE;
import static net.firedevops.firemud.worldmanagement.jooq.tables.ZoneInstance.ZONE_INSTANCE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.RoomInstance;
import net.firedevops.firemud.worldmanagement.entity.ZoneInstance;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RoomInstanceRepository {
  private final DSLContext dsl;

  public RoomInstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<RoomInstance> findByTenantIdAndGameInstanceIdAndRoomInstanceRowId(
      Long tenantId, Long gameInstanceId, Long roomInstanceRowId) {
    return dsl.select(ROOM_INSTANCE.fields())
        .select(ZONE_INSTANCE.REGION_INSTANCE_ID)
        .from(ROOM_INSTANCE)
        .join(ZONE_INSTANCE)
        .on(ROOM_INSTANCE.ZONE_INSTANCE_ID.eq(ZONE_INSTANCE.ID))
        .where(
            ROOM_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(ROOM_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(ROOM_INSTANCE.ROOM_INSTANCE_ID.eq(roomInstanceRowId)))
        .fetchOptional(this::toEntity);
  }

  public List<RoomInstance> findByTenantIdAndGameInstanceIdOrderByRoomInstanceRowIdAsc(
      Long tenantId, Long gameInstanceId) {
    return dsl.select(ROOM_INSTANCE.fields())
        .select(ZONE_INSTANCE.REGION_INSTANCE_ID)
        .from(ROOM_INSTANCE)
        .join(ZONE_INSTANCE)
        .on(ROOM_INSTANCE.ZONE_INSTANCE_ID.eq(ZONE_INSTANCE.ID))
        .where(
            ROOM_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(ROOM_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .orderBy(ROOM_INSTANCE.ROOM_INSTANCE_ID.asc())
        .fetch(this::toEntity);
  }

  public long countByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    return dsl.fetchCount(
        ROOM_INSTANCE,
        ROOM_INSTANCE
            .TENANT_ID
            .eq(tenantId)
            .and(ROOM_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)));
  }

  public void deleteByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    dsl.deleteFrom(ROOM_INSTANCE)
        .where(
            ROOM_INSTANCE
                .TENANT_ID
                .eq(tenantId)
                .and(ROOM_INSTANCE.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  public Optional<RoomInstance> findById(Long id) {
    return dsl.select(ROOM_INSTANCE.fields())
        .select(ZONE_INSTANCE.REGION_INSTANCE_ID)
        .from(ROOM_INSTANCE)
        .join(ZONE_INSTANCE)
        .on(ROOM_INSTANCE.ZONE_INSTANCE_ID.eq(ZONE_INSTANCE.ID))
        .where(ROOM_INSTANCE.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public RoomInstance save(RoomInstance entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ROOM_INSTANCE)
              .set(ROOM_INSTANCE.TENANT_ID, entity.getTenantId())
              .set(ROOM_INSTANCE.GAME_INSTANCE_ID, entity.getGameInstanceId())
              .set(ROOM_INSTANCE.ROOM_INSTANCE_ID, entity.getRoomInstanceRowId())
              .set(ROOM_INSTANCE.TEMPLATE_ROOM_ID, entity.getTemplateRoomId())
              .set(ROOM_INSTANCE.REGION_INSTANCE_ID, entity.getRegionInstance().getId())
              .set(ROOM_INSTANCE.NAME, entity.getName())
              .set(ROOM_INSTANCE.DESCRIPTION, entity.getDescription())
              .set(
                  ROOM_INSTANCE.NAME_LOCALIZED_VARIANTS_JSON, entity.getNameLocalizedVariantsJson())
              .set(
                  ROOM_INSTANCE.DESCRIPTION_LOCALIZED_VARIANTS_JSON,
                  entity.getDescriptionLocalizedVariantsJson())
              .set(ROOM_INSTANCE.VERSION, entity.getVersion())
              .set(ROOM_INSTANCE.ZONE_INSTANCE_ID, entity.getZoneInstance().getId())
              .returningResult(ROOM_INSTANCE.ID)
              .fetchOne(ROOM_INSTANCE.ID);
      return findById(id).orElseThrow();
    }
    int updated =
        dsl.update(ROOM_INSTANCE)
            .set(ROOM_INSTANCE.TENANT_ID, entity.getTenantId())
            .set(ROOM_INSTANCE.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(ROOM_INSTANCE.ROOM_INSTANCE_ID, entity.getRoomInstanceRowId())
            .set(ROOM_INSTANCE.TEMPLATE_ROOM_ID, entity.getTemplateRoomId())
            .set(ROOM_INSTANCE.REGION_INSTANCE_ID, entity.getRegionInstance().getId())
            .set(ROOM_INSTANCE.NAME, entity.getName())
            .set(ROOM_INSTANCE.DESCRIPTION, entity.getDescription())
            .set(ROOM_INSTANCE.NAME_LOCALIZED_VARIANTS_JSON, entity.getNameLocalizedVariantsJson())
            .set(
                ROOM_INSTANCE.DESCRIPTION_LOCALIZED_VARIANTS_JSON,
                entity.getDescriptionLocalizedVariantsJson())
            .set(ROOM_INSTANCE.ZONE_INSTANCE_ID, entity.getZoneInstance().getId())
            .set(ROOM_INSTANCE.VERSION, entity.getVersion() + 1)
            .where(
                ROOM_INSTANCE
                    .ID
                    .eq(entity.getId())
                    .and(ROOM_INSTANCE.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("room_instance", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private RoomInstance toEntity(Record record) {
    RoomInstance entity = new RoomInstance();
    entity.setId(record.get(ROOM_INSTANCE.ID));
    entity.setTenantId(record.get(ROOM_INSTANCE.TENANT_ID));
    entity.setGameInstanceId(record.get(ROOM_INSTANCE.GAME_INSTANCE_ID));
    entity.setRoomInstanceRowId(record.get(ROOM_INSTANCE.ROOM_INSTANCE_ID));
    entity.setTemplateRoomId(record.get(ROOM_INSTANCE.TEMPLATE_ROOM_ID));
    entity.setRegionInstance(
        JooqWorldManagementRepositorySupport.partialRegionInstance(
            record.get(ROOM_INSTANCE.REGION_INSTANCE_ID)));
    ZoneInstance zoneInstance =
        JooqWorldManagementRepositorySupport.partialZoneInstance(
            record.get(ROOM_INSTANCE.ZONE_INSTANCE_ID));
    zoneInstance.setRegionInstance(
        JooqWorldManagementRepositorySupport.partialRegionInstance(
            record.get(ZONE_INSTANCE.REGION_INSTANCE_ID)));
    entity.setZoneInstance(zoneInstance);
    entity.setName(record.get(ROOM_INSTANCE.NAME));
    entity.setDescription(record.get(ROOM_INSTANCE.DESCRIPTION));
    entity.setNameLocalizedVariantsJson(record.get(ROOM_INSTANCE.NAME_LOCALIZED_VARIANTS_JSON));
    entity.setDescriptionLocalizedVariantsJson(
        record.get(ROOM_INSTANCE.DESCRIPTION_LOCALIZED_VARIANTS_JSON));
    Integer version = record.get(ROOM_INSTANCE.VERSION);
    entity.setVersion(version == null ? 0 : version);
    return entity;
  }
}
