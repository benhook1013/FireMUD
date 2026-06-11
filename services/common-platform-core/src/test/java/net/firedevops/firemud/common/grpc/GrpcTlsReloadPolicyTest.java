package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcTlsReloadPolicyTest {

  @AfterEach
  void clearReloadProperty() {
    System.clearProperty(GrpcTlsReloadPolicy.PROPERTY_NAME);
  }

  @Test
  void defaultsToEnabledWithoutConfiguration() {
    assertThat(GrpcTlsReloadPolicy.isEnabled()).isTrue();
  }

  @Test
  void disablesWatcherWhenSystemPropertyIsFalse() {
    System.setProperty(GrpcTlsReloadPolicy.PROPERTY_NAME, "false");

    assertThat(GrpcTlsReloadPolicy.isEnabled()).isFalse();
  }

  @Test
  void enablesWatcherWhenSystemPropertyIsTrue() {
    System.setProperty(GrpcTlsReloadPolicy.PROPERTY_NAME, "true");

    assertThat(GrpcTlsReloadPolicy.isEnabled()).isTrue();
  }

  @Test
  void detectsThatPropertyValueWinsOverEnvironmentWhenPresent() throws Exception {
    System.setProperty(GrpcTlsReloadPolicy.PROPERTY_NAME, "disabled");

    Method configuredValue = GrpcTlsReloadPolicy.class.getDeclaredMethod("configuredValue");
    configuredValue.setAccessible(true);

    assertThat(configuredValue.invoke(null)).hasToString("Optional[disabled]");
    assertThat(GrpcTlsReloadPolicy.isEnabled()).isFalse();
  }
}
