package net.firedevops.firemud.automationscripting.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import net.firedevops.firemud.automationscripting.model.FormationType;

public record CreateFormationRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Size(max = 100) String name,
    @NotNull @Positive Long leaderNpcId,
    @NotNull FormationType formationType) {}
