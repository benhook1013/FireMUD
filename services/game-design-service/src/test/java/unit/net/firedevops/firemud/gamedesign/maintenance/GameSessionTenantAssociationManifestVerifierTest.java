package unit.net.firedevops.firemud.gamedesign.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.gamedesign.maintenance.GameSessionTenantAssociationManifestVerifier;
import net.firedevops.firemud.gamedesign.maintenance.GameSessionTenantAssociationManifestVerifier.Signed;
import org.junit.jupiter.api.Test;

class GameSessionTenantAssociationManifestVerifierTest {
  @Test
  void verifiesExactOwnerSignedReadbackAndRecomputesDigest() throws Exception {
    KeyPair ownerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    GameSessionTenantAssociationEvidence manifest = evidence();
    Map<String, String> trustedKeys = trustedKeys("owner-key-2026", ownerKey);
    Signed signed = signed(manifest, ownerKey);

    var verified = GameSessionTenantAssociationManifestVerifier.verify(signed, trustedKeys);
    assertThat(verified.manifest()).isEqualTo(manifest);
    assertThat(verified.manifestDigest()).isEqualTo(manifest.manifestDigest());
    assertThat(GameSessionTenantAssociationManifestVerifier.verify(signed, trustedKeys))
        .isEqualTo(verified);
  }

  @Test
  void everyMutableManifestFieldIsSignatureBound() throws Exception {
    KeyPair ownerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    GameSessionTenantAssociationEvidence manifest = evidence();
    Signed signed = signed(manifest, ownerKey);
    String publicKey = Base64.getEncoder().encodeToString(ownerKey.getPublic().getEncoded());
    Map<String, String> trustedKeys =
        Map.of("owner-key-2026", publicKey, "owner-key-2027", publicKey);

    for (GameSessionTenantAssociationEvidence changed : changedFields(manifest)) {
      assertThat(changed.preimage()).isNotEqualTo(manifest.preimage());
      assertThatThrownBy(
              () ->
                  GameSessionTenantAssociationManifestVerifier.verify(
                      new Signed(changed, signed.ed25519Signature()), trustedKeys))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("signature is invalid");
    }
    assertThatThrownBy(() -> copyWithSchemaVersion(manifest, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema version");
  }

  @Test
  void rejectsAbsentUntrustedWrongPurposeMalformedAndInvalidSignatures() throws Exception {
    KeyPair ownerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair unrelatedKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    GameSessionTenantAssociationEvidence manifest = evidence();
    Signed signed = signed(manifest, ownerKey);
    Map<String, String> trustedKeys = trustedKeys("owner-key-2026", ownerKey);

    assertThatThrownBy(() -> GameSessionTenantAssociationManifestVerifier.verify(null, trustedKeys))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameSessionTenantAssociationManifestVerifier.verify(
                    new Signed(null, signed.ed25519Signature()), trustedKeys))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GameSessionTenantAssociationManifestVerifier.verify(signed, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not trusted");

    GameSessionTenantAssociationEvidence wrongPurpose =
        copyWithSignerKeyId(manifest, "account-owner-key");
    Signed accountPurposeSignature = signed(wrongPurpose, unrelatedKey);
    assertThatThrownBy(
            () ->
                GameSessionTenantAssociationManifestVerifier.verify(
                    accountPurposeSignature, trustedKeys))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not trusted");

    assertThatThrownBy(
            () ->
                GameSessionTenantAssociationManifestVerifier.verify(
                    signed, Map.of("owner-key-2026", "not base64")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameSessionTenantAssociationManifestVerifier.verify(
                    signed,
                    Map.of("owner-key-2026", Base64.getEncoder().encodeToString(new byte[32]))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be verified");
    assertThatThrownBy(
            () ->
                GameSessionTenantAssociationManifestVerifier.verify(
                    new Signed(manifest, "%%%"), trustedKeys))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameSessionTenantAssociationManifestVerifier.verify(
                    new Signed(manifest, Base64.getEncoder().encodeToString(new byte[63])),
                    trustedKeys))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("64-byte");
    assertThatThrownBy(
            () ->
                GameSessionTenantAssociationManifestVerifier.verify(
                    signed(manifest, unrelatedKey), trustedKeys))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signature is invalid");
  }

  @Test
  void rejectsSignatureOverDistinctAccountAssociationDomain() throws Exception {
    KeyPair ownerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    GameSessionTenantAssociationEvidence gameSessionManifest = evidence();
    Map<String, String> trustedKeys = trustedKeys("owner-key-2026", ownerKey);
    Signed correctDomainSignature = signed(gameSessionManifest, ownerKey);
    assertThat(
            GameSessionTenantAssociationManifestVerifier.verify(correctDomainSignature, trustedKeys)
                .manifest())
        .isEqualTo(gameSessionManifest);

    byte[] correctDomainPreimage = gameSessionManifest.preimage();
    byte[] accountAssociationPreimage =
        replaceFirstLengthFramedSegment(
            correctDomainPreimage, "account/game-session-tenant-association/v1");
    assertThat(bytesAfterFirstLengthFramedSegment(accountAssociationPreimage))
        .isEqualTo(bytesAfterFirstLengthFramedSegment(correctDomainPreimage));

    Signature accountAssociationSigner = Signature.getInstance("Ed25519");
    accountAssociationSigner.initSign(ownerKey.getPrivate());
    accountAssociationSigner.update(accountAssociationPreimage);
    Signed wrongDomainSignature =
        new Signed(
            gameSessionManifest,
            Base64.getEncoder().encodeToString(accountAssociationSigner.sign()));

    assertThatThrownBy(
            () ->
                GameSessionTenantAssociationManifestVerifier.verify(
                    wrongDomainSignature, trustedKeys))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signature is invalid");
  }

  private static byte[] replaceFirstLengthFramedSegment(byte[] source, String replacement) {
    int colonIndex = 0;
    while (source[colonIndex] != ':') {
      colonIndex++;
    }
    int originalLength =
        Integer.parseInt(new String(source, 0, colonIndex, StandardCharsets.US_ASCII));
    int suffixStart = colonIndex + 1 + originalLength;
    byte[] replacementBytes = replacement.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.writeBytes(
        Integer.toString(replacementBytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(replacementBytes);
    output.write(source, suffixStart, source.length - suffixStart);
    return output.toByteArray();
  }

  private static byte[] bytesAfterFirstLengthFramedSegment(byte[] source) {
    int colonIndex = 0;
    while (source[colonIndex] != ':') {
      colonIndex++;
    }
    int firstSegmentLength =
        Integer.parseInt(new String(source, 0, colonIndex, StandardCharsets.US_ASCII));
    return Arrays.copyOfRange(source, colonIndex + 1 + firstSegmentLength, source.length);
  }

  private static GameSessionTenantAssociationEvidence evidence() {
    return new GameSessionTenantAssociationEvidence(
        2,
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        "firemud",
        "owner-key-2026",
        "owner 🧙",
        "review café-🐉",
        Instant.parse("2026-10-01T00:00:00Z").toString(),
        Instant.parse("2026-09-30T00:00:00Z").toString(),
        "9007199254740993",
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "42",
        "legacy-game-key-42",
        "RETAINED_GAME_V29",
        "sha256:" + "b".repeat(64),
        "sha256:" + "a".repeat(64));
  }

  private static java.util.List<GameSessionTenantAssociationEvidence> changedFields(
      GameSessionTenantAssociationEvidence source) {
    return java.util.List.of(
        copy(source, "9007199254740994", source.sourceGameRowId()),
        copyWithTargetNamespace(source, "firemud-dev"),
        copyWithSignerKeyId(source, "owner-key-2027"),
        copyWithApproval(source, "another owner", source.approvalReference()),
        copyWithApproval(source, source.approvedBy(), "another-review-reference"),
        copyWithSignedAt(source, "2026-10-01T00:00:01Z"),
        copyWithSourceCapturedAt(source, "2026-09-29T00:00:00Z"),
        copyWithOperationId(source, UUID.fromString("33333333-3333-4333-8333-333333333333")),
        copyWithTenantId(source, UUID.fromString("33333333-3333-4333-8333-333333333333")),
        copy(source, source.legacyGameSessionTenantId(), "43"),
        copyWithSourceGameTenantKey(source, "another-source-key"),
        copyWithProvenanceKind(source, "NEW_GAME_ROW"),
        copyWithGameSessionEvidenceDigest(source, "sha256:" + "b".repeat(64)));
  }

  private static Map<String, String> trustedKeys(String keyId, KeyPair keyPair) {
    return Map.of(keyId, Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
  }

  private static Signed signed(GameSessionTenantAssociationEvidence manifest, KeyPair keyPair)
      throws Exception {
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keyPair.getPrivate());
    signer.update(manifest.preimage());
    return new Signed(manifest, Base64.getEncoder().encodeToString(signer.sign()));
  }

  private static GameSessionTenantAssociationEvidence copy(
      GameSessionTenantAssociationEvidence source,
      String legacyGameSessionTenantId,
      String sourceGameRowId) {
    return make(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        legacyGameSessionTenantId,
        source.canonicalTenantId(),
        sourceGameRowId,
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithSchemaVersion(
      GameSessionTenantAssociationEvidence source, int schemaVersion) {
    return make(
        source,
        schemaVersion,
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithTargetNamespace(
      GameSessionTenantAssociationEvidence source, String targetNamespace) {
    return make(
        source,
        source.schemaVersion(),
        source.operationId(),
        targetNamespace,
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithSignerKeyId(
      GameSessionTenantAssociationEvidence source, String signerKeyId) {
    return make(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        signerKeyId,
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithOperationId(
      GameSessionTenantAssociationEvidence source, UUID operationId) {
    return make(
        source,
        source.schemaVersion(),
        operationId,
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithApproval(
      GameSessionTenantAssociationEvidence source, String approvedBy, String approvalReference) {
    return make(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        approvedBy,
        approvalReference,
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithSignedAt(
      GameSessionTenantAssociationEvidence source, String signedAt) {
    return make(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        signedAt,
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithTenantId(
      GameSessionTenantAssociationEvidence source, UUID canonicalTenantId) {
    return make(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        canonicalTenantId,
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithSourceGameTenantKey(
      GameSessionTenantAssociationEvidence source, String sourceGameTenantKey) {
    return make(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        sourceGameTenantKey,
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithProvenanceKind(
      GameSessionTenantAssociationEvidence source, String provenanceKind) {
    return make(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        provenanceKind,
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithGameSessionEvidenceDigest(
      GameSessionTenantAssociationEvidence source, String gameSessionEvidenceDigest) {
    return make(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        gameSessionEvidenceDigest);
  }

  private static GameSessionTenantAssociationEvidence make(
      GameSessionTenantAssociationEvidence source,
      int schemaVersion,
      UUID operationId,
      String targetNamespace,
      String signerKeyId,
      String approvedBy,
      String approvalReference,
      String signedAt,
      String legacyGameSessionTenantId,
      UUID canonicalTenantId,
      String sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind,
      String gameSessionEvidenceDigest) {
    return new GameSessionTenantAssociationEvidence(
        schemaVersion,
        operationId,
        targetNamespace,
        signerKeyId,
        approvedBy,
        approvalReference,
        signedAt,
        source.sourceCapturedAt(),
        legacyGameSessionTenantId,
        canonicalTenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        source.gameSessionProjectionDigest(),
        gameSessionEvidenceDigest);
  }

  private static GameSessionTenantAssociationEvidence copyWithSourceCapturedAt(
      GameSessionTenantAssociationEvidence source, String sourceCapturedAt) {
    return new GameSessionTenantAssociationEvidence(
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        sourceCapturedAt,
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionProjectionDigest(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithProvenance(
      GameSessionTenantAssociationEvidence source, String provenanceKind) {
    return copyWithProvenanceKind(source, provenanceKind);
  }
}
