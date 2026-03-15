package net.firedevops.firemud.automationscripting.service.advanced;

import net.firedevops.firemud.automationscripting.model.AggressionState;

/**
 * Advanced AI module adjusting aggression based on health, morale and reputation. See design docs
 * for fleeing and surrender logic.
 */
public interface NpcMoraleService {
  /** Determine the new aggression state. */
  AggressionState evaluateState(int healthPercent, int moralePercent, int reputation);

  /** Persist the aggression state for an NPC. */
  void updateState(Long tenantId, Long npcId, int healthPercent, int moralePercent, int reputation);
}
