package net.firedevops.firemud.entitymanagement.repository;

import net.firedevops.firemud.entitymanagement.entity.CharacterEquipmentEntry;
import net.firedevops.firemud.entitymanagement.entity.CharacterEquipmentKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterEquipmentRepository
    extends JpaRepository<CharacterEquipmentEntry, CharacterEquipmentKey> {

  @EntityGraph(attributePaths = {"item", "character"})
  Page<CharacterEquipmentEntry> findByIdCharacterIdAndCharacterTenantId(
      Long characterId, Long tenantId, Pageable pageable);
}
