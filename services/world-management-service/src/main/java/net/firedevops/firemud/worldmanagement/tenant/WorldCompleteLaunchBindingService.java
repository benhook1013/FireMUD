package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationClient;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
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
  private final CanonicalGameInstanceLaunchAssociationClient gameSessionClient;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  public WorldCompleteLaunchBindingService(
      AuthoredWorldLaunchDescriptorClient client,
      WorldCompleteLaunchBindingRepository repository,
      WorldAuthoredSourceIntakeRepository sourceIntakeRepository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this(
        client,
        repository,
        sourceIntakeRepository,
        transactionManager,
        workloadNamespace,
        null,
        null,
        true);
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
    this(
        client,
        repository,
        sourceIntakeRepository,
        transactionManager,
        workloadNamespace,
        versionStateClient,
        null,
        true);
  }

  /**
   * Constructs the explicitly unwired committed-launch producer with its actual Game Session
   * owner-read client. The client is required for the committed-launch entry point.
   */
  public WorldCompleteLaunchBindingService(
      AuthoredWorldLaunchDescriptorClient client,
      WorldCompleteLaunchBindingRepository repository,
      WorldAuthoredSourceIntakeRepository sourceIntakeRepository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace,
      AuthoredWorldVersionStateClient versionStateClient,
      CanonicalGameInstanceLaunchAssociationClient gameSessionClient) {
    this(
        client,
        repository,
        sourceIntakeRepository,
        transactionManager,
        workloadNamespace,
        versionStateClient,
        Objects.requireNonNull(gameSessionClient, "gameSessionClient"),
        false);
  }

  private WorldCompleteLaunchBindingService(
      AuthoredWorldLaunchDescriptorClient client,
      WorldCompleteLaunchBindingRepository repository,
      WorldAuthoredSourceIntakeRepository sourceIntakeRepository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace,
      AuthoredWorldVersionStateClient versionStateClient,
      CanonicalGameInstanceLaunchAssociationClient gameSessionClient,
      boolean allowMissingGameSessionClient) {
    this.client = Objects.requireNonNull(client, "client");
    this.versionStateClient = versionStateClient;
    if (!allowMissingGameSessionClient) {
      Objects.requireNonNull(gameSessionClient, "gameSessionClient");
    }
    this.gameSessionClient = gameSessionClient;
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
   * Authenticates Game Session's exact committed launch association, independently rereads the
   * complete immutable pair from Game Design, and retains that pair against its exact committed
   * World source intake. This is released-content evidence only; it does not prove local content,
   * current authorization, lifecycle preparation, or runtime admission.
   */
  public WorldCompleteLaunchBindingReceipt bindCommittedLaunch(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request) {
    requireAuthenticatedGameSessionCaller();
    requireNoAmbientCommittedLaunchTransaction();
    if (gameSessionClient == null) {
      throw new IllegalStateException(
          "World committed-launch binding has no configured Game Session owner reader");
    }
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new SecurityException(
          "Committed launch binding namespace does not match this World workload");
    }

    GetLaunchDescriptorRequest gameDesignReadRequest =
        committedLaunchGameDesignReadRequest(request);
    CompleteLaunchBindingEvidence fetched = client.getComplete(gameDesignReadRequest);
    requireCommittedLaunchEvidence(fetched, gameDesignReadRequest, request);

    CanonicalGameInstanceLaunchAssociationReadEvidence.Result association =
        gameSessionClient.read(request);
    WorldCanonicalInstancePreparationAssemblyService.requireGameSessionBinding(
        request, association, fetched);

    AuthoredWorldLaunchDescriptorEvidence descriptor = fetched.descriptor();
    WorldAuthoredSourceIntakeReceipt sourceReceipt = readExactSourceReceipt(fetched);
    requireFreshCommittedLaunchReadId(
        gameDesignReadRequest.getRequestId(), request, descriptor, sourceReceipt);
    requireCommittedLaunchSourcePair(fetched, sourceReceipt);
    Optional<WorldCompleteLaunchBindingRepository.StoredBinding> prior =
        repository.read(
            workloadNamespace, descriptor.canonicalTenantId(), descriptor.controlPlaneRequestId());
    if (prior.isPresent()) {
      return loadCommittedLaunchReceipt(prior.orElseThrow(), request, fetched, sourceReceipt);
    }

    WorldCompleteLaunchBindingReceipt accepted;
    try {
      accepted =
          ownerTransaction.execute(
              status -> repository.acceptFresh(workloadNamespace, sourceReceipt, fetched));
    } catch (RuntimeException ownerFailure) {
      Optional<WorldCompleteLaunchBindingRepository.StoredBinding> uncertainCommit =
          repository.read(
              workloadNamespace,
              descriptor.canonicalTenantId(),
              descriptor.controlPlaneRequestId());
      if (uncertainCommit.isEmpty()) {
        throw ownerFailure;
      }
      return loadCommittedLaunchReceipt(
          uncertainCommit.orElseThrow(), request, fetched, sourceReceipt);
    }
    if (accepted == null) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "World owner transaction returned no committed launch binding receipt");
    }
    requireCommittedLaunchRequest(accepted, request);
    requireSamePair(accepted, fetched);
    if (!accepted.sourceIntakeReceipt().equals(sourceReceipt)) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "World committed launch binding changed its exact source intake");
    }

    Optional<WorldCompleteLaunchBindingRepository.StoredBinding> committed =
        repository.read(
            workloadNamespace, descriptor.canonicalTenantId(), descriptor.controlPlaneRequestId());
    if (committed.isEmpty()) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "World committed launch binding is missing after its owner commit");
    }
    WorldCompleteLaunchBindingReceipt readback =
        loadCommittedLaunchReceipt(committed.orElseThrow(), request, fetched, sourceReceipt);
    if (!accepted.equals(readback)) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "World committed launch binding changed during independent owner readback");
    }
    return readback;
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

  private void requireFreshCommittedLaunchReadId(
      String readRequestId,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request associationRequest,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      WorldAuthoredSourceIntakeReceipt sourceReceipt) {
    if (readRequestId.equals(associationRequest.readRequestId().toString())
        || readRequestId.equals(descriptor.authoredWorldSourceOperationId().toString())
        || readRequestId.equals(sourceReceipt.operationId().toString())
        || readRequestId.equals(sourceReceipt.intakeRequestId().toString())
        || readRequestId.equals(sourceReceipt.sourceOperationId().toString())
        || readRequestId.equals(sourceReceipt.source().registrationRequestId().toString())
        || readRequestId.equals(descriptor.controlPlaneRequestId())) {
      throw new IllegalArgumentException(
          "Game Design committed-launch read ID must be distinct from original source and association identities");
    }
  }

  private void requireCommittedLaunchSourcePair(
      CompleteLaunchBindingEvidence evidence, WorldAuthoredSourceIntakeReceipt sourceReceipt) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = evidence.descriptor();
    if (!workloadNamespace.equals(sourceReceipt.targetNamespace())
        || !descriptor.canonicalTenantId().equals(sourceReceipt.canonicalTenantId())
        || !descriptor.worldSlug().equals(sourceReceipt.worldSlug())
        || !descriptor.authoredWorldSourceOperationId().equals(sourceReceipt.sourceOperationId())
        || !descriptor
            .authoredWorldSourceEvidenceDigest()
            .equals(sourceReceipt.sourceEvidenceDigest())
        || !sourceReceipt
            .intakeRequestId()
            .equals(
                evidence
                    .releaseAttestation()
                    .worldStartLocationEvidence()
                    .request()
                    .intakeRequestId())) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "Complete launch binding differs from the exact committed World source intake");
    }
  }

  private GetLaunchDescriptorRequest committedLaunchGameDesignReadRequest(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request) {
    UUID requestId;
    do {
      requestId = UUID.randomUUID();
    } while (requestId.equals(request.readRequestId())
        || requestId.equals(request.canonicalTenantId())
        || requestId.equals(request.gameInstanceUuid())
        || requestId.toString().equals(request.controlPlaneRequestId()));
    return GetLaunchDescriptorRequest.newBuilder()
        .setRequestId(requestId.toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setControlPlaneRequestId(request.controlPlaneRequestId())
        .setExpectedRequestDigest(request.expectedDescriptorRequestDigest())
        .setExpectedResultDigest(request.expectedDescriptorResultDigest())
        .build();
  }

  private void requireCommittedLaunchEvidence(
      CompleteLaunchBindingEvidence evidence,
      GetLaunchDescriptorRequest gameDesignReadRequest,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request associationRequest) {
    if (evidence == null) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "Game Design returned no complete committed launch binding evidence");
    }
    AuthoredWorldLaunchDescriptorEvidence descriptor = evidence.descriptor();
    descriptor.requireValid();
    evidence.releaseAttestation().requireValid(descriptor);
    if (gameDesignReadRequest
        .getRequestId()
        .equals(descriptor.authoredWorldSourceOperationId().toString())) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "Game Design committed launch read ID is not distinct from its source operation");
    }
    if (!workloadNamespace.equals(descriptor.targetNamespace())
        || !associationRequest.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !associationRequest.worldSlug().equals(descriptor.worldSlug())
        || !associationRequest.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
        || !associationRequest.launchDescriptorId().equals(descriptor.launchDescriptorId())
        || !associationRequest.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
        || !associationRequest.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
        || !associationRequest
            .expectedReleaseAttestationEvidenceDigest()
            .equals(evidence.releaseAttestation().evidenceDigest())
        || !gameDesignReadRequest
            .getCanonicalTenantId()
            .equals(descriptor.canonicalTenantId().toString())
        || !gameDesignReadRequest.getWorldSlug().equals(descriptor.worldSlug())
        || !gameDesignReadRequest
            .getControlPlaneRequestId()
            .equals(descriptor.controlPlaneRequestId())
        || !gameDesignReadRequest.getExpectedRequestDigest().equals(descriptor.requestDigest())
        || !gameDesignReadRequest.getExpectedResultDigest().equals(descriptor.resultDigest())) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "Game Design complete launch pair differs from the exact committed association selector");
    }
    if (!AuthoredWorldReleaseAttestationEvidence.requiresWorldStartLocationEvidence(
            evidence.releaseAttestation().schemaVersion())
        || evidence.releaseAttestation().worldStartLocationEvidence() == null) {
      throw new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
          "Committed launch binding requires full selected-release selector evidence");
    }
  }

  private WorldCompleteLaunchBindingReceipt loadCommittedLaunchReceipt(
      WorldCompleteLaunchBindingRepository.StoredBinding stored,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request,
      CompleteLaunchBindingEvidence expectedPair,
      WorldAuthoredSourceIntakeReceipt expectedSourceReceipt) {
    requireCommittedLaunchRequest(stored, request);
    requireSameStoredPair(stored.evidence(), expectedPair);
    WorldAuthoredSourceIntakeReceipt sourceReadback = readExactSourceReceipt(expectedPair);
    if (!expectedSourceReceipt.equals(sourceReadback)) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "World committed launch binding source intake changed during exact readback");
    }
    WorldCompleteLaunchBindingReceipt receipt = repository.toReceipt(stored, sourceReadback);
    requireCommittedLaunchRequest(receipt, request);
    requireSamePair(receipt, expectedPair);
    return receipt;
  }

  private void requireCommittedLaunchRequest(
      WorldCompleteLaunchBindingRepository.StoredBinding stored,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = stored.evidence().descriptor();
    if (!workloadNamespace.equals(stored.targetNamespace())
        || !request.canonicalTenantId().equals(stored.canonicalTenantId())
        || !request.controlPlaneRequestId().equals(stored.controlPlaneRequestId())
        || !request.worldSlug().equals(stored.worldSlug())
        || !workloadNamespace.equals(descriptor.targetNamespace())
        || !request.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !request.worldSlug().equals(descriptor.worldSlug())
        || !request.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
        || !request.launchDescriptorId().equals(descriptor.launchDescriptorId())
        || !request.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
        || !request.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
        || !request
            .expectedReleaseAttestationEvidenceDigest()
            .equals(stored.evidence().releaseAttestation().evidenceDigest())) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "World committed launch binding differs from its exact Game Session selector");
    }
  }

  private void requireCommittedLaunchRequest(
      WorldCompleteLaunchBindingReceipt receipt,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = receipt.descriptor();
    if (!workloadNamespace.equals(receipt.targetNamespace())
        || !request.canonicalTenantId().equals(receipt.canonicalTenantId())
        || !request.worldSlug().equals(receipt.worldSlug())
        || !request.controlPlaneRequestId().equals(receipt.controlPlaneRequestId())
        || !workloadNamespace.equals(descriptor.targetNamespace())
        || !request.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !request.worldSlug().equals(descriptor.worldSlug())
        || !request.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
        || !request.launchDescriptorId().equals(descriptor.launchDescriptorId())
        || !request.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
        || !request.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
        || !request
            .expectedReleaseAttestationEvidenceDigest()
            .equals(receipt.evidence().releaseAttestation().evidenceDigest())) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "World committed launch binding differs from its exact Game Session selector");
    }
  }

  private void requireSameStoredPair(
      CompleteLaunchBindingEvidence stored, CompleteLaunchBindingEvidence expected) {
    if (stored == null
        || !stored.equals(expected)
        || !stored.descriptor().equals(expected.descriptor())
        || !stored.releaseAttestation().equals(expected.releaseAttestation())) {
      throw new WorldCompleteLaunchBindingRepository.RegistrationConflictException(
          "World committed launch binding identity was reused with a changed descriptor or release");
    }
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
        || !peer.isInNamespace(workloadNamespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new SecurityException(
          "World complete launch binding requires only the authenticated same-namespace Game Session workload");
    }
  }

  private static void requireNoAmbientCommittedLaunchTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World committed launch binding requires independent owner reads outside ambient transactions");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World complete launch binding cannot enter from an ambient transaction");
    }
  }
}
