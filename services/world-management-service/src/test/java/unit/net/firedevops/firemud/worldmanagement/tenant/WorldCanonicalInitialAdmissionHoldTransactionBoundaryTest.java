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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Early repository-boundary refusal only; no physical transaction or remote authority is implied.
 */
class WorldCanonicalInitialAdmissionHoldTransactionBoundaryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final WorldCanonicalInstanceAssociationRepository associations =
      mock(WorldCanonicalInstanceAssociationRepository.class);
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycle =
      mock(WorldCanonicalInstanceLifecycleReadRepository.class);
  private final WorldCanonicalInitialAdmissionHoldRepository repository =
      new WorldCanonicalInitialAdmissionHoldRepository(dsl, manager, associations, lifecycle);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void acquisitionRejectsBeforeStartingOwnerTransactionOrReadingStorage(boolean actualTransaction) {
    Request request = request();
    var selector = selector(request);
    enterAmbient(actualTransaction);
    assertThatThrownBy(() -> repository.acquire(request, selector))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical World hold acquisition must not join an ambient transaction");
    verifyNoInteractions(manager, dsl, associations, lifecycle);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void identityReadRejectsBeforeStartingSnapshotOrReadingStorage(boolean actualTransaction) {
    Request request = request();
    enterAmbient(actualTransaction);
    assertThatThrownBy(() -> repository.readIdentity(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical World hold identity read must not join an ambient transaction");
    verifyNoInteractions(manager, dsl, associations, lifecycle);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void stateReadRejectsBeforeStartingSnapshotOrReadingStorage(boolean actualTransaction) {
    Request request = request();
    var identity = new HoldIdentity(request, UUID.randomUUID(), UUID.randomUUID());
    var selector = selector(request);
    enterAmbient(actualTransaction);
    assertThatThrownBy(() -> repository.readState(identity, selector))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical World hold state read must not join an ambient transaction");
    verifyNoInteractions(manager, dsl, associations, lifecycle);
  }

  private static void enterAmbient(boolean actualTransaction) {
    if (actualTransaction) {
      TransactionSynchronizationManager.setActualTransactionActive(true);
    } else {
      TransactionSynchronizationManager.initSynchronization();
    }
  }

  private static Request request() {
    return new Request(
        "test",
        UUID.randomUUID(),
        "starter-world",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        UUID.randomUUID(),
        7L,
        "initial-admission-request",
        "a".repeat(64),
        WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        3L,
        null);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request selector(Request request) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        UUID.randomUUID(),
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
}
