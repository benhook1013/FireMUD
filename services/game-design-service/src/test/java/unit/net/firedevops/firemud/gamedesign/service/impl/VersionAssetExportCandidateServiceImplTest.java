package unit.net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventory;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService.CandidateBinding;
import org.junit.jupiter.api.Test;

/** Candidate values retain exact readback bytes and never collapse to digest-only equality. */
class VersionAssetExportCandidateServiceImplTest {
  @Test
  void candidateBindingDefensivelyRetainsEveryExactReadbackByteArray() {
    byte[] request = new byte[] {1, 2};
    byte[] inventory = new byte[] {3, 4};
    byte[] manifest = new byte[] {5, 6};
    CandidateBinding candidate = candidate(request, inventory, manifest);
    request[0] = 9;
    inventory[0] = 9;
    manifest[0] = 9;

    assertArrayEquals(new byte[] {1, 2}, candidate.requestPreimage());
    assertArrayEquals(new byte[] {3, 4}, candidate.inventoryBytes());
    assertArrayEquals(new byte[] {5, 6}, candidate.manifestBytes());
    byte[] returned = candidate.inventoryBytes();
    returned[0] = 8;
    assertArrayEquals(new byte[] {3, 4}, candidate.inventoryBytes());
  }

  @Test
  void candidateEqualityIncludesExactInventoryAndManifestBytes() {
    CandidateBinding original = candidate(new byte[] {1}, new byte[] {2}, new byte[] {3});

    assertFalse(original.equals(candidate(new byte[] {1}, new byte[] {8}, new byte[] {3})));
    assertFalse(original.equals(candidate(new byte[] {1}, new byte[] {2}, new byte[] {8})));
    assertEquals(original, candidate(new byte[] {1}, new byte[] {2}, new byte[] {3}));
  }

  @Test
  void candidateRequiresCompleteSelectedInventorySchemaAndImmutableManifestEvidence() {
    var manifest = new ExportedAssetManifest("sha256:" + "a".repeat(64), List.of());

    assertThrows(
        IllegalStateException.class,
        () ->
            new CandidateBinding(
                "b".repeat(64),
                "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64),
                SelectedDraftAssetInventory.SCHEMA,
                "sha256:" + "e".repeat(64),
                manifest,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CandidateBinding(
                "b".repeat(64),
                "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64),
                "unsupported-inventory",
                "sha256:" + "e".repeat(64),
                new ExportedAssetManifest("sha256:" + "a".repeat(64), 1, List.of(), List.of()),
                new byte[] {1},
                new byte[] {2},
                new byte[] {3}));
  }

  private static CandidateBinding candidate(byte[] request, byte[] inventory, byte[] manifest) {
    return new CandidateBinding(
        "b".repeat(64),
        "sha256:" + "c".repeat(64),
        "sha256:" + "d".repeat(64),
        SelectedDraftAssetInventory.SCHEMA,
        "sha256:" + "e".repeat(64),
        new ExportedAssetManifest("sha256:" + "a".repeat(64), 1, List.of(), List.of()),
        request,
        inventory,
        manifest);
  }
}
