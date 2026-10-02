package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
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
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

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
