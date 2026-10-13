package net.firedevops.firemud.gamedesign.service;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventory;

/** Durable selected-inventory candidate evidence committed before any object-store write. */
public interface VersionAssetExportCandidateService {
  void recordSelectedCandidate(
      SelectedDraftAssetInventory inventory, ExportedAssetManifest candidate, byte[] manifestBytes);

  CandidateBinding readSelectedCandidate(PublicationDigestRequestBinding request);

  /** SQL-only retained-candidate comparison inside the caller-owned finalization transaction. */
  void requireSelectedCandidateForFinalization(
      PublicationDigestRequestBinding request, CandidateBinding expected);

  /** Exact source and output bindings independently read back from the immutable candidate row. */
  record CandidateBinding(
      String requestDigest,
      String operationDigest,
      String selectedCommitDigest,
      String inventorySchema,
      String inventoryDigest,
      ExportedAssetManifest manifest,
      byte[] requestPreimage,
      byte[] inventoryBytes,
      byte[] manifestBytes) {
    public CandidateBinding {
      if (requestDigest == null || !requestDigest.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("candidate request digest is invalid");
      }
      requireSha256(operationDigest, "operationDigest");
      requireSha256(selectedCommitDigest, "selectedCommitDigest");
      if (!SelectedDraftAssetInventory.SCHEMA.equals(inventorySchema)) {
        throw new IllegalArgumentException("candidate inventory schema is unsupported");
      }
      requireSha256(inventoryDigest, "inventoryDigest");
      Objects.requireNonNull(manifest, "manifest").requireImmutableArtifactEvidence();
      requestPreimage = Objects.requireNonNull(requestPreimage, "requestPreimage").clone();
      inventoryBytes = Objects.requireNonNull(inventoryBytes, "inventoryBytes").clone();
      manifestBytes = Objects.requireNonNull(manifestBytes, "manifestBytes").clone();
    }

    @Override
    public byte[] requestPreimage() {
      return requestPreimage.clone();
    }

    @Override
    public byte[] inventoryBytes() {
      return inventoryBytes.clone();
    }

    @Override
    public byte[] manifestBytes() {
      return manifestBytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof CandidateBinding binding
          && requestDigest.equals(binding.requestDigest)
          && operationDigest.equals(binding.operationDigest)
          && selectedCommitDigest.equals(binding.selectedCommitDigest)
          && inventorySchema.equals(binding.inventorySchema)
          && inventoryDigest.equals(binding.inventoryDigest)
          && manifest.equals(binding.manifest)
          && Arrays.equals(requestPreimage, binding.requestPreimage)
          && Arrays.equals(inventoryBytes, binding.inventoryBytes)
          && Arrays.equals(manifestBytes, binding.manifestBytes);
    }

    @Override
    public int hashCode() {
      int result =
          Objects.hash(
              requestDigest,
              operationDigest,
              selectedCommitDigest,
              inventorySchema,
              inventoryDigest,
              manifest);
      result = 31 * result + Arrays.hashCode(requestPreimage);
      result = 31 * result + Arrays.hashCode(inventoryBytes);
      result = 31 * result + Arrays.hashCode(manifestBytes);
      return result;
    }

    private static void requireSha256(String value, String field) {
      if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("candidate " + field + " is invalid");
      }
    }
  }
}
