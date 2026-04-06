package net.firedevops.firemud.entitymanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContainerInstanceRepository extends JpaRepository<ContainerInstance, Long> {
  @EntityGraph(attributePaths = {"character", "item"})
  Optional<ContainerInstance> findByIdAndTenantIdAndCharacter_Id(
      Long id, Long tenantId, Long characterId);

  @EntityGraph(attributePaths = {"character", "item"})
  Optional<ContainerInstance> findByTenantIdAndCharacter_IdAndItem_Id(
      Long tenantId, Long characterId, Long itemId);
}
