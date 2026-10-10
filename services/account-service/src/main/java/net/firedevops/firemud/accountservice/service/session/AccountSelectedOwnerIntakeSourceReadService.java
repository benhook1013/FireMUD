package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Objects;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered exact pending Entity/Automation source readback; never finalizes owner retention.
 */
public final class AccountSelectedOwnerIntakeSourceReadService {
  private final AccountSelectedOwnerIntakeSourceReservationRepository repository;
  private final AccountControlUiCoordination registry;
  private final TransactionTemplate transaction;
  private final String namespace;

  public AccountSelectedOwnerIntakeSourceReadService(
      AccountSelectedOwnerIntakeSourceReservationRepository repository,
      AccountControlUiCoordination registry,
      PlatformTransactionManager transactions,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    this.registry = Objects.requireNonNull(registry);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
  }

  /**
   * Confirms one exact, still-live preliminary source reservation. The registry read is outside
   * both independent READ_COMMITTED SQL transactions; this result grants no retention authority.
   */
  public SelectedOwnerIntakeSourceReadScope readSourceScope(
      SelectedOwnerIntakeSourceReadScope scope, String intendedReader, String purpose) {
    requireGameDesignPeer();
    Objects.requireNonNull(scope, "source-read scope is required");
    if (!namespace.equals(scope.targetNamespace())
        || !intendedReader(scope.owner()).equals(intendedReader)
        || !scope.intendedReader().equals(intendedReader)
        || !scope.purpose().equals(purpose)) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
    requireOutsideSql();
    var current =
        Objects.requireNonNull(
            transaction.execute(ignored -> repository.sourceReadCurrentness(scope)));
    var active = registry.readActive(current.tokenHash());
    if (!current.registryDigest().equals(DraftAuthorizationFenceBinding.digest(active))) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Original creator registry evidence is no longer exact")
          .asRuntimeException();
    }
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              repository.readSourceScope(scope, current);
              return scope;
            }));
  }

  private String intendedReader(Owner owner) {
    if (owner != Owner.ENTITY_MANAGEMENT && owner != Owner.AUTOMATION_SCRIPTING)
      throw Status.PERMISSION_DENIED.asRuntimeException();
    return "spiffe://firemud/ns/" + namespace + "/sa/account-service";
  }

  private void requireGameDesignPeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "Exact same-namespace Game Design workload without end-user context required")
          .asRuntimeException();
    }
  }

  private static void requireOutsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Selected-owner source read requires independent Account transactions")
          .asRuntimeException();
    }
  }
}
