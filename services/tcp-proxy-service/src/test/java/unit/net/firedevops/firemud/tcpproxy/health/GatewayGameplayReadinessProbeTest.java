package net.firedevops.firemud.tcpproxy.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import net.firedevops.firemud.tcpproxy.telnet.GatewayWebSocketClient;
import org.junit.jupiter.api.Test;

class GatewayGameplayReadinessProbeTest {

  @Test
  void delegatesReadinessToTheGatewayWebSocketClient() {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    URI readinessUri = URI.create("https://gateway.internal/actuator/health/readiness");
    when(client.isReady()).thenReturn(true);
    when(client.readinessUri()).thenReturn(readinessUri);
    GatewayGameplayReadinessProbe probe = new GatewayGameplayReadinessProbe(client);

    assertTrue(probe.isReady());
    assertEquals(readinessUri, probe.readinessUri());
    verify(client).isReady();
    verify(client).readinessUri();
  }
}
