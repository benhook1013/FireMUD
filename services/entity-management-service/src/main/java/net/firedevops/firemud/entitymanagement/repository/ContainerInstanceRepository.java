package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTERS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.CONTAINER_INSTANCES;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEMS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ContainerInstanceRepository {
  private final DSLContext dsl;
  private final Table<?> holderCharacters = CHARACTERS.as("holder_characters");

  public ContainerInstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ContainerInstance> findAccessibleByIdAndTenantIdAndCharacterId(
      Long id, Long tenantId, Long characterId) {
    return fetchOne(
        CONTAINER_INSTANCES
            .ID
            .eq(id)
            .and(CONTAINER_INSTANCES.TENANT_ID.eq(tenantId))
            .and(CONTAINER_INSTANCES.CHARACTER_ID.eq(characterId)));
  }

  public Optional<ContainerInstance> findAccessibleByIdAndTenantIdAndCharacterIdOrRoom(
      Long id, Long tenantId, Long characterId, String gameInstanceId, String roomInstanceId) {
    Condition roomCondition =
        gameInstanceId == null || roomInstanceId == null
            ? org.jooq.impl.DSL.falseCondition()
            : CONTAINER_INSTANCES
                .CHARACTER_ID
                .isNull()
                .and(CONTAINER_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(CONTAINER_INSTANCES.ROOM_INSTANCE_ID.eq(roomInstanceId));
    return fetchOne(
        CONTAINER_INSTANCES
            .ID
            .eq(id)
            .and(CONTAINER_INSTANCES.TENANT_ID.eq(tenantId))
            .and(CONTAINER_INSTANCES.CHARACTER_ID.eq(characterId).or(roomCondition)));
  }

  public Optional<ContainerInstance>
      findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
          Long tenantId, Long characterId, Long itemId) {
    return fetchOne(
        CONTAINER_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(CONTAINER_INSTANCES.CHARACTER_ID.eq(characterId))
            .and(CONTAINER_INSTANCES.ITEM_ID.eq(itemId))
            .and(CONTAINER_INSTANCES.EQUIPMENT_SLOT.isNull())
            .and(CONTAINER_INSTANCES.GAME_INSTANCE_ID.isNull())
            .and(CONTAINER_INSTANCES.ROOM_INSTANCE_ID.isNull()));
  }

  public Optional<ContainerInstance>
      findByTenantIdAndCharacter_IdAndEquipmentSlotAndItem_IdAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
          Long tenantId, Long characterId, String equipmentSlot, Long itemId) {
    return fetchOne(
        CONTAINER_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(CONTAINER_INSTANCES.CHARACTER_ID.eq(characterId))
            .and(CONTAINER_INSTANCES.EQUIPMENT_SLOT.eq(equipmentSlot))
            .and(CONTAINER_INSTANCES.ITEM_ID.eq(itemId))
            .and(CONTAINER_INSTANCES.GAME_INSTANCE_ID.isNull())
            .and(CONTAINER_INSTANCES.ROOM_INSTANCE_ID.isNull()));
  }

  public Optional<ContainerInstance>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndItem_IdAndCharacterIsNullAndEquipmentSlotIsNull(
          Long tenantId, String gameInstanceId, String roomInstanceId, Long itemId) {
    return fetchOne(
        CONTAINER_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(CONTAINER_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(CONTAINER_INSTANCES.ROOM_INSTANCE_ID.eq(roomInstanceId))
            .and(CONTAINER_INSTANCES.ITEM_ID.eq(itemId))
            .and(CONTAINER_INSTANCES.CHARACTER_ID.isNull())
            .and(CONTAINER_INSTANCES.EQUIPMENT_SLOT.isNull()));
  }

  public Optional<ContainerInstance> findByTenantIdAndItem_Id(Long tenantId, Long itemId) {
    return fetchOne(
        CONTAINER_INSTANCES.TENANT_ID.eq(tenantId).and(CONTAINER_INSTANCES.ITEM_ID.eq(itemId)));
  }

  public Optional<ContainerInstance> findByItemInstance_Id(Long itemInstanceId) {
    return fetchOne(CONTAINER_INSTANCES.ITEM_INSTANCE_ID.eq(itemInstanceId));
  }

  public long deleteByTenantIdAndGameInstanceId(Long tenantId, String gameInstanceId) {
    return dsl.deleteFrom(CONTAINER_INSTANCES)
        .where(
            CONTAINER_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(CONTAINER_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  public long countByTenantIdAndGameInstanceId(Long tenantId, String gameInstanceId) {
    return dsl.fetchCount(
        CONTAINER_INSTANCES,
        CONTAINER_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(CONTAINER_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId)));
  }

  public ContainerInstance save(ContainerInstance entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(CONTAINER_INSTANCES)
              .set(CONTAINER_INSTANCES.TENANT_ID, entity.getTenantId())
              .set(
                  CONTAINER_INSTANCES.CHARACTER_ID,
                  entity.getCharacter() == null ? null : entity.getCharacter().getId())
              .set(CONTAINER_INSTANCES.EQUIPMENT_SLOT, entity.getEquipmentSlot())
              .set(CONTAINER_INSTANCES.GAME_INSTANCE_ID, entity.getGameInstanceId())
              .set(CONTAINER_INSTANCES.ROOM_INSTANCE_ID, entity.getRoomInstanceId())
              .set(CONTAINER_INSTANCES.ITEM_ID, entity.getItem().getId())
              .set(
                  CONTAINER_INSTANCES.ITEM_INSTANCE_ID,
                  entity.getItemInstance() == null ? null : entity.getItemInstance().getId())
              .set(CONTAINER_INSTANCES.VERSION, entity.getVersion())
              .returningResult(CONTAINER_INSTANCES.ID)
              .fetchOne(CONTAINER_INSTANCES.ID);
      return fetchOne(CONTAINER_INSTANCES.ID.eq(id)).orElseThrow();
    }
    dsl.update(CONTAINER_INSTANCES)
        .set(CONTAINER_INSTANCES.TENANT_ID, entity.getTenantId())
        .set(
            CONTAINER_INSTANCES.CHARACTER_ID,
            entity.getCharacter() == null ? null : entity.getCharacter().getId())
        .set(CONTAINER_INSTANCES.EQUIPMENT_SLOT, entity.getEquipmentSlot())
        .set(CONTAINER_INSTANCES.GAME_INSTANCE_ID, entity.getGameInstanceId())
        .set(CONTAINER_INSTANCES.ROOM_INSTANCE_ID, entity.getRoomInstanceId())
        .set(CONTAINER_INSTANCES.ITEM_ID, entity.getItem().getId())
        .set(
            CONTAINER_INSTANCES.ITEM_INSTANCE_ID,
            entity.getItemInstance() == null ? null : entity.getItemInstance().getId())
        .set(CONTAINER_INSTANCES.VERSION, entity.getVersion() + 1)
        .where(CONTAINER_INSTANCES.ID.eq(entity.getId()))
        .execute();
    return fetchOne(CONTAINER_INSTANCES.ID.eq(entity.getId())).orElseThrow();
  }

  public void delete(ContainerInstance entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(CONTAINER_INSTANCES)
          .where(CONTAINER_INSTANCES.ID.eq(entity.getId()))
          .execute();
    }
  }

  private Optional<ContainerInstance> fetchOne(Condition condition) {
    return Optional.ofNullable(baseSelect(condition).limit(1).fetchOne(this::toEntity));
  }

  private SelectConditionStep<Record> baseSelect(Condition condition) {
    Table<?> holderCharactersTable = holderCharacters;
    return dsl.select(CONTAINER_INSTANCES.fields())
        .select(
            holderCharactersTable.field("id", Long.class),
            holderCharactersTable.field("tenant_id", Long.class),
            holderCharactersTable.field("account_id", Long.class),
            holderCharactersTable.field("playable_state_key", String.class),
            holderCharactersTable.field("name", String.class),
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
        .from(CONTAINER_INSTANCES)
        .leftJoin(holderCharactersTable)
        .on(CONTAINER_INSTANCES.CHARACTER_ID.eq(holderCharactersTable.field("id", Long.class)))
        .join(ITEMS)
        .on(CONTAINER_INSTANCES.ITEM_ID.eq(ITEMS.ID))
        .where(condition);
  }

  private ContainerInstance toEntity(Record record) {
    if (record == null) {
      return null;
    }
    var holderCharactersTable = holderCharacters;
    return JooqEntityManagementRepositorySupport.partialContainer(
        record.get(CONTAINER_INSTANCES.ID),
        record.get(CONTAINER_INSTANCES.TENANT_ID),
        JooqEntityManagementRepositorySupport.partialCharacter(
            record.get(holderCharactersTable.field("id", Long.class)),
            record.get(holderCharactersTable.field("tenant_id", Long.class)),
            record.get(holderCharactersTable.field("account_id", Long.class)),
            record.get(holderCharactersTable.field("playable_state_key", String.class)),
            record.get(holderCharactersTable.field("name", String.class))),
        record.get(CONTAINER_INSTANCES.EQUIPMENT_SLOT),
        record.get(CONTAINER_INSTANCES.GAME_INSTANCE_ID),
        record.get(CONTAINER_INSTANCES.ROOM_INSTANCE_ID),
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
        record.get(CONTAINER_INSTANCES.ITEM_INSTANCE_ID));
  }
}
