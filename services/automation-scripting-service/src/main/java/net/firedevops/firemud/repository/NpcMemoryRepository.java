package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.NpcMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NpcMemoryRepository extends JpaRepository<NpcMemory, Long> {}
