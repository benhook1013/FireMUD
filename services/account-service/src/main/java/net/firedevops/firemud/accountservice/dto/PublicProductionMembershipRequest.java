package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Internal request to create or return public-production gameplay membership. */
public record PublicProductionMembershipRequest(
    @NotNull Long accountId,
    @NotNull Long tenantId,
    @NotBlank @Size(max = 64) String worldSlug,
    @NotBlank @Size(max = 64) String realmSlug,
    @NotBlank @Size(max = 128) String requestId) {}
