package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountMembershipSourceReader.MembershipSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.MembershipGenerationProjection;
import net.firedevops.firemud.accountservice.service.RedisMembershipGenerationProjectionStore;
import net.firedevops.firemud.accountservice.service.RedisMembershipGenerationProjectionStore.ApplyResult;
import net.firedevops.firemud.accountservice.service.RedisMembershipGenerationProjectionStore.CacheRateLimitEndpoint;
import net.firedevops.firemud.accountservice.service.RedisMembershipGenerationProjectionStore.CoordinationEndpoint;
import net.firedevops.firemud.accountservice.service.RedisMembershipGenerationProjectionStore.Outcome;
import net.firedevops.firemud.accountservice.service.RedisMembershipGenerationProjectionStore.ProjectionSnapshot;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.v1.GameplayRealm;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Account-owned membership-generation projection proof over real Account SQL and isolated Redis.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = AccountServiceApplication.class,
    properties = {
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
      GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH,
      "firemud.grpc.workload-namespace=membership-generation-projection-test"
    })
class MembershipGenerationProjectionPostgresRedisIntegrationTest {
  private static final String WORKLOAD_NAMESPACE = "membership-generation-projection-test";
  private static final String WORLD_SLUG = "membership-projection-proof-world";
  private static final String REALM_SLUG = "production";
  private static final String NAMESPACE_ID = "membership-projection-proof-namespace";
  private static final UUID REALM_ID = UUID.fromString("7bda1169-a8a3-4b43-96a4-53f8579ac164");
  private static final long GAME_INSTANCE_ID = 79L;
  private static final long CATALOG_REVISION = 31L;
  private static final long POINTER_VERSION = 13L;
  private static final String AUTHORITY_STREAM_PREFIX = "account:auth-authority:v1:";
  private static final String MEMBERSHIP_KEY_PREFIX = MembershipGenerationProjection.KEY_PREFIX;
  private static final String COORD_PASSWORD = "membership-generation-projection-secret";
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
              "~" + MEMBERSHIP_KEY_PREFIX + "*",
              "+get",
              "+set",
              "+pttl",
              "+evalsha",
              "+script|load");

  private final List<RedisMembershipGenerationProjectionStore> stores = new ArrayList<>();
  private LettuceConnectionFactory adminConnectionFactory;
  private StringRedisTemplate adminTemplate;
  private LettuceConnectionFactory ownerConnectionFactory;
  private StringRedisTemplate ownerTemplate;

  @Autowired private DSLContext dsl;
  @Autowired private AccountService accountService;
  @Autowired private AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer;
  @Autowired private AccountMembershipLifecycleService membershipLifecycleService;
  @Autowired private AccountAuthorityGenerationRepository authorityGenerationRepository;
  @Autowired private ApprovedLegacyTenantAssociationRepository tenantAssociationRepository;
  @Autowired private FreshTenantIdentityAssociationRepository freshAssociationRepository;
  @Autowired private LegacyTenantSourceEvidence legacyTenantSourceEvidence;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(registry, postgres, "account_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @org.junit.jupiter.api.BeforeEach
  void startTestOnlyRedisReadbackClients() {
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

  @org.junit.jupiter.api.AfterEach
  void closeRedisClients() {
    stores.forEach(RedisMembershipGenerationProjectionStore::close);
    stores.clear();
    if (adminConnectionFactory != null) {
      adminConnectionFactory.destroy();
    }
    if (ownerConnectionFactory != null) {
      ownerConnectionFactory.destroy();
    }
  }

  @org.junit.jupiter.api.Test
  void existingOnlyZeroBaselineProjectsWithoutAdmissionOrSqlMutation() {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    importFreshTenantAssociation(tenantUuid);
    enrollFreshNeverJoinedBaseline(account.accountUuid(), tenantUuid);

    AccountMembershipSourceReader reader = membershipSourceReader();
    MembershipSourceSnapshot source = reader.readCurrent(account.accountUuid(), tenantUuid);
    assertSourceAxes(source, "MISSING", false, 1L, 1L, "1", "0", false);
    assertThat(source.snapshot().roles()).isEmpty();
    assertThat(source.snapshot().sourceEvent()).isNull();
    SourceSqlState beforeProjection = sourceSqlState(account, tenantUuid, null);

    ApplyResult result = store(reader).refreshCurrent(account.accountUuid(), tenantUuid);

    ProjectionSnapshot projection =
        assertProjection(
            result, Outcome.APPLIED, account.accountUuid(), tenantUuid, 1L, 1L, "1", "0", null);
    assertThat(sourceSqlState(account, tenantUuid, null)).isEqualTo(beforeProjection);
    assertThat(projection.json())
        .doesNotContain("membershipExists", "gameplayAdmissionAllowed", "roles");
  }

  @org.junit.jupiter.api.Test
  void unknownAndAssociatedUnenrolledPairsCannotSynthesizeProjectionState() {
    AccountFixture account = accountFixture();
    UUID unknownTenant = UUID.randomUUID();
    UUID associatedUnenrolledTenant = UUID.randomUUID();
    importFreshTenantAssociation(associatedUnenrolledTenant);
    AccountMembershipSourceReader reader = membershipSourceReader();
    RedisMembershipGenerationProjectionStore store = store(reader);

    SourceSqlState unknownBefore = sourceSqlState(account, unknownTenant, null);
    assertThatThrownBy(() -> reader.readCurrent(account.accountUuid(), unknownTenant))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> store.refreshCurrent(account.accountUuid(), unknownTenant))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sourceSqlState(account, unknownTenant, null)).isEqualTo(unknownBefore);
    assertThat(adminTemplate.opsForValue().get(key(account.accountUuid(), unknownTenant))).isNull();

    SourceSqlState associatedBefore = sourceSqlState(account, associatedUnenrolledTenant, null);
    assertThat(associatedBefore.pairAuthority()).isEmpty();
    assertThat(
            associatedBefore.generationRows().stream()
                .filter(row -> "MEMBERSHIP".equals(row.get("scope_kind")))
                .toList())
        .isEmpty();
    assertThatThrownBy(() -> reader.readCurrent(account.accountUuid(), associatedUnenrolledTenant))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Fresh Account membership pair baseline is absent");
    assertThatThrownBy(
            () -> store.refreshCurrent(account.accountUuid(), associatedUnenrolledTenant))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Fresh Account membership pair baseline is absent");
    assertThat(sourceSqlState(account, associatedUnenrolledTenant, null))
        .isEqualTo(associatedBefore);
    assertThat(
            adminTemplate.opsForValue().get(key(account.accountUuid(), associatedUnenrolledTenant)))
        .isNull();
    assertThat(associatedBefore.memberships()).isEmpty();
    assertThat(associatedBefore.transitionReceipts()).isEmpty();
    assertThat(associatedBefore.membershipEvents()).isEmpty();
  }

  @org.junit.jupiter.api.Test
  void retainedJoinLeftAndReactivationProjectExactIndependentSourceAxes() {
    JoinFixture fixture = retainedJoinFixture();
    assertThat(join(fixture).success()).isTrue();

    AccountMembershipSourceReader reader = membershipSourceReader();
    RedisMembershipGenerationProjectionStore store = store(reader);
    MembershipSourceSnapshot joined =
        reader.readCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertSourceAxes(joined, "ACTIVE", true, 1L, 1L, "2", "1", true);
    SourceSqlState afterJoin =
        sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId());
    ApplyResult firstJoin = store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    ProjectionSnapshot joinProjection =
        assertProjection(
            firstJoin,
            Outcome.APPLIED,
            fixture.accountUuid(),
            fixture.tenantUuid(),
            1L,
            1L,
            "2",
            "1",
            joined.snapshot().sourceEvent().canonicalJson());
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(afterJoin);

    ApplyResult joinRetry = store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(joinRetry.outcome()).isEqualTo(Outcome.REPLAYED);
    assertThat(joinRetry.snapshot()).contains(joinProjection);
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(afterJoin);

    assertThat(adminTemplate.delete(joinProjection.key())).isTrue();
    ApplyResult joinRepair = store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(joinRepair.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(joinRepair.snapshot()).contains(joinProjection);
    assertRedisExactWithoutTtl(joinProjection);
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(afterJoin);

    String leftRequestId = "projection-left-" + compactUuid();
    MembershipTransitionReceipt leftReceipt =
        membershipLifecycleService.leave(
            fixture.accountId(), fixture.legacyTenantId(), leftRequestId);
    MembershipSourceSnapshot left = reader.readCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertSourceAxes(left, "INACTIVE", false, 2L, 2L, "3", "2", true);
    SourceSqlState afterLeft =
        sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId());
    ApplyResult leftProjectionResult =
        store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    ProjectionSnapshot leftProjection =
        assertProjection(
            leftProjectionResult,
            Outcome.APPLIED,
            fixture.accountUuid(),
            fixture.tenantUuid(),
            2L,
            2L,
            "3",
            "2",
            left.snapshot().sourceEvent().canonicalJson());
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(afterLeft);

    ApplyResult leftRetry = store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(leftRetry.outcome()).isEqualTo(Outcome.REPLAYED);
    assertThat(leftRetry.snapshot()).contains(leftProjection);
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(afterLeft);

    JoinFixture reactivation = nextJoinAttempt(fixture, "projection-reactivate-");
    assertThat(join(reactivation).success()).isTrue();
    MembershipSourceSnapshot active =
        reader.readCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertSourceAxes(active, "ACTIVE", true, 3L, 3L, "4", "3", true);
    SourceSqlState afterReactivation =
        sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId());
    ApplyResult reactivationResult =
        store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    ProjectionSnapshot activeProjection =
        assertProjection(
            reactivationResult,
            Outcome.APPLIED,
            fixture.accountUuid(),
            fixture.tenantUuid(),
            3L,
            3L,
            "4",
            "3",
            active.snapshot().sourceEvent().canonicalJson());
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(afterReactivation);

    assertThat(
            membershipLifecycleService.leave(
                fixture.accountId(), fixture.legacyTenantId(), leftRequestId))
        .isEqualTo(leftReceipt);
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(afterReactivation);
    ApplyResult historicalLeftRetry =
        store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(historicalLeftRetry.outcome()).isEqualTo(Outcome.REPLAYED);
    assertThat(historicalLeftRetry.snapshot()).contains(activeProjection);
    assertRedisExactWithoutTtl(activeProjection);
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(afterReactivation);
    assertThat(adminTemplate.opsForValue().get(leftProjection.key()))
        .isEqualTo(activeProjection.json());
  }

  @org.junit.jupiter.api.Test
  void sourceAdvanceBetweenCandidateAndFreshReadbackReturnsNonconverged() {
    JoinFixture fixture = retainedJoinFixture();
    assertThat(join(fixture).success()).isTrue();

    AccountMembershipSourceReader delegate = membershipSourceReader();
    AccountMembershipSourceReader interleavedReader = mock(AccountMembershipSourceReader.class);
    AtomicInteger sourceReads = new AtomicInteger();
    AtomicReference<SourceSqlState> stateAfterRealLeave = new AtomicReference<>();
    String leftRequestId = "projection-race-left-" + compactUuid();
    doAnswer(
            invocation -> {
              UUID accountUuid = invocation.getArgument(0);
              UUID tenantUuid = invocation.getArgument(1);
              MembershipSourceSnapshot candidate = delegate.readCurrent(accountUuid, tenantUuid);
              if (sourceReads.incrementAndGet() == 1) {
                membershipLifecycleService.leave(
                    fixture.accountId(), fixture.legacyTenantId(), leftRequestId);
                stateAfterRealLeave.set(
                    sourceSqlState(
                        fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()));
              }
              return candidate;
            })
        .when(interleavedReader)
        .readCurrent(any(UUID.class), any(UUID.class));

    ApplyResult result =
        store(interleavedReader).refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());

    assertThat(result.outcome()).isEqualTo(Outcome.SOURCE_CHANGED);
    ProjectionSnapshot candidate = result.snapshot().orElseThrow();
    assertThat(candidate.key()).isEqualTo(key(fixture.accountUuid(), fixture.tenantUuid()));
    assertThat(parse(candidate.json()).path("outboxSequence").asText()).isEqualTo("1");
    assertThat(adminTemplate.opsForValue().get(candidate.key())).isEqualTo(candidate.json());
    assertThat(adminTemplate.getExpire(candidate.key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
    assertThat(stateAfterRealLeave.get()).isNotNull();
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(stateAfterRealLeave.get());
    MembershipSourceSnapshot current =
        delegate.readCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertSourceAxes(current, "INACTIVE", false, 2L, 2L, "3", "2", true);
  }

  @org.junit.jupiter.api.Test
  void staleCandidateCannotReplaceNewerSourceProjection() throws Exception {
    JoinFixture fixture = retainedJoinFixture();
    assertThat(join(fixture).success()).isTrue();

    AccountMembershipSourceReader delegate = membershipSourceReader();
    AccountMembershipSourceReader delayedReader = mock(AccountMembershipSourceReader.class);
    CountDownLatch candidateRead = new CountDownLatch(1);
    CountDownLatch continueStaleCandidate = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    doAnswer(
            invocation -> {
              MembershipSourceSnapshot candidate =
                  delegate.readCurrent(invocation.getArgument(0), invocation.getArgument(1));
              if (reads.incrementAndGet() == 1) {
                candidateRead.countDown();
                await(continueStaleCandidate);
              }
              return candidate;
            })
        .when(delayedReader)
        .readCurrent(any(UUID.class), any(UUID.class));

    RedisMembershipGenerationProjectionStore staleStore = store(delayedReader);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      var staleRefresh =
          executor.submit(
              () -> staleStore.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid()));
      assertThat(candidateRead.await(20, TimeUnit.SECONDS)).isTrue();

      String leftRequestId = "projection-stale-left-" + compactUuid();
      membershipLifecycleService.leave(
          fixture.accountId(), fixture.legacyTenantId(), leftRequestId);
      SourceSqlState afterLeft =
          sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId());
      ProjectionSnapshot current =
          assertProjection(
              store(delegate).refreshCurrent(fixture.accountUuid(), fixture.tenantUuid()),
              Outcome.APPLIED,
              fixture.accountUuid(),
              fixture.tenantUuid(),
              2L,
              2L,
              "3",
              "2",
              delegate
                  .readCurrent(fixture.accountUuid(), fixture.tenantUuid())
                  .snapshot()
                  .sourceEvent()
                  .canonicalJson());

      continueStaleCandidate.countDown();
      ApplyResult staleResult = staleRefresh.get(45, TimeUnit.SECONDS);
      assertThat(staleResult.outcome()).isEqualTo(Outcome.STALE_SOURCE);
      assertThat(staleResult.outcome()).isNotIn(Outcome.APPLIED, Outcome.REPLAYED);
      assertThat(adminTemplate.opsForValue().get(current.key())).isEqualTo(current.json());
      assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
          .isEqualTo(afterLeft);
    } finally {
      continueStaleCandidate.countDown();
      executor.shutdownNow();
    }
  }

  @org.junit.jupiter.api.Test
  void aheadMalformedSameCheckpointAndExpiringRedisStatesAreDeniedWithoutSourceWrites()
      throws Exception {
    JoinFixture fixture = retainedJoinFixture();
    assertThat(join(fixture).success()).isTrue();
    AccountMembershipSourceReader reader = membershipSourceReader();
    RedisMembershipGenerationProjectionStore store = store(reader);
    ProjectionSnapshot current =
        assertProjection(
            store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid()),
            Outcome.APPLIED,
            fixture.accountUuid(),
            fixture.tenantUuid(),
            1L,
            1L,
            "2",
            "1",
            reader
                .readCurrent(fixture.accountUuid(), fixture.tenantUuid())
                .snapshot()
                .sourceEvent()
                .canonicalJson());
    SourceSqlState sourceBeforePoison =
        sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId());

    String malformed = "{\"schemaVersion\":\"unknown\"}";
    adminTemplate.opsForValue().set(current.key(), malformed);
    ApplyResult malformedResult = store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(malformedResult.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(adminTemplate.opsForValue().get(current.key())).isEqualTo(malformed);
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(sourceBeforePoison);

    adminTemplate.opsForValue().set(current.key(), current.json());
    assertThat(adminTemplate.expire(current.key(), 30L, TimeUnit.SECONDS)).isTrue();
    Long ttlBefore = adminTemplate.getExpire(current.key(), TimeUnit.MILLISECONDS);
    assertThat(ttlBefore).isPositive();
    ApplyResult ttlResult = store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(ttlResult.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(adminTemplate.opsForValue().get(current.key())).isEqualTo(current.json());
    assertThat(adminTemplate.getExpire(current.key(), TimeUnit.MILLISECONDS))
        .isPositive()
        .isLessThanOrEqualTo(ttlBefore);
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(sourceBeforePoison);

    assertThat(adminTemplate.persist(current.key())).isTrue();
    ObjectNode ahead = (ObjectNode) parse(current.json());
    ahead.put("sourceVersion", "2");
    String aheadJson = writeJson(ahead);
    adminTemplate.opsForValue().set(current.key(), aheadJson);
    ApplyResult aheadResult = store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(aheadResult.outcome()).isEqualTo(Outcome.STALE_SOURCE);
    assertThat(adminTemplate.opsForValue().get(current.key())).isEqualTo(aheadJson);
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(sourceBeforePoison);

    adminTemplate.opsForValue().set(current.key(), current.json());
    String conflictingJson = sameCheckpointPoison(current);
    adminTemplate.opsForValue().set(current.key(), conflictingJson);
    ApplyResult conflictResult = store.refreshCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(conflictResult.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(conflictResult.detail()).contains("SAME_CHECKPOINT_DISAGREEMENT");
    assertThat(adminTemplate.opsForValue().get(current.key())).isEqualTo(conflictingJson);
    assertThat(sourceSqlState(fixture.account(), fixture.tenantUuid(), fixture.legacyTenantId()))
        .isEqualTo(sourceBeforePoison);
  }

  @org.junit.jupiter.api.Test
  void coordinationPrincipalCannotReadOtherOwnersOrInvokeEval() {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    importFreshTenantAssociation(tenantUuid);
    enrollFreshNeverJoinedBaseline(account.accountUuid(), tenantUuid);
    ProjectionSnapshot projection =
        store(membershipSourceReader())
            .refreshCurrent(account.accountUuid(), tenantUuid)
            .snapshot()
            .orElseThrow();

    String accountKey = "session:auth:generation:account:" + account.accountUuid();
    String tenantKey = "session:auth:generation:tenant:" + tenantUuid;
    String gameSessionIssuerKey =
        "session:game:auth:issuer-generation:v1:" + AccountServiceImpl.ACCOUNT_JWT_ISSUER;
    adminTemplate.opsForValue().set(accountKey, "admin-only-account-marker");
    adminTemplate.opsForValue().set(tenantKey, "admin-only-tenant-marker");
    adminTemplate.opsForValue().set(gameSessionIssuerKey, "admin-only-game-session-marker");

    assertThat(ownerTemplate.opsForValue().get(projection.key())).isEqualTo(projection.json());
    assertDeniedKeyRead(accountKey);
    assertDeniedKeyRead(tenantKey);
    assertDeniedKeyRead(gameSessionIssuerKey);
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
    assertThat(adminTemplate.delete(List.of(accountKey, tenantKey, gameSessionIssuerKey)))
        .isEqualTo(3L);
  }

  private AccountMembershipSourceReader membershipSourceReader() {
    return new AccountMembershipSourceReader(
        membershipAuthorityEventProducer, authorityGenerationRepository, transactionManager);
  }

  private RedisMembershipGenerationProjectionStore store(AccountMembershipSourceReader reader) {
    RedisMembershipGenerationProjectionStore store =
        new RedisMembershipGenerationProjectionStore(
            reader,
            new CoordinationEndpoint(
                redis.getHost(), redis.getMappedPort(6379), "account_coord_app", COORD_PASSWORD),
            new CacheRateLimitEndpoint("cache.invalid.test", 6380));
    store.init();
    stores.add(store);
    return store;
  }

  private AccountFixture accountFixture() {
    String suffix = compactUuid();
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) "
                        + "VALUES (?, ?, ?) RETURNING id",
                    "mgp-" + suffix,
                    "membership-projection-" + suffix + "@example.com",
                    "integration-proof-hash")
                .fetchOne(0, Long.class));
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    initializeAccountAuthority(accountUuid);
    return new AccountFixture(accountId, accountUuid);
  }

  private void initializeAccountAuthority(UUID accountUuid) {
    dsl.execute(
        "INSERT INTO account_authority_generations "
            + "(scope_kind, account_uuid, generation, source_version) VALUES ('ACCOUNT', ?, 1, 1)",
        accountUuid);
    dsl.execute(
        "INSERT INTO account_authority_issuance_fences "
            + "(account_uuid, issuance_fence, source_version) VALUES (?, 1, 1)",
        accountUuid);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                authorityGenerationRepository.initializeIssuerIfAbsent(
                    AccountServiceImpl.ACCOUNT_JWT_ISSUER));
  }

  private FreshTenantCreationEvidence importFreshTenantAssociation(UUID tenantUuid) {
    UUID creationRequestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String targetNamespace = WORKLOAD_NAMESPACE;
    String sourceGameTenantKey = "fresh-" + compactUuid();
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            targetNamespace,
            creationRequestId,
            sourceGameTenantKey,
            "Fresh membership projection fixture",
            null);
    long sourceGameRowId = positiveRandomLong();
    String provenanceKind = "NEW_GAME_ROW";
    FreshTenantCreationEvidence evidence =
        new FreshTenantCreationEvidence(
            1,
            targetNamespace,
            creationRequestId,
            operationId,
            requestDigest,
            tenantUuid,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind,
            GameTenantCreationDigest.evidenceDigest(
                targetNamespace,
                creationRequestId,
                operationId,
                requestDigest,
                tenantUuid,
                sourceGameRowId,
                sourceGameTenantKey,
                provenanceKind));
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              freshAssociationRepository.importVerified(evidence);
              authorityGenerationRepository.initializeTenantIfAbsent(tenantUuid);
            });
    return evidence;
  }

  private void enrollFreshNeverJoinedBaseline(UUID accountUuid, UUID tenantUuid) {
    new TransactionTemplate(transactionManager)
        .execute(
            status ->
                membershipAuthorityEventProducer.readFreshNeverJoinedMembershipSnapshot(
                    accountUuid, tenantUuid));
  }

  private JoinFixture retainedJoinFixture() {
    String suffix = compactUuid();
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) "
                        + "VALUES (?, ?, ?) RETURNING id",
                    "mgp-" + suffix,
                    "membership-projection-" + suffix + "@example.com",
                    "integration-proof-hash")
                .fetchOne(0, Long.class));
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    initializeAccountAuthority(accountUuid);

    long legacyTenantId = positiveRandomLong();
    seedRetainedV26TenantEvidence(legacyTenantId, suffix);
    dsl.execute(
        "INSERT INTO subscription "
            + "(account_id, tenant_id, plan_id, status, entitlement_version) "
            + "VALUES (?, ?, 'membership-projection-proof', 'active', 1)",
        accountId,
        legacyTenantId);
    UUID tenantUuid = UUID.randomUUID();
    tenantAssociationRepository.importApproved(
        legacyTenantId,
        approvedTenantAssociation(
            legacyTenantId, tenantUuid, legacyTenantSourceEvidence.digest(legacyTenantId), suffix));

    GameplayRealm realm =
        GameplayRealm.newBuilder()
            .setTenantId(Long.toString(legacyTenantId))
            .setRealmId(REALM_ID.toString())
            .setWorldSlug(WORLD_SLUG)
            .setRealmSlug(REALM_SLUG)
            .setPlayableStateNamespaceId(NAMESPACE_ID)
            .setGameInstanceId(Long.toString(GAME_INSTANCE_ID))
            .setCatalogRevision(CATALOG_REVISION)
            .setPointerVersion(POINTER_VERSION)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .build();
    GameplayAdmissionPointer pointer =
        GameplayAdmissionPointer.newBuilder()
            .setTenantId(Long.toString(legacyTenantId))
            .setRealmId(REALM_ID.toString())
            .setWorldSlug(WORLD_SLUG)
            .setRealmSlug(REALM_SLUG)
            .setPlayableStateNamespaceId(NAMESPACE_ID)
            .setGameInstanceId(Long.toString(GAME_INSTANCE_ID))
            .setCatalogRevision(CATALOG_REVISION)
            .setPointerVersion(POINTER_VERSION)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .build();
    when(gameSessionClient.listGameplayRealms(WORLD_SLUG)).thenReturn(List.of(realm));
    when(gameSessionClient.getAdmissionPointer(legacyTenantId, WORLD_SLUG, REALM_SLUG))
        .thenReturn(pointer);

    String requestId = "membership-projection-join-" + suffix;
    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            accountId,
            legacyTenantId,
            REALM_ID,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            "membership-projection-session-" + suffix,
            requestId);
    DirectTextJoinTarget target =
        new DirectTextJoinTarget(
            legacyTenantId,
            REALM_ID,
            WORLD_SLUG,
            REALM_SLUG,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            CATALOG_REVISION,
            POINTER_VERSION);
    DirectTextJoinScope scope = accountService.issueDirectTextConnectScope(caller, target);
    return new JoinFixture(
        new AccountFixture(accountId, accountUuid),
        accountId,
        accountUuid,
        legacyTenantId,
        tenantUuid,
        requestId,
        caller,
        scope);
  }

  private JoinFixture nextJoinAttempt(JoinFixture original, String requestPrefix) {
    String requestId = requestPrefix + compactUuid();
    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            original.accountId(),
            original.legacyTenantId(),
            REALM_ID,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            "membership-projection-session-" + compactUuid(),
            requestId);
    DirectTextJoinTarget target =
        new DirectTextJoinTarget(
            original.legacyTenantId(),
            REALM_ID,
            WORLD_SLUG,
            REALM_SLUG,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            CATALOG_REVISION,
            POINTER_VERSION);
    DirectTextJoinScope scope = accountService.issueDirectTextConnectScope(caller, target);
    return new JoinFixture(
        original.account(),
        original.accountId(),
        original.accountUuid(),
        original.legacyTenantId(),
        original.tenantUuid(),
        requestId,
        caller,
        scope);
  }

  private JoinPublicProductionResult join(JoinFixture fixture) {
    return accountService.joinPublicProductionFromGameSession(
        fixture.caller(),
        new JoinPublicProductionRequest(fixture.scope().connectScopeId(), fixture.requestId()));
  }

  private void seedRetainedV26TenantEvidence(long tenantId, String suffix) {
    long donorAccountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                    "caspd-" + suffix,
                    "membership-projection-donor-" + suffix + "@example.com",
                    "integration-proof-hash",
                    tenantId)
                .fetchOne(0, Long.class));
    UUID donorAccountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", donorAccountId)
                .fetchOne(0, UUID.class));
    initializeAccountAuthority(donorAccountUuid);
    long retainedMembershipId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO account_tenant_membership "
                        + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                        + "membership_version, membership_authority_generation, authority_provenance) "
                        + "VALUES (?, ?, FALSE, 'LEGACY_UNVERIFIED', 1, 1, 'LEGACY_UNVERIFIED') "
                        + "RETURNING id",
                    donorAccountId,
                    tenantId)
                .fetchOne(0, Long.class));
    dsl.execute(
        "INSERT INTO account_legacy_tenant_sources "
            + "(account_id, legacy_tenant_id, matching_membership_id, "
            + "matching_membership_admission_allowed, profile_tenant_count, "
            + "matching_profile_count, disposition) VALUES (?, ?, ?, FALSE, 0, 0, 'UNVERIFIED')",
        donorAccountId,
        tenantId,
        retainedMembershipId);
    dsl.execute(
        "INSERT INTO account_legacy_membership_sources "
            + "(membership_id, account_id, tenant_id, original_gameplay_admission_allowed, "
            + "matches_account_legacy_tenant, disposition) "
            + "VALUES (?, ?, ?, FALSE, TRUE, 'UNVERIFIED')",
        retainedMembershipId,
        donorAccountId,
        tenantId);
  }

  private ResolveLegacyAccountTenantAssociationResponse approvedTenantAssociation(
      long legacyTenantId, UUID tenantUuid, String evidenceDigest, String suffix) {
    String sourceLegacyGameTenantId = "legacy-game-" + suffix.substring(0, 16);
    return ResolveLegacyAccountTenantAssociationResponse.newBuilder()
        .setLegacyAccountTenantId(legacyTenantId)
        .setCanonicalTenantId(tenantUuid.toString())
        .setSourceLegacyGameTenantId(sourceLegacyGameTenantId)
        .setSourceGameRowId(positiveRandomLong())
        .setAccountEvidenceDigest(evidenceDigest)
        .setOperationId(UUID.randomUUID().toString())
        .setManifestDigest("sha256:" + "b".repeat(64))
        .setManifestSignature(Base64.getEncoder().encodeToString(new byte[64]))
        .setTargetNamespace(WORKLOAD_NAMESPACE)
        .setSignerKeyId("game-design-owner-test")
        .setApprovedBy("owner@example.test")
        .setApprovalReference("unit-1b-membership-projection-proof")
        .setSignedAt("2026-09-26T00:00:00Z")
        .setOperationEntryCount(1)
        .setManifestSchemaVersion(1)
        .build();
  }

  private void assertSourceAxes(
      MembershipSourceSnapshot source,
      String lifecycle,
      boolean admitted,
      long generation,
      long sourceVersion,
      String membershipVersion,
      String sequence,
      boolean eventExpected) {
    UUID tenantUuid = source.tenantId();
    String streamKey = membershipStreamKey(source.accountId(), tenantUuid);
    assertThat(source.sourceState().scope())
        .isEqualTo(AuthorityScope.membership(source.accountId(), tenantUuid));
    assertThat(source.sourceState().generation()).isEqualTo(generation);
    assertThat(source.sourceState().sourceVersion()).isEqualTo(sourceVersion);
    assertThat(source.sourceState().issuanceFence()).isNotNull();
    assertThat(source.sourceState().issuanceFence().accountId()).isEqualTo(source.accountId());
    assertThat(source.snapshot().accountUuid()).isEqualTo(source.accountId().toString());
    assertThat(source.snapshot().tenantUuid()).isEqualTo(tenantUuid.toString());
    assertThat(source.snapshot().requestAccountUuid()).isEqualTo(source.accountId().toString());
    assertThat(source.snapshot().requestTenantUuid()).isEqualTo(tenantUuid.toString());
    assertThat(source.snapshot().membershipExists()).isEqualTo(eventExpected);
    assertThat(source.snapshot().gameplayAdmissionAllowed()).isEqualTo(admitted);
    assertThat(source.snapshot().membershipBaseline().membershipLifecycleState())
        .isEqualTo(lifecycle);
    assertThat(source.snapshot().membershipBaseline().membershipAuthorityGeneration())
        .isEqualTo(Long.toString(generation));
    assertThat(source.snapshot().membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(tenantUuid.toString(), membershipVersion));
    List<AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry> checkpoints =
        source.snapshot().outboxCheckpoints().stream()
            .filter(entry -> streamKey.equals(entry.outboxStreamKey()))
            .toList();
    assertThat(checkpoints).hasSize(1);
    assertThat(checkpoints.getFirst().outboxSequence()).isEqualTo(sequence);

    MembershipEvent event = source.snapshot().sourceEvent();
    if (!eventExpected) {
      assertThat(event).isNull();
      assertThat(source.snapshot().roles()).isEmpty();
      assertThat(sequence).isEqualTo("0");
      return;
    }

    assertThat(event).isNotNull();
    assertThat(event.accountId()).isEqualTo(source.accountId().toString());
    assertThat(event.tenantId()).isEqualTo(tenantUuid.toString());
    assertThat(event.outboxStreamKey()).isEqualTo(streamKey);
    assertThat(event.outboxSequence()).isEqualTo(sequence);
    assertThat(event.membershipAuthorityGeneration()).isEqualTo(Long.toString(generation));
    assertThat(event.membershipVersion())
        .isEqualTo(Map.of(tenantUuid.toString(), membershipVersion));
    assertStoredEventExact(source, event);
  }

  private void assertStoredEventExact(MembershipSourceSnapshot source, MembershipEvent event) {
    Record row =
        dsl.resultQuery(
                "SELECT event_id, event_digest, encode(payload, 'hex') AS payload_hex "
                    + "FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ? AND outbox_sequence = ?",
                event.outboxStreamKey(),
                Long.parseLong(event.outboxSequence()))
            .fetchOne();
    assertThat(row).isNotNull();
    byte[] payload = HexFormat.of().parseHex(row.get("payload_hex", String.class));
    String payloadJson = new String(payload, StandardCharsets.UTF_8);
    assertThat(event.eventId()).isEqualTo(row.get("event_id", String.class));
    assertThat(event.eventDigest()).isEqualTo(row.get("event_digest", String.class));
    assertThat(event.canonicalJson()).isEqualTo(payloadJson);
    assertThat(event.canonicalJsonUtf8()).containsExactly(payload);
    assertThat(MembershipAuthorityEventV1Codec.verify(payloadJson).eventDigest())
        .isEqualTo(event.eventDigest());
    assertThat(source.snapshot().outboxSourceEvidence())
        .anySatisfy(
            evidence -> {
              assertThat(evidence.outboxStreamKey()).isEqualTo(event.outboxStreamKey());
              assertThat(evidence.outboxSequence()).isEqualTo(event.outboxSequence());
              assertThat(evidence.eventId()).isEqualTo(event.eventId());
              assertThat(evidence.eventDigest()).isEqualTo(event.eventDigest());
              assertThat(evidence.canonicalEventJson()).isEqualTo(payloadJson);
            });
  }

  private ProjectionSnapshot assertProjection(
      ApplyResult result,
      Outcome expectedOutcome,
      UUID accountUuid,
      UUID tenantUuid,
      long generation,
      long sourceVersion,
      String membershipVersion,
      String sequence,
      String sourceEvent) {
    assertThat(result.outcome()).isEqualTo(expectedOutcome);
    ProjectionSnapshot projection = result.snapshot().orElseThrow();
    assertThat(projection.key()).isEqualTo(key(accountUuid, tenantUuid));
    assertRedisExactWithoutTtl(projection);
    JsonNode json = parse(projection.json());
    assertThat(json.path("schemaVersion").asText())
        .isEqualTo("account-auth-membership-generation-projection/v1");
    assertThat(json.path("accountId").asText()).isEqualTo(accountUuid.toString());
    assertThat(json.path("tenantId").asText()).isEqualTo(tenantUuid.toString());
    assertThat(json.path("membershipAuthorityGeneration").asText())
        .isEqualTo(Long.toString(generation));
    assertThat(json.path("sourceVersion").asText()).isEqualTo(Long.toString(sourceVersion));
    assertThat(json.path("membershipVersion").size()).isEqualTo(1);
    assertThat(json.path("membershipVersion").path(tenantUuid.toString()).asText())
        .isEqualTo(membershipVersion);
    assertThat(json.path("outboxStreamKey").asText())
        .isEqualTo(membershipStreamKey(accountUuid, tenantUuid));
    assertThat(json.path("outboxSequence").asText()).isEqualTo(sequence);
    if (sourceEvent == null) {
      assertThat(json.has("sourceEvent")).isFalse();
      assertThat(fields(json))
          .containsExactlyInAnyOrder(
              "schemaVersion",
              "accountId",
              "tenantId",
              "membershipAuthorityGeneration",
              "sourceVersion",
              "membershipVersion",
              "outboxStreamKey",
              "outboxSequence");
    } else {
      assertThat(json.path("sourceEvent").asText()).isEqualTo(sourceEvent);
      assertThat(fields(json))
          .containsExactlyInAnyOrder(
              "schemaVersion",
              "accountId",
              "tenantId",
              "membershipAuthorityGeneration",
              "sourceVersion",
              "membershipVersion",
              "outboxStreamKey",
              "outboxSequence",
              "sourceEvent");
      MembershipEvent parsedEvent =
          MembershipAuthorityEventV1Codec.verify(json.path("sourceEvent").asText());
      assertThat(parsedEvent.canonicalJson()).isEqualTo(sourceEvent);
      assertThat(parsedEvent.outboxSequence()).isEqualTo(sequence);
      assertThat(parsedEvent.membershipVersion())
          .isEqualTo(Map.of(tenantUuid.toString(), membershipVersion));
      assertThat(parsedEvent.membershipAuthorityGeneration()).isEqualTo(Long.toString(generation));
    }
    return projection;
  }

  private void assertRedisExactWithoutTtl(ProjectionSnapshot projection) {
    assertThat(adminTemplate.opsForValue().get(projection.key())).isEqualTo(projection.json());
    assertThat(adminTemplate.getExpire(projection.key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
  }

  private String sameCheckpointPoison(ProjectionSnapshot projection) throws Exception {
    JsonNode current = parse(projection.json());
    String eventJson = current.path("sourceEvent").asText();
    Map<String, Object> eventPreimage =
        JSON.readValue(eventJson, new TypeReference<Map<String, Object>>() {});
    eventPreimage.remove("eventDigest");
    eventPreimage.put("issuanceFence", "999");
    String poisonedEvent = MembershipAuthorityEventV1Codec.seal(eventPreimage).canonicalJson();
    ObjectNode poisonedProjection = (ObjectNode) parse(projection.json());
    poisonedProjection.put("sourceEvent", poisonedEvent);
    String poisonedJson = writeJson(poisonedProjection);
    assertThat(MembershipGenerationProjection.parse(poisonedJson).toJson()).isEqualTo(poisonedJson);
    return poisonedJson;
  }

  private SourceSqlState sourceSqlState(
      AccountFixture account, UUID tenantUuid, Long legacyTenantId) {
    UUID accountUuid = account.accountUuid();
    long accountId = account.accountId();
    String membershipStream = membershipStreamKey(accountUuid, tenantUuid);
    List<Object> sourceStreamKeys =
        List.of(
            membershipStream,
            AUTHORITY_STREAM_PREFIX + "account/" + accountUuid,
            AUTHORITY_STREAM_PREFIX + "tenant/" + tenantUuid,
            AUTHORITY_STREAM_PREFIX + "issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER);
    List<Map<String, Object>> membershipRows =
        legacyTenantId == null
            ? queryRows(
                "SELECT xmin::text AS row_xmin, * FROM account_tenant_membership "
                    + "WHERE account_id = ? ORDER BY id",
                accountId)
            : queryRows(
                "SELECT xmin::text AS row_xmin, * FROM account_tenant_membership "
                    + "WHERE account_id = ? AND tenant_id = ? ORDER BY id",
                accountId,
                legacyTenantId);
    List<Map<String, Object>> receipts =
        legacyTenantId == null
            ? queryRows(
                "SELECT xmin::text AS row_xmin, * FROM account_membership_transition_receipts "
                    + "WHERE account_id = ? ORDER BY tenant_id, receipt_sequence",
                accountId)
            : queryRows(
                "SELECT xmin::text AS row_xmin, * FROM account_membership_transition_receipts "
                    + "WHERE account_id = ? AND tenant_id = ? ORDER BY receipt_sequence",
                accountId,
                legacyTenantId);
    List<Map<String, Object>> receiptHeads =
        legacyTenantId == null
            ? queryRows(
                "SELECT xmin::text AS row_xmin, * "
                    + "FROM account_membership_transition_receipt_stream_heads "
                    + "WHERE account_id = ? ORDER BY tenant_id",
                accountId)
            : queryRows(
                "SELECT xmin::text AS row_xmin, * "
                    + "FROM account_membership_transition_receipt_stream_heads "
                    + "WHERE account_id = ? AND tenant_id = ?",
                accountId,
                legacyTenantId);
    List<Map<String, Object>> roleSnapshots =
        legacyTenantId == null
            ? queryRows(
                "SELECT s.xmin::text AS row_xmin, s.membership_id, s.snapshot_version "
                    + "FROM account_tenant_membership_role_snapshots s "
                    + "JOIN account_tenant_membership m ON m.id = s.membership_id "
                    + "WHERE m.account_id = ? ORDER BY s.membership_id",
                accountId)
            : queryRows(
                "SELECT s.xmin::text AS row_xmin, s.membership_id, s.snapshot_version "
                    + "FROM account_tenant_membership_role_snapshots s "
                    + "JOIN account_tenant_membership m ON m.id = s.membership_id "
                    + "WHERE m.account_id = ? AND m.tenant_id = ? ORDER BY s.membership_id",
                accountId,
                legacyTenantId);
    List<Map<String, Object>> roleIdentifiers =
        legacyTenantId == null
            ? queryRows(
                "SELECT r.xmin::text AS row_xmin, r.membership_id, r.snapshot_version, "
                    + "r.role_identifier FROM account_tenant_membership_role_snapshot_roles r "
                    + "JOIN account_tenant_membership m ON m.id = r.membership_id "
                    + "WHERE m.account_id = ? ORDER BY r.membership_id, r.role_identifier",
                accountId)
            : queryRows(
                "SELECT r.xmin::text AS row_xmin, r.membership_id, r.snapshot_version, "
                    + "r.role_identifier FROM account_tenant_membership_role_snapshot_roles r "
                    + "JOIN account_tenant_membership m ON m.id = r.membership_id "
                    + "WHERE m.account_id = ? AND m.tenant_id = ? "
                    + "ORDER BY r.membership_id, r.role_identifier",
                accountId,
                legacyTenantId);
    List<Map<String, Object>> auditRows =
        legacyTenantId == null
            ? List.of()
            : queryRows(
                "SELECT xmin::text AS row_xmin, * FROM account_audit_outbox "
                    + "WHERE scope = 'tenant' AND tenant_id = ? ORDER BY audit_event_id",
                legacyTenantId);

    return new SourceSqlState(
        queryRow(
            "SELECT xmin::text AS row_xmin, id, account_uuid FROM accounts WHERE id = ?",
            accountId),
        queryRow(
            "SELECT xmin::text AS row_xmin, * FROM account_authority_issuance_fences "
                + "WHERE account_uuid = ?",
            accountUuid),
        queryRows(
            "SELECT xmin::text AS row_xmin, * FROM account_authority_generations "
                + "WHERE (scope_kind = 'ACCOUNT' AND account_uuid = ?) "
                + "OR (scope_kind = 'MEMBERSHIP' AND account_uuid = ? AND tenant_uuid = ?) "
                + "OR (scope_kind = 'TENANT' AND tenant_uuid = ?) "
                + "OR (scope_kind = 'ISSUER' AND issuer_id = ?) ORDER BY scope_kind, tenant_uuid",
            accountUuid,
            accountUuid,
            tenantUuid,
            tenantUuid,
            AccountServiceImpl.ACCOUNT_JWT_ISSUER),
        queryRow(
            "SELECT xmin::text AS row_xmin, * FROM account_membership_pair_authority "
                + "WHERE account_uuid = ? AND tenant_uuid = ?",
            accountUuid,
            tenantUuid),
        queryRows(
            "SELECT xmin::text AS row_xmin, * FROM account_fresh_tenant_identity_associations "
                + "WHERE canonical_tenant_id = ?",
            tenantUuid),
        queryRows(
            "SELECT xmin::text AS row_xmin, * FROM account_canonical_tenant_identity_claims "
                + "WHERE canonical_tenant_id = ?",
            tenantUuid),
        legacyTenantId == null
            ? List.of()
            : queryRows(
                "SELECT xmin::text AS row_xmin, * "
                    + "FROM account_approved_legacy_tenant_associations "
                    + "WHERE legacy_tenant_id = ?",
                legacyTenantId),
        membershipRows,
        roleSnapshots,
        roleIdentifiers,
        receipts,
        receiptHeads,
        queryRows(
            "SELECT xmin::text AS row_xmin, * FROM account_authority_outbox_streams "
                + "WHERE outbox_stream_key IN (?, ?, ?, ?) ORDER BY outbox_stream_key",
            sourceStreamKeys.toArray()),
        queryRows(
            "SELECT xmin::text AS row_xmin, outbox_stream_key, request_id, outbox_sequence, "
                + "event_id, event_digest, encode(payload, 'hex') AS payload_hex "
                + "FROM account_authority_outbox_events WHERE outbox_stream_key IN (?, ?, ?, ?) "
                + "ORDER BY outbox_stream_key, outbox_sequence",
            sourceStreamKeys.toArray()),
        auditRows);
  }

  private Map<String, Object> queryRow(String sql, Object... bindings) {
    Record row = dsl.resultQuery(sql, bindings).fetchOne();
    return row == null ? Map.of() : stableMap(row);
  }

  private List<Map<String, Object>> queryRows(String sql, Object... bindings) {
    return dsl.resultQuery(sql, bindings).fetch(this::stableMap);
  }

  private Map<String, Object> stableMap(Record row) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (Field<?> field : row.fields()) {
      Object value = row.get(field);
      values.put(
          field.getName(), value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
    }
    return Collections.unmodifiableMap(values);
  }

  private void assertDeniedKeyRead(String key) {
    assertThatThrownBy(() -> ownerTemplate.opsForValue().get(key))
        .satisfies(failure -> assertThat(exceptionMessageChain(failure)).contains("NOPERM"));
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
        throw new IllegalStateException("Timed out waiting for membership projection race latch");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Membership projection race was interrupted", interrupted);
    }
  }

  private JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception malformed) {
      throw new IllegalArgumentException("Projection JSON is malformed", malformed);
    }
  }

  private String writeJson(JsonNode json) {
    try {
      return JSON.writeValueAsString(json);
    } catch (Exception malformed) {
      throw new IllegalArgumentException("Projection JSON cannot be serialized", malformed);
    }
  }

  private Set<String> fields(JsonNode json) {
    Set<String> names = new java.util.HashSet<>();
    json.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private String key(UUID accountUuid, UUID tenantUuid) {
    return MEMBERSHIP_KEY_PREFIX + accountUuid + ":" + tenantUuid;
  }

  private String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return AUTHORITY_STREAM_PREFIX + "membership/" + accountUuid + "/" + tenantUuid;
  }

  private String compactUuid() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private long positiveRandomLong() {
    long candidate = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return candidate == 0L ? 1L : candidate;
  }

  private record AccountFixture(long accountId, UUID accountUuid) {}

  private record JoinFixture(
      AccountFixture account,
      long accountId,
      UUID accountUuid,
      long legacyTenantId,
      UUID tenantUuid,
      String requestId,
      DirectTextCallerContext caller,
      DirectTextJoinScope scope) {}

  private record SourceSqlState(
      Map<String, Object> accountRow,
      Map<String, Object> issuanceFence,
      List<Map<String, Object>> generationRows,
      Map<String, Object> pairAuthority,
      List<Map<String, Object>> freshTenantAssociations,
      List<Map<String, Object>> freshTenantClaims,
      List<Map<String, Object>> retainedTenantAssociations,
      List<Map<String, Object>> memberships,
      List<Map<String, Object>> roleSnapshots,
      List<Map<String, Object>> roleIdentifiers,
      List<Map<String, Object>> transitionReceipts,
      List<Map<String, Object>> transitionReceiptHeads,
      List<Map<String, Object>> authorityStreams,
      List<Map<String, Object>> membershipEvents,
      List<Map<String, Object>> auditRows) {}
}
