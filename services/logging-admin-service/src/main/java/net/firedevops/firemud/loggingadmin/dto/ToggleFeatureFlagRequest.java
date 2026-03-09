package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ToggleFeatureFlagRequest(
    @NotNull Long tenantId, @NotBlank @Size(max = 100) String name, boolean enabled) {}
