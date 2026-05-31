package net.firedevops.firemud.common.saga.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SagaPersistenceRepositoryIntegrationTest {
  private static final String SERVICE_SCHEMA = "saga_contract_test";

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine");

  private static Connection connection;
  private static DSLContext dsl;

  @BeforeAll
  static void setUp() throws Exception {
    POSTGRES.start();
    connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dsl = DSL.using(connection, SQLDialect.POSTGRES);
    dsl.execute("create schema " + SERVICE_SCHEMA);
    dsl.execute(readMigration("V1001__saga_instance_table.sql"));
    dsl.execute(readMigration("V1002__saga_step_table.sql"));
    dsl.execute("set search_path to public");
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (connection != null) {
      connection.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void resetTables() {
    dsl.execute(
        "truncate table "
            + SERVICE_SCHEMA
            + ".saga_step, "
            + SERVICE_SCHEMA
            + ".saga_instance restart identity");
  }

  @Test
  void sagaInstanceRepositoryUsesExplicitServiceSchema() {
    SagaInstanceRepository repository = new SagaInstanceRepository(dsl, SERVICE_SCHEMA);
    SagaInstance entity = new SagaInstance();
    entity.setSagaName("publish");
    entity.setState("RUNNING");
    entity.setCreatedAt(Instant.parse("2026-05-22T00:00:00Z"));
    entity.setUpdatedAt(Instant.parse("2026-05-22T00:00:01Z"));

    SagaInstance saved = repository.save(entity);
    List<SagaInstance> all = repository.findAll();

    assertThat(saved.getId()).isNotNull();
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getSagaName()).isEqualTo("publish");
    Long rowCount =
        dsl.fetchSingle(
                "select count(*) from " + SERVICE_SCHEMA + ".saga_instance where id = ?",
                saved.getId())
            .get(0, Long.class);
    assertThat(rowCount).isEqualTo(1L);
  }

  @Test
  void sagaStepRepositoryUsesExplicitServiceSchema() {
    SagaInstanceRepository instanceRepository = new SagaInstanceRepository(dsl, SERVICE_SCHEMA);
    SagaStepRepository stepRepository = new SagaStepRepository(dsl, SERVICE_SCHEMA);

    SagaInstance instance = new SagaInstance();
    instance.setSagaName("world-create");
    instance.setState("RUNNING");
    instance.setCreatedAt(Instant.parse("2026-05-22T00:01:00Z"));
    instance.setUpdatedAt(Instant.parse("2026-05-22T00:01:01Z"));
    SagaInstance savedInstance = instanceRepository.save(instance);

    SagaStep step = new SagaStep();
    step.setInstanceId(savedInstance.getId());
    step.setName("validate");
    step.setStatus("DONE");
    step.setAttempt(1);
    step.setCreatedAt(Instant.parse("2026-05-22T00:01:02Z"));
    step.setUpdatedAt(Instant.parse("2026-05-22T00:01:03Z"));

    SagaStep savedStep = stepRepository.save(step);
    List<SagaStep> steps = stepRepository.findByInstanceId(savedInstance.getId());

    assertThat(savedStep.getId()).isNotNull();
    assertThat(steps).hasSize(1);
    assertThat(steps.get(0).getName()).isEqualTo("validate");
    Long rowCount =
        dsl.fetchSingle(
                "select count(*) from " + SERVICE_SCHEMA + ".saga_step where id = ?",
                savedStep.getId())
            .get(0, Long.class);
    assertThat(rowCount).isEqualTo(1L);
  }

  private static String readMigration(String filename) throws Exception {
    String path = "/db/migration/saga/" + filename;
    try (var stream = SagaPersistenceRepositoryIntegrationTest.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalStateException("Missing migration resource " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .replace("${serviceSchema}", SERVICE_SCHEMA);
    }
  }
}
