package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ReportDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull Long reporterAccountId,
    Long targetAccountId,
    @NotNull @Size(max = 20) String type,
    @NotNull @Size(max = 255) String description,
    @NotNull Instant createdAt) {}
