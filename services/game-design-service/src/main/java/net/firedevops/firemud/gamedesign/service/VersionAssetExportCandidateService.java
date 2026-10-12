package net.firedevops.firemud.gamedesign.service;

/** Owner-local immutable candidate evidence committed before any external byte write. */
public interface VersionAssetExportCandidateService {
  FrozenAssetExportCandidate recordExportCandidate(
      String tenantId, int versionNumber, FrozenAssetExportCandidate candidate);

  FrozenAssetExportCandidate readExportCandidate(String tenantId, int versionNumber);
}
