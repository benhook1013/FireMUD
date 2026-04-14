package net.firedevops.firemud.gamedesign.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PublishedReleaseBundleDto(
    Long id,
    String tenantId,
    Long versionId,
    int versionNumber,
    String attestationSchemaVersion,
    String publishWorkflowId,
    String manifestHash,
    List<String> requiredManifestAssetKeys,
    boolean scriptOnly,
    String scriptPatchVersion,
    LocalDateTime publishedAt) {}
