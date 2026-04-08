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
  public CharacterDto create(CharacterDto dto) {
    Character entity = characterMapper.toEntity(dto);
    entity.setLevel(dto.level() > 0 ? dto.level() : 1);
    entity = characterRepository.save(entity);
    return characterMapper.toDto(entity);
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
    CharacterDto dto = characterMapper.toDto(character);
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
    return characterMapper.toDto(character);
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
  public Page<CharacterDto> listForTenantAndAccount(
      Long tenantId, Long accountId, Pageable pageable) {
    return characterRepository
        .findByTenantIdAndAccountId(tenantId, accountId, pageable)
        .map(characterMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "character.findByTenantAndName")
  public java.util.Optional<CharacterDto> findByTenantAndName(Long tenantId, String name) {
    if (name == null || name.isBlank()) {
      return java.util.Optional.empty();
    }
    return characterRepository
        .findByTenantIdAndNameIgnoreCase(tenantId, name.trim())
        .map(characterMapper::toDto);
  }
}
