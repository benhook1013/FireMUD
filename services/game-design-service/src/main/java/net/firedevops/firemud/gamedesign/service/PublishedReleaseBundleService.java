package net.firedevops.firemud.gamedesign.service;

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
}
