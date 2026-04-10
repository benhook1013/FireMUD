package net.firedevops.firemud.entitymanagement.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import org.springframework.stereotype.Component;

/** Keeps container-instance holder state aligned with the owning item instance. */
@Component
@RequiredArgsConstructor
final class ContainerHolderSyncSupport {
  private final ContainerInstanceRepository containerInstanceRepository;

  void ensureSynced(ItemInstance itemInstance) {
    if (!isContainerItem(itemInstance)) {
      return;
    }
    ContainerInstance containerInstance =
        containerInstanceRepository
            .findByItemInstance_Id(itemInstance.getId())
            .orElseGet(() -> newContainerInstance(itemInstance));
    applyHolderState(containerInstance, itemInstance);
    containerInstanceRepository.save(containerInstance);
  }

  void requireExistingAndSync(ItemInstance itemInstance) {
    if (!isContainerItem(itemInstance)) {
      return;
    }
    ContainerInstance containerInstance =
        containerInstanceRepository
            .findByItemInstance_Id(itemInstance.getId())
            .orElseThrow(() -> new IllegalArgumentException("Container instance not found"));
    applyHolderState(containerInstance, itemInstance);
    containerInstanceRepository.save(containerInstance);
  }

  private ContainerInstance newContainerInstance(ItemInstance itemInstance) {
    ContainerInstance created = new ContainerInstance();
    applyHolderState(created, itemInstance);
    return created;
  }

  private void applyHolderState(ContainerInstance containerInstance, ItemInstance itemInstance) {
    containerInstance.setTenantId(itemInstance.getTenantId());
    containerInstance.setCharacter(itemInstance.getCharacter());
    containerInstance.setEquipmentSlot(itemInstance.getEquipmentSlot());
    containerInstance.setGameInstanceId(itemInstance.getGameInstanceId());
    containerInstance.setRoomInstanceId(itemInstance.getRoomInstanceId());
    containerInstance.setItem(itemInstance.getItem());
    containerInstance.setItemInstance(itemInstance);
  }

  private boolean isContainerItem(ItemInstance itemInstance) {
    return itemInstance.getItem() != null && itemInstance.getItem().isContainer();
  }
}
