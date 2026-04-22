package net.firedevops.firemud.entitymanagement.repository;

import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InventoryEntryRepository extends JpaRepository<InventoryEntry, InventoryKey> {

  @EntityGraph(attributePaths = {"item", "character"})
  Page<InventoryEntry> findByIdCharacterId(Long characterId, Pageable pageable);

  @EntityGraph(attributePaths = {"item", "character"})
  Page<InventoryEntry> findByIdCharacterIdAndCharacterTenantId(
      Long characterId, Long tenantId, Pageable pageable);

  long countByCharacterTenantId(Long tenantId);
}
