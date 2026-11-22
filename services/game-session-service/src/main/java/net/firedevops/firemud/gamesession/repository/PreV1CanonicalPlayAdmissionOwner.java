package net.firedevops.firemud.gamesession.repository;

import io.grpc.Status;
import io.lettuce.core.RedisURI;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.client.AccountPlayerAdmissionEntitlementClient;
import net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.data.PreV1CanonicalPlayBootstrapDriver;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyScope;
import org.jooq.DSLContext;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;

/** Composes ordinary NEW admission for the verified run-owned fixture. */
public final class PreV1CanonicalPlayAdmissionOwner {
  private static final Logger LOG = LoggerFactory.getLogger(PreV1CanonicalPlayAdmissionOwner.class);
  private static final Duration SESSION_DURATION = Duration.ofMinutes(2);

  private final DSLContext dsl;
  private final PreV1CanonicalPlayStandIns standIns;
  private final PreV1RunOwnedFixturePlayRegion regions;
  private final RunOwnedCanonicalLaunchPreparationOwner fixture;
  private final PreV1CanonicalPlayBootstrapDriver bootstrap;
  private final AccountPlayerAdmissionEntitlementClient entitlements;
  private final AccountGameplayAdmissionLeaseClient leases;
  private final AccountTenantCapacityClient capacity;
  private final AccountPublicJwksCache jwks;
  private final Clock clock;

  public PreV1CanonicalPlayAdmissionOwner(
      DSLContext dsl,
      PreV1CanonicalPlayStandIns standIns,
      RedisURI coordinationUri,
      CanonicalGameInstanceLaunchAssociationRepository launches,
      WorldCanonicalInstanceLifecycleClient world,
      RuntimeIdentity runtimeIdentity,
      RunOwnedCanonicalLaunchPreparationOwner fixture,
      PreV1CanonicalPlayBootstrapDriver bootstrap,
      AccountPlayerAdmissionEntitlementClient entitlements,
      AccountGameplayAdmissionLeaseClient leases,
      AccountTenantCapacityClient capacity,
      AccountPublicJwksCache jwks,
      Clock clock) {
    this.dsl = dsl;
    this.standIns = standIns;
    this.regions =
        new PreV1RunOwnedFixturePlayRegion(
            dsl, standIns, coordinationUri, launches, world, runtimeIdentity, clock);
    this.fixture = fixture;
    this.bootstrap = bootstrap;
    this.entitlements = entitlements;
    this.leases = leases;
    this.capacity = capacity;
    this.jwks = jwks;
    this.clock = clock;
  }

  public boolean ready() {
    return bootstrap.ready();
  }

  public Admitted play(
      DirectTextAuthenticatedLobbyScope scope,
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult login,
      ObservedDirectTextTransport transport,
      BooleanSupplier current) {
    if (!ready()) throw unavailable();
    CanonicalPlayableTarget target = scope.publicTarget();
    UUID accountId = login.evidence().accountId();
    UUID attempt = UUID.randomUUID();
    requireCurrent(scope, original, login, transport, current);
    String phase = "fixture-region";
    try {
      fixture.loadFixedVerifiedEvidence().requireVerifiedFixture();
      var region = regions.prepare(target, accountId);
      phase = "account-entitlement";
      var entitlement = entitlements.read(scope, original, transport, attempt, login, current);
      var capacityEvidence = TenantCapacityAdmission.Evidence.player(entitlement);
      if (!entitlement.isRunOwnedFixtureSource() || !capacityEvidence.permitsCreation()) {
        throw unavailable();
      }

      phase = "issuer-local-new";
      var identity =
          new CanonicalGameplayBindingIdentity(
              accountId,
              target.canonicalTenantId(),
              target.playableStateNamespaceId(),
              region.characterId(),
              target.playableStateScope(),
              target.canonicalGameInstanceId(),
              target.gameInstanceId(),
              UUID.randomUUID().toString(),
              region.operationalRegionId(),
              BigInteger.valueOf(region.regionEpoch()),
              PreV1CurrentIssuerStateSource.ISSUER,
              PreV1CurrentIssuerStateSource.INITIAL_GENERATION,
              PreV1CanonicalGameplayStoreBootstrap.LAYOUT_VERSION,
              PreV1CanonicalGameplayStoreBootstrap.PARTITION_COUNT,
              PreV1CanonicalGameplayStoreBootstrap.PARTITION_CAPACITY);
      UUID transitionId = UUID.randomUUID();
      var issuerSource = standIns.issuerState();
      var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
      var operation =
          new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, issuerSource)
              .beginOrResumeOrdinaryNew(
                  new CanonicalGameplayIssuerBindingLocalOperation.Request(
                      attempt, transitionId, identity, null, null));
      BigInteger generation = operation.preparedTransition().bindingGeneration();

      phase = "initial-account-barrier";
      var initialRepository = new CanonicalGameplayAccountInitialBarrierInitializerRepository(dsl);
      var accountBarrier =
          new CanonicalGameplayAccountInitialBarrierRedisOwner(
              standIns.coordination(),
              standIns.scripts(),
              initialRepository,
              standIns.tags(),
              standIns
                  .attestor()
                  .initialBarrierReceiver(initialRepository, standIns.tags(), clock));
      requireCurrent(scope, original, login, transport, current);
      if (!(accountBarrier.initializeOnce(
              original, login, target.canonicalTenantId(), target.playableStateNamespaceId())
          instanceof CanonicalGameplayAccountInitialOpenProof.CurrentOpen)) {
        throw unavailable();
      }

      phase = "issuer-capacity-reservation";
      var issuerProjection =
          new CanonicalGameplayIssuerBindingLocalRedisOwner(
              standIns.coordination(),
              standIns.scripts(),
              new CanonicalGameplayIssuerBindingLocalProjectionRepository(dsl, issuerSource),
              issuerSource);
      issuerProjection.projectAndRead(operation);

      phase = "account-admission-lease";
      byte[] compact = login.compactJwtBytes();
      try {
        String jwt = new String(compact, StandardCharsets.US_ASCII);
        requireCurrent(scope, original, login, transport, current);
        var lease =
            leases
                .acquire(
                    attempt,
                    jwt,
                    wireCandidate(identity),
                    target.admissionDecisionTarget(),
                    generation)
                .originalLeaseEvidence();
        var decision =
            new CanonicalGameplayAdmissionDecision(
                transitionId, lease, identity, generation, null, target);

        phase = "candidate-install";
        new CanonicalGameplayOriginalNewCandidateRedisOwner(
                standIns.coordination(), standIns.scripts(), inventory, standIns.tags())
            .installOnce(decision);
        phase = "account-index-repair";
        new CanonicalGameplayAccountMemberRepairOwner(
                dsl, standIns.coordination(), standIns.scripts())
            .repair(transitionId);
        phase = "issuer-index-ack";
        var projection = issuerProjection.projectAndRead(operation);
        new CanonicalGameplayIssuerBindingLocalAcknowledgementRepository(dsl, issuerSource)
            .acknowledge(projection);

        phase = "region-bridge";
        var bridge = region.bridge();
        var installation = bridge.installForTransition(transitionId, region.request());
        bridge.bridge(installation);

        phase = "account-lease-finalization";
        var capacityRequest =
            new TenantCapacityAdmission.Request(
                    new TenantCapacityAdmission.Identity(
                        identity.tenantId(), attempt, transitionId),
                    UUID.randomUUID(),
                    1,
                    target.targetNamespace(),
                    new TenantCapacityAdmission.PlayerContext(
                        accountId,
                        identity.tenantId(),
                        identity.playableStateNamespaceId(),
                        identity.gameInstanceId(),
                        identity.characterId(),
                        UUID.fromString(identity.sessionId()),
                        target.realmId(),
                        attempt,
                        identity.playableStateScope()),
                    TenantCapacityAdmission.Operation.CAPACITY_CREATING,
                    new TenantCapacityAdmission.Shape(0, 0, 0),
                    new TenantCapacityAdmission.Shape(1, 0, 0),
                    capacityEvidence,
                    1L,
                    null)
                .withDigest();
        byte[] leaseBytes = lease.canonicalJson().getBytes(StandardCharsets.UTF_8);
        requireCurrent(scope, original, login, transport, current);
        var finalized = leases.finalizeLease(jwt, leaseBytes, transitionId);
        if (finalized.state() != AccountGameplayAdmissionLeaseGrpcCodec.State.COMMITTED
            || !finalized.originalLeaseEvidence().canonicalJson().equals(lease.canonicalJson())) {
          throw unavailable();
        }

        phase = "original-new-final";
        var issuerBarrier =
            new CanonicalGameplayIssuerAdmissionBarrierRedisOwner(
                standIns.coordination(),
                standIns.scripts(),
                new CanonicalGameplayIssuerAdmissionBarrierRepository(
                    dsl, standIns.tags(), issuerSource),
                issuerSource);
        var coordinator =
            new CanonicalGameplayOriginalNewFinalAdmissionCoordinator(
                inventory,
                leases,
                accountBarrier,
                issuerBarrier,
                bridge,
                new CanonicalGameplayOriginalNewBoundCredentialVerifier(jwks, clock),
                new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                    standIns.coordination(), standIns.scripts(), clock, standIns.tags()),
                clock,
                capacity,
                new CanonicalGameplayOriginalNewCapacityExecutionOwner(
                    dsl, standIns.coordination(), standIns.scripts(), clock, standIns.tags()));
        var result =
            coordinator.completeOriginalNew(
                decision,
                jwt,
                installation,
                SESSION_DURATION.toMillis(),
                Math.min(
                    clock.millis() + 5_000L, decision.leaseExpiresAtEpochMillis().longValueExact()),
                capacityRequest);
        requireCurrent(scope, original, login, transport, current);
        return new Admitted(
            target,
            identity,
            transitionId,
            generation,
            region.roomKey(),
            result.continuityExpiresAtMillis());
      } finally {
        Arrays.fill(compact, (byte) 0);
      }
    } catch (RuntimeException failure) {
      Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
      StackTraceElement[] trace = cause.getStackTrace();
      PSQLException postgres = cause instanceof PSQLException sql ? sql : null;
      var databaseError = postgres == null ? null : postgres.getServerErrorMessage();
      LOG.warn(
          "Run-owned PLAY unavailable phase={} type={} grpcStatus={} sqlState={} constraint={} source={}",
          phase,
          cause.getClass().getSimpleName(),
          Status.fromThrowable(failure).getCode(),
          postgres == null ? null : postgres.getSQLState(),
          databaseError == null ? null : databaseError.getConstraint(),
          trace.length == 0 ? "unknown" : trace[0]);
      throw unavailable();
    }
  }

  private void requireCurrent(
      DirectTextAuthenticatedLobbyScope scope,
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult login,
      ObservedDirectTextTransport transport,
      BooleanSupplier current) {
    if (!current.getAsBoolean()) throw unavailable();
    scope.requireCurrent(original, login, transport, clock);
  }

  private static Candidate wireCandidate(CanonicalGameplayBindingIdentity identity) {
    return new Candidate(
        identity.accountId(),
        identity.tenantId(),
        identity.playableStateNamespaceId(),
        identity.characterId(),
        identity.playableStateScope(),
        identity.gameInstanceId(),
        identity.runtimeGameInstanceId(),
        UUID.fromString(identity.sessionId()),
        identity.regionId(),
        identity.regionEpoch(),
        identity.issuerId(),
        identity.issuerAuthGeneration(),
        identity.issuerIndexLayoutVersion(),
        identity.issuerIndexPartitionCount(),
        identity.issuerIndexPartitionCapacity());
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Run-owned canonical PLAY unavailable");
  }

  public record Admitted(
      CanonicalPlayableTarget target,
      CanonicalGameplayBindingIdentity binding,
      UUID transitionId,
      BigInteger bindingGeneration,
      String roomKey,
      long expiresAtMillis) {}
}
