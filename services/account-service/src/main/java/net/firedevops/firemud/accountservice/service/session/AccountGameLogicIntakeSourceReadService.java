package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.time.Clock;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadScope;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered GD-only readback of existing source-read authority; never issues a grant. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Internal Account owner collaborators.")
public final class AccountGameLogicIntakeSourceReadService {
  private final AccountGameLogicIntakeAuthorizationRepository repository;
  private final AccountControlUiCoordination registry;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final String namespace;

  public AccountGameLogicIntakeSourceReadService(
      AccountGameLogicIntakeAuthorizationRepository repository,
      AccountControlUiCoordination registry,
      PlatformTransactionManager transactions,
      Clock clock,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    this.registry = Objects.requireNonNull(registry);
    this.clock = Objects.requireNonNull(clock);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public GameLogicIntakeSourceReadScope readSourceScope(
      GameLogicIntakeSourceReadScope scope, String intendedReader, String purpose) {
    requirePeer();
    requireReader(intendedReader, purpose, "account-service");
    Objects.requireNonNull(scope);
    if (!namespace.equals(scope.targetNamespace())
        || !intendedReader.equals(scope.intendedReader())
        || !purpose.equals(scope.purpose())) throw Status.PERMISSION_DENIED.asRuntimeException();
    outsideSql();
    var current =
        Objects.requireNonNull(
            transaction.execute(ignored -> repository.sourceReadCurrentness(scope)));
    var active = registry.readActive(current.tokenHash());
    if (!current.registryDigest().equals(DraftAuthorizationFenceBinding.digest(active)))
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    return transaction.execute(
        ignored -> {
          repository.readSourceScope(scope, current, clock.instant());
          return scope;
        });
  }

  public GameLogicIntakeAuthorizationBinding readFinalizedIntake(
      GameLogicIntakeAuthorizationBinding authorization, String intendedReader, String purpose) {
    requirePeer();
    requireReader(intendedReader, purpose, "game-logic-service");
    Objects.requireNonNull(authorization);
    outsideSql();
    return transaction.execute(
        ignored -> {
          repository.readFinalizedSourceScope(authorization, namespace);
          return authorization;
        });
  }

  private void requireReader(String reader, String purpose, String service) {
    if (!("spiffe://firemud/ns/" + namespace + "/sa/" + service).equals(reader)
        || !GameLogicIntakeSourceReadScope.PURPOSE.equals(purpose))
      throw Status.PERMISSION_DENIED.asRuntimeException();
  }

  private void requirePeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (!("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION.asRuntimeException();
  }
}
