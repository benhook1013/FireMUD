package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CompletePasswordResetRequest(
    @NotNull Long tenantId,
    @NotNull String token,
    @NotNull @Size(min = 6, max = 100) String newPassword) {}
