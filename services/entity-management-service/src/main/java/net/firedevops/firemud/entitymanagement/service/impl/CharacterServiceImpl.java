package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.mapper.CharacterMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CharacterServiceImpl implements CharacterService {

  private final CharacterRepository characterRepository;
  private final CharacterMapper characterMapper;
  private final PlayableStateKeyResolver playableStateKeyResolver;
  private final ScopedCharacterResolver scopedCharacterResolver;

  private static final int EXP_PER_LEVEL = 1000;

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
    // Rely exclusively on the repository's fresh persisted OWNER_RESOLVED predicate.
    Character character = characterRepository.findWithInventoryById(characterId).orElseThrow();
    return toDto(character);
  }

  @Override
  @Transactional
  @Timed(value = "character.gainExperience")
  public CharacterDto gainExperience(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      int amount) {
    Character character =
        scopedCharacterResolver.requireScopedCharacter(
            tenantId, characterId, gameInstanceId, playableStateScope);
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
  public boolean updateEntity(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope) {
    Character character =
        scopedCharacterResolver.requireScopedCharacter(
            tenantId, characterId, gameInstanceId, playableStateScope);
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
    return Page.empty(pageable == null ? Pageable.unpaged() : pageable);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "character.findByTenantAndName")
  public java.util.Optional<CharacterDto> findByGameplayScopeAndName(
      Long tenantId, String gameInstanceId, PlayableStateScope playableStateScope, String name) {
    if (name == null || name.isBlank()) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.empty();
  }

  private CharacterDto toDto(Character character) {
    CharacterDto dto = characterMapper.toDto(character);
    return new CharacterDto(
        dto.id(),
        dto.tenantId(),
        dto.accountId(),
        dto.name(),
        character.getActorIdentity() == null
                || character.getActorIdentity().status()
                    != net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus
                        .OWNER_RESOLVED
            ? PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED
            : character.getActorIdentity().playableStateScope(),
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
