package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApplyModerationActionRequest(
    @NotNull Long tenantId,
    @NotNull Long accountId,
    @NotNull Long sessionId,
    @NotBlank @Size(max = 20) String action,
    @Size(max = 255) String reason) {}
