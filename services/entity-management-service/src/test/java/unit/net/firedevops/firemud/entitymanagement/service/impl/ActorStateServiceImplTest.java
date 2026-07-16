package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.firedevops.firemud.entitymanagement.effect.DefaultEffectEvaluationService;
import net.firedevops.firemud.entitymanagement.effect.EffectPayloadParser;
import net.firedevops.firemud.entitymanagement.entity.ActorActiveCondition;
import net.firedevops.firemud.entitymanagement.entity.ActorResourceState;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.ActorActiveConditionRepository;
import net.firedevops.firemud.entitymanagement.repository.ActorResourceStateRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ActorStateServiceImplTest {
  private static final Instant NOW = Instant.parse("2026-04-24T00:00:00Z");

  @Test
  void queryActorStateReturnsBaselinesWithPersistedResourceOverridesAndActiveConditions() {
    ScopedCharacterResolver scopedCharacterResolver = Mockito.mock(ScopedCharacterResolver.class);
    ActorResourceStateRepository resourceStateRepository =
        Mockito.mock(ActorResourceStateRepository.class);
    ActorActiveConditionRepository activeConditionRepository =
        Mockito.mock(ActorActiveConditionRepository.class);
    ItemInstanceRepository itemInstanceRepository = Mockito.mock(ItemInstanceRepository.class);
    ActorStateServiceImpl service =
        new ActorStateServiceImpl(
            scopedCharacterResolver,
            resourceStateRepository,
            activeConditionRepository,
            itemInstanceRepository,
            new PlayableStateKeyResolver(),
            new DefaultEffectEvaluationService(),
            new EffectPayloadParser(new tools.jackson.databind.ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    Character character = new Character();
    character.setId(7L);
    character.setTenantId(1L);
    character.setAccountId(41L);
    character.setPlayableStateKey("instance:99");
    character.setName("Test");
    character.setAgility(5);
    character.setExperience(123);
    character.setHealth(80);
    character.setIntelligence(6);
    character.setLevel(3);
    character.setMana(40);
    character.setStamina(9);
    character.setStrength(8);
    when(scopedCharacterResolver.requireScopedCharacter(
            1L, 7L, "99", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(character);

    ActorResourceState health = new ActorResourceState();
    health.setTenantId(1L);
    health.setPlayableStateKey("instance:99");
    health.setCharacterId(7L);
    health.setStatKey("health");
    health.setCurrentValue(65L);
    health.setMaxValue(90L);
    health.setBaseValue(80L);
    health.setSourceType("EFFECT");
    health.setSourceId("poison:1");
    when(resourceStateRepository.findByTenantIdAndPlayableStateKeyAndCharacterIdOrderByStatKeyAsc(
            1L, "instance:99", 7L))
        .thenReturn(List.of(health));

    ActorActiveCondition condition = new ActorActiveCondition();
    condition.setTenantId(1L);
    condition.setPlayableStateKey("instance:99");
    condition.setCharacterId(7L);
    condition.setConditionKey("poisoned");
    condition.setStackCount(2);
    condition.setSourceType("EFFECT");
    condition.setSourceId("poison:1");
    condition.setStartedAt(NOW.minusSeconds(5));
    condition.setExpiresAt(NOW.plusSeconds(30));
    condition.setEffectPayloadJson("{\"damage_per_tick\":3}");
    when(activeConditionRepository.findActiveForCharacter(1L, "instance:99", 7L, NOW))
        .thenReturn(List.of(condition));
    when(itemInstanceRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
                1L, 7L))
        .thenReturn(List.of());

    var result =
        service.queryActorState(1L, 7L, "99", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED);

    assertEquals(1L, result.tenantId());
    assertEquals("99", result.gameInstanceId());
    assertEquals(7L, result.characterId());
    assertEquals(8, result.resources().size());
    assertEquals("agility", result.resources().get(0).statKey());
    var effectiveHealth =
        result.resources().stream()
            .filter(resource -> resource.statKey().equals("health"))
            .findFirst()
            .orElseThrow();
    assertEquals(65L, effectiveHealth.currentValue());
    assertEquals(90L, effectiveHealth.maxValue());
    assertEquals("EFFECT", effectiveHealth.sourceType());
    assertEquals(1, result.activeConditions().size());
    assertEquals("poisoned", result.activeConditions().get(0).conditionKey());
    assertEquals(2, result.activeConditions().get(0).stackCount());
  }

  @Test
  void queryActorStateAppliesActiveConditionEffectPayloadsThroughSharedEvaluator() {
    ScopedCharacterResolver scopedCharacterResolver = Mockito.mock(ScopedCharacterResolver.class);
    ActorResourceStateRepository resourceStateRepository =
        Mockito.mock(ActorResourceStateRepository.class);
    ActorActiveConditionRepository activeConditionRepository =
        Mockito.mock(ActorActiveConditionRepository.class);
    ItemInstanceRepository itemInstanceRepository = Mockito.mock(ItemInstanceRepository.class);
    ActorStateServiceImpl service =
        new ActorStateServiceImpl(
            scopedCharacterResolver,
            resourceStateRepository,
            activeConditionRepository,
            itemInstanceRepository,
            new PlayableStateKeyResolver(),
            new DefaultEffectEvaluationService(),
            new EffectPayloadParser(new tools.jackson.databind.ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    Character character = new Character();
    character.setId(7L);
    character.setTenantId(1L);
    character.setAccountId(41L);
    character.setPlayableStateKey("instance:99");
    character.setName("Test");
    character.setAgility(5);
    character.setExperience(123);
    character.setHealth(80);
    character.setIntelligence(6);
    character.setLevel(3);
    character.setMana(40);
    character.setStamina(9);
    character.setStrength(8);
    when(scopedCharacterResolver.requireScopedCharacter(
            1L, 7L, "99", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(character);
    when(resourceStateRepository.findByTenantIdAndPlayableStateKeyAndCharacterIdOrderByStatKeyAsc(
            1L, "instance:99", 7L))
        .thenReturn(List.of());

    ActorActiveCondition condition = new ActorActiveCondition();
    condition.setTenantId(1L);
    condition.setPlayableStateKey("instance:99");
    condition.setCharacterId(7L);
    condition.setConditionKey("blessed");
    condition.setStackCount(1);
    condition.setSourceType("CONDITION");
    condition.setSourceId("bless:1");
    condition.setStartedAt(NOW.minusSeconds(5));
    condition.setEffectPayloadJson(
        """
        {"modifiers":[
          {"operation":"ADD","target_key":"strength","value":2},
          {"operation":"MULTIPLY","target_key":"strength","value":1.5},
          {"operation":"CLAMP_MAX","target_key":"strength","value":20}
        ]}
        """);
    when(activeConditionRepository.findActiveForCharacter(1L, "instance:99", 7L, NOW))
        .thenReturn(List.of(condition));
    when(itemInstanceRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
                1L, 7L))
        .thenReturn(List.of());

    var result =
        service.queryActorState(1L, 7L, "99", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED);

    var strength =
        result.resources().stream()
            .filter(resource -> resource.statKey().equals("strength"))
            .findFirst()
            .orElseThrow();
    assertEquals(15L, strength.currentValue());
    assertEquals("EFFECT_EVALUATED", strength.sourceType());
    assertEquals("bless:1", strength.sourceId());
  }

  @Test
  void queryActorStateAppliesEquippedItemEffectPayloadsThroughSharedEvaluator() {
    ScopedCharacterResolver scopedCharacterResolver = Mockito.mock(ScopedCharacterResolver.class);
    ActorResourceStateRepository resourceStateRepository =
        Mockito.mock(ActorResourceStateRepository.class);
    ActorActiveConditionRepository activeConditionRepository =
        Mockito.mock(ActorActiveConditionRepository.class);
    ItemInstanceRepository itemInstanceRepository = Mockito.mock(ItemInstanceRepository.class);
    ActorStateServiceImpl service =
        new ActorStateServiceImpl(
            scopedCharacterResolver,
            resourceStateRepository,
            activeConditionRepository,
            itemInstanceRepository,
            new PlayableStateKeyResolver(),
            new DefaultEffectEvaluationService(),
            new EffectPayloadParser(new tools.jackson.databind.ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    Character character = new Character();
    character.setId(7L);
    character.setTenantId(1L);
    character.setAccountId(41L);
    character.setPlayableStateKey("instance:99");
    character.setName("Test");
    character.setAgility(5);
    character.setExperience(123);
    character.setHealth(80);
    character.setIntelligence(6);
    character.setLevel(3);
    character.setMana(40);
    character.setStamina(9);
    character.setStrength(8);
    when(scopedCharacterResolver.requireScopedCharacter(
            1L, 7L, "99", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(character);
    when(resourceStateRepository.findByTenantIdAndPlayableStateKeyAndCharacterIdOrderByStatKeyAsc(
            1L, "instance:99", 7L))
        .thenReturn(List.of());
    when(activeConditionRepository.findActiveForCharacter(1L, "instance:99", 7L, NOW))
        .thenReturn(List.of());

    Item armour = new Item();
    armour.setId(55L);
    armour.setTenantId(1L);
    armour.setName("Armour");
    armour.setEffectPayloadJson(
        """
        {"modifiers":[{"operation":"ADD","target_key":"armour_value","value":12}]}
        """);
    ItemInstance equipped = new ItemInstance();
    equipped.setId(101L);
    equipped.setTenantId(1L);
    equipped.setCharacter(character);
    equipped.setEquipmentSlot("CHEST");
    equipped.setVisibleRef("armour1");
    equipped.setItem(armour);
    when(itemInstanceRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
                1L, 7L))
        .thenReturn(List.of(equipped));

    var result =
        service.queryActorState(1L, 7L, "99", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED);

    var armourValue =
        result.resources().stream()
            .filter(resource -> resource.statKey().equals("armour_value"))
            .findFirst()
            .orElseThrow();
    assertEquals(12L, armourValue.currentValue());
    assertEquals("EQUIPMENT", armourValue.sourceType());
    assertEquals("armour1", armourValue.sourceId());
  }

  @Test
  void queryActorStateCombinesPersistedResourcesConditionsAndEquipmentInOneEvaluation() {
    ScopedCharacterResolver scopedCharacterResolver = Mockito.mock(ScopedCharacterResolver.class);
    ActorResourceStateRepository resourceStateRepository =
        Mockito.mock(ActorResourceStateRepository.class);
    ActorActiveConditionRepository activeConditionRepository =
        Mockito.mock(ActorActiveConditionRepository.class);
    ItemInstanceRepository itemInstanceRepository = Mockito.mock(ItemInstanceRepository.class);
    ActorStateServiceImpl service =
        new ActorStateServiceImpl(
            scopedCharacterResolver,
            resourceStateRepository,
            activeConditionRepository,
            itemInstanceRepository,
            new PlayableStateKeyResolver(),
            new DefaultEffectEvaluationService(),
            new EffectPayloadParser(new tools.jackson.databind.ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    Character character = character();
    when(scopedCharacterResolver.requireScopedCharacter(
            1L, 7L, "99", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(character);

    ActorResourceState health = new ActorResourceState();
    health.setTenantId(1L);
    health.setPlayableStateKey("instance:99");
    health.setCharacterId(7L);
    health.setStatKey("health");
    health.setCurrentValue(60L);
    health.setMaxValue(90L);
    health.setBaseValue(80L);
    health.setSourceType("EFFECT");
    health.setSourceId("wound:1");
    when(resourceStateRepository.findByTenantIdAndPlayableStateKeyAndCharacterIdOrderByStatKeyAsc(
            1L, "instance:99", 7L))
        .thenReturn(List.of(health));

    ActorActiveCondition blessing = new ActorActiveCondition();
    blessing.setTenantId(1L);
    blessing.setPlayableStateKey("instance:99");
    blessing.setCharacterId(7L);
    blessing.setConditionKey("blessed");
    blessing.setStackCount(1);
    blessing.setSourceType("CONDITION");
    blessing.setSourceId("bless:1");
    blessing.setStartedAt(NOW.minusSeconds(5));
    blessing.setEffectPayloadJson(
        "{\"modifiers\":[{\"operation\":\"MULTIPLY\",\"target_key\":\"health\",\"value\":1.5}]}");
    when(activeConditionRepository.findActiveForCharacter(1L, "instance:99", 7L, NOW))
        .thenReturn(List.of(blessing));

    Item armour = new Item();
    armour.setId(55L);
    armour.setTenantId(1L);
    armour.setName("Armour");
    armour.setEffectPayloadJson(
        "{\"modifiers\":[{\"operation\":\"ADD\",\"target_key\":\"health\",\"value\":10}]}");
    ItemInstance equipped = new ItemInstance();
    equipped.setId(101L);
    equipped.setTenantId(1L);
    equipped.setCharacter(character);
    equipped.setEquipmentSlot("CHEST");
    equipped.setVisibleRef("armour1");
    equipped.setItem(armour);
    when(itemInstanceRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
                1L, 7L))
        .thenReturn(List.of(equipped));

    var result =
        service.queryActorState(1L, 7L, "99", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED);

    var effectiveHealth =
        result.resources().stream()
            .filter(resource -> resource.statKey().equals("health"))
            .findFirst()
            .orElseThrow();
    assertEquals(105L, effectiveHealth.currentValue());
    assertEquals(90L, effectiveHealth.maxValue());
    assertEquals(80L, effectiveHealth.baseValue());
    assertEquals(
        List.of("blessed"),
        result.activeConditions().stream().map(condition -> condition.conditionKey()).toList());
  }

  private Character character() {
    Character character = new Character();
    character.setId(7L);
    character.setTenantId(1L);
    character.setAccountId(41L);
    character.setPlayableStateKey("instance:99");
    character.setName("Test");
    character.setAgility(5);
    character.setExperience(123);
    character.setHealth(80);
    character.setIntelligence(6);
    character.setLevel(3);
    character.setMana(40);
    character.setStamina(9);
    character.setStrength(8);
    return character;
  }
}
