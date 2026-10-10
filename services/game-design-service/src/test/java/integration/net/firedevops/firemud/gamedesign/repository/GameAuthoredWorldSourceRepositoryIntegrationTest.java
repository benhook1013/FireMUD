package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
class GameAuthoredWorldSourceRepositoryIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final String NAMESPACE = "authored-world-test";
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Table<?> TENANT_BINDINGS =
      DSL.table(DSL.name("game_design_tenant_slug_binding"));
  private static final Table<?> OPERATIONS =
      DSL.table(DSL.name("game_design_authored_world_source_operations"));
  private static final org.jooq.Field<Long> GAME_ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final org.jooq.Field<String> GAME_NAME = DSL.field(DSL.name("name"), String.class);
  private static final org.jooq.Field<String> GAME_DESCRIPTION =
      DSL.field(DSL.name("description"), String.class);
  private static final org.jooq.Field<UUID> OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final org.jooq.Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final org.jooq.Field<String> WORLD_SLUG =
      DSL.field(DSL.name("world_slug"), String.class);
  private static final org.jooq.Field<String> WORLD_DISPLAY_NAME =
      DSL.field(DSL.name("world_display_name"), String.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void registersAndReadsExactFreshAndRetainedV29ToV30Sources() {
    Fixture fixture = fixtureWithRetainedAndFreshGame();

    AuthoredWorldSourceEvidence retainedReceipt =
        fixture.register(
            uuid(1), fixture.retainedCanonicalTenantId(), "old-kingdom", "ember-coast", "Café 🐉");
    AuthoredWorldSourceEvidence freshReceipt =
        fixture.register(
            uuid(2),
            fixture.freshCanonicalTenantId(),
            "new-kingdom",
            "silver-march",
            "Silver March");

    assertThat(retainedReceipt.provenanceKind()).isEqualTo("RETAINED_GAME_V29");
    assertThat(retainedReceipt.sourceGameRowId()).isEqualTo(fixture.retainedGameRowId());
    assertThat(retainedReceipt.sourceGameTenantKey()).isEqualTo("retained-authored-tenant");
    assertThat(freshReceipt.provenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(freshReceipt.sourceGameRowId()).isEqualTo(fixture.freshGameRowId());
    assertThat(freshReceipt.sourceGameTenantKey()).isEqualTo("fresh-authored-tenant");
    assertThat(
            fixture.repository.read(
                retainedReceipt.operationId(),
                retainedReceipt.canonicalTenantId(),
                retainedReceipt.worldSlug(),
                retainedReceipt.targetNamespace()))
        .contains(retainedReceipt);
    assertThat(
            fixture.repository.read(
                freshReceipt.operationId(),
                freshReceipt.canonicalTenantId(),
                freshReceipt.worldSlug(),
                freshReceipt.targetNamespace()))
        .contains(freshReceipt);
    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(2);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(2);
  }

  @Test
  void exactRetryReusesBothDigestsAndChangedRequestLeavesNoSecondClaim() {
    Fixture fixture = fixtureWithRetainedAndFreshGame();
    UUID requestId = uuid(10);
    AuthoredWorldSourceEvidence original =
        fixture.register(
            requestId,
            fixture.freshCanonicalTenantId(),
            "stable-tenant",
            "first-world",
            "First World");
    String gameXmin = gameXmin(fixture.dsl, "fresh-authored-tenant");

    AuthoredWorldSourceEvidence retry =
        fixture.register(
            requestId,
            fixture.freshCanonicalTenantId(),
            "stable-tenant",
            "first-world",
            "First World");
    assertThat(retry).isEqualTo(original);
    assertThat(
            fixture.repository.read(
                original.operationId(),
                original.canonicalTenantId(),
                original.worldSlug(),
                original.targetNamespace()))
        .contains(original);

    assertThatThrownBy(
            () ->
                fixture.register(
                    requestId,
                    fixture.freshCanonicalTenantId(),
                    "stable-tenant",
                    "first-world",
                    "Changed World"))
        .isInstanceOf(GameAuthoredWorldSourceRepository.RegistrationConflictException.class)
        .hasMessageContaining("reused with changed input");
    assertThat(gameXmin(fixture.dsl, "fresh-authored-tenant")).isEqualTo(gameXmin);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(2);
    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void registrationRequiresOwnerTransactionAndReadRequiresCommittedOutcome() {
    Fixture fixture = fixtureWithRetainedAndFreshGame();
    assertThatThrownBy(
            () ->
                fixture.repository.register(
                    NAMESPACE,
                    uuid(11),
                    fixture.freshCanonicalTenantId(),
                    "transaction-tenant",
                    "transaction-world",
                    "Transaction World"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active Game Design owner transaction");
    assertThatThrownBy(
            () ->
                fixture.transactionTemplate.execute(
                    status ->
                        fixture.repository.read(
                            uuid(12),
                            fixture.freshCanonicalTenantId(),
                            "transaction-world",
                            NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed-outcome owner read");
    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
  }

  @Test
  void tenantSlugAndWorldSlugCollisionsFailWithoutPartialAssignments() {
    Fixture fixture = fixtureWithRetainedAndFreshGame();
    fixture.register(
        uuid(20), fixture.freshCanonicalTenantId(), "claimed-tenant", "one-world", "One World");

    assertThatThrownBy(
            () ->
                fixture.register(
                    uuid(21),
                    fixture.retainedCanonicalTenantId(),
                    "claimed-tenant",
                    "other-world",
                    "Other World"))
        .isInstanceOf(GameAuthoredWorldSourceRepository.RegistrationConflictException.class)
        .hasMessageContaining("already owned");
    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);

    assertThatThrownBy(
            () ->
                fixture.register(
                    uuid(22),
                    fixture.freshCanonicalTenantId(),
                    "claimed-tenant",
                    "one-world",
                    "Different world metadata"))
        .isInstanceOf(GameAuthoredWorldSourceRepository.RegistrationConflictException.class)
        .hasMessageContaining("selector is already owned");
    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(2);
  }

  @Test
  void failedOwnerCommitRollsBackBothTenantSelectorAndWorldSource() {
    Fixture fixture = fixtureWithRetainedAndFreshGame();
    assertThatThrownBy(
            () ->
                fixture.transactionTemplate.execute(
                    status -> {
                      fixture.repository.register(
                          NAMESPACE,
                          uuid(30),
                          fixture.freshCanonicalTenantId(),
                          "rollback-tenant",
                          "rollback-world",
                          "Rollback World");
                      throw new IllegalStateException("simulate failed owner commit");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulate failed owner commit");

    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
    assertThat(
            fixture.register(
                uuid(31),
                fixture.freshCanonicalTenantId(),
                "rollback-tenant",
                "rollback-world",
                "Rollback World"))
        .isNotNull();
  }

  @Test
  void databaseRejectsSelectorClaimsWithoutAnAtomicWorldSourceOperation() {
    Fixture fixture = fixtureWithRetainedAndFreshGame();
    assertThatThrownBy(
            () ->
                fixture.transactionTemplate.execute(
                    status -> {
                      fixture
                          .dsl
                          .insertInto(TENANT_BINDINGS)
                          .set(DSL.field(DSL.name("target_namespace"), String.class), NAMESPACE)
                          .set(
                              DSL.field(DSL.name("canonical_tenant_id"), UUID.class),
                              fixture.freshCanonicalTenantId())
                          .set(DSL.field(DSL.name("tenant_slug"), String.class), "orphan-tenant")
                          .set(
                              DSL.field(DSL.name("source_game_row_id"), Long.class),
                              fixture.freshGameRowId())
                          .set(
                              DSL.field(DSL.name("source_game_tenant_key"), String.class),
                              "fresh-authored-tenant")
                          .set(DSL.field(DSL.name("provenance_kind"), String.class), "NEW_GAME_ROW")
                          .execute();
                      return null;
                    }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("cannot commit without a world source");

    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
  }

  @Test
  void databaseRejectsTenantSelectorBoundToContradictoryGameProvenance() {
    Fixture fixture = fixtureWithRetainedAndFreshGame();
    assertThatThrownBy(
            () ->
                fixture.transactionTemplate.execute(
                    status -> {
                      fixture
                          .dsl
                          .insertInto(TENANT_BINDINGS)
                          .set(DSL.field(DSL.name("target_namespace"), String.class), NAMESPACE)
                          .set(
                              DSL.field(DSL.name("canonical_tenant_id"), UUID.class),
                              fixture.freshCanonicalTenantId())
                          .set(
                              DSL.field(DSL.name("tenant_slug"), String.class), "mismatched-tenant")
                          .set(
                              DSL.field(DSL.name("source_game_row_id"), Long.class),
                              fixture.freshGameRowId())
                          .set(
                              DSL.field(DSL.name("source_game_tenant_key"), String.class),
                              "retained-authored-tenant")
                          .set(DSL.field(DSL.name("provenance_kind"), String.class), "NEW_GAME_ROW")
                          .execute();
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("does not match its game row");

    assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
  }

  @Test
  void readIsExactAndPersistedBindingsAndSourceOperationsAreImmutable() {
    Fixture fixture = fixtureWithRetainedAndFreshGame();
    AuthoredWorldSourceEvidence receipt =
        fixture.register(
            uuid(40), fixture.freshCanonicalTenantId(), "read-tenant", "read-world", "Read World");

    assertThat(
            fixture.repository.read(
                receipt.operationId(),
                receipt.canonicalTenantId(),
                "different-world",
                receipt.targetNamespace()))
        .isEmpty();
    assertThat(
            fixture.repository.read(
                receipt.operationId(),
                fixture.retainedCanonicalTenantId(),
                receipt.worldSlug(),
                receipt.targetNamespace()))
        .isEmpty();
    assertThat(
            fixture.repository.read(
                receipt.operationId(),
                receipt.canonicalTenantId(),
                receipt.worldSlug(),
                "other-namespace"))
        .isEmpty();

    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .update(OPERATIONS)
                    .set(WORLD_DISPLAY_NAME, "Mutated")
                    .where(OPERATION_ID.eq(receipt.operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .deleteFrom(TENANT_BINDINGS)
                    .where(
                        DSL.field(DSL.name("target_namespace"), String.class)
                            .eq(receipt.targetNamespace())
                            .and(
                                DSL.field(DSL.name("canonical_tenant_id"), UUID.class)
                                    .eq(receipt.canonicalTenantId())))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("binding is immutable");
    assertThat(
            fixture.repository.read(
                receipt.operationId(),
                receipt.canonicalTenantId(),
                receipt.worldSlug(),
                receipt.targetNamespace()))
        .contains(receipt);
  }

  @Test
  void concurrentExactRegistrationRetriesReturnOnePersistedReceipt() throws Exception {
    Fixture fixture = fixtureWithRetainedAndFreshGame();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AuthoredWorldSourceEvidence> first =
          executor.submit(
              () ->
                  concurrentRegister(fixture, ready, start, uuid(45), "same-tenant", "same-world"));
      Future<AuthoredWorldSourceEvidence> second =
          executor.submit(
              () ->
                  concurrentRegister(fixture, ready, start, uuid(45), "same-tenant", "same-world"));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      AuthoredWorldSourceEvidence firstReceipt = first.get(10, TimeUnit.SECONDS);
      AuthoredWorldSourceEvidence secondReceipt = second.get(10, TimeUnit.SECONDS);
      assertThat(secondReceipt).isEqualTo(firstReceipt);
      assertThat(
              fixture.repository.read(
                  firstReceipt.operationId(),
                  firstReceipt.canonicalTenantId(),
                  firstReceipt.worldSlug(),
                  firstReceipt.targetNamespace()))
          .contains(firstReceipt);
      assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentDifferentTenantsCannotClaimTheSameTenantSlug() throws Exception {
    Fixture fixture = fixtureWithRetainedAndFreshGame();
    UUID secondCanonicalTenantId = fixture.createFreshGame("second-fresh-authored-tenant");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<RegistrationOutcome> freshTenant =
          executor.submit(
              () ->
                  raceRegister(
                      fixture,
                      ready,
                      start,
                      uuid(50),
                      fixture.freshCanonicalTenantId(),
                      "racing-tenant",
                      "fresh-world"));
      Future<RegistrationOutcome> secondTenant =
          executor.submit(
              () ->
                  raceRegister(
                      fixture,
                      ready,
                      start,
                      uuid(51),
                      secondCanonicalTenantId,
                      "racing-tenant",
                      "second-world"));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(
              java.util.List.of(
                  freshTenant.get(10, TimeUnit.SECONDS), secondTenant.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(RegistrationOutcome.CREATED, RegistrationOutcome.CONFLICT);
      assertThat(fixture.dsl.fetchCount(TENANT_BINDINGS)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(3);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private RegistrationOutcome raceRegister(
      Fixture fixture,
      CountDownLatch ready,
      CountDownLatch start,
      UUID registrationRequestId,
      UUID canonicalTenantId,
      String tenantSlug,
      String worldSlug) {
    ready.countDown();
    await(start);
    try {
      fixture.register(
          registrationRequestId, canonicalTenantId, tenantSlug, worldSlug, "Racing World");
      return RegistrationOutcome.CREATED;
    } catch (GameAuthoredWorldSourceRepository.RegistrationConflictException conflict) {
      return RegistrationOutcome.CONFLICT;
    }
  }

  private AuthoredWorldSourceEvidence concurrentRegister(
      Fixture fixture,
      CountDownLatch ready,
      CountDownLatch start,
      UUID registrationRequestId,
      String tenantSlug,
      String worldSlug) {
    ready.countDown();
    await(start);
    return fixture.register(
        registrationRequestId,
        fixture.freshCanonicalTenantId(),
        tenantSlug,
        worldSlug,
        "Concurrent World");
  }

  private Fixture fixtureWithRetainedAndFreshGame() {
    String schema = "game_design_authored_world_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, MigrationVersion.fromVersion("28"));

    DSLContext legacyDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    Long retainedGameRowId =
        legacyDsl
            .insertInto(GAME)
            .set(TENANT_ID, "retained-authored-tenant")
            .set(GAME_NAME, "Retained Source Game")
            .set(GAME_DESCRIPTION, "Created before canonical tenant identity")
            .returning(GAME_ID)
            .fetchOne(GAME_ID);
    assertThat(retainedGameRowId).isNotNull();

    migrate(dataSource, schema, null);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameRepository gameRepository = new GameRepository(dsl);
    GameAuthoredWorldSourceRepository repository = new GameAuthoredWorldSourceRepository(dsl);
    UUID retainedCanonicalTenantId =
        dsl.select(CANONICAL_TENANT_ID)
            .from(GAME)
            .where(GAME_ID.eq(retainedGameRowId))
            .fetchOne(CANONICAL_TENANT_ID);
    Game freshGame = new Game();
    freshGame.setTenantId("fresh-authored-tenant");
    freshGame.setName("Fresh Source Game");
    freshGame.setDescription("Created after canonical tenant identity");
    Game persistedFreshGame = transactionTemplate.execute(status -> gameRepository.save(freshGame));
    assertThat(persistedFreshGame).isNotNull();
    return new Fixture(
        dsl,
        repository,
        gameRepository,
        transactionTemplate,
        retainedGameRowId,
        retainedCanonicalTenantId,
        persistedFreshGame.getId(),
        persistedFreshGame.getCanonicalTenantId());
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private void migrate(DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private String gameXmin(DSLContext dsl, String tenantKey) {
    return dsl.fetch("SELECT xmin::text AS xmin FROM game WHERE tenant_id = ?", tenantKey)
        .getFirst()
        .get("xmin", String.class);
  }

  private UUID uuid(int suffix) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", suffix));
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent registration");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for concurrent registration", exception);
    }
  }

  private enum RegistrationOutcome {
    CREATED,
    CONFLICT
  }

  private record Fixture(
      DSLContext dsl,
      GameAuthoredWorldSourceRepository repository,
      GameRepository gameRepository,
      TransactionTemplate transactionTemplate,
      long retainedGameRowId,
      UUID retainedCanonicalTenantId,
      long freshGameRowId,
      UUID freshCanonicalTenantId) {
    AuthoredWorldSourceEvidence register(
        UUID requestId,
        UUID canonicalTenantId,
        String tenantSlug,
        String worldSlug,
        String worldDisplayName) {
      AuthoredWorldSourceEvidence receipt =
          transactionTemplate.execute(
              status ->
                  repository.register(
                      NAMESPACE,
                      requestId,
                      canonicalTenantId,
                      tenantSlug,
                      worldSlug,
                      worldDisplayName));
      return java.util.Objects.requireNonNull(receipt);
    }

    UUID createFreshGame(String tenantKey) {
      Game game = new Game();
      game.setTenantId(tenantKey);
      game.setName(tenantKey);
      game.setDescription("Concurrent source fixture");
      Game persisted = transactionTemplate.execute(status -> gameRepository.save(game));
      return java.util.Objects.requireNonNull(persisted).getCanonicalTenantId();
    }
  }
}
