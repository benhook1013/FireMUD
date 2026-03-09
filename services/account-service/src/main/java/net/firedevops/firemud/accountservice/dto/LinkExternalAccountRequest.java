package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LinkExternalAccountRequest(
    @NotNull Long tenantId,
    @NotNull Long accountId,
    @NotBlank String provider,
    @NotBlank String externalId) {}
