package net.firedevops.firemud.gamedesign.dto;

import java.time.LocalDateTime;

public record VersionAssetPurgeWorkflowStatusDto(
    String tenantId,
    Long versionId,
    String purgeWorkflowId,
    String workflowStatus,
    long startedFromStateEpoch,
    LocalDateTime requestedAt,
    LocalDateTime updatedAt,
    LocalDateTime completedAt,
    String lastErrorCode,
    String lastErrorMessage) {}
