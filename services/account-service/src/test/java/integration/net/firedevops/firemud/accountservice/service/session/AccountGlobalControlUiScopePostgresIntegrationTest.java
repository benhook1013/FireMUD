package integration.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Storage-only PostgreSQL proof of the Account/tenant issuance discriminator. Controlled issuance
 * bytes exercise the real SQL lifecycle; they are not authenticated credentials or owner authority.
 * Complete positive tenant-consumer workflows require the existing signer/Redis/owner fixtures.
 */
class AccountGlobalControlUiScopePostgresIntegrationTest {
  private static final List<String> UNCHANGED_TENANT_GUARDS =
      List.of(
          "account_game_logic_intake_complete_guard()",
          "account_game_logic_intake_source_read_complete_guard()",
          "account_selected_owner_intake_source_read_complete_guard()",
          "account_selected_owner_intake_authorization_complete_guard()",
          "account_start_session_authority_capture_guard()");

  @Test
  void preservesPopulatedCombinedParentOriginalsAndInstallsClosedImmutableScope() throws Exception {
    var fixture = new AccountPostgresIntegrationFixture();
    try (AutoCloseable fixtureCleanup = fixture::stop) {
      fixture.start();
      String schema = "global_control_scope_" + UUID.randomUUID().toString().replace("-", "");
      var admin = new JdbcTemplate(fixture.dataSource());
      admin.execute("CREATE SCHEMA " + schema);
      try (AutoCloseable schemaCleanup =
          () -> admin.execute("DROP SCHEMA " + schema + " CASCADE")) {
        var source = fixture.dataSource(schema);
        var jdbc = new JdbcTemplate(source);
        migration(source, schema, "1016").migrate();
        assertThat(historyVersionCount(jdbc, "1016")).isEqualTo(1);
        assertThat(historyVersionCount(jdbc, "1002")).isEqualTo(1);
        assertThat(historyVersionCount(jdbc, "1017")).isZero();

        UUID account = account(source);
        UUID tenant = tenant(jdbc);
        UUID prepared = insertPrepared(jdbc, account, tenant, null, false);
        UUID committed = insertPrepared(jdbc, account, tenant, null, false);
        commit(jdbc, committed);
        var originals = retainedRows(jdbc, false);
        assertThat(originals.get("account_control_ui_issuance_operations")).hasSize(2);
        assertThat(originals.get("account_control_ui_response_envelopes")).hasSize(1);
        String historySql =
            "SELECT jsonb_build_object('row', to_jsonb(h), 'xmin', h.xmin::TEXT)::TEXT "
                + "FROM flyway_schema_history h WHERE version IS DISTINCT FROM '1017' "
                + "ORDER BY installed_rank";
        var history = jdbc.queryForList(historySql, String.class);
        var guardDefinitions = new LinkedHashMap<String, String>();
        for (String guard : UNCHANGED_TENANT_GUARDS) {
          guardDefinitions.put(guard, definition(jdbc, guard));
        }
        String originalIssuanceGuard = definition(jdbc, "account_control_ui_issuance_guard()");
        String originalPublicationGuard =
            definition(jdbc, "account_selected_publication_complete_guard()");
        Long originalHeap =
            jdbc.queryForObject(
                "SELECT pg_relation_filenode('account_control_ui_issuance_operations')",
                Long.class);

        var upgrade = migration(source, schema, "1017");
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(retainedRows(jdbc, true)).isEqualTo(originals);
        assertThat(jdbc.queryForList(historySql, String.class)).isEqualTo(history);
        assertThat(
                jdbc.queryForObject(
                    "SELECT pg_relation_filenode('account_control_ui_issuance_operations')",
                    Long.class))
            .isEqualTo(originalHeap);
        assertThat(
                jdbc.queryForList(
                    "SELECT authorization_scope FROM account_control_ui_issuance_operations "
                        + "ORDER BY operation_id",
                    String.class))
            .containsExactly("TENANT", "TENANT");
        assertThat(definition(jdbc, "account_control_ui_issuance_guard()"))
            .isEqualTo(originalIssuanceGuard);
        assertThat(definition(jdbc, "account_selected_publication_complete_guard()"))
            .isEqualTo(
                originalPublicationGuard.replace(
                    "IF issuer.status IS DISTINCT FROM 'COMMITTED'",
                    "IF issuer.authorization_scope IS DISTINCT FROM 'TENANT'\n"
                        + "        OR issuer.status IS DISTINCT FROM 'COMMITTED'"));
        for (var guard : guardDefinitions.entrySet()) {
          assertThat(definition(jdbc, guard.getKey())).isEqualTo(guard.getValue());
        }

        // Existing callers which omit the new column still create TENANT operations.
        UUID defaultTenant = insertPrepared(jdbc, account, tenant, null, false);
        assertThat(scope(jdbc, defaultTenant)).isEqualTo("TENANT");
        UUID accountOnly = insertPrepared(jdbc, account, null, "ACCOUNT", true);
        assertThat(scope(jdbc, accountOnly)).isEqualTo("ACCOUNT");
        commit(jdbc, accountOnly);
        assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM account_control_ui_issuance_operations WHERE operation_id = ?",
                    String.class,
                    accountOnly))
            .isEqualTo("COMMITTED");

        for (String invalid : List.of("UNKNOWN", "account", "")) {
          assertSqlState(() -> insertPrepared(jdbc, account, null, invalid, true), "23514");
        }
        assertSqlState(() -> insertPrepared(jdbc, account, null, null, true), "23502");
        assertSqlState(() -> insertPrepared(jdbc, account, tenant, "ACCOUNT", true), "23514");
        assertSqlState(() -> insertPrepared(jdbc, account, null, "TENANT", true), "23514");
        assertSqlState(() -> insertPrepared(jdbc, account, null, null, false), "23514");
        assertSqlState(
            () -> insertPrepared(jdbc, account, UUID.randomUUID(), "TENANT", true), "23503");
        assertSqlState(
            () -> insertPrepared(jdbc, UUID.randomUUID(), null, "ACCOUNT", true), "23503");

        assertSqlState(
            () ->
                jdbc.update(
                    "UPDATE account_control_ui_issuance_operations SET authorization_scope = 'ACCOUNT', "
                        + "tenant_uuid = NULL, status = 'FAILED', recovery_failed_at = CURRENT_TIMESTAMP "
                        + "WHERE operation_id = ?",
                    prepared),
            "23514");
        UUID accountPrepared = insertPrepared(jdbc, account, null, "ACCOUNT", true);
        assertSqlState(
            () ->
                jdbc.update(
                    "UPDATE account_control_ui_issuance_operations SET authorization_scope = 'TENANT', "
                        + "tenant_uuid = ?, status = 'FAILED', recovery_failed_at = CURRENT_TIMESTAMP "
                        + "WHERE operation_id = ?",
                    tenant,
                    accountPrepared),
            "23514");
        assertThat(scope(jdbc, prepared)).isEqualTo("TENANT");
        assertThat(scope(jdbc, accountPrepared)).isEqualTo("ACCOUNT");
        assertTenantConsumerPredicates(jdbc, schema, accountOnly, tenant);
      }
    }
  }

  /**
   * Real persisted Account row and SQL null predicates, plus unchanged consumer definitions. This
   * deliberately does not claim a positive complete publication/intake/StartSession execution.
   */
  private static void assertTenantConsumerPredicates(
      JdbcTemplate jdbc, String schema, UUID accountOperation, UUID targetTenant) {
    assertThat(
            jdbc.queryForObject(
                "SELECT authorization_scope IS DISTINCT FROM 'TENANT' "
                    + "AND tenant_uuid IS DISTINCT FROM ?::UUID "
                    + "FROM account_control_ui_issuance_operations WHERE operation_id = ?",
                Boolean.class,
                targetTenant,
                accountOperation))
        .isTrue();
    for (String table :
        List.of(
            "account_selected_publication_authorizations",
            "account_game_logic_intake_authorizations",
            "account_game_logic_intake_source_read_reservations",
            "account_selected_owner_intake_source_read_reservations",
            "account_selected_owner_intake_authorizations",
            "account_start_session_authority_captures")) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT is_nullable FROM information_schema.columns "
                      + "WHERE table_schema = ? AND table_name = ? AND column_name = 'tenant_uuid'",
                  String.class,
                  schema,
                  table))
          .isEqualTo("NO");
    }
    for (String guard : UNCHANGED_TENANT_GUARDS) {
      String alias = guard.startsWith("account_start_session_") ? "control_ui" : "issuer";
      assertThat(definition(jdbc, guard))
          .contains(alias + ".tenant_uuid IS DISTINCT FROM NEW.tenant_uuid");
    }
    assertThat(definition(jdbc, "account_selected_publication_complete_guard()"))
        .contains("issuer.authorization_scope IS DISTINCT FROM 'TENANT'");
  }

  private static Flyway migration(DriverManagerDataSource source, String schema, String target) {
    return Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .target(MigrationVersion.fromVersion(target))
        .load();
  }

  private static UUID account(DriverManagerDataSource source) {
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              var account = new Account();
              account.setUsername("scope-" + UUID.randomUUID());
              account.setEmail(UUID.randomUUID() + "@example.test");
              account.setPasswordHash("test-only unused verifier");
              return new AccountRepository(dsl).save(account).getAccountUuid();
            }));
  }

  private static UUID tenant(JdbcTemplate jdbc) {
    UUID tenant = UUID.randomUUID();
    // Existing Account fixture pattern: stipulated external creation, real claim/source triggers.
    jdbc.update(
        "INSERT INTO account_fresh_tenant_identity_associations "
            + "(schema_version, target_namespace, creation_request_id, operation_id, request_digest, "
            + "canonical_tenant_id, source_game_row_id, source_game_tenant_key, provenance_kind, evidence_digest) "
            + "VALUES (1, 'test-control', ?, ?, ?, ?, 1, ?, 'NEW_GAME_ROW', ?)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "sha256:" + "a".repeat(64),
        tenant,
        tenant.toString(),
        "sha256:" + "b".repeat(64));
    return tenant;
  }

  private static UUID insertPrepared(
      JdbcTemplate jdbc, UUID account, UUID tenant, String scope, boolean explicitScope) {
    UUID operation = UUID.randomUUID();
    long issuedAt = Instant.now().getEpochSecond();
    String columns =
        "request_id, operation_id, token_jti, account_uuid, tenant_uuid, caller_workload, "
            + "caller_context_id, request_mac_key_id, request_digest, claims_payload, source_payload, "
            + "bundle_payload, signer_receipt, issued_at_epoch_second, expires_at_epoch_second, "
            + "registry_cleanup_deadline_epoch_millis, recovery_expires_at, status";
    String values = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'PREPARED'";
    var arguments = new java.util.ArrayList<Object>();
    arguments.addAll(
        java.util.Arrays.asList(
            UUID.randomUUID(),
            operation,
            UUID.randomUUID(),
            account,
            tenant,
            "spiffe://test-only/control-ui",
            UUID.randomUUID(),
            "test-storage",
            "c".repeat(64),
            bytes("test-only claims"),
            bytes("test-only sources"),
            bytes("test-only bundle"),
            bytes("test-only signer receipt"),
            issuedAt,
            issuedAt + 300,
            (issuedAt + 600) * 1000,
            OffsetDateTime.ofInstant(Instant.ofEpochSecond(issuedAt + 60), ZoneOffset.UTC)));
    if (explicitScope) {
      columns += ", authorization_scope";
      values += ", ?";
      arguments.add(scope);
    }
    jdbc.update(
        "INSERT INTO account_control_ui_issuance_operations ("
            + columns
            + ") VALUES ("
            + values
            + ")",
        arguments.toArray());
    return operation;
  }

  private static void commit(JdbcTemplate jdbc, UUID operation) {
    jdbc.update(
        "UPDATE account_control_ui_issuance_operations SET status = 'CANDIDATE', token_hash = ?, "
            + "pending_registry = ?, active_registry = ? WHERE operation_id = ?",
        UUID.randomUUID().toString().replace("-", "") + "d".repeat(32),
        bytes("test-only pending registry"),
        bytes("test-only active registry"),
        operation);
    jdbc.update(
        "INSERT INTO account_control_ui_response_envelopes "
            + "(operation_id, encrypted_response, owner_binding, recovery_expires_at) "
            + "SELECT operation_id, ?, ?, recovery_expires_at "
            + "FROM account_control_ui_issuance_operations WHERE operation_id = ?",
        bytes("test-only protected response"),
        bytes("test-only response binding"),
        operation);
    jdbc.update(
        "UPDATE account_control_ui_issuance_operations SET status = 'COMMITTED', pending_receipt = ?, "
            + "committed_at = CURRENT_TIMESTAMP WHERE operation_id = ?",
        bytes("test-only committed receipt"),
        operation);
  }

  private static Map<String, List<String>> retainedRows(JdbcTemplate jdbc, boolean upgraded) {
    var result = new LinkedHashMap<String, List<String>>();
    for (String table :
        List.of(
            "account_control_ui_issuance_operations", "account_control_ui_response_envelopes")) {
      String row =
          upgraded && table.equals("account_control_ui_issuance_operations")
              ? "to_jsonb(t) - 'authorization_scope'"
              : "to_jsonb(t)";
      result.put(
          table,
          jdbc.queryForList(
              "SELECT jsonb_build_object('row', "
                  + row
                  + ", 'xmin', t.xmin::TEXT)::TEXT FROM "
                  + table
                  + " t ORDER BY operation_id",
              String.class));
    }
    return result;
  }

  private static String definition(JdbcTemplate jdbc, String signature) {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT pg_get_functiondef(?::regprocedure)", String.class, signature));
  }

  private static String scope(JdbcTemplate jdbc, UUID operation) {
    return jdbc.queryForObject(
        "SELECT authorization_scope FROM account_control_ui_issuance_operations WHERE operation_id = ?",
        String.class,
        operation);
  }

  private static int historyVersionCount(JdbcTemplate jdbc, String version) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success",
            Integer.class,
            version));
  }

  private static void assertSqlState(Runnable action, String state) {
    assertThatThrownBy(action::run)
        .satisfies(
            failure -> {
              for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof PSQLException postgres) {
                  assertThat(postgres.getSQLState()).isEqualTo(state);
                  return;
                }
              }
              throw new AssertionError("Expected PostgreSQL failure", failure);
            });
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
