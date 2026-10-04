package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ApplyModerationActionRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long accountId,
    @NotNull @Positive Long sessionId,
    @NotBlank @Size(max = 20) String action,
    @Size(max = 255) String reason) {}
