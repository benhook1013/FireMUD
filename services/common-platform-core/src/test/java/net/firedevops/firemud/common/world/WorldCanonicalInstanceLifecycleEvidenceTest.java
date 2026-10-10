package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleResponse;
import org.junit.jupiter.api.Test;

class WorldCanonicalInstanceLifecycleEvidenceTest {
  private static final UUID NIL = new UUID(0L, 0L);

  @Test
  void completeOwnerCaptureAndExactRequestEchoRoundTrip() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence expected = evidence();
    Request request = expected.request();

    assertThat(Request.fromStored(request.canonicalBytes())).isEqualTo(request);
    var wireRequest = WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(request);
    assertThat(WorldCanonicalInstanceLifecycleGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(request);

    var firstResponse = WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(request, expected);
    var retryResponse = WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(request, expected);
    assertThat(retryResponse).isEqualTo(firstResponse);
    var decoded = WorldCanonicalInstanceLifecycleGrpcCodec.fromResponse(request, firstResponse);
    var reread = WorldCanonicalInstanceLifecycleEvidence.fromStored(decoded.canonicalBytes());
    assertThat(decoded.canonicalBytes()).containsExactly(expected.canonicalBytes());
    assertThat(reread).isEqualTo(expected);
    assertThat(reread.hashCode()).isEqualTo(expected.hashCode());
    assertThat(reread.operationalRegionAssignments())
        .containsExactlyEntriesOf(expected.operationalRegionAssignments());
  }

  @Test
  void rejectsMalformedOperationalRegionAssignmentMapsAndFreezesCallerInput() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    UUID canonicalRegionId = uuid("abcdefab-cdef-4abc-8def-abcdefabcdef");
    UUID operationalRegionId = uuid("22222222-2222-4222-8222-222222222222");
    Map<UUID, UUID> mutableAssignments = new java.util.LinkedHashMap<>();
    mutableAssignments.put(canonicalRegionId, operationalRegionId);
    var frozen =
        new WorldCanonicalInstanceLifecycleEvidence(
            evidence.request(),
            evidence.launchBinding(),
            evidence.startLocation(),
            evidence.runtimeRoomInstanceId(),
            evidence.lifecycleStatus(),
            evidence.lifecycleEpoch(),
            evidence.rowVersion(),
            evidence.captureId(),
            evidence.graphSha256(),
            evidence.preparationInputDigest(),
            mutableAssignments);
    mutableAssignments.clear();
    assertThat(frozen.operationalRegionAssignments())
        .containsExactlyEntriesOf(Map.of(canonicalRegionId, operationalRegionId));
    assertThatThrownBy(
            () -> frozen.operationalRegionAssignments().put(canonicalRegionId, operationalRegionId))
        .isInstanceOf(UnsupportedOperationException.class);

    UUID secondCanonicalRegionId = uuid("44444444-4444-4444-8444-444444444444");
    UUID secondOperationalRegionId = uuid("55555555-5555-4555-8555-555555555555");
    Map<UUID, UUID> forward = new java.util.LinkedHashMap<>();
    forward.put(canonicalRegionId, operationalRegionId);
    forward.put(secondCanonicalRegionId, secondOperationalRegionId);
    Map<UUID, UUID> reverse = new java.util.LinkedHashMap<>();
    reverse.put(secondCanonicalRegionId, secondOperationalRegionId);
    reverse.put(canonicalRegionId, operationalRegionId);
    assertThat(withAssignments(evidence, forward).canonicalBytes())
        .containsExactly(withAssignments(evidence, reverse).canonicalBytes());

    String stored = new String(frozen.canonicalBytes(), StandardCharsets.UTF_8);
    String assignmentField =
        "\"operationalRegionAssignments\":{\""
            + canonicalRegionId
            + "\":\""
            + operationalRegionId
            + "\"}";
    assertThat(stored).contains(assignmentField);
    String legacyShape = stored.replace("," + assignmentField, "");
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleEvidence.fromStored(
                    legacyShape.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing or unsupported fields");
    assertInvalidStoredAssignments(stored, assignmentField, "{}", "nonempty");
    assertInvalidStoredAssignments(
        stored,
        assignmentField,
        "{\"00000000-0000-0000-0000-000000000000\":\"" + operationalRegionId + "\"}",
        "non-nil UUID");
    assertInvalidStoredAssignments(
        stored,
        assignmentField,
        "{\"" + canonicalRegionId + "\":\"00000000-0000-0000-0000-000000000000\"}",
        "non-nil UUID");
    assertInvalidStoredAssignments(
        stored,
        assignmentField,
        "{\"" + canonicalRegionId + "\":\"" + canonicalRegionId + "\"}",
        "must be distinct");
    assertInvalidStoredAssignments(
        stored,
        assignmentField,
        "{\"" + canonicalRegionId.toString().toUpperCase() + "\":\"" + operationalRegionId + "\"}",
        "canonical non-nil UUID");
    assertInvalidStoredAssignments(
        stored,
        assignmentField,
        "{\""
            + canonicalRegionId
            + "\":\""
            + operationalRegionId
            + "\",\""
            + canonicalRegionId
            + "\":\""
            + uuid("33333333-3333-4333-8333-333333333333")
            + "\"}",
        "invalid");
    assertInvalidStoredAssignments(
        stored,
        assignmentField,
        "{\""
            + canonicalRegionId
            + "\":\""
            + operationalRegionId
            + "\",\""
            + uuid("44444444-4444-4444-8444-444444444444")
            + "\":\""
            + operationalRegionId
            + "\"}",
        "duplicate operational region identities");
  }

  @Test
  void rejectsChangedCanonicalIdentitiesAndEveryExpectedBindingDigest() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    Request request = evidence.request();
    var changedInstance =
        copyRequest(
            request,
            request.readRequestId(),
            request.canonicalTenantId(),
            request.worldSlug(),
            uuid("99999999-9999-4999-8999-999999999999"),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            request.controlPlaneRequestId(),
            request.canonicalVersionId(),
            request.expectedDescriptorRequestDigest(),
            request.expectedDescriptorResultDigest(),
            request.expectedReleaseAttestationDigest());
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleGrpcCodec.fromResponse(
                    changedInstance,
                    WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(request, evidence)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed exact read request");

    assertRejectedRequest(
        evidence,
        copyRequest(
            request,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "sha256:" + "1".repeat(64),
            null,
            null),
        "complete launch binding");
    assertRejectedRequest(
        evidence,
        copyRequest(
            request,
            null,
            uuid("66666666-6666-4666-8666-666666666666"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null),
        "complete launch binding");
    assertRejectedRequest(
        evidence,
        copyRequest(
            request, null, null, "another-world", null, null, null, null, null, null, null, null),
        "complete launch binding");
    assertRejectedRequest(
        evidence,
        copyRequest(
            request,
            null,
            null,
            null,
            null,
            null,
            null,
            "other-control-request",
            null,
            null,
            null,
            null),
        "complete launch binding");
    assertRejectedRequest(
        evidence,
        copyRequest(
            request,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            uuid("88888888-8888-4888-8888-888888888888"),
            null,
            null,
            null),
        "complete launch binding");
    assertRejectedRequest(
        evidence,
        copyRequest(
            request,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "sha256:" + "2".repeat(64),
            null,
            null),
        "complete launch binding");
    assertRejectedRequest(
        evidence,
        copyRequest(
            request,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "sha256:" + "3".repeat(64),
            null),
        "complete launch binding");
    assertRejectedRequest(
        evidence,
        copyRequest(
            request,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "sha256:" + "4".repeat(64)),
        "complete launch binding");

    var changedSelector =
        new RoomTemplateRef(
            evidence.startLocation().tenantId(),
            evidence.startLocation().versionId(),
            uuid("66666666-6666-4666-8666-666666666666"));
    assertThatThrownBy(
            () ->
                with(
                    evidence,
                    request,
                    evidence.launchBinding(),
                    changedSelector,
                    evidence.runtimeRoomInstanceId(),
                    evidence.lifecycleStatus(),
                    evidence.lifecycleEpoch(),
                    evidence.rowVersion(),
                    evidence.captureId(),
                    evidence.graphSha256(),
                    evidence.preparationInputDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("release selector");
    assertThatThrownBy(
            () ->
                with(
                    evidence,
                    request,
                    evidence.launchBinding(),
                    evidence.startLocation(),
                    evidence.runtimeRoomInstanceId(),
                    evidence.lifecycleStatus(),
                    evidence.lifecycleEpoch(),
                    evidence.rowVersion(),
                    evidence.captureId(),
                    "f".repeat(64),
                    evidence.preparationInputDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("graph digest");
  }

  @Test
  void rejectsUnselectedV1ReleaseAndMalformedLifecycleCountersOrDigests() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    var request = evidence.request();
    var descriptor = evidence.launchBinding().descriptor();
    var release = evidence.launchBinding().releaseAttestation();
    // Independent retained-v1 fixture; its GD digest is not the selected-v2 content hash.
    var v1Participants =
        release.participantDigests().stream()
            .map(
                participant ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionIdPresent(),
                        participant.baseVersionId(),
                        participant.appliedCommitId(),
                        "GAME_DESIGN_CONTROL_PLANE".equals(participant.participantKey())
                            ? "e".repeat(64)
                            : participant.contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participant.participantKey(),
                            AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION),
                        participant.abilitySchemaDigestPresent(),
                        participant.abilitySchemaDigest()))
            .toList();
    var v1Release =
        AuthoredWorldReleaseAttestationEvidence.create(
            release.targetNamespace(),
            release.descriptorResultDigest(),
            release.canonicalTenantId(),
            release.canonicalVersionId(),
            release.worldSlug(),
            release.authoredWorldSourceOperationId(),
            release.authoredWorldSourceEvidenceDigest(),
            release.launchDescriptorId(),
            release.publishedReleaseBundleRef(),
            release.versionStateEpoch(),
            release.publishWorkflowId(),
            release.commitId(),
            v1Participants,
            release.manifestHash(),
            release.manifestSchemaVersion(),
            release.requiredManifestAssetKeys(),
            release.artifactDigests(),
            release.commandDefinitions(),
            release.generationConfigRevision());
    var v1Request =
        copyRequest(
            request,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            v1Release.evidenceDigest());
    assertThatThrownBy(
            () ->
                with(
                    evidence,
                    v1Request,
                    new CompleteLaunchBindingEvidence(descriptor, v1Release),
                    evidence.startLocation(),
                    evidence.runtimeRoomInstanceId(),
                    evidence.lifecycleStatus(),
                    evidence.lifecycleEpoch(),
                    evidence.rowVersion(),
                    evidence.captureId(),
                    evidence.graphSha256(),
                    evidence.preparationInputDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete selected-release selector evidence");

    assertInvalid(evidence, evidence.runtimeRoomInstanceId(), evidence.lifecycleEpoch(), -1);
    assertInvalid(evidence, evidence.runtimeRoomInstanceId(), 0, evidence.rowVersion());
    assertInvalid(evidence, 0, evidence.lifecycleEpoch(), evidence.rowVersion());
    assertThatThrownBy(
            () ->
                with(
                    evidence,
                    evidence.request(),
                    evidence.launchBinding(),
                    evidence.startLocation(),
                    evidence.runtimeRoomInstanceId(),
                    evidence.lifecycleStatus(),
                    evidence.lifecycleEpoch(),
                    evidence.rowVersion(),
                    NIL,
                    evidence.graphSha256(),
                    evidence.preparationInputDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("captureId");
    assertThatThrownBy(
            () ->
                with(
                    evidence,
                    evidence.request(),
                    evidence.launchBinding(),
                    evidence.startLocation(),
                    evidence.runtimeRoomInstanceId(),
                    evidence.lifecycleStatus(),
                    evidence.lifecycleEpoch(),
                    evidence.rowVersion(),
                    evidence.captureId(),
                    "A".repeat(64),
                    evidence.preparationInputDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("graphSha256");
  }

  @Test
  void requestOnlyAcceptsProvedSharedPublicProductionIdentity() throws Exception {
    Request request = evidence().request();
    assertThatThrownBy(
            () ->
                copyRequest(
                    request, NIL, null, null, null, null, null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("readRequestId");
    assertThatThrownBy(
            () ->
                copyRequest(
                    request,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "ISOLATED",
                    null,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SHARED scope only");
    assertThatThrownBy(
            () ->
                new Request(
                    request.schemaVersion(),
                    request.readRequestId(),
                    request.targetNamespace(),
                    request.canonicalTenantId(),
                    request.worldSlug(),
                    request.canonicalGameInstanceId(),
                    request.playableStateNamespaceId(),
                    request.playableStateScope(),
                    false,
                    request.controlPlaneRequestId(),
                    request.canonicalVersionId(),
                    request.expectedDescriptorRequestDigest(),
                    request.expectedDescriptorResultDigest(),
                    request.expectedReleaseAttestationDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("public-production evidence");
    assertThatThrownBy(
            () ->
                copyRequest(
                    request,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "not-a-digest",
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedDescriptorRequestDigest");
  }

  @Test
  void preservesLongPrecisionAndRejectsOverflowOrNoncanonicalDecimalStrings() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence expected = evidence();
    long beyondJavaScriptInteger = 9_007_199_254_740_993L;
    WorldCanonicalInstanceLifecycleEvidence precise =
        with(
            expected,
            expected.request(),
            expected.launchBinding(),
            expected.startLocation(),
            beyondJavaScriptInteger,
            expected.lifecycleStatus(),
            beyondJavaScriptInteger + 2,
            beyondJavaScriptInteger + 4,
            expected.captureId(),
            expected.graphSha256(),
            expected.preparationInputDigest());
    assertThat(WorldCanonicalInstanceLifecycleEvidence.fromStored(precise.canonicalBytes()))
        .isEqualTo(precise);

    WorldCanonicalInstanceLifecycleEvidence maximum =
        with(
            expected,
            expected.request(),
            expected.launchBinding(),
            expected.startLocation(),
            Long.MAX_VALUE,
            expected.lifecycleStatus(),
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            expected.captureId(),
            expected.graphSha256(),
            expected.preparationInputDigest());
    byte[] maxBytes = maximum.canonicalBytes();
    assertThat(WorldCanonicalInstanceLifecycleEvidence.fromStored(maxBytes)).isEqualTo(maximum);
    String maxJson = new String(maxBytes, StandardCharsets.UTF_8);
    String overflow =
        maxJson.replace(
            "\"runtimeRoomInstanceId\":\"" + Long.MAX_VALUE + "\"",
            "\"runtimeRoomInstanceId\":\"9223372036854775808\"");
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleEvidence.fromStored(
                    overflow.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("supported integer range");
    String negative =
        maxJson.replace(
            "\"lifecycleEpoch\":\"" + Long.MAX_VALUE + "\"", "\"lifecycleEpoch\":\"-1\"");
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleEvidence.fromStored(
                    negative.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical positive decimal");
    String leadingZero =
        maxJson.replace("\"rowVersion\":\"" + Long.MAX_VALUE + "\"", "\"rowVersion\":\"01\"");
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleEvidence.fromStored(
                    leadingZero.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical nonnegative decimal");
  }

  @Test
  void rejectsClosedJsonAndProtobufUnknownOrDuplicateFields() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    String stored = new String(evidence.canonicalBytes(), StandardCharsets.UTF_8);
    String unknownField = stored.substring(0, 1) + "\"unsupported\":true," + stored.substring(1);
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleEvidence.fromStored(
                    unknownField.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    String duplicate =
        stored.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleEvidence.fromStored(
                    duplicate.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Request.fromStored(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strict UTF-8");
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleEvidence.fromStored(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strict UTF-8");

    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    ReadWorldCanonicalInstanceLifecycleRequest request =
        WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(evidence.request()).toBuilder()
            .setUnknownFields(unknown)
            .build();
    assertThatThrownBy(() -> WorldCanonicalInstanceLifecycleGrpcCodec.fromRequest(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    ReadWorldCanonicalInstanceLifecycleResponse response =
        WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(evidence.request(), evidence)
            .toBuilder()
            .setUnknownFields(unknown)
            .build();
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleGrpcCodec.fromResponse(evidence.request(), response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleGrpcCodec.fromResponse(
                    evidence.request(),
                    ReadWorldCanonicalInstanceLifecycleResponse.newBuilder()
                        .setCanonicalResponseBytes(ByteString.EMPTY)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("required");
  }

  private static void assertRejectedRequest(
      WorldCanonicalInstanceLifecycleEvidence evidence, Request changed, String message) {
    assertThatThrownBy(
            () ->
                with(
                    evidence,
                    changed,
                    evidence.launchBinding(),
                    evidence.startLocation(),
                    evidence.runtimeRoomInstanceId(),
                    evidence.lifecycleStatus(),
                    evidence.lifecycleEpoch(),
                    evidence.rowVersion(),
                    evidence.captureId(),
                    evidence.graphSha256(),
                    evidence.preparationInputDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  private static void assertInvalid(
      WorldCanonicalInstanceLifecycleEvidence evidence,
      long runtimeRoomInstanceId,
      long lifecycleEpoch,
      long rowVersion) {
    assertThatThrownBy(
            () ->
                with(
                    evidence,
                    evidence.request(),
                    evidence.launchBinding(),
                    evidence.startLocation(),
                    runtimeRoomInstanceId,
                    evidence.lifecycleStatus(),
                    lifecycleEpoch,
                    rowVersion,
                    evidence.captureId(),
                    evidence.graphSha256(),
                    evidence.preparationInputDigest()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorldCanonicalInstanceLifecycleEvidence with(
      WorldCanonicalInstanceLifecycleEvidence source,
      Request request,
      CompleteLaunchBindingEvidence launchBinding,
      RoomTemplateRef startLocation,
      long runtimeRoomInstanceId,
      String lifecycleStatus,
      long lifecycleEpoch,
      long rowVersion,
      UUID captureId,
      String graphSha256,
      String preparationInputDigest) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        launchBinding,
        startLocation,
        runtimeRoomInstanceId,
        lifecycleStatus,
        lifecycleEpoch,
        rowVersion,
        captureId,
        graphSha256,
        preparationInputDigest,
        source.operationalRegionAssignments());
  }

  private static Request copyRequest(
      Request source,
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      String controlPlaneRequestId,
      UUID canonicalVersionId,
      String expectedDescriptorRequestDigest,
      String expectedDescriptorResultDigest,
      String expectedReleaseAttestationDigest) {
    return new Request(
        Request.SCHEMA_VERSION,
        readRequestId == null ? source.readRequestId() : readRequestId,
        source.targetNamespace(),
        canonicalTenantId == null ? source.canonicalTenantId() : canonicalTenantId,
        worldSlug == null ? source.worldSlug() : worldSlug,
        canonicalGameInstanceId == null
            ? source.canonicalGameInstanceId()
            : canonicalGameInstanceId,
        playableStateNamespaceId == null
            ? source.playableStateNamespaceId()
            : playableStateNamespaceId,
        playableStateScope == null ? source.playableStateScope() : playableStateScope,
        source.publicProduction(),
        controlPlaneRequestId == null ? source.controlPlaneRequestId() : controlPlaneRequestId,
        canonicalVersionId == null ? source.canonicalVersionId() : canonicalVersionId,
        expectedDescriptorRequestDigest == null
            ? source.expectedDescriptorRequestDigest()
            : expectedDescriptorRequestDigest,
        expectedDescriptorResultDigest == null
            ? source.expectedDescriptorResultDigest()
            : expectedDescriptorResultDigest,
        expectedReleaseAttestationDigest == null
            ? source.expectedReleaseAttestationDigest()
            : expectedReleaseAttestationDigest);
  }

  private static WorldCanonicalInstanceLifecycleEvidence evidence() throws Exception {
    return AuthoringFixtures.lifecycleEvidence("PREPARING", 3L);
  }

  private static void assertInvalidStoredAssignments(
      String stored, String assignmentField, String replacement, String message) {
    String malformed =
        stored.replace(assignmentField, "\"operationalRegionAssignments\":" + replacement);
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceLifecycleEvidence.fromStored(
                    malformed.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  private static WorldCanonicalInstanceLifecycleEvidence withAssignments(
      WorldCanonicalInstanceLifecycleEvidence source, Map<UUID, UUID> assignments) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        source.request(),
        source.launchBinding(),
        source.startLocation(),
        source.runtimeRoomInstanceId(),
        source.lifecycleStatus(),
        source.lifecycleEpoch(),
        source.rowVersion(),
        source.captureId(),
        source.graphSha256(),
        source.preparationInputDigest(),
        assignments);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
