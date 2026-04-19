package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request to mint a short-lived gameplay connect token for a first-party client. */
public record ConnectTokenRequest(
    @NotBlank @Size(max = 2048) String connectScopeId,
    @NotBlank @Size(max = 128) String requestId) {}
