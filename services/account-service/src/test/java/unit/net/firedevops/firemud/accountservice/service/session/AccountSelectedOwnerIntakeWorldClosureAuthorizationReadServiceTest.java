package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic Account read tests; they do not establish PostgreSQL or owner-terminal proof. */
class AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceTest {
  private final AccountSelectedOwnerIntakeSourceReservationRepository repository =
      mock(AccountSelectedOwnerIntakeSourceReservationRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  @AfterEach
  void clearCallerAndTransactionContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void readsExactEntityAndAutomationBindingsInIndependentReadCommittedTransactions() {
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
    var service = newService();

    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var binding = AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(owner);
      var request =
          SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create("test", binding);
      asWorldReader(() -> service.requireHeld(request));
      verify(repository).readHeldFinalAuthorization(binding);
    }

    var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions, times(2)).getTransaction(definitions.capture());
    assertThat(definitions.getAllValues())
        .allSatisfy(
            definition -> {
              assertThat(definition.getPropagationBehavior())
                  .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              assertThat(definition.getIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
              assertThat(definition.isReadOnly()).isFalse();
            });
  }

  @Test
  void independentlyRejectsWrongWorkloadNamespaceAndEndUserBeforeRepositoryAccess() {
    var service = newService();
    var binding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.ENTITY_MANAGEMENT);
    var request =
        SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create("test", binding);

    assertCode(Status.Code.UNAUTHENTICATED, () -> service.requireHeld(request));
    asPeer(
        "test",
        "entity-management-service",
        () -> assertCode(Status.Code.PERMISSION_DENIED, () -> service.requireHeld(request)));
    asPeer(
        "other",
        "world-management-service",
        () -> assertCode(Status.Code.PERMISSION_DENIED, () -> service.requireHeld(request)));

    SessionContext.setContext("44", List.of(), Map.of());
    try {
      asWorldReader(
          () -> assertCode(Status.Code.PERMISSION_DENIED, () -> service.requireHeld(request)));
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(repository, transactions);
  }

  @Test
  void rejectsAmbientSqlBeforeStartingOwnerRead() {
    var service = newService();
    var binding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var request =
        SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create("test", binding);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      asWorldReader(
          () -> assertCode(Status.Code.FAILED_PRECONDITION, () -> service.requireHeld(request)));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(repository, transactions);
  }

  @Test
  void distinguishesAbsentOrChangedAuthorizationFromUnavailableStorage() {
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
    var service = newService();
    var binding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var request =
        SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create("test", binding);

    org.mockito.Mockito.doThrow(new IllegalArgumentException("not available"))
        .when(repository)
        .readHeldFinalAuthorization(binding);
    asWorldReader(
        () -> assertCode(Status.Code.FAILED_PRECONDITION, () -> service.requireHeld(request)));

    org.mockito.Mockito.doThrow(new IllegalStateException("storage unavailable"))
        .when(repository)
        .readHeldFinalAuthorization(binding);
    asWorldReader(() -> assertCode(Status.Code.UNAVAILABLE, () -> service.requireHeld(request)));
  }

  private AccountSelectedOwnerIntakeWorldClosureAuthorizationReadService newService() {
    return new AccountSelectedOwnerIntakeWorldClosureAuthorizationReadService(
        repository, transactions, "test");
  }

  private static void asWorldReader(Runnable action) {
    asPeer("test", "world-management-service", action);
  }

  private static void asPeer(String namespace, String workload, Runnable action) {
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                .orElseThrow())
        .run(action);
  }

  private static void assertCode(Status.Code expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(error -> assertThat(Status.fromThrowable(error).getCode()).isEqualTo(expected));
  }
}
