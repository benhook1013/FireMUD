package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.mapper.CharacterMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.serializer.SerializationException;

class CharacterServiceImplTest {
  @Test
  void unreadableCachedGraphReloadsTheExactUuidOwnerWithoutSaving() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CacheManager manager = Mockito.mock(CacheManager.class);
    Cache cache = Mockito.mock(Cache.class);
    when(manager.getCache("characterGraph")).thenReturn(cache);
    when(cache.get(1L, CharacterDto.class))
        .thenThrow(new SerializationException("retained graph format"));
    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    var owner = java.util.UUID.fromString("11111111-1111-4111-8111-111111111111");
    character.setAccountUuid(owner);
    character.setPlayableStateKey("shared-live");
    character.setName("Current");
    when(repo.findWithInventoryById(1L)).thenReturn(Optional.of(character));
    var service =
        new CharacterServiceImpl(
            repo,
            Mappers.getMapper(CharacterMapper.class),
            manager,
            new SimpleMeterRegistry(),
            new PlayableStateKeyResolver(),
            new ScopedCharacterResolver(repo, new PlayableStateKeyResolver()));
    service.initMetrics();

    CharacterDto dto = service.getWithInventory(1L);

    assertEquals(owner, dto.accountId());
    verify(cache).evict(1L);
    verify(cache).put(1L, dto);
    verify(repo, never()).save(any());
  }

  @Test
  void unreadableCachedGraphStillRefusesAnUnresolvedStoredOwner() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CacheManager manager = Mockito.mock(CacheManager.class);
    Cache cache = Mockito.mock(Cache.class);
    when(manager.getCache("characterGraph")).thenReturn(cache);
    when(cache.get(1L, CharacterDto.class))
        .thenThrow(new SerializationException("retained graph format"));
    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    character.setAccountId(42L);
    character.setPlayableStateKey("shared-live");
    character.setName("Retained");
    when(repo.findWithInventoryById(1L)).thenReturn(Optional.of(character));
    var service =
        new CharacterServiceImpl(
            repo,
            Mappers.getMapper(CharacterMapper.class),
            manager,
            new SimpleMeterRegistry(),
            new PlayableStateKeyResolver(),
            new ScopedCharacterResolver(repo, new PlayableStateKeyResolver()));
    service.initMetrics();

    assertThrows(IllegalStateException.class, () -> service.getWithInventory(1L));

    verify(cache).evict(1L);
    verify(cache, never()).put(any(), any());
    verify(repo, never()).save(any());
    assertEquals(42L, character.getAccountId());
    assertNull(character.getAccountUuid());
  }

  @Test
  void retainedUnresolvedOwnerCannotBeProjectedOrCached() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    var cacheManager = new ConcurrentMapCacheManager("characterGraph");
    CharacterServiceImpl service =
        new CharacterServiceImpl(
            repo,
            Mappers.getMapper(CharacterMapper.class),
            cacheManager,
            new SimpleMeterRegistry(),
            new PlayableStateKeyResolver(),
            new ScopedCharacterResolver(repo, new PlayableStateKeyResolver()));
    service.initMetrics();
    Character retained = new Character();
    retained.setId(1L);
    retained.setTenantId(1L);
    retained.setAccountId(42L);
    retained.setAccountUuid(null);
    retained.setPlayableStateKey("shared-live");
    retained.setName("Retained");
    when(repo.findWithInventoryById(1L)).thenReturn(Optional.of(retained));
    when(repo.findByTenantIdAndPlayableStateKeyAndNameIgnoreCase(1L, "shared-live", "Retained"))
        .thenReturn(Optional.of(retained));

    assertThrows(IllegalStateException.class, () -> service.getWithInventory(1L));
    assertThrows(
        IllegalStateException.class,
        () ->
            service.findByGameplayScopeAndName(
                1L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "Retained"));

    assertNull(cacheManager.getCache("characterGraph").get(1L));
    assertEquals(42L, retained.getAccountId());
    assertNull(retained.getAccountUuid());
    verify(repo, never()).save(any());
  }

  @Test
  void retainedNilOwnerCannotBeProjectedOrCached() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CharacterMapper mapper = Mockito.mock(CharacterMapper.class);
    var cacheManager = new ConcurrentMapCacheManager("characterGraph");
    CharacterServiceImpl service =
        new CharacterServiceImpl(
            repo,
            mapper,
            cacheManager,
            new SimpleMeterRegistry(),
            new PlayableStateKeyResolver(),
            new ScopedCharacterResolver(repo, new PlayableStateKeyResolver()));
    service.initMetrics();
    Character retained = Mockito.spy(new Character());
    retained.setId(1L);
    retained.setTenantId(1L);
    retained.setAccountId(42L);
    var nilUuid = new java.util.UUID(0L, 0L);
    // Simulate an invalid repository projection without weakening the entity setter guard.
    Mockito.doReturn(nilUuid).when(retained).getAccountUuid();
    retained.setPlayableStateKey("shared-live");
    retained.setName("Retained");
    when(repo.findWithInventoryById(1L)).thenReturn(Optional.of(retained));

    assertThrows(IllegalStateException.class, () -> service.getWithInventory(1L));

    assertNull(cacheManager.getCache("characterGraph").get(1L));
    assertEquals(nilUuid, retained.getAccountUuid());
    verify(mapper, never()).toDto(any());
    verify(repo, never()).save(any());
  }

  @Test
  void gainExperienceLevelsUp() {
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

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    character.setAccountId(1L);
    character.setAccountUuid(java.util.UUID.fromString("11111111-1111-4111-8111-111111111111"));
    character.setPlayableStateKey("shared-live");
    character.setName("Test");
    character.setLevel(1);
    character.setExperience(0);
    character.setStrength(1);
    character.setAgility(1);
    character.setIntelligence(1);
    character.setStamina(1);
    character.setHealth(10);
    character.setMana(5);

    when(repo.findByIdAndTenantIdAndPlayableStateKey(1L, 1L, "shared-live"))
        .thenReturn(Optional.of(character));
    when(repo.save(any(Character.class))).thenAnswer(a -> a.getArgument(0));

    CharacterDto dto =
        service.gainExperience(
            1L, 1L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, 1000);
    assertEquals(2, dto.level());
    assertEquals(0, dto.experience());
    assertEquals(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, dto.playableStateScope());
  }

  @Test
  void getWithInventoryReturnsDto() {
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

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    character.setAccountId(1L);
    character.setAccountUuid(java.util.UUID.fromString("11111111-1111-4111-8111-111111111111"));
    character.setPlayableStateKey("shared-live");
    character.setName("Test");

    when(repo.findWithInventoryById(1L)).thenReturn(Optional.of(character));

    CharacterDto dto = service.getWithInventory(1L);
    assertEquals(1L, dto.id());
    assertEquals(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, dto.playableStateScope());
  }
}
