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
    String publishedReleaseBundleRef,
    String remapSetId) {
  public ResolvedLaunchDescriptor(
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
      String publishedReleaseBundleRef) {
    this(
        launchDescriptorId,
        tenantId,
        gameTemplateId,
        controlPlaneRequestId,
        versionId,
        scriptPatchVersion,
        runtimeFlagsJson,
        generationConfigRevision,
        versionStateEpoch,
        releaseBundleId,
        publishedReleaseBundleRef,
        null);
  }
}
