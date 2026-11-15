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
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .locations("classpath:db/migration")
        .load()
        .migrate();
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
            prepare.effect().kind(),
            prepare.effect().operationTupleDigest(),
            prepare.payloadDigest(),
            EffectReadbackState.PREPARED_FENCED,
            EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
            "gamesession-preparation-readback-1",
            base.plusSeconds(6));
    Operation releasing =
        journal.recordEffectReadback(
            scope.operationId(),
            prepare.effect().effectId(),
            prepareAttempt,
            prepareReadback,
            base.plusSeconds(7));
    assertThat(releasing.phase()).isEqualTo(Phase.RELEASING);
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
    assertThat(authorized.phase()).isEqualTo(Phase.RESUME_AUTHORIZED);
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
