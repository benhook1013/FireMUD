package net.firedevops.firemud.gamedesign.dto;

public record VersionAssetDeletionEligibilityDto(
    String tenantId,
    Long versionId,
    boolean deletable,
    String currentArtifactState,
    long currentStateEpoch,
    String failureCode,
    String failureMessage) {}
