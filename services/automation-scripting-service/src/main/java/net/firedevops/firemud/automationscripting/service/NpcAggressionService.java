package net.firedevops.firemud.automationscripting.service;

import net.firedevops.firemud.automationscripting.model.AggressionState;

public interface NpcAggressionService {
  /**
   * Set the aggression state for the given NPC.
   *
   * @param tenantId game tenant identifier
   * @param npcId NPC identifier
   * @param state new aggression state
   */
  void setAggressionState(Long tenantId, Long npcId, AggressionState state);

  /**
   * Retrieve the current aggression state for an NPC. If no value is stored, {@link
   * AggressionState#NEUTRAL} is returned.
   */
  AggressionState getAggressionState(Long tenantId, Long npcId);
}
