package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;

/** Owner-local authoring and durable-readback operations for ordinary Version assets. */
public interface VersionAssetPublicationService {
  /**
   * Adds an exact source row to a caller-tenant Draft Version mapping. The tenant string and
   * numeric Version/asset ids are private owner keys, not external UUID identity aliases.
   */
  void associateDraftAsset(String tenantId, long versionId, long assetId, String usageType);

  /** Freezes the Draft mapping set once, or returns an earlier frozen selection after readback. */
  ExportSnapshot freezeOrReadSnapshot(String tenantId, int versionNumber);

  /** Independently reads and verifies an already committed durable selection. */
  ExportSnapshot readFrozenSnapshot(String tenantId, int versionNumber);
}
