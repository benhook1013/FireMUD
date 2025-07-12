package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CompletePasswordResetRequest(
    @NotNull Long tenantId,
    @NotNull String token,
    @NotNull @Size(min = 6, max = 100) String newPassword) {}
