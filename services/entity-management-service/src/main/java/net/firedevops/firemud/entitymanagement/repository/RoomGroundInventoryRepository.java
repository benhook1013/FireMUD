package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEMS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ROOM_GROUND_INVENTORY;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryEntry;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RoomGroundInventoryRepository {
  private final DSLContext dsl;

  public RoomGroundInventoryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Page<RoomGroundInventoryEntry> findByIdTenantIdAndIdGameInstanceIdAndIdRoomInstanceId(
      Long tenantId, String gameInstanceId, String roomInstanceId, Pageable pageable) {
    long total =
        dsl.fetchCount(
            dsl.selectOne()
                .from(ROOM_GROUND_INVENTORY)
                .where(
                    ROOM_GROUND_INVENTORY
                        .TENANT_ID
                        .eq(tenantId)
                        .and(ROOM_GROUND_INVENTORY.GAME_INSTANCE_ID.eq(gameInstanceId))
                        .and(ROOM_GROUND_INVENTORY.ROOM_INSTANCE_ID.eq(roomInstanceId))));
    var content =
        dsl.select(
                ROOM_GROUND_INVENTORY.TENANT_ID,
                ROOM_GROUND_INVENTORY.GAME_INSTANCE_ID,
                ROOM_GROUND_INVENTORY.ROOM_INSTANCE_ID,
                ROOM_GROUND_INVENTORY.ITEM_ID,
                ROOM_GROUND_INVENTORY.QUANTITY,
                ROOM_GROUND_INVENTORY.VERSION,
                ITEMS.ID,
                ITEMS.TENANT_ID,
                ITEMS.VERSION_ID,
                ITEMS.NAME,
                ITEMS.DESCRIPTION,
                ITEMS.EQUIPMENT_SLOT,
                ITEMS.EQUIPMENT_SLOT_GROUP_KEY,
                ITEMS.IS_CONTAINER,
                ITEMS.IS_STACKABLE,
                ITEMS.STACK_COMPATIBILITY_MODE,
                ITEMS.STACK_VARIANT_KEY,
                ITEMS.EFFECT_PAYLOAD_JSON)
            .from(ROOM_GROUND_INVENTORY)
            .join(ITEMS)
            .on(ROOM_GROUND_INVENTORY.ITEM_ID.eq(ITEMS.ID))
            .where(
                ROOM_GROUND_INVENTORY
                    .TENANT_ID
                    .eq(tenantId)
                    .and(ROOM_GROUND_INVENTORY.GAME_INSTANCE_ID.eq(gameInstanceId))
                    .and(ROOM_GROUND_INVENTORY.ROOM_INSTANCE_ID.eq(roomInstanceId)))
            .orderBy(ROOM_GROUND_INVENTORY.ITEM_ID.asc())
            .limit(
                JooqEntityManagementRepositorySupport.limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(JooqEntityManagementRepositorySupport.offsetOrZero(pageable))
            .fetch(this::toEntity);
    return JooqEntityManagementRepositorySupport.page(content, pageable, total);
  }

  public long deleteByIdTenantIdAndIdGameInstanceId(Long tenantId, String gameInstanceId) {
    return dsl.deleteFrom(ROOM_GROUND_INVENTORY)
        .where(
            ROOM_GROUND_INVENTORY
                .TENANT_ID
                .eq(tenantId)
                .and(ROOM_GROUND_INVENTORY.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  public long countByIdTenantIdAndIdGameInstanceId(Long tenantId, String gameInstanceId) {
    return dsl.fetchCount(
        ROOM_GROUND_INVENTORY,
        ROOM_GROUND_INVENTORY
            .TENANT_ID
            .eq(tenantId)
            .and(ROOM_GROUND_INVENTORY.GAME_INSTANCE_ID.eq(gameInstanceId)));
  }

  private RoomGroundInventoryEntry toEntity(Record record) {
    return JooqEntityManagementRepositorySupport.partialRoomGroundInventoryEntry(
        record.get(ROOM_GROUND_INVENTORY.TENANT_ID),
        record.get(ROOM_GROUND_INVENTORY.GAME_INSTANCE_ID),
        record.get(ROOM_GROUND_INVENTORY.ROOM_INSTANCE_ID),
        record.get(ROOM_GROUND_INVENTORY.ITEM_ID),
        JooqEntityManagementRepositorySupport.partialItem(
            record.get(ITEMS.ID),
            record.get(ITEMS.TENANT_ID),
            record.get(ITEMS.VERSION_ID),
            record.get(ITEMS.NAME),
            record.get(ITEMS.DESCRIPTION),
            record.get(ITEMS.EQUIPMENT_SLOT),
            record.get(ITEMS.EQUIPMENT_SLOT_GROUP_KEY),
            record.get(ITEMS.IS_CONTAINER),
            record.get(ITEMS.IS_STACKABLE),
            record.get(ITEMS.STACK_COMPATIBILITY_MODE),
            record.get(ITEMS.STACK_VARIANT_KEY),
            record.get(ITEMS.EFFECT_PAYLOAD_JSON)),
        record.get(ROOM_GROUND_INVENTORY.QUANTITY),
        record.get(ROOM_GROUND_INVENTORY.VERSION));
  }
}
