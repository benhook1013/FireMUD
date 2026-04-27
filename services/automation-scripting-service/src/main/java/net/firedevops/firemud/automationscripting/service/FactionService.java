package net.firedevops.firemud.automationscripting.service;

import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

public interface FactionService {
  int adjustReputation(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long factionId,
      int delta);

  int getReputation(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long factionId);
}
