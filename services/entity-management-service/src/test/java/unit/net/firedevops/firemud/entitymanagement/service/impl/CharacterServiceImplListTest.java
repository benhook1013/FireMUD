package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.mapper.CharacterMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
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
        new CharacterServiceImpl(repo, mapper, cacheManager, meterRegistry);
    service.initMetrics();

    Character c = new Character();
    c.setId(1L);
    c.setTenantId(1L);
    c.setAccountId(1L);
    c.setName("Hero");

    when(repo.findByTenantIdAndAccountId(1L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(c)));

    var result = service.listForTenantAndAccount(1L, 1L, Pageable.unpaged());
    assertEquals(1, result.getTotalElements());
    assertEquals("Hero", result.getContent().get(0).name());
    verify(repo).findByTenantIdAndAccountId(1L, 1L, Pageable.unpaged());
  }

  @Test
  void listForTenantAndAccountDoesNotCrossTenantBoundary() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CharacterMapper mapper = Mappers.getMapper(CharacterMapper.class);
    var cacheManager = new ConcurrentMapCacheManager("characterGraph");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    CharacterServiceImpl service =
        new CharacterServiceImpl(repo, mapper, cacheManager, meterRegistry);
    service.initMetrics();

    when(repo.findByTenantIdAndAccountId(2L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of()));

    var result = service.listForTenantAndAccount(2L, 1L, Pageable.unpaged());
    assertEquals(0, result.getTotalElements());
    verify(repo).findByTenantIdAndAccountId(2L, 1L, Pageable.unpaged());
  }
}
