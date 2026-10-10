package net.firedevops.firemud.automationscripting.sourceintake;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone Account-only lookup over Automation's immutable selected-source receipt. */
public final class AutomationEmptySelectedSourceIntakeTerminalReadService {
  private final AutomationEmptySelectedSourceIntakeRepository repository;
  private final String namespace;

  public AutomationEmptySelectedSourceIntakeTerminalReadService(
      AutomationEmptySelectedSourceIntakeRepository repository, String namespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical namespace required");
    }
    this.namespace = namespace;
  }

  /** Called by the transport before request decoding so unauthorized peers never reach a lookup. */
  void requireAuthenticatedAccountCaller() {
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED.asRuntimeException();
    }
    if (!namespace.equals(peer.namespace())
        || !"account-service".equals(peer.service())
        || !("spiffe://firemud/ns/" + namespace + "/sa/account-service").equals(peer.uri())) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
  }

  public AutomationSelectedSourceIntakeTerminalReadEvidence read(
      AutomationSelectedSourceIntakeTerminalReadEvidence.Request request) {
    requireAuthenticatedAccountCaller();
    Objects.requireNonNull(request, "terminal-read request is required");
    if (!namespace.equals(request.targetNamespace())) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    }

    final AutomationEmptySelectedSourceIntakeReceipt receipt;
    try {
      receipt =
          repository
              .readCommittedTerminal(request.binding())
              .orElseThrow(() -> Status.NOT_FOUND.asRuntimeException());
    } catch (StatusRuntimeException denied) {
      throw denied;
    } catch (AutomationEmptySelectedSourceIntakeRepository.IntakeConflictException changed) {
      throw Status.ALREADY_EXISTS
          .withDescription("Automation receipt differs from the complete original authorization")
          .asRuntimeException();
    } catch (RuntimeException corrupt) {
      throw Status.DATA_LOSS
          .withDescription("Automation committed receipt is invalid")
          .asRuntimeException();
    }

    try {
      return new AutomationSelectedSourceIntakeTerminalReadEvidence(request, receipt);
    } catch (IllegalArgumentException invalid) {
      throw Status.DATA_LOSS
          .withDescription("Automation receipt differs from its original order")
          .asRuntimeException();
    }
  }
}
