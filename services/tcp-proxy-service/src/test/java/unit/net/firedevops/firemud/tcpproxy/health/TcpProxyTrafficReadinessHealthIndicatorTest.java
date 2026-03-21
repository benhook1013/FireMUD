package net.firedevops.firemud.tcpproxy.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class TcpProxyTrafficReadinessHealthIndicatorTest {

  @Test
  void healthReturnsOutOfServiceWhenListenerIsNotRunning() {
    GatewayGameplayReadinessProbe probe = mock(GatewayGameplayReadinessProbe.class);
    ObjectProvider<TelnetServer> provider = providerFor(mock(TelnetServer.class));
    TcpProxyTrafficReadinessHealthIndicator indicator =
        new TcpProxyTrafficReadinessHealthIndicator(provider, probe);

    Health health = indicator.health();

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("DOWN", health.getDetails().get("telnetListener"));
  }

  @Test
  void healthReturnsOutOfServiceWhenGatewayAdmissionPathIsDown() {
    TelnetServer telnetServer = mock(TelnetServer.class);
    when(telnetServer.isRunning()).thenReturn(true);
    GatewayGameplayReadinessProbe probe = mock(GatewayGameplayReadinessProbe.class);
    when(probe.isReady()).thenReturn(false);
    when(probe.readinessUri())
        .thenReturn(java.net.URI.create("http://gateway/actuator/health/readiness"));
    TcpProxyTrafficReadinessHealthIndicator indicator =
        new TcpProxyTrafficReadinessHealthIndicator(providerFor(telnetServer), probe);

    Health health = indicator.health();

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("UP", health.getDetails().get("telnetListener"));
    assertEquals("DOWN", health.getDetails().get("gatewayReadiness"));
  }

  @Test
  void healthReturnsUpWhenListenerAndGatewayPathAreReady() {
    TelnetServer telnetServer = mock(TelnetServer.class);
    when(telnetServer.isRunning()).thenReturn(true);
    GatewayGameplayReadinessProbe probe = mock(GatewayGameplayReadinessProbe.class);
    when(probe.isReady()).thenReturn(true);
    when(probe.readinessUri())
        .thenReturn(java.net.URI.create("http://gateway/actuator/health/readiness"));
    TcpProxyTrafficReadinessHealthIndicator indicator =
        new TcpProxyTrafficReadinessHealthIndicator(providerFor(telnetServer), probe);

    Health health = indicator.health();

    assertEquals(Status.UP, health.getStatus());
    assertEquals("UP", health.getDetails().get("telnetListener"));
    assertEquals("UP", health.getDetails().get("gatewayReadiness"));
  }

  private static ObjectProvider<TelnetServer> providerFor(TelnetServer telnetServer) {
    return new ObjectProvider<>() {
      @Override
      public TelnetServer getObject(Object... args) {
        return telnetServer;
      }

      @Override
      public TelnetServer getIfAvailable() {
        return telnetServer;
      }

      @Override
      public TelnetServer getIfUnique() {
        return telnetServer;
      }

      @Override
      public TelnetServer getObject() {
        return telnetServer;
      }

      @Override
      public java.util.Iterator<TelnetServer> iterator() {
        return Collections.singleton(telnetServer).iterator();
      }
    };
  }
}
