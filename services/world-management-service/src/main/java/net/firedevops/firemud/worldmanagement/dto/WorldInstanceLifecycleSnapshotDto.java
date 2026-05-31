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
    String remapSetId,
    String workflowId,
    String workflowFamily,
    String workflowRunId,
    String workflowStatus) {
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
        null,
        null,
        null,
        null,
        null);
  }

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
      String status,
      String remapSetId) {
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
        remapSetId,
        null,
        null,
        null,
        null);
  }

  public WorldInstanceLifecycleSnapshotDto withWorkflowMetadata(
      String workflowId, String workflowFamily, String workflowRunId, String workflowStatus) {
    return new WorldInstanceLifecycleSnapshotDto(
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
        remapSetId,
        workflowId,
        workflowFamily,
        workflowRunId,
        workflowStatus);
  }
}
