package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.RegistrationConflictException;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionAuthoredWorldSourceRepositoryIntegrationTest {
  private static final String NAMESPACE = "authored-world-intake-test";
  private static final String MIGRATION_LOCATION = "classpath:db/migration";
  private static final Table<?> TENANT_BINDINGS =
      DSL.table(DSL.name("game_session_authored_world_tenant_source_binding"));
  private static final Table<?> INTAKES =
      DSL.table(DSL.name("game_session_authored_world_source_intake"));

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void registersAdditionalWorldsAndReplaysExactReceiptWithoutMutation() {
    Fixture fixture = fixture();
    UUID tenantId = uuid(1);
    UUID intakeRequestId = uuid(10);
    AuthoredWorldSourceEvidence firstSource =
        source(tenantId, uuid(101), uuid(201), "stable-tenant", "coast", "Café 🐉");

    IntakeReceipt first = fixture.register(intakeRequestId, firstSource);
    String bindingXmin =
        fixture.xmin(
            "SELECT xmin::text AS xmin FROM game_session_authored_world_tenant_source_binding "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
            NAMESPACE,
            tenantId);
    String intakeXmin =
        fixture.xmin(
            "SELECT xmin::text AS xmin FROM game_session_authored_world_source_intake "
                + "WHERE target_namespace = ? AND intake_request_id = ?",
            NAMESPACE,
            intakeRequestId);

    assertThat(fixture.register(intakeRequestId, firstSource)).isEqualTo(first);
    assertThat(
            fixture.xmin(
                "SELECT xmin::text AS xmin FROM game_session_authored_world_tenant_source_binding "
                    + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                NAMESPACE,
                tenantId))
        .isEqualTo(bindingXmin);
    assertThat(
            fixture.xmin(
                "SELECT xmin::text AS xmin FROM game_session_authored_world_source_intake "
                    + "WHERE target_namespace = ? AND intake_request_id = ?",
                NAMESPACE,
                intakeRequestId))
        .isEqualTo(intakeXmin);

    AuthoredWorldSourceEvidence secondSource =
        source(tenantId, uuid(102), uuid(202), "stable-tenant", "highlands", "Highlands");
    IntakeReceipt second = fixture.register(uuid(11), secondSource);
    assertThat(second.source()).isEqualTo(secondSource);
    assertThat(fixture.read(first)).contains(first);
    assertThat(fixture.read(second)).contains(second);
    assertThat(fixture.repository.read(uuid(999), tenantId, "coast", NAMESPACE)).isEmpty();
    assertThat(fixture.repository.read(first.operationId(), tenantId, "coast", "other-space"))
        .isEmpty();
    assertThat(fixture.repository.read(first.operationId(), uuid(999), "coast", NAMESPACE))
        .isEmpty();
    assertThat(fixture.repository.read(first.operationId(), tenantId, "other-world", NAMESPACE))
        .isEmpty();
    assertThat(
            fixture.xmin(
                "SELECT xmin::text AS xmin FROM game_session_authored_world_tenant_source_binding "
                    + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                NAMESPACE,
                tenantId))
        .isEqualTo(bindingXmin);
    assertThat(
            fixture.xmin(
                "SELECT xmin::text AS xmin FROM game_session_authored_world_source_intake "
                    + "WHERE target_namespace = ? AND intake_request_id = ?",
                NAMESPACE,
                intakeRequestId))
        .isEqualTo(intakeXmin);
    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(INTAKES)).isEqualTo(2);
  }

  @Test
  void changedRequestAndDuplicateSourceOrSelectorClaimsLeaveNoPartialRows() {
    Fixture fixture = fixture();
    UUID tenantId = uuid(2);
    UUID intakeRequestId = uuid(20);
    AuthoredWorldSourceEvidence original =
        source(tenantId, uuid(111), uuid(211), "tenant-two", "first-world", "First World");
    IntakeReceipt receipt = fixture.register(intakeRequestId, original);

    assertConflict(
        () ->
            fixture.register(
                intakeRequestId,
                source(
                    tenantId,
                    uuid(111),
                    uuid(211),
                    "tenant-two",
                    "first-world",
                    "Changed Display")));
    assertConflict(
        () ->
            fixture.register(
                uuid(21),
                source(tenantId, uuid(112), uuid(211), "tenant-two", "second-world", "Second")));
    assertConflict(
        () ->
            fixture.register(
                uuid(22),
                source(tenantId, uuid(111), uuid(212), "tenant-two", "second-world", "Second")));
    assertConflict(
        () ->
            fixture.register(
                uuid(23),
                source(tenantId, uuid(113), uuid(213), "tenant-two", "first-world", "Second")));
    assertConflict(
        () ->
            fixture.register(
                uuid(24),
                source(uuid(3), uuid(114), uuid(214), "tenant-two", "other-world", "Other")));
    assertConflict(
        () ->
            fixture.register(
                uuid(25),
                source(tenantId, uuid(115), uuid(215), "changed-tenant", "third-world", "Third")));
    assertConflict(
        () ->
            fixture.register(
                uuid(26),
                source(
                    tenantId,
                    uuid(116),
                    uuid(216),
                    "tenant-two",
                    "third-world",
                    "Third",
                    original.sourceGameRowId() + 1L)));
    assertConflict(
        () ->
            fixture.register(
                uuid(27),
                source(
                    uuid(6),
                    uuid(117),
                    uuid(217),
                    "other-tenant",
                    "other-world",
                    "Other",
                    original.sourceGameRowId())));

    assertThat(fixture.read(receipt)).contains(receipt);
    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(INTAKES)).isEqualTo(1);
  }

  @Test
  void requiresOwnerTransactionRollsBackClaimsAndReadsOnlyCommittedRows() {
    Fixture fixture = fixture();
    AuthoredWorldSourceEvidence source =
        source(uuid(4), uuid(121), uuid(221), "tenant-four", "one-world", "One World");
    assertThatThrownBy(() -> fixture.repository.register(uuid(30), source))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires an active owner transaction");

    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status -> {
                      fixture.repository.read(uuid(301), uuid(4), "one-world", NAMESPACE);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed-outcome owner read");

    fixture.transactions.execute(
        status -> {
          fixture.repository.register(uuid(31), source);
          status.setRollbackOnly();
          return null;
        });
    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isZero();
    assertThat(fixture.dsl.fetchCount(INTAKES)).isZero();
    assertThat(fixture.repository.read(uuid(301), uuid(4), "one-world", NAMESPACE)).isEmpty();
  }

  @Test
  void sourceAndTenantClaimsRejectMutationDeleteTruncateAndClaimOnlyCommit() {
    Fixture fixture = fixture();
    AuthoredWorldSourceEvidence source =
        source(uuid(5), uuid(131), uuid(231), "tenant-five", "one-world", "One World");
    IntakeReceipt receipt = fixture.register(uuid(40), source);

    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE game_session_authored_world_source_intake SET world_slug = ? "
                        + "WHERE operation_id = ?",
                    "mutated-world",
                    receipt.operationId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("authored-world source evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "DELETE FROM game_session_authored_world_source_intake WHERE operation_id = ?",
                    receipt.operationId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("authored-world source evidence is immutable");
    assertThatThrownBy(
            () -> fixture.dsl.execute("TRUNCATE TABLE game_session_authored_world_source_intake"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("authored-world source evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE game_session_authored_world_tenant_source_binding SET tenant_slug = ? "
                        + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                    "mutated-tenant",
                    NAMESPACE,
                    source.canonicalTenantId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("authored-world source evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "DELETE FROM game_session_authored_world_tenant_source_binding "
                        + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                    NAMESPACE,
                    source.canonicalTenantId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("authored-world source evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "TRUNCATE TABLE game_session_authored_world_tenant_source_binding"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("authored-world source evidence is immutable");

    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status -> {
                      fixture.dsl.execute(
                          "INSERT INTO game_session_authored_world_tenant_source_binding "
                              + "(target_namespace, canonical_tenant_id, tenant_slug, "
                              + "source_game_row_id, source_game_tenant_key, provenance_kind) "
                              + "VALUES (?, ?, ?, ?, ?, ?)",
                          NAMESPACE,
                          uuid(6),
                          "claim-only",
                          106L,
                          "source-six",
                          "NEW_GAME_ROW");
                      return null;
                    }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("cannot commit without a world source");

    assertThat(fixture.read(receipt)).contains(receipt);
    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(INTAKES)).isEqualTo(1);
  }

  @Test
  void concurrentExactIntakeRetriesReturnOneReceiptAndSlugRaceHasOneWinner() throws Exception {
    Fixture retryFixture = fixture();
    AuthoredWorldSourceEvidence retrySource =
        source(uuid(7), uuid(141), uuid(241), "same-tenant", "same-world", "Same World");
    CountDownLatch retryReady = new CountDownLatch(2);
    CountDownLatch retryStart = new CountDownLatch(1);
    ExecutorService retryExecutor = Executors.newFixedThreadPool(2);
    try {
      Future<IntakeReceipt> first =
          retryExecutor.submit(
              () ->
                  concurrentRegister(retryFixture, retryReady, retryStart, uuid(50), retrySource));
      Future<IntakeReceipt> second =
          retryExecutor.submit(
              () ->
                  concurrentRegister(retryFixture, retryReady, retryStart, uuid(50), retrySource));
      assertThat(retryReady.await(10, TimeUnit.SECONDS)).isTrue();
      retryStart.countDown();
      IntakeReceipt firstReceipt = first.get(10, TimeUnit.SECONDS);
      IntakeReceipt secondReceipt = second.get(10, TimeUnit.SECONDS);
      assertThat(secondReceipt).isEqualTo(firstReceipt);
      assertThat(retryFixture.read(firstReceipt)).contains(firstReceipt);
      assertThat(retryFixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(1);
      assertThat(retryFixture.dsl.fetchCount(INTAKES)).isEqualTo(1);
    } finally {
      retryStart.countDown();
      retryExecutor.shutdownNow();
    }

    Fixture raceFixture = fixture();
    AuthoredWorldSourceEvidence tenantA =
        source(uuid(8), uuid(151), uuid(251), "contended-tenant", "world-a", "World A");
    AuthoredWorldSourceEvidence tenantB =
        source(uuid(9), uuid(152), uuid(252), "contended-tenant", "world-b", "World B");
    CountDownLatch raceReady = new CountDownLatch(2);
    CountDownLatch raceStart = new CountDownLatch(1);
    ExecutorService raceExecutor = Executors.newFixedThreadPool(2);
    try {
      Future<RegistrationOutcome> first =
          raceExecutor.submit(
              () -> raceRegister(raceFixture, raceReady, raceStart, uuid(60), tenantA));
      Future<RegistrationOutcome> second =
          raceExecutor.submit(
              () -> raceRegister(raceFixture, raceReady, raceStart, uuid(61), tenantB));
      assertThat(raceReady.await(10, TimeUnit.SECONDS)).isTrue();
      raceStart.countDown();
      assertThat(
              java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(RegistrationOutcome.CREATED, RegistrationOutcome.CONFLICT);
      assertThat(raceFixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(1);
      assertThat(raceFixture.dsl.fetchCount(INTAKES)).isEqualTo(1);
    } finally {
      raceStart.countDown();
      raceExecutor.shutdownNow();
    }
  }

  private IntakeReceipt concurrentRegister(
      Fixture fixture,
      CountDownLatch ready,
      CountDownLatch start,
      UUID intakeRequestId,
      AuthoredWorldSourceEvidence source) {
    ready.countDown();
    await(start);
    return fixture.register(intakeRequestId, source);
  }

  private RegistrationOutcome raceRegister(
      Fixture fixture,
      CountDownLatch ready,
      CountDownLatch start,
      UUID intakeRequestId,
      AuthoredWorldSourceEvidence source) {
    ready.countDown();
    await(start);
    try {
      fixture.register(intakeRequestId, source);
      return RegistrationOutcome.CREATED;
    } catch (RegistrationConflictException conflict) {
      return RegistrationOutcome.CONFLICT;
    }
  }

  private static AuthoredWorldSourceEvidence source(
      UUID canonicalTenantId,
      UUID registrationRequestId,
      UUID operationId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName) {
    return source(
        canonicalTenantId,
        registrationRequestId,
        operationId,
        tenantSlug,
        worldSlug,
        worldDisplayName,
        Math.abs(canonicalTenantId.getMostSignificantBits()) + 1L);
  }

  private static AuthoredWorldSourceEvidence source(
      UUID canonicalTenantId,
      UUID registrationRequestId,
      UUID operationId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName,
      long sourceGameRowId) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            registrationRequestId,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName);
    String sourceGameTenantKey = "source-" + canonicalTenantId.toString().substring(0, 8);
    String provenanceKind = "NEW_GAME_ROW";
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        tenantSlug,
        worldSlug,
        worldDisplayName,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        evidenceDigest);
  }

  private Fixture fixture() {
    String schema = "gs_authored_world_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(dsl, new GameSessionAuthoredWorldSourceRepository(dsl), transactions);
  }

  private void assertConflict(Runnable registration) {
    assertThatThrownBy(registration::run).isInstanceOf(RegistrationConflictException.class);
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent source intake");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while awaiting concurrent source intake", exception);
    }
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private enum RegistrationOutcome {
    CREATED,
    CONFLICT
  }

  private record Fixture(
      DSLContext dsl,
      GameSessionAuthoredWorldSourceRepository repository,
      TransactionTemplate transactions) {
    IntakeReceipt register(UUID intakeRequestId, AuthoredWorldSourceEvidence source) {
      IntakeReceipt receipt =
          transactions.execute(status -> repository.register(intakeRequestId, source));
      return java.util.Objects.requireNonNull(receipt);
    }

    java.util.Optional<IntakeReceipt> read(IntakeReceipt receipt) {
      return repository.read(
          receipt.operationId(),
          receipt.source().canonicalTenantId(),
          receipt.source().worldSlug(),
          receipt.source().targetNamespace());
    }

    String xmin(String sql, Object... bindings) {
      return dsl.fetch(sql, bindings).getFirst().get("xmin", String.class);
    }
  }
}
