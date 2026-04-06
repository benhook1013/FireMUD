package net.firedevops.firemud.entitymanagement.repository;

import net.firedevops.firemud.entitymanagement.entity.ContainerContentEntry;
import net.firedevops.firemud.entitymanagement.entity.ContainerContentKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContainerContentRepository
    extends JpaRepository<ContainerContentEntry, ContainerContentKey> {
  @EntityGraph(attributePaths = {"containerInstance", "item"})
  Page<ContainerContentEntry> findByIdTenantIdAndIdContainerInstanceId(
      Long tenantId, Long containerInstanceId, Pageable pageable);

  @EntityGraph(attributePaths = {"containerInstance", "item"})
  java.util.Optional<ContainerContentEntry> findByIdTenantIdAndIdContainerInstanceIdAndIdItemId(
      Long tenantId, Long containerInstanceId, Long itemId);
}
