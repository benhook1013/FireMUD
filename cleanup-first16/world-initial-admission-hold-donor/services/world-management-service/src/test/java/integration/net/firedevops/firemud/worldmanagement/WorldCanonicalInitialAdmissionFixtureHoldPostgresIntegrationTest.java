package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.AuthorityKind;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.client.WorldCanonicalInitialAdmissionGameSessionProofClient;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldFixtureEvidenceTestFixtures;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider.GameSessionOwnerProofClientPins;
import net.firedevops.firemud.worldmanagement.service.WorldCanonicalInitialAdmissionHoldOwner;
import net.firedevops.firemud.worldmanagement.service.impl.RunOwnedWorldFixtureLifecycleTestFactory;
import net.firedevops.firemud.worldmanagement.service.impl.WorldLifecycleCommandServiceImpl;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceFixtureActivationRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Native PostgreSQL hold-owner proof. Source/descriptor, provider pins, peer/certificate context
 * and Game Session terminal replies are controlled inputs; World association, V1043 ACTIVE state,
 * preset, hold insert, guards, reconciliation, and terminal SQL functions are the actual owners.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = {"spring.grpc.server.port=0"})
class WorldCanonicalInitialAdmissionFixtureHoldPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GrpcGameSessionInitialAdmissionBindProofClient legacyProofClient;
  @MockitoBean private WorldCanonicalInitialAdmissionGameSessionProofClient fixtureProofClient;

  @Test
  void actualFixtureHoldReplaysFencesSubstitutionAndFinalizesOnlyExactGsProof() {
    RunOwnedWorldLaunchEvidence evidence =
        RunOwnedWorldFixtureEvidenceTestFixtures.create(objectMapper);
    var provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence())
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return evidence;
            });
    var pins = mock(GameSessionOwnerProofClientPins.class);
    when(pins.gameSessionUriSan())
        .thenReturn("spiffe://firemud/ns/dev/sa/game-session-service");
    when(pins.gameSessionLeafSha256()).thenReturn("a".repeat(64));
    doAnswer(
            invocation -> {
              var peer = GrpcPeerIdentity.current();
              var certificate = GrpcPeerCertificateEvidence.current();
              if (SessionContext.hasAuthenticatedCallerContext()
                  || peer == null
                  || certificate == null
                  || !peer.isService("game-session-service")
                  || !peer.isInNamespace("dev")
                  || !"spiffe://firemud/ns/dev/sa/game-session-service".equals(peer.uri())
                  || !"a".repeat(64).equals(certificate.leafSha256())) {
                throw new SecurityException("Pinned Game Session peer required");
              }
              return null;
            })
        .when(pins)
        .requireCurrentGameSessionPeer();
    when(provider.requireGameSessionOwnerProofClientPins()).thenReturn(pins);
    WorldLifecycleCommandServiceImpl lifecycle =
        nativeLifecycleService(evidence, provider);
    WorldCanonicalInstanceAssociationRepository associations = associationRepository();
    var active =
        withControlledGameSessionPeer(
            () -> {
              RunOwnedWorldFixtureLifecycleTestFactory.prepare(lifecycle, evidence);
              var association =
                  associations.readRunOwnedFixtureAssociation(
                      evidence, evidence.originalGameSessionRequest());
              var activation =
                  new WorldCanonicalInstanceFixtureActivationRequest(
                      UUID.randomUUID(),
                      association.identity().canonicalTenantId(),
                      association.identity().canonicalGameInstanceId(),
                      association.runOwnedFixture().fixtureProvenanceDigest(),
                      1L,
                      0L);
              assertThat(
                      RunOwnedWorldFixtureLifecycleTestFactory.activate(lifecycle, activation)
                          .lifecycleEvidence()
                          .lifecycleEpoch())
                  .isEqualTo(2L);
              return association;
            });
    var association = active;
    var holdRequest = holdRequest(association);
    var lifecycleRead = lifecycleRead(evidence, association, UUID.randomUUID());
    var holdOwner =
        RunOwnedWorldFixtureLifecycleTestFactory.createHoldOwner(
            dsl, transactionManager, provider, fixtureProofClient);

    var beforeWorld =
        dsl.fetchSingle(
            "SELECT to_jsonb(w)::text || ':xmin=' || w.xmin::text AS value "
                + "FROM world_instance w WHERE id=?",
            association.worldInstanceId())
            .get("value", String.class);
    var wrongSeal =
        new Request(
            holdRequest.targetNamespace(),
            holdRequest.canonicalTenantId(),
            holdRequest.worldSlug(),
            holdRequest.realmId(),
            holdRequest.playableStateNamespaceId(),
            holdRequest.playableStateScope(),
            holdRequest.canonicalGameInstanceId(),
            holdRequest.canonicalVersionId(),
            holdRequest.activeLifecycleEpoch(),
            holdRequest.initialAdmissionRequestId(),
            holdRequest.initialAdmissionRequestDigest(),
            holdRequest.initialAdmissionOrigin(),
            holdRequest.expectedCatalogRevision(),
            holdRequest.expectedPriorPointerVersion(),
            AuthorityKind.RUN_OWNED_FIXTURE,
            "sha256:" + "e".repeat(64));
    assertThatThrownBy(
            () ->
                withControlledGameSessionPeer(
                    () ->
                        holdOwner.acquire(
                            wrongSeal.canonicalRequestBytes(), lifecycleRead.canonicalBytes())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RUN_OWNED_WORLD_INITIAL_HOLD_EVIDENCE_MISMATCH");
    assertThat(dsl.fetchSingle("SELECT count(*) FROM initial_admission_bind_hold").get(0, Long.class))
        .isZero();

    var identity =
        withControlledGameSessionPeer(
            () -> holdOwner.acquire(holdRequest.canonicalRequestBytes(), lifecycleRead.canonicalBytes()));
    assertThat(identity.request()).isEqualTo(holdRequest);
    assertThat(
            withControlledGameSessionPeer(
                () -> holdOwner.acquire(holdRequest.canonicalRequestBytes(), lifecycleRead.canonicalBytes())))
        .isEqualTo(identity);
    assertThatThrownBy(() -> holdOwner.readIdentity(holdRequest.canonicalRequestBytes()))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withControlledPeer(
                    "world-management-service",
                    "a".repeat(64),
                    () -> holdOwner.readIdentity(holdRequest.canonicalRequestBytes())))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withControlledPeer(
                    "game-session-service",
                    "b".repeat(64),
                    () -> holdOwner.readIdentity(holdRequest.canonicalRequestBytes())))
        .isInstanceOf(SecurityException.class);
    assertThat(
            withControlledGameSessionPeer(
                () -> holdOwner.readIdentity(holdRequest.canonicalRequestBytes())))
        .isEqualTo(identity);
    verify(provider).requireGameSessionOwnerProofClientPins();

    var state =
        withControlledGameSessionPeer(
            () -> holdOwner.readState(identity.canonicalBytes(), lifecycleRead.canonicalBytes()));
    assertThat(state.holdStatus()).isEqualTo(WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING);
    assertThat(state.lifecycleEvidence().lifecycleStatus()).isEqualTo("ACTIVE");
    assertThat(state.lifecycleEvidence().lifecycleEpoch()).isEqualTo(2L);
    assertThat(state.lifecycleEvidence().rowVersion()).isEqualTo(1L);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE initial_admission_bind_hold SET status='COMMITTED' WHERE hold_id=?::uuid",
                    identity.holdId()))
        .isInstanceOf(RuntimeException.class);
    assertThat(holdStatus(identity.holdId())).isEqualTo("PENDING");

    var borrowedIdentity =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            identity.request(), identity.holdId(), UUID.randomUUID());
    when(fixtureProofClient.readOwnerProof(identity, pins))
        .thenReturn(pendingProof(borrowedIdentity));
    assertThat(holdOwner.reconcileHold(identity.holdId())).isEqualTo("GS_OWNER_PROOF_MISMATCH");
    assertThat(holdStatus(identity.holdId())).isEqualTo("RECONCILIATION_REQUIRED");

    when(fixtureProofClient.readOwnerProof(identity, pins)).thenReturn(pendingProof(identity));
    assertThat(holdOwner.reconcileHold(identity.holdId())).isEqualTo("GS_OWNER_PENDING");
    assertThat(holdStatus(identity.holdId())).isEqualTo("RECONCILIATION_REQUIRED");

    var committedProof = committedProof(identity);
    when(fixtureProofClient.readOwnerProof(identity, pins)).thenReturn(committedProof);
    assertThat(holdOwner.reconcileHold(identity.holdId())).isEqualTo("none");
    assertThat(holdStatus(identity.holdId())).isEqualTo("COMMITTED");
    assertThat(
            dsl.fetchSingle(
                    "SELECT canonical_owner_proof_bytes FROM initial_admission_bind_hold WHERE hold_id=?::uuid",
                    identity.holdId())
                .get("canonical_owner_proof_bytes", byte[].class))
        .isNotEmpty();
    var terminalState =
        withControlledGameSessionPeer(
            () -> holdOwner.readState(identity.canonicalBytes(), lifecycleRead.canonicalBytes()));
    assertThat(terminalState.holdStatus())
        .isEqualTo(WorldCanonicalInitialAdmissionHoldState.HoldStatus.COMMITTED);
    assertThat(
            dsl.fetchSingle(
                    "SELECT to_jsonb(w)::text || ':xmin=' || w.xmin::text AS value "
                        + "FROM world_instance w WHERE id=?",
                    association.worldInstanceId())
                .get("value", String.class))
        .isEqualTo(beforeWorld);
  }

  private WorldLifecycleCommandServiceImpl nativeLifecycleService(
      RunOwnedWorldLaunchEvidence evidence, RunOwnedWorldLaunchEvidenceProvider provider) {
    var sources = mock(AuthoredWorldSourceClient.class);
    when(sources.read(any())).thenReturn(evidence.sourceReceipt().sourceEvidence());
    var versions = mock(AuthoredWorldVersionStateClient.class);
    when(versions.read(any()))
        .thenAnswer(
            invocation -> {
              var request = invocation.<net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence.Request>getArgument(0);
              return net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence.create(
                  request,
                  evidence.sourceReceipt().sourceEvidence(),
                  evidence.gameSessionAssociation().canonicalVersionId(),
                  net.firedevops.firemud.gamedesign.v1.VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                  evidence.launchDescriptor().versionStateEpoch());
            });
    return RunOwnedWorldFixtureLifecycleTestFactory.create(
        dsl,
        transactionManager,
        provider,
        sources,
        versions,
        gameDesignClient,
        entityManagementClient);
  }

  private WorldCanonicalInstanceAssociationRepository associationRepository() {
    var source = new WorldAuthoredSourceIntakeRepository(dsl);
    return new WorldCanonicalInstanceAssociationRepository(
        dsl,
        new WorldCompleteLaunchBindingRepository(dsl),
        source,
        new WorldAuthoredVersionIdentityRepository(dsl));
  }

  private static Request holdRequest(
      net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation association) {
    return new Request(
        association.identity().targetNamespace(),
        association.identity().canonicalTenantId(),
        association.identity().worldSlug(),
        association.identity().canonicalRealmId(),
        association.identity().playableStateNamespaceId(),
        association.identity().playableStateScope(),
        association.identity().canonicalGameInstanceId(),
        association.canonicalVersionId(),
        2L,
        "first-open-" + UUID.randomUUID(),
        "a".repeat(64),
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        association.identity().catalogRevision(),
        null,
        AuthorityKind.RUN_OWNED_FIXTURE,
        association.runOwnedFixture().fixtureProvenanceDigest());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRead(
      RunOwnedWorldLaunchEvidence evidence,
      net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation association,
      UUID readId) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
        readId,
        association.identity().targetNamespace(),
        association.identity().canonicalTenantId(),
        association.identity().worldSlug(),
        association.identity().canonicalGameInstanceId(),
        association.identity().playableStateNamespaceId(),
        association.identity().playableStateScope(),
        true,
        evidence.capability().operationId(),
        association.canonicalVersionId(),
        evidence.launchDescriptor().requestDigest(),
        evidence.launchDescriptor().resultDigest(),
        null,
        AuthorityKind.RUN_OWNED_FIXTURE,
        association.runOwnedFixture().fixtureProvenanceDigest());
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof pendingProof(
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity) {
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        identity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING,
        null,
        null,
        null,
        false,
        null);
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof committedProof(
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity) {
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        identity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        1L,
        91L,
        "sha256:" + "d".repeat(64),
        false,
        Instant.parse("2026-10-15T00:00:00Z"));
  }

  private String holdStatus(String holdId) {
    return dsl.fetchSingle(
            "SELECT status FROM initial_admission_bind_hold WHERE hold_id=?::uuid", holdId)
        .get("status", String.class);
  }

  private static <T> T withControlledGameSessionPeer(java.util.function.Supplier<T> action) {
    // Stipulated peer/certificate context and owner responses are controlled test inputs, not a
    // genuine mTLS or Game Session admission proof.
    return withControlledPeer("game-session-service", "a".repeat(64), action);
  }

  private static <T> T withControlledPeer(
      String service, String leafSha256, java.util.function.Supplier<T> action) {
    GrpcPeerCertificateEvidence certificate = mock(GrpcPeerCertificateEvidence.class);
    when(certificate.leafSha256()).thenReturn(leafSha256);
    Context context =
        Context.ROOT
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                new GrpcPeerIdentity(
                    "spiffe://firemud/ns/dev/sa/" + service,
                    "dev",
                    service))
            .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }
}
