package net.firedevops.firemud.automationscripting.repository;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.FactionStanding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FactionStandingRepository extends JpaRepository<FactionStanding, Long> {
  Optional<FactionStanding> findByTenantIdAndCharacterIdAndFaction_Id(
      Long tenantId, Long characterId, Long factionId);
}
