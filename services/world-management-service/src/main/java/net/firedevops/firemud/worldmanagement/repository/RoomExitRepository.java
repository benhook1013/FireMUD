package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.RoomExit.ROOM_EXIT;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.RoomExitRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RoomExitRepository {
  private final DSLContext dsl;

  public RoomExitRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<RoomExit> findByTenantId(Long tenantId) {
    return dsl.selectFrom(ROOM_EXIT).where(ROOM_EXIT.TENANT_ID.eq(tenantId)).fetch(this::toEntity);
  }

  public List<RoomExit> findByTenantIdOrderByIdAsc(Long tenantId) {
    return dsl.selectFrom(ROOM_EXIT)
        .where(ROOM_EXIT.TENANT_ID.eq(tenantId))
        .orderBy(ROOM_EXIT.ID.asc())
        .fetch(this::toEntity);
  }

  public List<RoomExit> findByTenantIdAndVersionIdOrderByIdAsc(Long tenantId, Long versionId) {
    return dsl.selectFrom(ROOM_EXIT)
        .where(ROOM_EXIT.TENANT_ID.eq(tenantId).and(ROOM_EXIT.VERSION_ID.eq(versionId)))
        .orderBy(ROOM_EXIT.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<RoomExit> findByTenantIdAndVersionIdAndId(
      Long tenantId, Long versionId, Long id) {
    return dsl.selectFrom(ROOM_EXIT)
        .where(
            ROOM_EXIT
                .TENANT_ID
                .eq(tenantId)
                .and(ROOM_EXIT.VERSION_ID.eq(versionId))
                .and(ROOM_EXIT.ID.eq(id)))
        .fetchOptional(this::toEntity);
  }

  public List<RoomExit> findByTenantIdAndFromRoomId(Long tenantId, Long roomId) {
    return dsl.selectFrom(ROOM_EXIT)
        .where(ROOM_EXIT.TENANT_ID.eq(tenantId).and(ROOM_EXIT.FROM_ROOM_ID.eq(roomId)))
        .orderBy(ROOM_EXIT.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<RoomExit> findById(Long id) {
    return dsl.selectFrom(ROOM_EXIT).where(ROOM_EXIT.ID.eq(id)).fetchOptional(this::toEntity);
  }

  public Optional<RoomExit> findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
      Long tenantId, Long versionId, Long fromRoomId, Long toRoomId, String direction) {
    return dsl.selectFrom(ROOM_EXIT)
        .where(
            ROOM_EXIT
                .TENANT_ID
                .eq(tenantId)
                .and(ROOM_EXIT.VERSION_ID.eq(versionId))
                .and(ROOM_EXIT.FROM_ROOM_ID.eq(fromRoomId))
                .and(ROOM_EXIT.TO_ROOM_ID.eq(toRoomId))
                .and(ROOM_EXIT.DIRECTION.eq(direction)))
        .orderBy(ROOM_EXIT.ID.asc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public RoomExit save(RoomExit entity) {
    if (entity.getId() == null) {
      RoomExitRecord record = dsl.newRecord(ROOM_EXIT);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(ROOM_EXIT)
            .set(ROOM_EXIT.TENANT_ID, entity.getTenantId())
            .set(ROOM_EXIT.VERSION_ID, entity.getVersionId())
            .set(ROOM_EXIT.FROM_ROOM_ID, entity.getFromRoom().getId())
            .set(ROOM_EXIT.TO_ROOM_ID, entity.getToRoom().getId())
            .set(ROOM_EXIT.DIRECTION, entity.getDirection())
            .set(ROOM_EXIT.COST, entity.getCost())
            .set(ROOM_EXIT.VERSION, entity.getVersion() + 1)
            .where(ROOM_EXIT.ID.eq(entity.getId()).and(ROOM_EXIT.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("room_exit", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public void delete(RoomExit entity) {
    dsl.deleteFrom(ROOM_EXIT).where(ROOM_EXIT.ID.eq(entity.getId())).execute();
  }

  public void deleteAll(List<RoomExit> entities) {
    if (entities.isEmpty()) {
      return;
    }
    dsl.deleteFrom(ROOM_EXIT)
        .where(ROOM_EXIT.ID.in(entities.stream().map(RoomExit::getId).toList()))
        .execute();
  }

  private void populate(RoomExitRecord record, RoomExit entity) {
    record.setTenantId(entity.getTenantId());
    record.setVersionId(entity.getVersionId());
    record.setFromRoomId(entity.getFromRoom().getId());
    record.setToRoomId(entity.getToRoom().getId());
    record.setDirection(entity.getDirection());
    record.setCost(entity.getCost());
    record.setVersion(entity.getVersion());
  }

  private RoomExit toEntity(Record record) {
    RoomExit entity = new RoomExit();
    entity.setId(record.get(ROOM_EXIT.ID));
    entity.setTenantId(record.get(ROOM_EXIT.TENANT_ID));
    entity.setVersionId(record.get(ROOM_EXIT.VERSION_ID));
    entity.setFromRoom(
        JooqWorldManagementRepositorySupport.partialRoom(record.get(ROOM_EXIT.FROM_ROOM_ID)));
    entity.setToRoom(
        JooqWorldManagementRepositorySupport.partialRoom(record.get(ROOM_EXIT.TO_ROOM_ID)));
    entity.setDirection(record.get(ROOM_EXIT.DIRECTION));
    Integer cost = record.get(ROOM_EXIT.COST);
    entity.setCost(cost == null ? 1 : cost);
    Integer version = record.get(ROOM_EXIT.VERSION);
    entity.setVersion(version == null ? 0 : version);
    return entity;
  }
}
