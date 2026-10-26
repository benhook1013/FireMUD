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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL proof for the protected lower-level canonical initial-admission writer. */
@Testcontainers(disabledWithoutDocker = true)
class CanonicalInitialAdmissionProtectedWriterPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void commitsOneProtectionBoundPointerAuditAttemptAndIntentWithExactReplay() throws Exception {
    var harness = CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres);
    try {
      var target = harness.seed();
      Prepared prepared = prepare(target, "protected-writer-commit", 501);
      CanonicalInitialAdmissionRepository repository = repository(harness);

      harness
          .transactions()
          .executeWithoutResult(
              status -> repository.reserveProtected(prepared.request(), prepared.protection()));
      Record pending = terminal(harness, prepared.request());
      assertThat(pending.get("status", String.class)).isEqualTo("PENDING");
      assertThat(pending.get("account_protection_id", UUID.class))
          .isEqualTo(prepared.protection().accountProtectionId());
      assertThat(pending.get("account_protection_evidence_bytes", byte[].class))
          .containsExactly(prepared.protection().canonicalBytes());
      assertThatThrownBy(
              () ->
                  new AccountInitialAdmissionProtectionBindingRepository(harness.dsl())
                      .read(
                          prepared.request().targetNamespace(),
                          prepared.request().initialAdmissionRequestId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Protected admission has no immutable terminal result");

      var changedId = protection(target, prepared.hold(), uuid(590), 1L, prepared.original());
      var changedFence =
          protection(
              target,
              prepared.hold(),
              prepared.protection().accountProtectionId(),
              2L,
              prepared.original());
      var changedBytes =
          protection(
              target,
              prepared.hold(),
              prepared.protection().accountProtectionId(),
              prepared.protection().accountProtectionFence(),
              prepared.original(),
              "changed-controlled-account-source");
      for (var changed : List.of(changedId, changedFence, changedBytes)) {
        assertThatThrownBy(
                () ->
                    harness
                        .transactions()
                        .executeWithoutResult(
                            status -> repository.reserveProtected(prepared.request(), changed)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("different Account protection");
        assertThat(terminal(harness, prepared.request()).formatJSON())
            .isEqualTo(pending.formatJSON());
      }
      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              harness
                                  .dsl()
                                  .execute(
                                      "INSERT INTO game_session_canonical_initial_admission_attempt "
                                          + "SELECT (jsonb_populate_record(NULL::game_session_canonical_initial_admission_attempt, "
                                          + "to_jsonb(a) || jsonb_build_object("
                                          + "'initial_admission_request_id', ?, 'account_protection_id', NULL))).* "
                                          + "FROM game_session_canonical_initial_admission_attempt a "
                                          + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                                      prepared.request().initialAdmissionRequestId() + "-partial",
                                      prepared.request().targetNamespace(),
                                      prepared.request().initialAdmissionRequestId())))
          .rootCause()
          .hasMessageContaining("requires complete retained Account protection evidence");
      assertThat(terminal(harness, prepared.request()).formatJSON())
          .isEqualTo(pending.formatJSON());
      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              harness
                                  .dsl()
                                  .execute(
                                      "UPDATE game_session_canonical_initial_admission_attempt "
                                          + "SET account_protection_id = ? WHERE target_namespace = ? "
                                          + "AND initial_admission_request_id = ?",
                                      uuid(599),
                                      prepared.request().targetNamespace(),
                                      prepared.request().initialAdmissionRequestId())))
          .rootCause()
          .hasMessageContaining("immutable");
      assertThat(terminal(harness, prepared.request()).formatJSON())
          .isEqualTo(pending.formatJSON());

      harness
          .transactions()
          .executeWithoutResult(
              status ->
                  repository.commitProtected(
                      prepared.request(), prepared.protection(), prepared.worldProof()));

      var proof =
          repository
              .read(
                  prepared.request().targetNamespace(),
                  prepared.request().initialAdmissionRequestId())
              .orElseThrow();
      assertThat(proof.provesCommit()).isTrue();
      assertThat(proof.proofDigest()).startsWith("sha256:");
      Record terminal = terminal(harness, prepared.request());
      assertThat(terminal.get("status", String.class)).isEqualTo("COMMITTED");
      assertThat(terminal.get("account_protection_id", UUID.class))
          .isEqualTo(prepared.protection().accountProtectionId());
      assertThat(terminal.get("account_protection_evidence_bytes", byte[].class))
          .containsExactly(prepared.protection().canonicalBytes());
      for (String column :
          List.of(
              "account_protection_evidence_version",
              "account_protection_id",
              "account_protection_fence",
              "original_owner_attempt_id",
              "original_owner_mutation_id",
              "original_owner_fence")) {
        assertThat(terminal.get(column)).isEqualTo(pending.get(column));
      }
      assertThat(terminal.get("intent_state", String.class)).isEqualTo("TERMINAL");
      assertThat(terminal.get("intent_terminal", OffsetDateTime.class))
          .isEqualTo(terminal.get("terminal_at", OffsetDateTime.class));
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM gameplay_admission_pointer WHERE target_namespace = ? "
                          + "AND initial_admission_request_id = ? AND admission_state = 'OPEN'",
                      prepared.request().targetNamespace(),
                      prepared.request().initialAdmissionRequestId())
                  .get(0, Long.class))
          .isOne();
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM gameplay_admission_pointer_event WHERE target_namespace = ? "
                          + "AND initial_admission_request_id = ? AND admission_state = 'OPEN'",
                      prepared.request().targetNamespace(),
                      prepared.request().initialAdmissionRequestId())
                  .get(0, Long.class))
          .isOne();

      harness
          .transactions()
          .executeWithoutResult(
              status -> repository.reserveProtected(prepared.request(), prepared.protection()));
      harness
          .transactions()
          .executeWithoutResult(
              status ->
                  repository.commitProtected(
                      prepared.request(), prepared.protection(), prepared.worldProof()));
      assertThat(terminal(harness, prepared.request()).formatJSON())
          .isEqualTo(terminal.formatJSON());

      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              repository.commitProtected(
                                  prepared.request(), changedId, prepared.worldProof())))
          .isInstanceOf(
              CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException.class)
          .hasMessageContaining("changed Account protection");
      assertThat(terminal(harness, prepared.request()).formatJSON())
          .isEqualTo(terminal.formatJSON());
    } finally {
      harness.close();
    }
  }

  @Test
  void upgradeRetainsLegacyAllNullPendingWithoutAdoptingIt() throws Exception {
    var harness =
        CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres, "1022");
    try {
      var target = harness.seed();
      Prepared prepared = prepare(target, "retained-legacy-pending", 701);
      insertLegacyPending(harness, target, prepared.request());
      Record before = terminal(harness, prepared.request());
      assertThat(before.get("status", String.class)).isEqualTo("PENDING");
      assertThat(before.get("account_protection_evidence_version")).isNull();
      assertThat(before.get("account_protection_evidence_bytes")).isNull();

      harness.migrateLatest();

      Record after = terminal(harness, prepared.request());
      assertThat(after.formatJSON()).isEqualTo(before.formatJSON());
      CanonicalInitialAdmissionRepository repository = repository(harness);
      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              repository.reserveProtected(
                                  prepared.request(), prepared.protection())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("PENDING admission reservation retains different");
      assertThat(terminal(harness, prepared.request()).formatJSON()).isEqualTo(before.formatJSON());
    } finally {
      harness.close();
    }
  }

  @Test
  void definitiveProtectedAbortWritesNoCommitOriginAndRejectsLaterCommit() throws Exception {
    var harness = CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres);
    try {
      var target = harness.seed();
      Prepared prepared = prepare(target, "protected-writer-abort", 601);
      CanonicalInitialAdmissionRepository repository = repository(harness);

      harness
          .transactions()
          .executeWithoutResult(
              status -> repository.reserveProtected(prepared.request(), prepared.protection()));
      harness
          .transactions()
          .executeWithoutResult(
              status ->
                  repository.abortProtected(
                      prepared.request(), prepared.protection(), "controlled upstream denial"));

      Record terminal = terminal(harness, prepared.request());
      assertThat(terminal.get("status", String.class)).isEqualTo("ABORTED");
      assertThat(terminal.get("pointer_id")).isNull();
      assertThat(terminal.get("pointer_version")).isNull();
      assertThat(terminal.get("audit_event_id")).isNull();
      assertThat(terminal.get("commit_proof_digest")).isNull();
      assertThat(terminal.get("abort_proof_digest", String.class)).startsWith("sha256:");
      assertThat(terminal.get("intent_state", String.class)).isEqualTo("TERMINAL");
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM gameplay_admission_pointer WHERE target_namespace = ? "
                          + "AND initial_admission_request_id = ?",
                      prepared.request().targetNamespace(),
                      prepared.request().initialAdmissionRequestId())
                  .get(0, Long.class))
          .isZero();
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM gameplay_admission_pointer_event WHERE target_namespace = ? "
                          + "AND initial_admission_request_id = ?",
                      prepared.request().targetNamespace(),
                      prepared.request().initialAdmissionRequestId())
                  .get(0, Long.class))
          .isZero();

      harness
          .transactions()
          .executeWithoutResult(
              status ->
                  repository.abortProtected(
                      prepared.request(), prepared.protection(), "controlled upstream denial"));
      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              repository.commitProtected(
                                  prepared.request(),
                                  prepared.protection(),
                                  prepared.worldProof())))
          .isInstanceOf(
              CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException.class)
          .hasMessageContaining("ABORTED");
      assertThat(terminal(harness, prepared.request()).formatJSON())
          .isEqualTo(terminal.formatJSON());
    } finally {
      harness.close();
    }
  }

  // Controlled Account protection input; this proves Game Session storage, not Account composition.
  @Test
  void expiredOriginalLeaseAllowsOnlyTheExactProtectedAbortAcrossForwardMigration()
      throws Exception {
    var harness =
        CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres, "1040");
    try {
      var target = harness.seed(Duration.ofSeconds(10));
      Prepared prepared = prepare(target, "protected-writer-expired-abort", 1001);
      CanonicalInitialAdmissionRepository repository = repository(harness);

      harness
          .transactions()
          .executeWithoutResult(
              status -> repository.reserveProtected(prepared.request(), prepared.protection()));

      Record pending = terminal(harness, prepared.request());
      String historyBefore =
          harness
              .dsl()
              .fetch("SELECT * " + "FROM flyway_schema_history ORDER BY installed_rank")
              .formatJSON();
      Record intentBefore =
          harness
              .dsl()
              .fetchSingle(
                  "SELECT * FROM game_session_canonical_initial_admission_intent "
                      + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                  prepared.request().targetNamespace(),
                  prepared.request().initialAdmissionRequestId());
      byte[] holdIdentityBefore = intentBefore.get("hold_identity_bytes", byte[].class);
      Record originalBefore =
          harness
              .dsl()
              .fetchSingle(
                  "SELECT * FROM game_session_start_session_operator_attempt "
                      + "WHERE target_namespace = ? AND owner_attempt_id = ?",
                  prepared.request().targetNamespace(),
                  prepared.original().ownerAttemptId());
      Record launchBefore =
          harness
              .dsl()
              .fetchSingle(
                  "SELECT * FROM game_session_canonical_instance_launch "
                      + "WHERE target_namespace = ? AND control_plane_request_id = ?",
                  prepared.request().targetNamespace(),
                  target.launch().association().controlPlaneRequestId());
      assertThat(
              harness
                  .dsl()
                  .fetch(
                      "SELECT version FROM flyway_schema_history "
                          + "WHERE version IN ('1000', '1001', '1002', '1038.1', '1039', '1040') "
                          + "AND success ORDER BY installed_rank")
                  .getValues(0, String.class))
          .containsExactly("1000", "1001", "1002", "1038.1", "1039", "1040");

      harness.migrateLatest();

      assertThat(
              harness
                  .dsl()
                  .fetch(
                      "SELECT * "
                          + "FROM flyway_schema_history WHERE version IS DISTINCT FROM '1040.1' "
                          + "ORDER BY installed_rank")
                  .formatJSON())
          .isEqualTo(historyBefore);
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT version FROM flyway_schema_history "
                          + "ORDER BY installed_rank DESC LIMIT 1")
                  .get(0, String.class))
          .isEqualTo("1040.1");
      assertThat(terminal(harness, prepared.request()).formatJSON())
          .isEqualTo(pending.formatJSON());
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT * FROM game_session_canonical_initial_admission_intent "
                          + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                      prepared.request().targetNamespace(),
                      prepared.request().initialAdmissionRequestId())
                  .formatJSON())
          .isEqualTo(intentBefore.formatJSON());
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT * FROM game_session_start_session_operator_attempt "
                          + "WHERE target_namespace = ? AND owner_attempt_id = ?",
                      prepared.request().targetNamespace(),
                      prepared.original().ownerAttemptId())
                  .formatJSON())
          .isEqualTo(originalBefore.formatJSON());
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT * FROM game_session_canonical_instance_launch "
                          + "WHERE target_namespace = ? AND control_plane_request_id = ?",
                      prepared.request().targetNamespace(),
                      target.launch().association().controlPlaneRequestId())
                  .formatJSON())
          .isEqualTo(launchBefore.formatJSON());

      awaitOriginalOwnerLeaseExpiration(harness, prepared);
      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              repository.commitProtected(
                                  prepared.request(),
                                  prepared.protection(),
                                  prepared.worldProof())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("original owner lease is no longer live");
      assertThat(terminal(harness, prepared.request()).formatJSON())
          .isEqualTo(pending.formatJSON());

      harness
          .transactions()
          .executeWithoutResult(
              status ->
                  repository.abortProtected(
                      prepared.request(),
                      prepared.protection(),
                      "controlled denial after original lease expiry"));

      Record aborted = terminal(harness, prepared.request());
      assertThat(aborted.get("status", String.class)).isEqualTo("ABORTED");
      assertThat(aborted.get("pointer_id")).isNull();
      assertThat(aborted.get("pointer_version")).isNull();
      assertThat(aborted.get("audit_event_id")).isNull();
      assertThat(aborted.get("commit_proof_digest")).isNull();
      assertThat(aborted.get("abort_proof_digest", String.class)).startsWith("sha256:");
      assertThat(aborted.get("intent_state", String.class)).isEqualTo("TERMINAL");
      for (String column :
          List.of(
              "account_protection_evidence_version",
              "account_protection_id",
              "account_protection_fence",
              "original_owner_attempt_id",
              "original_owner_mutation_id",
              "original_owner_fence",
              "account_protection_evidence_bytes",
              "hold_id",
              "hold_fence",
              "hold_binding_digest")) {
        assertThat(aborted.get(column)).isEqualTo(pending.get(column));
      }
      Record intentAfterAbort =
          harness
              .dsl()
              .fetchSingle(
                  "SELECT * FROM game_session_canonical_initial_admission_intent "
                      + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                  prepared.request().targetNamespace(),
                  prepared.request().initialAdmissionRequestId());
      assertThat(intentAfterAbort.get("hold_identity_bytes", byte[].class))
          .containsExactly(holdIdentityBefore);
      assertThat(intentAfterAbort.get("canonical_hold_request_bytes", byte[].class))
          .containsExactly(intentBefore.get("canonical_hold_request_bytes", byte[].class));
      assertThat(intentAfterAbort.get("original_start_session_tuple", byte[].class))
          .containsExactly(intentBefore.get("original_start_session_tuple", byte[].class));
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT * FROM game_session_start_session_operator_attempt "
                          + "WHERE target_namespace = ? AND owner_attempt_id = ?",
                      prepared.request().targetNamespace(),
                      prepared.original().ownerAttemptId())
                  .formatJSON())
          .isEqualTo(originalBefore.formatJSON());
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM gameplay_admission_pointer "
                          + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                      prepared.request().targetNamespace(),
                      prepared.request().initialAdmissionRequestId())
                  .get(0, Long.class))
          .isZero();
      assertThat(
              harness
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM gameplay_admission_pointer_event "
                          + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                      prepared.request().targetNamespace(),
                      prepared.request().initialAdmissionRequestId())
                  .get(0, Long.class))
          .isZero();

      assertThat(
              repository
                  .read(
                      prepared.request().targetNamespace(),
                      prepared.request().initialAdmissionRequestId())
                  .orElseThrow()
                  .provesAbort())
          .isTrue();
      harness
          .transactions()
          .executeWithoutResult(
              status ->
                  repository.abortProtected(
                      prepared.request(),
                      prepared.protection(),
                      "controlled denial after original lease expiry"));
      assertThat(terminal(harness, prepared.request()).formatJSON())
          .isEqualTo(aborted.formatJSON());

      var changedProtection =
          protection(target, prepared.hold(), uuid(1005), 1L, prepared.original());
      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              repository.abortProtected(
                                  prepared.request(),
                                  changedProtection,
                                  "controlled denial after original lease expiry")))
          .isInstanceOf(
              CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException.class)
          .hasMessageContaining("changed Account protection");
      assertThatThrownBy(
              () ->
                  harness
                      .transactions()
                      .executeWithoutResult(
                          status ->
                              repository.commitProtected(
                                  prepared.request(),
                                  prepared.protection(),
                                  prepared.worldProof())))
          .isInstanceOf(
              CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException.class)
          .hasMessageContaining("ABORTED");
      assertThat(terminal(harness, prepared.request()).formatJSON())
          .isEqualTo(aborted.formatJSON());
    } finally {
      harness.close();
    }
  }

  @Test
  void concurrentProtectedCommitAndAbortSerializeToOneExactTerminalOutcome() throws Exception {
    proveConcurrentTerminalRace(true, 801);
    proveConcurrentTerminalRace(false, 901);
  }

  private static void proveConcurrentTerminalRace(boolean commitWins, int idBase) throws Exception {
    var harness = CanonicalInitialAdmissionIntentRepositoryIntegrationTest.harness(postgres);
    var executor = Executors.newFixedThreadPool(2);
    var releaseWinner = new CountDownLatch(1);
    try {
      var target = harness.seed();
      Prepared prepared =
          prepare(target, commitWins ? "concurrent-commit-wins" : "concurrent-abort-wins", idBase);
      CanonicalInitialAdmissionRepository repository = repository(harness);
      harness
          .transactions()
          .executeWithoutResult(
              status -> repository.reserveProtected(prepared.request(), prepared.protection()));
      Record pending = terminal(harness, prepared.request());

      var winnerLocked = new CountDownLatch(1);
      var loserEntered = new CountDownLatch(1);
      var winnerPid = new AtomicInteger();
      var loserPid = new AtomicInteger();
      Future<Throwable> winner =
          executor.submit(
              () ->
                  captureFailure(
                      () ->
                          harness
                              .transactions()
                              .executeWithoutResult(
                                  status -> {
                                    winnerPid.set(postgresBackendPid(harness));
                                    lockAdmissionTarget(harness, prepared.request());
                                    winnerLocked.countDown();
                                    await(releaseWinner, "release terminal winner");
                                    if (commitWins) {
                                      repository.commitProtected(
                                          prepared.request(),
                                          prepared.protection(),
                                          prepared.worldProof());
                                    } else {
                                      repository.abortProtected(
                                          prepared.request(),
                                          prepared.protection(),
                                          "concurrent controlled denial");
                                    }
                                  })));
      assertThat(winnerLocked.await(10, TimeUnit.SECONDS)).isTrue();

      Future<Throwable> loser =
          executor.submit(
              () ->
                  captureFailure(
                      () ->
                          harness
                              .transactions()
                              .executeWithoutResult(
                                  status -> {
                                    loserPid.set(postgresBackendPid(harness));
                                    loserEntered.countDown();
                                    if (commitWins) {
                                      repository.abortProtected(
                                          prepared.request(),
                                          prepared.protection(),
                                          "concurrent controlled denial");
                                    } else {
                                      repository.commitProtected(
                                          prepared.request(),
                                          prepared.protection(),
                                          prepared.worldProof());
                                    }
                                  })));
      assertThat(loserEntered.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(loserPid.get()).isNotEqualTo(winnerPid.get());
      awaitBlockingEdge(harness, loserPid.get(), winnerPid.get());
      releaseWinner.countDown();

      assertThat(winner.get(10, TimeUnit.SECONDS)).isNull();
      Throwable loserFailure = loser.get(10, TimeUnit.SECONDS);
      assertThat(loserFailure)
          .isInstanceOf(
              CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException.class);
      assertThat(loserFailure.getMessage())
          .contains(commitWins ? "different terminal result" : "definitively ABORTED");

      Record terminal = terminal(harness, prepared.request());
      assertThat(terminal.get("status", String.class))
          .isEqualTo(commitWins ? "COMMITTED" : "ABORTED");
      assertThat(terminal.get("intent_state", String.class)).isEqualTo("TERMINAL");
      assertThat(terminal.get("intent_terminal", OffsetDateTime.class))
          .isEqualTo(terminal.get("terminal_at", OffsetDateTime.class));
      assertThat(terminal.get("account_protection_evidence_bytes", byte[].class))
          .containsExactly(pending.get("account_protection_evidence_bytes", byte[].class));
      for (String column :
          List.of(
              "account_protection_evidence_version",
              "account_protection_id",
              "account_protection_fence",
              "original_owner_attempt_id",
              "original_owner_mutation_id",
              "original_owner_fence")) {
        assertThat(terminal.get(column)).isEqualTo(pending.get(column));
      }
      long pointerCount = ownerRowCount(harness, "gameplay_admission_pointer", prepared.request());
      long auditCount =
          ownerRowCount(harness, "gameplay_admission_pointer_event", prepared.request());
      assertThat(pointerCount).isEqualTo(commitWins ? 1L : 0L);
      assertThat(auditCount).isEqualTo(commitWins ? 1L : 0L);
      if (commitWins) {
        assertThat(terminal.get("commit_proof_digest", String.class)).startsWith("sha256:");
        assertThat(terminal.get("abort_proof_digest")).isNull();
        harness
            .transactions()
            .executeWithoutResult(
                status ->
                    repository.commitProtected(
                        prepared.request(), prepared.protection(), prepared.worldProof()));
        assertThatThrownBy(
                () ->
                    harness
                        .transactions()
                        .executeWithoutResult(
                            status ->
                                repository.abortProtected(
                                    prepared.request(),
                                    prepared.protection(),
                                    "concurrent controlled denial")))
            .isInstanceOf(
                CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException
                    .class)
            .hasMessageContaining("different terminal result");
      } else {
        assertThat(terminal.get("pointer_id")).isNull();
        assertThat(terminal.get("pointer_version")).isNull();
        assertThat(terminal.get("audit_event_id")).isNull();
        assertThat(terminal.get("commit_proof_digest")).isNull();
        assertThat(terminal.get("abort_proof_digest", String.class)).startsWith("sha256:");
        harness
            .transactions()
            .executeWithoutResult(
                status ->
                    repository.abortProtected(
                        prepared.request(), prepared.protection(), "concurrent controlled denial"));
        assertThatThrownBy(
                () ->
                    harness
                        .transactions()
                        .executeWithoutResult(
                            status ->
                                repository.commitProtected(
                                    prepared.request(),
                                    prepared.protection(),
                                    prepared.worldProof())))
            .isInstanceOf(
                CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException
                    .class)
            .hasMessageContaining("ABORTED");
      }
      assertThat(terminal(harness, prepared.request()).formatJSON())
          .isEqualTo(terminal.formatJSON());
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      harness.close();
    }
  }

  private static int postgresBackendPid(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness) {
    return harness.dsl().fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class);
  }

  private static void lockAdmissionTarget(
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

  private static void awaitBlockingEdge(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      int waitingPid,
      int expectedBlockerPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      List<Integer> blockers =
          harness
              .dsl()
              .fetch("SELECT unnest(pg_blocking_pids(?)) AS blocker_pid", waitingPid)
              .getValues("blocker_pid", Integer.class);
      if (blockers.contains(expectedBlockerPid)) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "Losing protected terminal transaction did not block behind the selected winner");
  }

  private static long ownerRowCount(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      String table,
      CanonicalInitialAdmissionRequest request) {
    if (!"gameplay_admission_pointer".equals(table)
        && !"gameplay_admission_pointer_event".equals(table)) {
      throw new IllegalArgumentException("Unexpected protected-writer owner table");
    }
    return harness
        .dsl()
        .fetchSingle(
            "SELECT count(*) FROM "
                + table
                + " WHERE target_namespace = ? AND initial_admission_request_id = ?",
            request.targetNamespace(),
            request.initialAdmissionRequestId())
        .get(0, Long.class);
  }

  private static Throwable captureFailure(ThrowingAction action) {
    try {
      action.run();
      return null;
    } catch (Throwable failure) {
      return failure;
    }
  }

  private static void awaitOriginalOwnerLeaseExpiration(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness, Prepared prepared) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!Boolean.TRUE.equals(
        harness
            .dsl()
            .fetchSingle(
                "SELECT lease_expires_at <= clock_timestamp() "
                    + "FROM game_session_start_session_operator_attempt "
                    + "WHERE target_namespace = ? AND owner_attempt_id = ?",
                prepared.request().targetNamespace(),
                prepared.original().ownerAttemptId())
            .get(0, Boolean.class))) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("Timed out waiting for the legitimate owner lease");
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

  private static void await(CountDownLatch latch, String description) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting to " + description);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted waiting to " + description, interrupted);
    }
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static Prepared prepare(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.OwnerTarget target,
      String requestId,
      int idBase)
      throws Exception {
    WorldCanonicalInitialAdmissionHold.Request holdRequest =
        CanonicalInitialAdmissionIntentRepositoryIntegrationTest.holdRequest(target, requestId);
    WorldCanonicalInitialAdmissionHold.HoldIdentity hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            holdRequest, uuid(idBase), uuid(idBase + 1));
    target
        .harness()
        .transactions()
        .executeWithoutResult(
            status ->
                target.intents().reserve(holdRequest, target.lifecycleRequest(uuid(idBase + 2))));
    target
        .harness()
        .transactions()
        .executeWithoutResult(status -> target.intents().attach(holdRequest, hold));
    CanonicalInitialAdmissionRequest request = request(hold);
    Original original = original(target);
    return new Prepared(
        request,
        hold,
        protection(target, hold, uuid(idBase + 3), 1L, original),
        worldProof(request),
        original);
  }

  private static Original original(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.OwnerTarget target) {
    Record row =
        Objects.requireNonNull(
            target
                .harness()
                .dsl()
                .fetchOne(
                    "SELECT post_authorization_execution_tuple, account_redemption_projection, "
                        + "owner_mutation_id, owner_attempt_id, owner_fence, lease_expires_at, created_at "
                        + "FROM game_session_start_session_operator_attempt "
                        + "WHERE target_namespace = ? AND control_plane_request_id = ?",
                    target.targetNamespace(),
                    target.launch().association().controlPlaneRequestId()));
    return new Original(
        row.get("post_authorization_execution_tuple", byte[].class),
        row.get("account_redemption_projection", byte[].class),
        row.get("owner_mutation_id", UUID.class),
        row.get("owner_attempt_id", UUID.class),
        row.get("owner_fence", Long.class),
        row.get("lease_expires_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /** Controlled Account evidence input; this test does not claim a genuine Account producer. */
  private static AccountStartSessionAdmissionProtectionEvidence protection(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.OwnerTarget target,
      WorldCanonicalInitialAdmissionHold.HoldIdentity hold,
      UUID protectionId,
      long protectionFence,
      Original original)
      throws Exception {
    return protection(
        target, hold, protectionId, protectionFence, original, "controlled-account-source");
  }

  private static AccountStartSessionAdmissionProtectionEvidence protection(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.OwnerTarget target,
      WorldCanonicalInitialAdmissionHold.HoldIdentity hold,
      UUID protectionId,
      long protectionFence,
      Original original,
      String controlledSource)
      throws Exception {
    var request =
        AccountStartSessionAdmissionProtectionRequest.create(
            original.tuple(),
            original.projection(),
            original.ownerMutationId(),
            original.ownerAttemptId(),
            original.ownerFence(),
            original.leaseExpiresAt().toInstant(),
            uuid(699),
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
                controlledSource.getBytes(StandardCharsets.UTF_8))));
  }

  /** Creates only the V1006-legal retained-history shape before V1039 is installed. */
  private static void insertLegacyPending(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.OwnerTarget target,
      CanonicalInitialAdmissionRequest request) {
    harness
        .transactions()
        .executeWithoutResult(
            status ->
                harness
                    .dsl()
                    .execute(
                        "INSERT INTO game_session_canonical_initial_admission_attempt "
                            + "(target_namespace, initial_admission_request_id, request_digest, "
                            + "canonical_tenant_id, world_slug, realm_id, playable_state_namespace_id, "
                            + "playable_state_scope, canonical_game_instance_id, game_session_tenant_id, "
                            + "game_instance_id, canonical_version_id, runtime_version_id, "
                            + "expected_catalog_revision, origin_kind, expected_prior_pointer_version, "
                            + "active_lifecycle_epoch, hold_id, hold_fence, hold_binding_digest, status, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "?, ?, ?, ?, ?, ?, 'PENDING', clock_timestamp(), clock_timestamp())",
                        request.targetNamespace(),
                        request.initialAdmissionRequestId(),
                        request.requestDigest(),
                        request.canonicalTenantId(),
                        request.worldSlug(),
                        request.realmId(),
                        request.playableStateNamespaceId(),
                        request.playableStateScope(),
                        request.canonicalGameInstanceId(),
                        target.launch().association().gameSessionTenantId(),
                        target.launch().gameInstanceId(),
                        request.canonicalVersionId(),
                        target.launch().runtimeVersionId(),
                        request.expectedCatalogRevision(),
                        request.originKind().name(),
                        request.expectedPriorPointerVersion(),
                        request.activeLifecycleEpoch(),
                        request.holdId(),
                        request.holdFence(),
                        request.holdBindingDigest()));
  }

  private static CanonicalInitialAdmissionRepository repository(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness) {
    var catalog = new GameSessionCanonicalRealmCatalogRepository(harness.dsl());
    var events = new GameplayAdmissionPointerEventRepository(harness.dsl());
    var pointers =
        new GameSessionCanonicalAdmissionPointerRepository(harness.dsl(), catalog, events);
    return new CanonicalInitialAdmissionRepository(harness.dsl(), catalog, pointers);
  }

  private static CanonicalInitialAdmissionRequest request(
      WorldCanonicalInitialAdmissionHold.HoldIdentity hold) {
    var world = hold.request();
    return new CanonicalInitialAdmissionRequest(
        world.targetNamespace(),
        world.canonicalTenantId(),
        world.worldSlug(),
        world.realmId(),
        world.playableStateNamespaceId(),
        world.playableStateScope(),
        world.canonicalGameInstanceId(),
        world.canonicalVersionId(),
        world.activeLifecycleEpoch(),
        world.expectedCatalogRevision(),
        CanonicalInitialAdmissionRequest.OriginKind.valueOf(world.initialAdmissionOrigin().name()),
        world.expectedPriorPointerVersion(),
        world.initialAdmissionRequestId(),
        world.initialAdmissionRequestDigest(),
        hold.holdId(),
        hold.holdFence(),
        hold.holdBindingDigest());
  }

  /** Controlled World verifier output; no genuine World service is claimed by this test. */
  private static CanonicalInitialAdmissionWorldProof worldProof(
      CanonicalInitialAdmissionRequest request) {
    return new CanonicalInitialAdmissionWorldProof(
        request.initialAdmissionRequestId(),
        request.requestDigest(),
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        "ACTIVE",
        request.activeLifecycleEpoch(),
        request.originKind(),
        request.expectedCatalogRevision(),
        request.expectedPriorPointerVersion(),
        request.holdId(),
        request.holdFence(),
        request.holdBindingDigest());
  }

  private static Record terminal(
      CanonicalInitialAdmissionIntentRepositoryIntegrationTest.Harness harness,
      CanonicalInitialAdmissionRequest request) {
    return harness
        .dsl()
        .fetchSingle(
            "SELECT a.*, i.state AS intent_state, i.terminal_at AS intent_terminal "
                + "FROM game_session_canonical_initial_admission_attempt a "
                + "JOIN game_session_canonical_initial_admission_intent i "
                + "ON i.target_namespace = a.target_namespace "
                + "AND i.initial_admission_request_id = a.initial_admission_request_id "
                + "WHERE a.target_namespace = ? AND a.initial_admission_request_id = ?",
            request.targetNamespace(),
            request.initialAdmissionRequestId());
  }

  private static UUID uuid(int value) {
    return CanonicalInitialAdmissionIntentRepositoryIntegrationTest.uuid(value);
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private record Prepared(
      CanonicalInitialAdmissionRequest request,
      WorldCanonicalInitialAdmissionHold.HoldIdentity hold,
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
