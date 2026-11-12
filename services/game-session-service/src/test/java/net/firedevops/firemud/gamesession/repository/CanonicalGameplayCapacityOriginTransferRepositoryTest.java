package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure source contracts only. Controlled objects do not prove SQL/Redis custody or a handoff. */
class CanonicalGameplayCapacityOriginTransferRepositoryTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String OTHER_DIGEST = "sha256:" + "b".repeat(64);
  private static final String OLD_OWNER = "old-owner";

  @Test
  void unknownOpenOriginHasExactReadbackWithoutReservationOrExecution() {
    var item = item(CanonicalGameplayCapacityOrigin.State.OPEN);
    var readback =
        new CanonicalGameplayCapacityOriginRedisOwner.Readback(
            DIGEST, "successor", 12L, item.state());
    byte[] first = CanonicalGameplayCapacityOriginTransferRepository.exactReadback(item, readback);
    assertThat(first)
        .isEqualTo(CanonicalGameplayCapacityOriginTransferRepository.exactReadback(item, readback));
    assertThat(new String(first, StandardCharsets.UTF_8))
        .contains(DIGEST, "successor", "12", "OPEN");
  }

  @Test
  void everyCapturedStateMustMatchThePhysicalSuccessorExactly() {
    for (var state :
        List.of(
            CanonicalGameplayCapacityOrigin.State.OPEN,
            CanonicalGameplayCapacityOrigin.State.DISPATCH_CLAIMED,
            CanonicalGameplayCapacityOrigin.State.CLOSURE_CLAIMED,
            CanonicalGameplayCapacityOrigin.State.DENIED)) {
      var item = item(state);
      assertThat(
              CanonicalGameplayCapacityOriginTransferRepository.exactReadback(
                  item,
                  new CanonicalGameplayCapacityOriginRedisOwner.Readback(
                      DIGEST, "successor", 12L, state)))
          .isNotEmpty();
      for (var wrong :
          List.of(
              new CanonicalGameplayCapacityOriginRedisOwner.Readback(
                  OTHER_DIGEST, "successor", 12L, state),
              new CanonicalGameplayCapacityOriginRedisOwner.Readback(DIGEST, OLD_OWNER, 12L, state),
              new CanonicalGameplayCapacityOriginRedisOwner.Readback(
                  DIGEST, "successor", 13L, state),
              new CanonicalGameplayCapacityOriginRedisOwner.Readback(
                  DIGEST,
                  "successor",
                  12L,
                  state == CanonicalGameplayCapacityOrigin.State.OPEN
                      ? CanonicalGameplayCapacityOrigin.State.CLOSURE_CLAIMED
                      : CanonicalGameplayCapacityOrigin.State.OPEN))) {
        assertThatThrownBy(
                () -> CanonicalGameplayCapacityOriginTransferRepository.exactReadback(item, wrong))
            .isInstanceOf(IllegalStateException.class);
      }
      assertThatThrownBy(
              () -> CanonicalGameplayCapacityOriginTransferRepository.exactReadback(item, null))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void nonIncreasingFenceCannotBecomeTransferWork() {
    var open = item(CanonicalGameplayCapacityOrigin.State.OPEN);
    assertThatThrownBy(
            () ->
                new CanonicalGameplayCapacityOriginTransferRepository.Item(
                    open.transferId(),
                    0,
                    open.origin(),
                    OLD_OWNER,
                    11,
                    "successor",
                    11,
                    CanonicalGameplayCapacityOrigin.State.OPEN))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sqlDenialRemainsTransferWorkUntilExactPhysicalSuccessorReadback() {
    var denied = item(CanonicalGameplayCapacityOrigin.State.DENIED);
    assertThat(denied.state()).isEqualTo(CanonicalGameplayCapacityOrigin.State.DENIED);
    assertThatThrownBy(
            () -> CanonicalGameplayCapacityOriginTransferRepository.exactReadback(denied, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayCapacityOriginTransferRepository.exactReadback(
                    denied,
                    new CanonicalGameplayCapacityOriginRedisOwner.Readback(
                        DIGEST, OLD_OWNER, 11, CanonicalGameplayCapacityOrigin.State.OPEN)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            CanonicalGameplayCapacityOriginTransferRepository.exactReadback(
                denied,
                new CanonicalGameplayCapacityOriginRedisOwner.Readback(
                    DIGEST, "successor", 12, CanonicalGameplayCapacityOrigin.State.DENIED)))
        .isNotEmpty();
    assertThat(
            context(
                    CanonicalGameplayCapacityOrigin.State.DENIED,
                    "successor",
                    12,
                    "c".repeat(64),
                    "FROZEN",
                    null)
                .state())
        .isEqualTo(CanonicalGameplayCapacityOrigin.State.DENIED);
  }

  @Test
  void originObservationPurposeRetainsItsOwnStateSuccessorAndRawLease() {
    var open =
        context(
            CanonicalGameplayCapacityOrigin.State.OPEN,
            "successor",
            12,
            "c".repeat(64),
            "FROZEN",
            null);
    var claimed =
        context(
            CanonicalGameplayCapacityOrigin.State.CLOSURE_CLAIMED,
            "successor",
            12,
            "c".repeat(64),
            "FROZEN",
            null);
    assertThat(open).isNotEqualTo(claimed);
    assertThat(open.state()).isEqualTo(CanonicalGameplayCapacityOrigin.State.OPEN);
    assertThat(open.newLease()).isEqualTo("c".repeat(64));
    assertThatThrownBy(
            () ->
                context(
                    CanonicalGameplayCapacityOrigin.State.OPEN,
                    "successor",
                    11,
                    "c".repeat(64),
                    "FROZEN",
                    null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                context(
                    CanonicalGameplayCapacityOrigin.State.OPEN,
                    "successor",
                    12,
                    DIGEST,
                    "FROZEN",
                    null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                context(
                    CanonicalGameplayCapacityOrigin.State.OPEN,
                    "successor",
                    12,
                    "c".repeat(64),
                    "FROZEN",
                    DIGEST))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                context(
                    CanonicalGameplayCapacityOrigin.State.OPEN,
                    "successor",
                    12,
                    "c".repeat(64),
                    "COMPLETED",
                    null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            context(
                    CanonicalGameplayCapacityOrigin.State.OPEN,
                    "successor",
                    12,
                    "c".repeat(64),
                    "COMPLETED",
                    DIGEST)
                .completionDigest())
        .isEqualTo(DIGEST);
  }

  @Test
  void requestDecoderRefusesMissingAndNoncanonicalFrames() {
    for (byte[] bytes :
        List.of(
            new byte[0],
            "mutationDigest/v1".getBytes(StandardCharsets.US_ASCII),
            "0:trailing".getBytes(StandardCharsets.US_ASCII))) {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayRegionOwnerTransferRepository.decodeRequest(
                      bytes, "a".repeat(64)))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerTransferRepository.decodeRequest(null, "a".repeat(64)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static CanonicalGameplayCapacityOriginTransferRepository.Item item(
      CanonicalGameplayCapacityOrigin.State state) {
    CanonicalGameplayCapacityOrigin origin = mock();
    when(origin.digest()).thenReturn(DIGEST);
    return new CanonicalGameplayCapacityOriginTransferRepository.Item(
        UUID.fromString("10000000-0000-4000-8000-000000000001"),
        0,
        origin,
        OLD_OWNER,
        11,
        "successor",
        12,
        state);
  }

  private static CanonicalGameplayCapacityOriginTransferRepository.Context context(
      CanonicalGameplayCapacityOrigin.State state,
      String newOwner,
      long newFence,
      String lease,
      String control,
      String completion) {
    UUID id = UUID.fromString("10000000-0000-4000-8000-000000000001");
    return new CanonicalGameplayCapacityOriginTransferRepository.Context(
        id,
        0,
        DIGEST,
        DIGEST,
        OLD_OWNER,
        11,
        id,
        id,
        id,
        1,
        id,
        id,
        id,
        id,
        DIGEST,
        DIGEST,
        "g1-10000000000040008000000000000001",
        "firemud",
        1,
        state,
        newOwner,
        newFence,
        lease,
        control,
        completion);
  }
}
