package net.firedevops.firemud.automationscripting.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ScriptDefinitionDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull @Size(max = 100) String name,
    @NotNull @Size(max = 20) String version,
    @NotNull String definition) {}
