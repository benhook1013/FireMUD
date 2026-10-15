package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreResponse;
import org.junit.jupiter.api.Test;

class AuthoredInitialActorCoreReadGrpcCodecTest {
  @Test
  void roundTripsExactReadRequestAndCompleteCanonicalResult() throws Exception {
    var fixture = AuthoredInitialActorCoreReadTestFixture.create();
    var request = new ReadRequest(fixture.request());
    var wireRequest = AuthoredInitialActorCoreReadGrpcCodec.toRequest(request);
    var response = AuthoredInitialActorCoreReadGrpcCodec.toResponse(fixture.result());

    var decoded =
        AuthoredInitialActorCoreReadGrpcCodec.fromResponse(fixture.request(), response);

    assertThat(AuthoredInitialActorCoreReadGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(request);
    assertThat(decoded.canonicalBytes()).containsExactly(fixture.result().canonicalBytes());
    assertThat(decoded.request()).isEqualTo(fixture.request());
    assertThat(decoded.associationRead()).isEqualTo(fixture.associationRead());
    assertThat(decoded.descriptor()).isEqualTo(fixture.descriptor());
  }

  @Test
  void rejectsMissingInitialUnknownAndChangedExactRequestSelectors() throws Exception {
    var fixture = AuthoredInitialActorCoreReadTestFixture.create();
    var wire =
        AuthoredInitialActorCoreReadGrpcCodec.toRequest(new ReadRequest(fixture.request()));
    assertThatThrownBy(
            () ->
                AuthoredInitialActorCoreReadGrpcCodec.fromRequest(
                    wire.toBuilder().clearAssociationRequest().build()))
        .isInstanceOf(IllegalArgumentException.class);

    var noSelection =
        wire.toBuilder()
            .setAssociationRequest(
                wire.getAssociationRequest().toBuilder().clearSelection().build())
            .build();
    assertThatThrownBy(() -> AuthoredInitialActorCoreReadGrpcCodec.fromRequest(noSelection))
        .isInstanceOf(IllegalArgumentException.class);

    var unknown =
        wire.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(88, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(() -> AuthoredInitialActorCoreReadGrpcCodec.fromRequest(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");

    var changedSelector =
        wire.toBuilder()
            .setAssociationRequest(
                wire.getAssociationRequest().toBuilder()
                    .setExactReplay(
                        wire.getAssociationRequest().getExactReplay().toBuilder()
                            .setSelectedCommitId(UUID.randomUUID().toString())
                            .build())
                    .build())
            .build();
    assertThat(AuthoredInitialActorCoreReadGrpcCodec.fromRequest(changedSelector))
        .isNotEqualTo(AuthoredInitialActorCoreReadGrpcCodec.fromRequest(wire));
  }

  @Test
  void rejectsChangedEchoCanonicalBytesDigestAndUnknownNestedFields() throws Exception {
    var fixture = AuthoredInitialActorCoreReadTestFixture.create();
    ReadAuthoredInitialActorCoreResponse response =
        AuthoredInitialActorCoreReadGrpcCodec.toResponse(fixture.result());

    assertThatThrownBy(
            () ->
                AuthoredInitialActorCoreReadGrpcCodec.fromResponse(
                    changedRequest(fixture.request()), response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact request echo");
    assertThatThrownBy(
            () ->
                AuthoredInitialActorCoreReadGrpcCodec.fromResponse(
                    fixture.request(),
                    response.toBuilder()
                        .setCanonicalResultBytes(
                            com.google.protobuf.ByteString.copyFromUtf8("{}"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical complete content");
    assertThatThrownBy(
            () ->
                AuthoredInitialActorCoreReadGrpcCodec.fromResponse(
                    fixture.request(), response.toBuilder().setResultDigest("sha256:" + "0".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical complete content");

    var unknownNested =
        response.toBuilder()
            .setLaunchDescriptor(
                response.getLaunchDescriptor().toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(89, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                            .build())
                    .build())
            .build();
    assertThatThrownBy(
            () -> AuthoredInitialActorCoreReadGrpcCodec.fromResponse(fixture.request(), unknownNested))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  private static net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request
      changedRequest(
          net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request
              request) {
    var replay = (ExactReplay) request.selection();
    return new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request(
        request.schemaVersion(),
        request.targetNamespace(),
        request.readRequestId(),
        request.canonicalPostAuthorizationTuple(),
        request.ownerAttemptId(),
        request.ownerFence() + 1L,
        new ExactReplay(
            replay.canonicalVersionId(),
            replay.selectedCommitId(),
            replay.publishWorkflowId(),
            replay.associationDigest()));
  }
}
