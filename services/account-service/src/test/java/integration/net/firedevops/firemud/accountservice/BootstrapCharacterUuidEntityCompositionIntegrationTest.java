package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.client.AccountBootstrapRosterReadClient;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountCanonicalPublicRealmScopeOwner;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadGrpcCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterFixture;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterPeerInterceptor;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterReadGrpcService;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterReadService;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus;
import net.firedevops.firemud.entitymanagement.mapper.CharacterMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.service.impl.CharacterServiceImpl;
import net.firedevops.firemud.entitymanagement.service.impl.EntityManagementGrpcService;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real Account HTTP registration/bootstrap/REALMS/CHARS, Redis credential lookup, canonical Account
 * JOIN owners and Entity/Saga PostgreSQL assignment/discovery, with Account-to-Entity-to-GS mTLS.
 * Account-side GS target/World observations and complete upstream publication/COMMITTED owner bytes
 * are controlled fixtures. Assignment eligibility and local PKI are also controlled: this does not
 * execute publication, GS/World owner stores, production custody, admission or first OPEN. The
 * ordinary RPC is retained only for a separate numeric-selector refusal/retention proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = AccountServiceApplication.class,
    properties = {
      "spring.config.import=classpath:account-main-application.yml",
      "spring.grpc.server.port=0",
      "firemud.grpc.workload-namespace=test",
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      "firemud.mail.verification-url=https://account.test/verify-email?token=%s"
    })
class BootstrapCharacterUuidEntityCompositionIntegrationTest {
  private static final String TEST_NAMESPACE = "test";
  private static final String TEST_ACCOUNT_PEER = "spiffe://firemud/ns/test/sa/account-service";
  private static final String TEST_ENTITY_PEER =
      "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final long RETAINED_NUMERIC_ACCOUNT_ID = 98765L;
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final AccountBootstrapRosterFixture.Fixture SOURCE = createSource();
  private static final Path PKI_DIRECTORY = createTemporaryDirectory();
  private static final LoopbackMtlsTestPki.Pki TEST_PKI = createTestPki();
  private static final CommonGrpcClientProperties ACCOUNT_CLIENT_TLS = createAccountClientTls();
  private static final PostgreSQLContainer<?> entityPostgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static PostgreSQLContainer<?> accountPostgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  private static volatile EntityRuntime entityRuntime;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, accountPostgres, "account_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
    registry.add("firemud.services.entity-management-service", () -> entityServiceTarget());
    registry.add("FIREMUD_GRPC_CERT_CHAIN_PATH", ACCOUNT_CLIENT_TLS::getCertChain);
    registry.add("FIREMUD_GRPC_PRIVATE_KEY_PATH", ACCOUNT_CLIENT_TLS::getPrivateKey);
    registry.add("FIREMUD_GRPC_CA_CERT_PATH", ACCOUNT_CLIENT_TLS::getCaCert);
  }

  @LocalServerPort private int accountHttpPort;
  @Autowired private DSLContext accountDsl;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private AccountServiceImpl facade;
  @Autowired private AccountRepository accounts;
  @Autowired private AccountAuthorityGenerationRepository generations;
  @Autowired private AccountAuthoritySourceEvidenceRepository accountSources;
  @Autowired private FreshTenantIdentityAssociationRepository tenants;
  @Autowired private AccountTenantMembershipRepository members;
  @Autowired private AccountConnectScopeRepository scopes;
  @Autowired private AccountTenantAuthorityEventRepository tenantEvents;
  @Autowired private AccountDemoTenantEntitlementRepository demos;
  @Autowired private AccountCanonicalFirstJoinTerminalCoordinator terminal;
  @Autowired private AccountTokenProperties tokenProperties;
  @Autowired private SessionService sessions;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  @org.junit.jupiter.api.AfterAll
  static void stopEntityRuntime() throws Exception {
    EntityRuntime runtime = entityRuntime;
    if (runtime != null) {
      runtime.server().shutdownNow();
      assertThat(runtime.server().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      runtime.ownerClient().close();
      runtime.sourceServer().shutdownNow();
      assertThat(runtime.sourceServer().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      runtime.meters().close();
    }
    if (entityPostgres.isRunning()) entityPostgres.stop();
  }

  @org.junit.jupiter.api.Test
  void failedEntityStartupClosesEveryRegisteredResourceDespiteCleanupFailures() {
    var failure = new IOException("Entity startup failed");
    var clientFailure = new IOException("Owner client cleanup failed");
    var sourceFailure = new IllegalStateException("Source server cleanup failed");
    var closed = new ArrayList<String>();
    Deque<AutoCloseable> resources = new ArrayDeque<>();
    resources.push(() -> closed.add("postgres"));
    resources.push(
        () -> {
          closed.add("source server");
          throw sourceFailure;
        });
    resources.push(
        () -> {
          closed.add("owner client");
          throw clientFailure;
        });
    resources.push(() -> closed.add("meters"));
    resources.push(() -> closed.add("entity server"));

    closeFailedStartup(failure, resources);

    assertThat(closed)
        .containsExactly("entity server", "meters", "owner client", "source server", "postgres");
    assertThat(failure.getSuppressed()).containsExactly(clientFailure, sourceFailure);
    assertThat(resources).isEmpty();
  }

  @org.junit.jupiter.api.Test
  @org.springframework.test.annotation.DirtiesContext
  void bootstrapCharactersUseCanonicalOwnersAndPersistedLevelsAcrossHttpAndTwoMtlsReads()
      throws Exception {
    var entity = requireEntityRuntime();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    TestAccount ownerA = registerAccount("uuid-owner-a-" + suffix);
    TestAccount ownerB = registerAccount("uuid-owner-b-" + suffix);
    assertThat(ownerA.accountUuid()).isNotEqualTo(ownerB.accountUuid());
    configureCanonicalAccountOwners();
    initializeMemberAuthority(ownerA);
    initializeMemberAuthority(ownerB);
    String tokenA = issuePlayerBootstrap(ownerA);
    String tokenB = issuePlayerBootstrap(ownerB);
    String scopeA = discoverConnectScope(ownerA, tokenA);
    String scopeB = discoverConnectScope(ownerB, tokenB);
    // Existing canonical JOIN is invoked as an owner composition, not a newly exposed HTTP route.
    var joinedA =
        facade.joinPublicProduction(
            tokenA, new JoinPublicProductionRequest(scopeA, UUID.randomUUID().toString()));
    var joinedB =
        facade.joinPublicProduction(
            tokenB, new JoinPublicProductionRequest(scopeB, UUID.randomUUID().toString()));
    assertThat(joinedA.success()).isTrue();
    assertThat(joinedB.success()).isTrue();
    assertThat(joinedA.tenantId()).isEqualTo(SOURCE.target().tenantUuid());
    assertThat(joinedA.membershipVersion()).isEqualTo(2L);
    assertThat(joinedB.membershipVersion()).isEqualTo(2L);
    assertThat(
            accountDsl
                .fetch(
                    "SELECT role_identifier FROM account_tenant_membership_role_snapshot_roles roles "
                        + "JOIN account_tenant_membership member ON member.id = roles.membership_id "
                        + "WHERE member.tenant_uuid = ? ORDER BY member.id",
                    SOURCE.target().tenantUuid())
                .getValues(0, String.class))
        .containsExactly("player", "player");
    try (var client = newRosterClient()) {
      client.init();
      facade.setAccountBootstrapRosterReadClient(client);
      var beforeEmpty = entityRows(entity);
      var emptyRoster = listBootstrapCharacters(tokenA, scopeA);
      assertThat(emptyRoster.isArray()).isTrue();
      assertThat(emptyRoster.size()).isZero();
      assertThat(entityRows(entity)).isEqualTo(beforeEmpty);
    }

    UUID actorA = assign(entity, ownerA, SOURCE.target(), "owned-by-a-" + suffix);
    UUID actorB = assign(entity, ownerB, SOURCE.target(), "owned-by-b-" + suffix);
    entity.dsl().execute("UPDATE characters SET level = 12 WHERE character_uuid = ?", actorA);
    assertThat(
            entity
                .dsl()
                .fetchValue("SELECT level FROM characters WHERE character_uuid = ?", actorB))
        .isEqualTo(0);
    var anotherNamespace =
        CanonicalGameplayRosterTarget.fromWire(
            SOURCE.target().toWire().toBuilder()
                .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
                .build());
    var anotherTenant =
        CanonicalGameplayRosterTarget.fromWire(
            SOURCE.target().toWire().toBuilder()
                .setTenantUuid(UUID.randomUUID().toString())
                .build());
    assign(entity, ownerA, anotherNamespace, "wrong-namespace-" + suffix);
    assign(entity, ownerA, anotherTenant, "wrong-tenant-" + suffix);
    var quarantined = saveOrdinaryCharacter(entity, ownerA.accountUuid(), "quarantined-" + suffix);
    assertOrdinaryUuidRowIsQuarantined(entity, quarantined.getId(), ownerA.accountUuid());
    var entityBefore = entityRows(entity);
    var retainedBefore = retainedNumericOwnerRow(entity);
    int ownerReadsBefore = entity.ownerReads().get();

    try (var client = newRosterClient()) {
      client.init();
      facade.setAccountBootstrapRosterReadClient(client);
      JsonNode actorsA = listBootstrapCharacters(tokenA, scopeA);
      JsonNode actorsB = listBootstrapCharacters(tokenB, scopeB);
      assertThat(characterNames(actorsA)).containsExactly("owned-by-a-" + suffix);
      assertThat(characterNames(actorsB)).containsExactly("owned-by-b-" + suffix);
      assertThat(actorsA.get(0).path("characterId").asText()).isEqualTo(actorA.toString());
      assertThat(actorsB.get(0).path("characterId").asText()).isEqualTo(actorB.toString());
      assertThat(actorsA.get(0).path("level").asInt()).isEqualTo(12);
      assertThat(actorsB.get(0).path("level").asInt()).isZero();
      assertThat(actorsA.get(0).has("level")).isTrue();
      assertThat(actorsB.get(0).has("level")).isTrue();
      assertThat(actorsA.get(0).path("stateScope").asText()).isEqualTo("SHARED");
      assertThat(actorsA.get(0).path("characterCreationPolicy").asText())
          .isEqualTo("PRESEEDED_ONLY");
      var crossOwner = charactersResponse(tokenA, scopeB);
      assertThat(crossOwner.statusCode()).isBetween(400, 499);
      assertThat(jsonMapper.readTree(crossOwner.body()).path("error").path("code").asText())
          .isEqualTo("CONNECT_SCOPE_INVALID");
    }
    assertThat(entity.ownerReads().get() - ownerReadsBefore).isEqualTo(4);
    assertThat(entityRows(entity)).isEqualTo(entityBefore);
    assertThat(retainedNumericOwnerRow(entity)).isEqualTo(retainedBefore);
    assertThat(
            accountDsl.fetchValue(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE tenant_uuid = ?",
                SOURCE.target().tenantUuid()))
        .isEqualTo(2L);
    assertThat(
            accountDsl.fetchValue(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE tenant_uuid = ? AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'",
                SOURCE.target().tenantUuid()))
        .isEqualTo(2L);
    assertThat(
            accountDsl.fetchValue(
                "SELECT COUNT(*) FROM account_join_operations WHERE account_uuid IN (?, ?) AND status = 'COMMITTED'",
                ownerA.accountUuid(),
                ownerB.accountUuid()))
        .isEqualTo(2L);
  }

  @org.junit.jupiter.api.Test
  void retainedNumericSelectorIsRefusedByOrdinaryRpcWithoutRewritingItsRow() throws Exception {
    var entity = requireEntityRuntime();
    var retainedBefore = retainedNumericOwnerRow(entity);
    assertThat(retainedBefore).hasSize(1);
    var request =
        ListCharactersByAccountRequest.newBuilder()
            .setTenantId("1")
            .setAccountId(Long.toString(RETAINED_NUMERIC_ACCOUNT_ID))
            .setGameInstanceId("44")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .build();
    var response = listCharactersDirectlyAsAccount(request);
    assertThat(response.hasError()).isTrue();
    assertThat(response.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(response.getCharactersList()).isEmpty();
    assertThat(retainedNumericOwnerRow(entity)).isEqualTo(retainedBefore);
  }

  private void configureCanonicalAccountOwners() {
    var transaction = new TransactionTemplate(transactionManager);
    var target = accountTarget();
    var creationRequest = UUID.randomUUID();
    var operation = UUID.randomUUID();
    String key = "b-" + operation.toString().replace("-", "");
    String digest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, creationRequest, key, "Bootstrap composition", null);
    long rowId = 101L;
    var source =
        new FreshTenantCreationEvidence(
            1,
            TEST_NAMESPACE,
            creationRequest,
            operation,
            digest,
            target.canonicalTenantId(),
            rowId,
            key,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                TEST_NAMESPACE,
                creationRequest,
                operation,
                digest,
                target.canonicalTenantId(),
                rowId,
                key,
                "NEW_GAME_ROW"));
    transaction.executeWithoutResult(
        status -> {
          tenants.importVerified(source);
          generations.initializeTenantIfAbsent(target.canonicalTenantId());
          accountSources.initializeIssuerIfAbsent("firemud-account-service");
          demos.provision(
              new DemoTenantEntitlementRequest(
                  UUID.randomUUID(),
                  target.canonicalTenantId(),
                  source.creationRequestId(),
                  source.requestDigest(),
                  null,
                  null,
                  null,
                  true,
                  true,
                  true,
                  true,
                  new DemoTenantEntitlementRequest.Quotas(10L, 3L, 4096L)),
              source);
        });
    var targets = controlledAccountTargets(target);
    var hosting = Mockito.mock(AccountHostingSubscriptionLifecycleRepository.class);
    var observer = Mockito.mock(StripeHostingSubscriptionObserver.class);
    var clock = Clock.systemUTC();
    var entitlement =
        new AccountPublicBootstrapEntitlementOwner(
            tenantEvents,
            new AccountHostingSubscriptionLifecycleService(hosting, observer, transactionManager),
            demos,
            transactionManager,
            clock);
    facade.setCanonicalPublicRealmScopeOwner(
        new AccountCanonicalPublicRealmScopeOwner(
            new AccountPlayerBootstrapCallerVerifier(jwtUtil, accounts, sessions),
            targets,
            entitlement,
            accounts,
            members,
            tenants,
            tenantEvents,
            scopes,
            jwtUtil,
            tokenProperties,
            clock,
            transactionManager,
            TEST_NAMESPACE));
    facade.setCanonicalScopeConsumers(
        new AccountCanonicalConnectScopeReader(jwtUtil, scopes, transactionManager, clock),
        targets,
        tenantEvents,
        demos,
        hosting,
        observer,
        terminal,
        TEST_NAMESPACE,
        clock);
  }

  private CanonicalGameplayAdmissionDecisionReadClient controlledAccountTargets(Target target) {
    var client = Mockito.mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    Mockito.when(client.listVisiblePublicProductionAdmissionTargets(Mockito.any()))
        .thenAnswer(
            invocation -> {
              var request =
                  new CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest(
                      invocation.getArgument(0, UUID.class), TEST_NAMESPACE);
              return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromDiscoveryResponse(
                  request,
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(
                      request, List.of(target)));
            });
    Mockito.when(client.resolvePublishedRealmAdmissionTarget(Mockito.any()))
        .thenAnswer(
            invocation -> {
              var request =
                  invocation.getArgument(
                      0,
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector.class);
              return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromTargetResolutionResponse(
                  request,
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionResponse(
                      request, target));
            });
    Mockito.when(client.readPublishedRealmAdmissionOwner(Mockito.any()))
        .thenAnswer(
            invocation -> {
              var request =
                  invocation.getArgument(0, CanonicalGameplayRosterOwnerReadEvidence.Request.class);
              var published =
                  Mockito.mock(
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence.class);
              Mockito.when(published.selector()).thenReturn(request);
              Mockito.when(published.currentTarget()).thenReturn(target);
              Mockito.when(published.sourceEvidence()).thenReturn(ownerEvidence(request));
              var world = Mockito.mock(WorldCanonicalInstanceLifecycleEvidence.class);
              Mockito.when(world.lifecycleStatus()).thenReturn("ACTIVE");
              Mockito.when(world.lifecycleEpoch()).thenReturn(target.activeWorldEpoch());
              Mockito.when(published.worldLifecycleEvidence()).thenReturn(world);
              return published;
            });
    return client;
  }

  private void initializeMemberAuthority(TestAccount account) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                generations.initialize(
                    AccountAuthorityGenerationRepository.AuthorityScope.membership(
                        account.accountUuid(), SOURCE.target().tenantUuid())));
  }

  private static Target accountTarget() {
    var target = SOURCE.target();
    var proof = SOURCE.evidence().gameSessionOwnerProof();
    var hold = proof.holdIdentity();
    var request = hold.request();
    return new Target(
        TEST_NAMESPACE,
        "bootstrap-tenant",
        target.tenantUuid(),
        target.worldSlug(),
        "Earth",
        target.realmUuid(),
        target.realmSlug(),
        "Main",
        1L,
        44L,
        target.playableStateNamespaceId(),
        "SHARED",
        target.gameInstanceUuid(),
        target.canonicalVersionUuid(),
        1L,
        target.catalogRevision(),
        target.pointerVersion(),
        target.admissionPointerSnapshotDigest(),
        target.activeWorldEpoch(),
        request.initialAdmissionRequestId(),
        request.initialAdmissionRequestDigest(),
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind.NO_PRIOR_POINTER,
        null,
        hold.holdId(),
        hold.holdFence(),
        hold.holdBindingDigest(),
        proof.auditEventId(),
        proof.proofDigest(),
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome.COMMITTED,
        false,
        proof.terminalAt(),
        "PRESEEDED_ONLY");
  }

  private static CanonicalGameplayRosterOwnerReadEvidence ownerEvidence(
      CanonicalGameplayRosterOwnerReadEvidence.Request request) {
    var base = SOURCE.evidence().request();
    var expected =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            request.requestUuid(),
            request.canonicalAccountUuid(),
            base.targetNamespace(),
            base.canonicalTenantUuid(),
            base.worldSlug(),
            base.realmUuid(),
            base.realmSlug(),
            base.playableStateNamespaceUuid(),
            base.playableStateScope(),
            base.canonicalGameInstanceUuid(),
            base.canonicalVersionUuid(),
            base.expectedCatalogRevision(),
            base.expectedPointerVersion(),
            base.expectedActiveWorldEpoch());
    if (!request.equals(expected))
      throw new IllegalArgumentException("Controlled owner target mismatch");
    return new CanonicalGameplayRosterOwnerReadEvidence(
        request,
        SOURCE.target().admissionPointerSnapshotDigest(),
        SOURCE.evidence().gameSessionOwnerProof(),
        SOURCE.evidence().publishedPolicySetEvidence());
  }

  private TestAccount registerAccount(String username) throws Exception {
    String email = username + "@example.com";
    var request =
        HttpRequest.newBuilder(URI.create(accountBaseUrl() + "/accounts"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(
                        Map.of("username", username, "email", email, "password", "password123"))))
            .build();
    var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    UUID id =
        UUID.fromString(jsonMapper.readTree(response.body()).path("data").path("id").asText());
    var row = accounts.findByAccountUuid(id).orElseThrow();
    assertThat(row.getAccountUuidSourceNumericId()).isEqualTo(row.getId());
    assertThat(row.getAccountUuidProvenance().name()).isEqualTo("ACCOUNT_REPOSITORY_INSERT");
    return new TestAccount(username, email, row.getId(), id);
  }

  private String issuePlayerBootstrap(TestAccount account) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(accountBaseUrl() + "/auth/player-bootstrap"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(
                        Map.of("accountIdentifier", account.username(), "secret", "password123"))))
            .build();
    var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var data = jsonMapper.readTree(response.body()).path("data");
    assertThat(data.path("accountId").asText()).isEqualTo(account.accountUuid().toString());
    String token = data.path("bootstrapToken").asText();
    assertThat(jwtUtil.parseToken(token).getPayload().getSubject())
        .isEqualTo(account.accountUuid().toString());
    return token;
  }

  private String discoverConnectScope(TestAccount account, String token) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    accountBaseUrl()
                        + "/auth/bootstrap/worlds/"
                        + SOURCE.target().worldSlug()
                        + "/realms?tenantSlug="
                        + accountTarget().tenantSlug()))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();
    var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      // Preserve the HTTP assertion while exposing an owner/proxy exception otherwise hidden by
      // the public INTERNAL_ERROR projection. This uses the same actual caller and owner setup.
      org.assertj.core.api.Assertions.assertThatCode(
              () ->
                  facade.listBootstrapRealms(
                      token, accountTarget().tenantSlug(), SOURCE.target().worldSlug()))
          .as("REALMS owner composition after HTTP response: %s", response.body())
          .doesNotThrowAnyException();
    }
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var realms = jsonMapper.readTree(response.body()).path("data");
    assertThat(realms.size()).isEqualTo(1);
    assertThat(realms.get(0).path("tenantId").asText())
        .isEqualTo(SOURCE.target().tenantUuid().toString());
    String scope = realms.get(0).path("connectScopeId").asText();
    var claims = jwtUtil.parseToken(scope).getPayload();
    assertThat(claims.getSubject()).isEqualTo(account.accountUuid().toString());
    assertThat(claims.get("scopeDigestVersion", Number.class).longValue()).isEqualTo(2L);
    assertThat(claims.get("tenantId", String.class))
        .isEqualTo(SOURCE.target().tenantUuid().toString());
    return scope;
  }

  private HttpResponse<String> charactersResponse(String token, String scope) throws Exception {
    String encoded = URLEncoder.encode(scope, StandardCharsets.UTF_8);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    accountBaseUrl()
                        + "/auth/bootstrap/worlds/"
                        + SOURCE.target().worldSlug()
                        + "/realms/"
                        + SOURCE.target().realmSlug()
                        + "/characters?connectScopeId="
                        + encoded))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode listBootstrapCharacters(String token, String scope) throws Exception {
    var response = charactersResponse(token, scope);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return jsonMapper.readTree(response.body()).path("data");
  }

  private static UUID assign(
      EntityRuntime runtime,
      TestAccount account,
      CanonicalGameplayRosterTarget target,
      String name) {
    UUID operation = UUID.randomUUID();
    var evidence =
        new PreseededActorAssignmentOwnerEvidence(
            operation,
            account.accountUuid(),
            PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
            Instant.parse("2026-10-04T00:00:00Z"),
            4L,
            "1".repeat(64),
            target.tenantUuid(),
            target.realmUuid(),
            target.worldSlug(),
            target.realmSlug(),
            target.gameInstanceUuid().toString(),
            target.catalogRevision(),
            target.canonicalVersionUuid(),
            target.publishedPolicyDigest(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
            PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
            PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
            "2".repeat(64),
            target.publishedOwnerProofDigest(),
            target.publishedReleaseBundleRef());
    var request =
        new PreseededActorAssignmentRequest(
            operation,
            account.accountUuid(),
            new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, name),
            new PreseededActorAssignmentExpectedTarget(
                target.tenantUuid(),
                target.realmUuid(),
                target.worldSlug(),
                target.realmSlug(),
                target.gameInstanceUuid().toString(),
                target.catalogRevision(),
                target.canonicalVersionUuid(),
                target.publishedPolicyDigest(),
                target.playableStateNamespaceId(),
                target.publishedReleaseBundleRef(),
                target.playableStateScope()));
    var result =
        new TransactionTemplate(runtime.transactionManager())
            .execute(
                status ->
                    runtime
                        .characterRepository()
                        .assignPreseededActor(
                            request,
                            evidence,
                            new RuntimeAccountIdentityEvidence(
                                1,
                                TEST_NAMESPACE,
                                operation,
                                account.accountUuid(),
                                account.storageId(),
                                "ACCOUNT_REPOSITORY_INSERT"),
                            request.mutationIntentDigest()));
    assertThat(result.outcome()).isEqualTo(PreseededActorAssignmentResult.Outcome.ASSIGNED);
    assertThat(
            runtime
                .dsl()
                .fetchValue(
                    "SELECT actor_identity_status FROM characters WHERE character_uuid = ?",
                    result.characterUuid()))
        .isEqualTo("OWNER_RESOLVED");
    return result.characterUuid();
  }

  private static net.firedevops.firemud.entitymanagement.entity.Character saveOrdinaryCharacter(
      EntityRuntime runtime, UUID account, String name) {
    var character = new net.firedevops.firemud.entitymanagement.entity.Character();
    character.setTenantId(1L);
    character.setAccountUuid(account);
    character.setPlayableStateKey("shared-live");
    character.setName(name);
    return runtime.characterRepository().save(character);
  }

  private static void assertOrdinaryUuidRowIsQuarantined(
      EntityRuntime runtime, long id, UUID account) {
    var character = runtime.characterRepository().findById(id).orElseThrow();
    assertThat(character.getAccountUuid()).isEqualTo(account);
    assertThat(character.getAccountId()).isNull();
    assertThat(character.getActorIdentity().status()).isEqualTo(ActorIdentityStatus.QUARANTINED);
    assertThat(character.getActorIdentity().tenantUuid()).isNull();
    assertThat(character.getActorIdentity().playableStateNamespaceId()).isNull();
  }

  private static Map<String, List<String>> entityRows(EntityRuntime runtime) {
    var rows = new LinkedHashMap<String, List<String>>();
    for (String table :
        List.of(
            "characters",
            "entity_playable_state_namespace_scopes",
            "entity_preseeded_actor_assignment_operations",
            "entity_canonical_gameplay_roster_snapshots",
            "entity_canonical_gameplay_roster_snapshot_actors",
            "entity_canonical_roster_original_selections",
            "entity_canonical_roster_admissions",
            "entity_canonical_roster_retention_references")) {
      rows.put(
          table,
          runtime
              .dsl()
              .fetch(
                  "SELECT to_jsonb(t)::text || ':' || xmin::text AS value FROM "
                      + table
                      + " t ORDER BY value")
              .getValues(0, String.class));
    }
    return rows;
  }

  private static List<Map<String, Object>> retainedNumericOwnerRow(EntityRuntime runtime) {
    return runtime
        .jdbc()
        .queryForList(
            "SELECT *, xmin::text AS retained_xmin FROM characters WHERE account_id = ?",
            RETAINED_NUMERIC_ACCOUNT_ID);
  }

  private static List<String> characterNames(JsonNode characters) {
    return java.util.stream.StreamSupport.stream(characters.spliterator(), false)
        .map(character -> character.path("characterName").asText())
        .toList();
  }

  private String accountBaseUrl() {
    return "http://localhost:" + accountHttpPort;
  }

  private static String entityServiceTarget() {
    return "localhost:" + requireEntityRuntime().server().getPort();
  }

  private static AccountBootstrapRosterReadClient newRosterClient() {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService(entityServiceTarget());
    return new AccountBootstrapRosterReadClient(
        endpoints, ACCOUNT_CLIENT_TLS, new GrpcChannelFactory(), TEST_NAMESPACE);
  }

  private static synchronized EntityRuntime requireEntityRuntime() {
    if (entityRuntime != null) return entityRuntime;
    Deque<AutoCloseable> startedResources = new ArrayDeque<>();
    startedResources.push(
        () -> {
          if (entityPostgres.isRunning()) entityPostgres.stop();
        });
    try {
      if (!entityPostgres.isRunning()) entityPostgres.start();
      var ds =
          new DriverManagerDataSource(
              entityPostgres.getJdbcUrl(),
              entityPostgres.getUsername(),
              entityPostgres.getPassword());
      String configured = System.getProperty("firemud.entity-management.migration-directory");
      if (configured == null || !Files.isDirectory(Path.of(configured)))
        throw new IllegalStateException("Entity migration directory is unavailable");
      var migrations = Path.of(configured);
      entityMigrationConfiguration(ds, migrations)
          .target(MigrationVersion.fromVersion("1023"))
          .load()
          .migrate();
      var jdbc = new JdbcTemplate(ds);
      jdbc.update(
          "INSERT INTO characters (tenant_id, account_id, name) VALUES (1, ?, 'retained-numeric-owner')",
          RETAINED_NUMERIC_ACCOUNT_ID);
      entityMigrationConfiguration(ds, migrations).load().migrate();
      var manager = new DataSourceTransactionManager(ds);
      DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(ds), SQLDialect.POSTGRES);
      var repository = new CharacterRepository(dsl);
      var reads = new AtomicInteger();
      Server sourceServer = startSourceServer(reads);
      startedResources.push(() -> stopFixtureServer(sourceServer));
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameSessionService("localhost:" + sourceServer.getPort());
      var client =
          new GameSessionCanonicalGameplayRosterOwnerReadClient(
              endpoints,
              TEST_PKI.clientProperties(PKI_DIRECTORY, TEST_PKI.identity("entity-client")),
              new GrpcChannelFactory(),
              TEST_NAMESPACE);
      startedResources.push(client);
      client.init();
      var service =
          new AccountBootstrapRosterReadService(
              new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(client, TEST_NAMESPACE),
              repository,
              manager);
      var meters = new SimpleMeterRegistry();
      startedResources.push(meters::close);
      var identity = TEST_PKI.identity("entity");
      Server server =
          NettyServerBuilder.forPort(0)
              .sslContext(
                  GrpcSslContexts.configure(
                          SslContextBuilder.forServer(
                              identity.privateKey(), identity.certificate()))
                      .trustManager(TEST_PKI.caCertificate())
                      .clientAuth(ClientAuth.REQUIRE)
                      .build())
              .addService(
                  ServerInterceptors.intercept(
                      new AccountBootstrapRosterReadGrpcService(service, TEST_NAMESPACE),
                      new AccountBootstrapRosterPeerInterceptor(TEST_NAMESPACE)))
              .addService(
                  ServerInterceptors.intercept(
                      ordinaryReceiver(repository, meters), new GrpcPeerIdentityInterceptor()))
              .build();
      startedResources.push(() -> stopFixtureServer(server));
      server.start();
      entityRuntime =
          new EntityRuntime(
              repository, jdbc, dsl, manager, server, sourceServer, client, reads, meters);
      return entityRuntime;
    } catch (IOException | CertificateEncodingException | RuntimeException failure) {
      closeFailedStartup(failure, startedResources);
      throw new IllegalStateException(
          "Could not start the real Entity mTLS owner fixture", failure);
    }
  }

  private static void closeFailedStartup(Throwable failure, Deque<AutoCloseable> resources) {
    boolean interrupted = false;
    while (!resources.isEmpty()) {
      try {
        resources.pop().close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
        if (cleanup instanceof InterruptedException) interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static void stopFixtureServer(Server server) throws InterruptedException {
    server.shutdownNow();
    if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Entity fixture gRPC server did not terminate");
    }
  }

  private static Server startSourceServer(AtomicInteger reads) throws IOException {
    var identity = TEST_PKI.identity("gs");
    return NettyServerBuilder.forPort(0)
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                .trustManager(TEST_PKI.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                new CanonicalGameplayRosterOwnerReadServiceGrpc
                    .CanonicalGameplayRosterOwnerReadServiceImplBase() {
                  @Override
                  public void getCanonicalGameplayRosterOwnerRead(
                      GetCanonicalGameplayRosterOwnerReadRequest wire,
                      StreamObserver<GetCanonicalGameplayRosterOwnerReadResponse> observer) {
                    var peer = GrpcPeerIdentity.current();
                    if (peer == null
                        || !peer.isService("entity-management-service")
                        || !peer.isInNamespace(TEST_NAMESPACE)) {
                      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
                      return;
                    }
                    try {
                      var request = CanonicalGameplayRosterOwnerReadGrpcCodec.fromRequest(wire);
                      var evidence = ownerEvidence(request);
                      reads.incrementAndGet();
                      observer.onNext(
                          CanonicalGameplayRosterOwnerReadGrpcCodec.toResponse(request, evidence));
                      observer.onCompleted();
                    } catch (IllegalArgumentException invalid) {
                      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
                    }
                  }
                },
                new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static EntityManagementGrpcService ordinaryReceiver(
      CharacterRepository repository, SimpleMeterRegistry meters) {
    var stateKeys = new PlayableStateKeyResolver();
    var characters =
        new CharacterServiceImpl(
            repository,
            Mappers.getMapper(CharacterMapper.class),
            new ConcurrentMapCacheManager("characterGraph"),
            meters,
            stateKeys,
            new ScopedCharacterResolver(repository, stateKeys));
    return new EntityManagementGrpcService(
        Mockito.mock(net.firedevops.firemud.entitymanagement.service.PingService.class),
        characters,
        Mockito.mock(net.firedevops.firemud.entitymanagement.service.ActorStateService.class),
        Mockito.mock(
            net.firedevops.firemud.entitymanagement.service.ActorConditionMutationService.class),
        Mockito.mock(
            net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService.class),
        Mockito.mock(net.firedevops.firemud.entitymanagement.service.EquipmentService.class),
        Mockito.mock(net.firedevops.firemud.entitymanagement.service.InventoryService.class),
        Mockito.mock(net.firedevops.firemud.entitymanagement.service.ContainerService.class),
        Mockito.mock(net.firedevops.firemud.entitymanagement.service.RoomEntityService.class),
        Mockito.mock(
            net.firedevops.firemud.entitymanagement.service.RuntimeInstanceCleanupService.class),
        Mockito.mock(
            net.firedevops.firemud.entitymanagement.service.impl.EntityMutationEffectReplayService
                .class),
        Mockito.mock(
            net.firedevops.firemud.entitymanagement.service.EntityUpgradeValidationService.class),
        Mockito.mock(
            net.firedevops.firemud.entitymanagement.service.EntityTemplateReferenceService.class),
        Mockito.mock(
            net.firedevops.firemud.common.security.GameplaySessionAttestationService.class),
        meters,
        Mockito.mock(net.firedevops.firemud.entitymanagement.effect.EffectPayloadParser.class),
        TEST_NAMESPACE);
  }

  private ListCharactersByAccountResponse listCharactersDirectlyAsAccount(
      ListCharactersByAccountRequest request) throws Exception {
    var identity = TEST_PKI.identity("account");
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("localhost", requireEntityRuntime().server().getPort())
            .sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(TEST_PKI.caCertificate())
                    .keyManager(
                        identity.privateKey(), identity.certificate(), TEST_PKI.caCertificate())
                    .build())
            .intercept(new GrpcServerPeerIdentityClientInterceptor(TEST_ENTITY_PEER))
            .build();
    try {
      return EntityManagementServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(5, TimeUnit.SECONDS)
          .listCharactersByAccount(request);
    } finally {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static FluentConfiguration entityMigrationConfiguration(
      DriverManagerDataSource ds, Path directory) {
    var config =
        Flyway.configure()
            .dataSource(ds)
            .table("flyway_schema_history_entity_management_service")
            .locations("filesystem:" + directory, "classpath:db/migration/saga")
            .placeholders(Map.of("serviceSchema", "public"));
    config
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    return config;
  }

  private static AccountBootstrapRosterFixture.Fixture createSource() {
    try {
      return AccountBootstrapRosterFixture.create();
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static Path createTemporaryDirectory() {
    try {
      return Files.createTempDirectory("firemud-account-entity-composition-");
    } catch (IOException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static LoopbackMtlsTestPki.Pki createTestPki() {
    try {
      return LoopbackMtlsTestPki.create(
          PKI_DIRECTORY,
          "Account-to-Entity composition proof CA",
          new LoopbackMtlsTestPki.IdentitySpec("entity", TEST_ENTITY_PEER, true),
          new LoopbackMtlsTestPki.IdentitySpec("account", TEST_ACCOUNT_PEER, false),
          new LoopbackMtlsTestPki.IdentitySpec("entity-client", TEST_ENTITY_PEER, false),
          new LoopbackMtlsTestPki.IdentitySpec(
              "gs", "spiffe://firemud/ns/test/sa/game-session-service", true));
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static CommonGrpcClientProperties createAccountClientTls() {
    try {
      return TEST_PKI.clientProperties(PKI_DIRECTORY, TEST_PKI.identity("account"));
    } catch (CertificateEncodingException | IOException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private record TestAccount(String username, String email, long storageId, UUID accountUuid) {}

  private record EntityRuntime(
      CharacterRepository characterRepository,
      JdbcTemplate jdbc,
      DSLContext dsl,
      DataSourceTransactionManager transactionManager,
      Server server,
      Server sourceServer,
      GameSessionCanonicalGameplayRosterOwnerReadClient ownerClient,
      AtomicInteger ownerReads,
      SimpleMeterRegistry meters) {}
}
