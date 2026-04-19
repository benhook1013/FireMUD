package net.firedevops.firemud.gamedesign.dto;

import java.time.LocalDateTime;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;

public record VersionStateDto(
    String tenantId,
    long versionId,
    VersionLifecycleState versionState,
    long versionStateEpoch,
    LocalDateTime updatedAt) {}
