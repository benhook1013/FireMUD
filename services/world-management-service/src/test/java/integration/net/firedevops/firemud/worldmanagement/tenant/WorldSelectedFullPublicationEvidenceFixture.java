package net.firedevops.firemud.worldmanagement.tenant;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.impl.WorldDraftDesignDigestServiceImpl;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Package-local access to the actual World selected-freeze owner path for the GDS schema-4 proof.
 *
 * <p>Inputs from the original Account authorization and authored-world producer are controlled test
 * evidence. This fixture does not establish production authority. Every World source, Version,
 * graph application, first-freeze checkpoint, inventory, frozen-graph capture, and committed
 * readback below goes through the current World SQL owners with their guards enabled.
 */
public final class WorldSelectedFullPublicationEvidenceFixture {
  private static final ObjectMapper JSON = new ObjectMapper();

  private WorldSelectedFullPublicationEvidenceFixture() {}

  /** Exact retained World evidence consumed by the actual GDS selected-publication owner. */
  public record Result(
      AuthoredDraftPublishSelectionBinding selection,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
      WorldSelectedPublicationArtifactInventoryEvidence inventoryEvidence,
      WorldPublishedStartLocationEvidence startLocationEvidence,
      ByIdReadRequest sourceReadRequest,
      PublicReceipt sourceReadReceipt,
      WorldDraftDesignDigestService.WorldDraftDesignDigest selectedWorldDigest,
      byte[] appliedResultBytes,
      byte[] graphBytes) {
    public Result {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(accountBinding, "accountBinding");
      Objects.requireNonNull(freezeEvidence, "freezeEvidence");
      Objects.requireNonNull(inventoryEvidence, "inventoryEvidence");
      Objects.requireNonNull(startLocationEvidence, "startLocationEvidence");
      Objects.requireNonNull(sourceReadRequest, "sourceReadRequest");
      Objects.requireNonNull(sourceReadReceipt, "sourceReadReceipt");
      Objects.requireNonNull(selectedWorldDigest, "selectedWorldDigest");
      appliedResultBytes = Objects.requireNonNull(appliedResultBytes, "appliedResultBytes").clone();
      graphBytes = Objects.requireNonNull(graphBytes, "graphBytes").clone();
    }

    @Override
    public byte[] appliedResultBytes() {
      return appliedResultBytes.clone();
    }

    @Override
    public byte[] graphBytes() {
      return graphBytes.clone();
    }
  }

  /** Actual World source/version and APPLIED graph owner rows for one exact full Draft binding. */
  public record AppliedGraph(
      AuthoredWorldSourceEvidence controlledWorldSource,
      WorldAuthoredSourceIntakeReceipt sourceIntake,
      WorldAuthoredVersionIdentityReceipt versionIdentity,
      WorldDraftTopologyCommitPlan plan,
      WorldDraftGraphApplication application,
      WorldDraftGraphAppliedResult applied) {
    public AppliedGraph {
      Objects.requireNonNull(controlledWorldSource, "controlledWorldSource");
      Objects.requireNonNull(sourceIntake, "sourceIntake");
      Objects.requireNonNull(versionIdentity, "versionIdentity");
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(application, "application");
      Objects.requireNonNull(applied, "applied");
      if (!plan.binding().equals(application.plan().binding())
          || !application.operation().equals(applied.application().operation())) {
        throw new IllegalArgumentException("World APPLIED graph does not match its selected plan");
      }
    }

    public DraftCommitBinding selectedCommit() {
      return plan.binding();
    }
  }

  /** Independently reads actual owner rows after the authenticated World begin has committed. */
  public static Result readCommittedFreeze(
      DSLContext worldDsl,
      String namespace,
      AuthoredDraftPublishSelectionBinding selection,
      AppliedGraph worldGraph,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    Objects.requireNonNull(worldDsl, "worldDsl");
    Objects.requireNonNull(selection, "selection");
    Objects.requireNonNull(worldGraph, "worldGraph");
    Objects.requireNonNull(accountBinding, "accountBinding");
    Objects.requireNonNull(freezeEvidence, "freezeEvidence");

    var target = selection.target();
    DraftCommitBinding binding = selection.selectedCommit();
    if (!binding.equals(worldGraph.selectedCommit())
        || !namespace.equals(worldGraph.sourceIntake().targetNamespace())
        || !target.canonicalTenantId().equals(worldGraph.sourceIntake().canonicalTenantId())
        || !target.canonicalVersionId().equals(worldGraph.versionIdentity().canonicalVersionId())
        || target.gameDesignVersionRowId() != worldGraph.versionIdentity().gameDesignVersionId()) {
      throw new IllegalArgumentException(
          "World freeze does not target the exact previously applied GDS Draft and World identity");
    }
    long expectedStateEpoch = Long.parseLong(selection.intent().expectedVersionStateEpoch());
    var intake = worldGraph.sourceIntake();
    var version = worldGraph.versionIdentity();
    requireExactVersionIdentity(selection, intake, version);
    var controlledWorldSource = worldGraph.controlledWorldSource();
    var plan = worldGraph.plan();
    var application = worldGraph.application();
    var intakeRepository = new WorldAuthoredSourceIntakeRepository(worldDsl);
    var fence = new WorldDesignPublicationFenceRepository(worldDsl, intakeRepository);
    var topology = new WorldDraftTopologyCommitRepository(worldDsl, fence, JSON);
    var applications = new WorldDraftGraphApplicationRepository(worldDsl, fence, JSON);
    var applied = worldGraph.applied();
    requireExactAppliedReadback(applications, topology, application, plan, applied);

    if (!selection.equals(accountBinding.input().selection())) {
      throw new IllegalArgumentException(
          "Controlled Account binding does not retain the exact GDS synchronized selection");
    }
    String publicationId = selection.intent().publishRequestId();
    String workflow =
        PublicationDigestRequestBinding.full(
                target.canonicalTenantId().toString(),
                Long.toString(target.gameDesignVersionRowId()),
                publicationId)
            .derivedWorkflowIdentity();
    var freeze =
        new WorldDesignPublicationFenceEvidence(
            namespace,
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            version.operationId(),
            target.gameDesignVersionRowId(),
            intake.intakeRequestId(),
            intake.operationId(),
            intake.requestDigest(),
            controlledWorldSource.operationId(),
            controlledWorldSource.evidenceDigest(),
            intake.receiptDigest(),
            publicationId,
            selection.digest().substring("sha256:".length()),
            expectedStateEpoch,
            workflow);
    var digestService = digestService(worldDsl);
    var authorizationRepository =
        new WorldSelectedDraftPublicationAuthorizationRepository(worldDsl);
    var inventoryRepository = new WorldSelectedPublicationArtifactInventoryRepository(worldDsl);
    var frozenAttempt = fence.readAttempt(freeze).orElseThrow();

    var retainedAccount = authorizationRepository.readCommitted(frozenAttempt).orElseThrow();
    if (!java.util.Arrays.equals(
        retainedAccount.canonicalBytes(), accountBinding.canonicalBytes())) {
      throw new IllegalStateException("Committed World Account-binding readback changed bytes");
    }
    var freezeRequest =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            namespace,
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            publicationId,
            expectedStateEpoch,
            selection.digest().substring("sha256:".length()),
            accountBinding);
    var acknowledgement =
        new WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement(
            freezeRequest,
            intake.intakeRequestId(),
            expectedStateEpoch,
            frozenAttempt.publicationFence(),
            OwnerFreezePhase.FROZEN,
            frozenAttempt.checkpoint().appliedCommitId(),
            frozenAttempt.checkpoint().contentDigest(),
            frozenAttempt.checkpoint().digestSchemaVersion());
    if (!freezeRequest.equals(freezeEvidence.request())
        || !acknowledgement.equals(freezeEvidence.acknowledgement())) {
      throw new IllegalStateException(
          "Authenticated begin differs from independent committed World rows");
    }
    var inventoryEvidence =
        new WorldSelectedPublicationArtifactInventoryReadService(
                namespace, fence, authorizationRepository, inventoryRepository)
            .readRetained(freezeEvidence);

    var captureRequest = captureRequest(plan, freeze, frozenAttempt);
    var frozenRepository =
        new WorldCanonicalFrozenTopologyRepository(
            worldDsl, new WorldAuthoredGraphSnapshotRepository(worldDsl), topology, digestService);
    var captured = frozenRepository.readCommitted(captureRequest).orElseThrow();
    var publishedSource =
        new WorldPublishedStartLocationRepository(worldDsl, frozenRepository, applications)
            .readCommitted(captureRequest)
            .orElseThrow();
    var worldEvidence = publishedStartLocationEvidence(publishedSource);
    var worldDigest =
        new WorldDraftDesignDigestService.WorldDraftDesignDigest(
            Long.toString(worldGraph.sourceIntake().localTenantKey()),
            Long.toString(worldGraph.versionIdentity().localVersionKey()),
            frozenAttempt.checkpoint().appliedCommitId(),
            frozenAttempt.checkpoint().contentDigest(),
            frozenAttempt.checkpoint().digestSchemaVersion());
    if (worldDigest.digestSchemaVersion() != 4) {
      throw new IllegalStateException(
          "World selected freeze did not retain current digest schema 4");
    }

    var committedIntake = intakeRepository.read(namespace, intake.intakeRequestId()).orElseThrow();
    var sourceReadRequest =
        new ByIdReadRequest(
            1,
            namespace,
            UUID.randomUUID(),
            committedIntake.intakeRequestId(),
            target.canonicalTenantId());
    var sourceReadReceipt =
        new PublicReceipt(
            committedIntake.schemaVersion(),
            committedIntake.targetNamespace(),
            committedIntake.intakeRequestId(),
            committedIntake.operationId(),
            committedIntake.canonicalTenantId(),
            committedIntake.worldSlug(),
            committedIntake.sourceOperationId(),
            committedIntake.sourceEvidenceDigest(),
            committedIntake.requestDigest(),
            committedIntake.receiptDigest(),
            committedIntake.source());
    WorldAuthoredSourceIntakeGrpcCodec.toReadByIdResponse(sourceReadRequest, sourceReadReceipt);

    return new Result(
        selection,
        accountBinding,
        freezeEvidence,
        inventoryEvidence,
        worldEvidence,
        sourceReadRequest,
        sourceReadReceipt,
        worldDigest,
        applied.canonicalBytes(),
        captured.graphBytes());
  }

  /**
   * Applies the exact World payloads in a selected GDS binding before its sync fence is advanced.
   */
  public static AppliedGraph applyGraph(
      DSLContext worldDsl,
      PlatformTransactionManager worldTransactions,
      String namespace,
      DraftCommitBinding selectedCommit,
      AuthoredWorldSourceEvidence controlledWorldSource) {
    return applyGraph(
        worldDsl,
        worldTransactions,
        namespace,
        selectedCommit,
        controlledWorldSource,
        UUID.randomUUID());
  }

  /** Uses the existing GDS outbox's original World intake identity in composed proof. */
  public static AppliedGraph applyGraph(
      DSLContext worldDsl,
      PlatformTransactionManager worldTransactions,
      String namespace,
      DraftCommitBinding selectedCommit,
      AuthoredWorldSourceEvidence controlledWorldSource,
      UUID originalIntakeRequestId) {
    Objects.requireNonNull(originalIntakeRequestId, "originalIntakeRequestId");
    Objects.requireNonNull(worldDsl, "worldDsl");
    Objects.requireNonNull(worldTransactions, "worldTransactions");
    Objects.requireNonNull(selectedCommit, "selectedCommit");
    Objects.requireNonNull(controlledWorldSource, "controlledWorldSource");
    requireExactControlledSource(namespace, selectedCommit.target(), controlledWorldSource);
    var intakeRepository = new WorldAuthoredSourceIntakeRepository(worldDsl);
    var versionRepository = new WorldAuthoredVersionIdentityRepository(worldDsl);
    var write = new TransactionTemplate(worldTransactions);
    write.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    var intake =
        Objects.requireNonNull(
            write.execute(
                status ->
                    intakeRepository.acceptFresh(
                        namespace, originalIntakeRequestId, controlledWorldSource)));
    var versionStateRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            namespace,
            UUID.randomUUID(),
            selectedCommit.target().canonicalTenantId(),
            controlledWorldSource.worldSlug(),
            controlledWorldSource.operationId(),
            controlledWorldSource.evidenceDigest(),
            selectedCommit.target().gameDesignVersionRowId());
    var versionState =
        AuthoredWorldVersionStateEvidence.create(
            versionStateRequest,
            controlledWorldSource,
            selectedCommit.target().canonicalVersionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            1L);
    var version =
        Objects.requireNonNull(
            write.execute(status -> versionRepository.acceptFresh(intake, versionState)));
    if (!selectedCommit.target().canonicalTenantId().equals(version.canonicalTenantId())
        || !selectedCommit.target().canonicalVersionId().equals(version.canonicalVersionId())
        || selectedCommit.target().gameDesignVersionRowId() != version.gameDesignVersionId()) {
      throw new IllegalStateException(
          "World source/version owner rows differ from the selected GDS target mapping");
    }
    var ownerBinding =
        new WorldDesignPublicationFenceEvidence.OwnerBinding(
            namespace,
            selectedCommit.target().canonicalTenantId(),
            selectedCommit.target().canonicalVersionId(),
            version.operationId(),
            selectedCommit.target().gameDesignVersionRowId(),
            intake.intakeRequestId(),
            intake.operationId(),
            intake.requestDigest(),
            controlledWorldSource.operationId(),
            controlledWorldSource.evidenceDigest(),
            intake.receiptDigest());
    var plan = WorldDraftTopologyCommitPlan.create(selectedCommit, ownerBinding);
    var application = controlledGraphApplication(plan);
    var fence = new WorldDesignPublicationFenceRepository(worldDsl, intakeRepository);
    var topology = new WorldDraftTopologyCommitRepository(worldDsl, fence, JSON);
    var applications = new WorldDraftGraphApplicationRepository(worldDsl, fence, JSON);
    var applied =
        new WorldDraftGraphApplicationService(
                applications,
                worldTransactions,
                operation -> new WorldDraftGraphApplicationService.CommitOrderProof(operation))
            .apply(application);
    requireExactAppliedReadback(applications, topology, application, plan, applied);
    return new AppliedGraph(controlledWorldSource, intake, version, plan, application, applied);
  }

  private static void requireExactControlledSource(
      String namespace, DraftCommitBinding.TargetProof target, AuthoredWorldSourceEvidence source) {
    if (!namespace.equals(source.targetNamespace())
        || !target.canonicalTenantId().equals(source.canonicalTenantId())
        || target.sourceGameRowId() != source.sourceGameRowId()
        || !target.sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !target.sourceProvenanceKind().equals(source.provenanceKind())) {
      throw new IllegalArgumentException(
          "Controlled World source differs from the exact retained GDS selected target");
    }
  }

  private static void requireExactVersionIdentity(
      AuthoredDraftPublishSelectionBinding selection,
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version) {
    if (!selection.target().canonicalTenantId().equals(version.canonicalTenantId())
        || !selection.target().canonicalVersionId().equals(version.canonicalVersionId())
        || selection.target().gameDesignVersionRowId() != version.gameDesignVersionId()
        || !intake.operationId().equals(version.sourceIntakeReceipt().operationId())
        || !selection.target().canonicalTenantId().equals(intake.canonicalTenantId())) {
      throw new IllegalStateException(
          "World retained source/Version rows differ from the selected GDS target mapping");
    }
  }

  private static WorldDraftGraphApplication controlledGraphApplication(
      WorldDraftTopologyCommitPlan plan) {
    DraftCommitBinding binding = plan.binding();
    UUID operationId = UUID.randomUUID();
    UUID authorizationFenceId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    byte[] accountBytes =
        new DraftAuthorizationFenceBinding(
                operationId,
                binding.requestId(),
                binding.commitId(),
                authorizationFenceId,
                actorId,
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.baseCommitId(),
                "0",
                binding.canonicalBytes(),
                binding.canonicalBytes(),
                binding.digest(),
                List.of(
                    new DraftAuthorizationFenceBinding.SourceEvidence(
                        DraftAuthorizationFenceBinding.SourceKind.GLOBAL_ROLES,
                        actorId.toString(),
                        null,
                        "1",
                        null,
                        null,
                        "TEST_ONLY controlled original COMMIT_ORDER input"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .withRequiredOwners()
            .canonicalBytes();
    var operation =
        new WorldDraftTerminalOperation(
            operationId,
            binding.requestId(),
            binding.commitId(),
            authorizationFenceId,
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding,
            plan.ownerBinding(),
            accountBytes);
    return new WorldDraftGraphApplication(operation, plan);
  }

  private static void requireExactAppliedReadback(
      WorldDraftGraphApplicationRepository applications,
      WorldDraftTopologyCommitRepository topology,
      WorldDraftGraphApplication application,
      WorldDraftTopologyCommitPlan plan,
      WorldDraftGraphAppliedResult applied) {
    var committedApplication = applications.readCommitted(application).orElseThrow();
    var committedTopology = topology.readCommitted(plan).orElseThrow();
    if (!java.util.Arrays.equals(committedApplication.canonicalBytes(), applied.canonicalBytes())
        || !java.util.Arrays.equals(committedTopology.graphBytes(), applied.graphBytes())) {
      throw new IllegalStateException("World graph application committed readback changed bytes");
    }
  }

  private static WorldAuthoredGraphSnapshot.CaptureRequest captureRequest(
      WorldDraftTopologyCommitPlan plan,
      WorldDesignPublicationFenceEvidence freeze,
      WorldDesignPublicationFenceEvidence.FrozenAttempt attempt) {
    List<WorldAuthoredGraphSnapshot.OwnedAffectedTuple> tuples =
        plan.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    return new WorldAuthoredGraphSnapshot.CaptureRequest(
        freeze.targetNamespace(),
        freeze.canonicalTenantId(),
        freeze.canonicalVersionId(),
        freeze.intakeRequestId(),
        attempt.publicationFence(),
        freeze.publicationRequestId(),
        freeze.requestDigest(),
        freeze.versionStateEpoch(),
        freeze.publishWorkflowId(),
        plan.binding().commitId().toString(),
        attempt.checkpoint().contentDigest(),
        attempt.checkpoint().digestSchemaVersion(),
        tuples);
  }

  private static WorldDraftDesignDigestServiceImpl digestService(DSLContext dsl) {
    return new WorldDraftDesignDigestServiceImpl(
        new RegionRepository(dsl),
        new ZoneRepository(dsl),
        new RoomRepository(dsl),
        new RoomExitRepository(dsl),
        new GenerationRuleRepository(dsl),
        new WorldEntitySpawnBindingRepository(dsl));
  }

  private static WorldPublishedStartLocationEvidence publishedStartLocationEvidence(
      WorldPublishedStartLocationSource source) {
    var freeze = source.frozenTopology().request().freeze();
    var tuples =
        freeze.suppliedOwnedAffectedTuples().stream()
            .map(
                tuple ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        tuple.owner(),
                        tuple.aggregateType(),
                        tuple.aggregateId(),
                        tuple.scopeType(),
                        tuple.scopeId(),
                        tuple.expectedEpoch()))
            .toList();
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            freeze.targetNamespace(),
            freeze.canonicalTenantId(),
            freeze.canonicalVersionId(),
            freeze.intakeRequestId(),
            freeze.publicationFence(),
            freeze.publicationRequestId(),
            freeze.requestDigest(),
            freeze.versionStateEpoch(),
            freeze.publishWorkflowId(),
            freeze.appliedCommitId(),
            freeze.contentDigest(),
            freeze.digestSchemaVersion(),
            tuples);
    return new WorldPublishedStartLocationEvidence(
        request,
        source.selectorReceipt().canonicalBytes(),
        source.appliedResult().application().operation().accountBindingBytes(),
        source.appliedResult().canonicalBytes());
  }

  /** Explicit test-only composition of existing readers; no runtime registration or authority. */
  public static java.util.List<io.grpc.BindableService> reservationReadEndpoints(
      DSLContext dsl,
      PlatformTransactionManager transactions,
      String namespace,
      net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient
          selectionClient,
      net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient versionClient,
      net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient
          accountClient,
      net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient sourceClient) {
    var intake = new WorldAuthoredSourceIntakeRepository(dsl);
    var fence = new WorldDesignPublicationFenceRepository(dsl, intake);
    var topology = new WorldDraftTopologyCommitRepository(dsl, fence, JSON);
    var applications = new WorldDraftGraphApplicationRepository(dsl, fence, JSON);
    var digest = digestService(dsl);
    var authorization = new WorldSelectedDraftPublicationAuthorizationRepository(dsl);
    var inventory = new WorldSelectedPublicationArtifactInventoryRepository(dsl);
    var frozen =
        new WorldCanonicalFrozenTopologyRepository(
            dsl, new WorldAuthoredGraphSnapshotRepository(dsl), topology, digest);
    var service =
        new WorldSelectedDraftPublicationFreezeService(
            namespace,
            selectionClient,
            versionClient,
            accountClient,
            intake,
            fence,
            new WorldSelectedDraftPublicationCheckpointRepository(
                fence, applications, topology, digest),
            authorization,
            inventory,
            applications,
            new WorldSelectedPublicationSelectorCapture(
                namespace, new WorldCanonicalFrozenTopologyService(frozen, transactions)),
            transactions);
    return java.util.List.of(
        new WorldSelectedDraftPublicationFreezeGrpcService(
            service,
            new WorldSelectedPublicationArtifactInventoryReadService(
                namespace, fence, authorization, inventory),
            namespace),
        new WorldPublishedStartLocationReadGrpcService(
            new WorldPublishedStartLocationRepository(dsl, frozen, applications), namespace),
        new WorldAuthoredSourceIntakeGrpcService(
            new WorldAuthoredSourceIntakeService(sourceClient, intake, transactions, namespace),
            namespace));
  }
}
