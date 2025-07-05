package net.fire_devops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CharacterDto(
        Long id,
        @NotNull Long tenantId,
        @NotNull Long accountId,
        @NotNull @Size(max = 100) String name,
        int level
) {}
