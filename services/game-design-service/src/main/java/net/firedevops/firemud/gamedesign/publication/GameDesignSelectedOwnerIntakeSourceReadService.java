package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadClient;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered selected-owner source read that obtains exact Account permission before opening the
 * independent Game Design source transaction.
 */
public final class GameDesignSelectedOwnerIntakeSourceReadService {
  private final GameDesignSourceRepository repository;
  private final SelectedOwnerIntakeSourceReadClient permissions;
  private final String namespace;
  private final TransactionTemplate transaction;

  public GameDesignSelectedOwnerIntakeSourceReadService(
      GameDesignSourceRepository repository,
      PlatformTransactionManager transactions,
      SelectedOwnerIntakeSourceReadClient permissions,
      String namespace) {
    this.repository = Objects.requireNonNull(repository, "source repository is required");
    this.permissions = Objects.requireNonNull(permissions, "Account permission client is required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
  }

  public SelectedOwnerIntakeSourceExport readSource(SelectedOwnerIntakeSourceReadScope scope) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + namespace + "/sa/account-service").equals(peer.uri())) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
    if (scope == null || !namespace.equals(scope.targetNamespace())) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
    if (!scope.intendedReader().equals(peer.uri())) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Independent Game Design owner read required")
          .asRuntimeException();
    }

    var permissionRequest = SelectedOwnerIntakeSourceReadEvidence.Request.create(namespace, scope);
    var permission = permissions.read(permissionRequest);
    if (permission == null || !permissionRequest.equals(permission.request())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact Account source permission required")
          .asRuntimeException();
    }

    return transaction.execute(
        ignored -> {
          var exported = repository.requireSelectedOwnerIntakeSource(scope);
          if (exported == null
              || !Arrays.equals(scope.canonicalBytes(), exported.scope().canonicalBytes())
              || !scope.digest().equals(exported.scope().digest())) {
            throw Status.FAILED_PRECONDITION
                .withDescription("Selected owner source scope differs")
                .asRuntimeException();
          }
          byte[] canonicalBytes = exported.canonicalBytes();
          if (!DraftAuthorizationFenceBinding.digest(canonicalBytes).equals(exported.digest())) {
            throw Status.FAILED_PRECONDITION
                .withDescription("Selected owner source export digest differs")
                .asRuntimeException();
          }
          return exported;
        });
  }
}
