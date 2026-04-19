package net.firedevops.firemud.gamesession.dto;

public record ResolvedLaunchDescriptor(
    String launchDescriptorId,
    long tenantId,
    long gameTemplateId,
    String controlPlaneRequestId,
    long versionId,
    String scriptPatchVersion,
    String runtimeFlagsJson,
    String generationConfigRevision,
    long versionStateEpoch,
    long releaseBundleId,
    String publishedReleaseBundleRef) {}
