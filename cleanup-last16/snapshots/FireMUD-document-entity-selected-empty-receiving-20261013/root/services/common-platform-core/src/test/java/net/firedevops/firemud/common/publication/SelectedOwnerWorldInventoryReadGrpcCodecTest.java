package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryRequest;
import org.junit.jupiter.api.Test;

class SelectedOwnerWorldInventoryReadGrpcCodecTest {
  @Test
  void rejectsIncompleteOrUnknownRequestsBeforeOwnerStorageCanBeReached() {
    var missing = ReadSelectedOwnerWorldInventoryRequest.getDefaultInstance();
    var unknown =
        missing.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();

    assertThatThrownBy(() -> SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(missing))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(unknown))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsWireMessagesAboveTheFiniteRecipientReadBudget() {
    var oversized =
        ReadSelectedOwnerWorldInventoryRequest.newBuilder()
            .setOriginalIntakeAuthorizationBinding(
                com.google.protobuf.ByteString.copyFrom(
                    new byte[SelectedOwnerWorldInventoryReadEvidence.MAX_WIRE_BYTES + 1]))
            .build();

    assertThatThrownBy(() -> SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(oversized))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire limit");
  }
}
