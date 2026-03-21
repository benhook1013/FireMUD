package net.firedevops.firemud.tcpproxy.health;

import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator for new Telnet traffic admission. */
@Component("trafficAdmissionReadiness")
public class TcpProxyTrafficReadinessHealthIndicator implements HealthIndicator {
  private final ObjectProvider<TelnetServer> telnetServerProvider;
  private final GatewayGameplayReadinessProbe gatewayGameplayReadinessProbe;

  public TcpProxyTrafficReadinessHealthIndicator(
      ObjectProvider<TelnetServer> telnetServerProvider,
      GatewayGameplayReadinessProbe gatewayGameplayReadinessProbe) {
    this.telnetServerProvider = telnetServerProvider;
    this.gatewayGameplayReadinessProbe = gatewayGameplayReadinessProbe;
  }

  @Override
  public Health health() {
    TelnetServer server = telnetServerProvider.getIfAvailable();
    if (server == null || !server.isRunning()) {
      return Health.outOfService().withDetail("telnetListener", "DOWN").build();
    }
    if (!gatewayGameplayReadinessProbe.isReady()) {
      return Health.outOfService()
          .withDetail("telnetListener", "UP")
          .withDetail("gatewayReadiness", "DOWN")
          .withDetail(
              "gatewayReadinessUri", String.valueOf(gatewayGameplayReadinessProbe.readinessUri()))
          .build();
    }
    return Health.up()
        .withDetail("telnetListener", "UP")
        .withDetail("gatewayReadiness", "UP")
        .withDetail(
            "gatewayReadinessUri", String.valueOf(gatewayGameplayReadinessProbe.readinessUri()))
        .build();
  }
}
