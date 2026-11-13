package net.firedevops.firemud.gamesession.repository;

import io.grpc.Context;
import io.grpc.Deadline;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultTransactionProvider;

/** Resource boundary only for original-NEW capacity execution, never a shared datasource policy. */
final class CanonicalGameplayOriginalNewCapacityExecutionBudget {
  static final int MAX_IN_FLIGHT_OPERATIONS = 4;
  private static final Semaphore OPERATIONS = new Semaphore(MAX_IN_FLIGHT_OPERATIONS);
  static final int MAX_BACKGROUND_OPERATIONS = 2;
  private static final Semaphore BACKGROUND_OPERATIONS = new Semaphore(MAX_BACKGROUND_OPERATIONS);
  private static final AtomicInteger RELEASE_FAILURE_RETAINED_OPERATIONS = new AtomicInteger();

  static {
    Gauge.builder(
            "original_new_capacity_sql_release_failure_retained_operations",
            RELEASE_FAILURE_RETAINED_OPERATIONS,
            AtomicInteger::get)
        .description("Capacity SQL operation permits retained after an uncertain provider release")
        .register(Metrics.globalRegistry);
  }

  private final ThreadFactory cancellationThreads;
  private final boolean background;
  private final AtomicBoolean releaseFailureRecorded = new AtomicBoolean();
  private final AtomicInteger outstandingConnections = new AtomicInteger();
  private final Context caller;
  private final Clock clock;
  private final Deadline deadline;
  private final long absoluteDeadlineMillis;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final CompletableFuture<Object> result = new CompletableFuture<>();
  private final Object resources = new Object();
  private final Object lifecycle = new Object();
  private final Map<Connection, OwnedConnection> connections = new IdentityHashMap<>();
  private final Map<DSLContext, DSLContext> contexts = new IdentityHashMap<>();
  private Statement active;
  private OwnedConnection activeConnection;
  private boolean workerDone;
  private boolean cancellationStarted;
  private boolean cancellationDone;
  private boolean released;

  private CanonicalGameplayOriginalNewCapacityExecutionBudget(
      Clock clock, long explicitDeadline, ThreadFactory cancellationThreads, boolean background) {
    this.background = background;
    this.clock = clock;
    this.cancellationThreads = Objects.requireNonNull(cancellationThreads);
    caller = Context.current();
    long startedMillis = clock.millis();
    long nanos = TimeUnit.SECONDS.toNanos(5);
    if (caller.getDeadline() != null)
      nanos = Math.min(nanos, caller.getDeadline().timeRemaining(TimeUnit.NANOSECONDS));
    if (explicitDeadline != Long.MAX_VALUE) {
      long remaining;
      try {
        remaining = Math.subtractExact(explicitDeadline, startedMillis);
      } catch (ArithmeticException invalid) {
        throw unavailable();
      }
      nanos = Math.min(nanos, TimeUnit.MILLISECONDS.toNanos(Math.min(5000, remaining)));
    }
    if (nanos <= 0 || caller.isCancelled()) throw unavailable();
    try {
      absoluteDeadlineMillis =
          Math.min(
              explicitDeadline, Math.addExact(startedMillis, TimeUnit.NANOSECONDS.toMillis(nanos)));
    } catch (ArithmeticException invalid) {
      throw unavailable();
    }
    Deadline local = Deadline.after(nanos, TimeUnit.NANOSECONDS);
    deadline = caller.getDeadline() == null ? local : local.minimum(caller.getDeadline());
  }

  static <T> T run(
      Clock clock,
      long explicitDeadline,
      Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, T> operation) {
    return run(
        clock,
        explicitDeadline,
        operation,
        Thread.ofVirtual().name("original-new-capacity-sql-cancel").factory());
  }

  static <T> T run(
      Clock clock,
      long explicitDeadline,
      Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, T> operation,
      ThreadFactory cancellationThreads) {
    return run(clock, explicitDeadline, operation, cancellationThreads, false);
  }

  static <T> T runBackground(
      Clock clock,
      long explicitDeadline,
      Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, T> operation) {
    return run(
        clock,
        explicitDeadline,
        operation,
        Thread.ofVirtual().name("original-new-capacity-sql-cancel").factory(),
        true);
  }

  private static <T> T run(
      Clock clock,
      long explicitDeadline,
      Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, T> operation,
      ThreadFactory cancellationThreads,
      boolean background) {
    var budget =
        new CanonicalGameplayOriginalNewCapacityExecutionBudget(
            clock, explicitDeadline, cancellationThreads, background);
    // Background reads and transfer work share the same four-operation ceiling, but cannot
    // consume the two slots reserved for final admission. Neither class waits in a queue.
    if (background && !BACKGROUND_OPERATIONS.tryAcquire()) throw unavailable();
    if (!OPERATIONS.tryAcquire()) {
      if (background) BACKGROUND_OPERATIONS.release();
      throw unavailable();
    }
    Context.CancellationListener listener = ignored -> budget.cancel();
    budget.caller.addListener(listener, Runnable::run);
    try {
      Runnable resource =
          () -> {
            Context previous = budget.caller.attach();
            T value = null;
            Throwable failure = null;
            try {
              budget.check();
              value = operation.apply(budget);
              budget.check();
            } catch (RuntimeException | Error unavailable) {
              failure = unavailable;
            } finally {
              try {
                budget.releaseOutstandingConnections();
              } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
              }
              budget.caller.detach(previous);
              synchronized (budget.lifecycle) {
                budget.workerDone = true;
                budget.releaseIfFinished();
              }
            }
            if (failure != null) budget.result.completeExceptionally(failure);
            else {
              try {
                budget.check();
                budget.result.complete(value);
              } catch (RuntimeException expired) {
                budget.result.completeExceptionally(expired);
              }
            }
          };
      try {
        Thread.ofVirtual().name("original-new-capacity-resource").start(resource);
      } catch (RuntimeException | Error startupFailure) {
        synchronized (budget.lifecycle) {
          budget.workerDone = true;
          budget.releaseIfFinished();
        }
        throw startupFailure;
      }
      @SuppressWarnings("unchecked")
      T value = (T) budget.result.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
      budget.check();
      return value;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      budget.cancel();
      throw unavailable();
    } catch (java.util.concurrent.ExecutionException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw unavailable();
    } catch (java.util.concurrent.TimeoutException | RuntimeException failure) {
      budget.cancel();
      throw unavailable();
    } finally {
      budget.caller.removeListener(listener);
    }
  }

  long remainingNanos() {
    if (cancelled.get() || caller.isCancelled()) throw unavailable();
    long remainingMillis;
    try {
      remainingMillis = Math.subtractExact(absoluteDeadlineMillis, clock.millis());
    } catch (ArithmeticException invalid) {
      throw unavailable();
    }
    long nanos =
        Math.min(
            deadline.timeRemaining(TimeUnit.NANOSECONDS),
            TimeUnit.MILLISECONDS.toNanos(remainingMillis));
    if (nanos <= 0) throw unavailable();
    return nanos;
  }

  long remainingMillis() {
    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos()));
  }

  long absoluteDeadlineMillis() {
    check();
    return absoluteDeadlineMillis;
  }

  void check() {
    remainingNanos();
  }

  /** Both acquisition and statements run only inside the capped resource worker. */
  DSLContext sql(DSLContext source) {
    check();
    return contexts.computeIfAbsent(
        source,
        original -> {
          ConnectionProvider provider = original.configuration().connectionProvider();
          ConnectionProvider scoped =
              new ConnectionProvider() {
                @Override
                public Connection acquire() throws DataAccessException {
                  check();
                  Connection acquired = provider.acquire();
                  if (acquired == null) throw unavailable();
                  OwnedConnection owned = new OwnedConnection(provider, acquired);
                  try {
                    // No query can run on a connection that arrives after cancellation/deadline.
                    synchronized (resources) {
                      connections.put(owned.proxy, owned);
                      outstandingConnections.incrementAndGet();
                    }
                    check();
                    return owned.proxy;
                  } catch (RuntimeException expired) {
                    owned.release();
                    throw expired;
                  }
                }

                @Override
                public void release(Connection connection) throws DataAccessException {
                  OwnedConnection owned;
                  synchronized (resources) {
                    owned = connections.get(connection);
                  }
                  if (owned == null) throw unavailable();
                  owned.release();
                }
              };
          return DSL.using(
              original
                  .configuration()
                  .derive(scoped)
                  .derive(new DefaultTransactionProvider(scoped)));
        });
  }

  private void cancel() {
    if (!cancelled.compareAndSet(false, true)) return;
    result.completeExceptionally(unavailable());
    synchronized (lifecycle) {
      if (workerDone) return;
      cancellationStarted = true;
    }
    // At most one cancellation worker per admitted operation. Its exit also owns the permit.
    Runnable cancellation =
        () -> {
          try {
            synchronized (resources) {
              if (active != null) {
                try {
                  active.cancel();
                } catch (SQLException ignored) {
                  /* Remains indeterminate. */
                }
              }
            }
          } finally {
            synchronized (lifecycle) {
              cancellationDone = true;
              releaseIfFinished();
            }
          }
        };
    try {
      Thread thread = Objects.requireNonNull(cancellationThreads.newThread(cancellation));
      thread.start();
    } catch (RuntimeException | Error startupFailure) {
      // No cancellation worker exists. The resource worker still owns acquisition/query/cleanup;
      // its outstanding resources, rather than a failed-start flag, continue to retain admission.
      synchronized (lifecycle) {
        cancellationDone = true;
        releaseIfFinished();
      }
    }
  }

  private void releaseOutstandingConnections() {
    ArrayList<OwnedConnection> outstanding;
    synchronized (resources) {
      outstanding = new ArrayList<>(connections.values());
    }
    RuntimeException failure = null;
    for (OwnedConnection connection : outstanding) {
      try {
        connection.release();
      } catch (RuntimeException cleanupFailure) {
        if (failure == null) failure = cleanupFailure;
        else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
      }
    }
    if (failure != null) throw failure;
  }

  private void releaseIfFinished() {
    if (!released
        && workerDone
        && outstandingConnections.get() == 0
        && (!cancellationStarted || cancellationDone)) {
      released = true;
      OPERATIONS.release();
      if (background) BACKGROUND_OPERATIONS.release();
    }
  }

  private final class OwnedConnection {
    private final ConnectionProvider provider;
    private final Connection raw;
    private final Connection proxy;
    private boolean returned;
    private boolean releaseAttempted;

    OwnedConnection(ConnectionProvider provider, Connection raw) {
      this.provider = provider;
      this.raw = raw;
      proxy =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (ignored, method, args) -> {
                    if (method.getName().equals("close")) {
                      release();
                      return null;
                    }
                    if (method.getName().equals("unwrap")
                        && ((Class<?>) args[0]).isInstance(OwnedConnection.this.proxy))
                      return OwnedConnection.this.proxy;
                    boolean cleanup =
                        method.getName().equals("rollback")
                            || method.getName().equals("isClosed")
                            || (method.getName().equals("setAutoCommit")
                                && Boolean.TRUE.equals(args[0]));
                    if (!cleanup) check();
                    Object value = invoke(raw, method, args);
                    if (value instanceof Statement statement) {
                      try {
                        check();
                        return statement(statement);
                      } catch (RuntimeException expired) {
                        synchronized (resources) {
                          statement.close();
                        }
                        throw expired;
                      }
                    }
                    return value;
                  });
    }

    private Object statement(Statement rawStatement) {
      Class<?> type =
          rawStatement instanceof CallableStatement
              ? CallableStatement.class
              : rawStatement instanceof PreparedStatement
                  ? PreparedStatement.class
                  : Statement.class;
      return Proxy.newProxyInstance(
          type.getClassLoader(),
          new Class<?>[] {type},
          (ignored, method, args) -> {
            String name = method.getName();
            if (name.equals("getConnection")) return proxy;
            if (name.equals("close")) {
              synchronized (resources) {
                if (active == rawStatement) {
                  active = null;
                  activeConnection = null;
                }
                return invoke(rawStatement, method, args);
              }
            }
            if (name.startsWith("execute")) {
              synchronized (resources) {
                check();
                if (returned) throw unavailable();
                active = rawStatement;
                activeConnection = this;
                rawStatement.setQueryTimeout(
                    (int) Math.max(1, (remainingNanos() + 999_999_999L) / 1_000_000_000L));
              }
              check();
            } else if (!name.equals("isClosed") && !name.equals("cancel")) check();
            return invoke(rawStatement, method, args);
          });
    }

    void release() {
      // Detach cancellation before pool return, serialized against a cancellation already running.
      synchronized (resources) {
        if (returned || releaseAttempted) return;
        releaseAttempted = true;
        if (activeConnection == this) {
          active = null;
          activeConnection = null;
        }
        // A throwing provider release has uncertain ownership. Do not retry it blindly or free
        // the resource permit as though that connection were known to have returned to its pool.
        try {
          provider.release(raw);
        } catch (RuntimeException | Error uncertainReturn) {
          if (releaseFailureRecorded.compareAndSet(false, true))
            RELEASE_FAILURE_RETAINED_OPERATIONS.incrementAndGet();
          throw uncertainReturn;
        }
        returned = true;
        if (connections.remove(proxy) != null) outstandingConnections.decrementAndGet();
      }
    }
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  static final class BudgetUnavailable extends IllegalStateException {
    private BudgetUnavailable() {
      super("Original NEW capacity execution budget is unavailable");
    }
  }

  private static BudgetUnavailable unavailable() {
    return new BudgetUnavailable();
  }
}
