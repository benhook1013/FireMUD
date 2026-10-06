package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.accountservice.service.RedisIssuerGenerationProjectionStore;
import net.firedevops.firemud.accountservice.service.RedisIssuerGenerationProjectionStore.CacheRateLimitEndpoint;
import net.firedevops.firemud.accountservice.service.RedisIssuerGenerationProjectionStore.CoordinationEndpoint;
import net.firedevops.firemud.accountservice.service.RedisIssuerGenerationProjectionStore.Outcome;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real owner SQL and Coordination projection proof; not complete bundle or issuance proof. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class IssuerGenerationProjectionPostgresRedisIntegrationTest {
  private static final String ISSUER = ControlUiJwtProfileValidator.ISSUER;
  private static final String KEY = IssuerGenerationProjection.keyForIssuer(ISSUER);
  private static final String PASSWORD = "issuer-projection-test-only-secret";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--save",
              "",
              "--appendonly",
              "no",
              "--user",
              "account_coord_app",
              "on",
              ">" + PASSWORD,
              "~" + IssuerGenerationProjection.KEY_PREFIX + "*",
              "+get",
              "+set",
              "+pttl",
              "+evalsha",
              "+script|load");

  private final List<RedisIssuerGenerationProjectionStore> stores = new ArrayList<>();
  private LettuceConnectionFactory adminFactory;
  private LettuceConnectionFactory ownerFactory;
  private StringRedisTemplate admin;
  private StringRedisTemplate owner;

  @BeforeEach
  void connections() {
    adminFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
    adminFactory.afterPropertiesSet();
    admin = new StringRedisTemplate(adminFactory);
    admin.afterPropertiesSet();
    admin.delete(KEY);
    var configuration =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
    configuration.setUsername("account_coord_app");
    configuration.setPassword(RedisPassword.of(PASSWORD));
    ownerFactory = new LettuceConnectionFactory(configuration);
    ownerFactory.afterPropertiesSet();
    owner = new StringRedisTemplate(ownerFactory);
    owner.afterPropertiesSet();
  }

  @AfterEach
  void closeConnections() {
    stores.forEach(RedisIssuerGenerationProjectionStore::close);
    if (adminFactory != null) adminFactory.destroy();
    if (ownerFactory != null) ownerFactory.destroy();
  }

  @Test
  void installsActualIssuerBaselineThenLatestAdvanceAndExactRetryWithoutSqlMutationOrTtl() {
    Fixture fixture = fixture(true);
    var store = store(fixture.producer());
    var baseline = store.refreshCurrent(ISSUER);
    assertThat(baseline.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(baseline.snapshot().orElseThrow().json())
        .isEqualTo(
            IssuerGenerationProjection.fromSource(fixture.producer().readCurrent(ISSUER)).toJson());
    assertThat(baseline.snapshot().orElseThrow().json()).doesNotContain("sourceEvent");
    fixture.producer().advance(ISSUER, UUID.randomUUID(), 1L, 1L);
    var latestEvent = fixture.producer().advance(ISSUER, UUID.randomUUID(), 2L, 2L);
    var durable = fixture.producer().readCurrent(ISSUER);
    var sqlBefore =
        fixture
            .dsl()
            .fetch("SELECT * FROM account_authority_outbox_events ORDER BY outbox_sequence");
    var applied = store.refreshCurrent(ISSUER);
    assertThat(applied.outcome()).isEqualTo(Outcome.APPLIED);
    var projection = IssuerGenerationProjection.parse(applied.snapshot().orElseThrow().json());
    assertThat(projection).isEqualTo(IssuerGenerationProjection.fromSource(durable));
    assertThat(projection.lastAppliedSourceOutboxSequence()).isEqualTo("2");
    assertThat(projection.sourceEvent()).contains(latestEvent.canonicalJson());
    assertThat(projection.lastAppliedSourceEventId()).contains(latestEvent.eventId());
    assertThat(projection.lastAppliedSourceEventDigest()).contains(latestEvent.eventDigest());
    var replay = store.refreshCurrent(ISSUER);
    assertThat(replay.outcome()).isEqualTo(Outcome.REPLAYED);
    assertThat(replay.snapshot()).isEqualTo(applied.snapshot());
    assertThat(admin.opsForValue().get(KEY)).isEqualTo(projection.toJson());
    assertThat(admin.getExpire(KEY, TimeUnit.MILLISECONDS)).isEqualTo(-1L);
    assertThat(
            fixture
                .dsl()
                .fetch("SELECT * FROM account_authority_outbox_events ORDER BY outbox_sequence"))
        .isEqualTo(sqlBefore);
  }

  @Test
  void malformedProjectionAndTtlDenyWithoutRepairingStoredBytes() {
    Fixture fixture = fixture(true);
    var store = store(fixture.producer());
    admin.opsForValue().set(KEY, "{\"unproved\":true}");
    assertThat(store.refreshCurrent(ISSUER).outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(admin.opsForValue().get(KEY)).isEqualTo("{\"unproved\":true}");
    String baseline =
        IssuerGenerationProjection.fromSource(fixture.producer().readCurrent(ISSUER)).toJson();
    admin.opsForValue().set(KEY, baseline, 1L, TimeUnit.HOURS);
    assertThat(store.refreshCurrent(ISSUER).outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(admin.opsForValue().get(KEY)).isEqualTo(baseline);
    assertThat(admin.getExpire(KEY, TimeUnit.MILLISECONDS)).isPositive();
  }

  @Test
  void aheadSourceAndSameCheckpointDifferentActualEventDenyWithoutOverwrite() {
    Fixture first = fixture(true);
    first.producer().advance(ISSUER, UUID.randomUUID(), 1L, 1L);
    String firstJson =
        IssuerGenerationProjection.fromSource(first.producer().readCurrent(ISSUER)).toJson();
    admin.opsForValue().set(KEY, firstJson);
    Fixture second = fixture(true);
    var secondStore = store(second.producer());
    assertThat(secondStore.refreshCurrent(ISSUER).outcome()).isEqualTo(Outcome.STALE_SOURCE);
    second.producer().advance(ISSUER, UUID.randomUUID(), 1L, 1L);
    var mismatch = secondStore.refreshCurrent(ISSUER);
    assertThat(mismatch.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(mismatch.detail()).contains("SAME_CHECKPOINT_DISAGREEMENT");
    assertThat(admin.opsForValue().get(KEY)).isEqualTo(firstJson);
  }

  @Test
  void actualCommittedSourceAdvanceBetweenOwnerReadsReturnsSourceChangedWithoutImplicitRetry() {
    Fixture fixture = fixture(true);
    AtomicInteger reads = new AtomicInteger();
    // Test-only scheduling hook. Both read results and the intervening advance use actual SQL.
    PlatformTransactionManager interleaving =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (reads.incrementAndGet() == 2) {
              fixture.producer().advance(ISSUER, UUID.randomUUID(), 1L, 1L);
            }
            return fixture.manager().getTransaction(definition);
          }

          @Override
          public void commit(TransactionStatus status) {
            fixture.manager().commit(status);
          }

          @Override
          public void rollback(TransactionStatus status) {
            fixture.manager().rollback(status);
          }
        };
    var reader =
        new AccountIssuerAuthorityEventProducer(
            ISSUER, fixture.generations(), fixture.outbox(), fixture.dsl(), interleaving);
    var store = store(reader);
    var result = store.refreshCurrent(ISSUER);
    assertThat(result.outcome()).isEqualTo(Outcome.SOURCE_CHANGED);
    assertThat(reads.get()).isEqualTo(2);
    assertThat(
            IssuerGenerationProjection.parse(admin.opsForValue().get(KEY)).issuerAuthGeneration())
        .isEqualTo("1");
    assertThat(fixture.producer().readCurrent(ISSUER).issuerAuthGeneration()).isEqualTo(2L);
    assertThat(store.refreshCurrent(ISSUER).outcome()).isEqualTo(Outcome.APPLIED);
  }

  @Test
  void missingAndContradictoryOwnerHistoryDenyWithoutEnrollmentOrRedisWrite() {
    Fixture missing = fixture(false);
    assertThatThrownBy(() -> store(missing.producer()).refreshCurrent(ISSUER))
        .isInstanceOf(IllegalStateException.class);
    assertThat(missing.dsl().fetchCount(DSL.table("account_authority_generations"))).isZero();
    assertThat(admin.opsForValue().get(KEY)).isNull();
    Fixture malformed = fixture(true);
    new TransactionTemplate(malformed.manager())
        .executeWithoutResult(
            status ->
                malformed
                    .generations()
                    .advance(malformed.generations().read(AuthorityScope.issuer(ISSUER)), null));
    assertThatThrownBy(() -> store(malformed.producer()).refreshCurrent(ISSUER))
        .isInstanceOf(IllegalStateException.class);
    assertThat(admin.opsForValue().get(KEY)).isNull();
  }

  @Test
  void explicitInitKnownIssuerEndpointSeparationAndAclPreventRoleOrDirectEvalSubstitution() {
    Fixture fixture = fixture(true);
    var uninitialized =
        new RedisIssuerGenerationProjectionStore(fixture.producer(), endpoint(), cache());
    stores.add(uninitialized);
    assertThatThrownBy(() -> uninitialized.refreshCurrent(ISSUER))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new RedisIssuerGenerationProjectionStore(
                    fixture.producer(),
                    endpoint(),
                    new CacheRateLimitEndpoint(redis.getHost(), redis.getMappedPort(6379))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CoordinationEndpoint(
                    redis.getHost(), redis.getMappedPort(6379), "cache_app", PASSWORD))
        .isInstanceOf(IllegalArgumentException.class);
    var store = store(fixture.producer());
    assertThatThrownBy(() -> store.refreshCurrent("other-issuer"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> owner.opsForValue().get("session:game:auth:issuer-generation:v1:" + ISSUER))
        .satisfies(failure -> assertThat(messageChain(failure)).contains("NOPERM"));
    assertThatThrownBy(() -> owner.opsForValue().get("ratelimit:test"))
        .satisfies(failure -> assertThat(messageChain(failure)).contains("NOPERM"));
    assertThatThrownBy(
            () ->
                owner.execute(
                    (RedisCallback<Object>)
                        connection ->
                            connection
                                .scriptingCommands()
                                .eval(
                                    "return 1".getBytes(StandardCharsets.UTF_8),
                                    ReturnType.INTEGER,
                                    0)))
        .satisfies(failure -> assertThat(messageChain(failure)).contains("NOPERM"));
    assertThat(admin.opsForValue().get(KEY)).isNull();
    store.close();
    assertThatThrownBy(() -> store.refreshCurrent(ISSUER))
        .isInstanceOf(IllegalStateException.class);
  }

  private RedisIssuerGenerationProjectionStore store(AccountIssuerAuthorityEventProducer producer) {
    var store = new RedisIssuerGenerationProjectionStore(producer, endpoint(), cache());
    stores.add(store);
    store.init();
    return store;
  }

  private CoordinationEndpoint endpoint() {
    return new CoordinationEndpoint(
        redis.getHost(), redis.getMappedPort(6379), "account_coord_app", PASSWORD);
  }

  private CacheRateLimitEndpoint cache() {
    return new CacheRateLimitEndpoint("cache-role.example.test", 6379);
  }

  private static Fixture fixture(boolean enroll) {
    String schema = "issuer_projection_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = new DriverManagerDataSource();
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
        .target("79")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(dataSource);
    var generations = new AccountAuthorityGenerationRepository(dsl);
    if (enroll)
      new TransactionTemplate(manager)
          .executeWithoutResult(status -> generations.initializeIssuerIfAbsent(ISSUER));
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var producer =
        new AccountIssuerAuthorityEventProducer(ISSUER, generations, outbox, dsl, manager);
    return new Fixture(dsl, manager, generations, outbox, producer);
  }

  private static String messageChain(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause())
      messages.append(current.getMessage()).append('\n');
    return messages.toString();
  }

  private record Fixture(
      DSLContext dsl,
      DataSourceTransactionManager manager,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox,
      AccountIssuerAuthorityEventProducer producer) {}
}
