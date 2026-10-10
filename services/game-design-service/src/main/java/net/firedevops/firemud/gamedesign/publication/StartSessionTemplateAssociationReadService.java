package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.common.account.StartSessionRedeemedOperationProjectionClient;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Standalone, unregistered exact StartSession template-association read. It verifies Account's
 * original redeemed operation before any local query and returns only immutable public evidence.
 */
public final class StartSessionTemplateAssociationReadService {
  private final DSLContext dsl;
  private final TemplateReferenceRepository templateReferences;
  private final TemplateConfigSourceRepository templateSources;
  private final VersionRepository versions;
  private final GameDesignPublicationOperationRepository publicationOperations;
  private final GameDesignSourceRepository sourceRepository;
  private final SelectedDraftTemplateWorldSourceAssociationRepository associations;
  private final PublishedReleaseBundleService releases;
  private final TransactionTemplate snapshot;
  private final String workloadNamespace;
  private final AccountProjectionReader accountProjectionReader;

  /**
   * Production construction always delegates to the explicit same-namespace Account mTLS client.
   */
  public StartSessionTemplateAssociationReadService(
      DSLContext dsl,
      PlatformTransactionManager transactions,
      String workloadNamespace,
      StartSessionRedeemedOperationProjectionClient accountProjectionClient,
      PublishedReleaseBundleService releases) {
    this(
        dsl,
        transactions,
        workloadNamespace,
        Objects.requireNonNull(accountProjectionClient, "accountProjectionClient")::read,
        releases);
  }

  StartSessionTemplateAssociationReadService(
      DSLContext dsl,
      PlatformTransactionManager transactions,
      String workloadNamespace,
      AccountProjectionReader accountProjectionReader,
      PublishedReleaseBundleService releases) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.templateReferences = new TemplateReferenceRepository(dsl);
    this.templateSources = new TemplateConfigSourceRepository(dsl);
    this.versions = new VersionRepository(dsl);
    this.publicationOperations = new GameDesignPublicationOperationRepository(dsl);
    this.sourceRepository = new GameDesignSourceRepository(dsl);
    this.associations = new SelectedDraftTemplateWorldSourceAssociationRepository(dsl);
    this.releases = Objects.requireNonNull(releases, "releases");
    this.accountProjectionReader =
        Objects.requireNonNull(accountProjectionReader, "accountProjectionReader");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    snapshot = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    snapshot.setName("game-design-start-session-template-association-read");
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
  }

  /**
   * The typed request is untrusted. This checks the peer, independently verifies Account's exact
   * operation/attempt projection, and only then opens a new local repeatable-read snapshot.
   */
  public Result read(Request request) {
    requirePeer();
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("StartSession association namespace differs from peer")
          .asRuntimeException();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Independent StartSession association snapshot required")
          .asRuntimeException();
    }

    final StartSessionPostAuthorizationExecutionTuple tuple;
    try {
      tuple = request.decodedTuple();
      requireAuthorizedTarget(request, tuple);
      ReadRedeemedOperationProjectionResponse projection =
          accountProjectionReader.read(tuple, request.ownerAttemptId(), request.ownerFence());
      requireAccountProjection(request, tuple, projection);
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (RuntimeException unavailableOrInvalid) {
      throw Status.UNAVAILABLE
          .withDescription("Exact redeemed StartSession projection unavailable")
          .withCause(unavailableOrInvalid)
          .asRuntimeException();
    }

    try {
      return Objects.requireNonNull(
          snapshot.execute(ignored -> readSnapshot(request, tuple)),
          "StartSession association snapshot returned no result");
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (DataAccessException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Game Design association owner storage unavailable")
          .withCause(unavailable)
          .asRuntimeException();
    } catch (RuntimeException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact StartSession template association is unavailable or conflicting")
          .withCause(invalid)
          .asRuntimeException();
    }
  }

  private Result readSnapshot(Request request, StartSessionPostAuthorizationExecutionTuple tuple) {
    var preTuple = tuple.preAuthorizationTuple();
    UUID canonicalTenantId = preTuple.action().scope().tenantId();
    long templateId = preTuple.action().target().gameTemplateId();

    var phase =
        templateReferences
            .readPhase(canonicalTenantId)
            .filter(value -> value.phase() == TemplateReferenceRepository.Phase.ENFORCED)
            .filter(value -> value.phaseEpoch() > 0L)
            .filter(
                value ->
                    value.inventoryTemplateCount() != null && value.inventoryTemplateCount() > 0L)
            .filter(value -> value.inventoryDigest() != null)
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Explicit enforced template reference phase unavailable")
                        .asRuntimeException());

    UUID canonicalVersionId;
    UUID selectedCommitId = null;
    TemplateReferenceRepository.BaseReference configuredReference = null;
    TemplateConfigSource.Entry configuredEntry = null;
    if (request.selection() instanceof InitialConfigured) {
      configuredReference =
          templateReferences
              .readExactBaseReference(canonicalTenantId, templateId)
              .orElseThrow(
                  () ->
                      Status.FAILED_PRECONDITION
                          .withDescription("Current normalized template reference unavailable")
                          .asRuntimeException());
      canonicalVersionId = configuredReference.canonicalVersionId();
      if (!canonicalTenantId.equals(configuredReference.canonicalTenantId())
          || templateId != configuredReference.templateId()) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_INITIAL_SELECTOR_SCOPE_CONFLICT");
      }
    } else if (request.selection() instanceof ExactReplay replay) {
      canonicalVersionId = replay.canonicalVersionId();
      selectedCommitId = replay.selectedCommitId();
    } else {
      throw Status.INVALID_ARGUMENT
          .withDescription("Explicit initial or exact replay selection required")
          .asRuntimeException();
    }

    Version version =
        versions
            .findByCanonicalTenantIdAndCanonicalVersionId(canonicalTenantId, canonicalVersionId)
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Exact canonical published Version unavailable")
                        .asRuntimeException());
    requireLaunchableVersion(version, canonicalTenantId, canonicalVersionId, phase);
    if (configuredReference != null) {
      configuredEntry =
          readCurrentConfiguredEntry(
              configuredReference, targetProof(version), version, templateId);
    }
    StartSessionTemplateAssociationReadEvidence.ExactReplay replay =
        request.selection() instanceof ExactReplay value ? value : null;
    String workflowId = replay == null ? null : replay.publishWorkflowId();
    if (workflowId == null) {
      // The canonical version has one exact immutable release row; its workflow ID, not a
      // workflow-list/latest query, selects the original publication operation.
      PublishedReleaseBundleDto configuredRelease =
          releases
              .findPublishedReleaseBundle(version.getTenantId(), version.getId())
              .orElseThrow(
                  () ->
                      Status.FAILED_PRECONDITION
                          .withDescription("Exact published release bundle unavailable")
                          .asRuntimeException());
      if (!canonicalTenantId.equals(configuredRelease.canonicalTenantId())
          || !canonicalVersionId.equals(configuredRelease.canonicalVersionId())
          || !("v2".equals(configuredRelease.attestationSchemaVersion())
              || "v3".equals(configuredRelease.attestationSchemaVersion())
              || "v4".equals(configuredRelease.attestationSchemaVersion()))) {
        throw new IllegalStateException("START_SESSION_CONFIGURED_RELEASE_IDENTITY_CONFLICT");
      }
      workflowId = configuredRelease.publishWorkflowId();
    }
    return readExactPublication(
        request,
        canonicalTenantId,
        templateId,
        canonicalVersionId,
        selectedCommitId,
        workflowId,
        replay,
        configuredEntry,
        version,
        phase.phaseEpoch());
  }

  private Result readExactPublication(
      Request request,
      UUID canonicalTenantId,
      long templateId,
      UUID canonicalVersionId,
      UUID selectedCommitId,
      String workflowId,
      ExactReplay replay,
      TemplateConfigSource.Entry configuredEntry,
      Version version,
      long phaseEpoch) {
    GameDesignPublicationOperationRepository.Readback operationReadback =
        publicationOperations
            .read(workflowId)
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Exact original publication operation unavailable")
                        .asRuntimeException());
    GameDesignPublicationOperation operation = operationReadback.operation();
    if (!"PUBLISHED".equals(operationReadback.outcome())
        || operationReadback.terminalEvidenceBytes() == null
        || !workflowId.equals(operation.workflowId())
        || !version.getTenantId().equals(operation.tenantKey())
        || !Objects.equals(version.getId(), operation.versionId())) {
      throw new IllegalStateException("START_SESSION_PUBLICATION_OPERATION_NOT_PUBLISHED");
    }
    GameDesignPublicationOperation exactOperation =
        publicationOperations.requireOutcome(
            operation.tenantKey(),
            workflowId,
            operation.versionId(),
            operation.selectionDigest(),
            "PUBLISHED");
    if (!Arrays.equals(exactOperation.canonicalBytes(), operation.canonicalBytes())) {
      throw new IllegalStateException("START_SESSION_PUBLICATION_SELECTION_CHANGED");
    }

    GameDesignPublicationTerminalEvidence terminal =
        GameDesignPublicationTerminalEvidence.fromStored(operationReadback.terminalEvidenceBytes());
    if (terminal.outcome() != GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
        || !Arrays.equals(terminal.operationBytes(), operation.canonicalBytes())
        || terminal.releaseContent() == null
        || terminal.publicationVersionStateEpoch() <= 0L) {
      throw new IllegalStateException("START_SESSION_PUBLICATION_TERMINAL_CONFLICT");
    }
    var selected = operation.account().input().selection();
    UUID actualSelectedCommitId = selected.selectedCommit().commitId();
    if (selectedCommitId != null && !selectedCommitId.equals(actualSelectedCommitId)) {
      throw new IllegalStateException("START_SESSION_REPLAY_SELECTION_CONFLICT");
    }
    selectedCommitId = actualSelectedCommitId;
    if (!canonicalTenantId.equals(selected.target().canonicalTenantId())
        || !canonicalVersionId.equals(selected.target().canonicalVersionId())
        || !Objects.equals(version.getId(), selected.target().gameDesignVersionRowId())
        || !targetProof(version).equals(selected.target())
        || !selectedCommitId.equals(selected.selectedCommit().commitId())
        || !workflowId.equals(operation.world().request().publishWorkflowId())
        || !canonicalTenantId.equals(operation.world().request().canonicalTenantId())
        || !canonicalVersionId.equals(operation.world().request().canonicalVersionId())
        || !request.targetNamespace().equals(operation.world().request().targetNamespace())) {
      throw new IllegalStateException("START_SESSION_OPERATION_COMMON_COMMIT_CONFLICT");
    }

    GameDesignSourceRepository.Capture capture =
        sourceRepository
            .readCapture(operation)
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Complete original selected source capture unavailable")
                        .asRuntimeException());
    TemplateConfigSourceSnapshot.Capture templateCapture = capture.templateConfig();
    if (!templateCapture.snapshot().binding().equals(selected.selectedCommit())) {
      throw new IllegalStateException("START_SESSION_CAPTURE_SOURCE_ENTRY_CONFLICT");
    }
    List<TemplateConfigSource.Entry> capturedTemplateEntries =
        templateCapture.snapshot().entries().stream()
            .filter(entry -> Long.parseLong(entry.templateId()) == templateId)
            .toList();
    if (capturedTemplateEntries.size() != 1) {
      throw new IllegalStateException("START_SESSION_CAPTURE_TEMPLATE_ENTRY_UNAVAILABLE");
    }
    TemplateConfigSource.Entry selectedTemplateEntry = capturedTemplateEntries.getFirst();
    if (!selectedTemplateEntry.config().baseVersionId().equals(canonicalVersionId)
        || replay == null
            && (configuredEntry == null || !configuredEntry.equals(selectedTemplateEntry))) {
      throw new IllegalStateException("START_SESSION_CAPTURE_SOURCE_ENTRY_CONFLICT");
    }
    var retainedAssociations = associations.readExact(operation, templateCapture);
    List<SelectedDraftTemplateWorldSourceAssociation.Association> templateAssociations =
        retainedAssociations.stream().filter(value -> value.templateId() == templateId).toList();
    if (templateAssociations.size() != 1) {
      throw new IllegalStateException("START_SESSION_TEMPLATE_ASSOCIATION_NOT_UNIQUE");
    }
    var retained = templateAssociations.getFirst();
    if (!canonicalTenantId.equals(retained.canonicalTenantId())
        || !canonicalVersionId.equals(retained.canonicalVersionId())
        || !selectedCommitId.equals(retained.selectedCommitId())
        || !workflowId.equals(retained.publishWorkflowId())
        || !retained.targetNamespace().equals(request.targetNamespace())) {
      throw new IllegalStateException("START_SESSION_TEMPLATE_ASSOCIATION_SELECTION_CONFLICT");
    }

    ReadAuthoredWorldSourceIntakeByIdRequest worldRequest;
    ReadAuthoredWorldSourceIntakeByIdResponse worldResponse;
    try {
      worldRequest =
          ReadAuthoredWorldSourceIntakeByIdRequest.parseFrom(retained.worldReadRequestBytes());
      worldResponse =
          ReadAuthoredWorldSourceIntakeByIdResponse.parseFrom(retained.worldReadResponseBytes());
    } catch (IOException malformed) {
      throw new IllegalStateException("START_SESSION_WORLD_SOURCE_RECEIPT_CORRUPT", malformed);
    }
    Association association =
        Association.fromStoredProjection(
            retained.canonicalTenantId(),
            retained.templateId(),
            retained.canonicalVersionId(),
            retained.selectedCommitId(),
            retained.publishWorkflowId(),
            operation.selectionDigest(),
            retained.digest(),
            retained.targetNamespace(),
            retained.intakeRequestId(),
            retained.worldOperationId(),
            retained.sourceOperationId(),
            retained.worldSlug(),
            retained.sourceEvidenceDigest(),
            worldRequest,
            worldResponse);

    if (replay != null
        && (!replay.canonicalVersionId().equals(association.canonicalVersionId())
            || !replay.selectedCommitId().equals(association.selectedCommitId())
            || !replay.publishWorkflowId().equals(association.publishWorkflowId())
            || !replay.associationDigest().equals(association.associationDigest()))) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact retry differs from the first pinned association")
          .asRuntimeException();
    }

    PublishedReleaseBundleDto release =
        releases
            .findPublishedReleaseBundle(version.getTenantId(), version.getId())
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Exact published release bundle unavailable")
                        .asRuntimeException());
    if (!workflowId.equals(release.publishWorkflowId())
        || !canonicalTenantId.equals(release.canonicalTenantId())
        || !canonicalVersionId.equals(release.canonicalVersionId())
        || !release.worldPublishedStartLocationEvidence().equals(operation.world())) {
      throw new IllegalStateException("START_SESSION_RELEASE_OWNER_EVIDENCE_CONFLICT");
    }
    var releaseContent = releaseContent(release);
    if (!Arrays.equals(
        terminal.releaseContent().canonicalBytes(), releaseContent.canonicalBytes())) {
      throw new IllegalStateException("START_SESSION_RELEASE_TERMINAL_CONTENT_CONFLICT");
    }
    WorldPublishedStartLocationEvidence worldEvidence =
        WorldPublishedStartLocationEvidence.fromStored(
            terminal.releaseContent().worldStartLocationEvidence().canonicalBytes());
    return new Result(
        request, association, toPublishedReleaseBundle(release), worldEvidence, phaseEpoch);
  }

  private TemplateConfigSource.Entry readCurrentConfiguredEntry(
      TemplateReferenceRepository.BaseReference ref,
      DraftCommitBinding.TargetProof target,
      Version version,
      long templateId) {
    if (ref == null
        || !ref.canonicalTenantId().equals(version.getCanonicalTenantId())
        || !ref.canonicalVersionId().equals(version.getCanonicalVersionId())
        || ref.templateId() != templateId) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_ENTRY_PROJECTION_CONFLICT");
    }
    Record head =
        dsl.fetchOne(
            "SELECT visible_commit_id FROM game_design_template_config_source_head "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            ref.canonicalTenantId(),
            ref.canonicalVersionId());
    UUID visibleCommitId = head == null ? null : head.get("visible_commit_id", UUID.class);
    if (visibleCommitId == null) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_SOURCE_HEAD_PROJECTION_CONFLICT");
    }
    TemplateConfigSourceSnapshot currentSource =
        templateSources
            .readSnapshot(target, visibleCommitId)
            .orElseThrow(
                () -> new IllegalStateException("TEMPLATE_REFERENCE_CURRENT_SOURCE_UNAVAILABLE"));
    List<TemplateConfigSource.Entry> currentEntries =
        currentSource.entries().stream()
            .filter(entry -> Long.parseLong(entry.templateId()) == templateId)
            .toList();
    if (!currentSource.binding().commitId().equals(visibleCommitId) || currentEntries.size() != 1) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_CURRENT_SOURCE_CONFLICT");
    }
    TemplateConfigSource.Entry currentEntry = currentEntries.getFirst();
    if (!currentEntry.sourceBinding().target().equals(target)
        || !currentEntry.sourceBinding().commitId().equals(ref.sourceCommitId())
        || !currentEntry.revisionId().equals(ref.sourceRevisionId())
        || !currentEntry.config().baseVersionId().equals(ref.canonicalVersionId())) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_ENTRY_PROJECTION_CONFLICT");
    }
    var privateTemplate =
        dsl.fetchOne(
            "SELECT tenant_id, default_version_id, config::TEXT AS config_json "
                + "FROM game_templates WHERE id = ?",
            templateId);
    if (privateTemplate == null
        || !version.getTenantId().equals(privateTemplate.get("tenant_id", String.class))
        || !Objects.equals(version.getId(), privateTemplate.get("default_version_id", Long.class))
        || !currentEntry
            .config()
            .canonicalJson()
            .equals(
                new TemplateConfigSource.Config(privateTemplate.get("config_json", String.class))
                    .canonicalJson())) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_CURRENT_SOURCE_PROJECTION_CONFLICT");
    }
    return currentEntry;
  }

  private void requireLaunchableVersion(
      Version version,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      TemplateReferenceRepository.PhaseSnapshot phase) {
    if (!Objects.equals(version.getCanonicalTenantId(), canonicalTenantId)
        || !Objects.equals(version.getCanonicalVersionId(), canonicalVersionId)
        || !(version.getVersionState() == VersionLifecycleState.PUBLISHED
            || version.getVersionState() == VersionLifecycleState.ACTIVE)
        || !"NEW_GAME_ROW".equals(phase.provenanceKind())
        || !Objects.equals(version.getIdentitySourceGameRowId(), phase.sourceGameRowId())
        || !Objects.equals(version.getIdentitySourceGameTenantKey(), phase.sourceGameTenantKey())
        || !Objects.equals(version.getIdentitySourceProvenanceKind(), phase.provenanceKind())
        || !Objects.equals(version.getTenantId(), phase.sourceGameTenantKey())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact fresh published or active template Version unavailable")
          .asRuntimeException();
    }
  }

  private DraftCommitBinding.TargetProof targetProof(Version version) {
    return new DraftCommitBinding.TargetProof(
        version.getCanonicalTenantId(),
        version.getCanonicalVersionId(),
        version.getId(),
        version.getTenantId(),
        version.getIdentitySourceGameRowId(),
        version.getIdentitySourceGameTenantKey(),
        version.getIdentitySourceProvenanceKind());
  }

  private void requireAuthorizedTarget(
      Request request, StartSessionPostAuthorizationExecutionTuple tuple) {
    var action = tuple.preAuthorizationTuple().action();
    if (!request.targetNamespace().equals(action.scope().targetNamespace())
        || !workloadNamespace.equals(action.scope().targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("StartSession target namespace differs from verified owner")
          .asRuntimeException();
    }
  }

  private void requireAccountProjection(
      Request request,
      StartSessionPostAuthorizationExecutionTuple tuple,
      ReadRedeemedOperationProjectionResponse projection) {
    byte[] canonicalPreTuple =
        tuple.preAuthorizationTuple().canonicalJson().getBytes(StandardCharsets.UTF_8);
    String expectedRedeemer =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    if (projection == null
        || !tuple.controlPlaneRequestId().equals(projection.getControlPlaneRequestId())
        || !MessageDigest.isEqual(
            canonicalPreTuple, projection.getCanonicalPreAuthorizationTupleBytes().toByteArray())
        || !tuple.preAuthorizationTuple().mutationDigest().equals(projection.getMutationDigest())
        || !request.ownerAttemptId().toString().equals(projection.getOwnerAttemptId())
        || request.ownerFence() != projection.getOwnerFence()
        || !expectedRedeemer.equals(projection.getAuthenticatedRedeemerWorkloadIdentity())) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "Account projection differs from the exact original StartSession attempt")
          .asRuntimeException();
    }
  }

  private GameDesignPublicationTerminalEvidence.ReleaseContent releaseContent(
      PublishedReleaseBundleDto dto) {
    if (dto.manifestSchemaVersion() == null || dto.artifactDigests() == null) {
      throw new IllegalStateException("START_SESSION_IMMUTABLE_RELEASE_ARTIFACT_PROOF_MISSING");
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
      if (!participant.succeeded()) {
        throw new IllegalStateException("START_SESSION_PUBLISHED_PARTICIPANT_FAILURE");
      }
      var value =
          net.firedevops.firemud.gamedesign.v1.ParticipantDigest.newBuilder()
              .setParticipantKey(participant.participantKey())
              .setScopeValue(participant.scopeValue())
              .setAppliedCommitId(Objects.requireNonNull(participant.appliedCommitId()))
              .setContentDigest(Objects.requireNonNull(participant.contentDigest()))
              .setDigestSchemaVersion(Objects.requireNonNull(participant.digestSchemaVersion()));
      if (participant.abilitySchemaDigest() != null) {
        value.setAbilitySchemaDigest(participant.abilitySchemaDigest());
      }
      builder.addParticipantDigests(value);
    }
    if (dto.manifestSchemaVersion() != null) {
      builder.setManifestSchemaVersion(dto.manifestSchemaVersion());
    }
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
    // Workflow run/status/family are mutable Temporal metadata, not part of the committed bundle.
    return builder.build();
  }

  private void requirePeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Game Session workload identity required")
          .asRuntimeException();
    }
    if (!("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")
        .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Session workload required")
          .asRuntimeException();
    }
  }

  @FunctionalInterface
  interface AccountProjectionReader {
    ReadRedeemedOperationProjectionResponse read(
        StartSessionPostAuthorizationExecutionTuple tuple, UUID ownerAttemptId, long ownerFence);
  }
}
