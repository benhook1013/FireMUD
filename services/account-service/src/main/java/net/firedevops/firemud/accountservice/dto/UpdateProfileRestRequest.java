package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;

/**
 * Public REST input; its Account selector is the canonical UUID, unlike the internal storage DTO.
 */
public record UpdateProfileRestRequest(
    @NotNull Long tenantId,
    @NotNull String accountId,
    @Size(max = 100) String displayName,
    @Size(max = 255) String bio,
    @NotNull ProfilePresenceVisibilityPolicy presenceVisibilityPolicy) {}
