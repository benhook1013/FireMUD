package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.client.GameSessionCanonicalInitialAdmissionOwnerClient;
import org.junit.jupiter.api.Test;

/**
 * Controlled client-boundary proof only; mocked Game Session responses do not qualify the remote
 * owner producer or the database finalizer.
 */
class WorldCanonicalInitialAdmissionHoldOwnerTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID HOLD_FENCE = uuid("77777777-7777-4777-8777-777777777777");

  @Test
  void acquisitionDoesNotReadOrSettleTheGameSessionOwner() {
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var gameSessionOwner = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var owner = new WorldCanonicalInitialAdmissionHoldOwner(repository, gameSessionOwner);
    Request request = request("a");
    var lifecycleRequest = lifecycleRequest(request, uuid("88888888-8888-4888-8888-888888888888"));
    HoldIdentity identity = identity(request);
    when(repository.acquire(request, lifecycleRequest)).thenReturn(identity);

    assertThat(owner.acquire(request, lifecycleRequest)).isEqualTo(identity);

    verify(repository).acquire(request, lifecycleRequest);
    verifyNoInteractions(gameSessionOwner);
  }

  @Test
  void pendingOwnerReadLeavesSettlementUnresolved() {
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var gameSessionOwner = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var owner = new WorldCanonicalInitialAdmissionHoldOwner(repository, gameSessionOwner);
    HoldIdentity identity = identity(request("b"));
    GameSessionCanonicalInitialAdmissionOwnerProof proof = pending(identity);
    when(gameSessionOwner.read(identity)).thenReturn(proof);

    assertThat(owner.settleFromAuthenticatedOwnerRead(identity)).isEqualTo(proof);

    verify(gameSessionOwner).read(identity);
    verify(repository).settle(identity, proof);
  }

  @Test
  void exactCommittedOwnerReadIsPassedToWorldSettlement() {
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var gameSessionOwner = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var owner = new WorldCanonicalInitialAdmissionHoldOwner(repository, gameSessionOwner);
    HoldIdentity identity = identity(request("c"));
    GameSessionCanonicalInitialAdmissionOwnerProof proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            1L,
            91L,
            "sha256:" + "d".repeat(64),
            false,
            Instant.parse("2026-10-24T01:02:03Z"));
    when(gameSessionOwner.read(identity)).thenReturn(proof);

    assertThat(owner.settleFromAuthenticatedOwnerRead(identity)).isEqualTo(proof);

    verify(repository).settle(identity, proof);
  }

  @Test
  void onlyPositiveDurableAbortOwnerReadIsPassedAsAbortSettlement() {
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var gameSessionOwner = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var owner = new WorldCanonicalInitialAdmissionHoldOwner(repository, gameSessionOwner);
    HoldIdentity identity = identity(request("f"));
    GameSessionCanonicalInitialAdmissionOwnerProof proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
            null,
            null,
            "sha256:" + "e".repeat(64),
            true,
            Instant.parse("2026-10-24T01:02:03Z"));
    when(gameSessionOwner.read(identity)).thenReturn(proof);

    assertThat(owner.settleFromAuthenticatedOwnerRead(identity)).isEqualTo(proof);

    verify(repository).settle(identity, proof);
  }

  @Test
  void unavailableOrMismatchedOwnerReadCannotReachWorldSettlement() {
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var gameSessionOwner = mock(GameSessionCanonicalInitialAdmissionOwnerClient.class);
    var owner = new WorldCanonicalInitialAdmissionHoldOwner(repository, gameSessionOwner);
    HoldIdentity identity = identity(request("d"));
    when(gameSessionOwner.read(identity))
        .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED));

    assertThatThrownBy(() -> owner.settleFromAuthenticatedOwnerRead(identity))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    verifyNoInteractions(repository);

    HoldIdentity otherIdentity = identity(request("e"));
    doReturn(pending(otherIdentity)).when(gameSessionOwner).read(identity);
    assertThatThrownBy(() -> owner.settleFromAuthenticatedOwnerRead(identity))
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException.class);
    verifyNoInteractions(repository);
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof pending(HoldIdentity identity) {
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        identity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING,
        null,
        null,
        null,
        false,
        null);
  }

  private static Request request(String digestChar) {
    return new Request(
        "test",
        TENANT,
        "starter-world",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        GAME_INSTANCE,
        VERSION,
        7L,
        "initial-admission-request",
        digestChar.repeat(64),
        WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        3L,
        null);
  }

  private static HoldIdentity identity(Request request) {
    return new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
      Request request, UUID readRequestId) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        readRequestId,
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.canonicalGameInstanceId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        true,
        "control-request",
        request.canonicalVersionId(),
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        "sha256:" + "3".repeat(64));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
