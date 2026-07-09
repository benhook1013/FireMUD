package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.RoomInstance.ROOM_INSTANCE;
import static net.firedevops.firemud.worldmanagement.jooq.tables.RoomInstanceExit.ROOM_INSTANCE_EXIT;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.worldmanagement.entity.RoomInstance;
import net.firedevops.firemud.worldmanagement.entity.RoomInstanceExit;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RoomInstanceExitRepository {
  private final DSLContext dsl;

  public RoomInstanceExitRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<RoomInstanceExit> findByTenantIdAndGameInstanceIdAndFromRoomInstanceRecordId(
      Long tenantId, Long gameInstanceId, Long fromRoomInstanceRecordId) {
    var toRoom = ROOM_INSTANCE.as("to_room");
    return dsl.select(ROOM_INSTANCE_EXIT.fields())
        .select(
            toRoom.ID,
            toRoom.ROOM_INSTANCE_ROW_ID,
            toRoom.NAME,
            toRoom.DESCRIPTION,
            toRoom.NAME_LOCALIZED_VARIANTS_JSON,
            toRoom.DESCRIPTION_LOCALIZED_VARIANTS_JSON)
        .from(ROOM_INSTANCE_EXIT)
        .join(toRoom)
        .on(ROOM_INSTANCE_EXIT.TO_ROOM_INSTANCE_ID.eq(toRoom.ID))
        .where(
            ROOM_INSTANCE_EXIT
                .TENANT_ID
                .eq(tenantId)
                .and(ROOM_INSTANCE_EXIT.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(ROOM_INSTANCE_EXIT.FROM_ROOM_INSTANCE_ID.eq(fromRoomInstanceRecordId)))
        .orderBy(ROOM_INSTANCE_EXIT.ID.asc())
        .fetch(record -> toEntity(record, toRoom));
  }

  public long countByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    return dsl.fetchCount(
        ROOM_INSTANCE_EXIT,
        ROOM_INSTANCE_EXIT
            .TENANT_ID
            .eq(tenantId)
            .and(ROOM_INSTANCE_EXIT.GAME_INSTANCE_ID.eq(gameInstanceId)));
  }

  public void deleteByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    dsl.deleteFrom(ROOM_INSTANCE_EXIT)
        .where(
            ROOM_INSTANCE_EXIT
                .TENANT_ID
                .eq(tenantId)
                .and(ROOM_INSTANCE_EXIT.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  public RoomInstanceExit save(RoomInstanceExit entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ROOM_INSTANCE_EXIT)
              .set(ROOM_INSTANCE_EXIT.TENANT_ID, entity.getTenantId())
              .set(ROOM_INSTANCE_EXIT.GAME_INSTANCE_ID, entity.getGameInstanceId())
              .set(ROOM_INSTANCE_EXIT.FROM_ROOM_INSTANCE_ID, entity.getFromRoomInstance().getId())
              .set(ROOM_INSTANCE_EXIT.TO_ROOM_INSTANCE_ID, entity.getToRoomInstance().getId())
              .set(ROOM_INSTANCE_EXIT.DIRECTION, entity.getDirection())
              .set(ROOM_INSTANCE_EXIT.COST, entity.getCost())
              .set(ROOM_INSTANCE_EXIT.VERSION, entity.getVersion())
              .returningResult(ROOM_INSTANCE_EXIT.ID)
              .fetchOne(ROOM_INSTANCE_EXIT.ID);
      RoomInstanceExit saved = new RoomInstanceExit();
      saved.setId(id);
      return saved;
    }
    int updated =
        dsl.update(ROOM_INSTANCE_EXIT)
            .set(ROOM_INSTANCE_EXIT.TENANT_ID, entity.getTenantId())
            .set(ROOM_INSTANCE_EXIT.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(ROOM_INSTANCE_EXIT.FROM_ROOM_INSTANCE_ID, entity.getFromRoomInstance().getId())
            .set(ROOM_INSTANCE_EXIT.TO_ROOM_INSTANCE_ID, entity.getToRoomInstance().getId())
            .set(ROOM_INSTANCE_EXIT.DIRECTION, entity.getDirection())
            .set(ROOM_INSTANCE_EXIT.COST, entity.getCost())
            .set(ROOM_INSTANCE_EXIT.VERSION, entity.getVersion() + 1)
            .where(
                ROOM_INSTANCE_EXIT
                    .ID
                    .eq(entity.getId())
                    .and(ROOM_INSTANCE_EXIT.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("room_instance_exit", entity.getId());
    }
    return entity;
  }

  private RoomInstanceExit toEntity(Record record, org.jooq.Table<?> toRoomAlias) {
    RoomInstanceExit entity = new RoomInstanceExit();
    entity.setId(record.get(ROOM_INSTANCE_EXIT.ID));
    entity.setTenantId(record.get(ROOM_INSTANCE_EXIT.TENANT_ID));
    entity.setGameInstanceId(record.get(ROOM_INSTANCE_EXIT.GAME_INSTANCE_ID));
    Long fromRoomInstanceRecordId = record.get(ROOM_INSTANCE_EXIT.FROM_ROOM_INSTANCE_ID);
    entity.setFromRoomInstance(
        JooqWorldManagementRepositorySupport.partialRoomInstanceRecord(fromRoomInstanceRecordId));
    Long toRoomInstanceRecordId =
        record.get((org.jooq.Field<Long>) toRoomAlias.field(ROOM_INSTANCE.ID));
    RoomInstance toRoomInstance =
        JooqWorldManagementRepositorySupport.partialRoomInstanceRecord(toRoomInstanceRecordId);
    toRoomInstance.setRoomInstanceRowId(
        record.get((org.jooq.Field<Long>) toRoomAlias.field(ROOM_INSTANCE.ROOM_INSTANCE_ROW_ID)));
    toRoomInstance.setName(
        record.get((org.jooq.Field<String>) toRoomAlias.field(ROOM_INSTANCE.NAME)));
    toRoomInstance.setDescription(
        record.get((org.jooq.Field<String>) toRoomAlias.field(ROOM_INSTANCE.DESCRIPTION)));
    toRoomInstance.setNameLocalizedVariantsJson(
        record.get(
            (org.jooq.Field<String>)
                toRoomAlias.field(ROOM_INSTANCE.NAME_LOCALIZED_VARIANTS_JSON)));
    toRoomInstance.setDescriptionLocalizedVariantsJson(
        record.get(
            (org.jooq.Field<String>)
                toRoomAlias.field(ROOM_INSTANCE.DESCRIPTION_LOCALIZED_VARIANTS_JSON)));
    entity.setToRoomInstance(toRoomInstance);
    entity.setDirection(record.get(ROOM_INSTANCE_EXIT.DIRECTION));
    Integer cost = record.get(ROOM_INSTANCE_EXIT.COST);
    entity.setCost(cost == null ? 1 : cost);
    Integer version = record.get(ROOM_INSTANCE_EXIT.VERSION);
    entity.setVersion(version == null ? 0 : version);
    return entity;
  }
}
