package net.firedevops.firemud.tcpproxy.health;

import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator for new Telnet traffic admission. */
@Component("trafficAdmissionReadiness")
public class TcpProxyTrafficReadinessHealthIndicator implements HealthIndicator {
  private static final String CONTRACT = "Telnet connect->LOGIN->LOOK admission";

  private final ObjectProvider<TelnetServer> telnetServerProvider;
  private final GatewayGameplayReadinessProbe gatewayGameplayReadinessProbe;

  public TcpProxyTrafficReadinessHealthIndicator(
      ObjectProvider<TelnetServer> telnetServerProvider,
      GatewayGameplayReadinessProbe gatewayGameplayReadinessProbe) {
    this.telnetServerProvider = telnetServerProvider;
    this.gatewayGameplayReadinessProbe = gatewayGameplayReadinessProbe;
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    Map<String, Object> dependencies = new LinkedHashMap<>();
    TelnetServer server = telnetServerProvider.getIfAvailable();
    if (server == null || !server.isRunning()) {
      dependencies.put(
          "telnetListener",
          DependencyReadinessSupport.downDependency(
              "bind", "tcp://0.0.0.0:telnet", "listener not running"));
      return DependencyReadinessSupport.outOfService(CONTRACT, "telnetListener", dependencies);
    }
    dependencies.put(
        "telnetListener",
        DependencyReadinessSupport.upDependency("bind", "tcp://0.0.0.0:telnet", "LISTENING"));
    if (!gatewayGameplayReadinessProbe.isReady()) {
      dependencies.put(
          "gatewayGameplayPath",
          DependencyReadinessSupport.downDependency(
              "readinessProbe",
              String.valueOf(gatewayGameplayReadinessProbe.readinessUri()),
              "gateway readiness endpoint not healthy"));
      return DependencyReadinessSupport.outOfService(CONTRACT, "gatewayGameplayPath", dependencies);
    }
    dependencies.put(
        "gatewayGameplayPath",
        DependencyReadinessSupport.upDependency(
            "readinessProbe",
            String.valueOf(gatewayGameplayReadinessProbe.readinessUri()),
            "READY"));
    return DependencyReadinessSupport.up(CONTRACT, dependencies);
  }
}
