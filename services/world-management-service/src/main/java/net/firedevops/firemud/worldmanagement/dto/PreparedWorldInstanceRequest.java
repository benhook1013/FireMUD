package net.firedevops.firemud.worldmanagement.dto;

public record PreparedWorldInstanceRequest(
    long tenantId,
    long gameInstanceId,
    long gameTemplateId,
    String controlPlaneRequestId,
    String launchDescriptorId,
    long versionId,
    String scriptPatchVersion,
    String runtimeFlagsJson,
    String generationConfigRevision,
    long releaseBundleId,
    String publishedReleaseBundleRef,
    long versionStateEpoch) {}
