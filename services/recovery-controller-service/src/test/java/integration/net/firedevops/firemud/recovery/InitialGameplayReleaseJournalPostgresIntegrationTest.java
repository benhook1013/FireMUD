package integration.net.firedevops.firemud.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseConflictException;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseTupleCodec;
import net.firedevops.firemud.recovery.journal.JooqInitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectKind;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectOutboxState;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackAuthority;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackState;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.InitialGameplayReleaseEvidence;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.IssuerLayoutProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.LayoutDescriptor;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.MigrationProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OutboxEntry;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OwnerInitializationProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OwnerService;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PartitionAcknowledgement;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PeerStorageBinding;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Phase;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.ProofDisposition;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PublicTransitionTuple;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.RegionLeaseProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.TransitionVerb;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterDisposition;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterFenceAcknowledgement;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterFenceProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterInventory;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class InitialGameplayReleaseJournalPostgresIntegrationTest {
  private static final String SCHEMA = "recovery_control";
  private static final String DIGEST = "b".repeat(64);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static Connection connection;
  private static DSLContext dsl;
  private static InitialGameplayReleaseTupleCodec tupleCodec;
  private static InitialGameplayReleaseJournal journal;

  @BeforeAll
  static void setUp() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .placeholders(Map.of("serviceSchema", SCHEMA))
            .locations("classpath:db/recovery-control-migration")
            .load();
    assertThat(flyway.info().all())
        .extracting(info -> info.getScript())
        .containsExactly("V1__initial_gameplay_release_journal.sql");
    flyway.migrate();
    connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dsl = DSL.using(connection, SQLDialect.POSTGRES);
    tupleCodec =
        new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build());
    journal = new JooqInitialGameplayReleaseJournal(dsl, tupleCodec);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (connection != null) {
      connection.close();
    }
  }

  @BeforeEach
  void clearJournal() {
    dsl.execute(
        "TRUNCATE TABLE recovery_control.initial_gameplay_release_outbox, "
            + "recovery_control.initial_gameplay_release_transition, "
            + "recovery_control.initial_gameplay_release_operation");
  }

  @Test
  void journalPersistsExactScopeEvidenceTransitionsAndOwnerReadbackGatedHandoff() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    Scope scope = scope();
    UUID lockIdentity = UUID.randomUUID();

    Operation created = journal.create(scope, lockIdentity);
    Operation duplicateCreate = journal.create(scope, UUID.randomUUID());
    assertThat(created.phase()).isEqualTo(Phase.COLLECTING);
    assertThat(duplicateCreate.maintenanceLockIdentity()).isEqualTo(lockIdentity);
    assertThat(journal.find(created.scope().operationId())).contains(created);
    assertThat(journal.find(UUID.randomUUID())).isEmpty();

    InitialGameplayReleaseEvidence proof = evidence(scope);
    Operation ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base.plusSeconds(1));
    assertThat(ready.phase()).isEqualTo(Phase.READY_TO_REOPEN);
    assertThat(ready.evidence()).isEqualTo(proof);

    PublicTransitionTuple continueTuple = transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY);
    assertThat(journal.findTransition(continueTuple)).isEmpty();
    Operation awaitingResume = journal.transition(continueTuple, base.plusSeconds(2));
    assertThat(awaitingResume.phase()).isEqualTo(Phase.AWAITING_RESUME);
    assertThat(journal.findTransition(continueTuple)).contains(awaitingResume);
    assertThat(journal.transition(continueTuple, base.plusSeconds(3))).isEqualTo(awaitingResume);

    PublicTransitionTuple resumeTuple = transitionTuple(awaitingResume, TransitionVerb.RESUME);
    Operation authorized = journal.transition(resumeTuple, base.plusSeconds(4));
    assertThat(authorized.phase()).isEqualTo(Phase.RESUME_AUTHORIZED);
    OutboxEntry prepare =
        journal
            .findEffect(scope.operationId(), EffectKind.PREPARE_PLAYABLE_STATE_FENCED)
            .orElseThrow();
    assertThat(prepare.state()).isEqualTo(EffectOutboxState.PENDING);

    UUID prepareAttempt = UUID.randomUUID();
    prepare =
        journal.claimEffect(
            scope.operationId(), prepare.effect().effectId(), prepareAttempt, base.plusSeconds(5));
    EffectReadback prepareReadback =
        new EffectReadback(
            scope.operationId(),
            prepare.effect().effectId(),
            prepareAttempt,
            prepare.effect().kind(),
            prepare.effect().operationTupleDigest(),
            prepare.payloadDigest(),
            EffectReadbackState.PREPARED_FENCED,
            EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
            "gamesession-preparation-readback-1",
            base.plusSeconds(6));
    UUID prepareEffectId = prepare.effect().effectId();
    assertThatThrownBy(
            () ->
                journal.recordEffectReadback(
                    scope.operationId(),
                    prepareEffectId,
                    prepareAttempt,
                    prepareReadback,
                    base.plusSeconds(5)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("observation is in the future");
    assertThat(journal.find(scope.operationId())).contains(authorized);
    assertThat(journal.findEffect(scope.operationId(), EffectKind.PREPARE_PLAYABLE_STATE_FENCED))
        .contains(prepare);
    assertThat(journal.findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isEmpty();
    EffectReadback otherAttempt =
        new EffectReadback(
            scope.operationId(),
            prepareEffectId,
            UUID.randomUUID(),
            prepare.effect().kind(),
            prepare.effect().operationTupleDigest(),
            prepare.payloadDigest(),
            EffectReadbackState.PREPARED_FENCED,
            EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
            "wrong-attempt",
            base.plusSeconds(6));
    assertThatThrownBy(
            () ->
                journal.recordEffectReadback(
                    scope.operationId(),
                    prepareEffectId,
                    prepareAttempt,
                    otherAttempt,
                    base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    journal.recordAmbiguousEffect(
        scope.operationId(),
        prepare.effect().effectId(),
        prepareAttempt,
        "TRANSPORT_OUTCOME_UNKNOWN",
        base.plusSeconds(5));
    Operation releasing =
        journal.recordEffectReadback(
            scope.operationId(),
            prepare.effect().effectId(),
            prepareAttempt,
            prepareReadback,
            prepareReadback.observedAt());
    assertThat(releasing.phase()).isEqualTo(Phase.RELEASING);
    assertThat(
            journal.recordEffectReadback(
                scope.operationId(),
                prepareEffectId,
                prepareAttempt,
                prepareReadback,
                base.plusSeconds(5)))
        .isEqualTo(releasing);
    assertThat(
            journal
                .recordAmbiguousEffect(
                    scope.operationId(),
                    prepare.effect().effectId(),
                    prepareAttempt,
                    "LATE_TRANSPORT_OUTCOME_UNKNOWN",
                    base.plusSeconds(8))
                .state())
        .isEqualTo(EffectOutboxState.ACKNOWLEDGED);
    UUID preparedEffectId = prepare.effect().effectId();
    String retainedAcknowledgement = storedOutbox(scope.operationId(), preparedEffectId);
    EffectReadback laterObservation =
        readbackFor(
            prepare,
            prepareAttempt,
            EffectReadbackState.PREPARED_FENCED,
            prepareReadback.readbackIdentity(),
            base.plusSeconds(8));
    assertThatThrownBy(
            () ->
                journal.recordEffectReadback(
                    scope.operationId(),
                    preparedEffectId,
                    prepareAttempt,
                    laterObservation,
                    base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(scope.operationId(), preparedEffectId))
        .isEqualTo(retainedAcknowledgement);
    assertThat(
            journal.recordEffectReadback(
                scope.operationId(),
                preparedEffectId,
                prepareAttempt,
                laterObservation,
                base.plusSeconds(9)))
        .isEqualTo(releasing);
    EffectReadback changedReceipt =
        readbackFor(
            prepare,
            prepareAttempt,
            EffectReadbackState.PREPARED_FENCED,
            "different-durable-receipt",
            base.plusSeconds(8));
    assertThatThrownBy(
            () ->
                journal.recordEffectReadback(
                    scope.operationId(),
                    preparedEffectId,
                    prepareAttempt,
                    changedReceipt,
                    base.plusSeconds(9)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(scope.operationId(), preparedEffectId))
        .isEqualTo(retainedAcknowledgement);
    assertThatThrownBy(
            () ->
                journal.recordAmbiguousEffect(
                    scope.operationId(),
                    preparedEffectId,
                    UUID.randomUUID(),
                    "WRONG_ATTEMPT",
                    base.plusSeconds(8)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(journal.findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.PENDING);

    OutboxEntry handoff =
        journal
            .findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF)
            .orElseThrow();
    UUID handoffAttempt = UUID.randomUUID();
    handoff =
        journal.claimEffect(
            scope.operationId(), handoff.effect().effectId(), handoffAttempt, base.plusSeconds(8));
    EffectReadback handoffReadback =
        new EffectReadback(
            scope.operationId(),
            handoff.effect().effectId(),
            handoffAttempt,
            handoff.effect().kind(),
            handoff.effect().operationTupleDigest(),
            handoff.payloadDigest(),
            EffectReadbackState.OWNER_HANDOFF_APPLIED,
            EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
            "gamesession-owner-handoff-readback-1",
            base.plusSeconds(9));
    Operation finalized =
        journal.recordEffectReadback(
            scope.operationId(),
            handoff.effect().effectId(),
            handoffAttempt,
            handoffReadback,
            base.plusSeconds(10));

    assertThat(finalized.phase()).isEqualTo(Phase.FINALIZED);
    assertThat(finalized.finalizedAt()).isEqualTo(base.plusSeconds(10));
    assertThat(journal.findNextEffect(scope.operationId())).isEmpty();
  }

  @Test
  void tupleConflictAndAmbiguousEffectNeverAdvanceOrRedispatch() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    Scope scope = scope();
    Operation operation = journal.create(scope, UUID.randomUUID());
    InitialGameplayReleaseEvidence proof = evidence(scope);
    Operation ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base.plusSeconds(1));

    PublicTransitionTuple wrongLock =
        new PublicTransitionTuple(
            ready.scope().operationId(),
            tupleCodec.digest(scope),
            TransitionVerb.CONTINUE_RECOVERY,
            Phase.READY_TO_REOPEN,
            UUID.randomUUID(),
            scope.evidenceRef(),
            proof.evidenceIdentity(),
            tupleCodec.digest(proof));
    assertThatThrownBy(() -> journal.transition(wrongLock, base.plusSeconds(2)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    PublicTransitionTuple wrongEvidence =
        new PublicTransitionTuple(
            ready.scope().operationId(),
            tupleCodec.digest(scope),
            TransitionVerb.CONTINUE_RECOVERY,
            Phase.READY_TO_REOPEN,
            ready.maintenanceLockIdentity(),
            "different-evidence-reference",
            proof.evidenceIdentity(),
            tupleCodec.digest(proof));
    assertThatThrownBy(() -> journal.transition(wrongEvidence, base.plusSeconds(2)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(journal.find(operation.scope().operationId()).orElseThrow().phase())
        .isEqualTo(Phase.READY_TO_REOPEN);

    Operation awaiting =
        journal.transition(
            transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY), base.plusSeconds(3));
    Operation authorized =
        journal.transition(transitionTuple(awaiting, TransitionVerb.RESUME), base.plusSeconds(4));
    OutboxEntry prepare = journal.findNextEffect(scope.operationId()).orElseThrow();
    UUID attemptId = UUID.randomUUID();
    OutboxEntry dispatched =
        journal.claimEffect(
            scope.operationId(), prepare.effect().effectId(), attemptId, base.plusSeconds(5));
    assertThat(dispatched.state()).isEqualTo(EffectOutboxState.DISPATCHED);
    journal.recordAmbiguousEffect(
        scope.operationId(),
        prepare.effect().effectId(),
        attemptId,
        "TRANSPORT_OUTCOME_UNKNOWN",
        base.plusSeconds(6));
    OutboxEntry retryRead =
        journal.claimEffect(
            scope.operationId(),
            prepare.effect().effectId(),
            UUID.randomUUID(),
            base.plusSeconds(7));

    assertThat(retryRead.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
    assertThat(retryRead.lastAttemptId()).isEqualTo(attemptId);
    assertThat(retryRead.attemptCount()).isEqualTo(1);
    assertThat(journal.find(scope.operationId())).contains(authorized);
    assertThat(journal.findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isEmpty();
    OutboxEntry definitiveNoEffect =
        journal.recordRetryableEffect(
            scope.operationId(),
            prepare.effect().effectId(),
            attemptId,
            "OWNER_DECLINED_WITHOUT_EFFECT",
            base.plusSeconds(8));
    assertThat(definitiveNoEffect.state()).isEqualTo(EffectOutboxState.RETRYABLE);
    UUID nextAttempt = UUID.randomUUID();
    OutboxEntry next =
        journal.claimEffect(
            scope.operationId(), prepare.effect().effectId(), nextAttempt, base.plusSeconds(9));
    assertThat(next.lastAttemptId()).isEqualTo(nextAttempt);
    assertThat(next.attemptCount()).isEqualTo(2);
  }

  @Test
  void delayedSameAttemptPreparationCannotOverrideNewerAmbiguity() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    OutboxEntry dispatched = authorizedDispatch(base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    UUID attemptId = dispatched.lastAttemptId();
    Operation before = journal.find(operationId).orElseThrow();
    EffectReadback unknown =
        readbackFor(
            dispatched,
            attemptId,
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "latest-unknown",
            base.plusSeconds(6));
    journal.recordEffectReadback(operationId, effectId, attemptId, unknown, base.plusSeconds(6));
    String retained = storedOutbox(operationId, effectId);

    for (EffectReadbackState delayedState :
        List.of(EffectReadbackState.UNKNOWN_OR_PARTIAL, EffectReadbackState.PREPARED_FENCED)) {
      EffectReadback delayed =
          readbackFor(dispatched, attemptId, delayedState, "delayed", base.plusSeconds(5));
      assertThatThrownBy(
              () ->
                  journal.recordEffectReadback(
                      operationId, effectId, attemptId, delayed, base.plusSeconds(7)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("predates the retained same-attempt observation");
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
      assertThat(journal.find(operationId)).contains(before);
      assertThat(journal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
          .isEmpty();
    }

    EffectReadback fresh =
        readbackFor(
            dispatched,
            attemptId,
            EffectReadbackState.PREPARED_FENCED,
            "fresh-owner-proof",
            base.plusSeconds(8));
    Operation advanced =
        journal.recordEffectReadback(operationId, effectId, attemptId, fresh, base.plusSeconds(8));
    assertThat(advanced.phase()).isEqualTo(Phase.RELEASING);
    assertThat(journal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isPresent();
  }

  @Test
  void definitiveAttemptClosureIsReplayableAndCannotBeOverwritten() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    Scope scope = scope();
    journal.create(scope, UUID.randomUUID());
    InitialGameplayReleaseEvidence proof = evidence(scope);
    Operation ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base.plusSeconds(1));
    Operation awaitingResume =
        journal.transition(
            transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY), base.plusSeconds(2));
    Operation authorized =
        journal.transition(
            transitionTuple(awaitingResume, TransitionVerb.RESUME), base.plusSeconds(3));
    UUID effectId =
        journal
            .findEffect(scope.operationId(), EffectKind.PREPARE_PLAYABLE_STATE_FENCED)
            .orElseThrow()
            .effect()
            .effectId();
    UUID firstAttempt = UUID.randomUUID();
    OutboxEntry dispatched =
        journal.claimEffect(scope.operationId(), effectId, firstAttempt, base.plusSeconds(4));
    EffectReadback terminal =
        readbackFor(
            dispatched,
            firstAttempt,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "durable-closure",
            base.plusSeconds(5));
    assertThat(
            journal.recordEffectReadback(
                scope.operationId(), effectId, firstAttempt, terminal, base.plusSeconds(6)))
        .isEqualTo(authorized);
    String retained = storedOutbox(scope.operationId(), effectId);

    assertThat(
            journal.recordEffectReadback(
                scope.operationId(), effectId, firstAttempt, terminal, base.plusSeconds(7)))
        .isEqualTo(authorized);
    assertThat(
            journal
                .recordAmbiguousEffect(
                    scope.operationId(),
                    effectId,
                    firstAttempt,
                    "LATE_TIMEOUT",
                    base.plusSeconds(7))
                .state())
        .isEqualTo(EffectOutboxState.RETRYABLE);
    assertThatThrownBy(
            () ->
                journal.recordAmbiguousEffect(
                    scope.operationId(),
                    effectId,
                    UUID.randomUUID(),
                    "WRONG_ATTEMPT",
                    base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    EffectReadback changed =
        readbackFor(
            dispatched,
            firstAttempt,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "durable-closure",
            base.plusSeconds(6));
    assertThatThrownBy(
            () ->
                journal.recordEffectReadback(
                    scope.operationId(), effectId, firstAttempt, changed, base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(scope.operationId(), effectId)).isEqualTo(retained);

    UUID replacementAttempt = UUID.randomUUID();
    assertThat(
            journal
                .claimEffect(scope.operationId(), effectId, replacementAttempt, base.plusSeconds(8))
                .lastAttemptId())
        .isEqualTo(replacementAttempt);
    String replacement = storedOutbox(scope.operationId(), effectId);
    assertThat(replacement)
        .contains(
            "\"readback_identity\":null", "\"readback_payload\":null", "\"readback_digest\":null");
    assertThatThrownBy(
            () ->
                journal.recordEffectReadback(
                    scope.operationId(), effectId, firstAttempt, terminal, base.plusSeconds(9)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(scope.operationId(), effectId)).isEqualTo(replacement);
  }

  @Test
  void firstDurableClosureAfterDefinitiveRejectionIsRetainedAndFenced() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    OutboxEntry dispatched = authorizedDispatch(base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    UUID attemptId = dispatched.lastAttemptId();
    journal.recordRetryableEffect(
        operationId, effectId, attemptId, "OWNER_REJECTED", base.plusSeconds(5));
    String before = storedOutbox(operationId, effectId);
    EffectReadback stale =
        readbackFor(
            dispatched,
            attemptId,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closure",
            base.plusSeconds(3));
    assertThatThrownBy(
            () ->
                journal.recordEffectReadback(
                    operationId, effectId, attemptId, stale, base.plusSeconds(6)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    EffectReadback future =
        readbackFor(
            dispatched,
            attemptId,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closure",
            base.plusSeconds(7));
    assertThatThrownBy(
            () ->
                journal.recordEffectReadback(
                    operationId, effectId, attemptId, future, base.plusSeconds(6)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
    EffectReadback closure =
        readbackFor(
            dispatched,
            attemptId,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closure",
            base.plusSeconds(6));
    journal.recordEffectReadback(operationId, effectId, attemptId, closure, base.plusSeconds(6));
    String retained = storedOutbox(operationId, effectId);
    assertThat(retained).contains(tupleCodec.digest(closure));
    journal.recordEffectReadback(operationId, effectId, attemptId, closure, base.plusSeconds(7));
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    assertThatThrownBy(
            () ->
                journal.recordEffectReadback(
                    operationId, effectId, attemptId, future, base.plusSeconds(8)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    UUID nextAttempt = UUID.randomUUID();
    journal.claimEffect(operationId, effectId, nextAttempt, base.plusSeconds(8));
    String replaced = storedOutbox(operationId, effectId);
    assertThatThrownBy(
            () ->
                journal.recordEffectReadback(
                    operationId, effectId, attemptId, closure, base.plusSeconds(9)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(replaced);
  }

  @Test
  void unknownReadbackBeforeDefinitiveRejectionAllowsOnlyValidatedSameAttemptClosure() {
    for (String variant : List.of("valid", "corrupt-digest", "other-attempt", "missing-digest")) {
      clearJournal();
      Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
      OutboxEntry dispatched = authorizedDispatch(base);
      UUID operationId = dispatched.effect().operationId();
      UUID effectId = dispatched.effect().effectId();
      UUID attemptId = dispatched.lastAttemptId();
      EffectReadback unknown =
          readbackFor(
              dispatched,
              attemptId,
              EffectReadbackState.UNKNOWN_OR_PARTIAL,
              "unknown",
              base.plusSeconds(5));
      journal.recordEffectReadback(operationId, effectId, attemptId, unknown, base.plusSeconds(5));
      journal.recordRetryableEffect(
          operationId, effectId, attemptId, "OWNER_REJECTED", base.plusSeconds(6));
      if (variant.equals("missing-digest")) {
        String retained = storedOutbox(operationId, effectId);
        assertThatThrownBy(
                () ->
                    dsl.execute(
                        "UPDATE recovery_control.initial_gameplay_release_outbox SET readback_digest=NULL WHERE effect_id=?",
                        effectId))
            .isInstanceOf(org.jooq.exception.IntegrityConstraintViolationException.class);
        assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
        continue;
      }
      if (!variant.equals("valid")) {
        EffectReadback stored =
            variant.equals("other-attempt")
                ? readbackFor(
                    dispatched,
                    UUID.randomUUID(),
                    EffectReadbackState.UNKNOWN_OR_PARTIAL,
                    "unknown",
                    base.plusSeconds(5))
                : unknown;
        dsl.execute(
            "UPDATE recovery_control.initial_gameplay_release_outbox SET readback_payload=CAST(? AS jsonb), readback_digest=? WHERE effect_id=?",
            tupleCodec.toJson(stored),
            variant.equals("missing-digest")
                ? null
                : variant.equals("corrupt-digest") ? "a".repeat(64) : tupleCodec.digest(stored),
            effectId);
      }
      String before = storedOutbox(operationId, effectId);
      EffectReadback stale =
          readbackFor(
              dispatched,
              attemptId,
              EffectReadbackState.PROVEN_NO_EFFECT,
              "closure",
              base.plusSeconds(3));
      assertThatThrownBy(
              () ->
                  journal.recordEffectReadback(
                      operationId, effectId, attemptId, stale, base.plusSeconds(7)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class);
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
      EffectReadback closure =
          readbackFor(
              dispatched,
              attemptId,
              EffectReadbackState.PROVEN_NO_EFFECT,
              "closure",
              base.plusSeconds(7));
      if (variant.equals("valid")) {
        journal.recordEffectReadback(
            operationId, effectId, attemptId, closure, base.plusSeconds(7));
        String retained = storedOutbox(operationId, effectId);
        assertThat(retained).contains(tupleCodec.digest(closure));
        assertThatThrownBy(
                () ->
                    journal.recordEffectReadback(
                        operationId, effectId, attemptId, unknown, base.plusSeconds(8)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class);
        assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
      } else {
        assertThatThrownBy(
                () ->
                    journal.recordEffectReadback(
                        operationId, effectId, attemptId, closure, base.plusSeconds(7)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class);
        assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
      }
    }
  }

  @Test
  void dispatchFloorUsesStoredMicrosecondsWithoutRoundingFutureOrLosingReceiptPrecision() {
    for (int nanos : new int[] {499, 999, 999_999_999}) {
      clearJournal();
      Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60).plusNanos(nanos);
      OutboxEntry dispatched = authorizedDispatch(base);
      UUID operationId = dispatched.effect().operationId();
      UUID effectId = dispatched.effect().effectId();
      Instant dispatchInstant = base.plusSeconds(4);
      Instant floor = dispatchInstant.truncatedTo(ChronoUnit.MICROS);
      assertThat(
              java.util.Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT last_dispatched_at FROM recovery_control.initial_gameplay_release_outbox WHERE effect_id = ?",
                          effectId))
                  .get("last_dispatched_at", java.time.OffsetDateTime.class)
                  .toInstant())
          .isEqualTo(floor);
      String before = storedOutbox(operationId, effectId);
      EffectReadback stale =
          readbackFor(
              dispatched,
              dispatched.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              "before-dispatch-floor",
              floor.minusNanos(1));
      assertThatThrownBy(
              () ->
                  journal.recordEffectReadback(
                      operationId,
                      effectId,
                      dispatched.lastAttemptId(),
                      stale,
                      base.plusSeconds(5)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class);
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
      EffectReadback closure =
          readbackFor(
              dispatched,
              dispatched.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              "same-instant",
              dispatchInstant);
      journal.recordEffectReadback(
          operationId, effectId, dispatched.lastAttemptId(), closure, base.plusSeconds(5));
      assertThat(storedOutbox(operationId, effectId)).contains(tupleCodec.digest(closure));
    }
  }

  private static OutboxEntry authorizedDispatch(Instant base) {
    Scope scope = scope();
    journal.create(scope, UUID.randomUUID());
    InitialGameplayReleaseEvidence proof = evidence(scope);
    Operation ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base.plusSeconds(1));
    Operation awaiting =
        journal.transition(
            transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY), base.plusSeconds(2));
    journal.transition(transitionTuple(awaiting, TransitionVerb.RESUME), base.plusSeconds(3));
    OutboxEntry pending = journal.findNextEffect(scope.operationId()).orElseThrow();
    return journal.claimEffect(
        scope.operationId(), pending.effect().effectId(), UUID.randomUUID(), base.plusSeconds(4));
  }

  private static String storedOutbox(UUID operationId, UUID effectId) {
    return java.util.Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT row_to_json(o)::text AS row_json FROM recovery_control.initial_gameplay_release_outbox o"
                    + " WHERE operation_id = ? AND effect_id = ?",
                operationId,
                effectId),
            "expected persisted outbox row")
        .get("row_json", String.class);
  }

  private static EffectReadback readbackFor(
      OutboxEntry entry,
      UUID attempt,
      EffectReadbackState state,
      String identity,
      Instant observedAt) {
    return new EffectReadback(
        entry.effect().operationId(),
        entry.effect().effectId(),
        attempt,
        entry.effect().kind(),
        entry.effect().operationTupleDigest(),
        entry.payloadDigest(),
        state,
        state == EffectReadbackState.PROVEN_NO_EFFECT
            ? EffectReadbackAuthority.GAME_SESSION_DURABLE_CLOSED_ATTEMPT
            : EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
        identity,
        observedAt);
  }

  @Test
  void createRetryWithChangedCanonicalTenantConflicts() {
    Scope original = scope();
    Operation created = journal.create(original, UUID.randomUUID());
    Scope changedTenant =
        new Scope(
            original.operationId(),
            UUID.randomUUID(),
            original.targetNamespace(),
            original.playableStateNamespaceId(),
            original.peerStorageBinding(),
            original.evidenceRef());

    assertThat(tupleCodec.digest(changedTenant)).isNotEqualTo(tupleCodec.digest(original));
    assertThatThrownBy(() -> journal.create(changedTenant, UUID.randomUUID()))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(journal.find(original.operationId())).contains(created);
  }

  private static PublicTransitionTuple transitionTuple(Operation operation, TransitionVerb verb) {
    return new PublicTransitionTuple(
        operation.scope().operationId(),
        tupleCodec.digest(operation.scope()),
        verb,
        verb.expectedPhase(),
        operation.maintenanceLockIdentity(),
        operation.scope().evidenceRef(),
        operation.evidence().evidenceIdentity(),
        tupleCodec.digest(operation.evidence()));
  }

  private static Scope scope() {
    return new Scope(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "release-target",
        UUID.randomUUID(),
        new PeerStorageBinding(
            "release-target", "cluster-a", "recovery-peer", "storage-uid-1", "revision-7", DIGEST),
        "immutable-initial-layout-evidence");
  }

  private static InitialGameplayReleaseEvidence evidence(Scope scope) {
    LayoutDescriptor descriptor =
        new LayoutDescriptor("issuer-layout-v1", "layout-r4", DIGEST, List.of(0, 1));
    IssuerLayoutProof issuerLayout =
        new IssuerLayoutProof(
            UUID.randomUUID(),
            descriptor,
            "account-issuer-source-identity-1",
            101,
            UUID.randomUUID(),
            "writer-inventory-r9",
            DIGEST,
            List.of(
                new PartitionAcknowledgement(0, "partition-0-ack", DIGEST, DIGEST, 32),
                new PartitionAcknowledgement(1, "partition-1-ack", DIGEST, DIGEST, 64)),
            Map.of(0, 32, 1, 64));
    WriterInventory inventory =
        new WriterInventory(
            "writer-inventory-r9", DIGEST, "legacy-layout-writer", List.of("writer-a", "writer-b"));
    WriterFenceProof writers =
        new WriterFenceProof(
            inventory,
            WriterDisposition.STOPPED,
            List.of(
                new WriterFenceAcknowledgement(
                    "writer-a", WriterDisposition.FENCED, "fence-a", DIGEST),
                new WriterFenceAcknowledgement(
                    "writer-b", WriterDisposition.FENCED, "fence-b", DIGEST)));
    return new InitialGameplayReleaseEvidence(
        scope.evidenceRef(),
        "evidence-owner-record-31",
        new MigrationProof(
            UUID.randomUUID(),
            ProofDisposition.VERIFIED,
            scope.peerStorageBinding().storageIdentity(),
            "writer-inventory-r9",
            DIGEST),
        issuerLayout,
        writers,
        new OwnerInitializationProof(
            OwnerService.WORLD_MANAGEMENT,
            UUID.randomUUID(),
            scope.playableStateNamespaceId(),
            ProofDisposition.VERIFIED,
            "world-init-source-1",
            DIGEST),
        new OwnerInitializationProof(
            OwnerService.ENTITY_MANAGEMENT,
            UUID.randomUUID(),
            scope.playableStateNamespaceId(),
            ProofDisposition.VERIFIED,
            "entity-init-source-1",
            DIGEST),
        new RegionLeaseProof("region-a", "lease-identity-51", 10));
  }
}
