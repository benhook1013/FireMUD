package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus;
import net.firedevops.firemud.entitymanagement.mapper.CharacterMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.service.impl.CharacterServiceImpl;
import net.firedevops.firemud.entitymanagement.service.impl.EntityManagementGrpcService;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Composes real Account bootstrap HTTP with its mTLS Entity roster client and real Entity storage.
 * Game Session target reads, test-owned membership rows, and test PKI are controlled fixtures; this
 * proves neither World-owned active lifecycle nor public admission or first OPEN. Entity RPCs not
 * used by ordinary roster reads remain mocked.
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
  private static final long TARGET_TENANT_ID = 1L;
  private static final long TARGET_GAME_INSTANCE_ID = 44L;
  private static final String WORLD_SLUG = "uuid-bootstrap-proof";
  private static final String REALM_SLUG = "production";
  private static final UUID TARGET_REALM_ID =
      UUID.fromString("d8f8de2e-89fd-4432-8cb4-93f241f56af6");
  private static final UUID TARGET_NAMESPACE_ID =
      UUID.fromString("f711c0c9-c1fc-46fb-8ff2-1af7c4d96a67");
  private static final long RETAINED_NUMERIC_ACCOUNT_ID = 98765L;

  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
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

  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  @org.junit.jupiter.api.AfterAll
  static void stopEntityRuntime() throws InterruptedException {
    EntityRuntime runtime = entityRuntime;
    if (runtime != null) {
      runtime.server().shutdownNow();
      assertThat(runtime.server().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      runtime.meters().close();
    }
    if (entityPostgres.isRunning()) {
      entityPostgres.stop();
    }
  }

  @org.junit.jupiter.api.Test
  void bootstrapCharactersUsePersistedUuidAcrossHttpAndRealEntityOwnerReads() throws Exception {
    stubControlledCurrentTarget();

    String suffix = UUID.randomUUID().toString().substring(0, 8);
    TestAccount ownerA = registerAccount("uuid-owner-a-" + suffix);
    TestAccount ownerB = registerAccount("uuid-owner-b-" + suffix);
    assertThat(ownerA.accountUuid()).isNotEqualTo(ownerB.accountUuid());
    seedAdmittingMembership(ownerA.storageId());
    seedAdmittingMembership(ownerB.storageId());
    seedActiveSubscription(ownerA.storageId());

    EntityRuntime entity = requireEntityRuntime();
    String ownerACharacterName = "owned-by-a-" + suffix;
    String ownerBCharacterName = "owned-by-b-" + suffix;
    String wrongTenantCharacterName = "wrong-tenant-a-" + suffix;
    var ownerACharacter =
        saveOrdinaryCharacter(
            entity, TARGET_TENANT_ID, ownerA.accountUuid(), ownerACharacterName, "shared-live");
    var ownerBCharacter =
        saveOrdinaryCharacter(
            entity, TARGET_TENANT_ID, ownerB.accountUuid(), ownerBCharacterName, "shared-live");
    var wrongTenantCharacter =
        saveOrdinaryCharacter(
            entity,
            TARGET_TENANT_ID + 1,
            ownerA.accountUuid(),
            wrongTenantCharacterName,
            "shared-live");

    assertOrdinaryUuidRowIsQuarantined(entity, ownerACharacter.getId(), ownerA.accountUuid());
    assertOrdinaryUuidRowIsQuarantined(entity, ownerBCharacter.getId(), ownerB.accountUuid());
    assertOrdinaryUuidRowIsQuarantined(entity, wrongTenantCharacter.getId(), ownerA.accountUuid());

    var retainedBefore = retainedNumericOwnerRow(entity);
    assertThat(retainedBefore).hasSize(1);

    String ownerAToken = issuePlayerBootstrap(ownerA);
    String ownerBToken = issuePlayerBootstrap(ownerB);
    String ownerAScope = discoverConnectScope(ownerA, ownerAToken);
    String ownerBScope = discoverConnectScope(ownerB, ownerBToken);

    JsonNode ownerACharacters = listBootstrapCharacters(ownerAToken, ownerAScope);
    JsonNode ownerBCharacters = listBootstrapCharacters(ownerBToken, ownerBScope);

    assertThat(characterNames(ownerACharacters)).containsExactly(ownerACharacterName);
    assertThat(characterNames(ownerACharacters))
        .doesNotContain(ownerBCharacterName, wrongTenantCharacterName);
    assertThat(characterNames(ownerBCharacters)).containsExactly(ownerBCharacterName);
    assertThat(characterNames(ownerBCharacters)).doesNotContain(ownerACharacterName);

    var retainedAccountIdRequest =
        ListCharactersByAccountRequest.newBuilder()
            .setTenantId(Long.toString(TARGET_TENANT_ID))
            .setAccountId(Long.toString(RETAINED_NUMERIC_ACCOUNT_ID))
            .setGameInstanceId(Long.toString(TARGET_GAME_INSTANCE_ID))
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .build();
    var retainedOwnerResponse = listCharactersDirectlyAsAccount(retainedAccountIdRequest);
    assertThat(retainedOwnerResponse.hasError()).isTrue();
    assertThat(retainedOwnerResponse.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(retainedOwnerResponse.getCharactersList()).isEmpty();
    assertThat(retainedNumericOwnerRow(entity)).isEqualTo(retainedBefore);
  }

  private TestAccount registerAccount(String username) throws Exception {
    String email = username + "@example.com";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(accountBaseUrl() + "/accounts"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(
                        Map.of("username", username, "email", email, "password", "password123"))))
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

    String responseUuid = jsonMapper.readTree(response.body()).path("data").path("id").asText();
    UUID accountUuid = UUID.fromString(responseUuid);
    Number accountId =
        accountDsl
            .resultQuery("SELECT id FROM accounts WHERE email = ?", email)
            .fetchOne(0, Number.class);
    assertThat(accountId).isNotNull();
    long storageId = accountId.longValue();
    assertThat(
            accountDsl
                .resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", storageId)
                .fetchOne(0, UUID.class))
        .isEqualTo(accountUuid);
    assertThat(
            accountDsl
                .resultQuery(
                    "SELECT account_uuid_source_numeric_id FROM accounts WHERE id = ?", storageId)
                .fetchOne(0, Long.class))
        .isEqualTo(storageId);
    assertThat(
            accountDsl
                .resultQuery("SELECT account_uuid_provenance FROM accounts WHERE id = ?", storageId)
                .fetchOne(0, String.class))
        .isEqualTo("ACCOUNT_REPOSITORY_INSERT");
    return new TestAccount(username, email, storageId, accountUuid);
  }

  private void seedAdmittingMembership(long accountId) {
    // This is test-owned Account state for the existing roster guard, not canonical membership
    // proof.
    accountDsl.execute(
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, ?, TRUE, 'ACTIVE', 1, 1, 'SEEDED_DEMO')",
        accountId,
        TARGET_TENANT_ID);
  }

  private void seedActiveSubscription(long accountId) {
    accountDsl.execute(
        "INSERT INTO subscription "
            + "(account_id, plan_id, status, tenant_id, entitlement_version) "
            + "VALUES (?, 'uuid-bootstrap-composition', 'active', ?, 1)",
        accountId,
        TARGET_TENANT_ID);
  }

  private String issuePlayerBootstrap(TestAccount account) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(accountBaseUrl() + "/auth/player-bootstrap"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    jsonMapper.writeValueAsString(
                        Map.of("accountIdentifier", account.username(), "secret", "password123"))))
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode data = jsonMapper.readTree(response.body()).path("data");
    assertThat(data.path("accountId").asText()).isEqualTo(account.accountUuid().toString());
    String bootstrapToken = data.path("bootstrapToken").asText();
    var claims = jwtUtil.parseToken(bootstrapToken).getPayload();
    assertThat(claims.getSubject()).isEqualTo(account.accountUuid().toString());
    assertThat(claims.get("accountId", String.class)).isEqualTo(account.accountUuid().toString());
    return bootstrapToken;
  }

  private String discoverConnectScope(TestAccount account, String bootstrapToken) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(accountBaseUrl() + "/auth/bootstrap/worlds/" + WORLD_SLUG + "/realms"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bootstrapToken)
            .GET()
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode realms = jsonMapper.readTree(response.body()).path("data");
    assertThat(realms.isArray()).isTrue();
    assertThat(realms.size()).isEqualTo(1);
    JsonNode realm = realms.get(0);
    assertThat(realm.path("tenantId").asLong()).isEqualTo(TARGET_TENANT_ID);
    String scope = realm.path("connectScopeId").asText();
    var claims = jwtUtil.parseToken(scope).getPayload();
    assertThat(claims.getSubject()).isEqualTo(account.accountUuid().toString());
    assertThat(claims.get("accountId", String.class)).isEqualTo(account.accountUuid().toString());
    return scope;
  }

  private JsonNode listBootstrapCharacters(String bootstrapToken, String connectScopeId)
      throws Exception {
    String encodedScope = URLEncoder.encode(connectScopeId, StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    accountBaseUrl()
                        + "/auth/bootstrap/worlds/"
                        + WORLD_SLUG
                        + "/realms/"
                        + REALM_SLUG
                        + "/characters?connectScopeId="
                        + encodedScope))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bootstrapToken)
            .GET()
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return jsonMapper.readTree(response.body()).path("data");
  }

  private ListCharactersByAccountResponse listCharactersDirectlyAsAccount(
      ListCharactersByAccountRequest request) throws Exception {
    EntityRuntime runtime = requireEntityRuntime();
    var identity = TEST_PKI.identity("account");
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("localhost", runtime.server().getPort())
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

  private void stubControlledCurrentTarget() {
    var realm =
        net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
            .setWorldSlug(WORLD_SLUG)
            .setRealmSlug(REALM_SLUG)
            .setDisplayName("Controlled UUID composition target")
            .setTenantId(Long.toString(TARGET_TENANT_ID))
            .setGameInstanceId(Long.toString(TARGET_GAME_INSTANCE_ID))
            .setRealmId(TARGET_REALM_ID.toString())
            .setPlayableStateNamespaceId(TARGET_NAMESPACE_ID.toString())
            .setCatalogRevision(23L)
            .setPointerVersion(17L)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .build();
    var pointer =
        net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
            .setWorldSlug(WORLD_SLUG)
            .setRealmSlug(REALM_SLUG)
            .setTenantId(Long.toString(TARGET_TENANT_ID))
            .setGameInstanceId(Long.toString(TARGET_GAME_INSTANCE_ID))
            .setRealmId(TARGET_REALM_ID.toString())
            .setPlayableStateNamespaceId(TARGET_NAMESPACE_ID.toString())
            .setCatalogRevision(23L)
            .setPointerVersion(17L)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .setCharacterCreationPolicy("ALLOW_NEW")
            .build();
    Mockito.when(gameSessionClient.listGameplayRealms(WORLD_SLUG)).thenReturn(List.of(realm));
    Mockito.when(gameSessionClient.getAdmissionPointer(TARGET_TENANT_ID, WORLD_SLUG, REALM_SLUG))
        .thenReturn(pointer);
  }

  private static net.firedevops.firemud.entitymanagement.entity.Character saveOrdinaryCharacter(
      EntityRuntime runtime,
      long tenantId,
      UUID accountUuid,
      String name,
      String playableStateKey) {
    var character = new net.firedevops.firemud.entitymanagement.entity.Character();
    character.setTenantId(tenantId);
    character.setAccountUuid(accountUuid);
    character.setPlayableStateKey(playableStateKey);
    character.setName(name);
    return runtime.characterRepository().save(character);
  }

  private static void assertOrdinaryUuidRowIsQuarantined(
      EntityRuntime runtime, long characterId, UUID accountUuid) {
    var persisted = runtime.characterRepository().findById(characterId).orElseThrow();
    assertThat(persisted.getAccountUuid()).isEqualTo(accountUuid);
    assertThat(persisted.getAccountId()).isNull();
    assertThat(persisted.getActorIdentity().status()).isEqualTo(ActorIdentityStatus.QUARANTINED);
    assertThat(persisted.getActorIdentity().tenantUuid()).isNull();
    assertThat(persisted.getActorIdentity().playableStateNamespaceId()).isNull();
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

  private static synchronized EntityRuntime requireEntityRuntime() {
    if (entityRuntime != null) {
      return entityRuntime;
    }
    try {
      if (!entityPostgres.isRunning()) {
        entityPostgres.start();
      }
      var dataSource =
          new DriverManagerDataSource(
              entityPostgres.getJdbcUrl(),
              entityPostgres.getUsername(),
              entityPostgres.getPassword());
      Path migrationDirectory =
          Path.of(System.getProperty("firemud.entity-management.migration-directory", ""));
      if (!Files.isDirectory(migrationDirectory)) {
        throw new IllegalStateException(
            "Entity migration directory is unavailable: " + migrationDirectory);
      }
      entityMigrationConfiguration(dataSource, migrationDirectory)
          .target(MigrationVersion.fromVersion("1023"))
          .load()
          .migrate();
      var jdbc = new JdbcTemplate(dataSource);
      jdbc.update(
          "INSERT INTO characters (tenant_id, account_id, name) VALUES (?, ?, ?)",
          TARGET_TENANT_ID,
          RETAINED_NUMERIC_ACCOUNT_ID,
          "retained-numeric-owner");
      entityMigrationConfiguration(dataSource, migrationDirectory).load().migrate();

      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      var repository = new CharacterRepository(dsl);
      var stateKeys = new PlayableStateKeyResolver();
      var meters = new SimpleMeterRegistry();
      var characterService =
          new CharacterServiceImpl(
              repository,
              Mappers.getMapper(CharacterMapper.class),
              new ConcurrentMapCacheManager("characterGraph"),
              meters,
              stateKeys,
              new ScopedCharacterResolver(repository, stateKeys));
      var receiver =
          new EntityManagementGrpcService(
              Mockito.mock(net.firedevops.firemud.entitymanagement.service.PingService.class),
              characterService,
              Mockito.mock(net.firedevops.firemud.entitymanagement.service.ActorStateService.class),
              Mockito.mock(
                  net.firedevops.firemud.entitymanagement.service.ActorConditionMutationService
                      .class),
              Mockito.mock(
                  net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService
                      .class),
              Mockito.mock(net.firedevops.firemud.entitymanagement.service.EquipmentService.class),
              Mockito.mock(net.firedevops.firemud.entitymanagement.service.InventoryService.class),
              Mockito.mock(net.firedevops.firemud.entitymanagement.service.ContainerService.class),
              Mockito.mock(net.firedevops.firemud.entitymanagement.service.RoomEntityService.class),
              Mockito.mock(
                  net.firedevops.firemud.entitymanagement.service.RuntimeInstanceCleanupService
                      .class),
              Mockito.mock(
                  net.firedevops.firemud.entitymanagement.service.impl
                      .EntityMutationEffectReplayService.class),
              Mockito.mock(
                  net.firedevops.firemud.entitymanagement.service.EntityUpgradeValidationService
                      .class),
              Mockito.mock(
                  net.firedevops.firemud.entitymanagement.service.EntityTemplateReferenceService
                      .class),
              Mockito.mock(
                  net.firedevops.firemud.common.security.GameplaySessionAttestationService.class),
              meters,
              Mockito.mock(
                  net.firedevops.firemud.entitymanagement.effect.EffectPayloadParser.class),
              TEST_NAMESPACE);
      var entityIdentity = TEST_PKI.identity("entity");
      Server server =
          NettyServerBuilder.forPort(0)
              .sslContext(
                  GrpcSslContexts.configure(
                          SslContextBuilder.forServer(
                              entityIdentity.privateKey(), entityIdentity.certificate()))
                      .trustManager(TEST_PKI.caCertificate())
                      .clientAuth(ClientAuth.REQUIRE)
                      .build())
              .addService(ServerInterceptors.intercept(receiver, new GrpcPeerIdentityInterceptor()))
              .build()
              .start();
      entityRuntime = new EntityRuntime(repository, jdbc, server, meters);
      return entityRuntime;
    } catch (IOException | RuntimeException failure) {
      if (entityPostgres.isRunning()) {
        entityPostgres.stop();
      }
      throw new IllegalStateException(
          "Could not start the real Entity mTLS owner fixture", failure);
    }
  }

  private static FluentConfiguration entityMigrationConfiguration(
      DriverManagerDataSource dataSource, Path migrationDirectory) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:" + migrationDirectory)
            .placeholders(Map.of("serviceSchema", "public"));
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    return configuration;
  }

  private static Path createTemporaryDirectory() {
    try {
      return Files.createTempDirectory("firemud-account-entity-composition-");
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static LoopbackMtlsTestPki.Pki createTestPki() {
    try {
      return LoopbackMtlsTestPki.create(
          PKI_DIRECTORY,
          "Account-to-Entity composition proof CA",
          new LoopbackMtlsTestPki.IdentitySpec("entity", TEST_ENTITY_PEER, true),
          new LoopbackMtlsTestPki.IdentitySpec("account", TEST_ACCOUNT_PEER, false));
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static CommonGrpcClientProperties createAccountClientTls() {
    try {
      return TEST_PKI.clientProperties(PKI_DIRECTORY, TEST_PKI.identity("account"));
    } catch (CertificateEncodingException | java.io.IOException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private record TestAccount(String username, String email, long storageId, UUID accountUuid) {}

  private record EntityRuntime(
      CharacterRepository characterRepository,
      JdbcTemplate jdbc,
      Server server,
      SimpleMeterRegistry meters) {}
}
