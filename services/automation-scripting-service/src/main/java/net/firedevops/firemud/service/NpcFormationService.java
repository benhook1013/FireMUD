package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.model.FormationType;

/** Manages NPC formations for squad AI behaviour. */
public interface NpcFormationService {
  Long createFormation(Long tenantId, String name, Long leaderNpcId, FormationType type);

  void addMember(Long tenantId, Long formationId, Long npcId);

  List<Long> getMembers(Long tenantId, Long formationId);
}
