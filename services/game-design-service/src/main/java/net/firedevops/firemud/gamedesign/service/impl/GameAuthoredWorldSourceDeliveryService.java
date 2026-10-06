package net.firedevops.firemud.gamedesign.service.impl;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ReadRequest;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository.DeliveryClaim;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One-item durable delivery pass used by the separately gated automatic source-delivery worker. */
public final class GameAuthoredWorldSourceDeliveryService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final GameAuthoredWorldSourceDeliveryRepository repository;
  private final WorldAuthoredSourceIntakeClient client;
  private final TransactionTemplate ownerWrite;
  private final String workloadNamespace;

  public GameAuthoredWorldSourceDeliveryService(
      GameAuthoredWorldSourceDeliveryRepository repository,
      WorldAuthoredSourceIntakeClient client,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.client = Objects.requireNonNull(client, "client");
    this.workloadNamespace = requireNamespace(workloadNamespace);
    this.ownerWrite = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.ownerWrite.setName("game-authored-world-source-delivery-acknowledge");
    this.ownerWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerWrite.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerWrite.setTimeout(10);
  }

  /**
   * Dispatches one persisted source claim and returns only a complete durably acknowledged result.
   */
  public DeliveryClaim deliver(UUID sourceOperationId) {
    requireNonNil(sourceOperationId, "sourceOperationId");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored-world source delivery cannot start inside an ambient transaction");
    }

    DeliveryClaim claim =
        repository
            .read(sourceOperationId)
            .orElseThrow(
                () ->
                    new DeliveryNotFoundException(
                        "No durable fresh authored-world delivery claim exists for the source"));
    if (!workloadNamespace.equals(claim.request().targetNamespace())) {
      throw new DeliveryConflictException(
          "Authored-world source delivery belongs to another workload namespace");
    }
    Optional<CommittedReceipt> acknowledged = claim.acknowledgedReceipt();
    if (acknowledged.isPresent()) {
      return claim;
    }

    // The outbox identity is stable, so a retry after an ambiguous response replays this exact
    // idempotent intake request. World dispatch and its independent read happen outside DB work.
    CommittedReceipt intakeReceipt = client.intake(claim.request());
    ReadRequest readRequest = new ReadRequest(claim.request(), freshReadRequestId(claim));
    CommittedReceipt readReceipt = client.read(readRequest);
    if (!intakeReceipt.equals(readReceipt)) {
      throw new DeliveryConflictException(
          "World intake and independent readback did not return the same complete receipt");
    }

    return persistAndReadBack(claim, readReceipt);
  }

  private DeliveryClaim persistAndReadBack(DeliveryClaim expected, CommittedReceipt receipt) {
    DeliveryClaim transactionResult;
    try {
      transactionResult = ownerWrite.execute(status -> repository.acknowledge(expected, receipt));
    } catch (RuntimeException transactionFailure) {
      // A JDBC/transaction-manager failure can occur after the database committed. Resolve that
      // ambiguity only from a fresh durable read; never dispatch again from this call.
      try {
        Optional<DeliveryClaim> recovered = repository.read(expected.source().operationId());
        if (recovered.isPresent()) {
          DeliveryClaim claim = recovered.orElseThrow();
          requireSameRequest(expected, claim);
          Optional<CommittedReceipt> durableReceipt = claim.acknowledgedReceipt();
          if (durableReceipt.isPresent()) {
            if (!receipt.equals(durableReceipt.orElseThrow())) {
              throw new DeliveryConflictException(
                  "A different World receipt won the durable delivery acknowledgement");
            }
            return claim;
          }
        }
      } catch (DeliveryConflictException conflict) {
        conflict.addSuppressed(transactionFailure);
        throw conflict;
      } catch (RuntimeException readFailure) {
        transactionFailure.addSuppressed(readFailure);
      }
      throw transactionFailure;
    }
    if (transactionResult == null) {
      throw new IllegalStateException(
          "World receipt acknowledgement transaction returned no proof");
    }

    Optional<DeliveryClaim> durableReadback = repository.read(expected.source().operationId());
    if (durableReadback.isEmpty()) {
      throw new IllegalStateException(
          "Committed World receipt acknowledgement is missing from independent owner readback");
    }
    DeliveryClaim readback = durableReadback.orElseThrow();
    requireSameRequest(expected, readback);
    if (!transactionResult.source().equals(readback.source())
        || !transactionResult.request().equals(readback.request())
        || !transactionResult.acknowledgedReceipt().equals(readback.acknowledgedReceipt())
        || readback.acknowledgedReceipt().isEmpty()
        || !receipt.equals(readback.acknowledgedReceipt().orElseThrow())) {
      throw new DeliveryConflictException(
          "Committed World receipt acknowledgement failed exact durable owner readback");
    }
    return readback;
  }

  private static void requireSameRequest(DeliveryClaim expected, DeliveryClaim actual) {
    if (!expected.source().equals(actual.source())
        || !expected.request().equals(actual.request())) {
      throw new DeliveryConflictException(
          "Authored-world source delivery changed while World intake was in flight");
    }
  }

  private static UUID freshReadRequestId(DeliveryClaim claim) {
    UUID requestId = UUID.randomUUID();
    while (NIL_UUID.equals(requestId)
        || requestId.equals(claim.request().intakeRequestId())
        || requestId.equals(claim.source().operationId())) {
      requestId = UUID.randomUUID();
    }
    return requestId;
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
  }

  private static String requireNamespace(String namespace) {
    Objects.requireNonNull(namespace, "workloadNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    return namespace;
  }

  public static class DeliveryConflictException extends IllegalStateException {
    public DeliveryConflictException(String message) {
      super(message);
    }
  }

  public static final class DeliveryNotFoundException extends IllegalStateException {
    public DeliveryNotFoundException(String message) {
      super(message);
    }
  }
}
