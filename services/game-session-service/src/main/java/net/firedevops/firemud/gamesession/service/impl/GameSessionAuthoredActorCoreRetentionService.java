package net.firedevops.firemud.gamesession.service.impl;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadClient;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository.OriginalStartSessionAssociation;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.Candidate;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.StoredSource;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.StaleStartSessionOperatorAttemptClaimException;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.StartSessionOperatorAttemptConflictException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered composition that retains the exact authenticated Game Design authored-core result
 * against its actual Game Session launch and original live owner attempt.
 *
 * <p>The result is observation evidence only. An empty projection list remains an exact retained
 * source result and does not grant Entity allocation, presence, placement, admission, or activation
 * authority.
 */
public final class GameSessionAuthoredActorCoreRetentionService {
  private final String workloadNamespace;
  private final CanonicalGameInstanceLaunchAssociationRepository launches;
  private final GameSessionStartSessionOperatorAttemptRepository attempts;
  private final AuthoredInitialActorCoreReadClient gameDesign;
  private final GameSessionAuthoredActorCoreRetentionRepository retention;
  private final TransactionOperations currentAttemptTransaction;
  private final TransactionOperations retentionTransaction;

  public GameSessionAuthoredActorCoreRetentionService(
      String workloadNamespace,
      CanonicalGameInstanceLaunchAssociationRepository launches,
      GameSessionStartSessionOperatorAttemptRepository attempts,
      AuthoredInitialActorCoreReadClient gameDesign,
      GameSessionAuthoredActorCoreRetentionRepository retention,
      PlatformTransactionManager transactionManager) {
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    this.launches = Objects.requireNonNull(launches, "launches");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.gameDesign = Objects.requireNonNull(gameDesign, "gameDesign");
    this.retention = Objects.requireNonNull(retention, "retention");
    Objects.requireNonNull(transactionManager, "transactionManager");
    currentAttemptTransaction = writableReadCommitted(transactionManager);
    retentionTransaction = writableReadCommitted(transactionManager);
  }

  /**
   * Reads, authenticates, and retains the exact owner-qualified result for one complete historical
   * original selection. Exact retries return the first retained bytes, including an empty result,
   * without extending the lease or repeating the remote source read.
   */
  public StoredSource retain(
      HistoricalOriginalStartSessionOwnerEvidence.Result historical,
      OriginalStartSessionCurrentAttemptEvidence.Request currentAttemptRequest) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(historical, "historical owner evidence");
    Objects.requireNonNull(currentAttemptRequest, "current original-attempt request");
    requireHistoricalAttemptMatch(historical, currentAttemptRequest);

    var historicalRequest = historical.request();
    var associationRequest = historical.descriptorPin().associationRead().request();
    var sourceReadRequest =
        new AuthoredInitialActorCoreReadEvidence.ReadRequest(associationRequest);
    byte[] sourceReadRequestBytes =
        StartSessionTemplateAssociationReadGrpcCodec.toRequest(associationRequest).toByteArray();
    byte[] accountProjection = historical.accountRedemptionProjection();
    String requestId = historical.originalTuple().controlPlaneRequestId();

    var existing = retention.readByOriginalOperation(workloadNamespace, requestId);
    if (existing.isPresent()) {
      StoredSource stored = existing.orElseThrow();
      requireStoredRetryMatches(
          stored, historical, currentAttemptRequest, sourceReadRequestBytes, accountProjection);
      return stored;
    }

    OriginalStartSessionAssociation originalLaunch =
        launches
            .readOriginalStartSessionAssociation(historicalRequest.associationSelector())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Exact actual Game Session launch association is unavailable"));
    requireLaunchMatchesHistorical(originalLaunch, historical, currentAttemptRequest);
    CanonicalGameInstanceLaunchAssociation launch = originalLaunch.association();

    AttemptSnapshot firstAttempt = readCurrentAttempt(currentAttemptRequest);
    requireAttemptMatchesHistorical(firstAttempt, historical, currentAttemptRequest);

    requireNoAmbientTransaction();
    AuthoredInitialActorCoreReadEvidence.ReadResult source = gameDesign.read(sourceReadRequest);
    requireSourceMatches(launch, historical, sourceReadRequest, source);
    Candidate candidate =
        candidate(launch, historical, firstAttempt, sourceReadRequestBytes, source);

    StoredSource committed =
        retentionTransaction.execute(
            status -> {
              AttemptSnapshot finalAttempt =
                  readCurrentAttemptInOwnerTransaction(currentAttemptRequest);
              requireSameLiveAttempt(firstAttempt, finalAttempt);
              return retention.retainInOwnerTransaction(candidate);
            });
    if (committed == null) {
      throw new IllegalStateException("Authored-core retention transaction returned no row");
    }

    StoredSource independentReadback =
        retention
            .readByOriginalOperation(workloadNamespace, requestId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Committed authored-core source retention is unavailable on independent readback"));
    requireSameStoredSource(candidate, committed, independentReadback);
    return independentReadback;
  }

  private AttemptSnapshot readCurrentAttempt(
      OriginalStartSessionCurrentAttemptEvidence.Request request) {
    AttemptSnapshot snapshot =
        currentAttemptTransaction.execute(status -> readCurrentAttemptInOwnerTransaction(request));
    if (snapshot == null) {
      throw new IllegalStateException("Current original-attempt transaction returned no row");
    }
    return snapshot;
  }

  private AttemptSnapshot readCurrentAttemptInOwnerTransaction(
      OriginalStartSessionCurrentAttemptEvidence.Request request) {
    AttemptSnapshot snapshot = attempts.readCurrentOriginalAttempt(request);
    requireCurrentAttemptSnapshot(request, snapshot);
    return snapshot;
  }

  private Candidate candidate(
      CanonicalGameInstanceLaunchAssociation launch,
      HistoricalOriginalStartSessionOwnerEvidence.Result historical,
      AttemptSnapshot attempt,
      byte[] sourceReadRequestBytes,
      AuthoredInitialActorCoreReadEvidence.ReadResult source) {
    var association = source.associationRead().association();
    var descriptor = launch.launchBindingEvidence().descriptor();
    var release = launch.launchBindingEvidence().releaseAttestation();
    return new Candidate(
        workloadNamespace,
        launch.controlPlaneRequestId(),
        launch.canonicalTenantId(),
        launch.gameInstanceUuid(),
        attempt.ownerAttemptId(),
        attempt.ownerMutationId(),
        attempt.ownerFence(),
        attempt.leaseExpiresAt(),
        historical.originalTuple().canonicalBytes(),
        attempt.accountRedemptionProjection(),
        OriginalStartSessionCurrentAttemptEvidence.projectionDigest(
            attempt.accountRedemptionProjection()),
        association.canonicalVersionId(),
        association.selectedCommitId(),
        association.publishWorkflowId(),
        association.associationDigest(),
        association.sourceOperationId(),
        association.sourceEvidenceDigest(),
        association.worldSlug(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest(),
        sourceReadRequestBytes,
        source.canonicalBytes(),
        source.digest());
  }

  private void requireHistoricalAttemptMatch(
      HistoricalOriginalStartSessionOwnerEvidence.Result historical,
      OriginalStartSessionCurrentAttemptEvidence.Request request) {
    var tuple = historical.originalTuple();
    var action = tuple.preAuthorizationTuple().action();
    if (!workloadNamespace.equals(request.targetNamespace())
        || !workloadNamespace.equals(historical.request().associationSelector().targetNamespace())
        || !action.scope().targetNamespace().equals(workloadNamespace)
        || !tuple.controlPlaneRequestId().equals(request.originalTuple().controlPlaneRequestId())
        || !Arrays.equals(tuple.canonicalBytes(), request.canonicalPostAuthorizationTuple())
        || !historical.ownerAttemptId().equals(request.expectedOwnerAttemptId())
        || historical.ownerFence() != request.expectedOwnerFence()
        || !tuple
            .controlPlaneRequestId()
            .equals(historical.request().associationSelector().controlPlaneRequestId())
        || !action
            .scope()
            .tenantId()
            .equals(historical.request().associationSelector().canonicalTenantId())
        || !Arrays.equals(
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            historical.accountRedemptionProjection())) {
      throw new StartSessionOperatorAttemptConflictException(
          "Historical source selection differs from the exact original attempt or tuple");
    }
  }

  private void requireLaunchMatchesHistorical(
      OriginalStartSessionAssociation original,
      HistoricalOriginalStartSessionOwnerEvidence.Result historical,
      OriginalStartSessionCurrentAttemptEvidence.Request request) {
    var launch = original.association();
    var retained = historical.launchAssociation();
    if (!Arrays.equals(
            original.postAuthorizationExecutionTuple().canonicalBytes(),
            historical.originalTuple().canonicalBytes())
        || !Arrays.equals(
            original.postAuthorizationExecutionTuple().canonicalBytes(),
            request.canonicalPostAuthorizationTuple())
        || !original.ownerAttemptId().equals(historical.ownerAttemptId())
        || !original.ownerAttemptId().equals(request.expectedOwnerAttemptId())
        || original.ownerFence() != historical.ownerFence()
        || original.ownerFence() != request.expectedOwnerFence()
        || launch.currentGameInstanceStatus()
            != CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING) {
      throw new StartSessionOperatorAttemptConflictException(
          "Actual original launch differs from the historical tuple or current owner selector");
    }
    if (!workloadNamespace.equals(launch.targetNamespace())
        || !launch.targetNamespace().equals(retained.targetNamespace())
        || !launch.canonicalTenantId().equals(retained.canonicalTenantId())
        || !launch.worldSlug().equals(retained.worldSlug())
        || !launch.gameInstanceUuid().equals(retained.gameInstanceUuid())
        || !launch.controlPlaneRequestId().equals(retained.controlPlaneRequestId())
        || !launch
            .launchBindingEvidence()
            .descriptor()
            .launchDescriptorId()
            .equals(retained.launchDescriptorId())
        || !launch.playableStateNamespaceId().equals(retained.playableStateNamespaceId())
        || launch.playableStateScope() != retained.playableStateScope()
        || launch.publicProduction() != retained.publicProduction()
        || launch.capturedStartingRowVersion() != retained.capturedStartingRowVersion()
        || !launch.launchBindingEvidence().equals(retained.launchBindingEvidence())) {
      throw new StartSessionOperatorAttemptConflictException(
          "Actual immutable launch differs from the historical selected descriptor or release");
    }
  }

  private void requireAttemptMatchesHistorical(
      AttemptSnapshot attempt,
      HistoricalOriginalStartSessionOwnerEvidence.Result historical,
      OriginalStartSessionCurrentAttemptEvidence.Request request) {
    requireCurrentAttemptSnapshot(request, attempt);
    if (!historical.ownerAttemptId().equals(attempt.ownerAttemptId())
        || historical.ownerFence() != attempt.ownerFence()
        || !Arrays.equals(
            historical.accountRedemptionProjection(), attempt.accountRedemptionProjection())) {
      throw new StartSessionOperatorAttemptConflictException(
          "Current owner attempt differs from the historical Account and selection evidence");
    }
  }

  private void requireSourceMatches(
      CanonicalGameInstanceLaunchAssociation launch,
      HistoricalOriginalStartSessionOwnerEvidence.Result historical,
      AuthoredInitialActorCoreReadEvidence.ReadRequest expected,
      AuthoredInitialActorCoreReadEvidence.ReadResult source) {
    Objects.requireNonNull(source, "authenticated Game Design source result");
    var sourceRequest = expected.associationRequest();
    var selection = source.associationRead().association();
    var expectedSelection = historical.firstSelection().association();
    var descriptor = launch.launchBindingEvidence().descriptor();
    if (!sourceRequest.equals(source.request())
        || !sourceRequest.equals(source.associationRead().request())
        || !selection.equals(expectedSelection)
        || !source.descriptor().equals(descriptor)
        || !source
            .associationRead()
            .releaseBundle()
            .equals(historical.descriptorPin().associationRead().releaseBundle())) {
      throw new StartSessionOperatorAttemptConflictException(
          "Authenticated Game Design result differs from the exact original association or launch binding");
    }
    requireSelectedReleaseMatches(
        selection,
        source.associationRead().releaseBundle(),
        source.associationRead().worldPublishedStartLocationEvidence(),
        descriptor,
        launch.launchBindingEvidence().releaseAttestation());
  }

  private static void requireSelectedReleaseMatches(
      net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
              .Association
          association,
      PublishedReleaseBundle bundle,
      net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence worldStart,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldReleaseAttestationEvidence attestation) {
    descriptor.requireValid();
    attestation.requireValid(descriptor);
    if (!association.canonicalTenantId().equals(attestation.canonicalTenantId())
        || !association.canonicalVersionId().equals(attestation.canonicalVersionId())
        || !association.selectedCommitId().toString().equals(attestation.commitId())
        || !association.publishWorkflowId().equals(attestation.publishWorkflowId())
        || !association.sourceOperationId().equals(attestation.authoredWorldSourceOperationId())
        || !association
            .sourceEvidenceDigest()
            .equals(attestation.authoredWorldSourceEvidenceDigest())
        || !association.worldSlug().equals(attestation.worldSlug())
        || !Objects.equals(worldStart, attestation.worldStartLocationEvidence())
        || bundle.getId() != descriptor.releaseBundleId()
        || bundle.getVersionId() != descriptor.versionId()
        || bundle.getIsScriptOnly()
        || !bundle.getPublishedReleaseBundleRef().equals(descriptor.publishedReleaseBundleRef())
        || !bundle.getCanonicalTenantId().equals(attestation.canonicalTenantId().toString())
        || !bundle.getCanonicalVersionId().equals(attestation.canonicalVersionId().toString())
        || !bundle.getPublishWorkflowId().equals(attestation.publishWorkflowId())
        || !bundle.getAttestationSchemaVersion().equals("v" + attestation.schemaVersion())
        || !bundle.getManifestHash().equals(attestation.manifestHash())
        || !bundle.hasManifestSchemaVersion()
        || bundle.getManifestSchemaVersion() != attestation.manifestSchemaVersion()
        || !bundle
            .getRequiredManifestAssetKeysList()
            .equals(attestation.requiredManifestAssetKeys())
        || !bundle.getGenerationConfigRevision().equals(attestation.generationConfigRevision())
        || !bundle.getCommandDefinitionsList().equals(attestation.commandDefinitions())
        || bundle.getParticipantDigestsCount() != attestation.participantDigests().size()
        || bundle.getArtifactDigestsCount() != attestation.artifactDigests().size()) {
      throw new StartSessionOperatorAttemptConflictException(
          "Selected Game Design release differs from the complete retained launch attestation");
    }
    for (int index = 0; index < bundle.getParticipantDigestsCount(); index++) {
      ParticipantDigest participant = bundle.getParticipantDigests(index);
      AuthoredWorldReleaseAttestationEvidence.Participant expected =
          attestation.participantDigests().get(index);
      if (!participant.getParticipantKey().equals(expected.participantKey())
          || !participant.getScopeValue().equals(expected.scopeValue())
          || !participant.getAppliedCommitId().equals(expected.appliedCommitId())
          || !participant.getContentDigest().equals(expected.contentDigest())
          || participant.getDigestSchemaVersion() != expected.digestSchemaVersion()
          || participant.hasAbilitySchemaDigest() != expected.abilitySchemaDigestPresent()
          || (participant.hasAbilitySchemaDigest()
              && !participant.getAbilitySchemaDigest().equals(expected.abilitySchemaDigest()))) {
        throw new StartSessionOperatorAttemptConflictException(
            "Selected release participant evidence differs from the retained launch attestation");
      }
    }
    for (int index = 0; index < bundle.getArtifactDigestsCount(); index++) {
      PublishedArtifactDigest artifact = bundle.getArtifactDigests(index);
      AuthoredWorldReleaseAttestationEvidence.Artifact expected =
          attestation.artifactDigests().get(index);
      if (!artifact.getUsageKey().equals(expected.usageKey())
          || !artifact.getArtifactKind().equals(expected.artifactKind())
          || !artifact.getImmutableObjectKey().equals(expected.immutableObjectKey())
          || !artifact.getContentDigest().equals(expected.contentDigest())
          || !artifact.getContentType().equals(expected.contentType())
          || artifact.getArtifactSchemaVersion() != expected.artifactSchemaVersion()) {
        throw new StartSessionOperatorAttemptConflictException(
            "Selected release artifacts differ from the retained launch attestation");
      }
    }
  }

  private static void requireCurrentAttemptSnapshot(
      OriginalStartSessionCurrentAttemptEvidence.Request request, AttemptSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "current original-attempt snapshot");
    var tuple = request.originalTuple();
    if (!request.targetNamespace().equals(snapshot.targetNamespace())
        || !tuple.controlPlaneRequestId().equals(snapshot.controlPlaneRequestId())
        || !request.expectedOwnerAttemptId().equals(snapshot.ownerAttemptId())
        || !request.expectedOwnerMutationId().equals(snapshot.ownerMutationId())
        || request.expectedOwnerFence() != snapshot.ownerFence()
        || !OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(snapshot.phaseState())
        || !Arrays.equals(
            request.canonicalPostAuthorizationTuple(), snapshot.postAuthorizationExecutionTuple())
        || !tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .tenantId()
            .equals(snapshot.canonicalTenantId())
        || snapshot.leaseExpiresAt() == null
        || snapshot.accountRedemptionProjection() == null
        || !Arrays.equals(
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            snapshot.accountRedemptionProjection())) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Exact live pending original StartSession attempt and Account projection are required");
    }
  }

  private static void requireSameLiveAttempt(AttemptSnapshot before, AttemptSnapshot after) {
    if (!before.targetNamespace().equals(after.targetNamespace())
        || !before.controlPlaneRequestId().equals(after.controlPlaneRequestId())
        || !before.canonicalTenantId().equals(after.canonicalTenantId())
        || !before.ownerAttemptId().equals(after.ownerAttemptId())
        || !before.ownerMutationId().equals(after.ownerMutationId())
        || before.ownerFence() != after.ownerFence()
        || !before.phaseState().equals(after.phaseState())
        || !before.leaseExpiresAt().equals(after.leaseExpiresAt())
        || !Arrays.equals(
            before.postAuthorizationExecutionTuple(), after.postAuthorizationExecutionTuple())
        || !Arrays.equals(
            before.accountRedemptionProjection(), after.accountRedemptionProjection())) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Original StartSession attempt changed during authenticated Game Design read");
    }
  }

  private static void requireStoredRetryMatches(
      StoredSource stored,
      HistoricalOriginalStartSessionOwnerEvidence.Result historical,
      OriginalStartSessionCurrentAttemptEvidence.Request request,
      byte[] sourceReadRequestBytes,
      byte[] accountProjection) {
    Candidate candidate = stored.candidate();
    var association = historical.firstSelection().association();
    var descriptor = historical.launchAssociation().launchBindingEvidence().descriptor();
    var release = historical.launchAssociation().launchBindingEvidence().releaseAttestation();
    if (!candidate.targetNamespace().equals(request.targetNamespace())
        || !candidate
            .controlPlaneRequestId()
            .equals(request.originalTuple().controlPlaneRequestId())
        || !candidate
            .canonicalTenantId()
            .equals(request.originalTuple().preAuthorizationTuple().action().scope().tenantId())
        || !candidate.ownerAttemptId().equals(request.expectedOwnerAttemptId())
        || !candidate.ownerMutationId().equals(request.expectedOwnerMutationId())
        || candidate.ownerFence() != request.expectedOwnerFence()
        || !Arrays.equals(
            candidate.originalPostAuthorizationTuple(), request.canonicalPostAuthorizationTuple())
        || !Arrays.equals(candidate.accountRedemptionProjection(), accountProjection)
        || !Arrays.equals(candidate.sourceReadRequestBytes(), sourceReadRequestBytes)
        || !candidate.canonicalVersionId().equals(association.canonicalVersionId())
        || !candidate.selectedCommitId().equals(association.selectedCommitId())
        || !candidate.publishWorkflowId().equals(association.publishWorkflowId())
        || !candidate.associationDigest().equals(association.associationDigest())
        || !candidate.worldSourceOperationId().equals(association.sourceOperationId())
        || !candidate.worldSourceEvidenceDigest().equals(association.sourceEvidenceDigest())
        || !candidate.worldSlug().equals(association.worldSlug())
        || !candidate.launchDescriptorId().equals(descriptor.launchDescriptorId())
        || !candidate.descriptorRequestDigest().equals(descriptor.requestDigest())
        || !candidate.descriptorResultDigest().equals(descriptor.resultDigest())
        || !candidate.releaseAttestationEvidenceDigest().equals(release.evidenceDigest())) {
      throw new StartSessionOperatorAttemptConflictException(
          "Original StartSession authored-core retry differs from the first retained request");
    }
  }

  private static void requireSameStoredSource(
      Candidate candidate, StoredSource committed, StoredSource readback) {
    if (!committed.retainedAt().equals(readback.retainedAt())
        || !committed
            .candidate()
            .sourceResultDigest()
            .equals(readback.candidate().sourceResultDigest())
        || !committed.candidate().sourceResultDigest().equals(candidate.sourceResultDigest())
        || !MessageDigest.isEqual(
            committed.candidate().canonicalSourceResultBytes(),
            readback.candidate().canonicalSourceResultBytes())
        || !MessageDigest.isEqual(
            candidate.canonicalSourceResultBytes(),
            readback.candidate().canonicalSourceResultBytes())) {
      throw new IllegalStateException(
          "Committed authored-core source failed exact independent readback");
    }
  }

  private static TransactionOperations writableReadCommitted(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
    return transaction;
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Authenticated Game Design authored-core read must remain outside Game Session SQL");
    }
  }
}
