package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ExecutePreparedVersionCutoverRequest(
    @NotBlank @Size(max = 100) String worldSlug,
    @NotBlank @Size(max = 100) String realmSlug,
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long targetGameInstanceId,
    @NotBlank @Size(max = 64) String preparedVersionUpgradeId,
    @Size(max = 255) String reason,
    @Size(max = 128) String controlPlaneRequestId,
    Long expectedPointerVersion) {}
