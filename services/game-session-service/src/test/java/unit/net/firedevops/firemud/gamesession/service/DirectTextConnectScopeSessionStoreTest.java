package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;

class DirectTextConnectScopeSessionStoreTest {
  private static final String ACCOUNT_A = "5e1340f8-99c8-49fa-a4fe-5fc9d2075621";
  private static final String ACCOUNT_B = "a49152be-2028-40b9-bc12-e47ccff6f6a2";
  private final DirectTextConnectScopeSessionStore store =
      DirectTextConnectScopeSessionStore.inMemoryForTest();

  @Test
  void retainedNumericOrMalformedAccountEvidenceFailsClosedWithoutMutation() throws Exception {
    for (Object retainedAccountId :
        List.of(
            7L,
            "7",
            "not-a-uuid",
            "00000000-0000-0000-0000-000000000000",
            ACCOUNT_A.toUpperCase(java.util.Locale.ROOT))) {
      String retainedJson =
          new ObjectMapper()
              .writeValueAsString(Map.of("sessionId", 41L, "accountId", retainedAccountId));
      StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
      ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
      Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      Mockito.when(valueOperations.get("gamesession:lobby:41")).thenReturn(retainedJson);
      DirectTextConnectScopeSessionStore persistentStore =
          new DirectTextConnectScopeSessionStore(redisTemplate, new ObjectMapper());
      SessionContext caller = session(41L, ACCOUNT_A);
      Instant now = Instant.now();

      assertThatThrownBy(() -> persistentStore.worldsSnapshot(caller, now))
          .isInstanceOfAny(
              DirectTextConnectScopeSessionStore.ConflictingIdentityException.class,
              DirectTextConnectScopeSessionStore.StoreUnavailableException.class);
      assertThatThrownBy(
              () ->
                  persistentStore.replaceWorldSnapshot(
                      caller.sessionId(), ACCOUNT_A, "fresh-catalog", List.of(), now))
          .isInstanceOfAny(
              DirectTextConnectScopeSessionStore.ConflictingIdentityException.class,
              DirectTextConnectScopeSessionStore.StoreUnavailableException.class);
      Mockito.verify(redisTemplate, Mockito.times(2)).opsForValue();
      Mockito.verify(valueOperations, Mockito.times(2)).get("gamesession:lobby:41");
      Mockito.verifyNoMoreInteractions(redisTemplate, valueOperations);
    }
  }

  @Test
  void resolvesOpaqueScopeOnlyForTheIssuingAccountAndTransportSession() {
    SessionContext caller = session(7L, ACCOUNT_A);
    Instant now = Instant.now();
    Instant expiry = now.plusSeconds(3600);
    store.replaceWorldScopes(
        caller,
        "1",
        22L,
        "demo-world",
        List.of(scopedRealm(caller, "demo-world", "scope-secret", expiry)),
        now);

    assertThat(store.publicProductionScope(caller, 22L, "demo-world", now))
        .hasValueSatisfying(scope -> assertThat(scope.connectScopeId()).isEqualTo("scope-secret"));
    assertThat(store.publicProductionScope(session(8L, ACCOUNT_A), 22L, "demo-world", now))
        .isEmpty();
    assertThat(store.publicProductionScope(session(7L, ACCOUNT_B), 22L, "demo-world", now))
        .isEmpty();
  }

  @Test
  void refusesExpiredAndAmbiguousPublicScopes() {
    SessionContext caller = session(7L, ACCOUNT_A);
    Instant now = Instant.now();
    store.replaceWorldScopes(
        caller,
        "demo-world",
        22L,
        "demo-world",
        List.of(scopedRealm(caller, "production", "expired-scope", now.minusSeconds(1))),
        now);

    assertThat(store.publicProductionScope(caller, 22L, "demo-world", now)).isEmpty();

    store.replaceWorldScopes(
        caller,
        "demo-world",
        22L,
        "demo-world",
        List.of(
            scopedRealm(caller, "production-a", "scope-a", now.plusSeconds(3600)),
            scopedRealm(caller, "production-b", "scope-b", now.plusSeconds(3600))),
        now);

    assertThat(store.publicProductionScope(caller, 22L, "demo-world", now)).isEmpty();
  }

  @Test
  void clearsPriorScopeBeforeReplacingTheWorldBinding() {
    SessionContext caller = session(7L, ACCOUNT_A);
    Instant now = Instant.now();
    store.replaceWorldScopes(
        caller,
        "1",
        22L,
        "demo-world",
        List.of(scopedRealm(caller, "production", "scope-secret", now.plusSeconds(3600))),
        now);

    store.clearWorldScopes(caller, 22L, "demo-world", now);

    assertThat(store.publicProductionScope(caller, 22L, "demo-world", now)).isEmpty();
  }

  @Test
  void reusesJoinRequestIdForScopeRetryAndStartsANewIdAfterFreshRealms() {
    SessionContext caller = session(7L, ACCOUNT_A);
    Instant now = Instant.now();
    Instant expiry = now.plusSeconds(3600);
    store.replaceRealmSnapshot(
        caller,
        "demo-world",
        22L,
        "demo-world",
        "realms-catalog",
        List.of(),
        List.of(scopedRealm(caller, "production", "scope-first", expiry)),
        now);

    DirectTextConnectScopeSessionStore.JoinScope firstAttempt =
        store
            .publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now)
            .orElseThrow();
    DirectTextConnectScopeSessionStore.JoinScope retryAttempt =
        store
            .publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now)
            .orElseThrow();

    assertThat(firstAttempt.requestId()).isEqualTo(retryAttempt.requestId());
    assertThat(firstAttempt.scope().connectScopeId())
        .isEqualTo(retryAttempt.scope().connectScopeId());

    store.clearWorldScopes(caller, 22L, "demo-world", now);
    store.replaceRealmSnapshot(
        caller,
        "demo-world",
        22L,
        "demo-world",
        "realms-catalog",
        List.of(),
        List.of(scopedRealm(caller, "production", "scope-second", expiry)),
        now);
    DirectTextConnectScopeSessionStore.JoinScope newAttempt =
        store
            .publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now)
            .orElseThrow();

    assertThat(newAttempt.scope().connectScopeId()).isEqualTo("scope-second");
    assertThat(newAttempt.requestId()).isNotEqualTo(firstAttempt.requestId());
  }

  @Test
  void clearingTransportLobbyRemovesScopeAndBoundJoinIdentityBeforeReuse() {
    SessionContext caller = session(7L, ACCOUNT_A);
    Instant now = Instant.now();
    store.replaceRealmSnapshot(
        caller,
        "demo-world",
        22L,
        "demo-world",
        "realms-catalog",
        List.of(),
        List.of(scopedRealm(caller, "production", "old-scope", now.plusSeconds(600))),
        now);
    String oldRequestId =
        store
            .publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now)
            .orElseThrow()
            .requestId();

    store.clearSession(caller.sessionId());

    assertThat(store.publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now))
        .isEmpty();
    assertThat(store.publicProductionScope(caller, 22L, "demo-world", now)).isEmpty();
    store.replaceRealmSnapshot(
        caller,
        "demo-world",
        22L,
        "demo-world",
        "realms-catalog",
        List.of(),
        List.of(scopedRealm(caller, "production", "new-scope", now.plusSeconds(600))),
        now);
    DirectTextConnectScopeSessionStore.JoinScope newAttempt =
        store
            .publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now)
            .orElseThrow();
    assertThat(newAttempt.scope().connectScopeId()).isEqualTo("new-scope");
    assertThat(newAttempt.requestId()).isNotEqualTo(oldRequestId);
  }

  @Test
  void storesExactPublicOrdinalSnapshotAndBoundsAccountScopeExpiry() {
    SessionContext caller = session(41L, ACCOUNT_A);
    Instant now = Instant.now();
    DirectTextConnectScopeSessionStore.WorldOrdinalTarget target =
        new DirectTextConnectScopeSessionStore.WorldOrdinalTarget(
            1, "demo-world", 22L, 13L, "target-fingerprint");
    store.replaceWorldSnapshot(
        caller.sessionId(), null, "catalog-fingerprint", List.of(target), now);

    assertThat(store.worldsSnapshot(caller, now))
        .hasValueSatisfying(
            snapshot -> {
              assertThat(snapshot.catalogFingerprint()).isEqualTo("catalog-fingerprint");
              assertThat(snapshot.ordinalTargets()).containsExactly(target);
              assertThat(snapshot.expiresAt()).isBeforeOrEqualTo(now.plusSeconds(301));
            });

    store.replaceRealmSnapshot(
        caller,
        "1",
        22L,
        "demo-world",
        "realms-catalog",
        List.of(),
        List.of(scopedRealm(caller, "production", "scope-secret", now.plusSeconds(86_400))),
        now);
    DirectTextConnectScopeSessionStore.JoinScope selected =
        store.publicProductionScopeForJoin(caller, "1", 22L, "demo-world", now).orElseThrow();

    assertThat(selected.scope().expiresAt()).isBeforeOrEqualTo(now.plusSeconds(901));
    assertThat(selected.scope().playerContext().getAccountId()).isEqualTo(ACCOUNT_A);
    assertThat(selected.scope().playerContext().getSessionId()).isEqualTo("41");
  }

  @Test
  @SuppressWarnings("unchecked")
  void readsUuidLobbyJsonAcrossProductionStoreInstances() throws Exception {
    SessionContext caller = session(41L, ACCOUNT_A);
    Instant now = Instant.now();
    long expiresAt = now.plusSeconds(600).toEpochMilli();
    String worldKey = "22:ZGVtby13b3JsZA";
    PlayerExecutionContext playerContext =
        PlayerExecutionContext.newBuilder()
            .setAccountId(caller.accountId())
            .setSessionId(Long.toString(caller.sessionId()))
            .setTenantId(Long.toString(caller.tenantId()))
            .setRealmId("4c4b57d8-e3a2-48fe-9977-e7df0fdce901")
            .setPlayableStateNamespaceId("42d234a2-7487-4dda-a7e5-a3831214328e")
            .setPlayableStateScope("SHARED")
            .setGameInstanceId("9")
            .build();
    String legacyJson =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "sessionId", caller.sessionId(),
                    "accountId", caller.accountId(),
                    "worldsExpiresAtEpochMs", expiresAt,
                    "catalogFingerprint", "catalog-fingerprint-v13",
                    "ordinalTargets", List.of(),
                    "scopesByWorld",
                        Map.of(
                            worldKey,
                            List.of(
                                Map.of(
                                    "realmSlug",
                                    "production",
                                    "publicProductionRealm",
                                    true,
                                    "connectScopeId",
                                    "account-connect-scope-legacy",
                                    "expiresAtEpochMs",
                                    expiresAt,
                                    "playerContextBase64",
                                    Base64.getEncoder().encodeToString(playerContext.toByteArray()),
                                    "joinRequestId",
                                    "join-request-already-bound"))),
                    "worldBySelector", Map.of("1", worldKey),
                    "realmsByWorld",
                        Map.of(
                            worldKey,
                            Map.of(
                                "tenantId",
                                22L,
                                "worldSlug",
                                "demo-world",
                                "requestedWorldSelector",
                                "1",
                                "catalogFingerprint",
                                "realm-catalog-fingerprint-v13",
                                "expiresAtEpochMs",
                                expiresAt,
                                "ordinalTargets",
                                List.of()))));
    AtomicReference<String> sharedRedisValue = new AtomicReference<>(legacyJson);
    StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
    ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
    Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    Mockito.when(valueOperations.get(Mockito.anyString()))
        .thenAnswer(invocation -> sharedRedisValue.get());
    Mockito.doAnswer(
            invocation -> {
              Object[] arguments =
                  java.util.Arrays.copyOfRange(
                      invocation.getArguments(), 2, invocation.getArguments().length);
              String expectedValue = "1".equals(arguments[0]) ? (String) arguments[1] : null;
              if (!Objects.equals(sharedRedisValue.get(), expectedValue)) {
                return 0L;
              }
              sharedRedisValue.set("1".equals(arguments[2]) ? (String) arguments[3] : null);
              return 1L;
            })
        .when(redisTemplate)
        .execute(Mockito.any(RedisScript.class), Mockito.anyList(), Mockito.any(Object[].class));

    DirectTextConnectScopeSessionStore firstInstance =
        new DirectTextConnectScopeSessionStore(redisTemplate, new ObjectMapper());
    DirectTextConnectScopeSessionStore replacementInstance =
        new DirectTextConnectScopeSessionStore(redisTemplate, new ObjectMapper());

    assertThat(firstInstance.realmsSnapshot(caller, 22L, "demo-world", now))
        .hasValueSatisfying(
            snapshot -> {
              assertThat(snapshot.tenantId()).isEqualTo(22L);
              assertThat(snapshot.requestedWorldSelector()).isEqualTo("1");
            });
    DirectTextConnectScopeSessionStore.JoinScope selected =
        replacementInstance
            .publicProductionScopeForJoin(caller, "1", 22L, "demo-world", now)
            .orElseThrow();
    assertThat(selected.scope().connectScopeId()).isEqualTo("account-connect-scope-legacy");
    assertThat(selected.scope().playerContext().getAccountId()).isEqualTo(ACCOUNT_A);
    assertThat(selected.scope().playerContext().getSessionId()).isEqualTo("41");
    assertThat(selected.scope().playerContext().getTenantId()).isEqualTo("22");
    assertThat(selected.requestId()).isEqualTo("join-request-already-bound");
    assertThat(
            firstInstance
                .publicProductionScopeForJoin(caller, "1", 22L, "demo-world", now)
                .orElseThrow()
                .requestId())
        .isEqualTo("join-request-already-bound");
    assertThat(sharedRedisValue.get()).doesNotContain("worldBySelector");
  }

  @Test
  void sameWorldSlugInDifferentTenantsKeepsOrdinalScopesIsolated() {
    SessionContext caller = session(41L, ACCOUNT_A);
    Instant now = Instant.now();
    store.replaceWorldSnapshot(
        caller.sessionId(),
        null,
        "catalog-fingerprint",
        List.of(
            new DirectTextConnectScopeSessionStore.WorldOrdinalTarget(
                1, "demo", 22L, 3L, "tenant-22-target"),
            new DirectTextConnectScopeSessionStore.WorldOrdinalTarget(
                2, "DEMO", 33L, 4L, "tenant-33-target")),
        now);
    store.replaceRealmSnapshot(
        caller,
        "1",
        22L,
        "demo",
        "tenant-22-catalog",
        List.of(),
        List.of(scopedRealm(caller, 22L, "production", "tenant-22-scope", now.plusSeconds(60))),
        now);
    store.replaceRealmSnapshot(
        caller,
        "2",
        33L,
        "demo",
        "tenant-33-catalog",
        List.of(),
        List.of(scopedRealm(caller, 33L, "production", "tenant-33-scope", now.plusSeconds(60))),
        now);

    assertThat(store.publicProductionScopeForJoin(caller, "1", 22L, "demo", now))
        .hasValueSatisfying(
            selected -> {
              assertThat(selected.scope().connectScopeId()).isEqualTo("tenant-22-scope");
              assertThat(selected.scope().playerContext().getTenantId()).isEqualTo("22");
            });
    assertThat(store.publicProductionScopeForJoin(caller, "2", 33L, "demo", now))
        .hasValueSatisfying(
            selected -> {
              assertThat(selected.scope().connectScopeId()).isEqualTo("tenant-33-scope");
              assertThat(selected.scope().playerContext().getTenantId()).isEqualTo("33");
            });
    assertThat(store.publicProductionScopeForJoin(caller, "2", 22L, "demo", now)).isEmpty();
    assertThat(store.publicProductionScope(caller, "demo", now)).isEmpty();
    assertThat(store.publicProductionScopeForJoin(caller, "demo", now)).isEmpty();
  }

  @Test
  void storesRealmsResponseSnapshotOnlyForTheIssuingAccountAndBeforeExpiry() {
    SessionContext caller = session(41L, ACCOUNT_A);
    Instant now = Instant.now();
    DirectTextConnectScopeSessionStore.RealmOrdinalTarget target =
        new DirectTextConnectScopeSessionStore.RealmOrdinalTarget(
            1, "production", 22L, 29L, 3L, "realm-target-fingerprint");
    store.replaceRealmSnapshot(
        caller, "demo", 22L, "demo", "realm-catalog-fingerprint", List.of(target), List.of(), now);

    assertThat(store.realmsSnapshot(caller, 22L, "demo", now))
        .hasValueSatisfying(
            snapshot -> {
              assertThat(snapshot.worldSlug()).isEqualTo("demo");
              assertThat(snapshot.tenantId()).isEqualTo(22L);
              assertThat(snapshot.requestedWorldSelector()).isEqualTo("demo");
              assertThat(snapshot.catalogFingerprint()).isEqualTo("realm-catalog-fingerprint");
              assertThat(snapshot.ordinalTargets()).containsExactly(target);
              assertThat(snapshot.expiresAt()).isBeforeOrEqualTo(now.plusSeconds(301));
            });
    assertThat(store.realmsSnapshot(session(41L, ACCOUNT_B), 22L, "demo", now)).isEmpty();
    assertThat(store.realmsSnapshot(session(42L, ACCOUNT_A), 22L, "demo", now)).isEmpty();
    assertThat(store.realmsSnapshot(caller, 22L, "demo", now.plusSeconds(301))).isEmpty();
  }

  @Test
  void replacingWorldScopesClearsOnlyThatWorldsRealmsSnapshotAndKeepsJoinScopes() {
    SessionContext caller = session(41L, ACCOUNT_A);
    Instant now = Instant.now();
    store.replaceRealmSnapshot(
        caller, "demo", 22L, "demo", "demo-catalog", List.of(), List.of(), now);
    store.replaceRealmSnapshot(
        caller, "secondary", 22L, "secondary", "secondary-catalog", List.of(), List.of(), now);
    store.replaceRealmSnapshot(
        caller, "demo", 33L, "demo", "other-tenant-catalog", List.of(), List.of(), now);
    store.replaceWorldScopes(
        caller,
        "demo",
        22L,
        "demo",
        List.of(scopedRealm(caller, 22L, "production", "join-scope", now.plusSeconds(60))),
        now);

    assertThat(store.realmsSnapshot(caller, 22L, "demo", now)).isEmpty();
    assertThat(store.realmsSnapshot(caller, 22L, "secondary", now))
        .hasValueSatisfying(
            snapshot -> assertThat(snapshot.catalogFingerprint()).isEqualTo("secondary-catalog"));
    assertThat(store.realmsSnapshot(caller, 33L, "demo", now))
        .hasValueSatisfying(
            snapshot ->
                assertThat(snapshot.catalogFingerprint()).isEqualTo("other-tenant-catalog"));
    assertThat(store.publicProductionScopeForJoin(caller, "demo", 22L, "demo", now)).isEmpty();
    assertThat(store.publicProductionScope(caller, 22L, "demo", now))
        .hasValueSatisfying(scope -> assertThat(scope.connectScopeId()).isEqualTo("join-scope"));
  }

  @Test
  void replacingWorldScopesUsesCallerTimeWhenPruningUnrelatedScopes() {
    SessionContext caller = session(41L, ACCOUNT_A);
    Instant now = Instant.parse("2000-01-01T00:00:00Z");
    Instant scopeExpiry = now.plusSeconds(600);
    store.replaceRealmSnapshot(
        caller,
        "demo",
        22L,
        "demo",
        "demo-catalog",
        List.of(),
        List.of(scopedRealm(caller, 22L, "production", "demo-scope", scopeExpiry)),
        now);
    store.replaceRealmSnapshot(
        caller,
        "secondary",
        22L,
        "secondary",
        "secondary-catalog",
        List.of(),
        List.of(scopedRealm(caller, 22L, "production", "secondary-scope", scopeExpiry)),
        now);

    store.replaceWorldScopes(
        caller,
        "demo",
        "demo",
        List.of(scopedRealm(caller, 22L, "production", "replacement-scope", scopeExpiry)),
        now);

    assertThat(store.publicProductionScope(caller, 22L, "demo", now))
        .hasValueSatisfying(
            scope -> assertThat(scope.connectScopeId()).isEqualTo("replacement-scope"));
    assertThat(store.realmsSnapshot(caller, 22L, "secondary", now))
        .hasValueSatisfying(
            snapshot -> assertThat(snapshot.catalogFingerprint()).isEqualTo("secondary-catalog"));
    assertThat(store.publicProductionScope(caller, 22L, "secondary", now))
        .hasValueSatisfying(
            scope -> assertThat(scope.connectScopeId()).isEqualTo("secondary-scope"));
  }

  @Test
  void clearingWorldScopesUsesCallerTimeWhenPruningUnrelatedScopes() {
    SessionContext caller = session(41L, ACCOUNT_A);
    Instant now = Instant.parse("2000-01-01T00:00:00Z");
    Instant scopeExpiry = now.plusSeconds(600);
    store.replaceRealmSnapshot(
        caller,
        "demo",
        22L,
        "demo",
        "demo-catalog",
        List.of(),
        List.of(scopedRealm(caller, 22L, "production", "demo-scope", scopeExpiry)),
        now);
    store.replaceRealmSnapshot(
        caller,
        "secondary",
        22L,
        "secondary",
        "secondary-catalog",
        List.of(),
        List.of(scopedRealm(caller, 22L, "production", "secondary-scope", scopeExpiry)),
        now);

    store.clearWorldScopes(caller, "demo", now);

    assertThat(store.realmsSnapshot(caller, 22L, "secondary", now))
        .hasValueSatisfying(
            snapshot -> assertThat(snapshot.catalogFingerprint()).isEqualTo("secondary-catalog"));
    assertThat(store.publicProductionScope(caller, 22L, "secondary", now))
        .hasValueSatisfying(
            scope -> assertThat(scope.connectScopeId()).isEqualTo("secondary-scope"));
  }

  @Test
  void worldScopeMutationsRejectNullCallerTime() {
    SessionContext caller = session(41L, ACCOUNT_A);

    assertThatThrownBy(() -> store.replaceWorldScopes(caller, "demo", 22L, "demo", List.of(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("now must not be null");
    assertThatThrownBy(() -> store.clearWorldScopes(caller, 22L, "demo", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("now must not be null");
  }

  @Test
  void replacingWorldScopesForAnotherAccountClearsPriorLobbyAuthority() {
    SessionContext previousCaller = session(41L, ACCOUNT_A);
    SessionContext currentCaller = session(41L, ACCOUNT_B);
    Instant now = Instant.now();
    Instant expiresAt = now.plusSeconds(60);
    store.replaceRealmSnapshot(
        previousCaller,
        "demo",
        22L,
        "demo",
        "previous-demo-catalog",
        List.of(),
        List.of(scopedRealm(previousCaller, 22L, "production", "previous-demo-scope", expiresAt)),
        now);
    store.replaceRealmSnapshot(
        previousCaller,
        "secondary",
        22L,
        "secondary",
        "previous-secondary-catalog",
        List.of(),
        List.of(
            scopedRealm(previousCaller, 22L, "production", "previous-secondary-scope", expiresAt)),
        now);

    store.replaceWorldScopes(
        currentCaller,
        "demo",
        22L,
        "demo",
        List.of(scopedRealm(currentCaller, 22L, "production", "current-demo-scope", expiresAt)),
        now);

    assertThat(store.realmsSnapshot(currentCaller, 22L, "demo", now)).isEmpty();
    assertThat(store.realmsSnapshot(currentCaller, 22L, "secondary", now)).isEmpty();
    assertThat(store.publicProductionScope(currentCaller, 22L, "secondary", now)).isEmpty();
    assertThat(store.publicProductionScope(currentCaller, 22L, "demo", now))
        .hasValueSatisfying(
            scope -> assertThat(scope.connectScopeId()).isEqualTo("current-demo-scope"));
  }

  @Test
  void rejectsInvalidTransportIdsAndScopesBoundToAnotherIdentity() {
    assertThatThrownBy(() -> store.clearSession(0L)).isInstanceOf(IllegalArgumentException.class);

    SessionContext caller = session(7L, ACCOUNT_A);
    Instant now = Instant.now();
    DirectTextConnectScopeSessionStore.ScopedRealm mismatchedScope =
        scopedRealm(session(8L, ACCOUNT_A), "production", "scope-secret", now.plusSeconds(60));
    assertThatThrownBy(
            () -> store.replaceWorldScopes(caller, "demo", "demo", List.of(mismatchedScope), now))
        .isInstanceOf(DirectTextConnectScopeSessionStore.ConflictingIdentityException.class);
  }

  private static SessionContext session(long sessionId, String accountId) {
    return new SessionContext(sessionId, 22L, accountId, 7001L, 9L, "jwt");
  }

  private static DirectTextConnectScopeSessionStore.ScopedRealm scopedRealm(
      SessionContext caller, String realmSlug, String scopeId, Instant expiry) {
    return scopedRealm(caller, 22L, realmSlug, scopeId, expiry);
  }

  private static DirectTextConnectScopeSessionStore.ScopedRealm scopedRealm(
      SessionContext caller, long tenantId, String realmSlug, String scopeId, Instant expiry) {
    PlayerExecutionContext playerContext =
        PlayerExecutionContext.newBuilder()
            .setAccountId(caller.accountId())
            .setSessionId(Long.toString(caller.sessionId()))
            .setTenantId(Long.toString(tenantId))
            .setRealmId("4c4b57d8-e3a2-48fe-9977-e7df0fdce901")
            .setPlayableStateNamespaceId("42d234a2-7487-4dda-a7e5-a3831214328e")
            .setPlayableStateScope("SHARED")
            .setGameInstanceId("9")
            .build();
    return new DirectTextConnectScopeSessionStore.ScopedRealm(
        realmSlug, true, scopeId, expiry, playerContext);
  }
}
