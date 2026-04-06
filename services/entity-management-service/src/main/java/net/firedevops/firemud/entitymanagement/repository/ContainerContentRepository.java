package net.firedevops.firemud.entitymanagement.repository;

import net.firedevops.firemud.entitymanagement.entity.ContainerContentEntry;
import net.firedevops.firemud.entitymanagement.entity.ContainerContentKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContainerContentRepository
    extends JpaRepository<ContainerContentEntry, ContainerContentKey> {
  Page<ContainerContentEntry> findByIdTenantIdAndIdCharacterIdAndIdContainerItemId(
      Long tenantId, Long characterId, Long containerItemId, Pageable pageable);

  java.util.Optional<ContainerContentEntry>
      findByIdTenantIdAndIdCharacterIdAndIdContainerItemIdAndIdItemId(
          Long tenantId, Long characterId, Long containerItemId, Long itemId);
}
