package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalPlayerAdmissionHold;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalPlayerAdmissionHoldRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalPlayerAdmissionHoldRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final WorldCanonicalInstanceAssociationRepository associations =
      mock(WorldCanonicalInstanceAssociationRepository.class);
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycle =
      mock(WorldCanonicalInstanceLifecycleReadRepository.class);
  private final WorldCanonicalPlayerAdmissionHoldRepository repository =
      new WorldCanonicalPlayerAdmissionHoldRepository(
          dsl, manager, "firemud", associations, lifecycle);

  @AfterEach
  void clearAmbientTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void directRepositoryAccessStillRequiresTheAuthenticatedAccountPeer() {
    assertThatThrownBy(() -> repository.acquire(request())).isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> repository.read(request())).isInstanceOf(SecurityException.class);
    verifyNoInteractions(dsl, manager, associations, lifecycle);
  }

  @Test
  void accountWorkloadWithAuthenticatedEndUserContextCannotReachOwnerTransactionOrSql() {
    SessionContext.setContext(
        WorldCanonicalPlayerAdmissionHoldTest.ACCOUNT, java.util.List.of(), java.util.Map.of());
    try {
      account()
          .run(
              () -> {
                assertThatThrownBy(() -> repository.acquire(request()))
                    .isInstanceOf(SecurityException.class);
                assertThatThrownBy(() -> repository.read(request()))
                    .isInstanceOf(SecurityException.class);
              });
      verifyNoInteractions(dsl, manager, associations, lifecycle);
    } finally {
      SessionContext.clear();
    }
  }

  @Test
  void authenticatedCallerCannotJoinAnAmbientTransactionForAcquireOrRead() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    account()
        .run(
            () -> {
              assertThatThrownBy(() -> repository.acquire(request()))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("no ambient");
              assertThatThrownBy(() -> repository.read(request()))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("no ambient");
            });
    verifyNoInteractions(dsl, manager, associations, lifecycle);
  }

  @Test
  void substitutedNamespaceDeniesBeforeOpeningSqlTransaction() {
    var carrier = WorldCanonicalPlayerAdmissionHoldTest.carrier();
    carrier.put("targetNamespace", "other");
    carrier.put("callerWorkload", "spiffe://firemud/ns/other/sa/game-session-service");
    var changed =
        new WorldCanonicalPlayerAdmissionHold.Request(
            net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence
                .fromCarrier(carrier),
            7L,
            8L);
    account()
        .run(
            () ->
                assertThatThrownBy(() -> repository.acquire(changed))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("namespace"));
    verifyNoInteractions(dsl, manager, associations, lifecycle);
  }

  private static WorldCanonicalPlayerAdmissionHold.Request request() {
    return new WorldCanonicalPlayerAdmissionHold.Request(
        WorldCanonicalPlayerAdmissionHoldTest.lease(), 7L, 8L);
  }

  private static Context account() {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/firemud/sa/account-service")
                .orElseThrow());
  }
}
