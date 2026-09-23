package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only the Account-issued target scope and caller-stable attempt ID cross the public API. */
public record JoinPublicProductionRequest(
    @NotBlank @Size(max = 2048) String connectScopeId,
    @NotBlank @Size(max = 128) String requestId) {}
