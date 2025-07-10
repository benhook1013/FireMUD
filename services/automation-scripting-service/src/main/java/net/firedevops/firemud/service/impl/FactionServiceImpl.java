package net.firedevops.firemud.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entity.Faction;
import net.firedevops.firemud.entity.FactionStanding;
import net.firedevops.firemud.repository.FactionRepository;
import net.firedevops.firemud.repository.FactionStandingRepository;
import net.firedevops.firemud.service.FactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FactionServiceImpl implements FactionService {
  private final FactionRepository factionRepository;
  private final FactionStandingRepository standingRepository;

  @Override
  @Transactional
  public int adjustReputation(Long tenantId, Long playerId, Long factionId, int delta) {
    FactionStanding standing =
        standingRepository
            .findByTenantIdAndPlayerIdAndFaction_Id(tenantId, playerId, factionId)
            .orElseGet(
                () -> {
                  Faction faction = factionRepository.findById(factionId).orElseThrow();
                  FactionStanding fs = new FactionStanding();
                  fs.setTenantId(tenantId);
                  fs.setPlayerId(playerId);
                  fs.setFaction(faction);
                  return fs;
                });
    standing.setReputation(standing.getReputation() + delta);
    standingRepository.save(standing);
    return standing.getReputation();
  }

  @Override
  @Transactional(readOnly = true)
  public int getReputation(Long tenantId, Long playerId, Long factionId) {
    return standingRepository
        .findByTenantIdAndPlayerIdAndFaction_Id(tenantId, playerId, factionId)
        .map(FactionStanding::getReputation)
        .orElse(0);
  }
}
