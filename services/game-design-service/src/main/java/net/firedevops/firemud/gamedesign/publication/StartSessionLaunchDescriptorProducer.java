package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.StartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.StartSessionLaunchDescriptorRepository.Binding;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Standalone fresh StartSession descriptor producer. It is intentionally not a Spring bean or a
 * runtime selector: the only accepted input is an exact association replay already pinned by Game
 * Session, and the Account-gated owner reader runs before the descriptor write transaction.
 */
public final class StartSessionLaunchDescriptorProducer {
  private static final String RUNTIME_SURFACE_EMPTY_V1 = "EMPTY_V1";
  private static final String RUNTIME_SURFACE_UNSUPPORTED_CAPTURE_V1 = "UNSUPPORTED_CAPTURE_V1";
  private static final String EMPTY_RUNTIME_FLAGS = "{}";
  private static final String INVALID_TEMPLATE_CONFIGURATION = "INVALID_TEMPLATE_CONFIGURATION";
  private static final String UNSUPPORTED_CAPTURE_MESSAGE =
      "INVALID_TEMPLATE_CONFIGURATION: captured template has unsupported World, Entity, or Automation references";

  private final DSLContext dsl;
  private final String workloadNamespace;
  private final StartSessionTemplateAssociationReadService associationReader;
  private final GameAuthoredWorldSourceRepository authoredWorldSources;
  private final LaunchDescriptorRepository descriptors;
  private final StartSessionLaunchDescriptorRepository bindings;
  private final VersionRepository versions;
  private final TemplateReferenceRepository templateReferences;
  private final GameDesignPublicationOperationRepository publicationOperations;
  private final GameDesignSourceRepository sourceRepository;
  private final SelectedDraftTemplateWorldSourceAssociationRepository associations;
  private final PublishedReleaseBundleService releases;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate writeTransaction;

  /**
   * Production construction accepts only the concrete Account-gated same-namespace owner reader. No
   * caller-supplied association, Version, or release can enter this path.
   */
  public StartSessionLaunchDescriptorProducer(
      DSLContext dsl,
      PlatformTransactionManager transactions,
      String workloadNamespace,
      StartSessionTemplateAssociationReadService associationReader,
      PublishedReleaseBundleService releases,
      ObjectMapper objectMapper) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    this.associationReader = Objects.requireNonNull(associationReader, "associationReader");
    this.releases = Objects.requireNonNull(releases, "releases");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    authoredWorldSources = new GameAuthoredWorldSourceRepository(dsl);
    descriptors = new LaunchDescriptorRepository(dsl);
    bindings = new StartSessionLaunchDescriptorRepository(dsl);
    versions = new VersionRepository(dsl);
    templateReferences = new TemplateReferenceRepository(dsl);
    publicationOperations = new GameDesignPublicationOperationRepository(dsl);
    sourceRepository = new GameDesignSourceRepository(dsl);
    associations = new SelectedDraftTemplateWorldSourceAssociationRepository(dsl);
    writeTransaction =
        new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    writeTransaction.setName("game-design-start-session-launch-descriptor");
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    writeTransaction.setReadOnly(false);
  }

  /** Reads the current Account projection, then stores or exactly replays one pinned descriptor. */
  public ResolvedBinding resolve(Request request) {
    requirePeer();
    Objects.requireNonNull(request, "request");
    if (!(request.selection() instanceof ExactReplay)) {
      throw Status.INVALID_ARGUMENT
          .withDescription("An exact Game Session-pinned association replay is required")
          .asRuntimeException();
    }
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("StartSession target namespace differs from the Game Design peer")
          .asRuntimeException();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "Account projection and owner descriptor writes require independent transactions")
          .asRuntimeException();
    }

    // This call performs the independent Account projection read before any local write
    // transaction.
    Result exact = associationReader.read(request);
    requireExactAssociationRead(request, exact);
    StartSessionPostAuthorizationExecutionTuple tuple = request.decodedTuple();
    var action = tuple.preAuthorizationTuple().action();
    UUID canonicalTenantId = action.scope().tenantId();
    long templateId = action.target().gameTemplateId();
    Association association = exact.association();
    AuthoredWorldSourceEvidence source =
        authoredWorldSources
            .read(
                association.sourceOperationId(),
                canonicalTenantId,
                association.worldSlug(),
                request.targetNamespace())
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription(
                            "Exact registered Game Design source for the association is unavailable")
                        .asRuntimeException());
    requireSourceMatches(association, source);
    AuthoredWorldLaunchDescriptorEvidence.Request descriptorRequest =
        descriptorRequest(tuple, association);
    String requestJson = writeJson(descriptorRequest);
    String sourceJson = writeJson(source);

    OwnerOutcome outcome =
        Objects.requireNonNull(
            writeTransaction.execute(
                ignored ->
                    writeOrRead(
                        request,
                        exact,
                        descriptorRequest,
                        requestJson,
                        source,
                        sourceJson,
                        canonicalTenantId,
                        templateId)),
            "StartSession launch descriptor transaction returned no binding");
    if (outcome.failure() != null) {
      throw new StoredBusinessDenial(exact, outcome.failure());
    }
    return Objects.requireNonNull(
        outcome.resolvedBinding(), "Successful descriptor binding is missing");
  }

  private OwnerOutcome writeOrRead(
      Request exactRequest,
      Result exact,
      AuthoredWorldLaunchDescriptorEvidence.Request descriptorRequest,
      String requestJson,
      AuthoredWorldSourceEvidence source,
      String sourceJson,
      UUID canonicalTenantId,
      long templateId) {
    String controlPlaneRequestId = descriptorRequest.controlPlaneRequestId();
    descriptors.lockBoundRequest(workloadNamespace, canonicalTenantId, controlPlaneRequestId);
    Optional<LaunchDescriptor> storedDescriptor =
        descriptors.findBoundByRequest(workloadNamespace, canonicalTenantId, controlPlaneRequestId);
    Optional<Binding> storedBinding =
        bindings.find(workloadNamespace, canonicalTenantId, controlPlaneRequestId);
    if (storedDescriptor.isPresent() || storedBinding.isPresent()) {
      if (storedDescriptor.isEmpty() || storedBinding.isEmpty()) {
        throw conflict("Stored descriptor is missing its exact StartSession association binding");
      }
      LaunchDescriptor descriptor = storedDescriptor.orElseThrow();
      CapturedTemplate capturedTemplate = requireCapturedTemplate(exact, templateId);
      requireBindingMatches(
          storedBinding.orElseThrow(),
          descriptor,
          exactRequest,
          exact,
          descriptorRequest,
          source,
          capturedTemplate.gameLogicReceipt());
      if (LaunchDescriptor.OUTCOME_FAILED.equals(descriptor.getOutcomeStatus())) {
        if (capturedTemplate.failureMessage() == null) {
          throw conflict(
              "Stored failed descriptor no longer matches its immutable captured configuration");
        }
        FrozenFailure failure =
            requireStoredFailure(descriptor, descriptorRequest, requestJson, source, sourceJson);
        return OwnerOutcome.failed(failure);
      }
      if (capturedTemplate.failureMessage() != null) {
        throw conflict(
            "Stored successful descriptor differs from its immutable captured configuration");
      }
      requireSuccessfulDescriptor(descriptor);
      ResolvedLaunchDescriptorDto resolved =
          readStored(descriptor, descriptorRequest, requestJson, source, sourceJson);
      return OwnerOutcome.succeeded(new ResolvedBinding(exactRequest, exact, resolved));
    }

    if (descriptors
        .findByPrivateRequest(source.sourceGameTenantKey(), controlPlaneRequestId)
        .isPresent()) {
      throw conflict(
          "Retained private-owner descriptor history is unbound or bound to another canonical identity");
    }

    TemplateReferenceRepository.PhaseSnapshot phase =
        templateReferences
            .readPhase(canonicalTenantId)
            .filter(value -> value.phase() == TemplateReferenceRepository.Phase.ENFORCED)
            .filter(value -> value.phaseEpoch() == exact.phaseEpoch())
            .filter(
                value -> value.inventoryDigest() != null && value.inventoryTemplateCount() != null)
            .filter(value -> value.inventoryTemplateCount() > 0L)
            .orElseThrow(
                () -> conflict("Current explicit enforced template-reference phase changed"));
    if (phase.sourceGameRowId() != source.sourceGameRowId()
        || !phase.sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !"NEW_GAME_ROW".equals(phase.provenanceKind())
        || !"NEW_GAME_ROW".equals(source.provenanceKind())) {
      throw conflict("Exact source and fresh tenant phase provenance differ");
    }

    Version selectedVersion =
        versions
            .findByCanonicalTenantIdAndCanonicalVersionId(
                canonicalTenantId, exact.association().canonicalVersionId())
            .orElseThrow(() -> conflict("Exact selected canonical Version is unavailable"));
    lockAndReloadSelectedVersion(
        selectedVersion, canonicalTenantId, exact.association().canonicalVersionId());
    Version version =
        versions
            .findByCanonicalTenantIdAndCanonicalVersionId(
                canonicalTenantId, exact.association().canonicalVersionId())
            .orElseThrow(
                () -> conflict("Exact selected canonical Version disappeared after locking"));
    if (!Objects.equals(selectedVersion.getId(), version.getId())) {
      throw conflict("Canonical Version row identity changed while locking the exact selection");
    }
    requireLaunchableVersion(version, source, phase, exact.association());
    PublishedReleaseBundleDto release =
        releases
            .findPublishedReleaseBundle(version.getTenantId(), version.getId())
            .orElseThrow(() -> conflict("Exact selected immutable release bundle is unavailable"));
    requireReleaseMatches(exact, release, version);
    CapturedTemplate capturedTemplate = requireCapturedTemplate(exact, templateId);
    String unsupportedCapture = capturedTemplate.failureMessage();
    long versionStateEpoch =
        Objects.requireNonNull(version.getVersionStateEpoch(), "versionStateEpoch");

    boolean deterministicFailure = unsupportedCapture != null;
    LaunchDescriptor candidate;
    if (deterministicFailure) {
      candidate =
          frozenFailure(
              descriptorRequest,
              source,
              requestJson,
              sourceJson,
              INVALID_TEMPLATE_CONFIGURATION,
              UNSUPPORTED_CAPTURE_MESSAGE);
    } else {
      AuthoredWorldLaunchDescriptorEvidence evidence =
          AuthoredWorldLaunchDescriptorEvidence.create(
              descriptorRequest,
              "ld-" + UUID.randomUUID(),
              version.getId(),
              false,
              null,
              EMPTY_RUNTIME_FLAGS,
              release.generationConfigRevision(),
              versionStateEpoch,
              release.id(),
              release.publishedReleaseBundleRef(),
              false,
              null);
      candidate = descriptorRow(evidence, source, requestJson, sourceJson);
    }
    LaunchDescriptor persisted = descriptors.insertImmutable(candidate);
    if (deterministicFailure) {
      requireStoredFailure(persisted, descriptorRequest, requestJson, source, sourceJson);
    } else {
      requireSuccessfulDescriptor(persisted);
    }
    String runtimeSurface =
        deterministicFailure ? RUNTIME_SURFACE_UNSUPPORTED_CAPTURE_V1 : RUNTIME_SURFACE_EMPTY_V1;
    Binding inserted =
        binding(
            exactRequest,
            exact,
            persisted.getId(),
            Objects.requireNonNull(release.id(), "release.id"),
            versionStateEpoch,
            runtimeSurface,
            capturedTemplate.gameLogicReceipt());
    Binding persistedBinding = bindings.insertImmutable(inserted);
    requireBindingMatches(
        persistedBinding,
        persisted,
        exactRequest,
        exact,
        descriptorRequest,
        source,
        capturedTemplate.gameLogicReceipt());
    if (deterministicFailure) {
      return OwnerOutcome.failed(
          new FrozenFailure(INVALID_TEMPLATE_CONFIGURATION, UNSUPPORTED_CAPTURE_MESSAGE));
    }
    ResolvedLaunchDescriptorDto resolved =
        readStored(persisted, descriptorRequest, requestJson, source, sourceJson);
    return OwnerOutcome.succeeded(new ResolvedBinding(exactRequest, exact, resolved));
  }

  private CapturedTemplate requireCapturedTemplate(Result exact, long templateId) {
    var readback =
        publicationOperations
            .read(exact.association().publishWorkflowId())
            .orElseThrow(() -> conflict("Exact selected publication operation is unavailable"));
    var operation = readback.operation();
    var selection = operation.account().input().selection();
    if (!"PUBLISHED".equals(readback.outcome())
        || !operation.workflowId().equals(exact.association().publishWorkflowId())
        || !operation.tenantKey().equals(selection.target().gameDesignVersionTenantKey())
        || operation.versionId() != selection.target().gameDesignVersionRowId()
        || !selection.target().canonicalTenantId().equals(exact.association().canonicalTenantId())
        || !selection.target().canonicalVersionId().equals(exact.association().canonicalVersionId())
        || !selection.selectedCommit().commitId().equals(exact.association().selectedCommitId())
        || readback.terminalEvidenceBytes() == null) {
      throw conflict("Published owner operation differs from the pinned association");
    }
    GameDesignPublicationTerminalEvidence terminal =
        GameDesignPublicationTerminalEvidence.fromStored(readback.terminalEvidenceBytes());
    if (terminal.outcome() != GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
        || !Arrays.equals(terminal.operationBytes(), operation.canonicalBytes())
        || terminal.releaseContent() == null
        || terminal.publicationVersionStateEpoch() <= 0L) {
      throw conflict("Exact selected publication terminal evidence is incomplete");
    }
    GameDesignSourceRepository.Capture capture =
        sourceRepository
            .readCapture(operation)
            .orElseThrow(
                () -> conflict("Complete exact publication source capture is unavailable"));
    var templateCapture = capture.templateConfig();
    if (!templateCapture.snapshot().binding().equals(selection.selectedCommit())) {
      throw conflict("Selected template capture differs from the original committed source");
    }
    List<TemplateConfigSource.Entry> entries =
        templateCapture.snapshot().entries().stream()
            .filter(entry -> Long.parseLong(entry.templateId()) == templateId)
            .toList();
    if (entries.size() != 1) {
      throw conflict("Exact selected template is missing or duplicated in the committed capture");
    }
    GameLogicReceiptBinding gameLogicReceipt = requireSelectedGameLogicReceipt(operation);
    TemplateConfigSource.Entry entry = entries.getFirst();
    if (!entry.config().baseVersionId().equals(exact.association().canonicalVersionId())) {
      throw conflict("Original captured template base differs from the pinned selected Version");
    }
    String unsupported = null;
    try {
      entry.config().requireAvailableOwnerReads();
    } catch (IllegalStateException unavailableOwner) {
      if (!List.of(
              "TEMPLATE_CONFIG_WORLD_EXACT_OWNER_READ_UNAVAILABLE",
              "TEMPLATE_CONFIG_ENTITY_EXACT_OWNER_READ_UNAVAILABLE",
              "TEMPLATE_CONFIG_AUTOMATION_EXACT_OWNER_READ_UNAVAILABLE")
          .contains(unavailableOwner.getMessage())) {
        throw conflict("Captured template owner-reference validation is unavailable");
      }
      unsupported = UNSUPPORTED_CAPTURE_MESSAGE;
    }

    List<SelectedDraftTemplateWorldSourceAssociation.Association> exactRows =
        associations.readExact(operation, templateCapture).stream()
            .filter(value -> value.templateId() == templateId)
            .toList();
    if (exactRows.size() != 1) {
      throw conflict("Exact immutable selected template/source association is unavailable");
    }
    var row = exactRows.getFirst();
    Association publicAssociation = exact.association();
    if (!row.canonicalTenantId().equals(publicAssociation.canonicalTenantId())
        || row.templateId() != publicAssociation.templateId()
        || !row.canonicalVersionId().equals(publicAssociation.canonicalVersionId())
        || !row.selectedCommitId().equals(publicAssociation.selectedCommitId())
        || !row.publishWorkflowId().equals(publicAssociation.publishWorkflowId())
        || !row.digest().equals(publicAssociation.associationDigest())
        || !row.targetNamespace().equals(publicAssociation.targetNamespace())
        || !row.sourceOperationId().equals(publicAssociation.sourceOperationId())
        || !row.sourceEvidenceDigest().equals(publicAssociation.sourceEvidenceDigest())
        || !row.worldSlug().equals(publicAssociation.worldSlug())) {
      throw conflict("Retained association row differs from the public pinned association");
    }
    return new CapturedTemplate(unsupported, gameLogicReceipt);
  }

  /**
   * Exact Game Logic receipt/source join already required by the selected-publication inventory.
   */
  private GameLogicReceiptBinding requireSelectedGameLogicReceipt(
      GameDesignPublicationOperation operation) {
    var selection = operation.account().input().selection();
    var target = selection.target();
    var publicationRequest =
        PublicationDigestRequestBinding.full(
            selection.intent().canonicalTenantId().toString(),
            Long.toString(target.gameDesignVersionRowId()),
            selection.intent().publishRequestId());
    SelectedDraftGameLogicReceipt receipt =
        new SelectedDraftGameLogicReceiptRepository(dsl)
            .readForPublication(publicationRequest)
            .orElseThrow(() -> conflict("Exact retained Game Logic source receipt is unavailable"));
    GameplayRuleSnapshot selectedRules =
        new GameplayRuleSourceRepository(dsl)
            .readSnapshot(target, selection.selectedCommit().commitId())
            .orElseThrow(
                () -> conflict("Exact selected Game Logic source snapshot is unavailable"));
    if (!Arrays.equals(selection.canonicalBytes(), receipt.selection().canonicalBytes())
        || !selection.selectedCommit().equals(selectedRules.binding())
        || !Arrays.equals(
            selectedRules.canonicalBytes(), receipt.authorization().source().canonicalBytes())) {
      throw conflict("Retained Game Logic receipt differs from the exact selected source");
    }
    return new GameLogicReceiptBinding(
        selection.intent().publishRequestId(),
        selection.digest(),
        receipt.authorization().digest(),
        receipt.receipt().digest());
  }

  private void requireReleaseMatches(
      Result exact, PublishedReleaseBundleDto release, Version version) {
    var selected = exact.releaseBundle();
    var current = toPublishedReleaseBundle(release);
    if (!selected.equals(current)
        || release.id() == null
        || release.id() <= 0L
        || !Objects.equals(release.id(), selected.getId())
        || !Objects.equals(release.versionId(), version.getId())
        || !Objects.equals(release.canonicalTenantId(), exact.association().canonicalTenantId())
        || !Objects.equals(release.canonicalVersionId(), exact.association().canonicalVersionId())
        || !Objects.equals(release.publishWorkflowId(), exact.association().publishWorkflowId())
        || !("v2".equals(release.attestationSchemaVersion())
            || "v3".equals(release.attestationSchemaVersion()))
        || !Objects.equals(
            release.worldPublishedStartLocationEvidence(),
            exact.worldPublishedStartLocationEvidence())
        || release.scriptOnly()
        || release.scriptPatchVersion() != null && !release.scriptPatchVersion().isBlank()) {
      throw conflict("Current release row differs from the exact committed published release");
    }
    var releaseContent = releaseContent(release);
    var readback =
        publicationOperations.read(exact.association().publishWorkflowId()).orElseThrow();
    if (readback.terminalEvidenceBytes() == null) {
      throw conflict("Exact published terminal evidence is unavailable");
    }
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(readback.terminalEvidenceBytes());
    if (terminal.releaseContent() == null
        || terminal.publicationVersionStateEpoch() > version.getVersionStateEpoch()
        || !Arrays.equals(
            terminal.releaseContent().canonicalBytes(), releaseContent.canonicalBytes())) {
      throw conflict(
          "Current release content differs from the original published terminal evidence");
    }
  }

  private void lockAndReloadSelectedVersion(
      Version version, UUID canonicalTenantId, UUID canonicalVersionId) {
    Record row =
        dsl.fetchOne(
            "SELECT id FROM version WHERE id = ? AND canonical_tenant_id = ? "
                + "AND canonical_version_id = ? FOR UPDATE",
            version.getId(),
            canonicalTenantId,
            canonicalVersionId);
    if (row == null || !Objects.equals(row.get("id", Long.class), version.getId())) {
      throw conflict("Exact canonical Version row could not be locked for descriptor creation");
    }
  }

  private void requireLaunchableVersion(
      Version version,
      AuthoredWorldSourceEvidence source,
      TemplateReferenceRepository.PhaseSnapshot phase,
      Association association) {
    if (!Objects.equals(version.getCanonicalTenantId(), association.canonicalTenantId())
        || !Objects.equals(version.getCanonicalVersionId(), association.canonicalVersionId())
        || !Objects.equals(version.getTenantId(), source.sourceGameTenantKey())
        || !Objects.equals(version.getIdentitySourceGameRowId(), phase.sourceGameRowId())
        || !Objects.equals(version.getIdentitySourceGameTenantKey(), phase.sourceGameTenantKey())
        || !Objects.equals(version.getIdentitySourceProvenanceKind(), phase.provenanceKind())
        || version.getVersionStateEpoch() == null
        || version.getVersionStateEpoch() <= 0L
        || !(version.getVersionState() == VersionLifecycleState.PUBLISHED
            || version.getVersionState() == VersionLifecycleState.ACTIVE)
        || version.isScriptOnly()
        || version.getBaseVersionId() != null
        || version.getScriptPatchVersion() != null) {
      throw conflict("Exact fresh published full Version is not launchable");
    }
  }

  private void requireSourceMatches(Association association, AuthoredWorldSourceEvidence source) {
    if (!workloadNamespace.equals(source.targetNamespace())
        || !association.targetNamespace().equals(source.targetNamespace())
        || !association.canonicalTenantId().equals(source.canonicalTenantId())
        || !association.sourceOperationId().equals(source.operationId())
        || !association.sourceEvidenceDigest().equals(source.evidenceDigest())
        || !association.worldSlug().equals(source.worldSlug())
        || !"NEW_GAME_ROW".equals(source.provenanceKind())
        || source.sourceGameRowId() <= 0L
        || source.sourceGameTenantKey() == null
        || source.sourceGameTenantKey().isBlank()) {
      throw conflict("Exact association source does not match fresh Game Design source evidence");
    }
  }

  private void requireExactAssociationRead(Request request, Result result) {
    if (result == null
        || !request.equals(result.request())
        || !(result.request().selection() instanceof ExactReplay replay)
        || !replay.canonicalVersionId().equals(result.association().canonicalVersionId())
        || !replay.selectedCommitId().equals(result.association().selectedCommitId())
        || !replay.publishWorkflowId().equals(result.association().publishWorkflowId())
        || !replay.associationDigest().equals(result.association().associationDigest())) {
      throw conflict("Account-gated association read differs from the exact Game Session replay");
    }
    var tuple = request.decodedTuple();
    var action = tuple.preAuthorizationTuple().action();
    if (!action.scope().tenantId().equals(result.association().canonicalTenantId())
        || action.target().gameTemplateId() != result.association().templateId()
        || !request.targetNamespace().equals(result.association().targetNamespace())) {
      throw conflict(
          "Exact association read differs from the original authorized StartSession target");
    }
  }

  private AuthoredWorldLaunchDescriptorEvidence.Request descriptorRequest(
      StartSessionPostAuthorizationExecutionTuple tuple, Association association) {
    var action = tuple.preAuthorizationTuple().action();
    if (!action.scope().tenantId().equals(association.canonicalTenantId())
        || action.target().gameTemplateId() != association.templateId()) {
      throw conflict("Association source differs from the original StartSession tenant/template");
    }
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        workloadNamespace,
        tuple.controlPlaneRequestId(),
        association.canonicalTenantId(),
        association.worldSlug(),
        association.sourceOperationId(),
        association.sourceEvidenceDigest(),
        association.templateId(),
        false,
        null,
        false,
        null,
        false,
        null,
        true,
        EMPTY_RUNTIME_FLAGS);
  }

  private LaunchDescriptor descriptorRow(
      AuthoredWorldLaunchDescriptorEvidence evidence,
      AuthoredWorldSourceEvidence source,
      String requestJson,
      String sourceJson) {
    LaunchDescriptor descriptor = new LaunchDescriptor();
    descriptor.setLaunchDescriptorId(evidence.launchDescriptorId());
    descriptor.setTenantId(source.sourceGameTenantKey());
    descriptor.setGameTemplateId(evidence.gameTemplateId());
    descriptor.setControlPlaneRequestId(evidence.controlPlaneRequestId());
    descriptor.setRequestHash(evidence.requestDigest());
    descriptor.setVersionId(evidence.versionId());
    descriptor.setScriptPatchVersion(null);
    descriptor.setRuntimeFlagsJson(evidence.runtimeFlagsJson());
    descriptor.setGenerationConfigRevision(evidence.generationConfigRevision());
    descriptor.setVersionStateEpoch(evidence.versionStateEpoch());
    descriptor.setReleaseBundleId(evidence.releaseBundleId());
    descriptor.setPublishedReleaseBundleRef(evidence.publishedReleaseBundleRef());
    descriptor.setRemapSetId(null);
    descriptor.setDescriptorSchemaVersion(evidence.schemaVersion());
    descriptor.setTargetNamespace(evidence.targetNamespace());
    descriptor.setCanonicalTenantId(evidence.canonicalTenantId().toString());
    descriptor.setWorldSlug(evidence.worldSlug());
    descriptor.setAuthoredWorldSourceOperationId(
        evidence.authoredWorldSourceOperationId().toString());
    descriptor.setAuthoredWorldSourceEvidenceDigest(evidence.authoredWorldSourceEvidenceDigest());
    descriptor.setRequestDigest(evidence.requestDigest());
    descriptor.setResultDigest(evidence.resultDigest());
    descriptor.setOriginalRequestJson(requestJson);
    descriptor.setSourceEvidenceJson(sourceJson);
    return descriptor;
  }

  private Binding binding(
      Request request,
      Result result,
      Long descriptorRowId,
      Long releaseBundleId,
      Long versionStateEpoch,
      String runtimeSurface,
      GameLogicReceiptBinding gameLogicReceipt) {
    Association association = result.association();
    var tuple = request.decodedTuple();
    return new Binding(
        workloadNamespace,
        association.canonicalTenantId(),
        tuple.controlPlaneRequestId(),
        descriptorRowId,
        releaseBundleId,
        versionStateEpoch,
        request.canonicalPostAuthorizationTuple(),
        request.ownerAttemptId(),
        request.ownerFence(),
        association.templateId(),
        association.canonicalVersionId(),
        association.selectedCommitId(),
        association.publishWorkflowId(),
        association.publicationSelectionDigest(),
        association.associationDigest(),
        association.sourceOperationId(),
        association.sourceEvidenceDigest(),
        association.worldSlug(),
        result.phaseEpoch(),
        runtimeSurface,
        gameLogicReceipt.publishRequestId(),
        gameLogicReceipt.selectionDigest(),
        gameLogicReceipt.authorizationDigest(),
        gameLogicReceipt.receiptDigest(),
        StartSessionTemplateAssociationReadGrpcCodec.toResponse(result).toByteArray(),
        java.time.LocalDateTime.now());
  }

  private void requireBindingMatches(
      Binding binding,
      LaunchDescriptor descriptor,
      Request request,
      Result result,
      AuthoredWorldLaunchDescriptorEvidence.Request descriptorRequest,
      AuthoredWorldSourceEvidence source,
      GameLogicReceiptBinding gameLogicReceipt) {
    Association association = result.association();
    if (!Objects.equals(binding.targetNamespace(), workloadNamespace)
        || !Objects.equals(binding.canonicalTenantId(), association.canonicalTenantId())
        || !Objects.equals(
            binding.controlPlaneRequestId(), descriptorRequest.controlPlaneRequestId())
        || !Objects.equals(binding.descriptorRowId(), descriptor.getId())
        || !Objects.equals(binding.releaseBundleId(), result.releaseBundle().getId())
        || (LaunchDescriptor.OUTCOME_SUCCESS.equals(descriptor.getOutcomeStatus())
            && !Objects.equals(binding.versionStateEpoch(), descriptor.getVersionStateEpoch()))
        || !Arrays.equals(
            binding.canonicalPostAuthorizationTuple(), request.canonicalPostAuthorizationTuple())
        || !Objects.equals(binding.ownerAttemptId(), request.ownerAttemptId())
        || !Objects.equals(binding.ownerFence(), request.ownerFence())
        || !Objects.equals(binding.gameTemplateId(), association.templateId())
        || !Objects.equals(binding.canonicalVersionId(), association.canonicalVersionId())
        || !Objects.equals(binding.selectedCommitId(), association.selectedCommitId())
        || !Objects.equals(binding.publishWorkflowId(), association.publishWorkflowId())
        || !Objects.equals(
            binding.publicationSelectionDigest(), association.publicationSelectionDigest())
        || !Objects.equals(binding.associationDigest(), association.associationDigest())
        || !Objects.equals(binding.sourceOperationId(), association.sourceOperationId())
        || !Objects.equals(binding.sourceEvidenceDigest(), association.sourceEvidenceDigest())
        || !Objects.equals(binding.worldSlug(), association.worldSlug())
        || !Objects.equals(binding.referencePhaseEpoch(), result.phaseEpoch())
        || !Objects.equals(binding.gameLogicPublishRequestId(), gameLogicReceipt.publishRequestId())
        || !Objects.equals(binding.gameLogicSelectionDigest(), gameLogicReceipt.selectionDigest())
        || !Objects.equals(
            binding.gameLogicAuthorizationDigest(), gameLogicReceipt.authorizationDigest())
        || !Objects.equals(binding.gameLogicReceiptDigest(), gameLogicReceipt.receiptDigest())
        || !(LaunchDescriptor.OUTCOME_SUCCESS.equals(descriptor.getOutcomeStatus())
                && Objects.equals(binding.runtimeSurface(), RUNTIME_SURFACE_EMPTY_V1)
            || LaunchDescriptor.OUTCOME_FAILED.equals(descriptor.getOutcomeStatus())
                && Objects.equals(binding.runtimeSurface(), RUNTIME_SURFACE_UNSUPPORTED_CAPTURE_V1))
        || !source.operationId().equals(binding.sourceOperationId())
        || !source.evidenceDigest().equals(binding.sourceEvidenceDigest())
        || !sameAssociationReadResponse(binding.associationReadResponseBytes(), request, result)) {
      throw conflict("Immutable StartSession descriptor association binding changed or conflicts");
    }
  }

  private boolean sameAssociationReadResponse(
      byte[] storedBytes, Request currentRequest, Result current) {
    try {
      var stored =
          net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse
              .parseFrom(storedBytes);
      Request storedRequest =
          StartSessionTemplateAssociationReadGrpcCodec.fromRequest(stored.getRequest());
      Result previous =
          StartSessionTemplateAssociationReadGrpcCodec.fromResponse(storedRequest, stored);
      return sameLogicalRequest(storedRequest, currentRequest)
          && previous.association().equals(current.association())
          && previous.releaseBundle().equals(current.releaseBundle())
          && previous
              .worldPublishedStartLocationEvidence()
              .equals(current.worldPublishedStartLocationEvidence())
          && previous.phaseEpoch() == current.phaseEpoch();
    } catch (IOException | IllegalArgumentException invalid) {
      throw conflict("Stored exact StartSession association evidence is corrupt");
    }
  }

  private boolean sameLogicalRequest(Request left, Request right) {
    return left.schemaVersion() == right.schemaVersion()
        && left.targetNamespace().equals(right.targetNamespace())
        && Arrays.equals(
            left.canonicalPostAuthorizationTuple(), right.canonicalPostAuthorizationTuple())
        && left.ownerAttemptId().equals(right.ownerAttemptId())
        && left.ownerFence() == right.ownerFence()
        && left.selection().equals(right.selection());
  }

  private ResolvedLaunchDescriptorDto readStored(
      LaunchDescriptor descriptor,
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      String requestJson,
      AuthoredWorldSourceEvidence source,
      String sourceJson) {
    if (!LaunchDescriptor.OUTCOME_SUCCESS.equals(descriptor.getOutcomeStatus())
        || descriptor.getDescriptorSchemaVersion() == null
        || descriptor.getDescriptorSchemaVersion()
            != AuthoredWorldLaunchDescriptorEvidence.SCHEMA_VERSION
        || !workloadNamespace.equals(descriptor.getTargetNamespace())
        || !request.canonicalTenantId().toString().equals(descriptor.getCanonicalTenantId())
        || !request.worldSlug().equals(descriptor.getWorldSlug())
        || !request
            .authoredWorldSourceOperationId()
            .toString()
            .equals(descriptor.getAuthoredWorldSourceOperationId())
        || !request
            .authoredWorldSourceEvidenceDigest()
            .equals(descriptor.getAuthoredWorldSourceEvidenceDigest())
        || !request.controlPlaneRequestId().equals(descriptor.getControlPlaneRequestId())
        || !Objects.equals(descriptor.getTenantId(), source.sourceGameTenantKey())
        || request.gameTemplateId() != descriptor.getGameTemplateId()
        || !requestJson.equals(descriptor.getOriginalRequestJson())
        || !sourceJson.equals(descriptor.getSourceEvidenceJson())
        || !request.requestDigest().equals(descriptor.getRequestHash())
        || !request.requestDigest().equals(descriptor.getRequestDigest())) {
      throw conflict("Stored descriptor is not the exact immutable StartSession request");
    }
    AuthoredWorldLaunchDescriptorEvidence evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            descriptor.getLaunchDescriptorId(),
            descriptor.getVersionId(),
            descriptor.getScriptPatchVersion() != null,
            descriptor.getScriptPatchVersion(),
            descriptor.getRuntimeFlagsJson(),
            descriptor.getGenerationConfigRevision(),
            descriptor.getVersionStateEpoch(),
            descriptor.getReleaseBundleId(),
            descriptor.getPublishedReleaseBundleRef(),
            descriptor.getRemapSetId() != null,
            descriptor.getRemapSetId());
    evidence.requireValid();
    if (!evidence.resultDigest().equals(descriptor.getResultDigest())) {
      throw conflict("Stored launch descriptor result digest is inconsistent");
    }
    if (descriptor.getScriptPatchVersion() != null
        || descriptor.getRemapSetId() != null
        || !EMPTY_RUNTIME_FLAGS.equals(descriptor.getRuntimeFlagsJson())) {
      throw conflict("Stored descriptor exceeds the explicitly empty launch runtime surface");
    }
    return new ResolvedLaunchDescriptorDto(
        evidence.launchDescriptorId(),
        evidence.canonicalTenantId().toString(),
        evidence.gameTemplateId(),
        evidence.controlPlaneRequestId(),
        evidence.versionId(),
        evidence.scriptPatchVersion(),
        evidence.runtimeFlagsJson(),
        evidence.generationConfigRevision(),
        evidence.versionStateEpoch(),
        evidence.releaseBundleId(),
        evidence.publishedReleaseBundleRef(),
        evidence.remapSetId(),
        evidence);
  }

  private void requireSuccessfulDescriptor(LaunchDescriptor descriptor) {
    if (!LaunchDescriptor.OUTCOME_SUCCESS.equals(descriptor.getOutcomeStatus())
        || descriptor.getDescriptorSchemaVersion() == null) {
      throw conflict("Retained descriptor history is not an exact successful bound descriptor");
    }
  }

  private FrozenFailure requireStoredFailure(
      LaunchDescriptor descriptor,
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      String requestJson,
      AuthoredWorldSourceEvidence source,
      String sourceJson) {
    if (!LaunchDescriptor.OUTCOME_FAILED.equals(descriptor.getOutcomeStatus())
        || !Objects.equals(
            descriptor.getDescriptorSchemaVersion(),
            AuthoredWorldLaunchDescriptorEvidence.SCHEMA_VERSION)
        || !workloadNamespace.equals(descriptor.getTargetNamespace())
        || !request.canonicalTenantId().toString().equals(descriptor.getCanonicalTenantId())
        || !request.worldSlug().equals(descriptor.getWorldSlug())
        || !request
            .authoredWorldSourceOperationId()
            .toString()
            .equals(descriptor.getAuthoredWorldSourceOperationId())
        || !request
            .authoredWorldSourceEvidenceDigest()
            .equals(descriptor.getAuthoredWorldSourceEvidenceDigest())
        || !request.controlPlaneRequestId().equals(descriptor.getControlPlaneRequestId())
        || !request.requestDigest().equals(descriptor.getRequestHash())
        || !request.requestDigest().equals(descriptor.getRequestDigest())
        || !requestJson.equals(descriptor.getOriginalRequestJson())
        || !sourceJson.equals(descriptor.getSourceEvidenceJson())
        || !Objects.equals(source.sourceGameTenantKey(), descriptor.getTenantId())
        || descriptor.getLaunchDescriptorId() != null
        || descriptor.getGameTemplateId() != null
        || descriptor.getVersionId() != null
        || descriptor.getScriptPatchVersion() != null
        || descriptor.getRuntimeFlagsJson() != null
        || descriptor.getGenerationConfigRevision() != null
        || descriptor.getVersionStateEpoch() != null
        || descriptor.getReleaseBundleId() != null
        || descriptor.getPublishedReleaseBundleRef() != null
        || descriptor.getRemapSetId() != null
        || descriptor.getResultDigest() != null
        || !INVALID_TEMPLATE_CONFIGURATION.equals(descriptor.getFailureCode())
        || !UNSUPPORTED_CAPTURE_MESSAGE.equals(descriptor.getFailureMessage())) {
      throw conflict(
          "Stored failed descriptor is not the exact immutable unsupported-capture outcome");
    }
    return new FrozenFailure(descriptor.getFailureCode(), descriptor.getFailureMessage());
  }

  private LaunchDescriptor frozenFailure(
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      AuthoredWorldSourceEvidence source,
      String requestJson,
      String sourceJson,
      String failureCode,
      String failureMessage) {
    LaunchDescriptor descriptor = new LaunchDescriptor();
    descriptor.setTenantId(source.sourceGameTenantKey());
    descriptor.setControlPlaneRequestId(request.controlPlaneRequestId());
    descriptor.setRequestHash(request.requestDigest());
    descriptor.setDescriptorSchemaVersion(AuthoredWorldLaunchDescriptorEvidence.SCHEMA_VERSION);
    descriptor.setTargetNamespace(workloadNamespace);
    descriptor.setCanonicalTenantId(request.canonicalTenantId().toString());
    descriptor.setWorldSlug(request.worldSlug());
    descriptor.setAuthoredWorldSourceOperationId(
        request.authoredWorldSourceOperationId().toString());
    descriptor.setAuthoredWorldSourceEvidenceDigest(request.authoredWorldSourceEvidenceDigest());
    descriptor.setRequestDigest(request.requestDigest());
    descriptor.setOriginalRequestJson(requestJson);
    descriptor.setSourceEvidenceJson(sourceJson);
    descriptor.setOutcomeStatus(LaunchDescriptor.OUTCOME_FAILED);
    descriptor.setFailureCode(failureCode);
    descriptor.setFailureMessage(failureMessage);
    return descriptor;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("Could not encode exact immutable owner evidence", failure);
    }
  }

  private net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle toPublishedReleaseBundle(
      PublishedReleaseBundleDto dto) {
    var builder =
        net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle.newBuilder()
            .setId(dto.id())
            .setVersionId(dto.versionId())
            .setVersionNumber(dto.versionNumber())
            .setAttestationSchemaVersion(dto.attestationSchemaVersion())
            .setPublishWorkflowId(dto.publishWorkflowId())
            .setManifestHash(dto.manifestHash())
            .addAllRequiredManifestAssetKeys(dto.requiredManifestAssetKeys())
            .setIsScriptOnly(dto.scriptOnly())
            .setScriptPatchVersion(Objects.requireNonNullElse(dto.scriptPatchVersion(), ""))
            .setPublishedAt(dto.publishedAt().toString())
            .setGenerationConfigRevision(dto.generationConfigRevision())
            .addAllCommandDefinitions(dto.commandDefinitions())
            .setPublishedReleaseBundleRef(dto.publishedReleaseBundleRef())
            .setCanonicalTenantId(dto.canonicalTenantId().toString())
            .setCanonicalVersionId(dto.canonicalVersionId().toString());
    for (PublishParticipantDigestDto participant : dto.participantDigests()) {
      if (!participant.succeeded()) throw conflict("Published release participant did not succeed");
      var value =
          net.firedevops.firemud.gamedesign.v1.ParticipantDigest.newBuilder()
              .setParticipantKey(participant.participantKey())
              .setScopeValue(participant.scopeValue())
              .setAppliedCommitId(Objects.requireNonNull(participant.appliedCommitId()))
              .setContentDigest(Objects.requireNonNull(participant.contentDigest()))
              .setDigestSchemaVersion(Objects.requireNonNull(participant.digestSchemaVersion()));
      if (participant.abilitySchemaDigest() != null)
        value.setAbilitySchemaDigest(participant.abilitySchemaDigest());
      builder.addParticipantDigests(value);
    }
    if (dto.manifestSchemaVersion() != null)
      builder.setManifestSchemaVersion(dto.manifestSchemaVersion());
    if (dto.artifactDigests() != null) {
      for (PublishedArtifactDigest artifact : dto.artifactDigests()) {
        builder.addArtifactDigests(
            net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest.newBuilder()
                .setUsageKey(artifact.usageKey())
                .setArtifactKind(artifact.artifactKind())
                .setImmutableObjectKey(artifact.immutableObjectKey())
                .setContentDigest(artifact.contentDigest())
                .setContentType(artifact.contentType())
                .setArtifactSchemaVersion(artifact.artifactSchemaVersion()));
      }
    }
    return builder.build();
  }

  private GameDesignPublicationTerminalEvidence.ReleaseContent releaseContent(
      PublishedReleaseBundleDto dto) {
    if (dto.manifestSchemaVersion() == null || dto.artifactDigests() == null) {
      throw conflict("Immutable release content evidence is incomplete");
    }
    List<GameDesignPublicationTerminalEvidence.Participant> participants =
        dto.participantDigests().stream()
            .map(
                value ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        value.participantKey(),
                        value.scopeValue(),
                        value.baseVersionId(),
                        value.appliedCommitId(),
                        value.contentDigest(),
                        Objects.requireNonNull(value.digestSchemaVersion()),
                        value.abilitySchemaDigest(),
                        value.errorCode(),
                        value.errorMessage()))
            .toList();
    List<net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence.Artifact>
        artifacts =
            dto.artifactDigests().stream()
                .map(
                    value ->
                        new net.firedevops.firemud.common.gamedesign
                            .AuthoredWorldReleaseAttestationEvidence.Artifact(
                            value.usageKey(),
                            value.artifactKind(),
                            value.immutableObjectKey(),
                            value.contentDigest(),
                            value.contentType(),
                            value.artifactSchemaVersion()))
                .toList();
    return new GameDesignPublicationTerminalEvidence.ReleaseContent(
        dto.canonicalTenantId(),
        dto.canonicalVersionId(),
        dto.publishedReleaseBundleRef(),
        dto.versionNumber(),
        dto.attestationSchemaVersion(),
        dto.publishWorkflowId(),
        dto.manifestHash(),
        dto.manifestSchemaVersion(),
        artifacts,
        dto.requiredManifestAssetKeys(),
        participants,
        dto.commandDefinitions(),
        dto.generationConfigRevision(),
        dto.worldPublishedStartLocationEvidence());
  }

  private void requirePeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified same-namespace Game Session workload identity required")
          .asRuntimeException();
    }
    if (!("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")
        .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Session workload required")
          .asRuntimeException();
    }
  }

  private static StatusRuntimeException conflict(String description) {
    return Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException();
  }

  /**
   * Returned to the caller so Game Session can atomically pin this descriptor with the association.
   */
  public record ResolvedBinding(
      Request request, Result associationRead, ResolvedLaunchDescriptorDto descriptor) {
    public ResolvedBinding {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(associationRead, "associationRead");
      Objects.requireNonNull(descriptor, "descriptor");
      if (!request.equals(associationRead.request())
          || !request
              .decodedTuple()
              .controlPlaneRequestId()
              .equals(descriptor.controlPlaneRequestId())
          || !request
              .decodedTuple()
              .preAuthorizationTuple()
              .action()
              .scope()
              .tenantId()
              .toString()
              .equals(descriptor.canonicalTenantId())
          || request.decodedTuple().preAuthorizationTuple().action().target().gameTemplateId()
              != descriptor.gameTemplateId()) {
        throw new IllegalArgumentException(
            "Resolved descriptor differs from exact StartSession binding");
      }
    }
  }

  /**
   * A proved immutable business result, exposed only after its owner transaction commits. RPC
   * failures and missing evidence never become this type, so transport adapters need not guess
   * whether a FAILED_PRECONDITION represents a retained result.
   */
  public static final class StoredBusinessDenial extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Result associationRead;
    private final String failureCode;

    private StoredBusinessDenial(Result associationRead, FrozenFailure failure) {
      super(failure.message());
      this.associationRead = Objects.requireNonNull(associationRead, "associationRead");
      this.failureCode = failure.code();
    }

    public Result associationRead() {
      return associationRead;
    }

    public String failureCode() {
      return failureCode;
    }
  }

  private record FrozenFailure(String code, String message) {
    private FrozenFailure {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(message, "message");
    }
  }

  private record CapturedTemplate(String failureMessage, GameLogicReceiptBinding gameLogicReceipt) {
    private CapturedTemplate {
      Objects.requireNonNull(gameLogicReceipt, "gameLogicReceipt");
    }
  }

  private record GameLogicReceiptBinding(
      String publishRequestId,
      String selectionDigest,
      String authorizationDigest,
      String receiptDigest) {
    private GameLogicReceiptBinding {
      Objects.requireNonNull(publishRequestId, "publishRequestId");
      Objects.requireNonNull(selectionDigest, "selectionDigest");
      Objects.requireNonNull(authorizationDigest, "authorizationDigest");
      Objects.requireNonNull(receiptDigest, "receiptDigest");
    }
  }

  private record OwnerOutcome(ResolvedBinding resolvedBinding, FrozenFailure failure) {
    private OwnerOutcome {
      if ((resolvedBinding == null) == (failure == null)) {
        throw new IllegalArgumentException("Exactly one descriptor outcome is required");
      }
    }

    static OwnerOutcome succeeded(ResolvedBinding binding) {
      return new OwnerOutcome(Objects.requireNonNull(binding), null);
    }

    static OwnerOutcome failed(FrozenFailure failure) {
      return new OwnerOutcome(null, Objects.requireNonNull(failure));
    }
  }
}
