package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;

public record UpdateProfileRequest(
    @NotNull Long tenantId,
    @NotNull Long accountId,
    @Size(max = 100) String displayName,
    @Size(max = 255) String bio,
    @NotNull ProfilePresenceVisibilityPolicy presenceVisibilityPolicy) {}
