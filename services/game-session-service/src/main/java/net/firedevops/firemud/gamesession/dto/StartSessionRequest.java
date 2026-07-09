package net.firedevops.firemud.gamesession.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Request payload for starting a new game session. */
public record StartSessionRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long gameTemplateId,
    @NotNull String controlPlaneRequestId,
    @NotNull @Positive Long ownerAccountId) {}
