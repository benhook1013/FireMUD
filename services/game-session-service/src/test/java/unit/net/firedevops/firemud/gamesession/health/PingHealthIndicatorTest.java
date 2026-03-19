package net.firedevops.firemud.gamesession.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.gamesession.service.PingService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.health.contributor.Health;

class PingHealthIndicatorTest {

  @Test
  void healthReturnsUpWithPingMessage() {
    PingService pingService = Mockito.mock(PingService.class);
    when(pingService.ping()).thenReturn("pong");

    PingHealthIndicator indicator = new PingHealthIndicator(pingService);

    Health health = indicator.health();

    assertEquals(Health.up().build().getStatus(), health.getStatus());
    assertTrue(health.getDetails().containsKey("message"));
    assertEquals("pong", health.getDetails().get("message"));
  }
}
