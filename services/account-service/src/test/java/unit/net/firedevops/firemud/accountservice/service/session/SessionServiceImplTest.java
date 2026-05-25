package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
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
                "2026-05-25T00:00:00Z",
                "2026-05-25T00:00:30Z"),
            "",
            "");

    service.storeConnectTokenReplay(7L, 11L, "scope-1", "req-7", replay, 30000L);

    verify(valueOperations)
        .set(
            "session:connect-token:tenant:7:account:11:scope:"
                + sha256("scope-1")
                + ":request:"
                + sha256("req-7"),
            Map.of(
                "success",
                "true",
                "accountId",
                "11",
                "tenantId",
                "7",
                "gameInstanceId",
                "44",
                "realmSlug",
                "production",
                "connectScopeId",
                "scope-1",
                "connectToken",
                "connect-1",
                "jti",
                "jti-1",
                "issuedAt",
                "2026-05-25T00:00:00Z",
                "expiresAt",
                "2026-05-25T00:00:30Z"),
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
