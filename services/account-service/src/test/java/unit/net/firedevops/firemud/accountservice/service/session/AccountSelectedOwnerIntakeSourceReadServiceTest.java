package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic owner-context tests; these do not establish TLS or PostgreSQL runtime proof. */
class AccountSelectedOwnerIntakeSourceReadServiceTest {
  private final AccountSelectedOwnerIntakeSourceReservationRepository repository =
      mock(AccountSelectedOwnerIntakeSourceReservationRepository.class);
  private final AccountControlUiCoordination registry = mock(AccountControlUiCoordination.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final AccountSelectedOwnerIntakeSourceReadService service =
      new AccountSelectedOwnerIntakeSourceReadService(repository, registry, transactions, "test");

  @AfterEach
  void clearContextAndTransaction() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactCreatorRegistryReadOccursBetweenIndependentSqlReadbacks() {
    var scope = scope("test");
    assertThat(scope.selected().requiredOwners())
        .doesNotContain(DraftCommitBinding.Owner.ENTITY_MANAGEMENT)
        .doesNotContain(DraftCommitBinding.Owner.AUTOMATION_SCRIPTING);
    byte[] active = "exact-active-registry".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var current =
        new AccountSelectedOwnerIntakeSourceReservationRepository.SourceReadCurrentness(
            "token-hash",
            DraftAuthorizationFenceBinding.digest(active),
            1_900_000_000L,
            "issuer-evidence",
            "complete-source-vector");
    TransactionStatus status = new SimpleTransactionStatus();
    when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    when(repository.sourceReadCurrentness(scope)).thenReturn(current);
    when(registry.readActive("token-hash")).thenReturn(active);
    asPeer(
        "test",
        "game-design-service",
        () -> service.readSourceScope(scope, scope.intendedReader(), scope.purpose()));

    InOrder order = inOrder(repository, registry);
    order.verify(repository).sourceReadCurrentness(scope);
    order.verify(registry).readActive("token-hash");
    order.verify(repository).readSourceScope(scope, current);
    verify(transactions, times(2)).commit(status);

    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions, times(2)).getTransaction(definition.capture());
    assertThat(definition.getAllValues())
        .allSatisfy(
            value -> {
              assertThat(value.getPropagationBehavior())
                  .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              assertThat(value.getIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
              assertThat(value.isReadOnly()).isFalse();
            });
  }

  @Test
  void changedActiveRegistryDeniesBeforeFinalDatabaseConfirmation() {
    var scope = scope("test");
    byte[] originalActive = "original-active".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    TransactionStatus status = new SimpleTransactionStatus();
    when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    when(repository.sourceReadCurrentness(scope))
        .thenReturn(
            new AccountSelectedOwnerIntakeSourceReservationRepository.SourceReadCurrentness(
                "token-hash",
                DraftAuthorizationFenceBinding.digest(originalActive),
                1_900_000_000L,
                "issuer-evidence",
                "complete-source-vector"));
    when(registry.readActive("token-hash")).thenReturn("replaced-active".getBytes());

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            asPeer(
                "test",
                "game-design-service",
                () -> service.readSourceScope(scope, scope.intendedReader(), scope.purpose())));

    verify(repository, never()).readSourceScope(any(), any());
    verify(transactions).commit(status);
  }

  @Test
  void authenticatesExactPeerAndRejectsEndUserContextBeforeScopeOrStorageAccess() {
    assertCode(Status.Code.UNAUTHENTICATED, () -> service.readSourceScope(null, null, null));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () ->
            asPeer(
                "other", "game-design-service", () -> service.readSourceScope(null, null, null)));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> asPeer("test", "account-service", () -> service.readSourceScope(null, null, null)));

    SessionContext.setContext("44", List.of(), Map.of());
    try {
      assertCode(
          Status.Code.PERMISSION_DENIED,
          () ->
              asPeer(
                  "test", "game-design-service", () -> service.readSourceScope(null, null, null)));
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(repository, registry, transactions);
  }

  private static SelectedOwnerIntakeSourceReadScope scope(String namespace) {
    UUID tenant = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    UUID version = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    var selected =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                tenant, version, 1L, "tenant-key", 2L, "tenant-key", "NEW_GAME_ROW"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "TEMPLATE_CONFIG",
                    "templates",
                    "TENANT",
                    tenant.toString(),
                    "0")));
    return new SelectedOwnerIntakeSourceReadScope(
        DraftCommitBinding.Owner.ENTITY_MANAGEMENT,
        namespace,
        UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
        UUID.fromString("99999999-9999-4999-8999-999999999999"),
        selected.requestId(),
        UUID.fromString("12121212-1212-4212-8212-121212121212"),
        selected);
  }

  private static void assertCode(Status.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static void asPeer(String namespace, String workload, Runnable action) {
    var peer =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var previous = peer.attach();
    try {
      action.run();
    } finally {
      peer.detach(previous);
    }
  }
}
