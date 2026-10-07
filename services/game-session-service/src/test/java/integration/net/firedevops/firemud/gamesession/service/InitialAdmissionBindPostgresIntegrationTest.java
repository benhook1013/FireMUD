package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Objects;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindAttemptRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindCatalogRepository;
import net.firedevops.firemud.gamesession.service.impl.DatabaseInitialAdmissionBindOwnerService;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
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
class InitialAdmissionBindPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          PostgresBackedServiceTestSupport.postgresImage("postgres:16-alpine"));

  @Test
  void initialAdmissionLedgerCommitsPointerAuditAndAttemptTogetherAndReadbackFailsClosed() {
    DriverManagerDataSource dataSource = dataSource();
    Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    GameInstanceRepository gameInstanceRepository = new GameInstanceRepository(dsl);
    GameInstance gameInstance = gameInstanceRepository.save(gameInstance());
    DatabaseInitialAdmissionBindOwnerService service =
        new DatabaseInitialAdmissionBindOwnerService(
            new InitialAdmissionBindCatalogRepository(dsl),
            new InitialAdmissionBindAttemptRepository(dsl),
            new GameplayAdmissionPointerEventRepository(dsl),
            gameInstanceRepository);

    InitialAdmissionBindCatalogSnapshot catalog =
        transactionTemplate.execute(
            status ->
                toSnapshot(
                    service.registerPublicSharedFixtureCatalog(
                        new InitialAdmissionBindCatalogDescriptor(
                            41L, 810L, "smoke", "Smoke World", "production", "Live Realm", true))));
    var request =
        new InitialAdmissionBindRequest(
            41L,
            "smoke",
            "production",
            "pg-initial-bind-" + gameInstance.getId(),
            "c".repeat(64),
            gameInstance.getId(),
            902L,
            13L);
    var attempt = transactionTemplate.execute(status -> service.beginIntent(request));
    var binding =
        new InitialAdmissionBindHoldBinding(
            "291787b2-ed31-4ae0-9d72-1f872931e7ed",
            "ea29d3c4-bb4c-4ce5-8f81-8fd60d21b472",
            41L,
            catalog.realmId(),
            catalog.namespaceId(),
            "SHARED",
            gameInstance.getId(),
            902L,
            13L,
            request.initialAdmissionRequestId(),
            request.requestDigest(),
            true,
            1L);
    transactionTemplate.execute(status -> service.attachHold(binding));

    dsl.execute(
        "CREATE FUNCTION reject_initial_admission_audit() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.reason = 'initial admission pointer bind' THEN "
            + "RAISE EXCEPTION 'forced initial admission audit failure'; END IF; RETURN NEW; END; $$");
    dsl.execute(
        "CREATE TRIGGER reject_initial_admission_audit BEFORE INSERT ON "
            + "gameplay_admission_pointer_event FOR EACH ROW EXECUTE FUNCTION "
            + "reject_initial_admission_audit()");
    assertThatThrownBy(() -> transactionTemplate.execute(status -> service.commit(binding)))
        .isInstanceOf(RuntimeException.class);
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer_event"))).isZero();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT status FROM gameplay_initial_admission_bind_attempt WHERE attempt_id = ?",
                        attempt.attemptId()),
                    "expected retained initial-admission bind attempt after audit rollback")
                .get("status", String.class))
        .isEqualTo("PENDING");
    dsl.execute("DROP TRIGGER reject_initial_admission_audit ON gameplay_admission_pointer_event");
    dsl.execute("DROP FUNCTION reject_initial_admission_audit()");

    var committed = transactionTemplate.execute(status -> service.commit(binding));
    assertThat(committed.outcome()).isEqualTo(InitialAdmissionBindOwnerProof.Outcome.COMMITTED);
    assertThat(committed.ownerProofId()).isEqualTo(attempt.attemptId().toString());
    assertThat(committed.pointerAuditRequestDigest()).isEqualTo(request.requestDigest());
    assertThat(committed.pointerVersion()).isEqualTo(1L);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT pointer.catalog_revision AS pointer_revision, "
                            + "event.catalog_revision AS event_revision, pointer.realm_id, "
                            + "event.realm_id AS event_realm_id, pointer.playable_state_namespace_id, "
                            + "event.playable_state_namespace_id AS event_namespace_id "
                            + "FROM gameplay_initial_admission_bind_attempt attempt "
                            + "JOIN gameplay_admission_pointer pointer ON pointer.id = attempt.pointer_id "
                            + "JOIN gameplay_admission_pointer_event event ON event.id = attempt.audit_event_id "
                            + "WHERE attempt.attempt_id = ?",
                        attempt.attemptId()),
                    "expected committed initial-admission pointer/audit row")
                .get("pointer_revision", Long.class))
        .isEqualTo(1L);
    var evidence =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT event.catalog_revision AS event_revision, event.realm_id AS event_realm_id, "
                    + "event.playable_state_namespace_id AS event_namespace_id "
                    + "FROM gameplay_initial_admission_bind_attempt attempt "
                    + "JOIN gameplay_admission_pointer_event event ON event.id = attempt.audit_event_id "
                    + "WHERE attempt.attempt_id = ?",
                attempt.attemptId()),
            "expected committed initial-admission audit evidence row");
    assertThat(evidence.get("event_revision", Long.class)).isEqualTo(1L);
    assertThat(evidence.get("event_realm_id", java.util.UUID.class).toString())
        .isEqualTo(catalog.realmId());
    assertThat(evidence.get("event_namespace_id", java.util.UUID.class).toString())
        .isEqualTo(catalog.namespaceId());
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer_event"))).isEqualTo(1);
  }

  private static DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static GameInstance gameInstance() {
    GameInstance gameInstance = new GameInstance();
    gameInstance.setTenantId(41L);
    gameInstance.setRuntimeVersion("smoke-1");
    gameInstance.setOwnerAccountId(1001L);
    gameInstance.setStatus("RUNNING");
    gameInstance.setGameTemplateId(810L);
    gameInstance.setVersionId(902L);
    return gameInstance;
  }

  private static InitialAdmissionBindCatalogSnapshot toSnapshot(
      net.firedevops.firemud.gamesession.entity.InitialAdmissionBindCatalog catalog) {
    return new InitialAdmissionBindCatalogSnapshot(
        catalog.realmId().toString(), catalog.playableStateNamespaceId().toString());
  }

  private record InitialAdmissionBindCatalogSnapshot(String realmId, String namespaceId) {}
}
