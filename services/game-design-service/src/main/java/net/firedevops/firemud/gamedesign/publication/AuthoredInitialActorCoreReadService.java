package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence.ReadResult;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreSourceBinding;
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
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.StartSessionAuthoredActorCoreProjectionRepository;
import net.firedevops.firemud.gamedesign.repository.StartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.StartSessionLaunchDescriptorRepository.Binding;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import tools.jackson.databind.ObjectMapper;

/**
 * Standalone, read-only authenticated authored-core projection. It never invokes descriptor
 * resolution or repairs missing owner/projection rows.
 */
public final class AuthoredInitialActorCoreReadService {
  private static final String EMPTY_RUNTIME_FLAGS = "{}";
  private static final String RUNTIME_SURFACE_EMPTY_V1 = "EMPTY_V1";

  private final String workloadNamespace;
  private final StartSessionTemplateAssociationReadService associationReader;
  private final GameAuthoredWorldSourceRepository worldSources;
  private final LaunchDescriptorRepository descriptors;
  private final StartSessionLaunchDescriptorRepository descriptorBindings;
  private final StartSessionAuthoredActorCoreProjectionRepository projections;
  private final GameDesignPublicationOperationRepository publicationOperations;
  private final GameDesignSourceRepository sourceRepository;
  private final SelectedDraftTemplateWorldSourceAssociationRepository associations;
  private final SelectedDraftGameLogicReceiptRepository gameLogicReceipts;
  private final GameplayRuleSourceRepository gameplayRules;
  private final ObjectMapper objectMapper;

  public AuthoredInitialActorCoreReadService(
      DSLContext dsl,
      String workloadNamespace,
      StartSessionTemplateAssociationReadService associationReader,
      ObjectMapper objectMapper) {
    Objects.requireNonNull(dsl, "dsl");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    this.associationReader = Objects.requireNonNull(associationReader, "associationReader");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    worldSources = new GameAuthoredWorldSourceRepository(dsl);
    descriptors = new LaunchDescriptorRepository(dsl);
    descriptorBindings = new StartSessionLaunchDescriptorRepository(dsl);
    projections = new StartSessionAuthoredActorCoreProjectionRepository(dsl);
    publicationOperations = new GameDesignPublicationOperationRepository(dsl);
    sourceRepository = new GameDesignSourceRepository(dsl);
    associations = new SelectedDraftTemplateWorldSourceAssociationRepository(dsl);
    gameLogicReceipts = new SelectedDraftGameLogicReceiptRepository(dsl);
    gameplayRules = new GameplayRuleSourceRepository(dsl);
  }

  /** Caller authorization occurs before request correlation checks or the local snapshot. */
  public ReadResult read(ReadRequest request) {
    associationReader.requireAuthoredInitialActorCorePeer();
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.associationRequest().targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Authored-core request namespace differs from Game Design")
          .asRuntimeException();
    }
    try {
      return associationReader.readForAuthoredInitialActorCore(
          request.associationRequest(), exact -> readOwner(exact, request.associationRequest()));
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (DataAccessException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Game Design authored-core owner storage unavailable")
          .withCause(unavailable)
          .asRuntimeException();
    } catch (RuntimeException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "Exact retained authored-core owner evidence is unavailable or conflicting")
          .withCause(invalid)
          .asRuntimeException();
    }
  }

  private ReadResult readOwner(Result exact, Request request) {
    StartSessionPostAuthorizationExecutionTuple tuple = request.decodedTuple();
    var action = tuple.preAuthorizationTuple().action();
    Association association = exact.association();
    if (!(request.selection() instanceof ExactReplay replay)
        || !request.equals(exact.request())
        || !replay.canonicalVersionId().equals(association.canonicalVersionId())
        || !replay.selectedCommitId().equals(association.selectedCommitId())
        || !replay.publishWorkflowId().equals(association.publishWorkflowId())
        || !replay.associationDigest().equals(association.associationDigest())
        || !action.scope().tenantId().equals(association.canonicalTenantId())
        || action.target().gameTemplateId() != association.templateId()) {
      throw conflict("Account-gated association differs from the exact original request");
    }

    AuthoredWorldSourceEvidence source =
        worldSources
            .read(
                association.sourceOperationId(),
                association.canonicalTenantId(),
                association.worldSlug(),
                workloadNamespace)
            .orElseThrow(() -> conflict("Exact registered Game Design source is unavailable"));
    requireSourceMatches(association, source);

    String requestId = tuple.controlPlaneRequestId();
    Binding owner =
        descriptorBindings
            .find(workloadNamespace, association.canonicalTenantId(), requestId)
            .orElseThrow(
                () -> conflict("Retained StartSession descriptor owner binding is missing"));
    LaunchDescriptor descriptor =
        descriptors
            .findBoundByRequest(workloadNamespace, association.canonicalTenantId(), requestId)
            .orElseThrow(() -> conflict("Retained StartSession descriptor row is missing"));
    if (!Objects.equals(owner.descriptorRowId(), descriptor.getId())) {
      throw conflict("Descriptor binding does not point at the actual immutable descriptor row");
    }

    AuthoredWorldLaunchDescriptorEvidence.Request descriptorRequest =
        descriptorRequest(request, association);
    String originalRequestJson = writeJson(descriptorRequest);
    String sourceEvidenceJson = writeJson(source);
    requireSuccessfulDescriptor(
        descriptor, source, descriptorRequest, originalRequestJson, sourceEvidenceJson);

    CapturedSources captured = requireCapturedSources(exact, association.templateId());
    requireOwnerBinding(owner, descriptor, request, exact, source, captured.gameLogicReceipt());
    List<AuthoredInitialActorCoreProjection> retained =
        projections.replayExact(owner, captured.actorCoreBindings());
    return new ReadResult(request, exact, evidence(descriptor, descriptorRequest), retained);
  }

  private CapturedSources requireCapturedSources(Result exact, long templateId) {
    Association association = exact.association();
    var readback =
        publicationOperations
            .read(association.publishWorkflowId())
            .orElseThrow(() -> conflict("Exact selected publication operation is unavailable"));
    var operation = readback.operation();
    var selection = operation.account().input().selection();
    if (!"PUBLISHED".equals(readback.outcome())
        || !operation.workflowId().equals(association.publishWorkflowId())
        || !operation.tenantKey().equals(selection.target().gameDesignVersionTenantKey())
        || operation.versionId() != selection.target().gameDesignVersionRowId()
        || !selection.target().canonicalTenantId().equals(association.canonicalTenantId())
        || !selection.target().canonicalVersionId().equals(association.canonicalVersionId())
        || !selection.selectedCommit().commitId().equals(association.selectedCommitId())
        || readback.terminalEvidenceBytes() == null) {
      throw conflict("Published operation differs from the pinned association");
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
            .orElseThrow(() -> conflict("Complete selected publication capture is unavailable"));
    var templateCapture = capture.templateConfig();
    if (!templateCapture.snapshot().binding().equals(selection.selectedCommit())) {
      throw conflict("Selected template capture differs from its publication commit");
    }
    List<TemplateConfigSource.Entry> templateEntries =
        templateCapture.snapshot().entries().stream()
            .filter(entry -> Long.parseLong(entry.templateId()) == templateId)
            .toList();
    if (templateEntries.size() != 1
        || !templateEntries
            .getFirst()
            .config()
            .baseVersionId()
            .equals(association.canonicalVersionId())) {
      throw conflict("Exact selected template entry is missing, duplicated or misbound");
    }

    SelectedGameplayRules selectedRules = requireSelectedGameLogic(operation);
    List<SelectedDraftTemplateWorldSourceAssociation.Association> associationRows =
        associations.readExact(operation, templateCapture).stream()
            .filter(value -> value.templateId() == templateId)
            .toList();
    if (associationRows.size() != 1) {
      throw conflict("Exact immutable template source association is missing or ambiguous");
    }
    var row = associationRows.getFirst();
    if (!row.canonicalTenantId().equals(association.canonicalTenantId())
        || row.templateId() != association.templateId()
        || !row.canonicalVersionId().equals(association.canonicalVersionId())
        || !row.selectedCommitId().equals(association.selectedCommitId())
        || !row.publishWorkflowId().equals(association.publishWorkflowId())
        || !row.digest().equals(association.associationDigest())
        || !row.targetNamespace().equals(association.targetNamespace())
        || !row.sourceOperationId().equals(association.sourceOperationId())
        || !row.sourceEvidenceDigest().equals(association.sourceEvidenceDigest())
        || !row.worldSlug().equals(association.worldSlug())) {
      throw conflict("Retained private association differs from its public pinned result");
    }
    List<AuthoredInitialActorCoreSourceBinding> actorBindings =
        StartSessionLaunchDescriptorProducer.resolveSelectedAuthoredInitialActorCoreSources(
            association.sourceOperationId(),
            association.sourceEvidenceDigest(),
            association.worldSlug(),
            operation.workflowId(),
            operation.canonicalBytes(),
            readback.terminalEvidenceBytes(),
            selection.selectedCommit(),
            capture.policy().snapshot().shared(),
            selectedRules.snapshot());
    return new CapturedSources(actorBindings, selectedRules.receiptBinding());
  }

  private SelectedGameplayRules requireSelectedGameLogic(GameDesignPublicationOperation operation) {
    var selection = operation.account().input().selection();
    var target = selection.target();
    var publicationRequest =
        PublicationDigestRequestBinding.full(
            selection.intent().canonicalTenantId().toString(),
            Long.toString(target.gameDesignVersionRowId()),
            selection.intent().publishRequestId());
    SelectedDraftGameLogicReceipt receipt =
        gameLogicReceipts
            .readForPublication(publicationRequest)
            .orElseThrow(() -> conflict("Exact retained Game Logic source receipt is unavailable"));
    GameplayRuleSnapshot snapshot =
        gameplayRules
            .readSnapshot(target, selection.selectedCommit().commitId())
            .orElseThrow(() -> conflict("Exact selected Game Logic source is unavailable"));
    if (!Arrays.equals(selection.canonicalBytes(), receipt.selection().canonicalBytes())
        || !selection.selectedCommit().equals(snapshot.binding())
        || !Arrays.equals(
            snapshot.canonicalBytes(), receipt.authorization().source().canonicalBytes())) {
      throw conflict("Retained Game Logic receipt differs from the selected source");
    }
    return new SelectedGameplayRules(
        snapshot,
        new GameLogicReceiptBinding(
            selection.intent().publishRequestId(),
            selection.digest(),
            receipt.authorization().digest(),
            receipt.receipt().digest()));
  }

  private void requireOwnerBinding(
      Binding owner,
      LaunchDescriptor descriptor,
      Request request,
      Result exact,
      AuthoredWorldSourceEvidence source,
      GameLogicReceiptBinding logic) {
    Association association = exact.association();
    if (!Objects.equals(owner.targetNamespace(), workloadNamespace)
        || !Objects.equals(owner.canonicalTenantId(), association.canonicalTenantId())
        || !Objects.equals(
            owner.controlPlaneRequestId(), request.decodedTuple().controlPlaneRequestId())
        || !Objects.equals(owner.descriptorRowId(), descriptor.getId())
        || !Objects.equals(owner.releaseBundleId(), exact.releaseBundle().getId())
        || !Objects.equals(owner.versionStateEpoch(), descriptor.getVersionStateEpoch())
        || !Arrays.equals(
            owner.canonicalPostAuthorizationTuple(), request.canonicalPostAuthorizationTuple())
        || !Objects.equals(owner.ownerAttemptId(), request.ownerAttemptId())
        || !Objects.equals(owner.ownerFence(), request.ownerFence())
        || !Objects.equals(owner.gameTemplateId(), association.templateId())
        || !Objects.equals(owner.canonicalVersionId(), association.canonicalVersionId())
        || !Objects.equals(owner.selectedCommitId(), association.selectedCommitId())
        || !Objects.equals(owner.publishWorkflowId(), association.publishWorkflowId())
        || !Objects.equals(
            owner.publicationSelectionDigest(), association.publicationSelectionDigest())
        || !Objects.equals(owner.associationDigest(), association.associationDigest())
        || !Objects.equals(owner.sourceOperationId(), association.sourceOperationId())
        || !Objects.equals(owner.sourceEvidenceDigest(), association.sourceEvidenceDigest())
        || !Objects.equals(owner.worldSlug(), association.worldSlug())
        || !Objects.equals(owner.referencePhaseEpoch(), exact.phaseEpoch())
        || !Objects.equals(owner.runtimeSurface(), RUNTIME_SURFACE_EMPTY_V1)
        || !Objects.equals(owner.gameLogicPublishRequestId(), logic.publishRequestId())
        || !Objects.equals(owner.gameLogicSelectionDigest(), logic.selectionDigest())
        || !Objects.equals(owner.gameLogicAuthorizationDigest(), logic.authorizationDigest())
        || !Objects.equals(owner.gameLogicReceiptDigest(), logic.receiptDigest())
        || !Arrays.equals(
            owner.associationReadResponseBytes(),
            StartSessionTemplateAssociationReadGrpcCodec.toResponse(exact).toByteArray())
        || !Objects.equals(source.operationId(), owner.sourceOperationId())
        || !Objects.equals(source.evidenceDigest(), owner.sourceEvidenceDigest())) {
      throw conflict("Immutable StartSession descriptor owner binding differs from exact read");
    }
  }

  private void requireSuccessfulDescriptor(
      LaunchDescriptor descriptor,
      AuthoredWorldSourceEvidence source,
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      String requestJson,
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
        || !request.requestDigest().equals(descriptor.getRequestDigest())
        || !request.requestDigest().equals(descriptor.getRequestHash())
        || descriptor.getScriptPatchVersion() != null
        || descriptor.getRemapSetId() != null
        || !EMPTY_RUNTIME_FLAGS.equals(descriptor.getRuntimeFlagsJson())) {
      throw conflict("Stored descriptor is not the exact successful immutable binding");
    }
  }

  private AuthoredWorldLaunchDescriptorEvidence evidence(
      LaunchDescriptor descriptor, AuthoredWorldLaunchDescriptorEvidence.Request request) {
    var evidence =
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
      throw conflict("Stored descriptor result digest differs from its complete content");
    }
    return evidence;
  }

  private AuthoredWorldLaunchDescriptorEvidence.Request descriptorRequest(
      Request request, Association association) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        request.targetNamespace(),
        request.decodedTuple().controlPlaneRequestId(),
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

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception invalid) {
      throw new IllegalStateException("Cannot encode exact retained descriptor evidence", invalid);
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
      throw conflict("Exact association source differs from registered source evidence");
    }
  }

  private static StatusRuntimeException conflict(String description) {
    return Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException();
  }

  private record CapturedSources(
      List<AuthoredInitialActorCoreSourceBinding> actorCoreBindings,
      GameLogicReceiptBinding gameLogicReceipt) {
    private CapturedSources {
      actorCoreBindings = List.copyOf(actorCoreBindings);
      Objects.requireNonNull(gameLogicReceipt, "gameLogicReceipt");
    }
  }

  private record SelectedGameplayRules(
      GameplayRuleSnapshot snapshot, GameLogicReceiptBinding receiptBinding) {}

  private record GameLogicReceiptBinding(
      String publishRequestId,
      String selectionDigest,
      String authorizationDigest,
      String receiptDigest) {}
}
