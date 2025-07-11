package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.NpcFormation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NpcFormationRepository extends JpaRepository<NpcFormation, Long> {}
