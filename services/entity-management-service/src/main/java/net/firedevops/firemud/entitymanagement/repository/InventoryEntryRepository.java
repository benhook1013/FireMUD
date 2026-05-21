package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTERS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.INVENTORY;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEMS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class InventoryEntryRepository {
  private final DSLContext dsl;

  public InventoryEntryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Page<InventoryEntry> findByIdCharacterId(Long characterId, Pageable pageable) {
    return fetchPage(INVENTORY.CHARACTER_ID.eq(characterId), pageable);
  }

  public Page<InventoryEntry> findByIdCharacterIdAndCharacterTenantId(
      Long characterId, Long tenantId, Pageable pageable) {
    return fetchPage(
        INVENTORY.CHARACTER_ID.eq(characterId).and(CHARACTERS.TENANT_ID.eq(tenantId)), pageable);
  }

  public long countByCharacterTenantId(Long tenantId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(INVENTORY)
            .join(CHARACTERS)
            .on(INVENTORY.CHARACTER_ID.eq(CHARACTERS.ID))
            .where(CHARACTERS.TENANT_ID.eq(tenantId)));
  }

  private Page<InventoryEntry> fetchPage(Condition condition, Pageable pageable) {
    long total =
        dsl.fetchCount(
            dsl.selectOne()
                .from(INVENTORY)
                .join(CHARACTERS)
                .on(INVENTORY.CHARACTER_ID.eq(CHARACTERS.ID))
                .where(condition));
    var content =
        dsl.select(
                INVENTORY.CHARACTER_ID,
                INVENTORY.ITEM_ID,
                INVENTORY.QUANTITY,
                INVENTORY.VERSION,
                CHARACTERS.ID,
                CHARACTERS.TENANT_ID,
                CHARACTERS.ACCOUNT_ID,
                CHARACTERS.PLAYABLE_STATE_KEY,
                CHARACTERS.NAME,
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
            .from(INVENTORY)
            .join(CHARACTERS)
            .on(INVENTORY.CHARACTER_ID.eq(CHARACTERS.ID))
            .join(ITEMS)
            .on(INVENTORY.ITEM_ID.eq(ITEMS.ID))
            .where(condition)
            .orderBy(INVENTORY.ITEM_ID.asc())
            .limit(
                JooqEntityManagementRepositorySupport.limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(JooqEntityManagementRepositorySupport.offsetOrZero(pageable))
            .fetch(this::toEntity);
    return JooqEntityManagementRepositorySupport.page(content, pageable, total);
  }

  private InventoryEntry toEntity(Record record) {
    return JooqEntityManagementRepositorySupport.partialInventoryEntry(
        record.get(INVENTORY.CHARACTER_ID),
        record.get(INVENTORY.ITEM_ID),
        JooqEntityManagementRepositorySupport.partialCharacter(
            record.get(CHARACTERS.ID),
            record.get(CHARACTERS.TENANT_ID),
            record.get(CHARACTERS.ACCOUNT_ID),
            record.get(CHARACTERS.PLAYABLE_STATE_KEY),
            record.get(CHARACTERS.NAME)),
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
        record.get(INVENTORY.QUANTITY),
        record.get(INVENTORY.VERSION));
  }
}
