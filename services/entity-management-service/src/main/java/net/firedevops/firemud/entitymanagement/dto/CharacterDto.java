package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CharacterDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long accountId,
    @NotNull @Size(max = 100) String name,
    int level,
    int experience,
    int strength,
    int agility,
    int intelligence,
    int stamina,
    int health,
    int mana) {}
