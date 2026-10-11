package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;

class AuthoredWorldLaunchDescriptorGrpcCodecTest {
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UnknownFieldSet UNKNOWN_FIELD =
      UnknownFieldSet.newBuilder()
          .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
          .build();

  @Test
  void resolveRequestAndResponsePreserveEveryPresentOptionalAndExactDescriptorBinding()
      throws Exception {
    AuthoredWorldLaunchDescriptorEvidence descriptor = descriptorWithOptionalValues();

    var request = AuthoredWorldLaunchDescriptorGrpcCodec.toResolveRequest(descriptor.request());
    assertThat(request.getTenantId()).isEmpty();
    assertThat(request.getCanonicalTenantId()).isEqualTo(descriptor.canonicalTenantId().toString());
    assertThat(request.getWorldSlug()).isEqualTo(descriptor.worldSlug());
    assertThat(request.getAuthoredWorldSourceOperationId())
        .isEqualTo(descriptor.authoredWorldSourceOperationId().toString());
    assertThat(request.getExpectedAuthoredWorldSourceEvidenceDigest())
        .isEqualTo(descriptor.authoredWorldSourceEvidenceDigest());
    assertThat(request.hasRequestedScriptPatchVersion()).isTrue();
    assertThat(request.getRequestedScriptPatchVersion()).isEqualTo("script-patch-v2");
    assertThat(request.hasSourceVersionId()).isTrue();
    assertThat(request.getSourceVersionId()).isEqualTo(41L);
    assertThat(request.hasTargetVersionId()).isTrue();
    assertThat(request.getTargetVersionId()).isEqualTo(42L);
    assertThat(request.hasRequestedRuntimeFlagsJson()).isTrue();
    assertThat(request.getRequestedRuntimeFlagsJson()).isEqualTo("{\"quality\":\"high\"}");

    var response =
        ResolveLaunchDescriptorResponse.newBuilder()
            .setLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor))
            .build();
    assertThat(response.getLaunchDescriptor().getAuthoredWorldBinding().hasScriptPatchVersion())
        .isTrue();
    assertThat(response.getLaunchDescriptor().getAuthoredWorldBinding().getScriptPatchVersion())
        .isEqualTo("script-patch-v2");
    assertThat(response.getLaunchDescriptor().getAuthoredWorldBinding().hasRemapSetId()).isTrue();
    assertThat(response.getLaunchDescriptor().getAuthoredWorldBinding().getRemapSetId())
        .isEqualTo("approved-remap-41-42");
    assertThat(
            AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                descriptor.request(), response))
        .isEqualTo(descriptor);
  }

  @Test
  void omittedResolveOptionsRemainAbsentAndDecodeAsNull() throws Exception {
    var descriptor = authoredWorldFixture().launchBinding().descriptor();
    var request = AuthoredWorldLaunchDescriptorGrpcCodec.toResolveRequest(descriptor.request());

    assertThat(request.hasRequestedScriptPatchVersion()).isFalse();
    assertThat(request.hasSourceVersionId()).isFalse();
    assertThat(request.hasTargetVersionId()).isFalse();
    assertThat(request.hasRequestedRuntimeFlagsJson()).isFalse();
    assertThat(descriptor.request().requestedScriptPatchVersion()).isNull();
    assertThat(descriptor.request().sourceVersionId()).isNull();
    assertThat(descriptor.request().targetVersionId()).isNull();
    assertThat(descriptor.request().requestedRuntimeFlagsJson()).isNull();

    var response =
        ResolveLaunchDescriptorResponse.newBuilder()
            .setLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor))
            .build();
    assertThat(
            AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                descriptor.request(), response))
        .isEqualTo(descriptor);
  }

  @Test
  void authoredWorldDigestVectorAndEmptyOptionalPresenceRemainStable() throws Exception {
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            "test",
            "cp-α",
            UUID.fromString("12345678-1234-4234-8234-123456789abc"),
            "copper-coast",
            UUID.fromString("22345678-1234-4234-8234-123456789abc"),
            "sha256:" + "a".repeat(64),
            19L,
            true,
            "pätch-🦊",
            false,
            null,
            false,
            null,
            false,
            null);
    assertThat(request.requestDigest())
        .isEqualTo("sha256:ac6fc51a4b81185a33cfe9c84df69e05537a69415a6d8b8fb478a946221e7111");

    var evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
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
    assertThat(evidence.resultDigest())
        .isEqualTo("sha256:701c68797b898b696cac24d48dcff4fe25c0856f15965ff7e8b219f7c57acd11");
    var readRequest =
        new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
            READ_REQUEST_ID, request, evidence.resultDigest());
    var readWire = AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(readRequest);
    var readResponse =
        GetLaunchDescriptorResponse.newBuilder()
            .setRequestId(readWire.getRequestId())
            .setLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(evidence))
            .build();
    assertThat(AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(readRequest, readResponse))
        .isEqualTo(evidence);

    var emptyFlagsRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            request.targetNamespace(),
            request.controlPlaneRequestId(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.authoredWorldSourceOperationId(),
            request.authoredWorldSourceEvidenceDigest(),
            request.gameTemplateId(),
            request.requestedScriptPatchVersionPresent(),
            request.requestedScriptPatchVersion(),
            request.sourceVersionIdPresent(),
            request.sourceVersionId(),
            request.targetVersionIdPresent(),
            request.targetVersionId(),
            true,
            "");
    var emptyFlagsWire = AuthoredWorldLaunchDescriptorGrpcCodec.toResolveRequest(emptyFlagsRequest);
    assertThat(emptyFlagsWire.hasRequestedRuntimeFlagsJson()).isTrue();
    assertThat(emptyFlagsWire.getRequestedRuntimeFlagsJson()).isEmpty();
    var emptyFlagsEvidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            emptyFlagsRequest,
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
    assertThat(
            AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                emptyFlagsRequest,
                ResolveLaunchDescriptorResponse.newBuilder()
                    .setLaunchDescriptor(
                        AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
                            emptyFlagsEvidence))
                    .build()))
        .satisfies(
            readback -> {
              assertThat(readback.requestedRuntimeFlagsJsonPresent()).isTrue();
              assertThat(readback.requestedRuntimeFlagsJson()).isEmpty();
            });
  }

  @Test
  void getRequestAndResponseKeepReadCorrelationSeparateFromTheOriginalResolveIdentity()
      throws Exception {
    var descriptor = descriptorWithOptionalValues();
    var getRequest = getRequest(descriptor);
    var wireRequest = AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(getRequest);

    assertThat(wireRequest.getRequestId()).isEqualTo(READ_REQUEST_ID.toString());
    assertThat(wireRequest.getRequestId())
        .isNotEqualTo(descriptor.authoredWorldSourceOperationId().toString())
        .isNotEqualTo(descriptor.controlPlaneRequestId());
    assertThat(wireRequest.getCanonicalTenantId())
        .isEqualTo(descriptor.canonicalTenantId().toString());
    assertThat(wireRequest.getWorldSlug()).isEqualTo(descriptor.worldSlug());
    assertThat(wireRequest.getControlPlaneRequestId())
        .isEqualTo(descriptor.controlPlaneRequestId());
    assertThat(wireRequest.getExpectedRequestDigest()).isEqualTo(descriptor.requestDigest());
    assertThat(wireRequest.getExpectedResultDigest()).isEqualTo(descriptor.resultDigest());

    var response =
        GetLaunchDescriptorResponse.newBuilder()
            .setRequestId(wireRequest.getRequestId())
            .setLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor))
            .build();
    assertThat(AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(getRequest, response))
        .isEqualTo(descriptor);
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    getRequest,
                    response.toBuilder().setRequestId(UUID.randomUUID().toString()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request ID echo");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
                        READ_REQUEST_ID, descriptor.request(), "sha256:" + "f".repeat(64)),
                    response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("result digest");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    getRequest,
                    GetLaunchDescriptorResponse.newBuilder()
                        .setRequestId(wireRequest.getRequestId())
                        .setError(ErrorDetail.getDefaultInstance())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rejected");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    getRequest,
                    GetLaunchDescriptorResponse.newBuilder()
                        .setRequestId(wireRequest.getRequestId())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no descriptor");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    getRequest, response.toBuilder().setUnknownFields(UNKNOWN_FIELD).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
                    descriptor.authoredWorldSourceOperationId(),
                    descriptor.request(),
                    descriptor.resultDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distinct");
    assertThatThrownBy(
            () ->
                new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
                    new UUID(0L, 0L), descriptor.request(), descriptor.resultDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonnil");
  }

  @Test
  void resolveReadRejectsErrorsAndMissingBindings() throws Exception {
    var world = authoredWorldFixture();
    var descriptor = world.launchBinding().descriptor();
    var wireDescriptor =
        AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor);
    var resolveResponse =
        ResolveLaunchDescriptorResponse.newBuilder().setLaunchDescriptor(wireDescriptor).build();

    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    ResolveLaunchDescriptorResponse.newBuilder()
                        .setError(ErrorDetail.getDefaultInstance())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rejected");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(), ResolveLaunchDescriptorResponse.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no descriptor");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder()
                        .setLaunchDescriptor(wireDescriptor.toBuilder().clearAuthoredWorldBinding())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no authored-world binding");
  }

  @Test
  void rejectsUnknownFieldsConflictingFlatDuplicatesAndLegacyTenantSelectors() throws Exception {
    var world = authoredWorldFixture();
    var descriptor = world.launchBinding().descriptor();
    var evidenceMessage =
        AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor);
    var resolveResponse =
        ResolveLaunchDescriptorResponse.newBuilder().setLaunchDescriptor(evidenceMessage).build();

    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder().setUnknownFields(UNKNOWN_FIELD).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder()
                        .setLaunchDescriptor(
                            evidenceMessage.toBuilder().setUnknownFields(UNKNOWN_FIELD))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    var bindingWithUnknownField =
        evidenceMessage.getAuthoredWorldBinding().toBuilder()
            .setUnknownFields(UNKNOWN_FIELD)
            .build();
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder()
                        .setLaunchDescriptor(
                            evidenceMessage.toBuilder()
                                .setAuthoredWorldBinding(bindingWithUnknownField))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder()
                        .setLaunchDescriptor(
                            evidenceMessage.toBuilder().setTenantId("legacy-tenant"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("legacy tenant");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder()
                        .setLaunchDescriptor(evidenceMessage.toBuilder().setVersionId(43L))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match evidence");

    for (var changedDescriptor :
        List.of(
            evidenceMessage.toBuilder().setLaunchDescriptorId("other").build(),
            evidenceMessage.toBuilder().setCanonicalTenantId(UUID.randomUUID().toString()).build(),
            evidenceMessage.toBuilder().setGameTemplateId(0L).build(),
            evidenceMessage.toBuilder().setControlPlaneRequestId("other").build(),
            evidenceMessage.toBuilder().setScriptPatchVersion("other").build(),
            evidenceMessage.toBuilder().setRuntimeFlagsJson("other").build(),
            evidenceMessage.toBuilder().setGenerationConfigRevision("other").build(),
            evidenceMessage.toBuilder().setVersionStateEpoch(0L).build(),
            evidenceMessage.toBuilder().setReleaseBundleId(0L).build(),
            evidenceMessage.toBuilder().setPublishedReleaseBundleRef("other").build(),
            evidenceMessage.toBuilder().setRemapSetId("other").build())) {
      assertThatThrownBy(
              () ->
                  AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                      descriptor.request(),
                      resolveResponse.toBuilder().setLaunchDescriptor(changedDescriptor).build()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not match evidence");
    }

    for (var changedRequest :
        List.of(
            requestWithTargetNamespaceAndWorldSlug(
                descriptor.request(), descriptor.targetNamespace(), "copper-shore"),
            requestWithTargetNamespaceAndWorldSlug(
                descriptor.request(), "other", descriptor.worldSlug()))) {
      var changedEvidence = descriptorWithRequest(descriptor, changedRequest);
      assertThatThrownBy(
              () ->
                  AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                      descriptor.request(),
                      ResolveLaunchDescriptorResponse.newBuilder()
                          .setLaunchDescriptor(
                              AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
                                  changedEvidence))
                          .build()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exact authored-world resolve request");
    }

    var unsupportedSchema =
        evidenceMessage.toBuilder()
            .setAuthoredWorldBinding(
                evidenceMessage.getAuthoredWorldBinding().toBuilder().setSchemaVersion(99))
            .build();
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder().setLaunchDescriptor(unsupportedSchema).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Authored-world launch evidence is invalid");
  }

  private static WorldCanonicalInstanceLifecycleEvidence authoredWorldFixture() throws Exception {
    return AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L);
  }

  private static AuthoredWorldLaunchDescriptorEvidence descriptorWithOptionalValues()
      throws Exception {
    var base = authoredWorldFixture().launchBinding().descriptor();
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            base.targetNamespace(),
            base.controlPlaneRequestId(),
            base.canonicalTenantId(),
            base.worldSlug(),
            base.authoredWorldSourceOperationId(),
            base.authoredWorldSourceEvidenceDigest(),
            base.gameTemplateId(),
            true,
            "script-patch-v2",
            true,
            41L,
            true,
            42L,
            true,
            "{\"quality\":\"high\"}");
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "optional-launch-descriptor",
        42L,
        true,
        "script-patch-v2",
        "{\"runtime\":true}",
        "generation-revision",
        9L,
        7L,
        "published-release",
        true,
        "approved-remap-41-42");
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request
      requestWithTargetNamespaceAndWorldSlug(
          AuthoredWorldLaunchDescriptorEvidence.Request original,
          String targetNamespace,
          String worldSlug) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        targetNamespace,
        original.controlPlaneRequestId(),
        original.canonicalTenantId(),
        worldSlug,
        original.authoredWorldSourceOperationId(),
        original.authoredWorldSourceEvidenceDigest(),
        original.gameTemplateId(),
        original.requestedScriptPatchVersionPresent(),
        original.requestedScriptPatchVersion(),
        original.sourceVersionIdPresent(),
        original.sourceVersionId(),
        original.targetVersionIdPresent(),
        original.targetVersionId(),
        original.requestedRuntimeFlagsJsonPresent(),
        original.requestedRuntimeFlagsJson());
  }

  private static AuthoredWorldLaunchDescriptorEvidence descriptorWithRequest(
      AuthoredWorldLaunchDescriptorEvidence original,
      AuthoredWorldLaunchDescriptorEvidence.Request request) {
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        original.launchDescriptorId(),
        original.versionId(),
        original.scriptPatchVersionPresent(),
        original.scriptPatchVersion(),
        original.runtimeFlagsJson(),
        original.generationConfigRevision(),
        original.versionStateEpoch(),
        original.releaseBundleId(),
        original.publishedReleaseBundleRef(),
        original.remapSetIdPresent(),
        original.remapSetId());
  }

  private static AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest getRequest(
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    return new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
        READ_REQUEST_ID, descriptor.request(), descriptor.resultDigest());
  }
}
