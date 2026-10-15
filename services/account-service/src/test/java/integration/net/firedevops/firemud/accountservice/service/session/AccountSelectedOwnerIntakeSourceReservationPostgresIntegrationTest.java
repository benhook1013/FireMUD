package integration.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** PostgreSQL installation proof for V1007's additive selected-owner source reservations. */
class AccountSelectedOwnerIntakeSourceReservationPostgresIntegrationTest {
  @Test
  void installsV1007OverCombinedV1006ParentGuardsAndRejectsMalformedReservationWithoutSourceRows() {
    var fixture = new AccountPostgresIntegrationFixture();
    try {
      fixture.start();
      String schema = "selected_owner_source_" + UUID.randomUUID().toString().replace("-", "");
      JdbcTemplate admin = new JdbcTemplate(fixture.dataSource());
      try {
        admin.execute("CREATE SCHEMA " + quotedSchema(schema));
        DriverManagerDataSource source = fixture.dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);

        migration(source, schema, "1006").migrate();
        assertVersionApplied(jdbc, schema, "1006");
        for (String version : List.of("137", "159", "160.1", "1000", "1001", "1002", "1003", "1004", "1004.1", "1004.2", "1004.3", "1005")) {
          assertVersionApplied(jdbc, schema, version);
        }
        assertVersionNotApplied(jdbc, schema, "1007");
        var ownerDsl = org.jooq.impl.DSL.using(
            new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(source),
            org.jooq.SQLDialect.POSTGRES);
        var ownerTransaction = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        ownerTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        var account = new net.firedevops.firemud.accountservice.entity.Account();
        account.setUsername("retained-parent-" + UUID.randomUUID());
        account.setEmail(UUID.randomUUID() + "@example.test");
        account.setPasswordHash("TEST_ONLY unused password verifier");
        account.setRole("player");
        var accounts = new net.firedevops.firemud.accountservice.repository.AccountRepository(ownerDsl);
        assertThat(ownerTransaction.execute(status -> accounts.save(account))).isNotNull();
        seedRetainedCombinedParentRows(jdbc);
        var retainedParent = retainedParentRows(jdbc);
        assertThat(retainedParent.values()).allSatisfy(rows -> assertThat(rows).isNotEmpty());
        String historyQuery =
            "SELECT jsonb_build_object('row', to_jsonb(h), 'xmin', h.xmin::TEXT)::TEXT "
                + "FROM flyway_schema_history h WHERE version IS NULL OR version <> '1007' "
                + "ORDER BY installed_rank";
        var retainedHistory = jdbc.queryForList(historyQuery, String.class);
        var protectionsGuard = functionDefinition(jdbc, "account_ss_admission_assert_no_pending(text[])");
        var disposalGuard = functionDefinition(jdbc, "account_initial_gameplay_login_disposal_immutable()");
        var disposalWriterGuard = functionDefinition(jdbc, "account_initial_gameplay_login_writer_guard()");
        var alignmentGuard = functionDefinition(jdbc, "account_ss_activation_settlement_insert_guard()");
        assertThat(alignmentGuard).contains(
            "terminal->>'activationResultSha256' IS DISTINCT FROM 'sha256:' || encode(sha256(result_bytes), 'hex')");
        assertThat(protectionsGuard).contains("account_start_session_admission_protections", "account_start_session_activation_protections");
        assertWorldSourceExclusionGuards(
            functionDefinition(jdbc, "account_control_ui_hold_required_sources(text[])"),
            functionDefinition(jdbc, "account_draft_authorization_source_change_guard()"),
            functionDefinition(jdbc, "account_hosted_terms_disclosure_handoff_guard()"));

        Flyway upgrade = migration(source, schema, "1007");
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertVersionApplied(jdbc, schema, "1006");
        assertVersionApplied(jdbc, schema, "1007");
        assertThat(jdbc.queryForList(historyQuery, String.class)).isEqualTo(retainedHistory);
        assertThat(retainedParentRows(jdbc)).isEqualTo(retainedParent);
        assertThat(functionDefinition(jdbc, "account_ss_admission_assert_no_pending(text[])"))
            .isEqualTo(protectionsGuard);
        assertThat(functionDefinition(jdbc, "account_initial_gameplay_login_disposal_immutable()"))
            .isEqualTo(disposalGuard);
        assertThat(functionDefinition(jdbc, "account_initial_gameplay_login_writer_guard()"))
            .isEqualTo(disposalWriterGuard);
        assertThat(functionDefinition(jdbc, "account_ss_activation_settlement_insert_guard()"))
            .isEqualTo(alignmentGuard);
        assertInstalledOwnerReservationSchema(jdbc, schema);

        String holdGuard =
            functionDefinition(jdbc, "account_control_ui_hold_required_sources(text[])");
        String sourceChangeGuard =
            functionDefinition(jdbc, "account_draft_authorization_source_change_guard()");
        String disclosureGuard =
            functionDefinition(jdbc, "account_hosted_terms_disclosure_handoff_guard()");
        assertWorldSourceExclusionGuards(holdGuard, sourceChangeGuard, disclosureGuard);
        assertThat(holdGuard)
            .contains(
                "account_selected_owner_intake_source_read_sources source",
                "account_selected_owner_intake_source_read_is_pending(source.operation_id)",
                "Selected owner intake source read remains pending for a required source");
        assertThat(sourceChangeGuard)
            .contains(
                "account_selected_owner_intake_source_read_sources source",
                "Pending selected owner intake source read excludes source-change completion");
        assertThat(disclosureGuard)
            .contains(
                "account_selected_owner_intake_source_read_sources source",
                "Pending selected owner intake source read excludes hosted-terms dispatch");

        assertMalformedReservationCreatesNoSourceRows(jdbc);
        assertThat(retainedParentRows(jdbc)).isEqualTo(retainedParent);
      } finally {
        admin.execute("DROP SCHEMA IF EXISTS " + quotedSchema(schema) + " CASCADE");
      }
    } finally {
      fixture.stop();
    }
  }

  private static Flyway migration(
      DriverManagerDataSource source, String schema, String targetVersion) {
    return Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .target(MigrationVersion.fromVersion(targetVersion))
        .load();
  }

  private static void seedRetainedCombinedParentRows(JdbcTemplate jdbc) {
    Long sagaId = jdbc.queryForObject(
        "INSERT INTO saga_instance(saga_name, state) VALUES (?, ?) RETURNING id",
        Long.class, "retained-selected-source-upgrade", "PENDING");
    jdbc.update(
        "INSERT INTO saga_step(instance_id, name, status, attempt) VALUES (?, ?, ?, ?)",
        sagaId, "retained-step", "PENDING", 3);
    jdbc.update(
        "INSERT INTO account_initial_gameplay_login_disposals(request_id, disposal_operation_id, "
            + "caller_workload, caller_context_id, disposal_digest, witness_canonical_bytes) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(), UUID.randomUUID(),
        "spiffe://firemud/ns/test/sa/game-session-service", UUID.randomUUID(),
        "a".repeat(64), "retained parent disposal witness".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** Exact parent Account/source, disposal and Saga rows; no credential authority implied. */
  private static Map<String, List<String>> retainedParentRows(JdbcTemplate jdbc) {
    Map<String, List<String>> rows = new java.util.LinkedHashMap<>();
    for (String table : List.of("accounts", "account_authority_source_records", "account_initial_gameplay_login_disposals", "saga_instance", "saga_step")) {
      rows.put(table, jdbc.queryForList("SELECT jsonb_build_object('row', to_jsonb(t), 'xmin', t.xmin::TEXT)::TEXT FROM "
          + table + " t ORDER BY to_jsonb(t)::TEXT", String.class));
    }
    return rows;
  }

  private static void assertInstalledOwnerReservationSchema(JdbcTemplate jdbc, String schema) {
    assertThat(
            Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM unnest(ARRAY["
                        + "'account_selected_owner_intake_source_read_reservations', "
                        + "'account_selected_owner_intake_source_read_sources', "
                        + "'account_selected_owner_intake_source_read_aborts']::TEXT[]) "
                        + "AS names(name) WHERE to_regclass(name) IS NOT NULL",
                    Long.class)))
        .isEqualTo(3L);

    assertThat(
            Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_trigger trigger_row "
                        + "JOIN pg_class relation ON relation.oid = trigger_row.tgrelid "
                        + "JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace "
                        + "WHERE namespace.nspname = ? AND NOT trigger_row.tgisinternal "
                        + "AND trigger_row.tgname IN ("
                        + "'account_selected_owner_intake_source_read_complete', "
                        + "'account_selected_owner_intake_source_read_source_validate', "
                        + "'account_selected_owner_intake_source_read_abort_validate', "
                        + "'account_selected_owner_intake_source_read_immutable', "
                        + "'account_selected_owner_intake_source_read_no_truncate', "
                        + "'account_selected_owner_intake_source_read_sources_immutable', "
                        + "'account_selected_owner_intake_source_read_sources_no_truncate', "
                        + "'account_selected_owner_intake_source_read_aborts_immutable', "
                        + "'account_selected_owner_intake_source_read_aborts_no_truncate')",
                    Long.class,
                    schema)))
        .isEqualTo(9L);

    assertThat(
            Objects.requireNonNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_proc p "
                        + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                        + "WHERE n.nspname = ? AND p.proname IN ("
                        + "'account_selected_owner_intake_source_read_is_pending', "
                        + "'account_selected_owner_intake_source_read_complete_guard', "
                        + "'account_selected_owner_intake_source_read_source_insert_guard', "
                        + "'account_selected_owner_intake_source_read_abort_guard')",
                    Long.class,
                    schema)))
        .isEqualTo(4L);
  }

  private static void assertMalformedReservationCreatesNoSourceRows(JdbcTemplate jdbc) {
    UUID operationId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO account_selected_owner_intake_source_read_reservations ("
                        + "operation_id, fence_id, intake_request_id, owner, actor_account_uuid, "
                        + "tenant_uuid, version_uuid, scope_bytes, scope_digest, "
                        + "issuance_operation_id, issuance_fence, source_payload, issuance_bundle, "
                        + "outbox_checkpoints) VALUES (?, ?, ?, 'UNDECLARED_OWNER', ?, ?, ?, "
                        + "decode('00', 'hex'), 'sha256:' || repeat('0', 64), ?, 1, "
                        + "decode('7b7d', 'hex'), decode('7b7d', 'hex'), decode('7b7d', 'hex'))",
                    operationId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(RuntimeException.class);

    assertThat(count(jdbc, "account_selected_owner_intake_source_read_reservations")).isZero();
    assertThat(count(jdbc, "account_selected_owner_intake_source_read_sources")).isZero();
    assertThat(count(jdbc, "account_selected_owner_intake_source_read_aborts")).isZero();
  }

  private static void assertWorldSourceExclusionGuards(
      String holdGuard, String sourceChangeGuard, String disclosureGuard) {
    assertThat(holdGuard)
        .contains(
            "account_ss_admission_assert_no_pending(source_keys)",
            "account_start_session_world_participations participation",
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "FOR UPDATE OF participation NOWAIT");
    assertThat(sourceChangeGuard)
        .contains(
            "account_ss_admission_assert_no_pending(",
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "Pending StartSession World participation excludes source-change completion");
    assertThat(disclosureGuard)
        .contains(
            "account_ss_admission_assert_no_pending(ARRAY(",
            "OLD.status IN ('PREPARED', 'AMBIGUOUS')",
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "Pending StartSession World participation excludes hosted-terms dispatch");
  }

  private static String functionDefinition(JdbcTemplate jdbc, String signature) {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT pg_get_functiondef(?::regprocedure)", String.class, signature));
  }

  private static int count(JdbcTemplate jdbc, String table) {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
  }

  private static void assertVersionApplied(JdbcTemplate jdbc, String schema, String version) {
    assertThat(historyCount(jdbc, schema, version)).isEqualTo(1);
  }

  private static void assertVersionNotApplied(JdbcTemplate jdbc, String schema, String version) {
    assertThat(historyCount(jdbc, schema, version)).isZero();
  }

  private static int historyCount(JdbcTemplate jdbc, String schema, String version) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM "
                + quotedSchema(schema)
                + ".flyway_schema_history "
                + "WHERE version = ? AND success",
            Integer.class,
            version));
  }

  private static String quotedSchema(String schema) {
    return "\"" + schema + "\"";
  }
}
