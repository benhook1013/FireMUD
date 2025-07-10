package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request payload for starting a new game session. */
public record StartSessionRequest(
    @NotNull Long tenantId,
    @NotNull @Size(max = 100) String versionId,
    @NotNull Long ownerAccountId) {}
