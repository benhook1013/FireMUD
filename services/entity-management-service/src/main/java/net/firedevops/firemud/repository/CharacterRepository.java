package net.firedevops.firemud.repository;

import java.util.Optional;
import net.firedevops.firemud.entity.Character;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterRepository extends JpaRepository<Character, Long> {

  @EntityGraph(attributePaths = {"inventoryEntries", "inventoryEntries.item"})
  Optional<Character> findWithInventoryById(Long id);
}
