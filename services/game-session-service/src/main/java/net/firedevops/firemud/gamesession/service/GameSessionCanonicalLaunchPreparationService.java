package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalLaunchPreparationSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalLaunchPreparationRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository.InvalidLaunchPreparationEvidenceException;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository.LaunchPreparationConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired Game Session consumer for exact source-qualified launch preparation. It persists no game
 * instance, calls no World API, and does not authorize creator or launch actions.
 */
public class GameSessionCanonicalLaunchPreparationService {
  private final AuthoredWorldLaunchDescriptorClient descriptorClient;
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository;
  private final GameSessionAuthoredWorldSourceRepository sourceRepository;
  private final GameSessionCanonicalLaunchPreparationRepository preparationRepository;
  private final String workloadNamespace;
  private final TransactionTemplate ownerTransaction;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Trusted owner collaborators and namespace are validated before use; construction acquires no resources.")
  public GameSessionCanonicalLaunchPreparationService(
      AuthoredWorldLaunchDescriptorClient descriptorClient,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionCanonicalLaunchPreparationRepository preparationRepository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.descriptorClient = Objects.requireNonNull(descriptorClient, "descriptorClient");
    this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
    this.sourceRepository = Objects.requireNonNull(sourceRepository, "sourceRepository");
    this.preparationRepository =
        Objects.requireNonNull(preparationRepository, "preparationRepository");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Resolves and independently reads back exact immutable Game Design evidence before taking any
   * catalog/source owner locks. Exact retries return the original preparation without network I/O.
   */
  public CanonicalLaunchPreparationSnapshot prepare(
      CreateCanonicalLaunchPreparationRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoAmbientTransaction();
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new SecurityException("Launch preparation namespace does not match this workload");
    }

    Optional<CanonicalLaunchPreparationSnapshot> prior =
        preparationRepository.readByControlPlaneRequestId(
            workloadNamespace, request.controlPlaneRequestId());
    if (prior.isPresent()) {
      CanonicalLaunchPreparationSnapshot stored = prior.orElseThrow();
      stored.requireValid();
      if (!stored.request().equals(request)) {
        throw new LaunchPreparationConflictException(
            "controlPlaneRequestId was reused with changed launch preparation inputs");
      }
      return stored;
    }

    CanonicalRealmCatalogSnapshot catalog =
        catalogRepository
            .readByRequest(workloadNamespace, request.catalogCreationRequestId())
            .orElseThrow(
                () ->
                    new InvalidLaunchPreparationEvidenceException(
                        "Exact committed canonical realm catalog is missing"));
    requireCatalogBinding(request, catalog);
    IntakeReceipt source =
        sourceRepository
            .read(
                request.sourceIntakeOperationId(),
                request.canonicalTenantId(),
                catalog.worldSlug(),
                workloadNamespace)
            .orElseThrow(
                () ->
                    new InvalidLaunchPreparationEvidenceException(
                        "Exact committed authored-world source intake is missing"));
    requireSourceBinding(request, catalog, source);

    AuthoredWorldLaunchDescriptorEvidence.Request descriptorRequest =
        request.descriptorRequest(catalog, source.source());
    AuthoredWorldLaunchDescriptorEvidence resolved = descriptorClient.resolve(descriptorRequest);
    if (resolved == null
        || !descriptorRequest.equals(resolved.request())
        || !workloadNamespace.equals(resolved.targetNamespace())) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Resolved Game Design descriptor does not match the exact launch preparation request");
    }
    resolved.requireValid();

    AuthoredWorldLaunchDescriptorEvidence readback =
        descriptorClient.get(
            new GetRequest(UUID.randomUUID(), descriptorRequest, resolved.resultDigest()));
    if (!resolved.equals(readback)) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Independent Game Design descriptor readback differs from the resolved evidence");
    }

    CanonicalLaunchPreparationSnapshot written =
        ownerTransaction.execute(
            status -> preparationRepository.persistPrepared(request, catalog, source, readback));
    if (written == null) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Game Session owner transaction returned no launch preparation result");
    }

    Optional<CanonicalLaunchPreparationSnapshot> committed =
        preparationRepository.readByControlPlaneRequestId(
            workloadNamespace, request.controlPlaneRequestId());
    if (committed.isEmpty()) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Committed launch preparation is missing after independent readback");
    }
    CanonicalLaunchPreparationSnapshot exact = committed.orElseThrow();
    exact.requireValid();
    if (!written.equals(exact)) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Launch preparation changed during independent commit readback");
    }
    return exact;
  }

  /**
   * Reads the complete authenticated Game Design binding for one exact committed preparation. This
   * is a non-admitting evidence read: it neither creates nor advances a game instance.
   */
  public CompleteLaunchBindingEvidence readCompleteBinding(
      CanonicalLaunchPreparationSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    requireNoAmbientTransaction();
    snapshot.requireValid();
    if (!workloadNamespace.equals(snapshot.request().targetNamespace())) {
      throw new SecurityException("Launch preparation namespace does not match this workload");
    }

    CanonicalLaunchPreparationSnapshot committed =
        preparationRepository
            .readByControlPlaneRequestId(
                workloadNamespace, snapshot.request().controlPlaneRequestId())
            .orElseThrow(
                () ->
                    new InvalidLaunchPreparationEvidenceException(
                        "Committed launch preparation is missing before complete binding read"));
    committed.requireValid();
    if (!snapshot.equals(committed)) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Committed launch preparation changed before complete binding read");
    }

    AuthoredWorldLaunchDescriptorEvidence descriptor = snapshot.launchDescriptorEvidence();
    GetRequest readSelector =
        new GetRequest(randomNonNilUuid(), descriptor.request(), descriptor.resultDigest());
    CompleteLaunchBindingEvidence binding =
        descriptorClient.getComplete(
            AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(readSelector));
    if (binding == null) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Game Design returned no complete launch binding");
    }
    binding.descriptor().requireValid();
    binding.releaseAttestation().requireValid(binding.descriptor());
    if (!descriptor.equals(binding.descriptor())
        || !workloadNamespace.equals(binding.descriptor().targetNamespace())) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Complete Game Design binding differs from the committed launch descriptor");
    }
    return binding;
  }

  private static void requireCatalogBinding(
      CreateCanonicalLaunchPreparationRequest request, CanonicalRealmCatalogSnapshot catalog) {
    if (!request.targetNamespace().equals(catalog.targetNamespace())
        || !request.canonicalTenantId().equals(catalog.tenantId())
        || !request.realmId().equals(catalog.realmId())
        || !request.catalogCreationRequestId().equals(catalog.creationRequestId())
        || request.catalogRevision() != catalog.catalogRevision()
        || !catalog.visible()
        || !catalog.publicProduction()) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Canonical launch request does not match the exact committed initial catalog");
    }
  }

  private static void requireSourceBinding(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source) {
    if (!request.targetNamespace().equals(source.source().targetNamespace())
        || !request.canonicalTenantId().equals(source.source().canonicalTenantId())
        || !request.sourceIntakeOperationId().equals(source.operationId())
        || !catalog.sourceIntakeReceipt().equals(source)
        || !"NEW_GAME_ROW".equals(source.source().provenanceKind())
        || !catalog.worldSlug().equals(source.source().worldSlug())) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Canonical launch request does not match the exact fresh source intake");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical launch preparation cannot enter from an ambient transaction");
    }
  }

  private static UUID randomNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (new UUID(0L, 0L).equals(value));
    return value;
  }
}
