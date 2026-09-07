package net.firedevops.firemud.springcloudgateway.health;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import net.firedevops.firemud.springcloudgateway.config.GatewayTcpProxyListenerProperties;
import net.firedevops.firemud.springcloudgateway.config.TcpProxyTlsListener;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness contribution for the dedicated TCP Proxy TLS listener. */
@Component("tcpProxyTlsListenerReadiness")
public final class TcpProxyTlsListenerHealthIndicator implements HealthIndicator {
  private final GatewayTcpProxyListenerProperties properties;
  private final TcpProxyTlsListener listener;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected configuration and listener are framework-owned singleton dependencies.")
  public TcpProxyTlsListenerHealthIndicator(
      GatewayTcpProxyListenerProperties properties, TcpProxyTlsListener listener) {
    this.properties = properties;
    this.listener = listener;
  }

  @Override
  public Health health() {
    if (!properties.isEnabled()) {
      return Health.up().withDetail("listener", "disabled").build();
    }
    Map<String, Object> details =
        Map.of(
            "listener", "tcp-proxy-internal-tls",
            "configuredPort", properties.getPort(),
            "boundPort", listener.boundPort(),
            "trustProfile", properties.getTrustProfile());
    return listener.isRunning()
        ? Health.up().withDetails(details).build()
        : Health.outOfService().withDetails(details).build();
  }
}
