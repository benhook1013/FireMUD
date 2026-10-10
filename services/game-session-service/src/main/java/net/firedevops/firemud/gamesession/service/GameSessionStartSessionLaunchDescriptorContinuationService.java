package net.firedevops.firemud.gamesession.service;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.common.account.StartSessionRedeemedOperationProjectionClient;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorReadClient;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository.PinnedLaunchDescriptorSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered continuation of one existing StartSession association and descriptor projection.
 * Account revalidates the original redeemed operation, and Game Design independently authorizes the
 * descriptor read; this service creates no mutation or runtime admission authority.
 */
public final class GameSessionStartSessionLaunchDescriptorContinuationService {
  private final GameSessionStartSessionOperatorAttemptRepository attemptRepository;
  private final GameSessionStartSessionTemplateAssociationRepository associationRepository;
  private final GameSessionStartSessionLaunchDescriptorRepository descriptorRepository;
  private final StartSessionRedeemedOperationProjectionClient accountProjectionClient;
  private final StartSessionLaunchDescriptorReadClient descriptorReadClient;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  public GameSessionStartSessionLaunchDescriptorContinuationService(
      GameSessionStartSessionOperatorAttemptRepository attemptRepository,
      GameSessionStartSessionTemplateAssociationRepository associationRepository,
      GameSessionStartSessionLaunchDescriptorRepository descriptorRepository,
      StartSessionRedeemedOperationProjectionClient accountProjectionClient,
      StartSessionLaunchDescriptorReadClient descriptorReadClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.attemptRepository =
        Objects.requireNonNull(attemptRepository, "attemptRepository is required");
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository is required");
    this.descriptorRepository =
        Objects.requireNonNull(descriptorRepository, "descriptorRepository is required");
    this.accountProjectionClient =
        Objects.requireNonNull(accountProjectionClient, "accountProjectionClient is required");
    this.descriptorReadClient =
        Objects.requireNonNull(descriptorReadClient, "descriptorReadClient is required");
    Objects.requireNonNull(transactionManager, "transactionManager is required");
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
   * Revalidates the same live original attempt and Account projection, reads its existing
   * selection, calls Account and Game Design outside SQL, and then pins only the exact returned
   * descriptor outcome.
   */
  public PinnedLaunchDescriptorSnapshot continueOriginalEvidence(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    Objects.requireNonNull(tuple, "complete post-authorization tuple is required");
    requireNoAmbientTransaction();
    if (!workloadNamespace.equals(
        tuple.preAuthorizationTuple().action().scope().targetNamespace())) {
      throw new EvidenceContinuationUnavailableException(
          "StartSession continuation tuple is outside the configured Game Session namespace");
    }

    ContinuationPlan plan =
        ownerTransaction.execute(
            status -> {
              EvidenceContinuation continuation =
                  attemptRepository.beginEvidenceContinuation(tuple);
              PinnedAssociationSnapshot association =
                  associationRepository
                      .findPinned(continuation)
                      .orElseThrow(
                          () ->
                              new EvidenceContinuationUnavailableException(
                                  "Original selection is missing; this attempt remains reconciliation-only"));
              return new ContinuationPlan(
                  continuation, association, exactReplayRequest(association.result()));
            });
    if (plan == null) {
      throw new IllegalStateException("StartSession continuation transaction returned no plan");
    }

    Request originalRequest = plan.association().result().request();
    requireOriginalRequestBinding(tuple, originalRequest);

    requireNoAmbientTransaction();
    ReadRedeemedOperationProjectionResponse originalProjection =
        accountProjectionClient.read(
            tuple, originalRequest.ownerAttemptId(), originalRequest.ownerFence());
    requireNoAmbientTransaction();

    var resolved = descriptorReadClient.resolve(plan.request());
    requireNoAmbientTransaction();

    ReadRedeemedOperationProjectionResponse currentProjection =
        accountProjectionClient.read(
            tuple, originalRequest.ownerAttemptId(), originalRequest.ownerFence());
    requireNoAmbientTransaction();
    requireSameAccountProjection(originalProjection, currentProjection);

    PinnedLaunchDescriptorSnapshot pinned =
        ownerTransaction.execute(
            status -> {
              PinnedAssociationSnapshot current =
                  associationRepository
                      .findPinned(plan.continuation())
                      .orElseThrow(
                          () ->
                              new EvidenceContinuationUnavailableException(
                                  "Original selection disappeared during descriptor resolution"));
              requireSameSelection(plan.association(), current);
              return descriptorRepository.pin(plan.continuation(), resolved);
            });
    if (pinned == null) {
      throw new IllegalStateException("StartSession descriptor pin transaction returned no result");
    }
    return pinned;
  }

  private static Request exactReplayRequest(Result original) {
    var association = original.association();
    var request = original.request();
    return new Request(
        request.schemaVersion(),
        request.targetNamespace(),
        request.readRequestId(),
        request.canonicalPostAuthorizationTuple(),
        request.ownerAttemptId(),
        request.ownerFence(),
        new ExactReplay(
            association.canonicalVersionId(),
            association.selectedCommitId(),
            association.publishWorkflowId(),
            association.associationDigest()));
  }

  private static void requireSameSelection(
      PinnedAssociationSnapshot originallyRead, PinnedAssociationSnapshot current) {
    if (!originallyRead.requestDigest().equals(current.requestDigest())
        || !originallyRead.responseDigest().equals(current.responseDigest())
        || !Arrays.equals(
            StartSessionTemplateAssociationReadGrpcCodec.toResponse(originallyRead.result())
                .toByteArray(),
            StartSessionTemplateAssociationReadGrpcCodec.toResponse(current.result())
                .toByteArray())) {
      throw new GameSessionStartSessionLaunchDescriptorRepository
          .StartSessionLaunchDescriptorConflictException(
          "Original immutable selection changed during descriptor continuation");
    }
  }

  private void requireOriginalRequestBinding(
      StartSessionPostAuthorizationExecutionTuple tuple, Request request) {
    if (!workloadNamespace.equals(request.targetNamespace())
        || !Arrays.equals(tuple.canonicalBytes(), request.canonicalPostAuthorizationTuple())
        || request.ownerAttemptId() == null
        || request.ownerAttemptId().equals(new java.util.UUID(0L, 0L))
        || request.ownerFence() <= 0L) {
      throw new EvidenceContinuationUnavailableException(
          "Original pinned selection does not bind the exact tuple and owner attempt");
    }
  }

  private static void requireSameAccountProjection(
      ReadRedeemedOperationProjectionResponse original,
      ReadRedeemedOperationProjectionResponse current) {
    if (!Arrays.equals(original.toByteArray(), current.toByteArray())) {
      throw new EvidenceContinuationUnavailableException(
          "Account's original redeemed operation projection changed during descriptor continuation");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Remote Account and Game Design reads must occur outside owner SQL transactions");
    }
  }

  private record ContinuationPlan(
      EvidenceContinuation continuation, PinnedAssociationSnapshot association, Request request) {}

  public static final class EvidenceContinuationUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public EvidenceContinuationUnavailableException(String message) {
      super(message);
    }
  }
}
