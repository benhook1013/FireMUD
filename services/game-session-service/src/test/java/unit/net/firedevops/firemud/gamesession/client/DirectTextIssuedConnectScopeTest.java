package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;

class DirectTextIssuedConnectScopeTest {
  private static final String ACCOUNT_ID = "00000000-0000-4000-8000-000000000042";
  private static final String EVALUATED_AT = "2026-08-02T00:00:00.120Z";
  private static final String EXPIRES_AT = "2026-08-02T00:02:00Z";
  private static final Instant NOW = Instant.parse("2026-08-02T00:01:00Z");

  @Test
  void retainsExactResolvedTargetAndAccountOwnedWireTimes() {
    var target = target();
    var response =
        response("opaque/account-scope:without-uuid-assumptions", EVALUATED_AT, EXPIRES_AT);

    var scope =
        DirectTextIssuedConnectScope.fromAccountResponse(ACCOUNT_ID, target, response, clock(NOW));

    assertThat(scope.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(scope.target()).isSameAs(target);
    assertThat(scope.connectScopeId()).isEqualTo("opaque/account-scope:without-uuid-assumptions");
    assertThat(scope.evaluatedAt()).isEqualTo(EVALUATED_AT);
    assertThat(scope.evaluatedAtInstant()).isEqualTo(Instant.parse(EVALUATED_AT));
    assertThat(scope.connectScopeExpiresAt()).isEqualTo(EXPIRES_AT);
    assertThat(scope.connectScopeExpiresAtInstant()).isEqualTo(Instant.parse(EXPIRES_AT));
  }

  @Test
  void enforcesExpiryAtTheExactAccountExpiryBoundary() {
    var scope =
        DirectTextIssuedConnectScope.fromAccountResponse(
            ACCOUNT_ID, target(), response("scope", EVALUATED_AT, EXPIRES_AT), clock(NOW));

    assertThat(scope.isLive(clock(Instant.parse("2026-08-02T00:01:59.999999999Z")))).isTrue();
    assertThat(scope.isLive(clock(Instant.parse(EXPIRES_AT)))).isFalse();
    assertThatThrownBy(
            () ->
                DirectTextIssuedConnectScope.fromAccountResponse(
                    ACCOUNT_ID,
                    target(),
                    response("scope", EVALUATED_AT, EXPIRES_AT),
                    clock(Instant.parse(EXPIRES_AT))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAccountErrorsAndMalformedOrContradictoryEvidence() {
    var valid = response("scope", EVALUATED_AT, EXPIRES_AT);
    var error =
        valid.toBuilder().setError(ErrorDetail.newBuilder().setCode("AUTH_UNAVAILABLE")).build();

    assertRejected(error, ACCOUNT_ID, target(), clock(NOW));
    assertRejected(
        valid.toBuilder().clearConnectScopeId().build(), ACCOUNT_ID, target(), clock(NOW));
    assertRejected(valid.toBuilder().clearEvaluatedAt().build(), ACCOUNT_ID, target(), clock(NOW));
    assertRejected(
        valid.toBuilder().setEvaluatedAt("2026-08-02T00:00:00+00:00").build(),
        ACCOUNT_ID,
        target(),
        clock(NOW));
    assertRejected(
        valid.toBuilder().setConnectScopeExpiresAt("not-a-timestamp").build(),
        ACCOUNT_ID,
        target(),
        clock(NOW));
    assertRejected(
        response("scope", "2026-08-02T00:03:00Z", EXPIRES_AT), ACCOUNT_ID, target(), clock(NOW));
    assertRejected(
        response("scope", EVALUATED_AT, "2026-08-02T00:00:00Z"), ACCOUNT_ID, target(), clock(NOW));
  }

  @Test
  void requiresCanonicalNonNilCallerAccountUuid() {
    for (String malformed :
        new String[] {
          "42",
          "00000000-0000-4000-8000-00000000004A",
          "00000000-0000-0000-0000-000000000000",
          " " + ACCOUNT_ID
        }) {
      assertRejected(response("scope", EVALUATED_AT, EXPIRES_AT), malformed, target(), clock(NOW));
    }
  }

  @Test
  void boundsOpaqueScopeWithoutChangingItsBytes() {
    String maximumScope = "s".repeat(2048);
    var retained =
        DirectTextIssuedConnectScope.fromAccountResponse(
            ACCOUNT_ID, target(), response(maximumScope, EVALUATED_AT, EXPIRES_AT), clock(NOW));
    assertThat(retained.connectScopeId()).isEqualTo(maximumScope);
    assertRejected(
        response(maximumScope + "s", EVALUATED_AT, EXPIRES_AT), ACCOUNT_ID, target(), clock(NOW));
  }

  @Test
  void retainsAccountEvaluationWithoutInventingLocalFreshnessPolicy() {
    String accountEvaluation = "2026-08-02T00:01:30Z";
    var retained =
        DirectTextIssuedConnectScope.fromAccountResponse(
            ACCOUNT_ID, target(), response("scope", accountEvaluation, EXPIRES_AT), clock(NOW));
    assertThat(retained.evaluatedAt()).isEqualTo(accountEvaluation);
    assertThat(retained.isLive(clock(NOW))).isTrue();
  }

  @Test
  void retainsOriginalResponseValuesAfterBuilderReuse() {
    var responseBuilder = response("opaque-scope", EVALUATED_AT, EXPIRES_AT).toBuilder();
    var scope =
        DirectTextIssuedConnectScope.fromAccountResponse(
            ACCOUNT_ID, target(), responseBuilder.build(), clock(NOW));
    responseBuilder.setConnectScopeId("replacement").setEvaluatedAt("2026-08-02T00:00:01Z");

    assertThat(scope.connectScopeId()).isEqualTo("opaque-scope");
    assertThat(scope.evaluatedAt()).isEqualTo(EVALUATED_AT);
    assertThat(scope.evaluatedAtInstant()).isEqualTo(Instant.parse(EVALUATED_AT));
  }

  private static void assertRejected(
      IssueDirectTextConnectScopeResponse response,
      String accountId,
      DirectTextConnectScopeTarget target,
      Clock clock) {
    assertThatThrownBy(
            () ->
                DirectTextIssuedConnectScope.fromAccountResponse(
                    accountId, target, response, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static IssueDirectTextConnectScopeResponse response(
      String scopeId, String evaluatedAt, String expiresAt) {
    return IssueDirectTextConnectScopeResponse.newBuilder()
        .setConnectScopeId(scopeId)
        .setEvaluatedAt(evaluatedAt)
        .setConnectScopeExpiresAt(expiresAt)
        .build();
  }

  private static DirectTextConnectScopeTarget target() {
    return new DirectTextConnectScopeTarget(
        "22",
        "demo-world",
        "production",
        "00000000-0000-4000-8000-000000000101",
        "00000000-0000-4000-8000-000000000102",
        "SHARED",
        "9",
        7L,
        4L);
  }

  private static Clock clock(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }
}
