package net.firedevops.firemud.gamedesign.service;

public interface AssetExportService {
  void exportAssets(String tenantId, int version);

  void deleteExportedAssets(String tenantId, int version);
}
