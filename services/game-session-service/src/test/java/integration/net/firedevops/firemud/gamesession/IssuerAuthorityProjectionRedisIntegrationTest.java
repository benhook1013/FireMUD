package integration.net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.account.v1.IssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeRequest;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeResponse;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.redis.contracts.RedisContractRegistry.InvocationRequest;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.SourceReadback;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionRedisContract;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionTransitions;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.ApplyResult;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.Outcome;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.ProjectionSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Redis integration; Account readbacks are minted by the client over its mocked response seam. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class IssuerAuthorityProjectionRedisIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final String QUERY_ID = "11111111-1111-4111-8111-111111111111";
  private static final String EVENT_ID_PREFIX = "account-issuer-authority-event-v1:";
  private static final String COORD_PASSWORD = "issuer-projection-integration-secret";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final RedisScript<String> CAS_SCRIPT =
      RedisScript.of(
          new ClassPathResource(IssuerAuthorityProjectionRedisContract.RESOURCE_PATH),
          String.class);

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--save",
              "",
              "--appendonly",
              "no",
              "--user",
              "gamesession_coord_app",
              "on",
              ">" + COORD_PASSWORD,
              "~session:game:auth:issuer-generation:v1:*",
              "+get",
              "+set",
              "+pttl",
              "+evalsha",
              "+script|load");

  private String issuerId;
  private RedisIssuerAuthorityProjectionStore store;
  private LettuceConnectionFactory adminConnectionFactory;
  private StringRedisTemplate adminTemplate;
  private LettuceConnectionFactory ownerConnectionFactory;
  private StringRedisTemplate ownerTemplate;

  @BeforeEach
  void startOwnerStoreAndTestOnlyAdminReadback() {
    issuerId = "https://accounts.example.test/issuer/" + UUID.randomUUID();
    store =
        new RedisIssuerAuthorityProjectionStore(
            NAMESPACE,
            new RedisIssuerAuthorityProjectionStore.CoordinationEndpoint(
                redis.getHost(),
                redis.getMappedPort(6379),
                "gamesession_coord_app",
                COORD_PASSWORD),
            new RedisIssuerAuthorityProjectionStore.CacheRateLimitEndpoint(
                "redis-cache.test.invalid", 6380));
    store.init();

    adminConnectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
    adminConnectionFactory.afterPropertiesSet();
    adminTemplate = new StringRedisTemplate(adminConnectionFactory);
    adminTemplate.afterPropertiesSet();

    RedisStandaloneConfiguration ownerConfiguration =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
    ownerConfiguration.setUsername("gamesession_coord_app");
    ownerConfiguration.setPassword(RedisPassword.of(COORD_PASSWORD));
    ownerConnectionFactory = new LettuceConnectionFactory(ownerConfiguration);
    ownerConnectionFactory.afterPropertiesSet();
    ownerTemplate = new StringRedisTemplate(ownerConnectionFactory);
    ownerTemplate.afterPropertiesSet();
  }

  @AfterEach
  void closeConnections() {
    if (store != null) {
      store.close();
    }
    if (adminConnectionFactory != null) {
      adminConnectionFactory.destroy();
    }
    if (ownerConnectionFactory != null) {
      ownerConnectionFactory.destroy();
    }
  }

  @Test
  void ownerPrincipalCannotInvokeEvalDirectly() {
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
  void authenticatedCurrentBootstrapAndContiguousEventUseDeterministicPersistentSnapshots()
      throws Exception {
    IssuerGenerationAuthorityEvent first = event("1", "2", "3", 1);
    SourceReadback current = readback("2", "3", "1", first, null);

    ApplyResult bootstrap = store.apply(current, "2026-10-02T11:00:00Z");
    assertThat(bootstrap.outcome()).isEqualTo(Outcome.APPLIED);
    ProjectionSnapshot firstSnapshot = bootstrap.snapshot().orElseThrow();
    assertThat(firstSnapshot.key()).isEqualTo(key());
    JsonNode firstProjection = parse(firstSnapshot.json());
    assertThat(firstProjection.path("lastAppliedIssuerGeneration").asText()).isEqualTo("2");
    assertThat(firstProjection.path("lastAppliedSourceOutboxSequence").asText()).isEqualTo("1");
    assertThat(firstProjection.path("outboxStreamKey").asText()).isEqualTo(first.outboxStreamKey());
    assertThat(firstProjection.path("lastAppliedSourceEventId").asText())
        .isEqualTo(first.eventId());
    assertThat(firstProjection.path("lastAppliedSourceEventDigest").asText())
        .isEqualTo(first.eventDigest());
    assertThat(firstProjection.has("issuerAuthGeneration")).isFalse();
    assertThat(firstProjection.has("sourceOutboxStreamKey")).isFalse();
    assertThat(firstProjection.has("sourceEventId")).isFalse();
    assertThat(firstProjection.has("sourceEventDigest")).isFalse();
    assertThat(adminTemplate.opsForValue().get(key())).isEqualTo(firstSnapshot.json());
    assertThat(adminTemplate.getExpire(key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);

    IssuerGenerationAuthorityEvent second = event("2", "3", "4", 2);
    SourceReadback selected = readback("3", "4", "2", second, second);
    ApplyResult advanced = store.apply(selected, "2026-10-02T11:01:00Z");
    assertThat(advanced.outcome()).isEqualTo(Outcome.APPLIED);
    String deterministicCandidate = advanced.snapshot().orElseThrow().json();
    assertThat(parse(deterministicCandidate).path("lastAppliedSourceOutboxSequence").asText())
        .isEqualTo("2");

    ApplyResult exactDuplicate = store.apply(selected, "ignored-for-no-op");
    assertThat(exactDuplicate.outcome()).isEqualTo(Outcome.NO_OP);
    assertThat(exactDuplicate.snapshot().orElseThrow().json()).isEqualTo(deterministicCandidate);
    assertThat(adminTemplate.opsForValue().get(key())).isEqualTo(deterministicCandidate);
    assertThat(adminTemplate.getExpire(key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
  }

  @Test
  void authenticatedSequenceZeroAbsenceBootstrapsWithoutEventIdentityOrAdmissionEvidence()
      throws Exception {
    ApplyResult bootstrap =
        store.apply(readback("1", "1", "0", null, null), "2026-10-02T10:59:00Z");

    assertThat(bootstrap.outcome()).isEqualTo(Outcome.APPLIED);
    JsonNode projection = parse(bootstrap.snapshot().orElseThrow().json());
    assertThat(projection.path("lastAppliedIssuerGeneration").asText()).isEqualTo("1");
    assertThat(projection.path("lastAppliedSourceOutboxSequence").asText()).isEqualTo("0");
    assertThat(projection.path("outboxStreamKey").asText())
        .isEqualTo(
            IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + issuerId);
    assertThat(projection.has("lastAppliedSourceEventId")).isFalse();
    assertThat(projection.has("lastAppliedSourceEventDigest")).isFalse();
    assertThat(projection.has("issuerAuthGeneration")).isFalse();
    assertThat(projection.has("sourceOutboxStreamKey")).isFalse();
    assertThat(projection.has("sourceEventId")).isFalse();
    assertThat(projection.has("sourceEventDigest")).isFalse();
    assertThat(projection.path("appliedSourceEvidence").isEmpty()).isTrue();
    assertThat(adminTemplate.opsForValue().get(key()))
        .isEqualTo(bootstrap.snapshot().orElseThrow().json());
    assertThat(adminTemplate.getExpire(key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
  }

  @Test
  void gapAndAuthenticatedOlderDuplicateHaveDistinctNoMutationOutcomes() throws Exception {
    IssuerGenerationAuthorityEvent first = event("1", "2", "3", 3);
    IssuerGenerationAuthorityEvent second = event("2", "3", "4", 4);
    assertThat(store.apply(readback("2", "3", "1", first, null), "stable-1").outcome())
        .isEqualTo(Outcome.APPLIED);
    assertThat(store.apply(readback("3", "4", "2", second, second), "stable-2").outcome())
        .isEqualTo(Outcome.APPLIED);
    String before = adminTemplate.opsForValue().get(key());

    IssuerGenerationAuthorityEvent fourth = event("4", "6", "7", 6);
    ApplyResult gap = store.apply(readback("6", "7", "4", fourth, fourth), "ignored-for-gap");
    assertThat(gap.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(gap.detail()).contains("EVENT_SEQUENCE_GAP");
    assertThat(adminTemplate.opsForValue().get(key())).isEqualTo(before);

    ApplyResult olderDuplicate =
        store.apply(readback("3", "4", "2", second, first), "ignored-for-older-duplicate");
    assertThat(olderDuplicate.outcome()).isEqualTo(Outcome.NO_OP);
    assertThat(olderDuplicate.transitionDecision().orElseThrow())
        .isInstanceOf(IssuerAuthorityProjectionTransitions.NoOp.class);
    assertThat(adminTemplate.opsForValue().get(key())).isEqualTo(before);
    assertThat(adminTemplate.getExpire(key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
  }

  @Test
  void staleExpectedBytesAreNonMutatingThroughTheRegisteredOwnerScript() {
    String key = key();
    adminTemplate.opsForValue().set(key, "newer exact bytes");
    String result = executeRegistered(key, "PRESENT", "older exact bytes", "stale candidate bytes");

    assertThat(result).isEqualTo("STALE");
    assertThat(adminTemplate.opsForValue().get(key())).isEqualTo("newer exact bytes");
    assertThat(adminTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isEqualTo(-1L);
  }

  @Test
  void unknownOrDuplicateJsonFieldsQuarantineWithoutChangingBytesOrTtl() throws Exception {
    String key = key();
    String unknownFieldJson = "{\"schemaVersion\":\"unknown\",\"extra\":true}";
    adminTemplate.opsForValue().set(key, unknownFieldJson);
    IssuerGenerationAuthorityEvent event = event("2", "3", "4", 7);

    ApplyResult unknown = store.apply(readback("3", "4", "2", event, event), "stable-time");

    assertThat(unknown.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(adminTemplate.opsForValue().get(key)).isEqualTo(unknownFieldJson);
    assertThat(adminTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isEqualTo(-1L);

    String duplicatePropertyJson =
        "{\"schemaVersion\":\"game-session-auth-issuer-projection/v1\","
            + "\"schemaVersion\":\"game-session-auth-issuer-projection/v1\"}";
    adminTemplate.opsForValue().set(key, duplicatePropertyJson);
    ApplyResult duplicateProperty =
        store.apply(readback("3", "4", "2", event, event), "stable-time");
    assertThat(duplicateProperty.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(adminTemplate.opsForValue().get(key)).isEqualTo(duplicatePropertyJson);
    assertThat(adminTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isEqualTo(-1L);
  }

  @Test
  void obsoletePersistedAliasesQuarantineWithoutChangingBytesOrTtl() throws Exception {
    IssuerGenerationAuthorityEvent first = event("1", "2", "3", 10);
    ApplyResult bootstrap = store.apply(readback("2", "3", "1", first, null), "stable-time");
    String canonicalJson = bootstrap.snapshot().orElseThrow().json();
    String obsoleteJson =
        canonicalJson
            .replace("\"lastAppliedIssuerGeneration\"", "\"issuerAuthGeneration\"")
            .replace("\"outboxStreamKey\"", "\"sourceOutboxStreamKey\"")
            .replace("\"lastAppliedSourceEventId\"", "\"sourceEventId\"")
            .replace("\"lastAppliedSourceEventDigest\"", "\"sourceEventDigest\"");
    adminTemplate.opsForValue().set(key(), obsoleteJson);

    ApplyResult result =
        store.apply(readback("2", "3", "1", first, first), "ignored-for-obsolete-alias");

    assertThat(result.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(result.detail()).contains("MALFORMED_STORED_JSON");
    assertThat(adminTemplate.opsForValue().get(key())).isEqualTo(obsoleteJson);
    assertThat(adminTemplate.getExpire(key(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
  }

  @Test
  void expiringProjectionIsQuarantinedWithoutChangingItsBytesOrExpiration() throws Exception {
    IssuerGenerationAuthorityEvent first = event("1", "2", "3", 8);
    assertThat(store.apply(readback("2", "3", "1", first, null), "stable-time").outcome())
        .isEqualTo(Outcome.APPLIED);
    String before = adminTemplate.opsForValue().get(key());
    adminTemplate.expire(key(), 30, TimeUnit.SECONDS);
    Long beforeTtl = adminTemplate.getExpire(key(), TimeUnit.MILLISECONDS);

    IssuerGenerationAuthorityEvent second = event("2", "3", "4", 9);
    ApplyResult result = store.apply(readback("3", "4", "2", second, second), "stable-time");

    assertThat(result.outcome()).isEqualTo(Outcome.QUARANTINED);
    assertThat(result.detail()).contains("TTL_PRESENT");
    assertThat(adminTemplate.opsForValue().get(key())).isEqualTo(before);
    assertThat(adminTemplate.getExpire(key(), TimeUnit.MILLISECONDS))
        .isPositive()
        .isLessThanOrEqualTo(beforeTtl);
  }

  private SourceReadback readback(
      String generation,
      String sourceVersion,
      String sequence,
      IssuerGenerationAuthorityEvent latest,
      IssuerGenerationAuthorityEvent selected)
      throws Exception {
    String scope = "issuer/" + issuerId;
    String streamKey = IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope;
    IssuerAuthoritySourceSnapshot.Builder snapshot =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(issuerId)
            .setSourceScope(scope)
            .setOutboxStreamKey(streamKey)
            .setIssuerAuthGeneration(generation)
            .setSourceVersion(sourceVersion)
            .setOutboxSequence(sequence);
    if (latest != null) {
      snapshot.setLatestEventCanonicalJson(latest.canonicalJson());
    }
    ReadIssuerAuthorityForRuntimeResponse.Builder response =
        ReadIssuerAuthorityForRuntimeResponse.newBuilder()
            .setSchemaVersion("account-auth-issuer-source-readback/v1")
            .setTargetNamespace(NAMESPACE)
            .setRequestId(QUERY_ID)
            .setSourceSnapshot(snapshot.build());
    if (selected != null) {
      response.setRequestedEventCanonicalJson(selected.canonicalJson());
    }

    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub =
        mock(IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.readIssuerAuthorityForRuntime(any(ReadIssuerAuthorityForRuntimeRequest.class)))
        .thenReturn(response.build());
    AccountIssuerAuthorityClient client = newClient(stub);
    if (selected == null) {
      return client.readCurrent(QUERY_ID);
    }
    return client.readCommittedEvent(QUERY_ID, selected.outboxSequence());
  }

  private String executeRegistered(
      String key, String expectedMode, String expectedBytes, String candidateBytes) {
    var descriptor = IssuerAuthorityProjectionRedisContract.descriptor();
    var request =
        new InvocationRequest(
            descriptor.scriptId(),
            descriptor.resourcePath(),
            descriptor.sha256(),
            descriptor.owner(),
            descriptor.principal(),
            descriptor.role(),
            List.of(key),
            List.of(expectedMode, expectedBytes, candidateBytes));
    var invocation = IssuerAuthorityProjectionRedisContract.registry().prepareInvocation(request);
    assertThat(invocation.descriptor()).isSameAs(descriptor);
    return adminTemplate.execute(
        CAS_SCRIPT, List.of(key), expectedMode, expectedBytes, candidateBytes);
  }

  private String key() {
    return IssuerAuthorityProjectionRedisContract.keyForIssuer(issuerId);
  }

  private static JsonNode parse(String json) throws Exception {
    return JSON.readTree(json);
  }

  private IssuerGenerationAuthorityEvent event(
      String sequence, String generation, String sourceVersion, int identity) {
    String requestId = String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", identity);
    String scope = "issuer/" + issuerId;
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion", IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId", EVENT_ID_PREFIX + requestId,
            "requestId", requestId,
            "issuerId", issuerId,
            "sourceScope", scope,
            "outboxStreamKey", IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope,
            "outboxSequence", sequence,
            "issuerAuthGeneration", generation,
            "sourceVersion", sourceVersion));
  }

  private AccountIssuerAuthorityClient newClient(
      IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub) throws Exception {
    AccountIssuerAuthorityClient client =
        new AccountIssuerAuthorityClient(
            new ServiceEndpointsProperties(),
            mtlsProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            NAMESPACE,
            issuerId);
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/game-session-client.crt");
    tls.setPrivateKey("certs/game-session-client.key");
    tls.setCaCert("certs/account-ca.crt");
    return tls;
  }

  private static String exceptionMessageChain(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current.getMessage() != null) {
        messages.append(current.getMessage()).append('\n');
      }
    }
    return messages.toString();
  }
}
