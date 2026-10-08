package unit.net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.AccountSecurityCutoff;
import org.junit.jupiter.api.Test;

class GameSessionAccountDelegationProfileTest {
  @Test
  void declaresOnlyTheAccountAudienceCredentialLoginProfileAndFiniteBounds() {
    assertThat(GameSessionAccountDelegationProfile.PROFILE)
        .isEqualTo("game-session-account-delegation");
    assertThat(GameSessionAccountDelegationProfile.TYPE).isEqualTo("private_player_delegation");
    assertThat(GameSessionAccountDelegationProfile.ISSUER).isEqualTo("firemud-account-service");
    assertThat(GameSessionAccountDelegationProfile.AUDIENCE).isEqualTo("account-service");
    assertThat(GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS).isEqualTo(300L);
    assertThat(GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES).isPositive();
    assertThat(GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES).isPositive();
    assertThat(GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES).isPositive();
  }

  @Test
  void sequenceZeroNonTenantAuthorityTupleHasExactGenerationAndEmptyScopeShape() {
    Map<String, Object> tuple = GameSessionAccountDelegationProfile.authorityTuple(7L, 1L);

    assertThat(tuple)
        .containsOnlyKeys(
            "issuerAuthGeneration",
            "accountAuthorityGeneration",
            "tenantAuthorityGeneration",
            "membershipAuthorityGeneration",
            "privateRealmGrantVersions")
        .containsEntry("issuerAuthGeneration", "7")
        .containsEntry("accountAuthorityGeneration", "1")
        .containsEntry("tenantAuthorityGeneration", Map.of())
        .containsEntry("membershipAuthorityGeneration", Map.of())
        .containsEntry("privateRealmGrantVersions", java.util.List.of());
  }

  @Test
  void includesOnlyAnExplicitlyApplicableAccountCutoff() {
    AccountSecurityCutoff cutoff =
        new AccountSecurityCutoff(
            "12", "account:auth-authority:v1:account/11111111-1111-4111-8111-111111111111", "44");

    Map<String, Object> tuple =
        GameSessionAccountDelegationProfile.authorityTuple(7L, 12L, Optional.of(cutoff));

    assertThat(tuple)
        .containsOnlyKeys(
            "issuerAuthGeneration",
            "accountAuthorityGeneration",
            "tenantAuthorityGeneration",
            "membershipAuthorityGeneration",
            "privateRealmGrantVersions",
            "accountSecurityCutoff")
        .containsEntry("accountSecurityCutoff", cutoff.toMap());
    assertThat(tuple)
        .containsEntry("issuerAuthGeneration", "7")
        .containsEntry("accountAuthorityGeneration", "12");
    assertThat(GameSessionAccountDelegationProfile.authorityTuple(7L, 1L, Optional.empty()))
        .doesNotContainKey("accountSecurityCutoff");
  }

  @Test
  void rejectsMalformedCutoffCountersAndNonAccountStreamScopes() {
    assertThatThrownBy(
            () ->
                new AccountSecurityCutoff(
                    "12",
                    "account:auth-authority:v1:account/11111111-1111-4111-8111-111111111111",
                    "044"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountSecurityCutoff(
                    "12",
                    "account:auth-authority:v1:account/11111111-1111-4111-8111-111111111111",
                    "0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountSecurityCutoff(
                    "12",
                    "account:auth-authority:v1:tenant/11111111-1111-4111-8111-111111111111",
                    "44"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameSessionAccountDelegationProfile.authorityTuple(
                    7L,
                    12L,
                    Optional.of(
                        new AccountSecurityCutoff(
                            "11",
                            "account:auth-authority:v1:account/11111111-1111-4111-8111-111111111111",
                            "44"))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
