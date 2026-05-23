package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.Room.ROOM;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.RoomRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RoomRepository {
  private final DSLContext dsl;

  public RoomRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<Room> findByTenantIdOrderByIdAsc(Long tenantId) {
    return dsl.selectFrom(ROOM)
        .where(ROOM.TENANT_ID.eq(tenantId))
        .orderBy(ROOM.ID.asc())
        .fetch(this::toEntity);
  }

  public List<Room> findByTenantIdAndVersionIdOrderByIdAsc(Long tenantId, Long versionId) {
    return dsl.selectFrom(ROOM)
        .where(ROOM.TENANT_ID.eq(tenantId).and(ROOM.VERSION_ID.eq(versionId)))
        .orderBy(ROOM.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<Room> findByTenantIdAndVersionIdAndId(Long tenantId, Long versionId, Long id) {
    return dsl.selectFrom(ROOM)
        .where(ROOM.TENANT_ID.eq(tenantId).and(ROOM.VERSION_ID.eq(versionId)).and(ROOM.ID.eq(id)))
        .fetchOptional(this::toEntity);
  }

  public Optional<Room> findFirstByTenantIdAndVersionIdAndZoneIdAndName(
      Long tenantId, Long versionId, Long zoneId, String name) {
    return dsl.selectFrom(ROOM)
        .where(
            ROOM.TENANT_ID
                .eq(tenantId)
                .and(ROOM.VERSION_ID.eq(versionId))
                .and(ROOM.ZONE_ID.eq(zoneId))
                .and(ROOM.NAME.eq(name)))
        .orderBy(ROOM.ID.asc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public Optional<Room> findById(Long id) {
    return dsl.selectFrom(ROOM).where(ROOM.ID.eq(id)).fetchOptional(this::toEntity);
  }

  public Room save(Room entity) {
    if (entity.getId() == null) {
      RoomRecord record = dsl.newRecord(ROOM);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(ROOM)
            .set(ROOM.ZONE_ID, entity.getZone().getId())
            .set(ROOM.NAME, entity.getName())
            .set(ROOM.DESCRIPTION, entity.getDescription())
            .set(ROOM.TENANT_ID, entity.getTenantId())
            .set(ROOM.VERSION_ID, entity.getVersionId())
            .set(ROOM.NAME_LOCALIZED_VARIANTS_JSON, entity.getNameLocalizedVariantsJson())
            .set(
                ROOM.DESCRIPTION_LOCALIZED_VARIANTS_JSON,
                entity.getDescriptionLocalizedVariantsJson())
            .set(ROOM.VERSION, entity.getVersion() + 1)
            .where(ROOM.ID.eq(entity.getId()).and(ROOM.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("room", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public void delete(Room entity) {
    dsl.deleteFrom(ROOM).where(ROOM.ID.eq(entity.getId())).execute();
  }

  public void deleteAll(List<Room> entities) {
    if (entities.isEmpty()) {
      return;
    }
    dsl.deleteFrom(ROOM).where(ROOM.ID.in(entities.stream().map(Room::getId).toList())).execute();
  }

  private void populate(RoomRecord record, Room entity) {
    record.setZoneId(entity.getZone().getId());
    record.setName(entity.getName());
    record.setDescription(entity.getDescription());
    record.setTenantId(entity.getTenantId());
    record.setVersion(entity.getVersion());
    record.setNameLocalizedVariantsJson(entity.getNameLocalizedVariantsJson());
    record.setDescriptionLocalizedVariantsJson(entity.getDescriptionLocalizedVariantsJson());
    record.setVersionId(entity.getVersionId());
  }

  private Room toEntity(Record record) {
    Room entity = new Room();
    entity.setId(record.get(ROOM.ID));
    entity.setZone(JooqWorldManagementRepositorySupport.partialZone(record.get(ROOM.ZONE_ID)));
    entity.setName(record.get(ROOM.NAME));
    entity.setDescription(record.get(ROOM.DESCRIPTION));
    entity.setTenantId(record.get(ROOM.TENANT_ID));
    Integer version = record.get(ROOM.VERSION);
    entity.setVersion(version == null ? 0 : version);
    entity.setNameLocalizedVariantsJson(record.get(ROOM.NAME_LOCALIZED_VARIANTS_JSON));
    entity.setDescriptionLocalizedVariantsJson(
        record.get(ROOM.DESCRIPTION_LOCALIZED_VARIANTS_JSON));
    entity.setVersionId(record.get(ROOM.VERSION_ID));
    return entity;
  }
}
