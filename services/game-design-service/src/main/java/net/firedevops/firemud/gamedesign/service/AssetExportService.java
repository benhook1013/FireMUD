package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;

public interface AssetExportService {
  ExportedAssetManifest exportAssets(String tenantId, int version);

  /**
   * Exports the exact retained complete selected-Draft inventory and returns its readback proof.
   */
  SelectedExportResult exportSelectedAssets(PublicationDigestRequestBinding request);

  /** Revalidates the exact retained candidate inside the caller's finalization transaction. */
  void requireSelectedCandidateForFinalization(
      PublicationDigestRequestBinding request, SelectedExportResult exportResult);

  void deleteExportedAssets(String tenantId, int version, java.util.List<String> manifestAssetKeys);

  record SelectedExportResult(
      ExportedAssetManifest manifest,
      VersionAssetExportCandidateService.CandidateBinding candidateBinding) {
    public SelectedExportResult {
      java.util.Objects.requireNonNull(manifest, "manifest");
      java.util.Objects.requireNonNull(candidateBinding, "candidateBinding");
      if (!manifest.equals(candidateBinding.manifest())) {
        throw new IllegalArgumentException("Selected export candidate manifest differs");
      }
    }
  }

  /** Signals an ambiguous selected-export boundary that must retain the publication as pending. */
  final class SelectedExportOutcomePendingException extends IllegalStateException {
    public SelectedExportOutcomePendingException(String message, RuntimeException cause) {
      super(message, cause);
    }
  }
}
