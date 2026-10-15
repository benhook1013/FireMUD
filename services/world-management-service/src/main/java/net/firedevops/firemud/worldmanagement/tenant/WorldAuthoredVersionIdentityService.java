package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.StatusRuntimeException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidence;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly unwired World owner step that associates a fresh source-owned Version UUID with a
 * private World identity key.
 *
 * <p>This identity receipt is non-admitting. It does not materialize content or authorize Draft
 * writes, publication, lifecycle preparation, or runtime activation.
 */
public final class WorldAuthoredVersionIdentityService {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final AuthoredWorldVersionStateClient versionStateClient;
  private final WorldAuthoredVersionIdentityRepository repository;
  private final WorldAuthoredSourceIntakeRepository sourceIntakeRepository;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  public WorldAuthoredVersionIdentityService(
      AuthoredWorldVersionStateClient versionStateClient,
      WorldAuthoredVersionIdentityRepository repository,
      WorldAuthoredSourceIntakeRepository sourceIntakeRepository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.versionStateClient = Objects.requireNonNull(versionStateClient, "versionStateClient");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.sourceIntakeRepository =
        Objects.requireNonNull(sourceIntakeRepository, "sourceIntakeRepository");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Associates one source-owned Version selector and canonical UUID with a World-local key.
   *
   * <p>The stable identity is `(namespace, canonical tenant, world, canonical Version UUID)`.
   * `readRequestId` is transport correlation only. Exact retries read the original stored receipt
   * before calling Game Design again.
   */
  public WorldAuthoredVersionIdentityReceipt associate(
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      UUID expectedCanonicalVersionId,
      long gameDesignVersionId,
      UUID readRequestId) {
    requireAuthenticatedGameDesignCaller();
    return associateInternal(
        targetNamespace,
        canonicalTenantId,
        worldSlug,
        sourceOperationId,
        sourceEvidenceDigest,
        expectedCanonicalVersionId,
        gameDesignVersionId,
        readRequestId,
        null,
        null);
  }

  /**
   * Reads and retains the exact canonical Version already committed by the trusted run-owned launch
   * fixture. The typed provider carrier is required; raw selectors cannot use this owner path or
   * bypass the ordinary authenticated Game Design entry point.
   */
  public WorldAuthoredVersionIdentityReceipt associateRunOwnedFixture(
      RunOwnedCanonicalWorldLaunchEvidence fixtureEvidence,
      WorldAuthoredSourceIntakeReceipt sourceReceipt) {
    Objects.requireNonNull(fixtureEvidence, "fixtureEvidence");
    fixtureEvidence.requireFixedGameSessionPeer();
    Objects.requireNonNull(sourceReceipt, "sourceReceipt");
    requireNoAmbientTransaction();
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw new SecurityException(
          "Run-owned fixture Version identity rejects authenticated end-user caller context");
    }

    var trustedSource = fixtureEvidence.sourceReceipt().sourceEvidence();
    var gs = fixtureEvidence.gameSessionAssociation();
    if (gs == null
        || !workloadNamespace.equals(trustedSource.targetNamespace())
        || !sourceReceipt.source().equals(trustedSource)
        || !sourceReceipt.targetNamespace().equals(workloadNamespace)
        || !sourceReceipt.canonicalTenantId().equals(trustedSource.canonicalTenantId())
        || !sourceReceipt.worldSlug().equals(trustedSource.worldSlug())
        || !sourceReceipt.sourceOperationId().equals(trustedSource.operationId())
        || !sourceReceipt.sourceEvidenceDigest().equals(trustedSource.evidenceDigest())
        || !gs.fixturePolicySeal().equals(fixtureEvidence.sourceReceipt().fixturePolicySeal())
        || !gs.canonicalTenantId().equals(trustedSource.canonicalTenantId())
        || !gs.canonicalVersionId()
            .toString()
            .equals(
                fixtureEvidence
                    .sourceReceipt()
                    .launchReadEvidence()
                    .publishedReleaseBundle()
                    .getCanonicalVersionId())
        || fixtureEvidence
                .sourceReceipt()
                .launchReadEvidence()
                .publishedReleaseBundle()
                .getVersionId()
            != fixtureEvidence.launchDescriptor().versionId()) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "Run-owned fixture evidence differs from the exact source, Version, or Game Session owner binding");
    }
    return associateInternal(
        workloadNamespace,
        trustedSource.canonicalTenantId(),
        trustedSource.worldSlug(),
        trustedSource.operationId(),
        trustedSource.evidenceDigest(),
        gs.canonicalVersionId(),
        fixtureEvidence.launchDescriptor().versionId(),
        UUID.randomUUID(),
        sourceReceipt,
        fixtureEvidence);
  }

  private WorldAuthoredVersionIdentityReceipt associateInternal(
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      UUID expectedCanonicalVersionId,
      long gameDesignVersionId,
      UUID readRequestId,
      WorldAuthoredSourceIntakeReceipt expectedSourceReceipt,
      RunOwnedCanonicalWorldLaunchEvidence expectedFixtureEvidence) {
    requireNoAmbientTransaction();
    validateRequest(
        targetNamespace,
        canonicalTenantId,
        worldSlug,
        sourceOperationId,
        sourceEvidenceDigest,
        expectedCanonicalVersionId,
        gameDesignVersionId,
        readRequestId);

    WorldAuthoredSourceIntakeReceipt sourceReceipt =
        readExactSourceIntake(
            canonicalTenantId, worldSlug, sourceOperationId, sourceEvidenceDigest);
    if (expectedSourceReceipt != null && !expectedSourceReceipt.equals(sourceReceipt)) {
      throw new WorldAuthoredVersionIdentityRepository.RegistrationConflictException(
          "Run-owned fixture Version identity differs from the exact committed World source receipt");
    }
    requireFreshReadRequestId(readRequestId, expectedCanonicalVersionId, sourceReceipt);

    Optional<WorldAuthoredVersionIdentityReceipt> prior =
        repository.readByCanonicalVersion(
            workloadNamespace, canonicalTenantId, worldSlug, expectedCanonicalVersionId);
    if (prior.isPresent()) {
      return requireSameIdentity(
          prior.orElseThrow(),
          sourceReceipt,
          expectedCanonicalVersionId,
          gameDesignVersionId,
          expectedFixtureEvidence);
    }
    Optional<WorldAuthoredVersionIdentityReceipt> priorByGameDesignVersion =
        repository.readByGameDesignVersion(
            workloadNamespace, canonicalTenantId, worldSlug, gameDesignVersionId);
    if (priorByGameDesignVersion.isPresent()) {
      return requireSameIdentity(
          priorByGameDesignVersion.orElseThrow(),
          sourceReceipt,
          expectedCanonicalVersionId,
          gameDesignVersionId,
          expectedFixtureEvidence);
    }

    AuthoredWorldVersionStateEvidence.Request currentRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            SCHEMA_VERSION,
            workloadNamespace,
            readRequestId,
            canonicalTenantId,
            worldSlug,
            sourceOperationId,
            sourceEvidenceDigest,
            gameDesignVersionId);
    AuthoredWorldVersionStateEvidence current;
    try {
      current = versionStateClient.read(currentRequest);
    } catch (StatusRuntimeException transportFailure) {
      throw transportFailure;
    } catch (RuntimeException exception) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "Game Design source-qualified Version identity evidence is unavailable or invalid",
          exception);
    }
    if (expectedFixtureEvidence != null) {
      requireFixtureVersionStateMatches(expectedFixtureEvidence, current);
    }
    requireCurrentEvidence(current, currentRequest, sourceReceipt, expectedCanonicalVersionId);

    WorldAuthoredVersionIdentityReceipt accepted;
    try {
      accepted = ownerTransaction.execute(status -> repository.acceptFresh(sourceReceipt, current));
    } catch (RuntimeException ownerFailure) {
      Optional<WorldAuthoredVersionIdentityReceipt> committed =
          repository.readByCanonicalVersion(
              workloadNamespace, canonicalTenantId, worldSlug, expectedCanonicalVersionId);
      if (committed.isPresent()) {
        requireSameSourceIntake(
            sourceReceipt,
            readExactSourceIntake(
                canonicalTenantId, worldSlug, sourceOperationId, sourceEvidenceDigest));
        return requireSameIdentity(
            committed.orElseThrow(),
            sourceReceipt,
            expectedCanonicalVersionId,
            gameDesignVersionId,
            expectedFixtureEvidence);
      }
      Optional<WorldAuthoredVersionIdentityReceipt> claimedSelector =
          repository.readByGameDesignVersion(
              workloadNamespace, canonicalTenantId, worldSlug, gameDesignVersionId);
      if (claimedSelector.isPresent()) {
        requireSameSourceIntake(
            sourceReceipt,
            readExactSourceIntake(
                canonicalTenantId, worldSlug, sourceOperationId, sourceEvidenceDigest));
        return requireSameIdentity(
            claimedSelector.orElseThrow(),
            sourceReceipt,
            expectedCanonicalVersionId,
            gameDesignVersionId,
            expectedFixtureEvidence);
      }
      throw ownerFailure;
    }
    if (accepted == null) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "World owner transaction returned no Version identity receipt");
    }
    requireSameIdentity(
        accepted,
        sourceReceipt,
        expectedCanonicalVersionId,
        gameDesignVersionId,
        expectedFixtureEvidence);

    Optional<WorldAuthoredVersionIdentityReceipt> committed =
        repository.readByCanonicalVersion(
            workloadNamespace, canonicalTenantId, worldSlug, expectedCanonicalVersionId);
    if (committed.isEmpty()) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "World Version identity is missing after its owner commit");
    }
    WorldAuthoredVersionIdentityReceipt readback = committed.orElseThrow();
    requireSameSourceIntake(
        sourceReceipt,
        readExactSourceIntake(
            canonicalTenantId, worldSlug, sourceOperationId, sourceEvidenceDigest));
    requireSameIdentity(
        readback,
        sourceReceipt,
        expectedCanonicalVersionId,
        gameDesignVersionId,
        expectedFixtureEvidence);
    if (!accepted.equals(readback)) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "World Version identity changed during independent commit readback");
    }
    return readback;
  }

  private static void requireFixtureVersionStateMatches(
      RunOwnedCanonicalWorldLaunchEvidence fixtureEvidence,
      AuthoredWorldVersionStateEvidence current) {
    if (current == null) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "Game Design returned no current Version state for the sealed run-owned fixture");
    }
    var expected = fixtureEvidence.sourceReceipt().launchReadEvidence().versionState();
    if (current.versionState() != expected.getVersionState()
        || current.versionStateEpoch() != expected.getVersionStateEpoch()
        || current.request().versionId() != fixtureEvidence.launchDescriptor().versionId()
        || !current
            .canonicalVersionId()
            .toString()
            .equals(
                fixtureEvidence
                    .sourceReceipt()
                    .launchReadEvidence()
                    .publishedReleaseBundle()
                    .getCanonicalVersionId())) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "Current Game Design Version state differs from the exact sealed fixture snapshot");
    }
  }

  private WorldAuthoredVersionIdentityReceipt requireSameIdentity(
      WorldAuthoredVersionIdentityReceipt receipt,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      UUID expectedCanonicalVersionId,
      long gameDesignVersionId,
      RunOwnedCanonicalWorldLaunchEvidence expectedFixtureEvidence) {
    if (!receipt.targetNamespace().equals(workloadNamespace)
        || !receipt.sourceIntakeReceipt().equals(sourceReceipt)
        || !receipt.canonicalTenantId().equals(sourceReceipt.canonicalTenantId())
        || !receipt.worldSlug().equals(sourceReceipt.worldSlug())
        || !receipt.canonicalVersionId().equals(expectedCanonicalVersionId)
        || receipt.gameDesignVersionId() != gameDesignVersionId) {
      throw new WorldAuthoredVersionIdentityRepository.RegistrationConflictException(
          "World Version identity scope, source intake, or selector conflicts with its first claim");
    }
    if (expectedFixtureEvidence != null) {
      requireFixtureVersionStateMatches(expectedFixtureEvidence, receipt.versionStateEvidence());
    }
    return receipt;
  }

  private void requireCurrentEvidence(
      AuthoredWorldVersionStateEvidence evidence,
      AuthoredWorldVersionStateEvidence.Request expectedRequest,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      UUID expectedCanonicalVersionId) {
    if (evidence == null) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "Game Design returned no source-qualified Version identity evidence");
    }
    try {
      evidence.requireValid();
    } catch (RuntimeException exception) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "Game Design source-qualified Version identity evidence is invalid", exception);
    }
    if (!expectedRequest.equals(evidence.request())
        || !sourceReceipt.source().equals(evidence.sourceEvidence())
        || !expectedCanonicalVersionId.equals(evidence.canonicalVersionId())) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "Game Design Version identity evidence differs from the exact source and expected UUID");
    }
    VersionLifecycleState state = evidence.versionState();
    if (state != VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT
        && state != VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED
        && state != VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE) {
      throw new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
          "Game Design Version identity evidence must be DRAFT, PUBLISHED, or ACTIVE");
    }
  }

  private void requireExactFreshSourceIntake(
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest) {
    if (!workloadNamespace.equals(sourceReceipt.targetNamespace())
        || !canonicalTenantId.equals(sourceReceipt.canonicalTenantId())
        || !worldSlug.equals(sourceReceipt.worldSlug())
        || !sourceOperationId.equals(sourceReceipt.sourceOperationId())
        || !sourceEvidenceDigest.equals(sourceReceipt.sourceEvidenceDigest())
        || !"NEW_GAME_ROW".equals(sourceReceipt.source().provenanceKind())) {
      throw new WorldAuthoredVersionIdentityRepository.RegistrationConflictException(
          "World Version identity requires the exact fresh NEW_GAME_ROW source intake");
    }
  }

  private WorldAuthoredSourceIntakeReceipt readExactSourceIntake(
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest) {
    WorldAuthoredSourceIntakeReceipt sourceReceipt =
        sourceIntakeRepository
            .readBySource(
                workloadNamespace,
                canonicalTenantId,
                worldSlug,
                sourceOperationId,
                sourceEvidenceDigest)
            .orElseThrow(
                () ->
                    new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
                        "World Version identity requires an exact committed fresh source intake"));
    requireExactFreshSourceIntake(
        sourceReceipt, canonicalTenantId, worldSlug, sourceOperationId, sourceEvidenceDigest);
    return sourceReceipt;
  }

  private static void requireSameSourceIntake(
      WorldAuthoredSourceIntakeReceipt expected, WorldAuthoredSourceIntakeReceipt actual) {
    if (!expected.equals(actual)) {
      throw new WorldAuthoredVersionIdentityRepository.RegistrationConflictException(
          "World source intake changed during Version identity readback");
    }
  }

  private void requireAuthenticatedGameDesignCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("game-design-service")
        || !peer.isInNamespace(workloadNamespace)) {
      throw new SecurityException(
          "World Version identity requires the authenticated same-namespace Game Design workload");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World Version identity cannot enter from an ambient transaction");
    }
  }

  private void validateRequest(
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      UUID expectedCanonicalVersionId,
      long gameDesignVersionId,
      UUID readRequestId) {
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new SecurityException("World Version identity namespace does not match this workload");
    }
    AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
    requireNonNil(sourceOperationId, "sourceOperationId");
    requireNonNil(expectedCanonicalVersionId, "expectedCanonicalVersionId");
    requireNonNil(readRequestId, "readRequestId");
    if (!GameTenantCreationDigest.isDigest(sourceEvidenceDigest)) {
      throw new IllegalArgumentException("sourceEvidenceDigest must be a canonical SHA-256 digest");
    }
    if (gameDesignVersionId <= 0) {
      throw new IllegalArgumentException("gameDesignVersionId must be positive");
    }
  }

  private static void requireFreshReadRequestId(
      UUID readRequestId,
      UUID expectedCanonicalVersionId,
      WorldAuthoredSourceIntakeReceipt sourceReceipt) {
    if (readRequestId.equals(expectedCanonicalVersionId)
        || readRequestId.equals(sourceReceipt.operationId())
        || readRequestId.equals(sourceReceipt.intakeRequestId())
        || readRequestId.equals(sourceReceipt.sourceOperationId())
        || readRequestId.equals(sourceReceipt.source().registrationRequestId())) {
      throw new IllegalArgumentException(
          "Version-state readRequestId must be fresh and separate from source identities");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || value.equals(NIL_UUID)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }
}
