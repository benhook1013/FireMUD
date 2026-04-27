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
import net.firedevops.firemud.entitymanagement.effect.DefaultEffectEvaluationService;
import net.firedevops.firemud.entitymanagement.effect.EffectEvaluationInput;
import net.firedevops.firemud.entitymanagement.effect.EffectEvaluationService;
import net.firedevops.firemud.entitymanagement.effect.EffectModifier;
import net.firedevops.firemud.entitymanagement.effect.EffectPayloadParser;
import net.firedevops.firemud.entitymanagement.effect.EffectResourceValue;
import net.firedevops.firemud.entitymanagement.effect.EffectSource;
import net.firedevops.firemud.entitymanagement.effect.EvaluatedResourceValue;
import net.firedevops.firemud.entitymanagement.entity.ActorActiveCondition;
import net.firedevops.firemud.entitymanagement.entity.ActorResourceState;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.ActorActiveConditionRepository;
import net.firedevops.firemud.entitymanagement.repository.ActorResourceStateRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.service.ActorStateService;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
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
  private final ItemInstanceRepository itemInstanceRepository;
  private final PlayableStateKeyResolver playableStateKeyResolver;
  private final EffectEvaluationService effectEvaluationService;
  private final EffectPayloadParser effectPayloadParser;
  private final Clock clock;

  public ActorStateServiceImpl(
      ScopedCharacterResolver scopedCharacterResolver,
      ActorResourceStateRepository resourceStateRepository,
      ActorActiveConditionRepository activeConditionRepository) {
    this(
        scopedCharacterResolver,
        resourceStateRepository,
        activeConditionRepository,
        null,
        new PlayableStateKeyResolver(),
        new DefaultEffectEvaluationService(),
        new EffectPayloadParser(new tools.jackson.databind.ObjectMapper()),
        Clock.systemUTC());
  }

  @Autowired
  public ActorStateServiceImpl(
      ScopedCharacterResolver scopedCharacterResolver,
      ActorResourceStateRepository resourceStateRepository,
      ActorActiveConditionRepository activeConditionRepository,
      ItemInstanceRepository itemInstanceRepository,
      PlayableStateKeyResolver playableStateKeyResolver,
      EffectEvaluationService effectEvaluationService,
      EffectPayloadParser effectPayloadParser) {
    this(
        scopedCharacterResolver,
        resourceStateRepository,
        activeConditionRepository,
        itemInstanceRepository,
        playableStateKeyResolver,
        effectEvaluationService,
        effectPayloadParser,
        Clock.systemUTC());
  }

  ActorStateServiceImpl(
      ScopedCharacterResolver scopedCharacterResolver,
      ActorResourceStateRepository resourceStateRepository,
      ActorActiveConditionRepository activeConditionRepository,
      ItemInstanceRepository itemInstanceRepository,
      PlayableStateKeyResolver playableStateKeyResolver,
      EffectEvaluationService effectEvaluationService,
      EffectPayloadParser effectPayloadParser,
      Clock clock) {
    this.scopedCharacterResolver = scopedCharacterResolver;
    this.resourceStateRepository = resourceStateRepository;
    this.activeConditionRepository = activeConditionRepository;
    this.itemInstanceRepository = itemInstanceRepository;
    this.playableStateKeyResolver = playableStateKeyResolver;
    this.effectEvaluationService = effectEvaluationService;
    this.effectPayloadParser = effectPayloadParser;
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
    String playableStateKey = playableStateKeyResolver.resolve(gameInstanceId, playableStateScope);
    Map<String, ActorResourceStateDto> resources = new TreeMap<>();
    addCharacterBaseline(resources, character);
    resourceStateRepository
        .findByTenantIdAndPlayableStateKeyAndCharacterIdOrderByStatKeyAsc(
            tenantId, playableStateKey, characterId)
        .forEach(resource -> resources.put(resource.getStatKey(), toDto(resource)));
    List<ActorConditionStateDto> activeConditions =
        activeConditionRepository
            .findActiveForCharacter(tenantId, playableStateKey, characterId, clock.instant())
            .stream()
            .map(this::toDto)
            .sorted(Comparator.comparing(ActorConditionStateDto::conditionKey))
            .toList();
    List<ActorResourceStateDto> evaluatedResources =
        evaluateEffects(tenantId, characterId, resources, activeConditions);
    return new ActorStateDto(
        tenantId, gameInstanceId, characterId, evaluatedResources, activeConditions);
  }

  private List<ActorResourceStateDto> evaluateEffects(
      long tenantId,
      long characterId,
      Map<String, ActorResourceStateDto> resources,
      List<ActorConditionStateDto> activeConditions) {
    List<EffectModifier> modifiers = new ArrayList<>();
    modifiers.addAll(conditionModifiers(characterId, activeConditions));
    modifiers.addAll(equipmentModifiers(tenantId, characterId));
    if (modifiers.isEmpty()) {
      return new ArrayList<>(resources.values());
    }
    return effectEvaluationService
        .evaluate(
            new EffectEvaluationInput(
                resources.values().stream()
                    .map(resource -> toEffectResource(characterId, resource))
                    .toList(),
                modifiers))
        .resources()
        .stream()
        .map(this::toDto)
        .toList();
  }

  private List<EffectModifier> conditionModifiers(
      long characterId, List<ActorConditionStateDto> activeConditions) {
    return activeConditions.stream()
        .flatMap(
            condition ->
                effectPayloadParser
                    .parseModifiers(
                        condition.effectPayloadJson(), toEffectSource(characterId, condition))
                    .stream())
        .toList();
  }

  private List<EffectModifier> equipmentModifiers(long tenantId, long characterId) {
    if (itemInstanceRepository == null) {
      return List.of();
    }
    return itemInstanceRepository
        .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
            tenantId, characterId)
        .stream()
        .flatMap(
            itemInstance ->
                effectPayloadParser
                    .parseModifiers(
                        itemInstance.getItem().getEffectPayloadJson(),
                        toEffectSource(characterId, itemInstance))
                    .stream())
        .toList();
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

  private EffectResourceValue toEffectResource(long characterId, ActorResourceStateDto resource) {
    return new EffectResourceValue(
        resource.statKey(),
        resource.currentValue(),
        resource.maxValue(),
        resource.baseValue(),
        resource.primitiveKind(),
        new EffectSource(resource.sourceType(), resource.sourceId(), String.valueOf(characterId)));
  }

  private EffectSource toEffectSource(long characterId, ActorConditionStateDto condition) {
    return new EffectSource(
        condition.sourceType(), condition.sourceId(), String.valueOf(characterId));
  }

  private EffectSource toEffectSource(long characterId, ItemInstance itemInstance) {
    String sourceId =
        itemInstance.getVisibleRef() == null || itemInstance.getVisibleRef().isBlank()
            ? String.valueOf(itemInstance.getItem().getId())
            : itemInstance.getVisibleRef();
    return new EffectSource("EQUIPMENT", sourceId, String.valueOf(characterId));
  }

  private ActorResourceStateDto toDto(EvaluatedResourceValue resource) {
    EffectSource source = soleSource(resource.contributingSources());
    return new ActorResourceStateDto(
        resource.statKey(),
        resource.currentValue(),
        resource.maxValue(),
        resource.baseValue(),
        resource.primitiveKind(),
        source.sourceType(),
        blankToNull(source.sourceId()));
  }

  private EffectSource soleSource(List<EffectSource> sources) {
    if (sources.size() == 1) {
      return sources.get(0);
    }
    String sourceId =
        sources.stream()
            .map(EffectSource::sourceId)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    return new EffectSource("EFFECT_EVALUATED", sourceId, "");
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
