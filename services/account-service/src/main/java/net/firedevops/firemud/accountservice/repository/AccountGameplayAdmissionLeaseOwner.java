package net.firedevops.firemud.accountservice.repository;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.math.BigInteger;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AcquireGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionAbortResolution;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionOriginalAckReceipt;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionLeaseRepository.Allocation;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionOriginalCommitExecutor.OriginalCommitAcknowledgement;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier.VerifiedCaller;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.AcquireRequest;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalAckReceiptEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Evidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account owner for the exact public-production NEW admission operation.
 *
 * <p>This class is manually composed. It owns no Spring registration and accepts no caller-supplied
 * authority adapter, candidate source, SQL callback, or authentication flag. It composes Account's
 * concrete caller verifier and source graph, the exact same-namespace Game Session owner reads, the
 * existing lease repository, and the original physical-COMMIT and ACK-receipt executors.
 *
 * <p>Responses report only the retained Account operation and, for COMMITTED, a receipt read from
 * the separate durable receipt owner. They do not admit a Game Session binding.
 */
public final class AccountGameplayAdmissionLeaseOwner {
  private static final String ACCOUNT_ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String GAME_SESSION_SERVICE = "game-session-service";

  private final DataSource dataSource;
  private final AccountGameplayAdmissionCallerVerifier callerVerifier;
  private final CanonicalGameplayAdmissionDecisionReadClient decisionReadClient;
  private final String workloadNamespace;
  private final AccountGameplayAdmissionOriginalCommitExecutor originalCommitExecutor;
  private final AccountGameplayAdmissionOriginalAckReceiptCommitExecutor receiptExecutor;

  public AccountGameplayAdmissionLeaseOwner(
      AccountGameplayAdmissionCallerVerifier callerVerifier,
      CanonicalGameplayAdmissionDecisionReadClient decisionReadClient,
      String workloadNamespace,
      DataSource dataSource) {
    this.callerVerifier =
        Objects.requireNonNull(callerVerifier, "Account caller verifier required");
    this.decisionReadClient =
        Objects.requireNonNull(decisionReadClient, "Canonical Game Session owner client required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    this.dataSource = Objects.requireNonNull(dataSource, "Account DataSource required");
    originalCommitExecutor = new AccountGameplayAdmissionOriginalCommitExecutor(dataSource);
    receiptExecutor = new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(dataSource);
  }

  /** Verifies current caller and published target before allocating or returning an operation. */
  public AccountGameplayAdmissionLeaseResponse acquire(
      AcquireGameplayAdmissionLeaseRequest wireRequest) {
    GrpcPeerIdentity peer = requireGameSessionCaller();
    requireNoAmbientSql();
    final AcquireRequest request;
    try {
      request = AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(wireRequest);
    } catch (RuntimeException malformed) {
      throw invalidArgument();
    }

    VerifiedCaller caller = verifyCaller(request.presentedInitialGsACompactJwt(), peer);
    Candidate expectedCandidate = request.expectedCandidate();
    Target expectedTarget = request.expectedTarget();
    requireCandidateMatchesCaller(expectedCandidate, caller, peer);
    requireCandidateMatchesTarget(expectedCandidate, expectedTarget);
    requireTargetNamespace(expectedTarget);

    // The independent Game Session read is deliberately complete before opening Account SQL.
    PublishedRealmEvidence published =
        readPublishedTarget(request.requestId(), expectedCandidate, expectedTarget);
    if (!published.currentTarget().equals(expectedTarget)) throw unavailable();

    AccountGameplayAdmissionLeaseOperation operation =
        withCurrentSources(
            caller,
            expectedTarget.canonicalTenantId(),
            current -> {
              current.requireCandidate(expectedCandidate, caller, peer);
              AccountGameplayAdmissionLeaseRepository repository = current.repository();
              UUID accountId = caller.identity().accountId();
              Allocation priorAllocation =
                  repository.findAllocation(accountId, request.requestId()).orElse(null);
              if (priorAllocation != null) {
                AccountGameplayAdmissionLeaseOperation retained =
                    repository
                        .findByRequest(accountId, request.requestId(), peer.uri())
                        .orElseThrow(AccountGameplayAdmissionLeaseOwner::unavailable);
                requireAllocationMatchesOperation(priorAllocation, retained);
                current.currentSources().requireMatchesOriginalLease(retained.evidence());
                requireLeaseMatchesCandidateTarget(
                    retained.evidence(),
                    request.requestId(),
                    expectedCandidate,
                    expectedTarget,
                    request.bindingGeneration());
                return retained;
              }

              Allocation allocation =
                  repository.allocate(accountId, request.requestId(), UUID.randomUUID());
              AccountGameplayAdmissionLeaseEvidence evidence =
                  buildOriginalEvidence(
                      current.dsl(),
                      allocation,
                      peer.uri(),
                      expectedCandidate,
                      expectedTarget,
                      request.bindingGeneration(),
                      current.currentSources().leaseSourceFields(),
                      caller.identity().expiresAtEpochSecond());
              return repository.beginPending(evidence);
            });

    OriginalAckReceiptEvidence receipt = requireReceiptIfCommitted(operation);
    return AccountGameplayAdmissionLeaseGrpcCodec.toAcquireResponse(
        request,
        operation.evidence(),
        wireState(operation.state()),
        operation.bindingDecisionId(),
        operation.orphanCleanupId(),
        receipt);
  }

  /** Revalidates the exact original PROVISIONAL decision and current published target. */
  public AccountGameplayAdmissionLeaseResponse finalizeLease(
      FinalizeGameplayAdmissionLeaseRequest wireRequest) {
    GrpcPeerIdentity peer = requireGameSessionCaller();
    requireNoAmbientSql();
    final AccountGameplayAdmissionLeaseGrpcCodec.OriginalLeaseRequest request;
    try {
      request = AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeRequest(wireRequest);
    } catch (RuntimeException malformed) {
      throw invalidArgument();
    }

    AccountGameplayAdmissionLeaseEvidence lease = request.originalLeaseEvidence();
    VerifiedCaller caller = verifyCaller(request.presentedInitialGsACompactJwt(), peer);
    requireCallerMatchesLease(caller, peer, lease);

    // Exact retries of a completed original operation can replay only its actual durable receipt.
    // Game Session is allowed to have advanced the decision beyond PROVISIONAL after the original
    // physical COMMIT, so do this narrowly fenced read before asking for a fresh provisional view.
    AccountGameplayAdmissionLeaseOperation retainedBeforeRemoteReads =
        readExactForFinalization(caller, lease);
    if (retainedBeforeRemoteReads.state()
        == AccountGameplayAdmissionLeaseOperation.State.COMMITTED) {
      OriginalAckReceiptEvidence receipt =
          requireReceipt(retainedBeforeRemoteReads, request.bindingDecisionId());
      return AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeResponse(
          request,
          retainedBeforeRemoteReads.evidence(),
          wireState(retainedBeforeRemoteReads.state()),
          retainedBeforeRemoteReads.bindingDecisionId(),
          retainedBeforeRemoteReads.orphanCleanupId(),
          receipt);
    }
    if (retainedBeforeRemoteReads.state() == AccountGameplayAdmissionLeaseOperation.State.ABORTED) {
      return AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeResponse(
          request,
          retainedBeforeRemoteReads.evidence(),
          wireState(retainedBeforeRemoteReads.state()),
          retainedBeforeRemoteReads.bindingDecisionId(),
          retainedBeforeRemoteReads.orphanCleanupId(),
          null);
    }

    // Both remote authorities are read after the short Account replay lookup and before the
    // original physical-COMMIT path. The decision reader returns only the actual retained
    // lease-bound PROVISIONAL decision, never request echoes.
    Evidence decision;
    PublishedRealmEvidence published;
    try {
      decision = decisionReadClient.read(request.bindingDecisionId(), lease);
      requireDecisionMatchesLease(decision, request.bindingDecisionId(), lease, caller, peer);
      published =
          readPublishedTarget(requestId(lease), decision.candidate(), decision.targetEvidence());
      if (!published.currentTarget().equals(decision.targetEvidence())) throw unavailable();
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }

    AccountGameplayAdmissionLeaseOperation beforeCommit = readExactForFinalization(caller, lease);

    if (beforeCommit.state() == AccountGameplayAdmissionLeaseOperation.State.COMMITTED) {
      OriginalAckReceiptEvidence receipt =
          requireReceipt(beforeCommit, request.bindingDecisionId());
      return AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeResponse(
          request,
          beforeCommit.evidence(),
          wireState(beforeCommit.state()),
          beforeCommit.bindingDecisionId(),
          beforeCommit.orphanCleanupId(),
          receipt);
    }
    if (beforeCommit.state() == AccountGameplayAdmissionLeaseOperation.State.ABORTED) {
      return AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeResponse(
          request,
          beforeCommit.evidence(),
          wireState(beforeCommit.state()),
          beforeCommit.bindingDecisionId(),
          beforeCommit.orphanCleanupId(),
          null);
    }

    // This is the one original physical Account COMMIT. Retained COMMITTED state is never passed
    // through this executor to reconstruct a late acknowledgement.
    OriginalCommitAcknowledgement acknowledgement;
    try {
      acknowledgement =
          originalCommitExecutor.executeWithCurrentSources(
              lease, request.bindingDecisionId(), workloadNamespace, caller.currentCandidate());
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
    AccountGameplayAdmissionOriginalAckReceipt receipt;
    try {
      receipt = receiptExecutor.confirm(acknowledgement);
    } catch (RuntimeException unavailable) {
      // The original COMMITTED row, if present, is intentionally left for exact recovery. This
      // call never rebuilds its timing acknowledgement or fabricates an ACK receipt.
      throw unavailable();
    }
    requireReceiptOperation(receipt, lease, request.bindingDecisionId());
    return AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeResponse(
        request,
        receipt.operation().evidence(),
        wireState(receipt.operation().state()),
        receipt.operation().bindingDecisionId(),
        receipt.operation().orphanCleanupId(),
        receiptEvidence(receipt));
  }

  /** Reads only the exact retained operation after fresh caller and Account-source checks. */
  public AccountGameplayAdmissionLeaseResponse read(ReadGameplayAdmissionLeaseRequest wireRequest) {
    GrpcPeerIdentity peer = requireGameSessionCaller();
    requireNoAmbientSql();
    final AccountGameplayAdmissionLeaseGrpcCodec.OriginalLeaseRequest request;
    try {
      request = AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(wireRequest);
    } catch (RuntimeException malformed) {
      throw invalidArgument();
    }

    AccountGameplayAdmissionLeaseEvidence lease = request.originalLeaseEvidence();
    VerifiedCaller caller = verifyCaller(request.presentedInitialGsACompactJwt(), peer);
    requireCallerMatchesLease(caller, peer, lease);
    AccountGameplayAdmissionLeaseOperation operation =
        readExactWithCurrentCallerOnly(caller, lease);
    OriginalAckReceiptEvidence receipt = requireReceiptIfCommitted(operation);
    return AccountGameplayAdmissionLeaseGrpcCodec.toReadResponse(
        request,
        operation.evidence(),
        wireState(operation.state()),
        operation.bindingDecisionId(),
        operation.orphanCleanupId(),
        receipt);
  }

  /**
   * Records an exact ABORTED operation with its durable orphan-cleanup identity.
   *
   * <p>An omitted decision identity remains unknown. If one is supplied for a pending operation, it
   * must first resolve to the real lease-bound PROVISIONAL Game Session decision. This method does
   * not delete Game Session state or claim token retirement, registry removal, or cleanup delivery;
   * those remain separately fenced owner obligations.
   */
  public AccountGameplayAdmissionLeaseResponse abort(
      AbortGameplayAdmissionLeaseRequest wireRequest) {
    GrpcPeerIdentity peer = requireGameSessionCaller();
    requireNoAmbientSql();
    final AccountGameplayAdmissionLeaseGrpcCodec.OriginalLeaseRequest request;
    try {
      request = AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(wireRequest);
    } catch (RuntimeException malformed) {
      throw invalidArgument();
    }

    AccountGameplayAdmissionLeaseEvidence lease = request.originalLeaseEvidence();
    VerifiedCaller caller = verifyCaller(request.presentedInitialGsACompactJwt(), peer);
    requireCallerMatchesLease(caller, peer, lease);

    AccountGameplayAdmissionLeaseOperation initial = readExactWithCurrentCallerOnly(caller, lease);

    if (initial.state() == AccountGameplayAdmissionLeaseOperation.State.COMMITTED) {
      requireAbortDecisionMatches(request.bindingDecisionId(), initial);
      OriginalAckReceiptEvidence receipt = requireReceiptIfCommitted(initial);
      return AccountGameplayAdmissionLeaseGrpcCodec.toAbortResponse(
          request,
          initial.evidence(),
          wireState(initial.state()),
          initial.bindingDecisionId(),
          initial.orphanCleanupId(),
          receipt);
    }
    if (initial.state() == AccountGameplayAdmissionLeaseOperation.State.ABORTED) {
      if (initial.bindingDecisionId() == null && request.bindingDecisionId() != null) {
        return resolveUnknownAbort(request, initial, caller, peer);
      }
      requireAbortDecisionMatches(request.bindingDecisionId(), initial);
      return AccountGameplayAdmissionLeaseGrpcCodec.toAbortResponse(
          request,
          initial.evidence(),
          wireState(initial.state()),
          initial.bindingDecisionId(),
          initial.orphanCleanupId(),
          null);
    }

    UUID decisionId = request.bindingDecisionId();
    if (decisionId != null) {
      // A supplied decision is verified outside SQL. No response or timeout is interpreted as
      // authoritative NO_TOKEN or as permission to clear a candidate.
      try {
        Evidence decision = decisionReadClient.read(decisionId, lease);
        requireDecisionMatchesLease(decision, decisionId, lease, caller, peer);
      } catch (RuntimeException unknown) {
        throw unavailable();
      }
    }

    AccountGameplayAdmissionLeaseOperation aborted =
        abortExactWithCurrentCallerOnly(caller, lease, decisionId);

    OriginalAckReceiptEvidence receipt = requireReceiptIfCommitted(aborted);
    return AccountGameplayAdmissionLeaseGrpcCodec.toAbortResponse(
        request,
        aborted.evidence(),
        wireState(aborted.state()),
        aborted.bindingDecisionId(),
        aborted.orphanCleanupId(),
        receipt);
  }

  private AccountGameplayAdmissionLeaseResponse resolveUnknownAbort(
      AccountGameplayAdmissionLeaseGrpcCodec.OriginalLeaseRequest request,
      AccountGameplayAdmissionLeaseOperation original,
      VerifiedCaller caller,
      GrpcPeerIdentity peer) {
    AccountGameplayAdmissionLeaseEvidence lease = original.evidence();
    UUID cleanupId = original.orphanCleanupId();
    UUID decisionId = request.bindingDecisionId();
    AccountGameplayAdmissionAbortResolution resolution =
        readOrRecordAbortResolution(caller, lease, cleanupId, decisionId, false);
    if (resolution == null) {
      // The real PROVISIONAL decision is read outside Account SQL. An unavailable response is
      // unknown; neither lease presence nor the cleanup UUID proves a transition link.
      try {
        Evidence decision = decisionReadClient.read(decisionId, lease);
        requireDecisionMatchesLease(decision, decisionId, lease, caller, peer);
      } catch (RuntimeException unknown) {
        throw unavailable();
      }
      resolution = readOrRecordAbortResolution(caller, lease, cleanupId, decisionId, true);
    }
    return AccountGameplayAdmissionLeaseGrpcCodec.toResolvedAbortResponse(
        request,
        lease,
        cleanupId,
        new AccountGameplayAdmissionLeaseGrpcCodec.AbortResolutionEvidence(
            resolution.resolutionId(),
            resolution.originalLeaseId(),
            resolution.originalLeaseFence(),
            resolution.originalLeaseSha256(),
            resolution.orphanCleanupId(),
            resolution.bindingDecisionId()));
  }

  private AccountGameplayAdmissionAbortResolution readOrRecordAbortResolution(
      VerifiedCaller caller,
      AccountGameplayAdmissionLeaseEvidence lease,
      UUID cleanupId,
      UUID decisionId,
      boolean record) {
    try {
      return inWritableSerializableTransaction(
              dsl -> {
                AccountGameplayAdmissionCurrentSources.bind(dsl, workloadNamespace)
                    .requireCurrentAuthenticatedCallerOnly(caller.currentCandidate());
                var repository = new AccountGameplayAdmissionAbortResolutionRepository(dsl);
                return record
                    ? java.util.Optional.of(repository.recordOrReplay(lease, cleanupId, decisionId))
                    : repository.readExact(lease, cleanupId, decisionId);
              })
          .orElse(null);
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
  }

  private PublishedRealmEvidence readPublishedTarget(
      UUID requestId, Candidate candidate, Target target) {
    requireTargetNamespace(target);
    CanonicalGameplayRosterOwnerReadEvidence.Request selector =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            requestId,
            candidate.accountId(),
            workloadNamespace,
            target.canonicalTenantId(),
            target.worldSlug(),
            target.realmId(),
            target.realmSlug(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            target.canonicalGameInstanceId(),
            target.canonicalVersionId(),
            target.catalogRevision(),
            target.pointerVersion(),
            target.activeWorldEpoch());
    PublishedRealmEvidence published;
    try {
      published = decisionReadClient.readPublishedRealmAdmissionOwner(selector);
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
    if (!selector.equals(published.selector())) throw unavailable();
    return published;
  }

  private void requireTargetNamespace(Target target) {
    if (!workloadNamespace.equals(target.targetNamespace())) throw unavailable();
  }

  private static void requireCandidateMatchesTarget(Candidate candidate, Target target) {
    if (!candidate.tenantId().equals(target.canonicalTenantId())
        || !candidate.playableStateNamespaceId().equals(target.playableStateNamespaceId())
        || !candidate.playableStateScope().equals(target.playableStateScope())
        || !candidate.gameInstanceId().equals(target.canonicalGameInstanceId())
        || candidate.runtimeGameInstanceId() != target.gameInstanceId()) {
      throw unavailable();
    }
  }

  private static void requireCandidateMatchesCaller(
      Candidate candidate, VerifiedCaller caller, GrpcPeerIdentity peer) {
    var identity = caller.identity();
    var authority = caller.currentCandidate().authoritySnapshot();
    if (!candidate.accountId().equals(identity.accountId())
        || !identity.accountId().equals(authority.accountId())
        || !ACCOUNT_ISSUER.equals(candidate.issuerId())
        || !BigInteger.valueOf(authority.issuerGeneration())
            .equals(candidate.issuerAuthGeneration())
        || !peer.uri().equals(identity.callerWorkload())) {
      throw unavailable();
    }
  }

  private void requireCallerMatchesLease(
      VerifiedCaller caller, GrpcPeerIdentity peer, AccountGameplayAdmissionLeaseEvidence lease) {
    if (lease.leaseKind() != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
        || !workloadNamespace.equals(lease.carrier().get("targetNamespace"))
        || !peer.uri().equals(lease.carrier().get("callerWorkload"))
        || !peer.uri().equals(caller.identity().callerWorkload())) {
      throw unavailable();
    }
    Map<?, ?> scope = object(lease.carrier().get("bindingScope"));
    Map<?, ?> token = object(lease.carrier().get("tokenIdentityEvidence"));
    if (!caller.identity().accountId().toString().equals(scope.get("accountId"))
        || !caller.identity().accountId().toString().equals(token.get("accountId"))
        || !caller.identity().tokenJti().toString().equals(token.get("tokenJti"))
        || !caller.currentCandidate().tokenSha256().equals(token.get("tokenSHA256"))
        || !GameSessionAccountDelegationProfile.PROFILE.equals(token.get("tokenProfile"))
        || !"1".equals(token.get("tokenIdentityFence"))) {
      throw unavailable();
    }
  }

  private static void requireDecisionMatchesLease(
      Evidence decision,
      UUID requestedDecisionId,
      AccountGameplayAdmissionLeaseEvidence lease,
      VerifiedCaller caller,
      GrpcPeerIdentity peer) {
    if (!requestedDecisionId.equals(decision.bindingDecisionId())
        || decision.status() != CanonicalGameplayAdmissionDecisionReadGrpcCodec.Status.PROVISIONAL
        || !decision.leaseEvidence().hasSameIdentity(lease)) {
      throw unavailable();
    }
    requireCandidateMatchesCaller(decision.candidate(), caller, peer);
    requireCandidateMatchesTarget(decision.candidate(), decision.targetEvidence());
    requireCandidateMatchesLease(decision.candidate(), lease);
    requireTargetMatchesLease(decision.targetEvidence(), lease);
  }

  private static void requireCandidateMatchesLease(
      Candidate candidate, AccountGameplayAdmissionLeaseEvidence lease) {
    Map<?, ?> scope = object(lease.carrier().get("bindingScope"));
    Map<?, ?> authority = object(lease.carrier().get("authorityTuple"));
    if (!candidate.accountId().toString().equals(scope.get("accountId"))
        || !candidate.tenantId().toString().equals(scope.get("tenantId"))
        || !candidate
            .playableStateNamespaceId()
            .toString()
            .equals(scope.get("playableStateNamespaceId"))
        || !candidate.playableStateScope().equals(scope.get("playableStateScope"))
        || !candidate.gameInstanceId().toString().equals(scope.get("gameInstanceId"))
        || !candidate.characterId().toString().equals(scope.get("characterId"))
        || !candidate.sessionId().toString().equals(scope.get("sessionId"))
        || !candidate.regionId().toString().equals(scope.get("regionId"))
        || !candidate.regionEpoch().toString().equals(scope.get("regionEpoch"))
        || !candidate
            .issuerAuthGeneration()
            .toString()
            .equals(authority.get("issuerAuthGeneration"))
        || !candidate.issuerId().equals(ACCOUNT_ISSUER)) {
      throw unavailable();
    }
  }

  private static void requireTargetMatchesLease(
      Target target, AccountGameplayAdmissionLeaseEvidence lease) {
    // Account's closed lease pins the canonical realm/catalog/pointer reference. Game Session's
    // fresh published-owner read resolves that reference to the complete immutable pointer,
    // catalog and committed OPEN proof (including active epoch and proof fields); the owner then
    // exact-compares that full result with the original PROVISIONAL decision.
    Map<?, ?> scope = object(lease.carrier().get("bindingScope"));
    if (!target.targetNamespace().equals(lease.carrier().get("targetNamespace"))
        || !target.canonicalTenantId().toString().equals(scope.get("tenantId"))
        || !target.realmId().toString().equals(scope.get("realmId"))
        || !target.worldSlug().equals(scope.get("worldSlug"))
        || !target.realmSlug().equals(scope.get("realmSlug"))
        || !target
            .playableStateNamespaceId()
            .toString()
            .equals(scope.get("playableStateNamespaceId"))
        || !target.playableStateScope().equals(scope.get("playableStateScope"))
        || !target.canonicalGameInstanceId().toString().equals(scope.get("gameInstanceId"))
        || !Long.toString(target.catalogRevision()).equals(scope.get("catalogRevision"))
        || !Long.toString(target.pointerVersion()).equals(scope.get("pointerVersion"))) {
      throw unavailable();
    }
  }

  private static void requireLeaseMatchesCandidateTarget(
      AccountGameplayAdmissionLeaseEvidence lease,
      UUID expectedRequestId,
      Candidate candidate,
      Target target,
      BigInteger bindingGeneration) {
    Map<?, ?> scope = object(lease.carrier().get("bindingScope"));
    if (!expectedRequestId.equals(UUID.fromString((String) lease.carrier().get("requestId")))
        || !bindingGeneration.toString().equals(scope.get("bindingGeneration"))
        || !candidate.accountId().toString().equals(scope.get("accountId"))
        || !candidate.tenantId().toString().equals(scope.get("tenantId"))
        || !candidate.characterId().toString().equals(scope.get("characterId"))
        || !candidate.sessionId().toString().equals(scope.get("sessionId"))
        || !candidate.regionId().toString().equals(scope.get("regionId"))
        || !candidate.regionEpoch().toString().equals(scope.get("regionEpoch"))) {
      throw unavailable();
    }
    requireCandidateMatchesTarget(candidate, target);
    requireTargetMatchesLease(target, lease);
  }

  private static void requireAllocationMatchesOperation(
      Allocation allocation, AccountGameplayAdmissionLeaseOperation operation) {
    AccountGameplayAdmissionLeaseEvidence evidence = operation.evidence();
    if (!allocation
            .accountId()
            .toString()
            .equals(object(evidence.carrier().get("bindingScope")).get("accountId"))
        || !allocation.requestId().toString().equals(evidence.carrier().get("requestId"))
        || !allocation.leaseId().toString().equals(evidence.carrier().get("leaseId"))
        || !Long.toString(allocation.leaseFence()).equals(evidence.carrier().get("leaseFence"))) {
      throw unavailable();
    }
  }

  private AccountGameplayAdmissionLeaseEvidence buildOriginalEvidence(
      DSLContext dsl,
      Allocation allocation,
      String callerWorkload,
      Candidate candidate,
      Target target,
      BigInteger bindingGeneration,
      Map<String, Object> currentSourceFields,
      long tokenExpiryEpochSecond) {
    Long databaseNow =
        dsl.fetchSingle("SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint")
            .get(0, Long.class);
    if (databaseNow == null || databaseNow <= 0L) throw unavailable();
    long now = databaseNow;
    long originalExpiry;
    try {
      originalExpiry =
          Math.min(
              Math.addExact(now, AccountGameplayAdmissionLeaseEvidence.MAX_DURATION_MILLIS),
              Math.multiplyExact(tokenExpiryEpochSecond, 1000L));
    } catch (ArithmeticException overflow) {
      throw unavailable();
    }
    if (now <= 0L || originalExpiry <= now) throw unavailable();

    Map<String, Object> scope =
        Map.ofEntries(
            Map.entry("accountId", candidate.accountId().toString()),
            Map.entry("tenantId", target.canonicalTenantId().toString()),
            Map.entry("realmId", target.realmId().toString()),
            Map.entry("worldSlug", target.worldSlug()),
            Map.entry("realmSlug", target.realmSlug()),
            Map.entry("playableStateNamespaceId", target.playableStateNamespaceId().toString()),
            Map.entry("playableStateScope", target.playableStateScope()),
            Map.entry("gameInstanceId", target.canonicalGameInstanceId().toString()),
            Map.entry("characterId", candidate.characterId().toString()),
            Map.entry("sessionId", candidate.sessionId().toString()),
            Map.entry("bindingGeneration", bindingGeneration.toString()),
            Map.entry("catalogRevision", Long.toString(target.catalogRevision())),
            Map.entry("pointerVersion", Long.toString(target.pointerVersion())),
            Map.entry("regionId", candidate.regionId().toString()),
            Map.entry("regionEpoch", candidate.regionEpoch().toString()));
    Map<String, Object> carrier = new LinkedHashMap<>();
    carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    carrier.put("schemaVersion", "1");
    carrier.put("mode", "PUBLIC_PRODUCTION");
    carrier.put("targetNamespace", target.targetNamespace());
    carrier.put("callerWorkload", callerWorkload);
    carrier.put("requestId", allocation.requestId().toString());
    carrier.put("leaseId", allocation.leaseId().toString());
    carrier.put("leaseFence", Long.toString(allocation.leaseFence()));
    carrier.put("bindingScope", scope);
    carrier.put("leaseKind", AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING.name());
    carrier.putAll(currentSourceFields);
    carrier.put("evaluatedAt", Long.toString(now));
    carrier.put("expiresAt", Long.toString(originalExpiry));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  private <T> T withCurrentSources(
      VerifiedCaller caller, UUID tenantId, CurrentSourceOperation<T> operation) {
    Objects.requireNonNull(caller);
    Objects.requireNonNull(tenantId);
    try {
      return inWritableSerializableTransaction(
          dsl -> {
            AccountGameplayAdmissionCurrentSources currentSources =
                AccountGameplayAdmissionCurrentSources.bind(dsl, workloadNamespace);
            var current =
                currentSources.readCurrentPublicProductionNew(
                    tenantId, caller.identity().tokenJti(), caller.currentCandidate());
            return operation.apply(
                new CurrentAccountOperation(
                    dsl, current, new AccountGameplayAdmissionLeaseRepository(dsl)));
          });
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
  }

  /** Exact non-admitting read after fresh current caller/global Account/token SQL proof. */
  private AccountGameplayAdmissionLeaseOperation readExactWithCurrentCallerOnly(
      VerifiedCaller caller, AccountGameplayAdmissionLeaseEvidence lease) {
    try {
      return inWritableSerializableTransaction(
          dsl -> {
            AccountGameplayAdmissionCurrentSources currentSources =
                AccountGameplayAdmissionCurrentSources.bind(dsl, workloadNamespace);
            currentSources.requireCurrentAuthenticatedCallerOnly(caller.currentCandidate());
            return new AccountGameplayAdmissionLeaseRepository(dsl)
                .readExact(lease)
                .orElseThrow(AccountGameplayAdmissionLeaseOwner::unavailable);
          });
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
  }

  /**
   * Exact finalization lookup: terminal history needs only current caller proof, while PENDING
   * still requires the complete current public-production tenant source tuple in this transaction.
   */
  private AccountGameplayAdmissionLeaseOperation readExactForFinalization(
      VerifiedCaller caller, AccountGameplayAdmissionLeaseEvidence lease) {
    try {
      return inWritableSerializableTransaction(
          dsl -> {
            AccountGameplayAdmissionCurrentSources currentSources =
                AccountGameplayAdmissionCurrentSources.bind(dsl, workloadNamespace);
            currentSources.requireCurrentAuthenticatedCallerOnly(caller.currentCandidate());
            AccountGameplayAdmissionLeaseRepository repository =
                new AccountGameplayAdmissionLeaseRepository(dsl);
            AccountGameplayAdmissionLeaseOperation operation =
                repository
                    .readExact(lease)
                    .orElseThrow(AccountGameplayAdmissionLeaseOwner::unavailable);
            if (operation.state() == AccountGameplayAdmissionLeaseOperation.State.PENDING) {
              var current =
                  currentSources.readCurrentPublicProductionNew(
                      tenantId(lease), caller.identity().tokenJti(), caller.currentCandidate());
              current.requireMatchesOriginalLease(lease);
            }
            return operation;
          });
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
  }

  /**
   * Atomically records or replays the exact orphan obligation under fresh caller-only SQL proof.
   */
  private AccountGameplayAdmissionLeaseOperation abortExactWithCurrentCallerOnly(
      VerifiedCaller caller, AccountGameplayAdmissionLeaseEvidence lease, UUID decisionId) {
    try {
      return inWritableSerializableTransaction(
          dsl -> {
            AccountGameplayAdmissionCurrentSources currentSources =
                AccountGameplayAdmissionCurrentSources.bind(dsl, workloadNamespace);
            currentSources.requireCurrentAuthenticatedCallerOnly(caller.currentCandidate());
            AccountGameplayAdmissionLeaseRepository repository =
                new AccountGameplayAdmissionLeaseRepository(dsl);
            AccountGameplayAdmissionLeaseOperation operation =
                repository
                    .readExact(lease)
                    .orElseThrow(AccountGameplayAdmissionLeaseOwner::unavailable);
            if (operation.state() == AccountGameplayAdmissionLeaseOperation.State.PENDING) {
              // Null decision records an unresolved orphan obligation, not proof of absence.
              return repository.recordAborted(lease, decisionId, UUID.randomUUID());
            }
            if (operation.state() == AccountGameplayAdmissionLeaseOperation.State.ABORTED) {
              requireAbortDecisionMatches(decisionId, operation);
            } else if (decisionId != null && !decisionId.equals(operation.bindingDecisionId())) {
              throw unavailable();
            }
            return operation;
          });
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
  }

  private <T> T inWritableSerializableTransaction(
      java.util.function.Function<DSLContext, T> operation) {
    DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
    T result =
        transaction.execute(
            status -> {
              if (!status.isNewTransaction()) throw unavailable();
              Object resource =
                  TransactionSynchronizationManager.getResource(
                      Objects.requireNonNull(manager.getDataSource()));
              if (!(resource instanceof ConnectionHolder holder)) throw unavailable();
              Connection connection = holder.getConnection();
              try {
                if (connection.getAutoCommit()
                    || connection.isReadOnly()
                    || connection.getTransactionIsolation()
                        != Connection.TRANSACTION_SERIALIZABLE) {
                  throw unavailable();
                }
              } catch (java.sql.SQLException failure) {
                throw unavailable();
              }
              return operation.apply(DSL.using(connection, SQLDialect.POSTGRES));
            });
    return Objects.requireNonNull(result);
  }

  private void requireNoAmbientSql() {
    Object managedDataSource =
        Objects.requireNonNull(new DataSourceTransactionManager(dataSource).getDataSource());
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.hasResource(dataSource)
        || TransactionSynchronizationManager.hasResource(managedDataSource)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Gameplay admission owner requires no ambient Account transaction")
          .asRuntimeException();
    }
  }

  private GrpcPeerIdentity requireGameSessionCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Game Session workload identity required")
          .asRuntimeException();
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || !GAME_SESSION_SERVICE.equals(peer.service())
        || !workloadNamespace.equals(peer.namespace())
        || !expectedGameSessionPeer().equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Session workload required")
          .asRuntimeException();
    }
    return peer;
  }

  private VerifiedCaller verifyCaller(String compactJwt, GrpcPeerIdentity peer) {
    VerifiedCaller caller;
    try {
      caller = callerVerifier.verifyPresentedCompactToken(compactJwt);
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
    if (!peer.uri().equals(caller.identity().callerWorkload())) throw unavailable();
    return caller;
  }

  private OriginalAckReceiptEvidence requireReceiptIfCommitted(
      AccountGameplayAdmissionLeaseOperation operation) {
    if (operation.state() != AccountGameplayAdmissionLeaseOperation.State.COMMITTED) return null;
    return requireReceipt(operation, operation.bindingDecisionId());
  }

  private OriginalAckReceiptEvidence requireReceipt(
      AccountGameplayAdmissionLeaseOperation operation, UUID decisionId) {
    if (operation.state() != AccountGameplayAdmissionLeaseOperation.State.COMMITTED
        || decisionId == null
        || !decisionId.equals(operation.bindingDecisionId())) throw unavailable();
    AccountGameplayAdmissionOriginalAckReceipt receipt;
    try {
      receipt = receiptExecutor.read(operation.evidence(), decisionId);
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
    requireReceiptOperation(receipt, operation.evidence(), decisionId);
    return receiptEvidence(receipt);
  }

  private static void requireReceiptOperation(
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      AccountGameplayAdmissionLeaseEvidence evidence,
      UUID decisionId) {
    if (!receipt.operation().evidence().hasSameIdentity(evidence)
        || receipt.operation().state() != AccountGameplayAdmissionLeaseOperation.State.COMMITTED
        || !decisionId.equals(receipt.bindingDecisionId())) throw unavailable();
  }

  private static OriginalAckReceiptEvidence receiptEvidence(
      AccountGameplayAdmissionOriginalAckReceipt receipt) {
    return OriginalAckReceiptEvidence.fromFields(
        receipt.schemaVersion(),
        receipt.requestId(),
        receipt.accountId(),
        receipt.leaseId(),
        receipt.leaseFence(),
        receipt.evidenceSha256(),
        receipt.bindingDecisionId(),
        receipt.expiresAtMs(),
        receipt.finalizationXid(),
        receipt.committedBeforeMs(),
        receipt.receiptXid());
  }

  private static void requireAbortDecisionMatches(
      UUID requestedDecision, AccountGameplayAdmissionLeaseOperation operation) {
    if (requestedDecision != null && !requestedDecision.equals(operation.bindingDecisionId())) {
      throw unavailable();
    }
  }

  private static AccountGameplayAdmissionLeaseGrpcCodec.State wireState(
      AccountGameplayAdmissionLeaseOperation.State state) {
    return switch (state) {
      case PENDING -> AccountGameplayAdmissionLeaseGrpcCodec.State.PENDING;
      case COMMITTED -> AccountGameplayAdmissionLeaseGrpcCodec.State.COMMITTED;
      case ABORTED -> AccountGameplayAdmissionLeaseGrpcCodec.State.ABORTED;
    };
  }

  private static UUID tenantId(AccountGameplayAdmissionLeaseEvidence evidence) {
    Map<?, ?> scope = object(evidence.carrier().get("bindingScope"));
    return UUID.fromString((String) scope.get("tenantId"));
  }

  private static UUID requestId(AccountGameplayAdmissionLeaseEvidence evidence) {
    return UUID.fromString((String) evidence.carrier().get("requestId"));
  }

  private String expectedGameSessionPeer() {
    return "spiffe://firemud/ns/" + workloadNamespace + "/sa/" + GAME_SESSION_SERVICE;
  }

  private static Map<?, ?> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw unavailable();
    return map;
  }

  private static StatusRuntimeException invalidArgument() {
    return Status.INVALID_ARGUMENT
        .withDescription("Malformed Account gameplay admission request")
        .asRuntimeException();
  }

  private static StatusRuntimeException unavailable() {
    return Status.UNAVAILABLE
        .withDescription("Account gameplay admission owner is unavailable")
        .asRuntimeException();
  }

  private record CurrentAccountOperation(
      DSLContext dsl,
      AccountGameplayAdmissionCurrentSources.Current currentSources,
      AccountGameplayAdmissionLeaseRepository repository) {
    private void requireCandidate(
        Candidate candidate, VerifiedCaller caller, GrpcPeerIdentity peer) {
      requireCandidateMatchesCaller(candidate, caller, peer);
    }
  }

  @FunctionalInterface
  private interface CurrentSourceOperation<T> {
    T apply(CurrentAccountOperation current);
  }
}
