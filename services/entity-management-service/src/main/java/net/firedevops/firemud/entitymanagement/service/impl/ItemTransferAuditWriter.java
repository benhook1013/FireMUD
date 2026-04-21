package net.firedevops.firemud.entitymanagement.service.impl;

import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;

interface ItemTransferAuditWriter {
  void recordInstanceTransfer(
      ItemInstance instance,
      ItemTransferSupport.ExpectedSource expectedSource,
      ItemTransferSupport.Destination destination,
      ItemTransferSupport.TransferAuditContext auditContext);

  void recordStackTransfer(
      Long tenantId,
      Item item,
      int quantity,
      String stackFamilyKey,
      ItemTransferSupport.HolderSnapshot source,
      ItemTransferSupport.HolderSnapshot destination,
      ItemTransferSupport.TransferAuditContext auditContext);
}
