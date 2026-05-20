package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.BODY_LAYOUT_SLOT_DEFINITIONS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.entitymanagement.entity.BodyLayoutSlotDefinition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class BodyLayoutSlotDefinitionRepository {
  private final DSLContext dsl;

  public BodyLayoutSlotDefinitionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public boolean existsByTenantIdAndVersionIdAndBodyLayoutKey(
      Long tenantId, Long versionId, String bodyLayoutKey) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(BODY_LAYOUT_SLOT_DEFINITIONS)
            .where(
                BODY_LAYOUT_SLOT_DEFINITIONS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(BODY_LAYOUT_SLOT_DEFINITIONS.VERSION_ID.eq(versionId))
                    .and(BODY_LAYOUT_SLOT_DEFINITIONS.BODY_LAYOUT_KEY.eq(bodyLayoutKey))));
  }

  public boolean existsByTenantIdAndVersionIdAndBodyLayoutKeyAndSlotKey(
      Long tenantId, Long versionId, String bodyLayoutKey, String slotKey) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(BODY_LAYOUT_SLOT_DEFINITIONS)
            .where(
                BODY_LAYOUT_SLOT_DEFINITIONS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(BODY_LAYOUT_SLOT_DEFINITIONS.VERSION_ID.eq(versionId))
                    .and(BODY_LAYOUT_SLOT_DEFINITIONS.BODY_LAYOUT_KEY.eq(bodyLayoutKey))
                    .and(BODY_LAYOUT_SLOT_DEFINITIONS.SLOT_KEY.eq(slotKey))));
  }

  public BodyLayoutSlotDefinition save(BodyLayoutSlotDefinition entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(BODY_LAYOUT_SLOT_DEFINITIONS)
              .set(BODY_LAYOUT_SLOT_DEFINITIONS.TENANT_ID, entity.getTenantId())
              .set(BODY_LAYOUT_SLOT_DEFINITIONS.VERSION_ID, entity.getVersionId())
              .set(BODY_LAYOUT_SLOT_DEFINITIONS.BODY_LAYOUT_KEY, entity.getBodyLayoutKey())
              .set(BODY_LAYOUT_SLOT_DEFINITIONS.SLOT_KEY, entity.getSlotKey())
              .set(BODY_LAYOUT_SLOT_DEFINITIONS.VERSION, entity.getVersion())
              .returningResult(BODY_LAYOUT_SLOT_DEFINITIONS.ID)
              .fetchOne(BODY_LAYOUT_SLOT_DEFINITIONS.ID);
      entity.setId(id);
      return entity;
    }
    dsl.update(BODY_LAYOUT_SLOT_DEFINITIONS)
        .set(BODY_LAYOUT_SLOT_DEFINITIONS.BODY_LAYOUT_KEY, entity.getBodyLayoutKey())
        .set(BODY_LAYOUT_SLOT_DEFINITIONS.SLOT_KEY, entity.getSlotKey())
        .set(BODY_LAYOUT_SLOT_DEFINITIONS.VERSION, entity.getVersion() + 1)
        .where(BODY_LAYOUT_SLOT_DEFINITIONS.ID.eq(entity.getId()))
        .execute();
    entity.setVersion(entity.getVersion() + 1);
    return entity;
  }

  private BodyLayoutSlotDefinition toEntity(Record record) {
    if (record == null) {
      return null;
    }
    BodyLayoutSlotDefinition entity = new BodyLayoutSlotDefinition();
    entity.setId(record.get(BODY_LAYOUT_SLOT_DEFINITIONS.ID));
    entity.setTenantId(record.get(BODY_LAYOUT_SLOT_DEFINITIONS.TENANT_ID));
    entity.setVersionId(record.get(BODY_LAYOUT_SLOT_DEFINITIONS.VERSION_ID));
    entity.setBodyLayoutKey(record.get(BODY_LAYOUT_SLOT_DEFINITIONS.BODY_LAYOUT_KEY));
    entity.setSlotKey(record.get(BODY_LAYOUT_SLOT_DEFINITIONS.SLOT_KEY));
    entity.setVersion(record.get(BODY_LAYOUT_SLOT_DEFINITIONS.VERSION));
    return entity;
  }
}
