package net.firedevops.firemud.gamedesign.service;

public interface AssetExportService {
  ExportedAssetManifest exportAssets(String tenantId, long versionId, int exportedVersionNumber);

  void deleteExportedAssets(String tenantId, int version, java.util.List<String> manifestAssetKeys);
}
