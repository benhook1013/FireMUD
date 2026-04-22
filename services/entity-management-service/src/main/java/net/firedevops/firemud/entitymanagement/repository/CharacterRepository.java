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

  Optional<Character> findByIdAndTenantId(Long id, Long tenantId);

  Page<Character> findByTenantIdAndAccountIdAndPlayableStateKey(
      Long tenantId, Long accountId, String playableStateKey, Pageable pageable);

  Optional<Character> findByTenantIdAndPlayableStateKeyAndNameIgnoreCase(
      Long tenantId, String playableStateKey, String name);

  long countByTenantId(Long tenantId);
}
