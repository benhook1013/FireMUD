package net.firedevops.firemud.entitymanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterRepository extends JpaRepository<Character, Long> {

  @EntityGraph(attributePaths = {"inventoryEntries", "inventoryEntries.item"})
  Optional<Character> findWithInventoryById(Long id);

  Page<Character> findByTenantIdAndAccountId(Long tenantId, Long accountId, Pageable pageable);

  Optional<Character> findByTenantIdAndNameIgnoreCase(Long tenantId, String name);
}
