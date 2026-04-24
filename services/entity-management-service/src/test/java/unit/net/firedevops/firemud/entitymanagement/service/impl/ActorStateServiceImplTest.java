package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.firedevops.firemud.entitymanagement.entity.ActorActiveCondition;
import net.firedevops.firemud.entitymanagement.entity.ActorResourceState;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.repository.ActorActiveConditionRepository;
import net.firedevops.firemud.entitymanagement.repository.ActorResourceStateRepository;
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
    ActorStateServiceImpl service =
        new ActorStateServiceImpl(
            scopedCharacterResolver,
            resourceStateRepository,
            activeConditionRepository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    Character character = new Character();
    character.setId(7L);
    character.setTenantId(1L);
    character.setAccountId(41L);
    character.setPlayableStateKey("instance-99");
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
    health.setGameInstanceId("99");
    health.setCharacterId(7L);
    health.setStatKey("health");
    health.setCurrentValue(65L);
    health.setMaxValue(90L);
    health.setBaseValue(80L);
    health.setSourceType("EFFECT");
    health.setSourceId("poison:1");
    when(resourceStateRepository.findByTenantIdAndGameInstanceIdAndCharacterIdOrderByStatKeyAsc(
            1L, "99", 7L))
        .thenReturn(List.of(health));

    ActorActiveCondition condition = new ActorActiveCondition();
    condition.setTenantId(1L);
    condition.setGameInstanceId("99");
    condition.setCharacterId(7L);
    condition.setConditionKey("poisoned");
    condition.setStackCount(2);
    condition.setSourceType("EFFECT");
    condition.setSourceId("poison:1");
    condition.setStartedAt(NOW.minusSeconds(5));
    condition.setExpiresAt(NOW.plusSeconds(30));
    condition.setEffectPayloadJson("{\"damage_per_tick\":3}");
    when(activeConditionRepository.findActiveForCharacter(1L, "99", 7L, NOW))
        .thenReturn(List.of(condition));

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
}
