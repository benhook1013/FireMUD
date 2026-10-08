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
    String publishedReleaseBundleRef,
    String remapSetId) {
  public ResolvedLaunchDescriptorDto(
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
