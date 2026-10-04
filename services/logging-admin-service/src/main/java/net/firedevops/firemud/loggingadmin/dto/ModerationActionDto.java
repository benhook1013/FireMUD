package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ModerationActionDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long accountId,
    @NotBlank @Size(max = 20) String action,
    @Size(max = 255) String reason,
    Instant createdAt,
    Instant expiresAt) {}
