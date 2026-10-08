package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.*;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTERS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.INVENTORY;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEMS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashSet;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class CharacterRepository {
  private final DSLContext dsl;

  public CharacterRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<Character> findWithInventoryById(Long id) {
    Character character =
        dsl.selectFrom(CHARACTERS).where(CHARACTERS.ID.eq(id)).fetchOne(this::toEntity);
    if (character == null) {
      return Optional.empty();
    }
    character.setInventoryEntries(
        new LinkedHashSet<>(
            dsl.select(
                    INVENTORY.CHARACTER_ID,
                    INVENTORY.ITEM_ID,
                    INVENTORY.QUANTITY,
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
                .join(ITEMS)
                .on(INVENTORY.ITEM_ID.eq(ITEMS.ID))
                .where(INVENTORY.CHARACTER_ID.eq(id))
                .orderBy(INVENTORY.ITEM_ID.asc())
                .fetch(record -> toInventoryEntry(record, character))));
    return Optional.of(character);
  }

  public Optional<Character> findById(Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTERS).where(CHARACTERS.ID.eq(id)).fetchOne(this::toEntity));
  }

  public Optional<Character> findByIdAndTenantId(Long id, Long tenantId) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTERS)
            .where(CHARACTERS.ID.eq(id).and(CHARACTERS.TENANT_ID.eq(tenantId)))
            .fetchOne(this::toEntity));
  }

  public Optional<Character> findByIdAndTenantIdAndPlayableStateKey(
      Long id, Long tenantId, String playableStateKey) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTERS)
            .where(
                CHARACTERS
                    .ID
                    .eq(id)
                    .and(CHARACTERS.TENANT_ID.eq(tenantId))
                    .and(CHARACTERS.PLAYABLE_STATE_KEY.eq(playableStateKey)))
            .fetchOne(this::toEntity));
  }

  public Page<Character> findByTenantIdAndAccountIdAndPlayableStateKey(
      Long tenantId, Long accountId, String playableStateKey, Pageable pageable) {
    var condition =
        CHARACTERS
            .TENANT_ID
            .eq(tenantId)
            .and(CHARACTERS.ACCOUNT_ID.eq(accountId))
            .and(CHARACTERS.PLAYABLE_STATE_KEY.eq(playableStateKey));
    long total = dsl.fetchCount(CHARACTERS, condition);
    var content =
        dsl.selectFrom(CHARACTERS)
            .where(condition)
            .orderBy(CHARACTERS.ID.asc())
            .limit(limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(offsetOrZero(pageable))
            .fetch(this::toEntity);
    return pageable == null || pageable.isUnpaged()
        ? new PageImpl<>(content)
        : new PageImpl<>(content, pageable, total);
  }

  public Optional<Character> findByTenantIdAndPlayableStateKeyAndNameIgnoreCase(
      Long tenantId, String playableStateKey, String name) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTERS)
            .where(
                CHARACTERS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(CHARACTERS.PLAYABLE_STATE_KEY.eq(playableStateKey))
                    .and(DSL.lower(CHARACTERS.NAME).eq(name.toLowerCase())))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public long countByTenantId(Long tenantId) {
    return dsl.fetchCount(CHARACTERS, CHARACTERS.TENANT_ID.eq(tenantId));
  }

  public Character save(Character entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(CHARACTERS)
              .set(CHARACTERS.TENANT_ID, entity.getTenantId())
              .set(CHARACTERS.ACCOUNT_ID, entity.getAccountId())
              .set(CHARACTERS.PLAYABLE_STATE_KEY, entity.getPlayableStateKey())
              .set(CHARACTERS.NAME, entity.getName())
              .set(CHARACTERS.BODY_LAYOUT_KEY, entity.getBodyLayoutKey())
              .set(CHARACTERS.LEVEL, entity.getLevel())
              .set(CHARACTERS.EXPERIENCE, entity.getExperience())
              .set(CHARACTERS.STRENGTH, entity.getStrength())
              .set(CHARACTERS.AGILITY, entity.getAgility())
              .set(CHARACTERS.INTELLIGENCE, entity.getIntelligence())
              .set(CHARACTERS.STAMINA, entity.getStamina())
              .set(CHARACTERS.HEALTH, entity.getHealth())
              .set(CHARACTERS.MANA, entity.getMana())
              .set(CHARACTERS.LAST_LOGIN_AT, toLocalDateTime(entity.getLastLoginAt()))
              .set(CHARACTERS.VERSION, entity.getVersion())
              .returningResult(CHARACTERS.ID)
              .fetchOne(CHARACTERS.ID);
      return findById(id).orElseThrow();
    }
    dsl.update(CHARACTERS)
        .set(CHARACTERS.TENANT_ID, entity.getTenantId())
        .set(CHARACTERS.ACCOUNT_ID, entity.getAccountId())
        .set(CHARACTERS.PLAYABLE_STATE_KEY, entity.getPlayableStateKey())
        .set(CHARACTERS.NAME, entity.getName())
        .set(CHARACTERS.BODY_LAYOUT_KEY, entity.getBodyLayoutKey())
        .set(CHARACTERS.LEVEL, entity.getLevel())
        .set(CHARACTERS.EXPERIENCE, entity.getExperience())
        .set(CHARACTERS.STRENGTH, entity.getStrength())
        .set(CHARACTERS.AGILITY, entity.getAgility())
        .set(CHARACTERS.INTELLIGENCE, entity.getIntelligence())
        .set(CHARACTERS.STAMINA, entity.getStamina())
        .set(CHARACTERS.HEALTH, entity.getHealth())
        .set(CHARACTERS.MANA, entity.getMana())
        .set(CHARACTERS.BODY_LAYOUT_KEY, entity.getBodyLayoutKey())
        .set(CHARACTERS.LAST_LOGIN_AT, toLocalDateTime(entity.getLastLoginAt()))
        .set(CHARACTERS.VERSION, entity.getVersion() + 1)
        .where(CHARACTERS.ID.eq(entity.getId()))
        .execute();
    return findById(entity.getId()).orElseThrow();
  }

  private Character toEntity(Record record) {
    if (record == null) {
      return null;
    }
    Character character = new Character();
    character.setId(record.get(CHARACTERS.ID));
    character.setTenantId(record.get(CHARACTERS.TENANT_ID));
    character.setAccountId(record.get(CHARACTERS.ACCOUNT_ID));
    character.setPlayableStateKey(record.get(CHARACTERS.PLAYABLE_STATE_KEY));
    character.setName(record.get(CHARACTERS.NAME));
    character.setBodyLayoutKey(record.get(CHARACTERS.BODY_LAYOUT_KEY));
    character.setLevel(record.get(CHARACTERS.LEVEL));
    character.setExperience(record.get(CHARACTERS.EXPERIENCE));
    character.setStrength(record.get(CHARACTERS.STRENGTH));
    character.setAgility(record.get(CHARACTERS.AGILITY));
    character.setIntelligence(record.get(CHARACTERS.INTELLIGENCE));
    character.setStamina(record.get(CHARACTERS.STAMINA));
    character.setHealth(record.get(CHARACTERS.HEALTH));
    character.setMana(record.get(CHARACTERS.MANA));
    character.setLastLoginAt(toInstant(record.get(CHARACTERS.LAST_LOGIN_AT)));
    character.setVersion(record.get(CHARACTERS.VERSION));
    return character;
  }

  private InventoryEntry toInventoryEntry(Record record, Character character) {
    InventoryEntry entry = new InventoryEntry();
    InventoryKey key = new InventoryKey();
    key.setCharacterId(record.get(INVENTORY.CHARACTER_ID));
    key.setItemId(record.get(INVENTORY.ITEM_ID));
    entry.setId(key);
    entry.setCharacter(character);
    entry.setItem(
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
            record.get(ITEMS.EFFECT_PAYLOAD_JSON)));
    entry.setQuantity(record.get(INVENTORY.QUANTITY));
    entry.setVersion(record.get(INVENTORY.VERSION));
    return entry;
  }
}
