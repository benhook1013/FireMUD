package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class RedisDirectTextConnectScopeSessionStoreTest {
  private static final long SESSION_ID = 4401L;
  private static final long ACCOUNT_ID = 7L;
  private static final String REDIS_KEY = "gamesession:lobby:4401";

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;

  @BeforeEach
  void setUpRedis() {
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    redisTemplate.delete(REDIS_KEY);
  }

  @AfterEach
  void tearDownRedis() {
    if (redisTemplate != null) {
      redisTemplate.delete(REDIS_KEY);
    }
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  void sharesLobbySnapshotAndAtomicallyBindsJoinRetryAcrossStoreInstances() throws Exception {
    DirectTextConnectScopeSessionStore firstInstance = newStoreInstance();
    SessionContext caller = session();
    Instant now = Instant.now();
    DirectTextConnectScopeSessionStore.WorldOrdinalTarget target =
        new DirectTextConnectScopeSessionStore.WorldOrdinalTarget(
            1, "demo-world", 22L, 13L, "target-fingerprint-v13");

    firstInstance.replaceWorldSnapshot(
        SESSION_ID, 0L, "catalog-fingerprint-v13", List.of(target), now);
    firstInstance.replaceWorldScopes(
        caller,
        "1",
        22L,
        "demo-world",
        List.of(
            new DirectTextConnectScopeSessionStore.ScopedRealm(
                "production",
                true,
                "account-connect-scope-17",
                now.plusSeconds(600),
                playerContext(caller))));
    firstInstance.replaceWorldScopes(
        caller,
        "2",
        33L,
        "demo-world",
        List.of(
            new DirectTextConnectScopeSessionStore.ScopedRealm(
                "production",
                true,
                "account-connect-scope-33",
                now.plusSeconds(600),
                playerContext(caller, 33L))));
    assertThat(
            replacementJoinScope(newStoreInstance(), caller, "2", 33L, "demo-world", Instant.now()))
        .satisfies(
            selected -> assertThat(selected.scope().playerContext().getTenantId()).isEqualTo("33"));

    List<DirectTextConnectScopeSessionStore> replacementInstances =
        java.util.stream.IntStream.range(0, 8).mapToObj(ignored -> newStoreInstance()).toList();
    assertThat(replacementInstances.getFirst().worldsSnapshot(caller, Instant.now()))
        .hasValueSatisfying(
            snapshot -> {
              assertThat(snapshot.catalogFingerprint()).isEqualTo("catalog-fingerprint-v13");
              assertThat(snapshot.ordinalTargets()).containsExactly(target);
            });

    ExecutorService workers = Executors.newFixedThreadPool(replacementInstances.size());
    CountDownLatch ready = new CountDownLatch(replacementInstances.size());
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<String>> attempts = new ArrayList<>();
      for (DirectTextConnectScopeSessionStore replacement : replacementInstances) {
        attempts.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent JOIN test did not start");
                  }
                  return replacement
                      .publicProductionScopeForJoin(caller, "1", 22L, "demo-world", Instant.now())
                      .orElseThrow()
                      .requestId();
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<String> requestIds = new ArrayList<>();
      for (Future<String> attempt : attempts) {
        requestIds.add(attempt.get(10, TimeUnit.SECONDS));
      }
      assertThat(requestIds).doesNotContainNull();
      assertThat(requestIds).containsOnly(requestIds.getFirst());

      DirectTextConnectScopeSessionStore replacement = replacementInstances.getLast();
      replacement.clearWorldScopes(caller, 22L, "demo-world");
      assertThat(
              firstInstance.publicProductionScopeForJoin(
                  caller, "1", 22L, "demo-world", Instant.now()))
          .isEmpty();

      replacement.replaceWorldScopes(
          caller,
          "1",
          22L,
          "demo-world",
          List.of(
              new DirectTextConnectScopeSessionStore.ScopedRealm(
                  "production",
                  true,
                  "account-connect-scope-18",
                  Instant.now().plusSeconds(600),
                  playerContext(caller))));
      String freshRequestId =
          firstInstance
              .publicProductionScopeForJoin(caller, "1", 22L, "demo-world", Instant.now())
              .orElseThrow()
              .requestId();
      assertThat(freshRequestId).isNotEqualTo(requestIds.getFirst());

      replacement.clearSession(SESSION_ID);
      assertThat(firstInstance.worldsSnapshot(caller, Instant.now())).isEmpty();
      assertThat(
              firstInstance.publicProductionScopeForJoin(
                  caller, "1", 22L, "demo-world", Instant.now()))
          .isEmpty();
    } finally {
      workers.shutdownNow();
    }
  }

  private DirectTextConnectScopeSessionStore newStoreInstance() {
    return new DirectTextConnectScopeSessionStore(redisTemplate, new ObjectMapper());
  }

  private static SessionContext session() {
    return new SessionContext(SESSION_ID, 22L, ACCOUNT_ID, 7001L, 9L, "R-1", "unused-test-jwt");
  }

  private static PlayerExecutionContext playerContext(SessionContext caller) {
    return playerContext(caller, 22L);
  }

  private static PlayerExecutionContext playerContext(SessionContext caller, long tenantId) {
    return PlayerExecutionContext.newBuilder()
        .setAccountId(Long.toString(caller.accountId()))
        .setSessionId(Long.toString(caller.sessionId()))
        .setTenantId(Long.toString(tenantId))
        .setRealmId("4c4b57d8-e3a2-48fe-9977-e7df0fdce901")
        .setPlayableStateNamespaceId("42d234a2-7487-4dda-a7e5-a3831214328e")
        .setPlayableStateScope("SHARED")
        .setGameInstanceId("9")
        .build();
  }

  private static DirectTextConnectScopeSessionStore.JoinScope replacementJoinScope(
      DirectTextConnectScopeSessionStore store,
      SessionContext caller,
      String selector,
      long tenantId,
      String slug,
      Instant now) {
    return store.publicProductionScopeForJoin(caller, selector, tenantId, slug, now).orElseThrow();
  }
}
