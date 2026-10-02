package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    AtomicReference<byte[]> storedValue = new AtomicReference<>();
    AtomicInteger readCount = new AtomicInteger();
    when(stringCommands.get(any(byte[].class)))
        .thenAnswer(
            invocation -> {
              readCount.incrementAndGet();
              byte[] value = storedValue.get();
              return value == null ? null : value.clone();
            });
    when(keyCommands.pTtl(any(byte[].class))).thenReturn(-1L);
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
              storedValue.set(evalShaArguments.get()[3].clone());
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
      assertThat(readCount).hasValue(2);
      verifyNoMoreInteractions(scriptingCommands);
    } finally {
      store.close();
    }
  }

  @Test
  void appliedScriptWithoutStoredValueReturnsNoPositiveSnapshot() throws Exception {
    StoreFixture fixture = storeFixture(PostReadbackMode.MISSING, "APPLIED");
    try {
      var result = fixture.store().apply(sourceReadback("test"), "stable-time");

      assertThat(result.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.QUARANTINED);
      assertThat(result.snapshot()).isEmpty();
      assertThat(result.detail()).contains("POST_SCRIPT_READBACK_MISSING");
      fixture.assertOneScriptCall();
      assertThat(fixture.getCount()).hasValue(2);
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void appliedScriptWithChangedStoredBytesReturnsNoPositiveSnapshot() throws Exception {
    StoreFixture fixture = storeFixture(PostReadbackMode.CHANGED, "APPLIED");
    try {
      var result = fixture.store().apply(sourceReadback("test"), "stable-time");

      assertThat(result.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.QUARANTINED);
      assertThat(result.snapshot()).isEmpty();
      assertThat(result.detail()).contains("POST_SCRIPT_READBACK_MISMATCH");
      fixture.assertOneScriptCall();
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void appliedScriptWithExpiringStoredValueReturnsNoPositiveSnapshot() throws Exception {
    StoreFixture fixture = storeFixture(PostReadbackMode.TTL, "APPLIED");
    try {
      var result = fixture.store().apply(sourceReadback("test"), "stable-time");

      assertThat(result.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.QUARANTINED);
      assertThat(result.snapshot()).isEmpty();
      assertThat(result.detail()).contains("TTL_PRESENT");
      fixture.assertOneScriptCall();
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void postScriptReadbackFailurePropagatesWithoutRetryOrPositiveSnapshot() throws Exception {
    StoreFixture fixture = storeFixture(PostReadbackMode.UNAVAILABLE, "APPLIED");
    try {
      assertThatThrownBy(() -> fixture.store().apply(sourceReadback("test"), "stable-time"))
          .isSameAs(fixture.readbackFailure());

      fixture.assertOneScriptCall();
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void verifyReplayRequiresExactPostScriptReadback() throws Exception {
    StoreFixture fixture = storeFixture(PostReadbackMode.EXACT, "APPLIED", "REPLAY");
    try {
      var applied = fixture.store().apply(sourceReadbackWithEvent("test", false), "stable-time");
      var result = fixture.store().apply(sourceReadbackWithEvent("test", true), "stable-time");

      assertThat(applied.outcome()).isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.APPLIED);
      assertThat(result.outcome()).isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.NO_OP);
      assertThat(result.snapshot())
          .isPresent()
          .get()
          .extracting(ProjectionSnapshot::json)
          .isEqualTo(applied.snapshot().orElseThrow().json());
      fixture.assertScriptCalls(2);
      assertThat(fixture.getCount()).hasValue(4);
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void verifyReplayWithChangedPostScriptReadbackReturnsNoPositiveSnapshot() throws Exception {
    StoreFixture fixture = storeFixture(PostReadbackMode.EXACT, "APPLIED", "REPLAY");
    try {
      fixture.store().apply(sourceReadbackWithEvent("test", false), "stable-time");
      fixture.setPostReadbackMode(PostReadbackMode.CHANGED);
      var result = fixture.store().apply(sourceReadbackWithEvent("test", true), "stable-time");

      assertThat(result.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.QUARANTINED);
      assertThat(result.snapshot()).isEmpty();
      assertThat(result.detail()).contains("POST_SCRIPT_READBACK_MISMATCH");
      fixture.assertScriptCalls(2);
      assertThat(fixture.getCount()).hasValue(4);
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void verifyReplayWithMissingPostScriptReadbackReturnsNoPositiveSnapshot() throws Exception {
    StoreFixture fixture = storeFixture(PostReadbackMode.EXACT, "APPLIED", "REPLAY");
    try {
      fixture.store().apply(sourceReadbackWithEvent("test", false), "stable-time");
      fixture.setPostReadbackMode(PostReadbackMode.MISSING);
      var result = fixture.store().apply(sourceReadbackWithEvent("test", true), "stable-time");

      assertThat(result.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.QUARANTINED);
      assertThat(result.snapshot()).isEmpty();
      assertThat(result.detail()).contains("POST_SCRIPT_READBACK_MISSING");
      fixture.assertScriptCalls(2);
      assertThat(fixture.getCount()).hasValue(4);
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void mutationReplayRequiresExactPostScriptReadback() throws Exception {
    StoreFixture fixture = storeFixture(PostReadbackMode.EXACT, "APPLIED", "REPLAY");
    try {
      fixture.store().apply(sourceReadbackWithEvent("test", false), "stable-time");
      var result =
          fixture
              .store()
              .apply(
                  sourceReadbackWithEvent(
                      "test", true, "33333333-3333-4333-8333-333333333333", "2", "3", "3"),
                  "stable-time");

      assertThat(result.outcome()).isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.REPLAYED);
      assertThat(result.snapshot()).isPresent();
      fixture.assertScriptCalls(2);
      assertThat(fixture.getCount()).hasValue(4);
    } finally {
      fixture.store().close();
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
    return sourceReadback(targetNamespace, null, false);
  }

  private static SourceReadback sourceReadbackWithEvent(String targetNamespace, boolean selectEvent)
      throws Exception {
    return sourceReadbackWithEvent(
        targetNamespace, selectEvent, "22222222-2222-4222-8222-222222222222", "1", "2", "2");
  }

  private static SourceReadback sourceReadbackWithEvent(
      String targetNamespace,
      boolean selectEvent,
      String eventRequestId,
      String sequence,
      String generation,
      String sourceVersion)
      throws Exception {
    var event =
        IssuerGenerationAuthorityEventV1Codec.seal(
            Map.of(
                "schemaVersion",
                IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                "eventType",
                IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
                "eventId",
                "account-issuer-authority-event-v1:" + eventRequestId,
                "requestId",
                eventRequestId,
                "issuerId",
                ISSUER_ID,
                "sourceScope",
                "issuer/" + ISSUER_ID,
                "outboxStreamKey",
                IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + ISSUER_ID,
                "outboxSequence",
                sequence,
                "issuerAuthGeneration",
                generation,
                "sourceVersion",
                sourceVersion));
    return sourceReadback(targetNamespace, event, selectEvent);
  }

  private static SourceReadback sourceReadback(
      String targetNamespace,
      IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent event,
      boolean selectEvent)
      throws Exception {
    String scope = "issuer/" + ISSUER_ID;
    IssuerAuthoritySourceSnapshot snapshot =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(ISSUER_ID)
            .setSourceScope(scope)
            .setOutboxStreamKey(IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope)
            .setIssuerAuthGeneration(event == null ? "1" : event.issuerAuthGeneration())
            .setSourceVersion(event == null ? "1" : event.sourceVersion())
            .setOutboxSequence(event == null ? "0" : event.outboxSequence())
            .build();
    if (event != null) {
      snapshot = snapshot.toBuilder().setLatestEventCanonicalJson(event.canonicalJson()).build();
    }
    ReadIssuerAuthorityForRuntimeResponse.Builder responseBuilder =
        ReadIssuerAuthorityForRuntimeResponse.newBuilder()
            .setSchemaVersion("account-auth-issuer-source-readback/v1")
            .setTargetNamespace(targetNamespace)
            .setRequestId(REQUEST_ID)
            .setSourceSnapshot(snapshot);
    if (selectEvent) {
      responseBuilder.setRequestedEventCanonicalJson(event.canonicalJson());
    }
    ReadIssuerAuthorityForRuntimeResponse response = responseBuilder.build();
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
    return selectEvent
        ? client.readCommittedEvent(REQUEST_ID, event.outboxSequence())
        : client.readCurrent(REQUEST_ID);
  }

  private static StoreFixture storeFixture(PostReadbackMode mode, String... scriptResults)
      throws Exception {
    byte[] resourceBytes = scriptBytes();
    String expectedSha1 = sha1(resourceBytes);
    RedisConnection connection = mock(RedisConnection.class);
    RedisStringCommands stringCommands = mock(RedisStringCommands.class);
    RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
    RedisScriptingCommands scriptingCommands = mock(RedisScriptingCommands.class);
    when(connection.stringCommands()).thenReturn(stringCommands);
    when(connection.keyCommands()).thenReturn(keyCommands);
    when(connection.scriptingCommands()).thenReturn(scriptingCommands);

    AtomicReference<PostReadbackMode> currentMode = new AtomicReference<>(mode);
    AtomicReference<byte[]> storedValue = new AtomicReference<>();
    AtomicInteger getCount = new AtomicInteger();
    AtomicInteger scriptCount = new AtomicInteger();
    DataAccessResourceFailureException readbackFailure =
        new DataAccessResourceFailureException("post-script GET unavailable");
    when(stringCommands.get(any(byte[].class)))
        .thenAnswer(
            invocation -> {
              int read = getCount.incrementAndGet();
              if (read % 2 == 0) {
                switch (currentMode.get()) {
                  case MISSING -> {
                    return null;
                  }
                  case CHANGED -> {
                    return "changed-projection-bytes".getBytes(StandardCharsets.UTF_8);
                  }
                  case UNAVAILABLE -> throw readbackFailure;
                  case EXACT, TTL -> {}
                }
              }
              byte[] value = storedValue.get();
              return value == null ? null : value.clone();
            });
    when(keyCommands.pTtl(any(byte[].class)))
        .thenAnswer(
            invocation ->
                getCount.get() % 2 == 0 && currentMode.get() == PostReadbackMode.TTL ? 0L : -1L);
    when(scriptingCommands.scriptLoad(any(byte[].class))).thenReturn(expectedSha1);
    when(scriptingCommands.evalSha(
            eq(expectedSha1), eq(ReturnType.VALUE), eq(1), any(byte[][].class)))
        .thenAnswer(
            invocation -> {
              int index = scriptCount.getAndIncrement();
              String result = scriptResults[index];
              byte[][] arguments = (byte[][]) invocation.getRawArguments()[3];
              if ("APPLIED".equals(result)
                  || ("REPLAY".equals(result)
                      && !"VERIFY".equals(new String(arguments[1], StandardCharsets.US_ASCII)))) {
                storedValue.set(arguments[3].clone());
              }
              return result.getBytes(StandardCharsets.US_ASCII);
            });

    return new StoreFixture(
        storeWithConnection(connection),
        scriptingCommands,
        getCount,
        scriptCount,
        currentMode,
        readbackFailure,
        expectedSha1);
  }

  private enum PostReadbackMode {
    EXACT,
    MISSING,
    CHANGED,
    TTL,
    UNAVAILABLE
  }

  private record StoreFixture(
      RedisIssuerAuthorityProjectionStore store,
      RedisScriptingCommands scriptingCommands,
      AtomicInteger getCount,
      AtomicInteger scriptCount,
      AtomicReference<PostReadbackMode> mode,
      DataAccessResourceFailureException readbackFailure,
      String scriptSha1) {
    private void setPostReadbackMode(PostReadbackMode newMode) {
      mode.set(newMode);
    }

    private void assertOneScriptCall() {
      assertScriptCalls(1);
    }

    private void assertScriptCalls(int expectedCount) {
      assertThat(scriptCount).hasValue(expectedCount);
      verify(scriptingCommands, times(expectedCount))
          .evalSha(eq(scriptSha1), eq(ReturnType.VALUE), eq(1), any(byte[][].class));
    }
  }
}
