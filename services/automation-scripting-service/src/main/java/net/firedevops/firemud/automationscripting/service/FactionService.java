package net.firedevops.firemud.automationscripting.service;

public interface FactionService {
  int adjustReputation(Long tenantId, Long characterId, Long factionId, int delta);

  int getReputation(Long tenantId, Long characterId, Long factionId);
}
