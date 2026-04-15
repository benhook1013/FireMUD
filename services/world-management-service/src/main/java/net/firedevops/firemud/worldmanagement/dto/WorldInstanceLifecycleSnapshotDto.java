package net.firedevops.firemud.worldmanagement.dto;

public record WorldInstanceLifecycleSnapshotDto(
    long tenantId,
    long gameInstanceId,
    long gameTemplateId,
    String controlPlaneRequestId,
    String launchDescriptorId,
    long versionId,
    long releaseBundleId,
    String generationConfigRevision,
    String publishedReleaseBundleRef,
    long versionStateEpoch,
    long lifecycleEpoch,
    String status) {}
