package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult.Disposition;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult.ProofKind;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository.JournalConflictException;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository.JournalUnavailableException;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Native PostgreSQL proof of the inactive, non-admitting LOGIN journal only. Controlled RSA result
 * fixtures exercise the private validated-result intake, not authenticated ingress or Account RPCs.
 * No live-context closure, Redis denial, restore qualification or runtime composition is proved.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class InitialGameplayLoginCoordinationJournalRepositoryIntegrationTest {
  private static final String TABLE = "game_session_initial_gameplay_login_journal";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);
  private static final long NOW = CLOCK.instant().getEpochSecond();
  private static final UUID ACCOUNT = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static KeyPair keys;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DriverManagerDataSource dataSource;
  private DSLContext dsl;
  private TransactionTemplate transactions;
  private InitialGameplayLoginCoordinationJournalRepository journal;

  @BeforeAll
  static void controlledSigningKey() throws Exception {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    keys = generator.generateKeyPair();
  }

  @BeforeEach
  void migratedIsolatedSchema() {
    schema = "gs_login_journal_" + UUID.randomUUID().toString().replace("-", "");
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();
    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(15);
    journal = new InitialGameplayLoginCoordinationJournalRepository(dsl);
  }

  @AfterEach
  void disposeOnlyOwnedSchema() throws Exception {
    if (schema != null) {
      try (var connection =
              DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      }
    }
  }

  @Test
  void committedReservationIsExactReplayableAndSurvivesOwnerReplacementWithoutCredentials() {
    var original = coordinates();
    transactions.executeWithoutResult(
        status -> {
          assertThat(journal.reserve(original).coordinates()).isEqualTo(original);
          assertThatThrownBy(() -> journal.readCommitted(original))
              .isInstanceOf(JournalUnavailableException.class);
        });
    var committed = journal.readCommitted(original).orElseThrow();
    assertThat(committed.abandoned()).isFalse();
    assertThat(committed.verifiedWitness()).isEmpty();
    assertThat(committed.frozenRetirementRequest()).isEmpty();
    assertThat(committed.retainedReceipt()).isEmpty();
    transactions.executeWithoutResult(
        status -> assertThat(journal.reserve(original)).isEqualTo(committed));
    // Simulated process loss after COMMIT/before dispatch: only nonsecret coordinates survive.
    var recovered = new InitialGameplayLoginCoordinationJournalRepository(dsl);
    assertThat(recovered.readRetainedPage(Optional.empty(), 1)).containsExactly(committed);
    transactions.executeWithoutResult(status -> recovered.abandon(original));
    transactions.executeWithoutResult(status -> recovered.freezeRetirementRequest(original));
    var cleanup = recovered.readCommitted(original).orElseThrow();
    assertThat(cleanup.abandoned()).isTrue();
    assertThat(cleanup.frozenRetirementRequest().orElseThrow().hasTokenWitness()).isFalse();
    assertThat(cleanup.frozenRetirementRequest().orElseThrow().getRequestId())
        .isEqualTo(original.requestId().toString());
    // A never-dispatched reservation still cannot manufacture Account nonissuance proof.
    assertThat(cleanup.retainedReceipt()).isEmpty();
    assertThat(
            dsl.fetch(
                    "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = ?",
                    schema,
                    TABLE)
                .getValues("column_name", String.class))
        .containsExactlyInAnyOrder(
            "request_id",
            "caller_context_id",
            "expected_attempt_fence",
            "abandoned",
            "verified_token_jti",
            "verified_token_sha256",
            "verified_token_generation",
            "verified_issuance_fence",
            "retirement_request_bytes",
            "disposal_operation_id",
            "disposal_disposition",
            "disposal_proof_kind",
            "created_at",
            "abandoned_at");
  }

  @Test
  void rolledBackReservationIsNotCommittedDispatchEvidence() {
    var original = coordinates();
    transactions.executeWithoutResult(
        status -> {
          journal.reserve(original);
          status.setRollbackOnly();
        });
    assertThat(journal.readCommitted(original)).isEmpty();
    assertThat(journal.readRetainedPage(Optional.empty(), 128)).isEmpty();
    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> journal.abandon(original)))
        .isInstanceOf(JournalUnavailableException.class);
  }

  @Test
  void requestIdentityAndContextFenceCannotBeRebound() {
    var original = coordinates();
    reserve(original);
    var wrongContext =
        new InitialGameplayLoginAttemptCoordinates(original.requestId(), UUID.randomUUID(), 1);
    var wrongFence =
        new InitialGameplayLoginAttemptCoordinates(
            original.requestId(), original.callerContextId(), 2);
    var reusedContextFence =
        new InitialGameplayLoginAttemptCoordinates(
            UUID.randomUUID(), original.callerContextId(), 1);
    for (var conflict : List.of(wrongContext, wrongFence, reusedContextFence)) {
      assertThatThrownBy(
              () -> transactions.executeWithoutResult(status -> journal.reserve(conflict)))
          .isInstanceOf(JournalConflictException.class);
    }
    assertThatThrownBy(() -> journal.readCommitted(wrongContext))
        .isInstanceOf(JournalConflictException.class);
    assertThatThrownBy(() -> journal.readCommitted(wrongFence))
        .isInstanceOf(JournalConflictException.class);
    assertThat(journal.readRetainedPage(Optional.empty(), 128)).hasSize(1);
  }

  @Test
  void frozenWitnessAbsenceSurvivesLateValidatedResultAndReceiptReplay() throws Exception {
    var original = coordinates();
    reserve(original);
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> journal.freezeRetirementRequest(original)))
        .isInstanceOf(JournalConflictException.class);
    transactions.executeWithoutResult(status -> journal.abandon(original));
    transactions.executeWithoutResult(status -> journal.freezeRetirementRequest(original));
    byte[] frozen = frozenBytes(original);
    var result = validatedResult(original, UUID.randomUUID());
    transactions.executeWithoutResult(status -> journal.recordVerifiedResult(original, result));
    transactions.executeWithoutResult(status -> journal.freezeRetirementRequest(original));
    var retained = journal.readCommitted(original).orElseThrow();
    assertThat(retained.verifiedWitness()).isPresent();
    assertThat(retained.frozenRetirementRequest().orElseThrow().hasTokenWitness()).isFalse();
    assertThat(retained.frozenRetirementRequest().orElseThrow().toByteArray())
        .containsExactly(frozen);
    assertThat(frozenBytes(original)).containsExactly(frozen);
    transactions.executeWithoutResult(status -> journal.recordVerifiedResult(original, result));
    var changedResult = validatedResult(original, UUID.randomUUID());
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> journal.recordVerifiedResult(original, changedResult)))
        .isInstanceOf(JournalConflictException.class);
    var otherOriginalResult = validatedResult(coordinates(), UUID.randomUUID());
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> journal.recordVerifiedResult(original, otherOriginalResult)))
        .isInstanceOf(JournalConflictException.class);
    UUID operation = UUID.randomUUID();
    var pending = receipt(original, operation, Disposition.PENDING);
    var terminal = receipt(original, operation, Disposition.RETIRED);
    transactions.executeWithoutResult(status -> journal.recordDisposalReceipt(original, pending));
    assertThat(journal.readCommitted(original).orElseThrow().retainedReceipt()).contains(pending);
    transactions.executeWithoutResult(status -> journal.recordDisposalReceipt(original, terminal));
    transactions.executeWithoutResult(status -> journal.recordDisposalReceipt(original, terminal));
    for (var conflict :
        List.of(
            pending,
            receipt(original, operation, Disposition.NO_TOKEN),
            receipt(original, UUID.randomUUID(), Disposition.RETIRED))) {
      assertThatThrownBy(
              () ->
                  transactions.executeWithoutResult(
                      status -> journal.recordDisposalReceipt(original, conflict)))
          .isInstanceOf(JournalConflictException.class);
    }
    assertThat(journal.readCommitted(original).orElseThrow().retainedReceipt()).contains(terminal);
    assertThat(frozenBytes(original)).containsExactly(frozen);
    // This synthetic receipt is historical journal evidence, not present physical denied-marker
    // proof.
  }

  @Test
  void witnessPresentBeforeFreezeIsPreservedAsExactProtobufBytes() throws Exception {
    var original = coordinates();
    reserve(original);
    var result = validatedResult(original, UUID.randomUUID());
    transactions.executeWithoutResult(status -> journal.recordVerifiedResult(original, result));
    transactions.executeWithoutResult(status -> journal.abandon(original));
    transactions.executeWithoutResult(status -> journal.freezeRetirementRequest(original));
    var retained = journal.readCommitted(original).orElseThrow();
    var request = retained.frozenRetirementRequest().orElseThrow();
    assertThat(request.hasTokenWitness()).isTrue();
    assertThat(request.getTokenWitness()).isEqualTo(retained.verifiedWitness().orElseThrow());
    assertThat(request.getTokenWitness().getTokenJti())
        .isEqualTo(result.evidence().tokenJti().toString());
    assertThat(request.getTokenWitness().getTokenSha256())
        .isEqualTo(result.evidence().tokenSha256());
    assertThat(request.getCallerContextId()).isEqualTo(original.callerContextId().toString());
    assertThat(frozenBytes(original)).containsExactly(request.toByteArray());
  }

  @Test
  void independentTransactionsSerializeFreezeAgainstLateWitness() throws Exception {
    var original = coordinates();
    reserve(original);
    var result = validatedResult(original, UUID.randomUUID());
    var frozenInsideTransaction = new CountDownLatch(1);
    var releaseCommit = new CountDownLatch(1);
    var lateStarted = new CountDownLatch(1);
    var lateBackend = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var freezer =
          executor.submit(
              () ->
                  transactions.execute(
                      status -> {
                        journal.abandon(original);
                        var request =
                            journal
                                .freezeRetirementRequest(original)
                                .frozenRetirementRequest()
                                .orElseThrow();
                        frozenInsideTransaction.countDown();
                        await(releaseCommit);
                        return request;
                      }));
      assertThat(frozenInsideTransaction.await(5, TimeUnit.SECONDS)).isTrue();
      var late =
          executor.submit(
              () ->
                  transactions.execute(
                      status -> {
                        lateBackend.set(
                            dsl.fetchSingle("SELECT pg_backend_pid() AS pid")
                                .get("pid", Integer.class));
                        lateStarted.countDown();
                        return journal.recordVerifiedResult(original, result);
                      }));
      assertThat(lateStarted.await(5, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      boolean waiting = false;
      while (!waiting && System.nanoTime() < deadline) {
        waiting =
            Boolean.TRUE.equals(
                dsl.fetchSingle(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                            + " WHERE pid = ? AND wait_event_type = 'Lock') AS waiting",
                        lateBackend.get())
                    .get("waiting", Boolean.class));
        if (!waiting) Thread.sleep(10);
      }
      assertThat(waiting)
          .as("independent PostgreSQL transaction waits for frozen row lock")
          .isTrue();
      assertThat(late.isDone()).isFalse();
      // Readback on another connection cannot expose the uncommitted abandonment/request.
      assertThat(journal.readCommitted(original).orElseThrow().abandoned()).isFalse();
      releaseCommit.countDown();
      var request = freezer.get(5, TimeUnit.SECONDS);
      assertThat(request.hasTokenWitness()).isFalse();
      assertThat(late.get(5, TimeUnit.SECONDS).verifiedWitness()).isPresent();
      assertThat(journal.readCommitted(original).orElseThrow().frozenRetirementRequest())
          .contains(request);
      assertThat(frozenBytes(original)).containsExactly(request.toByteArray());
    } finally {
      releaseCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void physicalSqlRejectsReplacementReopeningDeletionAndTruncation() throws Exception {
    var original = coordinates();
    reserve(original);
    rejectedSql(
        "UPDATE " + TABLE + " SET request_id = ? WHERE request_id = ?",
        UUID.randomUUID(),
        original.requestId());
    rejectedSql(
        "UPDATE " + TABLE + " SET caller_context_id = ? WHERE request_id = ?",
        UUID.randomUUID(),
        original.requestId());
    rejectedSql(
        "UPDATE " + TABLE + " SET expected_attempt_fence = 2 WHERE request_id = ?",
        original.requestId());
    rejectedSql(
        "UPDATE "
            + TABLE
            + " SET created_at = created_at + INTERVAL '1 second' WHERE request_id = ?",
        original.requestId());
    rejectedSql(
        "UPDATE " + TABLE + " SET verified_token_jti = ? WHERE request_id = ?",
        UUID.randomUUID(),
        original.requestId());
    rejectedSql(
        "UPDATE " + TABLE + " SET retirement_request_bytes = ? WHERE request_id = ?",
        new byte[] {1},
        original.requestId());
    rejectedSql(
        "INSERT INTO "
            + TABLE
            + " (request_id, caller_context_id, expected_attempt_fence) VALUES (?, ?, ?)",
        UUID.fromString("00000000-0000-1000-8000-000000000001"),
        UUID.randomUUID(),
        1);
    rejectedSql(
        "INSERT INTO "
            + TABLE
            + " (request_id, caller_context_id, expected_attempt_fence) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        0);
    rejectedSql(
        "INSERT INTO "
            + TABLE
            + " (request_id, caller_context_id, expected_attempt_fence, abandoned, abandoned_at)"
            + " VALUES (?, ?, 1, TRUE, clock_timestamp())",
        UUID.randomUUID(),
        UUID.randomUUID());
    var result = validatedResult(original, UUID.randomUUID());
    transactions.executeWithoutResult(status -> journal.recordVerifiedResult(original, result));
    rejectedSql(
        "UPDATE " + TABLE + " SET verified_token_sha256 = ? WHERE request_id = ?",
        "f".repeat(64),
        original.requestId());
    rejectedSql(
        "UPDATE "
            + TABLE
            + " SET verified_token_jti = NULL, verified_token_sha256 = NULL,"
            + " verified_token_generation = NULL, verified_issuance_fence = NULL WHERE request_id = ?",
        original.requestId());
    transactions.executeWithoutResult(status -> journal.abandon(original));
    rejectedSql(
        "UPDATE " + TABLE + " SET abandoned = FALSE, abandoned_at = NULL WHERE request_id = ?",
        original.requestId());
    transactions.executeWithoutResult(status -> journal.freezeRetirementRequest(original));
    rejectedSql(
        "UPDATE " + TABLE + " SET retirement_request_bytes = ? WHERE request_id = ?",
        new byte[] {1},
        original.requestId());
    var terminal = receipt(original, UUID.randomUUID(), Disposition.RETIRED);
    transactions.executeWithoutResult(status -> journal.recordDisposalReceipt(original, terminal));
    rejectedSql(
        "UPDATE " + TABLE + " SET disposal_operation_id = ? WHERE request_id = ?",
        UUID.randomUUID(),
        original.requestId());
    rejectedSql(
        "UPDATE "
            + TABLE
            + " SET disposal_disposition = 'PENDING', disposal_proof_kind = 'NONE' WHERE request_id = ?",
        original.requestId());
    rejectedSql("DELETE FROM " + TABLE + " WHERE request_id = ?", original.requestId());
    rejectedSql("TRUNCATE " + TABLE);
    assertThat(journal.readCommitted(original).orElseThrow().retainedReceipt()).contains(terminal);
  }

  @Test
  void transactionAndDurabilityMisuseCannotSupplyCommittedRemoteDispatchEvidence()
      throws Exception {
    var original = coordinates();
    assertThatThrownBy(() -> journal.reserve(original))
        .isInstanceOf(JournalUnavailableException.class);
    var readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    readOnly.setReadOnly(true);
    assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> journal.reserve(original)))
        .isInstanceOf(JournalUnavailableException.class);
    var wrongIsolation = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    wrongIsolation.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    assertThatThrownBy(
            () -> wrongIsolation.executeWithoutResult(status -> journal.reserve(original)))
        .isInstanceOf(JournalUnavailableException.class);
    // Actual Spring transaction with an unjoined DSL connection must also fail closed.
    var unjoined =
        new InitialGameplayLoginCoordinationJournalRepository(
            DSL.using(dataSource, SQLDialect.POSTGRES));
    assertThatThrownBy(
            () -> transactions.executeWithoutResult(status -> unjoined.reserve(original)))
        .isInstanceOf(JournalUnavailableException.class);
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      dsl.execute("SET LOCAL synchronous_commit = 'off'");
                      journal.reserve(original);
                    }))
        .isInstanceOf(JournalUnavailableException.class);
    reserve(original);
    transactions.executeWithoutResult(
        status -> {
          assertThatThrownBy(() -> journal.readCommitted(original))
              .isInstanceOf(JournalUnavailableException.class);
          assertThatThrownBy(() -> journal.readRetainedPage(Optional.empty(), 1))
              .isInstanceOf(JournalUnavailableException.class);
        });
    try (Connection connection = dataSource.getConnection()) {
      var reader =
          new InitialGameplayLoginCoordinationJournalRepository(
              DSL.using(connection, SQLDialect.POSTGRES));
      DSL.using(connection, SQLDialect.POSTGRES).execute("SET synchronous_commit = 'off'");
      assertThatThrownBy(() -> reader.readCommitted(original))
          .isInstanceOf(JournalUnavailableException.class);
      assertThatThrownBy(() -> reader.readRetainedPage(Optional.empty(), 1))
          .isInstanceOf(JournalUnavailableException.class);
      DSL.using(connection, SQLDialect.POSTGRES).execute("SET synchronous_commit = 'on'");
      connection.setAutoCommit(false);
      assertThatThrownBy(() -> reader.readCommitted(original))
          .isInstanceOf(JournalUnavailableException.class);
      connection.rollback();
    }
    assertThat(journal.readCommitted(original)).isPresent();
  }

  @Test
  void retainedInventoryIsBoundedKeysetOrderedAndIncludesReservedAndTerminalDuty() {
    var originals =
        List.of(coordinates(), coordinates(), coordinates(), coordinates(), coordinates());
    originals.forEach(this::reserve);
    var terminalOriginal = originals.get(0);
    transactions.executeWithoutResult(
        status -> {
          journal.abandon(terminalOriginal);
          journal.freezeRetirementRequest(terminalOriginal);
          journal.recordDisposalReceipt(
              terminalOriginal, receipt(terminalOriginal, UUID.randomUUID(), Disposition.NO_TOKEN));
        });
    var expected =
        dsl.fetch("SELECT request_id FROM " + TABLE + " ORDER BY request_id")
            .getValues("request_id", UUID.class);
    var first = journal.readRetainedPage(Optional.empty(), 2);
    var second =
        journal.readRetainedPage(Optional.of(first.getLast().coordinates().requestId()), 2);
    var third =
        journal.readRetainedPage(Optional.of(second.getLast().coordinates().requestId()), 2);
    var ids = new java.util.ArrayList<UUID>();
    for (var page : List.of(first, second, third)) {
      page.forEach(row -> ids.add(row.coordinates().requestId()));
    }
    assertThat(first).hasSize(2);
    assertThat(second).hasSize(2);
    assertThat(third).hasSize(1);
    assertThat(ids).containsExactlyElementsOf(expected);
    assertThat(journal.readRetainedPage(Optional.of(third.getLast().coordinates().requestId()), 2))
        .isEmpty();
    assertThat(journal.readRetainedPage(Optional.empty(), 128)).hasSize(5);
    for (int limit : List.of(0, -1, 129)) {
      assertThatThrownBy(() -> journal.readRetainedPage(Optional.empty(), limit))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private void reserve(InitialGameplayLoginAttemptCoordinates original) {
    transactions.executeWithoutResult(status -> journal.reserve(original));
    assertThat(journal.readCommitted(original).orElseThrow().coordinates()).isEqualTo(original);
  }

  private byte[] frozenBytes(InitialGameplayLoginAttemptCoordinates original) {
    return dsl.fetchSingle(
            "SELECT retirement_request_bytes FROM " + TABLE + " WHERE request_id = ?",
            original.requestId())
        .get("retirement_request_bytes", byte[].class);
  }

  private void rejectedSql(String sql, Object... values) {
    assertThatThrownBy(() -> dsl.execute(sql, values))
        .isInstanceOfSatisfying(
            DataAccessException.class,
            failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS))
        throw new IllegalStateException("Bounded journal race fixture timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Journal race fixture interrupted", interrupted);
    }
  }

  private static InitialGameplayLoginAttemptCoordinates coordinates() {
    return new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 1);
  }

  private static InitialGameplayLoginDisposalResult receipt(
      InitialGameplayLoginAttemptCoordinates original, UUID operation, Disposition disposition) {
    return new InitialGameplayLoginDisposalResult(
        original.requestId(),
        original.callerContextId(),
        operation,
        disposition,
        switch (disposition) {
          case PENDING -> ProofKind.NONE;
          case RETIRED -> ProofKind.PHYSICAL_TOKEN_DENIAL;
          case NO_TOKEN -> ProofKind.NO_ISSUANCE_BINDING;
        });
  }

  /**
   * Locally signed fixture follows CanonicalGameplayLoginResultTest; no real ingress/RPC custody.
   */
  private static CanonicalGameplayLoginResult validatedResult(
      InitialGameplayLoginAttemptCoordinates original, UUID jti) throws Exception {
    var claims =
        new LinkedHashMap<String, Object>(
            Map.ofEntries(
                Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
                Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
                Map.entry("sub", ACCOUNT.toString()),
                Map.entry("accountId", ACCOUNT.toString()),
                Map.entry("jti", jti.toString()),
                Map.entry("iat", NOW),
                Map.entry("nbf", NOW),
                Map.entry("exp", NOW + 120),
                Map.entry("tokenGeneration", "1"),
                Map.entry("issuanceFence", "1"),
                Map.entry("membershipVersion", Map.of()),
                Map.entry(
                    "authorityTuple", GameSessionAccountDelegationProfile.authorityTuple(1, 1))));
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input =
        encoder.encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"test-key\",\"typ\":\"JWT\"}"
                    .getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(JSON.writeValueAsBytes(claims));
    var signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keys.getPrivate());
    signature.update(input.getBytes(StandardCharsets.US_ASCII));
    String token = input + "." + encoder.encodeToString(signature.sign());
    var request =
        AuthenticateRequest.newBuilder()
            .setRequestId(original.requestId().toString())
            .setEmail("player@example.test")
            .setPassword("fixture-transient-secret")
            .setSourceContext(
                GameplayLoginSourceContext.newBuilder()
                    .setCallerContextId(original.callerContextId().toString())
                    .setCanonicalClientAddress("203.0.113.10")
                    .setTransportClass(
                        GameplayLoginTransportClass
                            .GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY))
            .build();
    var response =
        AuthenticateResponse.newBuilder()
            .setAuthToken(token)
            .setAccountId(ACCOUNT.toString())
            .setRequestId(original.requestId().toString())
            .setCallerContextId(original.callerContextId().toString())
            .setTokenJti(jti.toString())
            .setTokenSha256(
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.US_ASCII))))
            .setTokenProfile(GameSessionAccountDelegationProfile.PROFILE)
            .setIssuedAtEpochSeconds(NOW)
            .setNotBeforeEpochSeconds(NOW)
            .setExpiresAtEpochSeconds(NOW + 120)
            .setTokenGeneration("1")
            .setIssuanceFence("1")
            .setAuthorityTupleCanonicalJson(canonical(claims.get("authorityTuple")))
            .setOutboxCheckpointsCanonicalJson(
                canonical(
                    List.of(
                        Map.of(
                            "outboxStreamKey",
                            "account:auth-authority:v1:account/" + ACCOUNT,
                            "outboxSequence",
                            "0"),
                        Map.of(
                            "outboxStreamKey",
                            "account:auth-authority:v1:issuer/"
                                + GameSessionAccountDelegationProfile.ISSUER,
                            "outboxSequence",
                            "0"))))
            .setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of()))
            .build();
    return CanonicalGameplayLoginResult.fromAuthenticatedResponse(
        response, request, verifier(), CLOCK);
  }

  private static ByteString canonical(Object value) throws Exception {
    return ByteString.copyFrom(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)));
  }

  private static AccountAsymmetricJwtVerifier verifier() throws Exception {
    var pin =
        new AccountPublicJwksCache.SourceIdentity(
            "test",
            "cluster-a",
            "11111111-1111-4111-8111-111111111111",
            "test",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "binding-7",
            "https://kubernetes.example:6443",
            "a".repeat(64));
    var key = (RSAPublicKey) keys.getPublic();
    String jwks =
        JSON.writeValueAsString(
            Map.of(
                "keys",
                List.of(
                    Map.of(
                        "kty",
                        "RSA",
                        "use",
                        "sig",
                        "alg",
                        "RS256",
                        "kid",
                        "test-key",
                        "n",
                        unsigned(key.getModulus().toByteArray()),
                        "e",
                        unsigned(key.getPublicExponent().toByteArray())))));
    var cache =
        new AccountPublicJwksCache(
            () ->
                new AccountPublicJwksCache.PublicJwksSnapshot(
                    pin, jwks.getBytes(StandardCharsets.UTF_8)),
            pin,
            CLOCK,
            Duration.ofSeconds(30));
    return new AccountAsymmetricJwtVerifier(cache, CLOCK);
  }

  private static String unsigned(byte[] bytes) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
  }
}
