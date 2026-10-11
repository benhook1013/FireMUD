package net.firedevops.firemud.gamesession.service.impl;

import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.CanonicalInstanceLaunch;
import net.firedevops.firemud.gamesession.repository.GameSessionOriginalStartSessionSelectionPinRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionOriginalStartSessionSelectionPinRepository.Candidate;
import net.firedevops.firemud.gamesession.repository.GameSessionOriginalStartSessionSelectionPinRepository.StoredPin;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-local immutable first-selection retention and historical reconstruction. */
public final class GameSessionOriginalStartSessionSelectionPinService {
  private final GameSessionOriginalStartSessionSelectionPinRepository pins;
  private final GameSessionStartSessionOperatorAttemptRepository attempts;
  private final GameSessionCanonicalInstanceLaunchRepository launches;
  private final String workloadNamespace;
  private final TransactionTemplate writeTransaction;
  private final TransactionTemplate readTransaction;

  public GameSessionOriginalStartSessionSelectionPinService(
      GameSessionOriginalStartSessionSelectionPinRepository pins,
      GameSessionStartSessionOperatorAttemptRepository attempts,
      GameSessionCanonicalInstanceLaunchRepository launches,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.pins = Objects.requireNonNull(pins, "pins");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.launches = Objects.requireNonNull(launches, "launches");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    writeTransaction = new TransactionTemplate(transactionManager);
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    readTransaction = new TransactionTemplate(transactionManager);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readTransaction.setReadOnly(true);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /**
   * Stores exact first-read bytes after all remote reads have completed. The owner repository then
   * locks and revalidates the same pending Account-attached attempt and its original live lease.
   */
  public StoredPin retainFirst(Candidate candidate) {
    Objects.requireNonNull(candidate, "candidate");
    requireConfiguredOwner(candidate.targetNamespace());
    HistoricalOriginalStartSessionOwnerEvidence.Result evidence = candidate.evidence();
    var selector = evidence.request().associationSelector();
    var launch =
        launches
            .readByOriginalTuple(selector, evidence.originalTuple().canonicalBytes())
            .orElseThrow(
                () ->
                    new IllegalStateException("Exact original Game Session launch is unavailable"));
    requireLaunchMatches(evidence.launchAssociation(), launch);
    StoredPin retained =
        writeTransaction.execute(status -> pins.retainFirstInOwnerTransaction(candidate));
    return Objects.requireNonNull(retained, "Game Session selection-pin write returned no row");
  }

  /** Reconstructs only an already-retained pin and its exact V35/V26 owner correlations. */
  public Optional<HistoricalOriginalStartSessionOwnerEvidence.Result> readHistorical(
      HistoricalOriginalStartSessionOwnerEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    requireConfiguredOwner(request.associationSelector().targetNamespace());
    return Objects.requireNonNull(
        readTransaction.execute(status -> readHistoricalInSnapshot(request)),
        "Game Session historical selection read returned no optional");
  }

  private Optional<HistoricalOriginalStartSessionOwnerEvidence.Result> readHistoricalInSnapshot(
      HistoricalOriginalStartSessionOwnerEvidence.Request request) {
    var selector = request.associationSelector();
    Optional<StoredPin> stored =
        pins.readByOriginalOperationInOwnerTransaction(
            selector.targetNamespace(), selector.controlPlaneRequestId());
    if (stored.isEmpty()) return Optional.empty();
    StoredPin pin = stored.orElseThrow();
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(pin.originalTupleBytes());
    var attempt =
        attempts
            .readHistoricalOwnerAttemptInOwnerTransaction(
                tuple, request.expectedOwnerAttemptId(), request.expectedOwnerFence())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Exact original Game Session owner attempt is unavailable"));
    if (!attempt.ownerMutationId().equals(pin.ownerMutationId())
        || !attempt.ownerAttemptId().equals(pin.ownerAttemptId())
        || attempt.ownerFence() != pin.ownerFence()
        || !Arrays.equals(attempt.postAuthorizationExecutionTuple(), pin.originalTupleBytes())
        || !Arrays.equals(
            attempt.accountRedemptionProjection(), pin.accountRedemptionProjection())) {
      throw new IllegalStateException(
          "Historical pin differs from the exact retained owner attempt");
    }
    var launchAssociation =
        launches
            .readHistoricalAssociationInOwnerTransaction(selector, pin.originalTupleBytes())
            .orElseThrow(
                () ->
                    new IllegalStateException("Exact original Game Session launch is unavailable"));
    if (!pin.targetNamespace().equals(selector.targetNamespace())
        || !pin.controlPlaneRequestId().equals(selector.controlPlaneRequestId())
        || !pin.canonicalTenantId().equals(selector.canonicalTenantId())
        || !pin.gameInstanceUuid().equals(selector.gameInstanceUuid())
        || !pin.launchDescriptorId().equals(selector.launchDescriptorId())
        || !pin.ownerAttemptId().equals(request.expectedOwnerAttemptId())
        || pin.ownerFence() != request.expectedOwnerFence()) {
      throw new IllegalStateException(
          "Historical pin differs from the complete requested selector");
    }

    var initialRequest = parseAssociationRequest(pin.initialSelectionRequestBytes());
    var initialEvidence =
        StartSessionTemplateAssociationReadGrpcCodec.fromResponse(
            StartSessionTemplateAssociationReadGrpcCodec.fromRequest(initialRequest),
            parseAssociationResponse(pin.initialSelectionResponseBytes()));
    var descriptorRequest =
        StartSessionLaunchDescriptorGrpcCodec.fromRequest(
            parseAssociationRequest(pin.descriptorPinRequestBytes()));
    var descriptorPin =
        StartSessionLaunchDescriptorGrpcCodec.fromResponse(
            descriptorRequest, parseDescriptorResponse(pin.descriptorPinResponseBytes()));
    return Optional.of(
        new HistoricalOriginalStartSessionOwnerEvidence.Result(
            request,
            tuple,
            attempt.ownerAttemptId(),
            attempt.ownerFence(),
            attempt.accountRedemptionProjection(),
            initialEvidence,
            descriptorPin,
            launchAssociation,
            pin.initialSelectionRequestDigest(),
            pin.initialSelectionResponseDigest(),
            pin.descriptorPinRequestDigest(),
            pin.descriptorPinResponseDigest()));
  }

  private void requireConfiguredOwner(String targetNamespace) {
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Historical selector differs from configured Game Session owner");
    }
  }

  private static void requireLaunchMatches(
      HistoricalOriginalStartSessionOwnerEvidence.LaunchAssociation expected,
      CanonicalInstanceLaunch actual) {
    if (!expected.targetNamespace().equals(actual.targetNamespace())
        || !expected.canonicalTenantId().equals(actual.canonicalTenantId())
        || !expected.worldSlug().equals(actual.canonicalRealmCatalog().worldSlug())
        || !expected.gameInstanceUuid().equals(actual.gameInstanceUuid())
        || !expected.controlPlaneRequestId().equals(actual.controlPlaneRequestId())
        || !expected
            .launchDescriptorId()
            .equals(actual.launchBindingEvidence().descriptor().launchDescriptorId())
        || !expected.playableStateNamespaceId().equals(actual.playableStateNamespaceId())
        || expected.playableStateScope() != actual.playableStateScope()
        || expected.publicProduction() != actual.publicProduction()
        || expected.capturedStartingRowVersion() != actual.capturedStartingRowVersion()
        || !expected.launchBindingEvidence().equals(actual.launchBindingEvidence())) {
      throw new IllegalStateException(
          "First selection pin differs from its exact retained Game Session launch");
    }
  }

  private static net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest
      parseAssociationRequest(byte[] bytes) {
    try {
      return net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest
          .parseFrom(bytes);
    } catch (InvalidProtocolBufferException malformed) {
      throw new IllegalStateException(
          "Retained StartSession selection request is malformed", malformed);
    }
  }

  private static net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse
      parseAssociationResponse(byte[] bytes) {
    try {
      return net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse
          .parseFrom(bytes);
    } catch (InvalidProtocolBufferException malformed) {
      throw new IllegalStateException(
          "Retained StartSession selection response is malformed", malformed);
    }
  }

  private static net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse
      parseDescriptorResponse(byte[] bytes) {
    try {
      return net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse
          .parseFrom(bytes);
    } catch (InvalidProtocolBufferException malformed) {
      throw new IllegalStateException(
          "Retained StartSession descriptor response is malformed", malformed);
    }
  }
}
