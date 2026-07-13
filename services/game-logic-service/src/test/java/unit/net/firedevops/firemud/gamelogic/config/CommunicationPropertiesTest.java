package unit.net.firedevops.firemud.gamelogic.config;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamelogic.config.CommunicationProperties;
import org.junit.jupiter.api.Test;

class CommunicationPropertiesTest {

  @Test
  void defaultsUseTheSharedSocialCapabilityAndKeepObserverMetadataEnabled() {
    CommunicationProperties properties = new CommunicationProperties();

    assertThat(properties.maxMessageLength()).isEqualTo(512);
    assertThat(properties.whisperObserverMetadataEnabled()).isTrue();
  }

  @Test
  void explicitPropertiesControlOnlyCommunicationBehavior() {
    CommunicationProperties properties = new CommunicationProperties(256, false);

    assertThat(properties.maxMessageLength()).isEqualTo(256);
    assertThat(properties.whisperObserverMetadataEnabled()).isFalse();
  }
}
