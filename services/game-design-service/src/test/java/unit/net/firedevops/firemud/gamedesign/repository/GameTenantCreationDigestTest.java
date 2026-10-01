package unit.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.Test;

class GameTenantCreationDigestTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OPERATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String REQUEST_DIGEST =
      "sha256:467cbe9641a3ad61d14969aece34b770629ae569641d69fb1aea6e3f1538a28a";

  @Test
  void requestDigestUsesUtf8ByteLengthsForMultibyteSegments() {
    assertThat(
            GameTenantCreationDigest.requestDigest(
                "prod", REQUEST_ID, "legacy-α", "MUD 🎲", "café"))
        .isEqualTo("sha256:467cbe9641a3ad61d14969aece34b770629ae569641d69fb1aea6e3f1538a28a");
  }

  @Test
  void requestDigestSeparatesAbsentDescriptionFromEmptyDescription() {
    String absent =
        GameTenantCreationDigest.requestDigest("prod", REQUEST_ID, "legacy-α", "MUD 🎲", null);
    String empty =
        GameTenantCreationDigest.requestDigest("prod", REQUEST_ID, "legacy-α", "MUD 🎲", "");

    assertThat(absent)
        .isEqualTo("sha256:3fb24a9c0e7461c9a70183982a70faf22c7998bcd31b10aca803b938dd77c9df");
    assertThat(empty)
        .isEqualTo("sha256:8f6e0a815262b0fc761f0ea623d1ff95acb0d852f0a8d6c76a283b3c2d270c6a");
    assertThat(absent).isNotEqualTo(empty);
  }

  @Test
  void requestDigestRejectsUnpairedUnicodeSurrogates() {
    String malformedName = "bad" + (char) 0xD800;
    assertThatThrownBy(
            () ->
                GameTenantCreationDigest.requestDigest(
                    "prod", REQUEST_ID, "tenant", malformedName, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid Unicode");
  }

  @Test
  void evidenceDigestHasGoldenVectorAndChangesWhenTheSourceRowChanges() {
    String expected =
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            REQUEST_ID,
            OPERATION_ID,
            REQUEST_DIGEST,
            CANONICAL_TENANT_ID,
            42L,
            "legacy-α",
            "NEW_GAME_ROW");
    String changedSourceRow =
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            REQUEST_ID,
            OPERATION_ID,
            REQUEST_DIGEST,
            CANONICAL_TENANT_ID,
            43L,
            "legacy-α",
            "NEW_GAME_ROW");

    assertThat(expected)
        .isEqualTo("sha256:533f0c6590aff359baa6bbaa1a4b9ab7cd6ebf68a3663ed6681053f5a8cc1955");
    assertThat(changedSourceRow).isNotEqualTo(expected);
  }
}
