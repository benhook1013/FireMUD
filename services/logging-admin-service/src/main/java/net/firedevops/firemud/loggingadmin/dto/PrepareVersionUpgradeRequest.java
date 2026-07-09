package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PrepareVersionUpgradeRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long sourceGameInstanceId,
    @NotNull @Positive Long targetVersionId,
    @NotBlank @Size(max = 128) String controlPlaneRequestId) {}
