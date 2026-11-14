package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical component tests for retained original NEW capacity outcomes and exact closure. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewCapacityRetentionOwnerIntegrationTest
    extends CanonicalGameplayOriginalNewFinalAdmissionOwnerTestSupport {

  @Test
  void failedPositiveRetentionRollsBackFinalReceiptAndExactRecoveryCommitsBoth() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    String before = sqlImage();
    var retentionAttempted = new java.util.concurrent.atomic.AtomicBoolean();
    var observer =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String query = context.sql();
            if (query == null
                || !query.startsWith("UPDATE game_session_original_new_capacity_execution")
                || !query.contains("terminal_readback")) return;
            retentionAttempted.set(true);
            assertThat(sqlImage()).isEqualTo(before);
            throw new IllegalStateException("Controlled positive execution retention failure");
          }
        };
    var observedSql =
        org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
    var observedInventory = new CanonicalGameplayBindingInventoryRepository(observedSql);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
      var committedRedis = physicalImage();
      assertThatThrownBy(
              () -> observedInventory.commitOriginalNewFinalAdmission(readback, CLOCK, deadline()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Controlled positive execution retention failure");
      assertThat(retentionAttempted).isTrue();
      assertThat(sqlImage()).isEqualTo(before);
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
          .isInstanceOf(IllegalStateException.class);
      var recovered =
          capacityOwner()
              .retainCommittedBeforeCleanup(
                  controlledCapacity.request(), controlledCapacity.reservation(), deadline());
      assertThat(recovered.disposition()).isEqualTo("COMMITTED");
      assertThat(recovered.physicalDigest()).isEqualTo("sha256:" + readback.finalSessionSha256());
      assertThat(
              inventory
                  .readOriginalNewFinalAdmissionSource(decision)
                  .finalReceipt()
                  .orElseThrow()
                  .finalSessionSha256())
          .isEqualTo(readback.finalSessionSha256());
      assertThat(
              capacityOwner()
                  .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                  .canonicalReadback())
          .containsExactly(recovered.canonicalReadback());
      assertThat(physicalImage()).isEqualTo(committedRedis);
    }
  }

  @Test
  void historicalFinalReceiptWithoutCapacityEvidenceCannotMintCurrentSource() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      inventory.commitOriginalNewFinalAdmission(readback);
    }

    fixture
        .dsl()
        .transaction(
            configuration -> {
              var historical = org.jooq.impl.DSL.using(configuration);
              // Reproduce the retained pre-V1063 receipt shape without granting a normal writer
              // this mutation.
              historical.execute("SET LOCAL session_replication_role = replica");
              assertThat(
                      historical.execute(
                          "UPDATE game_session_canonical_binding_transition"
                              + " SET original_new_capacity_execution_digest = NULL WHERE transition_id = ?",
                          fixture.transitionId()))
                  .isEqualTo(1);
            });

    String retained = sqlImage();
    var physical = physicalImage();
    assertThatThrownBy(() -> inventory.readOriginalNewFinalAdmissionSource(decision))
        .hasMessageContaining(
            "Historical original NEW receipt lacks exact capacity execution evidence");
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(physicalImage()).isEqualTo(physical);
  }

  @Test
  void previouslySplitReceiptRequiresExactPhysicalProofAndRetainsIdempotently() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
      commitHistoricalSplitReceipt(readback);
    }
    var request = controlledCapacity.request();
    var reservation = controlledCapacity.reservation();
    var owner = capacityOwner();
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_session_original_new_capacity_execution"
                            + " SET status = 'CLOSED', terminal_readback = ? WHERE execution_digest = ?",
                        new byte[] {1},
                        controlledCapacity.digest()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("retained final receipt");
    assertThatThrownBy(() -> owner.readExact(request, reservation))
        .isInstanceOf(IllegalStateException.class);
    var originalPhysical = physicalImage();
    byte[] sessionKey = ascii(installation.sessionKey());
    byte[] originalSession = admin.get(sessionKey);
    assertThat(admin.pexpire(sessionKey, 0)).isTrue();
    assertThatThrownBy(() -> owner.retainCommittedBeforeCleanup(request, reservation, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> owner.readExact(request, reservation))
        .isInstanceOf(IllegalStateException.class);
    admin.set(sessionKey, originalSession);
    var retained = owner.retainCommittedBeforeCleanup(request, reservation, deadline());
    assertThat(retained.disposition()).isEqualTo("COMMITTED");
    assertThat(
            owner
                .retainCommittedBeforeCleanup(request, reservation, deadline())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
    assertThat(owner.readExact(request, reservation).canonicalReadback())
        .containsExactly(retained.canonicalReadback());
    assertThat(physicalImage()).isEqualTo(originalPhysical);
  }

  @Test
  void committedFinalAdmissionReplayAtomicallyRetainsPreviouslySplitExecutionEvidence()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      commitHistoricalSplitReceipt(readback);
      var authorized =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, terminal_readback FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                  readback.execution().digest());
      assertThat(authorized.get("status", String.class)).isEqualTo("AUTHORIZED");
      assertThat(authorized.get("terminal_readback", byte[].class)).isNull();

      inventory.commitOriginalNewFinalAdmission(readback);

      var retainedRow =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, terminal_readback FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                  readback.execution().digest());
      assertThat(retainedRow.get("status", String.class)).isEqualTo("COMMITTED");
      assertThat(retainedRow.get("terminal_readback", byte[].class)).isNotNull();
      var retained =
          capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
      assertThat(retained.disposition()).isEqualTo("COMMITTED");
      assertThat(retained.physicalDigest()).isEqualTo("sha256:" + readback.finalSessionSha256());
      byte[] exactProof = retained.canonicalReadback();

      inventory.commitOriginalNewFinalAdmission(readback);

      assertThat(
              capacityOwner()
                  .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                  .canonicalReadback())
          .containsExactly(exactProof);
    }
  }

  @Test
  void committedRedisWinnerRecoversFullPositiveSqlRetentionBeforeCleanup() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    byte[] committed;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      committed = admin.get(ascii(installation.sessionKey()));
    }
    // A lost SQL completion keeps the durable provisional fence: STOP cannot race retention.
    assertThatThrownBy(() -> fixture.dsl().execute("UPDATE game_instances SET status='STOPPED'"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("runtime row is fenced");
    // No SQL final receipt was retained by the interrupted final caller.
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id=?",
                    fixture.transitionId())
                .get(0, String.class))
        .isNull();
    var retained =
        capacityOwner()
            .retainCommittedBeforeCleanup(
                controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(retained.disposition()).isEqualTo("COMMITTED");
    assertThat(retained.physicalDigest())
        .isEqualTo(
            "sha256:"
                + net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution
                    .sha256(committed));
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("COMMITTED");
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(committed);
  }

  @Test
  void lostTopologyCannotBeReallocatedDuringPositiveRetention() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    byte[] committed;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      committed = admin.get(ascii(installation.sessionKey()));
    }
    fixture
        .dsl()
        .transaction(
            configuration -> {
              var damaged = org.jooq.impl.DSL.using(configuration);
              // Simulate lost durable topology, not an authorized mapping retirement.
              damaged.execute("SET LOCAL session_replication_role = replica");
              damaged.execute("DELETE FROM game_session_canonical_tenant_gameplay_tag");
            });
    var physical = physicalImage();
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .retainCommittedBeforeCleanup(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessage("Retained capacity execution mapping is unavailable");
    assertThat(
            fixture
                .dsl()
                .fetchCount(org.jooq.impl.DSL.table("game_session_canonical_tenant_gameplay_tag")))
        .isZero();
    assertThat(physicalImage()).isEqualTo(physical);
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(committed);
  }

  @Test
  void committedOriginalRetainsExactTerminalEvidenceAndReplay() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    byte[] committed;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      inventory.commitOriginalNewFinalAdmission(readback);
      committed = admin.get(ascii(installation.sessionKey()));
    }
    var retained =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(retained.disposition()).isEqualTo("COMMITTED");
    assertThat(retained.physicalDigest())
        .isEqualTo(
            "sha256:"
                + net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution
                    .sha256(committed));
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(committed);
  }

  @Test
  void committedHashWithoutCompletePositiveServingProofStillBlocksOwnerMovement() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      admin.del(ascii(installation.sessionKey()));
    }
    var original = regionBinding.installation();
    assertThatThrownBy(() -> replaceRegionOwner("successor-without-positive-serving-proof"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
                .find(original.source)
                .orElseThrow()
                .executorFence())
        .isEqualTo(original.status.executorFence());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("AUTHORIZED");
    assertThat(
            admin.hget(
                ascii(capacityKey(tags.assignmentForTransition(fixture.transitionId()))),
                ascii("state")))
        .containsExactly(ascii("COMMITTED"));
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void retainedClosedProofSurvivesControlledServingRemovalAndStillRejectsDelayedFinal()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    var account = controlledAccountOpen();
    var issuer = issuerOpen();
    capacityOwner()
        .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var retained =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    // Controlled physical retirement only. The existing genuine never-admitted owner accepts
    // untouched PREPARED attempts, not this already-acknowledged PROVISIONAL candidate.
    controlledRemoveServingKeys(source, assignment);
    var replay =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(replay.canonicalReadback()).containsExactly(retained.canonicalReadback());
    assertThat(replay.currentOwnerId()).isEqualTo(retained.currentOwnerId());
    assertThat(replay.currentOwnerFence()).isEqualTo(retained.currentOwnerFence());
    assertThat(replay.execution().selectedOwnerFence())
        .isEqualTo(controlledCapacity.selectedOwnerFence());
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          source, template, account, issuer, controlledCapacity, deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(admin.hget(ascii(capacityKey(assignment)), ascii("state")))
        .containsExactly(ascii("CLOSED"));
    assertThat(admin.type(ascii(installation.sessionKey()))).isEqualTo("none");
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
  }

  @Test
  void concurrentExactClosuresReplayIdenticalRetainedProof() throws Exception {
    repairAccountMember();
    bridge();
    var firstOwner = capacityOwner();
    var secondOwner = capacityOwner();
    var start = new java.util.concurrent.CountDownLatch(1);
    var callers = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first =
          callers.submit(
              () -> {
                start.await();
                firstOwner.closeOriginalAttempt(
                    controlledCapacity.request(), controlledCapacity.reservation());
                return true;
              });
      var second =
          callers.submit(
              () -> {
                start.await();
                secondOwner.closeOriginalAttempt(
                    controlledCapacity.request(), controlledCapacity.reservation());
                return true;
              });
      start.countDown();
      assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } finally {
      callers.shutdownNow();
    }

    var retained =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(retained.disposition()).isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
    assertThat(retained.canonicalReadback()).isNotEmpty();
    var row =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT status, terminal_readback FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                retained.execution().digest());
    assertThat(row.get("status", String.class)).isEqualTo("CLOSED");
    assertThat(row.get("terminal_readback", byte[].class))
        .containsExactly(retained.canonicalReadback());
  }

  @Test
  void closeReplayAfterServingRetirementUsesRetainedClosedProof() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    var owner = capacityOwner();
    owner.closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var retained = owner.readExact(controlledCapacity.request(), controlledCapacity.reservation());
    controlledRemoveServingKeys(source, assignment);
    String rows = sqlImage();
    var physical = physicalImage();

    owner.closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());

    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
    var replay = owner.readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(replay.disposition()).isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
    assertThat(replay.canonicalReadback()).containsExactly(retained.canonicalReadback());
  }

  @Test
  void retainedClosedProofRequiresItsExactTombstoneAndReservationFence() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    capacityOwner()
        .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var original = controlledCapacity.reservation();
    var changedFence =
        new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result(
            original.identity(),
            original.admissionAttemptId(),
            original.capacityRearmFence(),
            original.mutationDigest(),
            original.reservationId(),
            original.reservationFence() + 1,
            original.expiresAt(),
            original.state(),
            original.snapshotIdentity(),
            original.evaluationIdentity());
    assertThatThrownBy(() -> capacityOwner().readExact(controlledCapacity.request(), changedFence))
        .isInstanceOf(IllegalStateException.class);
    String rows = sqlImage();
    admin.del(ascii(capacityKey(assignment)));
    assertThatThrownBy(() -> capacityOwner().readExact(controlledCapacity.request(), original))
        .isInstanceOf(IllegalStateException.class);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var bound = raw.bindCapacityExecution(controlledCapacity)) {
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .authorize(
                          source,
                          bound,
                          controlledAccountOpen(),
                          issuerOpen(),
                          controlledCapacity,
                          deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(admin.type(ascii(capacityKey(assignment)))).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(rows);
  }

  @Test
  void closedExecutionRejectsContradictoryFinalSqlReceiptWithoutLosingItsRetainedProof()
      throws Exception {
    repairAccountMember();
    bridge();
    capacityOwner()
        .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var retained =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    String rows = sqlImage();
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_session_canonical_binding_transition SET original_new_final_session_sha256 = ?, original_new_capacity_execution_digest = ?, original_new_admission_at_ms = ?, original_new_continuity_expires_at_ms = ?, original_new_session_duration_ms = ? WHERE transition_id = ?",
                        "a".repeat(64),
                        controlledCapacity.digest(),
                        CLOCK.millis(),
                        CLOCK.millis() + 1000,
                        1000L,
                        fixture.transitionId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining(
            "Original NEW final receipt requires its exact authorized capacity execution");
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
  }

  @Test
  void physicalClosureBeforeSqlProofRetentionRemainsIndeterminateEvenAfterServingRemoval()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    assertThat(
            new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                    ownerClient, catalog, CLOCK, tags)
                .closeCapacityExecutionOnce(controlledCapacity, source.candidate(), assignment))
        .isEqualTo(4);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    controlledRemoveServingKeys(source, assignment);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("AUTHORIZED");
  }

  @Test
  void recreatedCapacityOwnerRetainsCommittedSqlBeforeServingCleanup() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    String finalDigest;
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
      inventory.commitOriginalNewFinalAdmission(actual);
      finalDigest = actual.finalSessionSha256();
    }
    // A recreated owner can read the retained positive immediately after the receipt commits.
    String rows = sqlImage();
    var physical = physicalImage();
    var retained =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(retained.disposition()).isEqualTo("COMMITTED");
    assertThat(retained.physicalDigest()).isEqualTo("sha256:" + finalDigest);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    controlledRemoveServingKeys(source, assignment);
    var replay =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(replay.canonicalReadback()).containsExactly(retained.canonicalReadback());
    assertThat(
            capacityOwner()
                .retainCommittedBeforeCleanup(
                    controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
  }

  @Test
  void actualFinalBeforeAccountFenceRetainsPhysicalPositiveButRecoveryRemainsIndeterminate()
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
      // This is the real physical FINAL, not an injected SQL or Redis positive receipt.
      byte[] finalBytes = admin.get(ascii(installation.sessionKey()));
      assertThat(finalBytes).containsExactly(actual.exactCanonicalBytes());
      assertThat(
              net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution.sha256(
                  finalBytes))
          .isEqualTo(actual.finalSessionSha256());
      var pending =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id=?",
                  fixture.transitionId());
      assertThat(pending.get("status", String.class)).isEqualTo("PROVISIONAL");
      assertThat(pending.get("original_new_final_session_sha256", String.class)).isNull();
      // The crash window intentionally precedes SQL commit. Physical FINAL is positive evidence,
      // but the Account-facing reader requires COMMITTED and must not expose it as final-readable.
      String beforeReader = sqlImage();
      var physicalBeforeFence = physicalImage();
      assertThatThrownBy(
              () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage("Original NEW transition is not final-readable");
      assertThat(sqlImage()).isEqualTo(beforeReader);
      assertThat(physicalImage()).isEqualTo(physicalBeforeFence);
      assertThat(inventory.readOriginalNewFinalAdmissionSource(decision).transitionStatus())
          .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL);
      var barrier = new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl());
      var intent =
          barrier.beginFenceIntent(
              source.candidate().bindingIdentity().accountId(), UUID.randomUUID());
      assertThat(intent.state().name()).isEqualTo("ACQUISITION_INTENT");
      assertThat(intent.cohortRows())
          .filteredOn(
              row ->
                  row.kind() == CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.TRANSITION
                      && row.sourceKey().equals(fixture.transitionId().toString()))
          .hasSize(1);
      assertThat(physicalImage()).isEqualTo(physicalBeforeFence);
      String rows = sqlImage();
      var physical = physicalImage();
      for (int attempt = 0; attempt < 2; attempt++) {
        // Current authority deliberately cannot promote this historical positive through a
        // newer frozen Account cohort. A future receipt-only recovery owner needs its own proof.
        assertThatThrownBy(
                () ->
                    new CanonicalGameplayBindingInventoryRepository(fixture.dsl())
                        .commitOriginalNewFinalAdmission(actual))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessage("Account-fence acquisition intent blocks a new binding transition");
        assertThatThrownBy(
                () ->
                    new CanonicalGameplayBindingInventoryRepository(fixture.dsl())
                        .readOriginalNewFinalAdmissionSource(decision))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessage("Account-fence acquisition intent blocks a new binding transition");
        assertThatThrownBy(
                () ->
                    capacityOwner()
                        .retainCommittedBeforeCleanup(
                            controlledCapacity.request(), controlledCapacity.reservation()))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
        assertThatThrownBy(
                () ->
                    capacityOwner()
                        .closeOriginalAttempt(
                            controlledCapacity.request(), controlledCapacity.reservation()))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
        assertThatThrownBy(
                () ->
                    capacityOwner()
                        .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
            .isInstanceOf(IllegalStateException.class);
        // This remains the SQL-commit guard; the source/commit assertions above independently
        // prove that the newer Account fence prevents reconciliation of the physical positive.
        assertThatThrownBy(
                () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessage("Original NEW transition is not final-readable");
        assertThat(sqlImage()).isEqualTo(rows);
        assertThat(physicalImage()).isEqualTo(physical);
        assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(finalBytes);
        var frozen = barrier.readCurrentFenceIntent(intent.operationId());
        assertThat(frozen.state()).isEqualTo(intent.state());
        assertThat(frozen.inventorySnapshotRevision())
            .isEqualTo(intent.inventorySnapshotRevision());
        assertThat(frozen.cohortDigest()).isEqualTo(intent.cohortDigest());
        assertThat(frozen.operationFence()).isEqualTo(intent.operationFence());
        assertThat(frozen.plannedAccountAdmissionFence())
            .isEqualTo(intent.plannedAccountAdmissionFence());
        assertThat(frozen.cohortRows()).usingRecursiveComparison().isEqualTo(intent.cohortRows());
        assertThat(frozen.barrierTargetDigest()).isEqualTo(intent.barrierTargetDigest());
        assertThat(frozen.barrierTargets())
            .usingRecursiveComparison()
            .isEqualTo(intent.barrierTargets());
      }
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                      controlledCapacity.digest())
                  .get(0, String.class))
          .isEqualTo("AUTHORIZED");
    }
  }

  @Test
  void missingCommittedServingEvidenceCannotReleaseOriginalOwnerCustody() throws Exception {
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
      commitHistoricalSplitReceipt(actual);
    }
    assertThat(admin.del(ascii(installation.sessionKey()))).isEqualTo(1L);
    String rows = sqlImage();
    var physical = physicalImage();
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .retainCommittedBeforeCleanup(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> replaceRegionOwner("successor-with-missing-committed-evidence"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
  }
}
