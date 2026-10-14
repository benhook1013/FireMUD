package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.config.RedisProperties;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.AccountSecurityCutoff;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.AuthorityTuple;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.GameplayConnectSource;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.GatewayContextMetadata;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.GatewaySigningKey;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.GatewayVerificationKeySource;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.TenantBillingCutoff;
import net.firedevops.firemud.gamesession.config.FirstPartyConnectContextProperties;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.impl.RedisFirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.impl.RedisSessionContextService;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical retention proof with controlled keys/owner results, not producer or admission proof. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class FirstPartyConnectContextRedisIntegrationTest {
  private static final long NOW = 1_800_000_000_000L;
  private static final String REGISTRY_KEY = "sessionctx:first-party:91:connect-context";
  private static final UUID ACCOUNT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID TENANT = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID REALM = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID NAMESPACE = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final UUID INSTANCE = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  private LettuceConnectionFactory connectionFactory;
  private RedisTemplate<String, Object> template;
  private KeyPair keys;
  private FirstPartyConnectContextProperties properties;
  private GatewayVerificationKeySource keySource;
  private FirstPartyConnectContextService producerView;
  private FirstPartyConnectContext original;

  @BeforeEach
  void setUp() throws Exception {
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    template =
        new DatabaseAutoConfiguration(new PostgresProperties(), new RedisProperties())
            .redisTemplate(connectionFactory);
    template.afterPropertiesSet();
    template.execute(
        (RedisCallback<Void>)
            connection -> {
              connection.serverCommands().flushDb();
              return null;
            });
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
    properties = new FirstPartyConnectContextProperties();
    // Redis retention remains present after signed expiry so expiry denial needs no wall-clock
    // sleep.
    properties.setTtlMs(120_000L);
    keySource = kid -> "gateway-test".equals(kid) ? (RSAPublicKey) keys.getPublic() : null;
    producerView = reader(keySource, NOW, target(17L));
    original = producerView.parse(token()).orElseThrow();
  }

  @AfterEach
  void tearDown() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  void productionSerializerRetainsExactBytesAndFreshReaderRebuildsVerifiedView() {
    registry(producerView).register(91L, original);
    byte[] storedBytes = rawValue(REGISTRY_KEY);
    assertThat(storedBytes).isNotEmpty();
    FirstPartyConnectContext deserialized =
        (FirstPartyConnectContext) template.opsForValue().get(REGISTRY_KEY);
    assertThat(deserialized).isEqualTo(original).isNotSameAs(original);
    assertThatThrownBy(deserialized::claims).isInstanceOf(IllegalStateException.class);

    FirstPartyConnectContextService freshService = reader(keySource, NOW, target(17L));
    FirstPartyConnectContext rebuilt = registry(freshService).find(91L).orElseThrow();
    assertThat(rebuilt.compactJws()).isEqualTo(original.compactJws());
    assertThat(rebuilt.resolvedTarget()).isEqualTo(original.resolvedTarget());
    assertThat(rebuilt.claims()).isEqualTo(original.claims());
    assertThat(rebuilt.claims().authorityTuple()).isEqualTo(source().authorityTuple());
    assertThat(rebuilt.claims().membershipVersion()).isEqualTo(source().membershipVersion());
    assertThat(rawValue(REGISTRY_KEY)).containsExactly(storedBytes);
  }

  @Test
  void completeSessionStorageSurvivesRegistryExpiryWithoutReconstructingSelectorFragments() {
    RedisSessionContextService sessions = new RedisSessionContextService(template, 120_000L);
    SessionContext shell =
        new SessionContext(
                91L,
                22L,
                null,
                null,
                0L,
                null,
                0L,
                null,
                null,
                "en-NZ",
                41L,
                "demo",
                "production",
                17L,
                "SHARED",
                "scope-1",
                "request-1")
            .withFirstPartyConnectContext(original);
    sessions.save(shell);
    RedisSessionContextService freshSessions = new RedisSessionContextService(template, 120_000L);
    SessionContext retained = freshSessions.findBySessionId(91L).orElseThrow();
    assertThat(retained).isEqualTo(shell).isNotSameAs(shell);
    assertThat(freshSessions.findByTenantAndSessionId(22L, 91L)).contains(retained);
    assertThatThrownBy(() -> retained.firstPartyConnectContext().claims())
        .isInstanceOf(IllegalStateException.class);
    var freshRegistry = registry(reader(keySource, NOW, target(17L)));
    assertThat(freshRegistry.find(91L)).isEmpty();
    var resolution = FirstPartyConnectContextResolution.resolve(91L, retained, freshRegistry);
    assertThat(resolution.invalid()).isFalse();
    assertThat(resolution.connectContext().orElseThrow().claims()).isEqualTo(original.claims());
    assertThat(resolution.connectContext().orElseThrow().compactJws())
        .isEqualTo(original.compactJws());
  }

  @Test
  void malformedPhysicalBytesDenyReadAndReplacementWithoutDeletingEvidenceOrFallingBack() {
    byte[] malformed = new byte[] {1, 2, 3, 4};
    putRaw(REGISTRY_KEY, malformed);
    var registry = registry(reader(keySource, NOW, target(17L)));
    assertThatThrownBy(() -> registry.find(91L)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> registry.register(91L, original))
        .isInstanceOf(IllegalStateException.class);
    var retained =
        new SessionContext(91L, 22L, null, 0L, 0L, (String) null)
            .withFirstPartyConnectContext(original);
    assertThat(FirstPartyConnectContextResolution.resolve(91L, retained, registry).invalid())
        .isTrue();
    assertThat(rawValue(REGISTRY_KEY)).containsExactly(malformed);
  }

  @Test
  void oldIncompleteRetainedShapeDeniesRatherThanBeingReplacedOrReconstructed() {
    template
        .opsForValue()
        .set(
            REGISTRY_KEY,
            Map.of(
                "accountId", ACCOUNT.toString(), "worldSlug", "demo", "connectScopeId", "scope-1"));
    byte[] prior = rawValue(REGISTRY_KEY);
    var registry = registry(reader(keySource, NOW, target(17L)));
    assertThatThrownBy(() -> registry.find(91L)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> registry.register(91L, original))
        .isInstanceOf(IllegalStateException.class);
    assertThat(FirstPartyConnectContextResolution.resolve(91L, null, registry).invalid()).isTrue();
    assertThat(rawValue(REGISTRY_KEY)).containsExactly(prior);
  }

  @Test
  void corruptedRetainedCompactIsNotTrustedBecauseItsObjectShapeLooksComplete() {
    var priorVerified =
        GatewayFirstPartyConnectContextEnvelope.verify(original.compactJws(), keySource, NOW, 0);
    var corrupted = new FirstPartyConnectContext("incomplete.context", priorVerified, target(17L));
    template.opsForValue().set(REGISTRY_KEY, corrupted);
    byte[] prior = rawValue(REGISTRY_KEY);
    var registry = registry(reader(keySource, NOW, target(17L)));
    assertThatThrownBy(() -> registry.find(91L)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> registry.register(91L, original))
        .isInstanceOf(IllegalStateException.class);
    assertThat(FirstPartyConnectContextResolution.resolve(91L, null, registry).invalid()).isTrue();
    assertThat(rawValue(REGISTRY_KEY)).containsExactly(prior);
  }

  @Test
  void unavailableDedicatedKeysChangedCurrentTargetAndSignedExpiryDenyWithoutOverwrite() {
    registry(producerView).register(91L, original);
    byte[] prior = rawValue(REGISTRY_KEY);
    List<FirstPartyConnectContextService> unavailableReaders =
        List.of(
            reader(kid -> null, NOW, target(17L)),
            reader(
                kid -> {
                  throw new GatewayFirstPartyConnectContextEnvelope.KeySourceUnavailableException();
                },
                NOW,
                target(17L)),
            reader(keySource, NOW, target(18L)),
            reader(keySource, NOW + 31_000L, target(17L)));
    for (FirstPartyConnectContextService service : unavailableReaders) {
      var registry = registry(service);
      assertThatThrownBy(() -> registry.find(91L)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> registry.register(91L, original))
          .isInstanceOf(IllegalStateException.class);
      var resolution = FirstPartyConnectContextResolution.resolve(91L, null, registry);
      assertThat(resolution.invalid()).isTrue();
      assertThat(resolution.connectContext()).isEmpty();
      assertThat(rawValue(REGISTRY_KEY)).containsExactly(prior);
    }
  }

  private RedisFirstPartyConnectContextRegistry registry(FirstPartyConnectContextService service) {
    return new RedisFirstPartyConnectContextRegistry(template, properties, service);
  }

  private FirstPartyConnectContextService reader(
      GatewayVerificationKeySource verificationKeys, long now, CanonicalPlayableTarget target) {
    CanonicalPlayerRouteReadService routes = mock(CanonicalPlayerRouteReadService.class);
    when(routes.readCurrentTarget(TENANT)).thenReturn(target);
    return new FirstPartyConnectContextService(
        properties,
        verificationKeys,
        routes,
        Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
  }

  private byte[] rawValue(String key) {
    byte[] serializedKey = serializedKey(key);
    return template.execute(
        (RedisCallback<byte[]>) connection -> connection.stringCommands().get(serializedKey));
  }

  private void putRaw(String key, byte[] bytes) {
    byte[] serializedKey = serializedKey(key);
    template.execute(
        (RedisCallback<Boolean>)
            connection -> connection.stringCommands().set(serializedKey, bytes));
  }

  @SuppressWarnings("unchecked")
  private byte[] serializedKey(String key) {
    return java.util.Objects.requireNonNull(
        ((RedisSerializer<String>) java.util.Objects.requireNonNull(template.getKeySerializer()))
            .serialize(key));
  }

  private GameplayConnectSource source() {
    AuthorityTuple tuple =
        new AuthorityTuple(
            "1",
            "2",
            Map.of(TENANT.toString(), "3"),
            Map.of(TENANT.toString(), "4"),
            List.of(),
            new AccountSecurityCutoff("2", "account:auth-authority:v1:account/" + ACCOUNT, "6"),
            Map.of(
                TENANT.toString(),
                new TenantBillingCutoff(
                    "3", "7", "account:auth-authority:v1:tenant/" + TENANT, "8")));
    return new GameplayConnectSource(
        ACCOUNT,
        TENANT,
        REALM,
        "demo",
        "production",
        NAMESPACE,
        "SHARED",
        INSTANCE,
        "17",
        "3",
        "scope-1",
        "request-1",
        tuple,
        Map.of(TENANT.toString(), "9"),
        "a".repeat(64),
        "5",
        null,
        null,
        NOW / 1000L + 30L);
  }

  private String token() {
    return GatewayFirstPartyConnectContextEnvelope.sign(
        source(),
        new GatewayContextMetadata("203.0.113.8", "gateway-1", NOW, NOW, NOW + 30_000L),
        new GatewaySigningKey("gateway-test", keys.getPrivate()));
  }

  private CanonicalPlayableTarget target(long pointerVersion) {
    return new CanonicalPlayableTarget(
        "firemud-test",
        "tenant",
        TENANT,
        "demo",
        "Demo",
        REALM,
        "production",
        "Production",
        22L,
        41L,
        NAMESPACE,
        "SHARED",
        INSTANCE,
        UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
        7L,
        3L,
        pointerVersion,
        "d".repeat(64),
        1L,
        "initial-request",
        "a".repeat(64),
        net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind
            .NO_PRIOR_POINTER,
        null,
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "sha256:" + "b".repeat(64),
        9L,
        "sha256:" + "c".repeat(64),
        CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        false,
        Instant.ofEpochMilli(NOW - 1000L),
        "PRESEEDED");
  }
}
