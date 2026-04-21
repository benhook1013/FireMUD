package net.firedevops.firemud.entitymanagement.service.impl;

import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;

final class NoOpItemTransferAuditWriter implements ItemTransferAuditWriter {
  @Override
  public void recordInstanceTransfer(
      ItemInstance instance,
      ItemTransferSupport.ExpectedSource expectedSource,
      ItemTransferSupport.Destination destination,
      ItemTransferSupport.TransferAuditContext auditContext) {}

  @Override
  public void recordStackTransfer(
      Long tenantId,
      Item item,
      int quantity,
      String stackFamilyKey,
      ItemTransferSupport.HolderSnapshot source,
      ItemTransferSupport.HolderSnapshot destination,
      ItemTransferSupport.TransferAuditContext auditContext) {}
}
