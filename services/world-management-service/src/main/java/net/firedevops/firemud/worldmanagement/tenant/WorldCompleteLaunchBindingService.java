package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly unwired World owner step for retaining the complete released-content launch binding.
 *
 * <p>Neither binding nor its current-Version read proves local content, commit fencing, lifecycle
 * preparation, or runtime admission.
 */
public final class WorldCompleteLaunchBindingService {
  private final AuthoredWorldLaunchDescriptorClient client;
  private final AuthoredWorldVersionStateClient versionStateClient;
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
    this(client, repository, sourceIntakeRepository, transactionManager, workloadNamespace, null);
  }

  /**
   * Constructs the explicitly unwired binding verifier with its separately authenticated Game
   * Design current-version reader.
   */
  public WorldCompleteLaunchBindingService(
      AuthoredWorldLaunchDescriptorClient client,
      WorldCompleteLaunchBindingRepository repository,
      WorldAuthoredSourceIntakeRepository sourceIntakeRepository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace,
      AuthoredWorldVersionStateClient versionStateClient) {
    this.client = Objects.requireNonNull(client, "client");
    this.versionStateClient = versionStateClient;
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

  /**
   * Reads current Game Design version evidence for an already committed, exact World binding.
   *
   * <p>This read never creates or changes the historical binding. It establishes only current
   * source-owned Version eligibility at the descriptor epoch; it does not prove World content,
   * commit fencing, preparation, or admission.
   */
  public AuthoredWorldVersionStateEvidence readCurrentVersionState(
      GetRequest request, UUID currentReadId) {
    requireAuthenticatedGameSessionCaller();
    requireNoAmbientTransaction();
    validateRequest(request);
    requireNonNilCurrentReadId(currentReadId);
    if (versionStateClient == null) {
      throw new IllegalStateException(
          "World current-version verification has no configured Game Design reader");
    }

    AuthoredWorldLaunchDescriptorEvidence.Request expected = request.expectedRequest();
    WorldCompleteLaunchBindingRepository.StoredBinding stored =
        repository
            .read(workloadNamespace, expected.canonicalTenantId(), expected.controlPlaneRequestId())
            .orElseThrow(
                () ->
                    new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
                        "World current-version verification requires a committed launch binding"));
    requireStoredRequest(stored, request);
    WorldCompleteLaunchBindingReceipt committed = loadExactSourceReceipt(stored);
    requireStoredRequest(committed, request);
    requireFreshCurrentReadId(currentReadId, request, committed);

    AuthoredWorldLaunchDescriptorEvidence descriptor = committed.descriptor();
    // This is the Game Design-owned Version selector, never World's private local tenant key.
    AuthoredWorldVersionStateEvidence.Request currentRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            descriptor.targetNamespace(),
            currentReadId,
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.versionId());
    AuthoredWorldVersionStateEvidence current;
    try {
      current = versionStateClient.read(currentRequest);
    } catch (RuntimeException exception) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "Current Game Design version-state evidence is unavailable or invalid", exception);
    }
    if (current == null
        || !currentRequest.equals(current.request())
        || !committed.sourceIntakeReceipt().source().equals(current.sourceEvidence())) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "Game Design current-version evidence differs from the exact committed World source");
    }
    UUID committedCanonicalVersionId =
        committed.evidence().releaseAttestation().canonicalVersionId();
    if (!committedCanonicalVersionId.equals(current.canonicalVersionId())) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "Game Design current canonical version UUID differs from the committed release attestation");
    }
    if (current.versionState() != VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED
        && current.versionState() != VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "Game Design current Version is not PUBLISHED or ACTIVE");
    }
    if (current.versionStateEpoch() != descriptor.versionStateEpoch()) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "Game Design current Version epoch differs from the immutable launch descriptor");
    }
    return current;
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

  private static void requireNonNilCurrentReadId(UUID currentReadId) {
    if (currentReadId == null || currentReadId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Current version-state read ID must be a non-nil UUID");
    }
  }

  private static void requireFreshCurrentReadId(
      UUID currentReadId, GetRequest request, WorldCompleteLaunchBindingReceipt committed) {
    WorldAuthoredSourceIntakeReceipt intake = committed.sourceIntakeReceipt();
    if (currentReadId.equals(request.requestId())
        || currentReadId.equals(committed.operationId())
        || currentReadId.equals(intake.operationId())
        || currentReadId.equals(intake.intakeRequestId())
        || currentReadId.equals(intake.sourceOperationId())
        || currentReadId.equals(intake.source().registrationRequestId())) {
      throw new IllegalArgumentException(
          "Current version-state read ID must be separate from source and registration IDs");
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
