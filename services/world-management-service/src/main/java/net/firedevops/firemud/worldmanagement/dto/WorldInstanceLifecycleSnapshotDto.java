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
    String status,
    String remapSetId) {
  public WorldInstanceLifecycleSnapshotDto(
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
      String status) {
    this(
        tenantId,
        gameInstanceId,
        gameTemplateId,
        controlPlaneRequestId,
        launchDescriptorId,
        versionId,
        releaseBundleId,
        generationConfigRevision,
        publishedReleaseBundleRef,
        versionStateEpoch,
        lifecycleEpoch,
        status,
        null);
  }
}
