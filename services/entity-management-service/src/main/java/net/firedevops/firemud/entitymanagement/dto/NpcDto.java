package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NpcDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull @Size(max = 100) String name,
    String behavior,
    int respawnDelaySeconds,
    Long lastDefeatedAtEpochMs) {}
