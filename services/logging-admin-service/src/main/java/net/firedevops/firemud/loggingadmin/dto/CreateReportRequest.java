package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateReportRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long reporterAccountId,
    @Positive Long targetAccountId,
    @NotNull @Size(max = 20) String type,
    @NotNull @Size(max = 255) String description) {}
