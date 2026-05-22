package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEMS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Item;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ItemRepository {
  private final DSLContext dsl;

  public ItemRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<Item> findById(Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(ITEMS).where(ITEMS.ID.eq(id)).fetchOne(this::toEntity));
  }

  public Optional<Item> findByIdAndTenantId(Long id, Long tenantId) {
    return Optional.ofNullable(
        dsl.selectFrom(ITEMS)
            .where(ITEMS.ID.eq(id).and(ITEMS.TENANT_ID.eq(tenantId)))
            .fetchOne(this::toEntity));
  }

  public Optional<Item> findByTenantIdAndVersionIdAndId(Long tenantId, Long versionId, Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(ITEMS)
            .where(
                ITEMS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(ITEMS.VERSION_ID.eq(versionId))
                    .and(ITEMS.ID.eq(id)))
            .fetchOne(this::toEntity));
  }

  public Optional<Item> findByTenantIdAndNameIgnoreCase(Long tenantId, String name) {
    return Optional.ofNullable(
        dsl.selectFrom(ITEMS)
            .where(ITEMS.TENANT_ID.eq(tenantId).and(DSL.lower(ITEMS.NAME).eq(name.toLowerCase())))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public List<Item> findByTenantIdOrderByIdAsc(Long tenantId) {
    return dsl.selectFrom(ITEMS)
        .where(ITEMS.TENANT_ID.eq(tenantId))
        .orderBy(ITEMS.ID.asc())
        .fetch(this::toEntity);
  }

  public List<Item> findByTenantIdAndVersionIdOrderByIdAsc(Long tenantId, Long versionId) {
    return dsl.selectFrom(ITEMS)
        .where(ITEMS.TENANT_ID.eq(tenantId).and(ITEMS.VERSION_ID.eq(versionId)))
        .orderBy(ITEMS.ID.asc())
        .fetch(this::toEntity);
  }

  public Item save(Item item) {
    if (item.getId() == null) {
      Long id =
          dsl.insertInto(ITEMS)
              .set(ITEMS.TENANT_ID, item.getTenantId())
              .set(ITEMS.VERSION_ID, item.getVersionId())
              .set(ITEMS.NAME, item.getName())
              .set(ITEMS.DESCRIPTION, item.getDescription())
              .set(ITEMS.EQUIPMENT_SLOT, item.getEquipmentSlot())
              .set(ITEMS.EQUIPMENT_SLOT_GROUP_KEY, item.getEquipmentSlotGroupKey())
              .set(ITEMS.IS_CONTAINER, item.isContainer())
              .set(ITEMS.IS_STACKABLE, item.isStackable())
              .set(
                  ITEMS.STACK_COMPATIBILITY_MODE,
                  item.getStackCompatibilityMode() == null
                      ? null
                      : item.getStackCompatibilityMode().name())
              .set(ITEMS.STACK_VARIANT_KEY, item.getStackVariantKey())
              .set(ITEMS.EFFECT_PAYLOAD_JSON, item.getEffectPayloadJson())
              .set(ITEMS.VERSION, item.getVersion())
              .returningResult(ITEMS.ID)
              .fetchOne(ITEMS.ID);
      return findById(id).orElseThrow();
    }
    dsl.update(ITEMS)
        .set(ITEMS.TENANT_ID, item.getTenantId())
        .set(ITEMS.VERSION_ID, item.getVersionId())
        .set(ITEMS.NAME, item.getName())
        .set(ITEMS.DESCRIPTION, item.getDescription())
        .set(ITEMS.EQUIPMENT_SLOT, item.getEquipmentSlot())
        .set(ITEMS.EQUIPMENT_SLOT_GROUP_KEY, item.getEquipmentSlotGroupKey())
        .set(ITEMS.IS_CONTAINER, item.isContainer())
        .set(ITEMS.IS_STACKABLE, item.isStackable())
        .set(
            ITEMS.STACK_COMPATIBILITY_MODE,
            item.getStackCompatibilityMode() == null
                ? null
                : item.getStackCompatibilityMode().name())
        .set(ITEMS.STACK_VARIANT_KEY, item.getStackVariantKey())
        .set(ITEMS.EFFECT_PAYLOAD_JSON, item.getEffectPayloadJson())
        .set(ITEMS.VERSION, item.getVersion() + 1)
        .where(ITEMS.ID.eq(item.getId()))
        .execute();
    return findById(item.getId()).orElseThrow();
  }

  private Item toEntity(Record record) {
    return JooqEntityManagementRepositorySupport.partialItem(
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
        record.get(ITEMS.EFFECT_PAYLOAD_JSON));
  }
}
