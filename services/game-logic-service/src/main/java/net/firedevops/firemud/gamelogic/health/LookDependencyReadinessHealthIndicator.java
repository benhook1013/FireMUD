package net.firedevops.firemud.gamelogic.health;

import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc.EntityManagementServiceBlockingStub;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.WorldManagementServiceBlockingStub;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator for the downstream services required by the LOOK path. */
@Component("lookDependencyReadiness")
public class LookDependencyReadinessHealthIndicator implements HealthIndicator {
  private final WorldManagementServiceBlockingStub worldStub;
  private final EntityManagementServiceBlockingStub entityStub;

  public LookDependencyReadinessHealthIndicator(
      WorldManagementServiceBlockingStub worldStub,
      EntityManagementServiceBlockingStub entityStub) {
    this.worldStub = worldStub;
    this.entityStub = entityStub;
  }

  @Override
  public Health health() {
    try {
      worldStub.ping(net.firedevops.firemud.worldmanagement.v1.PingRequest.getDefaultInstance());
    } catch (RuntimeException ex) {
      return Health.outOfService()
          .withDetail("worldManagementService", "DOWN")
          .withDetail("reason", message(ex))
          .build();
    }

    try {
      entityStub.ping(PingRequest.getDefaultInstance());
    } catch (RuntimeException ex) {
      return Health.outOfService()
          .withDetail("worldManagementService", "UP")
          .withDetail("entityManagementService", "DOWN")
          .withDetail("reason", message(ex))
          .build();
    }

    return Health.up()
        .withDetail("worldManagementService", "UP")
        .withDetail("entityManagementService", "UP")
        .build();
  }

  private static String message(RuntimeException ex) {
    return ex.getMessage() == null || ex.getMessage().isBlank()
        ? ex.getClass().getSimpleName()
        : ex.getMessage();
  }
}
