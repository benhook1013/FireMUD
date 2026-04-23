package net.firedevops.firemud.automationscripting.service.impl;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.stereotype.Component;

@Component
public class ScheduledJobReadinessGuard {
  private final ApplicationAvailability availability;

  public ScheduledJobReadinessGuard(ApplicationAvailability availability) {
    this.availability = availability;
  }

  boolean canRun() {
    return availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
  }
}
