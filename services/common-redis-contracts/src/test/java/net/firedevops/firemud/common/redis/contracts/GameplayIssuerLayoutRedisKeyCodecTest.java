package net.firedevops.firemud.common.redis.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GameplayIssuerLayoutRedisKeyCodecTest {
  @Test
  void matchesCanonicalLengthFramedVectorAndDoesNotSelectBindingPartition() {
    assertThat(
            GameplayIssuerLayoutRedisKeyCodec.layoutTag("layout-v1", "firemud-account-service", 0))
        .isEqualTo("gpi1-5996597914f7811c67de1b852000bedbd135b76d1229476e857fac9c203f5482");
    assertThat(
            GameplayIssuerLayoutRedisKeyCodec.partitionKey(
                "layout-v1", "firemud-account-service", 0))
        .isEqualTo(
            "session:game:index:issuer:{gpi1-5996597914f7811c67de1b852000bedbd135b76d1229476e857fac9c203f5482}:firemud-account-service:0");
    assertThat(
            GameplayIssuerLayoutRedisKeyCodec.layoutTag("layout-v2", "firemud-account-service", 0))
        .isNotEqualTo(
            GameplayIssuerLayoutRedisKeyCodec.layoutTag("layout-v1", "firemud-account-service", 0));
  }

  @Test
  void malformedUnscopedNegativeOrKeyInjectingInputsFailClosed() {
    assertThatThrownBy(() -> GameplayIssuerLayoutRedisKeyCodec.layoutTag("", "issuer", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> GameplayIssuerLayoutRedisKeyCodec.layoutTag("layout-v1", "issuer:{tag}", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GameplayIssuerLayoutRedisKeyCodec.layoutTag("layout-v1", "issuer", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
