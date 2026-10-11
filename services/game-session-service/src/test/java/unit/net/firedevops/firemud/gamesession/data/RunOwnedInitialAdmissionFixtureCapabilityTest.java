package net.firedevops.firemud.gamesession.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionFixtureCapability.CertificatePins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

class RunOwnedInitialAdmissionFixtureCapabilityTest {
  private static final String RUN_ID = "compose-smoke-2939";
  private static final String PROJECT_NAME = "firemud-smoke-compose-smoke-2939";
  private static final String OPERATION_ID = "d2db8478-9c56-42ab-99c2-9fbead6841ba";
  private static final String SOURCE_OPERATION_ID = "3f773701-e812-44f5-8ae0-7268d543beed";
  private static final String LEAF_SHA = "a".repeat(64);
  private static final String WORLD_LEAF_SHA = "c".repeat(64);
  private static final String CA_SHA = "b".repeat(64);

  @Test
  void parsesExactCapabilityAndBindsRunProjectSelectorsAndTlsPins() {
    RunOwnedInitialAdmissionFixtureCapability capability =
        RunOwnedInitialAdmissionFixtureCapability.parseAndValidate(
            validJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            RUN_ID,
            PROJECT_NAME,
            pins());

    assertThat(capability.runId()).isEqualTo(RUN_ID);
    assertThat(capability.composeProjectName()).isEqualTo(PROJECT_NAME);
    assertThat(capability.operationId()).isEqualTo(OPERATION_ID);
    assertThat(capability.tenantId()).isEqualTo(7L);
    assertThat(capability.gameTemplateId()).isEqualTo(17L);
    assertThat(capability.ownerAccountId()).isEqualTo(27L);
    assertThat(capability.worldSlug()).isEqualTo("demo");
    assertThat(capability.realmSlug()).isEqualTo("production");
    assertThat(capability.visible()).isTrue();
    assertThat(capability.publicProductionRealm()).isTrue();
    assertThat(capability.requiresCharacterSelection()).isFalse();
    assertThat(capability.stateScope()).isEqualTo("SHARED");
    assertThat(capability.characterCreationPolicy()).isEqualTo("ALLOW_NEW");
    assertThat(capability.worldManagementUriSan())
        .isEqualTo(RunOwnedInitialAdmissionFixtureCapability.WORLD_MANAGEMENT_URI_SAN);
    assertThat(capability.worldManagementLeafSha256()).isEqualTo(WORLD_LEAF_SHA);
  }

  @Test
  void rejectsUnknownDuplicateMismatchedAndNoncanonicalCapabilityFields() {
    assertInvalid(validJson().replace("\"schema\":", "\"extra\":true,\"schema\":"));
    assertInvalid(validJson().replace("\"schema\":", "\"schema\":\"wrong\",\"schema\":"));
    assertInvalid(validJson().replace(RUN_ID, "different-run"));
    assertInvalid(validJson().replace(OPERATION_ID, "not-a-uuid"));
    assertInvalid(
        validJson().replace("\"operationId\": \"" + OPERATION_ID + "\"", "\"operationId\": null"));
    assertInvalid(validJson().replace("\"tenantId\": 7", "\"tenantId\": \"7\""));
    assertInvalid(
        validJson().replace("\"stateScope\": \"SHARED\"", "\"stateScope\": \"ISOLATED\""));
    assertInvalid(validJson().replace(LEAF_SHA, "c".repeat(64)));
    assertThatThrownBy(
            () ->
                RunOwnedInitialAdmissionFixtureCapability.parseAndValidate(
                    validJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    RUN_ID,
                    "other-project",
                    pins()))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureCapability.InvalidCapabilityException.class);
  }

  @Test
  void rejectsWritableCapabilityFile(@TempDir Path directory) throws Exception {
    Path capabilityFile =
        Files.writeString(directory.resolve("fixture-capability.json"), validJson());

    assertThatThrownBy(
            () -> RunOwnedInitialAdmissionFixtureCapability.readCapability(capabilityFile))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureCapability.InvalidCapabilityException.class);
  }

  @Test
  void readsReadOnlyCapabilityFile(@TempDir Path directory) throws Exception {
    String json = validJson();
    Path capabilityFile = Files.writeString(directory.resolve("fixture-capability.json"), json);
    Files.setPosixFilePermissions(capabilityFile, Set.of(PosixFilePermission.OWNER_READ));

    assertThat(RunOwnedInitialAdmissionFixtureCapability.readCapability(capabilityFile))
        .isEqualTo(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void parsesExactSourceReceiptAndReconstructsTheFullEvidenceRecord() {
    RunOwnedInitialAdmissionFixtureCapability.SourceReceipt receipt =
        RunOwnedInitialAdmissionFixtureCapability.parseSourceReceipt(
            validSourceReceiptJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            RUN_ID,
            PROJECT_NAME,
            capability(),
            pins());

    assertThat(receipt.schema())
        .isEqualTo(RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_SCHEMA);
    assertThat(receipt.runId()).isEqualTo(RUN_ID);
    assertThat(receipt.composeProjectName()).isEqualTo(PROJECT_NAME);
    assertThat(receipt.fixtureOperationId()).isEqualTo(OPERATION_ID);
    assertThat(receipt.ownerService())
        .isEqualTo(RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_OWNER_SERVICE);
    assertThat(receipt.sourceEvidence()).isEqualTo(sourceEvidence());
    assertThat(receipt.sourceEvidence().registrationRequestId())
        .isEqualTo(UUID.fromString(OPERATION_ID));
    assertThat(receipt.sourceEvidence().operationId())
        .isNotEqualTo(receipt.sourceEvidence().registrationRequestId());
    assertThat(receipt.launchReadEvidence().schema())
        .isEqualTo(RunOwnedInitialAdmissionLaunchEvidence.SCHEMA);
    assertThat(receipt.launchReadEvidence().gameTemplateId()).isEqualTo(17L);
    assertThat(receipt.launchReadEvidence().publishedReleaseBundle()).isEqualTo(launchBundle());
    assertThat(receipt.launchReadEvidence().versionAssetArtifactState())
        .isEqualTo(launchArtifactState());
    assertThat(receipt.launchReadEvidence().versionState()).isEqualTo(launchVersionState());
    assertThat(receipt.launchReadEvidence().launchDescriptor())
        .isEqualTo(launchDescriptor(sourceEvidence(), launchBundle(), launchVersionState()));
  }

  @Test
  void rejectsOpenDuplicateMismatchedAndMalformedSourceReceiptFields() {
    String json = validSourceReceiptJson();
    assertInvalidSourceReceipt(json.replace("\"schema\":", "\"unknown\":true,\"schema\":"));
    assertInvalidSourceReceipt(json.replace("\"schema\":", "\"schema\":\"wrong\",\"schema\":"));
    assertInvalidSourceReceipt(json.replace(RUN_ID, "different-run"));
    assertInvalidSourceReceipt(json.replace(OPERATION_ID, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    assertInvalidSourceReceipt(json.replace(LEAF_SHA, "c".repeat(64)));
    assertInvalidSourceReceipt(json.replace(CA_SHA, "c".repeat(64)));
    assertInvalidSourceReceipt(json.replace(WORLD_LEAF_SHA, "d".repeat(64)));
    assertInvalidSourceReceipt(
        json.replace(
            RunOwnedInitialAdmissionFixtureCapability.WORLD_MANAGEMENT_URI_SAN,
            "spiffe://firemud/ns/dev/sa/other-service"));
    assertInvalidSourceReceipt(
        json.replace("\"sourceGameRowId\": 13", "\"sourceGameRowId\": \"13\""));
    assertInvalidSourceReceipt(json + " {}\n");
    assertInvalidSourceReceipt(
        json.replace("\"sourceGameTenantKey\": \"7\"", "\"sourceGameTenantKey\": \"07\""));
    assertInvalidSourceReceipt(json.replace("\"worldSlug\": \"demo\"", "\"worldSlug\": \"other\""));
    assertInvalidSourceReceipt(json.substring(0, json.length() - 30));
    assertInvalidSourceReceiptBytes(new byte[] {(byte) 0xc3, (byte) 0x28});
    assertInvalidSourceReceipt(
        json.replace(",\n  \"launchReadEvidence\": " + validLaunchReadEvidenceJson(), ""));
    assertInvalidSourceReceipt(
        json.replace("\"launchReadEvidence\": {", "\"launchReadEvidence\": {\"unknown\":true,"));
    assertInvalidSourceReceipt(
        json.replace(
            "\"launchReadEvidence\": {", "\"launchReadEvidence\": {\"schema\":\"duplicate\","));
    assertInvalidSourceReceipt(
        json.replace("\"gameTemplateId\":17", "\"gameTemplateId\":9223372036854775808"));
    assertInvalidSourceReceipt(json.replace("\"gameTemplateId\":17", "\"gameTemplateId\":17.0"));
    String canonicalBundle = encode(launchBundle().toByteArray());
    String nonCanonicalBundle =
        Base64.getEncoder().withoutPadding().encodeToString(launchBundle().toByteArray());
    if (nonCanonicalBundle.equals(canonicalBundle)) {
      nonCanonicalBundle += "=";
    }
    assertInvalidSourceReceipt(json.replace(canonicalBundle, nonCanonicalBundle));
    assertInvalidSourceReceipt(json.replace(canonicalBundle, encode(new byte[] {10, 5})));
    assertInvalidSourceReceipt(
        validSourceReceiptJson(
            sourceEvidence(),
            launchBundle().toBuilder()
                .setCanonicalTenantId("11111111-1111-4111-8111-111111111111")
                .build()));
    assertInvalidSourceReceipt(
        validSourceReceiptJson(
            sourceEvidence(),
            launchBundle(),
            launchArtifactState().toBuilder().setArtifactStateValue(999).build(),
            launchVersionState()));
    assertInvalidSourceReceipt(
        validSourceReceiptJson(
            sourceEvidence(),
            launchBundle(),
            launchArtifactState(),
            launchVersionState().toBuilder().setVersionStateValue(999).build()));
  }

  @Test
  void rejectsOwnerReadProjectionScopeAndStateMismatches() {
    var unknownField = com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1L).build();
    var unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder().addField(99, unknownField).build();
    var invalidReceipts =
        List.of(
            validSourceReceiptJson().replace("\"gameTemplateId\":17", "\"gameTemplateId\":18"),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle(),
                launchArtifactState().toBuilder().setTenantId("8").build(),
                launchVersionState()),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle(),
                launchArtifactState().toBuilder().setVersionId(12L).build(),
                launchVersionState()),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle(),
                launchArtifactState().toBuilder().setStateEpoch(0L).build(),
                launchVersionState()),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle(),
                launchArtifactState().toBuilder()
                    .setArtifactState(
                        net.firedevops.firemud.gamedesign.v1.ArtifactState
                            .ARTIFACT_STATE_TOMBSTONED)
                    .build(),
                launchVersionState()),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle(),
                launchArtifactState().toBuilder()
                    .setManifestHash("sha256:" + "c".repeat(64))
                    .build(),
                launchVersionState()),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle(),
                launchArtifactState(),
                launchVersionState().toBuilder().setTenantId("8").build()),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle(),
                launchArtifactState(),
                launchVersionState().toBuilder().setVersionId(12L).build()),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle(),
                launchArtifactState(),
                launchVersionState().toBuilder().setVersionStateEpoch(0L).build()),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle().toBuilder().setCanonicalVersionId("not-a-canonical-uuid").build()),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle(),
                launchArtifactState().toBuilder().setUnknownFields(unknownFields).build(),
                launchVersionState()),
            validSourceReceiptJson(
                sourceEvidence(),
                launchBundle(),
                launchArtifactState(),
                launchVersionState().toBuilder().setUnknownFields(unknownFields).build()));

    invalidReceipts.forEach(
        RunOwnedInitialAdmissionFixtureCapabilityTest::assertInvalidSourceReceipt);
  }

  @Test
  void rejectsArtifactSnapshotMissingTheBundlesRequiredManifestKey() {
    var bundle = launchBundle();
    var missingKeys = launchArtifactState().toBuilder().clearExportedManifestAssetKeys().build();
    var validReceipt =
        RunOwnedInitialAdmissionFixtureCapability.parseSourceReceipt(
            validSourceReceiptJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            RUN_ID,
            PROJECT_NAME,
            capability(),
            pins());

    assertThat(validReceipt.launchReadEvidence().publishedReleaseBundle()).isEqualTo(bundle);
    assertThat(bundle.getRequiredManifestAssetKeysList()).containsExactly("manifest.json");
    assertThat(missingKeys.getExportedManifestAssetKeysList()).isEmpty();
    assertInvalidSourceReceipt(
        validSourceReceiptJson(sourceEvidence(), bundle, missingKeys, launchVersionState()));
  }

  @Test
  void rejectsUnknownProtobufFieldsRecursivelyInOwnerReadEvidence() {
    var unknownField = com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1L).build();
    var unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder().addField(99, unknownField).build();
    var nestedUnknownBundle =
        launchBundle().toBuilder()
            .setArtifactDigests(
                0,
                launchBundle().getArtifactDigests(0).toBuilder()
                    .setUnknownFields(unknownFields)
                    .build())
            .build();
    assertInvalidSourceReceipt(validSourceReceiptJson(sourceEvidence(), nestedUnknownBundle));
    var nestedUnknownParticipant =
        launchBundle().toBuilder()
            .addParticipantDigests(
                net.firedevops.firemud.gamedesign.v1.ParticipantDigest.newBuilder()
                    .setUnknownFields(unknownFields)
                    .build())
            .build();
    assertInvalidSourceReceipt(validSourceReceiptJson(sourceEvidence(), nestedUnknownParticipant));
    assertInvalidSourceReceipt(
        validSourceReceiptJson(
            sourceEvidence(), launchBundle().toBuilder().setUnknownFields(unknownFields).build()));
  }

  @Test
  void sourceReceiptReaderRequiresBoundedRegularNonWritableNonSymlinkFile(@TempDir Path directory)
      throws Exception {
    Path writable =
        Files.writeString(directory.resolve("writable-receipt.json"), validSourceReceiptJson());
    assertThatThrownBy(() -> RunOwnedInitialAdmissionFixtureCapability.readSourceReceipt(writable))
        .isInstanceOf(
            RunOwnedInitialAdmissionFixtureCapability.InvalidSourceReceiptException.class);

    Path readOnly = Files.writeString(directory.resolve("receipt.json"), validSourceReceiptJson());
    Files.setPosixFilePermissions(readOnly, Set.of(PosixFilePermission.OWNER_READ));
    assertThat(RunOwnedInitialAdmissionFixtureCapability.readSourceReceipt(readOnly))
        .isEqualTo(validSourceReceiptJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));

    Path tooLarge =
        Files.writeString(directory.resolve("too-large.json"), "x".repeat(16 * 1024 + 1));
    Files.setPosixFilePermissions(tooLarge, Set.of(PosixFilePermission.OWNER_READ));
    assertThatThrownBy(() -> RunOwnedInitialAdmissionFixtureCapability.readSourceReceipt(tooLarge))
        .isInstanceOf(
            RunOwnedInitialAdmissionFixtureCapability.InvalidSourceReceiptException.class);

    Path link = directory.resolve("receipt-link.json");
    Files.createSymbolicLink(link, readOnly.getFileName());
    assertThatThrownBy(() -> RunOwnedInitialAdmissionFixtureCapability.readSourceReceipt(link))
        .isInstanceOf(
            RunOwnedInitialAdmissionFixtureCapability.InvalidSourceReceiptException.class);
  }

  @Test
  void rejectsReceiptReplacedAfterOpenWhileReadIsInProgress(@TempDir Path directory)
      throws Exception {
    byte[] receiptBytes =
        validSourceReceiptJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path receipt = Files.write(directory.resolve("receipt.json"), receiptBytes);
    Path replacement = Files.write(directory.resolve("replacement.json"), receiptBytes);
    Path displaced = directory.resolve("displaced-receipt.json");
    Files.setPosixFilePermissions(receipt, Set.of(PosixFilePermission.OWNER_READ));
    Files.setPosixFilePermissions(replacement, Set.of(PosixFilePermission.OWNER_READ));
    Files.setLastModifiedTime(replacement, Files.getLastModifiedTime(receipt));

    BasicFileAttributes originalAttributes =
        Files.readAttributes(receipt, BasicFileAttributes.class);
    BasicFileAttributes replacementAttributes =
        Files.readAttributes(replacement, BasicFileAttributes.class);
    assertThat(replacementAttributes.size()).isEqualTo(originalAttributes.size());
    assertThat(replacementAttributes.lastModifiedTime())
        .isEqualTo(originalAttributes.lastModifiedTime());
    assertThat(originalAttributes.fileKey()).isNotNull();
    assertThat(replacementAttributes.fileKey()).isNotEqualTo(originalAttributes.fileKey());

    try (SeekableByteChannel openedChannel =
            Files.newByteChannel(receipt, java.nio.file.StandardOpenOption.READ);
        MockedStatic<Files> mockedFiles =
            org.mockito.Mockito.mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      ReceiptReplacementStub.install(mockedFiles, receipt, replacement, displaced, openedChannel);

      assertThatThrownBy(() -> RunOwnedInitialAdmissionFixtureCapability.readSourceReceipt(receipt))
          .isInstanceOf(
              RunOwnedInitialAdmissionFixtureCapability.InvalidSourceReceiptException.class);
    }
  }

  @Test
  void derivesPinsFromActualLeafAndCaCertificatesAndRequiresOneExactUriSan() throws Exception {
    X509Certificate leaf =
        certificate(
            RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN, new byte[] {1}, -1);
    X509Certificate ca = certificate(null, new byte[] {2}, 1);
    PublicKey publicKey = mock(PublicKey.class);
    when(ca.getPublicKey()).thenReturn(publicKey);
    when(leaf.getSubjectAlternativeNames())
        .thenReturn(
            List.of(List.of(6, RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN)));

    CertificatePins pins = RunOwnedInitialAdmissionFixtureCapability.certificatePins(leaf, ca);

    assertThat(pins.leafSha256()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(pins.uriSan())
        .isEqualTo(RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN);
    assertThat(pins.caSha256()).hasSize(64).matches("[0-9a-f]{64}");

    when(leaf.getSubjectAlternativeNames())
        .thenReturn(
            List.of(
                List.of(6, RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN),
                List.of(6, "spiffe://firemud/ns/dev/sa/other-service")));
    assertThatThrownBy(() -> RunOwnedInitialAdmissionFixtureCapability.certificatePins(leaf, ca))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureCapability.InvalidCapabilityException.class);
  }

  private static CertificatePins pins() {
    return new CertificatePins(
        LEAF_SHA, RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN, CA_SHA);
  }

  private static RunOwnedInitialAdmissionFixtureCapability capability() {
    return RunOwnedInitialAdmissionFixtureCapability.parseAndValidate(
        validJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        RUN_ID,
        PROJECT_NAME,
        pins());
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    UUID registrationRequestId = UUID.fromString(OPERATION_ID);
    UUID operationId = UUID.fromString(SOURCE_OPERATION_ID);
    UUID canonicalTenantId = UUID.fromString("ed5d1541-a33e-4f8b-9b54-38c27d76ba6d");
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_TARGET_NAMESPACE,
            registrationRequestId,
            canonicalTenantId,
            "demo",
            "demo",
            "Demo World");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_TARGET_NAMESPACE,
            registrationRequestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            "demo",
            "demo",
            "Demo World",
            13L,
            "7",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        RunOwnedInitialAdmissionFixtureCapability.SOURCE_TARGET_NAMESPACE,
        registrationRequestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        "demo",
        "demo",
        "Demo World",
        13L,
        "7",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static String validSourceReceiptJson() {
    return validSourceReceiptJson(sourceEvidence());
  }

  private static String validSourceReceiptJson(AuthoredWorldSourceEvidence source) {
    return validSourceReceiptJson(source, launchBundle());
  }

  private static String validSourceReceiptJson(
      AuthoredWorldSourceEvidence source,
      net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle bundle) {
    return validSourceReceiptJson(source, bundle, launchArtifactState(), launchVersionState());
  }

  private static String validSourceReceiptJson(
      AuthoredWorldSourceEvidence source,
      net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle bundle,
      net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState artifactState,
      net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot versionState) {
    LaunchDescriptor descriptor = launchDescriptor(source, launchBundle(), launchVersionState());
    String sourceJson =
        """
        {%n\
          "schemaVersion": %d,%n\
          "targetNamespace": "%s",%n\
          "registrationRequestId": "%s",%n\
          "operationId": "%s",%n\
          "requestDigest": "%s",%n\
          "canonicalTenantId": "%s",%n\
          "tenantSlug": "%s",%n\
          "worldSlug": "%s",%n\
          "worldDisplayName": "%s",%n\
          "sourceGameRowId": %d,%n\
          "sourceGameTenantKey": "%s",%n\
          "provenanceKind": "%s",%n\
          "evidenceDigest": "%s"%n\
        }%n\
        """
            .formatted(
                source.schemaVersion(),
                source.targetNamespace(),
                source.registrationRequestId(),
                source.operationId(),
                source.requestDigest(),
                source.canonicalTenantId(),
                source.tenantSlug(),
                source.worldSlug(),
                source.worldDisplayName(),
                source.sourceGameRowId(),
                source.sourceGameTenantKey(),
                source.provenanceKind(),
                source.evidenceDigest());
    return """
        {%n\
          "schema": "%s",%n\
          "runId": "%s",%n\
          "composeProjectName": "%s",%n\
          "fixtureOperationId": "%s",%n\
          "ownerService": "%s",%n\
          "gameSessionUriSan": "%s",%n\
          "gameSessionLeafSha256": "%s",%n\
          "caCertificateSha256": "%s",%n\
          "worldManagementUriSan": "%s",%n\
          "worldManagementLeafSha256": "%s",%n\
          "sourceEvidence": %s,%n\
          "launchReadEvidence": %s%n\
        }%n\
        """
        .formatted(
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_SCHEMA,
            RUN_ID,
            PROJECT_NAME,
            OPERATION_ID,
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_OWNER_SERVICE,
            RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN,
            LEAF_SHA,
            CA_SHA,
            RunOwnedInitialAdmissionFixtureCapability.WORLD_MANAGEMENT_URI_SAN,
            WORLD_LEAF_SHA,
            sourceJson,
            launchReadEvidenceJson(source, bundle, artifactState, versionState, descriptor));
  }

  private static String validLaunchReadEvidenceJson() {
    return launchReadEvidenceJson(
        sourceEvidence(), launchBundle(), launchArtifactState(), launchVersionState());
  }

  private static String launchReadEvidenceJson(
      AuthoredWorldSourceEvidence source,
      net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle bundle,
      net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState artifactState,
      net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot versionState) {
    return launchReadEvidenceJson(
        source,
        bundle,
        artifactState,
        versionState,
        launchDescriptor(source, bundle, versionState));
  }

  private static String launchReadEvidenceJson(
      AuthoredWorldSourceEvidence source,
      net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle bundle,
      net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState artifactState,
      net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot versionState,
      LaunchDescriptor descriptor) {
    return """
        {"schema":"%s","gameTemplateId":17,"publishedReleaseBundleBase64":"%s","versionAssetArtifactStateBase64":"%s","versionStateBase64":"%s","launchDescriptorBase64":"%s"}%n\
        """
        .formatted(
            RunOwnedInitialAdmissionLaunchEvidence.SCHEMA,
            encode(bundle.toByteArray()),
            encode(artifactState.toByteArray()),
            encode(versionState.toByteArray()),
            encode(descriptor.toByteArray()));
  }

  private static LaunchDescriptor launchDescriptor(
      AuthoredWorldSourceEvidence source,
      net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle bundle,
      net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot versionState) {
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_TARGET_NAMESPACE,
            OPERATION_ID,
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            17L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "launch-descriptor-fixture-11",
            bundle.getVersionId(),
            false,
            null,
            "{}",
            bundle.getGenerationConfigRevision(),
            versionState.getVersionStateEpoch(),
            bundle.getId(),
            bundle.getPublishedReleaseBundleRef(),
            false,
            null);
    return AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(evidence);
  }

  private static net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle launchBundle() {
    String canonicalTenantId = sourceEvidence().canonicalTenantId().toString();
    String artifactDigest = "sha256:" + "b".repeat(64);
    return net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle.newBuilder()
        .setId(77L)
        .setVersionId(11L)
        .setVersionNumber(1)
        .setAttestationSchemaVersion("v1")
        .setPublishWorkflowId("publish-11")
        .setManifestHash("sha256:" + "a".repeat(64))
        .addRequiredManifestAssetKeys("manifest.json")
        .setGenerationConfigRevision("genrev-11")
        .setPublishedAt("2026-04-15T10:00:00")
        .setPublishedReleaseBundleRef("owner-release-ref-11")
        .setCanonicalTenantId(canonicalTenantId)
        .setCanonicalVersionId("10000000-0000-4000-8000-000000000011")
        .setManifestSchemaVersion(1)
        .addArtifactDigests(
            net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest.newBuilder()
                .setUsageKey("manifest.json")
                .setArtifactKind("manifest")
                .setImmutableObjectKey(
                    "artifacts/sha256/" + artifactDigest.substring("sha256:".length()))
                .setContentDigest(artifactDigest)
                .setContentType("application/json")
                .setArtifactSchemaVersion(1)
                .build())
        .build();
  }

  private static net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState
      launchArtifactState() {
    return net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState.newBuilder()
        .setTenantId("7")
        .setVersionId(11L)
        .setArtifactState(
            net.firedevops.firemud.gamedesign.v1.ArtifactState.ARTIFACT_STATE_PUBLISHED)
        .setStateEpoch(2L)
        .setManifestHash("sha256:" + "a".repeat(64))
        .setUpdatedAt("2026-04-15T10:00:00")
        .addExportedManifestAssetKeys("manifest.json")
        .setExportedVersionNumber(1)
        .build();
  }

  private static net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot launchVersionState() {
    return net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot.newBuilder()
        .setTenantId("7")
        .setVersionId(11L)
        .setVersionState(
            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                .VERSION_LIFECYCLE_STATE_PUBLISHED)
        .setVersionStateEpoch(77L)
        .setUpdatedAt("2026-04-15T10:00:00")
        .build();
  }

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static String validJson() {
    return """
        {%n\
          "schema": "%s",%n\
          "runId": "%s",%n\
          "composeProjectName": "%s",%n\
          "operationId": "%s",%n\
          "tenantId": 7,%n\
          "gameTemplateId": 17,%n\
          "ownerAccountId": 27,%n\
          "worldSlug": "demo",%n\
          "worldDisplayName": "Demo World",%n\
          "realmSlug": "production",%n\
          "realmDisplayName": "Live Realm",%n\
          "visible": true,%n\
          "publicProductionRealm": true,%n\
          "requiresCharacterSelection": false,%n\
          "stateScope": "SHARED",%n\
          "characterCreationPolicy": "ALLOW_NEW",%n\
          "gameSessionLeafSha256": "%s",%n\
          "gameSessionUriSan": "%s",%n\
          "caCertificateSha256": "%s",%n\
          "worldManagementUriSan": "%s",%n\
          "worldManagementLeafSha256": "%s"%n\
        }%n\
        """
        .formatted(
            RunOwnedInitialAdmissionFixtureCapability.CAPABILITY_SCHEMA,
            RUN_ID,
            PROJECT_NAME,
            OPERATION_ID,
            LEAF_SHA,
            RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN,
            CA_SHA,
            RunOwnedInitialAdmissionFixtureCapability.WORLD_MANAGEMENT_URI_SAN,
            WORLD_LEAF_SHA);
  }

  private static X509Certificate certificate(String san, byte[] der, int basicConstraints)
      throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getBasicConstraints()).thenReturn(basicConstraints);
    when(certificate.getEncoded()).thenReturn(der);
    when(certificate.getPublicKey()).thenReturn(mock(PublicKey.class));
    if (san == null) {
      when(certificate.getSubjectAlternativeNames()).thenReturn(List.of());
    } else {
      when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, san)));
    }
    return certificate;
  }

  private static void assertInvalid(String json) {
    assertThatThrownBy(
            () ->
                RunOwnedInitialAdmissionFixtureCapability.parseAndValidate(
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    RUN_ID,
                    PROJECT_NAME,
                    pins()))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureCapability.InvalidCapabilityException.class);
  }

  private static void assertInvalidSourceReceipt(String json) {
    assertInvalidSourceReceiptBytes(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void assertInvalidSourceReceiptBytes(byte[] bytes) {
    assertThatThrownBy(
            () ->
                RunOwnedInitialAdmissionFixtureCapability.parseSourceReceipt(
                    bytes, RUN_ID, PROJECT_NAME, capability(), pins()))
        .isInstanceOf(
            RunOwnedInitialAdmissionFixtureCapability.InvalidSourceReceiptException.class);
  }

  @SuppressFBWarnings(
      value = "OS_OPEN_STREAM",
      justification =
          "Mockito intercepts the Files.newByteChannel invocation below solely for static-stub"
              + " recording; it does not open a channel. The answer returns the channel already"
              + " opened by the test's try-with-resources.")
  private static final class ReceiptReplacementStub {
    private static void install(
        MockedStatic<Files> mockedFiles,
        Path receipt,
        Path replacement,
        Path displaced,
        SeekableByteChannel openedChannel) {
      mockedFiles
          .when(
              () ->
                  Files.newByteChannel(
                      org.mockito.ArgumentMatchers.eq(receipt),
                      org.mockito.ArgumentMatchers.any(java.nio.file.OpenOption[].class)))
          .thenAnswer(
              new Answer<>() {
                @Override
                public SeekableByteChannel answer(InvocationOnMock invocation) throws Throwable {
                  Files.move(receipt, displaced, StandardCopyOption.ATOMIC_MOVE);
                  Files.move(replacement, receipt, StandardCopyOption.ATOMIC_MOVE);
                  return openedChannel;
                }
              });
    }
  }
}
