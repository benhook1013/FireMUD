package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered exact synchronized source read with Account-confirmed reader permission. This read
 * does not grant source mutation or Game Logic retention authority.
 */
public final class GameDesignGameplayRuleSourceReadService {
  private final GameplayRuleSourceRepository repository;
  private final TransactionTemplate transaction;
  private final String namespace;
  private final net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadClient permissions;

  public GameDesignGameplayRuleSourceReadService(
      GameplayRuleSourceRepository repository,
      PlatformTransactionManager transactions,
      net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadClient permissions,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    this.permissions = Objects.requireNonNull(permissions);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
  }

  public GameplayRuleSourceReadEvidence read(GameplayRuleSourceReadEvidence.Request request) {
    requirePeer(namespace);
    if (!namespace.equals(request.targetNamespace()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION
          .withDescription("Independent GD owner read required")
          .asRuntimeException();
    String reader = GrpcPeerIdentity.current().uri();
    boolean accountReader =
        reader.equals("spiffe://firemud/ns/" + namespace + "/sa/account-service");
    if (accountReader != (request.proof() instanceof GameplayRuleSourceReadEvidence.Preliminary))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    var permissionRequest =
        net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadEvidence.Request.create(
            namespace, reader, request.purpose(), request.proof());
    var permission = permissions.read(permissionRequest);
    if (permission == null || !permissionRequest.equals(permission.request()))
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact Account source permission required")
          .asRuntimeException();
    return transaction.execute(
        ignored -> {
          var snapshot =
              repository.requireCompleteForGameLogic(
                  request.binding().target(), request.binding().commitId());
          if (!snapshot.binding().equals(request.binding()))
            throw Status.FAILED_PRECONDITION
                .withDescription("Selected rule binding differs")
                .asRuntimeException();
          if (request.proof() instanceof GameplayRuleSourceReadEvidence.Finalized finalized
              && !java.util.Arrays.equals(
                  snapshot.canonicalBytes(), finalized.authorization().source().canonicalBytes()))
            throw Status.FAILED_PRECONDITION
                .withDescription("Finalized source differs")
                .asRuntimeException();
          return new GameplayRuleSourceReadEvidence(
              request, new GameplayRuleSelectedSource(snapshot.canonicalJson()));
        });
  }

  static void requirePeer(String namespace) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    String prefix = "spiffe://firemud/ns/" + namespace + "/sa/";
    if (!(prefix + "account-service").equals(peer.uri())
        && !(prefix + "game-logic-service").equals(peer.uri()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
  }
}
