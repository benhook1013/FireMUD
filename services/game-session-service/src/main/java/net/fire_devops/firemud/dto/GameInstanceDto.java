package net.fire_devops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GameInstanceDto(
        Long id,
        @NotNull Long tenantId,
        @NotNull @Size(max = 100) String versionId,
        @NotNull Long ownerAccountId,
        @NotNull @Size(max = 20) String status
) {}
