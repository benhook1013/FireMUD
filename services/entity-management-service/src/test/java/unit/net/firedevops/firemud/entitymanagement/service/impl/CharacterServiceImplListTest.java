package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.mapper.CharacterMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class CharacterServiceImplListTest {
  @Test
  void listForTenantAndAccountReturnsDtos() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CharacterMapper mapper = Mappers.getMapper(CharacterMapper.class);
    var cacheManager = new ConcurrentMapCacheManager("characterGraph");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    CharacterServiceImpl service =
        new CharacterServiceImpl(
            repo,
            mapper,
            cacheManager,
            meterRegistry,
            new PlayableStateKeyResolver(),
            new ScopedCharacterResolver(repo, new PlayableStateKeyResolver()));
    service.initMetrics();

    Character c = new Character();
    c.setId(1L);
    c.setTenantId(1L);
    c.setAccountId(1L);
    c.setPlayableStateKey("shared-live");
    c.setName("Hero");

    when(repo.findByTenantIdAndAccountIdAndPlayableStateKey(
            1L, 1L, "shared-live", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(c)));

    var result =
        service.listForGameplayScope(
            1L, 1L, "44", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, Pageable.unpaged());
    assertEquals(1, result.getTotalElements());
    assertEquals("Hero", result.getContent().get(0).name());
    verify(repo)
        .findByTenantIdAndAccountIdAndPlayableStateKey(1L, 1L, "shared-live", Pageable.unpaged());
  }

  @Test
  void listForTenantAndAccountDoesNotCrossTenantBoundary() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CharacterMapper mapper = Mappers.getMapper(CharacterMapper.class);
    var cacheManager = new ConcurrentMapCacheManager("characterGraph");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    CharacterServiceImpl service =
        new CharacterServiceImpl(
            repo,
            mapper,
            cacheManager,
            meterRegistry,
            new PlayableStateKeyResolver(),
            new ScopedCharacterResolver(repo, new PlayableStateKeyResolver()));
    service.initMetrics();

    when(repo.findByTenantIdAndAccountIdAndPlayableStateKey(
            2L, 1L, "instance:91", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of()));

    var result =
        service.listForGameplayScope(
            2L, 1L, "91", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED, Pageable.unpaged());
    assertEquals(0, result.getTotalElements());
    verify(repo)
        .findByTenantIdAndAccountIdAndPlayableStateKey(2L, 1L, "instance:91", Pageable.unpaged());
  }
}
