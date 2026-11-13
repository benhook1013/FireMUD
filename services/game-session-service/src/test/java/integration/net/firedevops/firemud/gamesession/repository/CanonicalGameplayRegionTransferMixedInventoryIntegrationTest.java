package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalGameplayRegionTransferMixedInventoryIntegrationTest
    extends CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTestSupport {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Override
  PostgreSQLContainer<?> postgresContainer() {
    return POSTGRES;
  }

  @Override
  GenericContainer<?> redisContainer() {
    return REDIS;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void calibratesMixedRetainedExecutionHandoffAndRefusesLastMarkerLoss(boolean loseLastMarker)
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), proof.clock);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof
          .capacity(proof.sql)
          .authorize(
              proof.admission, template, account, issuer, proof.execution, transferDeadline());
      // Only the real authorization above can FINAL. The extra envelopes deliberately reuse the
      // fixture's original transition and model SQL dispatches that never reached Redis.
      var undelivered = List.of(proof.newControlledExecution(), proof.newControlledExecution());
      undelivered.forEach(proof::seedUndeliveredDispatch);
      UUID transferId;
      List<CanonicalGameplayRegionOwnerTransferRepository.Item> captured;
      try (var finalReadback =
          new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                  proof.redis, proof.scripts, proof.clock, proof.tags)
              .finalizeOriginalNewOnce(
                  proof.admission,
                  template,
                  account,
                  issuer,
                  proof.execution,
                  transferDeadline())) {
        transferId = proof.begin();
        captured =
            proof.transfers.within(
                transferDeadline(), ignored -> proof.transfers.capture(transferId));
        assertThat(captured)
            .extracting(item -> item.execution().digest())
            .containsExactlyInAnyOrder(
                proof.execution.digest(),
                undelivered.getFirst().digest(),
                undelivered.getLast().digest());
        new CanonicalGameplayBindingInventoryRepository(proof.sql)
            .commitOriginalNewFinalAdmission(finalReadback);
      }
      assertThat(proof.executionState(proof.execution)).isEqualTo("COMMITTED");
      String originalReceipts = proof.originalImage();

      var observationLatency = Duration.ofMillis(5);
      var attestor =
          new CanonicalGameplayControlledRegionTransferObservationFixture(
              proof.clock, Duration.ofSeconds(30), observationLatency);
      var transfer =
          new CanonicalGameplayRegionOwnerTransferCoordinator(
              proof.sql,
              proof.clock,
              proof.transfers,
              proof.capacity(proof.sql),
              attestor.receiver(proof.transfers, proof.tags));
      var identity =
          new RuntimeIdentity(
              "game-session-service",
              "mixed-transfer-calibration",
              "localhost",
              proof.clock.instant(),
              "test",
              "test",
              "controlled-test-image");
      var original = proof.region.installation();
      try (var connection = proof.redis.connect(ByteArrayCodec.INSTANCE)) {
        assertThat(connection.sync().pexpire(ascii(original.grant.key()), 0L)).isTrue();
      }
      var leases = new CanonicalGameplayRegionLeaseRedisOwner(proof.redis, proof.scripts);
      long deadline = transferDeadline();
      long start = System.nanoTime();
      var grant =
          leases.acquire(proof.scope, proof.prior.regionEpoch(), Duration.ofSeconds(30), identity);
      leases.requireGrantScope(grant, proof.scope, proof.prior.regionEpoch(), identity);
      leases.verifyInitializedTimeline(grant, original.preparation);
      leases.requireCurrent(grant);
      assertThat(transfer.advance(proof.scope, proof.prior, deadline)).isEqualTo(transferId);
      int advanceObservations = attestor.observationCount();
      for (var execution : undelivered) {
        assertThat(proof.physicalExecutionState(execution)).isEqualTo("FENCED_INDETERMINATE");
      }
      assertThat(proof.physicalExecutionState(proof.execution)).isEqualTo("COMMITTED");
      String retainedItems = proof.itemImage(transferId);
      var last = captured.getLast();
      if (loseLastMarker) proof.losePhysicalExecution(last.execution());

      leases.requireCurrent(grant);
      long installStart = System.nanoTime();
      CanonicalGameplayRegionRuntimeStatus installed = null;
      if (loseLastMarker) {
        assertThatThrownBy(
                () -> proof.installCalibrationTransfer(transfer, transferId, grant, deadline))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Original NEW capacity execution is indeterminate");
      } else {
        installed = proof.installCalibrationTransfer(transfer, transferId, grant, deadline);
      }
      long finish = System.nanoTime();
      long finishedAt = proof.clock.millis();
      var installElapsed = Duration.ofNanos(finish - installStart);
      var totalElapsed = Duration.ofNanos(finish - start);
      int installObservations = attestor.observationCount() - advanceObservations;
      LOGGER.info(
          "Mixed retained handoff calibration: items={}, lastMarkerLost={},"
              + " controlledObservationLatencyMs={}, advanceObservations={},"
              + " installObservations={}, installElapsedMs={}, totalElapsedMs={}",
          captured.size(),
          loseLastMarker,
          observationLatency.toMillis(),
          advanceObservations,
          installObservations,
          installElapsed.toMillis(),
          totalElapsed.toMillis());
      assertThat(advanceObservations).isPositive();
      assertThat(installObservations).isPositive();
      assertThat(installElapsed)
          .isGreaterThanOrEqualTo(observationLatency.multipliedBy(installObservations));
      assertThat(finishedAt).isLessThan(deadline);
      leases.requireCurrent(grant);
      assertThat(proof.itemImage(transferId)).isEqualTo(retainedItems);
      assertThat(proof.originalImage()).isEqualTo(originalReceipts);
      if (loseLastMarker) {
        assertThat(proof.currentOwner()).isEqualTo(proof.prior);
        assertThat(proof.transfers.readCurrent(transferId, last.ordinal()).controlState())
            .isEqualTo("FROZEN");
        assertThat(proof.physicalExecutionExists(last.execution())).isFalse();
        assertThatThrownBy(
                () ->
                    proof.installCalibrationTransfer(
                        transfer, transferId, grant, transferDeadline()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Original NEW capacity execution is indeterminate");
        assertThat(proof.currentOwner()).isEqualTo(proof.prior);
        assertThat(proof.itemImage(transferId)).isEqualTo(retainedItems);
        assertThat(proof.physicalExecutionExists(last.execution())).isFalse();
      } else {
        assertThat(installed).isNotNull();
        assertThat(installed.ownerInstanceId()).isEqualTo(identity.serviceInstanceId());
        assertThat(installed.leaseTokenCorrelation()).isEqualTo(grant.tokenCorrelation());
        assertThat(installed.executorFence()).isGreaterThan(proof.prior.executorFence());
        assertThat(proof.currentOwner()).isEqualTo(installed);
        assertThat(proof.transfers.readCurrent(transferId, last.ordinal()).controlState())
            .isEqualTo("COMPLETED");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void transfersTwoIndependentOriginsWithOptionalCapturedExecution(boolean captureExecution)
      throws Exception {
    try (var proof = new TransferSqlProof(true)) {
      var otherDecision = proof.otherDecision;
      assertThat(otherDecision.bindingDecisionId()).isNotEqualTo(proof.execution.transitionId());
      assertThat(otherDecision.candidate().characterId())
          .isNotEqualTo(proof.admission.candidate().bindingIdentity().characterId());
      assertThat(otherDecision.candidate().sessionId())
          .isNotEqualTo(proof.admission.candidate().bindingIdentity().sessionId());
      var otherRegion = proof.otherRegion;
      assertThat(
              CanonicalGameplayRegionRuntimeStatusRepository.sameRegionSource(
                  otherRegion.installation().source, proof.scope))
          .isTrue();
      assertThat(otherRegion.installation().source.lifecycleRequest().readRequestId())
          .isNotEqualTo(proof.scope.lifecycleRequest().readRequestId());
      assertThat(otherRegion.installation().grant.tokenCorrelation())
          .isEqualTo(proof.region.installation().grant.tokenCorrelation());
      assertThat(otherRegion.installation().status.executorFence())
          .isEqualTo(proof.prior.executorFence());

      var otherAdmission = proof.otherAdmission;
      var otherCapacity =
          CanonicalGameplayOriginalNewFinalAdmissionTestFixture.controlledCapacity(
              otherAdmission, proof.clock);
      var firstOrigin =
          proof
              .capacity(proof.sql)
              .prepareOrigin(proof.admission, proof.execution.request(), transferDeadline());
      var secondOrigin =
          proof
              .capacity(proof.sql)
              .prepareOrigin(otherAdmission, otherCapacity.request(), transferDeadline());
      assertThat(firstOrigin.transitionId()).isNotEqualTo(secondOrigin.transitionId());
      assertThat(firstOrigin.digest()).isNotEqualTo(secondOrigin.digest());
      assertThat(firstOrigin.candidateDigest()).isNotEqualTo(secondOrigin.candidateDigest());
      assertThat(firstOrigin.candidate().bindingIdentity().tenantId())
          .isEqualTo(secondOrigin.candidate().bindingIdentity().tenantId());
      assertThat(firstOrigin.candidate().bindingIdentity().gameInstanceId())
          .isEqualTo(secondOrigin.candidate().bindingIdentity().gameInstanceId());
      assertThat(firstOrigin.candidate().bindingIdentity().regionId())
          .isEqualTo(secondOrigin.candidate().bindingIdentity().regionId());
      assertThat(
              proof
                  .sql
                  .fetchSingle("SELECT count(*) FROM game_session_original_new_capacity_origin")
                  .get(0, Long.class))
          .isEqualTo(2L);
      if (captureExecution) proof.freezeWithCommittedOriginExecution(firstOrigin);
      var observationLatency = Duration.ofMillis(5);
      var regionAttestor =
          new CanonicalGameplayControlledRegionTransferObservationFixture(
              proof.clock, Duration.ofSeconds(30), observationLatency);
      var originAttestor =
          new CanonicalGameplayControlledOriginTransferObservationFixture(
              proof.clock, observationLatency);
      var originTransfers = new CanonicalGameplayCapacityOriginTransferRepository(proof.transfers);
      var transfer =
          new CanonicalGameplayRegionOwnerTransferCoordinator(
              proof.sql,
              proof.clock,
              proof.transfers,
              proof.capacity(proof.sql),
              regionAttestor.receiver(proof.transfers, proof.tags),
              originAttestor.receiver(originTransfers, proof.tags));

      try (var connection = proof.redis.connect(ByteArrayCodec.INSTANCE)) {
        assertThat(connection.sync().pexpire(ascii(proof.region.installation().grant.key()), 0L))
            .isTrue();
      }
      long deadline = transferDeadline();
      long start = System.nanoTime();
      var installed =
          CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
              proof.sql,
              proof.redis,
              proof.coordinationUri,
              proof.scripts,
              proof.region.installation(),
              proof.upstream.resolver,
              transfer,
              deadline);
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
      int observationCount = regionAttestor.observationCount() + originAttestor.observationCount();
      int signedOriginObservationCount = originAttestor.observationCount();
      if (captureExecution) {
        assertThat(regionAttestor.observationCount()).isPositive();
      } else {
        assertThat(regionAttestor.observationCount()).isZero();
      }
      assertThat(signedOriginObservationCount).isPositive();
      assertThat(elapsed).isGreaterThanOrEqualTo(observationLatency.multipliedBy(observationCount));
      assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
      LOGGER.info(
          "Two-origin transfer (captured execution={}) completed in {} ms with {} signed origin"
              + " observations and {} total signed observations",
          captureExecution,
          elapsed.toMillis(),
          signedOriginObservationCount,
          observationCount);
      assertThat(proof.clock.millis()).isLessThan(deadline);
      assertThat(installed.status.executorFence()).isGreaterThan(proof.prior.executorFence());
      assertThat(proof.currentOwner()).isEqualTo(installed.status);

      UUID transferId =
          proof
              .sql
              .fetchSingle(
                  "SELECT transfer_id FROM game_session_region_owner_transfer WHERE tenant_id=? AND"
                      + " game_instance_id=? AND region_id=? AND state='COMPLETED'",
                  proof.scope.tenantId(),
                  proof.scope.gameInstanceId(),
                  proof.scope.regionId())
              .get(0, UUID.class);
      var transferRows =
          proof.sql.fetch(
              "SELECT origin_digest,captured_state,fence_readback,incarnation_digest,verified_round"
                  + " FROM game_session_capacity_origin_transfer_item WHERE transfer_id=?",
              transferId);
      assertThat(transferRows).hasSize(2);
      assertThat(transferRows.getValues("origin_digest", String.class))
          .containsExactlyInAnyOrder(firstOrigin.digest(), secondOrigin.digest());
      assertThat(transferRows.getValues("captured_state", String.class))
          .containsExactlyInAnyOrder(captureExecution ? "DISPATCH_CLAIMED" : "OPEN", "OPEN");
      assertThat(transferRows.getValues("fence_readback", byte[].class))
          .allSatisfy(value -> assertThat(value).isNotNull().isNotEmpty());
      assertThat(transferRows.getValues("incarnation_digest", String.class))
          .allSatisfy(value -> assertThat(value).isNotNull());
      assertThat(transferRows.getValues("verified_round", UUID.class))
          .allSatisfy(value -> assertThat(value).isNotNull());

      var retainedOrigins =
          proof.sql.fetch(
              "SELECT * FROM game_session_original_new_capacity_origin ORDER BY origin_digest");
      assertThat(retainedOrigins).hasSize(2);
      assertThat(retainedOrigins.getValues("custody_owner_id", String.class))
          .containsOnly(installed.status.ownerInstanceId());
      assertThat(retainedOrigins.getValues("custody_owner_fence", Long.class))
          .containsOnly(installed.status.executorFence().longValueExact());
      assertThat(retainedOrigins.getValues("custody_transfer_id", UUID.class))
          .containsOnly(transferId);
      if (captureExecution) {
        assertThat(proof.transfers.readCurrent(transferId, 0).controlState())
            .isEqualTo("COMPLETED");
        assertThat(proof.physicalExecutionState(proof.execution)).isEqualTo("COMMITTED");
      }
      try (var connection = proof.redis.connect(ByteArrayCodec.INSTANCE)) {
        for (var row : retainedOrigins) {
          var origin = CanonicalGameplayCapacityOriginTransferRepository.decodeOrigin(row);
          Map<String, String> fields = new java.util.HashMap<>();
          connection
              .sync()
              .hgetall(ascii(origin.redisKey()))
              .forEach(
                  (key, value) ->
                      fields.put(
                          new String(key, StandardCharsets.US_ASCII),
                          new String(value, StandardCharsets.US_ASCII)));
          assertThat(fields)
              .containsExactlyInAnyOrderEntriesOf(
                  Map.of(
                      "origin", origin.digest(),
                      "owner", installed.status.ownerInstanceId(),
                      "fence", installed.status.executorFence().toString(),
                      "state", row.get("state", String.class)));
          assertThat(connection.sync().pttl(ascii(origin.redisKey()))).isEqualTo(-1L);
        }
      }
    }
  }

  private static final Logger LOGGER =
      LoggerFactory.getLogger(CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTest.class);
}
