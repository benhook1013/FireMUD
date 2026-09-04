package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.entity.Faction;
import net.firedevops.firemud.automationscripting.entity.FactionStanding;
import net.firedevops.firemud.automationscripting.repository.FactionRepository;
import net.firedevops.firemud.automationscripting.repository.FactionStandingRepository;
import net.firedevops.firemud.automationscripting.service.FactionService;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class FactionServiceImpl implements FactionService {
  private final FactionRepository factionRepository;
  private final FactionStandingRepository standingRepository;

  @Override
  @Transactional
  @Timed(value = "faction.adjustReputation")
  public int adjustReputation(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long factionId,
      int delta) {
    String playableStateKey = playableStateKey(gameInstanceId, playableStateScope);
    FactionStanding standing =
        standingRepository
            .findByTenantIdAndCharacterIdAndPlayableStateKeyAndFaction_Id(
                tenantId, characterId, playableStateKey, factionId)
            .orElseGet(
                () -> {
                  Faction faction =
                      factionRepository
                          .findByTenantIdAndId(tenantId, factionId)
                          .orElseThrow(
                              () ->
                                  new IllegalArgumentException(
                                      "faction not found or not owned by tenant"));
                  FactionStanding fs = new FactionStanding();
                  fs.setTenantId(tenantId);
                  fs.setCharacterId(characterId);
                  fs.setPlayableStateKey(playableStateKey);
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
  public int getReputation(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long factionId) {
    return standingRepository
        .findByTenantIdAndCharacterIdAndPlayableStateKeyAndFaction_Id(
            tenantId, characterId, playableStateKey(gameInstanceId, playableStateScope), factionId)
        .map(FactionStanding::getReputation)
        .orElse(0);
  }

  private String playableStateKey(String gameInstanceId, PlayableStateScope playableStateScope) {
    if (!StringUtils.hasText(gameInstanceId)) {
      throw new IllegalArgumentException("gameInstanceId must not be blank");
    }
    return switch (playableStateScope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "shared-live";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "instance:" + gameInstanceId.trim();
      case PLAYABLE_STATE_SCOPE_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("playableStateScope must be specified");
    };
  }
}
