package net.firedevops.firemud.entitymanagement.sourceintake;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account-only lookup over Entity's immutable selected-source terminal receipt. */
public final class EntityEmptySelectedSourceIntakeTerminalReadService {
  private final EntityEmptySelectedSourceIntakeRepository repository;
  private final String namespace;

  public EntityEmptySelectedSourceIntakeTerminalReadService(
      EntityEmptySelectedSourceIntakeRepository repository, String namespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical namespace required");
    }
    this.namespace = namespace;
  }

  /** Called by the transport before decoding caller-carried evidence or touching owner storage. */
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

  /** Reads only the exact original COMMITTED_EMPTY receipt; absence is not terminal abort. */
  public EntitySelectedSourceIntakeTerminalReadEvidence read(
      EntitySelectedSourceIntakeTerminalReadEvidence.Request request) {
    requireAuthenticatedAccountCaller();
    Objects.requireNonNull(request, "terminal-read request is required");
    if (!namespace.equals(request.targetNamespace())) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    }

    final net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt
        receipt;
    try {
      receipt =
          repository
              .read(namespace, request.binding().intakeRequestId())
              .orElseThrow(() -> Status.NOT_FOUND.asRuntimeException());
    } catch (StatusRuntimeException denied) {
      throw denied;
    } catch (RuntimeException corrupt) {
      throw Status.DATA_LOSS
          .withDescription("Entity committed receipt is invalid")
          .asRuntimeException();
    }

    if (!Arrays.equals(request.binding().canonicalBytes(), receipt.authorizationBindingBytes())) {
      throw Status.ALREADY_EXISTS
          .withDescription("Entity receipt differs from the complete original authorization")
          .asRuntimeException();
    }
    try {
      return new EntitySelectedSourceIntakeTerminalReadEvidence(request, receipt);
    } catch (RuntimeException corrupt) {
      throw Status.DATA_LOSS
          .withDescription("Entity receipt differs from its original order")
          .asRuntimeException();
    }
  }
}
