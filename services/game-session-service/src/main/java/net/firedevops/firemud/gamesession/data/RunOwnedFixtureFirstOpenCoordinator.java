package net.firedevops.firemud.gamesession.data;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.RunOwnedFixtureRuntimeCompletionRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives the canonical run-owned Compose fixture from its verified source receipt to its first
 * OPEN pointer: Game Session STARTING launch, World prepare and activate, the intent and World
 * initial-admission hold, the PENDING attempt, World verification, STARTING to RUNNING, and the
 * pointer commit.
 *
 * <p>Every step is an existing owner's exact, replay-safe operation, so a restart resumes from
 * the retained state. Remote calls run outside SQL transactions; each Game Session write uses its
 * own READ COMMITTED transaction. The fixture carries its policy-seal evidence in place of a Human
 * Account StartSession tuple and never grants player admission.
 */
public final class RunOwnedFixtureFirstOpenCoordinator {
  private final RunOwnedCanonicalLaunchPreparationOwner launchOwner;
  private final WorldManagementClient world;
  private final CanonicalGameInstanceLaunchAssociationRepository launches;
  private final CanonicalInitialAdmissionIntentRepository intents;
  private final WorldCanonicalInitialAdmissionHoldClient holds;
  private final CanonicalInitialAdmissionRepository admissions;
  private final RunOwnedFixtureRuntimeCompletionRepository completions;
  private final CanonicalInitialAdmissionWorldVerifier verifier;
  private final TransactionTemplate writeTransaction;
  private final String workloadNamespace;

  public RunOwnedFixtureFirstOpenCoordinator(
      RunOwnedCanonicalLaunchPreparationOwner launchOwner,
      WorldManagementClient world,
      CanonicalGameInstanceLaunchAssociationRepository launches,
      CanonicalInitialAdmissionIntentRepository intents,
      WorldCanonicalInitialAdmissionHoldClient holds,
      CanonicalInitialAdmissionRepository admissions,
      RunOwnedFixtureRuntimeCompletionRepository completions,
      CanonicalInitialAdmissionWorldVerifier verifier,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.launchOwner = Objects.requireNonNull(launchOwner, "launchOwner");
    this.world = Objects.requireNonNull(world, "world");
    this.launches = Objects.requireNonNull(launches, "launches");
    this.intents = Objects.requireNonNull(intents, "intents");
    this.holds = Objects.requireNonNull(holds, "holds");
    this.admissions = Objects.requireNonNull(admissions, "admissions");
    this.completions = Objects.requireNonNull(completions, "completions");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    writeTransaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Runs or resumes the fixture to its committed first OPEN and returns the owner proof. */
  public CanonicalInitialAdmissionOwnerProof openFirstRealm() {
    requireNoAmbientTransaction();
    var prepared = launchOwner.prepareFixedVerifiedFixtureLaunch();
    var launch = prepared.launch();
    var catalog = launch.catalog().snapshot();
    long gameSessionTenantId = launch.catalog().association().gameSessionTenantId();
    String requestId =
        "run-owned-fixture-" + prepared.launchEvidence().fixtureCapability().operationId();

    Optional<CanonicalInitialAdmissionOwnerProof> retained =
        admissions.read(workloadNamespace, requestId);
    if (retained.isPresent()
        && retained.orElseThrow().outcome() != CanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      return retained.orElseThrow();
    }

    long activeEpoch =
        activateWorld(
            gameSessionTenantId,
            launch.gameInstanceId(),
            prepared.launchEvidence().gameTemplateId(),
            prepared.launchEvidence().launchDescriptor());

    CanonicalGameInstanceLaunchAssociation association =
        launches
            .readForInitialAdmission(
                workloadNamespace, catalog.tenantId(), catalog.realmId(), launch.gameInstanceUuid())
            .orElseThrow(
                () -> new IllegalStateException("Fixture launch association is unavailable"))
            .association();
    if (!association.isRunOwnedFixture()) {
      throw new IllegalStateException("Fixture coordinator found a Human launch association");
    }
    UUID canonicalVersionId = association.canonicalVersionId();
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            workloadNamespace,
            catalog.tenantId(),
            catalog.worldSlug(),
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            catalog.stateScope(),
            launch.gameInstanceUuid(),
            canonicalVersionId,
            activeEpoch,
            catalog.catalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            requestId);
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            workloadNamespace,
            catalog.tenantId(),
            catalog.worldSlug(),
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            catalog.stateScope(),
            launch.gameInstanceUuid(),
            canonicalVersionId,
            activeEpoch,
            requestId,
            requestDigest,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            catalog.catalogRevision(),
            null);
    var lifecycleRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            UUID.randomUUID(),
            workloadNamespace,
            catalog.tenantId(),
            catalog.worldSlug(),
            launch.gameInstanceUuid(),
            catalog.playableStateNamespaceId(),
            catalog.stateScope(),
            true,
            association.controlPlaneRequestId(),
            canonicalVersionId,
            association.descriptor().requestDigest(),
            association.descriptor().resultDigest(),
            association.attestationDigest());

    write(() -> intents.reserveFixtureStarting(holdRequest, lifecycleRequest));
    requireNoAmbientTransaction();
    HoldIdentity identity = holds.acquire(holdRequest, lifecycleRequest);
    write(() -> intents.attachFixtureStarting(holdRequest, identity));

    var request =
        new CanonicalInitialAdmissionRequest(
            workloadNamespace,
            catalog.tenantId(),
            catalog.worldSlug(),
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            catalog.stateScope(),
            launch.gameInstanceUuid(),
            canonicalVersionId,
            activeEpoch,
            catalog.catalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            requestId,
            requestDigest,
            identity.holdId(),
            identity.holdFence(),
            identity.holdBindingDigest());
    write(() -> admissions.reserveFixtureStarting(request));
    requireNoAmbientTransaction();
    CanonicalInitialAdmissionWorldProof worldProof = verifier.verify(request);
    write(() -> completions.complete(request, worldProof));
    write(() -> admissions.commitFixture(request, worldProof));

    CanonicalInitialAdmissionOwnerProof committed =
        admissions
            .read(workloadNamespace, requestId)
            .orElseThrow(() -> new IllegalStateException("Fixture first OPEN was not retained"));
    if (committed.outcome() != CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
      throw new IllegalStateException("Fixture first OPEN did not commit");
    }
    return committed;
  }

  /** Prepares and activates the fixture World instance, returning its ACTIVE lifecycle epoch. */
  private long activateWorld(
      long gameSessionTenantId,
      long gameInstanceId,
      long gameTemplateId,
      LaunchDescriptor descriptor) {
    var prepared =
        world.prepareWorldInstance(
            gameSessionTenantId,
            gameInstanceId,
            gameTemplateId,
            descriptor.getControlPlaneRequestId(),
            descriptor.getLaunchDescriptorId(),
            descriptor.getVersionId(),
            descriptor.getScriptPatchVersion(),
            descriptor.getRuntimeFlagsJson(),
            descriptor.getGenerationConfigRevision(),
            descriptor.getReleaseBundleId(),
            descriptor.getPublishedReleaseBundleRef(),
            descriptor.getVersionStateEpoch(),
            descriptor.getRemapSetId());
    if (prepared.hasError() || !prepared.hasWorldInstance()) {
      throw new IllegalStateException(
          "Fixture World preparation failed: "
              + (prepared.hasError() ? prepared.getError().getCode() : "no World snapshot"));
    }
    WorldInstanceLifecycleSnapshot snapshot = prepared.getWorldInstance();
    if (snapshot.getStatus()
        == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING) {
      var activated =
          world.activatePreparedWorldInstance(
              gameSessionTenantId, gameInstanceId, snapshot.getLifecycleEpoch());
      if (activated.hasError() || !activated.hasWorldInstance()) {
        throw new IllegalStateException(
            "Fixture World activation failed: "
                + (activated.hasError() ? activated.getError().getCode() : "no World snapshot"));
      }
      snapshot = activated.getWorldInstance();
    }
    if (snapshot.getStatus()
        != WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE) {
      throw new IllegalStateException("Fixture World instance is not ACTIVE");
    }
    return snapshot.getLifecycleEpoch();
  }

  private <T> void write(Supplier<T> owner) {
    writeTransaction.execute(status -> owner.get());
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Fixture first OPEN coordination requires no ambient owner transaction");
    }
  }
}
