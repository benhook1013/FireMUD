package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantSnapshotRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionRetainedTenantSnapshotRepositoryIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String NAMESPACE = "retained-snapshot-test";
  private static final String TENANT_ID = "70123";
  private static final long TENANT = Long.parseLong(TENANT_ID);
  private static final long OTHER_TENANT = TENANT + 1;
  private static final UUID SHARED_NAMESPACE =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ISOLATED_NAMESPACE =
      UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID REALM_SHARED = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID REALM_ISOLATED =
      UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final String COMMITTED_INSTANCE_PHANTOM_INSERT =
      "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_id, status, row_version) "
          + "VALUES (901, "
          + TENANT
          + ", 'phantom', 90123, 'STOPPED', 0)";
  private static final String COMMITTED_POINTER_PHANTOM_INSERT =
      "INSERT INTO gameplay_admission_pointer (id, world_slug, world_display_name, realm_slug, "
          + "realm_display_name, tenant_id, game_instance_id, pointer_version, visible, "
          + "requires_character_selection, state_scope, character_creation_policy, last_updated_by, "
          + "last_update_reason, public_production_realm, realm_id, playable_state_namespace_id) "
          + "VALUES (902, 'phantom-world', 'Phantom', 'phantom-realm', 'Phantom realm', "
          + TENANT
          + ", 901, 1, false, false, 'ISOLATED', 'DISABLED', 'test', 'fence test', false, "
          + "'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 'ffffffff-ffff-4fff-8fff-ffffffffffff')";
  // The integration runtime includes other services' migration resources; scan this module only.
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void capturesExactOrderedTenantProjectionAndStableDigestUnderReadCommittedOwnerTransaction()
      throws Exception {
    Fixture fixture = fixture();
    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status -> fixture.repository.capture(NAMESPACE, Long.toString(OTHER_TENANT))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no retained tenant evidence");
    fixture.seedProjection();

    assertThatThrownBy(() -> fixture.repository.capture(NAMESPACE, TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");

    GameSessionRetainedTenantSnapshot snapshot = fixture.capture();
    JsonNode root = JSON.readTree(snapshot.canonicalJson());
    assertThat(root.get("schemaVersion").intValue()).isEqualTo(1);
    assertThat(root.get("targetNamespace").textValue()).isEqualTo(NAMESPACE);
    assertThat(root.get("legacyGameSessionTenantId").textValue()).isEqualTo(TENANT_ID);
    assertThat(textValues(root.get("instances"), "id")).containsExactly("2", "10", "20");
    assertThat(root.get("instances").get(1).get("runtime_version").textValue())
        .isEqualTo("Café 🐉");
    assertThat(root.get("instances").get(1).get("row_version").textValue())
        .isEqualTo("9223372036854775807");
    assertThat(textValues(root.get("pointers"), "id")).containsExactly("2", "10");
    assertThat(root.get("pointers").get(1).get("catalog_revision").textValue())
        .isEqualTo("9223372036854775807");
    assertThat(root.get("pointerEvents").get(1).get("game_instance_id").textValue())
        .isEqualTo("888888");
    assertThat(
            root.get("preparedUpgrades").get(0).get("executed_target_game_instance_id").textValue())
        .isEqualTo("999999");
    assertThat(root.get("backfillIssues").size()).isZero();
    assertThat(
            GameSessionRetainedTenantSnapshot.fromCanonicalJson(
                NAMESPACE, TENANT_ID, snapshot.canonicalJson(), snapshot.evidenceDigest()))
        .isEqualTo(snapshot);

    TransactionTemplate readOnly = new TransactionTemplate(fixture.transactionManager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () -> readOnly.execute(status -> fixture.repository.capture(NAMESPACE, TENANT_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("read-write owner transaction");

    TransactionTemplate repeatableRead = new TransactionTemplate(fixture.transactionManager);
    repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(
            () ->
                repeatableRead.execute(status -> fixture.repository.capture(NAMESPACE, TENANT_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ COMMITTED");

    GameSessionRetainedTenantSnapshotRepository autocommitRepository =
        new GameSessionRetainedTenantSnapshotRepository(fixture.autocommitDsl);
    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status -> autocommitRepository.capture(NAMESPACE, TENANT_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DSL connection to join the owner transaction");

    fixture.dsl.execute(
        "INSERT INTO gameplay_admission_pointer_identity_backfill_issue "
            + "(pointer_id, tenant_id, world_slug, realm_slug, prior_state_scope, issue_code) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        400L,
        TENANT,
        "affected-world",
        "affected-realm",
        "UNKNOWN",
        "TEST_UNRESOLVED");
    assertThatThrownBy(fixture::capture)
        .isInstanceOf(
            GameSessionRetainedTenantSnapshotRepository.InvalidRetainedTenantSnapshotException
                .class)
        .hasMessageContaining("backfill issue");
  }

  @Test
  void captureUsesNowaitToRejectAnExistingWriterThenSucceedsAfterRollback() throws Exception {
    Fixture fixture = fixture();
    fixture.seedProjection();
    CountDownLatch writerReady = new CountDownLatch(1);
    CountDownLatch finishWriter = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> writer =
          executor.submit(
              () ->
                  fixture.transactions.execute(
                      status -> {
                        fixture.dsl.execute(
                            "UPDATE game_instances SET runtime_version = ? WHERE id = ?",
                            "uncommitted-writer",
                            10L);
                        writerReady.countDown();
                        await(finishWriter);
                        status.setRollbackOnly();
                        return null;
                      }));
      assertThat(writerReady.await(10, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(fixture::capture).isInstanceOf(DataAccessException.class);
      finishWriter.countDown();
      writer.get(10, TimeUnit.SECONDS);
      assertThat(fixture.capture().canonicalJson()).contains("Café 🐉");
    } finally {
      finishWriter.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void
      shareFenceBlocksConcurrentInsertUpdateDeleteAcrossEverySourceFamilyAndReleasesAfterCommitOrRollback()
          throws Exception {
    Fixture fixture = fixture();
    fixture.seedProjection();
    GameSessionRetainedTenantSnapshot originalSnapshot = fixture.capture();

    fixture.transactions.execute(
        status -> {
          fixture.repository.capture(NAMESPACE, TENANT_ID);
          for (MutationCase mutation : mutationCases()) {
            fixture.assertMutationBlocked(mutation.insertSql());
            fixture.assertMutationBlocked(mutation.updateSql());
            fixture.assertMutationBlocked(mutation.deleteSql());
          }
          return null;
        });

    fixture.assertMutationMatrixSucceedsThenRollsBack();
    assertThat(fixture.capture().evidenceDigest()).isEqualTo(originalSnapshot.evidenceDigest());
    fixture.executeMutation(COMMITTED_INSTANCE_PHANTOM_INSERT);
    Long instancePhantomCount =
        java.util.Objects.requireNonNull(
                fixture.dsl.fetchOne(
                    "SELECT count(*) FROM game_instances WHERE id = 901 AND tenant_id = ?", TENANT))
            .get(0, Long.class);
    assertThat(instancePhantomCount).isEqualTo(1L);
    GameSessionRetainedTenantSnapshot afterInstanceInsert = fixture.capture();
    assertThat(afterInstanceInsert.evidenceDigest())
        .isNotEqualTo(originalSnapshot.evidenceDigest());

    fixture.transactions.execute(
        status -> {
          fixture.repository.capture(NAMESPACE, TENANT_ID);
          status.setRollbackOnly();
          return null;
        });
    fixture.assertMutationMatrixSucceedsThenRollsBack();
    assertThat(fixture.capture().evidenceDigest()).isEqualTo(afterInstanceInsert.evidenceDigest());
    fixture.executeMutation(COMMITTED_POINTER_PHANTOM_INSERT);
    Long pointerPhantomCount =
        java.util.Objects.requireNonNull(
                fixture.dsl.fetchOne(
                    "SELECT count(*) FROM gameplay_admission_pointer WHERE id = 902 AND tenant_id = ? "
                        + "AND game_instance_id = 901",
                    TENANT))
            .get(0, Long.class);
    assertThat(pointerPhantomCount).isEqualTo(1L);
    GameSessionRetainedTenantSnapshot afterPointerInsert = fixture.capture();
    assertThat(afterPointerInsert.evidenceDigest())
        .isNotEqualTo(afterInstanceInsert.evidenceDigest());
  }

  private Fixture fixture() {
    String schema = "gs_retained_snapshot_" + UUID.randomUUID().toString().replace("-", "");
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
    DSLContext autocommitDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    return new Fixture(
        dataSource,
        transactionManager,
        transactions,
        dsl,
        autocommitDsl,
        new GameSessionRetainedTenantSnapshotRepository(dsl));
  }

  private static List<String> textValues(JsonNode rows, String field) {
    java.util.ArrayList<String> values = new java.util.ArrayList<>();
    for (JsonNode row : rows) {
      values.add(row.get(field).textValue());
    }
    return values;
  }

  private static List<MutationCase> mutationCases() {
    return List.of(
        new MutationCase(
            "game_instances",
            "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_id, status, row_version) "
                + "VALUES (911, "
                + TENANT
                + ", 'matrix-phantom', 90123, 'STOPPED', 0)",
            "UPDATE game_instances SET runtime_version = 'matrix-updated' WHERE id = 10 AND tenant_id = "
                + TENANT,
            "DELETE FROM game_instances WHERE id = 10 AND tenant_id = " + TENANT),
        new MutationCase(
            "gameplay_admission_pointer",
            "INSERT INTO gameplay_admission_pointer (id, world_slug, world_display_name, realm_slug, "
                + "realm_display_name, tenant_id, game_instance_id, pointer_version, visible, "
                + "requires_character_selection, state_scope, character_creation_policy, "
                + "last_updated_by, last_update_reason, public_production_realm, realm_id, "
                + "playable_state_namespace_id) VALUES (912, 'matrix-world', 'Matrix', "
                + "'matrix-realm', 'Matrix realm', "
                + TENANT
                + ", 20, 1, false, false, "
                + "'ISOLATED', 'DISABLED', 'test', 'fence test', false, "
                + "'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', "
                + "'ffffffff-ffff-4fff-8fff-ffffffffffff')",
            "UPDATE gameplay_admission_pointer SET last_update_reason = 'matrix-updated' "
                + "WHERE id = 10 AND tenant_id = "
                + TENANT,
            "DELETE FROM gameplay_admission_pointer WHERE id = 10 AND tenant_id = " + TENANT),
        new MutationCase(
            "gameplay_tenant_shared_playable_state_namespace",
            "INSERT INTO gameplay_tenant_shared_playable_state_namespace "
                + "(tenant_id, playable_state_namespace_id) VALUES ("
                + OTHER_TENANT
                + ", '11111111-2222-4333-8444-555555555555')",
            "UPDATE gameplay_tenant_shared_playable_state_namespace SET "
                + "playable_state_namespace_id = '22222222-3333-4444-8555-666666666666' "
                + "WHERE tenant_id = "
                + TENANT,
            "DELETE FROM gameplay_tenant_shared_playable_state_namespace WHERE tenant_id = "
                + TENANT),
        new MutationCase(
            "gameplay_admission_pointer_identity_backfill_issue",
            "INSERT INTO gameplay_admission_pointer_identity_backfill_issue "
                + "(pointer_id, tenant_id, world_slug, realm_slug, prior_state_scope, issue_code) "
                + "VALUES (903, "
                + OTHER_TENANT
                + ", 'phantom-world', 'phantom-realm', 'UNKNOWN', 'TEST')",
            "UPDATE gameplay_admission_pointer_identity_backfill_issue SET issue_code = 'MATRIX_UPDATED' "
                + "WHERE pointer_id = 399 AND tenant_id = "
                + OTHER_TENANT,
            "DELETE FROM gameplay_admission_pointer_identity_backfill_issue WHERE pointer_id = 399 "
                + "AND tenant_id = "
                + OTHER_TENANT),
        new MutationCase(
            "gameplay_admission_pointer_event",
            "INSERT INTO gameplay_admission_pointer_event (id, world_slug, realm_slug, "
                + "world_display_name, realm_display_name, tenant_id, game_instance_id, pointer_version, "
                + "visible, requires_character_selection, state_scope, character_creation_policy, "
                + "actor_principal, reason, control_plane_request_id, public_production_realm) "
                + "VALUES (904, 'phantom-world', 'phantom-realm', 'Phantom', 'Phantom realm', "
                + OTHER_TENANT
                + ", 21, 1, false, false, 'ISOLATED', 'DISABLED', 'test', "
                + "'fence test', 'request-phantom-event', false)",
            "UPDATE gameplay_admission_pointer_event SET reason = 'matrix-updated' "
                + "WHERE id = 10 AND tenant_id = "
                + TENANT,
            "DELETE FROM gameplay_admission_pointer_event WHERE id = 10 AND tenant_id = " + TENANT),
        new MutationCase(
            "prepared_version_upgrade",
            "INSERT INTO prepared_version_upgrade (id, preparation_id, control_plane_request_id, "
                + "tenant_id, source_game_instance_id, source_version_id, target_version_id, "
                + "target_launch_descriptor_id, result, reasons_json, checked_participants_json, "
                + "participant_results_json, checked_at) VALUES (905, 'phantom-preparation', "
                + "'phantom-request', "
                + OTHER_TENANT
                + ", 21, 1, 2, 'phantom-launch', 'CHECKED', "
                + "'[]', '[]', '[]', CURRENT_TIMESTAMP)",
            "UPDATE prepared_version_upgrade SET result = 'MATRIX_UPDATED' WHERE id = 3 AND tenant_id = "
                + TENANT,
            "DELETE FROM prepared_version_upgrade WHERE id = 3 AND tenant_id = " + TENANT));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for retained-tenant writer");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for retained-tenant writer", exception);
    }
  }

  private record MutationCase(String table, String insertSql, String updateSql, String deleteSql) {}

  private record Fixture(
      DriverManagerDataSource dataSource,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      DSLContext dsl,
      DSLContext autocommitDsl,
      GameSessionRetainedTenantSnapshotRepository repository) {
    GameSessionRetainedTenantSnapshot capture() {
      GameSessionRetainedTenantSnapshot snapshot =
          transactions.execute(status -> repository.capture(NAMESPACE, TENANT_ID));
      return java.util.Objects.requireNonNull(snapshot);
    }

    void seedProjection() {
      dsl.execute(
          "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_id, status, row_version) "
              + "VALUES (10, ?, 'Café 🐉', 90010, 'STOPPED', ?), "
              + "(2, ?, 'runtime-two', 90002, 'STOPPED', 0)",
          TENANT,
          Long.MAX_VALUE,
          TENANT);
      dsl.execute(
          "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_id, status, row_version) "
              + "VALUES (20, ?, 'matrix-pointer-target', 90020, 'STOPPED', 0), "
              + "(21, ?, 'other-tenant-history-target', 90021, 'STOPPED', 0)",
          TENANT,
          OTHER_TENANT);
      dsl.execute(
          "INSERT INTO gameplay_tenant_shared_playable_state_namespace "
              + "(tenant_id, playable_state_namespace_id) VALUES (?, ?)",
          TENANT,
          SHARED_NAMESPACE);
      dsl.execute(
          "INSERT INTO gameplay_admission_pointer (id, world_slug, world_display_name, realm_slug, "
              + "realm_display_name, tenant_id, game_instance_id, pointer_version, visible, "
              + "requires_character_selection, state_scope, character_creation_policy, last_updated_by, "
              + "last_update_reason, public_production_realm, realm_id, playable_state_namespace_id, "
              + "catalog_revision) VALUES (10, 'north-star', 'Café World', 'quiet-bay', 'Quiet Bay', "
              + "?, 10, ?, true, true, 'ISOLATED', 'DISABLED', 'seed', 'projection fixture', false, "
              + "?, ?, ?), (2, 'north-star', 'Café World', 'shared-square', 'Shared Square', ?, 2, 1, "
              + "true, false, 'SHARED', 'DISABLED', 'seed', 'projection fixture', true, ?, ?, 1)",
          TENANT,
          Long.MAX_VALUE,
          REALM_ISOLATED,
          ISOLATED_NAMESPACE,
          Long.MAX_VALUE,
          TENANT,
          REALM_SHARED,
          SHARED_NAMESPACE);
      dsl.execute(
          "INSERT INTO gameplay_admission_pointer_event (id, world_slug, realm_slug, world_display_name, "
              + "realm_display_name, tenant_id, game_instance_id, pointer_version, visible, "
              + "requires_character_selection, state_scope, character_creation_policy, actor_principal, "
              + "reason, control_plane_request_id, prepared_version_upgrade_id, public_production_realm) "
              + "VALUES (10, 'old-world', 'old-realm', 'Old world', 'Old realm', ?, 888888, ?, false, "
              + "false, 'ISOLATED', 'DISABLED', 'historical-operator', 'retained history', 'event-ten', "
              + "'missing-upgrade', false), (2, 'old-world', 'old-realm-two', 'Old world', 'Old realm', "
              + "?, 777777, 1, true, false, 'SHARED', 'DISABLED', 'historical-operator', "
              + "'retained history', 'event-two', null, true)",
          TENANT,
          Long.MAX_VALUE,
          TENANT);
      dsl.execute(
          "INSERT INTO prepared_version_upgrade (id, preparation_id, control_plane_request_id, tenant_id, "
              + "source_game_instance_id, source_version_id, target_version_id, target_launch_descriptor_id, "
              + "remap_set_id, result, reasons_json, checked_participants_json, participant_results_json, "
              + "checked_at, executed_target_game_instance_id, executed_pointer_version, "
              + "execution_control_plane_request_id) VALUES (3, 'preparation-three', 'upgrade-request-three', "
              + "?, 888888, 99, 100, 'launch-descriptor', null, 'CHECKED', '[]', '[]', '[]', "
              + "CURRENT_TIMESTAMP, 999999, ?, 'execution-request-three')",
          TENANT,
          Long.MAX_VALUE);
      dsl.execute(
          "INSERT INTO gameplay_admission_pointer_identity_backfill_issue "
              + "(pointer_id, tenant_id, world_slug, realm_slug, prior_state_scope, issue_code) "
              + "VALUES (399, ?, 'other-tenant-world', 'other-tenant-realm', 'UNKNOWN', 'TEST_FIXTURE')",
          OTHER_TENANT);
    }

    void assertMutationBlocked(String sql) {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
          statement.execute("SET LOCAL lock_timeout = '200ms'");
          statement.execute(sql);
          connection.commit();
          throw new AssertionError(
              "Mutation unexpectedly passed the retained-tenant fence: " + sql);
        } catch (SQLException exception) {
          connection.rollback();
          assertThat(exception.getSQLState())
              .as("mutation should time out on its relation lock: %s", sql)
              .isEqualTo("55P03");
        }
      } catch (SQLException exception) {
        throw new IllegalStateException("Unable to test retained-tenant relation fence", exception);
      }
    }

    void assertMutationMatrixSucceedsThenRollsBack() {
      for (MutationCase mutation : mutationCases()) {
        assertMutationSucceedsThenRollsBack(mutation.insertSql());
        assertMutationSucceedsThenRollsBack(mutation.updateSql());
        assertMutationSucceedsThenRollsBack(mutation.deleteSql());
      }
    }

    private void assertMutationSucceedsThenRollsBack(String sql) {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try {
          try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '200ms'");
            int affectedRows = statement.executeUpdate(sql);
            assertThat(affectedRows)
                .as("mutation must change an existing or newly inserted row: %s", sql)
                .isPositive();
          }
        } finally {
          connection.rollback();
        }
      } catch (SQLException exception) {
        throw new IllegalStateException(
            "Mutation did not affect a row after retained-tenant transaction", exception);
      }
    }

    void executeMutation(String sql) {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        statement.execute("SET LOCAL lock_timeout = '200ms'");
        statement.execute(sql);
        connection.commit();
      } catch (SQLException exception) {
        throw new IllegalStateException(
            "Mutation remained blocked after retained-tenant transaction", exception);
      }
    }
  }
}
