package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import org.junit.jupiter.api.Test;

class AccountAuthorityEvidenceTest {
  private static final String ACCOUNT_UUID = "f2ed193b-12c1-4c96-bcad-c162229af440";
  private static final String OTHER_ACCOUNT_UUID = "b0ad193b-12c1-4c96-bcad-c162229af440";
  private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void acceptsFreshGrantForExactCanonicalAccountAndTenant() {
    assertThat(
            AccountAuthorityEvidence.isValidRealmAccessGrant(
                validGrant(), ACCOUNT_UUID, 22L, "demo", "production", CLOCK))
        .isTrue();
  }

  @Test
  void rejectsMalformedNilNumericAndWrongAccountIdentities() {
    assertInvalidAccount("malformed");
    assertInvalidAccount("00000000-0000-0000-0000-000000000000");
    assertInvalidAccount("123");
    assertInvalidAccount(ACCOUNT_UUID.toUpperCase(java.util.Locale.ROOT));
    assertInvalidAccount(OTHER_ACCOUNT_UUID);
    assertThat(
            AccountAuthorityEvidence.isValidRealmAccessGrant(
                validGrant(), "123", 22L, "demo", "production", CLOCK))
        .isFalse();
  }

  @Test
  void rejectsExpiredGrantEvidence() {
    var expired = validGrant().toBuilder().setEvaluatedAt(NOW.minusSeconds(16).toString()).build();

    assertThat(
            AccountAuthorityEvidence.isValidRealmAccessGrant(
                expired, ACCOUNT_UUID, 22L, "demo", "production", CLOCK))
        .isFalse();
  }

  @Test
  void retainsTenantWorldRealmAndVersionChecks() {
    assertThat(
            AccountAuthorityEvidence.isValidRealmAccessGrant(
                validGrant().toBuilder().setTenantId("23").build(),
                ACCOUNT_UUID,
                22L,
                "demo",
                "production",
                CLOCK))
        .isFalse();
    assertThat(
            AccountAuthorityEvidence.isValidRealmAccessGrant(
                validGrant(), ACCOUNT_UUID, 22L, "other", "production", CLOCK))
        .isFalse();
    assertThat(
            AccountAuthorityEvidence.isValidRealmAccessGrant(
                validGrant(), ACCOUNT_UUID, 22L, "demo", "other", CLOCK))
        .isFalse();
    assertThat(
            AccountAuthorityEvidence.isValidRealmAccessGrant(
                validGrant().toBuilder().setGrantVersion(0L).build(),
                ACCOUNT_UUID,
                22L,
                "demo",
                "production",
                CLOCK))
        .isFalse();
  }

  private static void assertInvalidAccount(String accountId) {
    assertThat(
            AccountAuthorityEvidence.isValidRealmAccessGrant(
                validGrant().toBuilder().setAccountId(accountId).build(),
                ACCOUNT_UUID,
                22L,
                "demo",
                "production",
                CLOCK))
        .isFalse();
  }

  private static GetRealmAccessGrantForRuntimeResponse validGrant() {
    return GetRealmAccessGrantForRuntimeResponse.newBuilder()
        .setAccountId(ACCOUNT_UUID)
        .setTenantId("22")
        .setWorldSlug("demo")
        .setRealmSlug("production")
        .setGrantVersion(1L)
        .setEvaluatedAt(NOW.toString())
        .build();
  }
}
