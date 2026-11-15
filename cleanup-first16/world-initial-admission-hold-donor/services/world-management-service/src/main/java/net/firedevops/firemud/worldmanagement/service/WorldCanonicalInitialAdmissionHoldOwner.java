package net.firedevops.firemud.worldmanagement.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.client.WorldCanonicalInitialAdmissionGameSessionProofClient;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldFixedPresetResources;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider.GameSessionOwnerProofClientPins;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldRepository.HoldRecord;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit run-owned fixture owner for the canonical World initial-admission hold.
 *
 * <p>The fixed-server evidence refresh runs with no World transaction. Only the narrow local
 * insert/status transitions run in owned READ COMMITTED transactions; state readback runs in an
 * independent read-only REPEATABLE READ snapshot.
 */
public final class WorldCanonicalInitialAdmissionHoldOwner {
  private final RunOwnedWorldLaunchEvidenceProvider evidenceProvider;
  private final WorldCanonicalInstanceAssociationRepository associationRepository;
  private final WorldCanonicalInitialAdmissionHoldRepository holdRepository;
  private final WorldCanonicalInitialAdmissionGameSessionProofClient proofClient;
  private final WorldInstanceRepository worldInstanceRepository;
  private final DSLContext dsl;
  private final TransactionTemplate noTransaction;
  private final TransactionTemplate writer;
  private final TransactionTemplate committedRead;
  private final Clock clock;

  public WorldCanonicalInitialAdmissionHoldOwner(
      RunOwnedWorldLaunchEvidenceProvider evidenceProvider,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      WorldCanonicalInitialAdmissionHoldRepository holdRepository,
      WorldCanonicalInitialAdmissionGameSessionProofClient proofClient,
      WorldInstanceRepository worldInstanceRepository,
      DSLContext dsl,
      PlatformTransactionManager transactionManager) {
    this(
        evidenceProvider,
        associationRepository,
        holdRepository,
        proofClient,
        worldInstanceRepository,
        dsl,
        transactionManager,
        Clock.systemUTC());
  }

  WorldCanonicalInitialAdmissionHoldOwner(
      RunOwnedWorldLaunchEvidenceProvider evidenceProvider,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      WorldCanonicalInitialAdmissionHoldRepository holdRepository,
      WorldCanonicalInitialAdmissionGameSessionProofClient proofClient,
      WorldInstanceRepository worldInstanceRepository,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.evidenceProvider = Objects.requireNonNull(evidenceProvider, "evidenceProvider");
    this.associationRepository = Objects.requireNonNull(associationRepository, "associationRepository");
    this.holdRepository = Objects.requireNonNull(holdRepository, "holdRepository");
    this.proofClient = Objects.requireNonNull(proofClient, "proofClient");
    this.worldInstanceRepository = Objects.requireNonNull(worldInstanceRepository, "worldInstanceRepository");
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.clock = Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(transactionManager, "transactionManager");
    noTransaction = new TransactionTemplate(transactionManager);
    noTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    writer = new TransactionTemplate(transactionManager);
    writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    committedRead = new TransactionTemplate(transactionManager);
    committedRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    committedRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    committedRead.setReadOnly(true);
  }

  /** Verifies current fixed evidence, then acquires/replays one exact local hold. */
  public HoldIdentity acquire(byte[] canonicalRequestBytes, byte[] lifecycleReadRequestBytes) {
    Request request = parseFixtureRequest(canonicalRequestBytes);
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest =
        WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(lifecycleReadRequestBytes);
    RunOwnedWorldLaunchEvidence evidence =
        required(
            noTransaction.execute(
                status -> evidenceProvider.requireAuthenticatedEvidence()),
            "Fixture evidence refresh returned no evidence");
    evidence.requireFixedGameSessionPeer();
    var originalGameSessionRequest = evidence.originalGameSessionRequest();
    WorldCanonicalInstanceAssociation association =
        associationRepository.readRunOwnedFixtureAssociation(evidence, originalGameSessionRequest);
    requireRequestMatchesCurrentOwners(request, lifecycleRequest, evidence, association);
    var fixture = association.runOwnedFixture();
    var presetPlan =
        RunOwnedWorldFixedPresetResources.plan(
            association, fixture.sourceIntakeReceipt(), association.versionIdentity());
    requireCommittedActiveFixture(association, presetPlan);

    HoldIdentity proposed = new HoldIdentity(request, UUID.randomUUID(), UUID.randomUUID());
    HoldIdentity written =
        required(
            writer.execute(
                status -> {
                  WorldCanonicalInstanceAssociation locked =
                      associationRepository.readRunOwnedFixtureAssociationInActivationTransaction(
                          evidence, originalGameSessionRequest);
                  requireSameFixtureAssociation(association, locked);
                  var lockedFixture = locked.runOwnedFixture();
                  var lockedPlan =
                      RunOwnedWorldFixedPresetResources.plan(
                          locked, lockedFixture.sourceIntakeReceipt(), locked.versionIdentity());
                  if (!presetPlan.planJson().equals(lockedPlan.planJson())) {
                    throw evidenceMismatch();
                  }
                  requireCommittedActiveFixture(locked, lockedPlan);
                  var inserted =
                      holdRepository.insertFixtureHold(proposed, locked, clock.instant());
                  if (inserted.isPresent()) return inserted.orElseThrow().identity();
                  return holdRepository
                      .findIdentityInCurrentTransaction(request)
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "INITIAL_ADMISSION_BIND_HOLD_CONFLICT: another hold owns this World realm"));
                }),
            "World fixture hold transaction returned no identity");

    HoldIdentity committed =
        holdRepository
            .findIdentity(request)
            .orElseThrow(() -> new IllegalStateException("World fixture hold did not commit"));
    if (!written.equals(committed)) {
      throw new IllegalStateException("World fixture hold identity changed after commit");
    }
    return committed;
  }

  /** Returns immutable acquisition history only; it is not a status or admission checkpoint. */
  public HoldIdentity readIdentity(byte[] canonicalRequestBytes) {
    Request request = parseFixtureRequest(canonicalRequestBytes);
    GameSessionOwnerProofClientPins pins =
        required(
            noTransaction.execute(
                status -> evidenceProvider.requireGameSessionOwnerProofClientPins()),
            "Fixed Game Session owner proof pins returned no result");
    pins.requireTargetNamespace(request.targetNamespace());
    pins.requireCurrentGameSessionPeer();
    return holdRepository
        .findIdentity(request)
        .orElseThrow(
            () -> new IllegalArgumentException("CANONICAL_WORLD_HOLD_NOT_FOUND: no exact hold exists"));
  }

  /** Reads hold status and fixture ACTIVE evidence from one owned read-only snapshot. */
  public WorldCanonicalInitialAdmissionHoldState readState(
      byte[] identityBytes, byte[] lifecycleReadRequestBytes) {
    HoldIdentity identity = HoldIdentity.fromStored(identityBytes);
    requireFixtureRequest(identity.request());
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest =
        WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(lifecycleReadRequestBytes);
    RunOwnedWorldLaunchEvidence evidence =
        required(
            noTransaction.execute(status -> evidenceProvider.requireAuthenticatedEvidence()),
            "Fixture evidence refresh returned no evidence");
    evidence.requireFixedGameSessionPeer();
    requireIdentityMatchesCurrentEvidence(identity, evidence);
    var originalGameSessionRequest = evidence.originalGameSessionRequest();
    var association =
        associationRepository.readRunOwnedFixtureAssociation(evidence, originalGameSessionRequest);
    requireRequestMatchesCurrentOwners(identity.request(), lifecycleRequest, evidence, association);
    var fixture = association.runOwnedFixture();
    var plan =
        RunOwnedWorldFixedPresetResources.plan(
            association, fixture.sourceIntakeReceipt(), association.versionIdentity());

    return required(
        committedRead.execute(
            status -> {
              var committedAssociation =
                  associationRepository.readRunOwnedFixtureAssociationInOwnerReadOnlyTransaction(
                      evidence, originalGameSessionRequest);
              requireSameFixtureAssociation(association, committedAssociation);
              RunOwnedWorldFixedPresetResources.readCommittedAfterActivation(
                  dsl,
                  plan,
                  committedAssociation.worldInstanceId(),
                  committedAssociation.worldPrivateKeys().gameInstanceId());
              HoldRecord hold =
                  holdRepository
                      .findLifecycleStateInReadOnlySnapshot(identity, lifecycleRequest)
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "CANONICAL_WORLD_HOLD_NOT_FOUND: exact hold identity is absent"))
                      .hold();
              var lifecycleEvidence =
                  new WorldCanonicalInstanceLifecycleEvidence(
                      lifecycleRequest,
                      null,
                      null,
                      0L,
                      "ACTIVE",
                      2L,
                      1L,
                      null,
                      null,
                      null,
                      Map.of());
              return new WorldCanonicalInitialAdmissionHoldState(
                  hold.identity(), hold.status(), lifecycleEvidence);
            }),
        "World fixture hold snapshot returned no state");
  }

  /** Reconciles one retained fixture hold only from an authenticated method-specific GS proof. */
  public String reconcileHold(String holdId) {
    HoldRecord hold =
        holdRepository
            .findByHoldId(holdId)
            .orElseThrow(
                () -> new IllegalArgumentException("CANONICAL_WORLD_HOLD_NOT_FOUND: hold is absent"));
    HoldIdentity identity = hold.identity();
    requireFixtureRequest(identity.request());
    if (isTerminal(hold.status())) return "none";

    GameSessionCanonicalInitialAdmissionOwnerProof proof;
    try {
      GameSessionOwnerProofClientPins pins =
          required(
              noTransaction.execute(
                  status -> evidenceProvider.requireGameSessionOwnerProofClientPins()),
              "Fixed Game Session owner proof pins returned no result");
      proof =
          required(
              noTransaction.execute(status -> proofClient.readOwnerProof(identity, pins)),
              "Game Session owner proof read returned no result");
    } catch (RuntimeException unavailable) {
      markReconciliation(identity, "GS_OWNER_READ_FAILED");
      return "GS_OWNER_READ_FAILED";
    }
    if (proof == null) {
      markReconciliation(identity, "GS_OWNER_PROOF_MISSING");
      return "GS_OWNER_PROOF_MISSING";
    }
    if (!identity.equals(proof.holdIdentity())) {
      markReconciliation(identity, "GS_OWNER_PROOF_MISMATCH");
      return "GS_OWNER_PROOF_MISMATCH";
    }
    if (proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      markReconciliation(identity, "GS_OWNER_PENDING");
      return "GS_OWNER_PENDING";
    }
    if (!hasDefinitiveTerminalEvidence(identity, proof)) {
      markReconciliation(identity, "GS_OWNER_TERMINAL_PROOF_INCOMPLETE");
      return "GS_OWNER_TERMINAL_PROOF_INCOMPLETE";
    }

    byte[] proofBytes = GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof);
    String proofDigest = digest(proofBytes);
    HoldStatus terminalStatus =
        proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED
            ? HoldStatus.COMMITTED
            : HoldStatus.ABORTED;
    writer.executeWithoutResult(
        status -> {
          HoldRecord current =
              holdRepository
                  .findByHoldId(holdId)
                  .orElseThrow(
                      () -> new IllegalArgumentException("CANONICAL_WORLD_HOLD_NOT_FOUND: hold is absent"));
          if (!identity.equals(current.identity())) {
            throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT: canonical hold identity changed");
          }
          if (isTerminal(current.status())) {
            if (current.status() != terminalStatus
                || !proofDigest.equals(current.ownerProofDigest())
                || !java.util.Arrays.equals(proofBytes, current.ownerProofBytes())) {
              throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT: terminal owner proof changed");
            }
            return;
          }
          holdRepository.finalizeWithOwnerProof(
              identity,
              terminalStatus,
              identity.request().initialAdmissionRequestId(),
              digestBare(proofBytes),
              proof.auditEventId() == null ? null : Long.toString(proof.auditEventId()),
              proof.committedPointerVersion(),
              proof.terminalAt(),
              proof.positiveDurableAbort(),
              proofBytes,
              proofDigest);
        });
    HoldRecord committed =
        holdRepository
            .findByHoldId(holdId)
            .orElseThrow(() -> new IllegalStateException("World fixture hold disappeared after commit"));
    if (committed.status() != terminalStatus
        || !proofDigest.equals(committed.ownerProofDigest())
        || !java.util.Arrays.equals(proofBytes, committed.ownerProofBytes())) {
      throw new IllegalStateException("World fixture terminal hold readback differs from its committed proof");
    }
    return "none";
  }

  public boolean isRunOwnedFixtureHold(String holdId) {
    return holdRepository.isRunOwnedFixtureHold(holdId);
  }

  private void markReconciliation(HoldIdentity identity, String errorCode) {
    writer.executeWithoutResult(
        status -> {
          HoldRecord current =
              holdRepository
                  .findByHoldId(identity.holdId())
                  .orElseThrow(
                      () -> new IllegalArgumentException("CANONICAL_WORLD_HOLD_NOT_FOUND: hold is absent"));
          if (!identity.equals(current.identity()) || isTerminal(current.status())) return;
          holdRepository.markReconciliationRequired(identity, errorCode);
        });
  }

  private void requireCommittedActiveFixture(
      WorldCanonicalInstanceAssociation association,
      RunOwnedWorldFixedPresetResources.FixedPresetPlan plan) {
    if (!association.isRunOwnedFixture()
        || association.runOwnedFixture().preparingLifecycleEpoch() != 1L) {
      throw evidenceMismatch();
    }
    var world =
        worldInstanceRepository
            .findById(association.worldInstanceId())
            .orElseThrow(() -> evidenceMismatch());
    if (!"ACTIVE".equals(world.getStatus())
        || !Long.valueOf(2L).equals(world.getLifecycleEpoch())
        || !Long.valueOf(1L).equals(world.getRowVersion())
        || !Long.valueOf(1L).equals(world.getRunOwnedFixturePreparingLifecycleEpoch())
        || world.getFailureReason() != null
        || world.getTerminationRequestId() != null
        || world.getTerminatedAt() != null
        || !association.runOwnedFixture().preparationInputDigest()
            .equals("sha256:" + world.getRunOwnedFixtureBindingSha256())) {
      throw evidenceMismatch();
    }
    RunOwnedWorldFixedPresetResources.readCommittedAfterActivation(
        dsl,
        plan,
        association.worldInstanceId(),
        association.worldPrivateKeys().gameInstanceId());
  }

  private static void requireRequestMatchesCurrentOwners(
      Request request,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
      RunOwnedWorldLaunchEvidence evidence,
      WorldCanonicalInstanceAssociation association) {
    requireFixtureRequest(request);
    evidence.requireFixedGameSessionPeer();
    var identity = association.identity();
    var fixture = association.runOwnedFixture();
    var descriptor = evidence.launchDescriptor();
    if (!association.isRunOwnedFixture()
        || !request.targetNamespace().equals(identity.targetNamespace())
        || !request.canonicalTenantId().equals(identity.canonicalTenantId())
        || !request.worldSlug().equals(identity.worldSlug())
        || !request.realmId().equals(identity.canonicalRealmId())
        || !request.playableStateNamespaceId().equals(identity.playableStateNamespaceId())
        || !request.playableStateScope().equals(identity.playableStateScope())
        || !request.canonicalGameInstanceId().equals(identity.canonicalGameInstanceId())
        || !request.canonicalVersionId().equals(association.canonicalVersionId())
        || request.expectedCatalogRevision() != identity.catalogRevision()
        || request.activeLifecycleEpoch() != 2L
        || !request.fixturePolicySealEvidenceDigest().equals(fixture.fixtureProvenanceDigest())
        || !lifecycleRequest.targetNamespace().equals(identity.targetNamespace())
        || !lifecycleRequest.canonicalTenantId().equals(identity.canonicalTenantId())
        || !lifecycleRequest.worldSlug().equals(identity.worldSlug())
        || !lifecycleRequest.canonicalGameInstanceId().equals(identity.canonicalGameInstanceId())
        || !lifecycleRequest.playableStateNamespaceId().equals(identity.playableStateNamespaceId())
        || !lifecycleRequest.playableStateScope().equals(identity.playableStateScope())
        || !lifecycleRequest.publicProduction()
        || !lifecycleRequest.controlPlaneRequestId().equals(evidence.capability().operationId())
        || !lifecycleRequest.canonicalVersionId().equals(association.canonicalVersionId())
        || !lifecycleRequest.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
        || !lifecycleRequest.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
        || lifecycleRequest.authorityKind()
            != WorldCanonicalInitialAdmissionHold.AuthorityKind.RUN_OWNED_FIXTURE
        || !request.fixturePolicySealEvidenceDigest()
            .equals(lifecycleRequest.fixturePolicySealEvidenceDigest())
        || !fixture.fixtureOperationId().equals(UUID.fromString(evidence.capability().operationId()))) {
      throw evidenceMismatch();
    }
  }

  private static void requireIdentityMatchesCurrentEvidence(
      HoldIdentity identity, RunOwnedWorldLaunchEvidence evidence) {
    var request = identity.request();
    var gs = evidence.gameSessionAssociation();
    if (gs == null
        || !request.targetNamespace().equals(evidence.sourceReceipt().targetNamespace())
        || !request.canonicalTenantId().equals(gs.canonicalTenantId())
        || !request.canonicalGameInstanceId().equals(gs.gameInstanceUuid())
        || !request.canonicalVersionId().equals(gs.canonicalVersionId())
        || !request.fixturePolicySealEvidenceDigest().equals(evidence.fixtureProvenanceDigest())) {
      throw evidenceMismatch();
    }
  }

  private static void requireSameFixtureAssociation(
      WorldCanonicalInstanceAssociation expected, WorldCanonicalInstanceAssociation actual) {
    if (actual == null
        || !actual.isRunOwnedFixture()
        || expected.worldInstanceId() != actual.worldInstanceId()
        || !expected.identity().equals(actual.identity())
        || !expected.worldPrivateKeys().equals(actual.worldPrivateKeys())
        || !expected.versionIdentity().equals(actual.versionIdentity())
        || !expected.runOwnedFixture().equals(actual.runOwnedFixture())) {
      throw evidenceMismatch();
    }
  }

  private static void requireFixtureRequest(Request request) {
    Objects.requireNonNull(request, "request");
    if (request.authorityKind()
        != WorldCanonicalInitialAdmissionHold.AuthorityKind.RUN_OWNED_FIXTURE) {
      throw new IllegalArgumentException("RUN_OWNED_FIXTURE authority is required for this owner");
    }
  }

  private static Request parseFixtureRequest(byte[] stored) {
    if (stored == null || stored.length == 0) {
      throw new IllegalArgumentException("Canonical World hold request bytes are required");
    }
    Request request = Request.fromStored(stored);
    requireFixtureRequest(request);
    return request;
  }

  private static boolean hasDefinitiveTerminalEvidence(
      HoldIdentity identity, GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    Request request = identity.request();
    if (proof.proofDigest() == null || proof.terminalAt() == null) return false;
    if (proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
      long expectedVersion =
          request.initialAdmissionOrigin()
                  == WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER
              ? 1L
              : request.expectedPriorPointerVersion() + 1L;
      return proof.committedPointerVersion() != null
          && proof.committedPointerVersion() == expectedVersion
          && proof.auditEventId() != null
          && proof.auditEventId() > 0L
          && !proof.positiveDurableAbort();
    }
    return proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED
        && proof.positiveDurableAbort()
        && proof.committedPointerVersion() == null
        && proof.auditEventId() == null;
  }

  private static boolean isTerminal(WorldCanonicalInitialAdmissionHoldState.HoldStatus status) {
    return status == WorldCanonicalInitialAdmissionHoldState.HoldStatus.COMMITTED
        || status == WorldCanonicalInitialAdmissionHoldState.HoldStatus.ABORTED;
  }

  private static String digest(byte[] value) {
    return "sha256:" + digestBare(value);
  }

  private static String digestBare(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("SHA-256 is required by the Java platform", impossible);
    }
  }

  private static IllegalArgumentException evidenceMismatch() {
    return new IllegalArgumentException("RUN_OWNED_WORLD_INITIAL_HOLD_EVIDENCE_MISMATCH");
  }

  private static <T> T required(T value, String message) {
    if (value == null) throw new IllegalStateException(message);
    return value;
  }
}
