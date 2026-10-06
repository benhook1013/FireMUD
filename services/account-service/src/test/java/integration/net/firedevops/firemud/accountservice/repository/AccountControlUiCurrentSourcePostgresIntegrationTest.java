package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCurrentSourceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGlobalRoleSourceRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiTokenChecks.InspectedToken;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
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
 * Real SQL source/lock proof only. Signed records and authority enrollment are explicit fixtures;
 * this is not Account-issued credential, retained signer, Redis, bundle or creator activation
 * proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountControlUiCurrentSourcePostgresIntegrationTest {
  private static final String ISSUER = "firemud-account-service";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void currentFreshSourceIsObservedAtOneOwnerTransactionWithoutEnrollmentOrMutation()
      throws Exception {
    Fixture fixture = fixture(true);
    var candidate = candidate(fixture.accountId());
    Long generationsBefore = count(fixture.dsl(), "account_authority_generations");
    var observation =
        fixture.tx().execute(status -> fixture.source().inspectUnscopedCurrent(candidate));
    assertThat(observation).isNotNull();
    assertThat(observation.accountId()).isEqualTo(fixture.accountId());
    assertThat(observation.authority().issuer().generation()).isEqualTo(1L);
    assertThat(observation.issuerSource().issuerAuthGeneration()).isEqualTo(1L);
    assertThat(observation.issuerSource().outboxSequence()).isZero();
    assertThat(observation.issuerSource().latestEvent()).isEmpty();
    assertThat(observation.accountSource().sourceState().generation()).isEqualTo(1L);
    assertThat(observation.globalRoleSourceVersion()).isEqualTo(1L);
    assertThat(observation.toString()).contains("non-authorizing");
    assertThat(count(fixture.dsl(), "account_authority_generations")).isEqualTo(generationsBefore);
    assertThat(count(fixture.dsl(), "account_authority_outbox_events")).isZero();
    assertThat(count(fixture.dsl(), "account_global_role_sources")).isEqualTo(1L);
  }

  @Test
  void currentIssuerAdvanceRetainsItsCompleteExactEventAndCheckpointInTheSameObservation()
      throws Exception {
    Fixture fixture = fixture(true);
    var event = fixture.issuer().advance(ISSUER, UUID.randomUUID(), 1L, 1L);
    var currentCandidate = candidate(fixture.accountId(), 2L);
    var observation =
        fixture.tx().execute(status -> fixture.source().inspectUnscopedCurrent(currentCandidate));
    assertThat(observation).isNotNull();
    assertThat(observation.issuerSource().issuerAuthGeneration()).isEqualTo(2L);
    assertThat(observation.issuerSource().sourceVersion()).isEqualTo(2L);
    assertThat(observation.issuerSource().outboxSequence()).isEqualTo(1L);
    var retained = observation.issuerSource().latestEvent().orElseThrow();
    assertThat(retained.canonicalJsonUtf8()).isEqualTo(event.canonicalJsonUtf8());
    assertThat(retained.eventId()).isEqualTo(event.eventId());
    assertThat(retained.eventDigest()).isEqualTo(event.eventDigest());
    assertThat(observation.accountSource().outboxSequence()).isZero();
  }

  @Test
  void matchingAdvancedCountersWithoutTheirIssuerEventCannotProduceObservation() throws Exception {
    Fixture fixture = fixture(true);
    fixture
        .tx()
        .execute(
            status ->
                fixture
                    .generations()
                    .advance(fixture.generations().read(AuthorityScope.issuer(ISSUER)), null));
    var matchingCounters = candidate(fixture.accountId(), 2L);
    assertThatThrownBy(
            () ->
                fixture
                    .tx()
                    .execute(status -> fixture.source().inspectUnscopedCurrent(matchingCounters)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sequence-zero");
    assertThat(count(fixture.dsl(), "account_authority_outbox_events")).isZero();
  }

  @Test
  void matchingCountersAndCheckpointCannotHideContradictoryIssuerEventDigest() throws Exception {
    Fixture fixture = fixture(true);
    UUID request = UUID.randomUUID();
    fixture
        .tx()
        .executeWithoutResult(
            status -> {
              fixture
                  .generations()
                  .advance(fixture.generations().read(AuthorityScope.issuer(ISSUER)), null);
              var event =
                  IssuerGenerationAuthorityEventV1Codec.seal(
                      Map.ofEntries(
                          Map.entry(
                              "schemaVersion",
                              IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION),
                          Map.entry("eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE),
                          Map.entry("eventId", "account-issuer-authority-event-v1:" + request),
                          Map.entry("requestId", request.toString()),
                          Map.entry("issuerId", ISSUER),
                          Map.entry("sourceScope", "issuer/" + ISSUER),
                          Map.entry(
                              "outboxStreamKey", "account:auth-authority:v1:issuer/" + ISSUER),
                          Map.entry("outboxSequence", "1"),
                          Map.entry("issuerAuthGeneration", "2"),
                          Map.entry("sourceVersion", "2")));
              fixture
                  .outbox()
                  .append(
                      event.outboxStreamKey(),
                      request.toString(),
                      event.eventId(),
                      "f".repeat(64),
                      event.canonicalJsonUtf8());
            });
    var matchingCounters = candidate(fixture.accountId(), 2L);
    assertThatThrownBy(
            () ->
                fixture
                    .tx()
                    .execute(status -> fixture.source().inspectUnscopedCurrent(matchingCounters)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("contradict");
    assertThat(count(fixture.dsl(), "account_authority_outbox_events")).isEqualTo(1L);
  }

  @Test
  void missingAuthorityFailsWithoutInventingAnInitialGeneration() throws Exception {
    Fixture fixture = fixture(false);
    var candidate = candidate(fixture.accountId());
    assertThatThrownBy(
            () ->
                fixture.tx().execute(status -> fixture.source().inspectUnscopedCurrent(candidate)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count(fixture.dsl(), "account_authority_generations")).isZero();
    assertThat(count(fixture.dsl(), "account_authority_issuance_fences")).isZero();
    assertThat(count(fixture.dsl(), "account_authority_outbox_events")).isZero();
  }

  @Test
  void absentOwnerTransactionAndReadOnlyTransactionCannotProduceObservation() throws Exception {
    Fixture fixture = fixture(true);
    var candidate = candidate(fixture.accountId());
    assertThatThrownBy(() -> fixture.source().inspectUnscopedCurrent(candidate))
        .isInstanceOf(IllegalStateException.class);
    TransactionTemplate readOnly = new TransactionTemplate(fixture.tx().getTransactionManager());
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () -> readOnly.execute(status -> fixture.source().inspectUnscopedCurrent(candidate)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void committedIssuerAdvanceRejectsThePriorExactSignedAndActiveRecord() throws Exception {
    Fixture fixture = fixture(true);
    var candidate = candidate(fixture.accountId());
    fixture
        .tx()
        .execute(
            status -> {
              var old = fixture.generations().read(AuthorityScope.issuer(ISSUER));
              return fixture.generations().advance(old, null);
            });
    assertThatThrownBy(
            () ->
                fixture.tx().execute(status -> fixture.source().inspectUnscopedCurrent(candidate)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count(fixture.dsl(), "account_authority_outbox_events")).isZero();
  }

  @Test
  void concurrentIssuerAdvanceSerializesOnTheObservationCompositeFence() throws Exception {
    Fixture fixture = fixture(true);
    var candidate = candidate(fixture.accountId());
    CountDownLatch observationLocked = new CountDownLatch(1);
    CountDownLatch releaseObservation = new CountDownLatch(1);
    CountDownLatch advanceEntered = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var observed =
          executor.submit(
              () ->
                  fixture
                      .tx()
                      .execute(
                          status -> {
                            var result = fixture.source().inspectUnscopedCurrent(candidate);
                            observationLocked.countDown();
                            await(releaseObservation);
                            return result;
                          }));
      assertThat(observationLocked.await(10L, TimeUnit.SECONDS)).isTrue();
      var advanced =
          executor.submit(
              () ->
                  fixture
                      .tx()
                      .execute(
                          status -> {
                            advanceEntered.countDown();
                            var old = fixture.generations().read(AuthorityScope.issuer(ISSUER));
                            return fixture.generations().advance(old, null);
                          }));
      assertThat(advanceEntered.await(10L, TimeUnit.SECONDS)).isTrue();
      try {
        assertThatThrownBy(() -> advanced.get(150L, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        releaseObservation.countDown();
      }
      assertThat(observed.get(10L, TimeUnit.SECONDS).authority().issuer().generation())
          .isEqualTo(1L);
      assertThat(advanced.get(10L, TimeUnit.SECONDS).generation()).isEqualTo(2L);
      assertThatThrownBy(
              () ->
                  fixture
                      .tx()
                      .execute(status -> fixture.source().inspectUnscopedCurrent(candidate)))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      releaseObservation.countDown();
    }
  }

  private static InspectedToken candidate(UUID accountId) throws Exception {
    return candidate(accountId, 1L);
  }

  private static InspectedToken candidate(UUID accountId, long issuerVersion) throws Exception {
    var crypto =
        new AccountControlUiTokenFixture(
            Clock.fixed(AccountControlUiTokenFixture.NOW, ZoneOffset.UTC));
    var claims = crypto.claims(accountId);
    @SuppressWarnings("unchecked")
    var tuple = new java.util.LinkedHashMap<>((Map<String, Object>) claims.get("authorityTuple"));
    tuple.put("issuerAuthGeneration", Long.toString(issuerVersion));
    claims.put("authorityTuple", tuple);
    String token = crypto.sign(claims);
    var registry = crypto.registry(token, claims);
    registry.put(
        "authoritySourceVersions",
        Map.of(
            "issuerSourceVersion",
            issuerVersion,
            "accountSourceVersion",
            1L,
            "issuanceFenceSourceVersion",
            1L));
    return crypto
        .checks()
        .inspect(
            "fresh-creator-candidate",
            token,
            AccountControlUiTokenFixture.canonical(registry),
            AccountControlUiTokenFixture.unscopedShape());
  }

  private static Fixture fixture(boolean enrollAuthority) {
    String schema = "control_ui_source_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("65"))
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var accounts = new AccountRepository(dsl);
    Account account = new Account();
    account.setUsername("control_ui_source");
    account.setEmail("control-ui-source@example.test");
    account.setPasswordHash("test-only-not-a-credential-proof");
    accounts.save(account);
    var generations = new AccountAuthorityGenerationRepository(dsl);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    if (enrollAuthority)
      tx.execute(
          status -> {
            generations.initializeIssuerIfAbsent(ISSUER);
            return generations.initialize(AuthorityScope.account(account.getAccountUuid()));
          });
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var issuer =
        new AccountIssuerAuthorityEventProducer(
            ISSUER,
            generations,
            outbox,
            dsl,
            java.util.Objects.requireNonNull(tx.getTransactionManager()));
    var readback =
        new AccountAuthoritySourceEventReadback(
            outbox,
            new AccountPasswordResetOperationRepository(dsl),
            new AccountLogoutAllOperationRepository(dsl),
            new net.firedevops.firemud.accountservice.repository
                .AccountSecurityStateOperationRepository(dsl));
    var source =
        new AccountControlUiCurrentSourceRepository(
            dsl,
            accounts,
            new AccountGlobalRoleSourceRepository(dsl, dataSource),
            generations,
            readback,
            issuer);
    return new Fixture(dsl, tx, account.getAccountUuid(), generations, source, issuer, outbox);
  }

  private static Long count(DSLContext dsl, String table) {
    return java.util.Objects.requireNonNull(
            dsl.fetchOne("SELECT COUNT(*) AS row_count FROM " + table),
            "Count query returned no row")
        .get("row_count", Long.class);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10L, TimeUnit.SECONDS))
        throw new IllegalStateException("Fixture latch timed out");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Fixture was interrupted", failure);
    }
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate tx,
      UUID accountId,
      AccountAuthorityGenerationRepository generations,
      AccountControlUiCurrentSourceRepository source,
      AccountIssuerAuthorityEventProducer issuer,
      AccountAuthorityOutboxRepository outbox) {}
}
