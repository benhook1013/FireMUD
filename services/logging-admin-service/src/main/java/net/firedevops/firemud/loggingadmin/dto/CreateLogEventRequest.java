package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateLogEventRequest(
    @NotNull Long tenantId,
    Long accountId,
    @NotNull @Size(max = 50) String type,
    @NotNull @Size(max = 255) String message) {}
