package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.Npc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NpcRepository extends JpaRepository<Npc, Long> {}
