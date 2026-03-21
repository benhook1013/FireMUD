package net.firedevops.firemud.common.health;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;

/** Shared helpers for structured readiness details across dependency-aware health indicators. */
public final class DependencyReadinessSupport {
  private DependencyReadinessSupport() {}

  public static Map<String, Object> upDependency(String check, String target, String outcome) {
    return dependency("UP", check, target, outcome, null);
  }

  public static Map<String, Object> downDependency(String check, String target, String reason) {
    return dependency("DOWN", check, target, null, reason);
  }

  public static Health up(String contract, Map<String, Object> dependencies) {
    return base(Health.up(), contract, dependencies, null).build();
  }

  public static Health outOfService(
      String contract, String failingDependency, Map<String, Object> dependencies) {
    return base(Health.outOfService(), contract, dependencies, failingDependency).build();
  }

  private static Map<String, Object> dependency(
      String status, String check, String target, String outcome, String reason) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("status", status);
    details.put("check", check);
    if (target != null && !target.isBlank()) {
      details.put("target", target);
    }
    if (outcome != null && !outcome.isBlank()) {
      details.put("outcome", outcome);
    }
    if (reason != null && !reason.isBlank()) {
      details.put("reason", reason);
    }
    return details;
  }

  private static Health.Builder base(
      Health.Builder builder,
      String contract,
      Map<String, Object> dependencies,
      String failingDependency) {
    builder.withDetail("contract", contract);
    builder.withDetail("admissionMeaning", "safe for new traffic on " + contract);
    builder.withDetail("dependencies", dependencies);
    if (failingDependency != null && !failingDependency.isBlank()) {
      builder.withDetail("failingDependency", failingDependency);
    }
    return builder;
  }
}
