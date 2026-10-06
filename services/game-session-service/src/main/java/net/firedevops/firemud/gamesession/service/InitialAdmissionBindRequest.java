package net.firedevops.firemud.gamesession.service;

import java.util.UUID;

/** Runtime-bound GS intent persisted before the coordinator contacts the World hold owner. */
public record InitialAdmissionBindRequest(
    long tenantId,
    String worldSlug,
    String realmSlug,
    String initialAdmissionRequestId,
    String requestDigest,
    long gameInstanceId,
    long versionId,
    long activeLifecycleEpoch,
    PublishedCatalogBinding publishedCatalog,
    LaunchEvidence launchEvidence) {
  /** Historical V9 fixture request shape. New published admissions must use the full shape. */
  public InitialAdmissionBindRequest(
      long tenantId,
      String worldSlug,
      String realmSlug,
      String initialAdmissionRequestId,
      String requestDigest,
      long gameInstanceId,
      long versionId,
      long activeLifecycleEpoch) {
    this(
        tenantId,
        worldSlug,
        realmSlug,
        initialAdmissionRequestId,
        requestDigest,
        gameInstanceId,
        versionId,
        activeLifecycleEpoch,
        null,
        null);
  }

  public boolean isPublishedCatalogRequest() {
    return publishedCatalog != null && launchEvidence != null;
  }

  /** Exact V14 snapshot identity; version and selected entry are checked against that snapshot. */
  public record PublishedCatalogBinding(
      String targetNamespace, UUID canonicalTenantId, long catalogRevision) {}

  /** Authored launch evidence independently confirmed by World lifecycle readback. */
  public record LaunchEvidence(
      long gameTemplateId,
      String launchDescriptorId,
      long releaseBundleId,
      String publishedReleaseBundleRef,
      long versionStateEpoch) {}
}
