package net.firedevops.firemud.entitymanagement.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import org.junit.jupiter.api.Test;

/**
 * Controlled value checks for the Account protection receiver only. The original is a stipulated
 * comparison value, not authenticated Account authority or independently validated creation intent.
 */
class EntityCharacterCreateProtectionBoundsTest {
  private static final ByteString ONE_MEBIBYTE = ByteString.copyFrom(new byte[1024 * 1024]);
  private static final ReadOriginalCharacterCreateResponse ORIGINAL =
      ReadOriginalCharacterCreateResponse.newBuilder()
          .setTargetNamespace("test")
          .setOperationId(uuid(1))
          .setMutationDigest("a".repeat(64))
          .build();
  private static final CommitProtectedCharacterCreateRequest REQUEST =
      CommitProtectedCharacterCreateRequest.newBuilder()
          .setOriginal(
              ReadOriginalCharacterCreateRequest.newBuilder()
                  .setTargetNamespace("test")
                  .setOperationId(uuid(1))
                  .setMutationDigest("a".repeat(64)))
          .setAccountProtection(reference(2))
          .setGameSessionProtection(reference(3))
          .setWorldProtection(reference(4))
          .build();

  @Test
  void eachExactOneMebibyteSnapshotIsAccepted() {
    assertThatCode(
            () ->
                EntityCharacterCreateBindings.requireAccountProtection(
                    REQUEST, ORIGINAL, protection()))
        .doesNotThrowAnyException();
  }

  @Test
  void eachSnapshotIndependentlyRejectsOneByteBeyondOneMebibyte() {
    var oversized = ONE_MEBIBYTE.concat(ByteString.copyFrom(new byte[] {1}));
    for (var changed :
        List.of(
            protection().toBuilder().setOriginalMembershipSnapshot(oversized).build(),
            protection().toBuilder().setOriginalEntitlementSnapshot(oversized).build())) {
      assertInvalid(
          () -> EntityCharacterCreateBindings.requireAccountProtection(REQUEST, ORIGINAL, changed));
    }
  }

  @Test
  void nullOriginalIsInvalidArgumentRatherThanANullDereference() {
    assertInvalid(
        () -> EntityCharacterCreateBindings.requireAccountProtection(REQUEST, null, protection()));
  }

  @Test
  void exactOriginalProtectionEmptySnapshotsAndAcquisitionGuardsRemainRequired() {
    for (var changed :
        List.of(
            protection().toBuilder().clearOriginal().build(),
            protection().toBuilder()
                .setOriginal(ORIGINAL.toBuilder().setOperationId(uuid(9)))
                .build(),
            protection().toBuilder().setAccountProtectionId(uuid(9)).build(),
            protection().toBuilder().setAccountProtectionFence(8).build(),
            protection().toBuilder().clearOriginalMembershipSnapshot().build(),
            protection().toBuilder().clearOriginalEntitlementSnapshot().build(),
            protection().toBuilder().setAcquiredAt("not-an-instant").build(),
            protection().toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                        .build())
                .build())) {
      assertInvalid(
          () -> EntityCharacterCreateBindings.requireAccountProtection(REQUEST, ORIGINAL, changed));
    }
  }

  private static ReadOriginalCharacterCreateProtectionResponse protection() {
    return ReadOriginalCharacterCreateProtectionResponse.newBuilder()
        .setOriginal(ORIGINAL)
        .setAccountProtectionId(REQUEST.getAccountProtection().getProtectionId())
        .setAccountProtectionFence(REQUEST.getAccountProtection().getProtectionFence())
        .setAcquiredAt("2026-10-24T00:00:00Z")
        .setOriginalMembershipSnapshot(ONE_MEBIBYTE)
        .setOriginalEntitlementSnapshot(ONE_MEBIBYTE)
        .build();
  }

  private static CharacterCreateProtectionReference reference(int value) {
    return CharacterCreateProtectionReference.newBuilder()
        .setProtectionId(uuid(value))
        .setProtectionFence(7)
        .build();
  }

  private static String uuid(int value) {
    return new UUID(0L, value).toString();
  }

  private static void assertInvalid(Runnable validation) {
    assertThatThrownBy(validation::run)
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }
}
