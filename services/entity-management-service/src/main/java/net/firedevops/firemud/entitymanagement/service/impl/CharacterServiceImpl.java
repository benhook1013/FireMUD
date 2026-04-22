package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.mapper.CharacterMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are not exposed")
public class CharacterServiceImpl implements CharacterService {

  private final CharacterRepository characterRepository;
  private final CharacterMapper characterMapper;
  private final CacheManager cacheManager;
  private final MeterRegistry meterRegistry;
  private final PlayableStateKeyResolver playableStateKeyResolver;

  private Counter cacheHitCounter;
  private Counter cacheMissCounter;

  private static final int EXP_PER_LEVEL = 1000;

  @PostConstruct
  void initMetrics() {
    cacheHitCounter = meterRegistry.counter("character_cache_hits_total");
    cacheMissCounter = meterRegistry.counter("character_cache_misses_total");
  }

  @Override
  @Transactional
  @Timed(value = "character.create")
  public CharacterDto create(
      Long tenantId,
      Long accountId,
      String name,
      String gameInstanceId,
      PlayableStateScope playableStateScope) {
    Character entity = new Character();
    entity.setTenantId(tenantId);
    entity.setAccountId(accountId);
    entity.setName(name);
    entity.setPlayableStateKey(
        playableStateKeyResolver.resolve(gameInstanceId, playableStateScope));
    entity.setLevel(1);
    entity.setExperience(0);
    entity.setStrength(10);
    entity.setAgility(10);
    entity.setIntelligence(10);
    entity.setStamina(10);
    entity.setHealth(100);
    entity.setMana(50);
    entity = characterRepository.save(entity);
    return toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "character.get")
  public CharacterDto getWithInventory(Long characterId) {
    Cache cache = cacheManager.getCache("characterGraph");
    if (cache != null) {
      CharacterDto cached = cache.get(characterId, CharacterDto.class);
      if (cached != null) {
        cacheHitCounter.increment();
        return cached;
      }
    }
    cacheMissCounter.increment();
    Character character = characterRepository.findWithInventoryById(characterId).orElseThrow();
    CharacterDto dto = toDto(character);
    if (cache != null) {
      cache.put(characterId, dto);
    }
    return dto;
  }

  @Override
  @Transactional
  @Timed(value = "character.gainExperience")
  public CharacterDto gainExperience(Long characterId, int amount) {
    Character character = characterRepository.findById(characterId).orElseThrow();
    character.setExperience(character.getExperience() + amount);
    while (character.getExperience() >= character.getLevel() * EXP_PER_LEVEL) {
      character.setExperience(character.getExperience() - character.getLevel() * EXP_PER_LEVEL);
      character.setLevel(character.getLevel() + 1);
    }
    characterRepository.save(character);
    return toDto(character);
  }

  @Override
  @Transactional
  @Timed(value = "character.update")
  public boolean updateEntity(Long characterId) {
    Character character = characterRepository.findById(characterId).orElseThrow();
    character.setLastLoginAt(java.time.Instant.now());
    characterRepository.save(character);
    return true;
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "character.listForAccount")
  public Page<CharacterDto> listForGameplayScope(
      Long tenantId,
      Long accountId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Pageable pageable) {
    return characterRepository
        .findByTenantIdAndAccountIdAndPlayableStateKey(
            tenantId,
            accountId,
            playableStateKeyResolver.resolve(gameInstanceId, playableStateScope),
            pageable)
        .map(this::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "character.findByTenantAndName")
  public java.util.Optional<CharacterDto> findByGameplayScopeAndName(
      Long tenantId, String gameInstanceId, PlayableStateScope playableStateScope, String name) {
    if (name == null || name.isBlank()) {
      return java.util.Optional.empty();
    }
    return characterRepository
        .findByTenantIdAndPlayableStateKeyAndNameIgnoreCase(
            tenantId,
            playableStateKeyResolver.resolve(gameInstanceId, playableStateScope),
            name.trim())
        .map(this::toDto);
  }

  private CharacterDto toDto(Character character) {
    CharacterDto dto = characterMapper.toDto(character);
    return new CharacterDto(
        dto.id(),
        dto.tenantId(),
        dto.accountId(),
        dto.name(),
        playableStateKeyResolver.resolveScope(character.getPlayableStateKey()),
        dto.level(),
        dto.experience(),
        dto.strength(),
        dto.agility(),
        dto.intelligence(),
        dto.stamina(),
        dto.health(),
        dto.mana());
  }
}
