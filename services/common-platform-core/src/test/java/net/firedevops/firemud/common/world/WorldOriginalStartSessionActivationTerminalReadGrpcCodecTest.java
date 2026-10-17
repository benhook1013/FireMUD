package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalStartSessionActivationTerminalEvidenceRequest;
import org.junit.jupiter.api.Test;

/** Closed-envelope denial tests; no authenticated World producer or physical receipt is implied. */
class WorldOriginalStartSessionActivationTerminalReadGrpcCodecTest {
  @Test
  void rejectsDuplicateFieldsBeforeEvidenceDecoding() {
    var duplicate =
        ReadOriginalStartSessionActivationTerminalEvidenceRequest.newBuilder()
            .addSchemaVersion(1)
            .addSchemaVersion(1)
            .build();

    assertThatThrownBy(
            () ->
                WorldOriginalStartSessionActivationTerminalReadGrpcCodec.fromRequest(
                    duplicate, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate field");
  }

  @Test
  void rejectsUnknownFieldsBeforeEvidenceDecoding() {
    var unknown =
        ReadOriginalStartSessionActivationTerminalEvidenceRequest.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();

    assertThatThrownBy(
            () ->
                WorldOriginalStartSessionActivationTerminalReadGrpcCodec.fromRequest(
                    unknown, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }
}
