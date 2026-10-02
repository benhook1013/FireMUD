package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.account.v1.IssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeRequest;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeResponse;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.SourceReadback;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionRedisContract;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.ProjectionSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisIssuerAuthorityProjectionStoreTest {
  private static final String COORD_PASSWORD = "coordination-secret";
  private static final String ISSUER_ID = "https://accounts.example.test/issuer";
  private static final String REQUEST_ID = "11111111-1111-4111-8111-111111111111";

  @Test
  void requiresExactCoordinationPrincipalAndCredentials() {
    assertThatThrownBy(
            () ->
                new RedisIssuerAuthorityProjectionStore.CoordinationEndpoint(
                    "coordination.internal", 6379, "gamesession_cache_app", COORD_PASSWORD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gamesession_coord_app");
    assertThatThrownBy(
            () ->
                new RedisIssuerAuthorityProjectionStore.CoordinationEndpoint(
                    "coordination.internal", 6379, "gamesession_coord_app", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("credential");
  }

  @Test
  void rejectsNormalizedCoordinationAndCacheEndpointCollision() {
    var coordination =
        new RedisIssuerAuthorityProjectionStore.CoordinationEndpoint(
            "redis-coord.internal.", 6379, "gamesession_coord_app", COORD_PASSWORD);
    var cache =
        new RedisIssuerAuthorityProjectionStore.CacheRateLimitEndpoint(
            "REDIS-COORD.INTERNAL", 6379);

    assertThatThrownBy(() -> new RedisIssuerAuthorityProjectionStore("test", coordination, cache))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoints must be distinct");
  }

  @Test
  void constructorRequiresBothTypedRoleEndpointsWithoutOpeningRedis() {
    var store =
        new RedisIssuerAuthorityProjectionStore(
            "test",
            coordination("coordination.internal", 6379),
            new RedisIssuerAuthorityProjectionStore.CacheRateLimitEndpoint("cache.internal", 6379));

    assertThatThrownBy(() -> store.apply(null, "stable-time"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("explicit init()");
    store.close();
  }

  @Test
  void appliesWithTheExactRegisteredScriptLoadedThenExecutedByEvalSha() throws Exception {
    byte[] resourceBytes = scriptBytes();
    String expectedSha1 = sha1(resourceBytes);
    assertThat(sha256(resourceBytes))
        .isEqualTo(IssuerAuthorityProjectionRedisContract.descriptor().sha256());

    RedisConnection connection = mock(RedisConnection.class);
    RedisStringCommands stringCommands = mock(RedisStringCommands.class);
    RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
    RedisScriptingCommands scriptingCommands = mock(RedisScriptingCommands.class);
    when(connection.stringCommands()).thenReturn(stringCommands);
    when(connection.keyCommands()).thenReturn(keyCommands);
    when(connection.scriptingCommands()).thenReturn(scriptingCommands);
    when(stringCommands.get(any(byte[].class))).thenReturn(null);
    AtomicReference<byte[]> loadedScript = new AtomicReference<>();
    AtomicReference<byte[][]> evalShaArguments = new AtomicReference<>();
    when(scriptingCommands.scriptLoad(any(byte[].class)))
        .thenAnswer(
            invocation -> {
              byte[] script = invocation.getArgument(0);
              loadedScript.set(script.clone());
              return sha1(script);
            });
    when(scriptingCommands.evalSha(
            eq(expectedSha1), eq(ReturnType.VALUE), eq(1), any(byte[][].class)))
        .thenAnswer(
            invocation -> {
              evalShaArguments.set((byte[][]) invocation.getRawArguments()[3]);
              return "APPLIED".getBytes(StandardCharsets.US_ASCII);
            });

    RedisIssuerAuthorityProjectionStore store = storeWithConnection(connection);
    try {
      var result = store.apply(sourceReadback("test"), "stable-time");

      assertThat(result.outcome()).isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.APPLIED);
      ProjectionSnapshot projection = result.snapshot().orElseThrow();
      assertThat(projection.json())
          .contains("\"lastAppliedIssuerGeneration\"")
          .contains("\"outboxStreamKey\"")
          .contains("\"lastAppliedSourceOutboxSequence\"")
          .doesNotContain("\"lastAppliedSourceEventId\"")
          .doesNotContain("\"lastAppliedSourceEventDigest\"")
          .doesNotContain("\"issuerAuthGeneration\"")
          .doesNotContain("\"sourceOutboxStreamKey\"")
          .doesNotContain("\"sourceEventId\"")
          .doesNotContain("\"sourceEventDigest\"");
      InOrder order = inOrder(scriptingCommands);
      order.verify(scriptingCommands).scriptLoad(any(byte[].class));
      order
          .verify(scriptingCommands)
          .evalSha(eq(expectedSha1), eq(ReturnType.VALUE), eq(1), any(byte[][].class));
      assertThat(loadedScript.get()).containsExactly(resourceBytes);
      byte[][] invocationArguments = evalShaArguments.get();
      assertThat(new String(invocationArguments[0], StandardCharsets.UTF_8))
          .isEqualTo(projection.key());
      assertThat(new String(invocationArguments[1], StandardCharsets.US_ASCII)).isEqualTo("ABSENT");
      assertThat(new String(invocationArguments[2], StandardCharsets.UTF_8)).isEmpty();
      assertThat(new String(invocationArguments[3], StandardCharsets.UTF_8))
          .isEqualTo(projection.json());
      verifyNoMoreInteractions(scriptingCommands);
    } finally {
      store.close();
    }
  }

  @Test
  void noscriptFromEvalShaPropagatesWithoutEvalFallback() throws Exception {
    byte[] resourceBytes = scriptBytes();
    String expectedSha1 = sha1(resourceBytes);
    RedisConnection connection = mock(RedisConnection.class);
    RedisStringCommands stringCommands = mock(RedisStringCommands.class);
    RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
    RedisScriptingCommands scriptingCommands = mock(RedisScriptingCommands.class);
    when(connection.stringCommands()).thenReturn(stringCommands);
    when(connection.keyCommands()).thenReturn(keyCommands);
    when(connection.scriptingCommands()).thenReturn(scriptingCommands);
    when(stringCommands.get(any(byte[].class))).thenReturn(null);
    when(scriptingCommands.scriptLoad(any(byte[].class))).thenReturn(expectedSha1);
    DataAccessResourceFailureException noScript =
        new DataAccessResourceFailureException("NOSCRIPT No matching script. Please use EVAL.");
    when(scriptingCommands.evalSha(
            eq(expectedSha1), eq(ReturnType.VALUE), eq(1), any(byte[][].class)))
        .thenThrow(noScript);

    RedisIssuerAuthorityProjectionStore store = storeWithConnection(connection);
    try {
      assertThatThrownBy(() -> store.apply(sourceReadback("test"), "stable-time"))
          .isSameAs(noScript);

      InOrder order = inOrder(scriptingCommands);
      order.verify(scriptingCommands).scriptLoad(any(byte[].class));
      order
          .verify(scriptingCommands)
          .evalSha(eq(expectedSha1), eq(ReturnType.VALUE), eq(1), any(byte[][].class));
      verifyNoMoreInteractions(scriptingCommands);
    } finally {
      store.close();
    }
  }

  @Test
  void wrongSourceNamespaceIsRejectedBeforeAnyRedisCommand() throws Exception {
    RedisIssuerAuthorityProjectionStore store =
        new RedisIssuerAuthorityProjectionStore(
            "other",
            coordination("127.0.0.1", 1),
            new RedisIssuerAuthorityProjectionStore.CacheRateLimitEndpoint("127.0.0.1", 2));
    store.init();

    try {
      var result = store.apply(sourceReadback("test"), "stable-time");

      assertThat(result.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.QUARANTINED);
      assertThat(result.detail()).contains("ACCOUNT_NAMESPACE_MISMATCH");
    } finally {
      store.close();
    }
  }

  @Test
  void coordinationCredentialIsRedactedAndNoAmbientRedisTemplateConstructorExists()
      throws Exception {
    var endpoint = coordination("coordination.internal", 6379);

    assertThat(endpoint.toString()).contains("password=<redacted>").doesNotContain(COORD_PASSWORD);
    boolean acceptsAmbientTemplate =
        Arrays.stream(RedisIssuerAuthorityProjectionStore.class.getConstructors())
            .anyMatch(
                constructor ->
                    Arrays.stream(constructor.getParameterTypes())
                        .anyMatch(RedisTemplate.class::isAssignableFrom));
    assertThat(acceptsAmbientTemplate).isFalse();
  }

  private static RedisIssuerAuthorityProjectionStore.CoordinationEndpoint coordination(
      String host, int port) {
    return new RedisIssuerAuthorityProjectionStore.CoordinationEndpoint(
        host, port, "gamesession_coord_app", COORD_PASSWORD);
  }

  private static RedisIssuerAuthorityProjectionStore storeWithConnection(RedisConnection connection)
      throws Exception {
    RedisIssuerAuthorityProjectionStore store =
        new RedisIssuerAuthorityProjectionStore(
            "test",
            coordination("127.0.0.1", 1),
            new RedisIssuerAuthorityProjectionStore.CacheRateLimitEndpoint("127.0.0.1", 2));
    store.init();
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    doAnswer(
            invocation -> {
              RedisCallback<?> callback = invocation.getArgument(0);
              return callback.doInRedis(connection);
            })
        .when(template)
        .execute(any(RedisCallback.class));
    Field templateField =
        RedisIssuerAuthorityProjectionStore.class.getDeclaredField("redisTemplate");
    templateField.setAccessible(true);
    templateField.set(store, template);
    return store;
  }

  private static String sha1(byte[] script) throws NoSuchAlgorithmException {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script));
  }

  private static String sha256(byte[] script) throws NoSuchAlgorithmException {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script));
  }

  private static byte[] scriptBytes() throws Exception {
    try (var input =
        new ClassPathResource(IssuerAuthorityProjectionRedisContract.RESOURCE_PATH)
            .getInputStream()) {
      return input.readAllBytes();
    }
  }

  private static SourceReadback sourceReadback(String targetNamespace) throws Exception {
    String scope = "issuer/" + ISSUER_ID;
    IssuerAuthoritySourceSnapshot snapshot =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(ISSUER_ID)
            .setSourceScope(scope)
            .setOutboxStreamKey(IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope)
            .setIssuerAuthGeneration("1")
            .setSourceVersion("1")
            .setOutboxSequence("0")
            .build();
    ReadIssuerAuthorityForRuntimeResponse response =
        ReadIssuerAuthorityForRuntimeResponse.newBuilder()
            .setSchemaVersion("account-auth-issuer-source-readback/v1")
            .setTargetNamespace(targetNamespace)
            .setRequestId(REQUEST_ID)
            .setSourceSnapshot(snapshot)
            .build();
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub =
        mock(IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.readIssuerAuthorityForRuntime(any(ReadIssuerAuthorityForRuntimeRequest.class)))
        .thenReturn(response);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/game-session-client.crt");
    tls.setPrivateKey("certs/game-session-client.key");
    tls.setCaCert("certs/account-ca.crt");
    AccountIssuerAuthorityClient client =
        new AccountIssuerAuthorityClient(
            new ServiceEndpointsProperties(),
            tls,
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            "test",
            ISSUER_ID);
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client.readCurrent(REQUEST_ID);
  }
}
