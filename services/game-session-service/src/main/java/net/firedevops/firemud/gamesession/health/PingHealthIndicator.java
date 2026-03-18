package net.firedevops.firemud.gamesession.health;

import net.firedevops.firemud.gamesession.service.PingService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator that delegates to the local ping service.
 *
 * <p>This keeps the actuator health endpoint in sync with the REST and gRPC ping endpoints so the
 * gateway can determine service availability without extra calls.
 */
@Component("servicePingHealthIndicator")
public class PingHealthIndicator implements HealthIndicator {
  private final PingService pingService;

  public PingHealthIndicator(PingService pingService) {
    this.pingService = pingService;
  }

  @Override
  public Health health() {
    String message = pingService.ping();
    return Health.up().withDetail("message", message).build();
  }
}
