package net.firedevops.firemud.gamedesign.dto;

import java.time.LocalDateTime;
import java.util.List;

public record VersionAssetArtifactStateDto(
    String tenantId,
    Long versionId,
    int exportedVersionNumber,
    String artifactState,
    long stateEpoch,
    String manifestHash,
    String lastWorkflowId,
    String lastErrorCode,
    String lastErrorMessage,
    LocalDateTime updatedAt,
    List<String> exportedManifestAssetKeys) {}
