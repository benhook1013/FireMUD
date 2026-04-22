package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TickRemediationRequest(
    @NotNull Long tenantId,
    @Size(max = 64) String gameInstanceId,
    @Size(max = 64) String regionId,
    @Size(max = 255) String reason) {}
