package net.firedevops.firemud.gamedesign.service;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;

public interface PublishedReleaseBundleService {
  PublishedReleaseBundleDto createFullVersionBundle(
      VersionDto version,
      String publishWorkflowId,
      ExportedAssetManifest exportedManifest,
      String generationConfigRevision,
      java.util.List<PublishParticipantDigestDto> participantDigests);

  PublishedReleaseBundleDto getPublishedReleaseBundle(String tenantId, long versionId);

  Optional<PublishedReleaseBundleDto> findPublishedReleaseBundle(String tenantId, long versionId);

  PublishedRealmEntryPolicyEvidence resolvePublishedRealmEntryPolicy(
      UUID canonicalTenantId, long versionId, String worldSlug, String realmSlug);

  PublishedRealmEntryPolicySetEvidence listPublishedRealmEntryPolicies(
      UUID canonicalTenantId, long versionId);
}
