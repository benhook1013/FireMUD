package net.firedevops.firemud.service.advanced;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.model.AggressionState;
import net.firedevops.firemud.service.NpcAggressionService;
import org.springframework.stereotype.Service;

/** Simple morale-based AI module. */
@Service
@RequiredArgsConstructor
public class NpcMoraleServiceImpl implements NpcMoraleService {
  private final NpcAggressionService aggressionService;

  @Override
  public AggressionState evaluateState(int healthPercent, int moralePercent, int reputation) {
    if (healthPercent <= 10 || moralePercent <= 10 || reputation <= -50) {
      return AggressionState.SURRENDERED;
    }
    if (healthPercent <= 25 || moralePercent <= 25 || reputation <= -20) {
      return AggressionState.FLEEING;
    }
    return AggressionState.HOSTILE;
  }

  @Override
  public void updateState(
      Long tenantId, Long npcId, int healthPercent, int moralePercent, int reputation) {
    AggressionState state = evaluateState(healthPercent, moralePercent, reputation);
    aggressionService.setAggressionState(tenantId, npcId, state);
  }
}
