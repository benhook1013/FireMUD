package net.firedevops.firemud.gamesession.repository;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.ProxyConnection;
import io.grpc.Context;
import io.grpc.Deadline;
import io.lettuce.core.api.StatefulConnection;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultTransactionProvider;
import org.postgresql.jdbc.PgConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Resource boundary only for original-NEW capacity execution, never a shared datasource policy. */
final class CanonicalGameplayOriginalNewCapacityExecutionBudget {
  private static final Logger LOG =
      LoggerFactory.getLogger(CanonicalGameplayOriginalNewCapacityExecutionBudget.class);
  static final long MAX_OPERATION_MILLIS = 5_000L;
  static final int MAX_IN_FLIGHT_OPERATIONS = 4;
  private static final Semaphore OPERATIONS = new Semaphore(MAX_IN_FLIGHT_OPERATIONS);
  static final int MAX_BACKGROUND_OPERATIONS = 2;
  private static final Semaphore BACKGROUND_OPERATIONS = new Semaphore(MAX_BACKGROUND_OPERATIONS);
  private static final AtomicInteger RELEASE_FAILURE_RETAINED_OPERATIONS = new AtomicInteger();
  private static final AtomicInteger RETIRE_FAILURE_RETAINED_OPERATIONS = new AtomicInteger();
  private static final AtomicInteger REDIS_CLOSE_FAILURE_RETAINED_OPERATIONS = new AtomicInteger();

  static {
    Gauge.builder(
            "original_new_capacity_sql_release_failure_retained_operations",
            RELEASE_FAILURE_RETAINED_OPERATIONS,
            AtomicInteger::get)
        .description("Capacity SQL operation permits retained after an uncertain provider release")
        .register(Metrics.globalRegistry);
    Gauge.builder(
            "original_new_capacity_sql_retire_failure_retained_operations",
            RETIRE_FAILURE_RETAINED_OPERATIONS,
            AtomicInteger::get)
        .description("Capacity SQL operation permits retained after unproved connection retirement")
        .register(Metrics.globalRegistry);
    Gauge.builder(
            "original_new_capacity_redis_close_failure_retained_operations",
            REDIS_CLOSE_FAILURE_RETAINED_OPERATIONS,
            AtomicInteger::get)
        .description("Capacity operation permits retained after unproved Redis disposal")
        .register(Metrics.globalRegistry);
  }

  private final ThreadFactory cancellationThreads;
  private final boolean background;
  private final AtomicBoolean releaseFailureRecorded = new AtomicBoolean();
  private final AtomicBoolean retireFailureRecorded = new AtomicBoolean();
  private boolean redisCloseFailureRecorded;
  private final AtomicInteger outstandingConnections = new AtomicInteger();
  private final AtomicLong jdbcCommitInvocations = new AtomicLong();
  private final Context caller;
  private final Clock clock;
  private final Deadline deadline;
  private final long absoluteDeadlineMillis;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final CompletableFuture<Object> result = new CompletableFuture<>();
  private final CompletableFuture<Void> physicallyReleased = new CompletableFuture<>();
  private final Object resources = new Object();
  private final Object lifecycle = new Object();
  private final Map<Connection, OwnedConnection> connections = new IdentityHashMap<>();
  private final Map<DSLContext, DSLContext> contexts = new IdentityHashMap<>();
  private Statement active;
  private OwnedConnection activeConnection;
  private int outstandingAsyncCloses;
  private int outstandingRetainedWork;
  private boolean workerDone;
  private boolean cancellationStarted;
  private boolean cancellationDone;
  private boolean released;
  private Runnable onPhysicalRelease;

  private CanonicalGameplayOriginalNewCapacityExecutionBudget(
      Clock clock, long explicitDeadline, ThreadFactory cancellationThreads, boolean background) {
    this.background = background;
    this.clock = clock;
    this.cancellationThreads = Objects.requireNonNull(cancellationThreads);
    caller = Context.current();
    long startedMillis = clock.millis();
    long nanos = TimeUnit.MILLISECONDS.toNanos(MAX_OPERATION_MILLIS);
    if (caller.getDeadline() != null)
      nanos = Math.min(nanos, caller.getDeadline().timeRemaining(TimeUnit.NANOSECONDS));
    if (explicitDeadline != Long.MAX_VALUE) {
      long remaining;
      try {
        remaining = Math.subtractExact(explicitDeadline, startedMillis);
      } catch (ArithmeticException invalid) {
        throw unavailable();
      }
      nanos =
          Math.min(nanos, TimeUnit.MILLISECONDS.toNanos(Math.min(MAX_OPERATION_MILLIS, remaining)));
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
    return run(clock, explicitDeadline, operation, cancellationThreads, false, null, null);
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
        true,
        null,
        null);
  }

  /** Notify only once admission refuses or all admitted physical work has finished. */
  static <T> T runBackground(
      Clock clock,
      long explicitDeadline,
      Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, T> operation,
      Runnable onPhysicalRelease) {
    return run(
        clock,
        explicitDeadline,
        operation,
        Thread.ofVirtual().name("original-new-capacity-sql-cancel").factory(),
        true,
        null,
        Objects.requireNonNull(onPhysicalRelease));
  }

  /** A result with private resources transfers ownership only after the caller's deadline check. */
  static <T> T runOwned(
      Clock clock,
      long explicitDeadline,
      Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, T> operation,
      Consumer<T> disposeAbandoned) {
    return run(
        clock,
        explicitDeadline,
        operation,
        Thread.ofVirtual().name("original-new-capacity-sql-cancel").factory(),
        false,
        Objects.requireNonNull(disposeAbandoned),
        null);
  }

  private static <T> T run(
      Clock clock,
      long explicitDeadline,
      Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, T> operation,
      ThreadFactory cancellationThreads,
      boolean background,
      Consumer<T> disposeAbandoned,
      Runnable onPhysicalRelease) {
    final CanonicalGameplayOriginalNewCapacityExecutionBudget budget;
    try {
      budget =
          new CanonicalGameplayOriginalNewCapacityExecutionBudget(
              clock, explicitDeadline, cancellationThreads, background);
      // Background reads and transfer work share the same four-operation ceiling, but cannot
      // consume the two slots reserved for final admission. Neither class waits in a queue.
      if (background && !BACKGROUND_OPERATIONS.tryAcquire()) throw unavailable();
      if (!OPERATIONS.tryAcquire()) {
        if (background) BACKGROUND_OPERATIONS.release();
        throw unavailable();
      }
    } catch (RuntimeException | Error refusal) {
      if (onPhysicalRelease != null) onPhysicalRelease.run();
      throw refusal;
    }
    budget.onPhysicalRelease = onPhysicalRelease;
    var resultClaimed = disposeAbandoned == null ? null : new CompletableFuture<Boolean>();
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
              try {
                if (resultClaimed != null) {
                  try {
                    budget.publishResult(value, failure);
                    if (!resultClaimed.join() && value != null) disposeAbandoned.accept(value);
                  } finally {
                    budget.workerFinished();
                  }
                } else {
                  budget.workerFinished();
                  budget.publishResult(value, failure);
                }
              } finally {
                budget.caller.detach(previous);
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
      if (resultClaimed != null) {
        // Claim the private heap result first; that allows its worker to finish. Do not let
        // a subsequent SQL phase race this operation's physical release for the same permit.
        resultClaimed.complete(true);
        boolean exposed = false;
        try {
          budget.physicallyReleased.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
          budget.check();
          exposed = true;
        } finally {
          if (!exposed && value != null) Objects.requireNonNull(disposeAbandoned).accept(value);
        }
      }
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
      if (resultClaimed != null) resultClaimed.complete(false);
      budget.caller.removeListener(listener);
    }
  }

  private void workerFinished() {
    synchronized (lifecycle) {
      workerDone = true;
      releaseIfFinished();
    }
  }

  private void publishResult(Object value, Throwable failure) {
    if (failure != null) {
      result.completeExceptionally(failure);
      return;
    }
    try {
      check();
      result.complete(value);
    } catch (RuntimeException expired) {
      result.completeExceptionally(expired);
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

  Deadline operationDeadline() {
    check();
    return deadline;
  }

  long absoluteDeadlineMillis() {
    check();
    return absoluteDeadlineMillis;
  }

  void check() {
    remainingNanos();
  }

  long jdbcCommitInvocations() {
    return jdbcCommitInvocations.get();
  }

  /** Releases the resource permit only after the registered Redis disposal succeeds. */
  void retainUntilAsyncClose(CompletableFuture<Void> closeAttempt) {
    Objects.requireNonNull(closeAttempt);
    synchronized (lifecycle) {
      outstandingAsyncCloses++;
    }
    closeAttempt.whenComplete((ignored, failure) -> {
      boolean reportFailure = false;
      synchronized (lifecycle) {
        if (failure == null) {
          outstandingAsyncCloses--;
          releaseIfFinished();
        } else if (!redisCloseFailureRecorded) {
          redisCloseFailureRecorded = true;
          REDIS_CLOSE_FAILURE_RETAINED_OPERATIONS.incrementAndGet();
          reportFailure = true;
        }
      }
      if (reportFailure) {
        // This local diagnostic must not emit throwable messages, command data, or selectors.
        LOG.warn(
            "Original NEW Redis close failed; operation permit retained (category={})",
            redisCloseFailureCategory(failure));
      }
    });
  }

  /** Actual worker exit, including SQL resource cleanup; never the caller timeout. */
  /** Keeps this operation's permits until borrowed work has actually exited. */
  void retainUntilCompletion(CompletableFuture<?> work) {
    Objects.requireNonNull(work);
    synchronized (lifecycle) {
      outstandingRetainedWork++;
    }
    work.whenComplete((ignored, failure) -> {
      synchronized (lifecycle) {
        outstandingRetainedWork--;
        releaseIfFinished();
      }
    });
  }

  private static String redisCloseFailureCategory(Throwable failure) {
    Throwable cause =
        failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
    if (cause instanceof CancellationException) return "cancelled";
    if (cause instanceof io.lettuce.core.RedisConnectionException) return "redis-connection";
    if (cause instanceof io.lettuce.core.RedisCommandTimeoutException
        || cause instanceof java.util.concurrent.TimeoutException) return "timeout";
    if (cause instanceof Error) return "error";
    return "exception";
  }

  /** Waits for one Redis close attempt only through this operation's deadline. */
  void awaitAsyncClose(CompletableFuture<Void> closeAttempt) {
    retainUntilAsyncClose(closeAttempt);
    try {
      closeAttempt.get(remainingNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.CancellationException failedClose) {
      throw unavailable();
    } catch (java.util.concurrent.TimeoutException expired) {
      throw unavailable();
    }
  }

  static CompletableFuture<Void> startRedisClose(StatefulConnection<?, ?> connection) {
    try {
      return Objects.requireNonNull(connection.closeAsync());
    } catch (RuntimeException | Error failure) {
      return CompletableFuture.failedFuture(failure);
    }
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

  void cancel() {
    if (!cancelled.compareAndSet(false, true)) return;
    result.completeExceptionally(unavailable());
    synchronized (lifecycle) {
      if (workerDone && outstandingRetainedWork == 0) return;
      cancellationStarted = true;
    }
    // At most one cancellation worker per admitted operation. Its exit also owns the permit.
    Runnable cancellation =
        () -> {
          try {
            synchronized (resources) {
              for (OwnedConnection connection : new ArrayList<>(connections.values())) {
                try {
                  connection.retire();
                } catch (RuntimeException ignored) {
                  // Unproved retirement leaves the connection owned and this operation admitted.
                }
              }
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

  /** Releases SQL connections opened by one retained companion operation. */
  void releaseRetainedWorkConnections() {
    releaseOutstandingConnections();
  }

  private void releaseIfFinished() {
    if (!released
        && workerDone
        && outstandingConnections.get() == 0
        && outstandingAsyncCloses == 0
        && outstandingRetainedWork == 0
        && (!cancellationStarted || cancellationDone)) {
      released = true;
      OPERATIONS.release();
      if (background) BACKGROUND_OPERATIONS.release();
      physicallyReleased.complete(null);
      if (onPhysicalRelease != null) onPhysicalRelease.run();
    }
  }

  private void recordRetirementFailure(Throwable failure) {
    if (retireFailureRecorded.compareAndSet(false, true)) {
      RETIRE_FAILURE_RETAINED_OPERATIONS.incrementAndGet();
      LOG.warn(
          "Original NEW SQL connection retirement is unproved; operation permit retained (category={})",
          sqlRetirementFailureCategory(failure));
    }
  }

  private static String sqlRetirementFailureCategory(Throwable failure) {
    if (failure instanceof SQLException) return "sql-exception";
    if (failure instanceof Error) return "error";
    return "exception";
  }

  /** Package-private test seam; production uses the pinned Spring/Hikari/PostgreSQL chain below. */
  interface ConnectionRetirer {
    void retire(Connection connection) throws SQLException;
  }

  private static void retireConfiguredConnection(ConnectionProvider provider, Connection raw)
      throws SQLException {
    if (provider instanceof ConnectionRetirer testRetirer) {
      testRetirer.retire(raw);
      return;
    }
    if (!(provider instanceof DataSourceConnectionProvider dataSourceProvider)) {
      throw new SQLException("Unsupported capacity SQL connection provider");
    }
    HikariDataSource pool = requireHikariDataSource(dataSourceProvider.dataSource());
    Connection pooled = raw;
    if (raw instanceof ConnectionProxy springProxy) {
      pooled = springProxy.getTargetConnection();
    }
    if (!(pooled instanceof ProxyConnection)
        || !pooled
            .getClass()
            .getName()
            .startsWith("com.zaxxer.hikari.pool.HikariProxyConnection")) {
      throw new SQLException("Unsupported capacity SQL pooled connection");
    }
    PgConnection driver = pooled.unwrap(PgConnection.class);
    driver.abort(Runnable::run);
    if (!driver.isClosed()) {
      throw new SQLException("PostgreSQL connection remained open after abort");
    }
    // Hikari's proxy abort delegates to PostgreSQL but does not evict its PoolEntry. Evict only
    // after the driver confirms its executor/socket is closed, and before this connection can be
    // released to the pool or a delayed statement invocation can resume.
    pool.evictConnection(pooled);
  }

  private static HikariDataSource requireHikariDataSource(DataSource source) throws SQLException {
    DataSource current = source;
    while (current instanceof DelegatingDataSource delegating) {
      current = delegating.getTargetDataSource();
      if (current == null) throw new SQLException("Capacity SQL datasource target is unavailable");
    }
    if (!(current instanceof HikariDataSource hikari)) {
      throw new SQLException("Unsupported capacity SQL datasource");
    }
    return hikari;
  }

  private final class OwnedConnection {
    private final ConnectionProvider provider;
    private final Connection raw;
    private final Connection proxy;
    private boolean returned;
    private boolean releaseAttempted;
    private boolean retired;
    private boolean retirementFailed;

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
                    if (cleanup) {
                      synchronized (resources) {
                        // Even a throwing release may already have transferred ownership.
                        // jOOQ must never roll back or reset a possibly reborrowed connection.
                        if (releaseAttempted) {
                          if (method.getName().equals("isClosed")) return true;
                          throw new java.sql.SQLException("Connection is no longer owned");
                        }
                        return invoke(raw, method, args);
                      }
                    }
                    check();
                    synchronized (resources) {
                      if (releaseAttempted) {
                        throw new java.sql.SQLException("Connection is no longer owned");
                      }
                    }
                    if (method.getName().equals("commit")) jdbcCommitInvocations.incrementAndGet();
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
        if (cancelled.get() && !retired) {
          try {
            retire();
          } catch (RuntimeException unproved) {
            throw unproved;
          }
        }
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

    /**
     * Must run under resources so cancellation and pool return retain single connection custody.
     */
    void retire() {
      synchronized (resources) {
        if (retired) return;
        if (retirementFailed) throw unavailable();
        if (returned || releaseAttempted) {
          retirementFailed = true;
          recordRetirementFailure(new SQLException("Connection custody already transferred"));
          throw unavailable();
        }
        try {
          retireConfiguredConnection(provider, raw);
          retired = true;
        } catch (SQLException | RuntimeException | Error unproved) {
          retirementFailed = true;
          recordRetirementFailure(unproved);
          throw unavailable();
        }
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
