package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import org.junit.jupiter.api.Test;

class AuthoredWorldLaunchDescriptorGrpcCodecTest {
  private static final UUID TENANT_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final UUID WRONG_READ_REQUEST_ID =
      UUID.fromString("42345678-1234-4234-8234-123456789abc");

  @Test
  void resolvesAndReadsBackSharedDigestVectorWithExactOptionalPresence() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("test", "copper-coast", false);
    net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence evidence =
        evidence(request);

    assertThat(request.requestDigest())
        .isEqualTo("sha256:ac6fc51a4b81185a33cfe9c84df69e05537a69415a6d8b8fb478a946221e7111");
    assertThat(evidence.resultDigest())
        .isEqualTo("sha256:701c68797b898b696cac24d48dcff4fe25c0856f15965ff7e8b219f7c57acd11");

    var resolveWire = AuthoredWorldLaunchDescriptorGrpcCodec.toResolveRequest(request);
    assertThat(resolveWire.hasRequestedScriptPatchVersion()).isTrue();
    assertThat(resolveWire.hasSourceVersionId()).isFalse();
    assertThat(resolveWire.hasTargetVersionId()).isFalse();
    assertThat(resolveWire.hasRequestedRuntimeFlagsJson()).isFalse();
    assertThat(
            AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                request, resolveResponse(evidence)))
        .isEqualTo(evidence);

    var emptyFlagsRequest = request("test", "copper-coast", true);
    var emptyFlagsWire = AuthoredWorldLaunchDescriptorGrpcCodec.toResolveRequest(emptyFlagsRequest);
    assertThat(emptyFlagsWire.hasRequestedRuntimeFlagsJson()).isTrue();
    assertThat(emptyFlagsWire.getRequestedRuntimeFlagsJson()).isEmpty();
    var emptyFlagsEvidence = evidence(emptyFlagsRequest);
    var emptyFlagsReadback =
        AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
            emptyFlagsRequest, resolveResponse(emptyFlagsEvidence));
    assertThat(emptyFlagsReadback.requestedRuntimeFlagsJsonPresent()).isTrue();
    assertThat(emptyFlagsReadback.requestedRuntimeFlagsJson()).isEmpty();

    var getRequest =
        new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
            READ_REQUEST_ID, request, evidence.resultDigest());
    var getWire = AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(getRequest);
    assertThat(getWire.getRequestId()).isEqualTo(READ_REQUEST_ID.toString());
    assertThat(getWire.getExpectedRequestDigest()).isEqualTo(request.requestDigest());
    assertThat(getWire.getExpectedResultDigest()).isEqualTo(evidence.resultDigest());
    assertThat(
            AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                getRequest, getResponse(READ_REQUEST_ID.toString(), evidence)))
        .isEqualTo(evidence);
  }

  @Test
  void rejectsAChangedRequestFieldAndWrongNamespace() {
    var expected = request("test", "copper-coast", false);
    var changedWorld = evidence(request("test", "copper-shore", false));
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    expected, resolveResponse(changedWorld)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact authored-world resolve request");

    var otherNamespace = evidence(request("other", "copper-coast", false));
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    expected, resolveResponse(otherNamespace)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact authored-world resolve request");
  }

  @Test
  void rejectsEveryChangedFlatDescriptorDuplicate() {
    var evidence = evidence(request("test", "copper-coast", false));
    LaunchDescriptor descriptor = descriptor(evidence);

    assertFlatDuplicateRejected(descriptor.toBuilder().setLaunchDescriptorId("other").build());
    assertFlatDuplicateRejected(descriptor.toBuilder().setCanonicalTenantId("other").build());
    assertFlatDuplicateRejected(descriptor.toBuilder().setGameTemplateId(20L).build());
    assertFlatDuplicateRejected(descriptor.toBuilder().setControlPlaneRequestId("other").build());
    assertFlatDuplicateRejected(descriptor.toBuilder().setVersionId(20L).build());
    assertFlatDuplicateRejected(descriptor.toBuilder().setScriptPatchVersion("other").build());
    assertFlatDuplicateRejected(descriptor.toBuilder().setRuntimeFlagsJson("other").build());
    assertFlatDuplicateRejected(
        descriptor.toBuilder().setGenerationConfigRevision("other").build());
    assertFlatDuplicateRejected(descriptor.toBuilder().setVersionStateEpoch(20L).build());
    assertFlatDuplicateRejected(descriptor.toBuilder().setReleaseBundleId(20L).build());
    assertFlatDuplicateRejected(
        descriptor.toBuilder().setPublishedReleaseBundleRef("other").build());
    assertFlatDuplicateRejected(descriptor.toBuilder().setRemapSetId("other").build());
  }

  @Test
  void rejectsMissingEvidenceUnknownFieldsAndUnsupportedSchema() {
    var request = request("test", "copper-coast", false);
    var evidence = evidence(request);
    LaunchDescriptor withoutBinding =
        descriptor(evidence).toBuilder().clearAuthoredWorldBinding().build();
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    request,
                    ResolveLaunchDescriptorResponse.newBuilder()
                        .setLaunchDescriptor(withoutBinding)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no authored-world binding");

    var unknown = unknownFields();
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    request,
                    ResolveLaunchDescriptorResponse.newBuilder()
                        .setLaunchDescriptor(descriptor(evidence))
                        .setUnknownFields(unknown)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    request,
                    ResolveLaunchDescriptorResponse.newBuilder()
                        .setLaunchDescriptor(
                            descriptor(evidence).toBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");

    var getRequest =
        new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
            READ_REQUEST_ID, request, evidence.resultDigest());
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    getRequest,
                    GetLaunchDescriptorResponse.newBuilder()
                        .setRequestId(READ_REQUEST_ID.toString())
                        .setLaunchDescriptor(descriptor(evidence))
                        .setUnknownFields(unknown)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    request,
                    ResolveLaunchDescriptorResponse.newBuilder()
                        .setLaunchDescriptor(
                            descriptor(evidence).toBuilder()
                                .setAuthoredWorldBinding(
                                    descriptor(evidence).getAuthoredWorldBinding().toBuilder()
                                        .setUnknownFields(unknown)))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");

    var unsupportedSchema =
        descriptor(evidence).toBuilder()
            .setAuthoredWorldBinding(
                descriptor(evidence).getAuthoredWorldBinding().toBuilder().setSchemaVersion(2))
            .build();
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    request,
                    ResolveLaunchDescriptorResponse.newBuilder()
                        .setLaunchDescriptor(unsupportedSchema)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  void rejectsConflictingDigestsAndNonmatchingReadEchoOrResult() {
    var request = request("test", "copper-coast", false);
    var evidence = evidence(request);
    var invalidRequestDigest =
        descriptor(evidence).toBuilder()
            .setAuthoredWorldBinding(
                descriptor(evidence).getAuthoredWorldBinding().toBuilder()
                    .setRequestDigest("sha256:" + "0".repeat(64)))
            .build();
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    request,
                    ResolveLaunchDescriptorResponse.newBuilder()
                        .setLaunchDescriptor(invalidRequestDigest)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid");

    var invalidResultDigest =
        descriptor(evidence).toBuilder()
            .setAuthoredWorldBinding(
                descriptor(evidence).getAuthoredWorldBinding().toBuilder()
                    .setResultDigest("sha256:" + "0".repeat(64)))
            .build();
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    request,
                    ResolveLaunchDescriptorResponse.newBuilder()
                        .setLaunchDescriptor(invalidResultDigest)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid");

    var getRequest =
        new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
            READ_REQUEST_ID, request, evidence.resultDigest());
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    getRequest, getResponse(WRONG_READ_REQUEST_ID.toString(), evidence)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("echo changed");
    var wrongResult =
        new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
            READ_REQUEST_ID, request, "sha256:" + "0".repeat(64));
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    wrongResult, getResponse(READ_REQUEST_ID.toString(), evidence)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("result digest");
  }

  @Test
  void requiresDistinctNonnilReadRequestUuid() {
    var request = request("test", "copper-coast", false);
    assertThatThrownBy(
            () ->
                new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
                    new UUID(0L, 0L), request, evidence(request).resultDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonnil");
    assertThatThrownBy(
            () ->
                new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
                    SOURCE_OPERATION_ID, request, evidence(request).resultDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distinct");
  }

  private static void assertFlatDuplicateRejected(LaunchDescriptor descriptor) {
    var expected = request("test", "copper-coast", false);
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    expected,
                    ResolveLaunchDescriptorResponse.newBuilder()
                        .setLaunchDescriptor(descriptor)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match evidence");
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request request(
      String namespace, String worldSlug, boolean runtimeFlagsPresent) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        namespace,
        "cp-α",
        TENANT_ID,
        worldSlug,
        SOURCE_OPERATION_ID,
        "sha256:" + "a".repeat(64),
        19L,
        true,
        "pätch-🦊",
        false,
        null,
        false,
        null,
        runtimeFlagsPresent,
        runtimeFlagsPresent ? "" : null);
  }

  private static net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence
      evidence(AuthoredWorldLaunchDescriptorEvidence.Request request) {
    return net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "ld-vector-1",
        Long.MAX_VALUE,
        true,
        "pätch-🦊",
        "{\"welcome\":\"雪\"}",
        "gen-rév-🧭",
        Long.MAX_VALUE,
        Long.MAX_VALUE,
        "release-bundle:12345678-1234-4234-8234-123456789abc:9223372036854775807:9223372036854775807",
        false,
        null);
  }

  private static ResolveLaunchDescriptorResponse resolveResponse(
      net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence evidence) {
    return ResolveLaunchDescriptorResponse.newBuilder()
        .setLaunchDescriptor(descriptor(evidence))
        .build();
  }

  private static GetLaunchDescriptorResponse getResponse(
      String requestId,
      net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence evidence) {
    return GetLaunchDescriptorResponse.newBuilder()
        .setRequestId(requestId)
        .setLaunchDescriptor(descriptor(evidence))
        .build();
  }

  private static LaunchDescriptor descriptor(
      net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence evidence) {
    net.firedevops.firemud.gamedesign.v1.AuthoredWorldLaunchDescriptorEvidence.Builder binding =
        net.firedevops.firemud.gamedesign.v1.AuthoredWorldLaunchDescriptorEvidence.newBuilder()
            .setSchemaVersion(evidence.schemaVersion())
            .setTargetNamespace(evidence.targetNamespace())
            .setControlPlaneRequestId(evidence.controlPlaneRequestId())
            .setCanonicalTenantId(evidence.canonicalTenantId().toString())
            .setWorldSlug(evidence.worldSlug())
            .setAuthoredWorldSourceOperationId(evidence.authoredWorldSourceOperationId().toString())
            .setAuthoredWorldSourceEvidenceDigest(evidence.authoredWorldSourceEvidenceDigest())
            .setGameTemplateId(evidence.gameTemplateId())
            .setRequestDigest(evidence.requestDigest())
            .setLaunchDescriptorId(evidence.launchDescriptorId())
            .setVersionId(evidence.versionId())
            .setRuntimeFlagsJson(evidence.runtimeFlagsJson())
            .setGenerationConfigRevision(evidence.generationConfigRevision())
            .setVersionStateEpoch(evidence.versionStateEpoch())
            .setReleaseBundleId(evidence.releaseBundleId())
            .setPublishedReleaseBundleRef(evidence.publishedReleaseBundleRef())
            .setResultDigest(evidence.resultDigest());
    if (evidence.requestedScriptPatchVersionPresent()) {
      binding.setRequestedScriptPatchVersion(evidence.requestedScriptPatchVersion());
    }
    if (evidence.sourceVersionIdPresent()) {
      binding.setSourceVersionId(evidence.sourceVersionId());
    }
    if (evidence.targetVersionIdPresent()) {
      binding.setTargetVersionId(evidence.targetVersionId());
    }
    if (evidence.requestedRuntimeFlagsJsonPresent()) {
      binding.setRequestedRuntimeFlagsJson(evidence.requestedRuntimeFlagsJson());
    }
    if (evidence.scriptPatchVersionPresent()) {
      binding.setScriptPatchVersion(evidence.scriptPatchVersion());
    }
    if (evidence.remapSetIdPresent()) {
      binding.setRemapSetId(evidence.remapSetId());
    }
    return LaunchDescriptor.newBuilder()
        .setLaunchDescriptorId(evidence.launchDescriptorId())
        .setCanonicalTenantId(evidence.canonicalTenantId().toString())
        .setGameTemplateId(evidence.gameTemplateId())
        .setControlPlaneRequestId(evidence.controlPlaneRequestId())
        .setVersionId(evidence.versionId())
        .setScriptPatchVersion(
            evidence.scriptPatchVersionPresent() ? evidence.scriptPatchVersion() : "")
        .setRuntimeFlagsJson(evidence.runtimeFlagsJson())
        .setGenerationConfigRevision(evidence.generationConfigRevision())
        .setVersionStateEpoch(evidence.versionStateEpoch())
        .setReleaseBundleId(evidence.releaseBundleId())
        .setPublishedReleaseBundleRef(evidence.publishedReleaseBundleRef())
        .setRemapSetId(evidence.remapSetIdPresent() ? evidence.remapSetId() : "")
        .setAuthoredWorldBinding(binding.build())
        .build();
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }
}
