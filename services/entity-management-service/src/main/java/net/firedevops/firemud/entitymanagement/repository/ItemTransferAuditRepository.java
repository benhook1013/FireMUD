package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEM_TRANSFER_AUDITS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.entitymanagement.entity.ItemTransferAudit;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ItemTransferAuditRepository {
  private final DSLContext dsl;

  public ItemTransferAuditRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public ItemTransferAudit save(ItemTransferAudit audit) {
    Long id =
        dsl.insertInto(ITEM_TRANSFER_AUDITS)
            .set(ITEM_TRANSFER_AUDITS.TENANT_ID, audit.getTenantId())
            .set(ITEM_TRANSFER_AUDITS.ITEM_ID, audit.getItemId())
            .set(ITEM_TRANSFER_AUDITS.ITEM_INSTANCE_ID, audit.getItemInstanceId())
            .set(ITEM_TRANSFER_AUDITS.QUANTITY, audit.getQuantity())
            .set(ITEM_TRANSFER_AUDITS.STACK_FAMILY_KEY, audit.getStackFamilyKey())
            .set(ITEM_TRANSFER_AUDITS.VERB, audit.getVerb())
            .set(ITEM_TRANSFER_AUDITS.ACTOR_CHARACTER_ID, audit.getActorCharacterId())
            .set(ITEM_TRANSFER_AUDITS.SESSION_ID, audit.getSessionId())
            .set(ITEM_TRANSFER_AUDITS.EFFECT_ID, audit.getEffectId())
            .set(ITEM_TRANSFER_AUDITS.CORRELATION_KEY, audit.getCorrelationKey())
            .set(ITEM_TRANSFER_AUDITS.SOURCE_HOLDER_KIND, audit.getSourceHolderKind())
            .set(ITEM_TRANSFER_AUDITS.SOURCE_CHARACTER_ID, audit.getSourceCharacterId())
            .set(ITEM_TRANSFER_AUDITS.SOURCE_EQUIPMENT_SLOT, audit.getSourceEquipmentSlot())
            .set(ITEM_TRANSFER_AUDITS.SOURCE_GAME_INSTANCE_ID, audit.getSourceGameInstanceId())
            .set(ITEM_TRANSFER_AUDITS.SOURCE_ROOM_INSTANCE_ID, audit.getSourceRoomInstanceId())
            .set(
                ITEM_TRANSFER_AUDITS.SOURCE_CONTAINER_INSTANCE_ID,
                audit.getSourceContainerInstanceId())
            .set(ITEM_TRANSFER_AUDITS.DESTINATION_HOLDER_KIND, audit.getDestinationHolderKind())
            .set(ITEM_TRANSFER_AUDITS.DESTINATION_CHARACTER_ID, audit.getDestinationCharacterId())
            .set(
                ITEM_TRANSFER_AUDITS.DESTINATION_EQUIPMENT_SLOT,
                audit.getDestinationEquipmentSlot())
            .set(
                ITEM_TRANSFER_AUDITS.DESTINATION_GAME_INSTANCE_ID,
                audit.getDestinationGameInstanceId())
            .set(
                ITEM_TRANSFER_AUDITS.DESTINATION_ROOM_INSTANCE_ID,
                audit.getDestinationRoomInstanceId())
            .set(
                ITEM_TRANSFER_AUDITS.DESTINATION_CONTAINER_INSTANCE_ID,
                audit.getDestinationContainerInstanceId())
            .set(ITEM_TRANSFER_AUDITS.CREATED_AT, toLocalDateTime(audit.getCreatedAt()))
            .returningResult(ITEM_TRANSFER_AUDITS.ID)
            .fetchOne(ITEM_TRANSFER_AUDITS.ID);
    audit.setId(id);
    return audit;
  }
}
