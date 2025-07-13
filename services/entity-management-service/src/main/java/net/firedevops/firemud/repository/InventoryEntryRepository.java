package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.InventoryEntry;
import net.firedevops.firemud.entity.InventoryKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InventoryEntryRepository
    extends JpaRepository<InventoryEntry, InventoryKey> {

  Page<InventoryEntry> findByIdCharacterId(Long characterId, Pageable pageable);
}
