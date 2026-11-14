package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewRegionHandoffHistoricalFinalIntegrationTest
    extends CanonicalGameplayOriginalNewFinalAdmissionOwnerIntegrationTestSupport {
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

  @Test
  void accountSqlFenceBeforeDelayedFinalCannotEstablishPreIntentCommitOrdering() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    // Capture the dispatch inputs before acquisition. This models an already-authorized delayed
    // final call; no OPEN hash is installed or refreshed after the SQL intent commits.
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      var barrier = new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl());
      UUID fenceRequest = UUID.randomUUID();
      var intent =
          barrier.beginFenceIntentWithFinalObservation(
              source.candidate().bindingIdentity().accountId(),
              fenceRequest,
              capacityOwner(),
              controlledCapacity.request(),
              controlledCapacity.reservation(),
              deadline());
      assertThat(intent.state().name()).isEqualTo("ACQUISITION_INTENT");
      assertThat(barrier.readFinalObservation(intent.operationId())).isEmpty();
      assertThat(
              barrier.readHistoricalFinalReceipt(
                  controlledCapacity.request(), controlledCapacity.reservation()))
          .isEmpty();
      assertThat(intent.cohortRows())
          .filteredOn(
              row ->
                  row.kind() == CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.TRANSITION
                      && row.sourceKey().equals(fixture.transitionId().toString()))
          .hasSize(1);
      String frozenSql = sqlImage();
      var beforeFinal = physicalImage();
      // The SQL intent is not the Redis CLOSED linearization point. The existing script still
      // accepts these original inputs against the unchanged exact physical OPEN barriers.
      try (var actual =
          finalOwner()
              .finalizeOriginalNewOnce(
                  source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
        assertThat(admin.get(ascii(installation.sessionKey())))
            .containsExactly(actual.exactCanonicalBytes());
        assertThat(physicalImage()).isNotEqualTo(beforeFinal);
        assertThat(sqlImage()).isEqualTo(frozenSql);
        var frozen = barrier.readCurrentFenceIntent(intent.operationId());
        assertThat(frozen.inventorySnapshotRevision())
            .isEqualTo(intent.inventorySnapshotRevision());
        assertThat(frozen.cohortDigest()).isEqualTo(intent.cohortDigest());
        assertThat(frozen.cohortRows()).usingRecursiveComparison().isEqualTo(intent.cohortRows());
        // A genuine physical positive and matching preexisting frozen candidate do not prove
        // FINAL preceded SQL acquisition. Preserve all existing admission/recovery refusals.
        assertThatThrownBy(() -> inventory.commitOriginalNewFinalAdmission(actual))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessage("Account-fence acquisition intent blocks a new binding transition");
        assertThatThrownBy(
                () ->
                    capacityOwner()
                        .retainCommittedBeforeCleanup(
                            controlledCapacity.request(), controlledCapacity.reservation()))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
        assertThatThrownBy(
                () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessage("Original NEW transition is not final-readable");
        assertThat(sqlImage()).isEqualTo(frozenSql);
        assertThat(admin.get(ascii(installation.sessionKey())))
            .containsExactly(actual.exactCanonicalBytes());
        assertThat(
                barrier
                    .beginFenceIntentWithFinalObservation(
                        intent.accountId(),
                        fenceRequest,
                        capacityOwner(),
                        controlledCapacity.request(),
                        controlledCapacity.reservation(),
                        deadline())
                    .operationId())
            .isEqualTo(intent.operationId());
        assertThat(barrier.readFinalObservation(intent.operationId())).isEmpty();
        assertThat(
                barrier.readHistoricalFinalReceipt(
                    controlledCapacity.request(), controlledCapacity.reservation()))
            .isEmpty();
      }
    }
  }

  @Test
  void fenceOwnerDoesNotRetainObservedFinalAfterInventoryRevisionChangesBeforeAcquisition()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      byte[] finalBytes = admin.get(ascii(installation.sessionKey()));
      assertThat(finalBytes).containsExactly(actual.exactCanonicalBytes());
      var accountId = source.candidate().bindingIdentity().accountId();
      UUID requestId = UUID.randomUUID();
      BigInteger observedRevision =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                      + " WHERE singleton_id=1")
              .get(0, java.math.BigDecimal.class)
              .toBigIntegerExact();
      var observed = new AtomicBoolean();
      var displaced = new AtomicBoolean();
      var observer =
          new org.jooq.impl.DefaultExecuteListener() {
            @Override
            public void executeStart(org.jooq.ExecuteContext context) {
              String sql = context.sql();
              if (sql == null
                  || !sql.contains("game_session_canonical_binding_inventory_clock")
                  || !sql.contains("FOR UPDATE")
                  || !displaced.compareAndSet(false, true)) return;
              assertThat(observed).as("exact physical FINAL was observed before locking").isTrue();
              // This is the transaction's acquisition lock, after physical FINAL observation.
              // Advance the shared source revision on an independent committed connection before
              // the locking SELECT reads it.
              fixture
                  .dsl()
                  .execute(
                      "UPDATE game_session_canonical_binding_inventory_clock SET inventory_revision"
                          + " = inventory_revision + 1 WHERE singleton_id = 1");
            }
          };
      var observedSql =
          org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
      var barrier = new CanonicalGameplayAccountAdmissionBarrierRepository(observedSql);
      long observationDeadline = deadline();
      var intent =
          barrier.beginFenceIntentWithFinalObservation(
              accountId,
              requestId,
              observingCapacityOwner(observed, observationDeadline),
              controlledCapacity.request(),
              controlledCapacity.reservation(),
              observationDeadline);

      assertThat(displaced).as("inventory revision changed after the FINAL readback").isTrue();
      assertThat(intent.state().name()).isEqualTo("ACQUISITION_INTENT");
      assertThat(intent.inventorySnapshotRevision()).isGreaterThan(observedRevision);
      assertThat(barrier.readFinalObservation(intent.operationId())).isEmpty();
      var transition =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, original_new_final_session_sha256 FROM"
                      + " game_session_canonical_binding_transition WHERE transition_id=?",
                  fixture.transitionId());
      assertThat(transition.get("status", String.class)).isEqualTo("PROVISIONAL");
      assertThat(transition.get("original_new_final_session_sha256", String.class)).isNull();
      assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(finalBytes);
    }
  }

  @Test
  void fenceOwnerDoesNotRetainObservedFinalAfterItsDeadlineExpiresBeforeAcquisition()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      byte[] finalBytes = admin.get(ascii(installation.sessionKey()));
      assertThat(finalBytes).containsExactly(actual.exactCanonicalBytes());
      var accountId = source.candidate().bindingIdentity().accountId();
      UUID requestId = UUID.randomUUID();
      long observationDeadline = CLOCK.millis() + 5_000L;
      long expiredAt = observationDeadline + 1L;
      var barrier =
          new CanonicalGameplayAccountAdmissionBarrierRepository(
              fixture.dsl(), Clock.fixed(Instant.ofEpochMilli(expiredAt), ZoneOffset.UTC));
      var observed = new AtomicBoolean();
      var intent =
          barrier.beginFenceIntentWithFinalObservation(
              accountId,
              requestId,
              observingCapacityOwner(observed, observationDeadline),
              controlledCapacity.request(),
              controlledCapacity.reservation(),
              observationDeadline);

      assertThat(observed)
          .as("exact physical FINAL was observed before expiry was checked")
          .isTrue();
      assertThat(intent.state().name()).isEqualTo("ACQUISITION_INTENT");
      assertThat(barrier.readFinalObservation(intent.operationId())).isEmpty();
      var transition =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, original_new_final_session_sha256 FROM"
                      + " game_session_canonical_binding_transition WHERE transition_id=?",
                  fixture.transitionId());
      assertThat(transition.get("status", String.class)).isEqualTo("PROVISIONAL");
      assertThat(transition.get("original_new_final_session_sha256", String.class)).isNull();
      assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(finalBytes);
    }
  }

  @Test
  void fenceOwnerRetainsObservedFinalWithoutPromotingFrozenInventory() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      var accountId = source.candidate().bindingIdentity().accountId();
      UUID fenceRequest = UUID.randomUUID();
      var physical = physicalImage();
      var revision =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                      + " WHERE singleton_id=1")
              .get(0, java.math.BigDecimal.class)
              .toBigIntegerExact();
      var barrier = new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl());
      var intent =
          barrier.beginFenceIntentWithFinalObservation(
              accountId,
              fenceRequest,
              capacityOwner(),
              controlledCapacity.request(),
              controlledCapacity.reservation(),
              deadline());
      var recreated = new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl());
      var receipt = recreated.readFinalObservation(intent.operationId()).orElseThrow();
      assertThat(receipt.transitionId()).isEqualTo(fixture.transitionId());
      assertThat(receipt.admissionAttemptId())
          .isEqualTo(controlledCapacity.request().admissionAttemptId());
      assertThat(receipt.executionDigest()).isEqualTo(controlledCapacity.digest());
      assertThat(receipt.candidateDigest()).isEqualTo(source.candidateDigest());
      assertThat(receipt.finalSessionSha256()).isEqualTo(actual.finalSessionSha256());
      assertThat(receipt.admissionAtMillis()).isEqualTo(actual.admissionAtMillis());
      assertThat(receipt.continuityExpiresAtMillis()).isEqualTo(actual.continuityExpiresAtMillis());
      assertThat(receipt.inventorySnapshotRevision()).isEqualTo(revision);
      assertThat(receipt.cohortDigest()).isEqualTo(intent.cohortDigest());
      assertThat(receipt.barrierTargetDigest()).isEqualTo(intent.barrierTargetDigest());
      var historical =
          recreated
              .readHistoricalFinalReceipt(
                  controlledCapacity.request(), controlledCapacity.reservation())
              .orElseThrow();
      assertThat(historical.accountId()).isEqualTo(accountId);
      assertThat(historical.tenantId())
          .isEqualTo(controlledCapacity.request().identity().tenantId());
      assertThat(historical.operationId()).isEqualTo(intent.operationId());
      assertThat(historical.observation()).isEqualTo(receipt);
      assertThat(historical.execution().transitionId()).isEqualTo(fixture.transitionId());
      assertThat(historical.execution().request().mutationDigest())
          .isEqualTo(controlledCapacity.request().mutationDigest());
      assertThat(historical.execution().reservation().reservationId())
          .isEqualTo(controlledCapacity.reservation().reservationId());
      assertThat(historical.execution().reservation().reservationFence())
          .isEqualTo(controlledCapacity.reservation().reservationFence());
      var wrongReservationFence = controlledCapacity.reservation();
      var mismatchedFence =
          new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result(
              wrongReservationFence.identity(),
              wrongReservationFence.admissionAttemptId(),
              wrongReservationFence.capacityRearmFence(),
              wrongReservationFence.mutationDigest(),
              wrongReservationFence.reservationId(),
              wrongReservationFence.reservationFence() + 1,
              wrongReservationFence.expiresAt(),
              wrongReservationFence.state(),
              wrongReservationFence.snapshotIdentity(),
              wrongReservationFence.evaluationIdentity());
      assertThat(
              recreated.readHistoricalFinalReceipt(controlledCapacity.request(), mismatchedFence))
          .isEmpty();
      var request = controlledCapacity.request();
      var wrongAttempt =
          new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request(
                  request.identity(),
                  UUID.randomUUID(),
                  request.capacityRearmFence(),
                  request.targetNamespace(),
                  request.context(),
                  request.operation(),
                  request.priorShape(),
                  request.requestedShape(),
                  request.evidence(),
                  request.capacityDelta(),
                  null)
              .withDigest();
      assertThat(
              recreated.readHistoricalFinalReceipt(wrongAttempt, controlledCapacity.reservation()))
          .isEmpty();
      for (String mutation :
          java.util.List.of(
              "UPDATE game_session_account_fence_final_observation"
                  + " SET inventory_snapshot_revision = inventory_snapshot_revision + 1",
              "DELETE FROM game_session_account_fence_final_observation",
              "TRUNCATE game_session_account_fence_final_observation")) {
        assertThatThrownBy(() -> fixture.dsl().execute(mutation))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .rootCause()
            .isInstanceOfSatisfying(
                org.postgresql.util.PSQLException.class,
                failure -> {
                  assertThat(failure.getSQLState()).isEqualTo("23514");
                  assertThat(failure.getMessage())
                      .contains("Historical FINAL observation is immutable");
                });
        assertThat(recreated.readFinalObservation(intent.operationId())).contains(receipt);
      }
      var transition =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, original_new_final_session_sha256 FROM"
                      + " game_session_canonical_binding_transition WHERE transition_id=?",
                  fixture.transitionId());
      assertThat(transition.get("status", String.class)).isEqualTo("PROVISIONAL");
      assertThat(transition.get("original_new_final_session_sha256", String.class)).isNull();
      var frozenSql = sqlImage();
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .retainCommittedBeforeCleanup(
                          controlledCapacity.request(), controlledCapacity.reservation()))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(recreated.beginFenceIntent(accountId, fenceRequest).cohortDigest())
          .isEqualTo(intent.cohortDigest());
      assertThat(recreated.readFinalObservation(intent.operationId())).contains(receipt);
      assertThat(sqlImage()).isEqualTo(frozenSql);
      assertThat(physicalImage()).isEqualTo(physical);
    }
  }

  private CanonicalGameplayOriginalNewCapacityExecutionOwner observingCapacityOwner(
      AtomicBoolean observed, long deadline) {
    var owner = spy(capacityOwner());
    doAnswer(
            call -> {
              Object actual = call.callRealMethod();
              observed.set(actual != null);
              return actual;
            })
        .when(owner)
        .readFinalBeforeAccountIntent(
            controlledCapacity.request(), controlledCapacity.reservation(), deadline);
    return owner;
  }
}
