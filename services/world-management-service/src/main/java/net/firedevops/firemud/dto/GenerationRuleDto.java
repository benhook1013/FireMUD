package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GenerationRuleDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull @Size(max = 100) String name,
    @Size(max = 255) String value) {}
