package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly unwired World owner step for retaining the complete released-content launch binding.
 *
 * <p>This proves neither local content nor lifecycle preparation, current authority, or runtime
 * activation.
 */
public final class WorldCompleteLaunchBindingService {
  private final AuthoredWorldLaunchDescriptorClient client;
  private final WorldCompleteLaunchBindingRepository repository;
  private final WorldAuthoredSourceIntakeRepository sourceIntakeRepository;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  public WorldCompleteLaunchBindingService(
      AuthoredWorldLaunchDescriptorClient client,
      WorldCompleteLaunchBindingRepository repository,
      WorldAuthoredSourceIntakeRepository sourceIntakeRepository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.client = Objects.requireNonNull(client, "client");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.sourceIntakeRepository =
        Objects.requireNonNull(sourceIntakeRepository, "sourceIntakeRepository");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Binds the exact immutable Game Design read selector to the already committed local source.
   *
   * <p>The verified same-namespace Game Session workload is required before any owner read or Game
   * Design call. Existing claims are resolved first so retries cannot select newer defaults.
   */
  public WorldCompleteLaunchBindingReceipt bind(GetRequest request) {
    requireAuthenticatedGameSessionCaller();
    requireNoAmbientTransaction();
    validateRequest(request);

    AuthoredWorldLaunchDescriptorEvidence.Request expected = request.expectedRequest();
    Optional<WorldCompleteLaunchBindingRepository.StoredBinding> prior =
        repository.read(
            workloadNamespace, expected.canonicalTenantId(), expected.controlPlaneRequestId());
    if (prior.isPresent()) {
      WorldCompleteLaunchBindingRepository.StoredBinding stored = prior.orElseThrow();
      requireStoredRequest(stored, request);
      WorldCompleteLaunchBindingReceipt receipt = loadExactSourceReceipt(stored);
      return receipt;
    }

    CompleteLaunchBindingEvidence fetched =
        client.getComplete(AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(request));
    requireFetchedEvidence(fetched, request);
    WorldAuthoredSourceIntakeReceipt sourceReceipt = readExactSourceReceipt(fetched);

    WorldCompleteLaunchBindingReceipt accepted;
    try {
      accepted =
          ownerTransaction.execute(
              status -> repository.acceptFresh(workloadNamespace, sourceReceipt, fetched));
    } catch (RuntimeException ownerFailure) {
      // A commit acknowledgment can be lost after PostgreSQL made the immutable claim durable.
      Optional<WorldCompleteLaunchBindingRepository.StoredBinding> uncertainCommit =
          repository.read(
              workloadNamespace, expected.canonicalTenantId(), expected.controlPlaneRequestId());
      if (uncertainCommit.isEmpty()) {
        throw ownerFailure;
      }
      WorldCompleteLaunchBindingReceipt recovered =
          recoverExactSourceReceipt(uncertainCommit.orElseThrow(), request);
      requireSamePair(recovered, fetched);
      return recovered;
    }
    if (accepted == null) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "World owner transaction returned no complete launch binding receipt");
    }
    requireStoredRequest(accepted, request);
    requireSamePair(accepted, fetched);

    Optional<WorldCompleteLaunchBindingRepository.StoredBinding> committed =
        repository.read(
            workloadNamespace, expected.canonicalTenantId(), expected.controlPlaneRequestId());
    if (committed.isEmpty()) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "World complete launch binding is missing after its owner commit");
    }
    WorldCompleteLaunchBindingRepository.StoredBinding stored = committed.orElseThrow();
    requireStoredRequest(stored, request);
    WorldCompleteLaunchBindingReceipt readback = loadExactSourceReceipt(stored);
    if (!accepted.equals(readback)) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "World complete launch binding changed during independent commit readback");
    }
    return readback;
  }

  private WorldCompleteLaunchBindingReceipt loadExactSourceReceipt(
      WorldCompleteLaunchBindingRepository.StoredBinding stored) {
    CompleteLaunchBindingEvidence evidence = stored.evidence();
    WorldAuthoredSourceIntakeReceipt sourceReceipt = readExactSourceReceipt(evidence);
    return repository.toReceipt(stored, sourceReceipt);
  }

  private WorldCompleteLaunchBindingReceipt recoverExactSourceReceipt(
      WorldCompleteLaunchBindingRepository.StoredBinding stored, GetRequest request) {
    requireStoredRequest(stored, request);
    return loadExactSourceReceipt(stored);
  }

  private WorldAuthoredSourceIntakeReceipt readExactSourceReceipt(
      CompleteLaunchBindingEvidence evidence) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = evidence.descriptor();
    return sourceIntakeRepository
        .readBySource(
            workloadNamespace,
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest())
        .orElseThrow(
            () ->
                new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
                    "Complete launch binding has no exact committed World source intake"));
  }

  private void requireStoredRequest(WorldCompleteLaunchBindingReceipt receipt, GetRequest request) {
    requireStoredRequest(receipt.descriptor(), receipt.targetNamespace(), request);
  }

  private void requireStoredRequest(
      WorldCompleteLaunchBindingRepository.StoredBinding stored, GetRequest request) {
    requireStoredRequest(stored.evidence().descriptor(), stored.targetNamespace(), request);
    if (!stored.canonicalTenantId().equals(request.expectedRequest().canonicalTenantId())
        || !stored
            .controlPlaneRequestId()
            .equals(request.expectedRequest().controlPlaneRequestId())) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "World complete launch binding stable request identity changed");
    }
  }

  private void requireStoredRequest(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      String targetNamespace,
      GetRequest request) {
    AuthoredWorldLaunchDescriptorEvidence.Request expected = request.expectedRequest();
    if (!descriptor.request().equals(expected)
        || !descriptor.resultDigest().equals(request.expectedResultDigest())
        || !workloadNamespace.equals(descriptor.targetNamespace())
        || !workloadNamespace.equals(targetNamespace)) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "World complete launch binding request identity was reused with changed request or result");
    }
  }

  private void requireFetchedEvidence(CompleteLaunchBindingEvidence evidence, GetRequest request) {
    if (evidence == null) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "Game Design returned no complete launch binding evidence");
    }
    AuthoredWorldLaunchDescriptorEvidence descriptor = evidence.descriptor();
    descriptor.requireValid();
    evidence.releaseAttestation().requireValid(descriptor);
    if (!descriptor.request().equals(request.expectedRequest())
        || !descriptor.resultDigest().equals(request.expectedResultDigest())
        || !workloadNamespace.equals(descriptor.targetNamespace())) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "Game Design complete launch binding differs from the exact read request");
    }
  }

  private void requireSamePair(
      WorldCompleteLaunchBindingReceipt receipt, CompleteLaunchBindingEvidence expected) {
    if (!receipt.evidence().equals(expected)) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "World complete launch binding request identity was reused with another release pair");
    }
  }

  private void validateRequest(GetRequest request) {
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.expectedRequest().targetNamespace())) {
      throw new SecurityException(
          "Complete launch binding request namespace does not match this World workload");
    }
  }

  private void requireAuthenticatedGameSessionCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("game-session-service")
        || !peer.isInNamespace(workloadNamespace)) {
      throw new SecurityException(
          "World complete launch binding requires the authenticated same-namespace Game Session workload");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World complete launch binding cannot enter from an ambient transaction");
    }
  }
}
