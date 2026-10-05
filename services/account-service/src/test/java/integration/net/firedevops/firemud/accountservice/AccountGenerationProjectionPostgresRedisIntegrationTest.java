package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
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
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer.LogoutAllResult;
import net.firedevops.firemud.accountservice.service.RedisAccountGenerationProjectionStore;
import net.firedevops.firemud.accountservice.service.RedisAccountGenerationProjectionStore.ApplyResult;
import net.firedevops.firemud.accountservice.service.RedisAccountGenerationProjectionStore.CacheRateLimitEndpoint;
import net.firedevops.firemud.accountservice.service.RedisAccountGenerationProjectionStore.CoordinationEndpoint;
import net.firedevops.firemud.accountservice.service.RedisAccountGenerationProjectionStore.Outcome;
import net.firedevops.firemud.accountservice.service.RedisAccountGenerationProjectionStore.ProjectionSnapshot;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Account-owned generation projection proof over real Account SQL and isolated Coordination Redis.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountGenerationProjectionPostgresRedisIntegrationTest {
  private static final String SCHEMA_PREFIX = "account_gen_projection";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final String KEY_PREFIX = AccountGenerationProjection.KEY_PREFIX;
  private static final String COORD_PASSWORD = "account-generation-projection-integration-secret";
  private static final String TOKEN_PROFILE = "control-ui";
  private static final ObjectMapper JSON = new ObjectMapper();

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

  private final List<RedisAccountGenerationProjectionStore> stores = new ArrayList<>();
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
    stores.forEach(RedisAccountGenerationProjectionStore::close);
    stores.clear();
    if (adminConnectionFactory != null) {
      adminConnectionFactory.destroy();
    }
    if (ownerConnectionFactory != null) {
      ownerConnectionFactory.destroy();
    }
  }

  @Test
  void installsPristineAccountBaselineFromCurrentDurableSourceAndReadsItBackExactly() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    AccountAuthoritySourceReader reader = sourceReader(fixture);
    AccountSourceSnapshot durableBaseline = reader.readCurrent(seed.accountUuid());

    assertThat(durableBaseline.sourceState().generation()).isEqualTo(1L);
    assertThat(durableBaseline.sourceState().sourceVersion()).isEqualTo(1L);
    assertThat(durableBaseline.outboxSequence()).isZero();
    assertThat(durableBaseline.latestEvent()).isEmpty();

    SqlState before = sqlState(fixture, seed);
    ApplyResult result = store(reader).refreshCurrent(seed.accountUuid());

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    ProjectionSnapshot projection = result.snapshot().orElseThrow();
    assertThat(projection.key()).isEqualTo(key(seed.accountUuid()));
    JsonNode json = parse(projection.json());
    assertThat(json.path("schemaVersion").asText())
        .isEqualTo("account-auth-account-generation-projection/v1");
    assertThat(json.path("accountId").asText()).isEqualTo(seed.accountUuid().toString());
    assertThat(json.path("accountAuthorityGeneration").asText()).isEqualTo("1");
    assertThat(json.path("sourceVersion").asText()).isEqualTo("1");
    assertThat(json.path("outboxStreamKey").asText()).isEqualTo(streamKey(seed.accountUuid()));
    assertThat(json.path("outboxSequence").asText()).isEqualTo("0");
    assertThat(json.has("sourceEvent")).isFalse();
    assertThat(fields(json))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "accountId",
            "accountAuthorityGeneration",
            "sourceVersion",
            "outboxStreamKey",
            "outboxSequence");
    assertThat(adminTemplate.opsForValue().get(projection.key())).isEqualTo(projection.json());
    assertThat(adminTemplate.getExpire(projection.key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
    assertThat(sqlState(fixture, seed)).isEqualTo(before);
  }

  @Test
  void accountCoordinationPrincipalCannotInvokeEvalDirectly() {
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
  }

  @Test
  void projectsActualPasswordResetAndLogoutAllCheckpointsWithoutRewritingSqlHistory() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    AccountAuthoritySourceReader reader = sourceReader(fixture);
    RedisAccountGenerationProjectionStore store = store(reader);

    reset(fixture, seed.rawToken(), "after-reset-password");
    var resetEvent = event(fixture, seed.accountUuid(), 1L);
    PasswordResetAuthorityEventV1Codec.verify(
        new String(resetEvent.payload(), StandardCharsets.UTF_8));
    SqlState afterResetProducer = sqlState(fixture, seed);
    ApplyResult resetProjection = store.refreshCurrent(seed.accountUuid());
    assertThat(resetProjection.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(
            parse(resetProjection.snapshot().orElseThrow().json())
                .path("accountAuthorityGeneration")
                .asText())
        .isEqualTo("2");
    assertThat(sqlState(fixture, seed)).isEqualTo(afterResetProducer);

    ScopeState expected = readAuthority(fixture, seed.accountUuid());
    UUID requestId = UUID.randomUUID();
    String tokenHash = sha256Hex("presented-control-token:" + requestId);
    String requestDigest =
        AccountLogoutRequestDigest.accountLogoutAll(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    LogoutAllResult logoutResult =
        logoutProducer(fixture)
            .commit(
                requestId,
                1,
                requestDigest,
                TOKEN_PROFILE,
                tokenHash,
                account(fixture, seed.accountId()),
                expected);
    assertThat(logoutResult).isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);

    var logoutEvent = event(fixture, seed.accountUuid(), 2L);
    AccountLogoutAllAuthorityEventV1Codec.verify(
        new String(logoutEvent.payload(), StandardCharsets.UTF_8));
    SqlState afterBothProducers = sqlState(fixture, seed);
    ApplyResult logoutProjection = store.refreshCurrent(seed.accountUuid());
    assertThat(logoutProjection.outcome()).isEqualTo(Outcome.APPLIED);
    ProjectionSnapshot currentProjection = logoutProjection.snapshot().orElseThrow();
    assertThat(currentProjection.key()).isEqualTo(key(seed.accountUuid()));
    JsonNode json = parse(currentProjection.json());
    assertThat(json.path("accountAuthorityGeneration").asText()).isEqualTo("3");
    assertThat(json.path("sourceVersion").asText()).isEqualTo("3");
    assertThat(json.path("outboxSequence").asText()).isEqualTo("2");
    assertThat(json.path("sourceEvent").asText())
        .isEqualTo(new String(logoutEvent.payload(), StandardCharsets.UTF_8));
    assertThat(fields(json))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "accountId",
            "accountAuthorityGeneration",
            "sourceVersion",
            "outboxStreamKey",
            "outboxSequence",
            "sourceEvent");
    assertThat(adminTemplate.opsForValue().get(currentProjection.key()))
        .isEqualTo(currentProjection.json());
    assertThat(sqlState(fixture, seed)).isEqualTo(afterBothProducers);

    ApplyResult duplicate = store.refreshCurrent(seed.accountUuid());
    assertThat(duplicate.outcome()).isEqualTo(Outcome.REPLAYED);
    assertThat(duplicate.snapshot()).contains(currentProjection);
    assertThat(adminTemplate.opsForValue().get(currentProjection.key()))
        .isEqualTo(currentProjection.json());
    assertThat(sqlState(fixture, seed)).isEqualTo(afterBothProducers);
  }

  @Test
  void committedPasswordResetReceiptSurvivesCoordinationAuthFailureExactRetryAndProjection() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    AccountAuthoritySourceReader reader = sourceReader(fixture);
    String tokenHash = sha256Hex(seed.rawToken());

    reset(fixture, seed.rawToken(), "committed-reset-password");
    PasswordResetReceipt committedReceipt = passwordResetReceipt(fixture, tokenHash);
    ScopeState committedAuthority = readAuthority(fixture, seed.accountUuid());
    var committedEvent = event(fixture, seed.accountUuid(), committedReceipt.outboxSequence());
    var committedCheckpoint =
        transaction(
            fixture.transaction(),
            () ->
                fixture.outbox().readCheckpoint(committedReceipt.outboxStreamKey()).orElseThrow());
    SqlState committedSql = sqlState(fixture, seed);

    assertThat(committedAuthority.generation()).isEqualTo(2L);
    assertThat(committedAuthority.sourceVersion()).isEqualTo(2L);
    assertThat(committedAuthority.issuanceFence().value()).isEqualTo(2L);
    assertThat(committedAuthority.issuanceFence().sourceVersion()).isEqualTo(2L);
    assertThat(committedEvent.outboxSequence()).isEqualTo(committedReceipt.outboxSequence());
    assertThat(committedEvent.eventId()).isEqualTo(committedReceipt.eventId());
    assertThat(committedEvent.eventDigest()).isEqualTo(committedReceipt.eventDigest());
    assertThat(committedCheckpoint.outboxSequence()).isEqualTo(committedReceipt.outboxSequence());
    assertThat(committedCheckpoint.sourceEventId()).isEqualTo(committedReceipt.eventId());
    assertThat(committedCheckpoint.sourceEventDigest()).isEqualTo(committedReceipt.eventDigest());

    RedisAccountGenerationProjectionStore deniedCoordinationStore =
        new RedisAccountGenerationProjectionStore(
            reader,
            new CoordinationEndpoint(
                redis.getHost(),
                redis.getMappedPort(6379),
                "account_coord_app",
                "deliberately-invalid-test-credential"),
            new CacheRateLimitEndpoint("cache.invalid.test", 6380));
    stores.add(deniedCoordinationStore);
    deniedCoordinationStore.init();

    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThatThrownBy(() -> deniedCoordinationStore.refreshCurrent(seed.accountUuid()))
        .satisfies(
            failure ->
                assertThat(exceptionMessageChain(failure))
                    .contains("WRONGPASS", "invalid username-password pair"));
    assertThat(adminTemplate.opsForValue().get(key(seed.accountUuid()))).isNull();
    assertThat(sqlState(fixture, seed)).isEqualTo(committedSql);
    assertThat(passwordResetReceipt(fixture, tokenHash)).isEqualTo(committedReceipt);
    assertThat(readAuthority(fixture, seed.accountUuid())).isEqualTo(committedAuthority);
    assertThat(event(fixture, seed.accountUuid(), committedReceipt.outboxSequence()))
        .isEqualTo(committedEvent);
    assertThat(
            transaction(
                fixture.transaction(),
                () ->
                    fixture
                        .outbox()
                        .readCheckpoint(committedReceipt.outboxStreamKey())
                        .orElseThrow()))
        .isEqualTo(committedCheckpoint);

    reset(fixture, seed.rawToken(), "committed-reset-password");
    assertThat(sqlState(fixture, seed)).isEqualTo(committedSql);
    assertThat(passwordResetReceipt(fixture, tokenHash)).isEqualTo(committedReceipt);
    assertThat(readAuthority(fixture, seed.accountUuid())).isEqualTo(committedAuthority);
    assertThat(event(fixture, seed.accountUuid(), committedReceipt.outboxSequence()))
        .isEqualTo(committedEvent);

    RedisAccountGenerationProjectionStore availableStore = store(reader);
    assertThatThrownBy(
            () ->
                transaction(
                    fixture.transaction(), () -> availableStore.refreshCurrent(seed.accountUuid())))
        .hasMessageContaining("without an ambient transaction");
    assertThat(adminTemplate.opsForValue().get(key(seed.accountUuid()))).isNull();

    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    ApplyResult projection = availableStore.refreshCurrent(seed.accountUuid());
    assertThat(projection.outcome()).isEqualTo(Outcome.APPLIED);
    ProjectionSnapshot projected = projection.snapshot().orElseThrow();
    JsonNode projectedJson = parse(projected.json());
    assertThat(projected.key()).isEqualTo(key(seed.accountUuid()));
    assertThat(projectedJson.path("accountId").asText()).isEqualTo(seed.accountUuid().toString());
    assertThat(projectedJson.path("accountAuthorityGeneration").asText()).isEqualTo("2");
    assertThat(projectedJson.path("sourceVersion").asText()).isEqualTo("2");
    assertThat(projectedJson.path("outboxStreamKey").asText())
        .isEqualTo(committedReceipt.outboxStreamKey());
    assertThat(projectedJson.path("outboxSequence").asText())
        .isEqualTo(Long.toString(committedReceipt.outboxSequence()));
    assertThat(projectedJson.path("sourceEvent").asText())
        .isEqualTo(new String(committedEvent.payload(), StandardCharsets.UTF_8));
    assertThat(adminTemplate.opsForValue().get(projected.key())).isEqualTo(projected.json());
    assertThat(adminTemplate.getExpire(projected.key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
    assertThat(sqlState(fixture, seed)).isEqualTo(committedSql);
    assertThat(passwordResetReceipt(fixture, tokenHash)).isEqualTo(committedReceipt);
  }

  @Test
  void unknownAccountCannotSynthesizeProjectionOrChangeSourceRows() {
    Fixture fixture = newFixture();
    UUID unknownAccount = UUID.randomUUID();
    AccountAuthoritySourceReader reader = sourceReader(fixture);
    String key = key(unknownAccount);

    assertThatThrownBy(() -> store(reader).refreshCurrent(unknownAccount))
        .isInstanceOf(IllegalStateException.class);
    assertThat(adminTemplate.opsForValue().get(key)).isNull();
    assertThat(adminTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isEqualTo(-2L);
    assertThat(count(fixture, "accounts")).isZero();
    assertThat(count(fixture, "account_authority_generations")).isZero();
    assertThat(count(fixture, "account_authority_outbox_events")).isZero();
    assertThat(count(fixture, "account_authority_outbox_streams")).isZero();
  }

  @Test
  void malformedExpiringAndAheadRedisValuesAreNeverOverwritten() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    AccountAuthoritySourceReader reader = sourceReader(fixture);
    RedisAccountGenerationProjectionStore store = store(reader);
    store.refreshCurrent(seed.accountUuid());

    reset(fixture, seed.rawToken(), "after-reset-password");
    ProjectionSnapshot current = store.refreshCurrent(seed.accountUuid()).snapshot().orElseThrow();
    String key = current.key();

    String malformed = "{\"schemaVersion\":\"unknown\",\"unexpected\":true}";
    adminTemplate.opsForValue().set(key, malformed);
    ApplyResult malformedResult = store.refreshCurrent(seed.accountUuid());
    assertRefusedOverwrite(malformedResult);
    assertThat(adminTemplate.opsForValue().get(key)).isEqualTo(malformed);

    adminTemplate.opsForValue().set(key, current.json());
    adminTemplate.expire(key, 30L, TimeUnit.SECONDS);
    Long ttlBefore = adminTemplate.getExpire(key, TimeUnit.MILLISECONDS);
    ApplyResult ttlResult = store.refreshCurrent(seed.accountUuid());
    assertRefusedOverwrite(ttlResult);
    assertThat(adminTemplate.opsForValue().get(key)).isEqualTo(current.json());
    assertThat(adminTemplate.getExpire(key, TimeUnit.MILLISECONDS))
        .isPositive()
        .isLessThanOrEqualTo(ttlBefore);

    // Poison Redis only: reseal an altered event projection without changing Account SQL evidence.
    String aheadBytes = higherCachedProjection(current);
    adminTemplate.opsForValue().set(key, aheadBytes);
    SqlState beforeAheadRefresh = sqlState(fixture, seed);
    ApplyResult aheadResult = store.refreshCurrent(seed.accountUuid());
    assertThat(aheadResult.outcome()).isEqualTo(Outcome.STALE_SOURCE);
    assertThat(aheadResult.snapshot()).contains(new ProjectionSnapshot(key, aheadBytes));
    assertThat(adminTemplate.opsForValue().get(key)).isEqualTo(aheadBytes);
    assertThat(sqlState(fixture, seed)).isEqualTo(beforeAheadRefresh);
  }

  @Test
  void staleSourceCandidateCannotReplaceAProjectionFromANewerAccountCommit() throws Exception {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    reset(fixture, seed.rawToken(), "after-reset-password");
    AccountAuthoritySourceReader delegate = sourceReader(fixture);
    CountDownLatch staleReadReturned = new CountDownLatch(1);
    CountDownLatch releaseStaleCandidate = new CountDownLatch(1);
    AccountAuthoritySourceReader controlledReader =
        controlledReader(
            delegate,
            1,
            () -> {
              staleReadReturned.countDown();
              await(releaseStaleCandidate);
            },
            false,
            new AtomicInteger());
    RedisAccountGenerationProjectionStore staleStore = store(controlledReader);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      var staleRefresh = executor.submit(() -> staleStore.refreshCurrent(seed.accountUuid()));
      assertThat(staleReadReturned.await(20, TimeUnit.SECONDS)).isTrue();

      commitLogoutAll(fixture, seed);
      RedisAccountGenerationProjectionStore currentStore = store(delegate);
      ApplyResult current = currentStore.refreshCurrent(seed.accountUuid());
      assertThat(current.outcome()).isEqualTo(Outcome.APPLIED);
      String newerBytes = current.snapshot().orElseThrow().json();
      assertThat(parse(newerBytes).path("outboxSequence").asText()).isEqualTo("2");

      releaseStaleCandidate.countDown();
      ApplyResult staleResult = staleRefresh.get(45, TimeUnit.SECONDS);
      assertRefusedOverwrite(staleResult);
      assertThat(adminTemplate.opsForValue().get(key(seed.accountUuid()))).isEqualTo(newerBytes);
    } finally {
      releaseStaleCandidate.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void absentProjectionRacingAnAccountAdvanceReturnsSourceChanged() {
    Fixture fixture = newFixture();
    Seed seed = seedAccount(fixture);
    AccountAuthoritySourceReader delegate = sourceReader(fixture);
    AtomicInteger reads = new AtomicInteger();
    AccountAuthoritySourceReader controlledReader =
        controlledReader(
            delegate,
            2,
            () -> reset(fixture, seed.rawToken(), "racing-reset-password"),
            true,
            reads);
    RedisAccountGenerationProjectionStore store = store(controlledReader);
    String key = key(seed.accountUuid());

    ApplyResult result = store.refreshCurrent(seed.accountUuid());

    assertThat(reads.get()).isGreaterThanOrEqualTo(2);
    assertThat(result.outcome()).isEqualTo(Outcome.SOURCE_CHANGED);
    JsonNode stored = parse(adminTemplate.opsForValue().get(key));
    assertThat(stored.path("accountAuthorityGeneration").asText()).isEqualTo("1");
    assertThat(stored.path("sourceVersion").asText()).isEqualTo("1");
    assertThat(stored.path("outboxSequence").asText()).isEqualTo("0");
    assertThat(adminTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isEqualTo(-1L);
    assertThat(sqlState(fixture, seed).generation()).isEqualTo(2L);
    assertThat(eventHistory(fixture, seed.accountUuid())).hasSize(1);
  }

  private void assertRefusedOverwrite(ApplyResult result) {
    assertThat(result.outcome()).isNotIn(Outcome.APPLIED, Outcome.REPLAYED);
  }

  private String higherCachedProjection(ProjectionSnapshot current) {
    ObjectNode eventPreimage =
        (ObjectNode) parse(parse(current.json()).path("sourceEvent").asText());
    eventPreimage.remove("eventDigest");
    eventPreimage.put("accountAuthorityGeneration", "999");
    eventPreimage.put("sourceVersion", "999");
    ((ObjectNode) eventPreimage.path("accountSecurityCutoff"))
        .put("accountAuthorityGeneration", "999");
    Map<String, Object> preimage =
        JSON.convertValue(eventPreimage, new TypeReference<Map<String, Object>>() {});
    var sealed = PasswordResetAuthorityEventV1Codec.seal(preimage);
    String canonicalSourceEvent = new String(sealed.canonicalJsonUtf8(), StandardCharsets.UTF_8);
    var verifiedEvent = PasswordResetAuthorityEventV1Codec.verify(canonicalSourceEvent);
    assertThat(verifiedEvent.accountAuthorityGeneration()).isEqualTo("999");
    assertThat(verifiedEvent.sourceVersion()).isEqualTo("999");
    assertThat(verifiedEvent.accountSecurityCutoff().accountAuthorityGeneration()).isEqualTo("999");

    AccountGenerationProjection higherProjection =
        new AccountGenerationProjection(
            parse(current.json()).path("accountId").asText(),
            "999",
            "999",
            parse(current.json()).path("outboxStreamKey").asText(),
            parse(current.json()).path("outboxSequence").asText(),
            Optional.of(canonicalSourceEvent));
    String json = higherProjection.toJson();
    assertThat(AccountGenerationProjection.parse(json)).isEqualTo(higherProjection);
    return json;
  }

  private AccountAuthoritySourceReader controlledReader(
      AccountAuthoritySourceReader delegate,
      int hookReadNumber,
      Runnable hook,
      boolean hookBeforeRead,
      AtomicInteger reads) {
    AccountAuthoritySourceReader reader = mock(AccountAuthoritySourceReader.class);
    when(reader.readCurrent(any(UUID.class)))
        .thenAnswer(
            invocation -> {
              UUID accountId = invocation.getArgument(0);
              int read = reads.incrementAndGet();
              if (hookBeforeRead && read == hookReadNumber) {
                hook.run();
              }
              AccountSourceSnapshot current = delegate.readCurrent(accountId);
              if (!hookBeforeRead && read == hookReadNumber) {
                hook.run();
              }
              return current;
            });
    return reader;
  }

  private void commitLogoutAll(Fixture fixture, Seed seed) {
    ScopeState expected = readAuthority(fixture, seed.accountUuid());
    UUID requestId = UUID.randomUUID();
    String tokenHash = sha256Hex("presented-control-token:" + requestId);
    String requestDigest =
        AccountLogoutRequestDigest.accountLogoutAll(seed.accountUuid(), TOKEN_PROFILE, tokenHash);
    LogoutAllResult result =
        logoutProducer(fixture)
            .commit(
                requestId,
                1,
                requestDigest,
                TOKEN_PROFILE,
                tokenHash,
                account(fixture, seed.accountId()),
                expected);
    assertThat(result).isEqualTo(LogoutAllResult.LOGOUT_ALL_COMMITTED);
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
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AccountRepository accounts = new AccountRepository(transactionDsl);
    AccountAuthorityGenerationRepository authority =
        new AccountAuthorityGenerationRepository(transactionDsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(transactionDsl);
    AccountPasswordResetOperationRepository resetOperations =
        new AccountPasswordResetOperationRepository(transactionDsl);
    AccountLogoutAllOperationRepository logoutOperations =
        new AccountLogoutAllOperationRepository(transactionDsl);
    PasswordResetTokenRepository tokens = new PasswordResetTokenRepository(transactionDsl);
    return new Fixture(
        setupDsl,
        transactionDsl,
        transactionManager,
        transaction,
        accounts,
        authority,
        outbox,
        resetOperations,
        logoutOperations,
        tokens);
  }

  private Seed seedAccount(Fixture fixture) {
    return transaction(
        fixture.transaction(),
        () -> {
          Account account = new Account();
          String unique = UUID.randomUUID().toString();
          account.setUsername("projection-" + unique);
          account.setEmail("account-projection-" + unique + "@example.test");
          account.setPasswordHash("initial-verifier");
          Account saved = fixture.accounts().save(account);
          fixture.authority().initialize(AuthorityScope.account(saved.getAccountUuid()));
          String rawToken = "reset-token-" + unique;
          PasswordResetToken token = new PasswordResetToken();
          token.setAccount(saved);
          token.setToken(rawToken);
          token.setExpiresAt(LocalDateTime.now().plusHours(2));
          fixture.tokens().save(token);
          return new Seed(saved.getId(), saved.getAccountUuid(), rawToken);
        });
  }

  private AccountAuthoritySourceReader sourceReader(Fixture fixture) {
    return new AccountAuthoritySourceReader(
        fixture.accounts(),
        fixture.authority(),
        fixture.outbox(),
        sourceReadback(fixture),
        fixture.transactionManager());
  }

  private AccountAuthoritySourceEventReadback sourceReadback(Fixture fixture) {
    return new AccountAuthoritySourceEventReadback(
        fixture.outbox(), fixture.resetOperations(), fixture.logoutOperations());
  }

  private RedisAccountGenerationProjectionStore store(AccountAuthoritySourceReader reader) {
    RedisAccountGenerationProjectionStore store =
        new RedisAccountGenerationProjectionStore(
            reader,
            new CoordinationEndpoint(
                redis.getHost(), redis.getMappedPort(6379), "account_coord_app", COORD_PASSWORD),
            new CacheRateLimitEndpoint("cache.invalid.test", 6380));
    stores.add(store);
    store.init();
    return store;
  }

  private AccountLogoutAllAuthorityEventProducer logoutProducer(Fixture fixture) {
    return new AccountLogoutAllAuthorityEventProducer(
        fixture.accounts(),
        fixture.authority(),
        fixture.outbox(),
        fixture.logoutOperations(),
        sourceReadback(fixture),
        fixture.transactionDsl(),
        fixture.transactionManager());
  }

  private void reset(Fixture fixture, String rawToken, String password) {
    AccountServiceImpl service =
        new AccountServiceImpl(
            fixture.accounts(),
            fixture.authority(),
            fixture.outbox(),
            fixture.resetOperations(),
            fixture.logoutOperations(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            fixture.tokens(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            fixture.transactionManager());
    transaction(
        fixture.transaction(),
        () -> {
          service.completePasswordReset(new CompletePasswordResetRequest(rawToken, password));
          return null;
        });
  }

  private Account account(Fixture fixture, long accountId) {
    return transaction(
        fixture.transaction(), () -> fixture.accounts().findById(accountId).orElseThrow());
  }

  private PasswordResetReceipt passwordResetReceipt(Fixture fixture, String tokenHash) {
    return transaction(
        fixture.transaction(),
        () -> fixture.resetOperations().findByTokenHash(tokenHash).orElseThrow());
  }

  private ScopeState readAuthority(Fixture fixture, UUID accountUuid) {
    return transaction(
        fixture.transaction(), () -> fixture.authority().read(AuthorityScope.account(accountUuid)));
  }

  private AccountAuthorityOutboxRepository.Event event(
      Fixture fixture, UUID accountUuid, long sequence) {
    return transaction(
        fixture.transaction(),
        () -> fixture.outbox().findEvent(streamKey(accountUuid), sequence).orElseThrow());
  }

  private SqlState sqlState(Fixture fixture, Seed seed) {
    Account account = account(fixture, seed.accountId());
    ScopeState source = readAuthority(fixture, seed.accountUuid());
    return new SqlState(
        account.getPasswordHash(),
        source.generation(),
        source.sourceVersion(),
        source.issuanceFence().value(),
        source.issuanceFence().sourceVersion(),
        count(fixture, "accounts"),
        count(fixture, "account_authority_generations"),
        count(fixture, "account_authority_issuance_fences"),
        count(fixture, "account_password_reset_operation_receipts"),
        count(fixture, "account_logout_all_operation_receipts"),
        count(fixture, "account_authority_outbox_events"),
        count(fixture, "account_authority_outbox_streams"),
        eventHistory(fixture, seed.accountUuid()));
  }

  private List<EventEvidence> eventHistory(Fixture fixture, UUID accountUuid) {
    return fixture
        .setupDsl()
        .resultQuery(
            "SELECT outbox_stream_key, outbox_sequence, request_id, event_id, event_digest, payload "
                + "FROM account_authority_outbox_events WHERE outbox_stream_key = ? "
                + "ORDER BY outbox_sequence",
            streamKey(accountUuid))
        .fetch(
            record ->
                new EventEvidence(
                    record.get("outbox_stream_key", String.class),
                    record.get("outbox_sequence", Long.class),
                    record.get("request_id", String.class),
                    record.get("event_id", String.class),
                    record.get("event_digest", String.class),
                    record.get("payload", byte[].class)));
  }

  private long count(Fixture fixture, String table) {
    return Objects.requireNonNull(
        fixture.setupDsl().resultQuery("SELECT COUNT(*) FROM " + table).fetchOne(0, Long.class),
        "Account projection source row count is missing");
  }

  private String key(UUID accountUuid) {
    return KEY_PREFIX + accountUuid;
  }

  private String streamKey(UUID accountUuid) {
    return STREAM_PREFIX + accountUuid;
  }

  private String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception exception) {
      throw new IllegalStateException("Projection JSON could not be read", exception);
    }
  }

  private Set<String> fields(JsonNode json) {
    Set<String> names = new HashSet<>();
    json.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private String exceptionMessageChain(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    Throwable current = failure;
    while (current != null) {
      messages.append(current.getMessage()).append('\n');
      current = current.getCause();
    }
    return messages.toString();
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Account projection race barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Account projection race proof was interrupted", interrupted);
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
      AccountRepository accounts,
      AccountAuthorityGenerationRepository authority,
      AccountAuthorityOutboxRepository outbox,
      AccountPasswordResetOperationRepository resetOperations,
      AccountLogoutAllOperationRepository logoutOperations,
      PasswordResetTokenRepository tokens) {}

  private record Seed(long accountId, UUID accountUuid, String rawToken) {}

  private record EventEvidence(
      String streamKey,
      long sequence,
      String requestId,
      String eventId,
      String digest,
      byte[] payload) {
    private EventEvidence {
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof EventEvidence evidence)) {
        return false;
      }
      return sequence == evidence.sequence
          && streamKey.equals(evidence.streamKey)
          && requestId.equals(evidence.requestId)
          && eventId.equals(evidence.eventId)
          && digest.equals(evidence.digest)
          && java.util.Arrays.equals(payload, evidence.payload);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          streamKey, sequence, requestId, eventId, digest, java.util.Arrays.hashCode(payload));
    }
  }

  private record SqlState(
      String passwordHash,
      long generation,
      long sourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      long accountCount,
      long generationCount,
      long fenceCount,
      long passwordResetReceiptCount,
      long logoutReceiptCount,
      long eventCount,
      long streamCount,
      List<EventEvidence> eventHistory) {}
}
