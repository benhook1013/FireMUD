package net.firedevops.firemud.recovery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.util.ReflectionTestUtils;

class RecoveryJournalHealthIndicatorTest {
  private static final long CALLER_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(2);
  private static final long CALLER_DEADLINE_SCHEDULING_ALLOWANCE_NANOS =
      TimeUnit.MILLISECONDS.toNanos(750);
  private static final long PROBE_WATCHDOG_SECONDS = 5;
  private static final Logger DRIVER_PROTOCOL_LOGGER =
      Logger.getLogger("org.postgresql.core.v3.QueryExecutorImpl");

  @ParameterizedTest
  @CsvSource({"0,2000,false", "5000,2000,true", "750,750,false"})
  void readinessBoundsTheProbeAndRestoresTheBorrowedConnection(
      int originalTimeout, int expectedTimeout, boolean queryFails) throws SQLException {
    DataSource source = mock(DataSource.class);
    try (Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class)) {
      when(source.getConnection()).thenReturn(connection);
      when(connection.getAutoCommit()).thenReturn(true);
      when(connection.getNetworkTimeout()).thenReturn(originalTimeout);
      when(connection.prepareStatement(anyString())).thenReturn(statement);
      if (queryFails) {
        when(statement.executeQuery()).thenThrow(new SQLException("fixture database unavailable"));
      } else {
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(1)).thenReturn(true);
      }

      boolean preserveShorterTimeout = originalTimeout == expectedTimeout;
      var indicator =
          preserveShorterTimeout
              ? new RecoveryJournalHealthIndicator(source, TimeUnit.SECONDS.toNanos(15))
              : new RecoveryJournalHealthIndicator(source);
      try {
        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(queryFails ? Status.DOWN : Status.UP);
        var sequence = inOrder(connection, statement);
        sequence
            .verify(connection)
            .setNetworkTimeout(
                any(),
                intThat(timeout ->
                    preserveShorterTimeout
                        ? timeout == expectedTimeout
                        : timeout > 0 && timeout <= expectedTimeout));
        assertThat(sequence.verify(connection).prepareStatement(anyString())).isNull();
        sequence.verify(statement).setQueryTimeout(2);
        assertThat(sequence.verify(statement).executeQuery()).isNull();
        sequence.verify(connection).setNetworkTimeout(any(), eq(originalTimeout));
        sequence.verify(connection).close();
      } finally {
        indicator.stopProbes();
        rows.close();
        statement.close();
        connection.close();
      }
    }
  }

  @Test
  void readinessTimesOutWhileBorrowingAndDoesNotQueueASecondBorrow() throws Exception {
    DataSource source = mock(DataSource.class);
    var acquiring = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var borrowFinished = new CountDownLatch(1);
    try (Connection connection = mock(Connection.class)) {
      when(source.getConnection())
          .thenAnswer(ignored -> {
            acquiring.countDown();
            try {
              release.await();
            } finally {
              borrowFinished.countDown();
            }
            return connection;
          });

      var indicator = new RecoveryJournalHealthIndicator(source);
      try {
        var first = startProbeThatMustReach(indicator, acquiring);
        assertHealthDownWithinCallerDeadline(first);
        assertThat(borrowFinished.getCount()).isEqualTo(1);
        assertThat(boundedHealth(indicator).getStatus()).isEqualTo(Status.DOWN);
        var probes = (ThreadPoolExecutor) ReflectionTestUtils.getField(indicator, "probes");
        assertThat(probes).isNotNull();
        assertThat(probes.getTaskCount()).isEqualTo(1);
        assertThat(verify(source, times(1)).getConnection()).isNull();
      } finally {
        release.countDown();
        indicator.stopProbes();
        connection.close();
      }
    }
  }

  @Test
  void overlappingHealthCallersAwaitOneSuccessfulProbe() throws Exception {
    DataSource source = mock(DataSource.class);
    try (Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class)) {
      when(source.getConnection()).thenReturn(connection);
      when(connection.getAutoCommit()).thenReturn(true);
      when(connection.prepareStatement(anyString())).thenReturn(statement);
      var queryStarted = new CountDownLatch(1);
      var releaseQuery = new CountDownLatch(1);
      when(statement.executeQuery())
          .thenAnswer(ignored -> {
            queryStarted.countDown();
            releaseQuery.await();
            return rows;
          });
      when(rows.next()).thenReturn(true);
      when(rows.getBoolean(1)).thenReturn(true);

      var indicator = new RecoveryJournalHealthIndicator(source, TimeUnit.SECONDS.toNanos(15));
      try {
        var first = new FutureTask<>(indicator::health);
        Thread.ofVirtual().start(first);
        assertThat(queryStarted.await(5, TimeUnit.SECONDS)).isTrue();

        var second = new FutureTask<>(indicator::health);
        Thread secondThread = Thread.ofVirtual().unstarted(second);
        secondThread.start();
        awaitTimedWaiting(secondThread);
        assertThat(verify(source, times(1)).getConnection()).isNull();

        releaseQuery.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(Status.UP);
        assertThat(second.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(Status.UP);
        assertThat(verify(source, times(1)).getConnection()).isNull();
      } finally {
        releaseQuery.countDown();
        indicator.stopProbes();
        rows.close();
        statement.close();
        connection.close();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @ResourceLock("postgresql-driver-protocol-logger")
  void readinessChecksDriverTracingAgainAfterSuccessfulProbe(boolean enableTracingDuringProbe)
      throws SQLException {
    DataSource source = mock(DataSource.class);
    try (Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class)) {
      Level prior = DRIVER_PROTOCOL_LOGGER.getLevel();
      var enableTracing = new AtomicBoolean(enableTracingDuringProbe);
      var tracingLock = new Object();
      when(source.getConnection()).thenReturn(connection);
      when(connection.getAutoCommit()).thenReturn(true);
      when(connection.prepareStatement(anyString())).thenReturn(statement);
      when(statement.executeQuery()).thenReturn(rows);
      when(rows.next()).thenReturn(true);
      when(rows.getBoolean(1))
          .thenAnswer(ignored -> {
            synchronized (tracingLock) {
              if (enableTracing.get()) {
                DRIVER_PROTOCOL_LOGGER.setLevel(Level.FINEST);
              }
            }
            return true;
          });

      var indicator = new RecoveryJournalHealthIndicator(source);
      try {
        synchronized (tracingLock) {
          DRIVER_PROTOCOL_LOGGER.setLevel(Level.INFO);
        }
        assertThat(indicator.health().getStatus())
            .isEqualTo(enableTracingDuringProbe ? Status.DOWN : Status.UP);
        verify(connection).close();
      } finally {
        synchronized (tracingLock) {
          enableTracing.set(false);
          DRIVER_PROTOCOL_LOGGER.setLevel(prior);
        }
        indicator.stopProbes();
        rows.close();
        statement.close();
        connection.close();
      }
    }
  }

  @Test
  void readinessRetainsBorrowThroughDelayedCleanupThenAcceptsFreshProbe() throws Exception {
    DataSource source = mock(DataSource.class);
    try (Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class)) {
      when(source.getConnection()).thenReturn(connection);
      when(connection.getAutoCommit()).thenReturn(true);
      when(connection.prepareStatement(anyString())).thenReturn(statement);
      when(statement.executeQuery()).thenReturn(rows);
      when(rows.next()).thenReturn(true);
      when(rows.getBoolean(1)).thenReturn(true);

      var closing = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var closeFinished = new CountDownLatch(1);
      doAnswer(
              ignored -> {
                closing.countDown();
                release.await();
                closeFinished.countDown();
                return null;
              })
          .doNothing()
          .when(connection)
          .close();

      var indicator = new RecoveryJournalHealthIndicator(source);
      try {
        var first = startProbeThatMustReach(indicator, closing);
        assertHealthDownWithinCallerDeadline(first);
        assertThat(closeFinished.getCount()).isEqualTo(1);
        // close() is still blocked. The successful readiness row must have been read first.
        verify(rows).getBoolean(1);
        assertThat(boundedHealth(indicator).getStatus()).isEqualTo(Status.DOWN);
        var probes = (ThreadPoolExecutor) ReflectionTestUtils.getField(indicator, "probes");
        assertThat(probes).isNotNull();
        assertThat(probes.getTaskCount()).isEqualTo(1);
        assertThat(verify(source, times(1)).getConnection()).isNull();

        release.countDown();
        assertThat(closeFinished.await(5, TimeUnit.SECONDS)).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (probes.getCompletedTaskCount() == 0 && System.nanoTime() < deadline) {
          Thread.sleep(10);
        }
        assertThat(probes.getCompletedTaskCount()).isEqualTo(1);
        assertThat(boundedHealth(indicator).getStatus()).isEqualTo(Status.UP);
        assertThat(verify(source, times(2)).getConnection()).isNull();
      } finally {
        release.countDown();
        indicator.stopProbes();
        rows.close();
        statement.close();
        connection.close();
      }
    }
  }

  private record ProbeCompletion(Health health, long elapsedNanos) {}

  private static FutureTask<ProbeCompletion> startProbeThatMustReach(
      RecoveryJournalHealthIndicator indicator, CountDownLatch blocked) {
    var result =
        new FutureTask<>(() -> {
          long started = System.nanoTime();
          Health health = indicator.health();
          long elapsed = System.nanoTime() - started;
          assertThat(blocked.getCount())
              .as("probe reached the blocked phase before return")
              .isZero();
          return new ProbeCompletion(health, elapsed);
        });
    Thread.ofVirtual().start(result);
    return result;
  }

  private static void assertHealthDownWithinCallerDeadline(FutureTask<ProbeCompletion> result)
      throws Exception {
    var completion = result.get(PROBE_WATCHDOG_SECONDS, TimeUnit.SECONDS);
    assertThat(completion.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(completion.elapsedNanos())
        .as("health() caller deadline elapsed nanos")
        .isLessThanOrEqualTo(CALLER_DEADLINE_NANOS + CALLER_DEADLINE_SCHEDULING_ALLOWANCE_NANOS);
  }

  private static Health boundedHealth(RecoveryJournalHealthIndicator indicator) throws Exception {
    var result = new FutureTask<>(indicator::health);
    Thread.ofVirtual().start(result);
    return result.get(5, TimeUnit.SECONDS);
  }

  private static void awaitTimedWaiting(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.isAlive()
        && thread.getState() != Thread.State.TIMED_WAITING
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
  }
}
