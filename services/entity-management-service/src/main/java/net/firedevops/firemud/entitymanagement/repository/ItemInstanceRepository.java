package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTERS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.CONTAINER_INSTANCES;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEMS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEM_INSTANCES;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ItemInstanceRepository {
  private final DSLContext dsl;
  private final Table<?> holderCharacters = CHARACTERS.as("holder_characters");
  private final Table<?> containerCharacters = CHARACTERS.as("container_characters");

  public ItemInstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Page<ItemInstance>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
          Long tenantId, Long characterId, Pageable pageable) {
    Condition condition =
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.CHARACTER_ID.eq(characterId))
            .and(ITEM_INSTANCES.EQUIPMENT_SLOT.isNull())
            .and(ITEM_INSTANCES.GAME_INSTANCE_ID.isNull())
            .and(ITEM_INSTANCES.ROOM_INSTANCE_ID.isNull());
    return fetchPage(pageable, condition, ITEM_INSTANCES.ID.asc());
  }

  public Page<ItemInstance>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
          Long tenantId, Long characterId, Pageable pageable) {
    Condition condition =
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.CHARACTER_ID.eq(characterId))
            .and(ITEM_INSTANCES.EQUIPMENT_SLOT.isNotNull())
            .and(ITEM_INSTANCES.GAME_INSTANCE_ID.isNull())
            .and(ITEM_INSTANCES.ROOM_INSTANCE_ID.isNull());
    return fetchPage(
        pageable, condition, ITEM_INSTANCES.EQUIPMENT_SLOT.asc(), ITEM_INSTANCES.ID.asc());
  }

  public List<ItemInstance>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
          Long tenantId, Long characterId) {
    Condition condition =
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.CHARACTER_ID.eq(characterId))
            .and(ITEM_INSTANCES.EQUIPMENT_SLOT.isNotNull())
            .and(ITEM_INSTANCES.GAME_INSTANCE_ID.isNull())
            .and(ITEM_INSTANCES.ROOM_INSTANCE_ID.isNull());
    return fetchMany(condition, ITEM_INSTANCES.EQUIPMENT_SLOT.asc(), ITEM_INSTANCES.ID.asc());
  }

  public Page<ItemInstance>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
          Long tenantId, String gameInstanceId, String roomInstanceId, Pageable pageable) {
    Condition condition =
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(ITEM_INSTANCES.ROOM_INSTANCE_ID.eq(roomInstanceId))
            .and(ITEM_INSTANCES.CHARACTER_ID.isNull())
            .and(ITEM_INSTANCES.EQUIPMENT_SLOT.isNull());
    return fetchPage(pageable, condition, ITEM_INSTANCES.ID.asc());
  }

  public List<ItemInstance>
      findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
          Long tenantId, Long characterId, Long itemId) {
    Condition condition =
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.CHARACTER_ID.eq(characterId))
            .and(ITEM_INSTANCES.ITEM_ID.eq(itemId))
            .and(ITEM_INSTANCES.EQUIPMENT_SLOT.isNull())
            .and(ITEM_INSTANCES.GAME_INSTANCE_ID.isNull())
            .and(ITEM_INSTANCES.ROOM_INSTANCE_ID.isNull());
    return fetchMany(condition, ITEM_INSTANCES.ID.asc());
  }

  public List<ItemInstance>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndItem_IdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
          Long tenantId, String gameInstanceId, String roomInstanceId, Long itemId) {
    Condition condition =
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(ITEM_INSTANCES.ROOM_INSTANCE_ID.eq(roomInstanceId))
            .and(ITEM_INSTANCES.ITEM_ID.eq(itemId))
            .and(ITEM_INSTANCES.CHARACTER_ID.isNull())
            .and(ITEM_INSTANCES.EQUIPMENT_SLOT.isNull());
    return fetchMany(condition, ITEM_INSTANCES.ID.asc());
  }

  public Optional<ItemInstance>
      findByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
          Long tenantId, Long characterId, String equipmentSlot) {
    Condition condition =
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.CHARACTER_ID.eq(characterId))
            .and(ITEM_INSTANCES.EQUIPMENT_SLOT.eq(equipmentSlot))
            .and(ITEM_INSTANCES.GAME_INSTANCE_ID.isNull())
            .and(ITEM_INSTANCES.ROOM_INSTANCE_ID.isNull());
    return fetchOne(condition);
  }

  public boolean
      existsByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
          Long tenantId, Long characterId, String equipmentSlot) {
    return dsl.fetchExists(
        ITEM_INSTANCES,
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.CHARACTER_ID.eq(characterId))
            .and(ITEM_INSTANCES.EQUIPMENT_SLOT.eq(equipmentSlot))
            .and(ITEM_INSTANCES.GAME_INSTANCE_ID.isNull())
            .and(ITEM_INSTANCES.ROOM_INSTANCE_ID.isNull()));
  }

  public List<ItemInstance> findByTenantIdAndCharacter_IdAndItem_IdOrderByIdAsc(
      Long tenantId, Long characterId, Long itemId) {
    Condition condition =
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.CHARACTER_ID.eq(characterId))
            .and(ITEM_INSTANCES.ITEM_ID.eq(itemId));
    return fetchMany(condition, ITEM_INSTANCES.ID.asc());
  }

  public Page<ItemInstance> findByTenantIdAndContainerInstance_IdOrderByIdAsc(
      Long tenantId, Long containerInstanceId, Pageable pageable) {
    Condition condition =
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.CONTAINER_INSTANCE_ID.eq(containerInstanceId));
    return fetchPage(pageable, condition, ITEM_INSTANCES.ID.asc());
  }

  public List<ItemInstance> findByTenantIdAndContainerInstance_IdAndItem_IdOrderByIdAsc(
      Long tenantId, Long containerInstanceId, Long itemId) {
    Condition condition =
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.CONTAINER_INSTANCE_ID.eq(containerInstanceId))
            .and(ITEM_INSTANCES.ITEM_ID.eq(itemId));
    return fetchMany(condition, ITEM_INSTANCES.ID.asc());
  }

  public Optional<ItemInstance> findByIdAndTenantId(Long id, Long tenantId) {
    return fetchOne(ITEM_INSTANCES.ID.eq(id).and(ITEM_INSTANCES.TENANT_ID.eq(tenantId)));
  }

  public Optional<ItemInstance> findByTenantIdAndVisibleRef(Long tenantId, String visibleRef) {
    return fetchOne(
        ITEM_INSTANCES.TENANT_ID.eq(tenantId).and(ITEM_INSTANCES.VISIBLE_REF.eq(visibleRef)));
  }

  public boolean existsByTenantIdAndVisibleRef(Long tenantId, String visibleRef) {
    return dsl.fetchExists(
        ITEM_INSTANCES,
        ITEM_INSTANCES.TENANT_ID.eq(tenantId).and(ITEM_INSTANCES.VISIBLE_REF.eq(visibleRef)));
  }

  public long deleteByTenantIdAndGameInstanceId(Long tenantId, String gameInstanceId) {
    return dsl.deleteFrom(ITEM_INSTANCES)
        .where(
            ITEM_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(ITEM_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  public long countByTenantIdAndGameInstanceId(Long tenantId, String gameInstanceId) {
    return dsl.fetchCount(
        ITEM_INSTANCES,
        ITEM_INSTANCES
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId)));
  }

  public ItemInstance save(ItemInstance entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ITEM_INSTANCES)
              .set(ITEM_INSTANCES.TENANT_ID, entity.getTenantId())
              .set(
                  ITEM_INSTANCES.CHARACTER_ID,
                  entity.getCharacter() == null ? null : entity.getCharacter().getId())
              .set(ITEM_INSTANCES.EQUIPMENT_SLOT, entity.getEquipmentSlot())
              .set(ITEM_INSTANCES.GAME_INSTANCE_ID, entity.getGameInstanceId())
              .set(ITEM_INSTANCES.ROOM_INSTANCE_ID, entity.getRoomInstanceId())
              .set(
                  ITEM_INSTANCES.CONTAINER_INSTANCE_ID,
                  entity.getContainerInstance() == null
                      ? null
                      : entity.getContainerInstance().getId())
              .set(ITEM_INSTANCES.ITEM_ID, entity.getItem().getId())
              .set(ITEM_INSTANCES.VISIBLE_REF_TOKEN, entity.getVisibleRefToken())
              .set(ITEM_INSTANCES.VISIBLE_REF_SEQUENCE, entity.getVisibleRefSequence())
              .set(ITEM_INSTANCES.VISIBLE_REF, entity.getVisibleRef())
              .set(ITEM_INSTANCES.VERSION, entity.getVersion())
              .returningResult(ITEM_INSTANCES.ID)
              .fetchOne(ITEM_INSTANCES.ID);
      return findByIdAndTenantId(id, entity.getTenantId()).orElseThrow();
    }
    dsl.update(ITEM_INSTANCES)
        .set(ITEM_INSTANCES.TENANT_ID, entity.getTenantId())
        .set(
            ITEM_INSTANCES.CHARACTER_ID,
            entity.getCharacter() == null ? null : entity.getCharacter().getId())
        .set(ITEM_INSTANCES.EQUIPMENT_SLOT, entity.getEquipmentSlot())
        .set(ITEM_INSTANCES.GAME_INSTANCE_ID, entity.getGameInstanceId())
        .set(ITEM_INSTANCES.ROOM_INSTANCE_ID, entity.getRoomInstanceId())
        .set(
            ITEM_INSTANCES.CONTAINER_INSTANCE_ID,
            entity.getContainerInstance() == null ? null : entity.getContainerInstance().getId())
        .set(ITEM_INSTANCES.ITEM_ID, entity.getItem().getId())
        .set(ITEM_INSTANCES.VISIBLE_REF_TOKEN, entity.getVisibleRefToken())
        .set(ITEM_INSTANCES.VISIBLE_REF_SEQUENCE, entity.getVisibleRefSequence())
        .set(ITEM_INSTANCES.VISIBLE_REF, entity.getVisibleRef())
        .set(ITEM_INSTANCES.VERSION, entity.getVersion() + 1)
        .where(ITEM_INSTANCES.ID.eq(entity.getId()))
        .execute();
    return findByIdAndTenantId(entity.getId(), entity.getTenantId()).orElseThrow();
  }

  public void delete(ItemInstance entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(ITEM_INSTANCES).where(ITEM_INSTANCES.ID.eq(entity.getId())).execute();
    }
  }

  private Page<ItemInstance> fetchPage(
      Pageable pageable, Condition condition, org.jooq.SortField<?>... orderBy) {
    long total = dsl.fetchCount(ITEM_INSTANCES, condition);
    var content =
        baseSelect(condition)
            .orderBy(orderBy)
            .limit(
                JooqEntityManagementRepositorySupport.limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(JooqEntityManagementRepositorySupport.offsetOrZero(pageable))
            .fetch(this::toEntity);
    return JooqEntityManagementRepositorySupport.page(content, pageable, total);
  }

  private List<ItemInstance> fetchMany(Condition condition, org.jooq.SortField<?>... orderBy) {
    return baseSelect(condition).orderBy(orderBy).fetch(this::toEntity);
  }

  private Optional<ItemInstance> fetchOne(Condition condition) {
    return Optional.ofNullable(baseSelect(condition).limit(1).fetchOne(this::toEntity));
  }

  private SelectConditionStep<Record> baseSelect(Condition condition) {
    Table<?> holderCharactersTable = holderCharacters;
    Table<?> containerCharactersTable = containerCharacters;
    return dsl.select(ITEM_INSTANCES.fields())
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
            ITEMS.EFFECT_PAYLOAD_JSON,
            CONTAINER_INSTANCES.ID,
            CONTAINER_INSTANCES.TENANT_ID,
            CONTAINER_INSTANCES.EQUIPMENT_SLOT,
            CONTAINER_INSTANCES.GAME_INSTANCE_ID,
            CONTAINER_INSTANCES.ROOM_INSTANCE_ID,
            CONTAINER_INSTANCES.ITEM_INSTANCE_ID,
            containerCharactersTable.field("id", Long.class),
            containerCharactersTable.field("tenant_id", Long.class),
            containerCharactersTable.field("account_id", Long.class),
            containerCharactersTable.field("playable_state_key", String.class),
            containerCharactersTable.field("name", String.class))
        .from(ITEM_INSTANCES)
        .leftJoin(holderCharactersTable)
        .on(ITEM_INSTANCES.CHARACTER_ID.eq(holderCharactersTable.field("id", Long.class)))
        .join(ITEMS)
        .on(ITEM_INSTANCES.ITEM_ID.eq(ITEMS.ID))
        .leftJoin(CONTAINER_INSTANCES)
        .on(ITEM_INSTANCES.CONTAINER_INSTANCE_ID.eq(CONTAINER_INSTANCES.ID))
        .leftJoin(containerCharactersTable)
        .on(CONTAINER_INSTANCES.CHARACTER_ID.eq(containerCharactersTable.field("id", Long.class)))
        .where(condition);
  }

  private ItemInstance toEntity(Record record) {
    if (record == null) {
      return null;
    }
    var holderCharactersTable = holderCharacters;
    var containerCharactersTable = containerCharacters;
    return JooqEntityManagementRepositorySupport.partialItemInstance(
        record.get(ITEM_INSTANCES.ID),
        record.get(ITEM_INSTANCES.TENANT_ID),
        JooqEntityManagementRepositorySupport.partialCharacter(
            record.get(holderCharactersTable.field("id", Long.class)),
            record.get(holderCharactersTable.field("tenant_id", Long.class)),
            record.get(holderCharactersTable.field("account_id", Long.class)),
            record.get(holderCharactersTable.field("playable_state_key", String.class)),
            record.get(holderCharactersTable.field("name", String.class))),
        record.get(ITEM_INSTANCES.EQUIPMENT_SLOT),
        record.get(ITEM_INSTANCES.GAME_INSTANCE_ID),
        record.get(ITEM_INSTANCES.ROOM_INSTANCE_ID),
        JooqEntityManagementRepositorySupport.partialContainer(
            record.get(CONTAINER_INSTANCES.ID),
            record.get(CONTAINER_INSTANCES.TENANT_ID),
            JooqEntityManagementRepositorySupport.partialCharacter(
                record.get(containerCharactersTable.field("id", Long.class)),
                record.get(containerCharactersTable.field("tenant_id", Long.class)),
                record.get(containerCharactersTable.field("account_id", Long.class)),
                record.get(containerCharactersTable.field("playable_state_key", String.class)),
                record.get(containerCharactersTable.field("name", String.class))),
            record.get(CONTAINER_INSTANCES.EQUIPMENT_SLOT),
            record.get(CONTAINER_INSTANCES.GAME_INSTANCE_ID),
            record.get(CONTAINER_INSTANCES.ROOM_INSTANCE_ID),
            null,
            record.get(CONTAINER_INSTANCES.ITEM_INSTANCE_ID)),
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
        record.get(ITEM_INSTANCES.VISIBLE_REF_TOKEN),
        record.get(ITEM_INSTANCES.VISIBLE_REF_SEQUENCE),
        record.get(ITEM_INSTANCES.VISIBLE_REF),
        record.get(ITEM_INSTANCES.VERSION));
  }
}
