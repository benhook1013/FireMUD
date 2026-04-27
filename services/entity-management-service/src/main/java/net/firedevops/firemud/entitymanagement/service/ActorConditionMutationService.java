package net.firedevops.firemud.entitymanagement.service;

import java.time.Instant;
import net.firedevops.firemud.entitymanagement.dto.ActorConditionStateDto;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

public interface ActorConditionMutationService {
  ActorConditionStateDto applyCondition(
      long tenantId,
      long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String conditionKey,
      int stackCount,
      String sourceType,
      String sourceId,
      Instant expiresAt,
      String effectPayloadJson);

  int expireConditions(Instant now);
}
