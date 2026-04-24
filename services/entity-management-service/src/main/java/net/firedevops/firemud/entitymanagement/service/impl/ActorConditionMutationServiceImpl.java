package net.firedevops.firemud.entitymanagement.service.impl;

import java.time.Clock;
import java.time.Instant;
import net.firedevops.firemud.entitymanagement.dto.ActorConditionStateDto;
import net.firedevops.firemud.entitymanagement.entity.ActorActiveCondition;
import net.firedevops.firemud.entitymanagement.repository.ActorActiveConditionRepository;
import net.firedevops.firemud.entitymanagement.service.ActorConditionMutationService;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ActorConditionMutationServiceImpl implements ActorConditionMutationService {
  private final ScopedCharacterResolver scopedCharacterResolver;
  private final ActorActiveConditionRepository activeConditionRepository;
  private final Clock clock;

  @Autowired
  public ActorConditionMutationServiceImpl(
      ScopedCharacterResolver scopedCharacterResolver,
      ActorActiveConditionRepository activeConditionRepository) {
    this(scopedCharacterResolver, activeConditionRepository, Clock.systemUTC());
  }

  ActorConditionMutationServiceImpl(
      ScopedCharacterResolver scopedCharacterResolver,
      ActorActiveConditionRepository activeConditionRepository,
      Clock clock) {
    this.scopedCharacterResolver = scopedCharacterResolver;
    this.activeConditionRepository = activeConditionRepository;
    this.clock = clock;
  }

  @Override
  @Transactional
  public ActorConditionStateDto applyCondition(
      long tenantId,
      long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String conditionKey,
      int stackCount,
      String sourceType,
      String sourceId,
      Instant expiresAt,
      String effectPayloadJson) {
    scopedCharacterResolver.requireScopedCharacter(
        tenantId, characterId, gameInstanceId, playableStateScope);
    String normalizedSourceType = requireText(sourceType, "sourceType");
    String normalizedSourceId = blankToNull(sourceId);
    if (normalizedSourceId != null) {
      var existing =
          activeConditionRepository
              .findFirstByTenantIdAndGameInstanceIdAndCharacterIdAndSourceTypeAndSourceIdOrderByIdAsc(
                  tenantId, gameInstanceId, characterId, normalizedSourceType, normalizedSourceId);
      if (existing.isPresent()) {
        return toDto(existing.orElseThrow());
      }
    }
    ActorActiveCondition condition = new ActorActiveCondition();
    condition.setTenantId(tenantId);
    condition.setGameInstanceId(gameInstanceId);
    condition.setCharacterId(characterId);
    condition.setConditionKey(requireText(conditionKey, "conditionKey"));
    condition.setStackCount(stackCount <= 0 ? 1 : stackCount);
    condition.setSourceType(normalizedSourceType);
    condition.setSourceId(normalizedSourceId);
    Instant now = clock.instant();
    condition.setStartedAt(now);
    condition.setExpiresAt(expiresAt);
    condition.setEffectPayloadJson(blankToNull(effectPayloadJson));
    condition.setCreatedAt(now);
    condition.setUpdatedAt(now);
    return toDto(activeConditionRepository.save(condition));
  }

  @Override
  @Transactional
  public int expireConditions(Instant now) {
    return activeConditionRepository.deleteExpired(now == null ? clock.instant() : now);
  }

  private ActorConditionStateDto toDto(ActorActiveCondition condition) {
    return new ActorConditionStateDto(
        condition.getConditionKey(),
        condition.getStackCount(),
        condition.getSourceType(),
        condition.getSourceId(),
        condition.getStartedAt(),
        condition.getExpiresAt(),
        condition.getEffectPayloadJson());
  }

  private String requireText(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(fieldName + " must be specified");
    }
    return value.trim();
  }

  private String blankToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
