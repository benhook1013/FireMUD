package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VerifyEmailRequest(@NotNull Long tenantId, @NotBlank String token) {}
