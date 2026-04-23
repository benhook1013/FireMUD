package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.mapper.CharacterMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class CharacterServiceImplTest {

  @Test
  void gainExperienceLevelsUp() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CharacterMapper mapper = Mappers.getMapper(CharacterMapper.class);
    var cacheManager = new ConcurrentMapCacheManager("characterGraph");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    CharacterServiceImpl service =
        new CharacterServiceImpl(
            repo, mapper, cacheManager, meterRegistry, new PlayableStateKeyResolver());
    service.initMetrics();

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    character.setAccountId(1L);
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
            repo, mapper, cacheManager, meterRegistry, new PlayableStateKeyResolver());
    service.initMetrics();

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    character.setAccountId(1L);
    character.setPlayableStateKey("shared-live");
    character.setName("Test");

    when(repo.findWithInventoryById(1L)).thenReturn(Optional.of(character));

    CharacterDto dto = service.getWithInventory(1L);
    assertEquals(1L, dto.id());
    assertEquals(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, dto.playableStateScope());
  }
}
