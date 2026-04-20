package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PrepareVersionUpgradeRequest(
    @NotNull Long tenantId,
    @NotNull Long sourceGameInstanceId,
    @NotNull Long targetVersionId,
    @NotBlank @Size(max = 128) String controlPlaneRequestId) {}
