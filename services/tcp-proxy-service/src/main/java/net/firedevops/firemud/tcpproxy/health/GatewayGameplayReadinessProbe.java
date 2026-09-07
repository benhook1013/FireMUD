package net.firedevops.firemud.tcpproxy.health;

import java.net.URI;
import net.firedevops.firemud.tcpproxy.telnet.GatewayWebSocketClient;
import org.springframework.stereotype.Component;

/** Checks whether the downstream gateway gameplay admission path is currently ready. */
@Component
public class GatewayGameplayReadinessProbe {
  private final GatewayWebSocketClient gatewayWebSocketClient;

  public GatewayGameplayReadinessProbe(GatewayWebSocketClient gatewayWebSocketClient) {
    this.gatewayWebSocketClient = gatewayWebSocketClient;
  }

  public boolean isReady() {
    return gatewayWebSocketClient.isReady();
  }

  public URI readinessUri() {
    return gatewayWebSocketClient.readinessUri();
  }
}
