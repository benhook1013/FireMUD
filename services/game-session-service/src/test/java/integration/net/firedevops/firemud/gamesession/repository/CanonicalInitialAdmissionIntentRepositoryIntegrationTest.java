package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalLaunchPreparationRequest;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import net.firedevops.firemud.gamesession.service.GameSessionCanonicalLaunchPreparationService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalInitialAdmissionHoldPreparation;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for the durable pre-World-hold Game Session intent owner. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalInitialAdmissionIntentRepositoryIntegrationTest {
  private static final String NAMESPACE = "canonical-intent-it";
  private static final UUID TENANT = uuid(401);
  private static final UUID SOURCE_OPERATION = uuid(402);
  private static final UUID INTAKE_REQUEST = uuid(403);
  private static final UUID CATALOG_REQUEST = uuid(404);
  private static final UUID ACTOR = uuid(405);
  private static final UUID GAME_INSTANCE_UUID = uuid(406);
  private static final UUID CANONICAL_VERSION = uuid(407);
  private static final UUID ASSOCIATION_OPERATION = uuid(408);
  private static final String CONTROL_PLANE_REQUEST_ID = "canonical-intent-launch-1";
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void reservesExactIntentAndAttachesOnlyTheMatchingWorldIdentity() {
    Harness harness = harness();
    try {
      OwnerTarget target = harness.seed();
      var request = holdRequest(target, "intent-exact-replay");
      var lifecycle = target.lifecycleRequest(uuid(410));
      var identity =
          new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity(
              request, uuid(411), uuid(412));

      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> target.intents().attach(request, identity)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must commit before World hold identity attachment");
      assertNoAdmissionMutation(harness.dsl);

      CanonicalInitialAdmissionIntentSnapshot reserved =
          harness.transactions.execute(status -> target.intents().reserve(request, lifecycle));
      assertThat(reserved.state())
          .isEqualTo(CanonicalInitialAdmissionIntentSnapshot.IntentState.PENDING_HOLD);
      assertThat(reserved.holdIdentity()).isNull();
      assertThat(reserved.sourceBinding().gameInstanceId()).isEqualTo(target.gameInstanceId());
      assertThat(reserved.sourceBinding().currentRowVersion()).isPositive();
      assertThat(
              target.intents().read(target.targetNamespace(), request.initialAdmissionRequestId()))
          .contains(reserved);

      CanonicalInitialAdmissionIntentSnapshot retry =
          harness.transactions.execute(status -> target.intents().reserve(request, lifecycle));
      assertThat(retry).isEqualTo(reserved);

      var changedLifecycle = target.lifecycleRequest(uuid(413));
      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> target.intents().reserve(request, changedLifecycle)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("retained request or locked owner source binding");

      var changedDigest = withDigest(request, "b".repeat(64));
      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> target.intents().reserve(changedDigest, lifecycle)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not bind the complete initial-admission input");

      var changedSource = withInstance(request, uuid(414));
      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> target.intents().reserve(changedSource, lifecycle)))
          .isInstanceOf(IllegalStateException.class);

      var competingRequest = holdRequest(target, "intent-other-request");
      var competingLifecycle = target.lifecycleRequest(uuid(415));
      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> target.intents().reserve(competingRequest, competingLifecycle)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("already owns this realm");

      CanonicalInitialAdmissionIntentSnapshot attached =
          harness.transactions.execute(status -> target.intents().attach(request, identity));
      assertThat(attached.state())
          .isEqualTo(CanonicalInitialAdmissionIntentSnapshot.IntentState.HOLD_ATTACHED);
      assertThat(attached.holdIdentity()).isEqualTo(identity);
      CanonicalInitialAdmissionIntentSnapshot attachedReplay =
          harness.transactions.execute(status -> target.intents().attach(request, identity));
      assertThat(attachedReplay).isEqualTo(attached);
      CanonicalInitialAdmissionIntentSnapshot reservedReplay =
          harness.transactions.execute(status -> target.intents().reserve(request, lifecycle));
      assertThat(reservedReplay).isEqualTo(attached);

      var changedIdentity =
          new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity(
              request, uuid(416), uuid(417));
      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> target.intents().attach(request, changedIdentity)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot accept a changed hold identity");
      assertThat(
              target.intents().read(target.targetNamespace(), request.initialAdmissionRequestId()))
          .contains(attached);
      assertThat(
              harness.dsl.fetchCount(
                  DSL.table(DSL.name("game_session_canonical_initial_admission_intent"))))
          .isEqualTo(1);
      assertNoAdmissionMutation(harness.dsl);
    } finally {
      harness.close();
    }
  }

  @Test
  void commitsPendingHoldBeforeWorldAcquireAndRetriesTheSameIntentAfterAcquireFailure()
      throws Exception {
    Harness harness = harness();
    try {
      OwnerTarget target = harness.seed();
      var request = holdRequest(target, "intent-coordinator-ordering");
      var lifecycle = target.lifecycleRequest(uuid(418));
      var identity =
          new WorldCanonicalInitialAdmissionHold.HoldIdentity(request, uuid(419), uuid(420));
      WorldCanonicalInitialAdmissionHoldClient worldClient =
          mock(WorldCanonicalInitialAdmissionHoldClient.class);
      AtomicInteger acquireCalls = new AtomicInteger();

      when(worldClient.acquire(any(), any()))
          .thenAnswer(
              invocation -> {
                var acquireRequest =
                    invocation.getArgument(0, WorldCanonicalInitialAdmissionHold.Request.class);
                var acquireLifecycle =
                    invocation.getArgument(
                        1, WorldCanonicalInstanceLifecycleEvidence.Request.class);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                assertThat(acquireRequest.canonicalRequestBytes())
                    .containsExactly(request.canonicalRequestBytes());
                assertThat(acquireLifecycle.canonicalBytes())
                    .containsExactly(lifecycle.canonicalBytes());
                assertPendingIntentVisibleFromSeparateConnection(
                    harness, acquireRequest, acquireLifecycle);

                if (acquireCalls.incrementAndGet() == 1) {
                  throw new IllegalStateException("simulated World acquire failure");
                }
                return identity;
              });

      var preparation =
          new CanonicalInitialAdmissionHoldPreparation(
              target.intents(), worldClient, harness.transactionManager);

      assertThatThrownBy(() -> preparation.prepare(request, lifecycle))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("simulated World acquire failure");
      var pending =
          target
              .intents()
              .read(target.targetNamespace(), request.initialAdmissionRequestId())
              .orElseThrow();
      assertThat(pending.state())
          .isEqualTo(CanonicalInitialAdmissionIntentSnapshot.IntentState.PENDING_HOLD);
      assertThat(pending.holdRequest().canonicalRequestBytes())
          .containsExactly(request.canonicalRequestBytes());
      assertThat(pending.lifecycleRequest().canonicalBytes())
          .containsExactly(lifecycle.canonicalBytes());
      assertThat(pending.holdIdentity()).isNull();

      var attached = preparation.prepare(request, lifecycle);
      assertThat(acquireCalls).hasValue(2);
      assertThat(attached.state())
          .isEqualTo(CanonicalInitialAdmissionIntentSnapshot.IntentState.HOLD_ATTACHED);
      assertThat(attached.holdRequest().canonicalRequestBytes())
          .containsExactly(request.canonicalRequestBytes());
      assertThat(attached.lifecycleRequest().canonicalBytes())
          .containsExactly(lifecycle.canonicalBytes());
      assertThat(attached.holdIdentity()).isEqualTo(identity);
      assertThat(
              target.intents().read(target.targetNamespace(), request.initialAdmissionRequestId()))
          .contains(attached);
      assertThat(
              harness.dsl.fetchCount(
                  DSL.table(DSL.name("game_session_canonical_initial_admission_intent"))))
          .isEqualTo(1);
      assertNoAdmissionMutation(harness.dsl);
    } finally {
      harness.close();
    }
  }

  private static void assertPendingIntentVisibleFromSeparateConnection(
      Harness harness,
      WorldCanonicalInitialAdmissionHold.Request request,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycle)
      throws java.sql.SQLException {
    try (var connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      connection.setSchema(harness.schema());
      try (var statement =
          connection.prepareStatement(
              "SELECT state, canonical_hold_request_bytes, canonical_lifecycle_request_bytes "
                  + "FROM game_session_canonical_initial_admission_intent "
                  + "WHERE target_namespace = ? AND initial_admission_request_id = ?")) {
        statement.setString(1, request.targetNamespace());
        statement.setString(2, request.initialAdmissionRequestId());
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString("state")).isEqualTo("PENDING_HOLD");
          assertThat(rows.getBytes("canonical_hold_request_bytes"))
              .containsExactly(request.canonicalRequestBytes());
          assertThat(rows.getBytes("canonical_lifecycle_request_bytes"))
              .containsExactly(lifecycle.canonicalBytes());
          assertThat(rows.next()).isFalse();
        }
      }
    }
  }

  @Test
  void rollbackLeavesNoIntentAndChangedRuntimeVersionBlocksAttachment() {
    Harness harness = harness();
    try {
      OwnerTarget target = harness.seed();
      var request = holdRequest(target, "intent-runtime-version");
      var lifecycle = target.lifecycleRequest(uuid(420));

      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> {
                        target.intents().reserve(request, lifecycle);
                        throw new IllegalStateException("force owner rollback");
                      }))
          .hasMessageContaining("force owner rollback");
      assertThat(
              target.intents().read(target.targetNamespace(), request.initialAdmissionRequestId()))
          .isEmpty();
      assertNoAdmissionMutation(harness.dsl);

      CanonicalInitialAdmissionIntentSnapshot reserved =
          harness.transactions.execute(status -> target.intents().reserve(request, lifecycle));
      harness.transactions.execute(
          status -> {
            harness.dsl.execute(
                "UPDATE game_instances SET row_version = row_version + 1 "
                    + "WHERE tenant_id = ? AND id = ?",
                target.gameSessionTenantId(),
                target.gameInstanceId());
            return null;
          });

      var identity =
          new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity(
              request, uuid(421), uuid(422));
      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> target.intents().attach(request, identity)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("retained request or locked owner source binding");
      assertThat(
              target.intents().read(target.targetNamespace(), request.initialAdmissionRequestId()))
          .contains(reserved);
      assertNoAdmissionMutation(harness.dsl);
    } finally {
      harness.close();
    }
  }

  @Test
  void competingRequestsSerializeOnTheCatalogAndOnlyOneIntentWins() throws Exception {
    Harness harness = harness();
    try {
      OwnerTarget target = harness.seed();
      var firstRequest = holdRequest(target, "intent-race-first");
      var secondRequest = holdRequest(target, "intent-race-second");
      var firstLifecycle = target.lifecycleRequest(uuid(430));
      var secondLifecycle = target.lifecycleRequest(uuid(431));
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      var executor = Executors.newFixedThreadPool(2);
      try {
        var first =
            executor.submit(() -> raceReserve(target, firstRequest, firstLifecycle, ready, start));
        var second =
            executor.submit(
                () -> raceReserve(target, secondRequest, secondLifecycle, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(first.get(20, TimeUnit.SECONDS)).isNotEqualTo(second.get(20, TimeUnit.SECONDS));
      } finally {
        executor.shutdownNow();
      }

      assertThat(
              harness.dsl.fetchCount(
                  DSL.table(DSL.name("game_session_canonical_initial_admission_intent"))))
          .isEqualTo(1);
      assertNoAdmissionMutation(harness.dsl);
    } finally {
      harness.close();
    }
  }

  private static boolean raceReserve(
      OwnerTarget target,
      WorldCanonicalInitialAdmissionHold.Request request,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycle,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting to begin competing owner transaction");
    }
    try {
      target.harness().transactions.execute(status -> target.intents().reserve(request, lifecycle));
      return true;
    } catch (
        CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException expected) {
      return false;
    }
  }

  private static WorldCanonicalInitialAdmissionHold.Request holdRequest(
      OwnerTarget target, String requestId) {
    long activeEpoch = 2L;
    var launch = target.launch().association();
    UUID canonicalVersionId = target.launch().canonicalVersionId();
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            target.targetNamespace(),
            target.canonicalTenantId(),
            target.worldSlug(),
            target.catalog().realmId(),
            target.playableStateNamespaceId(),
            "SHARED",
            launch.gameInstanceUuid(),
            canonicalVersionId,
            activeEpoch,
            target.catalog().catalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            requestId);
    return new WorldCanonicalInitialAdmissionHold.Request(
        target.targetNamespace(),
        target.canonicalTenantId(),
        target.worldSlug(),
        target.catalog().realmId(),
        target.playableStateNamespaceId(),
        "SHARED",
        launch.gameInstanceUuid(),
        canonicalVersionId,
        activeEpoch,
        requestId,
        requestDigest,
        WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        target.catalog().catalogRevision(),
        null);
  }

  private static WorldCanonicalInitialAdmissionHold.Request withDigest(
      WorldCanonicalInitialAdmissionHold.Request request, String digest) {
    return new WorldCanonicalInitialAdmissionHold.Request(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        request.activeLifecycleEpoch(),
        request.initialAdmissionRequestId(),
        digest,
        request.initialAdmissionOrigin(),
        request.expectedCatalogRevision(),
        request.expectedPriorPointerVersion());
  }

  private static WorldCanonicalInitialAdmissionHold.Request withInstance(
      WorldCanonicalInitialAdmissionHold.Request request, UUID instanceId) {
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.realmId(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            instanceId,
            request.canonicalVersionId(),
            request.activeLifecycleEpoch(),
            request.expectedCatalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.valueOf(
                request.initialAdmissionOrigin().name()),
            request.expectedPriorPointerVersion(),
            request.initialAdmissionRequestId());
    return new WorldCanonicalInitialAdmissionHold.Request(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        instanceId,
        request.canonicalVersionId(),
        request.activeLifecycleEpoch(),
        request.initialAdmissionRequestId(),
        requestDigest,
        request.initialAdmissionOrigin(),
        request.expectedCatalogRevision(),
        request.expectedPriorPointerVersion());
  }

  private static void assertNoAdmissionMutation(DSLContext dsl) {
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM gameplay_admission_pointer "
                        + "WHERE representation_version = 3")
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM gameplay_admission_pointer_event "
                        + "WHERE representation_version = 3")
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_initial_admission_attempt"))))
        .isZero();
  }

  private static AuthoredWorldSourceEvidence freshSource() {
    String worldSlug = "intent-world";
    String tenantSlug = "tenant-intent";
    String displayName = "Intent World";
    UUID registrationRequestId = uuid(432);
    long sourceGameRowId = 823;
    String sourceGameTenantKey = "gds-tenant-intent";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, TENANT, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            SOURCE_OPERATION,
            requestDigest,
            TENANT,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        SOURCE_OPERATION,
        requestDigest,
        TENANT,
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static CreateCanonicalRealmCatalogRequest catalogRequest(IntakeReceipt source) {
    return new CreateCanonicalRealmCatalogRequest(
        CATALOG_REQUEST,
        NAMESPACE,
        TENANT,
        source.operationId(),
        "intent-realm",
        "Intent Realm",
        true,
        true,
        "SHARED",
        "explicit-policy-v1",
        null,
        null);
  }

  private static CreateCanonicalLaunchPreparationRequest preparationRequest(
      IntakeReceipt source, CanonicalRealmCatalogSnapshot catalog) {
    return new CreateCanonicalLaunchPreparationRequest(
        CONTROL_PLANE_REQUEST_ID,
        ACTOR,
        NAMESPACE,
        TENANT,
        catalog.realmId(),
        CATALOG_REQUEST,
        catalog.catalogRevision(),
        source.operationId(),
        404,
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }

  private static AuthoredWorldLaunchDescriptorClient syntheticGameDesignClient(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source) {
    AuthoredWorldLaunchDescriptorClient client = mock(AuthoredWorldLaunchDescriptorClient.class);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request.descriptorRequest(catalog, source.source()),
            "launch-descriptor-canonical-intent",
            72,
            false,
            null,
            "{}",
            "generation-1",
            3,
            73,
            "published-bundle-intent",
            false,
            null);
    CompleteLaunchBindingEvidence binding = completeBinding(descriptor);
    when(client.resolve(any(AuthoredWorldLaunchDescriptorEvidence.Request.class)))
        .thenReturn(descriptor);
    when(client.get(any(GetRequest.class))).thenReturn(descriptor);
    when(client.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(binding);
    return client;
  }

  private static CompleteLaunchBindingEvidence completeBinding(
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    String commitId = "publish-commit-canonical-intent";
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        commitId,
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? digest("d") : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence attestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            CANONICAL_VERSION,
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish-canonical-intent",
            commitId,
            participants,
            digest("b"),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, attestation);
  }

  private static String digest(String letter) {
    return "sha256:" + letter.repeat(64);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private static Harness harness() {
    String schema = "gs_canonical_intent_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Harness(schema, dataSource, transactionManager, transactions, dsl);
  }

  private record Harness(
      String schema,
      DriverManagerDataSource dataSource,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      DSLContext dsl) {
    OwnerTarget seed() {
      var sourceRepository = new GameSessionAuthoredWorldSourceRepository(dsl);
      var catalogRepository = new GameSessionCanonicalRealmCatalogRepository(dsl);
      var preparationRepository =
          new GameSessionCanonicalLaunchPreparationRepository(dsl, catalogRepository);
      var launchRepository = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
      IntakeReceipt source =
          Objects.requireNonNull(
              transactions.execute(
                  status -> sourceRepository.register(INTAKE_REQUEST, freshSource())));
      var preparedCatalog =
          catalogRepository.prepareInitialPublicProduction(catalogRequest(source));
      CanonicalRealmCatalogSnapshot catalog =
          Objects.requireNonNull(
              transactions.execute(
                  status -> catalogRepository.createInitialPublicProduction(preparedCatalog)));
      var preparationRequest = preparationRequest(source, catalog);
      var preparationService =
          new GameSessionCanonicalLaunchPreparationService(
              syntheticGameDesignClient(preparationRequest, catalog, source),
              catalogRepository,
              sourceRepository,
              preparationRepository,
              transactionManager,
              NAMESPACE);
      var preparedLaunch = preparationService.prepare(preparationRequest);
      CompleteLaunchBindingEvidence binding =
          preparationService.readCompleteBinding(preparedLaunch);
      createTestOnlyFreshMappingAndRunningLaunch(source, binding, launchRepository);
      CanonicalInitialAdmissionLaunchTarget launch =
          launchRepository
              .readForInitialAdmission(NAMESPACE, TENANT, catalog.realmId(), GAME_INSTANCE_UUID)
              .orElseThrow();
      var intentRepository =
          new CanonicalInitialAdmissionIntentRepository(dsl, catalogRepository, launchRepository);
      return new OwnerTarget(this, catalog, launch, intentRepository);
    }

    private void createTestOnlyFreshMappingAndRunningLaunch(
        IntakeReceipt source,
        CompleteLaunchBindingEvidence binding,
        CanonicalGameInstanceLaunchAssociationRepository launchRepository) {
      RuntimeTenantIdentityEvidence sourceIdentity =
          new RuntimeTenantIdentityEvidence(
              1,
              NAMESPACE,
              source.source().registrationRequestId(),
              TENANT,
              source.source().sourceGameRowId(),
              source.source().sourceGameTenantKey(),
              source.source().provenanceKind());
      transactions.execute(
          status -> {
            Record reservation =
                Objects.requireNonNull(
                    dsl.fetchOne(
                        "INSERT INTO game_session_tenant_scope_reservation "
                            + "(reservation_kind) VALUES ('FRESH_SOURCE_BOUND') "
                            + "RETURNING game_session_tenant_id"));
            long tenantId = reservation.get("game_session_tenant_id", Long.class);
            var tenantAssociation =
                new FreshGameSessionTenantAssociation(
                    ASSOCIATION_OPERATION, tenantId, sourceIdentity);
            dsl.execute(
                "INSERT INTO game_session_tenant_canonical_claim "
                    + "(target_namespace, canonical_tenant_id, legacy_game_session_tenant_id, "
                    + "association_kind, reservation_kind, association_operation_id, "
                    + "association_request_id) "
                    + "VALUES (?, ?, ?, 'FRESH_SOURCE_BOUND', 'FRESH_SOURCE_BOUND', ?, ?)",
                NAMESPACE,
                TENANT,
                tenantId,
                ASSOCIATION_OPERATION,
                source.source().registrationRequestId());
            dsl.execute(
                "INSERT INTO game_session_fresh_tenant_association "
                    + "(association_operation_id, target_namespace, association_request_id, "
                    + "source_schema_version, source_target_namespace, source_request_id, "
                    + "source_canonical_tenant_id, canonical_tenant_id, "
                    + "legacy_game_session_tenant_id, source_game_row_id, "
                    + "source_game_tenant_key, provenance_kind, association_kind) "
                    + "VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', 'FRESH_SOURCE_BOUND')",
                ASSOCIATION_OPERATION,
                NAMESPACE,
                source.source().registrationRequestId(),
                NAMESPACE,
                source.source().registrationRequestId(),
                TENANT,
                TENANT,
                tenantId,
                source.source().sourceGameRowId(),
                source.source().sourceGameTenantKey());
            Record instance =
                Objects.requireNonNull(
                    dsl.fetchOne(
                        "INSERT INTO game_instances "
                            + "(tenant_id, runtime_version, game_template_id, launch_descriptor_id, "
                            + "version_id, release_bundle_id, generation_config_revision, "
                            + "version_state_epoch, owner_account_id, status, row_version, "
                            + "game_instance_uuid, run_owned_start_request_id, "
                            + "run_owned_start_request_digest, run_owned_start_published_release_bundle_ref, "
                            + "run_owned_start_preparing_epoch, run_owned_start_active_epoch) "
                            + "VALUES (?, '72', 404, ?, 72, 73, 'generation-1', 3, 77, 'STARTING', "
                            + "1, ?, ?, ?, ?, 1, 2) RETURNING id",
                        tenantId,
                        binding.descriptor().launchDescriptorId(),
                        GAME_INSTANCE_UUID,
                        CONTROL_PLANE_REQUEST_ID,
                        "c".repeat(64),
                        binding.descriptor().publishedReleaseBundleRef()));
            long gameInstanceId = instance.get("id", Long.class);
            launchRepository.capture(tenantAssociation, gameInstanceId, binding);
            dsl.execute(
                "UPDATE game_instances SET status = 'RUNNING' WHERE tenant_id = ? AND id = ?",
                tenantId,
                gameInstanceId);
            return null;
          });
    }

    void close() {
      try (var connection =
              DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      } catch (Exception failure) {
        throw new IllegalStateException("Failed to dispose canonical intent test schema", failure);
      }
    }
  }

  private record OwnerTarget(
      Harness harness,
      CanonicalRealmCatalogSnapshot catalog,
      CanonicalInitialAdmissionLaunchTarget launch,
      CanonicalInitialAdmissionIntentRepository intents) {
    String targetNamespace() {
      return catalog.targetNamespace();
    }

    UUID canonicalTenantId() {
      return catalog.tenantId();
    }

    String worldSlug() {
      return catalog.worldSlug();
    }

    UUID playableStateNamespaceId() {
      return catalog.playableStateNamespaceId();
    }

    long gameSessionTenantId() {
      return launch.association().gameSessionTenantId();
    }

    long gameInstanceId() {
      return launch.gameInstanceId();
    }

    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(UUID readRequestId) {
      CanonicalGameInstanceLaunchAssociation association = launch.association();
      var binding = association.launchBindingEvidence();
      return new WorldCanonicalInstanceLifecycleEvidence.Request(
          WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
          readRequestId,
          targetNamespace(),
          canonicalTenantId(),
          worldSlug(),
          association.gameInstanceUuid(),
          playableStateNamespaceId(),
          "SHARED",
          true,
          association.controlPlaneRequestId(),
          launch.canonicalVersionId(),
          binding.descriptor().requestDigest(),
          binding.descriptor().resultDigest(),
          binding.releaseAttestation().evidenceDigest());
    }
  }
}
