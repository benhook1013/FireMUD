package net.firedevops.firemud.socialgroups.repository;

import net.firedevops.firemud.socialgroups.entity.GuildAlliance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GuildAllianceRepository extends JpaRepository<GuildAlliance, Long> {}
