package net.firedevops.firemud.gamelogic.sourceintake;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone Account-only read over actual immutable Game Logic storage. */
public final class GameLogicGameplayRuleIntakeTerminalReadService {
  private final GameLogicGameplayRuleIntakeRepository repository;
  private final String namespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The injected repository is an intentionally shared service collaborator.")
  public GameLogicGameplayRuleIntakeTerminalReadService(
      GameLogicGameplayRuleIntakeRepository repository, String namespace) {
    this.repository = Objects.requireNonNull(repository);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
  }

  public GameLogicIntakeTerminalReadEvidence read(
      GameLogicIntakeTerminalReadEvidence.Request request) {
    requirePeer(namespace);
    if (!namespace.equals(request.targetNamespace()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    var original = new GameLogicGameplayRuleIntakeOperation(namespace, request.binding());
    var terminal = repository.findTerminal(request.binding().operationId());
    terminal.ifPresent(
        value -> {
          if (!Arrays.equals(original.canonicalBytes(), value.operation().canonicalBytes()))
            throw Status.ALREADY_EXISTS.asRuntimeException();
        });
    return new GameLogicIntakeTerminalReadEvidence(request, terminal);
  }

  static void requirePeer(String namespace) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (!("spiffe://firemud/ns/" + namespace + "/sa/account-service").equals(peer.uri()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
  }
}
