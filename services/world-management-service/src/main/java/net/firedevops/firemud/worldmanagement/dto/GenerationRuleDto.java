package net.firedevops.firemud.worldmanagement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GenerationRuleDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull @Size(max = 100) String name,
    @Size(max = 64) String scopeType,
    @Size(max = 128) String scopeId,
    @Size(max = 255) String value) {}
