package net.firedevops.firemud.gamesession.dto;

import jakarta.validation.constraints.NotNull;

/** Request payload for starting a new game session. */
public record StartSessionRequest(
    @NotNull Long tenantId,
    @NotNull Long gameTemplateId,
    @NotNull String controlPlaneRequestId,
    @NotNull Long ownerAccountId) {}
