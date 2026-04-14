package net.firedevops.firemud.gamedesign.service;

public interface AssetExportService {
  ExportedAssetManifest exportAssets(String tenantId, int version);

  void deleteExportedAssets(String tenantId, int version);
}
