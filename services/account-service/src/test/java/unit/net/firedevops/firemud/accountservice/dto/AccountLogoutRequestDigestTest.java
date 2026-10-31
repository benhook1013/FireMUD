package unit.net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import org.junit.jupiter.api.Test;

class AccountLogoutRequestDigestTest {
  private static final UUID LOGOUT_ALL_ACCOUNT =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TOKEN_LOGOUT_ACCOUNT =
      UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void accountLogoutAllMatchesFixedRequestDigestV1PreimageAndDigestVector() {
    String tokenHash = "a".repeat(64);

    assertThat(
            AccountLogoutRequestDigest.accountLogoutAllPreimageHex(
                LOGOUT_ALL_ACCOUNT, "control-ui", tokenHash))
        .isEqualTo(
            compactHex(
                """
                    31363a726571756573744469676573742f763131333a6f7065726174696f6e4b696e64363a737472696e673131383a4143434f554e545f4c4f474f55545f414c4c31363a7375626a6563744163636f756e744964363a737472696e673133363a31313131313131312d313131312d343131312d383131312d31313131313131313131313131323a746f6b656e50726f66696c65363a737472696e673131303a636f6e74726f6c2d756931383a70726573656e746564546f6b656e48617368363a737472696e673136343a61616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161
                    """));
    assertThat(
            AccountLogoutRequestDigest.accountLogoutAll(
                LOGOUT_ALL_ACCOUNT, "control-ui", tokenHash))
        .isEqualTo("78f999be5a5bdb0a5bc4517336ad6899b2383c8058b0bb8f76fe67eb67dea020");
  }

  @Test
  void tokenLogoutMatchesFixedRequestDigestV1PreimageAndDigestVector() {
    String tokenHash = "b".repeat(64);

    assertThat(
            AccountLogoutRequestDigest.tokenLogoutPreimageHex(
                TOKEN_LOGOUT_ACCOUNT, "player-bootstrap", tokenHash))
        .isEqualTo(
            compactHex(
                """
                    31363a726571756573744469676573742f763131333a6f7065726174696f6e4b696e64363a737472696e673131323a544f4b454e5f4c4f474f555431363a7375626a6563744163636f756e744964363a737472696e673133363a32323232323232322d323232322d343232322d383232322d32323232323232323232323231323a746f6b656e50726f66696c65363a737472696e673131363a706c617965722d626f6f747374726170393a746f6b656e48617368363a737472696e673136343a62626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262
                    """));
    assertThat(
            AccountLogoutRequestDigest.tokenLogout(
                TOKEN_LOGOUT_ACCOUNT, "player-bootstrap", tokenHash))
        .isEqualTo("21c085ddf384d4ab3d32c04f37601e710240d11ffe1be64531ee780cf2fcdee0");
  }

  @Test
  void eachClosedTupleFieldChangesTheDigest() {
    String tokenHash = "a".repeat(64);
    String original =
        AccountLogoutRequestDigest.accountLogoutAll(LOGOUT_ALL_ACCOUNT, "control-ui", tokenHash);

    assertThat(AccountLogoutRequestDigest.tokenLogout(LOGOUT_ALL_ACCOUNT, "control-ui", tokenHash))
        .isNotEqualTo(original);
    assertThat(
            AccountLogoutRequestDigest.accountLogoutAll(
                TOKEN_LOGOUT_ACCOUNT, "control-ui", tokenHash))
        .isNotEqualTo(original);
    assertThat(
            AccountLogoutRequestDigest.accountLogoutAll(
                LOGOUT_ALL_ACCOUNT, "player-bootstrap", tokenHash))
        .isNotEqualTo(original);
    assertThat(
            AccountLogoutRequestDigest.accountLogoutAll(
                LOGOUT_ALL_ACCOUNT, "control-ui", "b".repeat(64)))
        .isNotEqualTo(original);
  }

  @Test
  void rejectsUnsupportedProfilesNoncanonicalAccountIdentityAndMalformedTokenHashes() {
    assertThatThrownBy(
            () ->
                AccountLogoutRequestDigest.accountLogoutAll(
                    LOGOUT_ALL_ACCOUNT, "private-player-delegation", "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("token profile");
    assertThatThrownBy(
            () ->
                AccountLogoutRequestDigest.accountLogoutAll(
                    new UUID(0L, 0L), "control-ui", "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil Account UUID");
    assertThatThrownBy(
            () ->
                AccountLogoutRequestDigest.tokenLogout(
                    LOGOUT_ALL_ACCOUNT, "control-ui", "A".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lowercase SHA-256 hex");
    assertThatThrownBy(
            () ->
                AccountLogoutRequestDigest.tokenLogout(
                    LOGOUT_ALL_ACCOUNT, "control-ui", "a".repeat(63)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lowercase SHA-256 hex");
  }

  private static String compactHex(String hex) {
    return hex.replaceAll("\\s+", "");
  }
}
