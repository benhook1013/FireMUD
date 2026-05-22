package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.EQUIPMENT_SLOT_DEFINITIONS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.EquipmentSlotDefinition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class EquipmentSlotDefinitionRepository {
  private final DSLContext dsl;

  public EquipmentSlotDefinitionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public boolean existsByTenantIdAndVersionId(Long tenantId, Long versionId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(EQUIPMENT_SLOT_DEFINITIONS)
            .where(
                EQUIPMENT_SLOT_DEFINITIONS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(EQUIPMENT_SLOT_DEFINITIONS.VERSION_ID.eq(versionId))));
  }

  public boolean existsByTenantIdAndVersionIdAndSlotKey(
      Long tenantId, Long versionId, String slotKey) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(EQUIPMENT_SLOT_DEFINITIONS)
            .where(
                EQUIPMENT_SLOT_DEFINITIONS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(EQUIPMENT_SLOT_DEFINITIONS.VERSION_ID.eq(versionId))
                    .and(EQUIPMENT_SLOT_DEFINITIONS.SLOT_KEY.eq(slotKey))));
  }

  public Optional<EquipmentSlotDefinition> findByTenantIdAndVersionIdAndSlotKey(
      Long tenantId, Long versionId, String slotKey) {
    return Optional.ofNullable(
        dsl.selectFrom(EQUIPMENT_SLOT_DEFINITIONS)
            .where(
                EQUIPMENT_SLOT_DEFINITIONS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(EQUIPMENT_SLOT_DEFINITIONS.VERSION_ID.eq(versionId))
                    .and(EQUIPMENT_SLOT_DEFINITIONS.SLOT_KEY.eq(slotKey)))
            .fetchOne(this::toEntity));
  }

  public EquipmentSlotDefinition save(EquipmentSlotDefinition entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(EQUIPMENT_SLOT_DEFINITIONS)
              .set(EQUIPMENT_SLOT_DEFINITIONS.TENANT_ID, entity.getTenantId())
              .set(EQUIPMENT_SLOT_DEFINITIONS.VERSION_ID, entity.getVersionId())
              .set(EQUIPMENT_SLOT_DEFINITIONS.SLOT_KEY, entity.getSlotKey())
              .set(EQUIPMENT_SLOT_DEFINITIONS.DISPLAY_NAME, entity.getDisplayName())
              .set(EQUIPMENT_SLOT_DEFINITIONS.SLOT_GROUP_KEY, entity.getSlotGroupKey())
              .set(EQUIPMENT_SLOT_DEFINITIONS.VERSION, entity.getVersion())
              .returningResult(EQUIPMENT_SLOT_DEFINITIONS.ID)
              .fetchOne(EQUIPMENT_SLOT_DEFINITIONS.ID);
      entity.setId(id);
      return entity;
    }
    dsl.update(EQUIPMENT_SLOT_DEFINITIONS)
        .set(EQUIPMENT_SLOT_DEFINITIONS.DISPLAY_NAME, entity.getDisplayName())
        .set(EQUIPMENT_SLOT_DEFINITIONS.SLOT_GROUP_KEY, entity.getSlotGroupKey())
        .set(EQUIPMENT_SLOT_DEFINITIONS.VERSION, entity.getVersion() + 1)
        .where(EQUIPMENT_SLOT_DEFINITIONS.ID.eq(entity.getId()))
        .execute();
    entity.setVersion(entity.getVersion() + 1);
    return entity;
  }

  private EquipmentSlotDefinition toEntity(Record record) {
    if (record == null) {
      return null;
    }
    EquipmentSlotDefinition entity = new EquipmentSlotDefinition();
    entity.setId(record.get(EQUIPMENT_SLOT_DEFINITIONS.ID));
    entity.setTenantId(record.get(EQUIPMENT_SLOT_DEFINITIONS.TENANT_ID));
    entity.setVersionId(record.get(EQUIPMENT_SLOT_DEFINITIONS.VERSION_ID));
    entity.setSlotKey(record.get(EQUIPMENT_SLOT_DEFINITIONS.SLOT_KEY));
    entity.setDisplayName(record.get(EQUIPMENT_SLOT_DEFINITIONS.DISPLAY_NAME));
    entity.setSlotGroupKey(record.get(EQUIPMENT_SLOT_DEFINITIONS.SLOT_GROUP_KEY));
    entity.setVersion(record.get(EQUIPMENT_SLOT_DEFINITIONS.VERSION));
    return entity;
  }
}
