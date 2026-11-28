package net.firedevops.firemud.gamedesign.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository.DeliveryClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Executes one explicitly invoked, bounded pass over this namespace's durable fresh-source claims.
 */
public final class GameAuthoredWorldSourceDeliveryWorker {
  private static final Logger LOG =
      LoggerFactory.getLogger(GameAuthoredWorldSourceDeliveryWorker.class);

  private final GameAuthoredWorldSourceDeliveryRepository repository;
  private final GameAuthoredWorldSourceDeliveryService deliveryService;
  private final int batchSize;
  private final String workloadNamespace;
  private final AtomicBoolean passRunning = new AtomicBoolean();
  private volatile UUID afterIntakeRequestId;

  public GameAuthoredWorldSourceDeliveryWorker(
      GameAuthoredWorldSourceDeliveryRepository repository,
      GameAuthoredWorldSourceDeliveryService deliveryService,
      int batchSize,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    if (batchSize < 1
        || batchSize > GameAuthoredWorldSourceDeliveryRepository.MAX_PENDING_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "Authored-world delivery batch size is outside the supported bounds");
    }
    this.batchSize = batchSize;
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "Game Design workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Reads at most one indexed page and dispatches each stable claim at most once in this pass. */
  public int runPass() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored-world source delivery pass requires no ambient transaction");
    }
    if (!passRunning.compareAndSet(false, true)) {
      return 0;
    }
    try {
      List<DeliveryClaim> claims =
          repository.readPending(workloadNamespace, afterIntakeRequestId, batchSize);
      if (claims.isEmpty()) {
        afterIntakeRequestId = null;
        return 0;
      }

      for (DeliveryClaim claim : claims) {
        // Move the cursor before remote work. An ambiguous item remains pending but cannot hold
        // later claims behind it; a full cycle brings it back for an exact-identity retry.
        afterIntakeRequestId = claim.request().intakeRequestId();
        try {
          deliveryService.deliver(claim.source().operationId());
        } catch (RuntimeException failure) {
          LOG.warn(
              "Authored-world source delivery remains pending for operation {}",
              claim.source().operationId(),
              failure);
        }
      }
      if (claims.size() < batchSize) {
        afterIntakeRequestId = null;
      }
      return claims.size();
    } catch (RuntimeException failure) {
      LOG.warn(
          "Authored-world source delivery page read failed; durable claims remain pending",
          failure);
      return 0;
    } finally {
      passRunning.set(false);
    }
  }
}
