package net.firedevops.firemud.gamedesign.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record VersionDto(
    Long id,
    @NotNull @Size(max = 36) String tenantId,
    int versionNumber,
    String scriptPatchVersion,
    Long baseVersionId,
    boolean scriptOnly,
    String notes,
    LocalDateTime createdAt) {}
