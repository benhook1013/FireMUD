package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTERS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.CONTAINER_INSTANCES;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEMS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEM_STACKS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ItemStack;
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
public class ItemStackRepository {
  private final DSLContext dsl;
  private final Table<?> holderCharacters = CHARACTERS.as("holder_characters");
  private final Table<?> containerCharacters = CHARACTERS.as("container_characters");

  public ItemStackRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Page<ItemStack>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullOrderByIdAsc(
          Long tenantId, Long characterId, Pageable pageable) {
    Condition condition =
        ITEM_STACKS
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_STACKS.CHARACTER_ID.eq(characterId))
            .and(ITEM_STACKS.EQUIPMENT_SLOT.isNull())
            .and(ITEM_STACKS.GAME_INSTANCE_ID.isNull())
            .and(ITEM_STACKS.ROOM_INSTANCE_ID.isNull())
            .and(ITEM_STACKS.CONTAINER_INSTANCE_ID.isNull());
    return fetchPage(pageable, condition, ITEM_STACKS.ID.asc());
  }

  public Page<ItemStack>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullOrderByIdAsc(
          Long tenantId, String gameInstanceId, String roomInstanceId, Pageable pageable) {
    Condition condition =
        ITEM_STACKS
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_STACKS.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(ITEM_STACKS.ROOM_INSTANCE_ID.eq(roomInstanceId))
            .and(ITEM_STACKS.CHARACTER_ID.isNull())
            .and(ITEM_STACKS.EQUIPMENT_SLOT.isNull())
            .and(ITEM_STACKS.CONTAINER_INSTANCE_ID.isNull());
    return fetchPage(pageable, condition, ITEM_STACKS.ID.asc());
  }

  public Page<ItemStack> findByTenantIdAndContainerInstance_IdOrderByIdAsc(
      Long tenantId, Long containerInstanceId, Pageable pageable) {
    Condition condition =
        ITEM_STACKS
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_STACKS.CONTAINER_INSTANCE_ID.eq(containerInstanceId));
    return fetchPage(pageable, condition, ITEM_STACKS.ID.asc());
  }

  public Optional<ItemStack>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdAndCompatibilityFingerprint(
          Long tenantId, Long characterId, Long itemId, String compatibilityFingerprint) {
    Condition condition =
        ITEM_STACKS
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_STACKS.CHARACTER_ID.eq(characterId))
            .and(ITEM_STACKS.EQUIPMENT_SLOT.isNull())
            .and(ITEM_STACKS.GAME_INSTANCE_ID.isNull())
            .and(ITEM_STACKS.ROOM_INSTANCE_ID.isNull())
            .and(ITEM_STACKS.CONTAINER_INSTANCE_ID.isNull())
            .and(ITEM_STACKS.ITEM_ID.eq(itemId))
            .and(ITEM_STACKS.COMPATIBILITY_FINGERPRINT.eq(compatibilityFingerprint));
    return fetchOne(condition);
  }

  public Optional<ItemStack>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullAndItem_IdAndCompatibilityFingerprint(
          Long tenantId,
          String gameInstanceId,
          String roomInstanceId,
          Long itemId,
          String compatibilityFingerprint) {
    Condition condition =
        ITEM_STACKS
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_STACKS.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(ITEM_STACKS.ROOM_INSTANCE_ID.eq(roomInstanceId))
            .and(ITEM_STACKS.CHARACTER_ID.isNull())
            .and(ITEM_STACKS.EQUIPMENT_SLOT.isNull())
            .and(ITEM_STACKS.CONTAINER_INSTANCE_ID.isNull())
            .and(ITEM_STACKS.ITEM_ID.eq(itemId))
            .and(ITEM_STACKS.COMPATIBILITY_FINGERPRINT.eq(compatibilityFingerprint));
    return fetchOne(condition);
  }

  public Optional<ItemStack>
      findByTenantIdAndContainerInstance_IdAndItem_IdAndCompatibilityFingerprint(
          Long tenantId, Long containerInstanceId, Long itemId, String compatibilityFingerprint) {
    Condition condition =
        ITEM_STACKS
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_STACKS.CONTAINER_INSTANCE_ID.eq(containerInstanceId))
            .and(ITEM_STACKS.ITEM_ID.eq(itemId))
            .and(ITEM_STACKS.COMPATIBILITY_FINGERPRINT.eq(compatibilityFingerprint));
    return fetchOne(condition);
  }

  public List<ItemStack>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
          Long tenantId, Long characterId, Long itemId) {
    Condition condition =
        ITEM_STACKS
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_STACKS.CHARACTER_ID.eq(characterId))
            .and(ITEM_STACKS.EQUIPMENT_SLOT.isNull())
            .and(ITEM_STACKS.GAME_INSTANCE_ID.isNull())
            .and(ITEM_STACKS.ROOM_INSTANCE_ID.isNull())
            .and(ITEM_STACKS.CONTAINER_INSTANCE_ID.isNull())
            .and(ITEM_STACKS.ITEM_ID.eq(itemId));
    return fetchMany(condition, ITEM_STACKS.ID.asc());
  }

  public List<ItemStack>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
          Long tenantId, String gameInstanceId, String roomInstanceId, Long itemId) {
    Condition condition =
        ITEM_STACKS
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_STACKS.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(ITEM_STACKS.ROOM_INSTANCE_ID.eq(roomInstanceId))
            .and(ITEM_STACKS.CHARACTER_ID.isNull())
            .and(ITEM_STACKS.EQUIPMENT_SLOT.isNull())
            .and(ITEM_STACKS.CONTAINER_INSTANCE_ID.isNull())
            .and(ITEM_STACKS.ITEM_ID.eq(itemId));
    return fetchMany(condition, ITEM_STACKS.ID.asc());
  }

  public List<ItemStack> findByTenantIdAndContainerInstance_IdAndItem_IdOrderByIdAsc(
      Long tenantId, Long containerInstanceId, Long itemId) {
    Condition condition =
        ITEM_STACKS
            .TENANT_ID
            .eq(tenantId)
            .and(ITEM_STACKS.CONTAINER_INSTANCE_ID.eq(containerInstanceId))
            .and(ITEM_STACKS.ITEM_ID.eq(itemId));
    return fetchMany(condition, ITEM_STACKS.ID.asc());
  }

  public long deleteByTenantIdAndGameInstanceId(Long tenantId, String gameInstanceId) {
    return dsl.deleteFrom(ITEM_STACKS)
        .where(
            ITEM_STACKS.TENANT_ID.eq(tenantId).and(ITEM_STACKS.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  public long countByTenantIdAndGameInstanceId(Long tenantId, String gameInstanceId) {
    return dsl.fetchCount(
        ITEM_STACKS,
        ITEM_STACKS.TENANT_ID.eq(tenantId).and(ITEM_STACKS.GAME_INSTANCE_ID.eq(gameInstanceId)));
  }

  public ItemStack save(ItemStack entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ITEM_STACKS)
              .set(ITEM_STACKS.TENANT_ID, entity.getTenantId())
              .set(
                  ITEM_STACKS.CHARACTER_ID,
                  entity.getCharacter() == null ? null : entity.getCharacter().getId())
              .set(ITEM_STACKS.EQUIPMENT_SLOT, entity.getEquipmentSlot())
              .set(ITEM_STACKS.GAME_INSTANCE_ID, entity.getGameInstanceId())
              .set(ITEM_STACKS.ROOM_INSTANCE_ID, entity.getRoomInstanceId())
              .set(
                  ITEM_STACKS.CONTAINER_INSTANCE_ID,
                  entity.getContainerInstance() == null
                      ? null
                      : entity.getContainerInstance().getId())
              .set(ITEM_STACKS.ITEM_ID, entity.getItem().getId())
              .set(ITEM_STACKS.COMPATIBILITY_FINGERPRINT, entity.getCompatibilityFingerprint())
              .set(ITEM_STACKS.STACK_FAMILY_KEY, entity.getStackFamilyKey())
              .set(ITEM_STACKS.QUANTITY, entity.getQuantity())
              .set(ITEM_STACKS.VERSION, entity.getVersion())
              .returningResult(ITEM_STACKS.ID)
              .fetchOne(ITEM_STACKS.ID);
      return fetchOne(ITEM_STACKS.ID.eq(id)).orElseThrow();
    }
    dsl.update(ITEM_STACKS)
        .set(ITEM_STACKS.TENANT_ID, entity.getTenantId())
        .set(
            ITEM_STACKS.CHARACTER_ID,
            entity.getCharacter() == null ? null : entity.getCharacter().getId())
        .set(ITEM_STACKS.EQUIPMENT_SLOT, entity.getEquipmentSlot())
        .set(ITEM_STACKS.GAME_INSTANCE_ID, entity.getGameInstanceId())
        .set(ITEM_STACKS.ROOM_INSTANCE_ID, entity.getRoomInstanceId())
        .set(
            ITEM_STACKS.CONTAINER_INSTANCE_ID,
            entity.getContainerInstance() == null ? null : entity.getContainerInstance().getId())
        .set(ITEM_STACKS.ITEM_ID, entity.getItem().getId())
        .set(ITEM_STACKS.COMPATIBILITY_FINGERPRINT, entity.getCompatibilityFingerprint())
        .set(ITEM_STACKS.STACK_FAMILY_KEY, entity.getStackFamilyKey())
        .set(ITEM_STACKS.QUANTITY, entity.getQuantity())
        .set(ITEM_STACKS.VERSION, entity.getVersion() + 1)
        .where(ITEM_STACKS.ID.eq(entity.getId()))
        .execute();
    return fetchOne(ITEM_STACKS.ID.eq(entity.getId())).orElseThrow();
  }

  public void delete(ItemStack entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(ITEM_STACKS).where(ITEM_STACKS.ID.eq(entity.getId())).execute();
    }
  }

  public void deleteAll(Iterable<ItemStack> entities) {
    List<Long> ids =
        java.util.stream.StreamSupport.stream(entities.spliterator(), false)
            .map(ItemStack::getId)
            .filter(java.util.Objects::nonNull)
            .toList();
    if (!ids.isEmpty()) {
      dsl.deleteFrom(ITEM_STACKS).where(ITEM_STACKS.ID.in(ids)).execute();
    }
  }

  private Page<ItemStack> fetchPage(
      Pageable pageable, Condition condition, org.jooq.SortField<?>... orderBy) {
    long total = dsl.fetchCount(ITEM_STACKS, condition);
    var content =
        baseSelect(condition)
            .orderBy(orderBy)
            .limit(
                JooqEntityManagementRepositorySupport.limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(JooqEntityManagementRepositorySupport.offsetOrZero(pageable))
            .fetch(this::toEntity);
    return JooqEntityManagementRepositorySupport.page(content, pageable, total);
  }

  private List<ItemStack> fetchMany(Condition condition, org.jooq.SortField<?>... orderBy) {
    return baseSelect(condition).orderBy(orderBy).fetch(this::toEntity);
  }

  private Optional<ItemStack> fetchOne(Condition condition) {
    return Optional.ofNullable(baseSelect(condition).limit(1).fetchOne(this::toEntity));
  }

  private SelectConditionStep<Record> baseSelect(Condition condition) {
    Table<?> holderCharactersTable = holderCharacters;
    Table<?> containerCharactersTable = containerCharacters;
    return dsl.select(ITEM_STACKS.fields())
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
        .from(ITEM_STACKS)
        .leftJoin(holderCharactersTable)
        .on(ITEM_STACKS.CHARACTER_ID.eq(holderCharactersTable.field("id", Long.class)))
        .join(ITEMS)
        .on(ITEM_STACKS.ITEM_ID.eq(ITEMS.ID))
        .leftJoin(CONTAINER_INSTANCES)
        .on(ITEM_STACKS.CONTAINER_INSTANCE_ID.eq(CONTAINER_INSTANCES.ID))
        .leftJoin(containerCharactersTable)
        .on(CONTAINER_INSTANCES.CHARACTER_ID.eq(containerCharactersTable.field("id", Long.class)))
        .where(condition);
  }

  private ItemStack toEntity(Record record) {
    if (record == null) {
      return null;
    }
    var holderCharactersTable = holderCharacters;
    var containerCharactersTable = containerCharacters;
    return JooqEntityManagementRepositorySupport.partialItemStack(
        record.get(ITEM_STACKS.ID),
        record.get(ITEM_STACKS.TENANT_ID),
        JooqEntityManagementRepositorySupport.partialCharacter(
            record.get(holderCharactersTable.field("id", Long.class)),
            record.get(holderCharactersTable.field("tenant_id", Long.class)),
            record.get(holderCharactersTable.field("account_id", Long.class)),
            record.get(holderCharactersTable.field("playable_state_key", String.class)),
            record.get(holderCharactersTable.field("name", String.class))),
        record.get(ITEM_STACKS.EQUIPMENT_SLOT),
        record.get(ITEM_STACKS.GAME_INSTANCE_ID),
        record.get(ITEM_STACKS.ROOM_INSTANCE_ID),
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
        record.get(ITEM_STACKS.COMPATIBILITY_FINGERPRINT),
        record.get(ITEM_STACKS.STACK_FAMILY_KEY),
        record.get(ITEM_STACKS.QUANTITY),
        record.get(ITEM_STACKS.VERSION));
  }
}
