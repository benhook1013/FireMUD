package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof of V128 allocator ownership and capture-table statement protection. */
@Testcontainers(disabledWithoutDocker = true)
class AccountStartSessionAuthorityCapturePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void migrationInstallsIndependentAllocatorsAndRejectsUnownedMutationAndTruncate() {
    String schema = "start_session_capture_" + UUID.randomUUID().toString().replace("-", "");
    var source =
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
                        "SELECT last_source_version FROM account_start_session_capture_source_version_allocator"),
                    "capture sourceVersion allocator row must exist")
                .get(0, Long.class))
        .isZero();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT last_source_fence FROM account_start_session_capture_source_fence_allocator"),
                    "capture sourceFence allocator row must exist")
                .get(0, Long.class))
        .isZero();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal "
                            + "AND tgname IN ('account_start_session_authority_capture_immutable', "
                            + "'account_start_session_authority_capture_no_truncate', "
                            + "'account_start_session_capture_source_version_allocator_guard', "
                            + "'account_start_session_capture_source_fence_allocator_guard')"),
                    "V128 protection trigger query must return a row")
                .get(0, Long.class))
        .isEqualTo(4L);

    var actorId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    var tuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "capture-integration-request",
            actorId,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(
                    UUID.fromString("22222222-2222-4222-8222-222222222222"), "operator-test"),
                new StartSessionOperatorAction.Target(
                    42L, UUID.fromString("33333333-3333-4333-8333-333333333333")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "capture tuple guard regression"));
    String tupleJson = tuple.canonicalJson();
    String tupleMutationDigest = tuple.mutationDigest();
    assertThat(tupleJson).doesNotContain("mutationDigest");
    assertThat(tupleMutationDigest).matches("[0-9a-f]{64}");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne("SELECT (?::jsonb)->>'mutationDigest' AS tuple_digest", tupleJson),
                    "canonical pretuple query must return a row")
                .get("tuple_digest", String.class))
        .isNull();
    String captureGuard =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT pg_get_functiondef('account_start_session_authority_capture_guard()'::regprocedure)"),
                "V128 capture guard function must exist")
            .get(0, String.class);
    assertThat(captureGuard)
        .contains("snapshot->>'mutationDigest' IS DISTINCT FROM NEW.mutation_digest")
        .doesNotContain("tuple->>'mutationDigest'");

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_start_session_capture_source_version_allocator "
                        + "SET last_source_version = last_source_version + 1"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM account_start_session_capture_source_fence_allocator"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> dsl.execute("TRUNCATE account_start_session_authority_captures"))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne("SELECT count(*) FROM account_start_session_authority_captures"),
                    "V128 capture count query must return a row")
                .get(0, Long.class))
        .isZero();
  }
}
