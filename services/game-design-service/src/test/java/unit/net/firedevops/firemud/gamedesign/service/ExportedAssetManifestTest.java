package net.firedevops.firemud.gamedesign.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExportedAssetManifestTest {
  @Test
  void processLocalExportCannotSupplyImmutablePublicationProof() {
    var exported = new ExportedAssetManifest("process-local-hash", List.of("manifest.json"));
    assertEquals(List.of("manifest.json"), exported.requiredManifestAssetKeys());
    assertThrows(IllegalStateException.class, exported::requireImmutableArtifactEvidence);
  }

  @Test
  void completeEmptyManifestCarriesExplicitImmutableArtifactProof() {
    var exported = new ExportedAssetManifest("sha256:" + "a".repeat(64), 1, List.of(), List.of());
    assertDoesNotThrow(exported::requireImmutableArtifactEvidence);
  }
}
