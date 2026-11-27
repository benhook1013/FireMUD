package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.jooq.DSLContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Repository transaction-boundary proof; it does not qualify storage or restart recovery. */
class WorldCanonicalInitialAdmissionHoldRepositoryTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID HOLD_FENCE = uuid("77777777-7777-4777-8777-777777777777");

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void ambientTransactionOrSynchronizationRefusesBeforeAnyOwnerOrStorageInteraction(
      boolean actualTransaction) {
    var sql = mock(DSLContext.class);
    var manager = mock(PlatformTransactionManager.class);
    var associations = mock(WorldCanonicalInstanceAssociationRepository.class);
    var lifecycles = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var repository =
        new WorldCanonicalInitialAdmissionHoldRepository(sql, manager, associations, lifecycles);
    var request = request("a");
    var identity = identity(request);
    var lifecycle = lifecycleRequest(request, uuid("88888888-8888-4888-8888-888888888888"));
    try {
      if (actualTransaction) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
      } else {
        TransactionSynchronizationManager.initSynchronization();
      }
      assertThatThrownBy(() -> repository.acquire(request, lifecycle))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Canonical World hold acquisition must not join an ambient transaction");
      assertThatThrownBy(() -> repository.readIdentity(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Canonical World hold identity read must not join an ambient transaction");
      assertThatThrownBy(() -> repository.readState(identity, lifecycle))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Canonical World hold state read must not join an ambient transaction");
      verifyNoInteractions(sql, manager, associations, lifecycles);
    } finally {
      TransactionSynchronizationManager.clear();
    }
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
