package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCurrentSourceObservationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real existing-only, non-authorizing observation. No token, restriction receipt or enrollment. */
class AccountControlUiCurrentSourceObservationPostgresIntegrationTest {
  private static final String ISSUER = "firemud-account-service";
  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void start() {
    postgres.start();
  }

  @AfterAll
  static void stop() {
    postgres.stop();
  }

  @Test
  void exactFreshAndAdvancedExistingSourcesAreObservedWithoutAnySqlMutation() {
    var f = fixture();
    UUID account = f.createAccount();
    var before = f.rows();
    var observation = f.tx.execute(status -> f.observer.observeExisting(account));
    assertThat(observation.accountId()).isEqualTo(account);
    assertThat(observation.sources().issuer().checkpoint().sequence()).isZero();
    assertThat(observation.sources().account().checkpoint().sequence()).isZero();
    assertThat(observation.sources().issuanceFence().accountId()).isEqualTo(account);
    assertThat(observation.globalRoleSourceVersion()).isPositive();
    assertThat(observation.toString()).contains("non-authorizing");
    assertThat(f.rows()).isEqualTo(before);

    f.tx.executeWithoutResult(
        status ->
            f.sources.appendIssuerAuthorityChange(
                ISSUER, "SIGNER_COMPROMISE", "observation-advance-" + UUID.randomUUID()));
    var advancedRows = f.rows();
    var advanced = f.tx.execute(status -> f.observer.observeExisting(account));
    var owner = f.tx.execute(status -> f.sources.readCurrentCanonicalIssuerSource(ISSUER));
    assertThat(advanced.sources().issuer()).isEqualTo(owner.source());
    assertThat(advanced.sources().canonicalIssuerProjection().toJson())
        .isEqualTo(
            net.firedevops.firemud.accountservice.service.IssuerGenerationProjection.fromSource(
                    owner)
                .toJson());
    assertThat(advanced.sources().issuer().generation()).isEqualTo(2L);
    assertThat(advanced.sources().account()).isEqualTo(observation.sources().account());
    assertThat(owner.latestEvent()).isPresent();
    assertThat(f.rows()).isEqualTo(advancedRows);
  }

  @Test
  void absentIdentityAndNonOwnerTransactionsCannotEnrollOrMutateSources() {
    var f = fixture();
    UUID account = f.createAccount();
    var before = f.rows();
    assertThatThrownBy(() -> f.observer.observeExisting(account))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("observation unavailable");
    var readOnly = new TransactionTemplate(f.manager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(() -> readOnly.execute(status -> f.observer.observeExisting(account)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("observation unavailable");
    var wrongIsolation = new TransactionTemplate(f.manager);
    wrongIsolation.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(() -> wrongIsolation.execute(status -> f.observer.observeExisting(account)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("observation unavailable");
    assertThatThrownBy(() -> f.tx.execute(status -> f.observer.observeExisting(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("observation unavailable");
    assertThat(f.rows()).isEqualTo(before);
  }

  @Test
  void exactCountersCannotHideMissingOrContradictoryIssuerHistoryInIsolatedReaderClones() {
    var f = fixture();
    UUID account = f.createAccount();
    f.tx.executeWithoutResult(
        status ->
            f.sources.appendIssuerAuthorityChange(
                ISSUER, "SIGNER_COMPROMISE", "observed-event-" + UUID.randomUUID()));
    var canonical = f.rows();
    for (boolean missing : List.of(true, false)) {
      f.tx.executeWithoutResult(
          status -> {
            // Ordinary negative-input copies only: no production trigger, function or role is
            // changed.
            f.dsl.execute(
                "CREATE TEMP TABLE account_authority_outbox_events ON COMMIT DROP AS SELECT * FROM "
                    + f.schema
                    + ".account_authority_outbox_events");
            assertThat(
                    f.dsl
                        .fetchSingle(
                            "SELECT 'account_authority_outbox_events'::regclass::oid = "
                                + "'pg_temp.account_authority_outbox_events'::regclass::oid")
                        .get(0, Boolean.class))
                .isTrue();
            // The unmodified clone must really route through the same canonical reader first.
            assertThat(f.observer.observeExisting(account).sources().issuer().generation())
                .isEqualTo(2L);
            if (missing) {
              assertThat(
                      f.dsl.execute(
                          "DELETE FROM pg_temp.account_authority_outbox_events WHERE outbox_stream_key=?",
                          "account:auth-authority:v1:issuer/" + ISSUER))
                  .isEqualTo(1);
            } else {
              assertThat(
                      f.dsl.execute(
                          "UPDATE pg_temp.account_authority_outbox_events SET event_digest=? WHERE outbox_stream_key=?",
                          "sha256:" + "0".repeat(64),
                          "account:auth-authority:v1:issuer/" + ISSUER))
                  .isEqualTo(1);
            }
            var clone =
                f.dsl
                    .fetch(
                        "SELECT to_jsonb(t)::text AS row, xmin::text AS xmin FROM pg_temp.account_authority_outbox_events t ORDER BY 1,2")
                    .intoMaps();
            assertThatThrownBy(() -> f.observer.observeExisting(account))
                .isInstanceOf(
                    AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException
                        .class);
            assertThat(
                    f.dsl
                        .fetch(
                            "SELECT to_jsonb(t)::text AS row, xmin::text AS xmin FROM pg_temp.account_authority_outbox_events t ORDER BY 1,2")
                        .intoMaps())
                .isEqualTo(clone);
            assertThat(f.rows()).isEqualTo(canonical);
          });
    }
    assertThat(
            f.tx
                .execute(status -> f.observer.observeExisting(account))
                .sources()
                .issuer()
                .generation())
        .isEqualTo(2L);
    assertThat(f.rows()).isEqualTo(canonical);
  }

  @Test
  void missingExistingAccountSourceNeverEnrollsOrRepairsItsBaseline() {
    var f = fixture();
    UUID account = f.createAccount();
    var original = f.rows();
    f.tx.executeWithoutResult(
        status -> {
          f.dsl.execute(
              "CREATE TEMP TABLE account_authority_source_records ON COMMIT DROP AS SELECT * FROM "
                  + f.schema
                  + ".account_authority_source_records");
          assertThat(
                  f.dsl
                      .fetchSingle(
                          "SELECT 'account_authority_source_records'::regclass::oid = "
                              + "'pg_temp.account_authority_source_records'::regclass::oid")
                      .get(0, Boolean.class))
              .isTrue();
          assertThat(f.observer.observeExisting(account).accountId()).isEqualTo(account);
          assertThat(
                  f.dsl.execute(
                      "DELETE FROM pg_temp.account_authority_source_records WHERE account_uuid=?",
                      account))
              .isEqualTo(1);
          var missing =
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(t)::text AS row,xmin::text AS xmin FROM pg_temp.account_authority_source_records t ORDER BY 1,2")
                  .intoMaps();
          assertThatThrownBy(() -> f.observer.observeExisting(account))
              .isInstanceOf(
                  AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException
                      .class);
          assertThat(
                  f.dsl
                      .fetch(
                          "SELECT to_jsonb(t)::text AS row,xmin::text AS xmin FROM pg_temp.account_authority_source_records t ORDER BY 1,2")
                      .intoMaps())
              .isEqualTo(missing);
          assertThat(f.rows()).isEqualTo(original);
        });
    assertThat(f.tx.execute(status -> f.observer.observeExisting(account)).accountId())
        .isEqualTo(account);
    assertThat(f.rows()).isEqualTo(original);
  }

  @Test
  void actualConcurrentIssuerAdvanceWaitsForTheObservedCompositeCut() throws Exception {
    var f = fixture();
    UUID account = f.createAccount();
    var ready = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var readerPid = new CompletableFuture<Integer>();
    var writerPid = new CompletableFuture<Integer>();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var reader =
          executor.submit(
              () ->
                  f.tx.execute(
                      status -> {
                        readerPid.complete(
                            f.dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                        var cut = f.observer.observeExisting(account);
                        ready.countDown();
                        try {
                          assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return cut;
                      }));
      var writer =
          executor.submit(
              () -> {
                assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                f.tx.executeWithoutResult(
                    status -> {
                      writerPid.complete(
                          f.dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                      f.sources.appendIssuerAuthorityChange(
                          ISSUER, "SIGNER_COMPROMISE", "waiting-" + UUID.randomUUID());
                    });
                return null;
              });
      int reading = readerPid.get(30, TimeUnit.SECONDS);
      int writing = writerPid.get(30, TimeUnit.SECONDS);
      boolean blocked = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        var wait =
            f.dsl.fetchOne(
                "SELECT wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)) FROM pg_stat_activity WHERE pid=?",
                reading,
                writing);
        if (wait != null && Boolean.TRUE.equals(wait.get(0, Boolean.class))) {
          blocked = true;
          break;
        }
        Thread.sleep(20);
      }
      assertThat(blocked)
          .as("real PostgreSQL issuer writer blocked by observing transaction")
          .isTrue();
      assertThat(writer.isDone()).isFalse();
      release.countDown();
      assertThat(reader.get(30, TimeUnit.SECONDS).sources().issuer().generation()).isEqualTo(1L);
      writer.get(30, TimeUnit.SECONDS);
      assertThat(
              f.tx
                  .execute(status -> f.observer.observeExisting(account))
                  .sources()
                  .issuer()
                  .generation())
          .isEqualTo(2L);
    } finally {
      release.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static Fixture fixture() {
    String schema = "account_observe_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(source);
    var tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var evidence =
        new AccountAuthoritySourceEvidenceRepository(
            dsl,
            new AccountAuthorityGenerationRepository(dsl),
            new AccountAuthorityOutboxRepository(dsl));
    var accounts = new AccountRepository(dsl, evidence);
    return new Fixture(
        schema,
        dsl,
        manager,
        tx,
        evidence,
        accounts,
        new AccountControlUiCurrentSourceObservationRepository(dsl, accounts, evidence));
  }

  private record Fixture(
      String schema,
      DSLContext dsl,
      DataSourceTransactionManager manager,
      TransactionTemplate tx,
      AccountAuthoritySourceEvidenceRepository sources,
      AccountRepository accounts,
      AccountControlUiCurrentSourceObservationRepository observer) {
    UUID createAccount() {
      return tx.execute(
          status -> {
            Account account = new Account();
            String suffix = UUID.randomUUID().toString();
            account.setUsername("observed-" + suffix);
            account.setEmail(suffix + "@example.test");
            account.setPasswordHash("test-only-hash");
            return accounts.save(account).getAccountUuid();
          });
    }

    Map<String, List<Map<String, Object>>> rows() {
      var result = new java.util.TreeMap<String, List<Map<String, Object>>>();
      for (String table :
          dsl.fetch("SELECT tablename FROM pg_tables WHERE schemaname=? ORDER BY tablename", schema)
              .getValues(0, String.class)) {
        result.put(
            table,
            dsl.fetch(
                    "SELECT to_jsonb(t)::text AS row,xmin::text AS xmin FROM "
                        + schema
                        + "."
                        + table
                        + " t ORDER BY 1,2")
                .intoMaps());
      }
      return result;
    }
  }
}
