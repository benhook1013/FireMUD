package net.firedevops.firemud.automationscripting.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import net.firedevops.firemud.automationscripting.model.FormationType;

public record CreateFormationRequest(
    @NotNull Long tenantId,
    @NotNull @Size(max = 100) String name,
    @NotNull Long leaderNpcId,
    @NotNull FormationType formationType) {}
