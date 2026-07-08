package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SessionServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private ValueOperations<String, Object> valueOperations;
  private SessionServiceImpl service;

  @BeforeEach
  void setUp() {
    redisTemplate = mock(RedisTemplate.class);
    valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    service =
        new SessionServiceImpl(
            redisTemplate,
            new net.firedevops.firemud.accountservice.config.AccountTokenProperties());
  }

  @Test
  void storeSessionWritesHashedAccountAndTenantAllowlistKeys() {
    service.storeSession(7L, 11L, "token-123", 5000L);

    String tokenHash = sha256("token-123");
    verify(valueOperations)
        .set("session:auth:account:11:" + tokenHash, 11L, Duration.ofMillis(5000L));
    verify(valueOperations)
        .set("session:auth:tenant:7:" + tokenHash, 11L, Duration.ofMillis(5000L));
  }

  @Test
  void getAccountIdReadsTenantScopedHashedKeyFirst() {
    when(valueOperations.get("session:auth:tenant:7:" + sha256("token-123"))).thenReturn("11");

    Long accountId = service.getAccountId(7L, "token-123");

    assertThat(accountId).isEqualTo(11L);
  }

  @Test
  void getAccountIdUsesHashedTenantScopedKeyWithoutExposingRawToken() {
    String hashedKey = "session:auth:tenant:7:" + sha256("token-123");
    when(valueOperations.get(hashedKey)).thenReturn("11");

    Long accountId = service.getAccountId(7L, "token-123");

    assertThat(accountId).isEqualTo(11L);
    verify(valueOperations).get(hashedKey);
    assertThat(hashedKey).doesNotContain("token-123");
  }

  @Test
  void getAccountIdReturnsNullForMalformedStoredAccountId() {
    when(valueOperations.get("session:auth:tenant:7:" + sha256("token-123")))
        .thenReturn("not-a-number");

    Long accountId = service.getAccountId(7L, "token-123");

    assertThat(accountId).isNull();
  }

  @Test
  void getAccountIdReturnsNullForNonPositiveStoredAccountId() {
    when(valueOperations.get("session:auth:tenant:7:" + sha256("token-123"))).thenReturn("0");

    Long accountId = service.getAccountId(7L, "token-123");

    assertThat(accountId).isNull();
  }

  @Test
  void storeConnectTokenReplayWritesHashedScopeAndRequestKey() {
    SessionService.ConnectTokenReplay replay =
        new SessionService.ConnectTokenReplay(
            true,
            new ConnectTokenResult(
                11L,
                7L,
                44L,
                "production",
                "scope-1",
                "connect-1",
                "jti-1",
                "req-7",
                "2026-05-25T00:00:00Z",
                "2026-05-25T00:00:30Z",
                false),
            "",
            "");

    service.storeConnectTokenReplay(7L, 11L, "scope-1", "req-7", replay, 30000L);

    verify(valueOperations)
        .set(
            "session:connect-token:tenant:7:account:11:scope:"
                + sha256("scope-1")
                + ":request:"
                + sha256("req-7"),
            Map.ofEntries(
                Map.entry("success", "true"),
                Map.entry("accountId", "11"),
                Map.entry("tenantId", "7"),
                Map.entry("gameInstanceId", "44"),
                Map.entry("realmSlug", "production"),
                Map.entry("connectScopeId", "scope-1"),
                Map.entry("connectToken", "connect-1"),
                Map.entry("jti", "jti-1"),
                Map.entry("requestId", "req-7"),
                Map.entry("issuedAt", "2026-05-25T00:00:00Z"),
                Map.entry("expiresAt", "2026-05-25T00:00:30Z")),
            Duration.ofMillis(30000L));
  }

  @Test
  void getConnectTokenReplayReturnsFailureReplay() {
    String key =
        "session:connect-token:tenant:7:account:11:scope:"
            + sha256("scope-1")
            + ":request:"
            + sha256("req-7");
    when(valueOperations.get(key))
        .thenReturn(
            Map.of(
                "success", "false",
                "errorCode", "CONNECT_SCOPE_MISMATCH",
                "errorMessage", "Selected gameplay target is no longer admissible"));

    var replay = service.getConnectTokenReplay(7L, 11L, "scope-1", "req-7");

    assertThat(replay).isPresent();
    assertThat(replay.orElseThrow().success()).isFalse();
    assertThat(replay.orElseThrow().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
  }

  @Test
  void getConnectTokenReplayReturnsEmptyForMismatchedIdentity() {
    String key =
        "session:connect-token:tenant:7:account:11:scope:"
            + sha256("scope-1")
            + ":request:"
            + sha256("req-7");
    when(valueOperations.get(key))
        .thenReturn(
            Map.ofEntries(
                Map.entry("success", "true"),
                Map.entry("accountId", "999"),
                Map.entry("tenantId", "7"),
                Map.entry("gameInstanceId", "44"),
                Map.entry("realmSlug", "production"),
                Map.entry("connectScopeId", "scope-1"),
                Map.entry("connectToken", "connect-1"),
                Map.entry("jti", "jti-1"),
                Map.entry("requestId", "req-7"),
                Map.entry("issuedAt", "2026-05-25T00:00:00Z"),
                Map.entry("expiresAt", "2026-05-25T00:00:30Z")));

    var replay = service.getConnectTokenReplay(7L, 11L, "scope-1", "req-7");

    assertThat(replay).isEmpty();
  }

  @Test
  void getConnectTokenReplayReturnsEmptyForMalformedNumericFields() {
    String key =
        "session:connect-token:tenant:7:account:11:scope:"
            + sha256("scope-1")
            + ":request:"
            + sha256("req-7");
    when(valueOperations.get(key))
        .thenReturn(
            Map.of(
                "success",
                "true",
                "accountId",
                "not-a-number",
                "tenantId",
                "7",
                "gameInstanceId",
                "44"));

    var replay = service.getConnectTokenReplay(7L, 11L, "scope-1", "req-7");

    assertThat(replay).isEmpty();
  }

  @Test
  void getConnectTokenReplayReturnsEmptyForNonPositiveNumericFields() {
    String key =
        "session:connect-token:tenant:7:account:11:scope:"
            + sha256("scope-1")
            + ":request:"
            + sha256("req-7");
    when(valueOperations.get(key))
        .thenReturn(
            Map.of("success", "true", "accountId", "0", "tenantId", "7", "gameInstanceId", "44"));

    var replay = service.getConnectTokenReplay(7L, 11L, "scope-1", "req-7");

    assertThat(replay).isEmpty();
  }

  @Test
  void getConnectTokenReplayReturnsEmptyForMalformedSuccessFlag() {
    String key =
        "session:connect-token:tenant:7:account:11:scope:"
            + sha256("scope-1")
            + ":request:"
            + sha256("req-7");
    when(valueOperations.get(key))
        .thenReturn(
            Map.of(
                "success",
                "definitely",
                "errorCode",
                "CONNECT_SCOPE_MISMATCH",
                "errorMessage",
                "Selected gameplay target is no longer admissible"));

    var replay = service.getConnectTokenReplay(7L, 11L, "scope-1", "req-7");

    assertThat(replay).isEmpty();
  }

  @Test
  void getConnectTokenReplayReturnsEmptyForBlankRequiredTextField() {
    String key =
        "session:connect-token:tenant:7:account:11:scope:"
            + sha256("scope-1")
            + ":request:"
            + sha256("req-7");
    when(valueOperations.get(key))
        .thenReturn(
            Map.ofEntries(
                Map.entry("success", "true"),
                Map.entry("accountId", "11"),
                Map.entry("tenantId", "7"),
                Map.entry("gameInstanceId", "44"),
                Map.entry("realmSlug", "production"),
                Map.entry("connectScopeId", "scope-1"),
                Map.entry("connectToken", "   "),
                Map.entry("jti", "jti-1"),
                Map.entry("requestId", "req-7"),
                Map.entry("issuedAt", "2026-05-25T00:00:00Z"),
                Map.entry("expiresAt", "2026-05-25T00:00:30Z")));

    var replay = service.getConnectTokenReplay(7L, 11L, "scope-1", "req-7");

    assertThat(replay).isEmpty();
  }

  @Test
  void getConnectTokenReplayReturnsEmptyForWhitespacePaddedIdentityField() {
    String key =
        "session:connect-token:tenant:7:account:11:scope:"
            + sha256("scope-1")
            + ":request:"
            + sha256("req-7");
    when(valueOperations.get(key))
        .thenReturn(
            Map.ofEntries(
                Map.entry("success", "true"),
                Map.entry("accountId", "11"),
                Map.entry("tenantId", "7"),
                Map.entry("gameInstanceId", "44"),
                Map.entry("realmSlug", "production"),
                Map.entry("connectScopeId", " scope-1 "),
                Map.entry("connectToken", "connect-1"),
                Map.entry("jti", "jti-1"),
                Map.entry("requestId", "req-7"),
                Map.entry("issuedAt", "2026-05-25T00:00:00Z"),
                Map.entry("expiresAt", "2026-05-25T00:00:30Z")));

    var replay = service.getConnectTokenReplay(7L, 11L, "scope-1", "req-7");

    assertThat(replay).isEmpty();
  }

  @Test
  void storePublicProductionMembershipReplayWritesHashedRealmAndRequestKey() {
    SessionService.PublicProductionMembershipReplay replay =
        new SessionService.PublicProductionMembershipReplay(
            true,
            new net.firedevops.firemud.accountservice.dto.PublicProductionMembershipResult(
                11L,
                7L,
                "demo",
                "production",
                711L,
                true,
                "req-join-1",
                "2026-05-25T00:00:00Z",
                false),
            "",
            "");

    service.storePublicProductionMembershipReplay(
        7L, 11L, "demo", "production", "req-join-1", replay, 30000L);

    verify(valueOperations)
        .set(
            "session:public-production-membership:tenant:7:account:11:world:"
                + sha256("demo")
                + ":realm:"
                + sha256("production")
                + ":request:"
                + sha256("req-join-1"),
            Map.of(
                "success",
                "true",
                "accountId",
                "11",
                "tenantId",
                "7",
                "worldSlug",
                "demo",
                "realmSlug",
                "production",
                "membershipVersion",
                "711",
                "created",
                "true",
                "requestId",
                "req-join-1",
                "evaluatedAt",
                "2026-05-25T00:00:00Z"),
            Duration.ofMillis(30000L));
  }

  @Test
  void getPublicProductionMembershipReplayReturnsEmptyWhenStoredWorldSlugMismatchesLookup() {
    String key =
        "session:public-production-membership:tenant:7:account:11:world:"
            + sha256("demo")
            + ":realm:"
            + sha256("production")
            + ":request:"
            + sha256("req-join-1");
    when(valueOperations.get(key))
        .thenReturn(
            Map.of(
                "success",
                "true",
                "accountId",
                "11",
                "tenantId",
                "7",
                "worldSlug",
                "sandbox",
                "realmSlug",
                "production",
                "membershipVersion",
                "711",
                "created",
                "true",
                "requestId",
                "req-join-1",
                "evaluatedAt",
                "2026-05-25T00:00:00Z"));

    var replay =
        service.getPublicProductionMembershipReplay(7L, 11L, "demo", "production", "req-join-1");

    assertThat(replay).isEmpty();
  }

  @Test
  void getPublicProductionMembershipReplayReturnsEmptyWhenStoredRealmSlugMismatchesLookup() {
    String key =
        "session:public-production-membership:tenant:7:account:11:world:"
            + sha256("demo")
            + ":realm:"
            + sha256("production")
            + ":request:"
            + sha256("req-join-1");
    when(valueOperations.get(key))
        .thenReturn(
            Map.of(
                "success",
                "true",
                "accountId",
                "11",
                "tenantId",
                "7",
                "worldSlug",
                "demo",
                "realmSlug",
                "staging",
                "membershipVersion",
                "711",
                "created",
                "true",
                "requestId",
                "req-join-1",
                "evaluatedAt",
                "2026-05-25T00:00:00Z"));

    var replay =
        service.getPublicProductionMembershipReplay(7L, 11L, "demo", "production", "req-join-1");

    assertThat(replay).isEmpty();
  }

  @Test
  void getPublicProductionMembershipReplayReturnsEmptyForMismatchedIdentity() {
    String key =
        "session:public-production-membership:tenant:7:account:11:world:"
            + sha256("demo")
            + ":realm:"
            + sha256("production")
            + ":request:"
            + sha256("req-join-1");
    when(valueOperations.get(key))
        .thenReturn(
            Map.of(
                "success",
                "true",
                "accountId",
                "11",
                "tenantId",
                "999",
                "worldSlug",
                "demo",
                "realmSlug",
                "production",
                "membershipVersion",
                "711",
                "created",
                "true",
                "requestId",
                "req-join-1",
                "evaluatedAt",
                "2026-05-25T00:00:00Z"));

    var replay =
        service.getPublicProductionMembershipReplay(7L, 11L, "demo", "production", "req-join-1");

    assertThat(replay).isEmpty();
  }

  @Test
  void getPublicProductionMembershipReplayReturnsEmptyForMalformedCreatedFlag() {
    String key =
        "session:public-production-membership:tenant:7:account:11:world:"
            + sha256("demo")
            + ":realm:"
            + sha256("production")
            + ":request:"
            + sha256("req-join-1");
    when(valueOperations.get(key))
        .thenReturn(
            Map.of(
                "success",
                "true",
                "accountId",
                "11",
                "tenantId",
                "7",
                "worldSlug",
                "demo",
                "realmSlug",
                "production",
                "membershipVersion",
                "711",
                "created",
                "sometimes",
                "requestId",
                "req-join-1",
                "evaluatedAt",
                "2026-05-25T00:00:00Z"));

    var replay =
        service.getPublicProductionMembershipReplay(7L, 11L, "demo", "production", "req-join-1");

    assertThat(replay).isEmpty();
  }

  @Test
  void getPublicProductionMembershipReplayReturnsEmptyForBlankRequiredTextField() {
    String key =
        "session:public-production-membership:tenant:7:account:11:world:"
            + sha256("demo")
            + ":realm:"
            + sha256("production")
            + ":request:"
            + sha256("req-join-1");
    when(valueOperations.get(key))
        .thenReturn(
            Map.of(
                "success",
                "true",
                "accountId",
                "11",
                "tenantId",
                "7",
                "worldSlug",
                "demo",
                "realmSlug",
                "production",
                "membershipVersion",
                "711",
                "created",
                "true",
                "requestId",
                "req-join-1",
                "evaluatedAt",
                "   "));

    var replay =
        service.getPublicProductionMembershipReplay(7L, 11L, "demo", "production", "req-join-1");

    assertThat(replay).isEmpty();
  }

  @Test
  void storeConnectTokenReplayRejectsSuccessfulReplayWithoutResult() {
    SessionService.ConnectTokenReplay replay =
        new SessionService.ConnectTokenReplay(true, null, "", "");

    assertThatThrownBy(
            () -> service.storeConnectTokenReplay(7L, 11L, "scope-1", "req-7", replay, 30000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connect token replay");
  }

  @Test
  void storeConnectTokenReplayRejectsMismatchedPayloadIdentity() {
    SessionService.ConnectTokenReplay replay =
        new SessionService.ConnectTokenReplay(
            true,
            new net.firedevops.firemud.accountservice.dto.ConnectTokenResult(
                11L,
                7L,
                44L,
                "production",
                "scope-other",
                "connect-1",
                "jti-1",
                "req-7",
                "2026-05-25T00:00:00Z",
                "2026-05-25T00:00:30Z",
                false),
            "",
            "");

    assertThatThrownBy(
            () -> service.storeConnectTokenReplay(7L, 11L, "scope-1", "req-7", replay, 30000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connect token replay payload mismatch for connectScopeId");
  }

  @Test
  void storePublicProductionMembershipReplayRejectsMismatchedWorldRealmPayload() {
    SessionService.PublicProductionMembershipReplay replay =
        new SessionService.PublicProductionMembershipReplay(
            true,
            new net.firedevops.firemud.accountservice.dto.PublicProductionMembershipResult(
                11L,
                7L,
                "demo",
                "production",
                711L,
                true,
                "req-join-1",
                "2026-05-25T00:00:00Z",
                false),
            "",
            "");

    assertThatThrownBy(
            () ->
                service.storePublicProductionMembershipReplay(
                    7L, 11L, "sandbox", "production", "req-join-1", replay, 30000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("public production membership replay payload mismatch");
  }

  @Test
  void storePublicProductionMembershipReplayRejectsMismatchedRequestIdPayload() {
    SessionService.PublicProductionMembershipReplay replay =
        new SessionService.PublicProductionMembershipReplay(
            true,
            new net.firedevops.firemud.accountservice.dto.PublicProductionMembershipResult(
                11L,
                7L,
                "demo",
                "production",
                711L,
                true,
                "req-other",
                "2026-05-25T00:00:00Z",
                false),
            "",
            "");

    assertThatThrownBy(
            () ->
                service.storePublicProductionMembershipReplay(
                    7L, 11L, "demo", "production", "req-join-1", replay, 30000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("public production membership replay payload mismatch for requestId");
  }

  private static String sha256(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
