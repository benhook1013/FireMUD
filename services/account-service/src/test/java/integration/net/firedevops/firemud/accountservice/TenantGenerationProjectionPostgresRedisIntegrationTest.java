package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountTenantAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.RedisTenantGenerationProjectionStore;
import net.firedevops.firemud.accountservice.service.RedisTenantGenerationProjectionStore.ApplyResult;
import net.firedevops.firemud.accountservice.service.RedisTenantGenerationProjectionStore.CacheRateLimitEndpoint;
import net.firedevops.firemud.accountservice.service.RedisTenantGenerationProjectionStore.CoordinationEndpoint;
import net.firedevops.firemud.accountservice.service.RedisTenantGenerationProjectionStore.Outcome;
import net.firedevops.firemud.accountservice.service.RedisTenantGenerationProjectionStore.ProjectionSnapshot;
import net.firedevops.firemud.accountservice.service.TenantGenerationProjection;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Account-owned tenant-generation projection proof over real SQL and isolated Coordination Redis.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class TenantGenerationProjectionPostgresRedisIntegrationTest {
  private static final String SCHEMA_PREFIX = "tenant_gen_projection";
  private static final String TEST_NAMESPACE = "account-service";
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);
  private static final String KEY_PREFIX = "session:auth:generation:tenant:";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:tenant/";
  private static final String EVENT_ID_PREFIX = "account-tenant-generation-event-v1:";
  private static final String COORD_PASSWORD = "tenant-generation-projection-integration-secret";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final AtomicLong SOURCE_GAME_ROW_SEQUENCE = new AtomicLong(730L);

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
              ">" + COORD_PASSWORD,
              "~" + KEY_PREFIX + "*",
              "+get",
              "+set",
              "+pttl",
              "+evalsha",
              "+script|load");

  private final List<RedisTenantGenerationProjectionStore> stores = new ArrayList<>();
  private LettuceConnectionFactory adminConnectionFactory;
  private StringRedisTemplate adminTemplate;
  private LettuceConnectionFactory ownerConnectionFactory;
  private StringRedisTemplate ownerTemplate;

  @BeforeEach
  void startTestOnlyRedisAdminReadback() {
    adminConnectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
    adminConnectionFactory.afterPropertiesSet();
    adminTemplate = new StringRedisTemplate(adminConnectionFactory);
    adminTemplate.afterPropertiesSet();

    RedisStandaloneConfiguration ownerConfiguration =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
    ownerConfiguration.setUsername("account_coord_app");
    ownerConfiguration.setPassword(RedisPassword.of(COORD_PASSWORD));
    ownerConnectionFactory = new LettuceConnectionFactory(ownerConfiguration);
    ownerConnectionFactory.afterPropertiesSet();
    ownerTemplate = new StringRedisTemplate(ownerConnectionFactory);
    ownerTemplate.afterPropertiesSet();
  }

  @AfterEach
  void closeConnections() {
    stores.forEach(RedisTenantGenerationProjectionStore::close);
    stores.clear();
    if (adminConnectionFactory != null) {
      adminConnectionFactory.destroy();
    }
    if (ownerConnectionFactory != null) {
      ownerConnectionFactory.destroy();
    }
  }

  @Test
  void installsOnlyTheProvenPristineTenantBaselineAndReadsBackExactBytes() {
    Fixture fixture = newFixture();
    UUID tenantId = UUID.randomUUID();
    seed(fixture, tenantId);
    AccountTenantAuthorityEventProducer producer = fixture.producer();
    AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot baseline =
        producer.readCurrent(tenantId);

    assertThat(baseline.tenantAuthorityGeneration()).isEqualTo(1L);
    assertThat(baseline.sourceVersion()).isEqualTo(1L);
    assertThat(baseline.outboxSequence()).isZero();
    assertThat(baseline.latestEvent()).isEmpty();
    SqlState before = sqlState(fixture, tenantId);
    ApplyResult result = store(producer).refreshCurrent(tenantId);

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    ProjectionSnapshot projection = result.snapshot().orElseThrow();
    assertThat(projection.key()).isEqualTo(key(tenantId));
    JsonNode json = parse(projection.json());
    assertThat(json.path("schemaVersion").asText())
        .isEqualTo("account-auth-tenant-generation-projection/v1");
    assertThat(json.path("tenantId").asText()).isEqualTo(tenantId.toString());
    assertThat(json.path("tenantAuthorityGeneration").asText()).isEqualTo("1");
    assertThat(json.path("sourceVersion").asText()).isEqualTo("1");
    assertThat(json.path("outboxStreamKey").asText()).isEqualTo(streamKey(tenantId));
    assertThat(json.path("outboxSequence").asText()).isEqualTo("0");
    assertThat(json.has("sourceEvent")).isFalse();
    assertThat(fields(json))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "tenantId",
            "tenantAuthorityGeneration",
            "sourceVersion",
            "outboxStreamKey",
            "outboxSequence");
    assertThat(adminTemplate.opsForValue().get(projection.key())).isEqualTo(projection.json());
    assertThat(adminTemplate.getExpire(projection.key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
    assertThat(sqlState(fixture, tenantId)).isEqualTo(before);
  }

  @Test
  void projectsCompleteSourceEventReplaysExactlyAndRepairsOnlyTheMissingRedisKey() {
    Fixture fixture = newFixture();
    UUID tenantId = UUID.randomUUID();
    seed(fixture, tenantId);
    AccountTenantAuthorityEventProducer producer = fixture.producer();
    RedisTenantGenerationProjectionStore store = store(producer);
    store.refreshCurrent(tenantId);
    ScopeState accountAuthorityBeforeAdvance =
        readAuthority(fixture, AuthorityScope.account(fixture.accountUuid()));
    AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot baseline =
        producer.readCurrent(tenantId);

    UUID requestId = UUID.randomUUID();
    var event = producer.advance(tenantId, requestId, 1L, 1L);
    var exactRetry = producer.advance(tenantId, requestId, 1L, 1L);
    assertSameEvent(event, exactRetry);
    SqlState afterSourceAdvance = sqlState(fixture, tenantId);

    ApplyResult projected = store.refreshCurrent(tenantId);
    assertThat(projected.outcome()).isEqualTo(Outcome.APPLIED);
    ProjectionSnapshot installed = projected.snapshot().orElseThrow();
    JsonNode json = parse(installed.json());
    assertThat(json.path("tenantAuthorityGeneration").asText()).isEqualTo("2");
    assertThat(json.path("sourceVersion").asText()).isEqualTo("2");
    assertThat(json.path("outboxStreamKey").asText()).isEqualTo(baseline.outboxStreamKey());
    assertThat(json.path("outboxSequence").asText()).isEqualTo("1");
    assertThat(json.path("sourceEvent").asText()).isEqualTo(event.canonicalJson());
    assertThat(fields(json))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "tenantId",
            "tenantAuthorityGeneration",
            "sourceVersion",
            "outboxStreamKey",
            "outboxSequence",
            "sourceEvent");
    assertThat(
            TenantGenerationAuthorityEventV1Codec.verify(json.path("sourceEvent").asText())
                .canonicalJson())
        .isEqualTo(event.canonicalJson());
    assertThat(adminTemplate.opsForValue().get(installed.key())).isEqualTo(installed.json());
    assertThat(adminTemplate.getExpire(installed.key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
    assertThat(event.canonicalJson())
        .doesNotContain("recipientAccount", "membership", "issuanceFence", "billing");
    assertThat(afterSourceAdvance.accountAuthority()).isEqualTo(accountAuthorityBeforeAdvance);
    assertThat(sqlState(fixture, tenantId)).isEqualTo(afterSourceAdvance);

    ApplyResult replay = store.refreshCurrent(tenantId);
    assertThat(replay.outcome()).isEqualTo(Outcome.REPLAYED);
    assertThat(replay.snapshot()).contains(installed);
    assertThat(sqlState(fixture, tenantId)).isEqualTo(afterSourceAdvance);

    assertThat(adminTemplate.delete(installed.key())).isTrue();
    ApplyResult repaired = store.refreshCurrent(tenantId);
    assertThat(repaired.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(repaired.snapshot()).contains(installed);
    assertThat(adminTemplate.opsForValue().get(installed.key())).isEqualTo(installed.json());
    assertThat(sqlState(fixture, tenantId)).isEqualTo(afterSourceAdvance);
  }

  @Test
  void unknownAndAssociatedButUnenrolledTenantsCannotSynthesizeABaseline() {
    Fixture fixture = newFixture();
    UUID enrolledTenant = UUID.randomUUID();
    UUID unknownTenant = UUID.randomUUID();
    UUID associatedUnenrolledTenant = UUID.randomUUID();
    seed(fixture, enrolledTenant);
    importAssociationOnly(fixture, associatedUnenrolledTenant);
    RedisTenantGenerationProjectionStore store = store(fixture.producer());

    assertThatThrownBy(() -> store.refreshCurrent(unknownTenant))
        .isInstanceOf(IllegalStateException.class);
    assertThat(adminTemplate.opsForValue().get(key(unknownTenant))).isNull();
    assertThatThrownBy(() -> store.refreshCurrent(associatedUnenrolledTenant))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account authority generation is missing");
    assertThat(adminTemplate.opsForValue().get(key(associatedUnenrolledTenant))).isNull();
    assertThat(countTenantGenerations(fixture)).isEqualTo(1L);
    assertThat(countTenantEvents(fixture)).isZero();
    assertThat(countTenantStreams(fixture)).isZero();
    assertThat(count(fixture, "account_fresh_tenant_identity_associations")).isEqualTo(2L);
  }

  @Test
  void sourceAdvanceAfterCandidateReadReturnsNonconvergedWithoutChangingSqlHistory() {
    Fixture fixture = newFixture();
    UUID tenantId = UUID.randomUUID();
    seed(fixture, tenantId);
    AccountTenantAuthorityEventProducer delegate = fixture.producer();
    UUID requestId = UUID.randomUUID();
    AtomicInteger reads = new AtomicInteger();
    AtomicReference<SqlState> sourceAfterAdvance = new AtomicReference<>();
    AccountTenantAuthorityEventProducer controlledProducer =
        forwardingProducer(
            delegate,
            (id, current) -> {
              if (reads.incrementAndGet() == 1) {
                delegate.advance(
                    id, requestId, current.tenantAuthorityGeneration(), current.sourceVersion());
                sourceAfterAdvance.set(sqlState(fixture, id));
              }
            });
    RedisTenantGenerationProjectionStore store = store(controlledProducer);

    ApplyResult result = store.refreshCurrent(tenantId);

    assertThat(result.outcome()).isEqualTo(Outcome.SOURCE_CHANGED);
    assertThat(result.snapshot()).isPresent();
    JsonNode installed = parse(adminTemplate.opsForValue().get(key(tenantId)));
    assertThat(installed.path("tenantAuthorityGeneration").asText()).isEqualTo("1");
    assertThat(installed.path("sourceVersion").asText()).isEqualTo("1");
    assertThat(installed.path("outboxSequence").asText()).isEqualTo("0");
    assertThat(delegate.readCurrent(tenantId).tenantAuthorityGeneration()).isEqualTo(2L);
    assertThat(sourceAfterAdvance.get()).isNotNull();
    assertThat(sourceAfterAdvance.get().tenantEventHistory()).hasSize(1);
    assertThat(sqlState(fixture, tenantId)).isEqualTo(sourceAfterAdvance.get());
  }

  @Test
  void staleCandidateCannotReplaceAProjectionFromANewerTenantCommit() throws Exception {
    Fixture fixture = newFixture();
    UUID tenantId = UUID.randomUUID();
    seed(fixture, tenantId);
    AccountTenantAuthorityEventProducer delegate = fixture.producer();
    CountDownLatch staleReadReturned = new CountDownLatch(1);
    CountDownLatch releaseStaleCandidate = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    AccountTenantAuthorityEventProducer controlledProducer =
        forwardingProducer(
            delegate,
            (id, current) -> {
              if (reads.incrementAndGet() == 1) {
                staleReadReturned.countDown();
                await(releaseStaleCandidate);
              }
            });
    RedisTenantGenerationProjectionStore staleStore = store(controlledProducer);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      var staleRefresh = executor.submit(() -> staleStore.refreshCurrent(tenantId));
      assertThat(staleReadReturned.await(20, TimeUnit.SECONDS)).isTrue();

      delegate.advance(tenantId, UUID.randomUUID(), 1L, 1L);
      ApplyResult current = store(delegate).refreshCurrent(tenantId);
      assertThat(current.outcome()).isEqualTo(Outcome.APPLIED);
      String newerBytes = current.snapshot().orElseThrow().json();
      assertThat(parse(newerBytes).path("outboxSequence").asText()).isEqualTo("1");
      SqlState sourceBeforeStaleFinish = sqlState(fixture, tenantId);

      releaseStaleCandidate.countDown();
      ApplyResult staleResult = staleRefresh.get(45, TimeUnit.SECONDS);
      assertThat(staleResult.outcome()).isIn(Outcome.STALE, Outcome.STALE_SOURCE);
      assertThat(staleResult.outcome()).isNotIn(Outcome.APPLIED, Outcome.REPLAYED);
      assertThat(adminTemplate.opsForValue().get(key(tenantId))).isEqualTo(newerBytes);
      assertThat(sqlState(fixture, tenantId)).isEqualTo(sourceBeforeStaleFinish);
    } finally {
      releaseStaleCandidate.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void malformedExpiringAheadAndSameCheckpointRedisValuesAreQuarantinedWithoutWrites() {
    Fixture fixture = newFixture();
    UUID tenantId = UUID.randomUUID();
    seed(fixture, tenantId);
    AccountTenantAuthorityEventProducer producer = fixture.producer();
    RedisTenantGenerationProjectionStore store = store(producer);
    store.refreshCurrent(tenantId);
    var sourceEvent = producer.advance(tenantId, UUID.randomUUID(), 1L, 1L);
    ProjectionSnapshot current = store.refreshCurrent(tenantId).snapshot().orElseThrow();
    SqlState sourceBeforePoison = sqlState(fixture, tenantId);

    adminTemplate.opsForValue().set(current.key(), "{\"schemaVersion\":\"unknown\"}");
    String malformed = adminTemplate.opsForValue().get(current.key());
    ApplyResult malformedResult = store.refreshCurrent(tenantId);
    assertThat(malformedResult.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(adminTemplate.opsForValue().get(current.key())).isEqualTo(malformed);

    adminTemplate.opsForValue().set(current.key(), current.json());
    adminTemplate.expire(current.key(), 30L, TimeUnit.SECONDS);
    Long ttlBefore = adminTemplate.getExpire(current.key(), TimeUnit.MILLISECONDS);
    ApplyResult ttlResult = store.refreshCurrent(tenantId);
    assertThat(ttlResult.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(adminTemplate.opsForValue().get(current.key())).isEqualTo(current.json());
    assertThat(adminTemplate.getExpire(current.key(), TimeUnit.MILLISECONDS))
        .isPositive()
        .isLessThanOrEqualTo(ttlBefore);

    adminTemplate.persist(current.key());
    TenantGenerationProjection ahead =
        syntheticProjection(tenantId, 999L, 999L, 998L, UUID.randomUUID());
    TenantGenerationProjection.parse(ahead.toJson());
    adminTemplate.opsForValue().set(current.key(), ahead.toJson());
    ApplyResult aheadResult = store.refreshCurrent(tenantId);
    assertThat(aheadResult.outcome()).isEqualTo(Outcome.STALE_SOURCE);
    assertThat(adminTemplate.opsForValue().get(current.key())).isEqualTo(ahead.toJson());

    TenantGenerationProjection conflicting =
        syntheticProjection(tenantId, 2L, 2L, 1L, UUID.randomUUID());
    TenantGenerationProjection.parse(conflicting.toJson());
    adminTemplate.opsForValue().set(current.key(), conflicting.toJson());
    ApplyResult conflictResult = store.refreshCurrent(tenantId);
    assertThat(conflictResult.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(conflictResult.detail()).contains("SAME_CHECKPOINT_DISAGREEMENT");
    assertThat(adminTemplate.opsForValue().get(current.key())).isEqualTo(conflicting.toJson());
    assertThat(sqlState(fixture, tenantId)).isEqualTo(sourceBeforePoison);
    assertThat(sourceEvent.canonicalJson())
        .isEqualTo(producer.readCurrent(tenantId).latestEvent().orElseThrow().canonicalJson());
  }

  @Test
  void accountCoordinationPrincipalCanUseOnlyTenantProjectionKeysAndCannotInvokeEval() {
    Fixture fixture = newFixture();
    UUID tenantId = UUID.randomUUID();
    seed(fixture, tenantId);
    store(fixture.producer()).refreshCurrent(tenantId);

    assertThatThrownBy(
            () ->
                ownerTemplate.execute(
                    (RedisCallback<Object>)
                        connection ->
                            connection
                                .scriptingCommands()
                                .eval(
                                    "return 1".getBytes(StandardCharsets.UTF_8),
                                    ReturnType.INTEGER,
                                    0)))
        .satisfies(failure -> assertThat(exceptionMessageChain(failure)).contains("NOPERM"));
    assertThatThrownBy(
            () ->
                ownerTemplate
                    .opsForValue()
                    .get("session:auth:generation:account:" + UUID.randomUUID()))
        .satisfies(failure -> assertThat(exceptionMessageChain(failure)).contains("NOPERM"));
    assertThat(ownerTemplate.opsForValue().get(key(tenantId))).isNotNull();
  }

  private Fixture newFixture() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
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
        .load()
        .migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    String suffix = UUID.randomUUID().toString();
    Record account =
        setupDsl.fetchOne(
            "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING account_uuid",
            "tenant-proof-" + suffix,
            "tenant-proof-" + suffix + "@example.test",
            "integration-proof-hash");
    if (account == null || account.get("account_uuid", UUID.class) == null) {
      throw new IllegalStateException("Account source fixture UUID was not persisted");
    }

    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(transactionDsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(transactionDsl);
    FreshTenantIdentityAssociationRepository associations =
        new FreshTenantIdentityAssociationRepository(transactionDsl, TEST_NAMESPACE);
    return new Fixture(
        setupDsl,
        transactionDsl,
        transactionManager,
        transaction,
        generations,
        outbox,
        associations,
        account.get("account_uuid", UUID.class));
  }

  private void seed(Fixture fixture, UUID tenantId) {
    FreshTenantCreationEvidence association = tenantEvidence(tenantId);
    transaction(
        fixture.transaction(),
        () -> {
          fixture.associations().importVerified(association);
          fixture.generations().initializeTenantIfAbsent(tenantId);
          fixture.generations().initialize(AuthorityScope.account(fixture.accountUuid()));
          return null;
        });
  }

  private void importAssociationOnly(Fixture fixture, UUID tenantId) {
    FreshTenantCreationEvidence association = tenantEvidence(tenantId);
    transaction(
        fixture.transaction(),
        () -> {
          fixture.associations().importVerified(association);
          return null;
        });
  }

  private FreshTenantCreationEvidence tenantEvidence(UUID tenantId) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceGameTenantKey = "tn-" + UUID.randomUUID().toString().replace("-", "");
    long sourceGameRowId = SOURCE_GAME_ROW_SEQUENCE.incrementAndGet();
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            requestId,
            operationId,
            REQUEST_DIGEST,
            tenantId,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        requestId,
        operationId,
        REQUEST_DIGEST,
        tenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private RedisTenantGenerationProjectionStore store(AccountTenantAuthorityEventProducer producer) {
    RedisTenantGenerationProjectionStore store =
        new RedisTenantGenerationProjectionStore(
            producer,
            new CoordinationEndpoint(
                redis.getHost(), redis.getMappedPort(6379), "account_coord_app", COORD_PASSWORD),
            new CacheRateLimitEndpoint("cache.invalid.test", 6380));
    store.init();
    stores.add(store);
    return store;
  }

  private AccountTenantAuthorityEventProducer forwardingProducer(
      AccountTenantAuthorityEventProducer delegate, SourceReadHook hook) {
    AccountTenantAuthorityEventProducer forwarding =
        mock(AccountTenantAuthorityEventProducer.class);
    when(forwarding.readCurrent(any(UUID.class)))
        .thenAnswer(
            invocation -> {
              UUID tenantId = invocation.getArgument(0);
              var current = delegate.readCurrent(tenantId);
              hook.afterRead(tenantId, current);
              return current;
            });
    return forwarding;
  }

  private TenantGenerationProjection syntheticProjection(
      UUID tenantId, long generation, long sourceVersion, long sequence, UUID requestId) {
    String tenantText = tenantId.toString();
    String tenantScope = "tenant/" + tenantText;
    String projectionStreamKey = streamKey(tenantId);
    var event =
        TenantGenerationAuthorityEventV1Codec.seal(
            Map.of(
                "schemaVersion",
                TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                "eventType",
                TenantGenerationAuthorityEventV1Codec.EVENT_TYPE,
                "eventId",
                EVENT_ID_PREFIX + requestId,
                "requestId",
                requestId.toString(),
                "tenantId",
                tenantText,
                "sourceScope",
                tenantScope,
                "outboxStreamKey",
                projectionStreamKey,
                "outboxSequence",
                Long.toString(sequence),
                "tenantAuthorityGeneration",
                Long.toString(generation),
                "sourceVersion",
                Long.toString(sourceVersion)));
    return new TenantGenerationProjection(
        tenantText,
        Long.toString(generation),
        Long.toString(sourceVersion),
        projectionStreamKey,
        Long.toString(sequence),
        Optional.of(event.canonicalJson()));
  }

  private SqlState sqlState(Fixture fixture, UUID tenantId) {
    ScopeState tenantState = readAuthority(fixture, AuthorityScope.tenant(tenantId));
    ScopeState accountState = readAuthority(fixture, AuthorityScope.account(fixture.accountUuid()));
    return new SqlState(
        tenantState,
        accountState,
        rows(fixture, tenantId),
        tenantEventHistory(fixture, tenantId),
        countTenantStreams(fixture));
  }

  private List<StoredRow> tenantEventHistory(Fixture fixture, UUID tenantId) {
    String stream = streamKey(tenantId);
    return fixture
        .setupDsl()
        .resultQuery(
            "SELECT outbox_stream_key, request_id, outbox_sequence, event_id, event_digest, payload "
                + "FROM account_authority_outbox_events WHERE outbox_stream_key = ? ORDER BY outbox_sequence",
            stream)
        .fetch(
            row ->
                new StoredRow(
                    row.get("outbox_stream_key", String.class),
                    row.get("request_id", String.class),
                    row.get("outbox_sequence", Long.class),
                    row.get("event_id", String.class),
                    row.get("event_digest", String.class),
                    HexFormat.of().formatHex(row.get("payload", byte[].class))));
  }

  private SourceRows rows(Fixture fixture, UUID tenantId) {
    return new SourceRows(
        countWhere(
            fixture, "account_fresh_tenant_identity_associations", "canonical_tenant_id", tenantId),
        countWhere(
            fixture, "account_canonical_tenant_identity_claims", "canonical_tenant_id", tenantId),
        countWhere(fixture, "account_authority_generations", "tenant_uuid", tenantId),
        countWhere(
            fixture, "account_authority_outbox_streams", "outbox_stream_key", streamKey(tenantId)),
        countWhere(fixture, "account_membership_pair_authority", "tenant_uuid", tenantId),
        count(fixture, "account_membership_transition_receipts"));
  }

  private long countTenantGenerations(Fixture fixture) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_generations WHERE scope_kind = 'TENANT'")
            .fetchOne(0, Long.class),
        "Tenant generation count is missing");
  }

  private long countTenantEvents(Fixture fixture) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key LIKE 'account:auth-authority:v1:tenant/%'")
            .fetchOne(0, Long.class),
        "Tenant outbox event count is missing");
  }

  private long countTenantStreams(Fixture fixture) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_streams "
                    + "WHERE outbox_stream_key LIKE 'account:auth-authority:v1:tenant/%'")
            .fetchOne(0, Long.class),
        "Tenant outbox stream count is missing");
  }

  private long countWhere(Fixture fixture, String table, String column, Object value) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", value)
            .fetchOne(0, Long.class),
        "SQL row count is missing for " + table);
  }

  private long count(Fixture fixture, String table) {
    return Objects.requireNonNull(
        fixture.setupDsl().resultQuery("SELECT COUNT(*) FROM " + table).fetchOne(0, Long.class),
        "SQL row count is missing for " + table);
  }

  private ScopeState readAuthority(Fixture fixture, AuthorityScope scope) {
    return transaction(fixture.transaction(), () -> fixture.generations().read(scope));
  }

  private void assertSameEvent(
      TenantGenerationAuthorityEventV1Codec.TenantGenerationAuthorityEvent expected,
      TenantGenerationAuthorityEventV1Codec.TenantGenerationAuthorityEvent actual) {
    assertThat(actual.schemaVersion()).isEqualTo(expected.schemaVersion());
    assertThat(actual.eventType()).isEqualTo(expected.eventType());
    assertThat(actual.eventId()).isEqualTo(expected.eventId());
    assertThat(actual.requestId()).isEqualTo(expected.requestId());
    assertThat(actual.tenantId()).isEqualTo(expected.tenantId());
    assertThat(actual.sourceScope()).isEqualTo(expected.sourceScope());
    assertThat(actual.outboxStreamKey()).isEqualTo(expected.outboxStreamKey());
    assertThat(actual.outboxSequence()).isEqualTo(expected.outboxSequence());
    assertThat(actual.tenantAuthorityGeneration()).isEqualTo(expected.tenantAuthorityGeneration());
    assertThat(actual.sourceVersion()).isEqualTo(expected.sourceVersion());
    assertThat(actual.eventDigest()).isEqualTo(expected.eventDigest());
    assertThat(actual.canonicalJson()).isEqualTo(expected.canonicalJson());
    assertThat(actual.canonicalJsonUtf8()).containsExactly(expected.canonicalJsonUtf8());
  }

  private JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception malformed) {
      throw new IllegalArgumentException("Redis projection JSON is malformed", malformed);
    }
  }

  private Set<String> fields(JsonNode json) {
    Set<String> fields = new HashSet<>();
    json.fieldNames().forEachRemaining(fields::add);
    return fields;
  }

  private String key(UUID tenantId) {
    return KEY_PREFIX + tenantId;
  }

  private String streamKey(UUID tenantId) {
    return STREAM_PREFIX + tenantId;
  }

  private String exceptionMessageChain(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    Throwable current = failure;
    while (current != null) {
      if (current.getMessage() != null) {
        messages.append(current.getMessage()).append('\n');
      }
      current = current.getCause();
    }
    return messages.toString();
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Tenant projection race barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Tenant projection race was interrupted", interrupted);
    }
  }

  private <T> T transaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record Fixture(
      DSLContext setupDsl,
      DSLContext transactionDsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transaction,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox,
      FreshTenantIdentityAssociationRepository associations,
      UUID accountUuid) {
    private AccountTenantAuthorityEventProducer producer() {
      return new AccountTenantAuthorityEventProducer(
          generations, outbox, transactionDsl, transactionManager);
    }
  }

  @FunctionalInterface
  private interface SourceReadHook {
    void afterRead(
        UUID tenantId, AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot snapshot);
  }

  private record SourceRows(
      long freshAssociationRows,
      long canonicalClaimRows,
      long tenantGenerationRows,
      long outboxStreamRows,
      long membershipAuthorityRows,
      long membershipReceiptRows) {}

  private record StoredRow(
      String streamKey,
      String requestId,
      long sequence,
      String eventId,
      String eventDigest,
      String payloadHex) {}

  private record SqlState(
      ScopeState tenantAuthority,
      ScopeState accountAuthority,
      SourceRows rows,
      List<StoredRow> tenantEventHistory,
      long tenantStreamCount) {}
}
