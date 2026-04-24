package net.firedevops.firemud.entitymanagement.service.impl;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.firedevops.firemud.entitymanagement.dto.ActorConditionStateDto;
import net.firedevops.firemud.entitymanagement.dto.ActorResourceStateDto;
import net.firedevops.firemud.entitymanagement.dto.ActorStateDto;
import net.firedevops.firemud.entitymanagement.entity.ActorActiveCondition;
import net.firedevops.firemud.entitymanagement.entity.ActorResourceState;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.repository.ActorActiveConditionRepository;
import net.firedevops.firemud.entitymanagement.repository.ActorResourceStateRepository;
import net.firedevops.firemud.entitymanagement.service.ActorStateService;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActorStateServiceImpl implements ActorStateService {
  private static final String PRIMITIVE_INTEGER = "INTEGER";
  private static final String SOURCE_CHARACTER_BASELINE = "CHARACTER_BASELINE";

  private final ScopedCharacterResolver scopedCharacterResolver;
  private final ActorResourceStateRepository resourceStateRepository;
  private final ActorActiveConditionRepository activeConditionRepository;
  private final Clock clock;

  @Autowired
  public ActorStateServiceImpl(
      ScopedCharacterResolver scopedCharacterResolver,
      ActorResourceStateRepository resourceStateRepository,
      ActorActiveConditionRepository activeConditionRepository) {
    this(
        scopedCharacterResolver,
        resourceStateRepository,
        activeConditionRepository,
        Clock.systemUTC());
  }

  ActorStateServiceImpl(
      ScopedCharacterResolver scopedCharacterResolver,
      ActorResourceStateRepository resourceStateRepository,
      ActorActiveConditionRepository activeConditionRepository,
      Clock clock) {
    this.scopedCharacterResolver = scopedCharacterResolver;
    this.resourceStateRepository = resourceStateRepository;
    this.activeConditionRepository = activeConditionRepository;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public ActorStateDto queryActorState(
      long tenantId,
      long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope) {
    Character character =
        scopedCharacterResolver.requireScopedCharacter(
            tenantId, characterId, gameInstanceId, playableStateScope);
    Map<String, ActorResourceStateDto> resources = new TreeMap<>();
    addCharacterBaseline(resources, character);
    resourceStateRepository
        .findByTenantIdAndGameInstanceIdAndCharacterIdOrderByStatKeyAsc(
            tenantId, gameInstanceId, characterId)
        .forEach(resource -> resources.put(resource.getStatKey(), toDto(resource)));
    List<ActorConditionStateDto> activeConditions =
        activeConditionRepository
            .findActiveForCharacter(tenantId, gameInstanceId, characterId, clock.instant())
            .stream()
            .map(this::toDto)
            .sorted(Comparator.comparing(ActorConditionStateDto::conditionKey))
            .toList();
    return new ActorStateDto(
        tenantId,
        gameInstanceId,
        characterId,
        new ArrayList<>(resources.values()),
        activeConditions);
  }

  private void addCharacterBaseline(
      Map<String, ActorResourceStateDto> resources, Character character) {
    putBaseline(resources, "agility", character.getAgility());
    putBaseline(resources, "experience", character.getExperience());
    putBaseline(resources, "health", character.getHealth());
    putBaseline(resources, "intelligence", character.getIntelligence());
    putBaseline(resources, "level", character.getLevel());
    putBaseline(resources, "mana", character.getMana());
    putBaseline(resources, "stamina", character.getStamina());
    putBaseline(resources, "strength", character.getStrength());
  }

  private void putBaseline(
      Map<String, ActorResourceStateDto> resources, String statKey, long value) {
    resources.put(
        statKey,
        new ActorResourceStateDto(
            statKey, value, null, value, PRIMITIVE_INTEGER, SOURCE_CHARACTER_BASELINE, null));
  }

  private ActorResourceStateDto toDto(ActorResourceState resource) {
    return new ActorResourceStateDto(
        resource.getStatKey(),
        resource.getCurrentValue(),
        resource.getMaxValue(),
        resource.getBaseValue(),
        PRIMITIVE_INTEGER,
        resource.getSourceType(),
        resource.getSourceId());
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
}
