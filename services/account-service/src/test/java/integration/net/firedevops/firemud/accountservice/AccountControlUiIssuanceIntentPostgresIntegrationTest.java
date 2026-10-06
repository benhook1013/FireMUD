package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCurrentSourceRepository;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceIntent;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceIntentRepository;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountGlobalRoleSourceRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiOriginalSourceCapture;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiPreSignIntentService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
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

/**
 * Real source/claim/reservation/hash SQL only; synthetic signer bytes prove no signing or registry.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountControlUiIssuanceIntentPostgresIntegrationTest {
  private static final String ISSUER = "firemud-account-service";
  private static final String SIGNER_BYTES = "eyJ0ZXN0IjoxfQ.eyJ0ZXN0IjoyfQ.c3ludGhldGlj";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void ownerSourceCapturePrecedesSigningAndRestartRecoversOriginalReservationWithoutRecapture() {
    Fixture fixture = fixture(true);
    var request = fixture.request();
    var initial = fixture.tx().execute(status -> fixture.service().prepare(request));
    assertThat(initial).isNotNull();
    assertThat(initial.operation().operationId().toString()).isNotEqualTo(request.requestId());
    assertThat(initial.tokenHash()).isEmpty();
    assertThat(initial.sourceVersion()).isPositive();
    assertThat(initial.sourceFence()).isPositive();
    assertThat(initial.bundleVersion()).isEqualTo(1L);
    var capture = AccountControlUiOriginalSourceCapture.read(initial.operation().originalCapture());
    assertThat(capture.issuanceFence()).isEqualTo("1");
    assertThat(capture.authorityTuple())
        .containsEntry("issuerAuthGeneration", "1")
        .containsEntry("accountAuthorityGeneration", "1")
        .containsEntry("tenantAuthorityGeneration", Map.of())
        .containsEntry("membershipAuthorityGeneration", Map.of())
        .containsEntry("privateRealmGrantVersions", java.util.List.of());
    var restart = fixture.restartedService();
    assertThat(
            fixture
                .tx()
                .<AccountControlUiIssuanceIntent>execute(status -> restart.prepare(request)))
        .isEqualTo(initial);
    var bound = fixture.tx().execute(status -> restart.bindSignedToken(initial, SIGNER_BYTES));
    assertThat(bound).isNotNull();
    assertThat(bound.tokenHash())
        .hasValue("bbd9b4fbf04eea2ba68c68d87cd16a878a1f52c1be163a94009dfaed28592d51");
    assertThat(
            fixture
                .tx()
                .<AccountControlUiIssuanceIntent>execute(
                    status -> fixture.service().bindSignedToken(initial, SIGNER_BYTES)))
        .isEqualTo(bound);
    assertThat(
            fixture
                .tx()
                .<AccountControlUiIssuanceIntent>execute(status -> restart.prepare(request)))
        .isEqualTo(bound);
    assertThat(count(fixture.dsl(), "account_control_ui_issuance_operations")).isEqualTo(1L);
    assertThat(count(fixture.dsl(), "account_control_ui_issuance_intents")).isEqualTo(1L);
    assertThat(count(fixture.dsl(), "account_authority_outbox_events")).isZero();
  }

  @Test
  void differentHashIdentityOrFenceCannotReplaceOriginalAndSqlCannotMutateReservation() {
    Fixture fixture = fixture(true);
    var original =
        Objects.requireNonNull(
            fixture.tx().execute(status -> fixture.service().prepare(fixture.request())));
    var bound =
        fixture.tx().execute(status -> fixture.service().bindSignedToken(original, SIGNER_BYTES));
    assertThatThrownBy(
            () ->
                fixture
                    .tx()
                    .execute(
                        status -> fixture.service().bindSignedToken(original, SIGNER_BYTES + "x")))
        .isInstanceOf(IllegalStateException.class);
    for (var forged :
        java.util.List.of(
            new AccountControlUiIssuanceIntent(
                original.operation(),
                UUID.randomUUID(),
                original.bundleId(),
                original.bundleVersion(),
                original.sourceVersion(),
                original.sourceFence(),
                Optional.empty()),
            new AccountControlUiIssuanceIntent(
                original.operation(),
                original.jti(),
                original.bundleId(),
                original.bundleVersion(),
                original.sourceVersion(),
                original.sourceFence() + 1L,
                Optional.empty()))) {
      assertThatThrownBy(
              () ->
                  fixture
                      .tx()
                      .execute(status -> fixture.service().bindSignedToken(forged, SIGNER_BYTES)))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE account_control_ui_issuance_intents SET jti = ? WHERE operation_id = ?",
                        UUID.randomUUID(),
                        original.operation().operationId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "DELETE FROM account_control_ui_issuance_intents WHERE operation_id = ?",
                        original.operation().operationId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(
            fixture
                .tx()
                .<AccountControlUiIssuanceIntent>execute(
                    status -> fixture.service().prepare(original.operation().request())))
        .isEqualTo(bound);
  }

  @Test
  void changedCurrentIssuerDeniesProgressionAndPreservesHistoricalCaptureAndHash() {
    Fixture fixture = fixture(true);
    var original =
        Objects.requireNonNull(
            fixture.tx().execute(status -> fixture.service().prepare(fixture.request())));
    fixture.issuer().advance(ISSUER, UUID.randomUUID(), 1L, 1L);
    assertThatThrownBy(
            () ->
                fixture
                    .tx()
                    .execute(status -> fixture.service().prepare(original.operation().request())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer exact");
    assertThatThrownBy(
            () ->
                fixture
                    .tx()
                    .execute(status -> fixture.service().bindSignedToken(original, SIGNER_BYTES)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer exact");
    assertThat(
            fixture
                .tx()
                .<AccountControlUiIssuanceIntent>execute(
                    status -> fixture.intents().read(original.operation()).orElseThrow()))
        .isEqualTo(original);
    assertThat(count(fixture.dsl(), "account_control_ui_issuance_intents")).isEqualTo(1L);
  }

  @Test
  void advancedIssuerEventIsCapturedExactlyAndMissingAuthorityNeverEnrolls() {
    Fixture fixture = fixture(true);
    var event = fixture.issuer().advance(ISSUER, UUID.randomUUID(), 1L, 1L);
    var original =
        Objects.requireNonNull(
            fixture.tx().execute(status -> fixture.service().prepare(fixture.request())));
    String source =
        new String(
            original.operation().originalCapture().authorityCapture(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(source).contains("issuerSource");
    assertThat(
            AccountControlUiOriginalSourceCapture.read(original.operation().originalCapture())
                .authorityTuple())
        .containsEntry("issuerAuthGeneration", "2");
    // The canonical nested projection preserves the exact event; its bytes are not re-created.
    assertThat(source).contains(event.eventDigest()).contains(event.eventId());
    Fixture absent = fixture(false);
    assertThatThrownBy(
            () -> absent.tx().execute(status -> absent.service().prepare(absent.request())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count(absent.dsl(), "account_authority_generations")).isZero();
    assertThat(count(absent.dsl(), "account_authority_issuance_fences")).isZero();
    assertThat(count(absent.dsl(), "account_control_ui_issuance_operations")).isZero();
    assertThat(count(absent.dsl(), "account_control_ui_issuance_intents")).isZero();
  }

  @Test
  void abortedPresignAndPostsignTransactionsDoNotLeavePartialProgress() {
    Fixture fixture = fixture(true);
    var request = fixture.request();
    fixture
        .tx()
        .executeWithoutResult(
            status -> {
              fixture.service().prepare(request);
              status.setRollbackOnly();
            });
    assertThat(count(fixture.dsl(), "account_control_ui_issuance_operations")).isZero();
    assertThat(count(fixture.dsl(), "account_control_ui_issuance_intents")).isZero();
    var original =
        Objects.requireNonNull(fixture.tx().execute(status -> fixture.service().prepare(request)));
    fixture
        .tx()
        .executeWithoutResult(
            status -> {
              fixture.service().bindSignedToken(original, SIGNER_BYTES);
              status.setRollbackOnly();
            });
    assertThat(
            fixture
                .tx()
                .<AccountControlUiIssuanceIntent>execute(
                    status -> fixture.service().prepare(request)))
        .isEqualTo(original);
    assertThat(
            fixture
                .tx()
                .<AccountControlUiIssuanceIntent>execute(
                    status -> fixture.service().bindSignedToken(original, SIGNER_BYTES)))
        .isNotNull();
  }

  @Test
  void preexistingV66OperationWithoutSigningReservationIsNeverRepairedOnRetry() {
    Fixture fixture = fixture(true);
    var request = fixture.request();
    fixture
        .tx()
        .executeWithoutResult(
            status -> {
              var observation = fixture.sources().readUnscopedCurrent(fixture.accountId());
              var account =
                  fixture.accounts().findByAccountUuidForUpdate(fixture.accountId()).orElseThrow();
              var capture = AccountControlUiOriginalSourceCapture.fromCurrent(observation, account);
              fixture
                  .operations()
                  .claim(UUID.randomUUID(), request, Optional.of(capture.originalCapture()));
            });
    assertThatThrownBy(() -> fixture.tx().execute(status -> fixture.service().prepare(request)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot recapture");
    assertThat(count(fixture.dsl(), "account_control_ui_issuance_intents")).isZero();
  }

  @Test
  void postSignCasSerializesWithIssuerWriterAndRetainsOriginalHash() throws Exception {
    Fixture fixture = fixture(true);
    var original =
        Objects.requireNonNull(
            fixture.tx().execute(status -> fixture.service().prepare(fixture.request())));
    CountDownLatch hashLocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch advanceEntered = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(3)) {
      var bound =
          executor.submit(
              () ->
                  fixture
                      .tx()
                      .execute(
                          status -> {
                            var result = fixture.service().bindSignedToken(original, SIGNER_BYTES);
                            hashLocked.countDown();
                            await(release);
                            return result;
                          }));
      assertThat(hashLocked.await(10L, TimeUnit.SECONDS)).isTrue();
      var advanced =
          executor.submit(
              () -> {
                advanceEntered.countDown();
                return fixture.issuer().advance(ISSUER, UUID.randomUUID(), 1L, 1L);
              });
      assertThat(advanceEntered.await(10L, TimeUnit.SECONDS)).isTrue();
      try {
        assertThatThrownBy(() -> advanced.get(150L, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        release.countDown();
      }
      var retained = bound.get(10L, TimeUnit.SECONDS);
      assertThat(advanced.get(10L, TimeUnit.SECONDS)).isNotNull();
      assertThat(
              fixture
                  .tx()
                  .<AccountControlUiIssuanceIntent>execute(
                      status -> fixture.intents().read(original.operation()).orElseThrow()))
          .isEqualTo(retained);
      assertThatThrownBy(
              () ->
                  fixture
                      .tx()
                      .execute(status -> fixture.service().bindSignedToken(original, SIGNER_BYTES)))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      release.countDown();
    }
  }

  @Test
  void concurrentPresignRetriesRecoverOneOriginalIdentityAndDifferentHashCasHasOneWinner()
      throws Exception {
    Fixture fixture = fixture(true);
    var request = fixture.request();
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                await(start);
                return fixture.tx().execute(status -> fixture.service().prepare(request));
              });
      var second =
          executor.submit(
              () -> {
                await(start);
                return fixture.tx().execute(status -> fixture.service().prepare(request));
              });
      start.countDown();
      var original = Objects.requireNonNull(first.get(10L, TimeUnit.SECONDS));
      assertThat(second.get(10L, TimeUnit.SECONDS)).isEqualTo(original);
      CountDownLatch signStart = new CountDownLatch(1);
      var a =
          executor.submit(
              () -> {
                await(signStart);
                return bindOutcome(fixture, original, SIGNER_BYTES);
              });
      var b =
          executor.submit(
              () -> {
                await(signStart);
                return bindOutcome(fixture, original, SIGNER_BYTES + "x");
              });
      signStart.countDown();
      assertThat(java.util.List.of(a.get(10L, TimeUnit.SECONDS), b.get(10L, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(count(fixture.dsl(), "account_control_ui_issuance_operations")).isEqualTo(1L);
      assertThat(count(fixture.dsl(), "account_control_ui_issuance_intents")).isEqualTo(1L);
    } finally {
      start.countDown();
    }
  }

  private static boolean bindOutcome(
      Fixture fixture, AccountControlUiIssuanceIntent original, String token) {
    try {
      fixture.tx().execute(status -> fixture.service().bindSignedToken(original, token));
      return true;
    } catch (IllegalStateException expectedConflict) {
      return false;
    }
  }

  @Test
  void committedAccountLifecycleChangeSerializesWithPostSignCasAndDeniesHashBinding()
      throws Exception {
    Fixture fixture = fixture(true);
    var original =
        Objects.requireNonNull(
            fixture.tx().execute(status -> fixture.service().prepare(fixture.request())));
    CountDownLatch ownerLocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch casEntered = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var changed =
          executor.submit(
              () ->
                  fixture
                      .tx()
                      .execute(
                          status -> {
                            // Fixture owner mutation follows the same issuer-first order. This does
                            // not claim a live
                            // lifecycle ingress or its separately required source-event production.
                            fixture.sources().readUnscopedCurrent(fixture.accountId());
                            fixture
                                .dsl()
                                .execute(
                                    "UPDATE accounts SET lifecycle_state = 'security_locked' WHERE account_uuid = ?",
                                    fixture.accountId());
                            ownerLocked.countDown();
                            await(release);
                            return true;
                          }));
      assertThat(ownerLocked.await(10L, TimeUnit.SECONDS)).isTrue();
      var binding =
          executor.submit(
              () -> {
                casEntered.countDown();
                return bindOutcome(fixture, original, SIGNER_BYTES);
              });
      assertThat(casEntered.await(10L, TimeUnit.SECONDS)).isTrue();
      try {
        assertThatThrownBy(() -> binding.get(150L, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        release.countDown();
      }
      assertThat(changed.get(10L, TimeUnit.SECONDS)).isTrue();
      assertThat(binding.get(10L, TimeUnit.SECONDS)).isFalse();
      assertThat(
              fixture
                  .tx()
                  .<AccountControlUiIssuanceIntent>execute(
                      status -> fixture.intents().read(original.operation()).orElseThrow()))
          .isEqualTo(original);
    } finally {
      release.countDown();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10L, TimeUnit.SECONDS))
        throw new IllegalStateException("Fixture latch timed out");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }

  private static long count(DSLContext dsl, String table) {
    return Objects.requireNonNull(dsl.fetchOne("SELECT COUNT(*) AS n FROM " + table))
        .get("n", Long.class);
  }

  private static Fixture fixture(boolean enroll) {
    String schema = "control_ui_intent_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(
        postgres.getJdbcUrl()
            + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
            + "currentSchema="
            + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("74"))
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var accounts = new AccountRepository(dsl);
    Account account = new Account();
    account.setUsername("intent_source");
    account.setEmail("intent-source@example.test");
    account.setPasswordHash("test-only-owner-source-fixture");
    accounts.save(account);
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    if (enroll)
      tx.executeWithoutResult(
          status -> {
            generations.initializeIssuerIfAbsent(ISSUER);
            generations.initialize(AuthorityScope.account(account.getAccountUuid()));
          });
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var issuer =
        new AccountIssuerAuthorityEventProducer(
            ISSUER, generations, outbox, dsl, Objects.requireNonNull(tx.getTransactionManager()));
    var readback =
        new AccountAuthoritySourceEventReadback(
            outbox,
            new AccountPasswordResetOperationRepository(dsl),
            new AccountLogoutAllOperationRepository(dsl),
            new AccountSecurityStateOperationRepository(dsl));
    var sources =
        new AccountControlUiCurrentSourceRepository(
            dsl,
            accounts,
            new AccountGlobalRoleSourceRepository(dsl, dataSource),
            generations,
            readback,
            issuer);
    var operations = new AccountControlUiIssuanceOperationRepository(dsl);
    var intents = new AccountControlUiIssuanceIntentRepository(dsl);
    var service = new AccountControlUiPreSignIntentService(sources, accounts, operations, intents);
    return new Fixture(
        dsl, tx, account.getAccountUuid(), accounts, sources, operations, intents, issuer, service);
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate tx,
      UUID accountId,
      AccountRepository accounts,
      AccountControlUiCurrentSourceRepository sources,
      AccountControlUiIssuanceOperationRepository operations,
      AccountControlUiIssuanceIntentRepository intents,
      AccountIssuerAuthorityEventProducer issuer,
      AccountControlUiPreSignIntentService service) {
    AccountControlUiIssuanceRequest request() {
      return new AccountControlUiIssuanceRequest(
          UUID.randomUUID().toString(), accountId.toString());
    }

    AccountControlUiPreSignIntentService restartedService() {
      return new AccountControlUiPreSignIntentService(
          sources,
          accounts,
          new AccountControlUiIssuanceOperationRepository(dsl),
          new AccountControlUiIssuanceIntentRepository(dsl));
    }
  }
}
