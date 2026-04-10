package net.firedevops.firemud.entitymanagement.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import org.springframework.stereotype.Component;

/** Centralizes holder-policy checks for container-backed item transfers. */
@Component
@RequiredArgsConstructor
final class ContainerHolderPolicySupport {
  private final ContainerInstanceRepository containerInstanceRepository;

  ContainerInstance requireAccessibleContainer(
      Long tenantId, Long characterId, Long containerInstanceId) {
    ContainerInstance containerInstance =
        containerInstanceRepository
            .findAccessibleByIdAndTenantIdAndCharacterId(containerInstanceId, tenantId, characterId)
            .orElseThrow(() -> new IllegalArgumentException("Container instance not found"));
    requireContainerHolder(containerInstance);
    return containerInstance;
  }

  void requireCanContainItem(ContainerInstance containerInstance, Item item) {
    requireContainerHolder(containerInstance);
    if (item.isContainer()) {
      throw new IllegalArgumentException("Nested containers are not supported");
    }
    if (containerInstance.getItem() != null
        && containerInstance.getItem().getId() != null
        && containerInstance.getItem().getId().equals(item.getId())) {
      throw new IllegalArgumentException("Item cannot be placed into itself");
    }
  }

  private void requireContainerHolder(ContainerInstance containerInstance) {
    if (containerInstance.getItem() == null || !containerInstance.getItem().isContainer()) {
      throw new IllegalArgumentException("Item is not a container");
    }
  }
}
