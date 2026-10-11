package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;

public interface AssetExportService {
  ExportedAssetManifest exportAssets(String tenantId, int version);

  ExportedAssetManifest repairPublishedAssets(
      String tenantId, int version, PublishedReleaseBundleDto publishedBundle);

  void deleteExportedAssets(String tenantId, int version, java.util.List<String> manifestAssetKeys);
}
