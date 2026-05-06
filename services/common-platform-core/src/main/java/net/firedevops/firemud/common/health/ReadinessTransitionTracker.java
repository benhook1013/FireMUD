package net.firedevops.firemud.common.health;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;

/** Records bounded readiness transition logs and metrics for dependency-aware health indicators. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "MeterRegistry is a shared framework collaborator retained internally.")
public class ReadinessTransitionTracker {
  private static final Logger LOG = LoggerFactory.getLogger(ReadinessTransitionTracker.class);

  private final MeterRegistry meterRegistry;
  private final ConcurrentMap<String, String> lastStates = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicInteger> currentReadiness = new ConcurrentHashMap<>();

  public ReadinessTransitionTracker(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public Health record(String component, Health health) {
    String status = health.getStatus().getCode();
    String failingDependency =
        normalizeFailingDependency(health.getDetails().get("failingDependency"));
    String stateKey = status + "|" + failingDependency;

    currentGauge(component).set("UP".equals(status) ? 1 : 0);

    String previousState = lastStates.put(component, stateKey);
    if (!Objects.equals(previousState, stateKey)) {
      meterRegistry
          .counter(
              "firemud.readiness.transitions",
              "component",
              component,
              "to_status",
              status,
              "failing_dependency",
              failingDependency)
          .increment();
      logTransition(
          component,
          status,
          failingDependency,
          String.valueOf(health.getDetails().get("contract")),
          String.valueOf(health.getDetails().get("admissionMeaning")));
    }

    return health;
  }

  private AtomicInteger currentGauge(String component) {
    return currentReadiness.computeIfAbsent(
        component,
        key ->
            meterRegistry.gauge(
                "firemud.readiness.current",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("component", key)),
                new AtomicInteger(),
                AtomicInteger::get));
  }

  private void logTransition(
      String component,
      String status,
      String failingDependency,
      String contract,
      String admissionMeaning) {
    if ("UP".equals(status)) {
      LOG.info(
          "Readiness transitioned to UP: component={} contract={} admissionMeaning={}",
          component,
          contract,
          admissionMeaning);
      return;
    }
    LOG.warn(
        "Readiness transitioned away from traffic admission: component={} status={} failingDependency={} contract={} admissionMeaning={}",
        component,
        status,
        failingDependency,
        contract,
        admissionMeaning);
  }

  private static String normalizeFailingDependency(Object failingDependency) {
    if (failingDependency == null) {
      return "none";
    }
    String value = String.valueOf(failingDependency).trim();
    return value.isEmpty() ? "none" : value;
  }
}
