package net.firedevops.firemud.gamedesign.service;

/** Owner-local immutable candidate evidence committed before any external byte write. */
public interface VersionAssetExportCandidateService {
  ExportedAssetManifest recordExportCandidate(
      String tenantId, int versionNumber, ExportedAssetManifest candidate);

  ExportedAssetManifest readExportCandidate(String tenantId, int versionNumber);
}
