package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence.Request;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceResponse;
import org.junit.jupiter.api.Test;

class HistoricalOriginalStartSessionOwnerEvidenceGrpcCodecTest {
  private static final String NAMESPACE = "gameplay";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID GAME_INSTANCE = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID READ_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID ATTEMPT_ID = uuid("44444444-4444-4444-8444-444444444444");

  @Test
  void requestRoundTripsExactAssociationSelectorAndOriginalAttemptFence() {
    Request request = request();
    var wire = HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toRequest(request);

    assertThat(HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromRequest(wire))
        .isEqualTo(request);
  }

  @Test
  void requestRejectsUnknownFieldsUnsupportedSchemaAndNonPositiveFence() {
    var wire = HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toRequest(request());
    var unknown =
        wire.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    var unsupported = wire.toBuilder().setSchemaVersion(2).build();
    var noFence = wire.toBuilder().setExpectedOwnerFence(0L).build();

    assertThatThrownBy(
            () -> HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromRequest(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "ReadHistoricalOriginalStartSessionOwnerEvidenceRequest contains unsupported fields");
    assertThatThrownBy(
            () -> HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromRequest(unsupported))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema");
    assertThatThrownBy(
            () -> HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromRequest(noFence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Canonical historical original StartSession evidence request required")
        .hasRootCauseMessage("expectedOwnerFence must be positive");
  }

  @Test
  void responseRequiresTheExactRequestEchoBeforeDecodingEvidence() {
    Request request = request();
    var changedEcho =
        HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toRequest(request).toBuilder()
            .setExpectedOwnerFence(request.expectedOwnerFence() + 1L)
            .build();
    var response =
        ReadHistoricalOriginalStartSessionOwnerEvidenceResponse.newBuilder()
            .setRequest(changedEcho)
            .setTemplateAssociationRequest(
                net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest
                    .getDefaultInstance())
            .setTemplateAssociationResponse(
                net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse
                    .getDefaultInstance())
            .setDescriptorPinResponse(
                net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse
                    .getDefaultInstance())
            .setLaunchDescriptor(
                net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.getDefaultInstance())
            .setReleaseAttestation(
                net.firedevops.firemud.gamedesign.v1.AuthoredWorldReleaseAttestationEvidence
                    .getDefaultInstance())
            .build();

    assertThatThrownBy(
            () ->
                HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromResponse(
                    request, response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request echo");
  }

  @Test
  void responseRejectsUnknownNestedPinFields() {
    var request = HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toRequest(request());
    var response =
        ReadHistoricalOriginalStartSessionOwnerEvidenceResponse.newBuilder()
            .setRequest(request)
            .setTemplateAssociationRequest(
                net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest
                    .newBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                            .build())
                    .build())
            .setTemplateAssociationResponse(
                net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse
                    .getDefaultInstance())
            .setDescriptorPinResponse(
                net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse
                    .getDefaultInstance())
            .setLaunchDescriptor(
                net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.getDefaultInstance())
            .setReleaseAttestation(
                net.firedevops.firemud.gamedesign.v1.AuthoredWorldReleaseAttestationEvidence
                    .getDefaultInstance())
            .build();

    assertThatThrownBy(
            () ->
                HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromResponse(
                    request(), response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "ReadHistoricalOriginalStartSessionOwnerEvidenceResponse.template_association_request contains unsupported fields");
  }

  private static Request request() {
    return new Request(
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            READ_ID,
            NAMESPACE,
            TENANT,
            "world",
            GAME_INSTANCE,
            "start-request-1",
            "launch-descriptor-1",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64)),
        ATTEMPT_ID,
        17L);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
