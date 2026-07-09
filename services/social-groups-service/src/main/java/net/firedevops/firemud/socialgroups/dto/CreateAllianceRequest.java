package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateAllianceRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long guildId,
    @NotNull @Positive Long allyGuildId) {}
