package net.firedevops.firemud.common.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountProfileJsonTest {

  @Test
  void parseFallsBackToConfiguredDefaultPresenceVisibilityPolicyWhenFieldIsMissing()
      throws Exception {
    AccountProfileJson profile =
        AccountProfileJson.parse(
            """
            {"displayName":"Demo-7","bio":null}
            """,
            "FRIENDS_ONLY");

    assertThat(profile.displayName()).isEqualTo("Demo-7");
    assertThat(profile.bio()).isNull();
    assertThat(profile.presenceVisibilityPolicy()).isEqualTo("FRIENDS_ONLY");
  }

  @Test
  void toJsonPreservesExplicitNullsAndPresenceVisibilityPolicy() throws Exception {
    AccountProfileJson profile = new AccountProfileJson("Demo-7", null, "PRIVATE");

    assertThat(profile.toJson())
        .isEqualTo(
            "{\"displayName\":\"Demo-7\",\"bio\":null,\"presenceVisibilityPolicy\":\"PRIVATE\"}");
  }
}
