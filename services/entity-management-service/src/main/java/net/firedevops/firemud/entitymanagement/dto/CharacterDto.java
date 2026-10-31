package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

public record CharacterDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull java.util.UUID accountId,
    @NotNull @Size(max = 100) String name,
    @NotNull PlayableStateScope playableStateScope,
    int level,
    int experience,
    int strength,
    int agility,
    int intelligence,
    int stamina,
    int health,
    int mana) {
  public CharacterDto {
    if (accountId == null || accountId.equals(new java.util.UUID(0L, 0L))) {
      throw new IllegalArgumentException(
          "Public character ownership requires a non-nil Account UUID");
    }
  }
}
