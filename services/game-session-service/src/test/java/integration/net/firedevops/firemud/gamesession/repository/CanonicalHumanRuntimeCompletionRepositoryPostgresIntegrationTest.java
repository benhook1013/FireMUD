package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalHumanRuntimeCompletionRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import net.firedevops.firemud.gamesession.service.impl.ProtectedCanonicalInitialAdmissionCoordinator;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** Storage proof for the unregistered protected Human STARTING completion owner. */
@Testcontainers(disabledWithoutDocker = true)
class CanonicalHumanRuntimeCompletionRepositoryPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void coordinatorResumesCompletedRuntimeFromRetainedProtectionAndReplaysTerminalExactly()
      throws Exception {
    var harness = CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres);
    try {
      var prepared = prepareStarting(harness, "protected-coordinator-resume", 1151);
      var accountRequest = prepared.protection().request();
      var acquisitionCalls = new AtomicInteger();
      var worldReads = new AtomicInteger();
      // Controlled Account receipt and World verifier output; this is Game Session owner proof
      // only.
      AccountStartSessionAdmissionProtectionAcquisitionClient accountClient =
          request -> {
            acquisitionCalls.incrementAndGet();
            assertThat(request.canonicalBytes()).containsExactly(accountRequest.canonicalBytes());
            return prepared.protection();
          };
      CanonicalInitialAdmissionWorldVerifier worldVerifier =
          request -> {
            worldReads.incrementAndGet();
            assertThat(request).isEqualTo(prepared.request());
            return prepared.worldProof();
          };

      var catalog = new GameSessionCanonicalRealmCatalogRepository(harness.dsl());
      var events = new GameplayAdmissionPointerEventRepository(harness.dsl());
      var pointers =
          new GameSessionCanonicalAdmissionPointerRepository(harness.dsl(), catalog, events);
      var interruptedAdmission =
          new CanonicalInitialAdmissionRepository(harness.dsl(), catalog, pointers) {
            @Override
            public void commitProtected(
                CanonicalInitialAdmissionRequest request,
                AccountStartSessionAdmissionProtectionEvidence protection,
                CanonicalInitialAdmissionWorldProof worldProof) {
              throw new IllegalStateException("controlled interruption after runtime completion");
            }
          };
      var interruptedCoordinator =
          coordinator(harness, interruptedAdmission, accountClient, worldVerifier);

      assertThatThrownBy(() -> interruptedCoordinator.bind(prepared.request(), accountRequest))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("controlled interruption after runtime completion");
      assertThat(runtime(harness, prepared).get("status", String.class)).isEqualTo("RUNNING");
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("PENDING");
      assertThat(
              new CanonicalHumanRuntimeCompletionRepository(harness.dsl())
                  .read(
                      prepared.request().targetNamespace(),
                      prepared.request().initialAdmissionRequestId()))
          .isPresent();
      assertNoAdmissionEffects(harness, prepared.request());
      assertThat(acquisitionCalls).hasValue(1);

      var resumedCoordinator =
          coordinator(harness, admissionRepository(harness), accountClient, worldVerifier);
      var committed = resumedCoordinator.bind(prepared.request(), accountRequest);
      assertThat(committed.outcome())
          .isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("COMMITTED");
      assertThat(ownerCount(harness, "gameplay_admission_pointer", prepared.request())).isOne();
      assertThat(ownerCount(harness, "gameplay_admission_pointer_event", prepared.request()))
          .isOne();
      assertThat(acquisitionCalls).hasValue(1);
      assertThat(worldReads).hasValue(2);

      var replay = resumedCoordinator.bind(prepared.request(), accountRequest);
      assertThat(replay).isEqualTo(committed);
      assertThat(acquisitionCalls).hasValue(1);
      assertThat(worldReads).hasValue(2);

      var originalHold = accountRequest.worldAdmissionHoldIdentity();
      var changedHold =
          new WorldCanonicalInitialAdmissionHold.HoldIdentity(
              originalHold.request(), originalHold.holdId(), UUID.randomUUID());
      var changedAdmission = request(changedHold);
      var changedAccountRequest =
          AccountStartSessionAdmissionProtectionRequest.create(
              accountRequest.originalPostAuthorizationTupleBytes(),
              accountRequest.accountRedemptionProjectionBytes(),
              accountRequest.gameSessionOwnerMutationId(),
              accountRequest.gameSessionOwnerAttemptId(),
              accountRequest.gameSessionOwnerFence(),
              accountRequest.originalLeaseExpiresAt(),
              accountRequest.accountWorldParticipationId(),
              accountRequest.accountWorldParticipationFence(),
              changedHold);
      assertThatThrownBy(() -> resumedCoordinator.bind(changedAdmission, changedAccountRequest))
          .isInstanceOf(
              CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException.class)
          .hasMessageContaining("complete request");
      assertThat(acquisitionCalls).hasValue(1);
      assertThat(worldReads).hasValue(2);
      assertThat(ownerCount(harness, "gameplay_admission_pointer", prepared.request())).isOne();
    } finally {
      harness.close();
    }
  }

  @Test
  void worldFailureLeavesProtectedAttemptPendingUntilExactExplicitAbort() throws Exception {
    var harness = CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres);
    try {
      var prepared = prepareStarting(harness, "protected-coordinator-world-failure", 1161);
      var accountRequest = prepared.protection().request();
      var acquisitionCalls = new AtomicInteger();
      // Controlled Account receipt; the World verifier below deliberately reports an outage.
      AccountStartSessionAdmissionProtectionAcquisitionClient accountClient =
          request -> {
            acquisitionCalls.incrementAndGet();
            assertThat(request.canonicalBytes()).containsExactly(accountRequest.canonicalBytes());
            return prepared.protection();
          };
      CanonicalInitialAdmissionWorldVerifier unavailableWorld =
          request -> {
            throw new IllegalStateException("controlled World hold read outage");
          };
      var coordinator =
          coordinator(harness, admissionRepository(harness), accountClient, unavailableWorld);

      assertThatThrownBy(() -> coordinator.bind(prepared.request(), accountRequest))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("controlled World hold read outage");
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("PENDING");
      assertThat(runtime(harness, prepared).get("status", String.class)).isEqualTo("STARTING");
      assertNoAdmissionEffects(harness, prepared.request());
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT account_protection_evidence_bytes "
                          + "FROM game_session_canonical_initial_admission_attempt "
                          + "WHERE target_namespace=? AND initial_admission_request_id=?",
                      prepared.request().targetNamespace(),
                      prepared.request().initialAdmissionRequestId())
                  .get(0, byte[].class))
          .containsExactly(prepared.protection().canonicalBytes());
      assertThat(acquisitionCalls).hasValue(1);

      CanonicalInitialAdmissionWorldVerifier unusedWorld =
          request -> {
            throw new AssertionError("explicit abort must not need World readback");
          };
      var abortCoordinator =
          coordinator(harness, admissionRepository(harness), accountClient, unusedWorld);
      var aborted =
          abortCoordinator.abort(prepared.request(), accountRequest, "explicit caller abort");
      assertThat(aborted.outcome()).isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
      assertThat(aborted.positiveDurableAbort()).isTrue();
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("ABORTED");
      assertThat(runtime(harness, prepared).get("status", String.class)).isEqualTo("STARTING");
      assertNoAdmissionEffects(harness, prepared.request());
      assertThat(acquisitionCalls).hasValue(2);

      assertThat(
              abortCoordinator.abort(prepared.request(), accountRequest, "explicit caller abort"))
          .isEqualTo(aborted);
      assertThat(acquisitionCalls).hasValue(2);
      assertThatThrownBy(
              () ->
                  abortCoordinator.abort(
                      prepared.request(), accountRequest, "different abort reason"))
          .isInstanceOf(
              CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException.class);
      assertThat(acquisitionCalls).hasValue(2);
      assertThatThrownBy(() -> abortCoordinator.bind(prepared.request(), accountRequest))
          .isInstanceOf(
              CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException.class)
          .hasMessageContaining("cannot later commit");
      assertThat(acquisitionCalls).hasValue(2);
      assertNoAdmissionEffects(harness, prepared.request());
    } finally {
      harness.close();
    }
  }

  @Test
  void completedRuntimeCannotCommitAfterOriginalLeaseExpiresAndRetryDoesNotReacquire()
      throws Exception {
    var harness = CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres);
    try {
      var prepared =
          prepareStarting(
              harness, "protected-coordinator-expired-after-running", 1171, Duration.ofSeconds(5));
      var accountRequest = prepared.protection().request();
      var acquisitionCalls = new AtomicInteger();
      var worldReads = new AtomicInteger();
      // Controlled Account receipt and World verifier output; the PostgreSQL owner checks are
      // real.
      AccountStartSessionAdmissionProtectionAcquisitionClient accountClient =
          request -> {
            acquisitionCalls.incrementAndGet();
            assertThat(request.canonicalBytes()).containsExactly(accountRequest.canonicalBytes());
            return prepared.protection();
          };
      CanonicalInitialAdmissionWorldVerifier worldVerifier =
          request -> {
            worldReads.incrementAndGet();
            assertThat(request).isEqualTo(prepared.request());
            return prepared.worldProof();
          };
      var catalog = new GameSessionCanonicalRealmCatalogRepository(harness.dsl());
      var events = new GameplayAdmissionPointerEventRepository(harness.dsl());
      var pointers =
          new GameSessionCanonicalAdmissionPointerRepository(harness.dsl(), catalog, events);
      var expireBeforeCommit =
          new CanonicalInitialAdmissionRepository(harness.dsl(), catalog, pointers) {
            @Override
            public void commitProtected(
                CanonicalInitialAdmissionRequest request,
                AccountStartSessionAdmissionProtectionEvidence protection,
                CanonicalInitialAdmissionWorldProof worldProof) {
              awaitOriginalLeaseExpiration(harness, prepared);
              super.commitProtected(request, protection, worldProof);
            }
          };
      var initialCoordinator =
          coordinator(harness, expireBeforeCommit, accountClient, worldVerifier);

      assertThatThrownBy(() -> initialCoordinator.bind(prepared.request(), accountRequest))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Account protection original owner lease is no longer live");
      assertThat(runtime(harness, prepared).get("status", String.class)).isEqualTo("RUNNING");
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("PENDING");
      assertNoAdmissionEffects(harness, prepared.request());
      assertThat(acquisitionCalls).hasValue(1);

      AccountStartSessionAdmissionProtectionAcquisitionClient noReacquire =
          request -> {
            acquisitionCalls.incrementAndGet();
            throw new AssertionError("expired original protection must not be reacquired");
          };
      var retryCoordinator =
          coordinator(harness, admissionRepository(harness), noReacquire, worldVerifier);
      assertThatThrownBy(() -> retryCoordinator.bind(prepared.request(), accountRequest))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Account protection original owner lease is no longer live");
      assertThat(acquisitionCalls).hasValue(1);
      assertThat(worldReads).hasValue(2);
      assertThat(runtime(harness, prepared).get("status", String.class)).isEqualTo("RUNNING");
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("PENDING");
      assertNoAdmissionEffects(harness, prepared.request());
    } finally {
      harness.close();
    }
  }

  @Test
  void completionRechecksTheLiveOriginalOwnerAfterProtectedReservation() throws Exception {
    var harness = CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres);
    try {
      var prepared =
          prepareStarting(
              harness,
              "human-runtime-expired-original-after-reservation",
              1091,
              Duration.ofSeconds(2));
      var admission = admissionRepository(harness);
      var completion = new CanonicalHumanRuntimeCompletionRepository(harness.dsl());
      reserveProtection(harness, admission, prepared);
      Record before = runtime(harness, prepared);

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!Boolean.TRUE.equals(
          harness
              .dsl()
              .fetchSingle(
                  "SELECT lease_expires_at <= clock_timestamp() "
                      + "FROM game_session_start_session_operator_attempt "
                      + "WHERE target_namespace=? AND owner_attempt_id=?",
                  prepared.request().targetNamespace(),
                  prepared.original().ownerAttemptId())
              .get(0, Boolean.class))) {
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException("Timed out waiting for the original owner lease");
        }
        Thread.sleep(10L);
      }

      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              completion.completeProtected(
                                  prepared.request(),
                                  prepared.protection(),
                                  prepared.worldProof())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Account protection original owner lease is no longer live");
      assertThat(runtime(harness, prepared).formatJSON()).isEqualTo(before.formatJSON());
      assertThat(
              harness
                  .dsl()
                  .fetchCount(
                      DSL.table(DSL.name("game_session_canonical_human_runtime_completion"))))
          .isZero();
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("PENDING");
      assertNoAdmissionEffects(harness, prepared.request());
    } finally {
      harness.close();
    }
  }

  @Test
  void completesStartingThenCommitsAdmissionWithoutCouplingTheTransactions() throws Exception {
    var harness = CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres);
    try {
      var prepared = prepareStarting(harness, "human-runtime-complete", 1101);
      var admission = admissionRepository(harness);
      var completion = new CanonicalHumanRuntimeCompletionRepository(harness.dsl());
      reserveProtection(harness, admission, prepared);

      Record before = runtime(harness, prepared);
      assertThat(before.get("status", String.class)).isEqualTo("STARTING");
      assertNoAdmissionEffects(harness, prepared.request());

      var changedFence =
          protection(
              prepared.target(),
              prepared.protection().request().worldAdmissionHoldIdentity(),
              prepared.protection().accountProtectionId(),
              2L,
              prepared.original());
      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              completion.completeProtected(
                                  prepared.request(), changedFence, prepared.worldProof())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("different Account protection");
      var changedEpochProof =
          new CanonicalInitialAdmissionWorldProof(
              prepared.request().initialAdmissionRequestId(),
              prepared.request().requestDigest(),
              prepared.request().targetNamespace(),
              prepared.request().canonicalTenantId(),
              prepared.request().worldSlug(),
              prepared.request().realmId(),
              prepared.request().playableStateNamespaceId(),
              prepared.request().playableStateScope(),
              prepared.request().canonicalGameInstanceId(),
              prepared.request().canonicalVersionId(),
              "ACTIVE",
              prepared.request().activeLifecycleEpoch() + 1L,
              prepared.request().originKind(),
              prepared.request().expectedCatalogRevision(),
              prepared.request().expectedPriorPointerVersion(),
              prepared.request().holdId(),
              prepared.request().holdFence(),
              prepared.request().holdBindingDigest());
      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              completion.completeProtected(
                                  prepared.request(), prepared.protection(), changedEpochProof)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not match");
      assertThat(runtime(harness, prepared).formatJSON()).isEqualTo(before.formatJSON());

      var result =
          Objects.requireNonNull(
              harness
                  .transactions()
                  .execute(
                      status ->
                          completion.completeProtected(
                              prepared.request(), prepared.protection(), prepared.worldProof())));

      Record running = runtime(harness, prepared);
      assertThat(running.get("status", String.class)).isEqualTo("RUNNING");
      assertThat(running.get("row_version", Long.class))
          .isEqualTo(Math.addExact(before.get("row_version", Long.class), 1L));
      assertThat(result.completedRunningRowVersion())
          .isEqualTo(running.get("row_version", Long.class));
      assertThat(result.accountProtectionEvidenceBytes())
          .containsExactly(prepared.protection().canonicalBytes());
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("PENDING");
      assertNoAdmissionEffects(harness, prepared.request());

      var replay =
          Objects.requireNonNull(
              harness
                  .transactions()
                  .execute(
                      status ->
                          completion.completeProtected(
                              prepared.request(), prepared.protection(), prepared.worldProof())));
      assertThat(replay).isEqualTo(result);
      assertNoAdmissionEffects(harness, prepared.request());

      harness
          .transactions()
          .executeWithoutResult(
              status ->
                  admission.commitProtected(
                      prepared.request(), prepared.protection(), prepared.worldProof()));
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("COMMITTED");
      assertThat(ownerCount(harness, "gameplay_admission_pointer", prepared.request())).isOne();
      assertThat(ownerCount(harness, "gameplay_admission_pointer_event", prepared.request()))
          .isOne();
    } finally {
      harness.close();
    }
  }

  @Test
  void completionAndDefinitiveAbortUseOnePhysicalRealmFence() throws Exception {
    var harness = CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres);
    var executor = Executors.newFixedThreadPool(2);
    var releaseCompletion = new CountDownLatch(1);
    try {
      var prepared = prepareStarting(harness, "human-runtime-completion-abort-race", 1401);
      var admission = admissionRepository(harness);
      var completion = new CanonicalHumanRuntimeCompletionRepository(harness.dsl());
      reserveProtection(harness, admission, prepared);
      var completionLocked = new CountDownLatch(1);
      var abortEntered = new CountDownLatch(1);
      var completionPid = new AtomicInteger();
      var abortPid = new AtomicInteger();

      var completing =
          executor.submit(
              () ->
                  captureFailure(
                      () ->
                          harness
                              .transactions()
                              .executeWithoutResult(
                                  status -> {
                                    completionPid.set(postgresPid(harness));
                                    lockTarget(harness, prepared.request());
                                    completionLocked.countDown();
                                    await(releaseCompletion);
                                    completion.completeProtected(
                                        prepared.request(),
                                        prepared.protection(),
                                        prepared.worldProof());
                                  })));
      assertThat(completionLocked.await(10, TimeUnit.SECONDS)).isTrue();
      var aborting =
          executor.submit(
              () ->
                  captureFailure(
                      () ->
                          harness
                              .transactions()
                              .executeWithoutResult(
                                  status -> {
                                    abortPid.set(postgresPid(harness));
                                    abortEntered.countDown();
                                    admission.abortProtected(
                                        prepared.request(),
                                        prepared.protection(),
                                        "serialized after runtime completion");
                                  })));
      assertThat(abortEntered.await(10, TimeUnit.SECONDS)).isTrue();
      awaitBlocked(harness, abortPid.get(), completionPid.get());
      releaseCompletion.countDown();
      assertThat(completing.get(10, TimeUnit.SECONDS)).isNull();
      assertThat(aborting.get(10, TimeUnit.SECONDS)).isNull();

      assertThat(runtime(harness, prepared).get("status", String.class)).isEqualTo("RUNNING");
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("ABORTED");
      assertNoAdmissionEffects(harness, prepared.request());
    } finally {
      releaseCompletion.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      harness.close();
    }
  }

  @Test
  void abortBeforeCompletionFencesRunningAndWritesNoPointerOrAudit() throws Exception {
    var harness = CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres);
    try {
      var prepared = prepareStarting(harness, "human-runtime-abort-first", 1201);
      var admission = admissionRepository(harness);
      var completion = new CanonicalHumanRuntimeCompletionRepository(harness.dsl());
      reserveProtection(harness, admission, prepared);
      harness
          .transactions()
          .executeWithoutResult(
              status ->
                  admission.abortProtected(
                      prepared.request(),
                      prepared.protection(),
                      "controlled pre-completion abort"));

      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              completion.completeProtected(
                                  prepared.request(),
                                  prepared.protection(),
                                  prepared.worldProof())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("nonterminal protected reservation");
      assertThat(runtime(harness, prepared).get("status", String.class)).isEqualTo("STARTING");
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("ABORTED");
      assertNoAdmissionEffects(harness, prepared.request());
    } finally {
      harness.close();
    }
  }

  @Test
  void upgradedStartingHistoryCompletesWithoutBackfillOrPointerEffects() throws Exception {
    var harness =
        CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres, "1039");
    try {
      var prepared = prepareStarting(harness, "human-runtime-upgrade", 1301);
      var admission = admissionRepository(harness);
      reserveProtection(harness, admission, prepared);
      Record before = runtime(harness, prepared);

      harness.migrateLatest();

      assertThat(runtime(harness, prepared).formatJSON()).isEqualTo(before.formatJSON());
      var completion = new CanonicalHumanRuntimeCompletionRepository(harness.dsl());
      harness
          .transactions()
          .executeWithoutResult(
              status ->
                  completion.completeProtected(
                      prepared.request(), prepared.protection(), prepared.worldProof()));
      assertThat(runtime(harness, prepared).get("status", String.class)).isEqualTo("RUNNING");
      assertThat(attemptStatus(harness, prepared.request())).isEqualTo("PENDING");
      assertNoAdmissionEffects(harness, prepared.request());
    } finally {
      harness.close();
    }
  }

  private static Prepared prepareStarting(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      String requestId,
      int idBase)
      throws Exception {
    return prepareStarting(harness, requestId, idBase, Duration.ofMinutes(5));
  }

  private static Prepared prepareStarting(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      String requestId,
      int idBase,
      Duration ownerLease)
      throws Exception {
    var target = harness.seedStarting(ownerLease);
    WorldCanonicalInitialAdmissionHold.Request holdRequest =
        CanonicalInitialAdmissionIntentRepositoryIntegrationTest.holdRequest(target, requestId);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            holdRequest, uuid(idBase), uuid(idBase + 1));
    harness
        .transactions()
        .executeWithoutResult(
            status ->
                target
                    .intents()
                    .reserveHumanStarting(holdRequest, target.lifecycleRequest(uuid(idBase + 2))));
    harness
        .transactions()
        .executeWithoutResult(status -> target.intents().attachHumanStarting(holdRequest, hold));
    CanonicalInitialAdmissionRequest request = request(hold);
    Original original = original(harness, target);
    var protection = protection(target, hold, uuid(idBase + 3), original);
    return new Prepared(target, request, protection, worldProof(request), original);
  }

  private static void reserveProtection(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      CanonicalInitialAdmissionRepository admission,
      Prepared prepared) {
    harness
        .transactions()
        .executeWithoutResult(
            status ->
                admission.reserveProtectedStartingForRuntimeCompletion(
                    prepared.request(), prepared.protection()));
  }

  private static Original original(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.OwnerTarget target) {
    Record row =
        harness
            .dsl()
            .fetchSingle(
                "SELECT post_authorization_execution_tuple, account_redemption_projection, "
                    + "owner_mutation_id, owner_attempt_id, owner_fence, lease_expires_at, created_at "
                    + "FROM game_session_start_session_operator_attempt "
                    + "WHERE target_namespace=? AND control_plane_request_id=?",
                target.targetNamespace(),
                target.launch().association().controlPlaneRequestId());
    return new Original(
        row.get("post_authorization_execution_tuple", byte[].class),
        row.get("account_redemption_projection", byte[].class),
        row.get("owner_mutation_id", UUID.class),
        row.get("owner_attempt_id", UUID.class),
        row.get("owner_fence", Long.class),
        row.get("lease_expires_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /** Controlled Account evidence; genuine three-owner composition remains outside this test. */
  private static AccountStartSessionAdmissionProtectionEvidence protection(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.OwnerTarget target,
      WorldCanonicalInitialAdmissionHold.HoldIdentity hold,
      UUID protectionId,
      Original original)
      throws Exception {
    return protection(target, hold, protectionId, 1L, original);
  }

  private static AccountStartSessionAdmissionProtectionEvidence protection(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.OwnerTarget target,
      WorldCanonicalInitialAdmissionHold.HoldIdentity hold,
      UUID protectionId,
      long protectionFence,
      Original original)
      throws Exception {
    var request =
        AccountStartSessionAdmissionProtectionRequest.create(
            original.tuple(),
            original.projection(),
            original.ownerMutationId(),
            original.ownerAttemptId(),
            original.ownerFence(),
            original.leaseExpiresAt().toInstant(),
            uuid(1398),
            1L,
            hold);
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(original.tuple());
    var reference = tuple.bundleReference();
    byte[] capture =
        Rfc8785CanonicalJson.canonicalizeUtf8(
            JsonMapper.builder()
                .build()
                .writeValueAsString(
                    Map.of(
                        "schema",
                            AccountStartSessionAdmissionProtectionEvidence
                                .SOURCE_CAPTURE_REFERENCE_SCHEMA,
                        "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                        "capturedAt",
                            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
                                .withZone(ZoneOffset.UTC)
                                .format(original.createdAt().toInstant()),
                        "bundleReference",
                            Map.of(
                                "bundleVersion", reference.bundleVersion(),
                                "sourceVersion", reference.sourceVersion(),
                                "sourceFence", reference.sourceFence(),
                                "linearization", reference.linearization()),
                        "snapshotSha256", sha256(original.tuple()))));
    return AccountStartSessionAdmissionProtectionEvidence.create(
        request,
        protectionId,
        protectionFence,
        capture,
        sha256(capture),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                target.canonicalTenantId().toString(),
                "2",
                "17",
                null,
                null,
                "controlled-account-runtime-completion".getBytes(StandardCharsets.UTF_8))));
  }

  private static CanonicalInitialAdmissionRepository admissionRepository(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness) {
    var catalog = new GameSessionCanonicalRealmCatalogRepository(harness.dsl());
    var events = new GameplayAdmissionPointerEventRepository(harness.dsl());
    var pointers =
        new GameSessionCanonicalAdmissionPointerRepository(harness.dsl(), catalog, events);
    return new CanonicalInitialAdmissionRepository(harness.dsl(), catalog, pointers);
  }

  private static ProtectedCanonicalInitialAdmissionCoordinator coordinator(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      CanonicalInitialAdmissionRepository admissions,
      AccountStartSessionAdmissionProtectionAcquisitionClient accountClient,
      CanonicalInitialAdmissionWorldVerifier worldVerifier) {
    return new ProtectedCanonicalInitialAdmissionCoordinator(
        admissions,
        new CanonicalHumanRuntimeCompletionRepository(harness.dsl()),
        new AccountInitialAdmissionProtectionBindingRepository(harness.dsl()),
        accountClient,
        worldVerifier,
        harness.transactionManager(),
        "canonical-intent-it");
  }

  private static Record runtime(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness, Prepared prepared) {
    return harness
        .dsl()
        .fetchSingle(
            "SELECT status, row_version FROM game_instances WHERE tenant_id=? AND id=?",
            prepared.target().launch().association().gameSessionTenantId(),
            prepared.target().launch().gameInstanceId());
  }

  private static void awaitOriginalLeaseExpiration(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness, Prepared prepared) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!Boolean.TRUE.equals(
        harness
            .dsl()
            .fetchSingle(
                "SELECT lease_expires_at <= clock_timestamp() "
                    + "FROM game_session_start_session_operator_attempt "
                    + "WHERE target_namespace=? AND owner_attempt_id=?",
                prepared.request().targetNamespace(),
                prepared.original().ownerAttemptId())
            .get(0, Boolean.class))) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("Timed out waiting for the original owner lease");
      }
      try {
        Thread.sleep(10L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted waiting for the original owner lease", interrupted);
      }
    }
  }

  private static String attemptStatus(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      CanonicalInitialAdmissionRequest request) {
    return harness
        .dsl()
        .fetchSingle(
            "SELECT status FROM game_session_canonical_initial_admission_attempt "
                + "WHERE target_namespace=? AND initial_admission_request_id=?",
            request.targetNamespace(),
            request.initialAdmissionRequestId())
        .get(0, String.class);
  }

  private static void assertNoAdmissionEffects(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      CanonicalInitialAdmissionRequest request) {
    assertThat(ownerCount(harness, "gameplay_admission_pointer", request)).isZero();
    assertThat(ownerCount(harness, "gameplay_admission_pointer_event", request)).isZero();
  }

  private static long ownerCount(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      String table,
      CanonicalInitialAdmissionRequest request) {
    if (!"gameplay_admission_pointer".equals(table)
        && !"gameplay_admission_pointer_event".equals(table)) throw new IllegalArgumentException();
    return harness
        .dsl()
        .fetchSingle(
            "SELECT count(*) FROM "
                + table
                + " WHERE target_namespace=? AND initial_admission_request_id=?",
            request.targetNamespace(),
            request.initialAdmissionRequestId())
        .get(0, Long.class);
  }

  private static int postgresPid(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness) {
    return harness.dsl().fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class);
  }

  private static void lockTarget(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      CanonicalInitialAdmissionRequest request) {
    harness
        .dsl()
        .fetchSingle(
            "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ? || ':' || ?, 0))",
            request.targetNamespace(),
            request.canonicalTenantId().toString(),
            request.realmId().toString());
  }

  private static void awaitBlocked(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      int waitingPid,
      int blockerPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (harness
          .dsl()
          .fetch("SELECT unnest(pg_blocking_pids(?)) AS blocker", waitingPid)
          .getValues("blocker", Integer.class)
          .contains(blockerPid)) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Abort did not block behind Human runtime completion");
  }

  private static Throwable captureFailure(ThrowingAction action) {
    try {
      action.run();
      return null;
    } catch (Throwable failure) {
      return failure;
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static CanonicalInitialAdmissionRequest request(
      WorldCanonicalInitialAdmissionHold.HoldIdentity hold) {
    var w = hold.request();
    return new CanonicalInitialAdmissionRequest(
        w.targetNamespace(),
        w.canonicalTenantId(),
        w.worldSlug(),
        w.realmId(),
        w.playableStateNamespaceId(),
        w.playableStateScope(),
        w.canonicalGameInstanceId(),
        w.canonicalVersionId(),
        w.activeLifecycleEpoch(),
        w.expectedCatalogRevision(),
        CanonicalInitialAdmissionRequest.OriginKind.valueOf(w.initialAdmissionOrigin().name()),
        w.expectedPriorPointerVersion(),
        w.initialAdmissionRequestId(),
        w.initialAdmissionRequestDigest(),
        hold.holdId(),
        hold.holdFence(),
        hold.holdBindingDigest());
  }

  /** Controlled authenticated-verifier output; no genuine World producer is claimed. */
  private static CanonicalInitialAdmissionWorldProof worldProof(
      CanonicalInitialAdmissionRequest r) {
    return new CanonicalInitialAdmissionWorldProof(
        r.initialAdmissionRequestId(),
        r.requestDigest(),
        r.targetNamespace(),
        r.canonicalTenantId(),
        r.worldSlug(),
        r.realmId(),
        r.playableStateNamespaceId(),
        r.playableStateScope(),
        r.canonicalGameInstanceId(),
        r.canonicalVersionId(),
        "ACTIVE",
        r.activeLifecycleEpoch(),
        r.originKind(),
        r.expectedCatalogRevision(),
        r.expectedPriorPointerVersion(),
        r.holdId(),
        r.holdFence(),
        r.holdBindingDigest());
  }

  private static UUID uuid(int value) {
    return CanonicalInitialAdmissionIntentRepositoryIntegrationTest.uuid(value);
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private record Prepared(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.OwnerTarget target,
      CanonicalInitialAdmissionRequest request,
      AccountStartSessionAdmissionProtectionEvidence protection,
      CanonicalInitialAdmissionWorldProof worldProof,
      Original original) {}

  private record Original(
      byte[] tuple,
      byte[] projection,
      UUID ownerMutationId,
      UUID ownerAttemptId,
      long ownerFence,
      OffsetDateTime leaseExpiresAt,
      OffsetDateTime createdAt) {}
}
