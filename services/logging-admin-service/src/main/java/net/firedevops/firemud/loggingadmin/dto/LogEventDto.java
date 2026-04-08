package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record LogEventDto(
    Long id,
    @NotNull Long tenantId,
    Long accountId,
    @NotNull @Size(max = 50) String type,
    @NotNull @Size(max = 255) String message,
    @NotNull Instant timestamp) {}
