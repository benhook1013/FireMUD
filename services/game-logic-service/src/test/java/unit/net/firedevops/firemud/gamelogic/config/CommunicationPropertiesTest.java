package unit.net.firedevops.firemud.gamelogic.config;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamelogic.config.CommunicationProperties;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import org.junit.jupiter.api.Test;

class CommunicationPropertiesTest {

  @Test
  void defaultsEnableBuiltInCommunicationModes() {
    CommunicationProperties properties = new CommunicationProperties();

    assertThat(properties.maxMessageLength()).isEqualTo(512);
    assertThat(properties.enabled(CommunicationType.SAY)).isTrue();
    assertThat(properties.enabled(CommunicationType.WHISPER)).isTrue();
    assertThat(properties.enabled(CommunicationType.TELL)).isTrue();
  }

  @Test
  void explicitDefaultsDisableOnlyConfiguredModes() {
    CommunicationProperties properties =
        new CommunicationProperties(
            256, new CommunicationProperties.Defaults(true, false, true, true));

    assertThat(properties.maxMessageLength()).isEqualTo(256);
    assertThat(properties.enabled(CommunicationType.SAY)).isTrue();
    assertThat(properties.enabled(CommunicationType.WHISPER)).isFalse();
    assertThat(properties.enabled(CommunicationType.TELL)).isTrue();
  }
}
