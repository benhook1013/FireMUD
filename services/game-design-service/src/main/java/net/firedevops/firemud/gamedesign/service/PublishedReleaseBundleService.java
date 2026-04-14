package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;

public interface PublishedReleaseBundleService {
  PublishedReleaseBundleDto createFullVersionBundle(
      VersionDto version, String publishWorkflowId, ExportedAssetManifest exportedManifest);

  PublishedReleaseBundleDto getPublishedReleaseBundle(String tenantId, long versionId);
}
