package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
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
            release.participantDigests(),
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
        .hasMessageContaining("selector/v2");

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
        preparationInputDigest);
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
    WorldPublishedStartLocationEvidence selector =
        WorldPublishedStartLocationGrpcCodecTest.evidence();
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            selector.request().targetNamespace(),
            "world-lifecycle-control-request",
            selector.request().canonicalTenantId(),
            "synthetic-world",
            uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "sha256:" + "a".repeat(64),
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "canonical-instance-launch-descriptor",
            42L,
            false,
            null,
            "{}",
            "generation-revision",
            9L,
            7L,
            "release-bundle",
            false,
            null);
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selector.request().appliedCommitId(),
                        selector.request().contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            selector.request().canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            selector.request().publishWorkflowId(),
            selector.request().appliedCommitId(),
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision(),
            selector);
    CompleteLaunchBindingEvidence binding = new CompleteLaunchBindingEvidence(descriptor, release);
    WorldDraftStartLocationEvidence selectorReceipt =
        WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
    RoomTemplateRef startLocation = selectorReceipt.startLocation();
    Request request =
        new Request(
            Request.SCHEMA_VERSION,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222"),
            "SHARED",
            true,
            descriptor.controlPlaneRequestId(),
            release.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        binding,
        startLocation,
        1042L,
        "PREPARING",
        3L,
        0L,
        uuid("33333333-3333-4333-8333-333333333333"),
        selectorReceipt.graphDigest().substring("sha256:".length()),
        "sha256:" + "e".repeat(64));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
