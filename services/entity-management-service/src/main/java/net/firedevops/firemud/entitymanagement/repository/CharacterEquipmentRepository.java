package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTERS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTER_EQUIPMENT;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEMS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.entitymanagement.entity.CharacterEquipmentEntry;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class CharacterEquipmentRepository {
  private final DSLContext dsl;

  public CharacterEquipmentRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Page<CharacterEquipmentEntry> findByIdCharacterIdAndCharacterTenantId(
      Long characterId, Long tenantId, Pageable pageable) {
    long total =
        dsl.fetchCount(
            dsl.selectOne()
                .from(CHARACTER_EQUIPMENT)
                .join(CHARACTERS)
                .on(CHARACTER_EQUIPMENT.CHARACTER_ID.eq(CHARACTERS.ID))
                .where(
                    CHARACTER_EQUIPMENT
                        .CHARACTER_ID
                        .eq(characterId)
                        .and(CHARACTERS.TENANT_ID.eq(tenantId))));
    var content =
        dsl.select(
                CHARACTER_EQUIPMENT.CHARACTER_ID,
                CHARACTER_EQUIPMENT.SLOT,
                CHARACTER_EQUIPMENT.ITEM_ID,
                CHARACTER_EQUIPMENT.VERSION,
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
            .from(CHARACTER_EQUIPMENT)
            .join(CHARACTERS)
            .on(CHARACTER_EQUIPMENT.CHARACTER_ID.eq(CHARACTERS.ID))
            .join(ITEMS)
            .on(CHARACTER_EQUIPMENT.ITEM_ID.eq(ITEMS.ID))
            .where(
                CHARACTER_EQUIPMENT
                    .CHARACTER_ID
                    .eq(characterId)
                    .and(CHARACTERS.TENANT_ID.eq(tenantId)))
            .orderBy(CHARACTER_EQUIPMENT.SLOT.asc())
            .limit(
                JooqEntityManagementRepositorySupport.limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(JooqEntityManagementRepositorySupport.offsetOrZero(pageable))
            .fetch(this::toEntity);
    return JooqEntityManagementRepositorySupport.page(content, pageable, total);
  }

  public long countByCharacterTenantId(Long tenantId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(CHARACTER_EQUIPMENT)
            .join(CHARACTERS)
            .on(CHARACTER_EQUIPMENT.CHARACTER_ID.eq(CHARACTERS.ID))
            .where(CHARACTERS.TENANT_ID.eq(tenantId)));
  }

  private CharacterEquipmentEntry toEntity(Record record) {
    return JooqEntityManagementRepositorySupport.partialCharacterEquipmentEntry(
        record.get(CHARACTER_EQUIPMENT.CHARACTER_ID),
        record.get(CHARACTER_EQUIPMENT.SLOT),
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
        record.get(CHARACTER_EQUIPMENT.VERSION));
  }
}
