package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Standalone, unregistered read of complete original publication terminal evidence. This reads
 * historical owner truth without releasing Account sources, completing World operations, or
 * granting current authority or runtime admission.
 */
public final class GameDesignPublicationTerminalReadService {
  private final GameDesignPublicationOperationRepository repository;
  private final TransactionTemplate snapshot;
  private final String workloadNamespace;

  public GameDesignPublicationTerminalReadService(
      GameDesignPublicationOperationRepository repository,
      PlatformTransactionManager transactions,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    snapshot = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    snapshot.setName("game-design-publication-terminal-read");
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
  }

  /** Complete original operation bytes are lookup identity, never caller authority. */
  public GameDesignPublicationTerminalEvidence read(
      String targetNamespace, byte[] originalOperationBytes) {
    requirePeer();
    if (!workloadNamespace.equals(targetNamespace)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Publication read namespace differs from peer")
          .asRuntimeException();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Independent publication terminal snapshot required")
          .asRuntimeException();
    }
    final GameDesignPublicationOperationBinding requested;
    try {
      requested =
          GameDesignPublicationOperationBinding.fromStored(
              Objects.requireNonNull(originalOperationBytes, "originalOperationBytes"));
    } catch (RuntimeException malformed) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Canonical complete publication operation required")
          .asRuntimeException();
    }
    if (!workloadNamespace.equals(requested.world().request().targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Original publication namespace differs")
          .asRuntimeException();
    }
    try {
      return Objects.requireNonNull(
          snapshot.execute(
              status -> {
                // The binding validates this workflow against the canonical tenant and original
                // request.
                var stored =
                    repository
                        .read(requested.world().request().publishWorkflowId())
                        .orElseThrow(
                            () ->
                                Status.NOT_FOUND
                                    .withDescription("Publication operation unavailable")
                                    .asRuntimeException());
                if (!Arrays.equals(requested.canonicalBytes(), stored.operation().canonicalBytes())
                    || !("PUBLISHED".equals(stored.outcome())
                        || "NO_PUBLICATION".equals(stored.outcome()))
                    || stored.terminalEvidenceBytes() == null) {
                  throw Status.FAILED_PRECONDITION
                      .withDescription("Exact complete terminal publication evidence unavailable")
                      .asRuntimeException();
                }
                byte[] originalTerminalBytes = stored.terminalEvidenceBytes();
                var terminal =
                    GameDesignPublicationTerminalEvidence.fromStored(originalTerminalBytes);
                if (!Arrays.equals(requested.canonicalBytes(), terminal.operationBytes())
                    || !stored.outcome().equals(terminal.outcome().name())
                    || !Arrays.equals(originalTerminalBytes, terminal.canonicalBytes())) {
                  throw Status.FAILED_PRECONDITION
                      .withDescription("Terminal evidence differs from original publication")
                      .asRuntimeException();
                }
                return terminal;
              }),
          "Publication terminal snapshot returned no result");
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Publication terminal owner storage unavailable")
          .asRuntimeException();
    }
  }

  private void requirePeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    }
    if (!("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service").equals(peer.uri())
        && !("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service")
            .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Account or World Management workload required")
          .asRuntimeException();
    }
  }
}
