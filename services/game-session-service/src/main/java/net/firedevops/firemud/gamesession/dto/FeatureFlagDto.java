package net.firedevops.firemud.gamesession.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FeatureFlagDto(
    Long id, @NotNull Long tenantId, @NotBlank @Size(max = 100) String name, boolean enabled) {}
