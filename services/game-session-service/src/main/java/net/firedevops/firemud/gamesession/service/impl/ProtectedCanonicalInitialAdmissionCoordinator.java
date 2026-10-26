package net.firedevops.firemud.gamesession.service.impl;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalHumanRuntimeCompletionRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual, unregistered composition for one Account-protected Human initial admission.
 *
 * <p>Account and World calls are made outside SQL transactions. Each Game Session write uses its
 * own READ COMMITTED transaction. Constructing this type does not register an RPC, listener, or
 * runtime caller.
 */
public final class ProtectedCanonicalInitialAdmissionCoordinator {
  private final CanonicalInitialAdmissionRepository admissions;
  private final CanonicalHumanRuntimeCompletionRepository completions;
  private final AccountInitialAdmissionProtectionBindingRepository protectionBindings;
  private final AccountStartSessionAdmissionProtectionAcquisitionClient accountClient;
  private final CanonicalInitialAdmissionWorldVerifier worldVerifier;
  private final TransactionTemplate writeTransaction;
  private final String workloadNamespace;

  public ProtectedCanonicalInitialAdmissionCoordinator(
      CanonicalInitialAdmissionRepository admissions,
      CanonicalHumanRuntimeCompletionRepository completions,
      AccountInitialAdmissionProtectionBindingRepository protectionBindings,
      AccountStartSessionAdmissionProtectionAcquisitionClient accountClient,
      CanonicalInitialAdmissionWorldVerifier worldVerifier,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.admissions = Objects.requireNonNull(admissions, "admissions");
    this.completions = Objects.requireNonNull(completions, "completions");
    this.protectionBindings = Objects.requireNonNull(protectionBindings, "protectionBindings");
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient");
    this.worldVerifier = Objects.requireNonNull(worldVerifier, "worldVerifier");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    writeTransaction = new TransactionTemplate(transactionManager);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Acquires or reuses exact protection, completes the runtime, then commits the first OPEN. */
  public CanonicalInitialAdmissionOwnerProof bind(
      CanonicalInitialAdmissionRequest request,
      AccountStartSessionAdmissionProtectionRequest accountRequest) {
    requireNoAmbientOwnerTransaction();
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(accountRequest, "accountRequest");
    requireWorkloadNamespace(request);
    requireAccountRequestBindsAdmission(request, accountRequest);

    Optional<CanonicalInitialAdmissionOwnerProof> existing =
        admissions.read(workloadNamespace, request.initialAdmissionRequestId());
    if (existing.isPresent()) {
      CanonicalInitialAdmissionOwnerProof proof = existing.orElseThrow();
      requireExactOwnerProof(proof, request);
      if (proof.outcome() != Outcome.PENDING) {
        return replayTerminalBind(request, accountRequest, proof);
      }
    }

    Optional<CanonicalHumanRuntimeCompletionRepository.Completion> completion =
        completions.read(workloadNamespace, request.initialAdmissionRequestId());
    if (existing.isEmpty() && completion.isPresent()) {
      throw conflict("Runtime completion exists without its protected admission attempt");
    }

    AccountStartSessionAdmissionProtectionEvidence protection;
    if (completion.isPresent()) {
      protection = protectionFromCompletion(completion.orElseThrow(), request, accountRequest);
    } else {
      protection = acquireExactProtection(accountRequest);
      write(() -> admissions.reserveProtectedStartingForRuntimeCompletion(request, protection));
    }

    CanonicalInitialAdmissionWorldProof worldProof = verifyWorld(request);
    write(() -> completions.completeProtected(request, protection, worldProof));
    write(() -> admissions.commitProtected(request, protection, worldProof));

    CanonicalInitialAdmissionOwnerProof committed = requireOwnerResult(request);
    if (committed.outcome() != Outcome.COMMITTED) {
      throw conflict("Protected Human initial admission did not commit");
    }
    return committed;
  }

  /** Records or replays only an explicit, exact durable abort. */
  public CanonicalInitialAdmissionOwnerProof abort(
      CanonicalInitialAdmissionRequest request,
      AccountStartSessionAdmissionProtectionRequest accountRequest,
      String reason) {
    requireNoAmbientOwnerTransaction();
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(accountRequest, "accountRequest");
    requireReason(reason);
    requireWorkloadNamespace(request);
    requireAccountRequestBindsAdmission(request, accountRequest);

    Optional<CanonicalInitialAdmissionOwnerProof> existing =
        admissions.read(workloadNamespace, request.initialAdmissionRequestId());
    if (existing.isPresent()) {
      CanonicalInitialAdmissionOwnerProof proof = existing.orElseThrow();
      requireExactOwnerProof(proof, request);
      if (proof.outcome() == Outcome.COMMITTED) {
        throw conflict("A committed initial admission cannot be aborted");
      }
      if (proof.outcome() == Outcome.ABORTED) {
        AccountStartSessionAdmissionProtectionEvidence protection =
            retainedTerminalProtection(request, accountRequest, proof).protection();
        write(() -> admissions.reserveProtected(request, protection));
        write(() -> admissions.abortProtected(request, protection, reason));
        return requireOutcome(request, Outcome.ABORTED);
      }
    }

    Optional<CanonicalHumanRuntimeCompletionRepository.Completion> completion =
        completions.read(workloadNamespace, request.initialAdmissionRequestId());
    if (existing.isEmpty() && completion.isPresent()) {
      throw conflict("Runtime completion exists without its protected admission attempt");
    }

    AccountStartSessionAdmissionProtectionEvidence protection;
    if (completion.isPresent()) {
      protection = protectionFromCompletion(completion.orElseThrow(), request, accountRequest);
    } else {
      protection = acquireExactProtection(accountRequest);
      write(() -> admissions.reserveProtectedStartingForRuntimeCompletion(request, protection));
    }
    write(() -> admissions.abortProtected(request, protection, reason));
    return requireOutcome(request, Outcome.ABORTED);
  }

  private CanonicalInitialAdmissionOwnerProof replayTerminalBind(
      CanonicalInitialAdmissionRequest request,
      AccountStartSessionAdmissionProtectionRequest accountRequest,
      CanonicalInitialAdmissionOwnerProof proof) {
    AccountInitialAdmissionProtectionBindingRepository.RetainedProtection retained =
        retainedTerminalProtection(request, accountRequest, proof);
    write(() -> admissions.reserveProtected(request, retained.protection()));
    if (proof.outcome() != Outcome.COMMITTED) {
      throw conflict("A definitively aborted initial admission cannot later commit");
    }
    return requireOutcome(request, Outcome.COMMITTED);
  }

  private AccountInitialAdmissionProtectionBindingRepository.RetainedProtection
      retainedTerminalProtection(
          CanonicalInitialAdmissionRequest request,
          AccountStartSessionAdmissionProtectionRequest accountRequest,
          CanonicalInitialAdmissionOwnerProof proof) {
    var retained =
        protectionBindings
            .read(workloadNamespace, request.initialAdmissionRequestId())
            .orElseThrow(() -> conflict("Terminal admission has no complete retained protection"));
    requireExactAccountRequest(retained.protection(), accountRequest);
    if (!Objects.equals(retained.terminalProofDigest(), proof.proofDigest())) {
      throw conflict("Retained Account protection does not bind the exact terminal proof");
    }
    return retained;
  }

  private AccountStartSessionAdmissionProtectionEvidence protectionFromCompletion(
      CanonicalHumanRuntimeCompletionRepository.Completion completion,
      CanonicalInitialAdmissionRequest request,
      AccountStartSessionAdmissionProtectionRequest accountRequest) {
    if (!completion.request().equals(request)) {
      throw conflict("Runtime completion belongs to another initial-admission request");
    }
    AccountStartSessionAdmissionProtectionEvidence protection =
        AccountStartSessionAdmissionProtectionEvidence.decode(
            completion.accountProtectionEvidenceBytes());
    if (!completion.accountProtectionId().equals(protection.accountProtectionId())
        || completion.accountProtectionFence() != protection.accountProtectionFence()) {
      throw conflict("Runtime completion protection identity or fence is inconsistent");
    }
    requireExactAccountRequest(protection, accountRequest);
    return protection;
  }

  private AccountStartSessionAdmissionProtectionEvidence acquireExactProtection(
      AccountStartSessionAdmissionProtectionRequest accountRequest) {
    requireNoAmbientOwnerTransaction();
    AccountStartSessionAdmissionProtectionEvidence protection =
        Objects.requireNonNull(
            accountClient.acquire(accountRequest), "Account returned no admission protection");
    requireNoAmbientOwnerTransaction();
    requireExactAccountRequest(protection, accountRequest);
    return protection;
  }

  private CanonicalInitialAdmissionWorldProof verifyWorld(
      CanonicalInitialAdmissionRequest request) {
    requireNoAmbientOwnerTransaction();
    CanonicalInitialAdmissionWorldProof proof =
        Objects.requireNonNull(worldVerifier.verify(request), "World returned no hold proof");
    requireNoAmbientOwnerTransaction();
    proof.requireMatches(request);
    return proof;
  }

  private void write(Runnable operation) {
    requireNoAmbientOwnerTransaction();
    writeTransaction.executeWithoutResult(ignored -> operation.run());
    requireNoAmbientOwnerTransaction();
  }

  private CanonicalInitialAdmissionOwnerProof requireOwnerResult(
      CanonicalInitialAdmissionRequest request) {
    requireNoAmbientOwnerTransaction();
    CanonicalInitialAdmissionOwnerProof proof =
        admissions
            .read(workloadNamespace, request.initialAdmissionRequestId())
            .orElseThrow(() -> conflict("Initial-admission owner result is unavailable"));
    requireExactOwnerProof(proof, request);
    return proof;
  }

  private CanonicalInitialAdmissionOwnerProof requireOutcome(
      CanonicalInitialAdmissionRequest request, Outcome outcome) {
    CanonicalInitialAdmissionOwnerProof proof = requireOwnerResult(request);
    if (proof.outcome() != outcome
        || (outcome == Outcome.ABORTED && !proof.positiveDurableAbort())) {
      throw conflict("Initial-admission owner returned a different terminal outcome");
    }
    return proof;
  }

  private void requireWorkloadNamespace(CanonicalInitialAdmissionRequest request) {
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Initial-admission request is outside this Game Session workload namespace");
    }
  }

  private static void requireAccountRequestBindsAdmission(
      CanonicalInitialAdmissionRequest request,
      AccountStartSessionAdmissionProtectionRequest accountRequest) {
    if (!request.equals(admissionRequest(accountRequest))) {
      throw new IllegalArgumentException(
          "Account World hold does not bind the complete Game Session admission request");
    }
  }

  private static CanonicalInitialAdmissionRequest admissionRequest(
      AccountStartSessionAdmissionProtectionRequest accountRequest) {
    HoldIdentity hold = accountRequest.worldAdmissionHoldIdentity();
    Request world = hold.request();
    return new CanonicalInitialAdmissionRequest(
        world.targetNamespace(),
        world.canonicalTenantId(),
        world.worldSlug(),
        world.realmId(),
        world.playableStateNamespaceId(),
        world.playableStateScope(),
        world.canonicalGameInstanceId(),
        world.canonicalVersionId(),
        world.activeLifecycleEpoch(),
        world.expectedCatalogRevision(),
        CanonicalInitialAdmissionRequest.OriginKind.valueOf(world.initialAdmissionOrigin().name()),
        world.expectedPriorPointerVersion(),
        world.initialAdmissionRequestId(),
        world.initialAdmissionRequestDigest(),
        hold.holdId(),
        hold.holdFence(),
        hold.holdBindingDigest());
  }

  private static void requireExactAccountRequest(
      AccountStartSessionAdmissionProtectionEvidence protection,
      AccountStartSessionAdmissionProtectionRequest expected) {
    if (!Arrays.equals(protection.request().canonicalBytes(), expected.canonicalBytes())) {
      throw conflict("Account admission protection differs from the complete original request");
    }
  }

  private static void requireExactOwnerProof(
      CanonicalInitialAdmissionOwnerProof proof, CanonicalInitialAdmissionRequest request) {
    if (!proof.initialAdmissionRequestId().equals(request.initialAdmissionRequestId())
        || !proof.requestDigest().equals(request.requestDigest())
        || !proof.targetNamespace().equals(request.targetNamespace())
        || !proof.canonicalTenantId().equals(request.canonicalTenantId())
        || !proof.worldSlug().equals(request.worldSlug())
        || !proof.realmId().equals(request.realmId())
        || !proof.playableStateNamespaceId().equals(request.playableStateNamespaceId())
        || !proof.playableStateScope().equals(request.playableStateScope())
        || !proof.canonicalGameInstanceId().equals(request.canonicalGameInstanceId())
        || !proof.canonicalVersionId().equals(request.canonicalVersionId())
        || proof.activeLifecycleEpoch() != request.activeLifecycleEpoch()
        || proof.expectedCatalogRevision() != request.expectedCatalogRevision()
        || proof.originKind() != request.originKind()
        || !Objects.equals(
            proof.expectedPriorPointerVersion(), request.expectedPriorPointerVersion())
        || !proof.holdId().equals(request.holdId())
        || !proof.holdFence().equals(request.holdFence())
        || !proof.holdBindingDigest().equals(request.holdBindingDigest())) {
      throw conflict("Initial-admission owner result differs from the complete request");
    }
  }

  private static void requireReason(String reason) {
    Objects.requireNonNull(reason, "reason");
    if (reason.isBlank() || reason.length() > 500) {
      throw new IllegalArgumentException("reason must contain 1..500 characters");
    }
  }

  private static void requireNoAmbientOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Protected initial-admission composition requires independent owner operations");
    }
  }

  private static CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException
      conflict(String message) {
    return new CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException(
        message);
  }
}
