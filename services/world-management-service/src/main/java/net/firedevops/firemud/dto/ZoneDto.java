package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ZoneDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long regionId,
    @NotNull @Size(max = 100) String name) {}
