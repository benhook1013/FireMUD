package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request to mint a short-lived gameplay connect token for a first-party client. */
public record ConnectTokenRequest(
    @NotNull @Size(max = 128) String connectScopeId,
    @NotNull Long tenantId,
    @NotNull Long gameInstanceId,
    @Size(max = 128) String realmSlug,
    @Size(max = 128) String requestId) {}
