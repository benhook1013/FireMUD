package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadClient;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadClient;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadEvidence;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered composition of two authenticated owner reads and Account's immutable receipt.
 * Historical settlement grants neither creator authority nor runtime admission.
 */
public final class AccountSelectedDraftPublicationSettlementService {
  private final AccountPublicationAuthorizationRepository repository;
  private final GameDesignPublicationTerminalReadClient gameDesignClient;
  private final WorldPublicationTerminalReadClient worldClient;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Internal owner persistence and strict mTLS clients.")
  public AccountSelectedDraftPublicationSettlementService(
      AccountPublicationAuthorizationRepository repository,
      GameDesignPublicationTerminalReadClient gameDesignClient,
      WorldPublicationTerminalReadClient worldClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository);
    this.gameDesignClient = Objects.requireNonNull(gameDesignClient);
    this.worldClient = Objects.requireNonNull(worldClient);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /** Caller bytes identify the original order; only independent authenticated owners settle it. */
  public byte[] settle(byte[] originalOperation) {
    requireGameDesignPeer();
    requireNoAmbientTransaction();
    var operation = GameDesignPublicationOperationBinding.fromStored(originalOperation);
    if (!Arrays.equals(originalOperation, operation.canonicalBytes())
        || !workloadNamespace.equals(operation.world().request().targetNamespace())) {
      throw new IllegalArgumentException("Exact original publication operation namespace required");
    }
    var gameDesignRequest =
        GameDesignPublicationTerminalReadEvidence.Request.create(workloadNamespace, operation);
    var gameDesign = gameDesignClient.read(gameDesignRequest);
    if (gameDesign == null
        || !gameDesignRequest.equals(gameDesign.request())
        || gameDesign.terminalEvidence() == null) {
      throw new IllegalStateException("Game Design terminal differs from exact owner read request");
    }
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(
            gameDesign.terminalEvidence().canonicalBytes());
    if (!Arrays.equals(operation.canonicalBytes(), terminal.operationBytes())) {
      throw new IllegalStateException("Game Design terminal substitutes original operation");
    }
    requireNoAmbientTransaction();
    var worldRequest =
        WorldPublicationTerminalReadEvidence.Request.create(
            workloadNamespace, operation.canonicalBytes(), terminal.canonicalBytes());
    var world = worldClient.read(worldRequest);
    if (world == null
        || !worldRequest.equals(world.request())
        || world.worldOutcome() != worldRequest.expectedWorldOutcome()
        || world.terminalEvidence() == null
        || !Arrays.equals(operation.canonicalBytes(), world.terminalEvidence().operationBytes())
        || !Arrays.equals(terminal.canonicalBytes(), world.terminalEvidence().canonicalBytes())
        || !Arrays.equals(terminal.canonicalBytes(), world.worldTerminalEvidence())) {
      throw new IllegalStateException("World terminal differs from exact independent owner read");
    }
    requireNoAmbientTransaction();
    byte[] receipt =
        Objects.requireNonNull(
            ownerTransaction.execute(
                ignored ->
                    repository.settle(
                        operation,
                        terminal,
                        world.worldOutcome().name(),
                        world.worldTerminalEvidence())));
    // A second transaction establishes post-commit historical readback; no new issuer or
    // credential capture is involved. Neither SQL phase can contain an owner RPC.
    requireNoAmbientTransaction();
    byte[] committed =
        ownerTransaction.execute(
            ignored ->
                repository.settle(
                    operation,
                    terminal,
                    world.worldOutcome().name(),
                    world.worldTerminalEvidence()));
    if (!Arrays.equals(receipt, committed)) {
      throw new IllegalStateException("Committed Account settlement readback differs from receipt");
    }
    return receipt;
  }

  private void requireGameDesignPeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    }
    if (!("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service")
        .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Design workload required")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Publication settlement requires no ambient transaction")
          .asRuntimeException();
    }
  }
}
