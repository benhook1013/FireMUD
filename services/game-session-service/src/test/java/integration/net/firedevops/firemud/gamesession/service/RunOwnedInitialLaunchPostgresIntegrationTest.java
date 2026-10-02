package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
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
class RunOwnedInitialLaunchPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final long TENANT_ID = 41L;
  private static final String REQUEST_ID = "run-owned-pg-race";
  private static final String REQUEST_DIGEST = "c".repeat(64);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void concurrentExactIdentityAllocationConvergesAndOrdinaryRowsRemainNullable() throws Exception {
    DriverManagerDataSource dataSource = dataSource();
    Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    GameInstanceRepository repository = new GameInstanceRepository(dsl);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> allocateOrReadSameRunOwnedInstance(repository, transactions, ready, start));
      var second =
          executor.submit(
              () -> allocateOrReadSameRunOwnedInstance(repository, transactions, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      long firstId = first.get(15, TimeUnit.SECONDS);
      long secondId = second.get(15, TimeUnit.SECONDS);
      assertThat(firstId).isEqualTo(secondId);
      assertThat(
              dsl.fetchValue(
                  "SELECT count(*) FROM game_instances WHERE tenant_id = ? "
                      + "AND run_owned_start_request_id = ?",
                  Integer.class,
                  TENANT_ID,
                  REQUEST_ID))
          .isEqualTo(1);
      assertThat(
              dsl.fetchValue(
                  "SELECT run_owned_start_request_digest FROM game_instances WHERE id = ?",
                  String.class,
                  firstId))
          .isEqualTo(REQUEST_DIGEST);

      assertThatThrownBy(() -> insertDuplicateIdentity(dsl, firstId + 1L))
          .isInstanceOf(DataAccessException.class);

      GameInstance ordinaryOne = ordinaryInstance(1001L);
      GameInstance ordinaryTwo = ordinaryInstance(1002L);
      repository.save(ordinaryOne);
      repository.save(ordinaryTwo);
      assertThat(ordinaryOne.getRunOwnedStartRequestId()).isNull();
      assertThat(ordinaryTwo.getRunOwnedStartRequestId()).isNull();
      assertThat(
              dsl.fetchValue(
                  "SELECT count(*) FROM game_instances WHERE tenant_id = ? "
                      + "AND run_owned_start_request_id IS NULL",
                  Integer.class,
                  TENANT_ID))
          .isEqualTo(2);
    }
  }

  private static long allocateOrReadSameRunOwnedInstance(
      GameInstanceRepository repository,
      TransactionTemplate transactions,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent allocation start gate timed out");
    }
    Long id =
        transactions.execute(
            status -> {
              repository.lockRunOwnedStartIdentity(TENANT_ID, REQUEST_ID);
              var existing =
                  repository.findByTenantIdAndRunOwnedStartRequestIdForUpdate(
                      TENANT_ID, REQUEST_ID);
              if (existing.isPresent()) {
                assertThat(existing.get().getRunOwnedStartRequestDigest())
                    .isEqualTo(REQUEST_DIGEST);
                return existing.get().getId();
              }
              GameInstance instance = runOwnedInstance();
              return repository.save(instance).getId();
            });
    if (id == null) {
      throw new IllegalStateException("run-owned instance allocation returned no id");
    }
    return id;
  }

  private static void insertDuplicateIdentity(DSLContext dsl, long id) {
    dsl.execute(
        "INSERT INTO game_instances (id, tenant_id, runtime_version, game_template_id, "
            + "launch_descriptor_id, version_id, release_bundle_id, version_state_epoch, "
            + "generation_config_revision, owner_account_id, status, run_owned_start_request_id, "
            + "run_owned_start_request_digest, run_owned_start_published_release_bundle_ref) "
            + "VALUES (?, ?, '902', 810, 'ld-run-owned-pg', 902, 20, 13, 'genrev-1', "
            + "1001, 'STARTING', ?, ?, 'prb:41:902:20')",
        id,
        TENANT_ID,
        REQUEST_ID,
        REQUEST_DIGEST);
  }

  private static DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static GameInstance runOwnedInstance() {
    GameInstance instance = new GameInstance();
    instance.setTenantId(TENANT_ID);
    instance.setRuntimeVersion("902");
    instance.setGameTemplateId(810L);
    instance.setLaunchDescriptorId("ld-run-owned-pg");
    instance.setVersionId(902L);
    instance.setReleaseBundleId(20L);
    instance.setVersionStateEpoch(13L);
    instance.setGenerationConfigRevision("genrev-1");
    instance.setOwnerAccountId(1001L);
    instance.setStatus("STARTING");
    instance.setRunOwnedStartRequestId(REQUEST_ID);
    instance.setRunOwnedStartRequestDigest(REQUEST_DIGEST);
    instance.setRunOwnedStartPublishedReleaseBundleRef("prb:41:902:20");
    return instance;
  }

  private static GameInstance ordinaryInstance(long ownerAccountId) {
    GameInstance instance = new GameInstance();
    instance.setTenantId(TENANT_ID);
    instance.setRuntimeVersion("ordinary-runtime");
    instance.setOwnerAccountId(ownerAccountId);
    instance.setStatus("STARTING");
    return instance;
  }
}
