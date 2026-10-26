package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
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
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
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
  private static final UUID CANONICAL_VERSION = uuid(407);
  private static final UUID ASSOCIATION_OPERATION = uuid(408);
  private static final String CONTROL_PLANE_REQUEST_ID = "canonical-intent-launch-1";
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String SHARED_SAGA_MIGRATION_LOCATION =
      "filesystem:"
          + Path.of("../common-saga/src/main/resources/db/migration/saga")
              .toAbsolutePath()
              .normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void humanStartingIntentRefusesAnExpiredOriginalOwnerWithoutEffects() throws Exception {
    Harness harness = harness();
    try {
      OwnerTarget target = harness.seedStarting(Duration.ofSeconds(10));
      var request = holdRequest(target, "intent-expired-original-owner");
      var lifecycle = target.lifecycleRequest(uuid(409));

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (!Boolean.TRUE.equals(
          harness
              .dsl
              .fetchSingle(
                  "SELECT lease_expires_at <= clock_timestamp() "
                      + "FROM game_session_start_session_operator_attempt "
                      + "WHERE target_namespace = ? AND control_plane_request_id = ?",
                  NAMESPACE,
                  CONTROL_PLANE_REQUEST_ID)
              .get(0, Boolean.class))) {
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException("Timed out waiting for the legitimate owner lease");
        }
        Thread.sleep(10L);
      }

      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> target.intents().reserveHumanStarting(request, lifecycle)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Exact canonical pins are required before STARTING");
      assertThat(
              harness.dsl.fetchCount(
                  DSL.table(DSL.name("game_session_canonical_initial_admission_intent"))))
          .isZero();
      assertNoAdmissionMutation(harness.dsl);
    } finally {
      harness.close();
    }
  }

  @Test
  void reservesExactIntentAndAttachesOnlyTheMatchingWorldIdentity() throws java.sql.SQLException {
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
      assertThat(reserved.sourceBinding().currentRowVersion())
          .isEqualTo(target.launch().association().currentRowVersion());
      assertThat(
              target.intents().read(target.targetNamespace(), request.initialAdmissionRequestId()))
          .contains(reserved);

      harness.transactions.execute(
          status -> {
            harness.dsl.execute(
                "UPDATE game_instances SET row_version = row_version + 1 "
                    + "WHERE tenant_id = ? AND id = ?",
                target.gameSessionTenantId(),
                target.gameInstanceId());
            return null;
          });
      CanonicalInitialAdmissionIntentSnapshot retry =
          harness.transactions.execute(status -> target.intents().reserve(request, lifecycle));
      assertThat(retry).isEqualTo(reserved);
      assertThat(retry.sourceBinding().currentRowVersion())
          .isEqualTo(reserved.sourceBinding().currentRowVersion());

      var intentEvidence =
          harness.dsl.fetchSingle(
              "SELECT original_start_session_tuple, catalog_source_intake_operation_id, "
                  + "catalog_source_intake_request_id FROM game_session_canonical_initial_admission_intent "
                  + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
              request.targetNamespace(),
              request.initialAdmissionRequestId());
      var launchEvidence =
          harness.dsl.fetchSingle(
              "SELECT original_start_session_tuple, catalog_source_intake_operation_id, "
                  + "catalog_source_intake_request_id FROM game_session_canonical_instance_launch "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              request.targetNamespace(),
              reserved.sourceBinding().controlPlaneRequestId());
      assertThat(intentEvidence.get("original_start_session_tuple", byte[].class))
          .containsExactly(launchEvidence.get("original_start_session_tuple", byte[].class));
      assertThat(intentEvidence.get("catalog_source_intake_operation_id", UUID.class))
          .isEqualTo(target.catalog().sourceIntakeReceipt().operationId());
      assertThat(intentEvidence.get("catalog_source_intake_request_id", UUID.class))
          .isEqualTo(target.catalog().sourceIntakeReceipt().intakeRequestId());
      assertPendingIntentVisibleFromSeparateConnection(harness, request, lifecycle);
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
      assertThat(attached.sourceBinding().currentRowVersion())
          .isEqualTo(reserved.sourceBinding().currentRowVersion());
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
  void incompleteHumanPreparationDeniesBeforeIntentReservationOrWorldAcquire() {
    Harness harness = harness();
    try {
      OwnerTarget target = harness.seed();
      var request = holdRequest(target, "intent-unprotected-human");
      var lifecycle = target.lifecycleRequest(uuid(418));
      WorldCanonicalInitialAdmissionHoldClient worldClient =
          mock(WorldCanonicalInitialAdmissionHoldClient.class);
      var preparation =
          new CanonicalInitialAdmissionHoldPreparation(
              target.intents(), worldClient, harness.transactionManager);
      for (int retry = 0; retry < 2; retry++) {
        assertThatThrownBy(() -> preparation.prepare(request, lifecycle))
            .isInstanceOf(
                CanonicalInitialAdmissionRepository
                    .CanonicalHumanAdmissionProtectionUnavailableException.class)
            .hasMessageContaining("Account terminal protection");
      }
      verifyNoInteractions(worldClient);
      assertThat(
              target.intents().read(target.targetNamespace(), request.initialAdmissionRequestId()))
          .isEmpty();
      assertThat(
              harness.dsl.fetchCount(
                  DSL.table(DSL.name("game_session_canonical_initial_admission_intent"))))
          .isZero();
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
            harness.database().getJdbcUrl(),
            harness.database().getUsername(),
            harness.database().getPassword())) {
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
  void rollbackLeavesNoIntentAndRowVersionRegressionBlocksRetry() {
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

      harness.transactions.execute(
          status -> {
            harness.dsl.execute(
                "UPDATE game_instances SET row_version = row_version + 1 "
                    + "WHERE tenant_id = ? AND id = ?",
                target.gameSessionTenantId(),
                target.gameInstanceId());
            return null;
          });
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
      CanonicalInitialAdmissionIntentSnapshot monotonicRetry =
          harness.transactions.execute(status -> target.intents().reserve(request, lifecycle));
      assertThat(monotonicRetry).isEqualTo(reserved);
      assertThat(monotonicRetry.sourceBinding().currentRowVersion())
          .isEqualTo(reserved.sourceBinding().currentRowVersion());

      harness.transactions.execute(
          status -> {
            harness.dsl.execute(
                "UPDATE game_instances SET row_version = ? WHERE tenant_id = ? AND id = ?",
                reserved.sourceBinding().currentRowVersion() - 1,
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
                      status -> target.intents().reserve(request, lifecycle)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("retained request or locked owner source binding");
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
  void retryRejectsRuntimeVersionDescriptorAndCatalogChanges() {
    assertRuntimeSourceMutationRejected(
        "UPDATE game_instances SET version_id = version_id + 1, "
            + "row_version = row_version + 1 WHERE tenant_id = ? AND id = ?");
    assertRuntimeSourceMutationRejected(
        "UPDATE game_instances SET launch_descriptor_id = launch_descriptor_id || '-changed', "
            + "row_version = row_version + 1 WHERE tenant_id = ? AND id = ?");

    Harness harness = harness();
    try {
      OwnerTarget target = harness.seed();
      var request = holdRequest(target, "intent-catalog-change");
      var lifecycle = target.lifecycleRequest(uuid(423));
      CanonicalInitialAdmissionIntentSnapshot reserved =
          harness.transactions.execute(status -> target.intents().reserve(request, lifecycle));
      var changedCatalogRequest =
          holdRequest(
              target, request.initialAdmissionRequestId(), target.catalog().catalogRevision() + 1);

      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> target.intents().reserve(changedCatalogRequest, lifecycle)))
          .isInstanceOf(
              GameSessionCanonicalRealmCatalogRepository.InvalidCatalogEvidenceException.class)
          .hasMessageContaining("missing or stale");
      assertThat(
              target.intents().read(target.targetNamespace(), request.initialAdmissionRequestId()))
          .contains(reserved);
      assertNoAdmissionMutation(harness.dsl);
    } finally {
      harness.close();
    }
  }

  private static void assertRuntimeSourceMutationRejected(String updateSql) {
    Harness harness = harness();
    try {
      OwnerTarget target = harness.seed();
      var request = holdRequest(target, "intent-runtime-source-change");
      var lifecycle = target.lifecycleRequest(uuid(424));
      CanonicalInitialAdmissionIntentSnapshot reserved =
          harness.transactions.execute(status -> target.intents().reserve(request, lifecycle));
      Record originalRuntimeSource =
          Objects.requireNonNull(
              harness.dsl.fetchOne(
                  "SELECT version_id, launch_descriptor_id, row_version FROM game_instances "
                      + "WHERE tenant_id = ? AND id = ?",
                  target.gameSessionTenantId(),
                  target.gameInstanceId()));

      assertThatThrownBy(
              () ->
                  harness.transactions.execute(
                      status -> {
                        harness.dsl.execute(
                            updateSql, target.gameSessionTenantId(), target.gameInstanceId());
                        target.intents().reserve(request, lifecycle);
                        return null;
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("complete launch binding");
      Record runtimeSourceAfterRollback =
          Objects.requireNonNull(
              harness.dsl.fetchOne(
                  "SELECT version_id, launch_descriptor_id, row_version FROM game_instances "
                      + "WHERE tenant_id = ? AND id = ?",
                  target.gameSessionTenantId(),
                  target.gameInstanceId()));
      assertThat(runtimeSourceAfterRollback.get("version_id", Long.class))
          .isEqualTo(originalRuntimeSource.get("version_id", Long.class));
      assertThat(runtimeSourceAfterRollback.get("launch_descriptor_id", String.class))
          .isEqualTo(originalRuntimeSource.get("launch_descriptor_id", String.class));
      assertThat(runtimeSourceAfterRollback.get("row_version", Long.class))
          .isEqualTo(originalRuntimeSource.get("row_version", Long.class));
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

  static WorldCanonicalInitialAdmissionHold.Request holdRequest(
      OwnerTarget target, String requestId) {
    return holdRequest(target, requestId, target.catalog().catalogRevision());
  }

  private static WorldCanonicalInitialAdmissionHold.Request holdRequest(
      OwnerTarget target, String requestId, long expectedCatalogRevision) {
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
            expectedCatalogRevision,
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
        expectedCatalogRevision,
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
        "PRESEEDED_ONLY",
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
        true,
        "{}");
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
    CompleteLaunchBindingEvidence binding =
        CanonicalHumanPublicationOwnerTestFixtures.completeBinding(
            descriptor, publishedPolicy(source));
    when(client.resolve(any(AuthoredWorldLaunchDescriptorEvidence.Request.class)))
        .thenReturn(descriptor);
    when(client.get(any(GetRequest.class))).thenReturn(descriptor);
    when(client.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(binding);
    return client;
  }

  private static net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence.PublishedSet
      publishedPolicy(IntakeReceipt source) {
    return CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
        catalogRequest(source),
        source.source(),
        CANONICAL_VERSION,
        72L,
        "publish-canonical-intent",
        "published-bundle-intent",
        "generation-1",
        3L);
  }

  private static String digest(String letter) {
    return "sha256:" + letter.repeat(64);
  }

  static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  static Harness harness() {
    return harness(postgres, null);
  }

  static Harness harness(String targetVersion) {
    return harness(postgres, targetVersion);
  }

  static Harness harness(PostgreSQLContainer<?> database) {
    return harness(database, null);
  }

  static Harness harness(PostgreSQLContainer<?> database, String targetVersion) {
    Objects.requireNonNull(database, "database");
    String schema = "gs_canonical_intent_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(database.getJdbcUrl());
    dataSource.setUsername(database.getUsername());
    dataSource.setPassword(database.getPassword());
    dataSource.setSchema(schema);
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .placeholders(java.util.Map.of("serviceSchema", schema))
            .locations(MIGRATION_LOCATION, SHARED_SAGA_MIGRATION_LOCATION);
    if (targetVersion != null) {
      configuration.target(targetVersion);
    }
    configuration.load().migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Harness(database, schema, dataSource, transactionManager, transactions, dsl);
  }

  record Harness(
      PostgreSQLContainer<?> database,
      String schema,
      DriverManagerDataSource dataSource,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      DSLContext dsl) {
    OwnerTarget seed() {
      return seed(true);
    }

    OwnerTarget seedStarting() {
      return seed(false);
    }

    OwnerTarget seedStarting(Duration ownerLease) {
      return seed(false, ownerLease);
    }

    OwnerTarget seed(Duration ownerLease) {
      return seed(true, ownerLease);
    }

    private OwnerTarget seed(boolean completeRunning) {
      return seed(completeRunning, Duration.ofMinutes(5));
    }

    private OwnerTarget seed(boolean completeRunning, Duration ownerLease) {
      var sourceRepository = new GameSessionAuthoredWorldSourceRepository(dsl);
      var catalogRepository = new GameSessionCanonicalRealmCatalogRepository(dsl);
      var preparationRepository =
          new GameSessionCanonicalLaunchPreparationRepository(dsl, catalogRepository);
      var launchRepository = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
      IntakeReceipt source =
          Objects.requireNonNull(
              transactions.execute(
                  status -> sourceRepository.register(INTAKE_REQUEST, freshSource())));
      transactions.execute(
          status ->
              new GameSessionFreshTenantAssociationRepository(dsl)
                  .associateFresh(
                      ASSOCIATION_OPERATION,
                      NAMESPACE,
                      source,
                      CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(
                          source.source())));
      var preparedCatalog =
          catalogRepository.prepareInitialPublicProduction(
              catalogRequest(source), publishedPolicy(source));
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
      UUID allocatedInstanceUuid =
          createTestOnlyFreshMappingAndLaunch(
              source, binding, launchRepository, completeRunning, ownerLease);
      CanonicalInitialAdmissionLaunchTarget launch =
          launchRepository
              .readForInitialAdmission(NAMESPACE, TENANT, catalog.realmId(), allocatedInstanceUuid)
              .orElseThrow();
      var intentRepository =
          new CanonicalInitialAdmissionIntentRepository(dsl, catalogRepository, launchRepository);
      return new OwnerTarget(this, catalog, launch, intentRepository);
    }

    void migrateLatest() {
      Flyway flyway =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(schema)
              .defaultSchema(schema)
              .table("flyway_schema_history")
              .placeholders(java.util.Map.of("serviceSchema", schema))
              .locations(MIGRATION_LOCATION, SHARED_SAGA_MIGRATION_LOCATION)
              .load();
      flyway.migrate();
      flyway.validate();
    }

    private UUID createTestOnlyFreshMappingAndLaunch(
        IntakeReceipt source,
        CompleteLaunchBindingEvidence binding,
        CanonicalGameInstanceLaunchAssociationRepository launchRepository,
        boolean completeRunning,
        Duration ownerLease) {
      var tuple =
          CanonicalHumanPublicationOwnerTestFixtures.tuple(
              NAMESPACE,
              TENANT,
              ACTOR,
              binding.descriptor().gameTemplateId(),
              binding.descriptor().controlPlaneRequestId());
      CanonicalHumanPublicationOwnerTestFixtures.pinLaunch(
          dsl, transactions, tuple, source.source(), publishedPolicy(source), binding, ownerLease);
      return Objects.requireNonNull(
          transactions.execute(
              status -> {
                var association =
                    new GameSessionFreshTenantAssociationRepository(dsl)
                        .associateFresh(
                            ASSOCIATION_OPERATION,
                            NAMESPACE,
                            source,
                            CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(
                                source.source()));
                var starting =
                    new GameSessionCanonicalStartingInstanceRepository(dsl, NAMESPACE)
                        .createStarting(
                            association,
                            binding,
                            ACTOR,
                            tuple.controlPlaneRequestId(),
                            tuple.mutationDigest(),
                            tuple);
                if (completeRunning) {
                  dsl.execute(
                      "UPDATE game_instances SET status = 'RUNNING' WHERE tenant_id = ? AND id = ?",
                      association.legacyGameSessionTenantId(),
                      starting.gameInstanceId());
                }
                return starting.gameInstanceUuid();
              }),
          "Actual owner-allocated canonical instance UUID");
    }

    void close() {
      try (var connection =
              DriverManager.getConnection(
                  database.getJdbcUrl(), database.getUsername(), database.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      } catch (Exception failure) {
        throw new IllegalStateException("Failed to dispose canonical intent test schema", failure);
      }
    }
  }

  record OwnerTarget(
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
