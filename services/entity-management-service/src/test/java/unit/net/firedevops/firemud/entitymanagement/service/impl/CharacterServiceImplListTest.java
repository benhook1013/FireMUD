package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.entitymanagement.mapper.CharacterMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

class CharacterServiceImplListTest {
  @Test
  void legacyGameplayRosterRemainsClosedWithoutOwnerResolvedIdentity() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CharacterMapper mapper = Mappers.getMapper(CharacterMapper.class);
    CharacterServiceImpl service =
        new CharacterServiceImpl(
            repo,
            mapper,
            new PlayableStateKeyResolver(),
            new ScopedCharacterResolver(repo, new PlayableStateKeyResolver()));

    var result =
        service.listForGameplayScope(
            1L, 1L, "44", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, Pageable.unpaged());
    assertEquals(0, result.getTotalElements());
    assertEquals(0, result.getContent().size());
    verifyNoInteractions(repo);
  }

  @Test
  void listForTenantAndAccountDoesNotCrossTenantBoundary() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CharacterMapper mapper = Mappers.getMapper(CharacterMapper.class);
    CharacterServiceImpl service =
        new CharacterServiceImpl(
            repo,
            mapper,
            new PlayableStateKeyResolver(),
            new ScopedCharacterResolver(repo, new PlayableStateKeyResolver()));

    var result =
        service.listForGameplayScope(
            2L, 1L, "91", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED, Pageable.unpaged());
    assertEquals(0, result.getTotalElements());
    assertEquals(0, result.getContent().size());
    verifyNoInteractions(repo);
  }
}
