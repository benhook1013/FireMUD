package net.firedevops.firemud.gamedesign.service;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;

public interface PublishedReleaseBundleService {
  /**
   * Requires evidence already obtained over the authenticated World client before this transaction.
   */
  PublishedReleaseBundleDto createFullVersionBundle(
      VersionDto version,
      String publishWorkflowId,
      ExportedAssetManifest exportedManifest,
      String generationConfigRevision,
      java.util.List<PublishParticipantDigestDto> participantDigests,
      net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence worldEvidence);

  PublishedReleaseBundleDto createFullVersionBundle(
      VersionDto version,
      String publishWorkflowId,
      ExportedAssetManifest exportedManifest,
      String generationConfigRevision,
      java.util.List<PublishParticipantDigestDto> participantDigests);

  PublishedReleaseBundleDto getPublishedReleaseBundle(String tenantId, long versionId);

  Optional<PublishedReleaseBundleDto> findPublishedReleaseBundle(String tenantId, long versionId);
}
