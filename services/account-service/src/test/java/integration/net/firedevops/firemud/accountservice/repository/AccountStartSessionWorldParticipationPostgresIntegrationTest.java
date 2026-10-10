package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL contract proof for the additive Account-owned World participation migration. */
@Testcontainers(disabledWithoutDocker = true)
class AccountStartSessionWorldParticipationPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void latestMigrationsInstallParticipationGuardsAndPreserveAssembledSourceExclusions() {
    String schema = "ss_world_part_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(source, SQLDialect.POSTGRES);

    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM unnest(ARRAY["
                            + "'account_start_session_world_participations', "
                            + "'account_start_session_world_participation_sources', "
                            + "'account_start_session_world_participation_settlements']::TEXT[]) "
                            + "AS names(name) WHERE to_regclass(name) IS NOT NULL"),
                    "participation table inventory query must return a row")
                .get(0, Long.class))
        .isEqualTo(3L);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT attidentity FROM pg_attribute "
                            + "WHERE attrelid = 'account_start_session_world_participations'::regclass "
                            + "AND attname = 'participation_fence' AND NOT attisdropped"),
                    "participation fence column must exist")
                .get(0, String.class))
        .isEqualTo("a");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND conrelid IN ("
                            + "'account_start_session_world_participations'::regclass, "
                            + "'account_start_session_world_participation_sources'::regclass, "
                            + "'account_start_session_world_participation_settlements'::regclass)"),
                    "participation foreign-key query must return a row")
                .get(0, Long.class))
        .isEqualTo(6L);

    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal "
                            + "AND tgname = 'account_start_session_world_participation_complete' "
                            + "AND tgconstraint <> 0 AND tgdeferrable AND tginitdeferred"),
                    "deferred participation completion trigger query must return a row")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ("
                            + "'account_start_session_world_participation_validate', "
                            + "'account_start_session_world_participation_immutable', "
                            + "'account_start_session_world_participation_no_truncate', "
                            + "'account_start_session_world_participation_sources_insert', "
                            + "'account_start_session_world_participation_sources_immutable', "
                            + "'account_start_session_world_participation_sources_no_truncate', "
                            + "'account_start_session_world_participation_complete', "
                            + "'account_start_session_world_participation_settlement_validate', "
                            + "'account_start_session_world_participation_settlements_immutable', "
                            + "'account_start_session_world_settlement_no_truncate')"),
                    "participation protection trigger query must return a row")
                .get(0, Long.class))
        .isEqualTo(10L);

    String holdGuard = functionDefinition(dsl, "account_control_ui_hold_required_sources(text[])");
    assertThat(holdGuard)
        .contains(
            "account_draft_authorization_sources",
            "account_draft_authorization_is_settled(operation_value)",
            "account_selected_publication_sources",
            "account_selected_publication_authorizations",
            "account_game_logic_intake_sources",
            "account_game_logic_intake_authorizations",
            "account_game_logic_intake_source_read_sources",
            "account_game_logic_intake_source_read_is_pending(s.operation_id)",
            "account_start_session_world_participations participation",
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "FOR UPDATE OF participation NOWAIT");
    assertThat(holdGuard.indexOf("FOR UPDATE NOWAIT"))
        .isLessThan(holdGuard.indexOf("FROM account_start_session_world_participations"));

    String sourceChangeGuard =
        functionDefinition(dsl, "account_draft_authorization_source_change_guard()");
    assertThat(sourceChangeGuard)
        .contains(
            "account_draft_authorization_changed_scopes",
            "account_draft_authorization_is_settled(operation)",
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "Pending StartSession World participation excludes source-change completion");

    String disclosureGuard =
        functionDefinition(dsl, "account_hosted_terms_disclosure_handoff_guard()");
    assertThat(disclosureGuard)
        .contains(
            "OLD.status IN ('PREPARED', 'AMBIGUOUS')",
            "NEW.status = 'DISPATCH_AUTHORIZED'",
            "OLD.status = 'DISPATCH_AUTHORIZED' AND NEW.status = 'AMBIGUOUS'",
            "account_hosted_terms_disclosure_sources",
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "Pending StartSession World participation excludes hosted-terms dispatch");

    String acquisitionExclusion =
        functionDefinition(
            dsl, "account_start_session_world_participation_assert_exclusive(text[],jsonb)");
    assertThat(acquisitionExclusion)
        .contains(
            "change.status = 'WAITING'",
            "source_key_value LIKE 'HOSTED_TERMS:%'",
            "handoff.source_key = source_key_value",
            "handoff_source.source_key = handoff.source_key",
            "handoff_source.source_evidence = evidence_value",
            "handoff.status IN ('DISPATCH_AUTHORIZED', 'AMBIGUOUS', 'DISCLOSED')");
    assertThat(acquisitionExclusion).doesNotContain("handoff.source_key = ANY(source_keys)");

    for (String table :
        new String[] {
          "account_start_session_world_participations",
          "account_start_session_world_participation_sources",
          "account_start_session_world_participation_settlements"
        }) {
      assertThatThrownBy(() -> dsl.execute("TRUNCATE " + table))
          .as("TRUNCATE must be rejected for empty append-only table %s", table)
          .isInstanceOf(RuntimeException.class);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne("SELECT count(*) FROM " + table),
                      "post-TRUNCATE table state query must return a row")
                  .get(0, Long.class))
          .as("rejected TRUNCATE must leave the empty table unchanged")
          .isZero();
    }
  }

  private static String functionDefinition(DSLContext dsl, String signature) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT pg_get_functiondef(?::regprocedure)", signature),
            "function definition query must return a row")
        .get(0, String.class);
  }
}
