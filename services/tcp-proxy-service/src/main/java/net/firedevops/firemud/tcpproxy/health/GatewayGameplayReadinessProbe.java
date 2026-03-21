package net.firedevops.firemud.tcpproxy.health;

import java.net.URI;
import net.firedevops.firemud.common.health.HttpEndpointAvailabilityChecker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Checks whether the downstream gateway gameplay admission path is currently ready. */
@Component
public class GatewayGameplayReadinessProbe {
  private final HttpEndpointAvailabilityChecker availabilityChecker;
  private final URI readinessUri;

  public GatewayGameplayReadinessProbe(
      HttpEndpointAvailabilityChecker availabilityChecker,
      @Value("${GATEWAY_WS_URL:ws://spring-cloud-gateway:8080/ws/game}") String gatewayWsUrl) {
    this.availabilityChecker = availabilityChecker;
    this.readinessUri = availabilityChecker.readinessEndpointForWebSocketUrl(gatewayWsUrl);
  }

  public boolean isReady() {
    return availabilityChecker.isHealthy(readinessUri);
  }

  public URI readinessUri() {
    return readinessUri;
  }
}
