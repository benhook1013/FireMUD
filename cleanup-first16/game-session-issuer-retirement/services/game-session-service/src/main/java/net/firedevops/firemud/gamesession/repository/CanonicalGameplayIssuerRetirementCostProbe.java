package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.TreeMap;

/** Opt-in, aggregate-only timing for the physical issuer-retirement proof profile. */
final class CanonicalGameplayIssuerRetirementCostProbe {
  static final String ENVIRONMENT_VARIABLE = "FIREMUD_RETIREMENT_COST_PROFILE";

  private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();

  private CanonicalGameplayIssuerRetirementCostProbe() {}

  static Profile start() {
    if (!"1".equals(System.getenv(ENVIRONMENT_VARIABLE))) return Profile.DISABLED;
    State state = new State();
    ACTIVE.set(state);
    return new Profile(state);
  }

  static boolean isActive() {
    return ACTIVE.get() != null;
  }

  static Scope phase(String label) {
    State state = ACTIVE.get();
    if (state == null) return Scope.DISABLED;
    String previous = state.phase;
    state.phase = label;
    return new Scope(state, label, previous, System.nanoTime());
  }

  static Timing timing(String category) {
    State state = ACTIVE.get();
    return state == null ? Timing.DISABLED : new Timing(state, category, System.nanoTime());
  }

  @SuppressWarnings("unchecked")
  static StatefulRedisConnection<byte[], byte[]> connect(RedisClient client) {
    Timing timing = timing("redis.connection.open");
    StatefulRedisConnection<byte[], byte[]> connection;
    try {
      connection = client.connect(ByteArrayCodec.INSTANCE);
    } finally {
      timing.close();
    }
    if (!isActive()) return connection;

    return (StatefulRedisConnection<byte[], byte[]>)
        Proxy.newProxyInstance(
            StatefulRedisConnection.class.getClassLoader(),
            new Class<?>[] {StatefulRedisConnection.class},
            new RedisConnectionHandler(connection));
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments)
      throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static final class RedisConnectionHandler implements InvocationHandler {
    private final StatefulRedisConnection<byte[], byte[]> delegate;

    private RedisConnectionHandler(StatefulRedisConnection<byte[], byte[]> delegate) {
      this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        Object other = arguments == null || arguments.length == 0 ? null : unwrap(arguments[0]);
        return switch (method.getName()) {
          case "equals" -> proxy == arguments[0] || delegate == other || delegate.equals(other);
          case "hashCode" -> delegate.hashCode();
          case "toString" -> delegate.toString();
          default -> CanonicalGameplayIssuerRetirementCostProbe.invoke(delegate, method, arguments);
        };
      }
      if (!method.getName().equals("close")) {
        return CanonicalGameplayIssuerRetirementCostProbe.invoke(delegate, method, arguments);
      }
      Timing closeTiming = timing("redis.connection.close");
      try {
        return CanonicalGameplayIssuerRetirementCostProbe.invoke(delegate, method, arguments);
      } finally {
        closeTiming.close();
      }
    }

    private static Object unwrap(Object value) {
      if (value == null || !Proxy.isProxyClass(value.getClass())) return value;
      InvocationHandler handler = Proxy.getInvocationHandler(value);
      return handler instanceof RedisConnectionHandler redisHandler
          ? redisHandler.delegate
          : value;
    }
  }

  static final class Profile implements AutoCloseable {
    private static final Profile DISABLED = new Profile(null);

    private final State state;

    private Profile(State state) {
      this.state = state;
    }

    Scope phase(String label) {
      return state == null ? Scope.DISABLED : CanonicalGameplayIssuerRetirementCostProbe.phase(label);
    }

    @Override
    public void close() {
      if (state == null || state.finished) return;
      state.finished = true;
      state.record("profile", "elapsed", System.nanoTime() - state.startedAtNanos);
      try {
        System.out.println(state.summary());
      } catch (RuntimeException ignored) {
        // Diagnostic output must not replace a test failure.
      } finally {
        if (ACTIVE.get() == state) ACTIVE.remove();
      }
    }
  }

  static final class Scope implements AutoCloseable {
    private static final Scope DISABLED = new Scope(null, null, null, 0L);

    private final State state;
    private final String label;
    private final String previous;
    private final long startedAtNanos;

    private Scope(State state, String label, String previous, long startedAtNanos) {
      this.state = state;
      this.label = label;
      this.previous = previous;
      this.startedAtNanos = startedAtNanos;
    }

    @Override
    public void close() {
      if (state == null) return;
      state.record(label, "phase.elapsed", System.nanoTime() - startedAtNanos);
      state.phase = previous;
    }
  }

  static final class Timing implements AutoCloseable {
    private static final Timing DISABLED = new Timing(null, null, 0L);

    private final State state;
    private final String category;
    private final long startedAtNanos;

    private Timing(State state, String category, long startedAtNanos) {
      this.state = state;
      this.category = category;
      this.startedAtNanos = startedAtNanos;
    }

    @Override
    public void close() {
      if (state == null) return;
      state.record(state.phase, category, System.nanoTime() - startedAtNanos);
    }
  }

  private static final class State {
    private final long startedAtNanos = System.nanoTime();
    private final Map<String, Metric> metrics = new TreeMap<>();
    private String phase = "setup-teardown";
    private boolean finished;

    private void record(String phase, String category, long durationNanos) {
      String key = phase + "|" + category;
      metrics.computeIfAbsent(key, ignored -> new Metric()).add(Math.max(0L, durationNanos));
    }

    private String summary() {
      StringBuilder summary =
          new StringBuilder(
              "FIREMUD_RETIREMENT_COST_PROFILE v=1 unit=ns metric=count,total,min,max");
      summary
          .append(" env=")
          .append(ENVIRONMENT_VARIABLE)
          .append(" phase_elapsed=inclusive categories=client_observed phases=");
      boolean first = true;
      for (Map.Entry<String, Metric> entry : metrics.entrySet()) {
        if (!first) summary.append(';');
        summary.append(entry.getKey()).append(':').append(entry.getValue());
        first = false;
      }
      return summary.toString();
    }
  }

  private static final class Metric {
    private long count;
    private long totalNanos;
    private long minNanos = Long.MAX_VALUE;
    private long maxNanos;

    private void add(long durationNanos) {
      count++;
      totalNanos += durationNanos;
      minNanos = Math.min(minNanos, durationNanos);
      maxNanos = Math.max(maxNanos, durationNanos);
    }

    @Override
    public String toString() {
      return count + "," + totalNanos + "," + (count == 0 ? 0 : minNanos) + "," + maxNanos;
    }
  }
}
