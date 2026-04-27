package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.ActorStateDto;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

public interface ActorStateService {
  ActorStateDto queryActorState(
      long tenantId,
      long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope);
}
