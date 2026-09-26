package net.firedevops.firemud.gamedesign.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest.Entry;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest.Signed;
import org.junit.jupiter.api.Test;

class TenantAssociationManifestVerifierTest {
  private static final String ACCOUNT_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void exactApprovedManifestVerifiesAndChangedAssociationCannotReuseSignature() throws Exception {
    KeyPair ownerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    TenantAssociationManifest manifest = manifest();
    Map<String, String> trustedKeys =
        Map.of(
            "game-design-owner-2026",
            Base64.getEncoder().encodeToString(ownerKey.getPublic().getEncoded()));
    Signed signed = signed(manifest, ownerKey);

    var verified = TenantAssociationManifestVerifier.verify(signed, trustedKeys);
    assertThat(verified.manifest()).isEqualTo(manifest);
    assertThat(verified.manifestDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(TenantAssociationManifestVerifier.verify(signed, trustedKeys).manifestDigest())
        .isEqualTo(verified.manifestDigest());

    TenantAssociationManifest changed =
        new TenantAssociationManifest(
            manifest.schemaVersion(),
            manifest.operationId(),
            manifest.targetNamespace(),
            manifest.signerKeyId(),
            manifest.approvedBy(),
            manifest.approvalReference(),
            manifest.signedAt(),
            List.of(
                new Entry(
                    42,
                    "legacy-game",
                    manifest.entries().getFirst().canonicalTenantId(),
                    7,
                    ACCOUNT_DIGEST)));
    assertThatThrownBy(
            () ->
                TenantAssociationManifestVerifier.verify(
                    new Signed(changed, signed.ed25519Signature()), trustedKeys))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signature is invalid");
    assertThatThrownBy(() -> TenantAssociationManifestVerifier.verify(signed, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not trusted");
  }

  @Test
  void duplicateAndUnsortedPairsAreRejectedBeforeOwnerApproval() throws Exception {
    KeyPair ownerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    TenantAssociationManifest source = manifest();
    Entry first = source.entries().getFirst();
    TenantAssociationManifest duplicate =
        withEntries(
            source,
            List.of(
                first,
                new Entry(43, first.legacyGameTenantId(), UUID.randomUUID(), 8, ACCOUNT_DIGEST)));
    TenantAssociationManifest unsorted =
        withEntries(
            source,
            List.of(new Entry(43, "other-game", UUID.randomUUID(), 8, ACCOUNT_DIGEST), first));
    Map<String, String> keys =
        Map.of(
            "game-design-owner-2026",
            Base64.getEncoder().encodeToString(ownerKey.getPublic().getEncoded()));

    assertThatThrownBy(
            () -> TenantAssociationManifestVerifier.verify(new Signed(duplicate, ""), keys))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ambiguous");
    assertThatThrownBy(
            () -> TenantAssociationManifestVerifier.verify(new Signed(unsorted, ""), keys))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ambiguous");
  }

  private TenantAssociationManifest manifest() {
    return new TenantAssociationManifest(
        1,
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        "dev",
        "game-design-owner-2026",
        "owner@example.test",
        "reviewed-change-123",
        Instant.parse("2026-09-26T00:00:00Z"),
        List.of(
            new Entry(
                41,
                "legacy-game",
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                7,
                ACCOUNT_DIGEST)));
  }

  private TenantAssociationManifest withEntries(
      TenantAssociationManifest source, List<Entry> entries) {
    return new TenantAssociationManifest(
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        entries);
  }

  private Signed signed(TenantAssociationManifest manifest, KeyPair keyPair) throws Exception {
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(keyPair.getPrivate());
    signature.update(TenantAssociationManifestVerifier.preimage(manifest));
    return new Signed(manifest, Base64.getEncoder().encodeToString(signature.sign()));
  }
}
