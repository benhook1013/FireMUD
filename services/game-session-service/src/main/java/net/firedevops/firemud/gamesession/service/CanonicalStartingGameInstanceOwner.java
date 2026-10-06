package net.firedevops.firemud.gamesession.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalLaunchPreparationSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalLaunchPreparationRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository.CanonicalGameInstanceLaunchAssociationConflictException;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository.InvalidLaunchPreparationEvidenceException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit non-bean producer for a source-qualified Game Session STARTING row and its immutable
 * launch association. It does not authorize the acting Account, call World, or admit gameplay.
 */
public final class CanonicalStartingGameInstanceOwner {
  private static final String STARTING = "STARTING";
  private static final String RUN_OWNED_LAUNCH_DIGEST_SCHEMA =
      "firemud.run-owned-initial-launch/v1";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final FreshGameSessionTenantAssociationService tenantAssociationService;
  private final GameSessionCanonicalLaunchPreparationService launchPreparationService;
  private final CanonicalGameInstanceLaunchAssociationRepository launchAssociationRepository;
  private final GameInstanceRepository gameInstanceRepository;
  private final String workloadNamespace;
  private final TransactionTemplate ownerTransaction;

  public CanonicalStartingGameInstanceOwner(
      FreshGameSessionTenantAssociationService tenantAssociationService,
      GameSessionCanonicalLaunchPreparationService launchPreparationService,
      CanonicalGameInstanceLaunchAssociationRepository launchAssociationRepository,
      GameInstanceRepository gameInstanceRepository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.tenantAssociationService =
        Objects.requireNonNull(tenantAssociationService, "tenantAssociationService");
    this.launchPreparationService =
        Objects.requireNonNull(launchPreparationService, "launchPreparationService");
    this.launchAssociationRepository =
        Objects.requireNonNull(launchAssociationRepository, "launchAssociationRepository");
    this.gameInstanceRepository =
        Objects.requireNonNull(gameInstanceRepository, "gameInstanceRepository");
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
   * Resolves both owner sources without an ambient transaction, then atomically creates or exactly
   * reuses the STARTING row and its complete launch association in one local owner transaction.
   */
  public CanonicalGameInstanceLaunchAssociation createStartingInstance(
      CreateCanonicalLaunchPreparationRequest request, UUID associationRequestId) {
    Objects.requireNonNull(request, "request");
    requireNoAmbientTransaction();
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new SecurityException("Launch request namespace does not match this workload");
    }
    requireNonNil(associationRequestId, "associationRequestId");

    FreshGameSessionTenantAssociation tenantAssociation =
        tenantAssociationService.associate(associationRequestId, request.canonicalTenantId());
    requireFreshTenantJoin(request, associationRequestId, tenantAssociation);

    CanonicalLaunchPreparationSnapshot preparation = launchPreparationService.prepare(request);
    if (preparation == null) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Canonical launch preparation returned no committed snapshot");
    }
    preparation.requireValid();
    if (!request.equals(preparation.request())) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Committed launch preparation differs from the exact requested owner identity");
    }
    requirePublicSharedCatalog(preparation);
    requireFreshTenantSourceJoin(tenantAssociation, preparation.sourceIntakeReceipt().source());

    CompleteLaunchBindingEvidence binding =
        launchPreparationService.readCompleteBinding(preparation);
    requireCompleteBinding(request, preparation, binding);
    requireNoAmbientTransaction();

    OwnerWriteResult written =
        ownerTransaction.execute(
            status -> createOrReuseAndCapture(request, tenantAssociation, binding));
    if (written == null) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Canonical STARTING owner transaction returned no captured association");
    }

    Optional<CanonicalGameInstanceLaunchAssociation> committedAssociation =
        launchAssociationRepository.read(request.controlPlaneRequestId());
    if (committedAssociation.isEmpty()
        || !written.association().equals(committedAssociation.orElseThrow())) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Committed canonical STARTING association differs from its owner transaction result");
    }
    GameInstance committedInstance =
        gameInstanceRepository
            .findById(written.gameInstanceId())
            .orElseThrow(
                () ->
                    new InvalidLaunchPreparationEvidenceException(
                        "Committed canonical STARTING instance is missing after readback"));
    requireExactInstance(committedInstance, request, written.tenantAssociation(), binding, false);
    requireAssociationProjection(
        committedAssociation.orElseThrow(), committedInstance, request, binding);
    return committedAssociation.orElseThrow();
  }

  private OwnerWriteResult createOrReuseAndCapture(
      CreateCanonicalLaunchPreparationRequest request,
      FreshGameSessionTenantAssociation tenantAssociation,
      CompleteLaunchBindingEvidence binding) {
    launchAssociationRepository.lockRequestForStartingGameInstance(request.controlPlaneRequestId());
    Optional<GameInstance> existing =
        gameInstanceRepository.findByTenantIdAndRunOwnedStartRequestIdForUpdate(
            tenantAssociation.legacyGameSessionTenantId(), request.controlPlaneRequestId());

    GameInstance instance;
    if (existing.isPresent()) {
      instance = existing.orElseThrow();
      requireExactInstance(instance, request, tenantAssociation, binding, false);
    } else {
      instance =
          gameInstanceRepository.save(newStartingInstance(request, tenantAssociation, binding));
      requireExactInstance(instance, request, tenantAssociation, binding, true);
    }

    CanonicalGameInstanceLaunchAssociation association =
        launchAssociationRepository.capture(tenantAssociation, instance.getId(), binding);
    if (association == null
        || !association.launchBindingEvidence().equals(binding)
        || !association
            .tenantAssociationOperationId()
            .equals(tenantAssociation.associationOperationId())
        || !association.gameInstanceUuid().equals(instance.getGameInstanceUuid())
        || !association.controlPlaneRequestId().equals(request.controlPlaneRequestId())) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Captured launch association differs from the exact STARTING owner row");
    }
    return new OwnerWriteResult(instance.getId(), tenantAssociation, association);
  }

  private GameInstance newStartingInstance(
      CreateCanonicalLaunchPreparationRequest request,
      FreshGameSessionTenantAssociation tenantAssociation,
      CompleteLaunchBindingEvidence binding) {
    var descriptor = binding.descriptor();
    GameInstance instance = new GameInstance();
    instance.setTenantId(tenantAssociation.legacyGameSessionTenantId());
    instance.setRuntimeVersion(Long.toString(descriptor.versionId()));
    instance.setGameTemplateId(descriptor.gameTemplateId());
    instance.setLaunchDescriptorId(descriptor.launchDescriptorId());
    instance.setVersionId(descriptor.versionId());
    instance.setReleaseBundleId(descriptor.releaseBundleId());
    instance.setVersionStateEpoch(descriptor.versionStateEpoch());
    instance.setGenerationConfigRevision(descriptor.generationConfigRevision());
    instance.setRemapSetId(descriptor.remapSetId());
    instance.setOwnerAccountId(request.actingAccountUuid().toString());
    instance.setStatus(STARTING);
    instance.setRunOwnedStartRequestId(request.controlPlaneRequestId());
    instance.setRunOwnedStartRequestDigest(runOwnedStartRequestDigest(request, tenantAssociation));
    instance.setRunOwnedStartPublishedReleaseBundleRef(descriptor.publishedReleaseBundleRef());
    // A descriptor patch is only a candidate; a newly allocated runtime remains semantically
    // UNPINNED until Game Session's separate owner pin transition commits its exact tuple.
    instance.setScriptPatchVersion(null);
    instance.setScriptPatchBaseVersionId(null);
    instance.setScriptPinEpoch(null);
    instance.setScriptPatchPinnedControlPlaneRequestId(null);
    return instance;
  }

  private static void requireFreshTenantJoin(
      CreateCanonicalLaunchPreparationRequest request,
      UUID associationRequestId,
      FreshGameSessionTenantAssociation association) {
    Objects.requireNonNull(association, "tenantAssociation");
    RuntimeTenantIdentityEvidence source = association.sourceEvidence();
    if (!associationRequestId.equals(source.requestId())
        || !request.canonicalTenantId().equals(source.canonicalTenantId())
        || !request.targetNamespace().equals(source.targetNamespace())
        || !"NEW_GAME_ROW".equals(source.provenanceKind())) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Fresh Game Session tenant association differs from the exact launch tenant");
    }
  }

  private static void requireFreshTenantSourceJoin(
      FreshGameSessionTenantAssociation association, AuthoredWorldSourceEvidence source) {
    RuntimeTenantIdentityEvidence tenant = association.sourceEvidence();
    if (tenant.schemaVersion() != source.schemaVersion()
        || !tenant.targetNamespace().equals(source.targetNamespace())
        || !tenant.canonicalTenantId().equals(source.canonicalTenantId())
        || tenant.sourceGameRowId() != source.sourceGameRowId()
        || !tenant.sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !tenant.provenanceKind().equals(source.provenanceKind())
        || !"NEW_GAME_ROW".equals(source.provenanceKind())) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Fresh tenant identity does not match the exact authored-world source intake");
    }
  }

  private void requirePublicSharedCatalog(CanonicalLaunchPreparationSnapshot preparation) {
    var catalog = preparation.catalogSnapshot();
    if (!workloadNamespace.equals(catalog.targetNamespace())
        || !preparation.request().canonicalTenantId().equals(catalog.tenantId())
        || !preparation.request().realmId().equals(catalog.realmId())
        || !catalog.visible()
        || !catalog.publicProduction()
        || !"SHARED".equals(catalog.stateScope())) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Canonical STARTING owner requires the exact visible public SHARED catalog");
    }
  }

  private void requireCompleteBinding(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalLaunchPreparationSnapshot preparation,
      CompleteLaunchBindingEvidence binding) {
    Objects.requireNonNull(binding, "completeLaunchBinding");
    var descriptor = binding.descriptor();
    descriptor.requireValid();
    binding.releaseAttestation().requireValid(descriptor);
    if (!descriptor.equals(preparation.launchDescriptorEvidence())
        || !workloadNamespace.equals(descriptor.targetNamespace())
        || !request.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !request.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
        || request.gameTemplateId() != descriptor.gameTemplateId()) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Complete Game Design binding differs from the exact committed launch preparation");
    }
  }

  private static void requireExactInstance(
      GameInstance instance,
      CreateCanonicalLaunchPreparationRequest request,
      FreshGameSessionTenantAssociation tenantAssociation,
      CompleteLaunchBindingEvidence binding,
      boolean newlyAllocated) {
    Objects.requireNonNull(instance, "gameInstance");
    var descriptor = binding.descriptor();
    String expectedRequestDigest = runOwnedStartRequestDigest(request, tenantAssociation);
    if (instance.getId() == null
        || instance.getId() <= 0L
        || instance.getGameInstanceUuid() == null
        || NIL_UUID.equals(instance.getGameInstanceUuid())
        || instance.getTenantId() == null
        || instance.getTenantId() != tenantAssociation.legacyGameSessionTenantId()
        || !Objects.equals(instance.getOwnerAccountId(), request.actingAccountUuid().toString())
        || instance.getLegacyOwnerAccountId() != null
        || !Objects.equals(instance.getRuntimeVersion(), Long.toString(descriptor.versionId()))
        || !Objects.equals(instance.getGameTemplateId(), descriptor.gameTemplateId())
        || !Objects.equals(instance.getLaunchDescriptorId(), descriptor.launchDescriptorId())
        || !Objects.equals(instance.getVersionId(), descriptor.versionId())
        || !Objects.equals(instance.getReleaseBundleId(), descriptor.releaseBundleId())
        || !Objects.equals(instance.getVersionStateEpoch(), descriptor.versionStateEpoch())
        || !Objects.equals(
            instance.getGenerationConfigRevision(), descriptor.generationConfigRevision())
        || !Objects.equals(instance.getRemapSetId(), descriptor.remapSetId())
        || !Objects.equals(instance.getRunOwnedStartRequestId(), request.controlPlaneRequestId())
        || !Objects.equals(instance.getRunOwnedStartRequestDigest(), expectedRequestDigest)
        || !Objects.equals(
            instance.getRunOwnedStartPublishedReleaseBundleRef(),
            descriptor.publishedReleaseBundleRef())
        || instance.getRowVersion() == null
        || instance.getRowVersion() < 0L
        || !isSupportedCurrentStatus(instance.getStatus())
        || (newlyAllocated
            && (!STARTING.equals(instance.getStatus()) || instance.getRowVersion() != 0L))
        || (STARTING.equals(instance.getStatus()) && hasCurrentPin(instance))) {
      throw new CanonicalGameInstanceLaunchAssociationConflictException(
          "Run-owned STARTING request conflicts with the persisted owner instance");
    }
  }

  private static void requireAssociationProjection(
      CanonicalGameInstanceLaunchAssociation association,
      GameInstance instance,
      CreateCanonicalLaunchPreparationRequest request,
      CompleteLaunchBindingEvidence binding) {
    if (!association.launchBindingEvidence().equals(binding)
        || !association.canonicalTenantId().equals(request.canonicalTenantId())
        || !association.controlPlaneRequestId().equals(request.controlPlaneRequestId())
        || !association.gameInstanceUuid().equals(instance.getGameInstanceUuid())
        || association.gameSessionTenantId() != instance.getTenantId()
        || association.currentRowVersion() != instance.getRowVersion()
        || !association.currentGameInstanceStatus().name().equals(instance.getStatus())) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Committed launch association does not match the exact current Game Session row");
    }
  }

  private static boolean hasCurrentPin(GameInstance instance) {
    return instance.getScriptPatchVersion() != null
        || instance.getScriptPinEpoch() != null
        || instance.getScriptPatchPinnedControlPlaneRequestId() != null;
  }

  private static boolean isSupportedCurrentStatus(String status) {
    return STARTING.equals(status)
        || "RUNNING".equals(status)
        || "STOPPING".equals(status)
        || "STOPPED".equals(status);
  }

  /** Mirrors GameInstanceServiceImpl's run-owned request digest for exact future resumption. */
  private static String runOwnedStartRequestDigest(
      CreateCanonicalLaunchPreparationRequest request,
      FreshGameSessionTenantAssociation tenantAssociation) {
    StringBuilder preimage = new StringBuilder();
    appendDigestField(preimage, "schema", RUN_OWNED_LAUNCH_DIGEST_SCHEMA);
    appendDigestField(
        preimage, "tenantId", Long.toString(tenantAssociation.legacyGameSessionTenantId()));
    appendDigestField(preimage, "gameTemplateId", Long.toString(request.gameTemplateId()));
    appendDigestField(preimage, "controlPlaneRequestId", request.controlPlaneRequestId());
    appendDigestField(preimage, "ownerAccountId", request.actingAccountUuid().toString());
    appendDigestField(preimage, "replaceExistingFirst", "false");
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(preimage.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static void appendDigestField(StringBuilder preimage, String name, String value) {
    int nameByteLength = name.getBytes(StandardCharsets.UTF_8).length;
    int valueByteLength = value.getBytes(StandardCharsets.UTF_8).length;
    preimage
        .append(nameByteLength)
        .append(':')
        .append(name)
        .append(valueByteLength)
        .append(':')
        .append(value);
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical STARTING preparation and source reads require no ambient transaction");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private record OwnerWriteResult(
      long gameInstanceId,
      FreshGameSessionTenantAssociation tenantAssociation,
      CanonicalGameInstanceLaunchAssociation association) {}
}
