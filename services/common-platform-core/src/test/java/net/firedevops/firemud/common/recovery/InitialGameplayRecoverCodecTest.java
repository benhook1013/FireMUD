package net.firedevops.firemud.common.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class InitialGameplayRecoverCodecTest {
  private static final String D = "sha256:" + "a".repeat(64);
  private static final String WIRE =
      """
      {"actionFamilySchemaId":"initial-gameplay-recover/v1","actionFamilySchemaVersion":"1",
      "controlPlaneRequestId":"00000000-0000-4000-8000-000000000001","targetOwner":"recovery-controller-service",
      "scope":{"canonicalTenantId":"00000000-0000-4000-8000-000000000002","targetNamespace":"test","playableStateNamespaceId":"00000000-0000-4000-8000-000000000003"},
      "target":{"launchAssociation":{"gameInstanceUuid":"00000000-0000-4000-8000-000000000004","tenantAssociationOperationId":"00000000-0000-4000-8000-000000000005","worldSlug":"world","launchControlPlaneRequestId":"00000000-0000-4000-8000-000000000006","launchDescriptorId":"launch","descriptorRequestDigest":"%s","descriptorResultDigest":"%s","releaseAttestationEvidenceDigest":"%s"},
      "peerStorageBinding":{"targetNamespace":"test","clusterIdentity":"cluster","peerWorkloadIdentity":"spiffe://firemud/ns/test/sa/game-session-service","storageIdentity":"pg","storageRevision":"1","bindingDigest":"%s"},"evidenceRef":"evidence","evidenceDigest":"%s"},
      "mutation":{"operationId":"00000000-0000-4000-8000-000000000007","verb":"recover","workflow":"initial-gameplay-release","maintenanceLockPresence":"RESULT_ONLY"},
      "auditReason":{"code":"INITIAL_GAMEPLAY_RELEASE","detail":"initial"}}
      """
          .replace("%s", D);

  // Static cross-language artifact: lowercase hex of the exact ADR 0047 preimage bytes.
  private static final String PREIMAGE_HEX =
      "31373a6d75746174696f6e4469676573742f763132303a616374696f6e46616d696c79536368656d614964363a737472696e673132373a696e697469616c2d67616d65706c61792d7265636f7665722f763132353a616374696f6e46616d696c79536368656d6156657273696f6e363a737472696e6731313a31353a73636f7065363a6f626a656374313137393a313a3331373a63616e6f6e6963616c54656e616e744964363a737472696e673133363a30303030303030302d303030302d343030302d383030302d30303030303030303030303231353a7461726765744e616d657370616365363a737472696e6731343a7465737432343a706c617961626c6553746174654e616d6573706163654964363a737472696e673133363a30303030303030302d303030302d343030302d383030302d303030303030303030303033363a746172676574363a6f626a65637431313134353a313a3431373a6c61756e63684173736f63696174696f6e363a6f626a656374313632373a313a3831363a67616d65496e7374616e636555756964363a737472696e673133363a30303030303030302d303030302d343030302d383030302d30303030303030303030303432383a74656e616e744173736f63696174696f6e4f7065726174696f6e4964363a737472696e673133363a30303030303030302d303030302d343030302d383030302d303030303030303030303035393a776f726c64536c7567363a737472696e6731353a776f726c6432373a6c61756e6368436f6e74726f6c506c616e65526571756573744964363a737472696e673133363a30303030303030302d303030302d343030302d383030302d30303030303030303030303631383a6c61756e636844657363726970746f724964363a737472696e6731363a6c61756e636832333a64657363726970746f7252657175657374446967657374363a737472696e673137313a7368613235363a6161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616132323a64657363726970746f72526573756c74446967657374363a737472696e673137313a7368613235363a6161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616133323a72656c656173654174746573746174696f6e45766964656e6365446967657374363a737472696e673137313a7368613235363a6161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616131383a7065657253746f7261676542696e64696e67363a6f626a656374313331353a313a3631353a7461726765744e616d657370616365363a737472696e6731343a7465737431353a636c75737465724964656e74697479363a737472696e6731373a636c757374657232303a70656572576f726b6c6f61644964656e74697479363a737472696e673134383a7370696666653a2f2f666972656d75642f6e732f746573742f73612f67616d652d73657373696f6e2d7365727669636531353a73746f726167654964656e74697479363a737472696e6731323a706731353a73746f726167655265766973696f6e363a737472696e6731313a3131333a62696e64696e67446967657374363a737472696e673137313a7368613235363a6161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616131313a65766964656e6365526566363a737472696e6731383a65766964656e636531343a65766964656e6365446967657374363a737472696e673137313a7368613235363a6161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616131353a657870656374656456657273696f6e363a616273656e7430303a383a6d75746174696f6e363a6f626a656374313138343a313a3431313a6f7065726174696f6e4964363a737472696e673133363a30303030303030302d303030302d343030302d383030302d303030303030303030303037343a76657262363a737472696e6731373a7265636f766572383a776f726b666c6f77363a737472696e673132343a696e697469616c2d67616d65706c61792d72656c6561736532333a6d61696e74656e616e63654c6f636b50726573656e6365363a737472696e673131313a524553554c545f4f4e4c5931313a6175646974526561736f6e363a6f626a6563743137313a313a32343a636f6465363a737472696e673132343a494e495449414c5f47414d45504c41595f52454c45415345363a64657461696c363a737472696e6731373a696e697469616c";
  private static final String DIGEST =
      "55b684f3d25ea838ce74c31c18f6852d4d70cf0d298f1694c96929e865cfb670";
  // Same immutable prefix, followed by the independently framed 95-byte NFC audit-reason field.
  private static final String UNICODE_PREIMAGE_HEX =
      PREIMAGE_HEX.substring(0, PREIMAGE_HEX.length() - 194)
          + "31313a6175646974526561736f6e363a6f626a6563743136393a313a32343a636f6465363a737472696e673132343a494e495449414c5f47414d45504c41595f52454c45415345363a64657461696c363a737472696e6731353a636166c3a9";
  private static final String UNICODE_DIGEST =
      "e9c2302aa9dc1493f29e0c25a10eedd2c6c2a270a7f55018722593a667cd8458";

  @Test
  void matchesStaticCrossLanguagePreimageHexAndDigest() {
    var request = decode(WIRE);
    assertThat(InitialGameplayRecoverCodec.mutationPreimage(request))
        .isEqualTo(HexFormat.of().parseHex(PREIMAGE_HEX));
    assertThat(InitialGameplayRecoverCodec.mutationDigest(request)).isEqualTo(DIGEST);
    assertThat(new String(HexFormat.of().parseHex(PREIMAGE_HEX), StandardCharsets.UTF_8))
        .contains("15:expectedVersion6:absent00:", "maintenanceLockPresence6:string111:RESULT_ONLY")
        .doesNotContain("controlPlaneRequestId", "targetOwner", "maintenanceLockToken");
  }

  @Test
  void requestIdentityIsRetainedButExcludedFromMutationDigest() {
    var changed = decode(WIRE.replace("000000000001", "000000000009"));
    assertThat(changed.controlPlaneRequestId()).isNotEqualTo(decode(WIRE).controlPlaneRequestId());
    assertThat(InitialGameplayRecoverCodec.mutationDigest(changed)).isEqualTo(DIGEST);
  }

  @Test
  void everyVariablePayloadFieldChangesTheDigest() {
    for (String suffix :
        List.of(
            "000000000002",
            "000000000003",
            "000000000004",
            "000000000005",
            "000000000006",
            "000000000007")) {
      assertChanged(WIRE.replace(suffix, "000000000009"));
    }
    for (String field :
        List.of(
            "worldSlug",
            "launchDescriptorId",
            "clusterIdentity",
            "storageIdentity",
            "storageRevision",
            "evidenceRef",
            "detail")) {
      assertChanged(WIRE.replaceAll("(\\\"" + field + "\\\":\\\")([^\\\"]+)", "$1changed"));
    }
    for (String field :
        List.of(
            "descriptorRequestDigest",
            "descriptorResultDigest",
            "releaseAttestationEvidenceDigest",
            "bindingDigest",
            "evidenceDigest")) {
      assertChanged(
          WIRE.replace("\"" + field + "\":\"" + D, "\"" + field + "\":\"sha256:" + "b".repeat(64)));
    }
    // Scope namespace and the two matching peer/storage selector fields change together.
    assertChanged(
        WIRE.replace("\"targetNamespace\":\"test\"", "\"targetNamespace\":\"other\"")
            .replace("/ns/test/", "/ns/other/"));
  }

  @Test
  void nfcEquivalentAuditDetailsHaveIdenticalBytes() {
    var composed = decode(WIRE.replace("\"initial\"", "\"caf\u00e9\""));
    var decomposed = decode(WIRE.replace("\"initial\"", "\"cafe\u0301\""));
    assertThat(InitialGameplayRecoverCodec.mutationPreimage(composed))
        .isEqualTo(InitialGameplayRecoverCodec.mutationPreimage(decomposed))
        .isEqualTo(HexFormat.of().parseHex(UNICODE_PREIMAGE_HEX));
    assertThat(InitialGameplayRecoverCodec.mutationDigest(composed)).isEqualTo(UNICODE_DIGEST);
    assertThat(InitialGameplayRecoverCodec.mutationDigest(decomposed)).isEqualTo(UNICODE_DIGEST);
  }

  @Test
  void rejectsUnknownDuplicateMissingAndOutOfOrderFields() {
    rejects(WIRE.replace("\"scope\":", "\"unknown\":\"x\",\"scope\":"));
    rejects(
        WIRE.replace("\"worldSlug\":\"world\"", "\"worldSlug\":\"world\",\"worldSlug\":\"world\""));
    rejects(WIRE.replace("\"worldSlug\":\"world\",", ""));
    rejects(
        WIRE.replace(
            "\"storageIdentity\":\"pg\",\"storageRevision\":\"1\"",
            "\"storageRevision\":\"1\",\"storageIdentity\":\"pg\""));
    rejects(WIRE.replace("\"detail\"", "\"deta\\u0069l\":\"first\",\"detail\""));
  }

  @Test
  void rejectsEveryLockAndAuthorityEscape() {
    for (String value : List.of("REQUIRED", "NOT_APPLICABLE", "OPTIONAL", "", "sentinel")) {
      rejects(WIRE.replace("RESULT_ONLY", value));
    }
    for (String field :
        List.of(
            "maintenanceLockToken",
            "maintenanceLockIdentity",
            "operatorAuthorizationReference",
            "authorityTuple",
            "ownerAssociationVerified")) {
      rejects(WIRE.replace("\"auditReason\":", "\"" + field + "\":\"asserted\",\"auditReason\":"));
    }
    rejects(WIRE.replace("\"auditReason\":", "\"expectedVersion\":null,\"auditReason\":"));
    rejects(WIRE.replace("\"RESULT_ONLY\"", "null"));
    rejects(WIRE.replace("\"recover\"", "\"resume\""));
    rejects(WIRE.replace("initial-gameplay-release", "cold-start-restore"));
    rejects(WIRE.replace("recovery-controller-service", "game-session-service"));
  }

  @Test
  void rejectsNoncanonicalIdsWrongTypesMalformedUnicodeAndOverlimits() {
    rejects(
        WIRE.replace(
            "00000000-0000-4000-8000-000000000001", "00000000-0000-0000-0000-000000000000"));
    rejects(
        WIRE.replace(
            "00000000-0000-4000-8000-000000000001", "AAAAAAAA-0000-4000-8000-000000000001"));
    rejects(WIRE.replace("\"storageRevision\":\"1\"", "\"storageRevision\":1e3"));
    rejects(WIRE.replace("\"storageRevision\":\"1\"", "\"storageRevision\":[]"));
    rejects(WIRE.replace("\"initial\"", "\"\\ud800\""));
    rejects(WIRE.replace("\"initial\"", "\"" + "x".repeat(1025) + "\""));
    rejects(WIRE + " ".repeat(InitialGameplayRecoverCodec.MAX_RAW_BYTES));
    rejects(WIRE + "{}");
  }

  @Test
  void rejectsNonUtf8ObjectWithTheDecoderDiagnostic() {
    byte[] malformed = WIRE.getBytes(StandardCharsets.UTF_8);
    int offset = WIRE.indexOf("\"detail\":\"initial\"") + "\"detail\":\"".length();
    malformed[offset] = (byte) 0xc3;
    assertThatThrownBy(() -> InitialGameplayRecoverCodec.decode(malformed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Recover request must be valid UTF-8");
  }

  @Test
  void encodedTokenCanExceedDecodedStringLimitWithinRawRequestCeiling() {
    String detail = "a".repeat(700);
    String escaped = "\\u0061".repeat(700);
    assertThat(escaped.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(4096);
    String wire = WIRE.replace("\"initial\"", "\"" + escaped + "\"");
    int paddingBytes =
        InitialGameplayRecoverCodec.MAX_RAW_BYTES - wire.getBytes(StandardCharsets.UTF_8).length;
    String atRawLimit = wire + " ".repeat(paddingBytes);

    var escapedRequest = decode(atRawLimit);
    var plainRequest = decode(WIRE.replace("\"initial\"", "\"" + detail + "\""));
    assertThat(escapedRequest).isEqualTo(plainRequest);
    assertThat(InitialGameplayRecoverCodec.mutationDigest(escapedRequest))
        .isEqualTo(InitialGameplayRecoverCodec.mutationDigest(plainRequest));
    rejects(atRawLimit + " ");
  }

  @Test
  void rejectsRawFieldsThatShrinkBelowTheirLimitOnlyAfterNormalization() {
    rejects(WIRE.replace("\"world\"", "\"" + "e\u0301".repeat(43) + "\""));
    rejects(WIRE.replace("\"initial\"", "\"" + "e\u0301".repeat(342) + "\""));
  }

  @Test
  void rejectsUtf16AndUtf32EvenWhenTheirBytesAreValidUtf8() {
    for (String encoding : List.of("UTF-16BE", "UTF-16LE", "UTF-32BE", "UTF-32LE")) {
      assertThatThrownBy(() ->
              InitialGameplayRecoverCodec.decode(
                  WIRE.getBytes(java.nio.charset.Charset.forName(encoding))))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static InitialGameplayRecoverRequest decode(String wire) {
    return InitialGameplayRecoverCodec.decode(wire.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertChanged(String wire) {
    assertThat(InitialGameplayRecoverCodec.mutationDigest(decode(wire))).isNotEqualTo(DIGEST);
  }

  private static void rejects(String wire) {
    assertThatThrownBy(() -> decode(wire)).isInstanceOf(IllegalArgumentException.class);
  }
}
