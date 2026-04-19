package net.firedevops.firemud.gamedesign.dto;

public record DesignControlPlaneDigestDto(
    String tenantId,
    String scopeValue,
    String appliedCommitId,
    String contentDigest,
    int digestSchemaVersion) {}
