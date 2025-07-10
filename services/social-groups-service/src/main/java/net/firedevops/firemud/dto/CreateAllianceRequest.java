package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;

public record CreateAllianceRequest(
    @NotNull Long tenantId, @NotNull Long guildId, @NotNull Long allyGuildId) {}
