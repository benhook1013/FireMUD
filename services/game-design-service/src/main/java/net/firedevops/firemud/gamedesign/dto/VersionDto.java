package net.firedevops.firemud.gamedesign.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;

public record VersionDto(
    Long id,
    @NotNull @Size(max = 36) String tenantId,
    int versionNumber,
    VersionLifecycleState versionState,
    long versionStateEpoch,
    String scriptPatchVersion,
    Long baseVersionId,
    boolean scriptOnly,
    String notes,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
