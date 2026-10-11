package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.GameplayCredentialSourceContext;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBinding;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource.CredentialDigestKey;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigester;
import org.junit.jupiter.api.Test;

class AccountGameplayCredentialRequestDigesterTest {
  private static final String WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID OTHER_ACCOUNT_ID =
      UUID.fromString("674cb504-8f4c-4d06-86c6-7b72f79cae74");
  private static final Instant RETAINED_UNTIL = Instant.parse("2027-01-16T12:00:00Z");
  private static final CredentialDigestKey KEY = key("account-login-key-v1", new byte[32]);
  private static final CanonicalGameplayLoginRequest REQUEST =
      request(
          UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76"),
          "Player@Example.com",
          "one-time-secret",
          new GameplayCredentialSourceContext(
              UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436"), "203.0.113.5", "TLS_TCP"));

  @Test
  void bindingIsDeterministicVersionedAndBindsTheExactCredentialOperation() {
    AccountGameplayCredentialRequestBinding first =
        bind(KEY, REQUEST, "player@example.com", WORKLOAD, ACCOUNT_ID);
    AccountGameplayCredentialRequestBinding retry =
        bind(KEY, REQUEST, "player@example.com", WORKLOAD, ACCOUNT_ID);

    assertThat(first).isEqualTo(retry);
    assertThat(first.digestSchemaVersion()).isEqualTo(1);
    assertThat(first.digestKeyId()).isEqualTo(KEY.keyId());
    assertThat(first.credentialRequestDigest()).matches("[0-9a-f]{64}");
    assertThat(AccountGameplayCredentialRequestDigester.matches(first, retry)).isTrue();
    assertThat(REQUEST.toString()).doesNotContain("one-time-secret");
    assertThat(REQUEST.sourceContext().toString()).doesNotContain("203.0.113.5");
    assertThat(first.toString()).doesNotContain(first.credentialRequestDigest());
  }

  @Test
  void bindingChangesForEveryCredentialCallerAndSourceContextDimension() {
    AccountGameplayCredentialRequestBinding baseline =
        bind(KEY, REQUEST, "player@example.com", WORKLOAD, ACCOUNT_ID);

    assertDifferent(
        baseline,
        bind(
            KEY,
            request(
                UUID.fromString("58102ea4-9245-4ddc-a68c-3ff41dcf96af"),
                REQUEST.email(),
                REQUEST.credential(),
                REQUEST.sourceContext()),
            "player@example.com",
            WORKLOAD,
            ACCOUNT_ID));
    assertDifferent(
        baseline,
        bind(
            KEY,
            request(
                REQUEST.requestId(), REQUEST.email(), "changed-secret", REQUEST.sourceContext()),
            "player@example.com",
            WORKLOAD,
            ACCOUNT_ID));
    assertDifferent(baseline, bind(KEY, REQUEST, "other@example.com", WORKLOAD, ACCOUNT_ID));
    assertDifferent(
        baseline,
        bind(
            KEY,
            REQUEST,
            "player@example.com",
            "spiffe://firemud/ns/other/sa/game-session-service",
            ACCOUNT_ID));
    assertDifferent(
        baseline,
        bind(
            KEY,
            request(
                REQUEST.requestId(),
                REQUEST.email(),
                REQUEST.credential(),
                new GameplayCredentialSourceContext(
                    UUID.fromString("e46c59e8-4dd8-49a7-9d26-2db49f0d0ecb"),
                    "203.0.113.5",
                    "TLS_TCP")),
            "player@example.com",
            WORKLOAD,
            ACCOUNT_ID));
    assertDifferent(
        baseline,
        bind(
            KEY,
            request(
                REQUEST.requestId(),
                REQUEST.email(),
                REQUEST.credential(),
                new GameplayCredentialSourceContext(
                    REQUEST.sourceContext().contextId(), "203.0.113.6", "TLS_TCP")),
            "player@example.com",
            WORKLOAD,
            ACCOUNT_ID));
    assertDifferent(
        baseline,
        bind(
            KEY,
            request(
                REQUEST.requestId(),
                REQUEST.email(),
                REQUEST.credential(),
                new GameplayCredentialSourceContext(
                    REQUEST.sourceContext().contextId(), "203.0.113.5", "TLS_WEBSOCKET")),
            "player@example.com",
            WORKLOAD,
            ACCOUNT_ID));
    assertDifferent(baseline, bind(KEY, REQUEST, "player@example.com", WORKLOAD, OTHER_ACCOUNT_ID));
    assertDifferent(
        baseline,
        bind(
            key("account-login-key-v2", new byte[32]),
            REQUEST,
            "player@example.com",
            WORKLOAD,
            ACCOUNT_ID));
    assertDifferent(
        baseline,
        bind(
            key("account-login-key-v1", changedKeyMaterial()),
            REQUEST,
            "player@example.com",
            WORKLOAD,
            ACCOUNT_ID));
  }

  @Test
  void rejectsUnverifiedOrNonGameSessionCallerIdentity() {
    assertThatThrownBy(() -> bind(KEY, REQUEST, "player@example.com", null, ACCOUNT_ID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                bind(
                    KEY,
                    REQUEST,
                    "player@example.com",
                    "spiffe://firemud/ns/test/sa/tcp-proxy-service",
                    ACCOUNT_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertDifferent(
      AccountGameplayCredentialRequestBinding expected,
      AccountGameplayCredentialRequestBinding candidate) {
    assertThat(AccountGameplayCredentialRequestDigester.matches(expected, candidate)).isFalse();
  }

  private static AccountGameplayCredentialRequestBinding bind(
      CredentialDigestKey key,
      CanonicalGameplayLoginRequest request,
      String normalizedEmail,
      String callerWorkload,
      UUID accountId) {
    return AccountGameplayCredentialRequestDigester.bind(
        key, request, normalizedEmail, callerWorkload, accountId);
  }

  private static CanonicalGameplayLoginRequest request(
      UUID requestId, String email, String credential, GameplayCredentialSourceContext source) {
    return new CanonicalGameplayLoginRequest(requestId, email, credential, source);
  }

  private static CredentialDigestKey key(String keyId, byte[] bytes) {
    return new CredentialDigestKey(keyId, new SecretKeySpec(bytes, "HmacSHA256"), RETAINED_UNTIL);
  }

  private static byte[] changedKeyMaterial() {
    byte[] bytes = new byte[32];
    bytes[0] = 1;
    return bytes;
  }
}
