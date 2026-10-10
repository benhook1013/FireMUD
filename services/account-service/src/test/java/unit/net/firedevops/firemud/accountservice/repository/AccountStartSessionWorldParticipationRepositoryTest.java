package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.Candidate;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionAuthorityCapture;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.BundleReference;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Mocked jOOQ rows prove storage composition only, not PostgreSQL guards or authenticated proof.
 */
class AccountStartSessionWorldParticipationRepositoryTest {
  private static final String REQUEST_ID =
      "start-session/operator/2355f292-ab9c-458c-8d52-aef18b9254e8";
  private static final UUID ACTOR_ID = UUID.fromString("d888ddc4-4a62-4b25-9bab-c4f94860c2ca");
  private static final UUID TENANT_ID = UUID.fromString("6d1e5ce5-6127-4d35-88b8-7a6f40692038");
  private static final UUID TARGET_ACCOUNT_ID =
      UUID.fromString("1df91ae6-5125-4e47-9f1d-2e45eab8a4a0");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("ca1b63bf-f09f-4a55-96bf-a1fd2e22349e");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("51c67424-324e-486d-b3ad-45b6e85c1a0d");
  private static final UUID OWNER_ATTEMPT_ID =
      UUID.fromString("c0df9691-cba5-4274-a49d-0bc7b2158ef7");
  private static final UUID PARTICIPATION_ID =
      UUID.fromString("fa3555ac-e039-4827-9e04-eb0fa8cd37d4");
  private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
  private static final String LOGGING_URI =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final BundleReference BUNDLE_REFERENCE =
      new BundleReference("authorityEvidenceBundle/v1", "11", "13", "17");
  private static final Field<UUID> PARTICIPATION_ID_FIELD =
      field(name("participation_id"), SQLDataType.UUID);
  private static final Field<Long> PARTICIPATION_FENCE_FIELD =
      field(name("participation_fence"), SQLDataType.BIGINT);
  private static final Field<String> REQUEST_ID_FIELD =
      field(name("control_plane_request_id"), SQLDataType.VARCHAR);
  private static final Field<byte[]> ORIGINAL_TUPLE_FIELD =
      field(name("original_post_authorization_tuple"), SQLDataType.VARBINARY);
  private static final Field<String> NAMESPACE_FIELD =
      field(name("target_namespace"), SQLDataType.VARCHAR);
  private static final Field<UUID> TENANT_FIELD =
      field(name("canonical_tenant_id"), SQLDataType.UUID);
  private static final Field<UUID> GAME_INSTANCE_FIELD =
      field(name("canonical_game_instance_id"), SQLDataType.UUID);
  private static final Field<UUID> OWNER_ATTEMPT_FIELD =
      field(name("game_session_owner_attempt_id"), SQLDataType.UUID);
  private static final Field<Long> OWNER_FENCE_FIELD =
      field(name("game_session_owner_fence"), SQLDataType.BIGINT);
  private static final Field<String> PREPARATION_JSON_FIELD =
      field(name("preparation_input_json"), SQLDataType.VARCHAR);
  private static final Field<String> PREPARATION_DIGEST_FIELD =
      field(name("preparation_input_digest"), SQLDataType.VARCHAR);
  private static final Field<Long> PRODUCER_XID_FIELD =
      field(name("producer_xid"), SQLDataType.BIGINT);
  private static final Field<OffsetDateTime> CREATED_AT_FIELD =
      field(name("created_at"), SQLDataType.OFFSETDATETIME);
  private static final Field<String> UNKNOWN_FIELD =
      field(name("future_unreviewed_column"), SQLDataType.VARCHAR);
  private static final Field<String> CAPTURE_REQUEST_ID_FIELD =
      field(name("control_plane_request_id"), SQLDataType.VARCHAR);
  private static final Field<UUID> CAPTURE_ACCOUNT_FIELD =
      field(name("account_uuid"), SQLDataType.UUID);
  private static final Field<UUID> CAPTURE_TENANT_FIELD =
      field(name("tenant_uuid"), SQLDataType.UUID);
  private static final Field<String> CAPTURE_TARGET_OWNER_FIELD =
      field(name("target_owner"), SQLDataType.VARCHAR);
  private static final Field<String> CAPTURE_LOGGING_URI_FIELD =
      field(name("logging_workload_uri"), SQLDataType.VARCHAR);
  private static final Field<UUID> CAPTURE_RESERVATION_OWNER_FIELD =
      field(name("reservation_owner_id"), SQLDataType.UUID);
  private static final Field<Long> CAPTURE_RESERVATION_FENCE_FIELD =
      field(name("reservation_claim_fence"), SQLDataType.BIGINT);
  private static final Field<byte[]> CAPTURE_PRE_TUPLE_FIELD =
      field(name("pre_authorization_tuple"), SQLDataType.VARBINARY);
  private static final Field<String> CAPTURE_MUTATION_DIGEST_FIELD =
      field(name("mutation_digest"), SQLDataType.VARCHAR);
  private static final Field<UUID> CAPTURE_OPERATION_ID_FIELD =
      field(name("control_ui_operation_id"), SQLDataType.UUID);
  private static final Field<UUID> CAPTURE_TOKEN_JTI_FIELD =
      field(name("control_ui_token_jti"), SQLDataType.UUID);
  private static final Field<String> CAPTURE_TOKEN_HASH_FIELD =
      field(name("control_ui_token_hash"), SQLDataType.VARCHAR);
  private static final Field<String> CAPTURE_RECEIPT_DIGEST_FIELD =
      field(name("control_ui_signer_receipt_sha256"), SQLDataType.VARCHAR);
  private static final Field<Long> CAPTURE_ISSUANCE_FENCE_FIELD =
      field(name("issuance_fence"), SQLDataType.BIGINT);
  private static final Field<Long> CAPTURE_ISSUANCE_SOURCE_VERSION_FIELD =
      field(name("issuance_fence_source_version"), SQLDataType.BIGINT);
  private static final Field<OffsetDateTime> CAPTURED_AT_FIELD =
      field(name("captured_at"), SQLDataType.OFFSETDATETIME);
  private static final Field<String> CAPTURE_SNAPSHOT_DIGEST_FIELD =
      field(name("snapshot_sha256"), SQLDataType.VARCHAR);
  private static final Field<byte[]> CAPTURE_SNAPSHOT_BYTES_FIELD =
      field(name("canonical_snapshot_bytes"), SQLDataType.VARBINARY);
  private static final Field<Long> CAPTURE_SOURCE_VERSION_FIELD =
      field(name("source_version"), SQLDataType.BIGINT);
  private static final Field<Long> CAPTURE_SOURCE_FENCE_FIELD =
      field(name("source_fence"), SQLDataType.BIGINT);
  private static final Field<String> CAPTURE_LINEARIZATION_FIELD =
      field(name("linearization"), SQLDataType.VARCHAR);
  private static final Field<String> CAPTURE_CANONICAL_DIGEST_FIELD =
      field(name("canonical_sha256"), SQLDataType.VARCHAR);
  private static final Field<byte[]> CAPTURE_CANONICAL_BYTES_FIELD =
      field(name("canonical_capture_bytes"), SQLDataType.VARBINARY);
  private static final Field<OffsetDateTime> CAPTURE_CREATED_AT_FIELD =
      field(name("created_at"), SQLDataType.OFFSETDATETIME);
  private static final Field<UUID> SOURCE_PARTICIPATION_ID_FIELD =
      field(name("participation_id"), SQLDataType.UUID);
  private static final Field<String> SOURCE_KEY_FIELD =
      field(name("source_key"), SQLDataType.VARCHAR);
  private static final Field<byte[]> SOURCE_EVIDENCE_FIELD =
      field(name("source_evidence"), SQLDataType.VARBINARY);
  private static final Field<UUID> SETTLEMENT_PARTICIPATION_ID_FIELD =
      field(name("participation_id"), SQLDataType.UUID);
  private static final Field<String> SETTLEMENT_OUTCOME_FIELD =
      field(name("outcome"), SQLDataType.VARCHAR);
  private static final Field<Long> SETTLEMENT_WORLD_FENCE_FIELD =
      field(name("world_execution_fence"), SQLDataType.BIGINT);
  private static final Field<byte[]> SETTLEMENT_TERMINAL_BYTES_FIELD =
      field(name("terminal_bytes"), SQLDataType.VARBINARY);
  private static final Field<String> SETTLEMENT_TERMINAL_DIGEST_FIELD =
      field(name("terminal_digest"), SQLDataType.VARCHAR);
  private static final Field<OffsetDateTime> SETTLEMENT_SETTLED_AT_FIELD =
      field(name("settled_at"), SQLDataType.OFFSETDATETIME);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void typedReadbackRetainsTheExactCandidateAndRejectsChangedImmutableFields() {
    Fixture fixture = fixture();
    StoredParticipation stored =
        AccountStartSessionWorldParticipationRepository.decodeParticipation(
            participationRow(fixture.candidate()), List.of(fixture.source()));

    AccountStartSessionWorldParticipationRepository.requireSameCandidate(
        fixture.candidate(), stored);
    assertThat(stored.participationId()).isEqualTo(PARTICIPATION_ID);
    assertThat(stored.participationFence()).isEqualTo(41L);
    assertThat(stored.producerXid()).isEqualTo(2_147_483_651L);
    assertThat(stored.originalPostAuthorizationTuple())
        .containsExactly(fixture.candidate().originalPostAuthorizationTuple());

    Candidate changedPreparation =
        new Candidate(
            fixture.candidate().originalTuple(),
            fixture.candidate().capture(),
            fixture.candidate().canonicalGameInstanceId(),
            fixture.candidate().gameSessionOwnerAttemptId(),
            fixture.candidate().gameSessionOwnerFence(),
            "{\"changed\":true}",
            digest("{\"changed\":true}"));
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationRepository.requireSameCandidate(
                    changedPreparation, stored))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
  }

  @Test
  void participationReadbackRejectsUnknownDatabaseColumns() {
    Fixture fixture = fixture();
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationRepository.decodeParticipation(
                    participationRow(fixture.candidate(), true), List.of(fixture.source())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  void sourceReadbackRejectsMissingAndSubstitutedChildren() {
    Fixture fixture = fixture();
    SourceEvidence substituted =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            ACTOR_ID.toString(),
            "1",
            "2",
            "account/test",
            "1",
            bytes("substituted evidence"));

    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationRepository.requireExactSources(
                    List.of(fixture.source()), List.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationRepository.requireExactSources(
                    List.of(fixture.source()), List.of(substituted)))
        .isInstanceOf(IllegalStateException.class);
    AccountStartSessionWorldParticipationRepository.requireExactSources(
        List.of(fixture.source()), List.of(fixture.source()));
  }

  @Test
  void terminalMustRepeatEveryStoredIdentityAndPreparationField() {
    Fixture fixture = fixture();
    Candidate candidate = fixture.candidate();
    StoredParticipation stored =
        AccountStartSessionWorldParticipationRepository.decodeParticipation(
            participationRow(candidate), List.of(fixture.source()));
    WorldStartSessionExecutionTerminal exact = terminal(candidate, 41L, OWNER_ATTEMPT_ID, 8L);

    AccountStartSessionWorldParticipationRepository.requireTerminalMatches(stored, exact);

    WorldStartSessionExecutionTerminal changedAttempt =
        terminal(candidate, 41L, UUID.fromString("274c7a54-2f2a-409f-9eac-7b8a7733e590"), 8L);
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationRepository.requireTerminalMatches(
                    stored, changedAttempt))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
  }

  @Test
  void persistenceDeniesAbsentReadOnlyAndWrongIsolationTransactionsBeforeSql() {
    AtomicInteger databaseCalls = new AtomicInteger();
    MockDataProvider provider =
        context -> {
          databaseCalls.incrementAndGet();
          return new MockResult[0];
        };
    var repository =
        new AccountStartSessionWorldParticipationRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
    Candidate candidate = fixture().candidate();

    assertThatThrownBy(() -> repository.createOrReadExact(candidate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_COMMITTED");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> repository.createOrReadExact(candidate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_COMMITTED");
    TransactionSynchronizationManager.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_SERIALIZABLE);
    assertThatThrownBy(() -> repository.createOrReadExact(candidate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_COMMITTED");
    assertThat(databaseCalls.get()).isZero();
  }

  @Test
  void findCurrentExactReadsRetainedCaptureParentAndEveryChildWithoutWriting() throws Exception {
    Fixture fixture = fixture();
    List<String> reads = new java.util.ArrayList<>();
    AtomicReference<Object[]> writes = new AtomicReference<>();
    AccountStartSessionWorldParticipationRepository repository =
        mockedRepository(fixture, reads, writes, null);
    beginWritableTransaction();

    var found = repository.findCurrentExact(fixture.candidate());

    assertThat(found).isPresent();
    assertThat(found.orElseThrow().participationId()).isEqualTo(PARTICIPATION_ID);
    assertThat(found.orElseThrow().sources()).hasSize(1);
    assertThat(found.orElseThrow().sources().get(0).canonicalBytes())
        .containsExactly(fixture.source().canonicalBytes());
    assertThat(reads)
        .anyMatch(sql -> sql.contains("account_start_session_authority_captures"))
        .anyMatch(sql -> sql.contains("account_start_session_world_participations"))
        .anyMatch(sql -> sql.contains("account_start_session_world_participation_sources"))
        .anyMatch(sql -> sql.contains("account_start_session_world_participation_settlements"));
    assertThat(reads)
        .allMatch(sql -> !sql.toLowerCase(java.util.Locale.ROOT).contains("for update"));
    assertThat(writes.get()).isNull();
  }

  @Test
  void findHistoricalForOriginalTupleResolvesTheRetainedIdentityAndVerifiesItsSources()
      throws Exception {
    Fixture fixture = fixture();
    List<String> reads = new java.util.ArrayList<>();
    AtomicReference<Object[]> writes = new AtomicReference<>();
    AccountStartSessionWorldParticipationRepository repository =
        mockedRepository(fixture, reads, writes, null);
    beginWritableTransaction();

    var found =
        repository.findHistoricalForOriginalTuple(
            fixture.candidate().originalPostAuthorizationTuple());

    assertThat(found).isPresent();
    assertThat(found.orElseThrow().participationId()).isEqualTo(PARTICIPATION_ID);
    assertThat(found.orElseThrow().participationFence()).isEqualTo(41L);
    assertThat(found.orElseThrow().originalPostAuthorizationTuple())
        .containsExactly(fixture.candidate().originalPostAuthorizationTuple());
    assertThat(found.orElseThrow().sources()).hasSize(1);
    assertThat(found.orElseThrow().sources().get(0).canonicalBytes())
        .containsExactly(fixture.source().canonicalBytes());
    assertThat(reads)
        .anyMatch(sql -> sql.contains("WHERE control_plane_request_id = ?"))
        .anyMatch(sql -> sql.contains("account_start_session_authority_captures"))
        .anyMatch(sql -> sql.contains("account_start_session_world_participation_sources"));
    assertThat(reads)
        .allMatch(sql -> !sql.toLowerCase(java.util.Locale.ROOT).contains("for update"));
    assertThat(writes.get()).isNull();
  }

  @Test
  void findHistoricalForOriginalTupleRejectsSameRequestIdWithChangedCompleteTuple()
      throws Exception {
    Fixture fixture = fixture();
    StartSessionPostAuthorizationExecutionTuple retained = fixture.candidate().originalTuple();
    byte[] changedTuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
                retained.preAuthorizationTuple(),
                retained.authenticatedWorkloadIdentity(),
                retained.authorizationReferenceFingerprint(),
                retained.reservationOwnerId(),
                retained.reservationClaimFence() + 1L,
                retained.authorityEvidenceBundleBytes(),
                retained.bundleReference())
            .canonicalBytes();
    List<String> reads = new java.util.ArrayList<>();
    AtomicReference<Object[]> writes = new AtomicReference<>();
    AccountStartSessionWorldParticipationRepository repository =
        mockedRepository(fixture, reads, writes, null);
    beginWritableTransaction();

    assertThatThrownBy(() -> repository.findHistoricalForOriginalTuple(changedTuple))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");

    assertThat(reads).hasSize(1);
    assertThat(reads.get(0)).contains("WHERE control_plane_request_id = ?");
    assertThat(writes.get()).isNull();
  }

  @Test
  void findHistoricalForOriginalTupleReturnsEmptyWhenRequestIdentityHasNoRetainedRow()
      throws Exception {
    Fixture fixture = fixture();
    List<String> reads = new java.util.ArrayList<>();
    AtomicReference<Object[]> writes = new AtomicReference<>();
    AccountStartSessionWorldParticipationRepository repository =
        mockedRepository(fixture, reads, writes, null, true);
    beginWritableTransaction();

    var found =
        repository.findHistoricalForOriginalTuple(
            fixture.candidate().originalPostAuthorizationTuple());

    assertThat(found).isEmpty();
    assertThat(reads).hasSize(1);
    assertThat(reads.get(0)).contains("WHERE control_plane_request_id = ?");
    assertThat(writes.get()).isNull();
  }

  @Test
  void settleExactInsertsAndReadsBackTheSameTypedTerminalAfterHistoricalIdentityCheck()
      throws Exception {
    Fixture fixture = fixture();
    WorldStartSessionExecutionTerminal terminal =
        terminal(fixture.candidate(), 41L, OWNER_ATTEMPT_ID, 8L);
    List<String> reads = new java.util.ArrayList<>();
    AtomicReference<Object[]> writes = new AtomicReference<>();
    AccountStartSessionWorldParticipationRepository repository =
        mockedRepository(fixture, reads, writes, terminal);
    beginWritableTransaction();

    var stored = repository.settleExact(terminal);

    assertThat(stored.participationId()).isEqualTo(PARTICIPATION_ID);
    assertThat(stored.outcome()).isEqualTo(terminal.outcome());
    assertThat(stored.worldExecutionFence()).isEqualTo(terminal.worldExecutionFence());
    assertThat(stored.terminalBytes()).containsExactly(terminal.canonicalBytes());
    assertThat(stored.terminalDigest()).isEqualTo(terminal.digest());
    assertThat(reads)
        .anyMatch(sql -> sql.contains("account_start_session_authority_captures"))
        .anyMatch(sql -> sql.contains("account_start_session_world_participations"))
        .anyMatch(sql -> sql.contains("account_start_session_world_participation_sources"))
        .anyMatch(
            sql ->
                sql.startsWith("INSERT INTO account_start_session_world_participation_settlements"))
        .anyMatch(sql -> sql.contains("account_start_session_world_participation_settlements"));
    assertThat(writes.get()).isNotNull().hasSize(5);
    assertThat(writes.get()[0]).isEqualTo(PARTICIPATION_ID);
    assertThat(writes.get()[1]).isEqualTo(terminal.outcome().name());
    assertThat(writes.get()[2]).isEqualTo(terminal.worldExecutionFence());
    assertThat((byte[]) writes.get()[3]).containsExactly(terminal.canonicalBytes());
    assertThat(writes.get()[4]).isEqualTo(terminal.digest());
  }

  @Test
  void settleExactRejectsSubstitutedOriginalAttemptBeforeSettlementInsert() throws Exception {
    Fixture fixture = fixture();
    WorldStartSessionExecutionTerminal changedAttempt =
        terminal(
            fixture.candidate(), 41L, UUID.fromString("274c7a54-2f2a-409f-9eac-7b8a7733e590"), 8L);
    List<String> reads = new java.util.ArrayList<>();
    AtomicReference<Object[]> writes = new AtomicReference<>();
    AccountStartSessionWorldParticipationRepository repository =
        mockedRepository(fixture, reads, writes, null);
    beginWritableTransaction();

    assertThatThrownBy(() -> repository.settleExact(changedAttempt))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
    assertThat(writes.get()).isNull();
  }

  @Test
  void findSettlementExactReadsBackOnlyTheExactHistoricalReceiptWithoutWriting() throws Exception {
    Fixture fixture = fixture();
    WorldStartSessionExecutionTerminal terminal =
        terminal(fixture.candidate(), 41L, OWNER_ATTEMPT_ID, 8L);
    List<String> sqlCalls = new java.util.ArrayList<>();
    AtomicReference<Object[]> writes = new AtomicReference<>();
    AccountStartSessionWorldParticipationRepository repository =
        mockedRepository(fixture, sqlCalls, writes, terminal);
    beginWritableTransaction();

    var found = repository.findSettlementExact(PARTICIPATION_ID, 41L);

    assertThat(found).isPresent();
    assertThat(found.orElseThrow().terminalBytes()).containsExactly(terminal.canonicalBytes());
    assertThat(found.orElseThrow().terminalDigest()).isEqualTo(terminal.digest());
    assertThat(sqlCalls)
        .anyMatch(sql -> sql.contains("account_start_session_authority_captures"))
        .anyMatch(sql -> sql.contains("account_start_session_world_participations"))
        .anyMatch(sql -> sql.contains("account_start_session_world_participation_sources"))
        .anyMatch(sql -> sql.contains("account_start_session_world_participation_settlements"));
    assertThat(sqlCalls)
        .allMatch(sql -> !sql.toLowerCase(java.util.Locale.ROOT).contains("for update"));
    assertThat(writes.get()).isNull();
  }

  @Test
  void findSettlementExactReturnsEmptyForMissingReceiptOrParticipationWithoutWriting()
      throws Exception {
    Fixture fixture = fixture();
    List<String> missingReceiptCalls = new java.util.ArrayList<>();
    AtomicReference<Object[]> missingReceiptWrites = new AtomicReference<>();
    AccountStartSessionWorldParticipationRepository missingReceiptRepository =
        mockedRepository(fixture, missingReceiptCalls, missingReceiptWrites, null);
    beginWritableTransaction();

    assertThat(missingReceiptRepository.findSettlementExact(PARTICIPATION_ID, 41L)).isEmpty();
    assertThat(missingReceiptWrites.get()).isNull();

    TransactionSynchronizationManager.clear();
    List<String> missingParentCalls = new java.util.ArrayList<>();
    AtomicReference<Object[]> missingParentWrites = new AtomicReference<>();
    AccountStartSessionWorldParticipationRepository missingParentRepository =
        mockedRepository(fixture, missingParentCalls, missingParentWrites, null, true);
    beginWritableTransaction();

    assertThat(missingParentRepository.findSettlementExact(PARTICIPATION_ID, 41L)).isEmpty();
    assertThat(missingParentWrites.get()).isNull();
    assertThat(missingParentCalls)
        .noneMatch(sql -> sql.contains("account_start_session_world_participation_settlements"));
  }

  @Test
  void findSettlementExactRejectsReceiptWithSubstitutedOriginalAttemptWithoutWriting()
      throws Exception {
    Fixture fixture = fixture();
    WorldStartSessionExecutionTerminal changedAttempt =
        terminal(
            fixture.candidate(), 41L, UUID.fromString("274c7a54-2f2a-409f-9eac-7b8a7733e590"), 8L);
    List<String> sqlCalls = new java.util.ArrayList<>();
    AtomicReference<Object[]> writes = new AtomicReference<>();
    AccountStartSessionWorldParticipationRepository repository =
        mockedRepository(fixture, sqlCalls, writes, changedAttempt);
    beginWritableTransaction();

    assertThatThrownBy(() -> repository.findSettlementExact(PARTICIPATION_ID, 41L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
    assertThat(writes.get()).isNull();
  }

  private static AccountStartSessionWorldParticipationRepository mockedRepository(
      Fixture fixture,
      List<String> sqlCalls,
      AtomicReference<Object[]> writes,
      WorldStartSessionExecutionTerminal settlement)
      throws Exception {
    return mockedRepository(fixture, sqlCalls, writes, settlement, false);
  }

  private static AccountStartSessionWorldParticipationRepository mockedRepository(
      Fixture fixture,
      List<String> sqlCalls,
      AtomicReference<Object[]> writes,
      WorldStartSessionExecutionTerminal settlement,
      boolean missingParticipation)
      throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.isReadOnly()).thenReturn(false);
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    doAnswer(
            invocation -> {
              invocation.<ConnectionRunnable>getArgument(0).run(connection);
              return null;
            })
        .when(dsl)
        .connection(any(ConnectionRunnable.class));
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              sqlCalls.add(sql);
              if (sql.contains("account_start_session_authority_captures")) {
                return captureRow(fixture.candidate());
              }
              if (sql.contains("account_start_session_world_participations")) {
                return missingParticipation ? null : participationRow(fixture.candidate());
              }
              if (sql.contains("account_start_session_world_participation_settlements")) {
                return settlement == null ? null : settlementRow(settlement);
              }
              throw new AssertionError("Unexpected participation repository query: " + sql);
            });
    when(dsl.fetch(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              sqlCalls.add(sql);
              if (!sql.contains("account_start_session_world_participation_sources")) {
                throw new AssertionError(
                    "Unexpected participation repository result query: " + sql);
              }
              return sourceRows(PARTICIPATION_ID, fixture.source());
            });
    when(dsl.execute(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              sqlCalls.add(sql);
              writes.set((Object[]) invocation.getRawArguments()[1]);
              return 1;
            });
    return new AccountStartSessionWorldParticipationRepository(dsl);
  }

  private static void beginWritableTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private static Record participationRow(Candidate candidate) {
    return participationRow(candidate, false);
  }

  private static Record participationRow(Candidate candidate, boolean withUnknownColumn) {
    Record row;
    if (withUnknownColumn) {
      row =
          DSL.using(SQLDialect.POSTGRES)
              .newRecord(
                  PARTICIPATION_ID_FIELD,
                  PARTICIPATION_FENCE_FIELD,
                  REQUEST_ID_FIELD,
                  ORIGINAL_TUPLE_FIELD,
                  NAMESPACE_FIELD,
                  TENANT_FIELD,
                  GAME_INSTANCE_FIELD,
                  OWNER_ATTEMPT_FIELD,
                  OWNER_FENCE_FIELD,
                  PREPARATION_JSON_FIELD,
                  PREPARATION_DIGEST_FIELD,
                  PRODUCER_XID_FIELD,
                  CREATED_AT_FIELD,
                  UNKNOWN_FIELD);
      row.setValue(UNKNOWN_FIELD, "unreviewed");
    } else {
      row =
          DSL.using(SQLDialect.POSTGRES)
              .newRecord(
                  PARTICIPATION_ID_FIELD,
                  PARTICIPATION_FENCE_FIELD,
                  REQUEST_ID_FIELD,
                  ORIGINAL_TUPLE_FIELD,
                  NAMESPACE_FIELD,
                  TENANT_FIELD,
                  GAME_INSTANCE_FIELD,
                  OWNER_ATTEMPT_FIELD,
                  OWNER_FENCE_FIELD,
                  PREPARATION_JSON_FIELD,
                  PREPARATION_DIGEST_FIELD,
                  PRODUCER_XID_FIELD,
                  CREATED_AT_FIELD);
    }
    row.setValue(PARTICIPATION_ID_FIELD, PARTICIPATION_ID);
    row.setValue(PARTICIPATION_FENCE_FIELD, 41L);
    row.setValue(REQUEST_ID_FIELD, candidate.controlPlaneRequestId());
    row.setValue(ORIGINAL_TUPLE_FIELD, candidate.originalPostAuthorizationTuple());
    row.setValue(NAMESPACE_FIELD, candidate.targetNamespace());
    row.setValue(TENANT_FIELD, candidate.canonicalTenantId());
    row.setValue(GAME_INSTANCE_FIELD, candidate.canonicalGameInstanceId());
    row.setValue(OWNER_ATTEMPT_FIELD, candidate.gameSessionOwnerAttemptId());
    row.setValue(OWNER_FENCE_FIELD, candidate.gameSessionOwnerFence());
    row.setValue(PREPARATION_JSON_FIELD, candidate.preparationInputJson());
    row.setValue(PREPARATION_DIGEST_FIELD, candidate.preparationInputDigest());
    row.setValue(PRODUCER_XID_FIELD, 2_147_483_651L);
    row.setValue(CREATED_AT_FIELD, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return row;
  }

  private static Record captureRow(Candidate candidate) {
    var tuple = candidate.originalTuple();
    var capture = candidate.capture();
    Record row =
        DSL.using(SQLDialect.POSTGRES)
            .newRecord(
                CAPTURE_REQUEST_ID_FIELD,
                CAPTURE_ACCOUNT_FIELD,
                CAPTURE_TENANT_FIELD,
                CAPTURE_TARGET_OWNER_FIELD,
                CAPTURE_LOGGING_URI_FIELD,
                CAPTURE_RESERVATION_OWNER_FIELD,
                CAPTURE_RESERVATION_FENCE_FIELD,
                CAPTURE_PRE_TUPLE_FIELD,
                CAPTURE_MUTATION_DIGEST_FIELD,
                CAPTURE_OPERATION_ID_FIELD,
                CAPTURE_TOKEN_JTI_FIELD,
                CAPTURE_TOKEN_HASH_FIELD,
                CAPTURE_RECEIPT_DIGEST_FIELD,
                CAPTURE_ISSUANCE_FENCE_FIELD,
                CAPTURE_ISSUANCE_SOURCE_VERSION_FIELD,
                CAPTURED_AT_FIELD,
                CAPTURE_SNAPSHOT_DIGEST_FIELD,
                CAPTURE_SNAPSHOT_BYTES_FIELD,
                CAPTURE_SOURCE_VERSION_FIELD,
                CAPTURE_SOURCE_FENCE_FIELD,
                CAPTURE_LINEARIZATION_FIELD,
                CAPTURE_CANONICAL_DIGEST_FIELD,
                CAPTURE_CANONICAL_BYTES_FIELD,
                CAPTURE_CREATED_AT_FIELD);
    row.setValue(CAPTURE_REQUEST_ID_FIELD, candidate.controlPlaneRequestId());
    row.setValue(CAPTURE_ACCOUNT_FIELD, tuple.preAuthorizationTuple().actor().accountId());
    row.setValue(CAPTURE_TENANT_FIELD, candidate.canonicalTenantId());
    row.setValue(CAPTURE_TARGET_OWNER_FIELD, "game-session-service");
    row.setValue(CAPTURE_LOGGING_URI_FIELD, tuple.authenticatedWorkloadIdentity());
    row.setValue(CAPTURE_RESERVATION_OWNER_FIELD, tuple.reservationOwnerId());
    row.setValue(CAPTURE_RESERVATION_FENCE_FIELD, tuple.reservationClaimFence());
    row.setValue(
        CAPTURE_PRE_TUPLE_FIELD,
        tuple.preAuthorizationTuple().canonicalJson().getBytes(StandardCharsets.UTF_8));
    row.setValue(CAPTURE_MUTATION_DIGEST_FIELD, tuple.mutationDigest());
    row.setValue(
        CAPTURE_OPERATION_ID_FIELD, UUID.fromString("44444444-4444-4444-8444-444444444444"));
    row.setValue(CAPTURE_TOKEN_JTI_FIELD, UUID.fromString("55555555-5555-4555-8555-555555555555"));
    row.setValue(CAPTURE_TOKEN_HASH_FIELD, "b".repeat(64));
    row.setValue(CAPTURE_RECEIPT_DIGEST_FIELD, sha256Hex(bytes("receipt")));
    row.setValue(CAPTURE_ISSUANCE_FENCE_FIELD, 9L);
    row.setValue(CAPTURE_ISSUANCE_SOURCE_VERSION_FIELD, 10L);
    OffsetDateTime capturedAt = OffsetDateTime.parse(capture.capturedAt().replace("Z", "+00:00"));
    row.setValue(CAPTURED_AT_FIELD, capturedAt);
    row.setValue(CAPTURE_SNAPSHOT_DIGEST_FIELD, capture.snapshotSha256());
    row.setValue(CAPTURE_SNAPSHOT_BYTES_FIELD, capture.snapshotBytes());
    row.setValue(CAPTURE_SOURCE_VERSION_FIELD, capture.sourceVersion());
    row.setValue(CAPTURE_SOURCE_FENCE_FIELD, capture.sourceFence());
    row.setValue(CAPTURE_LINEARIZATION_FIELD, capture.linearization());
    row.setValue(CAPTURE_CANONICAL_DIGEST_FIELD, capture.canonicalSha256());
    row.setValue(CAPTURE_CANONICAL_BYTES_FIELD, capture.canonicalBytes());
    row.setValue(CAPTURE_CREATED_AT_FIELD, capturedAt);
    return row;
  }

  private static org.jooq.Result<org.jooq.Record3<UUID, String, byte[]>> sourceRows(
      UUID participationId, SourceEvidence source) {
    var context = DSL.using(SQLDialect.POSTGRES);
    var result =
        context.newResult(SOURCE_PARTICIPATION_ID_FIELD, SOURCE_KEY_FIELD, SOURCE_EVIDENCE_FIELD);
    var row =
        context.newRecord(SOURCE_PARTICIPATION_ID_FIELD, SOURCE_KEY_FIELD, SOURCE_EVIDENCE_FIELD);
    row.setValue(SOURCE_PARTICIPATION_ID_FIELD, participationId);
    row.setValue(SOURCE_KEY_FIELD, source.key());
    row.setValue(SOURCE_EVIDENCE_FIELD, source.canonicalBytes());
    result.add(row);
    return result;
  }

  private static Record settlementRow(WorldStartSessionExecutionTerminal terminal) {
    Record row =
        DSL.using(SQLDialect.POSTGRES)
            .newRecord(
                SETTLEMENT_PARTICIPATION_ID_FIELD,
                SETTLEMENT_OUTCOME_FIELD,
                SETTLEMENT_WORLD_FENCE_FIELD,
                SETTLEMENT_TERMINAL_BYTES_FIELD,
                SETTLEMENT_TERMINAL_DIGEST_FIELD,
                SETTLEMENT_SETTLED_AT_FIELD);
    row.setValue(SETTLEMENT_PARTICIPATION_ID_FIELD, terminal.accountWorldParticipationId());
    row.setValue(SETTLEMENT_OUTCOME_FIELD, terminal.outcome().name());
    row.setValue(SETTLEMENT_WORLD_FENCE_FIELD, terminal.worldExecutionFence());
    row.setValue(SETTLEMENT_TERMINAL_BYTES_FIELD, terminal.canonicalBytes());
    row.setValue(SETTLEMENT_TERMINAL_DIGEST_FIELD, terminal.digest());
    row.setValue(SETTLEMENT_SETTLED_AT_FIELD, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return row;
  }

  private static WorldStartSessionExecutionTerminal terminal(
      Candidate candidate, long participationFence, UUID attemptId, long ownerFence) {
    return new WorldStartSessionExecutionTerminal(
        candidate.originalPostAuthorizationTuple(),
        PARTICIPATION_ID,
        participationFence,
        attemptId,
        ownerFence,
        candidate.targetNamespace(),
        candidate.canonicalTenantId(),
        candidate.controlPlaneRequestId(),
        candidate.canonicalGameInstanceId(),
        candidate.preparationInputDigest(),
        candidate.preparationInputJson(),
        19L,
        WorldStartSessionExecutionTerminal.Outcome.COMMITTED);
  }

  private static Fixture fixture() {
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            ACTOR_ID.toString(),
            "1",
            "1",
            "account/test",
            "1",
            bytes("retained source evidence"));
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            REQUEST_ID,
            ACTOR_ID,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
                new StartSessionOperatorAction.Target(17L, TARGET_ACCOUNT_ID),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "World participation repository proof"));
    byte[] bundle = authorityBundle(preTuple);
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            LOGGING_URI,
            "arfp/v1/key-1/" + "a".repeat(64),
            RESERVATION_OWNER_ID,
            7L,
            bundle,
            BUNDLE_REFERENCE);
    byte[] snapshot =
        AccountControlUiAuthority.canonical(
            Map.ofEntries(
                Map.entry("schema", "account-start-session-authority-snapshot/v1"),
                Map.entry("controlPlaneRequestId", REQUEST_ID),
                Map.entry(
                    "preAuthorizationTuple",
                    Base64.getEncoder()
                        .encodeToString(preTuple.canonicalJson().getBytes(StandardCharsets.UTF_8))),
                Map.entry("mutationDigest", preTuple.mutationDigest()),
                Map.entry("accountId", ACTOR_ID.toString()),
                Map.entry("tenantId", TENANT_ID.toString()),
                Map.entry("targetOwner", "game-session-service"),
                Map.entry("loggingWorkloadUri", LOGGING_URI),
                Map.entry("reservationOwnerId", RESERVATION_OWNER_ID.toString()),
                Map.entry("reservationClaimFence", "7"),
                Map.entry("controlUiOperationId", "44444444-4444-4444-8444-444444444444"),
                Map.entry("controlUiTokenJti", "55555555-5555-4555-8555-555555555555"),
                Map.entry("controlUiTokenHash", "b".repeat(64)),
                Map.entry(
                    "controlUiSignerReceipt", Base64.getEncoder().encodeToString(bytes("receipt"))),
                Map.entry("controlUiSignerReceiptSha256", sha256Hex(bytes("receipt"))),
                Map.entry(
                    "sourceVectorEvidence",
                    Base64.getEncoder().encodeToString(source.canonicalBytes())),
                Map.entry(
                    "sourceVector",
                    List.of(Base64.getEncoder().encodeToString(source.canonicalBytes()))),
                Map.entry("outboxCheckpoints", List.of()),
                Map.entry(
                    "authorityTuple",
                    Map.of(
                        "issuerAuthGeneration", 1L,
                        "accountAuthorityGeneration", 1L,
                        "tenantAuthorityGeneration", Map.of(TENANT_ID.toString(), 1L),
                        "membershipAuthorityGeneration", Map.of(TENANT_ID.toString(), 1L),
                        "privateRealmGrantVersions", List.of())),
                Map.entry("membershipVersion", Map.of(TENANT_ID.toString(), 2L)),
                Map.entry("accountIdentitySource", Map.of("source", "fixture")),
                Map.entry("issuanceFence", "9"),
                Map.entry("issuanceFenceSourceVersion", "10")));
    var capture = capture(REQUEST_ID, 11L, 13L, "17", "2026-10-09T00:00:00.000Z", snapshot);
    String preparationJson = "{\"identity\":{\"worldSlug\":\"arena\"}}";
    Candidate candidate =
        new Candidate(
            tuple,
            capture,
            GAME_INSTANCE_ID,
            OWNER_ATTEMPT_ID,
            8L,
            preparationJson,
            digest(preparationJson));
    return new Fixture(candidate, source);
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenant = tuple.action().scope().tenantId().toString();
    String actor = tuple.actor().accountId().toString();
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 1L,
            "tenantAuthorityGeneration", Map.of(tenant, 1L),
            "membershipAuthorityGeneration", Map.of(tenant, 1L),
            "privateRealmGrantVersions", List.of());
    return AccountControlUiAuthority.canonical(
        Map.of(
            "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
                Map.of(
                    "scope",
                    Map.of("tenantId", tenant, "targetNamespace", "world-runtime"),
                    "actionFamily",
                    "StartSession",
                    "applicableAccountId",
                    actor,
                    "applicableTenantId",
                    tenant),
            "accountProjectionEvidence",
                Map.of(
                    "sourceType",
                    "ACCOUNT",
                    "sourceEvidenceId",
                    "sha256:" + "c".repeat(64),
                    "sourceEvidenceVersion",
                    BUNDLE_REFERENCE.sourceVersion(),
                    "projectionStatus",
                    "CURRENT",
                    "evaluatedAt",
                    NOW.toString(),
                    "expiresAt",
                    NOW.plusSeconds(180).toString()),
            "issuanceOperationIdentity",
                Map.of(
                    "issuanceOperationId",
                    "44444444-4444-4444-8444-444444444444",
                    "controlPlaneRequestId",
                    REQUEST_ID,
                    "actionFamilyRequestIdentity",
                    Map.of("requestIdentityKind", "controlPlaneRequestId", "requestId", REQUEST_ID),
                    "mutationDigest",
                    tuple.mutationDigest()),
            "issuanceKind", "human_operator",
            "authorityTuple", authorityTuple,
            "membershipVersion", Map.of(tenant, 2L),
            "issuanceFence", "9",
            "issuanceEvidence",
                Map.of(
                    "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                    "actorAccountId", actor,
                    "controlUiTokenJti", "55555555-5555-4555-8555-555555555555",
                    "role", "tenantAdmin",
                    "accountGeneration", "1",
                    "tenantGeneration", "1")));
  }

  private static AccountStartSessionAuthorityCapture capture(
      String requestId,
      long sourceVersion,
      long sourceFence,
      String linearization,
      String capturedAt,
      byte[] snapshot) {
    try {
      Method factory =
          net.firedevops.firemud.accountservice.service.session.AccountStartSessionAuthorityCapture
              .class
              .getDeclaredMethod(
                  "create",
                  String.class,
                  long.class,
                  long.class,
                  String.class,
                  String.class,
                  byte[].class);
      factory.setAccessible(true);
      return (net.firedevops.firemud.accountservice.service.session
              .AccountStartSessionAuthorityCapture)
          factory.invoke(
              null, requestId, sourceVersion, sourceFence, linearization, capturedAt, snapshot);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new AssertionError("Unexpected capture fixture failure", failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not create exact capture fixture", failure);
    }
  }

  private static String digest(String value) {
    return "sha256:" + sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private record Fixture(Candidate candidate, SourceEvidence source) {}
}
