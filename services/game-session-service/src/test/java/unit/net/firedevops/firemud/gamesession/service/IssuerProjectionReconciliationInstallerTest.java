package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.account.v1.IssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeRequest;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeResponse;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.account.authority.IssuerProjectionReconciliationRequestDigestV1;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionRedisContract;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionTransitions;
import net.firedevops.firemud.gamesession.service.IssuerProjectionReconciliationInstaller;
import net.firedevops.firemud.gamesession.service.IssuerProjectionReconciliationInstaller.InstallationReceipt;
import net.firedevops.firemud.gamesession.service.IssuerProjectionReconciliationInstaller.Outcome;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.ApplyResult;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.CacheRateLimitEndpoint;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.CoordinationEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class IssuerProjectionReconciliationInstallerTest {
  private static final String NAMESPACE = "test";
  private static final String ISSUER_ID = "https://accounts.example.test/issuer";
  private static final String REQUEST_ID = "11111111-1111-4111-8111-111111111111";
  private static final String OPERATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String COORD_PASSWORD = "coordination-secret";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void installsZeroAndPositiveCaptureSnapshotsThroughVerifiedAccountClient() throws Exception {
    Fixture zero = fixture(snapshot("1", "1", "0", null));
    try {
      var zeroResult = zero.installer().install(REQUEST_ID, "zero-applied-at");

      assertThat(zeroResult.outcome()).isEqualTo(Outcome.INSTALLED);
      InstallationReceipt zeroReceipt = zeroResult.receipt().orElseThrow();
      assertThat(zeroReceipt.operationId()).hasToString(OPERATION_ID);
      assertThat(zeroReceipt.requestId()).hasToString(REQUEST_ID);
      assertThat(zeroReceipt.capturedSource().outboxSequence()).isEqualTo("0");
      assertThat(zeroReceipt.projectionSnapshot().json())
          .contains("\"lastAppliedSourceOutboxSequence\":\"0\"")
          .contains("\"appliedAt\":\"zero-applied-at\"")
          .doesNotContain("lastAppliedSourceEventId");
      assertThat(zero.redis().scriptCount()).hasValue(1);
      assertThat(zero.redis().getCount()).hasValue(2);
      assertAccountCalls(zero.stub(), 1, 2);
      assertThat(InstallationReceipt.class.getConstructors()).isEmpty();
    } finally {
      zero.store().close();
    }

    IssuerGenerationAuthorityEvent event = event("7", "20", "35", 22);
    Fixture positive = fixture(snapshot("20", "35", "7", event.canonicalJson()));
    try {
      var result = positive.installer().install(REQUEST_ID, "positive-applied-at");

      assertThat(result.outcome()).isEqualTo(Outcome.INSTALLED);
      assertThat(
              result
                  .receipt()
                  .orElseThrow()
                  .capturedSource()
                  .latestEvent()
                  .orElseThrow()
                  .canonicalJson())
          .isEqualTo(event.canonicalJson());
      assertThat(result.receipt().orElseThrow().projectionSnapshot().json())
          .contains("\"lastAppliedSourceOutboxSequence\":\"7\"")
          .contains("\"lastAppliedSourceEventId\":\"" + event.eventId() + "\"")
          .contains(JSON.writeValueAsString(event.canonicalJson()));
      assertThat(positive.redis().scriptCount()).hasValue(1);
      assertThat(positive.redis().getCount()).hasValue(2);
    } finally {
      positive.store().close();
    }
  }

  @Test
  void exactRetryPreservesRedisBytesAndOriginalAppliedAt() throws Exception {
    Fixture fixture = fixture(snapshot("1", "1", "0", null));
    try {
      var first = fixture.installer().install(REQUEST_ID, "original-applied-at");
      byte[] originalBytes = fixture.redis().storedValue().get().clone();

      var retry = fixture.installer().install(REQUEST_ID, "different-retry-time");

      assertThat(first.outcome()).isEqualTo(Outcome.INSTALLED);
      assertThat(retry.outcome()).isEqualTo(Outcome.REPLAYED);
      assertThat(retry.receipt().orElseThrow().operationId())
          .isEqualTo(first.receipt().orElseThrow().operationId());
      assertThat(retry.receipt().orElseThrow().requestId())
          .isEqualTo(first.receipt().orElseThrow().requestId());
      assertThat(retry.receipt().orElseThrow().projectionSnapshot().json())
          .contains("\"appliedAt\":\"original-applied-at\"");
      assertThat(fixture.redis().storedValue().get()).containsExactly(originalBytes);
      assertThat(fixture.redis().scriptCount()).hasValue(2);
      assertThat(fixture.redis().getCount()).hasValue(4);
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void sourceAdvanceBeforeCasPreventsAnyRedisReadOrWrite() throws Exception {
    IssuerGenerationAuthorityEvent advanced = event("1", "2", "2", 23);
    Fixture fixture =
        fixture(
            snapshot("1", "1", "0", null),
            List.of(snapshot("2", "2", "1", advanced.canonicalJson())));
    try {
      var result = fixture.installer().install(REQUEST_ID, "stable-time");

      assertThat(result.outcome()).isEqualTo(Outcome.STALE_SOURCE);
      assertThat(result.receipt()).isEmpty();
      assertThat(fixture.redis().scriptCount()).hasValue(0);
      assertThat(fixture.redis().getCount()).hasValue(0);
      assertAccountCalls(fixture.stub(), 1, 1);
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void sourceAdvanceAfterCasWithholdsReceiptAndDoesNotCompensate() throws Exception {
    IssuerGenerationAuthorityEvent advanced = event("1", "2", "2", 24);
    Fixture fixture =
        fixture(
            snapshot("1", "1", "0", null),
            List.of(
                snapshot("1", "1", "0", null), snapshot("2", "2", "1", advanced.canonicalJson())));
    try {
      var result = fixture.installer().install(REQUEST_ID, "stable-time");

      assertThat(result.outcome()).isEqualTo(Outcome.STALE_SOURCE);
      assertThat(result.receipt()).isEmpty();
      assertThat(fixture.redis().scriptCount()).hasValue(1);
      assertThat(fixture.redis().getCount()).hasValue(2);
      assertThat(fixture.redis().storedValue()).isNotNull();
      assertAccountCalls(fixture.stub(), 1, 2);
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void lostCasResponseRetryUsesSameCaptureIdentityAndStoredBytes() throws Exception {
    Fixture fixture = fixture(snapshot("1", "1", "0", null));
    fixture.redis().loseNextAppliedResponse().set(true);
    try {
      assertThatThrownBy(() -> fixture.installer().install(REQUEST_ID, "first-attempt"))
          .isInstanceOf(DataAccessResourceFailureException.class);
      byte[] committedBytes = fixture.redis().storedValue().get().clone();

      var retry = fixture.installer().install(REQUEST_ID, "retry-at");

      assertThat(retry.outcome()).isEqualTo(Outcome.REPLAYED);
      assertThat(retry.receipt().orElseThrow().operationId()).hasToString(OPERATION_ID);
      assertThat(retry.receipt().orElseThrow().requestId()).hasToString(REQUEST_ID);
      assertThat(fixture.redis().storedValue().get()).containsExactly(committedBytes);
      assertThat(retry.receipt().orElseThrow().projectionSnapshot().json())
          .contains("\"appliedAt\":\"first-attempt\"");
      assertThat(fixture.redis().scriptCount()).hasValue(2);
      assertAccountCalls(fixture.stub(), 2, 3);
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void rejectsCaptureBindingBeforeAnyRedisInvocation() throws Exception {
    Fixture fixture = fixture(snapshot("1", "1", "0", null), List.of(), "wrong-caller");
    try {
      assertThatThrownBy(() -> fixture.installer().install(REQUEST_ID, "stable-time"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("capture");
      assertThat(fixture.redis().scriptCount()).hasValue(0);
      assertThat(fixture.redis().getCount()).hasValue(0);
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void quarantinesPoisonTtlAheadStateAndCasStaleWithoutReceipt() throws Exception {
    Fixture poison =
        fixture(
            snapshot("1", "1", "0", null),
            List.of(),
            "valid",
            "{}".getBytes(StandardCharsets.UTF_8),
            -1L);
    assertDirectStoreQuarantine(poison, "MALFORMED_EXISTING_PROJECTION", 0, 1);

    byte[] ttlProjection = JSON.writeValueAsBytes(projection("2", "2", "1", "prior-applied-at"));
    Fixture ttl = fixture(snapshot("1", "1", "0", null), List.of(), "valid", ttlProjection, 0L);
    assertDirectStoreQuarantine(ttl, "TTL_PRESENT", 0, 1);

    Map<String, Object> aheadProjection = projection("2", "2", "1", "prior-applied-at");
    byte[] aheadBytes = JSON.writeValueAsBytes(aheadProjection);
    Fixture ahead = fixture(snapshot("1", "1", "0", null), List.of(), "valid", aheadBytes, -1L);
    assertDirectStoreQuarantine(ahead, "CAPTURE_PROJECTION_AHEAD", 0, 1);

    Fixture staleCas = fixture(snapshot("1", "1", "0", null));
    staleCas.redis().forceStaleNextScript().set(true);
    try {
      ApplyResult result =
          staleCas
              .store()
              .installCapture(
                  staleCas.client().captureProjection(REQUEST_ID),
                  staleCas.client().readCurrent(REQUEST_ID),
                  "stable-time");
      assertThat(result.outcome()).isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.STALE);
      assertThat(result.snapshot()).isEmpty();
      assertThat(staleCas.redis().scriptCount()).hasValue(1);
      assertThat(staleCas.redis().getCount()).hasValue(1);
    } finally {
      staleCas.store().close();
    }
  }

  @Test
  void storeRechecksCurrentSourceAndRejectsRegressedOrConflictingProjection() throws Exception {
    IssuerGenerationAuthorityEvent advanced = event("1", "2", "2", 27);
    Fixture changedSource =
        fixture(
            snapshot("1", "1", "0", null),
            List.of(snapshot("2", "2", "1", advanced.canonicalJson())));
    try {
      ApplyResult result =
          changedSource
              .store()
              .installCapture(
                  changedSource.client().captureProjection(REQUEST_ID),
                  changedSource.client().readCurrent(REQUEST_ID),
                  "stable-time");
      assertThat(result.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.QUARANTINED);
      assertThat(result.detail()).contains("CAPTURE_CURRENT_SOURCE_MISMATCH");
      assertThat(changedSource.redis().scriptCount()).hasValue(0);
      assertThat(changedSource.redis().getCount()).hasValue(1);
    } finally {
      changedSource.store().close();
    }

    IssuerGenerationAuthorityEvent capturedEvent = event("3", "6", "9", 28);
    byte[] conflictingBytes =
        JSON.writeValueAsBytes(projection("6", "9", "3", "original-applied-at"));
    Fixture conflicting =
        fixture(
            snapshot("6", "9", "3", capturedEvent.canonicalJson()),
            List.of(),
            "valid",
            conflictingBytes,
            -1L);
    assertDirectStoreQuarantine(conflicting, "CAPTURE_SAME_CHECKPOINT_DISAGREEMENT", 0, 1);

    byte[] regressedBytes =
        JSON.writeValueAsBytes(projection("8", "8", "2", "original-applied-at"));
    Fixture regressed =
        fixture(
            snapshot("6", "9", "3", capturedEvent.canonicalJson()),
            List.of(),
            "valid",
            regressedBytes,
            -1L);
    assertDirectStoreQuarantine(regressed, "CAPTURE_SOURCE_REGRESSED", 0, 1);
  }

  @Test
  void storeRequiresItsConfiguredCallerNamespaceBeforeRedisAccess() throws Exception {
    Fixture fixture = fixture(snapshot("1", "1", "0", null));
    RedisIssuerAuthorityProjectionStore wrongNamespaceStore = storeWithNamespace("other");
    Field templateField =
        RedisIssuerAuthorityProjectionStore.class.getDeclaredField("redisTemplate");
    templateField.setAccessible(true);
    templateField.set(wrongNamespaceStore, fixture.redis().template());
    try {
      ApplyResult result =
          wrongNamespaceStore.installCapture(
              fixture.client().captureProjection(REQUEST_ID),
              fixture.client().readCurrent(REQUEST_ID),
              "stable-time");
      assertThat(result.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.QUARANTINED);
      assertThat(result.detail()).contains("ACCOUNT_NAMESPACE_MISMATCH");
      assertThat(fixture.redis().getCount()).hasValue(0);
      assertThat(fixture.redis().scriptCount()).hasValue(0);
    } finally {
      wrongNamespaceStore.close();
      fixture.store().close();
    }
  }

  @Test
  void constructorRequiresBothDependencies() throws Exception {
    Fixture fixture = fixture(snapshot("1", "1", "0", null));
    try {
      AccountIssuerAuthorityClient client = newClient(fixture.stub());
      assertThatThrownBy(() -> new IssuerProjectionReconciliationInstaller(null, fixture.store()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("Account issuer client");
      assertThatThrownBy(() -> new IssuerProjectionReconciliationInstaller(client, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("projection store");
    } finally {
      fixture.store().close();
    }
  }

  private static void assertDirectStoreQuarantine(
      Fixture fixture, String detail, int scripts, int reads) {
    try {
      var result =
          fixture
              .store()
              .installCapture(
                  fixture.client().captureProjection(REQUEST_ID),
                  fixture.client().readCurrent(REQUEST_ID),
                  "stable-time");
      assertThat(result.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.QUARANTINED);
      assertThat(result.detail()).contains(detail);
      assertThat(fixture.redis().scriptCount()).hasValue(scripts);
      assertThat(fixture.redis().getCount()).hasValue(reads);
    } finally {
      fixture.store().close();
    }
  }

  private static Fixture fixture(IssuerAuthoritySourceSnapshot captured) throws Exception {
    return fixture(captured, List.of(captured, captured));
  }

  private static Fixture fixture(
      IssuerAuthoritySourceSnapshot captured, List<IssuerAuthoritySourceSnapshot> readbacks)
      throws Exception {
    return fixture(captured, readbacks, "valid", null, -1L, captured);
  }

  private static Fixture fixture(
      IssuerAuthoritySourceSnapshot captured,
      List<IssuerAuthoritySourceSnapshot> readbacks,
      String captureBinding)
      throws Exception {
    return fixture(captured, readbacks, captureBinding, null, -1L, captured);
  }

  private static Fixture fixture(
      IssuerAuthoritySourceSnapshot captured,
      List<IssuerAuthoritySourceSnapshot> readbacks,
      String captureBinding,
      byte[] initialRedis,
      long ttlMillis)
      throws Exception {
    return fixture(captured, readbacks, captureBinding, initialRedis, ttlMillis, captured);
  }

  private static Fixture fixture(
      IssuerAuthoritySourceSnapshot captured,
      List<IssuerAuthoritySourceSnapshot> readbacks,
      String captureBinding,
      byte[] initialRedis,
      long ttlMillis,
      IssuerAuthoritySourceSnapshot before)
      throws Exception {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub =
        mock(IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    String caller = "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
    String projectedKey = IssuerAuthorityProjectionTransitions.KEY_PREFIX + ISSUER_ID;
    String responseCaller = "wrong-caller".equals(captureBinding) ? "spiffe://wrong" : caller;
    when(stub.captureIssuerProjectionForRuntime(any()))
        .thenReturn(
            CaptureIssuerProjectionForRuntimeResponse.newBuilder()
                .setSchemaVersion("account-auth-issuer-projection-capture/v1")
                .setTargetNamespace(NAMESPACE)
                .setOperationId(OPERATION_ID)
                .setRequestId(REQUEST_ID)
                .setIssuerId(ISSUER_ID)
                .setCallerWorkloadIdentity(responseCaller)
                .setProjectionKey(projectedKey)
                .setRequestDigestVersion(1)
                .setRequestDigest(
                    IssuerProjectionReconciliationRequestDigestV1.digest(
                        ISSUER_ID, caller, projectedKey, java.util.UUID.fromString(REQUEST_ID)))
                .setCapturedSourceSnapshot(captured)
                .build());

    List<IssuerAuthoritySourceSnapshot> replies = new ArrayList<>(readbacks);
    if (replies.isEmpty()) {
      replies.add(before);
    }
    AtomicInteger responseIndex = new AtomicInteger();
    when(stub.readIssuerAuthorityForRuntime(any(ReadIssuerAuthorityForRuntimeRequest.class)))
        .thenAnswer(
            invocation -> {
              int index = responseIndex.getAndIncrement();
              IssuerAuthoritySourceSnapshot reply =
                  replies.get(Math.min(index, replies.size() - 1));
              return readResponse(reply);
            });

    AccountIssuerAuthorityClient client = newClient(stub);
    RedisFixture redis = redisFixture(initialRedis, ttlMillis);
    RedisIssuerAuthorityProjectionStore store = storeWithConnection();
    Field templateField =
        RedisIssuerAuthorityProjectionStore.class.getDeclaredField("redisTemplate");
    templateField.setAccessible(true);
    templateField.set(store, redis.template());
    return new Fixture(
        stub, client, store, redis, new IssuerProjectionReconciliationInstaller(client, store));
  }

  private static AccountIssuerAuthorityClient newClient(
      IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub) throws Exception {
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
            NAMESPACE,
            ISSUER_ID);
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static ReadIssuerAuthorityForRuntimeResponse readResponse(
      IssuerAuthoritySourceSnapshot source) {
    return ReadIssuerAuthorityForRuntimeResponse.newBuilder()
        .setSchemaVersion("account-auth-issuer-source-readback/v1")
        .setTargetNamespace(NAMESPACE)
        .setRequestId(REQUEST_ID)
        .setSourceSnapshot(source)
        .build();
  }

  private static IssuerAuthoritySourceSnapshot snapshot(
      String generation, String sourceVersion, String sequence, String latestEvent) {
    String scope = "issuer/" + ISSUER_ID;
    IssuerAuthoritySourceSnapshot.Builder result =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(ISSUER_ID)
            .setSourceScope(scope)
            .setOutboxStreamKey(IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope)
            .setIssuerAuthGeneration(generation)
            .setSourceVersion(sourceVersion)
            .setOutboxSequence(sequence);
    if (latestEvent != null) {
      result.setLatestEventCanonicalJson(latestEvent);
    }
    return result.build();
  }

  private static IssuerGenerationAuthorityEvent event(
      String sequence, String generation, String sourceVersion, int requestNumber) {
    String requestId =
        String.format(java.util.Locale.ROOT, "00000000-0000-4000-8000-%012d", requestNumber);
    String scope = "issuer/" + ISSUER_ID;
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion", IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId", "account-issuer-authority-event-v1:" + requestId,
            "requestId", requestId,
            "issuerId", ISSUER_ID,
            "sourceScope", scope,
            "outboxStreamKey", IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope,
            "outboxSequence", sequence,
            "issuerAuthGeneration", generation,
            "sourceVersion", sourceVersion));
  }

  private static Map<String, Object> projection(
      String generation, String sourceVersion, String sequence, String appliedAt) {
    IssuerGenerationAuthorityEvent prior = event(sequence, generation, sourceVersion, 26);
    return Map.of(
        "schemaVersion",
        IssuerAuthorityProjectionTransitions.SCHEMA_VERSION,
        "issuerId",
        ISSUER_ID,
        "lastAppliedIssuerGeneration",
        generation,
        "lastAppliedSourceOutboxSequence",
        sequence,
        "outboxStreamKey",
        prior.outboxStreamKey(),
        "lastAppliedSourceEventId",
        prior.eventId(),
        "lastAppliedSourceEventDigest",
        prior.eventDigest(),
        "appliedAt",
        appliedAt,
        "appliedSourceEvidence",
        Map.of(sequence, prior.canonicalJson()));
  }

  private static void assertAccountCalls(
      IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub,
      int captureCalls,
      int readCalls) {
    verify(stub, times(captureCalls)).captureIssuerProjectionForRuntime(any());
    verify(stub, times(readCalls)).readIssuerAuthorityForRuntime(any());
  }

  private static RedisFixture redisFixture(byte[] initial, long ttlMillis) throws Exception {
    byte[] script = scriptBytes();
    String sha1 = sha1(script);
    RedisConnection connection = mock(RedisConnection.class);
    RedisStringCommands stringCommands = mock(RedisStringCommands.class);
    RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
    RedisScriptingCommands scriptingCommands = mock(RedisScriptingCommands.class);
    when(connection.stringCommands()).thenReturn(stringCommands);
    when(connection.keyCommands()).thenReturn(keyCommands);
    when(connection.scriptingCommands()).thenReturn(scriptingCommands);
    AtomicReference<byte[]> storedValue =
        new AtomicReference<>(initial == null ? null : initial.clone());
    AtomicInteger getCount = new AtomicInteger();
    AtomicInteger scriptCount = new AtomicInteger();
    AtomicBoolean loseNextAppliedResponse = new AtomicBoolean();
    AtomicBoolean forceStaleNextScript = new AtomicBoolean();
    when(stringCommands.get(any(byte[].class)))
        .thenAnswer(
            ignored -> {
              getCount.incrementAndGet();
              byte[] value = storedValue.get();
              return value == null ? null : value.clone();
            });
    when(keyCommands.pTtl(any(byte[].class))).thenReturn(ttlMillis);
    when(scriptingCommands.scriptLoad(any(byte[].class))).thenReturn(sha1);
    when(scriptingCommands.evalSha(eq(sha1), eq(ReturnType.VALUE), eq(1), any(byte[][].class)))
        .thenAnswer(
            invocation -> {
              scriptCount.incrementAndGet();
              byte[][] args = (byte[][]) invocation.getRawArguments()[3];
              assertThat(args.length)
                  .as("registered issuer CAS receives one key and three script arguments")
                  .isEqualTo(4);
              assertThat(new String(args[0], StandardCharsets.UTF_8))
                  .as("registered issuer CAS targets the capture's exact Redis key")
                  .isEqualTo(IssuerAuthorityProjectionRedisContract.keyForIssuer(ISSUER_ID));
              String mode = new String(args[1], StandardCharsets.US_ASCII);
              byte[] expected = args[2];
              byte[] candidate = args[3];
              byte[] current = storedValue.get();
              String result;
              if (forceStaleNextScript.getAndSet(false)) {
                result = "STALE";
              } else if (mode.equals("VERIFY")) {
                result = java.util.Arrays.equals(current, expected) ? "REPLAY" : "STALE";
              } else if (current != null && java.util.Arrays.equals(current, candidate)) {
                result = "REPLAY";
              } else if ((mode.equals("ABSENT") && current == null)
                  || (mode.equals("PRESENT") && java.util.Arrays.equals(current, expected))) {
                storedValue.set(candidate.clone());
                result = "APPLIED";
              } else {
                result = "STALE";
              }
              if ("APPLIED".equals(result) && loseNextAppliedResponse.getAndSet(false)) {
                throw new DataAccessResourceFailureException("lost response after Redis commit");
              }
              return result.getBytes(StandardCharsets.US_ASCII);
            });

    StringRedisTemplate template = mock(StringRedisTemplate.class);
    doAnswer(
            invocation -> {
              RedisCallback<?> callback = invocation.getArgument(0);
              return callback.doInRedis(connection);
            })
        .when(template)
        .execute(any(RedisCallback.class));
    return new RedisFixture(
        connection,
        template,
        storedValue,
        getCount,
        scriptCount,
        loseNextAppliedResponse,
        forceStaleNextScript);
  }

  private static RedisIssuerAuthorityProjectionStore storeWithConnection() {
    return storeWithNamespace(NAMESPACE);
  }

  private static RedisIssuerAuthorityProjectionStore storeWithNamespace(String namespace) {
    RedisIssuerAuthorityProjectionStore store =
        new RedisIssuerAuthorityProjectionStore(
            namespace,
            new CoordinationEndpoint("127.0.0.1", 1, "gamesession_coord_app", COORD_PASSWORD),
            new CacheRateLimitEndpoint("127.0.0.1", 2));
    store.init();
    return store;
  }

  private static byte[] scriptBytes() throws Exception {
    try (var input =
        new ClassPathResource(IssuerAuthorityProjectionRedisContract.RESOURCE_PATH)
            .getInputStream()) {
      return input.readAllBytes();
    }
  }

  private static String sha1(byte[] value) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value));
  }

  private record Fixture(
      IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub,
      AccountIssuerAuthorityClient client,
      RedisIssuerAuthorityProjectionStore store,
      RedisFixture redis,
      IssuerProjectionReconciliationInstaller installer) {}

  private record RedisFixture(
      RedisConnection connection,
      StringRedisTemplate template,
      AtomicReference<byte[]> storedValue,
      AtomicInteger getCount,
      AtomicInteger scriptCount,
      AtomicBoolean loseNextAppliedResponse,
      AtomicBoolean forceStaleNextScript) {}
}
