package net.firedevops.firemud.common.runtime;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.MDC;

/** Attaches shared runtime identity and correlation fields to MDC for bounded logging scopes. */
public final class RuntimeLoggingContext implements AutoCloseable {
  private final List<AutoCloseable> closeables;

  private RuntimeLoggingContext(List<AutoCloseable> closeables) {
    this.closeables = closeables;
  }

  public static RuntimeLoggingContext open(RuntimeIdentity runtimeIdentity) {
    return open(runtimeIdentity, null, null);
  }

  public static RuntimeLoggingContext open(RuntimeIdentity runtimeIdentity, String correlationId) {
    return open(runtimeIdentity, correlationId, null);
  }

  public static RuntimeLoggingContext open(
      RuntimeIdentity runtimeIdentity, String correlationId, String traceId) {
    List<AutoCloseable> closeables = new ArrayList<>();
    if (runtimeIdentity != null) {
      add(closeables, "service", runtimeIdentity.service());
      add(closeables, "serviceInstanceId", runtimeIdentity.serviceInstanceId());
    }
    add(closeables, "correlationId", correlationId);
    add(closeables, "traceId", traceId);
    return new RuntimeLoggingContext(closeables);
  }

  private static void add(List<AutoCloseable> closeables, String key, String value) {
    if (value != null && !value.isBlank()) {
      closeables.add(MDC.putCloseable(key, value));
    }
  }

  @Override
  public void close() {
    for (int i = closeables.size() - 1; i >= 0; i--) {
      try {
        closeables.get(i).close();
      } catch (Exception ignored) {
        // MDC cleanup should not affect runtime flow.
      }
    }
  }
}
