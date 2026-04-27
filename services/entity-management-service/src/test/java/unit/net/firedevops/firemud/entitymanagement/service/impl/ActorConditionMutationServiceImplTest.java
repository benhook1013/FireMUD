package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ActorActiveCondition;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.repository.ActorActiveConditionRepository;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ActorConditionMutationServiceImplTest {
  private static final Instant NOW = Instant.parse("2026-04-24T00:00:00Z");

  @Test
  void applyConditionPersistsScopedConditionWithDefaults() {
    ScopedCharacterResolver scopedCharacterResolver = Mockito.mock(ScopedCharacterResolver.class);
    ActorActiveConditionRepository activeConditionRepository =
        Mockito.mock(ActorActiveConditionRepository.class);
    ActorConditionMutationServiceImpl service =
        new ActorConditionMutationServiceImpl(
            scopedCharacterResolver,
            activeConditionRepository,
            new PlayableStateKeyResolver(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    Character character = new Character();
    character.setId(7L);
    when(scopedCharacterResolver.requireScopedCharacter(
            1L, 7L, "99", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(character);
    when(activeConditionRepository.save(Mockito.any(ActorActiveCondition.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.applyCondition(
            1L,
            7L,
            "99",
            PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
            "blocking",
            0,
            "ACTION_STATE",
            "block:1",
            NOW.plusSeconds(10),
            "{\"modifiers\":[]}");

    ArgumentCaptor<ActorActiveCondition> saved =
        ArgumentCaptor.forClass(ActorActiveCondition.class);
    verify(activeConditionRepository).save(saved.capture());
    assertEquals(1, saved.getValue().getStackCount());
    assertEquals("instance:99", saved.getValue().getPlayableStateKey());
    assertEquals(NOW, saved.getValue().getStartedAt());
    assertEquals("blocking", result.conditionKey());
    assertEquals("ACTION_STATE", result.sourceType());
    assertEquals(NOW.plusSeconds(10), result.expiresAt());
  }

  @Test
  void expireConditionsUsesProvidedInstant() {
    ActorActiveConditionRepository activeConditionRepository =
        Mockito.mock(ActorActiveConditionRepository.class);
    ActorConditionMutationServiceImpl service =
        new ActorConditionMutationServiceImpl(
            Mockito.mock(ScopedCharacterResolver.class),
            activeConditionRepository,
            new PlayableStateKeyResolver(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(activeConditionRepository.deleteExpired(NOW.minusSeconds(1))).thenReturn(3);

    int expired = service.expireConditions(NOW.minusSeconds(1));

    assertEquals(3, expired);
  }

  @Test
  void applyConditionReturnsExistingConditionForSameSourceIdentity() {
    ActorActiveConditionRepository activeConditionRepository =
        Mockito.mock(ActorActiveConditionRepository.class);
    ActorActiveCondition existing = new ActorActiveCondition();
    existing.setConditionKey("blocking");
    existing.setStackCount(1);
    existing.setSourceType("ACTION_STATE");
    existing.setSourceId("effect-1");
    existing.setStartedAt(NOW);
    existing.setExpiresAt(NOW.plusSeconds(5));
    when(activeConditionRepository
            .findFirstByTenantIdAndPlayableStateKeyAndCharacterIdAndSourceTypeAndSourceIdOrderByIdAsc(
                1L, "instance:99", 7L, "ACTION_STATE", "effect-1"))
        .thenReturn(Optional.of(existing));
    ActorConditionMutationServiceImpl service =
        new ActorConditionMutationServiceImpl(
            Mockito.mock(ScopedCharacterResolver.class),
            activeConditionRepository,
            new PlayableStateKeyResolver(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    var result =
        service.applyCondition(
            1L,
            7L,
            "99",
            PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
            "blocking",
            1,
            " ACTION_STATE ",
            " effect-1 ",
            NOW.plusSeconds(5),
            null);

    assertEquals("blocking", result.conditionKey());
    verify(activeConditionRepository, Mockito.never()).save(Mockito.any());
  }
}
