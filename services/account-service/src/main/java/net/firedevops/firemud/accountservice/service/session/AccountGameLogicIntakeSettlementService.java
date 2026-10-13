package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeTerminalReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered GD-only producer: genuine GL terminal read completes before Account SQL. */
public final class AccountGameLogicIntakeSettlementService {
  private final AccountGameLogicIntakeAuthorizationRepository repository;
  private final GameLogicIntakeTerminalReadClient terminals;
  private final TransactionTemplate transaction;
  private final String namespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The injected repository is an intentionally shared service collaborator.")
  public AccountGameLogicIntakeSettlementService(
      AccountGameLogicIntakeAuthorizationRepository repository,
      GameLogicIntakeTerminalReadClient terminals,
      PlatformTransactionManager transactions,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    this.terminals = Objects.requireNonNull(terminals);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
  }

  public AccountGameLogicIntakeSettlement settle(GameLogicIntakeAuthorizationBinding original) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (!("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    Objects.requireNonNull(original);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION
          .withDescription("Terminal read must precede Account SQL")
          .asRuntimeException();
    var prior = transaction.execute(ignored -> repository.findSettlement(original, namespace));
    if (prior != null && prior.isPresent()) return prior.orElseThrow();
    var request = GameLogicIntakeTerminalReadEvidence.Request.create(namespace, original);
    var evidence = terminals.read(request);
    if (evidence == null || !request.equals(evidence.request()) || evidence.terminal().isEmpty())
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact definitive GL terminal required")
          .asRuntimeException();
    var terminal = evidence.terminal().orElseThrow();
    if (!namespace.equals(terminal.operation().targetNamespace())
        || !Arrays.equals(original.canonicalBytes(), terminal.authorizationBytes()))
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    return transaction.execute(
        ignored -> repository.settle(new AccountGameLogicIntakeSettlement(terminal)));
  }
}
