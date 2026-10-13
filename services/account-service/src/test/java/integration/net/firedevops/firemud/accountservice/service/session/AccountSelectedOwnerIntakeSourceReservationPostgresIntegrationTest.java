package integration.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** PostgreSQL installation proof for V130's additive selected-owner source reservations. */
class AccountSelectedOwnerIntakeSourceReservationPostgresIntegrationTest {
  @Test
  void installsV130OverV129WorldGuardsAndRejectsMalformedReservationWithoutSourceRows() {
    var fixture = new AccountPostgresIntegrationFixture();
    try {
      fixture.start();
      String schema = "selected_owner_source_" + UUID.randomUUID().toString().replace("-", "");
      JdbcTemplate admin = new JdbcTemplate(fixture.dataSource());
      try {
        admin.execute("CREATE SCHEMA " + quotedSchema(schema));
        DriverManagerDataSource source = fixture.dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);

        migration(source, schema, "129").migrate();
        assertVersionApplied(jdbc, schema, "129");
        assertVersionNotApplied(jdbc, schema, "130");
        assertWorldSourceExclusionGuards(
            functionDefinition(jdbc, "account_control_ui_hold_required_sources(text[])"),
            functionDefinition(jdbc, "account_draft_authorization_source_change_guard()"),
            functionDefinition(jdbc, "account_hosted_terms_disclosure_handoff_guard()"));

        migration(source, schema, "130").migrate();
        assertVersionApplied(jdbc, schema, "129");
        assertVersionApplied(jdbc, schema, "130");
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
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion(targetVersion))
        .load();
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
            "account_start_session_world_participations participation",
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "FOR UPDATE OF participation NOWAIT");
    assertThat(sourceChangeGuard)
        .contains(
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "Pending StartSession World participation excludes source-change completion");
    assertThat(disclosureGuard)
        .contains(
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
