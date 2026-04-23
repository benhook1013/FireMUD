package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SetAdmissionPointerRequest(
    @NotBlank @Size(max = 100) String worldSlug,
    @NotBlank @Size(max = 255) String worldDisplayName,
    @NotBlank @Size(max = 100) String realmSlug,
    @NotBlank @Size(max = 255) String realmDisplayName,
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long gameInstanceId,
    boolean visible,
    boolean publicProductionRealm,
    boolean requiresCharacterSelection,
    @NotBlank @Size(max = 50) String stateScope,
    @NotBlank @Size(max = 50) String characterCreationPolicy,
    @Size(max = 255) String reason,
    @Size(max = 128) String controlPlaneRequestId,
    Long expectedPointerVersion,
    @Size(max = 64) String preparedVersionUpgradeId) {}
