package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.GuildAlliance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GuildAllianceRepository extends JpaRepository<GuildAlliance, Long> {}
