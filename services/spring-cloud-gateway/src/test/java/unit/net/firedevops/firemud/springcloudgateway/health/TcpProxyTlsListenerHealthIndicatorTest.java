package net.firedevops.firemud.springcloudgateway.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.springcloudgateway.config.GatewayTcpProxyListenerProperties;
import net.firedevops.firemud.springcloudgateway.config.TcpProxyTlsListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class TcpProxyTlsListenerHealthIndicatorTest {

  @Test
  void disabledListenerDoesNotBlockReadiness() {
    GatewayTcpProxyListenerProperties properties = new GatewayTcpProxyListenerProperties();
    TcpProxyTlsListener listener = mock(TcpProxyTlsListener.class);

    assertThat(new TcpProxyTlsListenerHealthIndicator(properties, listener).health().getStatus())
        .isEqualTo(Status.UP);
  }

  @Test
  void enabledListenerIsReadyOnlyAfterBinding() {
    GatewayTcpProxyListenerProperties properties = new GatewayTcpProxyListenerProperties();
    properties.setEnabled(true);
    properties.setPort(8443);
    properties.setTrustProfile("production_uri");
    TcpProxyTlsListener listener = mock(TcpProxyTlsListener.class);
    when(listener.boundPort()).thenReturn(-1);
    when(listener.isRunning()).thenReturn(false);

    assertThat(new TcpProxyTlsListenerHealthIndicator(properties, listener).health().getStatus())
        .isEqualTo(Status.OUT_OF_SERVICE);

    when(listener.boundPort()).thenReturn(8443);
    when(listener.isRunning()).thenReturn(true);
    assertThat(new TcpProxyTlsListenerHealthIndicator(properties, listener).health().getStatus())
        .isEqualTo(Status.UP);
  }
}
