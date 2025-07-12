package net.firedevops.firemud.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
    @NotNull Long tenantId, @NotNull @Email @Size(max = 100) String email) {}
