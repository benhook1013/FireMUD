package net.firedevops.firemud.automationscripting.service.advanced;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.model.AggressionState;
import net.firedevops.firemud.automationscripting.service.NpcAggressionService;
import org.springframework.stereotype.Service;

/** Simple morale-based AI module. */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Service dependency is not exposed")
public class NpcMoraleServiceImpl implements NpcMoraleService {
  private final NpcAggressionService aggressionService;

  @Override
  @Timed(value = "npc.morale.evaluate")
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
  @Timed(value = "npc.morale.update")
  public void updateState(
      Long tenantId, Long npcId, int healthPercent, int moralePercent, int reputation) {
    AggressionState state = evaluateState(healthPercent, moralePercent, reputation);
    aggressionService.setAggressionState(tenantId, npcId, state);
  }
}
