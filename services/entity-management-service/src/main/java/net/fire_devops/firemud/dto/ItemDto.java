package net.fire_devops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ItemDto(
        Long id,
        @NotNull Long tenantId,
        @NotNull @Size(max = 100) String name,
        String description
) {}
