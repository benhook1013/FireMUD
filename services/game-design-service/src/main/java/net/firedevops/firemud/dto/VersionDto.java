package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record VersionDto(
    Long id,
    @NotNull Long tenantId,
    int versionNumber,
    String scriptPatchVersion,
    Long baseVersionId,
    boolean scriptOnly,
    String notes,
    LocalDateTime createdAt) {}
