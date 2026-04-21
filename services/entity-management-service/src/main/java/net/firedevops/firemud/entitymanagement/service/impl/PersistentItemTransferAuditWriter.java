package net.firedevops.firemud.entitymanagement.service.impl;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.entity.ItemTransferAudit;
import net.firedevops.firemud.entitymanagement.repository.ItemTransferAuditRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class PersistentItemTransferAuditWriter implements ItemTransferAuditWriter {
  private final ItemTransferAuditRepository itemTransferAuditRepository;
  private final ItemTransferSupport itemTransferSupport;

  @Override
  public void recordInstanceTransfer(
      ItemInstance instance,
      ItemTransferSupport.ExpectedSource expectedSource,
      ItemTransferSupport.Destination destination,
      ItemTransferSupport.TransferAuditContext auditContext) {
    ItemTransferAudit audit =
        buildAudit(
            instance.getTenantId(),
            instance.getItem().getId(),
            instance.getId(),
            1,
            null,
            itemTransferSupport.snapshot(expectedSource),
            itemTransferSupport.snapshot(destination),
            auditContext);
    itemTransferAuditRepository.save(audit);
  }

  @Override
  public void recordStackTransfer(
      Long tenantId,
      Item item,
      int quantity,
      String stackFamilyKey,
      ItemTransferSupport.HolderSnapshot source,
      ItemTransferSupport.HolderSnapshot destination,
      ItemTransferSupport.TransferAuditContext auditContext) {
    ItemTransferAudit audit =
        buildAudit(
            tenantId,
            item.getId(),
            null,
            quantity,
            stackFamilyKey,
            source,
            destination,
            auditContext);
    itemTransferAuditRepository.save(audit);
  }

  private ItemTransferAudit buildAudit(
      Long tenantId,
      Long itemId,
      Long itemInstanceId,
      int quantity,
      String stackFamilyKey,
      ItemTransferSupport.HolderSnapshot source,
      ItemTransferSupport.HolderSnapshot destination,
      ItemTransferSupport.TransferAuditContext auditContext) {
    ItemTransferAudit audit = new ItemTransferAudit();
    audit.setTenantId(tenantId);
    audit.setItemId(itemId);
    audit.setItemInstanceId(itemInstanceId);
    audit.setQuantity(quantity);
    audit.setStackFamilyKey(stackFamilyKey);
    audit.setVerb(auditContext.verb());
    audit.setActorCharacterId(auditContext.actorCharacterId());
    audit.setSessionId(auditContext.sessionId());
    audit.setEffectId(auditContext.effectId());
    applySource(audit, source);
    applyDestination(audit, destination);
    audit.setCorrelationKey(
        resolveCorrelationKey(
            tenantId,
            itemId,
            itemInstanceId,
            quantity,
            stackFamilyKey,
            source,
            destination,
            auditContext));
    return audit;
  }

  private void applySource(ItemTransferAudit audit, ItemTransferSupport.HolderSnapshot source) {
    audit.setSourceHolderKind(source.kind().name());
    audit.setSourceCharacterId(source.characterId());
    audit.setSourceEquipmentSlot(source.equipmentSlot());
    audit.setSourceGameInstanceId(source.gameInstanceId());
    audit.setSourceRoomInstanceId(source.roomInstanceId());
    audit.setSourceContainerInstanceId(source.containerInstanceId());
  }

  private void applyDestination(
      ItemTransferAudit audit, ItemTransferSupport.HolderSnapshot destination) {
    audit.setDestinationHolderKind(destination.kind().name());
    audit.setDestinationCharacterId(destination.characterId());
    audit.setDestinationEquipmentSlot(destination.equipmentSlot());
    audit.setDestinationGameInstanceId(destination.gameInstanceId());
    audit.setDestinationRoomInstanceId(destination.roomInstanceId());
    audit.setDestinationContainerInstanceId(destination.containerInstanceId());
  }

  private String resolveCorrelationKey(
      Long tenantId,
      Long itemId,
      Long itemInstanceId,
      int quantity,
      String stackFamilyKey,
      ItemTransferSupport.HolderSnapshot source,
      ItemTransferSupport.HolderSnapshot destination,
      ItemTransferSupport.TransferAuditContext auditContext) {
    if (auditContext.correlationId() != null && !auditContext.correlationId().isBlank()) {
      return auditContext.correlationId().trim();
    }
    return String.join(
        "|",
        auditContext.verb(),
        "tenant=" + tenantId,
        "item=" + itemId,
        "instance=" + Objects.toString(itemInstanceId, ""),
        "quantity=" + quantity,
        "stack=" + Objects.toString(stackFamilyKey, ""),
        "actor=" + Objects.toString(auditContext.actorCharacterId(), ""),
        "session=" + Objects.toString(auditContext.sessionId(), ""),
        "effect=" + Objects.toString(auditContext.effectId(), ""),
        "source=" + describeHolder(source),
        "destination=" + describeHolder(destination));
  }

  private String describeHolder(ItemTransferSupport.HolderSnapshot holder) {
    return String.join(
        ",",
        holder.kind().name(),
        Objects.toString(holder.characterId(), ""),
        Objects.toString(holder.equipmentSlot(), ""),
        Objects.toString(holder.gameInstanceId(), ""),
        Objects.toString(holder.roomInstanceId(), ""),
        Objects.toString(holder.containerInstanceId(), ""));
  }
}
