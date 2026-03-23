package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.entity.Faction;
import net.firedevops.firemud.automationscripting.entity.FactionStanding;
import net.firedevops.firemud.automationscripting.repository.FactionRepository;
import net.firedevops.firemud.automationscripting.repository.FactionStandingRepository;
import net.firedevops.firemud.automationscripting.service.FactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FactionServiceImpl implements FactionService {
  private final FactionRepository factionRepository;
  private final FactionStandingRepository standingRepository;

  @Override
  @Transactional
  @Timed(value = "faction.adjustReputation")
  public int adjustReputation(Long tenantId, Long characterId, Long factionId, int delta) {
    FactionStanding standing =
        standingRepository
            .findByTenantIdAndCharacterIdAndFaction_Id(tenantId, characterId, factionId)
            .orElseGet(
                () -> {
                  Faction faction = factionRepository.findById(factionId).orElseThrow();
                  FactionStanding fs = new FactionStanding();
                  fs.setTenantId(tenantId);
                  fs.setCharacterId(characterId);
                  fs.setFaction(faction);
                  return fs;
                });
    standing.setReputation(standing.getReputation() + delta);
    standingRepository.save(standing);
    return standing.getReputation();
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "faction.getReputation")
  public int getReputation(Long tenantId, Long characterId, Long factionId) {
    return standingRepository
        .findByTenantIdAndCharacterIdAndFaction_Id(tenantId, characterId, factionId)
        .map(FactionStanding::getReputation)
        .orElse(0);
  }
}
