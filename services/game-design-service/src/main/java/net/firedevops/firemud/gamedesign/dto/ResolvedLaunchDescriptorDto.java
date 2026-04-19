package net.firedevops.firemud.gamedesign.dto;

public record ResolvedLaunchDescriptorDto(
    String launchDescriptorId,
    String tenantId,
    long gameTemplateId,
    String controlPlaneRequestId,
    long versionId,
    String scriptPatchVersion,
    String runtimeFlagsJson,
    String generationConfigRevision,
    long versionStateEpoch,
    long releaseBundleId,
    String publishedReleaseBundleRef) {}
