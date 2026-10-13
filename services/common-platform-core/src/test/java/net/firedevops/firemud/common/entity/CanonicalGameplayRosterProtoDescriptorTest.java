package net.firedevops.firemud.common.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRosterProtoDescriptorTest {
  @Test
  void canonicalRosterRequestsCarryTheTypedPlayerExecutionContext() {
    assertPlayerExecutionContextField(CanonicalGameplayRosterRequest.getDescriptor(), 4);
    assertPlayerExecutionContextField(
        CanonicalGameplayRosterSelectedAssignmentRequest.getDescriptor(), 6);
  }

  @Test
  void selectedAssignmentRoundTripsTheExactTypedSnapshotReference() {
    FieldDescriptor expectedSnapshot =
        CanonicalGameplayRosterSelectedAssignmentRequest.getDescriptor().findFieldByNumber(5);
    FieldDescriptor snapshot =
        CanonicalGameplayRosterSelectedAssignmentResponse.getDescriptor().findFieldByNumber(8);

    assertThat(expectedSnapshot.getName()).isEqualTo("expected_snapshot");
    assertThat(expectedSnapshot.getMessageType())
        .isEqualTo(CanonicalGameplayRosterSnapshotReference.getDescriptor());
    assertThat(snapshot.getName()).isEqualTo("snapshot");
    assertThat(snapshot.getMessageType())
        .isEqualTo(CanonicalGameplayRosterSnapshotReference.getDescriptor());

    Descriptor snapshotDescriptor = CanonicalGameplayRosterSnapshotReference.getDescriptor();
    assertThat(snapshotDescriptor.findFieldByNumber(1).getName()).isEqualTo("snapshot_uuid");
    assertThat(snapshotDescriptor.findFieldByNumber(2).getName()).isEqualTo("snapshot_digest");
  }

  @Test
  void canonicalRosterResponsesHaveNoInResponseErrorAndReserveItsSlot() {
    assertErrorSlotReserved(CanonicalGameplayRosterResponse.getDescriptor(), 6);
    assertErrorSlotReserved(CanonicalGameplayRosterSelectedAssignmentResponse.getDescriptor(), 7);
    assertThat(CanonicalGameplayRosterResponse.getDescriptor().findFieldByNumber(6)).isNull();
    assertThat(
            CanonicalGameplayRosterSelectedAssignmentResponse.getDescriptor()
                .findFieldByNumber(8)
                .getName())
        .isEqualTo("snapshot");
  }

  @Test
  void selectedAssignmentResponseCarriesTheOriginalAssignmentReference() {
    Descriptor response = CanonicalGameplayRosterSelectedAssignmentResponse.getDescriptor();

    assertThat(response.findFieldByNumber(5).getName()).isEqualTo("assignment_uuid");
    assertThat(response.findFieldByNumber(5).getType()).isEqualTo(FieldDescriptor.Type.STRING);
    assertThat(response.findFieldByNumber(6).getName()).isEqualTo("intent_digest");
    assertThat(response.findFieldByNumber(6).getType()).isEqualTo(FieldDescriptor.Type.STRING);
    assertThat(response.findFieldByNumber(7)).isNull();
    assertThat(response.toProto().getReservedRangeList())
        .anySatisfy(range -> assertThat(range.getStart()).isEqualTo(7));
    assertThat(response.toProto().getReservedNameList()).contains("error");
  }

  private static void assertErrorSlotReserved(Descriptor descriptor, int fieldNumber) {
    assertThat(descriptor.toProto().getReservedRangeList())
        .anySatisfy(range -> assertThat(range.getStart()).isEqualTo(fieldNumber));
    assertThat(descriptor.toProto().getReservedNameList()).contains("error");
  }

  private static void assertPlayerExecutionContextField(Descriptor descriptor, int fieldNumber) {
    FieldDescriptor context = descriptor.findFieldByNumber(fieldNumber);
    assertThat(context.getName()).isEqualTo("player_execution_context");
    assertThat(context.getMessageType()).isEqualTo(PlayerExecutionContext.getDescriptor());
  }
}
